package com.fran.teclas.onboarding

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.util.Locale

/**
 * Background-survival setup for aggressive OEM ROMs (MagicOS/Honor, EMUI/Huawei,
 * OriginOS/Vivo, MIUI/Xiaomi, ColorOS/Oppo, etc.).
 *
 * MagicOS combines Android's stopped-state with Honor's own "Manage automatically"
 * power control: a launcher's accessibility injector, docked-keyboard overlay and
 * notification listener get killed in the background unless the user *manually*
 * exempts the app. Honor exposes **no developer API** to request this — survival
 * depends entirely on the user flipping settings. So the only thing we can do is
 * detect the risk and walk the user to the exact screens.
 *
 * This helper is pure logic + intents; it holds no UI. Feed [buildChecklist] into
 * whatever onboarding surface you render and call [launch] when the user taps a step.
 */
object MagicOsSetup {

    enum class Vendor { HONOR, HUAWEI, VIVO, XIAOMI, OPPO, ONEPLUS, SAMSUNG, OTHER }

    data class Step(
        val id: String,
        val title: String,
        val rationale: String,
        /** True once the user has satisfied it (best-effort; some can't be read back). */
        val satisfied: Boolean,
        /** Ordered intents to try; first that resolves wins. */
        val intents: List<Intent>,
    )

    fun vendor(): Vendor {
        val id = listOf(Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.MODEL)
            .joinToString(" ").lowercase(Locale.US)
        return when {
            id.contains("honor") -> Vendor.HONOR
            id.contains("huawei") -> Vendor.HUAWEI
            id.contains("vivo") || id.contains("iqoo") -> Vendor.VIVO
            id.contains("xiaomi") || id.contains("redmi") || id.contains("poco") -> Vendor.XIAOMI
            id.contains("oppo") || id.contains("realme") -> Vendor.OPPO
            id.contains("oneplus") -> Vendor.ONEPLUS
            id.contains("samsung") -> Vendor.SAMSUNG
            else -> Vendor.OTHER
        }
    }

    /** ROMs known to kill background launcher services unless whitelisted. */
    fun isAggressiveRom(): Boolean = vendor() in setOf(
        Vendor.HONOR, Vendor.HUAWEI, Vendor.VIVO, Vendor.XIAOMI, Vendor.OPPO, Vendor.ONEPLUS
    )

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * The onboarding steps relevant to *this* device, in the order we want the user to
     * complete them. Skips steps that don't apply (e.g. no autostart step off aggressive
     * ROMs). Steps whose state we can read back are marked [Step.satisfied].
     */
    fun buildChecklist(context: Context): List<Step> {
        val steps = mutableListOf<Step>()

        steps += Step(
            id = "battery",
            title = "Allow unrestricted battery use",
            rationale = "Keeps the keyboard, gestures and notification badges working when Teclas is in the background.",
            satisfied = isIgnoringBatteryOptimizations(context),
            intents = batteryIntents(context),
        )

        if (isAggressiveRom()) {
            steps += Step(
                id = "autostart",
                title = "Enable auto-launch / remove from restricted apps",
                rationale = "${vendorLabel()} stops relaunching Teclas after it's swiped away or the phone sleeps. Turn on auto-launch so it comes back.",
                satisfied = false, // not readable via public API
                intents = autostartIntents(),
            )
        }

        return steps
    }

    /** Launch the first intent in [step] that the system can resolve. Returns false if none. */
    fun launch(context: Context, step: Step): Boolean {
        for (intent in step.intents) {
            val resolvable = intent.resolveActivity(context.packageManager) != null
            if (resolvable) {
                runCatching {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onSuccess { return true }
            }
        }
        return false
    }

    // --- intent tables -------------------------------------------------------

    private fun batteryIntents(context: Context): List<Intent> = listOf(
        // Direct per-app request. Requires REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in the
        // manifest. Fine for the sideload/main build; DO NOT ship on the Play build —
        // Google restricts this action and will reject the listing.
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}")),
        // Fallback: the full battery-optimization list, user finds Teclas manually.
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}")),
    )

    /**
     * Best-effort deep links to each OEM's autostart / protected-apps manager. These are
     * private components with no stability guarantee, so every one is tried behind a
     * resolveActivity() check and we always fall back to app details.
     */
    private fun autostartIntents(): List<Intent> {
        val components = when (vendor()) {
            Vendor.HONOR, Vendor.HUAWEI -> listOf(
                "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupapp.StartupAppControlActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            )
            Vendor.VIVO -> listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            )
            Vendor.XIAOMI -> listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
            Vendor.OPPO, Vendor.ONEPLUS -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            )
            else -> emptyList()
        }
        return components.map { (pkg, cls) ->
            Intent().setComponent(ComponentName(pkg, cls))
        }
    }

    private fun vendorLabel(): String = when (vendor()) {
        Vendor.HONOR -> "MagicOS"
        Vendor.HUAWEI -> "EMUI"
        Vendor.VIVO -> "OriginOS"
        Vendor.XIAOMI -> "MIUI"
        Vendor.OPPO -> "ColorOS"
        Vendor.ONEPLUS -> "OxygenOS"
        Vendor.SAMSUNG -> "One UI"
        Vendor.OTHER -> "Your phone"
    }
}
