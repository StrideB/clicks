package com.fran.teclas.keyboard

import kotlin.math.abs

/**
 * Whether a finger that has moved off a key is starting a glide, or is just a tap that slid.
 *
 * Getting this wrong is expensive in one direction only. A missed glide costs a word the user
 * retypes. A phantom glide costs the letter they already typed — the keyboard commits on touch-down
 * and a glide *steals that commit back* — so a tap misread as a swipe deletes a character and then
 * usually decodes to nothing, leaving a hole mid-word. That is the failure people describe as the
 * keyboard "halting" their typing.
 *
 * Distance alone cannot tell the two apart. Thumb-typing at speed routinely slides a third of a key
 * width, and on a dense display the old threshold — 20dp, about half a key — was inside the range a
 * fast tap reaches. What separates them is *when* the travel happens: a glide is already moving in
 * the first few frames, while a tap drifts gradually across the whole press.
 *
 * So the gate is two-tier: move early and the normal slop applies; take your time and you have to
 * travel substantially further to be believed.
 */
object GlideGate {

    /** Movement within this long of touch-down reads as intent to glide rather than tap drift. */
    const val FAST_WINDOW_MS = 220L

    /** How much further a late-starting drag must travel before it counts. */
    const val SLOW_TRAVEL_MULTIPLIER = 2.0f

    /**
     * @param dxPx, dyPx travel since touch-down.
     * @param elapsedMs since touch-down (`event.eventTime - event.downTime`).
     * @param slopPx the base activation distance.
     *
     * Travel is per-axis, matching how the keyboards already measure it: a glide that runs along a
     * key row is horizontal, and requiring diagonal distance would make the commonest gesture the
     * hardest to start.
     */
    fun shouldActivate(dxPx: Float, dyPx: Float, elapsedMs: Long, slopPx: Float): Boolean {
        val travel = maxOf(abs(dxPx), abs(dyPx))
        val needed = if (elapsedMs <= FAST_WINDOW_MS) slopPx else slopPx * SLOW_TRAVEL_MULTIPLIER
        return travel > needed
    }
}
