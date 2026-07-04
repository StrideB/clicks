package com.fran.clicks.keyboard

import android.content.Context
import android.graphics.PointF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.Collections

/**
 * On-device neural glide decoder (Neuroswipe / IndicSwipe style).
 *
 * Slots in behind [StatisticalGlideTypingClassifier]: if a trained model is present in assets it
 * decodes; otherwise [isReady] stays false and callers fall back to the geometric classifier, so
 * shipping without a model changes nothing.
 *
 * Drop-in requirements (produced by the offline PyTorch → ONNX export):
 *   assets/neural_swipe_engine.onnx   — TransformerEncoder, input "src" [1, N, 4], output word logits
 *   assets/neural_swipe_vocab.txt     — one word per line; line index == model's vocab index
 *
 * The model's input MUST be normalized to a [0,1] bounding box (not raw pixels), so the same weights
 * work in both docked and widget keyboard modes regardless of on-screen size. That normalization is
 * done here, not in the graph.
 */
class NeuralSwipeEngine(private val context: Context) {

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null
    private var vocab: List<String> = emptyList()
    private val targetSequenceLength = 50   // fixed input steps; must match training

    val isReady: Boolean get() = ortSession != null && vocab.isNotEmpty()

    /** Loads model + vocab if both exist in assets. Safe to call off the main thread. */
    fun tryLoad(): Boolean {
        if (isReady) return true
        return runCatching {
            val names = context.assets.list("")?.toSet() ?: emptySet()
            if ("neural_swipe_engine.onnx" !in names || "neural_swipe_vocab.txt" !in names) return false
            vocab = context.assets.open("neural_swipe_vocab.txt").bufferedReader()
                .useLines { it.map(String::trim).filter(String::isNotEmpty).toList() }
            val modelBytes = context.assets.open("neural_swipe_engine.onnx").readBytes()
            ortSession = ortEnv.createSession(modelBytes)
            isReady
        }.getOrDefault(false)
    }

    /**
     * Decodes a raw swipe into ranked word candidates.
     * @param rawPoints screen-space path points captured during the gesture.
     * @param boundsLeft/Top/Width/Height the keyboard content box in the same coordinate space —
     *        used to normalize into [0,1] so the model is resolution- and mode-independent.
     * @param wordFrequencies app dictionary; used only to gently re-rank ties among model candidates.
     */
    fun decodeSwipePath(
        rawPoints: List<PointF>,
        boundsLeft: Float, boundsTop: Float, boundsWidth: Float, boundsHeight: Float,
        wordFrequencies: Map<String, Float> = emptyMap(),
        topK: Int = 5
    ): List<String> {
        val session = ortSession ?: return emptyList()
        if (rawPoints.size < 2 || boundsWidth <= 0f || boundsHeight <= 0f) return emptyList()

        // 1. Uniform arc-length resampling to a fixed length, then normalize into the [0,1] box.
        val resampled = resampleUniform(rawPoints, targetSequenceLength)
        val norm = FloatArray(targetSequenceLength * 2)
        for (i in 0 until targetSequenceLength) {
            norm[i * 2]     = ((resampled[i].x - boundsLeft) / boundsWidth).coerceIn(0f, 1f)
            norm[i * 2 + 1] = ((resampled[i].y - boundsTop) / boundsHeight).coerceIn(0f, 1f)
        }

        // 2. Feature extraction: [x, y, dx, dy] per step.
        val input = FloatBuffer.allocate(targetSequenceLength * 4)
        for (i in 0 until targetSequenceLength) {
            val x = norm[i * 2]; val y = norm[i * 2 + 1]
            val dx = if (i == 0) 0f else x - norm[(i - 1) * 2]
            val dy = if (i == 0) 0f else y - norm[(i - 1) * 2 + 1]
            input.put(x); input.put(y); input.put(dx); input.put(dy)
        }
        input.rewind()

        // 3. Inference.
        val shape = longArrayOf(1, targetSequenceLength.toLong(), 4)
        val logits = OnnxTensor.createTensor(ortEnv, input, shape).use { tensor ->
            session.run(Collections.singletonMap("src", tensor)).use { results ->
                @Suppress("UNCHECKED_CAST")
                (results[0].value as Array<FloatArray>)[0]
            }
        }

        // 4. Top-K over the model's own vocab (index-aligned), re-ranked by dictionary frequency on
        //    ties. A partial-sort keeps this cheap even with a 10k+ vocab.
        val n = minOf(logits.size, vocab.size)
        val idx = (0 until n).sortedWith(
            compareByDescending<Int> { logits[it] }
                .thenByDescending { wordFrequencies[vocab[it]] ?: 0f }
        )
        return idx.asSequence().map { vocab[it] }.take(topK).toList()
    }

    /** Resamples [points] to exactly [count] points evenly spaced along the path's arc length. */
    private fun resampleUniform(points: List<PointF>, count: Int): List<PointF> {
        if (points.size <= 1) return List(count) { points.firstOrNull() ?: PointF(0f, 0f) }
        val cumulative = FloatArray(points.size)
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            cumulative[i] = cumulative[i - 1] + kotlin.math.hypot(dx, dy)
        }
        val total = cumulative.last()
        if (total <= 0f) return List(count) { points.first() }
        val out = ArrayList<PointF>(count)
        var seg = 1
        for (k in 0 until count) {
            val target = total * k / (count - 1)
            while (seg < points.size - 1 && cumulative[seg] < target) seg++
            val segStart = cumulative[seg - 1]; val segEnd = cumulative[seg]
            val f = if (segEnd > segStart) (target - segStart) / (segEnd - segStart) else 0f
            val a = points[seg - 1]; val b = points[seg]
            out.add(PointF(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f))
        }
        return out
    }

    fun close() {
        runCatching { ortSession?.close() }
        ortSession = null
    }
}
