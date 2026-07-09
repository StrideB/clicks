package com.fran.teclas.keyboard

import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Slop-bucket debouncer: rejects ghost taps from micro-slides and
 * impossible sub-35ms keypresses.
 */
class TouchDebouncer(viewConfiguration: ViewConfiguration) {

    private val touchSlop = viewConfiguration.scaledTouchSlop.toFloat()
    private var startX = 0f
    private var startY = 0f
    private var downTime = 0L

    /** Call from onTouchEvent. Returns true only for deliberate, settled taps. */
    fun onTouch(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y; downTime = event.eventTime; false
            }
            MotionEvent.ACTION_UP -> {
                val dx = abs(event.x - startX)
                val dy = abs(event.y - startY)
                val duration = event.eventTime - downTime
                duration >= 35 && dx <= touchSlop && dy <= touchSlop
            }
            else -> false
        }
    }

    fun reset() { downTime = 0 }
}
