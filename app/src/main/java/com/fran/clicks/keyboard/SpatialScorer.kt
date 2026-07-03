package com.fran.clicks.keyboard

import android.graphics.Rect
import kotlin.math.exp

/**
 * Gaussian spatial model: computes probability that a touch belongs to each key,
 * rather than using hard hit-box boundaries. Adapts sigma to actual key dimensions.
 *
 * It also learns this user's systematic touch bias online: if taps consistently land a few px
 * low/left of the visual centers, [recordTap] shifts a running offset so future taps are scored as
 * if re-centered. That shrinks the "dead zone / near-miss" feeling without moving a single pixel.
 */
class SpatialScorer {

    private data class KeyPoint(val label: String, val cx: Float, val cy: Float)

    private var keys: List<KeyPoint> = emptyList()
    private var sigmaX = 45.0
    private var sigmaY = 55.0

    // Learned systematic offset (device px) between where this user taps and the key centers.
    private var offsetX = 0.0
    private var offsetY = 0.0

    fun setKeys(bounds: Map<String, Rect>) {
        keys = bounds.entries.mapNotNull { (label, rect) ->
            if (label.isEmpty()) null
            else KeyPoint(label, rect.exactCenterX(), rect.exactCenterY())
        }
        val widths = bounds.values.map { it.width().toDouble() }
        val heights = bounds.values.map { it.height().toDouble() }
        if (widths.isNotEmpty()) sigmaX = widths.average() * 0.48
        if (heights.isNotEmpty()) sigmaY = heights.average() * 0.44
    }

    fun setLearnedOffset(x: Double, y: Double) { offsetX = x; offsetY = y }
    fun learnedOffsetX(): Double = offsetX
    fun learnedOffsetY(): Double = offsetY

    /**
     * Returns the best-matching key label for the given raw screen coordinates. The learned bias is
     * subtracted first, so a user who habitually taps off-center still resolves to the right key.
     * With [letterOnly] the winner is chosen among letter keys only (used for tap snapping).
     */
    fun bestKey(rawX: Float, rawY: Float, bigramWeights: Map<Char, Double> = emptyMap(), letterOnly: Boolean = false): String? {
        val ax = rawX - offsetX
        val ay = rawY - offsetY
        var bestLabel: String? = null
        var bestScore = -1.0
        for (key in keys) {
            if (letterOnly && (key.label.length != 1 || !key.label[0].isLetter())) continue
            val dx = ax - key.cx
            val dy = ay - key.cy
            val spatialProb = exp(-((dx * dx) / (2 * sigmaX * sigmaX) + (dy * dy) / (2 * sigmaY * sigmaY)))
            val langWeight = if (key.label.length == 1) bigramWeights[key.label[0]] ?: 1.0 else 1.0
            val score = spatialProb * langWeight
            if (score > bestScore) { bestScore = score; bestLabel = key.label }
        }
        return bestLabel
    }

    /**
     * Learn from a confident letter tap: nudge the running offset toward this tap's displacement
     * from the letter key it clearly belongs to. Ambiguous taps near a boundary are ignored, so the
     * model tracks systematic bias, not noise. The offset is clamped so one stray tap can't skew it.
     */
    fun recordTap(rawX: Float, rawY: Float) {
        if (keys.isEmpty()) return
        val ax = rawX - offsetX
        val ay = rawY - offsetY
        var best: KeyPoint? = null
        var bestProb = -1.0
        for (key in keys) {
            if (key.label.length != 1 || !key.label[0].isLetter()) continue
            val dx = ax - key.cx
            val dy = ay - key.cy
            val p = exp(-((dx * dx) / (2 * sigmaX * sigmaX) + (dy * dy) / (2 * sigmaY * sigmaY)))
            if (p > bestProb) { bestProb = p; best = key }
        }
        val k = best ?: return
        if (bestProb < 0.55) return   // ambiguous / outlier tap — don't learn from it
        offsetX += LEARN_RATE * ((rawX - k.cx) - offsetX)
        offsetY += LEARN_RATE * ((rawY - k.cy) - offsetY)
        offsetX = offsetX.coerceIn(-sigmaX * 1.6, sigmaX * 1.6)
        offsetY = offsetY.coerceIn(-sigmaY * 1.6, sigmaY * 1.6)
    }

    /** Spatial probability for a specific key — used by the swipe classifier. */
    fun probability(rawX: Float, rawY: Float, keyLabel: String): Double {
        val key = keys.firstOrNull { it.label == keyLabel } ?: return 0.0
        val dx = (rawX - offsetX) - key.cx
        val dy = (rawY - offsetY) - key.cy
        return exp(-((dx * dx) / (2 * sigmaX * sigmaX) + (dy * dy) / (2 * sigmaY * sigmaY)))
    }

    companion object {
        private const val LEARN_RATE = 0.05
    }
}
