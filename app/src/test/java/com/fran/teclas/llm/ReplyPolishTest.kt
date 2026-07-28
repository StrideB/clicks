package com.fran.teclas.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanitizing a model-written reply before a person sees it.
 *
 * These are mostly rejection tests, because the dangerous outputs here are the *plausible* ones —
 * a chatty preamble or an unfilled placeholder reads fine in a log and is mortifying in a text
 * message. Returning null is always a safe answer: the user keeps what they typed.
 */
class ReplyPolishTest {

    private val shorthand = "tell him ill be there but grab coffee first"

    // ---------------------------------------------------------------- accepted

    @Test fun `a clean rewrite passes through`() {
        assertEquals(
            "I'll be there, but I'm grabbing a coffee first.",
            ReplyPolish.sanitize("I'll be there, but I'm grabbing a coffee first.", shorthand),
        )
    }

    @Test fun `surrounding quotes are stripped`() {
        assertEquals("On my way.", ReplyPolish.sanitize("\"On my way.\"", "omw"))
        assertEquals("On my way.", ReplyPolish.sanitize("“On my way.”", "omw"))
    }

    @Test fun `leading blank lines are skipped and the message is taken`() {
        assertEquals("Running late, sorry!", ReplyPolish.sanitize("\n\nRunning late, sorry!", "late sry"))
    }

    // ---------------------------------------------------------------- rejected

    @Test fun `a chatty preamble is never sent`() {
        // The single most likely bad output, and the most embarrassing one to deliver.
        assertNull(ReplyPolish.sanitize("Sure! Here's a polished version:", shorthand))
        assertNull(ReplyPolish.sanitize("Certainly, here you go", shorthand))
        assertNull(ReplyPolish.sanitize("Here is the rewritten message", shorthand))
    }

    @Test fun `a refusal is never sent`() {
        assertNull(ReplyPolish.sanitize("I'm sorry, I can't help with that.", shorthand))
        assertNull(ReplyPolish.sanitize("As an AI language model, I cannot", shorthand))
    }

    @Test fun `an unfilled placeholder is never sent`() {
        assertNull(ReplyPolish.sanitize("Hi [Name], I'll be there shortly.", shorthand))
        assertNull(ReplyPolish.sanitize("See you at <time>.", shorthand))
        assertNull(ReplyPolish.sanitize("Meet at {place}.", shorthand))
    }

    @Test fun `a label prefix is treated as preamble`() {
        assertNull(ReplyPolish.sanitize("Reply: I'll be there soon.", shorthand))
        assertNull(ReplyPolish.sanitize("Message: on my way", shorthand))
    }

    @Test fun `an essay is rejected`() {
        assertNull(ReplyPolish.sanitize("x".repeat(ReplyPolish.MAX_CHARS + 1), shorthand))
    }

    @Test fun `padding three words into a paragraph is rejected`() {
        // Growth well past the shorthand means invention, not tidying.
        val short = "omw"
        assertNull(ReplyPolish.sanitize(
            "I wanted to let you know that I am currently on my way and should arrive shortly.", short))
    }

    @Test fun `an unchanged rewrite is not worth showing`() {
        assertNull(ReplyPolish.sanitize(shorthand, shorthand))
        assertNull(ReplyPolish.sanitize("  ${shorthand.uppercase()}  ", shorthand))
    }

    @Test fun `no output and empty output are safe`() {
        assertNull(ReplyPolish.sanitize(null, shorthand))
        assertNull(ReplyPolish.sanitize("", shorthand))
        assertNull(ReplyPolish.sanitize("   \n  ", shorthand))
        assertNull(ReplyPolish.sanitize("\"\"", shorthand))
    }

    @Test fun `only the first line is used`() {
        // Trailing model commentary is dropped rather than sent.
        assertEquals("On my way.",
            ReplyPolish.sanitize("On my way.\n\nLet me know if you'd like a different tone!", "omw"))
    }

    // ---------------------------------------------------------------- the prompt

    @Test fun `the prompt carries the context it is given and survives its absence`() {
        val p = ReplyPolish.prompt(shorthand, "where are you?", "Sarah")
        assertTrue(p.contains(shorthand))
        assertTrue(p.contains("where are you?"))
        assertTrue(p.contains("Sarah"))

        val bare = ReplyPolish.prompt(shorthand, "", "")
        assertTrue(bare.contains(shorthand))
        assertTrue("no empty context lines", !bare.contains("Replying to:"))
        assertTrue(!bare.contains("Their message:"))
    }
}
