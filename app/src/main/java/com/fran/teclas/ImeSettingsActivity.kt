package com.fran.teclas

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fran.teclas.keyboard.DictionaryLoader

/** Toggle live autocorrect in the IME. Shared "teclas" pref, default on. */
const val IME_AUTOCORRECT_PREF = "autocorrect_enabled"

/** Smart touch: let the model reassign near-boundary taps. Off = taps are always taken literally. */
const val IME_SMART_TOUCH_PREF = "smart_touch_enabled"

/** Proofread mode (opt-in): never auto-change words while typing; fix misspellings only on send, and
 *  learn the user's own words so slang/abbreviations are protected. Default off. */
const val IME_PROOFREAD_PREF = "proofread_mode"

/**
 * The IME's own settings screen — launched from Android's system keyboard settings (wired via
 * android:settingsActivity in teclas_input_method.xml). Compose UI in the Neu design language to
 * match the rest of Teclas; writes to the shared "teclas" prefs the keyboards read.
 */
class ImeSettingsActivity : ComponentActivity() {

    private val accent = Color(0xFF8B5CF6)
    private val proAccent = Color(0xFFCBB4FF)
    private val onAccent = Color(0xFFF5F2FF)

    // Pref names are declared per-file across this codebase (they're private consts elsewhere), so
    // redeclare the handful this screen writes. Values match the launcher's exactly.
    private companion object {
        const val PREFS_NAME = "teclas"
        const val THEME_MODE_PREF = "theme_mode"
        const val THEME_MODE_DARK = "dark"
        const val THEME_MODE_LIGHT = "light"
        const val THEME_MODE_SYSTEM = "system"
        const val HAPTICS_PREF = "haptics"
        const val HAPTIC_LEVEL_PREF = "haptic_level"   // 0–100, keyboard-only intensity
        const val GEMINI_ENABLED_PREF = "gemini_enabled"
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun tokens(): NeuTokens = when (prefs().getString(THEME_MODE_PREF, THEME_MODE_SYSTEM)) {
        THEME_MODE_DARK -> Neu.Dark
        THEME_MODE_LIGHT -> Neu.Light
        else -> {
            val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (night == Configuration.UI_MODE_NIGHT_YES) Neu.Dark else Neu.Light
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t = tokens()
        window.setBackgroundDrawable(ColorDrawable(t.base))
        window.statusBarColor = t.base
        if (t.mode == NeuMode.LIGHT) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        setContent { Screen(t) }
    }

    @Composable
    private fun Screen(t: NeuTokens) {
        Column(
            Modifier
                .fillMaxSize()
                .background(t.baseCompose)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 40.dp)
        ) {
            Text("Teclas Keyboard", color = t.inkCompose, fontSize = 23.sp, fontWeight = FontWeight.Medium)
            Text("Keyboard settings", Modifier.padding(top = 2.dp), color = t.inkDimCompose, fontSize = 13.5.sp)

            Section(t, "TYPING") {
                ToggleRow(t, "Auto-correction", "Fix typos as you type", IME_AUTOCORRECT_PREF, true)
                Div(t)
                ToggleRow(t, "Haptic feedback", "Vibrate on key press", HAPTICS_PREF, true)
                SliderRow(t, "Haptic strength", "Vibration intensity for the keyboard only", HAPTIC_LEVEL_PREF, 100)
                Div(t)
                ToggleRow(t, "Smart touch", "Nudge near-edge taps to the likely key. Turn off for literal taps.",
                    IME_SMART_TOUCH_PREF, true)
                Div(t)
                ToggleRow(t, "Proofread mode (beta)",
                    "Don't change words as you type. Learn your slang, and fix only clear misspellings when you send.",
                    IME_PROOFREAD_PREF, false)
            }

            Section(t, "LANGUAGES") {
                Text("Type in every selected language at once — no switching. Corrections and predictions " +
                    "come from all of them together.",
                    Modifier.padding(bottom = 10.dp), color = t.inkDimCompose, fontSize = 12.5.sp)
                LanguageChips(t)
            }

            Section(t, "AI WRITING") {
                ToggleRow(t, "AI suggestions & Polish", "Gemini-powered predictions and rewrite",
                    GEMINI_ENABLED_PREF, false)
                Div(t)
                // Account mode: sign in with Google and AI just works — no API key to create or paste.
                AccountRow(t)
                Text("Recommended — no API key needed. AI runs through the Teclas service on your account.",
                    Modifier.padding(top = 4.dp), color = t.inkDimCompose, fontSize = 12.sp)
                Div(t)
                Text("Advanced — bring your own key instead",
                    Modifier.padding(top = 6.dp), color = t.inkFaintCompose, fontSize = 11.5.sp)
                InputRow(t, "Gemini API key", GeminiClient.API_KEY_PREF, "Paste your API key", secret = true)
                InputRow(t, "Model", GeminiClient.MODEL_PREF, GeminiClient.DEFAULT_MODEL, secret = false)
                InputRow(t, "AI proxy URL", GeminiProxy.URL_PREF, "https://…workers.dev", secret = false)
                val pro = remember { ProManager.isUnlocked(this@ImeSettingsActivity) }
                Text(
                    if (pro) "Pro unlocked — ✨ Polish and //commands are available."
                    else "✨ Polish and //commands require Teclas Pro.",
                    Modifier.padding(top = 10.dp), color = if (pro) proAccent else t.inkDimCompose, fontSize = 12.sp)
            }

            Section(t, "AGENTIC") {
                Text("Type a command and hold the go/enter key to run it — music, maps, timers, and your own skills.",
                    Modifier.padding(bottom = 4.dp), color = t.inkDimCompose, fontSize = 12.5.sp)
                Text("Hold the space bar for Gemini to keep writing — drafts and replies right in the field.",
                    Modifier.padding(bottom = 10.dp), color = t.inkDimCompose, fontSize = 12.5.sp)
                ActionButton("Manage skills", filled = true) {
                    startActivity(Intent(this@ImeSettingsActivity, AgenticSkillsActivity::class.java))
                }
                ActionButton("Spaces — context prediction", filled = false, Modifier.padding(top = 8.dp)) {
                    startActivity(Intent(this@ImeSettingsActivity, SpacesSettingsActivity::class.java))
                }
            }

            // Skill connections — optional API keys (free tiers) that unlock more agentic skills.
            Section(t, "SKILL CONNECTIONS") {
                Text("Optional free API keys unlock more skills. Paste a key to turn it on.",
                    Modifier.padding(bottom = 8.dp), color = t.inkDimCompose, fontSize = 12.5.sp)
                InputRow(t, "Finnhub key — Stock Sniffer", StockApi.KEY_PREF, "finnhub.io (free)", secret = true)
                Div(t)
                InputRow(t, "Odds API key — World Cup Odds", OddsApi.KEY_PREF, "the-odds-api.com (free)", secret = true)
                Div(t)
                InputRow(t, "Notion token — Notion Summon", NotionApi.KEY_PREF, "notion.so/my-integrations", secret = true)
                Div(t)
                InputRow(t, "Google Search API key", GoogleSearchApi.KEY_PREF, "Custom Search JSON API (free 100/day)", secret = true)
                Div(t)
                InputRow(t, "Google Search engine ID (cx)", GoogleSearchApi.CX_PREF, "programmablesearchengine.google.com", secret = false)
                Text("Google Meet uses your Google connection — connect Google in the Teclas app, then type “meet” and hold go.",
                    Modifier.padding(top = 10.dp), color = t.inkDimCompose, fontSize = 12.sp)
            }

            Section(t, "TRY IT") {
                var sample by remember { mutableStateOf("") }
                BasicTextField(
                    value = sample,
                    onValueChange = { sample = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .neu(t, 12.dp, NeuLevel.PRESSED_SM)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    textStyle = TextStyle(color = t.inkCompose, fontSize = 15.sp),
                    cursorBrush = SolidColor(accent),
                    minLines = 2,
                    decorationBox = { inner ->
                        Box {
                            if (sample.isEmpty())
                                Text("Type here to test the keyboard…", color = t.inkFaintCompose, fontSize = 15.sp)
                            inner()
                        }
                    }
                )
            }
        }
    }

    // ---- building blocks -----------------------------------------------------------------------

    /** A monospace section header followed by a titled neu card holding [content]. */
    @Composable
    private fun Section(t: NeuTokens, title: String, content: @Composable ColumnScope.() -> Unit) {
        Text(title, Modifier.padding(top = 22.dp, bottom = 8.dp, start = 4.dp),
            color = accent, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.14.em)
        Column(
            Modifier
                .fillMaxWidth()
                .neu(t, 18.dp, NeuLevel.RAISED_SM)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            content = content
        )
    }

    @Composable
    private fun Div(t: NeuTokens) =
        HorizontalDivider(thickness = 1.dp, color = t.inkFaintCompose.copy(alpha = 0.13f))

    @Composable
    private fun ToggleRow(t: NeuTokens, title: String, sub: String, pref: String, default: Boolean) {
        var checked by remember { mutableStateOf(prefs().getBoolean(pref, default)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = t.inkCompose, fontSize = 15.5.sp)
                Text(sub, Modifier.padding(top = 1.dp), color = t.inkDimCompose, fontSize = 12.5.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = { checked = it; prefs().edit().putBoolean(pref, it).apply() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = accent,
                    uncheckedThumbColor = t.inkDimCompose,
                    checkedTrackColor = accent.copy(alpha = 0.4f),
                    uncheckedTrackColor = t.inkDimCompose.copy(alpha = 0.25f),
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
    }

    /** A labelled 0–100 percentage slider that writes to an Int pref and buzzes a live preview. */
    @Composable
    private fun SliderRow(t: NeuTokens, title: String, sub: String, pref: String, default: Int) {
        var value by remember { mutableFloatStateOf(prefs().getInt(pref, default).coerceIn(0, 100).toFloat()) }
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = t.inkCompose, fontSize = 15.5.sp)
                    Text(sub, Modifier.padding(top = 1.dp), color = t.inkDimCompose, fontSize = 12.5.sp)
                }
                Text("${value.toInt()}%", color = accent, fontSize = 13.sp)
            }
            Slider(
                value = value,
                onValueChange = { value = it; prefs().edit().putInt(pref, it.toInt()).apply() },
                modifier = Modifier.padding(top = 2.dp),
                valueRange = 0f..100f,
                onValueChangeFinished = {
                    // Fire a preview buzz at the chosen strength so the user feels the level.
                    if (prefs().getBoolean(HAPTICS_PREF, true)) {
                        (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.let { v ->
                            val amp = ((value / 100f) * 200).toInt().coerceIn(1, 255)
                            runCatching { v.vibrate(VibrationEffect.createOneShot(18L, amp)) }
                        }
                    }
                },
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = t.inkDimCompose.copy(alpha = 0.25f)
                )
            )
        }
    }

    /** Google sign-in row: connect an account (AI with no key) or show/sign out the current one. */
    @Composable
    private fun AccountRow(t: NeuTokens) {
        val auth = remember { AccountAuth(this@ImeSettingsActivity) }
        var account by remember { mutableStateOf(auth.isSignedIn to auth.email) }
        // The OAuth flow finishes in a Custom Tab + MainActivity, so re-check whenever we resume.
        DisposableEffect(Unit) {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) account = auth.isSignedIn to auth.email
            }
            lifecycle.addObserver(obs)
            onDispose { lifecycle.removeObserver(obs) }
        }
        val (signedIn, email) = account
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (signedIn) "Signed in with Google" else "Sign in with Google",
                    color = t.inkCompose, fontSize = 15.5.sp)
                Text(if (signedIn) (email ?: "Account connected") else "AI with no API key to create or paste",
                    Modifier.padding(top = 1.dp), color = t.inkDimCompose, fontSize = 12.5.sp)
            }
            Text(
                if (signedIn) "Sign out" else "Sign in",
                Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (signedIn) Color.Black.copy(alpha = 0.13f) else accent)
                    .clickable {
                        if (signedIn) {
                            auth.signOut()
                            prefs().edit().putBoolean(GeminiProxy.ACCOUNT_MODE_PREF, false).apply()
                            GeminiClient.proxy = null
                            account = false to null
                        } else {
                            auth.startSignIn(this@ImeSettingsActivity)
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                color = if (signedIn) t.inkDimCompose else onAccent, fontSize = 12.5.sp
            )
        }
    }

    @Composable
    private fun InputRow(t: NeuTokens, label: String, pref: String, hint: String, secret: Boolean) {
        var value by remember { mutableStateOf(prefs().getString(pref, "") ?: "") }
        Column(Modifier.padding(top = 10.dp, bottom = 2.dp)) {
            Text(label, color = t.inkDimCompose, fontSize = 12.5.sp)
            BasicTextField(
                value = value,
                onValueChange = { value = it; prefs().edit().putString(pref, it.trim()).apply() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp)
                    .neu(t, 11.dp, NeuLevel.PRESSED_SM)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = TextStyle(color = t.inkCompose, fontSize = 14.5.sp),
                cursorBrush = SolidColor(accent),
                singleLine = true,
                // Secrets stay visible (like the old VISIBLE_PASSWORD input type) but skip suggestions.
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text(hint, color = t.inkFaintCompose, fontSize = 14.5.sp)
                        inner()
                    }
                }
            )
        }
    }

    @Composable
    private fun LanguageChips(t: NeuTokens) {
        val labels = listOf("en" to "EN", "es" to "ES", "fr" to "FR", "de" to "DE", "pt" to "PT", "it" to "IT")
        var selected by remember {
            mutableStateOf(DictionaryLoader.enabledLanguages(this@ImeSettingsActivity).toSet())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            labels.forEach { (code, label) ->
                val on = code in selected
                Text(
                    label,
                    Modifier
                        .weight(1f)
                        .neu(t, 12.dp, if (on) NeuLevel.PRESSED_SM else NeuLevel.RAISED_SM)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            toggleLanguage(code)
                            selected = DictionaryLoader.enabledLanguages(this@ImeSettingsActivity).toSet()
                        }
                        .padding(vertical = 10.dp),
                    color = if (on) t.inkCompose else t.inkDimCompose,
                    fontSize = 13.sp, letterSpacing = 0.06.em, fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    private fun toggleLanguage(code: String) {
        val order = DictionaryLoader.available()
        val cur = DictionaryLoader.enabledLanguages(this).toMutableSet()
        if (code in cur) { if (cur.size > 1) cur.remove(code) } else cur.add(code)
        val ordered = order.filter { it in cur }
        prefs().edit().putString(DictionaryLoader.LANGUAGES_PREF, ordered.joinToString(",")).apply()
    }

    @Composable
    private fun ActionButton(label: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
        val shape = RoundedCornerShape(12.dp)
        Text(
            label,
            modifier
                .fillMaxWidth()
                .then(if (filled) Modifier.background(accent, shape)
                    else Modifier.border(1.dp, accent.copy(alpha = 0.53f), shape))
                .clip(shape)
                .clickable(onClick = onClick)
                .padding(vertical = 11.dp),
            color = if (filled) onAccent else accent,
            fontSize = 14.5.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center
        )
    }
}
