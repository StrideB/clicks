package com.fran.clicks

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
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
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.StateListDrawable

class DockedKeyboardService : Service() {
    private var windowManager: WindowManager? = null
    private var deckView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var shifted = false
    private var overlayVisible = true

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
        showDeck()
    }

    private fun registerOverlayVisibilityReceiver() {
        val filter = IntentFilter(InputInjectionService.ACTION_SET_DOCKED_OVERLAY_VISIBLE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(overlayVisibilityReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(overlayVisibilityReceiver, filter)
        }
    }

    private fun showDeck() {
        if (deckView != null) return
        val deck = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), keyboardTopPadding(), dp(10), keyboardBottomPadding())
            background = deckBackground()
        }
        listOf(
            "qwertyuiop".map { it.toString() },
            "asdfghjkl".map { it.toString() },
            listOf("shift") + "zxcvbnm".map { it.toString() } + listOf("back"),
            listOf("123", "clicks", "space", ".", "enter")
        ).forEachIndexed { rowIndex, row ->
            deck.addView(keyRow(row, rowIndex), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                keyRowHeight()
            ).apply {
                if (rowIndex > 0) topMargin = -keyRowOverlap()
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
        }
        windowManager?.addView(deck, lp)
        overlayParams = lp
        deckView = deck
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
                    leftMargin = dp(3)
                    rightMargin = dp(3)
                })
            }
        }
    }

    private fun keyView(label: String): TextView {
        val useBrushedOpticalKey = keyboardTheme() == KEYBOARD_THEME_BRUSHED && label != "clicks"
        return (if (useBrushedOpticalKey) com.fran.clicks.keyboard.OpticalKeyTextView(this) else TextView(this)).apply {
            tag = label
            text = if (keyboardTheme() == KEYBOARD_THEME_BRUSHED && label == "clicks") "" else visualLabel(label)
            if (keyboardTheme() == KEYBOARD_THEME_BRUSHED && label == "clicks") {
                contentDescription = "Docked, tap to undock"
            }
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            if (keyboardTheme() == KEYBOARD_THEME_BRUSHED && this is com.fran.clicks.keyboard.OpticalKeyTextView) {
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
            typeface = if (keyboardTheme() == KEYBOARD_THEME_SEEME || keyboardTheme() == KEYBOARD_THEME_BRUSHED) {
                Typeface.create(Typeface.MONOSPACE, if (label == "enter") Typeface.BOLD else Typeface.NORMAL)
            } else if (label == "enter") Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor(label))
            background = keyBackground(label)
            isClickable = true
            run {
                val handler = Handler(Looper.getMainLooper())
                var longPressFired = false
                var longPressRunnable: Runnable? = null
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            longPressFired = false
                            v.isPressed = true
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            v.animate().translationY(dp(4).toFloat()).setDuration(35L).start()
                            if (label == "clicks") {
                                val runnable = Runnable {
                                    longPressFired = true
                                    v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    openLauncherKeyboardAction(ClicksKeyboardActions.SWITCH_TO_WIDGET_MODE)
                                }
                                longPressRunnable = runnable
                                handler.postDelayed(runnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            v.isPressed = false
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                            longPressRunnable = null
                            seemeReleaseHaptic(v)
                            v.animate().translationY(0f).setDuration(35L).start()
                            if (!longPressFired) handleKey(label)
                            longPressFired = false
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                            longPressRunnable = null
                            longPressFired = false
                            v.animate().translationY(0f).setDuration(35L).start()
                            true
                        }
                        else -> true
                    }
                }
            }
            if (label == "clicks") {
                setOnLongClickListener {
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    openLauncherKeyboardAction(ClicksKeyboardActions.SWITCH_TO_WIDGET_MODE)
                    true
                }
            }
        }
    }

    private fun handleKey(label: String) {
        when (label) {
            "shift" -> {
                shifted = !shifted
                (deckView as? LinearLayout)?.let { deck ->
                    for (rowIndex in 0 until deck.childCount) {
                        val row = deck.getChildAt(rowIndex) as? LinearLayout ?: continue
                        for (keyIndex in 0 until row.childCount) {
                            val key = row.getChildAt(keyIndex) as? TextView ?: continue
                            val raw = key.tag as? String ?: continue
                            key.text = visualLabel(raw)
                        }
                    }
                }
            }
            "back" -> sendKey(InputInjectionService.KEY_BACKSPACE)
            "enter" -> sendKey(InputInjectionService.KEY_ENTER)
            "space" -> sendKey(" ")
            "clicks" -> openLauncherKeyboardAction(ClicksKeyboardActions.OPEN_KEYBOARD_SETTINGS)
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
            "clicks" -> 1.55f
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
        if (label == "enter" || label == "123") return base
        return InsetDrawable(base, dp(1), keyVerticalInset(), dp(1), keyVerticalInset())
    }

    private fun keyFaceBackground(label: String, pressed: Boolean): Drawable {
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
            KEYBOARD_THEME_CLICKS -> physicalKeyBackground(pressed = pressed, premium = false, fn = isFnKey(label))
            KEYBOARD_THEME_SKEUO -> physicalKeyBackground(pressed = pressed, premium = true, fn = isFnKey(label))
            else -> Neu.drawable(selectedNeuTokens(), dp(7).toFloat(), if (pressed) NeuLevel.PRESSED_SM else NeuLevel.RAISED_SM)
        }
    }

    private fun deckBackground(): Drawable {
        val theme = keyboardVisualTheme()
        if (theme == KEYBOARD_THEME_SEEME) return SeemeDrawables.panel(darkTint = true)
        if (theme == KEYBOARD_THEME_BRUSHED) return BrushedDrawables.panel(selectedNeuTokens().mode == NeuMode.DARK, resources.displayMetrics.density)
        if (theme == KEYBOARD_THEME_DEFAULT) return Neu.drawable(selectedNeuTokens(), dp(16).toFloat(), NeuLevel.RAISED)
        val light = keyboardLightMode(theme)
        val colors = if (light) {
            when (theme) {
                KEYBOARD_THEME_SKEUO -> intArrayOf(0xFFF1F3F6.toInt(), 0xFFE0E4EA.toInt(), 0xFFC9D0DA.toInt())
                KEYBOARD_THEME_CLICKS -> intArrayOf(0xFFECEFF4.toInt(), 0xFFDDE2E9.toInt(), 0xFFC7CED9.toInt())
                KEYBOARD_THEME_GOKEYS -> intArrayOf(0xFFF3F5F8.toInt(), 0xFFE2E6EC.toInt(), 0xFFC9D0DA.toInt())
                KEYBOARD_THEME_HYPER3D, KEYBOARD_THEME_HYPER3D_LIGHT -> intArrayOf(0xFFE8EBF0.toInt(), 0xFFD4D8E0.toInt(), 0xFFBEC4CF.toInt())
                else -> intArrayOf(0xFFECEEF2.toInt(), 0xFFDDE1E8.toInt(), 0xFFC9D0DA.toInt())
            }
        } else {
            when (theme) {
                KEYBOARD_THEME_SKEUO -> intArrayOf(0xFF24262D.toInt(), 0xFF101116.toInt(), 0xFF050506.toInt())
                KEYBOARD_THEME_CLICKS -> intArrayOf(0xFF1A1C21.toInt(), 0xFF111318.toInt(), 0xFF08090C.toInt(), 0xFF030304.toInt())
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
        return dp(8 + size * 3 / 100)
    }

    private fun keyVerticalInset(): Int {
        val size = KeyboardSettings.keyboardSize(this)
        val theme = keyboardVisualTheme()
        return if (theme == KEYBOARD_THEME_CLICKS || theme == KEYBOARD_THEME_GOKEYS || theme == KEYBOARD_THEME_BRUSHED || isHyper3dTheme(theme)) {
            dp(10 + size * 5 / 100)
        } else {
            dp(7 + size * 4 / 100)
        }
    }

    private fun keyboardTopPadding() = dp(4)
    private fun keyboardBottomPadding() = dp(2)

    private fun keyTextSize(label: String): Float {
        val size = KeyboardSettings.keyboardSize(this)
        if (keyboardTheme() == KEYBOARD_THEME_BRUSHED) {
            val brushedBase = when (label) {
                "shift", "." -> 22f
                "back" -> 19f
                "space" -> 18f
                "123", "abc", "enter" -> 15.5f
                "clicks" -> 13.5f
                else -> 20f
            }
            val brushedGrowth = when (label) {
                "123", "abc", "enter", "clicks" -> 1.5f
                "space" -> 2f
                else -> 2.5f
            }
            return brushedBase + (size * brushedGrowth / 100f)
        }
        val base = when (label) {
            "shift" -> 24f
            "space" -> 18f
            "123", "clicks", "enter", "back", "." -> 13.5f
            else -> 20f
        }
        val growth = when (label) {
            "shift" -> 2.5f
            "space" -> 2f
            "123", "clicks", "enter", "back", "." -> 1.5f
            else -> 2.5f
        }
        return base + (size * growth / 100f)
    }

    private fun textColor(label: String): Int {
        val theme = keyboardVisualTheme()
        if (isHyper3dTheme(theme)) {
            val visualTheme = hyper3dVisualTheme(theme)
            return when {
                visualTheme == KEYBOARD_THEME_HYPER3D_LIGHT && label == "enter" -> 0xFF104026.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D_LIGHT && isFnKey(label) -> 0xFF596170.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D_LIGHT -> 0xFF1E2633.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D_BLACK && label == "enter" -> 0xFFFF6B6B.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D_BLACK && label == "clicks" -> 0xFF9DB4FF.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D_BLACK && isFnKey(label) -> 0xFF9AA2B1.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D && label == "enter" -> 0xFFEAFFF2.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D && label == "clicks" -> 0xFFEAF0FF.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D && isFnKey(label) -> 0xFF9AA2B1.toInt()
                else -> 0xFFEEF1F7.toInt()
            }
        }
        if (theme == KEYBOARD_THEME_SEEME) {
            return when (label) {
                "enter" -> 0xFFFFFFFF.toInt()
                "clicks" -> 0xFFFF5A60.toInt()
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
                    "123", "clicks", "back", "shift", "." -> 0xFF6F7884.toInt()
                    else -> 0xFF202733.toInt()
                }
            } else {
                when (label) {
                    "enter" -> 0xFFFFFFFF.toInt()
                    "123", "clicks", "back", "shift", "." -> 0xFF9AA1AB.toInt()
                    else -> 0xFFF3F5F8.toInt()
                }
            }
        }
        return if (keyboardLightMode(theme)) {
            when (label) {
                "enter" -> Neu.ACCENT
                "clicks" -> 0xFF2F6C4C.toInt()
                "123", "back", "shift", "." -> 0xFF6F7884.toInt()
                else -> 0xFF202733.toInt()
            }
        } else {
            val tokens = selectedNeuTokens()
            when (label) {
                "enter" -> Neu.ACCENT
                "clicks" -> Neu.GREEN
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
        val clicks = keyboardTheme() == KEYBOARD_THEME_CLICKS
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
        val faceTop = if (pressed) 0xFF3A3E4A.toInt() else if (clicks) 0xFF22252B.toInt() else 0xFF34373E.toInt()
        val faceMid = if (pressed) 0xFF2A2D36.toInt() else if (clicks) 0xFF171A1F.toInt() else 0xFF202329.toInt()
        val faceBot = if (pressed) 0xFF151719.toInt() else if (clicks) 0xFF07080B.toInt() else 0xFF0B0C10.toInt()
        val skirt = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF050609.toInt()) }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(faceTop, faceMid, faceBot)).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), if (clicks) 0xFF111318.toInt() else 0xFF05060A.toInt())
        }
        val glint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(if (clicks) 0x10FFFFFF else 0x3DFFFFFF, 0x00FFFFFF)).apply { shape = GradientDrawable.OVAL }
        return LayerDrawable(arrayOf(skirt, face, glint)).apply {
            val drop = if (pressed) dp(1) else dp(if (clicks) 4 else 6)
            setLayerInset(0, dp(1), drop, dp(1), 0)
            setLayerInset(1, dp(2), 0, dp(2), drop)
            setLayerInset(2, dp(8), dp(5), dp(8), dp(if (clicks) 20 else 18))
        }
    }

    private fun physicalKeyBackground(pressed: Boolean, premium: Boolean, fn: Boolean): Drawable {
        val clicks = keyboardTheme() == KEYBOARD_THEME_CLICKS
        val radius = dp(when {
            premium -> 10
            clicks -> 5
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
                clicks -> 0xFF050609.toInt()
                else -> 0xFF07080B.toInt()
            },
            if (clicks) 0xFF020304.toInt() else 0xFF050609.toInt(),
            0xFF010102.toInt()
        )).apply {
            cornerRadius = radius
            setStroke(dp(1), 0xFF040507.toInt())
        }
        val outerRim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            when {
                pressed -> 0xFFFF8E68.toInt()
                premium -> 0xFF525866.toInt()
                clicks -> 0xFF24272F.toInt()
                else -> 0xFF34373E.toInt()
            },
            if (clicks) 0xFF05060A.toInt() else 0xFF090A0D.toInt()
        )).apply {
            cornerRadius = radius
            setStroke(dp(1), if (premium) 0xFF05060A.toInt() else 0xFF040507.toInt())
        }
        val faceColors = when {
            pressed -> intArrayOf(0xFFFF9B72.toInt(), 0xFFFF5A3C.toInt(), 0xFF9C2F11.toInt())
            premium -> intArrayOf(0xFF4B505B.toInt(), 0xFF2D323D.toInt(), 0xFF151821.toInt(), 0xFF08090D.toInt())
            clicks -> intArrayOf(0xFF2B2E35.toInt(), 0xFF202329.toInt(), 0xFF15171C.toInt(), 0xFF0A0B0E.toInt())
            else -> intArrayOf(0xFF34373E.toInt(), 0xFF202329.toInt(), 0xFF14161B.toInt(), 0xFF0B0C10.toInt())
        }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, faceColors).apply {
            cornerRadius = radius
            setStroke(dp(1), if (pressed) 0xFFFFB199.toInt() else if (premium) 0xFF3C4350.toInt() else if (clicks) 0xFF111318.toInt() else 0xFF05060A.toInt())
        }
        val topGlint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (pressed) 0x66FFFFFF else if (premium) 0x55FFFFFF else if (clicks) 0x20FFFFFF else 0x3DFFFFFF,
            0x00FFFFFF
        )).apply {
            cornerRadius = radius
        }
        val warmBleed = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(
            if (fn && !pressed) (if (clicks) 0x16F5C451 else 0x22F5C451) else if (pressed) 0x66FF5A3C else 0x00000000,
            0x00000000
        )).apply {
            cornerRadius = radius
        }
        return LayerDrawable(arrayOf(skirt, outerRim, face, warmBleed, topGlint)).apply {
            val drop = if (pressed) dp(1) else dp(when {
                premium -> 8
                clicks -> 8
                else -> 5
            })
            val side = if (premium) dp(2) else dp(if (clicks) 2 else 1)
            setLayerInset(0, dp(1), drop, dp(1), 0)
            setLayerInset(1, side, dp(if (pressed) 1 else 0), side, drop)
            setLayerInset(2, side + dp(1), dp(1), side + dp(1), drop + dp(if (premium) 1 else 0))
            setLayerInset(3, side + dp(2), dp(if (clicks) 5 else 3), side + dp(2), drop + dp(1))
            setLayerInset(4, side + dp(3), dp(2), side + dp(3), dp(if (premium) 19 else if (clicks) 24 else 20))
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
                label == "clicks" -> R.drawable.key_hyper3d_black_accent
                isFnKey(label) -> R.drawable.key_hyper3d_black_dark
                else -> R.drawable.key_hyper3d_black
            }
            KEYBOARD_THEME_HYPER3D_LIGHT -> when {
                label == "enter" -> R.drawable.key_hyper3d_light_go
                label == "clicks" -> R.drawable.key_hyper3d_light_accent
                isFnKey(label) -> R.drawable.key_hyper3d_light_dark
                else -> R.drawable.key_hyper3d_light
            }
            else -> return null
        }
        return runCatching { resources.getDrawable(id, theme) }.getOrNull()
    }

    private fun selectedNeuTokens(): NeuTokens {
        return when (themeMode()) {
            THEME_MODE_DARK -> Neu.Dark
            THEME_MODE_LIGHT -> Neu.Light
            else -> if (isSystemDarkMode()) Neu.Dark else Neu.Light
        }
    }

    private fun isSystemDarkMode(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun keyboardLightMode(theme: String): Boolean {
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

    private fun keyboardTheme(): String {
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
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(GO_KEY_COLOR_PREF, 0xFFFF5A3C.toInt())
    }

    private fun isFnKey(label: String) = label in setOf("123", "clicks", "back", "shift", ".")

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
        overlayParams = null
        VivoDockedExperiment.clearViewportTruncation(this)
        super.onDestroy()
    }

    companion object {
        private const val PREFS_NAME = "clicks"
        private const val KEYBOARD_THEME_PREF = "keyboard_theme"
        private const val KEYBOARD_THEME_DEFAULT = "default"
        private const val KEYBOARD_THEME_CLICKS = "clicks"
        private const val KEYBOARD_THEME_SKEUO = "skeuo"
        private const val KEYBOARD_THEME_GOKEYS = "gokeys"
        private const val KEYBOARD_THEME_HYPER3D = "hyper3d"
        private const val KEYBOARD_THEME_HYPER3D_BLACK = "hyper3d_black"
        private const val KEYBOARD_THEME_HYPER3D_LIGHT = "hyper3d_light"
        private const val KEYBOARD_THEME_BRUSHED = "brushed"
        private const val KEYBOARD_THEME_SEEME = "seeme"
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
