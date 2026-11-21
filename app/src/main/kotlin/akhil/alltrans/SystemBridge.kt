package akhil.alltrans

import android.app.ActivityManager
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
 * Privileged actions the AllTrans app asks the module's system_server hook to
 * perform, for ROMs that hide the matching system screens:
 *  - "get"/"set": per-app language (Android 13+ app languages, LocaleManager);
 *  - "forcestop": force-stop an app so new AllTrans settings take effect;
 *  - "ping": is the system hook active, and which framework runs it.
 *
 * These need system permissions (CHANGE_CONFIGURATION, FORCE_STOP_PACKAGES)
 * that normal apps cannot hold. AllTrans already hooks SettingsProvider.call()
 * in system_server (the "System Framework" scope of its proxy), so the app asks
 * there: ContentResolver.call(content://settings, METHOD, null, {op, pkg, ...}).
 * Only calls coming from the AllTrans app's own uid are served. Without the
 * system scope, the app falls back to `su -c cmd ...` (root) where possible.
 */
internal object SystemBridge {
    const val METHOD = "alltransSystemBridge"
    private const val MODULE_PACKAGE = "akhil.alltrans"
    private const val KEY_OP = "op"
    private const val KEY_PKG = "pkg"
    private const val KEY_LOCALES = "locales"
    private const val KEY_OK = "ok"
    private const val KEY_ERROR = "error"
    private const val KEY_FRAMEWORK = "framework"

    /** Packages that are never force-stopped. */
    private val PROTECTED_PACKAGES = setOf("android", "com.android.systemui", "com.android.phone", MODULE_PACKAGE)

    // ------------------------------------------------------- system_server side

    /** Called from the SettingsProvider.call hook. Returns the reply bundle. */
    fun handleInSystem(provider: Any?, extras: Bundle?): Bundle {
        val reply = Bundle()
        try {
            val context = (provider as? ContentProvider)?.context ?: return reply.err("no context")
            // Who is asking? Checked before clearing the calling identity.
            val callingUid = Binder.getCallingUid()
            val moduleUid = try {
                context.packageManager.getPackageUid(MODULE_PACKAGE, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                -1
            }
            if (callingUid != moduleUid) return reply.err("caller not allowed")

            val op = extras?.getString(KEY_OP) ?: return reply.err("no op")
            val ident = Binder.clearCallingIdentity()
            try {
                when (op) {
                    "ping" -> reply.putString(KEY_FRAMEWORK, HookRuntime.frameworkDescription())
                    "forcestop" -> {
                        val pkg = extras.getString(KEY_PKG) ?: return reply.err("no package")
                        if (pkg in PROTECTED_PACKAGES) return reply.err("protected package")
                        val am = context.getSystemService(ActivityManager::class.java)
                        // forceStopPackage(String) is hidden API; system_server may call it.
                        ActivityManager::class.java.getMethod("forceStopPackage", String::class.java).invoke(am, pkg)
                    }
                    "get", "set" -> {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return reply.err("Android 13+ required")
                        val pkg = extras.getString(KEY_PKG) ?: return reply.err("no package")
                        val lm = context.getSystemService(LocaleManager::class.java) ?: return reply.err("no LocaleManager")
                        if (op == "set") {
                            // setApplicationLocales(String, LocaleList) is a @SystemApi.
                            LocaleManager::class.java
                                .getMethod("setApplicationLocales", String::class.java, LocaleList::class.java)
                                .invoke(lm, pkg, LocaleList.forLanguageTags(extras.getString(KEY_LOCALES).orEmpty()))
                        }
                        reply.putString(KEY_LOCALES, lm.getApplicationLocales(pkg).toLanguageTags())
                    }
                    else -> return reply.err("unknown op")
                }
                reply.putBoolean(KEY_OK, true)
            } finally {
                Binder.restoreCallingIdentity(ident)
            }
        } catch (t: Throwable) {
            HookRuntime.log("SystemBridge failed", t)
            reply.err((t.cause ?: t).toString())
        }
        return reply
    }

    private fun Bundle.err(message: String): Bundle = apply {
        putBoolean(KEY_OK, false)
        putString(KEY_ERROR, message)
    }

    // ------------------------------------------------------------ module app side

    class Result(val ok: Boolean, val value: String?, val via: String, val error: String?)

    /** Sends [op] to the system hook; null when the hook is not active. */
    private fun callSystem(context: Context, op: String, pkg: String?, locales: String?): Result? {
        return try {
            val extras = Bundle().apply {
                putString(KEY_OP, op)
                if (pkg != null) putString(KEY_PKG, pkg)
                if (locales != null) putString(KEY_LOCALES, locales)
            }
            val reply = context.contentResolver.call(Uri.parse("content://settings"), METHOD, null, extras)
            if (reply == null || !reply.containsKey(KEY_OK)) {
                Utils.debugLog("SystemBridge: $op: system hook not active")
                null
            } else {
                val value = reply.getString(KEY_LOCALES) ?: reply.getString(KEY_FRAMEWORK)
                Utils.debugLog("SystemBridge: $op ${pkg.orEmpty()} via system -> ok=${reply.getBoolean(KEY_OK)} $value ${reply.getString(KEY_ERROR) ?: ""}")
                Result(reply.getBoolean(KEY_OK), value, "system", reply.getString(KEY_ERROR))
            }
        } catch (t: Throwable) {
            Utils.debugLog("SystemBridge: $op: system call failed: ${t.message}")
            null
        }
    }

    /** Framework description if the system hook is active, else null. Not on the main thread. */
    fun ping(context: Context): Result? = callSystem(context, "ping", null, null)

    /** Current per-app locales of [pkg] ("" = system default). Not on the main thread. */
    fun getLocales(context: Context, pkg: String): Result =
        callSystem(context, "get", pkg, null) ?: rootGetLocales(pkg)

    /** Sets [tags] (e.g. "vi", "" for system default) for [pkg]. Not on the main thread. */
    fun setLocales(context: Context, pkg: String, tags: String): Result {
        callSystem(context, "set", pkg, tags)?.let { return it }
        return try {
            val quoted = if (tags.isEmpty()) "\"\"" else tags
            val r = su("cmd locale set-app-locales $pkg --locales $quoted")
            if (r.first != 0) Result(false, null, "root", r.second.ifBlank { "su exit ${r.first}" }) else rootGetLocales(pkg)
        } catch (t: Throwable) {
            Result(false, null, "none", t.message)
        }
    }

    /** Force-stops [pkg]. Not on the main thread. */
    fun forceStop(context: Context, pkg: String): Result {
        callSystem(context, "forcestop", pkg, null)?.let { return it }
        if (pkg in PROTECTED_PACKAGES) return Result(false, null, "none", "protected package")
        return try {
            val r = su("am force-stop $pkg")
            Result(r.first == 0, null, "root", if (r.first == 0) null else r.second.ifBlank { "su exit ${r.first}" })
        } catch (t: Throwable) {
            Result(false, null, "none", t.message)
        }
    }

    /** Whether root is granted to AllTrans (may show the root manager's prompt). Not on the main thread. */
    fun hasRoot(): Boolean = try { su("id -u").let { it.first == 0 && it.second.trim().endsWith("0") } } catch (_: Throwable) { false }

    private fun rootGetLocales(pkg: String): Result = try {
        val g = su("cmd locale get-app-locales $pkg")
        if (g.first != 0) {
            Result(false, null, "root", g.second.ifBlank { "su exit ${g.first}" })
        } else {
            // Output: "Locales for <pkg> for user 0 are [vi]"
            Result(true, Regex("\\[(.*)]").find(g.second)?.groupValues?.get(1).orEmpty(), "root", null)
        }
    } catch (t: Throwable) {
        Result(false, null, "none", t.message)
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

    // ------------------------------------------------------------------ helpers

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
            Utils.debugLog("SystemBridge: supportedLocales($pkg) failed: ${t.message}")
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
