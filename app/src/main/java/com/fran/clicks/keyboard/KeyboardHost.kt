package com.fran.clicks.keyboard

/**
 * The seam between the shared keyboard core and its two hosts.
 *
 * Everything that differs between the launcher's own keyboard and the system IME reduces to *where
 * text goes* and *what editor context surrounds it*. The core drives typing/glide/autocorrect
 * purely through this interface, so a feature is written once and both keyboards inherit it.
 *
 * - Launcher host: reads/writes the in-memory `query` / `composeText` strings.
 * - IME host: reads/writes the remote editor via `InputConnection`.
 *
 * Step 1 of the shared-core migration only INTRODUCES this seam (both keyboards implement it with
 * their existing behavior). Later steps move duplicated logic into the core, which talks to the
 * active host through these methods.
 */
interface KeyboardHost {

    // ── Text sink (the only fundamental difference between the two keyboards) ──
    fun commitText(text: String)
    fun deleteBeforeCursor(count: Int)
    fun textBeforeCursor(count: Int): String
    fun textAfterCursor(count: Int): String
    /** Move the caret one character left/right (DPAD in the IME, cursorPos in the launcher). */
    fun moveCursor(right: Boolean)

    // ── Editor context ──
    fun editorPackage(): String?
    fun isPasswordField(): Boolean
    val hostHapticsEnabled: Boolean

    // ── Host-specific escape hatches (never pulled into the core) ──
    fun onAgenticCommand(text: String)
    fun openHostKeyboardSettings()
}
