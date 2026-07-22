package com.fran.teclas.keyboard

import com.fran.teclas.keyboard.neural.CharTrie
import kotlin.math.ln

/**
 * Gboard-style decode-at-commit for TAP typing. Instead of committing a letter per key and
 * "correcting" the typo afterward, this treats the buffered touch points as fuzzy evidence and asks
 * the real question: *which dictionary word best explains this trail of taps, given the sentence so
 * far?* Candidates are generated from the GEOMETRY (a beam walk of the dictionary trie guided by how
 * near each tap fell to each key), not from the literally-typed letters — so "letw" can resolve to
 * "lets" because the touch points explain "lets" nearly as well spatially and far better
 * linguistically, even though "lets" is not an edit-neighbour the old corrector would have generated
 * from the committed string.
 *
 * Pure Kotlin: the spatial probability, word frequency, and language-model prior are injected as
 * functions, so this decodes identically for both keyboards and is unit-testable on a plain JVM.
 *
 * Cost is bounded and lag-free: the trie prunes 26-way branching to a handful of live paths at each
 * step (most letters are dead ends), and only [beamWidth] beams survive per position. One decode per
 * word commit, never per keystroke.
 */
class TapLatticeDecoder(
    private val trie: CharTrie,
    /** P(this tap belongs to [key]) from the learned Gaussian spatial model, in (0,1]. */
    private val spatialProb: (x: Float, y: Float, key: Char) -> Float,
    /** Word frequency prior in [0,1]; 0 for unknown words. */
    private val wordFreq: (word: String) -> Float,
    /** P(word | previousWord) in [0,1] from the n-gram context model; 0 when unknown. */
    private val lmProb: (prev: String, word: String) -> Float,
) {
    data class Scored(val word: String, val score: Double)

    /**
     * @param taps buffered letter touch points, one per typed letter, in order.
     * @param prevWord the previous word for the language-model prior ("" if none).
     * @param topK how many candidates to return.
     * @param allowCompletion also offer dictionary words that continue a few letters past the taps
     *   (predictive completion) — off for the pure "fix what I typed" decode.
     */
    fun decode(
        taps: List<Pair<Float, Float>>,
        prevWord: String,
        topK: Int = 5,
        allowCompletion: Boolean = false,
        beamWidth: Int = 12,
    ): List<Scored> {
        if (taps.isEmpty() || taps.size > MAX_TAPS) return emptyList()

        // Precompute, per tap, the letters worth exploring: a small shortlist of the nearest keys.
        // A tap only ever extends a beam by one of ITS top-[FANOUT] keys, which is what keeps the
        // search a handful of paths wide instead of 26^n.
        val perTap: List<List<Pair<Int, Double>>> = taps.map { (x, y) ->
            (0 until 26)
                .map { li -> li to spatialProb(x, y, 'a' + li).toDouble() }
                .filter { it.second > MIN_KEY_PROB }
                .sortedByDescending { it.second }
                .take(FANOUT)
                .map { it.first to ln(it.second) }
        }
        if (perTap.any { it.isEmpty() }) return emptyList()

        // Beam: (trie cursor, prefix, accumulated spatial log-prob).
        data class Beam(val cursor: Any?, val prefix: String, val logp: Double)
        var beams = listOf(Beam(trie.rootCursor(), "", 0.0))
        val finished = ArrayList<Scored>()

        for (t in taps.indices) {
            val next = ArrayList<Beam>(beams.size * FANOUT)
            for (b in beams) {
                for ((li, lp) in perTap[t]) {
                    if (!trie.canExtend(b.cursor, li)) continue
                    next.add(Beam(trie.advance(b.cursor, li), b.prefix + ('a' + li), b.logp + lp))
                }
            }
            if (next.isEmpty()) break
            next.sortByDescending { it.logp }
            beams = next.take(beamWidth)
            // A word completing here consumes (t+1) taps; any remaining taps are UNEXPLAINED and cost
            // a penalty, so a word must account for the whole gesture — "let" can't beat "lets" by
            // silently dropping the 4th tap. A word using every tap (t == last) pays nothing.
            val unconsumed = taps.size - (t + 1)
            for (b in beams) if (trie.isWord(b.cursor)) {
                finished += finalize(b.prefix, b.logp + unconsumed * COMPLETION_PENALTY, prevWord)
            }
        }

        // Optional predictive completion: let a surviving prefix finish into slightly longer words.
        if (allowCompletion) {
            for (b in beams) collectCompletions(b.cursor, b.prefix, b.logp, prevWord, finished)
        }

        // Best score per distinct word.
        val best = HashMap<String, Double>()
        for (s in finished) if (best[s.word]?.let { s.score > it } != false) best[s.word] = s.score
        return best.entries.sortedByDescending { it.value }.take(topK).map { Scored(it.key, it.value) }
    }

    /** Fuse spatial evidence with the language priors into one comparable score (all in log space). */
    private fun finalize(word: String, spatialLogp: Double, prev: String): Scored {
        val perTap = spatialLogp / word.length                       // length-normalised: fair across lengths
        val freq = ln((wordFreq(word)).coerceIn(1e-6f, 1f).toDouble())
        val lm = ln((lmProb(prev, word)).coerceIn(1e-6f, 1f).toDouble())
        return Scored(word, W_SPATIAL * perTap + W_FREQ * freq + W_LM * lm)
    }

    private fun collectCompletions(
        cursor: Any?, prefix: String, logp: Double, prev: String, out: ArrayList<Scored>, depth: Int = 0,
    ) {
        if (depth >= MAX_COMPLETION) return
        for (li in 0 until 26) {
            val c = trie.advance(cursor, li) ?: continue
            val np = prefix + ('a' + li)
            val nlp = logp + COMPLETION_PENALTY            // unseen taps cost a flat penalty
            if (trie.isWord(c)) out += finalize(np, nlp, prev)
            collectCompletions(c, np, nlp, prev, out, depth + 1)
        }
    }

    private companion object {
        const val MAX_TAPS = 32
        const val FANOUT = 6              // nearest keys a single tap may resolve to
        const val MIN_KEY_PROB = 0.02     // ignore keys the tap almost certainly isn't
        const val MAX_COMPLETION = 4      // letters a completion may add past the taps
        const val COMPLETION_PENALTY = -2.3   // ln(0.1): each unseen letter is a real cost

        // Fusion weights (log space). Spatial dominates when it's decisive; the language priors
        // settle cases the geometry leaves ambiguous — the balance that makes eyes-free typing work.
        const val W_SPATIAL = 1.0
        const val W_FREQ = 0.35
        const val W_LM = 0.55
    }
}
