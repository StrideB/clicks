package com.fran.teclas.keyboard.unified

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** Pure-JVM checks on the unified algorithm's satellite components. */
class UnifiedComponentsTest {

    // ── ContextModel (bigram LM) ────────────────────────────────────────────────────────────────

    private fun model(text: String) = ContextModel().apply { load(ByteArrayInputStream(text.toByteArray())) }

    @Test fun contextModel_normalizesPerPrev() {
        val m = model(
            """
            # comment
            the store 100
            the stork 10
            good morning 50
            """.trimIndent()
        )
        assertTrue(m.isLoaded)
        assertEquals(1.0f, m.prob("the", "store"))
        assertEquals(0.1f, m.prob("the", "stork"))
        assertEquals(1.0f, m.prob("good", "morning"))
        assertEquals(0f, m.prob("the", "zzz"))
        assertEquals(0f, m.prob("zzz", "store"))
    }

    @Test fun contextModel_isCaseInsensitiveAndSkipsMalformedLines() {
        val m = model("The Store 10\nbroken line\nx y notanumber\nx y 0\n")
        assertEquals(1.0f, m.prob("THE", "STORE"))
        assertEquals(0f, m.prob("x", "y"))          // zero/malformed counts dropped
    }

    @Test fun contextModel_returnsZeroBeforeLoad() {
        assertEquals(0f, ContextModel().prob("the", "store"))
    }

    @Test fun contextModel_topContinuationsBestFirst() {
        val m = model("the store 100\nthe stork 10\nthe same 50\n")
        assertEquals(listOf("store", "same"), m.topContinuations("the", 2))
        assertEquals(emptyList<String>(), m.topContinuations("zzz"))
    }

    // ── AdaptiveWeights update rule ─────────────────────────────────────────────────────────────

    @Test fun adapted_movesWeightTowardSignalsThatFavoredThePick() {
        val defaults = ScoreWeights().asArray()
        val picked = DoubleArray(ScoreWeights.SIZE)   // pick was favored only by personal signal
        val top = DoubleArray(ScoreWeights.SIZE)
        picked[2] = 1.0   // personal
        top[0] = 1.0      // top word was favored by edit distance
        val next = AdaptiveWeights.adapted(defaults.copyOf(), defaults, picked, top)
        assertTrue(next[2] > defaults[2] * 0.999)     // personal went up (before renorm ≥)
        assertTrue(next[0] < defaults[0])             // edit distance came down
        // Total mass conserved on an unclamped step (redistribution, not inflation).
        assertEquals(defaults.sum(), next.sum(), 1e-9)
    }

    @Test fun adapted_isBoundedTo50PercentOfDefaults() {
        val defaults = ScoreWeights().asArray()
        var w = defaults.copyOf()
        val picked = DoubleArray(ScoreWeights.SIZE); picked[2] = 1.0
        val top = DoubleArray(ScoreWeights.SIZE); top[0] = 1.0
        repeat(2000) { w = AdaptiveWeights.adapted(w, defaults, picked, top) }
        // Even after 2000 identical one-sided updates, every weight stays hard-bounded, and the
        // total mass stays near the default total (clamping after renorm trades exactness for
        // unconditional bounds).
        for (i in w.indices) {
            assertTrue(w[i] <= defaults[i] * 1.5 + 1e-9)
            assertTrue(w[i] >= defaults[i] * 0.5 - 1e-9)
        }
        assertTrue(kotlin.math.abs(w.sum() - defaults.sum()) < defaults.sum() * 0.15)
    }

    // ── SentenceChecks ──────────────────────────────────────────────────────────────────────────

    @Test fun doubledWord_detectsAndCollapses() {
        val fix = SentenceChecks.doubledWord("I saw the the ")
        assertTrue(fix != null)
        assertEquals(4, fix!!.replaceLastChars)   // drops "the " leaving "the "
        assertEquals("", fix.replacement)
        assertNull(SentenceChecks.doubledWord("I saw the cat "))
        assertNull(SentenceChecks.doubledWord("the the"))     // no trailing space yet
        assertNull(SentenceChecks.doubledWord("blah 11 11 ")) // digits are not words
    }

    @Test fun doubledWord_isCaseInsensitive() {
        assertTrue(SentenceChecks.doubledWord("The the ") != null)
    }

    @Test fun standaloneI_capitalizes() {
        val fix = SentenceChecks.standaloneI("i ")
        assertEquals("I ", fix!!.replacement)
        assertTrue(SentenceChecks.standaloneI("well i ") != null)
        assertNull(SentenceChecks.standaloneI("hi "))      // part of a word
        assertNull(SentenceChecks.standaloneI("I "))       // already capitalized
    }

    @Test fun aAn_agreement() {
        val fix = SentenceChecks.aAnAgreement("I ate a apple ")
        assertEquals("an apple ", fix!!.replacement)
        assertEquals("a car ", SentenceChecks.aAnAgreement("saw an car ")!!.replacement)
        assertNull(SentenceChecks.aAnAgreement("an apple "))   // already right
        assertNull(SentenceChecks.aAnAgreement("a car "))      // already right
        // Sound exceptions: "a university", "an hour".
        assertNull(SentenceChecks.aAnAgreement("a university "))
        assertEquals("an hour ", SentenceChecks.aAnAgreement("in a hour ")!!.replacement)
        // Acronyms are pronounced by letter name — never "fix" them.
        assertNull(SentenceChecks.aAnAgreement("an FBI "))
        assertNull(SentenceChecks.aAnAgreement("a NDA "))
    }
}
