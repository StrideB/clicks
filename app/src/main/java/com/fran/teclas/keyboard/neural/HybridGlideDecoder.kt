package com.fran.teclas.keyboard.neural

import com.fran.teclas.glide.KeyInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Which decoder(s) produced the winning candidate — carried out for learning + diagnostics. */
enum class GlideSource { NEURAL, STATISTICAL, BOTH, NONE }

/** Result of a hybrid decode: the fused ranking plus provenance for the online-learning loop. */
data class HybridResult(
    val words: List<String>,
    val neuralWords: List<String>,
    val statisticalWords: List<String>,
    val agreed: Boolean,            // top pick found by both engines
    val neuralConfident: Boolean,   // neural's #1 clearly beat its #2
    val source: GlideSource
)

/**
 * Fuses the neural encoder-decoder and the statistical shape decoders into one result that is more
 * accurate than either alone, because their failure modes differ (geometry vs. language/sequence).
 * This is the single shared decode path both keyboards call — wired once, like the rest of the core.
 *
 * Levels combined here:
 *  - L1 Parallel ensemble: both decoders run, their ranked lists are merged by Reciprocal Rank Fusion
 *    (RRF) — scale-free, needs only the orderings, so no score calibration between a geometric matcher
 *    and a neural log-prob. Agreement between engines naturally rises to the top; coverage is kept.
 *  - L2 Confidence gating + cross-priming: the statistical decoder is cheap, so it runs first and its
 *    ranking primes the neural beam ([NeuralGlideEngine.decode] priorBoost). If the neural top-1 then
 *    clearly beats its top-2, it's treated as authoritative (weighted up in the fusion); otherwise the
 *    two vote as equals.
 *
 * Degrades safely: with no neural model (or the flag off) this returns the pure statistical ranking —
 * exactly today's behavior — so nothing regresses.
 */
class HybridGlideDecoder(private val neural: NeuralGlideEngine) {

    // ── Tunables ──
    /** RRF damping; larger = flatter weighting across ranks. */
    var rrfK: Double = 60.0
    // The neural decoder is now trained on the REAL FUTO swipe corpus and hits ~86% top-1 on held-out
    // real swipes (vs ~38% for the old synthetic model), so it LEADS the fusion — including on the
    // hard cases (double letters), which the real training data covers. The statistical shape decoder
    // stays a strong equal-ish partner via RRF, so a neural miss is still caught by geometry.
    var neuralWeight: Double = 1.2
    var statisticalWeight: Double = 1.0
    /** Multiplier on neural votes when its #1 is clearly ahead of its #2 — trustworthy now that the
     *  model is real-data-trained. */
    var confidentNeuralBoost: Double = 1.4
    /** Min gap between neural #1 and #2 RAW beam scores to count as "confident". Calibrated on
     *  device-space swipes with the shipped model: at 0.5 the gate fires on 93% of swipes and the
     *  neural #1 is correct 99.3% of the time it fires (wrong top-1 margins were all ≤ 0.80,
     *  correct ones had median 1.74). */
    var confidenceMargin: Float = 0.5f
    /** Run statistical first and prime the neural beam with it (L2). Off = fully parallel (L1 only). */
    var crossPrime: Boolean = true

    /**
     * @param bounds letter-key box [left, top, width, height]; null disables the neural path for this call.
     * @param statistical suspending producer of the statistical decoder's ranked words (host-owned
     *   classifier; the lambda should run it off the main thread).
     */
    suspend fun decode(
        path: List<TimedPoint>,
        bounds: FloatArray?,
        keyCenters: List<KeyInfo>,
        @Suppress("UNUSED_PARAMETER") wordFrequencies: Map<String, Float>,   // kept for call-site stability; both engines weigh frequency internally
        topK: Int,
        statistical: suspend () -> List<String>
    ): HybridResult = coroutineScope {
        val neuralUsable = neural.enabled && bounds != null && path.size > 1

        if (!neuralUsable) {
            // No model / not enough gesture — pure statistical (today's path).
            val s = statistical()
            return@coroutineScope HybridResult(
                words = s.take(topK), neuralWords = emptyList(), statisticalWords = s,
                agreed = false, neuralConfident = false,
                source = if (s.isEmpty()) GlideSource.NONE else GlideSource.STATISTICAL
            )
        }

        val statWords: List<String>
        val neuralScored: List<ScoredWord>
        if (crossPrime) {
            // Statistical is fast; run it first and let it prime the neural beam, then fuse.
            statWords = statistical()
            val prior = rankPrior(statWords)
            neuralScored = neural.decode(
                path, bounds!![0], bounds[1], bounds[2], bounds[3], keyCenters, topK * 2, prior
            )
        } else {
            // Fully parallel ensemble.
            val nDef = async(Dispatchers.Default) {
                neural.decode(path, bounds!![0], bounds[1], bounds[2], bounds[3], keyCenters, topK * 2)
            }
            val sDef = async { statistical() }
            statWords = sDef.await()
            neuralScored = nDef.await()
        }

        val neuralWords = neuralScored.map { it.word }
        val confident = neuralScored.size >= 2 &&
            (neuralScored[0].score - neuralScored[1].score) >= confidenceMargin
        val effectiveNeuralWeight = if (confident) neuralWeight * confidentNeuralBoost else neuralWeight

        // A confident neural #1 is authoritative: measured 99.3% correct when the gate fires. It is
        // PINNED to the top of the fusion — weight boosts alone don't work, because RRF also counts
        // the neural list's lower-ranked vote FOR the statistical favorite, which used to let the
        // statistical decoder's systematic error ("good" for a "google" swipe) outvote the model.
        val fused = rrfFuse(neuralWords, statWords, effectiveNeuralWeight, topK).let { f ->
            if (confident && neuralWords.isNotEmpty())
                listOf(neuralWords.first()) + f.filterNot { it == neuralWords.first() }
            else f
        }

        val agreed = neuralWords.isNotEmpty() && statWords.isNotEmpty() &&
            neuralWords.first().equals(statWords.first(), ignoreCase = true)
        val source = when {
            neuralWords.isEmpty() && statWords.isEmpty() -> GlideSource.NONE
            neuralWords.isEmpty() -> GlideSource.STATISTICAL
            statWords.isEmpty() -> GlideSource.NEURAL
            fused.firstOrNull()?.let { it in neuralWords && it in statWords } == true -> GlideSource.BOTH
            fused.firstOrNull() in neuralWords -> GlideSource.NEURAL
            else -> GlideSource.STATISTICAL
        }
        HybridResult(fused, neuralWords, statWords, agreed, confident, source)
    }

    /** Reciprocal Rank Fusion of two rankings. Context rerank is applied downstream by the host's
     *  GlideCore, so it's deliberately not repeated here.
     *
     *  NO frequency term: RRF scores live at the 1/rrfK scale (~0.017), so the old
     *  0.02*ln(freq) "tiebreak" spanned ~0.14 — seven times the primary signal — and turned the
     *  fusion into a frequency sort where every rare word lost to a frequent lookalike. Both
     *  engines already weigh frequency internally; it has no business being counted a third time. */
    private fun rrfFuse(
        neural: List<String>,
        statistical: List<String>,
        neuralW: Double,
        topK: Int
    ): List<String> {
        val score = HashMap<String, Double>()
        neural.forEachIndexed { i, w -> score[w] = (score[w] ?: 0.0) + neuralW / (rrfK + i) }
        statistical.forEachIndexed { i, w -> score[w] = (score[w] ?: 0.0) + statisticalWeight / (rrfK + i) }
        return score.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(topK)
    }

    /** Turn a ranked list into a [0,1] rank-decayed prior for cross-priming the neural beam. */
    private fun rankPrior(ranked: List<String>): Map<String, Float> {
        if (ranked.isEmpty()) return emptyMap()
        val out = HashMap<String, Float>(ranked.size)
        ranked.forEachIndexed { i, w -> out[w] = 1f / (1f + i) }
        return out
    }

}
