package com.fran.teclas.keyboard.unified

import com.fran.teclas.keyboard.PredictionEngine

/**
 * The unified keyboard algorithm (Phase 1): ONE composite scorer that ranks every candidate word
 * by the weighted sum of independent signals, replacing the separate prediction / correction /
 * fallback heuristics with a single tunable brain. Candidate *generation* stays in
 * [PredictionEngine] (first-letter buckets, budget-capped keyboard-aware edit distance); this
 * class decides which candidate wins and how confident we are.
 *
 * Signals (each normalized to 0..1 before weighting):
 *  - edit distance   — how close the candidate is to what was typed (keyboard-aware: fat-finger
 *                      substitutions and transpositions are cheap)
 *  - global frequency— how common the word is in the language (normalized within the candidate set,
 *                      so the scorer is independent of the wordlist's frequency scale)
 *  - personal usage  — how often THIS user commits the word (the learning hook; grows on-device)
 *  - context         — whether the user actually types this word after the preceding word
 *                      (n-gram store), fixing homophone-class slips a string metric can't
 *  - completion      — the candidate extends what's typed (a prediction, not a rewrite)
 *  - phonetic        — curated common-misspelling table (teh→the, recieve→receive, …)
 *  - morphology      — deterministic affix repairs (runing→running, happyness→happiness, …)
 *
 * Pure Kotlin, no Android dependencies: fully unit-testable on the JVM and safe to call from the
 * background prediction thread (all inputs are immutable or thread-safe suppliers).
 */
class UnifiedRanker(
    private val engine: () -> PredictionEngine,
    /** 0..1 saturating boost from the user's personal usage counts (see PersonalFrequencyStore). */
    private val personalBoost: (String) -> Float = { 0f },
    /** Learning hook: pairs the user has explicitly rejected (typed→candidate) never win again. */
    private val isRejectedPair: (typed: String, candidate: String) -> Boolean = { _, _ -> false },
    private val weights: ScoreWeights = ScoreWeights()
) {

    /**
     * Suggestion-strip candidates for [typed]: completions, corrections, and (via [ngramBoost])
     * personalized next-word predictions that extend what's typed. Drop-in replacement for
     * [PredictionEngine.getSuggestions] — same contract, unified scoring.
     */
    fun suggestions(typed: String, maxCount: Int = 3, ngramBoost: List<String> = emptyList()): List<String> {
        if (typed.length < 2) return ngramBoost.take(maxCount)
        val t = typed.lowercase()
        val maxDist = if (t.length <= 4) 1.6 else 2.4
        val cands = engine().candidatesFor(t, maxDist)
        val phonetic = PhoneticPatterns.fix(t)
        val morphs = Morphology.repairs(t)
        val maxFreq = cands.maxOfOrNull { it.freq } ?: 0f
        val scored = cands.map { c ->
            var s = weights.editDistance * (1.0 - c.distance / (maxDist + 1e-3)).coerceIn(0.0, 1.0) +
                weights.frequency * freqSignal(c.freq, maxFreq) +
                weights.personal * personalBoost(c.word).toDouble()
            if (c.word.length > t.length && c.word.startsWith(t)) s += weights.completion
            if (c.word == phonetic) s += weights.phonetic
            if (c.word in morphs) s += weights.morphology
            c.word to s
        }.sortedByDescending { it.second }.map { it.first }
        // N-gram continuations of the previous word that extend what's typed lead the list — parity
        // with the engine's behavior (they're the strongest personalization signal we have).
        val boost = ngramBoost.filter { it.length > t.length && it.startsWith(t, ignoreCase = true) }
        return (boost + scored).distinct().take(maxCount)
    }

    /**
     * High-confidence correction for [typed], or null when we shouldn't touch it. Drop-in
     * replacement for [PredictionEngine.bestCorrection] — same guards (never rewrites a dictionary
     * word, refuses ambiguous ties), with the phonetic/morphology/personal signals folded in.
     */
    fun bestCorrection(typed: String, contextNextWords: List<String> = emptyList()): String? {
        if (typed.length < 3) return null
        val t = typed.lowercase()
        if (!t.all { it in 'a'..'z' }) return null
        val eng = engine()
        if (eng.isDictWord(t)) return null                       // already a real word
        // Deterministic tiers first: a curated-misspelling or affix-repair hit IS the answer —
        // these encode how English typos actually happen, and they're immune to the frequency
        // trap (e.g. "thier"→"their" even if some closer-by-distance word is more common).
        PhoneticPatterns.fix(t)?.let { fix ->
            if (eng.isDictWord(fix) && !isRejectedPair(t, fix)) return fix
        }
        Morphology.repairs(t).firstOrNull { eng.isDictWord(it) && !isRejectedPair(t, it) }?.let { return it }
        // Scored tier: composite over the candidate set.
        val maxAccept = if (t.length >= 6) 2.2 else 1.35
        val cands = eng.candidatesFor(t, maxAccept).filter { it.distance <= maxAccept && !isRejectedPair(t, it.word) }
        if (cands.isEmpty()) return null
        val ctx = contextNextWords.mapTo(HashSet()) { it.lowercase() }
        val maxFreq = cands.maxOfOrNull { it.freq } ?: 0f
        val scored = cands.map { c ->
            val s = weights.editDistance * (1.0 - c.distance / (maxAccept + 1e-3)).coerceIn(0.0, 1.0) +
                weights.frequency * freqSignal(c.freq, maxFreq) +
                weights.personal * personalBoost(c.word).toDouble() +
                weights.context * (if (c.word in ctx) 1.0 else 0.0)
            Triple(c, s, c.freq)
        }.sortedByDescending { it.second }
        val best = scored.first()
        if (best.first.word == t) return null
        // Ambiguity guard (engine parity): when the top two are effectively tied but the runner-up
        // is the more common word, refuse to guess rather than risk a wrong rewrite.
        val runnerUp = scored.getOrNull(1)
        if (runnerUp != null && best.second - runnerUp.second < TIE_EPSILON && runnerUp.third > best.third) return null
        return best.first.word
    }

    private fun freqSignal(freq: Float, maxFreq: Float): Double =
        if (maxFreq <= 0f) 0.0 else (freq / maxFreq).toDouble()

    private companion object {
        // Composite-score gap under which two candidates count as "tied" for the ambiguity guard.
        private const val TIE_EPSILON = 0.05
    }
}

/**
 * Tunable weights for [UnifiedRanker]'s composite score. Defaults hand-set for Phase 1; the
 * learning system (Phase 4) adapts them per user within bounded ranges.
 */
data class ScoreWeights(
    val editDistance: Double = 0.40,
    val frequency: Double = 0.25,
    val personal: Double = 0.12,
    val context: Double = 0.10,
    val completion: Double = 0.08,
    val phonetic: Double = 0.03,
    val morphology: Double = 0.02
)

/**
 * Curated common-misspelling table — the classic English typos whose fixes are NOT the
 * nearest-by-edit-distance frequent word (or where we want certainty instead of a scored guess).
 * Targets are validated against the active dictionary at lookup time, so entries can never
 * introduce a word the current language doesn't have.
 */
object PhoneticPatterns {
    private val fixes = mapOf(
        "teh" to "the", "hte" to "the", "taht" to "that", "thsi" to "this", "tihs" to "this",
        "adn" to "and", "nad" to "and", "waht" to "what", "wich" to "which", "whcih" to "which",
        "jsut" to "just", "yuo" to "you", "yoru" to "your", "woudl" to "would", "coudl" to "could",
        "shoudl" to "should", "thier" to "their", "freind" to "friend", "wierd" to "weird",
        "recieve" to "receive", "beleive" to "believe", "acheive" to "achieve",
        "seperate" to "separate", "definately" to "definitely", "becuase" to "because",
        "untill" to "until", "occured" to "occurred", "tommorow" to "tomorrow",
        "tomorow" to "tomorrow", "tounge" to "tongue", "rythm" to "rhythm", "goverment" to "government",
        "enviroment" to "environment", "wensday" to "wednesday", "febuary" to "february",
        "neccessary" to "necessary", "accomodate" to "accommodate", "embarass" to "embarrass",
        "existance" to "existence", "grammer" to "grammar", "independant" to "independent",
        "knowlege" to "knowledge", "occurence" to "occurrence", "posession" to "possession",
        "publically" to "publicly", "recomend" to "recommend", "succesful" to "successful",
        "truely" to "truly", "basicly" to "basically"
    )

    fun fix(typed: String): String? = fixes[typed]
}

/**
 * Deterministic affix repairs: the systematic mistakes English word-formation invites. Given a
 * non-word, propose the spellings its stem+suffix structure says were probably intended. Callers
 * validate every proposal against the dictionary — this object only knows morphology, not vocabulary.
 */
object Morphology {
    private val suffixes = listOf("ing", "ed", "er", "est", "ly", "ness", "es", "s", "tion")

    fun repairs(typed: String): List<String> {
        if (typed.length < 4) return emptyList()
        val out = LinkedHashSet<String>()
        for (suffix in suffixes) {
            if (!typed.endsWith(suffix)) continue
            val stem = typed.dropLast(suffix.length)
            if (stem.length < 2) continue
            val last = stem.last()
            // Missing doubled consonant: "runing" → "running", "stoped" → "stopped".
            if (last.isConsonant()) out.add(stem + last + suffix)
            // y→i before suffix: "happyness" → "happiness", "beautyful" → skip (not a suffix here).
            if (last == 'y') out.add(stem.dropLast(1) + "i" + suffix)
            // Undropped silent e: "takeing" → "taking", "hopeing" → "hoping".
            if (last == 'e') out.add(stem.dropLast(1) + suffix)
        }
        // ie/ei swap anywhere: "recieve" → "receive" class, generalized.
        for (i in 0 until typed.length - 1) {
            if (typed[i] == 'i' && typed[i + 1] == 'e') out.add(typed.substring(0, i) + "ei" + typed.substring(i + 2))
            else if (typed[i] == 'e' && typed[i + 1] == 'i') out.add(typed.substring(0, i) + "ie" + typed.substring(i + 2))
        }
        out.remove(typed)
        return out.toList()
    }

    private fun Char.isConsonant(): Boolean = this in 'a'..'z' && this !in "aeiou"
}
