package com.fran.clicks

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.fran.clicks.db.SkillDatabase
import com.fran.clicks.db.SkillEntity

/**
 * The Skills screen: view every agentic skill, toggle it, see how often you use it, and add your own
 * (name + trigger words + a URL with {q}). Built programmatically in the Neu design language. New
 * skills persist to the Room database and the router reloads its cache immediately.
 */
class AgenticSkillsActivity : Activity() {

    private val accent = 0xFF8B5CF6.toInt()
    private lateinit var t: NeuTokens
    private lateinit var list: LinearLayout

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dao() = SkillDatabase.get(this).skillDao()

    private companion object {
        private const val PREFS_NAME = "clicks"
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
        }
        root.addView(text("Skills", 23f, t.ink, bold = true))
        root.addView(text("Type a command, hold the go/enter key to run it.", 13.5f, t.inkDim).also {
            (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(2)
        })

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        root.addView(list)
        root.addView(buildReferenceSection())
        root.addView(addSkillCard())

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        reloadList()
    }

    private fun reloadList() {
        Thread {
            AgenticRouter.ensureSeeded(this)
            val all = runCatching { dao().getAll() }.getOrDefault(emptyList())
            runOnUiThread { renderList(all) }
        }.start()
    }

    private fun renderList(skills: List<SkillEntity>) {
        list.removeAllViews()
        for (s in skills) list.addView(skillRow(s))
    }

    private fun skillRow(s: SkillEntity): View {
        val example = s.triggers.split(",").firstOrNull()?.trim()?.removeSuffix("*")?.trim().orEmpty()
        // Collapsible editor: change the words that trigger this skill (e.g. rename "share location"
        // to "loc"). Works for built-in skills too — the seeder never overwrites an existing name.
        val editor = triggerEditor(s)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Neu.drawable(t, dp(16).toFloat(), NeuLevel.RAISED_SM)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(TextView(context).apply {
                text = s.emoji; textSize = 20f; gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(s.name, 15.5f, t.ink))
                val sub = buildString {
                    append("e.g. “$example…”")
                    if (s.usageCount > 0) append("   ·  used ${s.usageCount}×")
                }
                addView(text(sub, 12f, t.inkDim).also {
                    (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(1)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })

            // Edit-triggers toggle (available for every skill).
            addView(TextView(context).apply {
                text = "✎"; textSize = 16f; setTextColor(accent)
                setPadding(dp(10), dp(4), dp(10), dp(4)); isClickable = true
                setOnClickListener {
                    editor.visibility = if (editor.visibility == View.GONE) View.VISIBLE else View.GONE
                }
            })

            if (!s.builtin) {
                addView(TextView(context).apply {
                    text = "✕"; textSize = 15f; setTextColor(t.inkDim)
                    setPadding(dp(6), dp(4), dp(10), dp(4)); isClickable = true
                    setOnClickListener { deleteSkill(s) }
                })
            }

            addView(Switch(context).apply {
                isChecked = s.enabled
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
                thumbTintList = ColorStateList(states, intArrayOf(accent, t.inkDim))
                trackTintList = ColorStateList(states, intArrayOf(
                    (accent and 0x00FFFFFF) or 0x66000000, (t.inkDim and 0x00FFFFFF) or 0x40000000))
                setOnCheckedChangeListener { _, checked -> setEnabled(s, checked) }
            })
        })
        card.addView(editor)
        return card
    }

    /** Hidden-by-default editor row: edit the comma-separated trigger words for [s] and save. */
    private fun triggerEditor(s: SkillEntity): View {
        val field = input("Trigger words, comma-separated").apply {
            setText(s.triggers)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            addView(text("The words you type to run this — a trailing space means \"prefix + argument\" " +
                "(e.g. \"loc \"), \"* near me\" means suffix.", 11.5f, t.inkDim))
            addView(field)
            addView(TextView(context).apply {
                text = "Save triggers"; gravity = Gravity.CENTER; textSize = 13.5f
                setTextColor(0xFFF5F2FF.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, dp(9), 0, dp(9)); isClickable = true
                background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(11).toFloat() }
                setOnClickListener { updateTriggers(s, field.text.toString()) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            })
        }
    }

    private fun updateTriggers(s: SkillEntity, raw: String) {
        val clean = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
        if (clean.isBlank()) {
            Toast.makeText(this, "Add at least one trigger word", Toast.LENGTH_SHORT).show(); return
        }
        Thread {
            runCatching { dao().update(s.copy(triggers = clean)) }
            AgenticRouter.reload(this)
            runOnUiThread { reloadList(); Toast.makeText(this, "Triggers updated", Toast.LENGTH_SHORT).show() }
        }.start()
    }

    private data class RefRow(val emoji: String, val name: String, val trigger: String, val ok: Boolean, val need: String)

    // A read-only catalog of the built-in typed-trigger skills, with a live "Ready / needs …" status
    // so the whole catalog is discoverable instead of hidden behind keywords.
    private fun buildReferenceSection(): View {
        val p = getSharedPreferences("clicks", Context.MODE_PRIVATE)
        fun has(pref: String) = !p.getString(pref, null).isNullOrBlank()
        val gemini = has(GeminiClient.API_KEY_PREF)
        val google = runCatching { GmailAuth(this).isConnected }.getOrDefault(false)
        val refs = listOf(
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
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
            addView(text("More skills", 17f, t.ink, bold = true))
            addView(text("Built in — type the trigger, then hold go (or space for //commands).", 12.5f, t.inkDim).also {
                (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(2); (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(12)
            })
            for (r in refs) addView(referenceRow(r))
        }
    }

    private fun referenceRow(r: RefRow): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = Neu.drawable(t, dp(16).toFloat(), NeuLevel.RAISED_SM)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        addView(TextView(context).apply {
            text = r.emoji; textSize = 20f; gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(r.name, 15.5f, t.ink))
            addView(text("type “${r.trigger}”", 12f, t.inkDim).also {
                (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(1)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        addView(TextView(context).apply {
            text = if (r.ok) "Ready" else r.need
            textSize = 11f
            setTextColor(if (r.ok) 0xFF12A968.toInt() else 0xFFCF8A3A.toInt())
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (r.ok) 0x2212A968 else 0x22CF8A3A); cornerRadius = dp(9).toFloat()
            }
        })
    }

    private fun addSkillCard(): View {
        val nameIn = input("Name (e.g. Reddit)")
        val emojiIn = input("Emoji (e.g. 👽)")
        val trigIn = input("Trigger words, comma-separated (e.g. reddit ,r/ )")
        val urlIn = input("URL with {q} (e.g. https://reddit.com/search?q={q})")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = Neu.drawable(t, dp(18).toFloat(), NeuLevel.RAISED_SM)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }

            addView(TextView(context).apply {
                text = "ADD A SKILL"; textSize = 10.5f; letterSpacing = 0.14f
                setTextColor(accent); typeface = Typeface.MONOSPACE
                (layoutParams ?: LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)).also { layoutParams = it }
            })
            addView(nameIn); addView(emojiIn); addView(trigIn); addView(urlIn)
            addView(TextView(context).apply {
                text = "Save skill"; gravity = Gravity.CENTER; textSize = 14.5f
                setTextColor(0xFFF5F2FF.toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, dp(11), 0, dp(11)); isClickable = true
                background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(12).toFloat() }
                setOnClickListener {
                    saveSkill(
                        nameIn.text.toString().trim(), emojiIn.text.toString().trim(),
                        trigIn.text.toString().trim(), urlIn.text.toString().trim()
                    )
                    nameIn.text = null; emojiIn.text = null; trigIn.text = null; urlIn.text = null
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
    }

    private fun saveSkill(name: String, emoji: String, triggers: String, url: String) {
        if (name.isBlank() || triggers.isBlank() || url.isBlank()) {
            Toast.makeText(this, "Name, triggers and URL are required", Toast.LENGTH_SHORT).show(); return
        }
        if (!url.contains("{q}")) {
            Toast.makeText(this, "URL needs a {q} placeholder", Toast.LENGTH_SHORT).show(); return
        }
        val skill = SkillEntity(
            name = name, emoji = emoji.ifBlank { "✨" }, actionType = "URI",
            uriTemplate = url, triggers = triggers, labelTemplate = "${emoji.ifBlank { "✨" }}  $name {q}",
            enabled = true, builtin = false, sortOrder = 100
        )
        Thread {
            runCatching { dao().insert(skill) }
            AgenticRouter.reload(this)
            runOnUiThread { reloadList(); Toast.makeText(this, "Skill added", Toast.LENGTH_SHORT).show() }
        }.start()
    }

    private fun setEnabled(s: SkillEntity, enabled: Boolean) {
        Thread { runCatching { dao().setEnabled(s.id, enabled) }; AgenticRouter.reload(this) }.start()
    }

    private fun deleteSkill(s: SkillEntity) {
        Thread {
            runCatching { dao().deleteCustom(s.id) }
            AgenticRouter.reload(this)
            runOnUiThread { reloadList() }
        }.start()
    }

    // --- builders ---
    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun input(hintText: String) = EditText(this).apply {
        hint = hintText
        setHintTextColor(t.inkFaint); setTextColor(t.ink); textSize = 14f
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = Neu.drawable(t, dp(11).toFloat(), NeuLevel.PRESSED_SM)
        inputType = InputType.TYPE_CLASS_TEXT
        setSingleLine()
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }
}
