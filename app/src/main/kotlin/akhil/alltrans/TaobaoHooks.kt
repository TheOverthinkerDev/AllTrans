package akhil.alltrans

import android.content.Context
import android.graphics.Canvas
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import java.util.Collections
import java.util.WeakHashMap

/**
 * App-specific hooks for Taobao (com.taobao.taobao).
 *
 * Taobao renders much of its UI without TextView.setText():
 *  - DinamicX components draw text with DXNativeFastText, which receives a
 *    prebuilt StaticLayout through setStaticLayout(). The generic StaticLayout
 *    hook does not see these builds, so "大家都在搜", search keywords, promo tags
 *    and the search-box suggestions stayed in Chinese.
 *
 * Not covered: the bottom tab bar (NavigationTabIndicatorView) has no text view
 * at all; its labels are part of the icon images (TUrlImageView).
 */
internal object TaobaoHooks {
    const val PACKAGE = "com.taobao.taobao"
    private const val TAG = "AllTrans:Taobao"
    private const val DX_FAST_TEXT = "com.taobao.android.dinamicx.view.DXNativeFastText"
    private const val MIN_TEXT_SCALE = 0.75f

    @Volatile
    private var installed = false

    private val buildingLayout = ThreadLocal<Boolean>()

    /**
     * In Taobao the generic StaticLayout.Builder hook must stay out of the way:
     * it swapped translations into DinamicX builds after DinamicX had measured
     * the Chinese text, which left those views sized 0x0 (blank). DinamicX text
     * is handled by the setStaticLayout hook below and TextViews by setText().
     */
    @Volatile
    var genericStaticLayoutDisabled = false
        private set

    /** True while AllTrans itself builds a StaticLayout, or the generic hook is off. */
    fun isBuildingLayout(): Boolean = genericStaticLayoutDisabled || buildingLayout.get() == true

    fun install(context: Context) {
        if (context.packageName != PACKAGE || installed) return
        installed = true
        genericStaticLayoutDisabled = true
        val loader = context.classLoader
        hookDxFastText(loader)
    }

    // ---------------------------------------------------------------- DinamicX

    /**
     * Original DinamicX layout -> layout built from its translation.
     *
     * The view's own staticLayout field is never replaced: DinamicX reads it
     * back (state saving, re-measuring), and when it found translated text there
     * it re-laid the item out around the longer string and sized the view 0x0.
     * The translation is swapped in only for the duration of onDraw().
     */
    private val dxTranslations: MutableMap<StaticLayout, StaticLayout> =
        Collections.synchronizedMap(WeakHashMap())

    private class DxCall(val view: View, val original: StaticLayout)

    private fun hookDxFastText(loader: ClassLoader) {
        try {
            val cls = Class.forName(DX_FAST_TEXT, false, loader)
            val field = cls.getDeclaredField("staticLayout").apply { isAccessible = true }
            val setter = cls.getDeclaredMethod("setStaticLayout", StaticLayout::class.java)
            val onDraw = cls.getDeclaredMethod("onDraw", Canvas::class.java)
            HookRuntime.hook(setter, DxSetStaticLayoutHook())
            HookRuntime.hook(onDraw, DxOnDrawHook(field))
            Utils.debugLog("$TAG: hooked $DX_FAST_TEXT#setStaticLayout/onDraw")
        } catch (t: Throwable) {
            Utils.debugLog("$TAG: DXNativeFastText not hooked: ${t.message}")
        }
    }

    /** Prepares a translated layout for every layout DinamicX hands the view. */
    private class DxSetStaticLayoutHook : SafeHook(), OriginalCallable {
        override fun beforeHookedMethod(param: HookParam) {
            val view = param.thisObject as? View ?: return
            val layout = param.args.firstOrNull() as? StaticLayout ?: return
            if (dxTranslations.containsKey(layout)) return

            val text = layout.text?.toString() ?: return
            if (!SetTextHookHandler.isNotWhiteSpace(text) || SetTextHookHandler.shouldSkipTranslation(text)) return
            if (!PreferenceManager.isEnabledForPackage(view.context, view.context?.packageName)) return

            val cached = cachedTranslation(text)
            if (cached != null) {
                if (cached != text) dxTranslations[layout] = rebuild(layout, cached)
                return
            }

            val key = 31 * System.identityHashCode(layout) + text.hashCode()
            synchronized(Alltrans.pendingTextViewTranslations) {
                if (!Alltrans.pendingTextViewTranslations.add(key)) return
            }
            val getTranslate = GetTranslate().apply {
                stringToBeTrans = text
                userData = DxCall(view, layout)
                originalCallable = this@DxSetStaticLayoutHook
                canCallOriginal = true
                pendingCompositeKey = key
                forceCacheResult = true
            }
            GetTranslateToken().apply { this.getTranslate = getTranslate }.doAll()
        }

        // Runs on the main thread once the translation arrives.
        override fun callOriginalMethod(translatedString: CharSequence?, userData: Any?) {
            val call = userData as? DxCall ?: return
            val translated = translatedString?.toString().orEmpty()
            val original = call.original.text?.toString().orEmpty()
            if (translated.isEmpty() || translated == original) return
            try {
                dxTranslations[call.original] = rebuild(call.original, translated)
                // Redraw; if the view was recycled for another item, its current
                // layout has no entry yet and it simply draws that layout.
                call.view.invalidate()
            } catch (t: Throwable) {
                Utils.debugLog("$TAG: failed to prepare DX translation: ${t.message}")
            }
        }
    }

    /** Draws the translated layout in place of the original one. */
    private class DxOnDrawHook(private val field: java.lang.reflect.Field) : SafeHook() {
        private val swapped = ThreadLocal<StaticLayout?>()

        override fun beforeHookedMethod(param: HookParam) {
            val view = param.thisObject ?: return
            val original = field.get(view) as? StaticLayout ?: return
            val translated = dxTranslations[original] ?: return
            field.set(view, translated)
            swapped.set(original)
        }

        override fun afterHookedMethod(param: HookParam) {
            val original = swapped.get() ?: return
            swapped.remove()
            param.thisObject?.let { field.set(it, original) }
        }
    }

    /**
     * Builds a layout for [text] with the same paint, width, alignment and
     * spacing as [source]. Translations are often longer, so keep the original
     * line count and ellipsize instead of overflowing the fixed-size view.
     */
    private fun rebuild(source: StaticLayout, text: String): StaticLayout {
        buildingLayout.set(true)
        try {
            val includePad = source.topPadding != 0 || source.bottomPadding != 0
            val width = source.width.coerceAtLeast(1)
            val lines = source.lineCount.coerceAtLeast(1)
            val styled = copyFullSpans(source.text, text)

            // Shrink the font (down to 75%) when the translation does not fit
            // the space the original text had, before falling back to "…".
            val paint = TextPaint(source.paint)
            val needed = paint.measureText(text)
            val available = width.toFloat() * lines
            if (needed > available) {
                paint.textSize = paint.textSize * (available / needed).coerceAtLeast(MIN_TEXT_SCALE)
            }

            val layout = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, width)
                .setAlignment(source.alignment)
                .setLineSpacing(source.spacingAdd, source.spacingMultiplier)
                .setIncludePad(includePad)
                .setMaxLines(lines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setEllipsizedWidth(width)
                .build()
            if (Utils.Debug) {
                val spans = (source.text as? Spanned)?.let { s ->
                    s.getSpans(0, s.length, Any::class.java).joinToString(",") {
                        "${it.javaClass.simpleName}[${s.getSpanStart(it)}-${s.getSpanEnd(it)}]"
                    }
                }
                Utils.debugLog(
                    "$TAG: rebuilt '${source.text}' -> '$text' w=$width lines=$lines " +
                        "h=${source.height}->${layout.height} color=${Integer.toHexString(source.paint.color)} " +
                        "shader=${source.paint.shader?.javaClass?.simpleName} spans=$spans " +
                        "textClass=${source.text?.javaClass?.simpleName}"
                )
            }
            return layout
        } finally {
            buildingLayout.remove()
        }
    }

    /**
     * Carries over spans that cover the whole original text (DinamicX sets the
     * text colour and size this way; without them the text was drawn invisibly).
     * Partial spans cannot be mapped onto a translation and are dropped.
     */
    private fun copyFullSpans(original: CharSequence?, translated: String): CharSequence {
        if (original !is Spanned || original.isEmpty()) return translated
        val result = SpannableString(translated)
        for (span in original.getSpans(0, original.length, Any::class.java)) {
            val start = original.getSpanStart(span)
            val end = original.getSpanEnd(span)
            if (start <= 0 && end >= original.length) {
                result.setSpan(span, 0, translated.length, original.getSpanFlags(span))
            }
        }
        return result
    }

    // ---------------------------------------------------------------- Cache

    private fun cachedTranslation(text: String): String? {
        if (!PreferenceList.Caching) return null
        Alltrans.cacheAccess.acquireUninterruptibly()
        return try {
            when (val cached = Alltrans.cache?.get(text)) {
                null -> null
                SetTextHookHandler.NO_TRANSLATION_MARKER -> text
                else -> cached
            }
        } finally {
            if (Alltrans.cacheAccess.availablePermits() == 0) Alltrans.cacheAccess.release()
        }
    }
}
