package com.fran.teclas.keyboard

/**
 * Pure, host-driven text edits for placing a word into the field. Deliberately free of any Android
 * or data-layer dependency so the launcher and IME behave identically AND the logic is unit-testable
 * against a fake [KeyboardHost]. [PredictionCore] and [GlideCore] delegate here.
 */
object WordEditing {

    /** The in-progress (letter-only) word immediately before the cursor. */
    fun currentWord(host: KeyboardHost): String =
        host.textBeforeCursor(48).takeLastWhile { it.isLetter() }

    /** Replace the in-progress partial word with [word] + a trailing space. */
    fun replaceCurrentWord(host: KeyboardHost, word: String) {
        val cur = currentWord(host)
        if (cur.isNotEmpty()) host.deleteBeforeCursor(cur.length)
        host.commitText("$word ")
    }

    /**
     * Replace the *just-committed* word — a word followed by a single trailing space, as left by a
     * glide or autocorrect — with [word]. Falls back to [replaceCurrentWord] when the cursor isn't
     * sitting just after a "word ", so tapping an alternative fixes a swipe in place instead of
     * appending after it.
     */
    fun replaceCommittedWord(host: KeyboardHost, word: String) {
        val before = host.textBeforeCursor(64)
        if (before.isEmpty() || before.last() != ' ') { replaceCurrentWord(host, word); return }
        val committed = before.dropLast(1).takeLastWhile { it.isLetter() || it == '\'' }
        if (committed.isEmpty()) { host.commitText("$word "); return }
        host.deleteBeforeCursor(committed.length + 1)   // the word plus its trailing space
        host.commitText("$word ")
    }

    /** Place a glided word: replace the in-progress partial, or append after a completed word. */
    fun commitGlideWord(host: KeyboardHost, word: String) {
        val curLen = host.textBeforeCursor(64).takeLastWhile { it.isLetter() }.length
        if (curLen > 0) host.deleteBeforeCursor(curLen)
        host.commitText("$word ")
    }
}
