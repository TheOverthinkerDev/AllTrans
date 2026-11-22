package akhil.alltrans

import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * "Translate inside the app" switch: turning it on first shows what it
 * changes (traffic attributed to the target app, its firewall/VPN rules,
 * a little more memory, security-sensitive apps may notice) and only applies
 * after the user confirms. Turning it off needs no confirmation.
 */
internal object InProcessWarning {
    fun attach(pref: SwitchPreference?) {
        pref ?: return
        pref.setOnPreferenceChangeListener { p, newValue ->
            if (newValue != true) return@setOnPreferenceChangeListener true
            MaterialAlertDialogBuilder(p.context)
                .setTitle(R.string.in_process_warning_title)
                .setMessage(R.string.in_process_warning_message)
                .setNegativeButton(R.string.in_process_warning_cancel, null)
                .setPositiveButton(R.string.in_process_warning_ok) { _, _ -> (p as SwitchPreference).isChecked = true }
                .show()
            false // applied by the dialog's positive button
        }
    }
}
