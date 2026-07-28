package com.fran.teclas

/**
 * The single character that stands in for an app when there is no icon.
 *
 * In icon-less mode this is not a fallback any more — it is the entire visual identity of every
 * app on the home screen, so `name.take(1)` is not good enough. Real launcher labels start with
 * emoji, quotes, bullets, leading spaces and bracketed tags, and any of those rendered as the
 * "letter" is the difference between a typographic column and a page of punctuation.
 */
object AppMonogram {

    private const val FALLBACK = "#"

    /**
     * The first letter or digit of [name], uppercased.
     *
     * Skips leading decoration (spaces, emoji, brackets, quotes, bullets). A name that is nothing
     * but decoration — an emoji-only label, which does exist — gets [FALLBACK] rather than an empty
     * tile, so every app is always something rather than a blank square.
     *
     * Uses the full code point, so scripts outside the BMP are not sliced in half into a rendering
     * artefact.
     */
    fun of(name: String): String {
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) {
                return String(Character.toChars(cp)).uppercase()
            }
            i += Character.charCount(cp)
        }
        return FALLBACK
    }
}
