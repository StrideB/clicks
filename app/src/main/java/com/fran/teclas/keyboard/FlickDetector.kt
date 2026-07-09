package com.fran.teclas.keyboard

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

enum class FlickDirection { TAP, UP, DOWN, LEFT, RIGHT }

/**
 * Classifies a completed touch gesture (start → end) into a tap or 4-directional flick.
 * flickRadius: minimum pixel displacement to qualify as a flick (not a tap).
 */
class FlickDetector(private val flickRadius: Float = 42f) {

    fun classify(startX: Float, startY: Float, endX: Float, endY: Float): FlickDirection {
        val dx = endX - startX
        val dy = endY - startY
        val dist = hypot(dx.toDouble(), dy.toDouble())
        if (dist < flickRadius) return FlickDirection.TAP

        val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        return when {
            deg >= -45 && deg < 45   -> FlickDirection.RIGHT
            deg >= 45  && deg < 135  -> FlickDirection.DOWN
            deg >= -135 && deg < -45 -> FlickDirection.UP
            else                     -> FlickDirection.LEFT
        }
    }

    /** True when the gesture is a clean horizontal-left swipe (for word-delete). */
    fun isLeftSwipe(startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
        val dx = startX - endX   // positive = moved left
        val dy = abs(endY - startY)
        return dx > 120 && dx > dy * 2.5f
    }
}
