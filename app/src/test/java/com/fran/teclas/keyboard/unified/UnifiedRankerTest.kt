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
            "farm" to 0.5f,
            "store" to 0.5f,
            "stork" to 0.5f
        )
    )

    private fun ranker(
        personal: Map<String, Float> = emptyMap(),
        rejected: Set<Pair<String, String>> = emptySet(),
        lm: Map<String, Float> = emptyMap(),          // key "prev word" → strength
        tap: Map<String, Float> = emptyMap()          // word → tap-lattice score
    ) = UnifiedRanker(
        engine = { engine },
        personalBoost = { personal[it] ?: 0f },
        isRejectedPair = { t, c -> (t to c) in rejected },
        lmProb = { prev, w -> lm["$prev $w"] ?: 0f },
        tapSpatial = { w, _ -> tap[w] ?: 0f }
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
        // not introduce a word the active language doesn't have.
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
        assertEquals("their", ranker().bestCorrection("thier", contextNextWords = listOf("their")))
    }

    @Test fun tapLattice_breaksSpatialTie() {
        // Same story as the LM test, but decided by where the finger ACTUALLY touched: the trace
        // sits on store's keys, so "store" outranks the equal-frequency, equal-distance "stork".
        val r = ranker(tap = mapOf("store" to 0.95f, "stork" to 0.15f))
        val trace = List(5) { 0f to 0f }   // content unused by the fake scorer; length gates the signal
        assertEquals("store", r.bestCorrection("stora", tapTrace = trace))
    }

    @Test fun languageModel_breaksSpatialTie() {
        // "stora" is equidistant-ish from "store" and "stork" (same frequency here). The bigram
        // LM saying "the store" is a strong continuation must decide it.
        val r = ranker(lm = mapOf("the store" to 1.0f))
        assertEquals("store", r.bestCorrection("stora", prevWord = "the"))
    }

    @Test fun personalUsage_liftsUsersWordInSuggestions() {
        val warm = ranker(personal = mapOf("fam" to 0.9f)).suggestions("fam")
        assertTrue("fam" in warm)
        assertEquals("fam", warm.first())
    }

    @Test fun personalUsage_cannotOverturnCorrectionOfClearTypo() {
        val r = ranker(personal = mapOf("ruining" to 0.95f))
        assertEquals("running", r.bestCorrection("runing"))
    }

    // ── Learning hooks ──────────────────────────────────────────────────────────────────────────

    @Test fun rejectedPair_neverWinsAgain() {
        val r = ranker(rejected = setOf("teh" to "the"))
        assertTrue(r.bestCorrection("teh") != "the")
    }

    @Test fun explain_returnsSignalsForReachableCandidate() {
        val sig = ranker(lm = mapOf("the store" to 1.0f)).explain("stor", "store", prevWord = "the")
        assertTrue(sig != null)
        assertTrue(sig!!.completion == 1.0)
        assertTrue(sig.languageModel == 1.0)
        assertNull(ranker().explain("stor", "zzz"))
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
