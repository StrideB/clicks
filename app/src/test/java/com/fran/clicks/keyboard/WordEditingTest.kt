package com.fran.clicks.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the word-placement rules shared by both keyboards — in particular the fix that makes tapping
 * a suggestion right after a swipe REPLACE the swiped word instead of appending to it.
 */
class WordEditingTest {

    @Test fun currentWord_readsLetterWordBeforeCursor() {
        assertEquals("wor", WordEditing.currentWord(FakeKeyboardHost("hello wor")))
    }

    @Test fun currentWord_isEmptyAfterTrailingSpace() {
        assertEquals("", WordEditing.currentWord(FakeKeyboardHost("hello ")))
    }

    @Test fun replaceCurrentWord_replacesInProgressPartial() {
        val h = FakeKeyboardHost("hello wor")
        WordEditing.replaceCurrentWord(h, "world")
        assertEquals("hello world ", h.text)
    }

    @Test fun replaceCommittedWord_replacesSwipedWordAfterTrailingSpace() {
        // User swiped "helo" (committed with a trailing space), then taps the alternative "hello".
        val h = FakeKeyboardHost("helo ")
        WordEditing.replaceCommittedWord(h, "hello")
        assertEquals("hello ", h.text)   // replaced in place — NOT "helo hello "
    }

    @Test fun replaceCommittedWord_keepsEarlierText() {
        val h = FakeKeyboardHost("see you helo ")
        WordEditing.replaceCommittedWord(h, "hello")
        assertEquals("see you hello ", h.text)
    }

    @Test fun replaceCommittedWord_fallsBackToPartialWhenMidWord() {
        val h = FakeKeyboardHost("hell")
        WordEditing.replaceCommittedWord(h, "hello")
        assertEquals("hello ", h.text)
    }

    @Test fun commitGlideWord_appendsAfterCompletedWord() {
        val h = FakeKeyboardHost("see you ")
        WordEditing.commitGlideWord(h, "later")
        assertEquals("see you later ", h.text)
    }

    @Test fun commitGlideWord_replacesInProgressPartial() {
        val h = FakeKeyboardHost("see yo")
        WordEditing.commitGlideWord(h, "you")
        assertEquals("see you ", h.text)
    }
}
