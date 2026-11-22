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

    /** How long a tick waits for the user to approve the scope request. */
    private const val SCOPE_WAIT_MS = 20_000L

    /** Apps waiting for the scope approval (shown ticked, "waiting for approval"). */
    private val pendingScope = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun isWaitingForScope(pkg: String) = pkg in pendingScope

    /**
     * Turn translation on for [pkg] once it is in the LSPosed scope. If it is
     * not, ask the framework and wait: approval turns translation on; denial,
     * an error or no answer within [SCOPE_WAIT_MS] leaves it off. [onDone]
     * runs on the main thread with the final state. A late approval (after the
     * timeout) still turns translation on, and calls [onDone] again.
     */
    fun enableTranslation(context: Context, pkg: String, onDone: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val service = RemotePrefs.service
        if (service == null || isInScope(pkg) == true) {
            // In scope already, or no framework service to ask (cannot check).
            if (service == null) toast(appContext, appContext.getString(R.string.scope_no_service))
            setTranslation(appContext, pkg, true)
            onDone(true)
            return
        }
        pendingScope += pkg
        var finished = false
        fun finish(approved: Boolean, late: Boolean = false) = main.post {
            if (finished && !late) return@post
            finished = true
            pendingScope -= pkg
            if (approved) setTranslation(appContext, pkg, true)
            onDone(approved)
        }
        val timeout = Runnable {
            if (!finished) {
                toast(appContext, appContext.getString(R.string.scope_timeout))
                finish(false)
            }
        }
        try {
            service.requestScope(listOf(pkg), object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: List<String>) {
                    refreshScope()
                    val ok = pkg in approved || isInScope(pkg) == true
                    toast(appContext, appContext.getString(if (ok) R.string.scope_approved else R.string.scope_denied, pkg))
                    main.removeCallbacks(timeout)
                    finish(ok, late = ok)
                }

                override fun onScopeRequestFailed(message: String) {
                    toast(appContext, appContext.getString(R.string.scope_failed, message))
                    main.removeCallbacks(timeout)
                    finish(false)
                }
            })
            toast(appContext, appContext.getString(R.string.scope_requested))
            main.postDelayed(timeout, SCOPE_WAIT_MS)
        } catch (t: Throwable) {
            toast(appContext, appContext.getString(R.string.scope_failed, t.message ?: ""))
            finish(false)
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

    // ---------------------------------------------------------- background

    /**
     * The translation providers run in the ReAllTrans process while another app
     * is in front. Unless ReAllTrans is exempt from battery optimisation,
     * Android 15+ / HyperOS block its network (and may freeze it) in the
     * background, and every translation request times out.
     */
    fun isBackgroundAllowed(context: Context): Boolean =
        context.getSystemService(android.os.PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Shows Android's own "allow background" dialog (the user decides). */
    fun requestBackground(context: Context) {
        val pkgUri = android.net.Uri.parse("package:" + context.packageName)
        val intents = listOf(
            android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
            android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        )
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Throwable) {
            }
        }
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}
