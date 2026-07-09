package com.fran.teclas.keyboard

/** In-memory [KeyboardHost] for unit tests: a text buffer with a caret, no Android dependencies. */
class FakeKeyboardHost(initial: String = "") : KeyboardHost {
    private val sb = StringBuilder(initial)
    var cursor: Int = sb.length

    val text: String get() = sb.toString()

    override fun commitText(text: String) {
        sb.insert(cursor, text)
        cursor += text.length
    }

    override fun deleteBeforeCursor(count: Int) {
        val start = (cursor - count).coerceAtLeast(0)
        sb.delete(start, cursor)
        cursor = start
    }

    override fun textBeforeCursor(count: Int): String {
        val start = (cursor - count).coerceAtLeast(0)
        return sb.substring(start, cursor)
    }

    override fun textAfterCursor(count: Int): String {
        val end = (cursor + count).coerceAtMost(sb.length)
        return sb.substring(cursor, end)
    }

    override fun moveCursor(right: Boolean) {
        cursor = (cursor + if (right) 1 else -1).coerceIn(0, sb.length)
    }

    override fun editorPackage(): String? = null
    override fun isPasswordField(): Boolean = false
    override val hostHapticsEnabled: Boolean = false
    override fun onAgenticCommand(text: String) {}
    override fun openHostKeyboardSettings() {}
}
