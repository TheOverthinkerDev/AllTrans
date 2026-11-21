package akhil.alltrans

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.ContentProvider
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Per-app language (Android 13+ "app languages", LocaleManager) for any app,
 * for ROMs that hide the system screen or only allow it through adb.
 *
 * LocaleManager.set/getApplicationLocales() for another package needs
 * CHANGE_CONFIGURATION / READ_APP_SPECIFIC_LOCALES, which normal apps cannot
 * hold. AllTrans already hooks SettingsProvider.call() in system_server (the
 * "System Framework" scope used by its proxy), so the module app asks it there:
 *   ContentResolver.call(content://settings, METHOD, null, {op, pkg, locales})
 * Only calls coming from the AllTrans app itself are served. Without the
 * system scope, the app falls back to `su -c cmd locale ...` (root).
 */
internal object AppLocaleBridge {
    const val METHOD = "alltransAppLocales"
    private const val MODULE_PACKAGE = "akhil.alltrans"
    private const val KEY_OP = "op"
    private const val KEY_PKG = "pkg"
    private const val KEY_LOCALES = "locales"
    private const val KEY_OK = "ok"
    private const val KEY_ERROR = "error"

    // ------------------------------------------------------- system_server side

    /** Called from the SettingsProvider.call hook. Returns the reply bundle. */
    fun handleInSystem(provider: Any?, extras: Bundle?): Bundle {
        val reply = Bundle()
        try {
            val context = (provider as? ContentProvider)?.context ?: return reply.err("no context")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return reply.err("Android 13+ required")
            // Who is asking? Checked before clearing the calling identity.
            val callingUid = Binder.getCallingUid()
            val moduleUid = try {
                context.packageManager.getPackageUid(MODULE_PACKAGE, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                -1
            }
            if (callingUid != moduleUid) return reply.err("caller not allowed")

            val pkg = extras?.getString(KEY_PKG) ?: return reply.err("no package")
            val ident = Binder.clearCallingIdentity()
            try {
                val lm = context.getSystemService(LocaleManager::class.java) ?: return reply.err("no LocaleManager")
                when (extras.getString(KEY_OP)) {
                    // setApplicationLocales(String, LocaleList) is a @SystemApi.
                    "set" -> LocaleManager::class.java
                        .getMethod("setApplicationLocales", String::class.java, LocaleList::class.java)
                        .invoke(lm, pkg, LocaleList.forLanguageTags(extras.getString(KEY_LOCALES).orEmpty()))
                    "get" -> {}
                    else -> return reply.err("unknown op")
                }
                reply.putString(KEY_LOCALES, lm.getApplicationLocales(pkg).toLanguageTags())
                reply.putBoolean(KEY_OK, true)
            } finally {
                Binder.restoreCallingIdentity(ident)
            }
        } catch (t: Throwable) {
            HookRuntime.log("AppLocaleBridge failed", t)
            reply.err(t.toString())
        }
        return reply
    }

    private fun Bundle.err(message: String): Bundle = apply {
        putBoolean(KEY_OK, false)
        putString(KEY_ERROR, message)
    }

    // ------------------------------------------------------------ module app side

    class Result(val ok: Boolean, val locales: String?, val via: String, val error: String?)

    /** Current per-app locales of [pkg] ("" = system default). Do not call on the main thread. */
    fun get(context: Context, pkg: String): Result = request(context, pkg, "get", null)

    /** Sets [tags] (e.g. "vi", "" for system default) for [pkg]. Do not call on the main thread. */
    fun set(context: Context, pkg: String, tags: String): Result = request(context, pkg, "set", tags)

    private fun request(context: Context, pkg: String, op: String, tags: String?): Result {
        // 1) Through the module's system_server hook.
        try {
            val extras = Bundle().apply {
                putString(KEY_OP, op)
                putString(KEY_PKG, pkg)
                if (tags != null) putString(KEY_LOCALES, tags)
            }
            val reply = context.contentResolver.call(Uri.parse("content://settings"), METHOD, null, extras)
            if (reply != null && reply.containsKey(KEY_OK)) {
                return Result(reply.getBoolean(KEY_OK), reply.getString(KEY_LOCALES), "system", reply.getString(KEY_ERROR))
            }
        } catch (t: Throwable) {
            Utils.debugLog("AppLocaleBridge: system call failed: ${t.message}")
        }
        // 2) Root fallback: the same thing the ROM's adb command does.
        return try {
            if (op == "set") {
                val quoted = if (tags.isNullOrEmpty()) "\"\"" else tags
                val r = su("cmd locale set-app-locales $pkg --locales $quoted")
                if (r.first != 0) return Result(false, null, "root", r.second.ifBlank { "su exit ${r.first}" })
            }
            val g = su("cmd locale get-app-locales $pkg")
            if (g.first != 0) return Result(false, null, "root", g.second.ifBlank { "su exit ${g.first}" })
            // Output: "Locales for <pkg> for user 0 are [vi]"
            val current = Regex("\\[(.*)]").find(g.second)?.groupValues?.get(1).orEmpty()
            Result(true, current, "root", null)
        } catch (t: Throwable) {
            Result(false, null, "none", t.message)
        }
    }

    private fun su(command: String): Pair<Int, String> {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroy()
            return -1 to "timeout"
        }
        return process.exitValue() to output.trim()
    }

    /**
     * Languages [pkg] can show: its declared localeConfig, or else the
     * locales its resources contain. Returns BCP-47 tags, sorted by name.
     */
    fun supportedLocales(context: Context, pkg: String): List<String> {
        val tags = LinkedHashSet<String>()
        try {
            val pkgContext = context.createPackageContext(pkg, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    LocaleConfig.fromContextIgnoringOverride(pkgContext)
                } else {
                    LocaleConfig(pkgContext)
                }
                val list = config.supportedLocales
                if (config.status == LocaleConfig.STATUS_SUCCESS && list != null) {
                    for (i in 0 until list.size()) tags.add(list[i].toLanguageTag())
                }
            }
            if (tags.isEmpty()) {
                // No localeConfig: fall back to the locales in its resources.
                val assets = pkgContext.resources.assets
                @Suppress("UNCHECKED_CAST")
                val locales = assets.javaClass.getMethod("getLocales").invoke(assets) as? Array<String>
                locales?.filter { it.isNotBlank() }?.forEach { tags.add(Locale.forLanguageTag(it).toLanguageTag()) }
            }
        } catch (t: Throwable) {
            Utils.debugLog("AppLocaleBridge: supportedLocales($pkg) failed: ${t.message}")
        }
        val display = Locale.getDefault()
        return tags.filter { it != "und" }.sortedBy { Locale.forLanguageTag(it).getDisplayName(display).lowercase() }
    }

    fun displayName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        val name = locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
        return "$name ($tag)"
    }
}
