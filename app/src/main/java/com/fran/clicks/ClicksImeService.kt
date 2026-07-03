package com.fran.clicks

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView

class ClicksImeService : InputMethodService() {
    private var shifted = false
    private var deckView: LinearLayout? = null

    override fun onCreateInputView(): View {
        shifted = false
        return buildKeyboard().also { deckView = it }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        shifted = shouldStartShifted(attribute)
        refreshLabels()
    }

    private fun buildKeyboard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(8))
            background = deckBackground()
            minimumHeight = imeKeyboardHeight()
            listOf(
                "qwertyuiop".map { it.toString() },
                "asdfghjkl".map { it.toString() },
                listOf("shift") + "zxcvbnm".map { it.toString() } + listOf("back"),
                listOf("123", "clicks", "space", ".", "enter")
            ).forEachIndexed { rowIndex, row ->
                addView(keyRow(row, rowIndex), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply {
                    if (rowIndex > 0) topMargin = dp(4)
                })
            }
        }
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
            tag = label
            text = visualLabel(label)
            gravity = Gravity.CENTER
            includeFontPadding = false
            textSize = if (label == "space") 16f else 13.5f
            typeface = if (label == "enter") Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor(label))
            background = keyBackground(label)
            isClickable = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                handleKey(label)
            }
        }
    }

    private fun handleKey(label: String) {
        val input = currentInputConnection ?: return
        when (label) {
            "shift" -> {
                shifted = !shifted
                refreshLabels()
            }
            "back" -> {
                if (!input.deleteSurroundingText(1, 0)) {
                    keyEvent(KeyEvent.KEYCODE_DEL)
                }
            }
            "enter" -> keyEvent(KeyEvent.KEYCODE_ENTER)
            "space" -> input.commitText(" ", 1)
            "clicks" -> switchToNextInputMethod(false)
            "123" -> Unit
            else -> input.commitText(if (shifted && label.length == 1) label.uppercase() else label, 1)
        }
    }

    private fun keyEvent(code: Int) {
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun refreshLabels() {
        deckView?.let { deck ->
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

    private fun visualLabel(label: String): String {
        return when (label) {
            "shift" -> if (shifted) "⇧" else "↑"
            "back" -> "⌫"
            "enter" -> "GO"
            "space" -> "space"
            else -> if (shifted && label.length == 1) label.uppercase() else label
        }
    }

    private fun textColor(label: String): Int {
        return when (label) {
            "enter" -> 0xFFFF5A3C.toInt()
            "clicks" -> 0xFFF5C451.toInt()
            "123" -> 0xFF9AA2B1.toInt()
            else -> 0xFFE9ECF2.toInt()
        }
    }

    private fun keyWeight(label: String): Float {
        return when (label) {
            "space" -> 4.4f
            "123", "clicks", "enter", "back", "shift" -> 1.45f
            else -> 1f
        }
    }

    private fun imeKeyboardHeight(): Int {
        val size = KeyboardSettings.keyboardSize(this)
        return dp(238 + (size * 54 / 100))
    }

    private fun keyBackground(label: String): GradientDrawable {
        val colors = when (label) {
            "enter" -> intArrayOf(0xFF2A2624.toInt(), 0xFF151719.toInt(), 0xFF090A0D.toInt())
            "clicks" -> intArrayOf(0xFF24314A.toInt(), 0xFF151B27.toInt(), 0xFF080A0E.toInt())
            "123" -> intArrayOf(0xFF22252C.toInt(), 0xFF12151A.toInt(), 0xFF06070A.toInt())
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

    private fun shouldStartShifted(attribute: EditorInfo?): Boolean {
        val variation = attribute?.inputType ?: return false
        return variation and android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
