package akhil.alltrans

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * App side of the libxposed settings channel.
 *
 * AllTrans keeps editing its normal SharedPreferences files; this object mirrors
 * every settings file into a remote preferences group of the same name through
 * libxposed/service. Hooked apps then read them with
 * XposedInterface.getRemotePreferences() (see [HookRuntime.readRemoteSettings]),
 * without querying a ContentProvider through the system_server proxy.
 *
 * Groups: "AllTransPref" (global settings, incl. one boolean per enabled app)
 * and one group per package name (per-app settings).
 */
object RemotePrefs {
    private const val TAG = "AllTrans:RemotePrefs"
    const val GLOBAL = "AllTransPref"

    @Volatile
    var service: XposedService? = null
        private set

    private lateinit var appContext: Context
    private val main = Handler(Looper.getMainLooper())
    private val watched = ConcurrentHashMap<String, SharedPreferences.OnSharedPreferenceChangeListener>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val listeners = mutableListOf<(XposedService?) -> Unit>()

    fun init(context: Context) {
        appContext = context.applicationContext
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                Log.i(TAG, "Service bound: ${describe(service)}")
                this@RemotePrefs.service = service
                Thread { syncAll() }.start()
                notifyListeners(service)
            }

            override fun onServiceDied(service: XposedService) {
                Log.w(TAG, "Service died")
                if (this@RemotePrefs.service === service) this@RemotePrefs.service = null
                notifyListeners(null)
            }
        })
        // Watch the files that exist now, so edits are mirrored even before the
        // service binds (they are re-synced in full on bind).
        settingFiles().forEach { watch(it) }
    }

    /** Called with the current service now and whenever it binds or dies. */
    fun observe(listener: (XposedService?) -> Unit) {
        synchronized(listeners) { listeners += listener }
        listener(service)
    }

    fun removeObserver(listener: (XposedService?) -> Unit) {
        synchronized(listeners) { listeners -= listener }
    }

    private fun notifyListeners(service: XposedService?) {
        val copy = synchronized(listeners) { listeners.toList() }
        main.post { copy.forEach { it(service) } }
    }

    val remoteSupported: Boolean
        get() = service?.let { runCatching { it.frameworkProperties and XposedService.PROP_CAP_REMOTE != 0L }.getOrNull() } == true

    fun describe(service: XposedService): String = runCatching {
        "${service.frameworkName} ${service.frameworkVersion} (${service.frameworkVersionCode}), API ${service.apiVersion}"
    }.getOrElse { "unknown (${it.message})" }

    /** Settings files: global prefs and one file per app (package names contain dots). */
    fun settingFiles(): List<String> {
        val dir = File(appContext.applicationInfo.dataDir, "shared_prefs")
        val names = dir.listFiles()?.map { it.name.removeSuffix(".xml") }.orEmpty()
        return names.filter { isSettingsFile(it) }
    }

    private fun isSettingsFile(name: String) =
        name == GLOBAL || (name.contains('.') && !name.startsWith("akhil.alltrans") && !name.startsWith("androidx."))

    /** Mirror [name] to the framework whenever it changes in this process. */
    fun watch(name: String) {
        if (!isSettingsFile(name) || watched.containsKey(name)) return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> schedule(name) }
        if (watched.putIfAbsent(name, listener) == null) {
            appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(listener)
        }
    }

    /** Coalesce bursts of edits (a single screen often writes several keys). */
    private fun schedule(name: String) {
        if (!pending.add(name)) return
        main.postDelayed({
            pending.remove(name)
            Thread { sync(name) }.start()
        }, 300)
    }

    fun syncAll() {
        val files = settingFiles()
        files.forEach { watch(it) }
        var ok = 0
        for (name in files) if (sync(name)) ok++
        Log.i(TAG, "Synced $ok/${files.size} settings groups")
    }

    /** Replace remote group [name] with the content of the local file. */
    @Synchronized
    fun sync(name: String): Boolean {
        val s = service ?: return false
        if (!remoteSupported) return false
        return try {
            val local = appContext.getSharedPreferences(name, Context.MODE_PRIVATE).all
            val remote = s.getRemotePreferences(name)
            if (remote.all == local) return true
            val editor = remote.edit().clear()
            for ((key, value) in local) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.map { it.toString() }.toSet())
                }
            }
            editor.commit()
            // Plain Log: Utils/HookRuntime reference framework classes absent in the app process.
            Log.d(TAG, "Synced $name (${local.size} keys)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to sync $name", t)
            false
        }
    }
}
