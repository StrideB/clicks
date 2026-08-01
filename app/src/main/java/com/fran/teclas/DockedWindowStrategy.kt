package com.fran.teclas

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.fran.teclas.nav.ShizukuShell

/**
 * Getting a launched app to occupy the top half of the screen while the keyboard is docked, on ROMs
 * where the two existing routes are refused.
 *
 * ### What already existed, and why Xiaomi falls through it
 *
 * [DockedFreeform] launches with `ActivityOptions.launchBounds` plus the freeform windowing mode,
 * and [ShizukuPinner] re-asserts that through the privileged `IActivityTaskManager`. Both work on
 * Galaxy and on Vivo/OriginOS. Both are AOSP-shaped, and MIUI/HyperOS is not: its multi-window is a
 * separate implementation ("small window"), and the ATM calls that AOSP leaves open to a privileged
 * caller are gated to Xiaomi's own launcher. So `launchBounds` is ignored and `setTaskWindowingMode`
 * returns without doing anything — no exception, no error, just a fullscreen app.
 *
 * ### What this adds
 *
 * Two more rungs below those, and a fallback that was already written but never wired up:
 *
 *  - **shell launch** — `am start --windowingMode 5`, run at shell uid through Shizuku. The gate
 *    MIUI applies is on *who is asking*; shell is not a third-party app, so the same request goes
 *    through. This is the rung most likely to be the answer on HyperOS.
 *  - **shell resize** — `am task resize`, again as shell, to move whatever window did appear into
 *    the top region. Split from the launch because they fail independently: a ROM can accept the
 *    freeform launch and still refuse a resize, and knowing which is which is the whole point.
 *  - **split screen** — [MainActivity.requestDockedSplitFallbackIfNeeded] existed but had no
 *    caller, so the last resort had never once run. Split screen is not freeform, but it is the one
 *    multi-window mode every OEM supports properly, and it produces exactly what the docked mode
 *    wants: the app in the top half, the keyboard below it.
 *
 * ### Why it reports instead of just trying
 *
 * None of this is verifiable without a Xiaomi in hand — every rung is reasoned from how MIUI gates
 * these calls, not from a device. So each attempt records what it did and what came back, and
 * [diagnose] runs the whole ladder on demand and hands the result to the user. The next change to
 * this file should be driven by that output rather than by another guess.
 */
internal object DockedWindowStrategy {
    private const val TAG = "DockedWindowStrategy"

    /** `android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM`. */
    private const val WINDOWING_MODE_FREEFORM = 5

    /**
     * MIUI's master switch for its own behaviour overrides. With it off, MIUI stops substituting its
     * implementations for a number of AOSP paths — multi-window among them. It is a real, system-wide
     * change (it also relaxes MIUI's permission handling and can affect battery behaviour), so it is
     * never written as part of a normal launch: only from [applyMiuiOptimizationOff], behind an
     * explicit, warned action.
     */
    private const val MIUI_OPTIMIZATION = "miui_optimization"

    /** One rung of the ladder: what was tried, whether it took, and what came back. */
    data class Attempt(val name: String, val ok: Boolean, val detail: String)

    fun isXiaomi(): Boolean =
        listOfNotNull(Build.MANUFACTURER, Build.BRAND).map { it.lowercase() }
            .any { it == "xiaomi" || it == "redmi" || it == "poco" || it.startsWith("xiaomi") }

    // ---------------------------------------------------------------- shell rungs

    /**
     * Launch [packageName] straight into freeform at shell uid, then size it to [bounds].
     *
     * `am start` takes no bounds argument, so the window first appears at whatever default the ROM
     * picks and is moved immediately after. The two steps are reported separately.
     */
    fun shellLaunchFreeform(context: Context, packageName: String, bounds: Rect): List<Attempt> {
        if (!ShizukuShell.isReady()) {
            return listOf(Attempt("shell launch", false, "Shizuku not connected"))
        }
        val component = launchComponent(context, packageName)
            ?: return listOf(Attempt("shell launch", false, "no launcher activity for $packageName"))

        // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK — the same clearing the in-process launch
        // does, for the same reason: windowing mode binds to a task's root activity, so an existing
        // task for this app would simply reopen fullscreen.
        val result = ShizukuShell.exec(
            "am start --windowingMode $WINDOWING_MODE_FREEFORM -f 0x18000000 -n $component"
        )
        val launch = Attempt(
            "shell launch (am start --windowingMode $WINDOWING_MODE_FREEFORM)",
            result.ok,
            result.message().ifEmpty { if (result.ok) "started" else "no detail" },
        )
        return listOf(launch, shellResize(packageName, bounds))
    }

    /** Move [packageName]'s foreground task to [bounds] with `am task resize`. */
    fun shellResize(packageName: String, bounds: Rect): Attempt {
        if (!ShizukuShell.isReady()) return Attempt("shell resize", false, "Shizuku not connected")
        val taskId = ShizukuPinner.foregroundTaskId(packageName)
            ?: return Attempt("shell resize", false, "could not read the task list")
        val result = ShizukuShell.exec(
            "am task resize $taskId ${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}"
        )
        return Attempt(
            "shell resize (am task resize $taskId)",
            result.ok,
            result.message().ifEmpty { if (result.ok) "resized" else "no detail" },
        )
    }

    // ---------------------------------------------------------------- escalation

    private const val PREFS = "teclas"
    private const val KEY_RUNG = "docked_window_rung"

    const val RUNG_NATIVE = "native"
    const val RUNG_BINDER = "binder"
    const val RUNG_SHELL = "shell"
    const val RUNG_SPLIT = "split"

    /**
     * The rung that last placed an app successfully on this device, or null if we've never got one
     * to land.
     *
     * Latched because escalation is not free to repeat: walking the whole ladder on every launch
     * means a binder call and a shell round-trip behind every app open, on exactly the ROM where
     * both are going to be refused. Once a rung works it is remembered, and a rung that stops
     * working simply escalates again from there.
     */
    fun rung(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RUNG, null)

    /**
     * Record the rung that placed a window. Only ever called after a measurement confirmed it —
     * writing it on "we tried this one" would make the report's headline a guess, and the report
     * exists because guesses are what got this feature into trouble.
     */
    fun recordRung(context: Context, rung: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RUNG, rung).apply()
    }

    /** How long to let a window settle after a rung before measuring whether it actually moved. */
    private const val SETTLE_MS = 600L

    /**
     * Try progressively harder to get [packageName] into [bounds], stopping at the first rung whose
     * effect can be *seen*. Returns the rung that worked, or null when the caller should fall back
     * to split screen.
     *
     * [verify] re-reads the measured window bounds (true = placed, false = still wrong, null =
     * cannot tell). It is the whole point of this function. An earlier version stopped as soon as a
     * rung returned true, and on HyperOS that meant stopping at the binder pin — which MIUI accepts
     * without acting on, leaving the app floating at the ROM's own default small-window geometry
     * with the shell rung never tried. A rung's own boolean says "the call did not throw"; only the
     * measurement says "the window moved".
     *
     * `null` from [verify] is accepted rather than treated as failure: with the accessibility
     * service off there is no measurement to be had, and refusing to believe any rung would drop
     * every launch to split screen. Only a positive "still wrong" keeps the ladder climbing.
     *
     * Binder-uid first because it is the cheapest and the least visible; shell second because it is
     * the one that gets past MIUI's caller check. Call off the main thread — both do binder work,
     * and this sleeps between rungs.
     */
    fun escalate(
        context: Context,
        packageName: String,
        bounds: Rect,
        verify: () -> Boolean?,
    ): String? {
        if (ShizukuPinner.pin(packageName, bounds)) {
            if (settledInto(verify)) {
                Log.i(TAG, "escalate: binder pin placed $packageName")
                recordRung(context, RUNG_BINDER)
                return RUNG_BINDER
            }
            Log.i(TAG, "escalate: binder pin was accepted but the window did not move — climbing")
        }
        val resize = shellResize(packageName, bounds)
        Log.i(TAG, "escalate: ${resize.name} ok=${resize.ok} ${resize.detail}")
        if (resize.ok && settledInto(verify)) {
            recordRung(context, RUNG_SHELL)
            return RUNG_SHELL
        }
        return null
    }

    private fun settledInto(verify: () -> Boolean?): Boolean {
        runCatching { Thread.sleep(SETTLE_MS) }
        return verify() != false
    }

    // ---------------------------------------------------------------- MIUI arming

    fun miuiOptimizationOff(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, MIUI_OPTIMIZATION, 1) == 0
    }.getOrDefault(false)

    /**
     * Turn MIUI Optimization off. Deliberately its own function, never folded into arming: it is a
     * broad system-wide change and the caller is expected to warn before offering it.
     */
    fun applyMiuiOptimizationOff(context: Context): Attempt {
        val viaSettings = runCatching {
            Settings.Global.putInt(context.contentResolver, MIUI_OPTIMIZATION, 0)
        }.getOrDefault(false)
        if (viaSettings) return Attempt("MIUI optimization off", true, "written")
        if (!ShizukuShell.isReady()) {
            return Attempt("MIUI optimization off", false, "needs the settings grant or Shizuku")
        }
        val result = ShizukuShell.exec("settings put global $MIUI_OPTIMIZATION 0")
        return Attempt(
            "MIUI optimization off",
            result.ok,
            result.message().ifEmpty { if (result.ok) "written via shell" else "no detail" },
        )
    }

    // ---------------------------------------------------------------- diagnosis

    /**
     * Run every rung against [packageName] and report. This is the honest version of "does docked
     * half-screen work on this device" — it answers with what the device actually did rather than
     * with what the feature flags claim.
     */
    fun diagnose(context: Context, packageName: String?, bounds: Rect): List<Attempt> = buildList {
        add(
            Attempt(
                "device",
                true,
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · " +
                    if (isXiaomi()) "Xiaomi family" else "not Xiaomi",
            )
        )
        add(
            Attempt(
                "settings grant",
                DockedFreeform.hasWriteSecureSettings(context),
                if (DockedFreeform.hasWriteSecureSettings(context)) "granted" else "missing",
            )
        )
        add(
            Attempt(
                "freeform globals armed",
                DockedFreeform.isArmed(context),
                if (DockedFreeform.isArmed(context)) "enable_freeform_support + force_resizable_activities"
                else "not armed",
            )
        )
        add(
            Attempt(
                "system freeform feature",
                DockedFreeform.hasFreeformCapability(context),
                if (DockedFreeform.hasFreeformCapability(context)) "advertised or armed" else "not advertised",
            )
        )
        add(Attempt("Shizuku", ShizukuShell.isReady(), if (ShizukuShell.isReady()) "connected" else "not connected"))
        if (isXiaomi()) {
            add(
                Attempt(
                    "MIUI optimization",
                    miuiOptimizationOff(context),
                    if (miuiOptimizationOff(context)) "off (multi-window paths relaxed)" else "on (MIUI overrides active)",
                )
            )
        }
        if (packageName == null) {
            add(Attempt("live test", false, "launch an app from the dock first, then re-run"))
            return@buildList
        }
        // Distinguish "we looked and there is no task" from "we cannot look". Both come back as a
        // null task id, and reporting the second as the first blames the app for Shizuku being off.
        val taskId = ShizukuPinner.foregroundTaskId(packageName)
        add(
            Attempt(
                "task lookup",
                taskId != null,
                when {
                    taskId != null -> "task $taskId"
                    !ShizukuShell.isReady() -> "cannot look — the task list needs Shizuku"
                    else -> "no task found for $packageName"
                },
            )
        )
        if (taskId != null) {
            add(
                Attempt(
                    "binder pin",
                    ShizukuPinner.pin(packageName, bounds),
                    "IActivityTaskManager.setTaskWindowingMode + resizeTask",
                )
            )
            add(shellResize(packageName, bounds))
        }
    }

    fun reportText(attempts: List<Attempt>): String =
        attempts.joinToString("\n") { "${if (it.ok) "✓" else "✗"}  ${it.name}\n     ${it.detail}" }

    private fun launchComponent(context: Context, packageName: String): String? =
        runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)?.component?.flattenToString()
        }.getOrNull()
}
