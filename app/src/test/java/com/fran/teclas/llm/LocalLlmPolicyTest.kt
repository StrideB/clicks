package com.fran.teclas.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two safety policies around the 2.5 GB local model: which phones may load it, and when its
 * memory goes back to the system. Both used to be absent — the model loaded anywhere and, once
 * loaded, was never freed.
 *
 * Tests [LlmPolicy] rather than [LocalLlmEngine]: the engine loads a native library in its
 * initializer, which is exactly the kind of coupling that keeps important rules untested.
 */
class LocalLlmPolicyTest {

    private val gb = 1024L * 1024 * 1024

    // ---------------------------------------------------------------- who may run the 4B model

    @Test fun `a phone with plenty of RAM may run the quality model`() {
        assertTrue(LlmPolicy.qualityAllowed(12 * gb, lowRamDevice = false))
        assertTrue(LlmPolicy.qualityAllowed(8 * gb, lowRamDevice = false))
    }

    @Test fun `a small phone may not`() {
        assertFalse(LlmPolicy.qualityAllowed(4 * gb, lowRamDevice = false))
        assertFalse(LlmPolicy.qualityAllowed(3 * gb, lowRamDevice = false))
    }

    @Test fun `the low-RAM flag overrides a generous total`() {
        // Android sets this on devices tuned for constrained memory regardless of installed RAM;
        // it is a stronger signal than the raw number.
        assertFalse(LlmPolicy.qualityAllowed(16 * gb, lowRamDevice = true))
    }

    @Test fun `the boundary is inclusive`() {
        assertTrue(LlmPolicy.qualityAllowed(LlmPolicy.QUALITY_MIN_RAM_BYTES, false))
        assertFalse(LlmPolicy.qualityAllowed(LlmPolicy.QUALITY_MIN_RAM_BYTES - 1, false))
    }

    @Test fun `an unreadable memory total never enables the big model`() {
        assertFalse(LlmPolicy.qualityAllowed(0L, lowRamDevice = false))
    }

    // ---------------------------------------------------------------- when the model is freed

    private val idle = 5 * 60_000L

    @Test fun `an idle model is released`() {
        assertTrue(LlmPolicy.shouldRelease(loaded = true, busy = false, lastUseMs = 0, nowMs = idle, idleMs = idle))
    }

    @Test fun `a recently used model is kept`() {
        assertFalse(LlmPolicy.shouldRelease(loaded = true, busy = false, lastUseMs = 0, nowMs = idle - 1, idleMs = idle))
    }

    @Test fun `a generation in flight is never unloaded underneath itself`() {
        assertFalse(LlmPolicy.shouldRelease(loaded = true, busy = true, lastUseMs = 0, nowMs = idle * 10, idleMs = idle))
    }

    @Test fun `nothing loaded is nothing to release`() {
        assertFalse(LlmPolicy.shouldRelease(loaded = false, busy = false, lastUseMs = 0, nowMs = idle * 10, idleMs = idle))
    }
}
