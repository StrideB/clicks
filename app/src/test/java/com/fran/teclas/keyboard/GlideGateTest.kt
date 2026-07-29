package com.fran.teclas.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling a glide from a tap that slid.
 *
 * The two errors are not equal, and the tests are weighted accordingly. Missing a glide costs a
 * word the user retypes. Inventing one deletes a letter they already typed — the keyboard commits
 * on touch-down and a glide takes that commit back — so a phantom swipe leaves a hole mid-word.
 */
class GlideGateTest {

    private val slop = 70f   // ~20dp on a dense display

    // ---------------------------------------------------------------- real glides still start

    @Test fun `a quick sideways flick starts a glide`() {
        assertTrue(GlideGate.shouldActivate(dxPx = 90f, dyPx = 4f, elapsedMs = 40, slopPx = slop))
    }

    @Test fun `a vertical glide starts too`() {
        assertTrue(GlideGate.shouldActivate(dxPx = 3f, dyPx = -95f, elapsedMs = 60, slopPx = slop))
    }

    @Test fun `travel is per-axis, so a glide along a key row is not penalised`() {
        // Requiring diagonal distance would make the commonest gesture the hardest to begin.
        assertTrue(GlideGate.shouldActivate(dxPx = 80f, dyPx = 0f, elapsedMs = 50, slopPx = slop))
    }

    @Test fun `a deliberate slow glide still starts once it travels far enough`() {
        // Slower than the fast window, but well past twice the slop — clearly not tap drift.
        assertTrue(GlideGate.shouldActivate(dxPx = 200f, dyPx = 10f, elapsedMs = 600, slopPx = slop))
    }

    // ---------------------------------------------------------------- phantoms stay out

    @Test fun `a fast tap that slid slightly is not a glide`() {
        assertFalse(GlideGate.shouldActivate(dxPx = 40f, dyPx = 8f, elapsedMs = 45, slopPx = slop))
    }

    @Test fun `a press that drifts gradually is not a glide`() {
        // The reported failure: thumb-typing at speed slides a third of a key width across the
        // whole press. Under distance-only this activated and ate the keystroke.
        assertFalse(GlideGate.shouldActivate(dxPx = 90f, dyPx = 12f, elapsedMs = 500, slopPx = slop))
        assertFalse(GlideGate.shouldActivate(dxPx = 130f, dyPx = 0f, elapsedMs = 900, slopPx = slop))
    }

    @Test fun `a long press that barely moves is never a glide`() {
        assertFalse(GlideGate.shouldActivate(dxPx = 5f, dyPx = 5f, elapsedMs = 2_000, slopPx = slop))
    }

    @Test fun `no movement is not a glide`() {
        assertFalse(GlideGate.shouldActivate(dxPx = 0f, dyPx = 0f, elapsedMs = 0, slopPx = slop))
    }

    // ---------------------------------------------------------------- the boundaries

    @Test fun `the fast threshold is exclusive`() {
        assertFalse(GlideGate.shouldActivate(slop, 0f, 10, slop))
        assertTrue(GlideGate.shouldActivate(slop + 0.1f, 0f, 10, slop))
    }

    @Test fun `the window edge switches which threshold applies`() {
        val justInside = GlideGate.FAST_WINDOW_MS
        val justOutside = GlideGate.FAST_WINDOW_MS + 1
        val travel = slop * 1.5f          // past the fast bar, short of the slow one
        assertTrue(GlideGate.shouldActivate(travel, 0f, justInside, slop))
        assertFalse(GlideGate.shouldActivate(travel, 0f, justOutside, slop))
    }
}
