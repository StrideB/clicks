package com.fran.teclas.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** The send-time proofreader must fix only clear misspellings and leave everything else verbatim. */
class ProofreaderTest {

    private val dict = setOf("hello", "world", "there", "meeting", "tomorrow")
    private val known: (String) -> Boolean = { it.lowercase() in dict }
    // A tiny stand-in corrector: fixes two known typos, nothing else.
    private val correct: (String) -> String? = { w ->
        when (w.lowercase()) {
            "helllo" -> "hello"
            "tmrw" -> "tomorrow"
            else -> null
        }
    }

    @Test fun fixesAClearMisspelling() {
        assertEquals("hello world", Proofreader.fix("helllo world", known, correct))
    }

    @Test fun leavesKnownWordsUntouched() {
        assertEquals("hello there world", Proofreader.fix("hello there world", known, correct))
    }

    @Test fun leavesWordsWithoutAConfidentCorrection() {
        // "brb" has no correction and isn't known → left exactly as typed (slang/abbrev safe).
        assertEquals("brb", Proofreader.fix("brb", known, correct))
    }

    @Test fun preservesPunctuationAndSpacing() {
        assertEquals("hello, world!", Proofreader.fix("helllo, world!", known, correct))
    }

    @Test fun preservesCapitalization() {
        assertEquals("Hello world", Proofreader.fix("Helllo world", known, correct))
    }

    @Test fun leavesShortTokensAlone() {
        // Nothing under 3 chars is judged, so "hi" stays even though it's not in the dict.
        assertEquals("hi", Proofreader.fix("hi", known, correct))
    }

    @Test fun blankStaysBlank() {
        assertEquals("", Proofreader.fix("", known, correct))
    }
}
