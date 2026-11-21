package akhil.alltrans

import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Translates selected Taobao MTOP API responses before the app reads them.
 *
 * Some screens never put their text into Android views: the cart is rendered by
 * Weex 2 (UnicornView, Skia in native code) and parts of the item page by
 * engines the view hooks cannot reach. All of them get their text from MTOP
 * JSON responses, which pass through MtopResponse.setBytedata() on a network
 * thread. Rewriting the CJK display strings there translates those screens
 * whatever renders them.
 *
 * Kept deliberately narrow: only allow-listed APIs, only string values that
 * contain CJK, never values under id/url/code/param-like keys or values that
 * look like embedded JSON or links, and a time budget so a slow translator
 * delays the response by at most a few seconds (untranslated strings are left
 * as they are).
 */
internal object MtopTranslator {
    private const val TAG = "AllTrans:Mtop"

    /** API name prefixes whose responses are translated (lower case). */
    private val API_ALLOWLIST = listOf(
        "mtop.trade.query.bag",          // cart
        "mtop.taobao.detail.data.get",   // item page
    )

    // Text travels URL-encoded in the provider URI (~9 bytes per CJK char).
    private const val CHUNK_CHARS = 600
    private const val BUDGET_MS = 6000L
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "AllTrans-mtop").apply { isDaemon = true } }

    private val CJK = Regex("[㐀-鿿]")
    private val SKIP_KEY = Regex(
        "(?i)(^|_)(id|ids|url|urls|link|href|img|image|images|pic|pics|icon|logo|avatar|key|code|token|sign|" +
            "spm|scm|track|trackparams|utparam|utparams|params|param|args|extra|exparams|args|query|scheme|" +
            "md5|hash|version|style|css|template|bizcode|event|events|action|actions|cookie)$|" +
            "(Id|ID|Ids|Url|URL|Key|Code|Param|Params|Args|Token)$"
    )

    fun install(loader: ClassLoader) {
        try {
            val cls = Class.forName("mtopsdk.mtop.domain.MtopResponse", false, loader)
            val hooked = HookRuntime.hookAllMethods(cls, "setBytedata", object : SafeHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    if (Looper.myLooper() == Looper.getMainLooper()) return
                    val bytes = param.args.firstOrNull() as? ByteArray ?: return
                    val api = (try { Reflect.getObjectField(param.thisObject ?: return, "api") } catch (_: Throwable) { null })
                        ?.toString()?.lowercase() ?: return
                    if (API_ALLOWLIST.none { api.startsWith(it) }) return
                    translate(api, bytes)?.let { param.args[0] = it }
                }
            })
            Utils.debugLog("$TAG: hooked $hooked setBytedata overloads")
        } catch (t: Throwable) {
            Utils.debugLog("$TAG: not installed: ${t.message}")
        }
    }

    private fun translate(api: String, bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return null
        val start = System.currentTimeMillis()
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Throwable) {
            return null
        }
        val data = root.opt("data") ?: return null

        val texts = LinkedHashSet<String>()
        collect(data, null, texts)
        if (texts.isEmpty()) return null

        val translations = HashMap<String, String>()
        val missing = ArrayList<String>()
        for (t in texts) {
            when (val c = cached(t)) {
                null -> missing.add(t)
                t -> {}
                else -> translations[t] = c
            }
        }
        val fetched = fetch(missing, start + BUDGET_MS)
        translations.putAll(fetched)
        if (translations.isEmpty()) return null

        replace(data, null, translations)
        if (Utils.Debug) logLeftovers(api, data)
        val out = root.toString().toByteArray(Charsets.UTF_8)
        Utils.debugLog(
            "$TAG: $api ${texts.size} strings, ${translations.size - fetched.size} cached, ${fetched.size}/${missing.size} fetched " +
                "in ${System.currentTimeMillis() - start}ms"
        )
        return out
    }

    /** Debug: CJK strings still in the response and the key they sit under. */
    private fun logLeftovers(api: String, data: Any) {
        val left = LinkedHashMap<String, String>()
        fun walk(node: Any?, key: String?) {
            when (node) {
                is JSONObject -> node.keys().forEach { k -> walk(node.opt(k), k) }
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), key)
                is String -> if (CJK.containsMatchIn(node) && left.size < 40) left[node.take(60)] = key ?: "-"
            }
        }
        walk(data, null)
        left.forEach { (v, k) -> Utils.debugLog("$TAG: left in $api [$k] '$v'") }
    }

    private fun isCandidate(key: String?, value: String): Boolean {
        if (key != null && SKIP_KEY.containsMatchIn(key)) return false
        val v = value.trim()
        if (v.isEmpty() || v.length > 300 || !CJK.containsMatchIn(v)) return false
        if (v.startsWith("{") || v.startsWith("[") || v.startsWith("http") || v.startsWith("//") || v.contains("://")) return false
        if (v.contains('\n')) return false
        return true
    }

    private fun collect(node: Any?, key: String?, out: MutableSet<String>) {
        when (node) {
            is JSONObject -> node.keys().forEach { k -> collect(node.opt(k), k, out) }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), key, out)
            is String -> if (isCandidate(key, node)) out.add(node.trim())
        }
    }

    private fun replace(node: Any?, key: String?, map: Map<String, String>) {
        when (node) {
            is JSONObject -> node.keys().asSequence().toList().forEach { k ->
                val v = node.opt(k)
                if (v is String) {
                    if (isCandidate(k, v)) map[v.trim()]?.let { node.put(k, it) }
                } else replace(v, k, map)
            }
            is JSONArray -> for (i in 0 until node.length()) {
                val v = node.opt(i)
                if (v is String) {
                    if (isCandidate(key, v)) map[v.trim()]?.let { node.put(i, it) }
                } else replace(v, key, map)
            }
        }
    }

    /** Translates [texts] in newline-joined chunks, in parallel, within the deadline. */
    private fun fetch(texts: List<String>, deadline: Long): Map<String, String> {
        if (texts.isEmpty()) return emptyMap()
        val chunks = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var size = 0
        for (t in texts) {
            if (size + t.length > CHUNK_CHARS && current.isNotEmpty()) {
                chunks.add(current); current = ArrayList(); size = 0
            }
            current.add(t); size += t.length + 1
        }
        if (current.isNotEmpty()) chunks.add(current)

        val futures = chunks.map { chunk ->
            pool.submit(Callable {
                // The Google provider uses the translateHtml endpoint: one <p>
                // per string survives translation (plain newlines/<br> get
                // merged), and the provider turns each </p> into a newline.
                val joined = chunk.joinToString("") { "<p>${escapeHtml(it)}</p>" }
                val result = GetTranslateToken.translateBlocking(joined) ?: return@Callable emptyMap<String, String>()
                val lines = result.split('\n').map { unescapeHtml(it).trim() }.dropLastWhile { it.isEmpty() }
                if (lines.size != chunk.size) {
                    Utils.debugLog("$TAG: chunk line mismatch ${chunk.size} -> ${lines.size}, skipped")
                    return@Callable emptyMap<String, String>()
                }
                chunk.indices.associate { chunk[it] to lines[it] }
                    .filter { (k, v) -> v.isNotEmpty() && v != k }
            })
        }
        val result = HashMap<String, String>()
        for (f in futures) {
            val wait = deadline - System.currentTimeMillis()
            try {
                if (wait > 0) result.putAll(f.get(wait, TimeUnit.MILLISECONDS)) else f.cancel(true)
            } catch (_: Throwable) {
                f.cancel(true)
            }
        }
        result.forEach { (k, v) -> cache(k, v) }
        return result
    }

    private fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun unescapeHtml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&#x27;", "'").replace("&nbsp;", " ").replace("&amp;", "&")

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

    private fun cache(text: String, translated: String) {
        if (!PreferenceList.Caching) return
        Alltrans.cacheAccess.acquireUninterruptibly()
        try {
            Alltrans.cache?.put(text, translated)
        } finally {
            if (Alltrans.cacheAccess.availablePermits() == 0) Alltrans.cacheAccess.release()
        }
    }
}
