package com.fran.clicks.keyboard.neural

import android.content.Context
import com.fran.clicks.glide.KeyInfo
import com.fran.clicks.keyboard.neural.NeuralSwipeContract.DEFAULT_BEAM_WIDTH
import com.fran.clicks.keyboard.neural.NeuralSwipeContract.DEFAULT_TOP_K
import com.fran.clicks.keyboard.neural.NeuralSwipeContract.MAX_DECODE_LEN
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
    suspend fun decode(
        path: List<TimedPoint>,
        boundsLeft: Float, boundsTop: Float, boundsWidth: Float, boundsHeight: Float,
        keyCenters: List<KeyInfo>,
        topK: Int = DEFAULT_TOP_K
    ): List<ScoredWord> = withContext(Dispatchers.Default) {
        if (!isReady) return@withContext emptyList()
        val feats = featurizer.featurize(path, boundsLeft, boundsTop, boundsWidth, boundsHeight, keyCenters)
            ?: return@withContext emptyList()
        val raw = NeuralBeamSearch(model, trie)
            .search(feats.features, feats.mask, beamWidth, topK * 2, MAX_DECODE_LEN)
        if (raw.isEmpty()) return@withContext emptyList()
        // Blend a gentle frequency prior so common words break near-ties among model candidates,
        // without letting frequency override a confident model prediction.
        raw.map { ScoredWord(it.word, it.score + FREQ_WEIGHT * ln((freqs[it.word] ?: 0f) + 1e-3f)) }
            .sortedByDescending { it.score }
            .take(topK)
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
        const val PREFS = "clicks"
        /** Boolean pref: use the neural decoder when a model is present. Default true. */
        const val PREF_NEURAL_GLIDE = "kbd_neural_glide"
        /** Weight of the log-frequency prior relative to per-char log-prob; small = tiebreak only. */
        private const val FREQ_WEIGHT = 0.15f
    }
}
