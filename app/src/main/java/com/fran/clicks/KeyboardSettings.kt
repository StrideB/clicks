package com.fran.clicks

import android.content.Context

internal object KeyboardSettings {
    private const val PREFS_NAME = "clicks"
    private const val KEY_PLACEMENT = "keyboard_placement"
    private const val KEY_SIZE = "keyboard_size"

    const val MODE_DOCKED = "docked"
    const val MODE_WIDGET = "widget"

    fun getPlacementMode(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PLACEMENT, MODE_DOCKED) ?: MODE_DOCKED
    }

    fun setPlacementMode(context: Context, mode: String) {
        val safe = if (mode == MODE_WIDGET) MODE_WIDGET else MODE_DOCKED
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PLACEMENT, safe)
            .apply()
    }

    fun isDocked(context: Context): Boolean = getPlacementMode(context) == MODE_DOCKED

    fun keyboardSize(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SIZE, 28)
            .coerceIn(0, 100)
    }
}
