package akhil.alltrans

import android.app.Application

/** Starts the libxposed service connection as early as possible. */
class AllTransApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RemotePrefs.init(this)
    }
}
