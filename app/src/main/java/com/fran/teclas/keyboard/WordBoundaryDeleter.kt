package com.fran.teclas.keyboard

/**
 * State-machine tokenizer that reads text backward from the cursor and deletes
 * exactly one structural block: whitespace run → then the preceding word/token.
 * Handles punctuation, emoji surrogates, and URLs without naive space-splitting.
 */
object WordBoundaryDeleter {

    private enum class CharClass { WHITESPACE, ALPHANUMERIC, PUNCTUATION, EMOJI }

    private fun cls(ch: Char): CharClass = when {
        ch.isWhitespace()       -> CharClass.WHITESPACE
        ch.isLetterOrDigit()    -> CharClass.ALPHANUMERIC
        ch.isSurrogate()        -> CharClass.EMOJI
        ",.!?;:()[]{}'\"-/\\@#" .contains(ch) -> CharClass.PUNCTUATION
        else                    -> CharClass.ALPHANUMERIC
    }

    /** Returns how many characters to delete from the end of [text]. */
    fun charsToDelete(text: String): Int {
        if (text.isEmpty()) return 0
        val chars = text.reversed()
        var i = 0
        // 1. Skip trailing whitespace
        while (i < chars.length && cls(chars[i]) == CharClass.WHITESPACE) i++
        if (i >= chars.length) return text.length
        // 2. Identify the dominant class and consume the whole token
        val target = cls(chars[i])
        while (i < chars.length && cls(chars[i]) == target) i++
        return i
    }

    /** Deletes one word from the end of a mutable string. */
    fun deleteWord(text: String): String {
        val n = charsToDelete(text)
        return if (n >= text.length) "" else text.dropLast(n)
    }
}
