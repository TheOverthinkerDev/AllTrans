package akhil.alltrans

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.libxposed.service.XposedService

/** Per-app state shared by the app list and the per-app settings screen (app process only). */
internal object AppConfig {
    /** Per-app pref: language tag AllTrans set through Android's app languages. */
    const val KEY_BUILT_IN_LANGUAGE = "BuiltInLanguage"
    /** Per-app pref: the user set this app up (translation, built-in language or own settings). */
    private const val KEY_CONFIGURED = "Configured"

    enum class Mode { NONE, CONFIGURED, TRANSLATION, BUILT_IN }

    private fun global(context: Context) = context.getSharedPreferences(RemotePrefs.GLOBAL, Context.MODE_PRIVATE)
    private fun local(context: Context, pkg: String) = context.getSharedPreferences(pkg, Context.MODE_PRIVATE)

    fun isTranslationOn(context: Context, pkg: String) = global(context).getBoolean(pkg, false)

    fun setTranslation(context: Context, pkg: String, on: Boolean) {
        global(context).edit().apply { if (on) putBoolean(pkg, true) else remove(pkg) }.apply()
        local(context, pkg).edit().putBoolean("LocalEnabled", on).apply { if (on) putBoolean(KEY_CONFIGURED, true) }.apply()
        RemotePrefs.watch(pkg)
        PreferenceManager.clearCache(pkg)
    }

    fun builtInLanguage(context: Context, pkg: String): String? =
        local(context, pkg).getString(KEY_BUILT_IN_LANGUAGE, null)?.takeIf { it.isNotEmpty() }

    fun setBuiltInLanguage(context: Context, pkg: String, tag: String?) {
        local(context, pkg).edit().apply {
            if (tag.isNullOrEmpty()) remove(KEY_BUILT_IN_LANGUAGE)
            else putString(KEY_BUILT_IN_LANGUAGE, tag).putBoolean(KEY_CONFIGURED, true)
        }.apply()
        RemotePrefs.watch(pkg)
    }

    fun markConfigured(context: Context, pkg: String) {
        local(context, pkg).edit().putBoolean(KEY_CONFIGURED, true).apply()
        RemotePrefs.watch(pkg)
    }

    /**
     * How the app is set up. Anything but NONE is shown ticked at the top of the
     * list: translation on, a built-in language, or settings of its own (marked
     * when changed, or "override global settings" on).
     */
    fun mode(context: Context, pkg: String): Mode {
        if (builtInLanguage(context, pkg) != null) return Mode.BUILT_IN
        if (isTranslationOn(context, pkg)) return Mode.TRANSLATION
        val l = local(context, pkg)
        return if (l.getBoolean(KEY_CONFIGURED, false) || l.getBoolean("OverRide", false)) Mode.CONFIGURED else Mode.NONE
    }

    /**
     * Unticking an app in the list: translation off, built-in language back to
     * the system default, its own settings deleted and its scope removed.
     * Call off the main thread (locale reset and scope go through binders).
     */
    fun clear(context: Context, pkg: String) {
        if (builtInLanguage(context, pkg) != null) SystemBridge.setLocales(context, pkg, "")
        global(context).edit().remove(pkg).apply()
        context.deleteSharedPreferences(pkg)
        RemotePrefs.delete(pkg)
        PreferenceManager.clearCache(pkg)
        removeScope(context, pkg)
    }

    // --------------------------------------------------------------- alibaba

    fun isAlibabaApp(pkg: String?) = pkg != null && pkg in PreferenceList.ALIBABA_PACKAGES

    // ------------------------------------------------------------------ scope

    @Volatile
    private var scopeCache: Set<String>? = null

    /** Re-reads the module scope from the framework (binder call). */
    fun refreshScope(): Set<String>? {
        scopeCache = RemotePrefs.service?.let { s -> runCatching { s.scope.toSet() }.getOrNull() }
        return scopeCache
    }

    /** null = unknown (framework service not connected). */
    fun isInScope(pkg: String): Boolean? = (scopeCache ?: refreshScope())?.contains(pkg)

    /**
     * libxposed modules are only loaded into processes in their scope, so an app
     * must be in AllTrans' LSPosed scope to be translated. Ask the framework to
     * add it; it shows its own confirmation to the user.
     */
    fun requestScopeIfNeeded(context: Context, pkg: String) {
        val appContext = context.applicationContext
        val service: XposedService = RemotePrefs.service ?: run {
            toast(appContext, appContext.getString(R.string.scope_no_service))
            return
        }
        if (isInScope(pkg) == true) return
        try {
            service.requestScope(listOf(pkg), object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: List<String>) {
                    refreshScope()
                    toast(appContext, appContext.getString(R.string.scope_approved, pkg))
                }

                override fun onScopeRequestFailed(message: String) {
                    toast(appContext, appContext.getString(R.string.scope_failed, message))
                }
            })
            toast(appContext, appContext.getString(R.string.scope_requested))
        } catch (t: Throwable) {
            toast(appContext, appContext.getString(R.string.scope_failed, t.message ?: ""))
        }
    }

    /** An app using its built-in language (or unticked) needs no hooks: drop it from the scope. */
    fun removeScope(context: Context, pkg: String) {
        val service = RemotePrefs.service ?: return
        if (isInScope(pkg) != true) return
        val appContext = context.applicationContext
        try {
            service.removeScope(listOf(pkg))
            refreshScope()
            toast(appContext, appContext.getString(R.string.scope_removed, pkg))
        } catch (t: Throwable) {
            toast(appContext, appContext.getString(R.string.scope_failed, t.message ?: ""))
        }
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}
