package akhil.alltrans

import android.os.SystemClock
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.TextView

/**
 * "Scroll long translations": a translated single-line TextView whose text got
 * cut off ("…" or clipped) slides back and forth instead.
 *
 * TextView's own marquee only runs while the view is focused or selected, and
 * changing those states would affect the app (clicks, focus, selector colors).
 * So the text is moved with scrollTo() on animation frames instead: the view's
 * focus, selection, clickability and touch handling are never touched, and the
 * original ellipsize / scrolling settings are restored as soon as the app sets
 * other text on the view.
 */
internal object MarqueeHelper {
    private const val TAG = "AllTrans:Marquee"
    private const val STATE_TAG_KEY = 0x7A117AAC

    const val PAUSE_MS = 1000L
    private const val DP_PER_SECOND = 40f

    private class State(val ellipsize: TextUtils.TruncateAt?, val horizontallyScrolling: Boolean) {
        var running = false
    }

    /** Call after AllTrans put a translation on [textView]. */
    fun onTranslated(textView: TextView) {
        if (!PreferenceList.Scroll || textView is EditText) return
        // The new text is laid out on the next pass.
        textView.post { start(textView) }
    }

    /** Call before any new text is set on [textView]: undoes the marquee. */
    fun reset(textView: TextView) {
        val state = textView.getTag(STATE_TAG_KEY) as? State ?: return
        state.running = false
        textView.setTag(STATE_TAG_KEY, null)
        textView.scrollTo(0, 0)
        textView.setHorizontallyScrolling(state.horizontallyScrolling)
        textView.ellipsize = state.ellipsize
    }

    private fun start(tv: TextView) {
        if (tv.width <= 0 || tv.getTag(STATE_TAG_KEY) != null) return
        val layout = tv.layout ?: return
        if (tv.lineCount != 1) return
        val available = tv.width - tv.compoundPaddingLeft - tv.compoundPaddingRight
        if (layout.getEllipsisCount(0) <= 0 && layout.getLineWidth(0) <= available + 1) return
        try {
            val state = State(tv.ellipsize, tv.isHorizontallyScrollable)
            tv.setTag(STATE_TAG_KEY, state)
            // Lay the line out at full length; the view keeps its size and clips.
            tv.ellipsize = null
            tv.setHorizontallyScrolling(true)
            state.running = true
            tv.post(object : Runnable {
                override fun run() {
                    if (!state.running || tv.getTag(STATE_TAG_KEY) !== state) return
                    if (!tv.isAttachedToWindow) {
                        reset(tv)
                        return
                    }
                    val line = tv.layout?.getLineWidth(0) ?: return
                    val distance = (line - (tv.width - tv.compoundPaddingLeft - tv.compoundPaddingRight)).toInt()
                    tv.scrollTo(offset(tv, distance).toInt(), 0)
                    tv.postOnAnimation(this)
                }
            })
            Utils.debugLog("$TAG: scrolling '${tv.text.toString().take(30)}'")
        } catch (t: Throwable) {
            Utils.debugLog("$TAG: ${t.message}")
        }
    }

    /** Offset at the current time: pause, slide left, pause, slide back. */
    fun offset(view: View, distance: Int): Float {
        if (distance <= 0) return 0f
        val pxPerMs = DP_PER_SECOND * view.resources.displayMetrics.density / 1000f
        val travel = (distance / pxPerMs).toLong().coerceAtLeast(1)
        val cycle = 2 * (PAUSE_MS + travel)
        val t = SystemClock.uptimeMillis() % cycle
        return when {
            t < PAUSE_MS -> 0f
            t < PAUSE_MS + travel -> (t - PAUSE_MS) * pxPerMs
            t < 2 * PAUSE_MS + travel -> distance.toFloat()
            else -> distance - (t - 2 * PAUSE_MS - travel) * pxPerMs
        }
    }
}
