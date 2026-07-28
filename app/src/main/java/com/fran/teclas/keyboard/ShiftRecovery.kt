package com.fran.teclas.keyboard

/**
 * Recovery for a word typed with the hands off-position — the "I wasn't looking and my fingers were
 * one key over" case that turns a whole sentence into gibberish no spellchecker can touch, because
 * every letter is wrong in the same direction.
 *
 * The insight is that this is geometry, not cryptography. [TapLatticeDecoder] already decodes from
 * raw touch coordinates, so a hand resting one key to the right is just a constant offset added to
 * every tap in the word. Re-running the decode with a handful of candidate offsets and keeping the
 * one that explains the taps best costs a few extra beam walks and is exact — where guessing at the
 * *committed letters* can only ever be a cipher puzzle, having thrown the coordinates away.
 *
 * This object is the decision half only: which offsets are worth trying, and whether a shifted read
 * is convincing enough to replace the normal one. The decoding itself stays in the decoder, so this
 * is plain arithmetic and unit-testable.
 */
object ShiftRecovery {

    /** A constant offset applied to every tap in a word, in pixels. */
    data class Shift(val dx: Float, val dy: Float) {
        val isNone: Boolean get() = dx == 0f && dy == 0f
    }

    /** One decode attempt: the offset tried, the word it produced, and its score. */
    data class Read(val shift: Shift, val word: String, val score: Double)

    val NONE = Shift(0f, 0f)

    /**
     * How much better a shifted read must score before it wins.
     *
     * Scores are log-space sums, so this is a ratio, not a difference in confidence percent. It is
     * set high deliberately: silently rewriting a word the user typed correctly is a far worse
     * failure than leaving one garbled word alone, and a shifted decode always has a slight unfair
     * advantage — it gets extra attempts at the same taps.
     */
    const val MIN_MARGIN = 1.5

    /**
     * The offsets worth trying, given the key pitch: one key left/right, one row up/down, and the
     * four diagonals. Deliberately not two keys out — a hand that far off produces taps that land
     * plausibly on *some* other word, and the false-rewrite risk stops being worth it.
     *
     * Ordered nearest-first so a caller that wants to stop early tries the likely ones first.
     */
    fun candidateShifts(keyWidth: Float, keyHeight: Float): List<Shift> {
        if (keyWidth <= 0f || keyHeight <= 0f) return emptyList()
        return listOf(
            Shift(-keyWidth, 0f), Shift(keyWidth, 0f),
            Shift(0f, -keyHeight), Shift(0f, keyHeight),
            Shift(-keyWidth, -keyHeight), Shift(keyWidth, -keyHeight),
            Shift(-keyWidth, keyHeight), Shift(keyWidth, keyHeight),
        )
    }

    /**
     * Whether an off-position read is worth attempting at all.
     *
     * Only when the normal decode came back with nothing — no dictionary word explains these taps.
     * That is the whole safety argument: in this state today's behaviour is already "leave the
     * gibberish alone", so anything found here is strictly better than what the user gets now, and
     * a word that decoded fine is never reconsidered.
     *
     * Very short words are excluded: two taps carry too little evidence to tell a shifted "hi" from
     * an ordinary "no", and single words are where a wrong rewrite is most jarring.
     */
    fun worthTrying(normalDecodeFound: Boolean, tapCount: Int): Boolean =
        !normalDecodeFound && tapCount >= MIN_TAPS

    /**
     * Pick the winning read, or null to change nothing.
     *
     * [reads] may include the unshifted attempt; if present it is the incumbent and a shifted read
     * must clear it by [margin]. With no unshifted read (the usual case here, since we only run
     * when the normal decode found nothing) the best shifted read still has to be the clear winner
     * among its peers — two different offsets producing equally good words means we cannot tell
     * which hand position was real, and guessing between them is how this feature would earn its
     * reputation for mangling text.
     */
    fun choose(reads: List<Read>, margin: Double = MIN_MARGIN): Read? {
        if (reads.isEmpty()) return null
        val baseline = reads.firstOrNull { it.shift.isNone }
        val shifted = reads.filter { !it.shift.isNone }.sortedByDescending { it.score }
        val best = shifted.firstOrNull() ?: return null

        if (baseline != null && best.score < baseline.score + margin) return null

        // Ambiguous between two different offsets that disagree about the word: decline.
        val runnerUp = shifted.drop(1).firstOrNull { it.word != best.word }
        if (runnerUp != null && best.score < runnerUp.score + margin) return null

        return best
    }

    /** Apply a shift to a buffered tap trail. */
    fun shiftTaps(taps: List<Pair<Float, Float>>, shift: Shift): List<Pair<Float, Float>> =
        if (shift.isNone) taps else taps.map { (x, y) -> (x + shift.dx) to (y + shift.dy) }

    const val MIN_TAPS = 4
}
