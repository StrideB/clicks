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
 * The encoder runs once; the decoder is then run once per live beam per step (batch 1), all sharing
 * the same encoder memory. At each step a beam may only extend by a letter that keeps its prefix on
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
        val mem = model.encode(features, mask) ?: return emptyList()
        try {
            var beams = listOf(Beam(intArrayOf(SOS), "", trie.rootCursor(), 0f))
            val finished = ArrayList<ScoredWord>()

            for (step in 0 until maxLen) {
                if (beams.isEmpty()) break

                // Expand every live beam into (letter-extend) and (eos-finish) candidates. Each beam
                // gets its own decoder call at batch 1 (see OnnxSwipeModel).
                val next = ArrayList<Beam>(beams.size * KEY_COUNT)
                for (beam in beams) {
                    val logits = model.decodeStep(mem, beam.tokens) ?: continue
                    val logp = logSoftmax(logits)
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
