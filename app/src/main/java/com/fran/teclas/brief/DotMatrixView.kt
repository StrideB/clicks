package com.fran.teclas.brief

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * A minimal 5×7 LED dot-matrix text view — the Nothing-phone aesthetic: every glyph is a grid of
 * lit and unlit dots. Off dots render faintly so the panel reads as a real dot-matrix display,
 * not just floating pixels. Pure Canvas, no Compose, so it drops straight into the brief card's
 * View tree. Font covers A–Z, 0–9, and a few separators; unknown chars render as blank cells.
 */
class DotMatrixView(context: Context) : View(context) {

    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glyphs: List<List<String>> = emptyList()

    var dotSize = dp(3.2f)
    var dotGap = dp(1.4f)

    var text: String = ""
        set(value) {
            field = value
            glyphs = value.uppercase().map { FONT[it] ?: FONT[' ']!! }
            requestLayout(); invalidate()
        }

    fun setColors(on: Int, off: Int) {
        onPaint.color = on
        offPaint.color = off
        invalidate()
    }

    private fun cell() = dotSize + dotGap
    private fun glyphW() = 5 * cell()
    private fun charGap() = cell() // one blank column between glyphs

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = glyphs.size
        val w = if (n == 0) 0f else n * glyphW() + (n - 1) * charGap()
        val h = 7 * cell()
        setMeasuredDimension(
            resolveSize((w + paddingLeft + paddingRight).toInt(), widthMeasureSpec),
            resolveSize((h + paddingTop + paddingBottom).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val r = dotSize / 2f
        var ox = paddingLeft.toFloat()
        for (g in glyphs) {
            for (row in 0 until 7) {
                val bits = g[row]
                for (col in 0 until 5) {
                    val cx = ox + col * cell() + r
                    val cy = paddingTop + row * cell() + r
                    canvas.drawCircle(cx, cy, r, if (bits[col] == '1') onPaint else offPaint)
                }
            }
            ox += glyphW() + charGap()
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private companion object {
        val FONT: Map<Char, List<String>> = mapOf(
            'A' to listOf("01110","10001","10001","11111","10001","10001","10001"),
            'B' to listOf("11110","10001","10001","11110","10001","10001","11110"),
            'C' to listOf("01110","10001","10000","10000","10000","10001","01110"),
            'D' to listOf("11100","10010","10001","10001","10001","10010","11100"),
            'E' to listOf("11111","10000","10000","11110","10000","10000","11111"),
            'F' to listOf("11111","10000","10000","11110","10000","10000","10000"),
            'G' to listOf("01110","10001","10000","10111","10001","10001","01111"),
            'H' to listOf("10001","10001","10001","11111","10001","10001","10001"),
            'I' to listOf("01110","00100","00100","00100","00100","00100","01110"),
            'J' to listOf("00111","00010","00010","00010","00010","10010","01100"),
            'K' to listOf("10001","10010","10100","11000","10100","10010","10001"),
            'L' to listOf("10000","10000","10000","10000","10000","10000","11111"),
            'M' to listOf("10001","11011","10101","10101","10001","10001","10001"),
            'N' to listOf("10001","10001","11001","10101","10011","10001","10001"),
            'O' to listOf("01110","10001","10001","10001","10001","10001","01110"),
            'P' to listOf("11110","10001","10001","11110","10000","10000","10000"),
            'Q' to listOf("01110","10001","10001","10001","10101","10010","01101"),
            'R' to listOf("11110","10001","10001","11110","10100","10010","10001"),
            'S' to listOf("01111","10000","10000","01110","00001","00001","11110"),
            'T' to listOf("11111","00100","00100","00100","00100","00100","00100"),
            'U' to listOf("10001","10001","10001","10001","10001","10001","01110"),
            'V' to listOf("10001","10001","10001","10001","10001","01010","00100"),
            'W' to listOf("10001","10001","10001","10101","10101","11011","10001"),
            'X' to listOf("10001","10001","01010","00100","01010","10001","10001"),
            'Y' to listOf("10001","10001","01010","00100","00100","00100","00100"),
            'Z' to listOf("11111","00001","00010","00100","01000","10000","11111"),
            '0' to listOf("01110","10001","10011","10101","11001","10001","01110"),
            '1' to listOf("00100","01100","00100","00100","00100","00100","01110"),
            '2' to listOf("01110","10001","00001","00010","00100","01000","11111"),
            '3' to listOf("11111","00010","00100","00010","00001","10001","01110"),
            '4' to listOf("00010","00110","01010","10010","11111","00010","00010"),
            '5' to listOf("11111","10000","11110","00001","00001","10001","01110"),
            '6' to listOf("00110","01000","10000","11110","10001","10001","01110"),
            '7' to listOf("11111","00001","00010","00100","01000","01000","01000"),
            '8' to listOf("01110","10001","10001","01110","10001","10001","01110"),
            '9' to listOf("01110","10001","10001","01111","00001","00010","01100"),
            ':' to listOf("00000","00100","00100","00000","00100","00100","00000"),
            '.' to listOf("00000","00000","00000","00000","00000","00110","00110"),
            '-' to listOf("00000","00000","00000","11111","00000","00000","00000"),
            '/' to listOf("00001","00010","00010","00100","01000","01000","10000"),
            ' ' to listOf("00000","00000","00000","00000","00000","00000","00000"),
        )
    }
}
