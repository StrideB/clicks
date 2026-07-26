package com.fran.teclas.keyboard

/**
 * Decides whether an on-device grammar-fix is safe to apply SILENTLY (no user tap). A silent fix must
 * only *clean up* the sentence: the same number of words, and each changed word differing from the
 * original only by casing, attached punctuation, or a small spelling edit. Anything that adds, drops,
 * or genuinely rewords must be offered to the user, never forced — this object is the guardrail that
 * stops the language model from quietly changing what the user meant.
 */
object ProofreadSafety {
    private val whitespace = Regex("\\s+")

    /** True when [corrected] is a safe silent cleanup of [original] (see class doc). */
    fun isSafeEdit(original: String, corrected: String): Boolean {
        val a = original.trim().split(whitespace).filter { it.isNotBlank() }
        val b = corrected.trim().split(whitespace).filter { it.isNotBlank() }
        if (a.size != b.size || a.isEmpty()) return false
        var changed = 0
        for (i in a.indices) {
            if (a[i] == b[i]) continue
            val ca = a[i].lowercase().filter { it.isLetterOrDigit() }
            val cb = b[i].lowercase().filter { it.isLetterOrDigit() }
            if (ca == cb) continue                                  // only casing/punctuation moved
            if (levenshteinBounded(ca, cb, 2) > 2) return false     // a real reword, not a fix
            changed++
        }
        return changed <= 3                                         // never a wholesale rewrite
    }

    /** Levenshtein distance, short-circuiting to [max]+1 as soon as it's provably over [max]. */
    fun levenshteinBounded(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1]
                    else 1 + minOf(prev[j - 1], prev[j], cur[j - 1])
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            prev = cur
        }
        return prev[b.length]
    }
}
