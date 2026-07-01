package com.fran.clicks.keyboard

import kotlin.math.min

/**
 * Combines Levenshtein edit-distance correction with frequency-weighted ranking.
 * Searches only words sharing the first letter or an adjacent key to the first letter,
 * keeping each call well under 1ms on the word list.
 */
class PredictionEngine(private val wordFrequencies: Map<String, Float>) {

    // QWERTY adjacency for first-letter miss tolerance
    private val adj = mapOf(
        'q' to "wa",  'w' to "qea", 'e' to "wrs", 'r' to "etd", 't' to "ryf",
        'y' to "tug", 'u' to "yih", 'i' to "uoj", 'o' to "ipk", 'p' to "ol",
        'a' to "qsz", 's' to "awdx",'d' to "sefx",'f' to "drgv",'g' to "ftyhb",
        'h' to "gyunb",'j' to "humk",'k' to "jiml",'l' to "ko",
        'z' to "asx", 'x' to "zsdc",'c' to "xvdf",'v' to "cfgb",'b' to "vghn",
        'n' to "bhjm",'m' to "njk"
    )

    /**
     * Returns up to [maxCount] corrected/predicted words for [typed].
     * [ngramBoost] words appear first (from the Room n-gram DB).
     */
    fun getSuggestions(typed: String, maxCount: Int = 3, ngramBoost: List<String> = emptyList()): List<String> {
        if (typed.length < 2) return ngramBoost.take(maxCount)
        val t = typed.lowercase()
        val fc = t[0]
        val maxDist = if (t.length <= 4) 1 else 2
        val lenMin = maxOf(1, t.length - 2)
        val lenMax = t.length + 2

        val candidates = wordFrequencies.asSequence()
            .filter { (w, _) ->
                w.length in lenMin..lenMax &&
                (w[0] == fc || adj[fc]?.contains(w[0]) == true)
            }
            .map { (w, freq) ->
                val dist = levenshtein(t, w)
                val score = dist * 1000 - (freq * 8000).toInt()
                Triple(w, dist, score)
            }
            .filter { it.second <= maxDist }
            .sortedBy { it.third }
            .take(maxCount + 2)
            .map { it.first }
            .toList()

        return (ngramBoost + candidates).distinct().take(maxCount)
    }

    fun levenshtein(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) for (j in 1..s2.length) {
            val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
            dp[i][j] = min(min(dp[i-1][j] + 1, dp[i][j-1] + 1), dp[i-1][j-1] + cost)
        }
        return dp[s1.length][s2.length]
    }
}
