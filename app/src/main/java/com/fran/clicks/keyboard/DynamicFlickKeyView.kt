package com.fran.clicks.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
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

    fun setSymbolHint(sym: String?, color: Int) {
        if (symbolHint != sym || symbolPaint.color != color) {
            symbolHint = sym; symbolPaint.color = color; invalidate()
        }
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
        super.onDraw(canvas)
        // Swipe-down symbol at the bottom edge.
        symbolHint?.let { s ->
            if (keyH > 0) {
                val faceTop = faceInsetY
                val faceBottom = keyH - faceInsetY
                val faceHeight = (faceBottom - faceTop).coerceAtLeast(keyH * 0.58f)
                symbolPaint.textSize = faceHeight * 0.16f
                canvas.drawText(s, keyW / 2f, faceBottom - faceHeight * 0.16f, symbolPaint)
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
