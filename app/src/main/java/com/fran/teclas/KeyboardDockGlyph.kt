package com.fran.teclas

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.sin

/**
 * Shared Teclas dock-key face. The key still uses the existing input routing; this only replaces
 * the old logo/text face with a compact docked/undocked hardware-state glyph.
 */
internal object KeyboardDockGlyph {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconRect = RectF()
    private val slotRect = RectF()
    private val keyRect = RectF()
    private val arrowPath = Path()

    fun draw(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        docked: Boolean,
        ink: Int,
        accent: Int,
        phase: Float
    ) {
        val w = (right - left).coerceAtLeast(1f)
        val h = (bottom - top).coerceAtLeast(1f)
        val size = minOf(w, h)
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        iconRect.set(cx - size * 0.48f, cy - size * 0.44f, cx + size * 0.48f, cy + size * 0.44f)
        val pulse = (sin((phase.coerceIn(0f, 1f) * 2f * PI)).toFloat() + 1f) * 0.5f

        val slotTop = iconRect.top + size * 0.60f
        slotRect.set(iconRect.left + size * 0.13f, slotTop, iconRect.right - size * 0.13f, slotTop + size * 0.15f)
        fillPaint.shader = null
        fillPaint.color = adjustAlpha(ink, if (docked) 0.22f else 0.13f)
        canvas.drawRoundRect(slotRect, size * 0.055f, size * 0.055f, fillPaint)
        strokePaint.strokeWidth = (size * 0.035f).coerceAtLeast(1.2f)
        strokePaint.color = adjustAlpha(ink, if (docked) 0.50f else 0.28f)
        canvas.drawRoundRect(slotRect, size * 0.055f, size * 0.055f, strokePaint)

        val floatLift = if (docked) 0f else size * (0.17f + 0.035f * pulse)
        val keyTop = iconRect.top + size * 0.19f - floatLift
        keyRect.set(iconRect.left + size * 0.12f, keyTop, iconRect.right - size * 0.12f, keyTop + size * 0.33f)
        fillPaint.color = adjustAlpha(Color.BLACK, if (docked) 0.28f else 0.20f)
        canvas.drawRoundRect(keyRect.left + size * 0.035f, keyRect.top + size * 0.075f, keyRect.right + size * 0.035f, keyRect.bottom + size * 0.075f, size * 0.08f, size * 0.08f, fillPaint)
        fillPaint.color = adjustAlpha(ink, if (docked) 0.20f else 0.16f)
        canvas.drawRoundRect(keyRect, size * 0.095f, size * 0.095f, fillPaint)
        strokePaint.strokeWidth = (size * 0.04f).coerceAtLeast(1.35f)
        strokePaint.color = adjustAlpha(ink, 0.72f)
        canvas.drawRoundRect(keyRect, size * 0.095f, size * 0.095f, strokePaint)

        val dotY = keyRect.centerY()
        val keyDot = size * 0.034f
        val startX = keyRect.left + size * 0.17f
        repeat(3) { i ->
            fillPaint.color = adjustAlpha(if (i == 1) accent else ink, if (i == 1) 0.86f else 0.38f)
            canvas.drawCircle(startX + i * size * 0.19f, dotY, keyDot, fillPaint)
        }

        val arrowCx = iconRect.centerX()
        val arrowY = if (docked) iconRect.top + size * 0.07f + size * 0.018f * pulse else slotRect.bottom + size * 0.13f
        arrowPath.reset()
        if (docked) {
            arrowPath.moveTo(arrowCx - size * 0.11f, arrowY)
            arrowPath.lineTo(arrowCx, arrowY - size * 0.09f)
            arrowPath.lineTo(arrowCx + size * 0.11f, arrowY)
        } else {
            arrowPath.moveTo(arrowCx - size * 0.11f, arrowY - size * 0.08f)
            arrowPath.lineTo(arrowCx, arrowY)
            arrowPath.lineTo(arrowCx + size * 0.11f, arrowY - size * 0.08f)
        }
        strokePaint.strokeWidth = (size * 0.055f).coerceAtLeast(1.65f)
        strokePaint.color = adjustAlpha(accent, if (docked) 0.84f else 0.92f)
        canvas.drawPath(arrowPath, strokePaint)
    }
}
