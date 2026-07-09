package com.fran.teclas.keyboard.neural

import com.fran.teclas.glide.KeyInfo
import com.fran.teclas.keyboard.neural.NeuralSwipeContract.FEATURE_DIM
import com.fran.teclas.keyboard.neural.NeuralSwipeContract.MAX_TRAJ

/** Encoder input built from a swipe: flat feature buffer + padding mask, both length [MAX_TRAJ]. */
class SwipeFeatures(
    /** float32, length MAX_TRAJ * FEATURE_DIM (row-major [t, f]). */
    val features: FloatArray,
    /** int64, length MAX_TRAJ; 1 = real point, 0 = padding. */
    val mask: LongArray,
    /** Number of real (non-padded) steps. */
    val length: Int
)

/**
 * Turns a raw captured swipe path into the encoder's input tensor, exactly matching
 * [NeuralSwipeContract]. This is where x/y are normalized into the keyboard's [0,1] box (so one model
 * serves both keyboard sizes), where velocity/acceleration are derived from the real event
 * timestamps, and where each step's nearest letter key is one-hot encoded.
 *
 * The identical math must live in `tools/neural_swipe/` so the model trains on the same features it
 * sees on-device.
 */
class SwipeFeaturizer {

    /**
     * @param path raw screen-space samples with timestamps.
     * @param boundsLeft/Top/Width/Height the letter-key bounding box in the same screen space.
     * @param keyCenters letter keys (screen-space centers) used for the nearest-key feature.
     */
    fun featurize(
        path: List<TimedPoint>,
        boundsLeft: Float, boundsTop: Float, boundsWidth: Float, boundsHeight: Float,
        keyCenters: List<KeyInfo>
    ): SwipeFeatures? {
        if (path.size < 2 || boundsWidth <= 0f || boundsHeight <= 0f) return null

        // Uniformly subsample (keeping both endpoints) when the gesture is longer than the model input.
        val steps = if (path.size <= MAX_TRAJ) path else subsample(path, MAX_TRAJ)
        val n = steps.size

        // Precompute normalized key centers once for the nearest-key lookup.
        val keys = keyCenters.filter { it.char in 'a'..'z' }
        val kx = FloatArray(keys.size); val ky = FloatArray(keys.size); val kIdx = IntArray(keys.size)
        for (i in keys.indices) {
            kx[i] = (keys[i].centerX - boundsLeft) / boundsWidth
            ky[i] = (keys[i].centerY - boundsTop) / boundsHeight
            kIdx[i] = keys[i].char - 'a'
        }

        val nx = FloatArray(n); val ny = FloatArray(n)
        val vx = FloatArray(n); val vy = FloatArray(n)
        for (i in 0 until n) {
            nx[i] = ((steps[i].x - boundsLeft) / boundsWidth).coerceIn(0f, 1f)
            ny[i] = ((steps[i].y - boundsTop) / boundsHeight).coerceIn(0f, 1f)
            if (i > 0) {
                val dtSec = ((steps[i].t - steps[i - 1].t).coerceAtLeast(1L)) / 1000f
                vx[i] = (nx[i] - nx[i - 1]) / dtSec
                vy[i] = (ny[i] - ny[i - 1]) / dtSec
            }
        }

        val features = FloatArray(MAX_TRAJ * FEATURE_DIM)
        val mask = LongArray(MAX_TRAJ)
        for (i in 0 until n) {
            val base = i * FEATURE_DIM
            val dtSec = if (i > 0) ((steps[i].t - steps[i - 1].t).coerceAtLeast(1L)) / 1000f else 1f
            features[base + 0] = nx[i]
            features[base + 1] = ny[i]
            features[base + 2] = vx[i]
            features[base + 3] = vy[i]
            features[base + 4] = if (i > 0) (vx[i] - vx[i - 1]) / dtSec else 0f
            features[base + 5] = if (i > 0) (vy[i] - vy[i - 1]) / dtSec else 0f
            val nearest = nearestKey(nx[i], ny[i], kx, ky, kIdx)
            if (nearest >= 0) features[base + NeuralSwipeContract.BASE_FEATURES + nearest] = 1f
            mask[i] = 1L
        }
        return SwipeFeatures(features, mask, n)
    }

    private fun nearestKey(x: Float, y: Float, kx: FloatArray, ky: FloatArray, kIdx: IntArray): Int {
        var best = -1; var bestD = Float.MAX_VALUE
        for (i in kx.indices) {
            val dx = kx[i] - x; val dy = ky[i] - y
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = kIdx[i] }
        }
        return best
    }

    /** Uniform index subsample to [count] points, always keeping the first and last. */
    private fun subsample(points: List<TimedPoint>, count: Int): List<TimedPoint> {
        val out = ArrayList<TimedPoint>(count)
        val last = points.size - 1
        for (k in 0 until count) {
            val idx = Math.round(k.toFloat() * last / (count - 1))
            out.add(points[idx])
        }
        return out
    }
}
