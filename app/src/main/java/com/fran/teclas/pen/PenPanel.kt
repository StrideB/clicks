package com.fran.teclas.pen

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The complete pen-mode surface — action strip (ABC · language · SPACE · ⌫ · ⏎) over the
 * handwriting canvas — shared verbatim by the launcher keyboard deck and the system IME so
 * both hosts get the identical writing experience. Hosts supply theme colors and the text
 * commit callbacks; recognition is wired here through [InkRecognizers].
 */
object PenPanel {

    class Callbacks(
        val languageTag: () -> String,
        val onText: (String) -> Unit,
        val onSpace: () -> Unit,
        val onBackspace: () -> Unit,
        val onEnter: () -> Unit,
        val onExit: () -> Unit,
        val onStatus: (String) -> Unit,
    )

    fun build(
        context: Context,
        accent: Int,
        ink: Int,
        inkDim: Int,
        panelFill: Int,
        line: Int,
        callbacks: Callbacks,
    ): View {
        fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

        fun chip(label: String, color: Int, weight: Float, onTap: () -> Unit): TextView =
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 10f
                letterSpacing = 0.12f
                typeface = Typeface.MONOSPACE
                setTextColor(color)
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(7).toFloat()
                    setStroke(dp(1), line)
                }
                isClickable = true
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
                    .apply { marginEnd = dp(6) }
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onTap()
                }
            }

        val canvas = HandwritingPanelView(
            context,
            inkColor = accent,
            hintColor = inkDim,
            onInk = { inkData ->
                InkRecognizers.recognize(
                    callbacks.languageTag(), inkData,
                    onText = callbacks.onText,
                    onStatus = callbacks.onStatus,
                )
            },
        ).apply {
            background = GradientDrawable().apply {
                setColor(panelFill)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), line)
            }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(6), dp(4), dp(6), dp(6))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(chip("ABC", ink, 0.9f) { callbacks.onExit() })
                addView(TextView(context).apply {
                    text = "✍ ${callbacks.languageTag().substringBefore('-').uppercase()}"
                    gravity = Gravity.CENTER
                    textSize = 9f
                    letterSpacing = 0.14f
                    typeface = Typeface.MONOSPACE
                    setTextColor(accent)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f)
                })
                addView(chip("SPACE", ink, 1.4f) { callbacks.onSpace() })
                addView(chip("⌫", ink, 0.8f) { callbacks.onBackspace() })
                addView(chip("⏎", accent, 0.8f) { callbacks.onEnter() })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply {
                bottomMargin = dp(5)
            })

            addView(canvas, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }
}
