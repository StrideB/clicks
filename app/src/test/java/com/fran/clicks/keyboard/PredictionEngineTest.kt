package com.fran.clicks.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM checks on the correction/prediction engine that drives autocorrect on both keyboards. */
class PredictionEngineTest {

    private val engine = PredictionEngine(
        mapOf(
            "hello" to 1.0f,
            "help" to 0.6f,
            "world" to 0.9f,
            "the" to 1.0f,
            "there" to 0.8f,
            "their" to 0.5f
        )
    )

    @Test fun isDictWord_isCaseInsensitiveAndExact() {
        assertTrue(engine.isDictWord("hello"))
        assertTrue(engine.isDictWord("HELLO"))
        assertFalse(engine.isDictWord("helllo"))
    }

    @Test fun isPrefixOfDictWord_detectsRealPrefixes() {
        assertTrue(engine.isPrefixOfDictWord("hel"))   // hello, help
        assertFalse(engine.isPrefixOfDictWord("zzz"))
    }

    @Test fun bestCorrection_leavesRealWordsAlone() {
        assertNull(engine.bestCorrection("hello"))
    }

    @Test fun bestCorrection_ignoresVeryShortInput() {
        assertNull(engine.bestCorrection("he"))
    }

    @Test fun bestCorrection_fixesNearMissToADictWord() {
        val fix = engine.bestCorrection("helo")   // a dropped-letter slip
        assertNotNull(fix)
        assertTrue(engine.isDictWord(fix!!))
    }

    @Test fun getSuggestions_returnsNothingForSingleChar() {
        assertEquals(emptyList<String>(), engine.getSuggestions("h"))
    }

    @Test fun getSuggestions_returnsOnlyDictWords() {
        val s = engine.getSuggestions("helo", 3)
        assertTrue(s.isNotEmpty())
        assertTrue(s.all { engine.isDictWord(it) })
    }
}
