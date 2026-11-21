package akhil.alltrans

import android.content.Context
import android.util.AttributeSet
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreference

/**
 * Preferences that can be "locked": shown greyed out but still tappable, so a
 * tap explains why they cannot be used right now (a disabled preference would
 * swallow the tap silently).
 */
private const val LOCKED_ALPHA = 0.38f

class LockableSwitchPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwitchPreference(context, attrs) {
    /** Non-null = locked; shown as a toast when tapped. */
    var lockedMessage: String? = null
        set(value) {
            field = value
            notifyChanged()
        }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.alpha = if (lockedMessage != null) LOCKED_ALPHA else 1f
    }

    override fun onClick() {
        lockedMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            return
        }
        super.onClick()
    }
}

class LockableListPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ListPreference(context, attrs) {
    var lockedMessage: String? = null
        set(value) {
            field = value
            notifyChanged()
        }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.alpha = if (lockedMessage != null) LOCKED_ALPHA else 1f
    }

    override fun onClick() {
        lockedMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            return
        }
        super.onClick()
    }
}
