package com.fran.clicks.keyboard

import com.fran.clicks.AbbreviationExpander
import com.fran.clicks.db.NgramRepository

/**
 * Shared suggestion-strip logic for both keyboards: computes the candidate words shown in the strip
 * and replaces the in-progress word when one is accepted. Reads/writes only through [KeyboardHost],
 * so the launcher and IME show the same candidates from the same rules.
 *
 * Hosts still render the strip themselves and may add their own extras (the IME's notification
 * quick-replies, Gemini blend; the launcher's app-color tint / Polish button) around this base list.
 */
class PredictionCore(
    private val host: KeyboardHost,
    private val engine: () -> PredictionEngine,
    private val ngram: NgramRepository
) {
    fun currentWord(): String = host.textBeforeCursor(48).takeLastWhile { it.isLetter() }

    fun previousWord(): String {
        val before = host.textBeforeCursor(96)
        val tokens = Regex("[A-Za-z]+").findAll(before).map { it.value }.toList()
        val endsLetter = before.isNotEmpty() && before.last().isLetter()
        return if (endsLetter) tokens.getOrElse(tokens.size - 2) { "" } else tokens.lastOrNull().orEmpty()
    }

    /** Up to 3 candidates: completions/corrections for the current word, or — between words —
     *  next-word predictions for the previous word. */
    fun computeSuggestions(): List<String> {
        val word = currentWord()
        val prev = previousWord()
        if (word.length < 2) {
            if (prev.length < 2) return emptyList()
            ngram.prefetchNextWords(prev)
            return ngram.cachedNextWords(prev).take(3)
        }
        if (prev.isNotEmpty()) ngram.prefetchNextWords(prev)
        ngram.prefetchNextWords(word)
        val chord = AbbreviationExpander.expand(word)
        val base = engine().getSuggestions(word, 3, ngramBoost = ngram.cachedNextWords(prev))
        return ((if (chord != null) listOf(chord) else emptyList()) + base).distinct().take(3)
    }

    /** Replace the in-progress word with [word] + a trailing space (the host learns afterwards). */
    fun replaceCurrentWord(word: String) {
        val cur = currentWord()
        if (cur.isNotEmpty()) host.deleteBeforeCursor(cur.length)
        host.commitText("$word ")
    }
}
