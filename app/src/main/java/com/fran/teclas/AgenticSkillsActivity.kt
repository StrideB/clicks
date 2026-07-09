package com.fran.teclas

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.fran.teclas.db.SkillDatabase
import com.fran.teclas.db.SkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Skills screen: view every agentic skill, toggle it, see how often you use it, and add your own
 * (name + trigger words + a URL with {q}). Compose UI in the Neu design language. New skills persist
 * to the Room database and the router reloads its cache immediately.
 */
class AgenticSkillsActivity : ComponentActivity() {

    private val accent = Color(0xFF8B5CF6)
    private val onAccent = Color(0xFFF5F2FF)
    private lateinit var t: NeuTokens

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun dao() = SkillDatabase.get(this).skillDao()

    private companion object {
        private const val PREFS_NAME = "teclas"
        private const val THEME_MODE_PREF = "theme_mode"
        private const val THEME_MODE_DARK = "dark"
        private const val THEME_MODE_LIGHT = "light"
    }

    private fun tokens(): NeuTokens = when (prefs().getString(THEME_MODE_PREF, "system")) {
        THEME_MODE_DARK -> Neu.Dark
        THEME_MODE_LIGHT -> Neu.Light
        else -> {
            val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            if (night == android.content.res.Configuration.UI_MODE_NIGHT_YES) Neu.Dark else Neu.Light
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        t = tokens()
        window.setBackgroundDrawable(ColorDrawable(t.base))
        window.statusBarColor = t.base
        if (t.mode == NeuMode.LIGHT) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        setContent { SkillsScreen() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private suspend fun loadSkills(): List<SkillEntity> = withContext(Dispatchers.IO) {
        AgenticRouter.ensureSeeded(this@AgenticSkillsActivity)
        runCatching { dao().getAll() }.getOrDefault(emptyList())
    }

    @Composable
    private fun SkillsScreen() {
        val scope = rememberCoroutineScope()
        var skills by remember { mutableStateOf<List<SkillEntity>>(emptyList()) }
        LaunchedEffect(Unit) { skills = loadSkills() }
        fun mutate(msg: String?, op: () -> Unit) = scope.launch {
            withContext(Dispatchers.IO) { runCatching(op); AgenticRouter.reload(this@AgenticSkillsActivity) }
            skills = loadSkills()
            msg?.let { toast(it) }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 40.dp)
        ) {
            Text("Skills", fontSize = 23.sp, color = t.inkCompose, fontWeight = FontWeight.Medium)
            Text("Type a command, hold the go/enter key to run it.", fontSize = 13.5.sp,
                color = t.inkDimCompose, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(16.dp))
            for (s in skills) SkillRow(
                s,
                onToggle = { checked ->
                    skills = skills.map { if (it.id == s.id) it.copy(enabled = checked) else it }
                    scope.launch(Dispatchers.IO) {
                        runCatching { dao().setEnabled(s.id, checked) }
                        AgenticRouter.reload(this@AgenticSkillsActivity)
                    }
                },
                onDelete = { mutate(null) { dao().deleteCustom(s.id) } },
                onSaveTriggers = { raw ->
                    val clean = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
                    if (clean.isBlank()) toast("Add at least one trigger word")
                    else mutate("Triggers updated") { dao().update(s.copy(triggers = clean)) }
                }
            )
            ReferenceSection()
            AddSkillCard { name, emoji, triggers, url ->
                when {
                    name.isBlank() || triggers.isBlank() || url.isBlank() -> {
                        toast("Name, triggers and URL are required"); false
                    }
                    !url.contains("{q}") -> { toast("URL needs a {q} placeholder"); false }
                    else -> {
                        val skill = SkillEntity(
                            name = name, emoji = emoji.ifBlank { "✨" }, actionType = "URI",
                            uriTemplate = url, triggers = triggers,
                            labelTemplate = "${emoji.ifBlank { "✨" }}  $name {q}",
                            enabled = true, builtin = false, sortOrder = 100
                        )
                        mutate("Skill added") { dao().insert(skill) }; true
                    }
                }
            }
        }
    }

    @Composable
    private fun SkillRow(s: SkillEntity, onToggle: (Boolean) -> Unit, onDelete: () -> Unit, onSaveTriggers: (String) -> Unit) {
        var editing by remember(s.id) { mutableStateOf(false) }
        var trig by remember(s.id, s.triggers) { mutableStateOf(s.triggers) }
        val example = s.triggers.split(",").firstOrNull()?.trim()?.removeSuffix("*")?.trim().orEmpty()
        Column(
            Modifier.fillMaxWidth().padding(bottom = 10.dp).neu(t, 16.dp, NeuLevel.RAISED_SM)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.emoji, fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.width(34.dp))
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    Text(s.name, fontSize = 15.5.sp, color = t.inkCompose)
                    val sub = buildString {
                        append("e.g. “$example…”")
                        if (s.usageCount > 0) append("   ·  used ${s.usageCount}×")
                    }
                    Text(sub, fontSize = 12.sp, color = t.inkDimCompose, modifier = Modifier.padding(top = 1.dp))
                }
                // Edit-triggers toggle (available for every skill).
                Text("✎", fontSize = 16.sp, color = accent,
                    modifier = Modifier.clickable { editing = !editing }
                        .padding(horizontal = 10.dp, vertical = 4.dp))
                if (!s.builtin) {
                    Text("✕", fontSize = 15.sp, color = t.inkDimCompose,
                        modifier = Modifier.clickable(onClick = onDelete)
                            .padding(start = 6.dp, end = 10.dp, top = 4.dp, bottom = 4.dp))
                }
                Switch(
                    checked = s.enabled, onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = accent,
                        checkedTrackColor = accent.copy(alpha = 0.4f),
                        uncheckedThumbColor = t.inkDimCompose,
                        uncheckedTrackColor = t.inkDimCompose.copy(alpha = 0.25f)
                    )
                )
            }
            // Collapsible editor: change the words that trigger this skill (e.g. rename "share
            // location" to "loc"). Works for built-in skills too — the seeder never overwrites names.
            if (editing) {
                Column(Modifier.padding(top = 4.dp)) {
                    Text("The words you type to run this — a trailing space means \"prefix + argument\" " +
                        "(e.g. \"loc \"), \"* near me\" means suffix.", fontSize = 11.5.sp, color = t.inkDimCompose)
                    NeuInput(trig, { trig = it }, "Trigger words, comma-separated")
                    AccentButton("Save triggers", 13.5.sp, 9.dp, 11.dp, topMargin = 8.dp) { onSaveTriggers(trig) }
                }
            }
        }
    }

    private data class RefRow(val emoji: String, val name: String, val trigger: String, val ok: Boolean, val need: String)

    // A read-only catalog of the built-in typed-trigger skills, with a live "Ready / needs …" status
    // so the whole catalog is discoverable instead of hidden behind keywords.
    @Composable
    private fun ReferenceSection() {
        val refs = remember {
            val p = getSharedPreferences("teclas", Context.MODE_PRIVATE)
            fun has(pref: String) = !p.getString(pref, null).isNullOrBlank()
            val gemini = has(GeminiClient.API_KEY_PREF)
            val google = runCatching { GmailAuth(this@AgenticSkillsActivity).isConnected }.getOrDefault(false)
            listOf(
                RefRow("✨", "Humanize / tone", "<text> //human · //mid · //genz", gemini, "Add Gemini key"),
                RefRow("↩", "Reply modes", "<text> //reply", gemini, "Add Gemini key"),
                RefRow("😃", "Text → Emoji", "<text> //emoji", gemini, "Add Gemini key"),
                RefRow("🧠", "Emotion Detection", "copy → mood", gemini, "Add Gemini key"),
                RefRow("🌐", "Translation HUD", "copy → translate", gemini, "Add Gemini key"),
                RefRow("𝕏", "Super X Reply", "copy → xreply snarky", gemini, "Add Gemini key"),
                RefRow("📈", "Stock Sniffer", "\$AAPL", has(StockApi.KEY_PREF), "Add Finnhub key"),
                RefRow("🏆", "World Cup Odds", "world cup", has(OddsApi.KEY_PREF), "Add odds key"),
                RefRow("📹", "Google Meet", "meet", google, "Connect Google"),
                RefRow("📄", "Notion Summon", "notion <keyword>", has(NotionApi.KEY_PREF), "Add Notion token")
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("More skills", fontSize = 17.sp, color = t.inkCompose, fontWeight = FontWeight.Medium)
        Text("Built in — type the trigger, then hold go (or space for //commands).", fontSize = 12.5.sp,
            color = t.inkDimCompose, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        for (r in refs) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    .neu(t, 16.dp, NeuLevel.RAISED_SM).padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(r.emoji, fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.width(34.dp))
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    Text(r.name, fontSize = 15.5.sp, color = t.inkCompose)
                    Text("type “${r.trigger}”", fontSize = 12.sp, color = t.inkDimCompose,
                        modifier = Modifier.padding(top = 1.dp))
                }
                Text(
                    if (r.ok) "Ready" else r.need, fontSize = 11.sp,
                    color = if (r.ok) Color(0xFF12A968) else Color(0xFFCF8A3A),
                    modifier = Modifier
                        .background(if (r.ok) Color(0x2212A968) else Color(0x22CF8A3A), RoundedCornerShape(9.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }

    @Composable
    private fun AddSkillCard(onSave: (String, String, String, String) -> Boolean) {
        var name by remember { mutableStateOf("") }
        var emoji by remember { mutableStateOf("") }
        var trig by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        Column(
            Modifier.fillMaxWidth().padding(top = 6.dp).neu(t, 18.dp, NeuLevel.RAISED_SM).padding(14.dp)
        ) {
            Text("ADD A SKILL", fontSize = 10.5.sp, letterSpacing = 0.14.em, color = accent,
                fontFamily = FontFamily.Monospace)
            NeuInput(name, { name = it }, "Name (e.g. Reddit)")
            NeuInput(emoji, { emoji = it }, "Emoji (e.g. 👽)")
            NeuInput(trig, { trig = it }, "Trigger words, comma-separated (e.g. reddit ,r/ )")
            NeuInput(url, { url = it }, "URL with {q} (e.g. https://reddit.com/search?q={q})")
            AccentButton("Save skill", 14.5.sp, 11.dp, 12.dp, topMargin = 12.dp) {
                if (onSave(name.trim(), emoji.trim(), trig.trim(), url.trim())) {
                    name = ""; emoji = ""; trig = ""; url = ""
                }
            }
        }
    }

    // --- builders ---
    @Composable
    private fun NeuInput(value: String, onChange: (String) -> Unit, hint: String) {
        Box(
            Modifier.fillMaxWidth().padding(top = 8.dp).neu(t, 11.dp, NeuLevel.PRESSED_SM)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (value.isEmpty()) Text(hint, fontSize = 14.sp, color = t.inkFaintCompose)
            BasicTextField(
                value, onChange, singleLine = true, cursorBrush = SolidColor(accent),
                textStyle = TextStyle(color = t.inkCompose, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    @Composable
    private fun AccentButton(label: String, size: androidx.compose.ui.unit.TextUnit,
                             padV: androidx.compose.ui.unit.Dp, radius: androidx.compose.ui.unit.Dp,
                             topMargin: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
        Text(
            label, fontSize = size, color = onAccent, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = topMargin)
                .background(accent, RoundedCornerShape(radius))
                .clickable(onClick = onClick).padding(vertical = padV)
        )
    }
}
