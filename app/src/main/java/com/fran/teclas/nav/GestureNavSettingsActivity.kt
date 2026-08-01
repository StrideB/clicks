package com.fran.teclas.nav

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fran.teclas.InputInjectionService
import com.fran.teclas.NeuMode
import com.fran.teclas.NeuTokens
import com.fran.teclas.ShizukuPinner
import com.fran.teclas.resolveTeclasNeuTokens
import kotlin.math.roundToInt

/**
 * Gesture navigation settings.
 *
 * Presented as a ladder rather than a pile of switches, because the right answer is genuinely
 * device-dependent and the user cannot be expected to know which rung theirs lands on:
 *
 *  1. **Ask the system.** [SystemGestureBridge] tries to make the ROM hand its own gestures back.
 *     Best outcome by a distance when it works — real animations, real quick-switch, nothing
 *     intercepting touches. On Xiaomi this is the documented escape hatch from "third-party launcher
 *     means 3-button navigation"; on stock-shaped ROMs it is the navbar overlay.
 *  2. **Draw our own.** [GestureNavOverlay] — edge strips dispatched through the launcher's
 *     accessibility service. Works everywhere, at the cost of eating touches in a few millimetres
 *     along each edge.
 *  3. **Take the bar away.** Free inside the launcher's own window; system-wide only with Shizuku,
 *     and only until the next reboot.
 *
 * Every rung reports back what actually happened, including the failures — the alternative is a
 * toggle that flips and changes nothing, which is exactly the experience this screen exists to
 * replace.
 */
class GestureNavSettingsActivity : ComponentActivity() {

    private lateinit var t: NeuTokens
    private var tick by mutableIntStateOf(0)
    private var report by mutableStateOf<SystemGestureBridge.BridgeReport?>(null)

    private fun refresh() {
        tick++
    }

    /**
     * Run a privileged step off the main thread, then hand the result back on it.
     *
     * Everything on this screen that touches Shizuku is a shell round-trip with an 8-second ceiling
     * ([ShizukuShell]), and a settings write is a binder call. On the main thread a slow or wedged
     * Shizuku is an ANR, not a spinner, so nothing here is allowed to run inline.
     */
    private fun <T> offMain(work: () -> T, then: (T) -> Unit) {
        Thread({
            val result = runCatching(work)
            runOnUiThread {
                // onSuccess, not getOrNull()?.let — a step whose result is legitimately null (the
                // self-grant returns null when there was nothing to do) still has to report back.
                result
                    .onSuccess(then)
                    .onFailure { toast("Failed: ${it.message ?: it.javaClass.simpleName}", long = true) }
                refresh()
            }
        }, "gesture-nav-action").start()
    }

    private fun toast(message: String, long: Boolean = false) =
        Toast.makeText(this@GestureNavSettingsActivity, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        t = resolveTeclasNeuTokens(
            getSharedPreferences("teclas", Context.MODE_PRIVATE).getString("theme_mode", "system")
        )
        window.setBackgroundDrawable(ColorDrawable(t.base))
        window.statusBarColor = t.base
        if (t.mode == NeuMode.LIGHT) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        setContent { Screen() }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the accessibility settings or the Shizuku app should show the new truth,
        // not the state this screen was opened with.
        refresh()
    }

    // ------------------------------------------------------------------ screen

    @Composable
    private fun Screen() {
        val scroll = rememberScrollState()
        Column(
            Modifier
                .fillMaxSize()
                .background(t.baseCompose)
                .verticalScroll(scroll)
                .padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 48.dp)
        ) {
            Label("Gesture navigation", 23.sp, t.inkCompose, bold = true)
            Label(
                "Swipe instead of buttons — and, where the ROM allows it, no navigation bar at all. " +
                    "Work down the list: each step below only matters if the one above it did not " +
                    "already solve it on your device.",
                13.5.sp, t.inkDimCompose, topPad = 4.dp
            )

            StatusSection()
            SystemGesturesSection()
            OverlaySection()
            HideBarSection()
            ManualSection()
        }
    }

    // ------------------------------------------------------------------ status

    @Composable
    private fun StatusSection() {
        val mode = remember(tick) { SystemGestureBridge.navMode(this@GestureNavSettingsActivity) }
        val vendor = remember(tick) { SystemGestureBridge.vendor() }
        val granted = remember(tick) { SystemGestureBridge.hasWriteSecureSettings(this@GestureNavSettingsActivity) }
        val shizuku = remember(tick) { ShizukuPinner.isReady() }
        val a11y = remember(tick) { accessibilityServiceEnabled() }

        Section("THIS DEVICE") {
            StatusRow(
                "Navigation mode", when (mode) {
                    SystemGestureBridge.NavMode.THREE_BUTTON -> "3-button"
                    SystemGestureBridge.NavMode.TWO_BUTTON -> "2-button"
                    SystemGestureBridge.NavMode.GESTURAL -> "Gestures"
                    SystemGestureBridge.NavMode.UNKNOWN -> "Not reported"
                },
                good = mode == SystemGestureBridge.NavMode.GESTURAL
            )
            StatusRow(
                "ROM family",
                if (vendor == SystemGestureBridge.Vendor.XIAOMI) "Xiaomi / MIUI / HyperOS" else "Stock-like",
                good = true
            )
            StatusRow(
                "adb settings grant", if (granted) "Granted" else "Not granted", good = granted
            )
            StatusRow("Shizuku", if (shizuku) "Connected" else "Not available", good = shizuku)
            if (!granted && shizuku) {
                // `pm grant` at shell uid is the same call the adb one-liner makes, so with Shizuku
                // running there is nothing left for a computer to do.
                ActionButton("GRANT IT NOW — NO COMPUTER NEEDED") {
                    offMain({ SystemGestureBridge.ensureGrantViaShizuku(this@GestureNavSettingsActivity) }) {
                        toast(it ?: "Shizuku refused the grant", long = true)
                    }
                }
            }
            StatusRow(
                "Launcher accessibility service",
                if (a11y) "On" else "Off — gestures cannot fire",
                good = a11y
            )
            if (!a11y) {
                ActionButton("OPEN ACCESSIBILITY SETTINGS") {
                    runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        .onFailure { toast("Could not open accessibility settings") }
                }
            }
        }
    }

    // ------------------------------------------------------------------ step 1

    @Composable
    private fun SystemGesturesSection() {
        val requested = remember(tick) { GestureNavPrefs.systemGesturesRequested(this@GestureNavSettingsActivity) }
        val vendor = remember(tick) { SystemGestureBridge.vendor() }

        Section("1 · ASK THE SYSTEM FOR ITS OWN GESTURES") {
            Label(
                if (vendor == SystemGestureBridge.Vendor.XIAOMI) {
                    "MIUI and HyperOS drop back to 3-button navigation whenever the default home is " +
                        "not their own launcher, because their gesture animations live inside it. " +
                        "The ROM keeps a flag to override that, and this is it."
                } else {
                    "On stock-shaped ROMs the navigation mode is chosen by a system overlay, so this " +
                        "switches that overlay to gestural. It needs Shizuku; the settings write on " +
                        "its own is only enough for OEM ROMs that read the value directly."
                },
                12.5.sp, t.inkDimCompose, topPad = 2.dp, bottomPad = 10.dp
            )
            ToggleRow("Keep system gestures forced on", requested) { next ->
                GestureNavPrefs.setBoolean(this@GestureNavSettingsActivity, GestureNavPrefs.KEY_SYSTEM_GESTURES, next)
                offMain({
                    if (next) SystemGestureBridge.applyGestures(this@GestureNavSettingsActivity)
                    else SystemGestureBridge.revert(this@GestureNavSettingsActivity)
                }) { report = it }
            }
            ToggleRow(
                "Also hide the ROM's gesture handle",
                remember(tick) { GestureNavPrefs.hidePillRequested(this@GestureNavSettingsActivity) }
            ) { next ->
                GestureNavPrefs.setBoolean(this@GestureNavSettingsActivity, GestureNavPrefs.KEY_HIDE_PILL, next)
                if (GestureNavPrefs.systemGesturesRequested(this@GestureNavSettingsActivity)) {
                    offMain({ SystemGestureBridge.applyGestures(this@GestureNavSettingsActivity) }) { report = it }
                } else {
                    refresh()
                }
            }
            ActionButton("APPLY NOW") {
                offMain({ SystemGestureBridge.applyGestures(this@GestureNavSettingsActivity) }) { report = it }
            }
            if (ShizukuPinner.isReady()) {
                ActionButton("RESTART SYSTEM UI") {
                    offMain({ SystemGestureBridge.restartSystemUi() }) {
                        toast(if (it.ok) "SystemUI restarting" else "Failed: ${it.message()}", long = !it.ok)
                    }
                }
            }
            report?.let { ReportBlock(it) }
        }
    }

    @Composable
    private fun ReportBlock(bridgeReport: SystemGestureBridge.BridgeReport) {
        Spacer(Modifier.height(10.dp))
        if (bridgeReport.applied.isEmpty() && bridgeReport.skipped.isEmpty()) {
            Label("Nothing to do — this device already reports gesture navigation.", 12.5.sp, t.inkDimCompose)
            return
        }
        bridgeReport.applied.forEach { Label("· $it", 12.5.sp, Color(GOOD), topPad = 2.dp) }
        bridgeReport.skipped.forEach { Label("· $it", 12.5.sp, Color(WARN), topPad = 2.dp) }
        if (bridgeReport.restartSystemUiSuggested) {
            Label(
                "Some ROMs only re-read the navigation mode when SystemUI restarts. If nothing " +
                    "changed, reboot — or use RESTART SYSTEM UI above when Shizuku is connected.",
                12.5.sp, t.inkDimCompose, topPad = 6.dp
            )
        }
        if (bridgeReport.needsGrant) {
            Label(
                "The settings writes need a one-time grant. Copy the adb command at the bottom of " +
                    "this screen, run it once from a computer, then come back and tap APPLY NOW.",
                12.5.sp, t.inkDimCompose, topPad = 6.dp
            )
        }
        if (bridgeReport.needsShizuku) {
            Label(
                "The overlay switch needs adb-level shell access. Either start Shizuku and grant " +
                    "this launcher, or run the overlay command from the list below yourself.",
                12.5.sp, t.inkDimCompose, topPad = 6.dp
            )
        }
    }

    // ------------------------------------------------------------------ step 2

    @Composable
    private fun OverlaySection() {
        val enabled = remember(tick) { GestureNavPrefs.overlayEnabled(this@GestureNavSettingsActivity) }

        Section("2 · THE LAUNCHER'S OWN GESTURE BAR") {
            Label(
                "Three invisible strips — left and right edges for back, the bottom for home, " +
                    "recents and the notification shade. Works on any ROM, including one that will " +
                    "not give its gestures back. The strips do swallow touches in the few " +
                    "millimetres they cover, which is why each one can be turned off and resized.",
                12.5.sp, t.inkDimCompose, topPad = 2.dp, bottomPad = 10.dp
            )
            ToggleRow("Gesture bar", enabled) { next ->
                GestureNavPrefs.setBoolean(this@GestureNavSettingsActivity, GestureNavPrefs.KEY_OVERLAY, next)
                if (next && !accessibilityServiceEnabled()) {
                    toast("Turn on the launcher's accessibility service or nothing will fire", long = true)
                }
                refresh()
            }
            if (enabled) {
                ToggleRow("Left edge → back", remember(tick) { GestureNavPrefs.leftEdgeEnabled(this@GestureNavSettingsActivity) }) {
                    GestureNavPrefs.setBoolean(this@GestureNavSettingsActivity, GestureNavPrefs.KEY_LEFT_EDGE, it); refresh()
                }
                ToggleRow("Right edge → back", remember(tick) { GestureNavPrefs.rightEdgeEnabled(this@GestureNavSettingsActivity) }) {
                    GestureNavPrefs.setBoolean(this@GestureNavSettingsActivity, GestureNavPrefs.KEY_RIGHT_EDGE, it); refresh()
                }
                ToggleRow("Bottom → home / recents", remember(tick) { GestureNavPrefs.bottomBarEnabled(this@GestureNavSettingsActivity) }) {
                    GestureNavPrefs.setBoolean(this@GestureNavSettingsActivity, GestureNavPrefs.KEY_BOTTOM_BAR, it); refresh()
                }
                ToggleRow("Draw a handle at the bottom", remember(tick) { GestureNavPrefs.showHandle(this@GestureNavSettingsActivity) }) {
                    GestureNavPrefs.setBoolean(this@GestureNavSettingsActivity, GestureNavPrefs.KEY_SHOW_HANDLE, it); refresh()
                }
                ToggleRow("Haptics", remember(tick) { GestureNavPrefs.hapticsEnabled(this@GestureNavSettingsActivity) }) {
                    GestureNavPrefs.setBoolean(this@GestureNavSettingsActivity, GestureNavPrefs.KEY_HAPTICS, it); refresh()
                }
                ToggleRow("Pass taps through the bottom strip", remember(tick) { GestureNavPrefs.tapPassthrough(this@GestureNavSettingsActivity) }) {
                    GestureNavPrefs.setBoolean(this@GestureNavSettingsActivity, GestureNavPrefs.KEY_TAP_PASSTHROUGH, it); refresh()
                }
                Label(
                    "With the navigation bar gone, apps put their own bottom row against the screen " +
                        "edge — right under the strip. Passing taps through keeps that row alive.",
                    12.sp, t.inkFaintCompose, topPad = 2.dp, bottomPad = 8.dp
                )
                SliderRow(
                    "Side edge width",
                    remember(tick) { GestureNavPrefs.edgeWidthDp(this@GestureNavSettingsActivity) },
                    GestureNavPrefs.MIN_EDGE_WIDTH_DP, GestureNavPrefs.MAX_EDGE_WIDTH_DP,
                ) {
                    GestureNavPrefs.setInt(this@GestureNavSettingsActivity, GestureNavPrefs.KEY_EDGE_WIDTH_DP, it); refresh()
                }
                SliderRow(
                    "Bottom strip height",
                    remember(tick) { GestureNavPrefs.bottomHeightDp(this@GestureNavSettingsActivity) },
                    GestureNavPrefs.MIN_BOTTOM_HEIGHT_DP, GestureNavPrefs.MAX_BOTTOM_HEIGHT_DP,
                ) {
                    GestureNavPrefs.setInt(this@GestureNavSettingsActivity, GestureNavPrefs.KEY_BOTTOM_HEIGHT_DP, it); refresh()
                }
                Label(
                    "Swipe up — home.   Swipe up and hold — recents.   Swipe down — notifications.   " +
                        "Long swipe sideways — app switcher.",
                    12.sp, t.inkDimCompose, topPad = 8.dp
                )
            }
        }
    }

    // ------------------------------------------------------------------ step 3

    @Composable
    private fun HideBarSection() {
        val overlayOn = remember(tick) { GestureNavPrefs.overlayEnabled(this@GestureNavSettingsActivity) }
        val shizuku = remember(tick) { ShizukuPinner.isReady() }

        Section("3 · TAKE THE NAVIGATION BAR AWAY") {
            ToggleRow(
                "Hide it on the home screen",
                remember(tick) { GestureNavPrefs.hideNavBarOnHome(this@GestureNavSettingsActivity) },
                enabled = overlayOn,
            ) {
                GestureNavPrefs.setBoolean(this@GestureNavSettingsActivity, GestureNavPrefs.KEY_HIDE_NAV_ON_HOME, it); refresh()
            }
            Label(
                if (overlayOn) {
                    "Costs nothing and needs no permission — the launcher simply hides the bar in " +
                        "its own window. Other apps keep theirs."
                } else {
                    "Turn the gesture bar on first. Hiding the buttons without a replacement would " +
                        "leave the home screen with no way back."
                },
                12.5.sp, t.inkDimCompose, topPad = 2.dp, bottomPad = 10.dp
            )

            HorizontalDivider(color = t.inkFaintCompose.copy(alpha = 0.25f))
            Label("System-wide, every app", 13.sp, t.inkCompose, bold = true, topPad = 12.dp)
            Label(
                "Android 12 removed every other way to do this, so what is left is the switch the " +
                    "setup wizard uses. Read before tapping: it hides the status bar as well as the " +
                    "navigation bar, it locks the notification shade, and it clears itself at the " +
                    "next reboot. Only worth it with step 1 or step 2 already working.",
                12.5.sp, t.inkDimCompose, topPad = 4.dp, bottomPad = 8.dp
            )
            if (shizuku) {
                ActionButton("HIDE THE BARS NOW") {
                    if (!overlayOn && !SystemGestureBridge.alreadyGestural(this@GestureNavSettingsActivity)) {
                        toast("Set up gestures first — otherwise there is no way to navigate", long = true)
                    } else {
                        offMain({ SystemGestureBridge.setSystemBarsHidden(true) }) {
                            toast(if (it.ok) "Bars hidden until reboot" else "Failed: ${it.message()}", long = !it.ok)
                        }
                    }
                }
                ActionButton("BRING THEM BACK") {
                    offMain({ SystemGestureBridge.setSystemBarsHidden(false) }) {
                        toast(if (it.ok) "Bars restored" else "Failed: ${it.message()}", long = !it.ok)
                    }
                }
            } else {
                Label("Needs Shizuku, or the adb command below.", 12.5.sp, Color(WARN))
            }
        }
    }

    // ------------------------------------------------------------------ manual

    @Composable
    private fun ManualSection() {
        val commands = remember(tick) { SystemGestureBridge.manualCommands(this@GestureNavSettingsActivity) }
        Section("DO IT BY HAND") {
            Label(
                "One computer, one cable, once. Everything above is the same commands, run for you.",
                12.5.sp, t.inkDimCompose, bottomPad = 8.dp
            )
            commands.forEach { command ->
                Text(
                    command,
                    color = t.inkCompose,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(t.loCompose.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .clickable { copy(command) }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
            ActionButton("COPY ALL") { copy(commands.joinToString("\n")) }
        }
    }

    private fun copy(text: String) {
        runCatching {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("teclas_gesture_nav", text))
        }.onSuccess { toast("Copied") }.onFailure { toast("Could not copy") }
    }

    /**
     * Whether the launcher's accessibility service is switched on. Read from the setting rather than
     * from [NavActions] because this screen has to be able to say "off" *before* anything has tried
     * to use it — an unconnected service and a service that has simply not been reached yet look
     * identical from the object's point of view.
     */
    private fun accessibilityServiceEnabled(): Boolean {
        val enabled = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull().orEmpty()
        val target = "$packageName/${InputInjectionService::class.java.name}"
        val shortTarget = "$packageName/.${InputInjectionService::class.java.simpleName}"
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            if (entry.equals(target, ignoreCase = true) || entry.equals(shortTarget, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------ small composables

    @Composable
    private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
        Spacer(Modifier.height(22.dp))
        Text(
            title,
            color = t.inkFaintCompose,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(t.hiCompose.copy(alpha = if (t.mode == NeuMode.LIGHT) 0.7f else 0.25f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            content = content,
        )
    }

    @Composable
    private fun Label(
        text: String,
        size: TextUnit,
        color: Color,
        bold: Boolean = false,
        topPad: Dp = 0.dp,
        bottomPad: Dp = 0.dp,
    ) {
        Text(
            text,
            color = color,
            fontSize = size,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(top = topPad, bottom = bottomPad),
        )
    }

    @Composable
    private fun StatusRow(label: String, value: String, good: Boolean) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = t.inkDimCompose, fontSize = 13.sp)
            Text(
                value,
                color = if (good) Color(GOOD) else Color(WARN),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }

    @Composable
    private fun ToggleRow(
        label: String,
        checked: Boolean,
        enabled: Boolean = true,
        onChange: (Boolean) -> Unit,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                color = if (enabled) t.inkCompose else t.inkFaintCompose,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(ACCENT),
                    uncheckedThumbColor = t.inkDimCompose,
                    uncheckedTrackColor = t.loCompose,
                ),
            )
        }
    }

    @Composable
    private fun SliderRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, color = t.inkCompose, fontSize = 14.sp)
                Text("$value dp", color = t.inkDimCompose, fontSize = 13.sp)
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange(it.roundToInt().coerceIn(min, max)) },
                valueRange = min.toFloat()..max.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Color(ACCENT),
                    activeTrackColor = Color(ACCENT),
                    inactiveTrackColor = t.loCompose,
                ),
            )
        }
    }

    @Composable
    private fun ActionButton(label: String, onClick: () -> Unit) {
        Spacer(Modifier.height(10.dp))
        Text(
            label,
            color = Color(ACCENT),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(ACCENT).copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                .clickable { onClick() }
                .padding(vertical = 11.dp, horizontal = 12.dp),
        )
            }

    private companion object {
        const val ACCENT = 0xFF8B5CF6.toInt()
        const val GOOD = 0xFF28E06A.toInt()
        const val WARN = 0xFFE8B84B.toInt()
    }
}
