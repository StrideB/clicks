package com.fran.teclas.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The silent-apply gate: an on-device grammar-fix may only be applied without asking when it merely
 * cleans up the sentence. Meaning-changing rewrites must be rejected here so they get offered, not
 * forced.
 */
class ProofreadSafetyTest {

    @Test fun spellingFixIsSafe() {
        assertTrue(ProofreadSafety.isSafeEdit("i recieved teh msg", "i received the msg"))
    }

    @Test fun casingAndPunctuationOnlyIsSafe() {
        assertTrue(ProofreadSafety.isSafeEdit("hello there how are you", "Hello there, how are you"))
    }

    @Test fun addingAWordIsNotSafe() {
        // Word count changed → a reword, must be offered not forced.
        assertFalse(ProofreadSafety.isSafeEdit("going store", "i am going to the store"))
    }

    @Test fun droppingAWordIsNotSafe() {
        assertFalse(ProofreadSafety.isSafeEdit("i really do like this", "i like this"))
    }

    @Test fun rewordingAWordIsNotSafe() {
        // "happy" → "delighted" is a genuine substitution, not a spelling cleanup.
        assertFalse(ProofreadSafety.isSafeEdit("i am happy today", "i am delighted today"))
    }

    @Test fun tooManyChangedWordsIsNotSafe() {
        assertFalse(ProofreadSafety.isSafeEdit("teh ct st onn teh mt", "the cat sat on the mat"))
    }

    @Test fun identicalIsTriviallySafe() {
        assertTrue(ProofreadSafety.isSafeEdit("all good here", "all good here"))
    }

    @Test fun emptyIsNotSafe() {
        assertFalse(ProofreadSafety.isSafeEdit("", ""))
    }

    @Test fun boundedDistanceShortCircuits() {
        // Length gap alone exceeds the cap → returns max+1 without a full computation.
        assertEquals(3, ProofreadSafety.levenshteinBounded("a", "abcd", 2))
    }

    @Test fun boundedDistanceExact() {
        assertEquals(1, ProofreadSafety.levenshteinBounded("kitten", "kittens", 2))   // one insertion
        assertEquals(2, ProofreadSafety.levenshteinBounded("teh", "the", 2))          // two substitutions
    }
}
