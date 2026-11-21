package akhil.alltrans

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.libxposed.service.XposedService
import java.io.File

/** Per-app state shared by the app list and the per-app settings screen (app process only). */
internal object AppConfig {
    /** Per-app pref: language tag AllTrans set through Android's app languages. */
    const val KEY_BUILT_IN_LANGUAGE = "BuiltInLanguage"

    private fun global(context: Context) = context.getSharedPreferences(RemotePrefs.GLOBAL, Context.MODE_PRIVATE)
    private fun local(context: Context, pkg: String) = context.getSharedPreferences(pkg, Context.MODE_PRIVATE)

    fun isTranslationOn(context: Context, pkg: String) = global(context).getBoolean(pkg, false)

    fun setTranslation(context: Context, pkg: String, on: Boolean) {
        global(context).edit().apply { if (on) putBoolean(pkg, true) else remove(pkg) }.apply()
        local(context, pkg).edit().putBoolean("LocalEnabled", on).apply()
        RemotePrefs.watch(pkg)
        PreferenceManager.clearCache(pkg)
    }

    fun builtInLanguage(context: Context, pkg: String): String? =
        local(context, pkg).getString(KEY_BUILT_IN_LANGUAGE, null)?.takeIf { it.isNotEmpty() }

    fun setBuiltInLanguage(context: Context, pkg: String, tag: String?) {
        local(context, pkg).edit().apply {
            if (tag.isNullOrEmpty()) remove(KEY_BUILT_IN_LANGUAGE) else putString(KEY_BUILT_IN_LANGUAGE, tag)
        }.apply()
        RemotePrefs.watch(pkg)
    }

    /**
     * Apps the user has set up in AllTrans: translation on, or a settings file
     * exists (it was ticked once, configured, or given a built-in language).
     * These stay in the top group of the list even with translation off.
     */
    fun isConfigured(context: Context, pkg: String): Boolean =
        isTranslationOn(context, pkg) ||
            File(context.applicationInfo.dataDir, "shared_prefs/$pkg.xml").let { it.exists() && local(context, pkg).all.isNotEmpty() }

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
        val service: XposedService = RemotePrefs.service ?: run {
            toast(context, context.getString(R.string.scope_no_service))
            return
        }
        if (isInScope(pkg) == true) return
        val appContext = context.applicationContext
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

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}
