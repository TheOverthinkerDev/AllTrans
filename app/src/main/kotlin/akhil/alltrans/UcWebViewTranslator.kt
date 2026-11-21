package akhil.alltrans

import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.ValueCallback
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * Translates pages shown in UC/U4 WebViews (com.uc.webview.export.WebView),
 * which Alibaba apps use instead of android.webkit.WebView (Taobao's
 * WindVane WVUCWebView, e.g. the "包邮" tab). The generic WebView hooks only
 * cover android.webkit.WebView.
 *
 * Pull model, so no JavaScript interface is needed: a MutationObserver marks
 * the page dirty; while the view is shown, AllTrans periodically asks the page
 * for untranslated CJK text nodes via evaluateJavascript, translates them
 * (cache first) and writes the translations back.
 */
internal object UcWebViewTranslator {
    private const val TAG = "AllTrans:UcWebView"
    private const val UC_WEBVIEW = "com.uc.webview.export.WebView"
    private const val TICK_MS = 1500L
    private const val MAX_TEXTS_PER_TICK = 150

    private val handler = Handler(Looper.getMainLooper())
    private val webViews: MutableSet<View> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private var evaluateMethod: Method? = null
    private var ticking = false

    /** Translations ready to be written into a page, per WebView. */
    private val pendingApply = WeakHashMap<View, MutableMap<String, String>>()

    @Volatile
    private var installed = false

    fun install(loader: ClassLoader) {
        if (installed) return
        installed = true
        try {
            val cls = Class.forName(UC_WEBVIEW, false, loader)
            evaluateMethod = cls.methods.firstOrNull {
                it.name == "evaluateJavascript" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java
            } ?: run {
                Utils.debugLog("$TAG: evaluateJavascript(String, ?) not found")
                return
            }
            // Register every UC WebView that loads something.
            val hooked = HookRuntime.hookAllMethods(cls, "loadUrl", object : SafeHook() {
                override fun afterHookedMethod(param: HookParam) {
                    (param.thisObject as? View)?.let { register(it) }
                }
            })
            Utils.debugLog("$TAG: hooked $hooked loadUrl overloads, evaluate=${evaluateMethod}")
        } catch (t: Throwable) {
            Utils.debugLog("$TAG: not installed: ${t.message}")
        }
    }

    private fun register(view: View) {
        if (!webViews.add(view)) return
        Utils.debugLog("$TAG: tracking ${view.javaClass.name}@${System.identityHashCode(view)}")
        if (!ticking) {
            ticking = true
            handler.postDelayed(::tick, TICK_MS)
        }
    }

    private fun tick() {
        val views = synchronized(webViews) { webViews.toList() }
        if (views.isEmpty()) {
            ticking = false
            return
        }
        for (view in views) {
            if (view.isAttachedToWindow && view.isShown) collect(view)
        }
        handler.postDelayed(::tick, TICK_MS)
    }

    private fun collect(view: View) {
        if (!PreferenceManager.isWebViewTranslationEnabled(view.context)) return
        evaluate(view, COLLECT_SCRIPT) { result ->
            val texts = try {
                JSONArray(result ?: return@evaluate)
            } catch (_: Throwable) {
                return@evaluate
            }
            if (texts.length() == 0) return@evaluate
            val ready = HashMap<String, String>()
            for (i in 0 until texts.length()) {
                val text = texts.optString(i).takeIf { it.isNotBlank() } ?: continue
                val cached = cached(text)
                when {
                    cached == null -> requestTranslation(view, text)
                    cached != text -> ready[text] = cached
                }
            }
            if (ready.isNotEmpty()) apply(view, ready)
        }
    }

    private fun requestTranslation(view: View, text: String) {
        val key = 31 * System.identityHashCode(view) + text.hashCode()
        synchronized(Alltrans.pendingTextViewTranslations) {
            if (!Alltrans.pendingTextViewTranslations.add(key)) return
        }
        val getTranslate = GetTranslate().apply {
            stringToBeTrans = text
            userData = Pair(view, text)
            originalCallable = Callback
            canCallOriginal = true
            pendingCompositeKey = key
            forceCacheResult = true
        }
        GetTranslateToken().apply { this.getTranslate = getTranslate }.doAll()
    }

    /** Batches translations that arrive one by one into a single page update. */
    private object Callback : OriginalCallable {
        override fun callOriginalMethod(translatedString: CharSequence?, userData: Any?) {
            val pair = userData as? Pair<*, *> ?: return
            val view = pair.first as? View ?: return
            val original = pair.second as? String ?: return
            val translated = translatedString?.toString() ?: return
            if (translated.isBlank() || translated == original) return
            synchronized(pendingApply) {
                val batch = pendingApply.getOrPut(view) { HashMap() }
                val first = batch.isEmpty()
                batch[original] = translated
                if (first) handler.postDelayed({ flush(view) }, 200)
            }
        }
    }

    private fun flush(view: View) {
        val batch = synchronized(pendingApply) { pendingApply.remove(view) } ?: return
        if (batch.isNotEmpty() && view.isAttachedToWindow) apply(view, batch)
    }

    private fun apply(view: View, translations: Map<String, String>) {
        val map = JSONObject()
        translations.forEach { (k, v) -> map.put(k, v) }
        val script = APPLY_SCRIPT.replace("__SCROLL__", PreferenceList.Scroll.toString()).replace("__MAP__", map.toString())
        evaluate(view, script, null)
    }

    private fun evaluate(view: View, script: String, callback: ((String?) -> Unit)?) {
        val method = evaluateMethod ?: return
        try {
            val valueCallback = callback?.let { cb -> ValueCallback<String> { cb(it) } }
            method.invoke(view, script, valueCallback)
        } catch (t: Throwable) {
            Utils.debugLog("$TAG: evaluateJavascript failed: ${t.cause ?: t}")
        }
    }

    private fun cached(text: String): String? {
        if (!PreferenceList.Caching) return null
        Alltrans.cacheAccess.acquireUninterruptibly()
        return try {
            when (val c = Alltrans.cache?.get(text)) {
                null -> null
                SetTextHookHandler.NO_TRANSLATION_MARKER -> text
                else -> c
            }
        } finally {
            if (Alltrans.cacheAccess.availablePermits() == 0) Alltrans.cacheAccess.release()
        }
    }

    // Returns a JSON array of untranslated text containing CJK characters, or
    // [] when the DOM has not changed since the last call.
    private val COLLECT_SCRIPT = """
        (function(){
          try {
            if (!window.__atObs) {
              window.__atDirty = true;
              window.__atObs = new MutationObserver(function(){ window.__atDirty = true; });
              window.__atObs.observe(document, {subtree:true, childList:true, characterData:true});
            }
            if (!window.__atDirty || !document.body) return [];
            window.__atDirty = false;
            var seen = {}, out = [];
            var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false), n;
            while ((n = w.nextNode()) && out.length < $MAX_TEXTS_PER_TICK) {
              var p = n.parentElement;
              if (!p || p.tagName === 'SCRIPT' || p.tagName === 'STYLE') continue;
              if (n.__atDone) continue;
              var t = n.nodeValue.trim();
              if (!t || !/[㐀-鿿]/.test(t) || seen[t]) continue;
              seen[t] = 1; out.push(t);
            }
            if (out.length >= $MAX_TEXTS_PER_TICK) window.__atDirty = true;
            return out;
          } catch (e) { return []; }
        })();
    """.trimIndent()

    // With scrolling on, an element whose translated text overflows horizontally
    // gets an inline text-indent animation (styles only; the DOM is unchanged).
    private val APPLY_SCRIPT = """
        (function(){
          try {
            var m = __MAP__, scroll = __SCROLL__, touched = [];
            var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false), n;
            while ((n = w.nextNode())) {
              var t = n.nodeValue.trim();
              if (m.hasOwnProperty(t)) {
                n.nodeValue = n.nodeValue.replace(t, m[t]); n.__atDone = true;
                if (scroll && n.parentElement) touched.push(n.parentElement);
              }
            }
            if (!touched.length) return;
            if (!document.getElementById('__atMarqueeStyle')) {
              var s = document.createElement('style'); s.id = '__atMarqueeStyle';
              s.textContent = '@keyframes __atMarquee{0%,12%{text-indent:0}88%,100%{text-indent:var(--at-d)}}';
              (document.head || document.documentElement).appendChild(s);
            }
            requestAnimationFrame(function(){
              touched.forEach(function(el){
                // Text-only elements: text-indent would also move images/icons.
                if (el.__atMarquee || el.children.length > 0) return;
                var d = el.scrollWidth - el.clientWidth;
                if (d <= 2) return;
                el.__atMarquee = true;
                el.style.setProperty('--at-d', (-d) + 'px');
                el.style.textOverflow = 'clip';
                el.style.animation = '__atMarquee ' + Math.max(3, d / 40 + 2) + 's linear infinite alternate';
              });
            });
          } catch (e) {}
        })();
    """.trimIndent()
}
