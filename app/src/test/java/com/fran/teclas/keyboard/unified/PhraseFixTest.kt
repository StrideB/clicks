package com.fran.teclas.keyboard.unified

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phrase layer fixes the errors word-level autocorrect structurally cannot see. It must fire
 * only on a completed word, match the longest phrase, preserve case, and — most importantly — never
 * touch a context-dependent pair.
 */
class PhraseFixTest {

    private fun fixed(before: String): String? =
        SentenceChecks.phraseFix(before)?.let { f ->
            before.dropLast(f.replaceLastChars) + f.replacement
        }

    @Test fun modalOfBecomesHave() {
        assertEquals("i should have ", fixed("i should of "))
        assertEquals("we could have ", fixed("we could of "))
    }

    @Test fun longestPhraseWins() {
        // "should of been" must beat the shorter "should of".
        assertEquals("it should have been ", fixed("it should of been "))
    }

    @Test fun runTogetherCompoundSplits() {
        assertEquals("thanks a lot ", fixed("thanks alot "))
        assertEquals("as well ", fixed("aswell "))
    }

    @Test fun missingApostropheRestored() {
        assertEquals("i don't ", fixed("i dont "))
        assertEquals("they wouldn't ", fixed("they wouldnt "))
    }

    @Test fun misplacedApostropheFixed() {
        assertEquals("i couldn't ", fixed("i could'nt "))
    }

    @Test fun idiomFixed() {
        assertEquals("a sneak peek ", fixed("a sneak peak "))
    }

    @Test fun capitalizationPreserved() {
        assertEquals("Don't ", fixed("Dont "))
        assertEquals("A lot ", fixed("Alot "))
        assertEquals("DON'T ", fixed("DONT "))
        // "im" → "I'm" keeps the replacement's own capital even with no signal from the input.
        assertEquals("I'm ", fixed("im "))
    }

    @Test fun onlyFiresOnCompletedWord() {
        // Mid-word (no trailing space) must never fire.
        assertNull(SentenceChecks.phraseFix("i dont"))
        assertNull(SentenceChecks.phraseFix("alot"))
    }

    @Test fun doesNotStraddlePunctuation() {
        // The match must stay inside the final clause, not span the period.
        assertEquals("ok. don't ", fixed("ok. dont "))
    }

    @Test fun leavesOrdinaryTextAlone() {
        assertNull(SentenceChecks.phraseFix("hello there "))
        assertNull(SentenceChecks.phraseFix("see you tomorrow "))
    }

    /** The safety contract: context-dependent pairs must NOT be in the table at all. */
    @Test fun contextDependentPairsAreAbsent() {
        for (risky in listOf("its", "it's", "were", "your", "their", "there", "to", "too", "lets", "cant", "wont", "ill")) {
            assertNull("'$risky' must not be a phrase key", PhrasePatterns.fix(risky))
        }
        // ...and no multi-word entry may hinge on them either.
        assertNull(PhrasePatterns.fix("your welcome"))
        assertNull(PhrasePatterns.fix("to much"))
        assertNull(PhrasePatterns.fix("their is"))
    }

    @Test fun everyEntryActuallyChangesTheText() {
        listOf("dont", "alot", "should of", "sneak peak", "irregardless", "do'nt").forEach { k ->
            assertTrue("'$k' must map to something different", PhrasePatterns.fix(k) != k)
        }
    }
}
