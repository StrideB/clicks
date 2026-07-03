package com.fran.clicks.keyboard

import kotlin.math.abs

/**
 * Correction + prediction over a frequency-ranked word list.
 *
 * Ranking uses a *keyboard-aware* Damerau–Levenshtein distance: substituting a key for a
 * physically adjacent one (a fat-finger slip) is cheap, a transposition is cheap, and word
 * frequency only breaks near-ties. This is what stops the old engine from "correcting" a
 * one-slip typo into a totally different, more common word.
 */
class PredictionEngine(private val wordFrequencies: Map<String, Float>) {

    // QWERTY adjacency (used both to widen the candidate net and to price fat-finger slips).
    private val adj = mapOf(
        'q' to "wa",  'w' to "qesa", 'e' to "wrsd", 'r' to "etdf", 't' to "ryfg",
        'y' to "tugh", 'u' to "yijh", 'i' to "uokj", 'o' to "iplk", 'p' to "ol",
        'a' to "qwsz", 's' to "awedxz",'d' to "serfcx",'f' to "drtgvc",'g' to "ftyhbv",
        'h' to "gyujnb",'j' to "huikmn",'k' to "jiolm",'l' to "kop",
        'z' to "asx", 'x' to "zsdc",'c' to "xdfv",'v' to "cfgb",'b' to "vghn",
        'n' to "bhjm",'m' to "njk"
    )

    /** Predictions for the suggestion strip / live routing. */
    fun getSuggestions(typed: String, maxCount: Int = 3, ngramBoost: List<String> = emptyList()): List<String> {
        if (typed.length < 2) return ngramBoost.take(maxCount)
        val maxDist = if (typed.length <= 4) 1.6 else 2.4
        val ranked = rank(typed, maxDist).map { it.first }
        return (ngramBoost + ranked).distinct().take(maxCount)
    }

    /**
     * Returns a high-confidence correction for [typed], or null when we shouldn't touch it.
     * Never corrects a word that's already in the dictionary.
     */
    fun bestCorrection(typed: String): String? {
        if (typed.length < 3) return null
        val t = typed.lowercase()
        if (!t.all { it in 'a'..'z' }) return null
        if (wordFrequencies.containsKey(t)) return null           // already a real word
        val maxAccept = if (t.length >= 6) 2.2 else 1.35
        val ranked = rank(t, maxAccept)
        val best = ranked.firstOrNull() ?: return null
        if (best.second > maxAccept) return null
        // Guard against low-confidence swaps: if the two best candidates are basically tied in
        // distance, only accept when the winner is also clearly the more common word.
        val runnerUp = ranked.getOrNull(1)
        if (runnerUp != null && abs(runnerUp.second - best.second) < 0.25) {
            val bf = wordFrequencies[best.first] ?: 0f
            val rf = wordFrequencies[runnerUp.first] ?: 0f
            if (rf > bf) return null
        }
        return best.first
    }

    // Returns (word, weightedDistance) sorted best-first.
    private fun rank(typedRaw: String, maxDist: Double): List<Pair<String, Double>> {
        val t = typedRaw.lowercase()
        if (t.length < 2) return emptyList()
        val fc = t[0]
        val lenMin = maxOf(1, t.length - 2)
        val lenMax = t.length + 2
        return wordFrequencies.asSequence()
            .filter { (w, _) ->
                w.length in lenMin..lenMax &&
                    (w[0] == fc || adj[fc]?.contains(w[0]) == true)
            }
            .mapNotNull { (w, freq) ->
                val d = weightedDistance(t, w, maxDist + 0.5)
                if (d > maxDist) null
                else Triple(w, d, d - 0.18 * freq)   // frequency only nudges near-ties
            }
            .sortedBy { it.third }
            .map { it.first to it.second }
            .toList()
    }

    /**
     * Damerau–Levenshtein where a substitution between adjacent keys costs 0.4 (fat-finger),
     * a transposition costs 0.5, and everything else costs 1.0. Early-exits past [cutoff].
     */
    private fun weightedDistance(a: String, b: String, cutoff: Double): Double {
        val n = a.length; val m = b.length
        if (abs(n - m) > 3) return Double.MAX_VALUE
        val prev2 = DoubleArray(m + 1)
        val prev = DoubleArray(m + 1)
        val curr = DoubleArray(m + 1)
        for (j in 0..m) prev[j] = j.toDouble()
        for (i in 1..n) {
            curr[0] = i.toDouble()
            var rowMin = curr[0]
            for (j in 1..m) {
                val ca = a[i - 1]; val cb = b[j - 1]
                val subCost = when {
                    ca == cb -> 0.0
                    adj[ca]?.contains(cb) == true -> 0.4
                    else -> 1.0
                }
                var v = minOf(prev[j] + 1.0, curr[j - 1] + 1.0, prev[j - 1] + subCost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = minOf(v, prev2[j - 2] + 0.5)
                }
                curr[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > cutoff) return Double.MAX_VALUE
            System.arraycopy(prev, 0, prev2, 0, m + 1)
            System.arraycopy(curr, 0, prev, 0, m + 1)
        }
        return prev[m]
    }
}
