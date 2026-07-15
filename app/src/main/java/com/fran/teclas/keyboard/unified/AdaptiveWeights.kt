package com.fran.teclas.keyboard.unified

import android.content.SharedPreferences

/**
 * The weight-adaptation learner: tunes [ScoreWeights] to this user's typing from one natural
 * label — when the user taps suggestion #2 or #3 instead of the word we ranked first, whatever
 * signals favored THEIR pick over OUR pick deserved more weight (and vice versa). Classic bounded
 * online perceptron update, deliberately conservative:
 *
 *   w_i ← clamp(w_i + LR · (picked_i − top_i) · w_default_i,  [0.5×, 1.5×] of default)
 *
 * - Learning rate is small (a single pick barely moves anything; consistent behavior over weeks
 *   does), and each weight is hard-bounded to ±50% of its default, so no run of odd picks can
 *   wreck the ranker.
 * - Weights are re-normalized to the default total after each update, so adaptation redistributes
 *   emphasis between signals rather than inflating all scores.
 * - Persisted to the shared prefs (both keyboards learn as one); [reset] restores factory defaults.
 *
 * Also keeps simple pick-rank telemetry (how often the user takes our #1 vs #2 vs #3) — the
 * measure of whether ranking is actually improving over time.
 */
class AdaptiveWeights(private val prefs: SharedPreferences) {

    private val defaults = ScoreWeights()
    @Volatile private var current: ScoreWeights = load()

    fun weights(): ScoreWeights = current

    /**
     * Learn from one suggestion pick. [pickedSignals]/[topSignals] are the raw signal vectors of
     * the word the user chose and the word we ranked first ([UnifiedRanker.explain]); equal
     * vectors (user took our #1) are a mild confirmation and nudge weights back toward defaults.
     */
    fun onSuggestionPicked(pickedRank: Int, pickedSignals: UnifiedRanker.Signals?, topSignals: UnifiedRanker.Signals?) {
        recordRank(pickedRank)
        if (pickedRank == 0 || pickedSignals == null || topSignals == null) {
            // Confirmation: decay adapted weights slightly toward defaults so stale adaptations
            // fade once the ranker is getting it right.
            val d = defaults.asArray(); val c = current.asArray()
            for (i in c.indices) c[i] += DECAY * (d[i] - c[i])
            current = ScoreWeights.fromArray(c)
            persist()
            return
        }
        current = ScoreWeights.fromArray(
            adapted(current.asArray(), defaults.asArray(), pickedSignals.asArray(), topSignals.asArray())
        )
        persist()
    }

    /** Pick-rank counts [taken #1, #2, #3] — the live measure of ranking quality. */
    fun pickStats(): IntArray = IntArray(3) { prefs.getInt("$RANK_KEY$it", 0) }

    fun reset() {
        current = defaults
        prefs.edit().remove(KEY).apply()
    }

    private fun recordRank(rank: Int) {
        if (rank in 0..2) prefs.edit().putInt("$RANK_KEY$rank", prefs.getInt("$RANK_KEY$rank", 0) + 1).apply()
    }

    private fun load(): ScoreWeights {
        val raw = prefs.getString(KEY, null) ?: return defaults
        val parts = raw.split(',').mapNotNull { it.toDoubleOrNull() }
        if (parts.size != ScoreWeights.SIZE) return defaults
        val d = defaults.asArray()
        // Re-clamp on load so bounds tightened in an update apply to previously-persisted values.
        val a = DoubleArray(ScoreWeights.SIZE) { i -> parts[i].coerceIn(d[i] * MIN_FACTOR, d[i] * MAX_FACTOR) }
        return ScoreWeights.fromArray(a)
    }

    private fun persist() {
        prefs.edit().putString(KEY, current.asArray().joinToString(",")).apply()
    }

    companion object {
        private const val KEY = "unified_weights"
        private const val RANK_KEY = "unified_pick_rank_"
        private const val LEARNING_RATE = 0.02
        private const val DECAY = 0.002
        private const val MIN_FACTOR = 0.5
        private const val MAX_FACTOR = 1.5

        /** The pure update rule (see class doc) — extracted so the math is JVM-testable.
         *  Order matters: nudge → renormalize (redistribute, don't inflate) → clamp LAST, so the
         *  hard per-signal bounds hold on the returned weights unconditionally (renormalizing
         *  after clamping could push a capped weight back over its bound). */
        internal fun adapted(current: DoubleArray, defaults: DoubleArray, picked: DoubleArray, top: DoubleArray): DoubleArray {
            val c = current.copyOf()
            for (i in c.indices) c[i] += LEARNING_RATE * (picked[i] - top[i]) * defaults[i]
            val total = c.sum(); val defTotal = defaults.sum()
            if (total > 0) for (i in c.indices) c[i] *= defTotal / total
            for (i in c.indices) c[i] = c[i].coerceIn(defaults[i] * MIN_FACTOR, defaults[i] * MAX_FACTOR)
            return c
        }
    }
}
