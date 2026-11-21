package akhil.alltrans

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

/**
 * Settings of the AllTrans app itself (not of translation). Kept in their own
 * SharedPreferences file so they are never sent to target apps.
 */
internal object AppSettings {
    const val PREFS = "AllTransApp"
    const val KEY_THEME = "Theme"
    const val KEY_DYNAMIC_COLOR = "DynamicColor"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Light / dark / follow system. Call before Activity.onCreate. */
    fun applyTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (prefs(context).getString(KEY_THEME, "system")) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /** Material You colours from the wallpaper (Android 12+), if enabled. */
    fun applyDynamicColor(activity: Activity) {
        if (prefs(activity).getBoolean(KEY_DYNAMIC_COLOR, false)) {
            DynamicColors.applyToActivityIfAvailable(activity)
        }
    }
}
