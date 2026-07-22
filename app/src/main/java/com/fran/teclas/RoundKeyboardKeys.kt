package com.fran.teclas

import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.min

internal fun isRoundKeyboardKey(label: String): Boolean =
    label == "enter" || label == "123" || label == "abc"

internal fun View.applyRoundKeyboardKeyOutline(label: String) {
    if (!isRoundKeyboardKey(label)) return
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val side = min(view.width, view.height)
            val left = (view.width - side) / 2
            val top = (view.height - side) / 2
            outline.setOval(left, top, left + side, top + side)
        }
    }
    clipToOutline = true
}

internal inline fun Canvas.withRoundKeyboardKeyClip(label: String, bounds: Rect, draw: () -> Unit) {
    if (!isRoundKeyboardKey(label)) {
        draw()
        return
    }

    val side = min(bounds.width(), bounds.height()).toFloat()
    val left = bounds.left + (bounds.width() - side) / 2f
    val top = bounds.top + (bounds.height() - side) / 2f
    val clip = Path().apply {
        addOval(RectF(left, top, left + side, top + side), Path.Direction.CW)
    }

    save()
    clipPath(clip)
    draw()
    restore()
}
