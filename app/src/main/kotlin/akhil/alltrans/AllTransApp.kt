package akhil.alltrans

import android.app.Application
import android.content.Context

/** Starts the libxposed service connection as early as possible. */
class AllTransApp : Application() {
    override fun onCreate() {
        super.onCreate()
        migrateAlibabaDefaults()
        RemotePrefs.init(this)
    }

    /**
     * Older builds stored the Alibaba switches' default (optimise = on) in the
     * settings of every app whose page was opened. Those were never user
     * choices: drop them for non-Alibaba apps so the option is off there.
     */
    private fun migrateAlibabaDefaults() {
        val state = getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
        if (state.getBoolean(KEY_MIGRATED, false)) return
        val dir = java.io.File(applicationInfo.dataDir, "shared_prefs")
        dir.listFiles()?.map { it.name.removeSuffix(".xml") }
            ?.filter { it.contains('.') && !it.startsWith("akhil.alltrans") && !AppConfig.isAlibabaApp(it) }
            ?.forEach { pkg ->
                getSharedPreferences(pkg, Context.MODE_PRIVATE).edit()
                    .remove("AlibabaOptimize").remove("SafeMode").remove("MtopTranslate").commit()
            }
        state.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    private companion object {
        const val KEY_MIGRATED = "MigratedAlibabaDefaults"
    }
}
