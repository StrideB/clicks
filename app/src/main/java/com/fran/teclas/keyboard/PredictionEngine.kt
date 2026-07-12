package com.fran.teclas.keyboard

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

    // First-letter index over the union dictionary. Every lookup below is bounded by the typed
    // word's first letter (plus its adjacent keys), so nothing walks the full 20k–120k-word union
    // on the typing hot path. Built once; the engine is immutable after construction, so it's safe
    // to read from a background prediction thread.
    private class Entry(val word: String, val freq: Float)
    private val byFirstChar: Map<Char, List<Entry>> = run {
        val buckets = HashMap<Char, ArrayList<Entry>>(64)
        for ((w, f) in wordFrequencies) {
            if (w.isEmpty()) continue
            buckets.getOrPut(w[0]) { ArrayList() }.add(Entry(w, f))
        }
        buckets
    }

    /**
     * True if any dictionary word extends [prefix] beyond itself — i.e. the user could still be
     * mid-typing a real word. Live (no-space) autocorrect uses this to only rewrite "dead-end"
     * strings that can't become a real word, so it never mangles a word in progress.
     */
    /** True if [word] is exactly a word in this dictionary. Used for language detection. */
    fun isDictWord(word: String): Boolean = wordFrequencies.containsKey(word.lowercase())

    fun isPrefixOfDictWord(prefix: String): Boolean {
        if (prefix.isEmpty()) return false
        val p = prefix.lowercase()
        val bucket = byFirstChar[p[0]] ?: return false
        return bucket.any { it.word.length > p.length && it.word.startsWith(p) }
    }

    /**
     * Per-letter weights for the NEXT character given what's typed so far, drawn from the frequency
     * of dictionary words that extend [prefix]. Values are >= 1.0 (a likely continuation gets a
     * boost, everything else stays neutral at 1.0), so a caller can use them to gently break a
     * near-boundary touch tie without ever penalizing the key the finger actually hit. Empty when
     * there's no prefix or no continuation. Cheap: skips all words whose first letter differs.
     */
    fun nextCharWeights(prefix: String): Map<Char, Double> {
        if (prefix.isEmpty()) return emptyMap()
        val p = prefix.lowercase()
        if (!p[0].isLetter()) return emptyMap()
        val fc = p[0]
        val tally = HashMap<Char, Double>()
        var total = 0.0
        for (e in byFirstChar[fc].orEmpty()) {
            val w = e.word
            if (w.length <= p.length) continue
            if (!w.startsWith(p)) continue
            val nc = w[p.length]
            val wt = e.freq.toDouble() + 1e-3
            tally[nc] = (tally[nc] ?: 0.0) + wt
            total += wt
        }
        if (total <= 0.0) return emptyMap()
        val out = HashMap<Char, Double>(tally.size)
        for ((c, v) in tally) out[c] = 1.0 + NEXT_CHAR_BOOST * (v / total)
        return out
    }

    /**
     * Predictions for the suggestion strip / live routing. [ngramBoost] holds personalized
     * next-word predictions for the *preceding* word (from the n-gram store). With nothing typed
     * yet they ARE the prediction; once the user starts a word, only the boost entries that extend
     * what's typed are valid completions — the rest would be unrelated words, so they're filtered.
     */
    fun getSuggestions(typed: String, maxCount: Int = 3, ngramBoost: List<String> = emptyList()): List<String> {
        if (typed.length < 2) return ngramBoost.take(maxCount)
        val maxDist = if (typed.length <= 4) 1.6 else 2.4
        val ranked = rank(typed, maxDist).map { it.first }
        val boost = ngramBoost.filter { it.length > typed.length && it.startsWith(typed, ignoreCase = true) }
        return (boost + ranked).distinct().take(maxCount)
    }

    /**
     * Returns a high-confidence correction for [typed], or null when we shouldn't touch it.
     * Never corrects a word that's already in the dictionary.
     *
     * [contextNextWords] are the user's personalized next-word predictions for the *preceding* word
     * (from the n-gram store). When supplied, a near-distance candidate that the user actually types
     * in this context wins the tiebreak — fixing homophone-style slips (their/there, form/from)
     * that a context-free string metric can't, since it picks the word that fits the sentence.
     * Passing an empty list reproduces the original context-free behavior exactly.
     */
    fun bestCorrection(typed: String, contextNextWords: List<String> = emptyList()): String? {
        if (typed.length < 3) return null
        val t = typed.lowercase()
        if (!t.all { it in 'a'..'z' }) return null
        if (wordFrequencies.containsKey(t)) return null           // already a real word
        val maxAccept = if (t.length >= 6) 2.2 else 1.35
        val ranked = rank(t, maxAccept)
        val best = ranked.firstOrNull() ?: return null
        if (best.second > maxAccept) return null
        // Context-aware tiebreak: among candidates within a small distance window of the best, if
        // one is something the user types after the preceding word, prefer it over the raw winner.
        if (contextNextWords.isNotEmpty()) {
            val ctx = contextNextWords.mapTo(HashSet()) { it.lowercase() }
            val contextPick = ranked.firstOrNull { (w, d) -> w != t && d <= best.second + 0.5 && w in ctx }
            if (contextPick != null) return contextPick.first
        }
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

    // Returns (word, weightedDistance) sorted best-first. Candidates come from the typed first
    // letter's bucket plus its adjacent keys' buckets (a fat-fingered first letter), never the
    // whole dictionary.
    private fun rank(typedRaw: String, maxDist: Double): List<Pair<String, Double>> {
        val t = typedRaw.lowercase()
        if (t.length < 2) return emptyList()
        val fc = t[0]
        val lenMin = maxOf(1, t.length - 2)
        val lenMax = t.length + 2
        val out = ArrayList<Triple<String, Double, Double>>()
        val firstChars = adj[fc]?.let { "$fc$it" } ?: fc.toString()
        for (c in firstChars) {
            for (e in byFirstChar[c].orEmpty()) {
                val w = e.word
                if (w.length !in lenMin..lenMax) continue
                val d = weightedDistance(t, w, maxDist + 0.5)
                if (d > maxDist) continue
                out.add(Triple(w, d, d - 0.18 * e.freq))   // frequency only nudges near-ties
            }
        }
        out.sortBy { it.third }
        return out.map { it.first to it.second }
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
    private companion object {
        // Max multiplicative boost a fully-dominant next-letter can add in an ambiguous tie-break.
        private const val NEXT_CHAR_BOOST = 1.5
    }
}
