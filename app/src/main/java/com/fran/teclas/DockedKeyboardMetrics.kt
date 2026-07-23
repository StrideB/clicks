package com.fran.teclas

import android.content.Context

internal object DockedKeyboardMetrics {
    // Lifts the whole docked deck up off the screen's bottom edge. The band this opens up below the
    // keyboard is claimed by the space key's touch delegate (see MainActivity.installSpaceTouchDelegate),
    // so it types a space instead of being a dead strip — a dead zone at the bottom hurts typing more
    // than a miss, so the lift and the touch-extension always ship together.
    fun overlayBottomLiftPx(context: Context): Int = context.dp(10)

    fun externalReservedBandHeightPx(context: Context): Int {
        val density = context.resources.displayMetrics.density
        val size = KeyboardSettings.keyboardSize(context)
        val deckHeight = ((238 + (size * 54 / 100)) * density).toInt()
        return deckHeight + launcherSearchShelfPx(context) + overlayBottomLiftPx(context)
    }

    fun freeformTargetNudgePx(context: Context): Int = context.dp(70)

    // The docked launcher keyboard always owns a visible search/typing shelf above the key grid.
    // Freeform app bounds reserve it too, otherwise OEM re-pin paths can cover the search field.
    fun launcherSearchShelfPx(context: Context): Int = context.dp(45)

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
