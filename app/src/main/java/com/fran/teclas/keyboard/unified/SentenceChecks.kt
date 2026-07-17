package com.fran.teclas.keyboard.unified

/**
 * Tier-2 foundation: pure, rule-based sentence checks that run at word boundaries (space/period),
 * never per keystroke. Each check returns a concrete fix or null — no scoring, no ambiguity —
 * so hosts can surface them as one-tap fixes (or auto-apply the unambiguous ones) without any
 * model or network. This is the deterministic slice of a Grammarly-class checker; the fuzzy
 * remainder (agreement, tense, phrasing) belongs to the AI polish tier.
 *
 * All checks operate on the text before the cursor and are language-guarded by the caller
 * (English rules must only fire when the active dictionary is English).
 */
object SentenceChecks {

    /** A concrete, applyable finding: replace the last [replaceLastChars] chars with [replacement]. */
    data class Fix(val replaceLastChars: Int, val replacement: String, val reason: String)

    /**
     * Doubled word: "the the ", "and and ". Detected right after the trailing space lands.
     * The fix drops the duplicate plus its separator, leaving the single word and its space.
     */
    fun doubledWord(beforeCursor: String): Fix? {
        if (!beforeCursor.endsWith(" ")) return null
        val m = DOUBLED_RE.find(beforeCursor) ?: return null
        val dup = m.groupValues[2]
        return Fix(replaceLastChars = dup.length + 1, replacement = "", reason = "doubled word")
    }

    private val DOUBLED_RE = Regex("(?:^|[\\s.,!?;:])([A-Za-z]+) (\\1) $", RegexOption.IGNORE_CASE)

    /**
     * Standalone lowercase "i" as a word: "i think" → "I think". English-only — the caller must
     * gate on the active language ("i" is a real word in Italian and Spanish).
     */
    fun standaloneI(beforeCursor: String): Fix? {
        if (!beforeCursor.endsWith("i ")) return null
        val prior = beforeCursor.dropLast(2)
        if (prior.isNotEmpty() && (prior.last().isLetter() || prior.last() == '\'')) return null
        return Fix(replaceLastChars = 2, replacement = "I ", reason = "capitalize I")
    }

    /**
     * a/an agreement with the FOLLOWING word, checked once that word is committed:
     * "a apple " → "an apple ", "an car " → "a car ". Vowel-letter heuristic with the classic
     * exception lists (silent h, consonant-sounding u/eu/one), which covers the common cases; the
     * genuinely ambiguous ones (acronyms, "an historic") are left alone.
     */
    fun aAnAgreement(beforeCursor: String): Fix? {
        if (!beforeCursor.endsWith(" ")) return null
        val words = beforeCursor.trimEnd().split(' ', '\n', '\t').filter { it.isNotEmpty() }
        if (words.size < 2) return null
        val article = words[words.size - 2].lowercase()
        // Acronyms are pronounced by letter name ("an FBI...", "a URL...") — the vowel-letter
        // heuristic is wrong for them, so leave anything typed with uppercase past index 0 alone.
        if (words.last().drop(1).any { it.isUpperCase() }) return null
        val noun = words.last().lowercase().trimEnd { !it.isLetter() }
        if (noun.isEmpty() || !noun.all { it.isLetter() }) return null
        val wantsAn = startsWithVowelSound(noun)
        val fixTo = when {
            article == "a" && wantsAn -> "an"
            article == "an" && !wantsAn -> "a"
            else -> return null
        }
        val tail = "${words.last()} "
        val replaceLen = article.length + 1 + tail.length
        if (!beforeCursor.endsWith("$article $tail")) return null
        return Fix(replaceLastChars = replaceLen, replacement = "$fixTo $tail", reason = "a/an")
    }

    private fun startsWithVowelSound(word: String): Boolean {
        if (word.isEmpty()) return false
        // Consonant-sounding vowel starts ("a university", "a european", "a one-time").
        if (CONSONANT_SOUND_PREFIXES.any { word.startsWith(it) }) return false
        // Silent-h vowel sounds ("an hour", "an honest", "an heir").
        if (SILENT_H.any { word.startsWith(it) }) return true
        return word[0] in "aeiou"
    }

    private val SILENT_H = listOf("hour", "honest", "honor", "honour", "heir")
    private val CONSONANT_SOUND_PREFIXES = listOf("uni", "use", "user", "usu", "eu", "one", "once", "ubiq")
}
