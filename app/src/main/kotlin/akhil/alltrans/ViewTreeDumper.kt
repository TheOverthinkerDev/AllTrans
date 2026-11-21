package akhil.alltrans

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.reflect.Modifier

/**
 * Debug-only helper: logs the view tree of a resumed Activity, including text
 * held in fields of custom views that draw text themselves. Used to find which
 * views an app renders text with when setText() hooks do not reach them.
 *
 * Output: adb logcat -s AllTransViewDump
 */
internal object ViewTreeDumper {
    private const val TAG = "AllTransViewDump"
    private const val DELAY_MS = 6000L
    private const val MAX_NODES = 2500

    private val handler = Handler(Looper.getMainLooper())

    private const val REPEAT_MS = 15000L
    private val RENDER_HOSTS = Regex("(?i)webview|weex|unicorn|\\bmus|surfaceview|textureview|flutter|lottie|canvas")

    fun scheduleDump(activity: Activity) {
        if (!Utils.Debug) return
        android.util.Log.i(TAG, "resumed ${activity.javaClass.name}, dumping in ${DELAY_MS}ms")
        scheduleRepeating(activity, DELAY_MS)
    }

    // Tab switches inside one Activity do not resume it, so keep dumping while
    // it stays in front (Debug mode only).
    private fun scheduleRepeating(activity: Activity, delay: Long) {
        handler.postDelayed({
            if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) return@postDelayed
            dumpNow(activity)
            scheduleRepeating(activity, REPEAT_MS)
        }, delay)
    }

    private fun dumpNow(activity: Activity) {
        val decor = activity.window?.peekDecorView() ?: return
        android.util.Log.i(TAG, "=== ${activity.javaClass.name} ===")
        dump(decor, 0, intArrayOf(0))
    }

    private fun dump(view: View, depth: Int, counter: IntArray, verbose: Boolean = false) {
        if (counter[0]++ >= MAX_NODES || view.visibility != View.VISIBLE) return
        val texts = describeText(view)
        // Inside views that hold text but render it elsewhere, log every child.
        val verboseChildren = verbose || (texts.isNotEmpty() && view is ViewGroup && view !is TextView &&
            view.javaClass.name.contains("Navigation"))
        // Rendering surfaces whose text never reaches Android views.
        if (texts.isEmpty() && RENDER_HOSTS.containsMatchIn(classChain(view.javaClass))) {
            val loc = IntArray(2).also { view.getLocationOnScreen(it) }
            android.util.Log.i(TAG, "d=$depth HOST ${classChain(view.javaClass)} @${loc[0]},${loc[1]} ${view.width}x${view.height}")
        }
        if (verbose && texts.isEmpty()) {
            android.util.Log.i(TAG, "d=$depth ${classChain(view.javaClass)} ${view.width}x${view.height} (child)")
        }
        if (texts.isNotEmpty() && view !is TextView) logMethodsOnce(view.javaClass)
        if (texts.isNotEmpty()) {
            val loc = IntArray(2).also { view.getLocationOnScreen(it) }
            android.util.Log.i(TAG, "d=$depth ${classChain(view.javaClass)} @${loc[0]},${loc[1]} ${view.width}x${view.height} $texts")
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) view.getChildAt(i)?.let { dump(it, depth + 1, counter, verboseChildren) }
        }
    }

    private val loggedClasses = HashSet<String>()

    /** Logs the declared methods of a custom text-holding view class once. */
    private fun logMethodsOnce(cls: Class<*>) {
        var c: Class<*>? = cls
        while (c != null && c != View::class.java && !c.name.startsWith("android.")) {
            if (loggedClasses.add(c.name)) {
                val methods = c.declaredMethods.joinToString("; ") { m ->
                    "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }}):${m.returnType.simpleName}"
                }
                methods.chunked(3500).forEachIndexed { i, chunk ->
                    android.util.Log.i(TAG, "methods ${c!!.name} [$i]: $chunk")
                }
            }
            c = c.superclass
        }
    }

    private fun classChain(cls: Class<*>): String {
        val names = ArrayList<String>()
        var c: Class<*>? = cls
        while (c != null && c != View::class.java && names.size < 4) {
            names.add(c.name)
            if (c.name.startsWith("android.")) break
            c = c.superclass
        }
        return names.joinToString(" < ")
    }

    private fun describeText(view: View): String {
        val parts = ArrayList<String>()
        if (view is TextView) {
            view.text?.takeIf { it.isNotBlank() }?.let { parts.add("text='${it.toString().take(30)}'") }
            view.hint?.takeIf { it.isNotBlank() }?.let { parts.add("hint='${it.toString().take(30)}'") }
        } else {
            // Custom views: look for CharSequence and Layout fields up the class chain.
            var c: Class<*>? = view.javaClass
            while (c != null && c != View::class.java && !c.name.startsWith("android.")) {
                for (f in c.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    val isText = CharSequence::class.java.isAssignableFrom(f.type)
                    val isLayout = Layout::class.java.isAssignableFrom(f.type)
                    if (!isText && !isLayout) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(view) ?: continue
                        val s = if (v is Layout) v.text?.toString() else v.toString()
                        if (!s.isNullOrBlank()) parts.add("${c.simpleName}.${f.name}:${f.type.simpleName}='${s.take(30)}'")
                    } catch (_: Throwable) {
                    }
                }
                c = c.superclass
            }
        }
        view.contentDescription?.takeIf { it.isNotBlank() }?.let { parts.add("desc='${it.toString().take(30)}'") }
        return parts.joinToString(" ")
    }
}
