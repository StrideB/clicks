package com.fran.teclas.keyboard

import java.util.Locale

/**
 * Shared autocorrect for both keyboards. Reads and rewrites text only through [KeyboardHost], so the
 * exact same correction, undo, and rejected-word learning run in the launcher and the IME.
 *
 * Unifies the best of each side:
 *  - case preservation (launcher)
 *  - cross-language protection via [extendedEngine] (IME) — never "corrects away" a word valid in a
 *    secondary enabled language
 *  - optional looser edit-distance fallback (launcher)
 *  - undo-on-backspace + rejected-correction memory (both)
 *
 * Correction happens ONLY on commit (space/punctuation). There is no mid-typing rewrite — that
 * fought the user and re-triggered on retype.
 */
class AutocorrectCore(
    private val host: KeyboardHost,
    private val engine: () -> PredictionEngine,
    private val contextNextWords: (prevWord: String) -> List<String>,
    private val extendedEngine: () -> PredictionEngine? = { null },
    private val useFallback: Boolean = false
) {
    private val rejected = HashMap<String, MutableSet<String>>()
    private var pendingOriginal: String? = null
    private var pendingCorrected: String? = null

    fun currentWord(): String = host.textBeforeCursor(48).takeLastWhile { it.isLetter() }

    private fun previousWord(): String {
        val before = host.textBeforeCursor(96)
        val tokens = Regex("[A-Za-z]+").findAll(before).map { it.value }.toList()
        val endsLetter = before.isNotEmpty() && before.last().isLetter()
        return if (endsLetter) tokens.getOrElse(tokens.size - 2) { "" } else tokens.lastOrNull().orEmpty()
    }

    /** Corrects the just-finished word in place (call right before committing a space/period). */
    fun correctBeforeCommit(): Boolean {
        val word = currentWord()
        val corrected = computeCorrection(word, contextNextWords(previousWord())) ?: return false
        return applyCorrection(word, corrected)
    }

    /**
     * The candidate-search half of [correctBeforeCommit]: what would [word] be corrected to, given
     * the personalized next-word context [ctx]? Pure — touches no host text and no mutable state —
     * so a host can precompute it on a background thread while the user is still mid-word and apply
     * the cached answer instantly when space lands (the Gboard pipeline: decode while typing,
     * commit on the keystroke).
     */
    fun computeCorrection(word: String, ctx: List<String>): String? {
        if (word.length < 2) return null
        extendedEngine()?.let { if (it.isDictWord(word)) return null }   // valid in another language
        val corrected = engine().bestCorrection(word, ctx) ?: run {
            if (!useFallback) return null
            val g = engine().getSuggestions(word, 1).firstOrNull() ?: return null
            if (levenshtein(word.lowercase(Locale.US), g.lowercase(Locale.US)) > 1) return null
            g
        }
        if (corrected.equals(word, ignoreCase = true)) return null
        return corrected
    }

    /**
     * The edit half of [correctBeforeCommit]: replace [word] (the in-progress word before the
     * cursor) with [corrected], honoring remembered rejections and preserving case. Main thread
     * only — it writes through the host and arms the backspace undo.
     */
    fun applyCorrection(word: String, corrected: String): Boolean {
        if (word.isEmpty() || corrected.equals(word, ignoreCase = true)) return false
        if (rejected[word.lowercase(Locale.US)]?.contains(corrected.lowercase(Locale.US)) == true) return false
        val cased = preserveCase(word, corrected)
        host.deleteBeforeCursor(word.length)
        host.commitText(cased)
        pendingOriginal = word; pendingCorrected = cased
        return true
    }

    /** On backspace: if a correction is still right before the cursor, restore the original and
     *  remember the rejection. Returns true if it consumed the backspace. */
    fun undoOnBackspace(): Boolean {
        val orig = pendingOriginal; val corr = pendingCorrected
        pendingOriginal = null; pendingCorrected = null
        if (orig == null || corr == null) return false
        val before = host.textBeforeCursor(corr.length + 1)
        val hasSpace = before.endsWith("$corr ")
        if (!hasSpace && !before.endsWith(corr)) return false
        host.deleteBeforeCursor(corr.length + if (hasSpace) 1 else 0)
        host.commitText(orig)
        rejected.getOrPut(orig.lowercase(Locale.US)) { HashSet() }.add(corr.lowercase(Locale.US))
        return true
    }

    /** Any other edit (typing a letter, moving the cursor) invalidates the pending undo. */
    fun clearPending() { pendingOriginal = null; pendingCorrected = null }

    private fun preserveCase(typed: String, corrected: String): String = when {
        typed.all { it.isUpperCase() } -> corrected.uppercase(Locale.US)
        typed.firstOrNull()?.isUpperCase() == true -> corrected.replaceFirstChar { it.titlecase(Locale.US) }
        else -> corrected
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev else 1 + minOf(prev, dp[j], dp[j - 1])
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
