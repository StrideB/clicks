package com.fran.teclas.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two safety policies around the 2.5 GB local model: which phones may load it, and when its
 * memory goes back to the system. Both used to be absent — the model loaded anywhere and, once
 * loaded, was never freed.
 */
class LocalLlmPolicyTest {

    private val gb = 1024L * 1024 * 1024

    // ---------------------------------------------------------------- who may run the 4B model

    @Test fun `a phone with plenty of RAM may run the quality model`() {
        assertTrue(LocalLlmEngine.qualityAllowed(12 * gb, lowRamDevice = false))
        assertTrue(LocalLlmEngine.qualityAllowed(8 * gb, lowRamDevice = false))
    }

    @Test fun `a small phone may not`() {
        assertFalse(LocalLlmEngine.qualityAllowed(4 * gb, lowRamDevice = false))
        assertFalse(LocalLlmEngine.qualityAllowed(3 * gb, lowRamDevice = false))
    }

    @Test fun `the low-RAM flag overrides a generous total`() {
        // Android sets this on devices tuned for constrained memory regardless of installed RAM;
        // it is a stronger signal than the raw number.
        assertFalse(LocalLlmEngine.qualityAllowed(16 * gb, lowRamDevice = true))
    }

    @Test fun `the boundary is inclusive`() {
        assertTrue(LocalLlmEngine.qualityAllowed(LocalLlmEngine.QUALITY_MIN_RAM_BYTES, false))
        assertFalse(LocalLlmEngine.qualityAllowed(LocalLlmEngine.QUALITY_MIN_RAM_BYTES - 1, false))
    }

    @Test fun `an unreadable memory total never enables the big model`() {
        assertFalse(LocalLlmEngine.qualityAllowed(0L, lowRamDevice = false))
    }

    // ---------------------------------------------------------------- when the model is freed

    private val idle = 5 * 60_000L

    @Test fun `an idle model is released`() {
        assertTrue(LocalLlmEngine.shouldRelease(loaded = true, busy = false, lastUseMs = 0, nowMs = idle, idleMs = idle))
    }

    @Test fun `a recently used model is kept`() {
        assertFalse(LocalLlmEngine.shouldRelease(loaded = true, busy = false, lastUseMs = 0, nowMs = idle - 1, idleMs = idle))
    }

    @Test fun `a generation in flight is never unloaded underneath itself`() {
        assertFalse(LocalLlmEngine.shouldRelease(loaded = true, busy = true, lastUseMs = 0, nowMs = idle * 10, idleMs = idle))
    }

    @Test fun `nothing loaded is nothing to release`() {
        assertFalse(LocalLlmEngine.shouldRelease(loaded = false, busy = false, lastUseMs = 0, nowMs = idle * 10, idleMs = idle))
    }
}
