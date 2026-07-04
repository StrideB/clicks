package com.fran.clicks.keyboard.neural

import com.fran.clicks.keyboard.neural.NeuralSwipeContract.EOS
import com.fran.clicks.keyboard.neural.NeuralSwipeContract.FIRST_LETTER
import com.fran.clicks.keyboard.neural.NeuralSwipeContract.KEY_COUNT
import com.fran.clicks.keyboard.neural.NeuralSwipeContract.SOS
import com.fran.clicks.keyboard.neural.NeuralSwipeContract.VOCAB_SIZE
import kotlin.math.ln

/**
 * Dictionary-constrained beam search over the decoder.
 *
 * The decoder runs as one batched call per step (all beams share the encoder memory, tiled by
 * [OnnxSwipeModel.encode]). At each step a beam may only extend by a letter that keeps its prefix on
 * a path to some real word (via [CharTrie]) and may only emit <eos> when its prefix is already a
 * complete word — so every finished hypothesis is guaranteed to be a dictionary word.
 *
 * Scores are summed log-probabilities, length-normalized (per-character average) at the end so the
 * search doesn't systematically favor short words. Frequency re-ranking is applied later by the
 * caller, which owns the word-frequency table.
 */
class NeuralBeamSearch(
    private val model: OnnxSwipeModel,
    private val trie: CharTrie
) {

    private class Beam(
        val tokens: IntArray,   // includes leading SOS
        val letters: String,
        val cursor: Any?,       // CharTrie node for `letters`
        val score: Float        // summed log-prob
    )

    /**
     * @return up to [topK] candidate words with length-normalized log-prob scores, best first.
     */
    fun search(
        features: FloatArray,
        mask: LongArray,
        beamWidth: Int,
        topK: Int,
        maxLen: Int
    ): List<ScoredWord> {
        val mem = model.encode(features, mask, beamWidth) ?: return emptyList()
        try {
            var beams = listOf(Beam(intArrayOf(SOS), "", trie.rootCursor(), 0f))
            val finished = ArrayList<ScoredWord>()

            for (step in 0 until maxLen) {
                if (beams.isEmpty()) break
                val len = beams[0].tokens.size   // all live beams share the same length each step

                // Build the batched decoder input: one row per beam, padded up to beamWidth with a
                // copy of beam 0 (those extra rows' logits are ignored).
                val tgtFlat = LongArray(beamWidth * len)
                for (b in 0 until beamWidth) {
                    val src = if (b < beams.size) beams[b].tokens else beams[0].tokens
                    for (j in 0 until len) tgtFlat[b * len + j] = src[j].toLong()
                }
                val stepLogits = model.decodeStep(mem, tgtFlat, len) ?: break

                // Expand every live beam into (letter-extend) and (eos-finish) candidates.
                val next = ArrayList<Beam>(beamWidth * KEY_COUNT)
                for (b in beams.indices) {
                    val beam = beams[b]
                    val logp = logSoftmax(stepLogits[b])
                    // Finish this hypothesis if its prefix is a complete word.
                    if (beam.letters.isNotEmpty() && trie.isWord(beam.cursor)) {
                        val s = beam.score + logp[EOS]
                        finished.add(ScoredWord(beam.letters, s / beam.letters.length))
                    }
                    // Extend by any letter that stays inside the trie.
                    for (li in 0 until KEY_COUNT) {
                        if (!trie.canExtend(beam.cursor, li)) continue
                        val token = FIRST_LETTER + li
                        next.add(
                            Beam(
                                tokens = beam.tokens + token,
                                letters = beam.letters + ('a' + li),
                                cursor = trie.advance(beam.cursor, li),
                                score = beam.score + logp[token]
                            )
                        )
                    }
                }
                if (next.isEmpty()) break
                beams = next.sortedByDescending { it.score }.take(beamWidth)
            }

            return finished
                .groupBy { it.word }                       // same word can finish on multiple paths
                .map { (_, v) -> v.maxByOrNull { it.score }!! }
                .sortedByDescending { it.score }
                .take(topK)
        } finally {
            mem.close()
        }
    }

    /** Numerically stable log-softmax over one logit row. */
    private fun logSoftmax(logits: FloatArray): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > max) max = v
        var sum = 0.0
        for (v in logits) sum += Math.exp((v - max).toDouble())
        val logSum = max + ln(sum).toFloat()
        return FloatArray(VOCAB_SIZE) { logits[it] - logSum }
    }
}
