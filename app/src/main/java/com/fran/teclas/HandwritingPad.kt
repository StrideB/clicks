package com.fran.teclas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink

/**
 * S Pen handwriting for launcher search. On-device via ML Kit Digital Ink Recognition — the model
 * downloads once on first use and everything runs offline. Wrapped so a device without the model
 * (or before it finishes downloading) simply produces no text rather than crashing.
 */
class HandwritingRecognizer(private val tag: String = "TeclasHandwriting") {
    private val manager = RemoteModelManager.getInstance()
    private val model: DigitalInkRecognitionModel? = runCatching {
        val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US") ?: return@runCatching null
        DigitalInkRecognitionModel.builder(id).build()
    }.getOrNull()
    private var recognizer: DigitalInkRecognizer? = null
    @Volatile private var downloading = false

    init { ensureReady() }

    /** Kicks the one-time model download / recognizer build; safe to call repeatedly. */
    fun ensureReady() {
        val m = model ?: return
        if (recognizer != null || downloading) return
        downloading = true
        runCatching {
            manager.isModelDownloaded(m)
                .addOnSuccessListener { downloaded ->
                    if (downloaded) build() else download(m)
                }
                .addOnFailureListener { downloading = false; Log.w(tag, "isModelDownloaded failed", it) }
        }.onFailure { downloading = false; Log.w(tag, "ensureReady failed", it) }
    }

    private fun download(m: DigitalInkRecognitionModel) {
        runCatching {
            manager.download(m, DownloadConditions.Builder().build())
                .addOnSuccessListener { build() }
                .addOnFailureListener { downloading = false; Log.w(tag, "model download failed", it) }
        }.onFailure { downloading = false; Log.w(tag, "download() failed", it) }
    }

    private fun build() {
        val m = model ?: return
        recognizer = runCatching { DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).build()) }
            .onFailure { Log.w(tag, "getClient failed", it) }
            .getOrNull()
        downloading = false
        Log.i(tag, "recognizer ready=${recognizer != null}")
    }

    val isReady: Boolean get() = recognizer != null

    /** Recognizes [ink] into text; [cb] gets the top candidate, or null if unavailable. */
    fun recognize(ink: Ink, cb: (String?) -> Unit) {
        val r = recognizer
        if (r == null) { ensureReady(); cb(null); return }
        runCatching {
            r.recognize(ink)
                .addOnSuccessListener { result -> cb(result.candidates.firstOrNull()?.text) }
                .addOnFailureListener { Log.w(tag, "recognize failed", it); cb(null) }
        }.onFailure { Log.w(tag, "recognize() threw", it); cb(null) }
    }

    fun close() { runCatching { recognizer?.close() } }
}

/**
 * Transparent overlay that sits on top of the keyboard keys. It intercepts ONLY stylus touches
 * (finger touches return false and fall through to the keys, so normal typing is untouched). A
 * stylus stroke draws ink and, after a short pause, is recognized to text and handed to [onText].
 */
class HandwritingPadView(
    context: Context,
    private val recognizer: HandwritingRecognizer,
    private val accentColor: Int,
    private val onEngageChanged: (Boolean) -> Unit,
    private val onText: (String) -> Unit,
) : View(context) {

    private val density = context.resources.displayMetrics.density
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = 4f * density; color = accentColor
    }
    private val sheetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 14f * density
        color = adjust(accentColor, 0.55f)
    }

    private val committedPaths = ArrayList<Path>()
    private var currentPath: Path? = null
    private var inkBuilder = Ink.builder()
    private var strokeBuilder: Ink.Stroke.Builder? = null
    private var hasInk = false

    var engaged = false
        private set

    private val commitRunnable = Runnable { commit() }
    private val COMMIT_DELAY = 700L

    private fun setEngaged(v: Boolean) {
        if (engaged == v) return
        engaged = v
        onEngageChanged(v)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Finger (or anything that isn't a stylus) falls through to the keyboard below.
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(commitRunnable)
                setEngaged(true)
                startStroke(event)
            }
            MotionEvent.ACTION_MOVE -> addToStroke(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endStroke()
                if (hasInk) postDelayed(commitRunnable, COMMIT_DELAY)
            }
        }
        return true
    }

    private fun startStroke(event: MotionEvent) {
        currentPath = Path().apply { moveTo(event.x, event.y) }
        strokeBuilder = Ink.Stroke.builder().apply { addPoint(Ink.Point.create(event.x, event.y, event.eventTime)) }
        hasInk = true
        invalidate()
    }

    private fun addToStroke(event: MotionEvent) {
        val path = currentPath ?: return
        val sb = strokeBuilder ?: return
        for (i in 0 until event.historySize) {
            path.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
            sb.addPoint(Ink.Point.create(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalEventTime(i)))
        }
        path.lineTo(event.x, event.y)
        sb.addPoint(Ink.Point.create(event.x, event.y, event.eventTime))
        invalidate()
    }

    private fun endStroke() {
        currentPath?.let { committedPaths.add(it) }
        currentPath = null
        strokeBuilder?.let { inkBuilder.addStroke(it.build()) }
        strokeBuilder = null
        invalidate()
    }

    private fun commit() {
        if (!hasInk) return
        val ink = runCatching { inkBuilder.build() }.getOrNull()
        clearInk()
        if (ink == null) return
        recognizer.recognize(ink) { text ->
            if (!text.isNullOrBlank()) onText(text.trim())
        }
    }

    /** Wipe the ink and drop back to the pass-through keyboard state. */
    fun clearInk() {
        removeCallbacks(commitRunnable)
        committedPaths.clear()
        currentPath = null
        inkBuilder = Ink.builder()
        strokeBuilder = null
        hasInk = false
        setEngaged(false)
    }

    override fun onDraw(canvas: Canvas) {
        if (engaged) {
            // A whisper of a glass sheet so ink reads over a busy keyboard while writing.
            sheetPaint.color = adjust(Color.BLACK, 0.18f)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), sheetPaint)
            if (!hasInk) {
                canvas.drawText("Write with your pen", width / 2f, height / 2f, hintPaint)
            }
        }
        committedPaths.forEach { canvas.drawPath(it, inkPaint) }
        currentPath?.let { canvas.drawPath(it, inkPaint) }
    }

    private fun adjust(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }
}
