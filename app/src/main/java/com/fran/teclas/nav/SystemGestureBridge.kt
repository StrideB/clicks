package com.fran.teclas.nav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Tries to get the *ROM's own* full-screen gestures back on a device that took them away because a
 * third-party launcher is the default home.
 *
 * ### Why this exists
 *
 * Xiaomi (MIUI 12+ / HyperOS) is the loud case: choosing a third-party launcher silently forces the
 * device back to 3-button navigation. It is not a bug — MIUI's full-screen gestures are driven by
 * its own launcher (the recents animation lives in `com.miui.home`), so with that launcher out of
 * the picture the ROM falls back to buttons rather than ship a half-working gesture stack. The ROM
 * keeps an escape hatch for it, `Settings.Global.force_fsg_nav_bar`, which is exactly what the
 * Xiaomi branch below writes. Vivo/OriginOS and a few others carry the same disable-with-third-party
 * -launcher behaviour, and where they expose a comparable flag it is unlocked the same way.
 *
 * Stock Android is a different shape entirely. There the mode is decided by which
 * `com.android.internal.systemui.navbar.*` runtime overlay is enabled, and
 * `Settings.Secure.navigation_mode` is the value SystemUI *publishes* after reading that overlay —
 * writing it changes nothing on its own. So AOSP-shaped ROMs are handled by `cmd overlay`, which
 * needs shell uid and therefore Shizuku.
 *
 * ### Why it degrades instead of failing
 *
 * Every step is attempted independently and reported back through [BridgeReport]. A ROM that has
 * never heard of `force_fsg_nav_bar` simply records the write as skipped; nothing throws, and the
 * settings screen shows the user which rungs of the ladder actually landed. When no rung lands, the
 * launcher's own [GestureNavOverlay] is the answer, and the settings screen says so.
 *
 * The only prerequisite for the settings-write path is a one-time adb grant of
 * WRITE_SECURE_SETTINGS ([adbGrantCommand]) — the same grant [com.fran.teclas.DockedFreeform]
 * already asks for, so on a device set up for docked freeform this costs nothing extra.
 */
internal object SystemGestureBridge {
    private const val TAG = "SystemGestureBridge"

    /** `Settings.Secure.navigation_mode`: 0 = 3-button, 1 = 2-button, 2 = gestural. */
    private const val SECURE_NAVIGATION_MODE = "navigation_mode"

    /**
     * MIUI/HyperOS: keep full-screen gestures available even when the default home is not
     * `com.miui.home`. This is the whole reason the Xiaomi branch exists.
     */
    private const val GLOBAL_FORCE_FSG = "force_fsg_nav_bar"

    /** MIUI/HyperOS: drop the gesture handle ("gesture line") drawn along the bottom edge. */
    private const val GLOBAL_HIDE_GESTURE_LINE = "hide_gesture_line"

    /** AOSP runtime overlays that select the navigation interaction mode. */
    private const val OVERLAY_GESTURAL = "com.android.internal.systemui.navbar.gestural"
    private const val OVERLAY_THREE_BUTTON = "com.android.internal.systemui.navbar.threebutton"

    enum class NavMode { THREE_BUTTON, TWO_BUTTON, GESTURAL, UNKNOWN }

    /** Which family of workaround applies to this device. */
    enum class Vendor { XIAOMI, AOSP_LIKE }

    /**
     * What an [applyGestures] or [revert] pass actually managed to do.
     *
     * [applied] and [skipped] are user-facing lines, not log spam: the settings screen renders them
     * verbatim so "nothing happened" is never the whole story the user gets.
     */
    data class BridgeReport(
        val applied: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
        val needsGrant: Boolean = false,
        val needsShizuku: Boolean = false,
        val restartSystemUiSuggested: Boolean = false,
    ) {
        val changedSomething: Boolean get() = applied.isNotEmpty()
    }

    // ---------------------------------------------------------------- detection

    /**
     * Vendor family from build identity. Pure and string-typed so it is unit-testable without a
     * device: [Build] statics are unset in a plain JVM test run.
     */
    fun vendorFor(manufacturer: String?, brand: String?): Vendor {
        val ids = listOfNotNull(manufacturer, brand).map { it.lowercase() }
        val xiaomi = ids.any { it == "xiaomi" || it == "redmi" || it == "poco" || it.startsWith("xiaomi") }
        return if (xiaomi) Vendor.XIAOMI else Vendor.AOSP_LIKE
    }

    fun vendor(): Vendor = vendorFor(Build.MANUFACTURER, Build.BRAND)

    fun navMode(context: Context): NavMode = when (navModeSetting(context)) {
        0 -> NavMode.THREE_BUTTON
        1 -> NavMode.TWO_BUTTON
        2 -> NavMode.GESTURAL
        else -> NavMode.UNKNOWN
    }

    private fun navModeSetting(context: Context): Int? = runCatching {
        Settings.Secure.getInt(context.contentResolver, SECURE_NAVIGATION_MODE)
    }.getOrNull()

    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** True when the ROM's forced-gesture flag is currently set — the Xiaomi path is armed. */
    fun forcedGesturesArmed(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, GLOBAL_FORCE_FSG, 0) == 1
    }.getOrDefault(false)

    /**
     * The device is already navigating by gesture, so there is nothing for the bridge to fix. Note
     * this is deliberately *not* "the bridge succeeded": a stock Pixel reads GESTURAL out of the box.
     */
    fun alreadyGestural(context: Context): Boolean = navMode(context) == NavMode.GESTURAL

    // ---------------------------------------------------------------- apply / revert

    /**
     * Walk the ladder for this device and report what landed. Safe to call repeatedly — every write
     * is idempotent, which is what makes [ensureAppliedIfRequested] cheap enough for onResume.
     */
    fun applyGestures(context: Context): BridgeReport {
        val applied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        var needsGrant = false
        var needsShizuku = false
        var restartSuggested = false

        val granted = hasWriteSecureSettings(context)
        val hidePill = GestureNavPrefs.hidePillRequested(context)

        when (vendor()) {
            Vendor.XIAOMI -> {
                if (granted) {
                    if (putGlobal(context, GLOBAL_FORCE_FSG, 1)) {
                        applied += "Forced full-screen gestures on (force_fsg_nav_bar)"
                        restartSuggested = true
                    } else {
                        skipped += "This ROM rejected force_fsg_nav_bar"
                    }
                    if (hidePill) {
                        if (putGlobal(context, GLOBAL_HIDE_GESTURE_LINE, 1)) {
                            applied += "Hid the gesture handle (hide_gesture_line)"
                        } else {
                            skipped += "This ROM rejected hide_gesture_line"
                        }
                    }
                } else {
                    needsGrant = true
                    skipped += "Needs the one-time WRITE_SECURE_SETTINGS grant"
                }
            }

            Vendor.AOSP_LIKE -> {
                // Try the overlay first: on a stock-shaped ROM it is the only thing that genuinely
                // moves the mode. The settings write below is the consolation prize for OEM ROMs
                // that read navigation_mode directly instead of deriving it from an overlay.
                if (ShizukuShell.isReady()) {
                    val result = ShizukuShell.exec(
                        "cmd overlay enable-exclusive --category $OVERLAY_GESTURAL"
                    )
                    if (result.ok) {
                        applied += "Enabled the gesture navigation overlay"
                    } else {
                        skipped += "Overlay switch failed: ${result.message().ifEmpty { "no detail" }}"
                    }
                } else {
                    needsShizuku = true
                    skipped += "Overlay switch needs Shizuku (adb-level shell)"
                }
                if (granted) {
                    if (putSecure(context, SECURE_NAVIGATION_MODE, 2)) {
                        applied += "Set navigation_mode to gestural"
                        restartSuggested = true
                    } else {
                        skipped += "This ROM rejected navigation_mode"
                    }
                } else {
                    needsGrant = true
                    skipped += "Needs the one-time WRITE_SECURE_SETTINGS grant"
                }
            }
        }

        return BridgeReport(applied, skipped, needsGrant, needsShizuku, restartSuggested)
    }

    /** Undo what [applyGestures] wrote. Best effort, same reporting shape. */
    fun revert(context: Context): BridgeReport {
        val applied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val granted = hasWriteSecureSettings(context)

        when (vendor()) {
            Vendor.XIAOMI -> {
                if (granted) {
                    if (putGlobal(context, GLOBAL_FORCE_FSG, 0)) applied += "Cleared force_fsg_nav_bar"
                    if (putGlobal(context, GLOBAL_HIDE_GESTURE_LINE, 0)) applied += "Restored the gesture handle"
                } else {
                    skipped += "Needs the one-time WRITE_SECURE_SETTINGS grant"
                }
            }
            Vendor.AOSP_LIKE -> {
                if (ShizukuShell.isReady()) {
                    val result = ShizukuShell.exec(
                        "cmd overlay enable-exclusive --category $OVERLAY_THREE_BUTTON"
                    )
                    if (result.ok) applied += "Restored 3-button navigation"
                    else skipped += "Overlay switch failed: ${result.message().ifEmpty { "no detail" }}"
                } else {
                    skipped += "Overlay switch needs Shizuku (adb-level shell)"
                }
            }
        }
        return BridgeReport(applied, skipped)
    }

    /**
     * Re-apply on resume when the user asked for ROM gestures and the ROM has quietly dropped them.
     *
     * This is not belt-and-braces: MIUI re-evaluates the third-party-launcher rule whenever the
     * default home changes, so the flag really does come back off after a launcher switch or a
     * system update. Cheap when there is nothing to do — the guard is two setting reads.
     */
    fun ensureAppliedIfRequested(context: Context) {
        if (!GestureNavPrefs.systemGesturesRequested(context)) return
        if (!hasWriteSecureSettings(context)) return
        val needsWork = when (vendor()) {
            Vendor.XIAOMI -> !forcedGesturesArmed(context)
            Vendor.AOSP_LIKE -> !alreadyGestural(context)
        }
        if (needsWork) applyGestures(context)
    }

    // ---------------------------------------------------------------- system-wide bar removal

    /**
     * Take the navigation bar off the screen system-wide, for real, on Android 12+.
     *
     * There is exactly one supported lever left. `wm overscan` was removed in Android 11 and the
     * `policy_control` immersive shortcut went with Android 12, so what remains is the switch
     * SetupWizard itself uses. It is blunt — it hides the status bar and locks the notification
     * shade too — and it does not survive a reboot, both of which the settings screen states plainly
     * before offering the button.
     *
     * Only meaningful alongside [GestureNavOverlay] or armed ROM gestures: with the bar gone and
     * neither of those in place, the device has no way to go back or home.
     */
    fun setSystemBarsHidden(hidden: Boolean): ShizukuShell.Result =
        ShizukuShell.exec("cmd statusbar disable-for-setup ${if (hidden) "true" else "false"}")

    /**
     * Bounce SystemUI so a navigation-mode write is picked up without a reboot. User-triggered only:
     * it blanks the status bar for a second or two and drops any in-progress shade interaction.
     */
    fun restartSystemUi(): ShizukuShell.Result =
        ShizukuShell.exec("pkill -f com.android.systemui || killall com.android.systemui")

    // ---------------------------------------------------------------- manual fallback

    fun adbGrantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    /**
     * The commands to paste into a terminal when neither the grant nor Shizuku is available — the
     * device-appropriate ones only, so the user is not left guessing which line is theirs.
     */
    fun manualCommands(context: Context): List<String> = buildList {
        add(adbGrantCommand(context))
        when (vendor()) {
            Vendor.XIAOMI -> {
                add("adb shell settings put global $GLOBAL_FORCE_FSG 1")
                add("adb shell settings put global $GLOBAL_HIDE_GESTURE_LINE 1")
            }
            Vendor.AOSP_LIKE -> {
                add("adb shell cmd overlay enable-exclusive --category $OVERLAY_GESTURAL")
                add("adb shell settings put secure $SECURE_NAVIGATION_MODE 2")
            }
        }
        add("adb shell cmd statusbar disable-for-setup true   # hides the bars until reboot")
    }

    // ---------------------------------------------------------------- writes

    private fun putGlobal(context: Context, key: String, value: Int): Boolean = runCatching {
        Settings.Global.putInt(context.contentResolver, key, value)
    }.onFailure { Log.w(TAG, "global $key=$value failed", it) }.getOrDefault(false)

    private fun putSecure(context: Context, key: String, value: Int): Boolean = runCatching {
        Settings.Secure.putInt(context.contentResolver, key, value)
    }.onFailure { Log.w(TAG, "secure $key=$value failed", it) }.getOrDefault(false)
}
