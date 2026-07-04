package com.fran.clicks.keyboard

import android.graphics.Rect
import kotlin.math.exp

/**
 * Gaussian spatial model: computes probability that a touch belongs to each key, rather than using
 * hard hit-box boundaries. Adapts sigma to actual key dimensions.
 *
 * It learns this user's touch bias online at two levels:
 *  - a global offset (fast prior that covers keys with little data), and
 *  - a per-key offset (where the user actually lands for each individual key).
 * [recordTap] nudges both from confident taps; [bestKey] scores against the learned "effective"
 * centers. This shrinks the near-miss / dead-zone feeling and handles a single oddly-placed key
 * that a single global offset can't. State survives sessions via [exportState] / [importState].
 */
class SpatialScorer {

    private data class KeyPoint(val label: String, val cx: Float, val cy: Float)

    private var keys: List<KeyPoint> = emptyList()
    private var sigmaX = 45.0
    private var sigmaY = 55.0

    // Global fallback bias (device px) and per-key learned offsets from the geometric center.
    private var offsetX = 0.0
    private var offsetY = 0.0
    private val keyOffset = HashMap<String, DoubleArray>()   // label -> [dx, dy]

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

    private fun effOffset(label: String): DoubleArray {
        val o = keyOffset[label] ?: return doubleArrayOf(offsetX, offsetY)
        return o
    }

    /**
     * Returns the best-matching key label for the given raw screen coordinates, scored against each
     * key's learned effective center. With [letterOnly] the winner is chosen among letter keys only.
     *
     * [contextWeights], when supplied, provides per-letter language weights (>= 1.0) used ONLY to
     * break near-boundary ties: a confident tap — one key clearly closest — is always returned as-is
     * (we never override where the finger obviously went). Only when the top two keys are spatially
     * close do we let a linguistically-likely next-letter win, and since the weights only ever boost
     * (never penalize), the decision is nudged toward a plausible neighbor, never away from the tap.
     * The provider is a lambda so its cost is paid only on the rare ambiguous tap.
     */
    fun bestKey(
        rawX: Float,
        rawY: Float,
        letterOnly: Boolean = false,
        contextWeights: (() -> Map<Char, Double>)? = null
    ): String? {
        if (keys.isEmpty()) return null
        val scores = ArrayList<Pair<KeyPoint, Double>>(keys.size)
        var top: KeyPoint? = null
        var topScore = -1.0
        var runnerScore = -1.0
        for (key in keys) {
            if (letterOnly && (key.label.length != 1 || !key.label[0].isLetter())) continue
            val off = effOffset(key.label)
            val dx = rawX - (key.cx + off[0])
            val dy = rawY - (key.cy + off[1])
            val p = exp(-((dx * dx) / (2 * sigmaX * sigmaX) + (dy * dy) / (2 * sigmaY * sigmaY)))
            scores.add(key to p)
            when {
                p > topScore -> { runnerScore = topScore; topScore = p; top = key }
                p > runnerScore -> runnerScore = p
            }
        }
        val best = top ?: return null
        // Confident tap (or no context): trust geometry, never override.
        if (contextWeights == null || runnerScore <= 0.0 || topScore / runnerScore >= DOMINANCE) return best.label
        val weights = contextWeights()
        if (weights.isEmpty()) return best.label
        // Ambiguous: among the near-top candidates, let a likely continuation win.
        var chosen = best.label
        var chosenAdj = topScore * weightFor(best.label, weights)
        for ((key, p) in scores) {
            if (p < topScore * BAND) continue
            val adj = p * weightFor(key.label, weights)
            if (adj > chosenAdj) { chosenAdj = adj; chosen = key.label }
        }
        return chosen
    }

    private fun weightFor(label: String, weights: Map<Char, Double>): Double =
        if (label.length == 1) weights[label[0].lowercaseChar()] ?: 1.0 else 1.0

    /**
     * Learn from a confident letter tap: nudge the tapped key's per-key offset (and the global
     * prior) toward the tap's displacement from that key's geometric center. Ambiguous taps near a
     * boundary are ignored so the model tracks systematic bias, not noise; offsets are clamped so
     * one stray tap can't skew the layout.
     */
    fun recordTap(rawX: Float, rawY: Float) {
        if (keys.isEmpty()) return
        var best: KeyPoint? = null
        var bestProb = -1.0
        for (key in keys) {
            if (key.label.length != 1 || !key.label[0].isLetter()) continue
            val off = effOffset(key.label)
            val dx = rawX - (key.cx + off[0])
            val dy = rawY - (key.cy + off[1])
            val p = exp(-((dx * dx) / (2 * sigmaX * sigmaX) + (dy * dy) / (2 * sigmaY * sigmaY)))
            if (p > bestProb) { bestProb = p; best = key }
        }
        val k = best ?: return
        if (bestProb < 0.55) return   // ambiguous / outlier tap — don't learn from it
        val maxX = sigmaX * 1.6
        val maxY = sigmaY * 1.6
        val arr = keyOffset.getOrPut(k.label) { doubleArrayOf(offsetX, offsetY) }
        arr[0] = (arr[0] + LEARN_RATE * ((rawX - k.cx) - arr[0])).coerceIn(-maxX, maxX)
        arr[1] = (arr[1] + LEARN_RATE * ((rawY - k.cy) - arr[1])).coerceIn(-maxY, maxY)
        // Global prior updates slower; it only backfills keys the user hasn't hit much yet.
        offsetX = (offsetX + GLOBAL_RATE * ((rawX - k.cx) - offsetX)).coerceIn(-maxX, maxX)
        offsetY = (offsetY + GLOBAL_RATE * ((rawY - k.cy) - offsetY)).coerceIn(-maxY, maxY)
    }

    /** Spatial probability for a specific key — used by the swipe classifier. */
    fun probability(rawX: Float, rawY: Float, keyLabel: String): Double {
        val key = keys.firstOrNull { it.label == keyLabel } ?: return 0.0
        val off = effOffset(key.label)
        val dx = rawX - (key.cx + off[0])
        val dy = rawY - (key.cy + off[1])
        return exp(-((dx * dx) / (2 * sigmaX * sigmaX) + (dy * dy) / (2 * sigmaY * sigmaY)))
    }

    /** Serialize learned state: "gx,gy|label:dx,dy;label:dx,dy;...". Safe to persist in prefs. */
    fun exportState(): String {
        val sb = StringBuilder()
        sb.append(offsetX).append(',').append(offsetY).append('|')
        for ((label, o) in keyOffset) {
            if (label.length != 1) continue
            sb.append(label).append(':').append(o[0]).append(',').append(o[1]).append(';')
        }
        return sb.toString()
    }

    /** Restore state produced by [exportState]. Malformed input is ignored. */
    fun importState(state: String) {
        if (state.isBlank()) return
        runCatching {
            val parts = state.split('|')
            val g = parts[0].split(',')
            offsetX = g[0].toDouble(); offsetY = g[1].toDouble()
            keyOffset.clear()
            if (parts.size > 1) {
                for (entry in parts[1].split(';')) {
                    if (entry.isBlank()) continue
                    val kv = entry.split(':')
                    val dxy = kv[1].split(',')
                    keyOffset[kv[0]] = doubleArrayOf(dxy[0].toDouble(), dxy[1].toDouble())
                }
            }
        }
    }

    companion object {
        private const val LEARN_RATE = 0.06
        private const val GLOBAL_RATE = 0.02
        // Tie-break tuning: only kick in when the top key is < DOMINANCE x the runner-up, and only
        // reconsider candidates within BAND of the top spatial score.
        private const val DOMINANCE = 1.6
        private const val BAND = 0.65
    }
}
