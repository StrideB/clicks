package com.fran.teclas

import android.content.Context

internal object DockedKeyboardMetrics {
    fun overlayBottomLiftPx(context: Context): Int = context.dp(0)

    fun externalReservedBandHeightPx(context: Context): Int {
        val density = context.resources.displayMetrics.density
        val size = KeyboardSettings.keyboardSize(context)
        val deckHeight = ((238 + (size * 54 / 100)) * density).toInt()
        return deckHeight + overlayBottomLiftPx(context)
    }

    fun freeformTargetNudgePx(context: Context): Int = context.dp(70)

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
