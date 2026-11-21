package akhil.alltrans

import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Opt-in: translates selected MTOP API responses before the app reads them.
 *
 * Some screens never put their text into Android views (Taobao's cart is
 * rendered by Weex 2 / Skia in native code), but they get it from MTOP JSON
 * responses passing through MtopResponse.setBytedata() on a network thread.
 *
 * This changes the app's data, so it is off unless the user disables safe mode
 * and enables it for the app, and it is kept as narrow as possible:
 *  - only read-only APIs on the allow-list (never cart/order/pay actions);
 *  - only string values under display keys (title, text, name, desc, ...);
 *  - only values containing CJK, never links or embedded JSON;
 *  - the raw JSON text is edited in place: every other byte (numbers, key
 *    order, whitespace, other strings) stays exactly as the server sent it;
 *  - a time budget, after which remaining strings are left untranslated.
 */
internal object MtopTranslator {
    private const val TAG = "AllTrans:Mtop"

    /** Read-only APIs whose responses are translated (lower-case prefixes). */
    private val API_ALLOWLIST = listOf(
        "mtop.trade.query.bag",          // cart (query only)
        "mtop.taobao.detail.data.get",   // item page
    )

    /** Keys whose values are pure display text. */
    private val DISPLAY_KEY = Regex(
        "(?i)^(title|subtitle|sub_title|text|name|desc|description|content|label|tip|tips|hint|placeholder|" +
            "msg|message|summary|tag|tags|tagtext|buttontext|btntext|shopname|sellernick|itemtitle|skutext|" +
            "propname|valuename|pricedesc|pricetext|promotiontext|benefittext|ruletext)$"
    )
    private val DISPLAY_SUFFIX = Regex("(Title|Text|Name|Desc|Label|Tip|Tips|Message|Summary)$")

    private const val CHUNK_CHARS = 600
    private const val BUDGET_MS = 6000L
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "AllTrans-mtop").apply { isDaemon = true } }
    private val CJK = Regex("[㐀-鿿]")

    @Volatile
    private var installed = false

    fun install(loader: ClassLoader) {
        if (installed) return
        installed = true
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

    private fun isDisplayKey(key: String?) = key != null && (DISPLAY_KEY.matches(key) || DISPLAY_SUFFIX.containsMatchIn(key))

    private fun isCandidate(key: String?, value: String): Boolean {
        if (!isDisplayKey(key)) return false
        val v = value.trim()
        if (v.isEmpty() || v.length > 300 || !CJK.containsMatchIn(v)) return false
        if (v.startsWith("{") || v.startsWith("[") || v.startsWith("http") || v.startsWith("//") || v.contains("://")) return false
        return !v.contains('\n')
    }

    private fun translate(api: String, bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return null
        val start = System.currentTimeMillis()
        val json = String(bytes, Charsets.UTF_8)

        val texts = LinkedHashSet<String>()
        if (scan(json, null, texts) == null) return null   // not well-formed: leave untouched
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

        val out = scan(json, translations, null) ?: return null
        Utils.debugLog(
            "$TAG: $api ${texts.size} strings, ${translations.size - fetched.size} cached, ${fetched.size}/${missing.size} fetched " +
                "in ${System.currentTimeMillis() - start}ms"
        )
        return out.toByteArray(Charsets.UTF_8)
    }

    private class Ctx(val isObject: Boolean, val parentKey: String?) {
        var key: String? = null
        var expectKey = isObject
    }

    /**
     * Walks the raw JSON text. With [collect], records candidate values; with
     * [map], returns the text with candidate values replaced and everything
     * else copied verbatim. Returns null if the text is not valid JSON enough
     * to walk safely.
     */
    internal fun scan(json: String, map: Map<String, String>?, collect: MutableSet<String>?): String? {
        val out = if (map != null) StringBuilder(json.length + json.length / 8) else null
        val stack = ArrayList<Ctx>()
        var i = 0
        val n = json.length
        while (i < n) {
            val c = json[i]
            when (c) {
                '{', '[' -> {
                    val top = stack.lastOrNull()
                    val parentKey = when {
                        top == null -> null
                        top.isObject -> top.key
                        else -> top.parentKey
                    }
                    stack.add(Ctx(c == '{', parentKey))
                    out?.append(c); i++
                }
                '}', ']' -> {
                    if (stack.isEmpty()) return null
                    stack.removeAt(stack.size - 1)
                    out?.append(c); i++
                }
                ',' -> {
                    stack.lastOrNull()?.let { if (it.isObject) it.expectKey = true }
                    out?.append(c); i++
                }
                ':' -> {
                    stack.lastOrNull()?.expectKey = false
                    out?.append(c); i++
                }
                '"' -> {
                    val end = stringEnd(json, i) ?: return null
                    val raw = json.substring(i, end + 1)
                    val top = stack.lastOrNull()
                    if (top != null && top.isObject && top.expectKey) {
                        top.key = decode(raw) ?: return null
                        out?.append(raw)
                    } else {
                        val key = if (top == null) null else if (top.isObject) top.key else top.parentKey
                        val value = if (raw.indexOf('\\') < 0) raw.substring(1, raw.length - 1) else decode(raw) ?: return null
                        if (isCandidate(key, value)) {
                            val trimmed = value.trim()
                            collect?.add(trimmed)
                            val tr = map?.get(trimmed)
                            if (tr != null) out?.append(JSONObject.quote(value.replace(trimmed, tr))) else out?.append(raw)
                        } else out?.append(raw)
                    }
                    i = end + 1
                }
                else -> { out?.append(c); i++ }
            }
        }
        if (stack.isNotEmpty()) return null
        return out?.toString() ?: ""
    }

    /** Index of the closing quote of the string literal starting at [start]. */
    private fun stringEnd(s: String, start: Int): Int? {
        var i = start + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return null
    }

    private fun decode(raw: String): String? =
        try { org.json.JSONTokener(raw).nextValue() as? String } catch (_: Throwable) { null }

    /** Translates [texts] in chunks of one <p> per string, in parallel, within the deadline. */
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
                // per string survives translation (newline/<br> separators get
                // merged), and the provider turns each </p> into a newline.
                val joined = chunk.joinToString("") { "<p>${escapeHtml(it)}</p>" }
                val result = GetTranslateToken.translateBlocking(joined) ?: return@Callable emptyMap<String, String>()
                val lines = result.split('\n').map { unescapeHtml(it).trim() }.dropLastWhile { it.isEmpty() }
                if (lines.size != chunk.size) {
                    Utils.debugLog("$TAG: chunk line mismatch ${chunk.size} -> ${lines.size}, skipped")
                    return@Callable emptyMap<String, String>()
                }
                chunk.indices.associate { chunk[it] to lines[it] }.filter { (k, v) -> v.isNotEmpty() && v != k }
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
