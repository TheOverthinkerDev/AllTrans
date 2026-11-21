package akhil.alltrans

import android.content.Context
import android.graphics.Canvas
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import java.util.Collections
import java.util.WeakHashMap

/**
 * Hooks for Alibaba apps (Taobao and others built on the same frameworks).
 * Each part is installed only when the app ships the class it targets, and
 * only the display is changed, except MTOP which is opt-in (see MtopTranslator):
 *  - DinamicX components draw text with DXNativeFastText, which receives a
 *    prebuilt StaticLayout through setStaticLayout(); the translation is swapped
 *    in only while drawing.
 *  - Taobao's bottom tab bar draws its labels into the icon bitmaps; text-free
 *    "_i18n" icons plus the (translated) title TextView are used instead.
 */
internal object TaobaoHooks {
    private const val TAG = "AllTrans:Taobao"
    private const val DX_FAST_TEXT = "com.taobao.android.dinamicx.view.DXNativeFastText"
    private const val NAV_TAB = "com.taobao.tao.navigation.NavigationTabIndicatorView"
    private const val MIN_TEXT_SCALE = 0.75f

    @Volatile
    private var installed = false

    private val buildingLayout = ThreadLocal<Boolean>()

    /**
     * In Taobao the generic StaticLayout.Builder hook must stay out of the way:
     * it swapped translations into DinamicX builds after DinamicX had measured
     * the Chinese text, which left those views sized 0x0 (blank). DinamicX text
     * is handled by the setStaticLayout hook below and TextViews by setText().
     */
    @Volatile
    var genericStaticLayoutDisabled = false
        private set

    /** True while AllTrans itself builds a StaticLayout, or the generic hook is off. */
    fun isBuildingLayout(): Boolean = genericStaticLayoutDisabled || buildingLayout.get() == true

    fun install(context: Context) {
        if (installed) return
        installed = true
        val loader = context.classLoader
        if (hasClass(loader, DX_FAST_TEXT)) {
            genericStaticLayoutDisabled = true
            hookDxFastText(loader)
        }
        if (hasClass(loader, NAV_TAB)) hookNavigationTab(loader)
        // Changes app data: opt-in, and never in safe mode.
        if (PreferenceList.mtopTranslationAllowed) MtopTranslator.install(loader)
        Utils.debugLog("$TAG: installed for ${context.packageName} (safeMode=${PreferenceList.SafeMode}, mtop=${PreferenceList.mtopTranslationAllowed})")
    }

    private fun hasClass(loader: ClassLoader, name: String): Boolean =
        try { Class.forName(name, false, loader); true } catch (_: Throwable) { false }

    // ------------------------------------------------------------- Tab bar

    /**
     * The bottom tab bar shows bundled icons with the Chinese label drawn into
     * the bitmap (uik_nav_cart_normal.png = cart + "购物车") and hides its title
     * TextView (mShowTitle = false). Taobao also ships text-free "_i18n" icons
     * meant to be paired with that TextView. Switch to those and show the title,
     * whose text then goes through the normal setText() translation.
     */
    private fun hookNavigationTab(loader: ClassLoader) {
        try {
            val cls = Class.forName(NAV_TAB, false, loader)
            HookRuntime.hook(cls.getDeclaredMethod("loadImage"), object : SafeHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    val tab = param.thisObject as? View ?: return
                    if (Reflect.getObjectField(tab, "mIconSourceType")?.toString() != "DRAWABLE") return
                    for (field in arrayOf("mContentSelected", "mContentUnSelected")) {
                        val id = Reflect.getObjectField(tab, field) as? Int ?: continue
                        val textFree = textFreeIcon(tab, id)
                        if (textFree != 0) Reflect.setObjectField(tab, field, textFree)
                    }
                }
            })
            HookRuntime.hook(cls.getDeclaredMethod("updateTitleView"), object : SafeHook() {
                override fun beforeHookedMethod(param: HookParam) {
                    val tab = param.thisObject as? View ?: return
                    val id = Reflect.getObjectField(tab, "mContentUnSelected") as? Int ?: return
                    val icon = Reflect.getObjectField(tab, "mIconView") as? View
                    when {
                        isTextFreeIcon(tab, id) -> icon?.let { clipLabel(it, false) }
                        // No "_i18n" variant (e.g. uik_nav_message_*): hide the
                        // label row of the bitmap instead, so it is not shown twice.
                        hasBakedLabel(tab, id) -> icon?.let { clipLabel(it, true) }
                        else -> return
                    }
                    Reflect.setObjectField(tab, "mShowTitle", true)
                }

                override fun afterHookedMethod(param: HookParam) {
                    val title = Reflect.getObjectField(param.thisObject ?: return, "mTitleView") as? TextView ?: return
                    if (title.maxLines != 1) {
                        title.maxLines = 1
                        title.ellipsize = TextUtils.TruncateAt.END
                        // Translations are longer than 2-4 Chinese characters.
                        val max = title.textSize.toInt().coerceAtLeast(2)
                        title.setAutoSizeTextTypeUniformWithConfiguration(
                            (max * MIN_TEXT_SCALE * 0.8f).toInt().coerceAtLeast(1), max, 1, TypedValue.COMPLEX_UNIT_PX
                        )
                    }
                }
            })
            Utils.debugLog("$TAG: hooked $NAV_TAB#loadImage/updateTitleView")
        } catch (t: Throwable) {
            Utils.debugLog("$TAG: NavigationTabIndicatorView not hooked: ${t.message}")
        }
    }

    private val ICON_SUFFIX = Regex("_(ch|en|elder|ti|ru|thai|malay)$")

    /** Resource id of the "_i18n" (text-free) variant of a tab icon, or 0. */
    private fun textFreeIcon(view: View, id: Int): Int {
        return try {
            val res = view.resources
            val name = res.getResourceEntryName(id)
            if (name.endsWith("_i18n")) return 0
            res.getIdentifier(name.replace(ICON_SUFFIX, "") + "_i18n", "drawable", res.getResourcePackageName(id))
        } catch (_: Throwable) {
            0
        }
    }

    private fun isTextFreeIcon(view: View, id: Int): Boolean =
        try { view.resources.getResourceEntryName(id).endsWith("_i18n") } catch (_: Throwable) { false }

    /** Bundled tab icons (uik_nav_*) draw the label in the lower part of the bitmap. */
    private fun hasBakedLabel(view: View, id: Int): Boolean =
        try { view.resources.getResourceEntryName(id).startsWith("uik_nav_") } catch (_: Throwable) { false }

    /** Fraction of the icon height above the baked-in label (220px icons: label starts ~140px). */
    private const val ICON_FRACTION_ABOVE_LABEL = 0.62f

    private val clipListeners = Collections.synchronizedMap(WeakHashMap<View, View.OnLayoutChangeListener>())

    private fun clipLabel(icon: View, clip: Boolean) {
        clipListeners.remove(icon)?.let { icon.removeOnLayoutChangeListener(it) }
        if (!clip) {
            icon.clipBounds = null
            return
        }
        val apply = { icon.clipBounds = android.graphics.Rect(0, 0, icon.width, (icon.height * ICON_FRACTION_ABOVE_LABEL).toInt()) }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply() }
        clipListeners[icon] = listener
        icon.addOnLayoutChangeListener(listener)
        if (icon.width > 0) apply()
    }

    // ---------------------------------------------------------------- DinamicX

    /**
     * Original DinamicX layout -> layout built from its translation.
     *
     * The view's own staticLayout field is never replaced: DinamicX reads it
     * back (state saving, re-measuring), and when it found translated text there
     * it re-laid the item out around the longer string and sized the view 0x0.
     * The translation is swapped in only for the duration of onDraw().
     */
    private val dxTranslations: MutableMap<StaticLayout, DxTranslated> =
        Collections.synchronizedMap(WeakHashMap())

    /**
     * [fitted]: translation fitted into the original box (shrunk, then "…").
     * [marquee]: single unbroken line, set when "scroll long translations" is on
     * and the one-line translation still does not fit; drawn sliding back and forth.
     */
    private class DxTranslated(val fitted: StaticLayout, val marquee: StaticLayout?, val boxWidth: Int)

    private class DxCall(val view: View, val original: StaticLayout)

    // ------------------------------------------- Other self-drawing text views

    /**
     * Custom views (outside Alibaba apps too) that draw a StaticLayout field in
     * onDraw() and set their text through an obfuscated method, e.g. Douyin's
     * IMLightTextView in the message list. Translated at draw time with the
     * same display-only swap as DinamicX: the view's field is never changed.
     */
    private val LAYOUT_FIELD_VIEWS = listOf(
        "com.ss.android.ugc.aweme.im.widget.IMLightTextView" to "staticLayout",
    )

    private val layoutRequests: MutableSet<StaticLayout> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    @Volatile
    private var layoutFieldViewsInstalled = false

    fun installLayoutFieldViews(context: Context) {
        if (layoutFieldViewsInstalled) return
        layoutFieldViewsInstalled = true
        val loader = context.classLoader
        for ((className, fieldName) in LAYOUT_FIELD_VIEWS) {
            try {
                val cls = Class.forName(className, false, loader)
                val field = cls.getDeclaredField(fieldName).apply { isAccessible = true }
                HookRuntime.hook(cls.getDeclaredMethod("onDraw", Canvas::class.java), DxOnDrawHook(field, ::translateAtDraw))
                Utils.debugLog("$TAG: hooked $className#onDraw ($fieldName)")
            } catch (_: ClassNotFoundException) {
            } catch (t: Throwable) {
                Utils.debugLog("$TAG: $className not hooked: ${t.message}")
            }
        }
    }

    /** Cached translation now, or request one and redraw the view when it arrives. */
    private fun translateAtDraw(view: View, layout: StaticLayout): DxTranslated? {
        val text = layout.text?.toString() ?: return null
        if (!SetTextHookHandler.isNotWhiteSpace(text) || SetTextHookHandler.shouldSkipTranslation(text)) return null
        val cached = cachedTranslation(text)
        if (cached != null) {
            if (cached == text) return null
            return rebuild(layout, cached).also { dxTranslations[layout] = it }
        }
        if (!layoutRequests.add(layout)) return null
        val getTranslate = GetTranslate().apply {
            stringToBeTrans = text
            userData = DxCall(view, layout)
            originalCallable = DrawTimeCallback
            canCallOriginal = true
            pendingCompositeKey = 31 * System.identityHashCode(layout) + text.hashCode()
            forceCacheResult = true
        }
        GetTranslateToken().apply { this.getTranslate = getTranslate }.doAll()
        return null
    }

    private object DrawTimeCallback : OriginalCallable {
        override fun callOriginalMethod(translatedString: CharSequence?, userData: Any?) {
            val call = userData as? DxCall ?: return
            val translated = translatedString?.toString().orEmpty()
            if (translated.isEmpty() || translated == call.original.text?.toString()) return
            dxTranslations[call.original] = rebuild(call.original, translated)
            call.view.invalidate()
        }
    }

    private fun hookDxFastText(loader: ClassLoader) {
        try {
            val cls = Class.forName(DX_FAST_TEXT, false, loader)
            val field = cls.getDeclaredField("staticLayout").apply { isAccessible = true }
            val setter = cls.getDeclaredMethod("setStaticLayout", StaticLayout::class.java)
            val onDraw = cls.getDeclaredMethod("onDraw", Canvas::class.java)
            HookRuntime.hook(setter, DxSetStaticLayoutHook())
            HookRuntime.hook(onDraw, DxOnDrawHook(field))
            Utils.debugLog("$TAG: hooked $DX_FAST_TEXT#setStaticLayout/onDraw")
        } catch (t: Throwable) {
            Utils.debugLog("$TAG: DXNativeFastText not hooked: ${t.message}")
        }
    }

    /** Prepares a translated layout for every layout DinamicX hands the view. */
    private class DxSetStaticLayoutHook : SafeHook(), OriginalCallable {
        override fun beforeHookedMethod(param: HookParam) {
            val view = param.thisObject as? View ?: return
            val layout = param.args.firstOrNull() as? StaticLayout ?: return
            if (dxTranslations.containsKey(layout)) return

            val text = layout.text?.toString() ?: return
            if (!SetTextHookHandler.isNotWhiteSpace(text) || SetTextHookHandler.shouldSkipTranslation(text)) return
            if (!PreferenceManager.isEnabledForPackage(view.context, view.context?.packageName)) return

            val cached = cachedTranslation(text)
            if (cached != null) {
                if (cached != text) dxTranslations[layout] = rebuild(layout, cached)
                return
            }

            val key = 31 * System.identityHashCode(layout) + text.hashCode()
            synchronized(Alltrans.pendingTextViewTranslations) {
                if (!Alltrans.pendingTextViewTranslations.add(key)) return
            }
            val getTranslate = GetTranslate().apply {
                stringToBeTrans = text
                userData = DxCall(view, layout)
                originalCallable = this@DxSetStaticLayoutHook
                canCallOriginal = true
                pendingCompositeKey = key
                forceCacheResult = true
            }
            GetTranslateToken().apply { this.getTranslate = getTranslate }.doAll()
        }

        // Runs on the main thread once the translation arrives.
        override fun callOriginalMethod(translatedString: CharSequence?, userData: Any?) {
            val call = userData as? DxCall ?: return
            val translated = translatedString?.toString().orEmpty()
            val original = call.original.text?.toString().orEmpty()
            if (translated.isEmpty() || translated == original) return
            try {
                dxTranslations[call.original] = rebuild(call.original, translated)
                // Redraw; if the view was recycled for another item, its current
                // layout has no entry yet and it simply draws that layout.
                call.view.invalidate()
            } catch (t: Throwable) {
                Utils.debugLog("$TAG: failed to prepare DX translation: ${t.message}")
            }
        }
    }

    /**
     * Draws the translated layout in place of the original one.
     * [onMiss] (optional) is asked for a translation when none is known yet;
     * views without a hookable text setter use it to translate at draw time.
     */
    private class DxOnDrawHook(
        private val field: java.lang.reflect.Field,
        private val onMiss: ((View, StaticLayout) -> DxTranslated?)? = null,
    ) : SafeHook() {
        private val swapped = ThreadLocal<StaticLayout?>()
        private val canvasSaved = ThreadLocal<Boolean>()

        override fun beforeHookedMethod(param: HookParam) {
            val view = param.thisObject as? View ?: return
            val original = field.get(view) as? StaticLayout ?: return
            val translated = dxTranslations[original] ?: onMiss?.invoke(view, original) ?: return
            val canvas = param.args.firstOrNull() as? Canvas
            val marquee = translated.marquee
            if (marquee != null && canvas != null && view.width > 0) {
                // Clip to the view and slide the unbroken line back and forth.
                canvas.save()
                canvas.clipRect(0, 0, view.width, view.height)
                canvas.translate(-MarqueeHelper.offset(view, marquee.width - translated.boxWidth), 0f)
                canvasSaved.set(true)
                field.set(view, marquee)
            } else {
                field.set(view, translated.fitted)
            }
            swapped.set(original)
        }

        override fun afterHookedMethod(param: HookParam) {
            val original = swapped.get() ?: return
            swapped.remove()
            val view = param.thisObject as? View ?: return
            field.set(view, original)
            if (canvasSaved.get() == true) {
                canvasSaved.remove()
                (param.args.firstOrNull() as? Canvas)?.restore()
                // Next frame only while the view keeps being drawn.
                view.postInvalidateOnAnimation()
            }
        }
    }


    /**
     * Builds a layout for [text] with the same paint, width, alignment and
     * spacing as [source]. Translations are often longer, so keep the original
     * line count and ellipsize instead of overflowing the fixed-size view.
     */
    private fun rebuild(source: StaticLayout, text: String): DxTranslated {
        buildingLayout.set(true)
        try {
            val includePad = source.topPadding != 0 || source.bottomPadding != 0
            val width = source.width.coerceAtLeast(1)
            val lines = source.lineCount.coerceAtLeast(1)
            val styled = copyFullSpans(source.text, text)

            // Shrink the font (down to 75%) when the translation does not fit
            // the space the original text had, before falling back to "…".
            val paint = TextPaint(source.paint)
            val needed = paint.measureText(text)
            val available = width.toFloat() * lines
            if (needed > available) {
                paint.textSize = paint.textSize * (available / needed).coerceAtLeast(MIN_TEXT_SCALE)
            }

            val layout = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, width)
                .setAlignment(source.alignment)
                .setLineSpacing(source.spacingAdd, source.spacingMultiplier)
                .setIncludePad(includePad)
                .setMaxLines(lines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setEllipsizedWidth(width)
                .build()
            if (Utils.Debug) {
                val spans = (source.text as? Spanned)?.let { s ->
                    s.getSpans(0, s.length, Any::class.java).joinToString(",") {
                        "${it.javaClass.simpleName}[${s.getSpanStart(it)}-${s.getSpanEnd(it)}]"
                    }
                }
                Utils.debugLog(
                    "$TAG: rebuilt '${source.text}' -> '$text' w=$width lines=$lines " +
                        "h=${source.height}->${layout.height} color=${Integer.toHexString(source.paint.color)} " +
                        "shader=${source.paint.shader?.javaClass?.simpleName} spans=$spans " +
                        "textClass=${source.text?.javaClass?.simpleName}"
                )
            }
            // One-line text that still does not fit: also keep an unbroken
            // version to scroll, if the user enabled scrolling.
            val marquee = if (PreferenceList.Scroll && lines == 1 && paint.measureText(text) > width) {
                val full = kotlin.math.ceil(paint.measureText(text).toDouble()).toInt() + 1
                StaticLayout.Builder.obtain(styled, 0, styled.length, paint, full)
                    .setLineSpacing(source.spacingAdd, source.spacingMultiplier)
                    .setIncludePad(includePad)
                    .setMaxLines(1)
                    .build()
            } else null
            return DxTranslated(layout, marquee, width)
        } finally {
            buildingLayout.remove()
        }
    }

    /**
     * Carries over spans that cover the whole original text (DinamicX sets the
     * text colour and size this way; without them the text was drawn invisibly).
     * Partial spans cannot be mapped onto a translation and are dropped.
     */
    private fun copyFullSpans(original: CharSequence?, translated: String): CharSequence {
        if (original !is Spanned || original.isEmpty()) return translated
        val result = SpannableString(translated)
        for (span in original.getSpans(0, original.length, Any::class.java)) {
            val start = original.getSpanStart(span)
            val end = original.getSpanEnd(span)
            if (start <= 0 && end >= original.length) {
                result.setSpan(span, 0, translated.length, original.getSpanFlags(span))
            }
        }
        return result
    }

    // ---------------------------------------------------------------- Cache

    private fun cachedTranslation(text: String): String? {
        if (!PreferenceList.Caching) return null
        Alltrans.cacheAccess.acquireUninterruptibly()
        return try {
            when (val cached = Alltrans.cache?.get(text)) {
                null -> null
                SetTextHookHandler.NO_TRANSLATION_MARKER -> text
                else -> cached
            }
        } finally {
            if (Alltrans.cacheAccess.availablePermits() == 0) Alltrans.cacheAccess.release()
        }
    }
}
