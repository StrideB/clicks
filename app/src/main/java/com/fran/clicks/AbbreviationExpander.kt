package com.fran.clicks

/**
 * Common chat abbreviations → full phrase. Surfaced as a tap-to-expand suggestion in the strip
 * (never auto-replaced), so it's purely additive and non-surprising. Shared by launcher + IME.
 */
object AbbreviationExpander {
    private val map = mapOf(
        "omw" to "on my way",
        "brb" to "be right back",
        "idk" to "I don't know",
        "afaik" to "as far as I know",
        "btw" to "by the way",
        "ttyl" to "talk to you later",
        "imo" to "in my opinion",
        "tbh" to "to be honest",
        "hbu" to "how about you",
        "np" to "no problem",
        "nvm" to "never mind",
        "ily" to "I love you",
        "gm" to "good morning",
        "gn" to "good night",
        "wdyt" to "what do you think",
        "lmk" to "let me know",
        "rn" to "right now",
        "wyd" to "what are you doing"
    )

    /** The full phrase for [word] if it's a known abbreviation, else null. */
    fun expand(word: String): String? = map[word.lowercase().trim()]
}
