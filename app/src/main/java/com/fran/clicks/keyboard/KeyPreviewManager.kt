package com.fran.clicks.keyboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView

class KeyPreviewManager(private val context: Context) {

    private var popup: PopupWindow? = null
    private val handler = Handler(Looper.getMainLooper())
    private val dismiss = Runnable { popup?.dismiss(); popup = null }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun show(anchor: View, label: String) {
        if (label.length != 1 || !label[0].isLetter()) return
        handler.removeCallbacks(dismiss)
        popup?.dismiss()

        val size = dp(40)
        val textSizeSp = 20f

        val tv = TextView(context).apply {
            text = label.uppercase()
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            gravity = Gravity.CENTER
            setTextColor(0xFFF3F0E7.toInt())
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            background = GradientDrawable().apply {
                setColor(0xFF3A3E4A.toInt())
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = ViewGroup.LayoutParams(size, size)
        }

        // Measure so the popup knows its exact size before positioning
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        )

        popup = PopupWindow(tv, size, size, false).apply {
            isOutsideTouchable = false
            isTouchable = false
            elevation = dp(4).toFloat()
        }

        anchor.post {
            if (!anchor.isAttachedToWindow) return@post
            // Center popup horizontally over the key, position above it with a small gap
            val xOff = (anchor.width - size) / 2
            val yOff = -(size + anchor.height + dp(4))
            popup?.showAsDropDown(anchor, xOff, yOff)
        }

        handler.postDelayed(dismiss, 180)
    }

    fun dismiss() {
        handler.removeCallbacks(dismiss)
        popup?.dismiss()
        popup = null
    }
}
