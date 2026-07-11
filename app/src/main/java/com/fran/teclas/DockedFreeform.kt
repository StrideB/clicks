package com.fran.teclas

import android.app.ActivityOptions
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.util.Log

/**
 * Docked-mode "open apps in the top region" feature.
 *
 * When the Teclas keyboard is docked at the bottom, this launches every external app into a
 * freeform window bounded to the top of the screen (above the keyboard) instead of fullscreen.
 *
 * It leans on AOSP freeform windowing:
 *   - arming (once per device): the globals enable_freeform_support / force_resizable_activities,
 *     writable only with WRITE_SECURE_SETTINGS (adb-granted once, see [adbGrantCommand]);
 *   - per launch: ActivityOptions.launchBounds (public, API 24+) for the top rect, plus the
 *     freeform windowing mode injected directly into the options Bundle by key (avoids the hidden
 *     ActivityOptions.setLaunchWindowingMode, which is blocked on targetSdk 34+).
 *
 * Everything degrades gracefully: if the device can't do freeform or isn't armed, [activityOptions]
 * returns null and the caller falls back to a normal fullscreen launch — never a crash. Distinct
 * from [VivoDockedExperiment], which shrinks the whole display globally rather than placing a
 * single app in a top rect.
 */
internal object DockedFreeform {
    private const val TAG = "DockedFreeform"
    private const val PREFS = "teclas"
    private const val FEATURE_PREF = "docked_top_region"

    // android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM (hidden constant).
    private const val WINDOWING_MODE_FREEFORM = 5
    // ActivityOptions.KEY_LAUNCH_WINDOWING_MODE (private string key read from the options Bundle).
    private const val KEY_LAUNCH_WINDOWING_MODE = "android.activity.windowingMode"

    /**
     * True while a launcher-launched app occupies the freeform top region with the docked deck
     * visible below it. Maintained by the accessibility service from real window bounds (so it can't
     * go stale on app switches). Read by the keyboard router and by TeclasImeService, which
     * suppresses itself while this is set so the deck — not the IME — receives keystrokes.
     */
    @Volatile
    var externalAppInFront = false

    // The launcher's measured top-region bottom (Y of the keyboard dock top, with margin). Set by
    // MainActivity so the accessibility service can build Shizuku pin bounds without the launcher views.
    @Volatile
    var lastKeyboardTopPx: Int = 0

    /** The rect an app should occupy in the docked top region, used for Shizuku pinning. */
    fun pinBounds(context: Context): Rect {
        val dm = context.resources.displayMetrics
        val minH = (360 * dm.density).toInt()
        val bottom = lastKeyboardTopPx.takeIf { it in minH..dm.heightPixels }
            ?: (dm.heightPixels - keyboardHeightPx(context)).coerceAtLeast(minH)
        return Rect(0, 0, dm.widthPixels, bottom)
    }

    // Settings.Global keys that arm system-wide freeform (hidden; require WRITE_SECURE_SETTINGS).
    private const val ENABLE_FREEFORM = "enable_freeform_support"
    private const val FORCE_RESIZABLE = "force_resizable_activities"
    private const val SIZECOMPAT_FREEFORM = "enable_sizecompat_freeform_scaling"

    /** The user-facing toggle (only meaningful in docked mode). Default on. */
    fun isFeatureEnabled(context: Context): Boolean =
        prefs(context).getBoolean(FEATURE_PREF, true)

    /**
     * Arm freeform if the feature is on and we already hold WRITE_SECURE_SETTINGS but aren't armed
     * yet — so "default on" actually works on a granted device without the user tapping the toggle.
     * No-op otherwise (arming needs the one-time adb grant). Safe to call on every resume.
     */
    fun ensureArmedIfEnabled(context: Context) {
        if (isFeatureEnabled(context) && hasWriteSecureSettings(context) && !isArmed(context)) {
            arm(context)
        }
    }

    fun setFeatureEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(FEATURE_PREF, enabled).apply()
    }

    /**
     * Device can place windows in freeform. Note: some OEMs (e.g. Vivo/OriginOS) support freeform
     * but do NOT advertise FEATURE_FREEFORM_WINDOW_MANAGEMENT — verified on a V2562/Android 16 where
     * `am start --windowingMode 5` yields a real freeform task. So armed globals (which we control)
     * count as capable too, otherwise the feature would be wrongly disabled on exactly those devices.
     */
    fun hasFreeformCapability(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
            isArmed(context)

    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Freeform globals are currently set — the launch bounds will actually be honored. */
    fun isArmed(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, ENABLE_FREEFORM, 0) == 1 &&
            Settings.Global.getInt(context.contentResolver, FORCE_RESIZABLE, 0) == 1
    }.getOrDefault(false)

    /**
     * Everything needed for the top-region launch to work right now: feature on and freeform armed.
     * Armed (enable_freeform_support + force_resizable_activities) is the real, device-agnostic
     * enabler — it's what makes launchBounds honored, and it implies capability. When false,
     * [activityOptions] returns null and the caller launches fullscreen.
     */
    fun isActive(context: Context): Boolean =
        isFeatureEnabled(context) && isArmed(context)

    /**
     * Write the freeform globals. Requires WRITE_SECURE_SETTINGS; the WindowManager only re-reads
     * config_freeformWindowManagement at boot, so callers should tell the user to reboot once.
     * Returns true if the writes went through.
     */
    fun arm(context: Context): Boolean {
        if (!hasWriteSecureSettings(context)) return false
        return runCatching {
            val cr = context.contentResolver
            Settings.Global.putInt(cr, ENABLE_FREEFORM, 1)
            Settings.Global.putInt(cr, FORCE_RESIZABLE, 1)
            // Lets non-resizable apps scale in-window instead of hard letterboxing.
            runCatching { Settings.Global.putInt(cr, SIZECOMPAT_FREEFORM, 1) }
            true
        }.onFailure { Log.w(TAG, "arm failed", it) }.getOrDefault(false)
    }

    /** Revert the freeform globals we set (best effort; also needs a reboot to fully clear). */
    fun disarm(context: Context) {
        if (!hasWriteSecureSettings(context)) return
        runCatching {
            val cr = context.contentResolver
            Settings.Global.putInt(cr, ENABLE_FREEFORM, 0)
            Settings.Global.putInt(cr, FORCE_RESIZABLE, 0)
            runCatching { Settings.Global.putInt(cr, SIZECOMPAT_FREEFORM, 0) }
        }.onFailure { Log.w(TAG, "disarm failed", it) }
    }

    fun adbGrantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    /**
     * Top rectangle the launched app should occupy: full width, from the top of the display down to
     * the top of the docked keyboard. [keyboardTopPx] is the keyboard band's actual on-screen top
     * (from the launcher's laid-out dock view) for a pixel-perfect fit; when null/unusable it falls
     * back to the height formula.
     */
    fun topBounds(context: Context, keyboardTopPx: Int? = null): Rect {
        val dm = context.resources.displayMetrics
        val minHeight = (360 * dm.density).toInt()
        val bottom = if (keyboardTopPx != null && keyboardTopPx in minHeight..dm.heightPixels) {
            keyboardTopPx
        } else {
            (dm.heightPixels - keyboardHeightPx(context)).coerceAtLeast(minHeight)
        }
        return Rect(0, 0, dm.widthPixels, bottom)
    }

    /**
     * ActivityOptions that place a launch into the top freeform rect, or null when the feature
     * isn't active (caller then launches fullscreen as before). [keyboardTopPx] is the measured
     * on-screen top of the docked keyboard, so the app's bottom edge meets the keys exactly.
     */
    fun activityOptions(context: Context, keyboardTopPx: Int? = null): Bundle? {
        if (!isActive(context)) return null
        val bounds = topBounds(context, keyboardTopPx)
        // launchBounds via the public setter (API 24+); windowing mode injected directly into the
        // options Bundle by key — avoids ActivityOptions.setLaunchWindowingMode, which is a hidden
        // test-api and is blocked on targetSdk 34+ (the hidden-API exemption is itself blocked there).
        val bundle = ActivityOptions.makeBasic().apply { launchBounds = bounds }.toBundle()
        bundle.putInt(KEY_LAUNCH_WINDOWING_MODE, WINDOWING_MODE_FREEFORM)
        Log.i(TAG, "docked freeform launch bounds=$bounds")
        return bundle
    }

    private fun keyboardHeightPx(context: Context): Int {
        val density = context.resources.displayMetrics.density
        val size = KeyboardSettings.keyboardSize(context)
        return ((238 + (size * 54 / 100)) * density).toInt()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
