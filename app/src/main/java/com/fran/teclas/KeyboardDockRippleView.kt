package com.fran.teclas

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import kotlin.math.max

internal class KeyboardDockRippleView(context: Context, private val color: Int) : View(context) {
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private var progress = 0f
    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520L
            interpolator = DecelerateInterpolator(1.7f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    (parent as? ViewGroup)?.removeView(this@KeyboardDockRippleView)
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height - dp(48).toFloat()
        val maxRadius = max(width, height) * 0.72f
        val radius = lerp(dp(18).toFloat(), maxRadius, progress)
        val fade = (1f - progress).coerceIn(0f, 1f)
        val alpha = (112 * fade * fade).toInt().coerceIn(0, 112)

        fillPaint.color = adjustAlpha(color, (18 * fade * fade).toInt())
        canvas.drawCircle(cx, cy, radius * 0.58f, fillPaint)

        strokePaint.strokeWidth = lerp(dp(2).toFloat(), dp(1).toFloat(), progress)
        strokePaint.color = adjustAlpha(color, alpha)
        canvas.drawCircle(cx, cy, radius, strokePaint)

        strokePaint.strokeWidth = dp(1).toFloat()
        strokePaint.color = adjustAlpha(Color.WHITE, (38 * fade).toInt().coerceIn(0, 38))
        canvas.drawCircle(cx, cy, radius * 0.82f, strokePaint)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun lerp(start: Float, end: Float, t: Float): Float = start + (end - start) * t

    private fun adjustAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    companion object {
        fun play(parent: ViewGroup, color: Int) {
            parent.clipChildren = false
            parent.clipToPadding = false
            parent.addView(
                KeyboardDockRippleView(parent.context, color),
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
    }
}
