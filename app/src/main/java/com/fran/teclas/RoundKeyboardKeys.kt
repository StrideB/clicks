package com.fran.teclas

import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.min

internal fun isRoundKeyboardKey(label: String): Boolean =
    label == "enter" || label == "123" || label == "abc"

internal fun roundKeyboardKeyDrawable(label: String, drawable: Drawable, insetPx: Int = 0): Drawable {
    if (!isRoundKeyboardKey(label)) return drawable
    return CenteredRoundKeyDrawable(drawable, insetPx)
}

private class CenteredRoundKeyDrawable(
    private val inner: Drawable,
    private val insetPx: Int
) : Drawable() {
    private val clipPath = Path()
    private val clipRect = RectF()

    override fun onBoundsChange(bounds: Rect) {
        val side = (min(bounds.width(), bounds.height()) - insetPx * 2).coerceAtLeast(1)
        val left = bounds.left + (bounds.width() - side) / 2
        val top = bounds.top + (bounds.height() - side) / 2
        inner.setBounds(left, top, left + side, top + side)
    }

    override fun draw(canvas: Canvas) {
        val b = inner.bounds
        clipRect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        clipPath.reset()
        clipPath.addOval(clipRect, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        inner.draw(canvas)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        inner.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        inner.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

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
