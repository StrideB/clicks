package com.fran.clicks

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class DockedKeyboardService : Service() {
    private var windowManager: WindowManager? = null
    private var deckView: View? = null
    private var shifted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!KeyboardSettings.isDocked(this) || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showDeck()
    }

    private fun showDeck() {
        if (deckView != null) return
        val deck = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(8))
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
                0,
                1f
            ).apply {
                if (rowIndex > 0) topMargin = dp(4)
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
        deckView = deck
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
        return TextView(this).apply {
            text = visualLabel(label)
            gravity = Gravity.CENTER
            includeFontPadding = false
            textSize = if (label == "space") 16f else 13.5f
            typeface = if (label == "enter") Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (label == "enter") 0xFFFF5A3C.toInt() else 0xFFE9ECF2.toInt())
            background = keyBackground(label)
            isClickable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                handleKey(label)
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
            "clicks", "123" -> Unit
            else -> sendKey(if (shifted && label.length == 1) label.uppercase() else label)
        }
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
            "space" -> 4.4f
            "123", "clicks", "enter", "back", "shift" -> 1.45f
            else -> 1f
        }
    }

    private fun overlayKeyboardHeight(): Int {
        val size = KeyboardSettings.keyboardSize(this)
        return dp(238 + (size * 54 / 100))
    }

    private fun keyBackground(label: String): GradientDrawable {
        val colors = when (label) {
            "enter" -> intArrayOf(0xFF2A2624.toInt(), 0xFF151719.toInt(), 0xFF090A0D.toInt())
            "clicks" -> intArrayOf(0xFF24314A.toInt(), 0xFF151B27.toInt(), 0xFF080A0E.toInt())
            else -> intArrayOf(0xFF2A2E36.toInt(), 0xFF171A20.toInt(), 0xFF090A0D.toInt())
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = dp(13).toFloat()
            setStroke(dp(1), 0x332A3038)
        }
    }

    private fun deckBackground(): GradientDrawable {
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xF51A1D23.toInt(),
            0xFA0D0F13.toInt(),
            0xFF050608.toInt()
        )).apply {
            cornerRadii = floatArrayOf(dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), 0f, 0f, 0f, 0f)
            setStroke(dp(1), 0x22FFFFFF)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        deckView?.let { view -> runCatching { windowManager?.removeView(view) } }
        deckView = null
        super.onDestroy()
    }

    companion object {
        fun overlaySettingsIntent(context: Context): Intent {
            return Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        }
    }
}
