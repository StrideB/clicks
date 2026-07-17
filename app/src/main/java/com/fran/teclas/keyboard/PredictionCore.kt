package com.fran.teclas.keyboard

import com.fran.teclas.AbbreviationExpander
import com.fran.teclas.db.NgramRepository

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
    private val ngram: NgramRepository,
    /** When set, the unified ranker scores/orders candidates instead of the engine's heuristics. */
    private val ranker: () -> com.fran.teclas.keyboard.unified.UnifiedRanker? = { null },
    /** The LANGUAGE's next-word continuations (bigram LM) — blended after the user's own n-grams
     *  between words, so the strip stays useful even for contexts the user hasn't typed before. */
    private val languageNextWords: (prev: String) -> List<String> = { emptyList() }
) {
    fun currentWord(): String = WordEditing.currentWord(host)

    fun previousWord(): String {
        val before = host.textBeforeCursor(96)
        val tokens = Regex("[A-Za-z]+").findAll(before).map { it.value }.toList()
        val endsLetter = before.isNotEmpty() && before.last().isLetter()
        return if (endsLetter) tokens.getOrElse(tokens.size - 2) { "" } else tokens.lastOrNull().orEmpty()
    }

    /** Up to 3 candidates: completions/corrections for the current word, or — between words —
     *  next-word predictions for the previous word. */
    fun computeSuggestions(): List<String> = computeSuggestions(currentWord(), previousWord())

    /** Between-words predictions: the user's own n-gram habits first, the language's bigram-LM
     *  continuations filling remaining slots. */
    fun nextWordPredictions(prev: String, limit: Int = 3): List<String> =
        (ngram.cachedNextWords(prev) + languageNextWords(prev)).distinct().take(limit)

    /**
     * [computeSuggestions] over an explicit (word, previous-word) snapshot. Thread-safe: the
     * engine is immutable, the n-gram reads hit a concurrent cache, and no host text is touched —
     * so a host can take the snapshot on the UI thread and run the dictionary work on a background
     * prediction thread.
     */
    fun computeSuggestions(word: String, prev: String, tapTrace: List<Pair<Float, Float>> = emptyList()): List<String> {
        if (word.length < 2) {
            if (prev.length < 2) return emptyList()
            ngram.prefetchNextWords(prev)
            return nextWordPredictions(prev)
        }
        if (prev.isNotEmpty()) ngram.prefetchNextWords(prev)
        ngram.prefetchNextWords(word)
        val chord = AbbreviationExpander.expand(word)
        val boost = ngram.cachedNextWords(prev)
        val base = ranker()?.suggestions(word, 3, ngramBoost = boost, prevWord = prev, tapTrace = tapTrace)
            ?: engine().getSuggestions(word, 3, ngramBoost = boost)
        return ((if (chord != null) listOf(chord) else emptyList()) + base).distinct().take(3)
    }

    /** Replace the in-progress word with [word] + a trailing space (the host learns afterwards). */
    fun replaceCurrentWord(word: String) = WordEditing.replaceCurrentWord(host, word)

    /**
     * Replace the *just-committed* word — a word followed by a single trailing space, as left by a
     * glide or autocorrect — with [word]. This is what makes "tap an alternative right after a swipe"
     * replace the swiped word instead of appending to it.
     */
    fun replaceCommittedWord(word: String) = WordEditing.replaceCommittedWord(host, word)
}
