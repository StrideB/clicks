package com.fran.clicks.keyboard

import android.graphics.Rect
import kotlin.math.exp

/**
 * Gaussian spatial model: computes probability that a touch belongs to each key,
 * rather than using hard hit-box boundaries. Adapts sigma to actual key dimensions.
 */
class SpatialScorer {

    private data class KeyPoint(val label: String, val cx: Float, val cy: Float)

    private var keys: List<KeyPoint> = emptyList()
    private var sigmaX = 45.0
    private var sigmaY = 55.0

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

    /** Returns the best-matching key label for the given raw screen coordinates. */
    fun bestKey(rawX: Float, rawY: Float, bigramWeights: Map<Char, Double> = emptyMap()): String? {
        var bestLabel: String? = null
        var bestScore = -1.0
        for (key in keys) {
            val dx = (rawX - key.cx).toDouble()
            val dy = (rawY - key.cy).toDouble()
            val spatialProb = exp(-((dx * dx) / (2 * sigmaX * sigmaX) + (dy * dy) / (2 * sigmaY * sigmaY)))
            val langWeight = if (key.label.length == 1) bigramWeights[key.label[0]] ?: 1.0 else 1.0
            val score = spatialProb * langWeight
            if (score > bestScore) { bestScore = score; bestLabel = key.label }
        }
        return bestLabel
    }

    /** Spatial probability for a specific key — used by the swipe classifier. */
    fun probability(rawX: Float, rawY: Float, keyLabel: String): Double {
        val key = keys.firstOrNull { it.label == keyLabel } ?: return 0.0
        val dx = (rawX - key.cx).toDouble()
        val dy = (rawY - key.cy).toDouble()
        return exp(-((dx * dx) / (2 * sigmaX * sigmaX) + (dy * dy) / (2 * sigmaY * sigmaY)))
    }
}
