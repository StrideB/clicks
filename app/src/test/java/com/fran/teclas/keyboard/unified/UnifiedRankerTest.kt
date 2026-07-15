package com.fran.teclas.keyboard.unified

import com.fran.teclas.keyboard.PredictionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM checks on the unified composite ranker that drives both keyboards' intelligence. */
class UnifiedRankerTest {

    private val engine = PredictionEngine(
        mapOf(
            "the" to 1.0f,
            "that" to 0.9f,
            "their" to 0.5f,
            "there" to 0.8f,
            "this" to 0.9f,
            "receive" to 0.4f,
            "running" to 0.6f,
            "ruining" to 0.1f,
            "happiness" to 0.3f,
            "taking" to 0.5f,
            "hello" to 1.0f,
            "help" to 0.6f,
            "fam" to 0.001f,
            "farm" to 0.5f
        )
    )

    private fun ranker(
        personal: Map<String, Float> = emptyMap(),
        rejected: Set<Pair<String, String>> = emptySet()
    ) = UnifiedRanker(
        engine = { engine },
        personalBoost = { personal[it] ?: 0f },
        isRejectedPair = { t, c -> (t to c) in rejected }
    )

    // ── Correction guards (engine parity) ──────────────────────────────────────────────────────

    @Test fun bestCorrection_leavesRealWordsAlone() {
        assertNull(ranker().bestCorrection("hello"))
    }

    @Test fun bestCorrection_ignoresVeryShortInput() {
        assertNull(ranker().bestCorrection("he"))
    }

    @Test fun bestCorrection_ignoresNonLetters() {
        assertNull(ranker().bestCorrection("he11o"))
    }

    // ── Deterministic tiers ─────────────────────────────────────────────────────────────────────

    @Test fun phoneticTable_fixesClassicMisspelling() {
        assertEquals("the", ranker().bestCorrection("teh"))
        assertEquals("receive", ranker().bestCorrection("recieve"))
    }

    @Test fun phoneticFix_skippedWhenTargetNotInDictionary() {
        // "tommorow"→"tomorrow" is in the table but not in this test dictionary; the ranker must
        // not introduce a word the active language doesn't have. (No dict candidate is close
        // enough either, so the result is null, not a phantom word.)
        assertNull(ranker().bestCorrection("tommorow"))
    }

    @Test fun morphology_repairsDoubledConsonant() {
        assertEquals("running", ranker().bestCorrection("runing"))
    }

    @Test fun morphology_repairsYToI() {
        assertEquals("happiness", ranker().bestCorrection("happyness"))
    }

    @Test fun morphology_repairsUndroppedSilentE() {
        assertEquals("taking", ranker().bestCorrection("takeing"))
    }

    @Test fun morphology_listsRepairsWithoutVocabularyKnowledge() {
        assertTrue("running" in Morphology.repairs("runing"))
        assertTrue("happiness" in Morphology.repairs("happyness"))
        assertTrue("receive" in Morphology.repairs("recieve"))
        assertTrue(Morphology.repairs("the").isEmpty())   // too short
    }

    // ── Scored tier ─────────────────────────────────────────────────────────────────────────────

    @Test fun scoredCorrection_fixesNearMissToADictWord() {
        // "helo" is one fat-finger sub from "help" and one insertion from "hello" — either is a
        // legitimate fix; the contract is that a near-miss resolves to SOME dictionary word.
        val fix = ranker().bestCorrection("helo")
        assertTrue(fix != null && engine.isDictWord(fix))
    }

    @Test fun contextSignal_breaksHomophoneTie() {
        // Both "there" and "their" are near "thier"; the phonetic table says "their", and context
        // agreeing must not flip it.
        assertEquals("their", ranker().bestCorrection("thier", contextNextWords = listOf("their")))
    }

    @Test fun personalUsage_liftsUsersWordInSuggestions() {
        // "fam" is globally rare next to "farm". With personal usage, the user's word must rank
        // at least as a completion candidate for its own typed prefix.
        val cold = ranker().suggestions("fam")
        val warm = ranker(personal = mapOf("fam" to 0.9f)).suggestions("fam")
        assertTrue(warm.indexOf("fam") <= cold.indexOf("fam").let { if (it < 0) Int.MAX_VALUE else it })
        assertTrue("fam" in warm)
    }

    @Test fun personalUsage_cannotOverturnCorrectionOfClearTypo() {
        // Even a heavily-used personal word must not stop a deterministic morphology repair.
        val r = ranker(personal = mapOf("ruining" to 0.95f))
        assertEquals("running", r.bestCorrection("runing"))
    }

    // ── Learning hooks ──────────────────────────────────────────────────────────────────────────

    @Test fun rejectedPair_neverWinsAgain() {
        val r = ranker(rejected = setOf("teh" to "the"))
        val fix = r.bestCorrection("teh")
        assertTrue(fix != "the")
    }

    // ── Suggestions ─────────────────────────────────────────────────────────────────────────────

    @Test fun suggestions_singleCharYieldsOnlyNgramPredictions() {
        assertEquals(listOf("hello"), ranker().suggestions("h", ngramBoost = listOf("hello")))
    }

    @Test fun suggestions_completionLeadsForCleanPrefix() {
        val s = ranker().suggestions("hel")
        assertTrue(s.isNotEmpty())
        assertTrue(s.first() == "hello" || s.first() == "help")
    }

    @Test fun suggestions_ngramContinuationsLeadTheList() {
        val s = ranker().suggestions("th", ngramBoost = listOf("there"))
        assertEquals("there", s.first())
    }
}
