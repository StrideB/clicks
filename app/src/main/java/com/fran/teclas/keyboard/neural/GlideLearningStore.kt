package com.fran.teclas.keyboard.neural

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The online-learning loop (Level 3). Every accepted glide is a free labeled example, so we:
 *   1. Bump a personal word-frequency count (persisted) — this feeds BOTH decoders on the next load,
 *      so words you actually swipe rise for the statistical shape prior AND the neural freq blend.
 *   2. Append the gesture + chosen word to an on-device corpus (JSONL in filesDir) for later
 *      fine-tuning of the neural model. Disagreements between the two engines are flagged, because
 *      those are the highest-value samples to learn from.
 *
 * 100% on-device: nothing is uploaded. The corpus is capped so it can't grow without bound, and the
 * gesture is stored normalized to the [0,1] key box (resolution- and mode-independent, ready to train
 * on directly). Export it with `adb ... pull` and feed it to `tools/neural_swipe/` to fine-tune.
 */
class GlideLearningStore(context: Context) {

    private val appContext = context.applicationContext
    private val corpusFile = File(appContext.filesDir, CORPUS_FILE)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Record one accepted glide.
     * @param path raw screen-space samples; stored normalized into the [bounds] box.
     * @param bounds letter-key box [left, top, width, height]; if null, the gesture isn't stored
     *   (frequency is still bumped).
     * @param sources which engine(s) proposed the winner; [agreed] true when both agreed on top-1.
     */
    fun recordAcceptance(
        word: String,
        path: List<TimedPoint>,
        bounds: FloatArray?,
        source: GlideSource,
        agreed: Boolean
    ) {
        val w = word.lowercase()
        if (w.isEmpty() || !w.all { it in 'a'..'z' }) return
        bumpFrequency(w)
        if (bounds == null || bounds[2] <= 0f || bounds[3] <= 0f || path.size < 2) return
        runCatching { appendSample(w, path, bounds, source, agreed) }
            .onFailure { Log.w(TAG, "corpus append failed: ${it.message}") }
    }

    /**
     * The highest-signal training example: the user corrected a mis-decoded swipe, so [path] should
     * have produced [word] (not [was]). Recorded with corrected=true so training can weight these
     * heavily — this is how the model stops making YOUR specific mistakes after the next retrain.
     */
    fun recordCorrection(word: String, path: List<TimedPoint>, bounds: FloatArray?, was: String) {
        val w = word.lowercase()
        if (w.isEmpty() || !w.all { it in 'a'..'z' } || w == was.lowercase()) return
        bumpFrequency(w)   // boost the RIGHT word
        if (bounds == null || bounds[2] <= 0f || bounds[3] <= 0f || path.size < 2) return
        runCatching { appendSample(w, path, bounds, GlideSource.NEURAL, agreed = false, corrected = true, was = was.lowercase()) }
            .onFailure { Log.w(TAG, "corpus correction append failed: ${it.message}") }
    }

    private fun bumpFrequency(word: String) {
        val raw = prefs.getString(KEY_COUNTS, null)
        val counts = if (raw != null) JSONObject(raw) else JSONObject()
        counts.put(word, counts.optInt(word, 0) + 1)
        prefs.edit().putString(KEY_COUNTS, counts.toString()).apply()
    }

    /**
     * Per-word additive frequency boost in ~[0, MAX_BOOST], from how often each word was accepted.
     * Merge into the dictionary frequencies at load so both decoders reflect personal usage.
     */
    fun personalFrequencyBoost(): Map<String, Float> {
        val raw = prefs.getString(KEY_COUNTS, null) ?: return emptyMap()
        val counts = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Float>(counts.length())
        val keys = counts.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val c = counts.optInt(k, 0)
            // Saturating: a handful of accepts already lifts a word; more can't dominate the lexicon.
            out[k] = (MAX_BOOST * (1f - 1f / (1f + c * 0.5f)))
        }
        return out
    }

    private fun appendSample(
        word: String,
        path: List<TimedPoint>,
        bounds: FloatArray,
        source: GlideSource,
        agreed: Boolean,
        corrected: Boolean = false,
        was: String? = null
    ) {
        rotateIfLarge()
        val pts = JSONArray()
        val t0 = path.first().t
        for (p in path) {
            val nx = ((p.x - bounds[0]) / bounds[2]).coerceIn(0f, 1f)
            val ny = ((p.y - bounds[1]) / bounds[3]).coerceIn(0f, 1f)
            pts.put(JSONArray().put(round3(nx)).put(round3(ny)).put(p.t - t0))
        }
        val obj = JSONObject()
            .put("w", word)
            .put("src", source.name)
            .put("agreed", agreed)
            .put("corrected", corrected)
            .put("pts", pts)
        if (was != null) obj.put("was", was)
        corpusFile.appendText(obj.toString() + "\n")
    }

    /** Keep the newest ~half when the corpus passes the cap, so it never grows unbounded. */
    private fun rotateIfLarge() {
        if (!corpusFile.exists() || corpusFile.length() < MAX_BYTES) return
        runCatching {
            val lines = corpusFile.readLines()
            corpusFile.writeText(lines.takeLast(lines.size / 2).joinToString("\n", postfix = "\n"))
        }
    }

    private fun round3(v: Float): Double = Math.round(v * 1000.0) / 1000.0

    private companion object {
        const val TAG = "NeuralSwipe"
        const val PREFS = "teclas"
        const val KEY_COUNTS = "glide_personal_counts"
        const val CORPUS_FILE = "glide_corpus.jsonl"
        const val MAX_BYTES = 4L * 1024 * 1024   // 4 MB cap
        const val MAX_BOOST = 0.35f
    }
}
