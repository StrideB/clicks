package com.fran.clicks.keyboard

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import com.fran.clicks.keyboard.KeyPreviewManager.Companion.dp

/**
 * Shows a magnified character bubble above the tapped key on ACTION_DOWN,
 * dismissed automatically after 180 ms.
 */
class KeyPreviewManager(private val context: Context) {

    companion object {
        fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()
    }

    private var popup: PopupWindow? = null
    private val handler = Handler(Looper.getMainLooper())
    private val dismiss = Runnable { popup?.dismiss(); popup = null }

    fun show(anchor: View, label: String) {
        if (label.length != 1 || !label[0].isLetter()) return
        handler.removeCallbacks(dismiss)
        popup?.dismiss()

        val tv = TextView(context).apply {
            text = label.uppercase()
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(0xFFF3F0E7.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF3A3E4A.toInt())
                cornerRadius = dp(context, 9).toFloat()
            }
        }

        val w = dp(context, 44); val h = dp(context, 44)
        popup = PopupWindow(tv, w, h, false).also { pw ->
            pw.isOutsideTouchable = false
            pw.isTouchable = false
            anchor.post {
                if (anchor.isAttachedToWindow) {
                    pw.showAsDropDown(anchor, 0, -(anchor.height + h + dp(context, 6)))
                }
            }
        }
        handler.postDelayed(dismiss, 180)
    }

    fun dismiss() {
        handler.removeCallbacks(dismiss)
        popup?.dismiss(); popup = null
    }
}
