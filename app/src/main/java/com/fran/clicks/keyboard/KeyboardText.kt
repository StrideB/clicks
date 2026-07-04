package com.fran.clicks.keyboard

/**
 * Shared typing behaviors and layout data used by both keyboards, driven through [KeyboardHost].
 * Single source of truth so the launcher and IME can't drift on symbols, auto-cap, or punctuation.
 */
object KeyboardSymbols {
    // Symbols page rows (the host appends its own "back" / bottom function row).
    val ROW_DIGITS = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    val ROW_SYMBOLS_1 = listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/")
    val ROW_SYMBOLS_2 = listOf("*", "\"", "'", ":", ";", "!", "?", ",")

    // Swipe-up-on-a-key inserts this symbol (chosen: insert-symbol wins over accept-prediction).
    val keyUp = mapOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
        "a" to "@", "s" to "#", "d" to "$", "f" to "_", "g" to "&",
        "h" to "-", "j" to "+", "k" to "(", "l" to ")",
        "z" to "*", "x" to "\"", "c" to "'", "v" to ":", "b" to ";",
        "n" to "!", "m" to "?"
    )
}

/**
 * A key label with its swipe symbol shown small above the letter (Apple/Gboard style) so the user
 * can see where to flick. Returns a plain string when the key has no symbol.
 */
fun keyLabelWithSymbol(letter: CharSequence, baseLetter: String, symbolColor: Int): CharSequence {
    val sym = KeyboardSymbols.keyUp[baseLetter.lowercase()] ?: return letter
    val s = android.text.SpannableString("$sym\n$letter")
    val flag = android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
    s.setSpan(android.text.style.RelativeSizeSpan(0.52f), 0, sym.length, flag)
    s.setSpan(android.text.style.ForegroundColorSpan(symbolColor), 0, sym.length, flag)
    return s
}

/** Should the next letter auto-capitalize? True at the start of text or after sentence punctuation. */
fun KeyboardHost.shouldAutoCapitalize(): Boolean {
    val before = textBeforeCursor(4).trimEnd()
    return before.isEmpty() || before.endsWith('.') || before.endsWith('!') || before.endsWith('?')
}

/** Double-space → ". ": if the char before the cursor is a space, rewrite it to a period + space. */
fun KeyboardHost.applyDoubleSpacePeriod(): Boolean {
    if (textBeforeCursor(1) != " ") return false
    deleteBeforeCursor(1)
    commitText(". ")
    return true
}
