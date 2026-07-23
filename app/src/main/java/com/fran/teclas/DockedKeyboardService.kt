package com.fran.teclas

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.StateListDrawable

class DockedKeyboardService : Service() {
    private var windowManager: WindowManager? = null
    private var deckView: View? = null
    private var keyboardDeck: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var shifted = false
    private var overlayVisible = true
    private var lastBuiltMode: NeuMode? = null
    private var swapMode = false
    private var swapPreviewTheme: String? = null
    private var swapDownX = 0f
    private var swapDownY = 0f
    private var swapHasDown = false
    private var themeDotsView: LinearLayout? = null
    private val hapticEngine by lazy { com.fran.teclas.keyboard.CustomHapticEngine(this) }

    // ── Interactive slide-to-reveal for the docked overlay (flag-gated) ──
    // Collapsed = a thin search-bar handle peeking at the screen bottom; drag it up to grow the
    // overlay window into the full keyboard, drag the grab pill down to tuck it back. The window
    // resizes in one piece so the gesture stays in a single window; the app above stays usable when
    // collapsed. All paths gate on slideEnabled() so classic docked mode is untouched when off.
    private var slideExpanded = false
    private var slideHeightPx = 0
    private var slideDragging = false
    private var slideDownRawY = 0f
    private var slideDragStartHeight = 0
    private var slideLastRawY = 0f
    private var slideLastT = 0L
    private var slideVelPxPerMs = 0f
    private var slideHandleView: View? = null
    private var slideCollapseGrabView: View? = null
    private var slideSettleAnim: android.animation.ValueAnimator? = null

    // ── Text intelligence for typing INTO third-party apps (this overlay was a raw key-sender). ──
    @Volatile private var predictor: com.fran.teclas.keyboard.PredictionEngine? = null
    private val fgDraft = StringBuilder()                 // mirrors what we've injected into the app's field
    private var suggestionStrip: LinearLayout? = null
    private var lastCorrection: Pair<String, String>? = null   // typed -> corrected, armed for backspace-undo
    private val bgHandler = Handler(Looper.getMainLooper())

    private fun loadPredictor() {
        if (predictor != null) return
        Thread {
            runCatching {
                val loaded = com.fran.teclas.keyboard.DictionaryLoader.load(this)
                val engine = com.fran.teclas.keyboard.PredictionEngine(loaded.freqs)
                bgHandler.post { predictor = engine; refreshSuggestions() }
            }.onFailure { android.util.Log.w("TeclasDiag", "docked predictor load failed: ${it.message}") }
        }.start()
    }

    private val overlayVisibilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != InputInjectionService.ACTION_SET_DOCKED_OVERLAY_VISIBLE) return
            setOverlayVisible(intent.getBooleanExtra(InputInjectionService.EXTRA_VISIBLE, true))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!KeyboardSettings.isDocked(this) || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        registerOverlayVisibilityReceiver()
        VivoDockedExperiment.applyViewportTruncation(this)
        loadPredictor()
        showDeck()
    }

    private fun registerOverlayVisibilityReceiver() {
        val filter = IntentFilter(InputInjectionService.ACTION_SET_DOCKED_OVERLAY_VISIBLE)
        androidx.core.content.ContextCompat.registerReceiver(
            this, overlayVisibilityReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun showDeck() {
        if (deckView != null) return
        lastBuiltMode = selectedNeuTokens().mode
        val deck = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), keyboardTopPadding(), dp(10), keyboardBottomPadding())
            background = deckBackground()
        }
        keyboardDeck = deck
        populateKeyboardDeck(deck)
        val root = FrameLayout(this).apply {
            addView(deck, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                overlayKeyboardHeight(),
                Gravity.BOTTOM
            ))
            themeDotsView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                alpha = 0f
                visibility = View.GONE
            }
            addView(themeDotsView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(24),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(9)
            })
            addView(freeformRestoreButton(), FrameLayout.LayoutParams(dp(44), dp(30), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(9)
                rightMargin = dp(16)
            })
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayKeyboardHeight(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = DockedKeyboardMetrics.overlayBottomLiftPx(this@DockedKeyboardService)
        }
        windowManager?.addView(root, lp)
        overlayParams = lp
        deckView = root
        // Slide handle (peek) + collapse grab live in the same window as the deck, so a drag never
        // crosses a window boundary. Built regardless of the flag (cheap, hidden) so toggling the
        // pref on takes effect without a rebuild; behaviour is gated by slideEnabled().
        slideHandleView = buildSlideHandle().also { handle ->
            root.addView(handle, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, slidePeekHeight(), Gravity.BOTTOM).apply {
                leftMargin = dp(8); rightMargin = dp(8); bottomMargin = dp(6)
            })
        }
        slideCollapseGrabView = buildSlideCollapseGrab().also { grab ->
            root.addView(grab, FrameLayout.LayoutParams(dp(72), dp(18), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(6)
            })
        }
        if (slideEnabled()) {
            slideExpanded = false
            driveDockedSlide(slidePeekHeight())
        } else {
            slideHandleView?.visibility = View.GONE
            slideCollapseGrabView?.visibility = View.GONE
        }
        updateSwapLayout(animate = false)
    }

    private fun populateKeyboardDeck(deck: LinearLayout) {
        deck.removeAllViews()
        // Suggestion strip above the keys (typing into the foreground app).
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        suggestionStrip = strip
        deck.addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        refreshSuggestions()
        listOf(
            "qwertyuiop".map { it.toString() },
            "asdfghjkl".map { it.toString() },
            listOf("shift") + "zxcvbnm".map { it.toString() } + listOf("back"),
            listOf("123", "teclas", "space", ".", "enter")
        ).forEachIndexed { rowIndex, row ->
            deck.addView(keyRow(row, rowIndex), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                keyRowHeight()
            ).apply {
                if (rowIndex > 0) topMargin = -keyRowOverlap()
            })
        }
    }

    private fun dockedSwapLiftPx(): Int = dp(132)

    private fun dockedSwapOverlayHeight(): Int =
        overlayKeyboardHeight() + dockedSwapLiftPx() + dp(18)

    private fun updateSwapLayout(animate: Boolean) {
        val params = overlayParams ?: return
        val root = deckView ?: return
        val deck = keyboardDeck ?: return
        val targetHeight = if (swapMode) dockedSwapOverlayHeight() else overlayKeyboardHeight()
        if (params.height != targetHeight) {
            params.height = targetHeight
            runCatching { windowManager?.updateViewLayout(root, params) }
        }
        val targetY = if (swapMode) -dockedSwapLiftPx().toFloat() else 0f
        deck.animate().cancel()
        if (animate) {
            deck.animate()
                .translationY(targetY)
                .scaleX(if (swapMode) 0.962f else 1f)
                .scaleY(if (swapMode) 0.952f else 1f)
                .rotationX(if (swapMode) 12f else 0f)
                .setDuration(if (swapMode) 260L else 180L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.7f))
                .start()
        } else {
            deck.translationY = targetY
            deck.scaleX = if (swapMode) 0.962f else 1f
            deck.scaleY = if (swapMode) 0.952f else 1f
            deck.rotationX = if (swapMode) 12f else 0f
        }
        themeDotsView?.apply {
            if (swapMode) {
                updateDockedThemeDots()
                visibility = View.VISIBLE
                animate().alpha(1f).setDuration(140L).start()
            } else {
                animate().alpha(0f).setDuration(120L).withEndAction { visibility = View.GONE }.start()
            }
        }
    }

    private fun enterDockedKeyboardSwap(anchor: View) {
        if (swapMode) return
        swapMode = true
        swapPreviewTheme = keyboardThemeFromPrefs()
        swapHasDown = false
        anchor.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        keyboardDeck?.cameraDistance = 12000f
        updateSwapLayout(animate = true)
    }

    private fun seatDockedKeyboardSwap(anchor: View? = keyboardDeck) {
        val selected = swapPreviewTheme ?: keyboardThemeFromPrefs()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEYBOARD_THEME_PREF, selected)
            .apply()
        swapMode = false
        swapPreviewTheme = null
        swapHasDown = false
        keyboardDeck?.let { deck ->
            populateKeyboardDeck(deck)
        }
        updateSwapLayout(animate = true)
        Handler(Looper.getMainLooper()).postDelayed({
            playDockedSeatFeedback(anchor ?: keyboardDeck)
        }, 190L)
    }

    private fun playDockedSeatFeedback(anchor: View?) {
        hapticEngine.dockSeatSnap()
        anchor?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        (deckView as? ViewGroup)?.let { KeyboardDockRippleView.play(it, goKeyColor()) }
    }

    private fun previewDockedKeyboardTheme(theme: String, fromLeft: Boolean?) {
        if (!swapMode || theme == swapPreviewTheme) {
            updateDockedThemeDots()
            return
        }
        val deck = keyboardDeck ?: return
        swapPreviewTheme = theme
        deck.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        updateDockedThemeDots()
        val outX = if (fromLeft == true) dp(38).toFloat() else -dp(38).toFloat()
        val inX = -outX
        deck.animate().cancel()
        deck.animate()
            .alpha(0.22f)
            .translationX(outX)
            .setDuration(90L)
            .withEndAction {
                populateKeyboardDeck(deck)
                deck.alpha = 0.22f
                deck.translationX = inX
                deck.translationY = -dockedSwapLiftPx().toFloat()
                deck.scaleX = 0.962f
                deck.scaleY = 0.952f
                deck.rotationX = 12f
                deck.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(180L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.9f))
                    .start()
            }
            .start()
    }

    private fun handleDockedKeyboardSwapTouch(event: MotionEvent): Boolean {
        if (!swapMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swapDownX = event.rawX
                swapDownY = event.rawY
                swapHasDown = true
                keyboardDeck?.animate()?.cancel()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swapHasDown) return true
                val dx = event.rawX - swapDownX
                keyboardDeck?.translationX = dx * 0.22f
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!swapHasDown) return true
                swapHasDown = false
                val dx = event.rawX - swapDownX
                val dy = event.rawY - swapDownY
                val absDx = kotlin.math.abs(dx)
                val absDy = kotlin.math.abs(dy)
                return when {
                    absDx > dp(42) && absDx > absDy -> {
                        val themes = dockedSwapThemes
                        val current = themes.indexOf(swapPreviewTheme ?: keyboardThemeFromPrefs()).coerceAtLeast(0)
                        val next = if (dx < 0f) (current + 1) % themes.size else (current - 1 + themes.size) % themes.size
                        previewDockedKeyboardTheme(themes[next], fromLeft = dx > 0f)
                        true
                    }
                    absDx < dp(10) && absDy < dp(10) -> {
                        seatDockedKeyboardSwap()
                        true
                    }
                    else -> {
                        keyboardDeck?.animate()
                            ?.translationX(0f)
                            ?.setDuration(120L)
                            ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                            ?.start()
                        true
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                swapHasDown = false
                keyboardDeck?.animate()?.translationX(0f)?.setDuration(100L)?.start()
                return true
            }
        }
        return true
    }

    private fun updateDockedThemeDots() {
        val dots = themeDotsView ?: return
        dots.removeAllViews()
        val active = swapPreviewTheme ?: keyboardThemeFromPrefs()
        dockedSwapThemes.forEach { theme ->
            val selected = theme == active
            dots.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(5).toFloat()
                    setColor(if (selected) 0xFF7AF0A0.toInt() else 0x66727782)
                }
                setOnClickListener {
                    if (swapMode) previewDockedKeyboardTheme(theme, fromLeft = null)
                }
            }, LinearLayout.LayoutParams(if (selected) dp(22) else dp(9), dp(9)).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            })
        }
    }

    private val dockedSwapThemes: List<String>
        get() = listOf(
            KEYBOARD_THEME_DEFAULT,
            KEYBOARD_THEME_TECLAS,
            KEYBOARD_THEME_SKEUO,
            KEYBOARD_THEME_GOKEYS,
            KEYBOARD_THEME_HYPER3D,
            KEYBOARD_THEME_HYPER3D_BLACK,
            KEYBOARD_THEME_HYPER3D_LIGHT,
            KEYBOARD_THEME_BRUSHED,
            KEYBOARD_THEME_SEEME
        ) + KeyboardThemeDrawables.cycleThemes

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (themeMode() != THEME_MODE_SYSTEM || selectedNeuTokens().mode == lastBuiltMode) return
        val wasVisible = overlayVisible
        deckView?.let { view -> runCatching { windowManager?.removeView(view) } }
        deckView = null
        keyboardDeck = null
        overlayParams = null
        overlayVisible = true
        showDeck()
        if (!wasVisible) setOverlayVisible(false)
    }

    private fun setOverlayVisible(visible: Boolean) {
        if (overlayVisible == visible) return
        overlayVisible = visible
        val view = deckView ?: return
        val params = overlayParams ?: return
        view.visibility = if (visible) View.VISIBLE else View.GONE
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        params.flags = if (visible) {
            baseFlags
        } else {
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager?.updateViewLayout(view, params) }
        if (visible && slideEnabled()) {
            driveDockedSlide(if (slideExpanded) slideFullHeight() else slidePeekHeight())
        }
        // Auto-focus (opt-in): focus the foreground field (or open its search) via the accessibility
        // service so you can just start typing without reaching up to tap it. In classic docked mode
        // this fires when the deck appears; with slide on it fires on expand (see finishDockedSlide).
        if (visible && slideAutoFocusEnabled() && !slideEnabled()) {
            sendBroadcast(Intent(InputInjectionService.ACTION_PREPARE_FIELD).apply { setPackage(packageName) })
        }
    }

    private fun slideAutoFocusEnabled(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("slide_keyboard_autofocus", false)

    private fun slideEnabled(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("slide_keyboard_interactive", false)

    private fun slidePeekHeight(): Int = dp(56)
    private fun slideFullHeight(): Int = overlayKeyboardHeight()

    private fun buildSlideHandle(): View {
        val dark = selectedNeuTokens().mode == NeuMode.DARK
        return FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (dark) 0xFF181B21.toInt() else 0xFFE9ECF1.toInt())
                setStroke(dp(1), if (dark) 0x22FFFFFF else 0x33000000)
            }
            addView(TextView(context).apply {
                text = "Search  ·  slide up to type"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (dark) 0xFF9AA1AB.toInt() else 0xFF6F7884.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(View(context).apply {
                background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(goKeyColor()) }
            }, FrameLayout.LayoutParams(dp(34), dp(4), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(5) })
            isClickable = true
            setOnTouchListener { _, e -> if (slideEnabled()) handleDockedSlideDrag(e) else false }
        }
    }

    private fun buildSlideCollapseGrab(): View {
        return View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(goKeyColor()) }
            alpha = 0f
            visibility = View.GONE
            isClickable = true
            setOnTouchListener { _, e -> if (slideEnabled()) handleDockedSlideDrag(e) else false }
        }
    }

    private fun driveDockedSlide(h: Int) {
        val peek = slidePeekHeight()
        val full = slideFullHeight()
        val clamped = h.coerceIn(peek, full)
        slideHeightPx = clamped
        val params = overlayParams ?: return
        val root = deckView ?: return
        if (params.height != clamped) {
            params.height = clamped
            runCatching { windowManager?.updateViewLayout(root, params) }
        }
        val p = (clamped - peek).toFloat() / (full - peek).coerceAtLeast(1)
        slideHandleView?.apply {
            val a = (1f - p * 2f).coerceIn(0f, 1f)
            alpha = a
            visibility = if (a <= 0.02f) View.GONE else View.VISIBLE
        }
        slideCollapseGrabView?.apply {
            val a = ((p - 0.5f) * 2.4f).coerceIn(0f, 1f)
            alpha = a
            visibility = if (a <= 0.02f) View.GONE else View.VISIBLE
        }
    }

    private fun handleDockedSlideDrag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                slideSettleAnim?.cancel(); slideSettleAnim = null
                slideDragging = true
                slideDownRawY = event.rawY
                slideDragStartHeight = slideHeightPx.coerceIn(slidePeekHeight(), slideFullHeight())
                slideLastRawY = event.rawY
                slideLastT = android.os.SystemClock.uptimeMillis()
                slideVelPxPerMs = 0f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!slideDragging) return true
                val dy = event.rawY - slideDownRawY
                driveDockedSlide((slideDragStartHeight - dy).toInt())   // drag up (dy<0) grows the window
                val now = android.os.SystemClock.uptimeMillis()
                val dt = (now - slideLastT).coerceAtLeast(1L)
                slideVelPxPerMs = -(event.rawY - slideLastRawY) / dt
                slideLastRawY = event.rawY
                slideLastT = now
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (slideDragging) {
                    slideDragging = false
                    val mid = (slidePeekHeight() + slideFullHeight()) / 2
                    val expand = when {
                        slideVelPxPerMs > 0.6f -> true
                        slideVelPxPerMs < -0.6f -> false
                        else -> slideHeightPx > mid
                    }
                    settleDockedSlide(expand)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (slideDragging) {
                    slideDragging = false
                    settleDockedSlide(slideHeightPx > (slidePeekHeight() + slideFullHeight()) / 2)
                }
                return true
            }
        }
        return true
    }

    private fun settleDockedSlide(expand: Boolean) {
        slideSettleAnim?.cancel()
        val from = slideHeightPx.coerceIn(slidePeekHeight(), slideFullHeight())
        val to = if (expand) slideFullHeight() else slidePeekHeight()
        if (from == to) { finishDockedSlide(expand); return }
        slideSettleAnim = android.animation.ValueAnimator.ofInt(from, to).apply {
            duration = 240L
            interpolator = android.view.animation.DecelerateInterpolator(1.7f)
            addUpdateListener { driveDockedSlide(it.animatedValue as Int) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { finishDockedSlide(expand) }
            })
            start()
        }
    }

    private fun finishDockedSlide(expand: Boolean) {
        slideSettleAnim = null
        slideExpanded = expand
        driveDockedSlide(if (expand) slideFullHeight() else slidePeekHeight())
        if (expand && slideAutoFocusEnabled()) {
            sendBroadcast(Intent(InputInjectionService.ACTION_PREPARE_FIELD).apply { setPackage(packageName) })
        }
        runCatching { deckView?.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
    }

    private fun keyRow(labels: List<String>, rowIndex: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val inset = when (rowIndex) {
                1 -> dp(12)
                2 -> dp(6)
                3 -> dp(18)
                else -> 0
            }
            setPadding(inset, 0, inset, 0)
            labels.forEach { label ->
                addView(keyView(label), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, keyWeight(label)).apply {
                    leftMargin = dp(1)
                    rightMargin = dp(1)
                })
            }
        }
    }

    private fun keyView(label: String): TextView {
        val useBrushedOpticalKey = keyboardTheme() == KEYBOARD_THEME_BRUSHED && label != "teclas"
        return (if (label == "teclas") DockModeKeyView(this) else if (useBrushedOpticalKey) com.fran.teclas.keyboard.OpticalKeyTextView(this) else TextView(this)).apply {
            tag = label
            text = if (label == "teclas") "" else visualLabel(label)
            if (label == "teclas") {
                contentDescription = "Docked, tap to undock"
            }
            gravity = Gravity.CENTER
            applyRoundKeyboardKeyOutline(label)
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            if (keyboardTheme() == KEYBOARD_THEME_BRUSHED && this is com.fran.teclas.keyboard.OpticalKeyTextView) {
                opticalTextOffsetY = dp(
                    when (label) {
                        "enter" -> -2
                        "shift" -> -12
                        "back", "space" -> -16
                        "123", "abc", "." -> -5
                        else -> 0
                    }
                ).toFloat()
                opticalTextOffsetX = if (label == "back") -dp(4).toFloat() else 0f
            }
            textSize = keyTextSize(label)
            typeface = KeyboardThemeDrawables.typeface(keyboardVisualTheme(), label) ?: if (keyboardTheme() == KEYBOARD_THEME_SEEME || keyboardTheme() == KEYBOARD_THEME_BRUSHED) {
                Typeface.create(Typeface.MONOSPACE, if (label == "enter") Typeface.BOLD else Typeface.NORMAL)
            } else if (label == "enter") Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor(label))
            background = keyBackground(label)
            isClickable = true
            run {
                val handler = Handler(Looper.getMainLooper())
                var longPressFired = false
                var longPressRunnable: Runnable? = null
                // "teclas" is the only key that distinguishes tap from long-press (hold = enter swap
                // mode), so it must wait for finger-lift. EVERY OTHER key commits on ACTION_DOWN:
                // waiting for ACTION_UP meant a tap whose finger drifted a pixel (→ ACTION_CANCEL, no
                // ACTION_UP) was silently dropped, which is the "have to press twice" bug. Down-commit
                // never loses a press because ACTION_DOWN is always delivered first.
                val holdsForLongPress = label == "teclas"
                var downCommitted = false
                setOnTouchListener { v, event ->
                    if (swapMode) return@setOnTouchListener handleDockedKeyboardSwapTouch(event)
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            longPressFired = false
                            downCommitted = false
                            v.isPressed = true
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            v.animate().translationY(dp(4).toFloat()).setDuration(35L).start()
                            if (holdsForLongPress) {
                                val runnable = Runnable {
                                    longPressFired = true
                                    enterDockedKeyboardSwap(v)
                                }
                                longPressRunnable = runnable
                                handler.postDelayed(runnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                            } else {
                                handleKey(label)      // down-commit: the press can never be lost to a cancel
                                downCommitted = true
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            v.isPressed = false
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                            longPressRunnable = null
                            seemeReleaseHaptic(v)
                            v.animate().translationY(0f).setDuration(35L).start()
                            // Only the hold-key commits on lift; everything else already fired on down.
                            if (holdsForLongPress && !longPressFired && !downCommitted) handleKey(label)
                            longPressFired = false
                            downCommitted = false
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                            longPressRunnable = null
                            longPressFired = false
                            downCommitted = false
                            v.animate().translationY(0f).setDuration(35L).start()
                            true
                        }
                        else -> true
                    }
                }
            }
            if (label == "teclas") {
                setOnLongClickListener {
                    enterDockedKeyboardSwap(this)
                    true
                }
            }
        }
    }

    private inner class DockModeKeyView(context: Context) : TextView(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val phase = (android.os.SystemClock.uptimeMillis() % 1600L) / 1600f
            KeyboardDockGlyph.draw(
                canvas,
                dp(4).toFloat(),
                dp(5).toFloat(),
                width - dp(4).toFloat(),
                height - dp(5).toFloat(),
                docked = true,
                ink = textColor("teclas"),
                accent = goKeyColor(),
                phase = phase
            )
        }
    }

    private fun handleKey(label: String) {
        when (label) {
            "back" -> {
                if (undoDockedAutocorrect()) return          // backspace right after a fix reverts it
                lastCorrection = null
                if (fgDraft.isNotEmpty()) fgDraft.deleteCharAt(fgDraft.length - 1)
                sendKey(InputInjectionService.KEY_BACKSPACE)
                refreshSuggestions()
                return
            }
            "space" -> {
                autocorrectDraft()                            // fix the word BEFORE the space lands
                fgDraft.append(' ')
                sendKey(" ")
                refreshSuggestions()
                return
            }
            "." -> {
                autocorrectDraft()
                fgDraft.append('.')
                sendKey(".")
                refreshSuggestions()
                return
            }
            "enter" -> {
                lastCorrection = null
                fgDraft.setLength(0)
                sendKey(InputInjectionService.KEY_ENTER)
                refreshSuggestions()
                return
            }
            "shift", "teclas", "123" -> { /* handled below, no draft change */ }
            else -> {
                // A letter/symbol: mirror it into the draft so autocorrect and suggestions see it.
                lastCorrection = null
                val ch = if (shifted && label.length == 1) label.uppercase() else label
                fgDraft.append(ch)
                sendKey(ch)
                refreshSuggestions()
                return
            }
        }
        when (label) {
            "shift" -> {
                shifted = !shifted
                keyboardDeck?.let { deck ->
                    for (rowIndex in 0 until deck.childCount) {
                        val row = deck.getChildAt(rowIndex) as? LinearLayout ?: continue
                        for (keyIndex in 0 until row.childCount) {
                            val key = row.getChildAt(keyIndex) as? TextView ?: continue
                            val raw = key.tag as? String ?: continue
                            key.text = if (raw == "teclas") "" else visualLabel(raw)
                        }
                    }
                }
            }
            "back" -> sendKey(InputInjectionService.KEY_BACKSPACE)
            "enter" -> sendKey(InputInjectionService.KEY_ENTER)
            "space" -> sendKey(" ")
            "teclas" -> openLauncherKeyboardAction(TeclasKeyboardActions.SWITCH_TO_WIDGET_MODE)
            "123" -> Unit
            else -> sendKey(if (shifted && label.length == 1) label.uppercase() else label)
        }
    }

    private fun openLauncherKeyboardAction(action: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    private fun sendKey(value: String) {
        sendBroadcast(Intent(InputInjectionService.ACTION_INJECT_KEY).apply {
            setPackage(packageName)
            putExtra(InputInjectionService.EXTRA_CHAR, value)
        })
    }

    // ── Autocorrect + suggestions for typing into the foreground app ─────────────────────────────

    private fun currentDraftWord(): String = fgDraft.takeLastWhile { it.isLetter() }.toString()

    /** Correct the in-progress word before a space/period lands, via one atomic guarded tail-replace
     *  (the InputInjectionService no-ops it if the field no longer ends with what we typed). */
    private fun autocorrectDraft() {
        lastCorrection = null
        val p = predictor ?: return
        val word = currentDraftWord()
        if (word.length < 2 || word.length > 24) return
        val corrected = p.bestCorrection(word.lowercase()) ?: return
        if (corrected.equals(word, ignoreCase = true)) return
        val cased = if (word.first().isUpperCase())
            corrected.replaceFirstChar { it.uppercase() } else corrected
        sendReplaceTail(word, cased)
        fgDraft.setLength(fgDraft.length - word.length)
        fgDraft.append(cased)
        lastCorrection = word to cased
    }

    /** Backspace immediately after an autocorrection restores the typed word. */
    private fun undoDockedAutocorrect(): Boolean {
        val (typed, cased) = lastCorrection ?: return false
        lastCorrection = null
        val tail = "$cased "
        if (!fgDraft.endsWith(tail)) return false
        sendReplaceTail(tail, "$typed ")
        fgDraft.setLength(fgDraft.length - tail.length)
        fgDraft.append("$typed ")
        refreshSuggestions()
        return true
    }

    private fun sendReplaceTail(expect: String, with: String) {
        sendBroadcast(Intent(InputInjectionService.ACTION_REPLACE_TAIL).apply {
            setPackage(packageName)
            putExtra(InputInjectionService.EXTRA_EXPECT, expect)
            putExtra(InputInjectionService.EXTRA_WITH, with)
        })
    }

    /** Repaint the suggestion strip from the current in-progress word. */
    private fun refreshSuggestions() {
        val strip = suggestionStrip ?: return
        val p = predictor
        val word = currentDraftWord()
        val sugg = if (p != null && word.length >= 1)
            p.getSuggestions(word, 3).filterNot { it.equals(word, ignoreCase = true) } else emptyList()
        strip.removeAllViews()
        if (sugg.isEmpty()) { strip.visibility = View.GONE; return }
        strip.visibility = View.VISIBLE
        val items = sugg.take(3)
        items.forEachIndexed { i, s ->
            if (i > 0) {
                // Thin vertical divider between suggestions.
                strip.addView(View(this).apply {
                    setBackgroundColor((textColor("space") and 0x00FFFFFF) or 0x40000000)
                }, LinearLayout.LayoutParams(dp(1), dp(22)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                })
            }
            strip.addView(TextView(this).apply {
                text = s
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(textColor("space"))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                isClickable = true
                setOnClickListener { applySuggestion(s) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    /** Tap a suggestion: replace the in-progress word with it and add a space. */
    private fun applySuggestion(word: String) {
        val cur = currentDraftWord()
        if (cur.isEmpty()) return
        val cased = if (cur.first().isUpperCase()) word.replaceFirstChar { it.uppercase() } else word
        sendReplaceTail(cur, "$cased ")
        fgDraft.setLength(fgDraft.length - cur.length)
        fgDraft.append("$cased ")
        lastCorrection = null
        refreshSuggestions()
    }

    private fun freeformRestoreButton(): TextView {
        return TextView(this).apply {
            text = "↙"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(selectedNeuTokens().ink)
            alpha = 0.76f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(15).toFloat()
                setColor(if (selectedNeuTokens().mode == NeuMode.LIGHT) 0xDDEBF0F4.toInt() else 0xBB090B10.toInt())
                setStroke(dp(1), if (selectedNeuTokens().mode == NeuMode.LIGHT) 0x55FFFFFF else 0x22FFFFFF)
            }
            isClickable = true
            contentDescription = "Resize app back above keyboard"
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                sendBroadcast(Intent(InputInjectionService.ACTION_REPIN_FREEFORM).apply { setPackage(packageName) })
            }
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.92f).scaleY(0.92f).alpha(1f).setDuration(80).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).alpha(0.76f).setDuration(120).start()
                }
                false
            }
        }
    }

    private fun visualLabel(label: String): String {
        return when (label) {
            "shift" -> if (shifted) "⇧" else "↑"
            "back" -> "⌫"
            "enter" -> "GO"
            "space" -> "space"
            else -> if (shifted && label.length == 1) label.uppercase() else label
        }
    }

    private fun keyWeight(label: String): Float {
        return when (label) {
            "space" -> 3.65f
            "enter" -> 0.82f
            "." -> 0.86f
            "teclas" -> 0.86f
            "123", "back", "shift" -> 1.02f
            else -> 1f
        }
    }

    private fun overlayKeyboardHeight(): Int {
        return keyRowHeight() * 4 - keyRowOverlap() * 3 + keyboardTopPadding() + keyboardBottomPadding()
    }

    private fun keyBackground(label: String): Drawable {
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), visualKeyBackground(label, pressed = true))
            addState(intArrayOf(), visualKeyBackground(label, pressed = false))
        }
    }

    private fun visualKeyBackground(label: String, pressed: Boolean): Drawable {
        val base = keyFaceBackground(label, pressed)
        val theme = keyboardVisualTheme()
        if (theme == KEYBOARD_THEME_DEFAULT || theme == KEYBOARD_THEME_TECLAS) {
            if (isRoundKeyboardKey(label)) return base
            return InsetDrawable(base, keyVisualHorizontalInset(label), keyVerticalInset(), keyVisualHorizontalInset(label), keyVerticalInset())
        }
        val fixedInset = dp(2)
        val topBottom = if (isRoundKeyboardKey(label)) fixedInset else keyVerticalInset()
        return InsetDrawable(base, keyVisualHorizontalInset(label), topBottom, keyVisualHorizontalInset(label), topBottom)
    }

    private fun keyFaceBackground(label: String, pressed: Boolean): Drawable {
        val theme = keyboardVisualTheme()
        if (KeyboardThemeDrawables.isAddedTheme(theme)) {
            return KeyboardThemeDrawables.keyLayer(
                this,
                theme,
                label,
                pressed = pressed,
                darkMode = selectedNeuTokens().mode == NeuMode.DARK,
                goColor = if (theme == KEYBOARD_THEME_TECLAS_GLASS) {
                    if (pressed) brighten(goKeyColor()) else goKeyColor()
                } else {
                    KeyboardThemeDrawables.DEFAULT_ACCENT
                }
            )
        }
        if (keyboardTheme() == KEYBOARD_THEME_SEEME) {
            return SeemeDrawables.key(label, pressed = pressed, density = resources.displayMetrics.density, goColor = if (pressed) brighten(goKeyColor()) else goKeyColor())
        }
        if (keyboardTheme() == KEYBOARD_THEME_BRUSHED) {
            return BrushedDrawables.key(
                label,
                pressed = pressed,
                dark = selectedNeuTokens().mode == NeuMode.DARK,
                density = resources.displayMetrics.density,
                docked = true,
                goColor = if (pressed) brighten(goKeyColor()) else goKeyColor()
            )
        }
        if (label == "enter") return themedGoKeyBackground(if (pressed) brighten(goKeyColor()) else goKeyColor(), pressed)
        hyper3dKeyDrawable(label)?.let { return it }
        if (keyboardTheme() == KEYBOARD_THEME_GOKEYS) return goKeysKeyBackground(label, pressed)
        if (label == "123") return themed123KeyBackground(pressed)
        return when (keyboardTheme()) {
            KEYBOARD_THEME_TECLAS -> physicalKeyBackground(pressed = pressed, premium = false, fn = isFnKey(label))
            KEYBOARD_THEME_SKEUO -> physicalKeyBackground(pressed = pressed, premium = true, fn = isFnKey(label))
            else -> Neu.drawable(selectedNeuTokens(), dp(7).toFloat(), if (pressed) NeuLevel.PRESSED_SM else NeuLevel.RAISED_SM)
        }
    }

    private fun deckBackground(): Drawable {
        val theme = keyboardVisualTheme()
        if (KeyboardThemeDrawables.isAddedTheme(theme)) {
            return KeyboardThemeDrawables.panel(this, theme, selectedNeuTokens().mode == NeuMode.DARK)
        }
        if (theme == KEYBOARD_THEME_SEEME) return SeemeDrawables.panel(darkTint = true)
        if (theme == KEYBOARD_THEME_BRUSHED) return BrushedDrawables.panel(selectedNeuTokens().mode == NeuMode.DARK, resources.displayMetrics.density)
        if (theme == KEYBOARD_THEME_DEFAULT) return DefaultKeyboardGlass.deck(selectedNeuTokens(), dp(16).toFloat())
        val light = keyboardLightMode(theme)
        val colors = if (light) {
            when (theme) {
                KEYBOARD_THEME_SKEUO -> intArrayOf(0xFFF1F3F6.toInt(), 0xFFE0E4EA.toInt(), 0xFFC9D0DA.toInt())
                KEYBOARD_THEME_TECLAS -> intArrayOf(0xFFECEFF4.toInt(), 0xFFDDE2E9.toInt(), 0xFFC7CED9.toInt())
                KEYBOARD_THEME_GOKEYS -> intArrayOf(0xFFF3F5F8.toInt(), 0xFFE2E6EC.toInt(), 0xFFC9D0DA.toInt())
                KEYBOARD_THEME_HYPER3D, KEYBOARD_THEME_HYPER3D_LIGHT -> intArrayOf(0xFFE8EBF0.toInt(), 0xFFD4D8E0.toInt(), 0xFFBEC4CF.toInt())
                else -> intArrayOf(0xFFECEEF2.toInt(), 0xFFDDE1E8.toInt(), 0xFFC9D0DA.toInt())
            }
        } else {
            when (theme) {
                KEYBOARD_THEME_SKEUO -> intArrayOf(0xFF24262D.toInt(), 0xFF101116.toInt(), 0xFF050506.toInt())
                KEYBOARD_THEME_TECLAS -> intArrayOf(0xFF1A1C21.toInt(), 0xFF111318.toInt(), 0xFF08090C.toInt(), 0xFF030304.toInt())
                KEYBOARD_THEME_GOKEYS -> intArrayOf(0xFF15171B.toInt(), 0xFF101115.toInt(), 0xFF0B0C0F.toInt())
                KEYBOARD_THEME_HYPER3D_BLACK -> intArrayOf(0xFF0C0C0E.toInt(), 0xFF080809.toInt(), 0xFF050506.toInt())
                KEYBOARD_THEME_HYPER3D_LIGHT -> intArrayOf(0xFFE8EBF0.toInt(), 0xFFD4D8E0.toInt(), 0xFFBEC4CF.toInt())
                KEYBOARD_THEME_HYPER3D -> intArrayOf(0xFF171A20.toInt(), 0xFF0D0F13.toInt(), 0xFF050608.toInt())
                else -> intArrayOf(0xFF1F2127.toInt(), 0xFF0C0D10.toInt(), 0xFF030304.toInt())
            }
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadii = floatArrayOf(dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), 0f, 0f, 0f, 0f)
            setStroke(dp(1), if (light) 0x66FFFFFF else when (theme) {
                KEYBOARD_THEME_SKEUO -> 0x303B4250
                KEYBOARD_THEME_GOKEYS -> 0x14FFFFFF
                KEYBOARD_THEME_HYPER3D_LIGHT -> 0x66FFFFFF
                else -> 0x181B20
            })
        }
    }

    private fun keyRowHeight(): Int {
        val size = KeyboardSettings.keyboardSize(this)
        return dp(56 + (size * 20 / 100))
    }

    private fun keyRowOverlap(): Int {
        val size = KeyboardSettings.keyboardSize(this)
        return dp(12 + size * 3 / 100)
    }

    private fun keyVerticalInset(): Int {
        val size = KeyboardSettings.keyboardSize(this)
        val theme = keyboardVisualTheme()
        if (theme == KEYBOARD_THEME_DEFAULT) return dp(7 + size * 4 / 100)
        if (theme != KEYBOARD_THEME_TECLAS) return dp(10 + size * 5 / 100)
        return if (theme == KEYBOARD_THEME_TECLAS || theme == KEYBOARD_THEME_GOKEYS || theme == KEYBOARD_THEME_BRUSHED || isHyper3dTheme(theme)) {
            dp(10 + size * 5 / 100)
        } else {
            dp(7 + size * 4 / 100)
        }
    }

    private fun keyVisualHorizontalInset(label: String): Int {
        val theme = keyboardVisualTheme()
        if (theme == KEYBOARD_THEME_DEFAULT || theme == KEYBOARD_THEME_TECLAS) return 0
        if (label == "space") return dp(3)
        if (label == "teclas") return dp(3)
        if (label == "123" || label == "back" || label == "shift" || label == ".") return dp(4)
        return dp(4)
    }

    private fun keyboardTopPadding() = dp(4)
    private fun keyboardBottomPadding() = dp(2)
    private fun keyTextSize(label: String): Float {
        val size = KeyboardSettings.keyboardSize(this)
        if (KeyboardThemeDrawables.isAddedTheme(keyboardVisualTheme())) {
            val base = when (label) {
                "shift" -> 23f
                "space" -> 18f
                "123", "abc", "enter", "back", "." -> 13.5f
                else -> 20f
            }
            val growth = when (label) {
                "shift" -> 2f
                "space" -> 2f
                "123", "abc", "enter", "back", "." -> 1.4f
                else -> 2.2f
            }
            return base + (size * growth / 100f)
        }
        if (keyboardTheme() == KEYBOARD_THEME_BRUSHED) {
            val brushedBase = when (label) {
                "shift", "." -> 22f
                "back" -> 19f
                "space" -> 18f
                "123", "abc", "enter" -> 15.5f
                "teclas" -> 13.5f
                else -> 20f
            }
            val brushedGrowth = when (label) {
                "123", "abc", "enter", "teclas" -> 1.5f
                "space" -> 2f
                else -> 2.5f
            }
            return brushedBase + (size * brushedGrowth / 100f)
        }
        val base = when (label) {
            "shift" -> 24f
            "space" -> 18f
            "123", "teclas", "enter", "back", "." -> 13.5f
            else -> 20f
        }
        val growth = when (label) {
            "shift" -> 2.5f
            "space" -> 2f
            "123", "teclas", "enter", "back", "." -> 1.5f
            else -> 2.5f
        }
        return base + (size * growth / 100f)
    }

    private fun goLegendColor(): Int =
        if (KeyboardThemeDrawables.isAddedTheme(keyboardVisualTheme())) {
            KeyboardThemeDrawables.textColor(keyboardVisualTheme(), "enter", selectedNeuTokens().mode == NeuMode.DARK, goKeyColor())
        } else if (selectedNeuTokens().mode == NeuMode.LIGHT) 0xFFFFFFFF.toInt() else 0xFF050506.toInt()

    private fun textColor(label: String): Int {
        if (label == "enter") return goLegendColor()
        val theme = keyboardVisualTheme()
        if (KeyboardThemeDrawables.isAddedTheme(theme)) {
            return when (label) {
                "shift", "back" -> KeyboardThemeDrawables.accent(theme, selectedNeuTokens().mode == NeuMode.DARK, goKeyColor())
                else -> KeyboardThemeDrawables.textColor(theme, label, selectedNeuTokens().mode == NeuMode.DARK)
            }
        }
        if (isHyper3dTheme(theme)) {
            val visualTheme = hyper3dVisualTheme(theme)
            return when {
                visualTheme == KEYBOARD_THEME_HYPER3D_LIGHT && isFnKey(label) -> 0xFF596170.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D_LIGHT -> 0xFF1E2633.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D_BLACK && label == "teclas" -> 0xFF9DB4FF.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D_BLACK && isFnKey(label) -> 0xFF9AA2B1.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D && label == "teclas" -> 0xFFEAF0FF.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D && isFnKey(label) -> 0xFF9AA2B1.toInt()
                else -> 0xFFEEF1F7.toInt()
            }
        }
        if (theme == KEYBOARD_THEME_SEEME) {
            return when (label) {
                "teclas" -> 0xFFFF5A60.toInt()
                "123", "back", "shift", "." -> 0xFF8A8A8A.toInt()
                else -> 0xFFF2F2F2.toInt()
            }
        }
        if (theme == KEYBOARD_THEME_BRUSHED) {
            return BrushedDrawables.ink(label, selectedNeuTokens().mode == NeuMode.DARK)
        }
        if (theme == KEYBOARD_THEME_GOKEYS) {
            return if (keyboardLightMode(theme)) {
                when (label) {
                    "enter" -> 0xFFFFFFFF.toInt()
                    "123", "teclas", "back", "shift", "." -> 0xFF6F7884.toInt()
                    else -> 0xFF202733.toInt()
                }
            } else {
                when (label) {
                    "enter" -> 0xFFFFFFFF.toInt()
                    "123", "teclas", "back", "shift", "." -> 0xFF9AA1AB.toInt()
                    else -> 0xFFF3F5F8.toInt()
                }
            }
        }
        return if (keyboardLightMode(theme)) {
            when (label) {
                "enter" -> Neu.ACCENT
                "teclas" -> 0xFF2F6C4C.toInt()
                "123", "back", "shift", "." -> 0xFF6F7884.toInt()
                else -> 0xFF202733.toInt()
            }
        } else {
            val tokens = selectedNeuTokens()
            when (label) {
                "enter" -> Neu.ACCENT
                "teclas" -> Neu.GREEN
                "123", "back", "shift", "." -> tokens.inkDim
                else -> tokens.ink
            }
        }
    }

    private fun themedGoKeyBackground(fillColor: Int, pressed: Boolean): Drawable {
        val skirt = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF050609.toInt())
        }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(brighten(fillColor), fillColor, darken(fillColor))).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), brighten(fillColor))
        }
        val glint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x55FFFFFF, 0x00FFFFFF)).apply {
            shape = GradientDrawable.OVAL
        }
        return LayerDrawable(arrayOf(skirt, face, glint)).apply {
            val drop = if (pressed) dp(1) else dp(if (keyboardTheme() == KEYBOARD_THEME_SKEUO) 6 else 4)
            setLayerInset(0, dp(1), drop, dp(1), 0)
            setLayerInset(1, dp(2), 0, dp(2), drop)
            setLayerInset(2, dp(8), dp(5), dp(8), dp(if (keyboardTheme() == KEYBOARD_THEME_SKEUO) 18 else 20))
        }
    }

    private fun themed123KeyBackground(pressed: Boolean): Drawable {
        hyper3dKeyDrawable("123")?.let { return it }
        if (keyboardTheme() == KEYBOARD_THEME_DEFAULT) return Neu.drawable(selectedNeuTokens(), dp(99).toFloat(), if (pressed) NeuLevel.PRESSED_SM else NeuLevel.RAISED_SM)
        val teclas = keyboardTheme() == KEYBOARD_THEME_TECLAS
        if (keyboardLightMode(keyboardVisualTheme())) {
            val faceTop = if (pressed) 0xFFD1D8E2.toInt() else 0xFFF1F4F8.toInt()
            val faceMid = if (pressed) 0xFFBEC7D3.toInt() else 0xFFDCE3EC.toInt()
            val faceBot = if (pressed) 0xFFA8B3C0.toInt() else 0xFFC4CDD8.toInt()
            val skirt = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x668A96A6) }
            val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(faceTop, faceMid, faceBot)).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), 0xFFFFFFFF.toInt())
            }
            val glint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x88FFFFFF.toInt(), 0x00FFFFFF)).apply { shape = GradientDrawable.OVAL }
            return LayerDrawable(arrayOf(skirt, face, glint)).apply {
                val drop = if (pressed) dp(1) else dp(5)
                setLayerInset(0, dp(1), drop, dp(1), 0)
                setLayerInset(1, dp(2), 0, dp(2), drop)
                setLayerInset(2, dp(8), dp(5), dp(8), dp(18))
            }
        }
        val faceTop = if (pressed) 0xFF3A3E4A.toInt() else if (teclas) 0xFF22252B.toInt() else 0xFF34373E.toInt()
        val faceMid = if (pressed) 0xFF2A2D36.toInt() else if (teclas) 0xFF171A1F.toInt() else 0xFF202329.toInt()
        val faceBot = if (pressed) 0xFF151719.toInt() else if (teclas) 0xFF07080B.toInt() else 0xFF0B0C10.toInt()
        val skirt = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF050609.toInt()) }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(faceTop, faceMid, faceBot)).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), if (teclas) 0xFF111318.toInt() else 0xFF05060A.toInt())
        }
        val glint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(if (teclas) 0x10FFFFFF else 0x3DFFFFFF, 0x00FFFFFF)).apply { shape = GradientDrawable.OVAL }
        return LayerDrawable(arrayOf(skirt, face, glint)).apply {
            val drop = if (pressed) dp(1) else dp(if (teclas) 4 else 6)
            setLayerInset(0, dp(1), drop, dp(1), 0)
            setLayerInset(1, dp(2), 0, dp(2), drop)
            setLayerInset(2, dp(8), dp(5), dp(8), dp(if (teclas) 20 else 18))
        }
    }

    private fun physicalKeyBackground(pressed: Boolean, premium: Boolean, fn: Boolean): Drawable {
        val teclas = keyboardTheme() == KEYBOARD_THEME_TECLAS
        val radius = dp(when {
            premium -> 10
            teclas -> 5
            else -> 9
        }).toFloat()
        if (keyboardLightMode(keyboardVisualTheme())) {
            val skirt = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFB3BCC9.toInt(), 0xFF8E99A8.toInt(), 0xFF687482.toInt())).apply {
                cornerRadius = radius
                setStroke(dp(1), 0x88FFFFFF.toInt())
            }
            val outerRim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                if (pressed) 0xFFD3DBE5.toInt() else 0xFFFFFFFF.toInt(),
                if (pressed) 0xFFAEB8C6.toInt() else 0xFFCBD3DE.toInt()
            )).apply {
                cornerRadius = radius
                setStroke(dp(1), 0xFFFFFFFF.toInt())
            }
            val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                if (pressed) 0xFFD2DAE4.toInt() else 0xFFF5F7FA.toInt(),
                if (pressed) 0xFFBBC5D1.toInt() else 0xFFDCE3EC.toInt(),
                if (pressed) 0xFFA4AFBD.toInt() else 0xFFC2CCD8.toInt()
            )).apply {
                cornerRadius = radius
                setStroke(dp(1), if (pressed) 0xFFA6B1BE.toInt() else 0xFFFFFFFF.toInt())
            }
            val topGlint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99FFFFFF.toInt(), 0x00FFFFFF)).apply {
                cornerRadius = radius
            }
            val warmBleed = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(
                if (fn && !pressed) 0x22F5C451 else if (pressed) 0x33FF5A3C else 0x00000000,
                0x00000000
            )).apply {
                cornerRadius = radius
            }
            return LayerDrawable(arrayOf(skirt, outerRim, face, warmBleed, topGlint)).apply {
                val drop = if (pressed) dp(1) else dp(if (premium) 7 else 6)
                val side = dp(2)
                setLayerInset(0, dp(1), drop, dp(1), 0)
                setLayerInset(1, side, dp(if (pressed) 1 else 0), side, drop)
                setLayerInset(2, side + dp(1), dp(1), side + dp(1), drop + dp(1))
                setLayerInset(3, side + dp(2), dp(4), side + dp(2), drop + dp(1))
                setLayerInset(4, side + dp(3), dp(2), side + dp(3), dp(20))
            }
        }
        val skirt = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            when {
                premium -> 0xFF14171D.toInt()
                teclas -> 0xFF050609.toInt()
                else -> 0xFF07080B.toInt()
            },
            if (teclas) 0xFF020304.toInt() else 0xFF050609.toInt(),
            0xFF010102.toInt()
        )).apply {
            cornerRadius = radius
            setStroke(dp(1), 0xFF040507.toInt())
        }
        val outerRim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            when {
                pressed -> 0xFFFF8E68.toInt()
                premium -> 0xFF525866.toInt()
                teclas -> 0xFF24272F.toInt()
                else -> 0xFF34373E.toInt()
            },
            if (teclas) 0xFF05060A.toInt() else 0xFF090A0D.toInt()
        )).apply {
            cornerRadius = radius
            setStroke(dp(1), if (premium) 0xFF05060A.toInt() else 0xFF040507.toInt())
        }
        val faceColors = when {
            pressed -> intArrayOf(0xFFFF9B72.toInt(), 0xFFFF5A3C.toInt(), 0xFF9C2F11.toInt())
            premium -> intArrayOf(0xFF4B505B.toInt(), 0xFF2D323D.toInt(), 0xFF151821.toInt(), 0xFF08090D.toInt())
            teclas -> intArrayOf(0xFF2B2E35.toInt(), 0xFF202329.toInt(), 0xFF15171C.toInt(), 0xFF0A0B0E.toInt())
            else -> intArrayOf(0xFF34373E.toInt(), 0xFF202329.toInt(), 0xFF14161B.toInt(), 0xFF0B0C10.toInt())
        }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, faceColors).apply {
            cornerRadius = radius
            setStroke(dp(1), if (pressed) 0xFFFFB199.toInt() else if (premium) 0xFF3C4350.toInt() else if (teclas) 0xFF111318.toInt() else 0xFF05060A.toInt())
        }
        val topGlint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (pressed) 0x66FFFFFF else if (premium) 0x55FFFFFF else if (teclas) 0x20FFFFFF else 0x3DFFFFFF,
            0x00FFFFFF
        )).apply {
            cornerRadius = radius
        }
        val warmBleed = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(
            if (fn && !pressed) (if (teclas) 0x16F5C451 else 0x22F5C451) else if (pressed) 0x66FF5A3C else 0x00000000,
            0x00000000
        )).apply {
            cornerRadius = radius
        }
        return LayerDrawable(arrayOf(skirt, outerRim, face, warmBleed, topGlint)).apply {
            val drop = if (pressed) dp(1) else dp(when {
                premium -> 8
                teclas -> 8
                else -> 5
            })
            val side = if (premium) dp(2) else dp(if (teclas) 2 else 1)
            setLayerInset(0, dp(1), drop, dp(1), 0)
            setLayerInset(1, side, dp(if (pressed) 1 else 0), side, drop)
            setLayerInset(2, side + dp(1), dp(1), side + dp(1), drop + dp(if (premium) 1 else 0))
            setLayerInset(3, side + dp(2), dp(if (teclas) 5 else 3), side + dp(2), drop + dp(1))
            setLayerInset(4, side + dp(3), dp(2), side + dp(3), dp(if (premium) 19 else if (teclas) 24 else 20))
        }
    }

    private fun goKeysKeyBackground(label: String, pressed: Boolean): Drawable {
        val isGo = label == "enter"
        val radius = dp(if (isGo) 9 else 6).toFloat()
        if (isGo) {
            return GradientDrawable().apply {
                setColor(if (pressed) 0xFFE35C16.toInt() else 0xFFFF7A2A.toInt())
                cornerRadius = radius
                setStroke(dp(1), if (pressed) 0xFFE35C16.toInt() else 0xFFFF8A43.toInt())
            }
        }
        if (keyboardLightMode(keyboardVisualTheme())) {
            return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                if (pressed) 0xFFD0D6DF.toInt() else 0xFFF3F5F8.toInt(),
                if (pressed) 0xFFB8C1CE.toInt() else 0xFFDCE2EA.toInt()
            )).apply {
                cornerRadius = radius
                setStroke(dp(1), if (pressed) 0xFFAAB4C1.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return GradientDrawable().apply {
            setColor(if (pressed) 0xFF1A1D22.toInt() else 0xFF22262D.toInt())
            cornerRadius = radius
            setStroke(dp(1), if (pressed) 0xFF16191E.toInt() else 0xFF2E333B.toInt())
        }
    }

    private fun hyper3dKeyDrawable(label: String): Drawable? {
        val id = when (hyper3dVisualTheme(keyboardVisualTheme())) {
            KEYBOARD_THEME_HYPER3D -> when {
                label == "enter" -> R.drawable.key_hyper3d_go
                isFnKey(label) -> R.drawable.key_hyper3d_dark
                else -> R.drawable.key_hyper3d
            }
            KEYBOARD_THEME_HYPER3D_BLACK -> when {
                label == "enter" -> R.drawable.key_hyper3d_black_go
                label == "teclas" -> R.drawable.key_hyper3d_black_accent
                isFnKey(label) -> R.drawable.key_hyper3d_black_dark
                else -> R.drawable.key_hyper3d_black
            }
            KEYBOARD_THEME_HYPER3D_LIGHT -> when {
                label == "enter" -> R.drawable.key_hyper3d_light_go
                label == "teclas" -> R.drawable.key_hyper3d_light_accent
                isFnKey(label) -> R.drawable.key_hyper3d_light_dark
                else -> R.drawable.key_hyper3d_light
            }
            else -> return null
        }
        return runCatching { resources.getDrawable(id, theme) }.getOrNull()
    }

    private fun selectedNeuTokens(): NeuTokens {
        return resolveTeclasNeuTokens(themeMode())
    }

    private fun isSystemDarkMode(): Boolean {
        return teclasSystemDarkMode()
    }

    private fun keyboardLightMode(theme: String): Boolean {
        if (KeyboardThemeDrawables.isAddedTheme(theme)) {
            return KeyboardThemeDrawables.isLight(theme, selectedNeuTokens().mode == NeuMode.DARK)
        }
        if (keyboardTheme() == KEYBOARD_THEME_DEFAULT) return selectedNeuTokens().mode == NeuMode.LIGHT
        return selectedNeuTokens().mode == NeuMode.LIGHT && theme != KEYBOARD_THEME_HYPER3D_BLACK
    }

    private fun hyper3dVisualTheme(theme: String): String {
        return if (theme == KEYBOARD_THEME_HYPER3D && keyboardLightMode(theme)) KEYBOARD_THEME_HYPER3D_LIGHT else theme
    }

    private fun isHyper3dTheme(theme: String): Boolean {
        return theme == KEYBOARD_THEME_HYPER3D ||
            theme == KEYBOARD_THEME_HYPER3D_BLACK ||
            theme == KEYBOARD_THEME_HYPER3D_LIGHT
    }

    private fun keyboardTheme(): String = swapPreviewTheme ?: keyboardThemeFromPrefs()

    private fun keyboardThemeFromPrefs(): String {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEYBOARD_THEME_PREF, KEYBOARD_THEME_DEFAULT) ?: KEYBOARD_THEME_DEFAULT
    }

    private fun keyboardVisualTheme(): String = keyboardTheme()

    private fun seemeReleaseHaptic(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
        }
    }

    private fun themeMode(): String {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(THEME_MODE_PREF, THEME_MODE_SYSTEM) ?: THEME_MODE_SYSTEM
    }

    private fun goKeyColor(): Int {
        // Default: Cursor Violet (brand accent). A user-chosen accent overrides it.
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(GO_KEY_COLOR_PREF, 0xFFC9A7FF.toInt())
    }

    private fun isFnKey(label: String) = label in setOf("123", "abc", "teclas", "back", "shift", ".", "period")

    private fun brighten(color: Int): Int {
        val r = ((color shr 16 and 0xFF) * 1.18f).toInt().coerceAtMost(255)
        val g = ((color shr 8 and 0xFF) * 1.18f).toInt().coerceAtMost(255)
        val b = ((color and 0xFF) * 1.18f).toInt().coerceAtMost(255)
        return (color and -0x1000000) or (r shl 16) or (g shl 8) or b
    }

    private fun darken(color: Int): Int {
        val r = ((color shr 16 and 0xFF) * 0.58f).toInt()
        val g = ((color shr 8 and 0xFF) * 0.58f).toInt()
        val b = ((color and 0xFF) * 0.58f).toInt()
        return (color and -0x1000000) or (r shl 16) or (g shl 8) or b
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        runCatching { unregisterReceiver(overlayVisibilityReceiver) }
        deckView?.let { view -> runCatching { windowManager?.removeView(view) } }
        deckView = null
        keyboardDeck = null
        overlayParams = null
        VivoDockedExperiment.clearViewportTruncation(this)
        super.onDestroy()
    }

    companion object {
        private const val PREFS_NAME = "teclas"
        private const val KEYBOARD_THEME_PREF = "keyboard_theme"
        private const val KEYBOARD_THEME_DEFAULT = "default"
        private const val KEYBOARD_THEME_TECLAS = "teclas"
        private const val KEYBOARD_THEME_SKEUO = "skeuo"
        private const val KEYBOARD_THEME_GOKEYS = "gokeys"
        private const val KEYBOARD_THEME_HYPER3D = "hyper3d"
        private const val KEYBOARD_THEME_HYPER3D_BLACK = "hyper3d_black"
        private const val KEYBOARD_THEME_HYPER3D_LIGHT = "hyper3d_light"
        private const val KEYBOARD_THEME_BRUSHED = "brushed"
        private const val KEYBOARD_THEME_SEEME = "seeme"
        private const val KEYBOARD_THEME_TECLAS_GLASS = KeyboardThemeDrawables.TECLAS_GLASS
        private const val THEME_MODE_PREF = "theme_mode"
        private const val THEME_MODE_DARK = "dark"
        private const val THEME_MODE_LIGHT = "light"
        private const val THEME_MODE_SYSTEM = "system"
        private const val GO_KEY_COLOR_PREF = "go_key_color"

        fun overlaySettingsIntent(context: Context): Intent {
            return Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        }
    }
}
