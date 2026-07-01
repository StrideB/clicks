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

    fun updatePrediction(word: String?) {
        if (upWordHint != word) { upWordHint = word; invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        hintPaint.textSize = h * 0.22f   // ~22% of key height — scales with keyboard size
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val hint = upWordHint ?: return
        // Clip to avoid drawing outside key bounds
        val y = hintPaint.textSize * 1.05f
        canvas.drawText(hint, width / 2f, y, hintPaint)
    }
}
