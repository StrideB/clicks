package com.fran.clicks.keyboard

/**
 * Pure send-time proofreading: fix only the words that are clearly misspelled, and leave everything
 * else exactly as the user wrote it. A word is left untouched when it's a known word (dictionary or
 * the user's own words), too short to judge, or has no confident correction — so slang, names, and
 * deliberate abbreviations are never "corrected". No Android or engine dependency: the dictionary
 * check and the corrector are injected, which also makes it unit-testable.
 */
object Proofreader {

    fun fix(
        text: String,
        isKnownWord: (String) -> Boolean,
        correct: (String) -> String?
    ): String {
        if (text.isBlank()) return text
        val sb = StringBuilder(text.length)
        var last = 0
        for (match in Regex("[A-Za-z']+").findAll(text)) {
            sb.append(text, last, match.range.first)
            sb.append(fixToken(match.value, isKnownWord, correct))
            last = match.range.last + 1
        }
        sb.append(text, last, text.length)
        return sb.toString()
    }

    private fun fixToken(
        token: String,
        isKnownWord: (String) -> Boolean,
        correct: (String) -> String?
    ): String {
        if (token.length < 3) return token
        if (isKnownWord(token)) return token
        val fix = correct(token) ?: return token
        return matchCase(token, fix)
    }

    /** Carry the original word's capitalization onto the correction (ALL CAPS / Title / lower). */
    private fun matchCase(original: String, fix: String): String = when {
        original.length >= 2 && original.all { it.isUpperCase() } -> fix.uppercase()
        original.first().isUpperCase() -> fix.replaceFirstChar { it.uppercaseChar() }
        else -> fix
    }
}
