package com.fran.teclas.keyboard

import com.fran.teclas.keyboard.ShiftRecovery.NONE
import com.fran.teclas.keyboard.ShiftRecovery.Read
import com.fran.teclas.keyboard.ShiftRecovery.Shift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision rules for recovering a word typed with the hands one key off-position.
 *
 * Most of these tests are about *declining*. Rewriting a word the user typed correctly is a much
 * worse failure than leaving one garbled word alone, so the interesting cases are the ones where
 * the evidence is merely suggestive and the right answer is to do nothing.
 */
class ShiftRecoveryTest {

    private val kw = 100f
    private val kh = 140f

    // ---------------------------------------------------------------- when we even try

    @Test fun `only runs when the normal decode found nothing`() {
        // A word that decoded fine is never reconsidered — that is the whole safety argument.
        assertTrue(ShiftRecovery.worthTrying(normalDecodeFound = false, tapCount = 5))
        assertTrue(!ShiftRecovery.worthTrying(normalDecodeFound = true, tapCount = 5))
    }

    @Test fun `very short words are left alone`() {
        // Two taps carry too little evidence to tell a shifted "hi" from an ordinary "no".
        assertTrue(!ShiftRecovery.worthTrying(normalDecodeFound = false, tapCount = 2))
        assertTrue(!ShiftRecovery.worthTrying(normalDecodeFound = false, tapCount = 3))
        assertTrue(ShiftRecovery.worthTrying(normalDecodeFound = false, tapCount = 4))
    }

    // ---------------------------------------------------------------- which offsets

    @Test fun `candidates are the eight neighbours and never the identity`() {
        val c = ShiftRecovery.candidateShifts(kw, kh)
        assertEquals(8, c.size)
        assertTrue("must not re-try the unshifted read", c.none { it.isNone })
        assertTrue("one key left is offered", c.contains(Shift(-kw, 0f)))
        assertTrue("one row down is offered", c.contains(Shift(0f, kh)))
        assertTrue("diagonals are offered", c.contains(Shift(kw, -kh)))
    }

    @Test fun `no key geometry means no guessing`() {
        assertTrue(ShiftRecovery.candidateShifts(0f, kh).isEmpty())
        assertTrue(ShiftRecovery.candidateShifts(kw, 0f).isEmpty())
    }

    @Test fun `two keys out is never attempted`() {
        val c = ShiftRecovery.candidateShifts(kw, kh)
        assertTrue(c.none { kotlin.math.abs(it.dx) > kw || kotlin.math.abs(it.dy) > kh })
    }

    // ---------------------------------------------------------------- choosing a winner

    @Test fun `a clear shifted winner is taken`() {
        val r = ShiftRecovery.choose(listOf(
            Read(Shift(-kw, 0f), "hello", -2.0),
            Read(Shift(kw, 0f), "jazz", -9.0),
        ))
        assertEquals("hello", r?.word)
    }

    @Test fun `a shifted read must clearly beat a word that decoded normally`() {
        val baseline = Read(NONE, "asdf", -5.0)
        // Only marginally better — not enough to overwrite what the user actually typed.
        assertNull(ShiftRecovery.choose(listOf(baseline, Read(Shift(-kw, 0f), "hello", -4.5))))
        // Clearly better — take it.
        assertEquals("hello",
            ShiftRecovery.choose(listOf(baseline, Read(Shift(-kw, 0f), "hello", -2.0)))?.word)
    }

    @Test fun `two offsets that disagree about the word are declined`() {
        // If a left shift says "hello" and a right shift says "world" equally well, we cannot tell
        // which hand position was real. Guessing here is how this feature would earn a reputation
        // for mangling text.
        assertNull(ShiftRecovery.choose(listOf(
            Read(Shift(-kw, 0f), "hello", -2.0),
            Read(Shift(kw, 0f), "world", -2.2),
        )))
    }

    @Test fun `two offsets that agree on the word are fine`() {
        val r = ShiftRecovery.choose(listOf(
            Read(Shift(-kw, 0f), "hello", -2.0),
            Read(Shift(-kw, -kh), "hello", -2.1),
        ))
        assertEquals("hello", r?.word)
    }

    @Test fun `no shifted reads means no change`() {
        assertNull(ShiftRecovery.choose(emptyList()))
        assertNull(ShiftRecovery.choose(listOf(Read(NONE, "asdf", -1.0))))
    }

    // ---------------------------------------------------------------- applying the offset

    @Test fun `shifting moves every tap by the same amount`() {
        val taps = listOf(10f to 20f, 30f to 40f)
        val out = ShiftRecovery.shiftTaps(taps, Shift(-kw, kh))
        assertEquals(listOf(10f - kw to 20f + kh, 30f - kw to 40f + kh), out)
    }

    @Test fun `the identity shift returns the taps untouched`() {
        val taps = listOf(10f to 20f)
        assertTrue(ShiftRecovery.shiftTaps(taps, NONE) === taps)
    }
}
