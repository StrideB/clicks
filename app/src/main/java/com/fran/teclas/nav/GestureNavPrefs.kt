package com.fran.teclas.nav

import android.content.Context
import android.content.SharedPreferences

/**
 * Everything the gesture-navigation feature persists.
 *
 * Two independent halves live here, and keeping them apart matters:
 *
 *  - [systemGesturesRequested] drives [SystemGestureBridge] — asking the *ROM* to give the real
 *    system gestures back on a device that revoked them for third-party launchers. When that works
 *    it is strictly better than anything we can draw: real animations, real quick-switch, no touch
 *    interception.
 *  - [overlayEnabled] drives [GestureNavOverlay] — our own edge strips, dispatched through the
 *    accessibility service. That is the fallback for ROMs where the bridge can't win.
 *
 * They are not mutually exclusive on purpose: a user can arm the ROM gestures and still keep our
 * bottom strip off, or run the overlay on a stock device that already has gestures. The settings
 * screen presents them as a ladder, but nothing here enforces an order.
 *
 * Shares the launcher's "teclas" preference file (same as [com.fran.teclas.KeyboardSettings]) so the
 * accessibility service can watch a single file for changes instead of three.
 */
internal object GestureNavPrefs {
    private const val PREFS_NAME = "teclas"

    /** Ask the ROM for real system gestures (Xiaomi FSG force-flag / AOSP navbar overlay). */
    const val KEY_SYSTEM_GESTURES = "gesture_nav_system_requested"

    /** Draw our own edge strips and dispatch back/home/recents through the accessibility service. */
    const val KEY_OVERLAY = "gesture_nav_overlay"

    /** Hide the navigation bar inside the launcher's own window (no permission needed). */
    const val KEY_HIDE_NAV_ON_HOME = "gesture_nav_hide_on_home"

    /** Hide the ROM's gesture pill / handle where the ROM exposes a switch for it. */
    const val KEY_HIDE_PILL = "gesture_nav_hide_pill"

    const val KEY_LEFT_EDGE = "gesture_nav_left_edge"
    const val KEY_RIGHT_EDGE = "gesture_nav_right_edge"
    const val KEY_BOTTOM_BAR = "gesture_nav_bottom_bar"
    const val KEY_SHOW_HANDLE = "gesture_nav_show_handle"
    const val KEY_HAPTICS = "gesture_nav_haptics"
    const val KEY_TAP_PASSTHROUGH = "gesture_nav_tap_passthrough"
    const val KEY_EDGE_WIDTH_DP = "gesture_nav_edge_width_dp"
    const val KEY_BOTTOM_HEIGHT_DP = "gesture_nav_bottom_height_dp"

    /** Every key that should make the running overlay rebuild itself. */
    val OVERLAY_KEYS = setOf(
        KEY_OVERLAY, KEY_LEFT_EDGE, KEY_RIGHT_EDGE, KEY_BOTTOM_BAR, KEY_SHOW_HANDLE,
        KEY_HAPTICS, KEY_TAP_PASSTHROUGH, KEY_EDGE_WIDTH_DP, KEY_BOTTOM_HEIGHT_DP,
    )

    // Defaults tuned against the stock gesture areas: AOSP uses a 24dp bottom handle region and a
    // ~20dp back-sensitive edge. Ours sit just inside those so an armed ROM's own gestures stay
    // reachable when both are live, and so an app's bottom navigation row is still tappable.
    const val DEFAULT_EDGE_WIDTH_DP = 16
    const val DEFAULT_BOTTOM_HEIGHT_DP = 20
    const val MIN_EDGE_WIDTH_DP = 8
    const val MAX_EDGE_WIDTH_DP = 40
    const val MIN_BOTTOM_HEIGHT_DP = 10
    const val MAX_BOTTOM_HEIGHT_DP = 48

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun systemGesturesRequested(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SYSTEM_GESTURES, false)

    fun overlayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OVERLAY, false)

    /**
     * Hiding the bar inside our own window is only offered together with the overlay: without an
     * alternative way to press back the launcher would be a screen you cannot leave except by home,
     * and on a 3-button ROM that is a genuine trap. Callers read this, never the raw key.
     */
    fun hideNavBarOnHome(context: Context): Boolean =
        overlayEnabled(context) && prefs(context).getBoolean(KEY_HIDE_NAV_ON_HOME, true)

    fun hidePillRequested(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_PILL, false)

    fun leftEdgeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LEFT_EDGE, true)

    fun rightEdgeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RIGHT_EDGE, true)

    fun bottomBarEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BOTTOM_BAR, true)

    fun showHandle(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_HANDLE, true)

    fun hapticsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HAPTICS, true)

    /**
     * Replay a bare tap on the bottom strip as a synthesised tap underneath it.
     *
     * With the navigation bar gone, apps lay their own bottom row out against the physical edge —
     * directly under our strip. We consume the DOWN before we know it is a tap, so without this the
     * bottom-most row of every app is dead. Default on for exactly that reason.
     */
    fun tapPassthrough(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TAP_PASSTHROUGH, true)

    fun edgeWidthDp(context: Context): Int =
        prefs(context).getInt(KEY_EDGE_WIDTH_DP, DEFAULT_EDGE_WIDTH_DP)
            .coerceIn(MIN_EDGE_WIDTH_DP, MAX_EDGE_WIDTH_DP)

    fun bottomHeightDp(context: Context): Int =
        prefs(context).getInt(KEY_BOTTOM_HEIGHT_DP, DEFAULT_BOTTOM_HEIGHT_DP)
            .coerceIn(MIN_BOTTOM_HEIGHT_DP, MAX_BOTTOM_HEIGHT_DP)

    fun setBoolean(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }

    fun setInt(context: Context, key: String, value: Int) {
        prefs(context).edit().putInt(key, value).apply()
    }
}
