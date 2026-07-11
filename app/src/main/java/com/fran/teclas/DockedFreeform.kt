package com.fran.teclas

import android.content.Context
import android.os.Bundle

/**
 * Docked-mode "open apps in the top region" feature — dormant on the Play build.
 *
 * Placing a launched app into a freeform window bounded to the top of the screen requires arming the
 * system freeform globals (`enable_freeform_support` / `force_resizable_activities`), which is only
 * writable with `WRITE_SECURE_SETTINGS` — an adb-granted, sideload-only capability. That arming path
 * (and the Shizuku re-pinning that backed it) is stripped from this build, so the feature never
 * activates: [isActive] is always false and [activityOptions] always returns null, which makes the
 * caller fall back to a normal fullscreen launch.
 *
 * The [externalAppInFront] / [lastKeyboardTopPx] signals are retained because the docked keyboard
 * router reads them; with no arming they simply never flip on. See the sideload build for the full
 * freeform + Shizuku implementation.
 */
internal object DockedFreeform {

    /**
     * True while a launcher-launched app occupies the freeform top region with the docked deck
     * visible below it. Never set on this build (freeform is dormant), but read by the keyboard
     * router and by TeclasImeService, so the field is kept.
     */
    @Volatile
    var externalAppInFront = false

    /** The launcher's measured top-region bottom, shared with the accessibility service. */
    @Volatile
    var lastKeyboardTopPx: Int = 0

    /** Dormant on the Play build (no freeform arming) — always false, so launches stay fullscreen. */
    fun isActive(context: Context): Boolean = false

    /** Always null on the Play build → the caller performs a normal fullscreen launch. */
    fun activityOptions(context: Context, keyboardTopPx: Int? = null): Bundle? = null
}
