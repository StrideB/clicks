package com.fran.teclas.pen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.google.mlkit.vision.digitalink.Ink

/**
 * The writing surface of pen mode: captures stylus (or finger) strokes, renders the wet ink,
 * and after a short quiet period hands the accumulated [Ink] to [onInk] and clears itself —
 * the same write-pause-commit rhythm as Gboard's handwriting keyboard.
 */
@SuppressLint("ViewConstructor")
class HandwritingPanelView(
    context: Context,
    inkColor: Int,
    private val hintColor: Int,
    private val onInk: (Ink) -> Unit,
) : View(context) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = inkColor
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hintColor
        textAlign = Paint.Align.CENTER
        textSize = dp(13f)
        typeface = android.graphics.Typeface.MONOSPACE
        letterSpacing = 0.14f
    }

    private val paths = ArrayList<Path>()
    private var activePath: Path? = null
    private val strokes = ArrayList<Ink.Stroke.Builder>()
    private var activeStroke: Ink.Stroke.Builder? = null

    private val commitRunnable = Runnable { commitInk() }

    /** Quiet period after pen-up before the strokes are recognized. */
    private val commitDelayMs = 700L

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (paths.isEmpty() && activePath == null) {
            canvas.drawText("✍  WRITE", width / 2f, height / 2f + hintPaint.textSize / 3f, hintPaint)
        }
        paths.forEach { canvas.drawPath(it, strokePaint) }
        activePath?.let { canvas.drawPath(it, strokePaint) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The keyboard deck's glide layout must not steal the stroke mid-word.
                parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(commitRunnable)
                activePath = Path().apply { moveTo(event.x, event.y) }
                activeStroke = Ink.Stroke.builder().apply {
                    addPoint(Ink.Point.create(event.x, event.y, event.eventTime))
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val path = activePath ?: return true
                val stroke = activeStroke ?: return true
                for (h in 0 until event.historySize) {
                    path.lineTo(event.getHistoricalX(h), event.getHistoricalY(h))
                    stroke.addPoint(Ink.Point.create(
                        event.getHistoricalX(h), event.getHistoricalY(h), event.getHistoricalEventTime(h)))
                }
                path.lineTo(event.x, event.y)
                stroke.addPoint(Ink.Point.create(event.x, event.y, event.eventTime))
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                activePath?.let {
                    // A bare tap still counts as a dot stroke (i-dots, periods).
                    it.lineTo(event.x + 0.1f, event.y + 0.1f)
                    paths.add(it)
                }
                activeStroke?.let { strokes.add(it) }
                activePath = null
                activeStroke = null
                invalidate()
                postDelayed(commitRunnable, commitDelayMs)
            }
            MotionEvent.ACTION_CANCEL -> {
                activePath = null
                activeStroke = null
                invalidate()
                if (strokes.isNotEmpty()) postDelayed(commitRunnable, commitDelayMs)
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(commitRunnable)
        super.onDetachedFromWindow()
    }

    private fun commitInk() {
        if (strokes.isEmpty()) return
        val ink = Ink.builder().apply { strokes.forEach { addStroke(it.build()) } }.build()
        strokes.clear()
        paths.clear()
        invalidate()
        onInk(ink)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
