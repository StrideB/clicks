package com.fran.clicks.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.TextView

/**
 * Drop-in TextView replacement for letter keys that draws a prediction word
 * in small green text near the top of the key — BlackBerry inline style.
 * All existing setText / setTextColor / setOnClickListener calls work unchanged.
 */
class DynamicFlickKeyView(context: Context) : TextView(context) {

    var upWordHint: String? = null
        private set

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4ADE80.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // Swipe-down symbol shown small at the bottom of the key (so users see where to flick down).
    private var symbolHint: String? = null
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var primaryLabel: String? = null
    private var primaryColor: Int = currentTextColor
    private var primaryTextSizePx: Float = 0f
    private var primaryTypeface: Typeface? = null

    fun setSymbolHint(sym: String?, color: Int) {
        if (symbolHint != sym || symbolPaint.color != color) {
            symbolHint = sym; symbolPaint.color = color; invalidate()
        }
    }

    fun setDrawnPrimaryLabel(label: String?, color: Int, textSizePx: Float, typeface: Typeface?) {
        primaryLabel = label
        primaryColor = color
        primaryTextSizePx = textSizePx
        primaryTypeface = typeface
        text = if (label == null) text else ""
        invalidate()
    }

    fun updatePrediction(word: String?) {
        if (upWordHint != word) { upWordHint = word; invalidate() }
    }

    private var keyW = 0
    private var keyH = 0
    private var faceInsetX = 0f
    private var faceInsetY = 0f

    fun setKeyFaceInsets(horizontalInsetPx: Int, verticalInsetPx: Int) {
        faceInsetX = horizontalInsetPx.toFloat().coerceAtLeast(0f)
        faceInsetY = verticalInsetPx.toFloat().coerceAtLeast(0f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        keyW = w
        keyH = h
        hintPaint.textSize = h * 0.20f
    }

    override fun onDraw(canvas: Canvas) {
        if (primaryLabel == null) super.onDraw(canvas)
        val faceTop = faceInsetY
        val faceBottom = keyH - faceInsetY
        val faceHeight = (faceBottom - faceTop).coerceAtLeast(keyH * 0.58f)
        // Swipe-down symbol at the bottom edge.
        symbolHint?.let { s ->
            if (keyH > 0) {
                symbolPaint.textSize = faceHeight * 0.135f
                symbolPaint.alpha = 130
                canvas.drawText(s, keyW / 2f, faceTop + faceHeight * 0.25f, symbolPaint)
                symbolPaint.alpha = 255
            }
        }
        primaryLabel?.let { label ->
            if (keyW > 0 && keyH > 0) {
                hintPaint.color = primaryColor
                hintPaint.typeface = primaryTypeface
                hintPaint.isFakeBoldText = false
                hintPaint.textSize = primaryTextSizePx.takeIf { it > 0f } ?: faceHeight * 0.44f
                val metrics = hintPaint.fontMetrics
                val centerY = faceTop + faceHeight * 0.54f
                val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
                canvas.drawText(label, keyW / 2f, baseline, hintPaint)
                hintPaint.typeface = null
                hintPaint.color = 0xFF4ADE80.toInt()
                hintPaint.isFakeBoldText = true
            }
        }
        val hint = upWordHint ?: return
        if (keyW == 0) return
        // Scale text down if the word is wider than the key (minus 4px margins each side)
        val maxWidth = (keyW - faceInsetX * 2f - 8f).coerceAtLeast(8f)
        val measured = hintPaint.measureText(hint)
        val savedSize = hintPaint.textSize
        if (measured > maxWidth) hintPaint.textSize = savedSize * (maxWidth / measured)
        val y = faceInsetY + hintPaint.textSize + 2f
        canvas.drawText(hint, keyW / 2f, y, hintPaint)
        hintPaint.textSize = savedSize
    }
}
