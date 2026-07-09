package com.fran.teclas.predict

/**
 * Pure decision logic for the homescreen dock's two faces — Pinned (the user's own apps)
 * and Context (the active Space's predicted apps). No Android dependencies, so the
 * auto-follow-with-respected-override flow is unit-testable.
 *
 * Model: a single override — the Space id the user last swiped to Pinned in. While the
 * active Space still equals it, Pinned is respected. When the Space changes, the override
 * is dropped and the dock auto-shows Context for the new Space.
 */
object DockContextPolicy {

    /** [showContext] = which face to show; [override] = the override to persist afterward. */
    data class Decision(val showContext: Boolean, val override: String?)

    /**
     * The target dock face for the current active Space. A Pinned override only applies to
     * the Space it was set in — while there, Pinned is respected; anywhere else the override
     * is dropped and the dock shows Context (the context-first default and the auto-switch
     * that fires when the Space changes).
     */
    fun onSpaceObserved(override: String?, current: String): Decision {
        val liveOverride = override?.takeIf { it == current }
        return if (liveOverride != null) Decision(showContext = false, override = liveOverride)
        else Decision(showContext = true, override = null)
    }

    /** The user deliberately flipped the dock. Returns the override to persist. */
    fun onUserSwipe(toContext: Boolean, current: String): String? =
        if (toContext) null else current
}
