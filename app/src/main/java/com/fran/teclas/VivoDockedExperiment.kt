package com.fran.teclas

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.IBinder
import android.os.ResultReceiver
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.inputmethod.InputMethodManager

/**
 * Personal Vivo-only docked mode experiment.
 *
 * This is intentionally gated to debuggable Vivo builds. It uses hidden/fragile
 * window-manager paths for local testing and must not become normal Play-store behavior.
 */
internal object VivoDockedExperiment {
    private const val LEGACY_DEBUG_PREF = "vivo_docked_experiment"
    private const val MODE_PREF = "vivo_docked_experiment_mode"
    private const val DEV_EXPERIMENTS_PREF = "dev_experiments"
    private const val STATUS_PREF = "vivo_docked_experiment_status"
    const val MODE_OFF = "off"
    const val MODE_WM_SIZE = "wm_size"
    const val MODE_OVERSCAN = "overscan"
    private const val TAG = "VivoDockedExperiment"
    private var originalWidth = 0
    private var originalHeight = 0
    private var applied = false

    fun isAvailable(context: Context): Boolean {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val vivo = Build.MANUFACTURER.contains("vivo", ignoreCase = true) ||
            Build.BRAND.contains("vivo", ignoreCase = true)
        return debuggable && vivo
    }

    fun currentMode(context: Context): String {
        val prefs = context.getSharedPreferences("teclas", Context.MODE_PRIVATE)
        val saved = prefs.getString(MODE_PREF, null)
        if (saved in setOf(MODE_OFF, MODE_WM_SIZE, MODE_OVERSCAN)) return saved.orEmpty()
        return if (prefs.getBoolean(LEGACY_DEBUG_PREF, true)) MODE_WM_SIZE else MODE_OFF
    }

    fun setMode(context: Context, mode: String) {
        val safe = if (mode in setOf(MODE_OFF, MODE_WM_SIZE, MODE_OVERSCAN)) mode else MODE_OFF
        context.getSharedPreferences("teclas", Context.MODE_PRIVATE)
            .edit()
            .putString(MODE_PREF, safe)
            .putBoolean(LEGACY_DEBUG_PREF, safe != MODE_OFF)
            .apply()
        applied = false
    }

    fun modeLabel(context: Context): String = when (currentMode(context)) {
        MODE_WM_SIZE -> "WM SIZE"
        MODE_OVERSCAN -> "OVERSCAN"
        else -> "OFF"
    }

    fun isEnabled(context: Context): Boolean {
        val devEnabled = context.getSharedPreferences("teclas", Context.MODE_PRIVATE)
            .getBoolean(DEV_EXPERIMENTS_PREF, false)
        return isAvailable(context) && devEnabled && currentMode(context) != MODE_OFF
    }

    fun keyboardHeight(context: Context): Int {
        val density = context.resources.displayMetrics.density
        val size = KeyboardSettings.keyboardSize(context)
        return ((238 + (size * 54 / 100)) * density).toInt()
    }

    @SuppressLint("PrivateApi")
    fun applyViewportTruncation(context: Context) {
        if (!isEnabled(context)) {
            status(context, "skip: unavailable/dev-off/mode-off")
            return
        }
        if (currentMode(context) == MODE_OVERSCAN) {
            applied = applyOverscan(context)
            status(context, "overscan applied=$applied")
            return
        }
        val realSize = physicalDisplaySize(context)
        originalWidth = realSize.x
        originalHeight = realSize.y
        val targetHeight = (originalHeight - keyboardHeight(context)).coerceAtLeast((360 * context.resources.displayMetrics.density).toInt())
        if (targetHeight >= originalHeight) {
            status(context, "skip: targetHeight=$targetHeight originalHeight=$originalHeight")
            return
        }
        Log.i(TAG, "Applying viewport truncation ${originalWidth}x$targetHeight from physical ${originalWidth}x$originalHeight")
        status(context, "apply start ${originalWidth}x$targetHeight")

        val reflected = runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            val windowBinder = getService.invoke(null, Context.WINDOW_SERVICE) as IBinder
            val stub = Class.forName("android.view.IWindowManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val wm = asInterface.invoke(null, windowBinder)
            val method = wm.javaClass.getMethod(
                "setForcedDisplaySize",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            method.invoke(wm, Display.DEFAULT_DISPLAY, originalWidth, targetHeight)
            true
        }.onFailure {
            Log.w(TAG, "setForcedDisplaySize failed; trying settings/shell fallback", it)
            status(context, "reflection failed: ${it.javaClass.simpleName}: ${it.message}")
        }
            .getOrDefault(false)

        val settingsApplied = if (!reflected) applyDisplaySizeSetting(context, originalWidth, targetHeight) else false
        val shellApplied = if (!reflected && !settingsApplied) {
            runShell("wm size ${originalWidth}x$targetHeight", arrayOf("wm", "size", "${originalWidth}x$targetHeight"))
        } else false
        applied = reflected || settingsApplied || shellApplied
        status(context, "apply result reflected=$reflected settings=$settingsApplied shell=$shellApplied target=${originalWidth}x$targetHeight")
    }

    @SuppressLint("PrivateApi")
    fun clearViewportTruncation(context: Context) {
        if (!isAvailable(context)) return
        runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            val windowBinder = getService.invoke(null, Context.WINDOW_SERVICE) as IBinder
            val stub = Class.forName("android.view.IWindowManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val wm = asInterface.invoke(null, windowBinder)
            val clear = wm.javaClass.getMethod("clearForcedDisplaySize", Int::class.javaPrimitiveType)
            clear.invoke(wm, Display.DEFAULT_DISPLAY)
        }.onFailure { Log.w(TAG, "clearForcedDisplaySize failed; shell fallback may require shell/root", it)
            runShell("wm size reset", arrayOf("wm", "size", "reset"))
        }
        clearDisplaySizeSetting(context)
        runShell("wm overscan reset", arrayOf("wm", "overscan", "reset"))
        applied = false
        status(context, "cleared viewport")
    }

    fun forceImeVisible(service: InputMethodService) {
        if (!isEnabled(service) || KeyboardSettings.getPlacementMode(service) != KeyboardSettings.MODE_DOCKED) return
        runCatching {
            val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val method = imm.javaClass.getMethod(
                "showSoftInputUnchecked",
                Int::class.javaPrimitiveType,
                ResultReceiver::class.java
            )
            method.invoke(imm, InputMethodManager.SHOW_FORCED, null)
        }.onFailure {
            runCatching { service.requestShowSelf(InputMethodManager.SHOW_FORCED) }
        }
    }

    fun injectInput(value: String) {
        val cmd = when (value) {
            "⌫" -> "input keyevent 67"
            "⏎" -> "input keyevent 66"
            " " -> "input keyevent 62"
            else -> "input text ${value.replace(" ", "%s")}"
        }
        runCatching { Runtime.getRuntime().exec(cmd) }
    }

    private fun applyOverscan(context: Context): Boolean {
        val bottomInset = keyboardHeight(context).coerceAtLeast(1)
        Log.i(TAG, "Applying overscan bottom inset $bottomInset")
        return runShell("wm overscan 0,0,0,$bottomInset", arrayOf("wm", "overscan", "0,0,0,$bottomInset"))
    }

    private fun applyDisplaySizeSetting(context: Context, width: Int, height: Int): Boolean {
        return runCatching {
            Settings.Global.putString(context.contentResolver, "display_size_forced", "${width},${height}")
        }.onFailure {
            Log.w(TAG, "display_size_forced fallback failed", it)
            status(context, "settings failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
    }

    private fun clearDisplaySizeSetting(context: Context) {
        runCatching {
            Settings.Global.putString(context.contentResolver, "display_size_forced", null)
        }.onFailure {
            Log.w(TAG, "display_size_forced clear failed", it)
        }
    }

    private fun runShell(label: String, command: Array<String>): Boolean {
        return runCatching {
            val process = Runtime.getRuntime().exec(command)
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Log.w(TAG, "$label exited $exitCode: ${process.errorStream.bufferedReader().readText().trim()}")
            }
            exitCode == 0
        }.onFailure { Log.w(TAG, "$label failed", it) }
            .getOrDefault(false)
    }

    private fun status(context: Context, value: String) {
        context.getSharedPreferences("teclas", Context.MODE_PRIVATE)
            .edit()
            .putString(STATUS_PREF, "${System.currentTimeMillis()}: $value")
            .apply()
    }

    private fun realDisplaySize(context: Context): Point {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        return Point().also { display.getRealSize(it) }
    }

    private fun physicalDisplaySize(context: Context): Point {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        return if (mode != null) {
            Point(mode.physicalWidth, mode.physicalHeight)
        } else {
            realDisplaySize(context)
        }
    }
}
