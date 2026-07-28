package com.fran.teclas.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The calculator behind `Calculate:` in the typing strip.
 *
 * Two halves matter equally. It must be exactly right on the sums people actually type — money,
 * splits, discounts — and it must return null for everything else, because a strip that
 * confidently answers "5" to "meeting at 5" is worse than a strip with no calculator.
 */
class MathEvalTest {

    private fun eval(s: String) = MathEval.eval(s)
    private fun assertValue(expected: Double, input: String) =
        assertEquals(input, expected, eval(input) ?: Double.NaN, 1e-9)

    // ---------------------------------------------------------------- arithmetic

    @Test fun `basic arithmetic and precedence`() {
        assertValue(4.0, "2+2")
        assertValue(14.0, "2+3*4")
        assertValue(20.0, "(2+3)*4")
        assertValue(-3.0, "-5+2")
    }

    @Test fun `division by zero refuses rather than returning infinity`() {
        assertNull(eval("5/0"))
        assertNull(eval("10 / (5 - 5)"))
    }

    // ---------------------------------------------------------------- percent

    @Test fun `percent of a number`() {
        assertValue(30.0, "15% of 200")
        assertValue(10.0, "20 percent of 50")
        assertValue(30.0, "200 * 15%")
    }

    @Test fun `a bare percent added or subtracted is relative`() {
        // Calculator convention, and what people mean: 15% *of 200*, not the number 0.15.
        assertValue(170.0, "200 - 15%")
        assertValue(220.0, "200 + 10%")
    }

    @Test fun `a plain number added or subtracted is absolute`() {
        // The distinction that makes the rule above safe rather than surprising.
        assertValue(185.0, "200 - 15")
    }

    // ---------------------------------------------------------------- everyday phrasing

    @Test fun `the sum people actually type`() {
        assertValue(301.75, "\$1420 split 4 ways minus a 15% discount")
    }

    @Test fun `word operators`() {
        assertValue(355.0, "1420 split 4 ways")
        assertValue(25.0, "100 divided by 4")
        assertValue(160.0, "100 plus 20 times 3")
    }

    @Test fun `thousands separators and currency are ignored`() {
        assertValue(355.0, "1,420 / 4")
        assertValue(55.0, "£50 + 10%")
    }

    // ---------------------------------------------------------------- refusing

    @Test fun `a sentence that merely contains a number is not a sum`() {
        // Without this guard the label-stripping would reduce these to a bare number and answer it.
        assertNull(eval("meeting at 5"))
        assertNull(eval("call mom at 7"))
    }

    @Test fun `prose is not a sum`() {
        assertNull(eval("hello there"))
        assertNull(eval(""))
        assertNull(eval("   "))
    }

    @Test fun `malformed expressions return nothing`() {
        assertNull(eval("2 + + "))
        assertNull(eval("(2+3"))
        assertNull(eval("2 + 3)"))
        assertNull(eval("* 5"))
    }

    @Test fun `absurdly long input is refused outright`() {
        assertNull(eval("1+".repeat(200)))
    }
}
