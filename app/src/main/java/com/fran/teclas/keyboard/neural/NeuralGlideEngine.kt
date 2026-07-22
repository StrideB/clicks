package com.fran.teclas.keyboard.neural

import android.content.Context
import com.fran.teclas.glide.KeyInfo
import com.fran.teclas.keyboard.neural.NeuralSwipeContract.DEFAULT_BEAM_WIDTH
import com.fran.teclas.keyboard.neural.NeuralSwipeContract.DEFAULT_TOP_K
import com.fran.teclas.keyboard.neural.NeuralSwipeContract.MAX_DECODE_LEN
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln

/**
 * The single, host-agnostic entry point for neural glide decoding. Both keyboards (the Docked IME and
 * the launcher Widget) construct one of these and call [decode] — the engine is wired once here, not
 * duplicated per host.
 *
 * It composes the four pieces: [OnnxSwipeModel] (ORT sessions) → [SwipeFeaturizer] (path → tensor) →
 * [NeuralBeamSearch] (dictionary-constrained decoding) → frequency re-rank. It runs entirely off the
 * main thread via coroutines and is a strict no-op when no model is present, so callers keep their
 * existing statistical fallback untouched.
 *
 * Selection: [enabled] gates use behind a preference ([PREF_NEURAL_GLIDE], default on) so a model can
 * be A/B'd against the statistical decoder without a rebuild. [isReady] is false until both the model
 * assets load and a dictionary is set.
 */
class NeuralGlideEngine(private val context: Context) {

    private val model = OnnxSwipeModel(context)
    private val featurizer = SwipeFeaturizer()
    private val trie = CharTrie()
    @Volatile private var freqs: Map<String, Float> = emptyMap()

    /** Tunable beam width; higher = more accurate, slower. */
    @Volatile var beamWidth: Int = DEFAULT_BEAM_WIDTH

    val isReady: Boolean get() = model.isReady && trie.size > 0

    /** True when the model is ready AND the user hasn't disabled neural glide. */
    val enabled: Boolean
        get() = isReady &&
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_NEURAL_GLIDE, true)

    /** Loads the ONNX sessions from assets. Blocking — call off the main thread. Safe if absent. */
    fun load(): Boolean = model.load()

    /** Builds the constraint trie + frequency table from the app dictionary. Blocking. */
    fun setDictionary(words: List<String>, wordFrequencies: Map<String, Float>) {
        trie.addAll(words)
        freqs = wordFrequencies
    }

    /**
     * Decodes a captured swipe into ranked dictionary words. Returns empty (so the caller falls back)
     * when the model isn't ready/enabled or the path can't be featurized.
     *
     * @param path raw screen-space samples with timestamps.
     * @param boundsLeft/Top/Width/Height letter-key bounding box in the same screen space (for [0,1] norm).
     * @param keyCenters letter keys (screen-space centers) for the nearest-key feature.
     */
    /**
     * @param priorBoost optional per-word prior in [0,1] (e.g. from the statistical decoder's ranking)
     *   that gently nudges the neural candidates toward geometrically-plausible words — the
     *   "cross-priming" coupling. Empty = pure neural ranking.
     */
    suspend fun decode(
        path: List<TimedPoint>,
        boundsLeft: Float, boundsTop: Float, boundsWidth: Float, boundsHeight: Float,
        keyCenters: List<KeyInfo>,
        topK: Int = DEFAULT_TOP_K,
        priorBoost: Map<String, Float> = emptyMap()
    ): List<ScoredWord> = withContext(Dispatchers.Default) {
        if (!isReady) return@withContext emptyList()
        val feats = featurizer.featurize(path, boundsLeft, boundsTop, boundsWidth, boundsHeight, keyCenters)
            ?: return@withContext emptyList()
        val raw = NeuralBeamSearch(model, trie)
            .search(feats.features, feats.mask, beamWidth, topK * 2, MAX_DECODE_LEN)
        if (raw.isEmpty()) return@withContext emptyList()
        // ORDER by beam score blended with two small priors (frequency, statistical cross-prime),
        // but RETURN the raw beam score — downstream confidence gating must measure what the MODEL
        // believes, not the priors. Prior scale is calibrated against measured beam-score margins
        // (correct top-1 margins: p10 0.89, median 1.74; wrong top-1 margins ≤ 0.80): the combined
        // prior influence (≤ ~0.45) can only reorder genuine near-ties, never a confident answer.
        // The old weights (0.15/0.5) exceeded typical margins and let a frequent statistical
        // favorite ("good") outrank a correct rare word ("google") the model was certain about.
        raw.map {
            Triple(
                it.word,
                it.score,
                it.score + FREQ_WEIGHT * ln((freqs[it.word] ?: 0f) + 1e-3f) +
                    PRIOR_WEIGHT * (priorBoost[it.word] ?: 0f)
            )
        }
            .sortedByDescending { it.third }
            .take(topK)
            .map { ScoredWord(it.first, it.second) }
    }

    /** Convenience: just the ranked words, matching the statistical classifier's `getSuggestions`. */
    suspend fun decodeWords(
        path: List<TimedPoint>,
        boundsLeft: Float, boundsTop: Float, boundsWidth: Float, boundsHeight: Float,
        keyCenters: List<KeyInfo>,
        topK: Int = DEFAULT_TOP_K
    ): List<String> =
        decode(path, boundsLeft, boundsTop, boundsWidth, boundsHeight, keyCenters, topK).map { it.word }

    fun close() = model.close()

    companion object {
        const val PREFS = "teclas"
        /** Boolean pref: use the neural decoder when a model is present. Default true. */
        const val PREF_NEURAL_GLIDE = "kbd_neural_glide"
        /** Weight of the log-frequency prior relative to per-char log-prob; small = tiebreak only.
         *  Max influence ≈ 0.35 — below the p10 margin (0.89) of correct predictions. */
        private const val FREQ_WEIGHT = 0.05f
        /** Weight of the cross-priming shape prior from the statistical decoder. Max 0.1 — a nudge
         *  inside genuine near-ties only. */
        private const val PRIOR_WEIGHT = 0.1f
    }
}
