package com.fran.teclas.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.TextView
import kotlin.math.min

/**
 * Drop-in TextView replacement for letter keys. Draws the letter label and the small swipe-down
 * symbol hint. (The old BlackBerry-style per-key word prediction was removed — the suggestion strip
 * is now the single source of word suggestions.)
 * All existing setText / setTextColor / setOnClickListener calls work unchanged.
 */
class DynamicFlickKeyView(context: Context) : TextView(context) {

    // Always null now — BlackBerry per-key prediction removed. Kept so existing callers still compile.
    val upWordHint: String? = null

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
    private var labelVerticalBias = 0.52f
    private var symbolVerticalBias = 0.24f
    private var primaryMaxFaceScale = 0.62f
    private var symbolFaceScale = 0.16f
    private var extraBottomFaceInset = 0f
    private var engraveSymbolHint = false

    fun setSymbolHint(sym: String?, color: Int) {
        if (symbolHint != sym || symbolPaint.color != color) {
            symbolHint = sym; symbolPaint.color = color; invalidate()
        }
    }

    fun setDrawnPrimaryLabel(label: String?, color: Int, textSizePx: Float, typeface: Typeface?) {
        // Chrome refresh runs after every shift/auto-cap flip; skipping identical values keeps an
        // unchanged key from invalidating on each space.
        if (primaryLabel == label && primaryColor == color &&
            primaryTextSizePx == textSizePx && primaryTypeface == typeface) return
        primaryLabel = label
        primaryColor = color
        primaryTextSizePx = textSizePx
        primaryTypeface = typeface
        text = if (label == null) text else ""
        invalidate()
    }

    fun setLabelPlacement(
        labelBias: Float,
        symbolBias: Float = 0.24f,
        labelMaxScale: Float = 0.62f,
        symbolScale: Float = 0.16f,
        extraBottomInsetPx: Int = 0,
        engravedSymbols: Boolean = false
    ) {
        val newLabelBias = labelBias.coerceIn(0.28f, 0.62f)
        val newSymbolBias = symbolBias.coerceIn(0.04f, 0.36f)
        val newMaxScale = labelMaxScale.coerceIn(0.36f, 0.68f)
        val newSymbolScale = symbolScale.coerceIn(0.08f, 0.22f)
        val newBottomInset = extraBottomInsetPx.toFloat().coerceAtLeast(0f)
        if (labelVerticalBias == newLabelBias && symbolVerticalBias == newSymbolBias &&
            primaryMaxFaceScale == newMaxScale && symbolFaceScale == newSymbolScale &&
            extraBottomFaceInset == newBottomInset && engraveSymbolHint == engravedSymbols) return
        labelVerticalBias = newLabelBias
        symbolVerticalBias = newSymbolBias
        primaryMaxFaceScale = newMaxScale
        symbolFaceScale = newSymbolScale
        extraBottomFaceInset = newBottomInset
        engraveSymbolHint = engravedSymbols
        invalidate()
    }

    /** No-op: BlackBerry-style per-key word prediction was removed (suggestion strip only). */
    fun updatePrediction(word: String?) { }

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
        val faceBottom = keyH - faceInsetY - extraBottomFaceInset
        val faceHeight = (faceBottom - faceTop).coerceAtLeast(1f)
        // Swipe-down symbol at the bottom edge.
        symbolHint?.let { s ->
            if (keyH > 0) {
                symbolPaint.textSize = faceHeight * symbolFaceScale
                symbolPaint.alpha = 230
                val symbolMetrics = symbolPaint.fontMetrics
                val symbolCenterY = faceTop + faceHeight * symbolVerticalBias
                val symbolBaseline = symbolCenterY - (symbolMetrics.ascent + symbolMetrics.descent) / 2f
                if (engraveSymbolHint) {
                    val originalColor = symbolPaint.color
                    symbolPaint.color = 0x99000000.toInt()
                    symbolPaint.alpha = 150
                    canvas.drawText(s, keyW / 2f, symbolBaseline + 1.4f, symbolPaint)
                    symbolPaint.color = 0x66FFFFFF
                    symbolPaint.alpha = 80
                    canvas.drawText(s, keyW / 2f, symbolBaseline - 0.8f, symbolPaint)
                    symbolPaint.color = originalColor
                    symbolPaint.alpha = 215
                }
                canvas.drawText(s, keyW / 2f, symbolBaseline, symbolPaint)
                symbolPaint.alpha = 255
            }
        }
        primaryLabel?.let { label ->
            if (keyW > 0 && keyH > 0) {
                hintPaint.color = primaryColor
                hintPaint.typeface = primaryTypeface
                hintPaint.isFakeBoldText = false
                val requestedSize = primaryTextSizePx.takeIf { it > 0f } ?: faceHeight * 0.48f
                hintPaint.textSize = min(requestedSize, faceHeight * primaryMaxFaceScale)
                val metrics = hintPaint.fontMetrics
                val centerY = faceTop + faceHeight * labelVerticalBias
                val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
                canvas.drawText(label, keyW / 2f, baseline, hintPaint)
                hintPaint.typeface = null
                hintPaint.color = 0xFF4ADE80.toInt()
                hintPaint.isFakeBoldText = true
            }
        }
    }
}
