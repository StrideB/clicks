package com.fran.teclas.spacetoday.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

/** Cheap native right-edge hint for Space Today. It pulses once when new work lands. */
class HomeEdgeHint(context: Context, private val onOpen: () -> Unit) {
    private val tab = FrameLayout(context).apply {
        alpha = 0.52f
        isClickable = true
        setOnClickListener { onOpen() }
    }
    private var hasNew = false
    private var accent = 0xFFC9A7FF.toInt()

    init { redraw() }

    fun setHasNewActivity(hasNew: Boolean) {
        if (this.hasNew == hasNew) return
        this.hasNew = hasNew
        redraw()
        if (hasNew) tab.animate().alpha(0.95f).setDuration(180).withEndAction {
            tab.animate().alpha(0.58f).setDuration(420).start()
        }.start()
    }

    fun setAccent(colorArgb: Int) {
        accent = colorArgb
        redraw()
    }

    fun view(): View = tab

    private fun redraw() {
        tab.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            adjustAlpha(accent, if (hasNew) 0.70f else 0.36f),
            adjustAlpha(accent, 0.08f)
        )).apply {
            cornerRadii = floatArrayOf(18f, 18f, 0f, 0f, 0f, 0f, 18f, 18f)
        }
        tab.foregroundGravity = Gravity.CENTER
    }

    private fun adjustAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}
