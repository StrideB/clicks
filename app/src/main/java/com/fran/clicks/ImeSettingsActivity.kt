package com.fran.clicks

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.fran.clicks.keyboard.DictionaryLoader

/** Toggle live autocorrect in the IME. Shared "clicks" pref, default on. */
const val IME_AUTOCORRECT_PREF = "autocorrect_enabled"

/** Smart touch: let the model reassign near-boundary taps. Off = taps are always taken literally. */
const val IME_SMART_TOUCH_PREF = "smart_touch_enabled"

/** Proofread mode (opt-in): never auto-change words while typing; fix misspellings only on send, and
 *  learn the user's own words so slang/abbreviations are protected. Default off. */
const val IME_PROOFREAD_PREF = "proofread_mode"

/**
 * The IME's own settings screen — launched from Android's system keyboard settings (wired via
 * android:settingsActivity in clicks_input_method.xml). Built programmatically in the Neu design
 * language to match the rest of Clicks; writes to the shared "clicks" prefs the keyboards read.
 */
class ImeSettingsActivity : Activity() {

    private val accent = 0xFF8B5CF6.toInt()
    private val proAccent = 0xFFCBB4FF.toInt()
    private lateinit var t: NeuTokens

    // Pref names are declared per-file across this codebase (they're private consts elsewhere), so
    // redeclare the handful this screen writes. Values match the launcher's exactly.
    private companion object {
        private const val PREFS_NAME = "clicks"
        private const val THEME_MODE_PREF = "theme_mode"
        private const val THEME_MODE_DARK = "dark"
        private const val THEME_MODE_LIGHT = "light"
        private const val THEME_MODE_SYSTEM = "system"
        private const val HAPTICS_PREF = "haptics"
        private const val HAPTIC_LEVEL_PREF = "haptic_level"   // 0–100, keyboard-only intensity
        private const val GEMINI_ENABLED_PREF = "gemini_enabled"
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tokens(): NeuTokens = when (prefs().getString(THEME_MODE_PREF, THEME_MODE_SYSTEM)) {
        THEME_MODE_DARK -> Neu.Dark
        THEME_MODE_LIGHT -> Neu.Light
        else -> {
            val night = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
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
        contentRoot = root

        root.addView(text("Clicks Keyboard", 23f, t.ink, bold = true))
        root.addView(text("Keyboard settings", 13.5f, t.inkDim).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(2)
        })

        // Typing
        section("TYPING").also { c ->
            c.addView(toggleRow("Auto-correction", "Fix typos as you type",
                IME_AUTOCORRECT_PREF, true))
            c.addView(divider())
            c.addView(toggleRow("Haptic feedback", "Vibrate on key press",
                HAPTICS_PREF, true))
            c.addView(sliderRow("Haptic strength", "Vibration intensity for the keyboard only",
                HAPTIC_LEVEL_PREF, 100))
            c.addView(divider())
            c.addView(toggleRow("Smart touch", "Nudge near-edge taps to the likely key. Turn off for literal taps.",
                IME_SMART_TOUCH_PREF, true))
            c.addView(divider())
            c.addView(toggleRow("Proofread mode (beta)",
                "Don't change words as you type. Learn your slang, and fix only clear misspellings when you send.",
                IME_PROOFREAD_PREF, false))
        }

        // Languages
        section("LANGUAGES").also { c ->
            c.addView(text(
                "Type in every selected language at once — no switching. Corrections and predictions " +
                    "come from all of them together.", 12.5f, t.inkDim).apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(10)
            })
            c.addView(languageChips())
        }

        // AI writing
        section("AI WRITING").also { c ->
            c.addView(toggleRow("AI suggestions & Polish", "Gemini-powered predictions and rewrite",
                GEMINI_ENABLED_PREF, false))
            c.addView(divider())
            // Account mode: sign in with Google and AI just works — no API key to create or paste.
            c.addView(accountRow())
            c.addView(text(
                "Recommended — no API key needed. AI runs through the Clicks service on your account.",
                12f, t.inkDim).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(4) })
            c.addView(divider())
            c.addView(text("Advanced — bring your own key instead", 11.5f, t.inkFaint).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
            })
            c.addView(inputRow("Gemini API key", GeminiClient.API_KEY_PREF, "Paste your API key", secret = true))
            c.addView(inputRow("Model", GeminiClient.MODEL_PREF, GeminiClient.DEFAULT_MODEL, secret = false))
            c.addView(inputRow("AI proxy URL", GeminiProxy.URL_PREF, "https://…workers.dev", secret = false))
            val pro = ProManager.isUnlocked(this)
            c.addView(text(
                if (pro) "Pro unlocked — ✨ Polish and //commands are available."
                else "✨ Polish and //commands require Clicks Pro.",
                12f, if (pro) proAccent else t.inkDim).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
            })
        }

        // Agentic
        section("AGENTIC").also { c ->
            c.addView(text("Type a command and hold the go/enter key to run it \u2014 music, maps, timers, and your own skills.", 12.5f, t.inkDim).apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(4)
            })
            c.addView(text("Hold the space bar for Gemini to keep writing \u2014 drafts and replies right in the field.", 12.5f, t.inkDim).apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(10)
            })
            c.addView(TextView(this).apply {
                text = "Manage skills"
                gravity = Gravity.CENTER; textSize = 14.5f
                setTextColor(0xFFF5F2FF.toInt())
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding(0, dp(11), 0, dp(11)); isClickable = true
                background = android.graphics.drawable.GradientDrawable().apply { setColor(accent); cornerRadius = dp(12).toFloat() }
                setOnClickListener { startActivity(android.content.Intent(this@ImeSettingsActivity, AgenticSkillsActivity::class.java)) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // Skill connections — optional API keys (free tiers) that unlock more agentic skills.
        section("SKILL CONNECTIONS").also { c ->
            c.addView(text("Optional free API keys unlock more skills. Paste a key to turn it on.", 12.5f, t.inkDim).apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8)
            })
            c.addView(inputRow("Finnhub key — Stock Sniffer", StockApi.KEY_PREF, "finnhub.io (free)", secret = true))
            c.addView(divider())
            c.addView(inputRow("Odds API key — World Cup Odds", OddsApi.KEY_PREF, "the-odds-api.com (free)", secret = true))
            c.addView(divider())
            c.addView(inputRow("Notion token — Notion Summon", NotionApi.KEY_PREF, "notion.so/my-integrations", secret = true))
            c.addView(divider())
            c.addView(inputRow("Google Search API key", GoogleSearchApi.KEY_PREF, "Custom Search JSON API (free 100/day)", secret = true))
            c.addView(divider())
            c.addView(inputRow("Google Search engine ID (cx)", GoogleSearchApi.CX_PREF, "programmablesearchengine.google.com", secret = false))
            c.addView(text("Google Meet uses your Google connection — connect Google in the Clicks app, then type “meet” and hold go.", 12f, t.inkDim).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
            })
        }

        // Try it
        section("TRY IT").also { c ->
            c.addView(EditText(this).apply {
                hint = "Type here to test the keyboard…"
                setHintTextColor(t.inkFaint)
                setTextColor(t.ink)
                textSize = 15f
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = Neu.drawable(t, dp(12).toFloat(), NeuLevel.PRESSED_SM)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 2
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    // ---- builders ----------------------------------------------------------------------------

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** A titled neu card; returns its content column so callers can add rows. */
    private fun section(title: String): LinearLayout {
        val root = contentRoot!!
        root.addView(TextView(this).apply {
            text = title
            textSize = 10.5f
            letterSpacing = 0.14f
            setTextColor(accent)
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(22); bottomMargin = dp(8); leftMargin = dp(4) }
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Neu.drawable(t, dp(18).toFloat(), NeuLevel.RAISED_SM)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(card)
        return card
    }

    // The root column, tracked directly so section() can append to it.
    private var contentRoot: LinearLayout? = null

    private fun toggleRow(title: String, sub: String, pref: String, default: Boolean): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 15.5f, t.ink))
                addView(text(sub, 12.5f, t.inkDim).apply {
                    (layoutParams as LinearLayout.LayoutParams).topMargin = dp(1)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(context).apply {
                isChecked = prefs().getBoolean(pref, default)
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
                thumbTintList = ColorStateList(states, intArrayOf(accent, t.inkDim))
                trackTintList = ColorStateList(states, intArrayOf(
                    (accent and 0x00FFFFFF) or 0x66000000, (t.inkDim and 0x00FFFFFF) or 0x40000000))
                setOnCheckedChangeListener { _, checked -> prefs().edit().putBoolean(pref, checked).apply() }
            })
        }
    }

    /** A labelled 0–100 percentage slider that writes to an Int pref and buzzes a live preview. */
    private fun sliderRow(title: String, sub: String, pref: String, default: Int): View {
        val valueLabel = text("", 13f, accent)
        val start = prefs().getInt(pref, default).coerceIn(0, 100)
        valueLabel.text = "$start%"
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(title, 15.5f, t.ink))
                    addView(text(sub, 12.5f, t.inkDim).apply {
                        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(1)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(valueLabel)
            })
            addView(SeekBar(context).apply {
                max = 100
                progress = start
                progressTintList = ColorStateList.valueOf(accent)
                thumbTintList = ColorStateList.valueOf(accent)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                        valueLabel.text = "$value%"
                        if (fromUser) prefs().edit().putInt(pref, value).apply()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        // Fire a preview buzz at the chosen strength so the user feels the level.
                        if (prefs().getBoolean(HAPTICS_PREF, true)) {
                            (getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.let { v ->
                                val amp = ((progress / 100f) * 200).toInt().coerceIn(1, 255)
                                runCatching {
                                    v.vibrate(android.os.VibrationEffect.createOneShot(18L, amp))
                                }
                            }
                        }
                    }
                })
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            })
        }
    }

    /** Google sign-in row: connect an account (AI with no key) or show/sign out the current one. */
    private fun accountRow(): View {
        val auth = AccountAuth(this)
        val signedIn = auth.isSignedIn
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(if (signedIn) "Signed in with Google" else "Sign in with Google", 15.5f, t.ink))
                addView(text(
                    if (signedIn) (auth.email ?: "Account connected") else "AI with no API key to create or paste",
                    12.5f, t.inkDim
                ).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(1) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = if (signedIn) "Sign out" else "Sign in"
                textSize = 12.5f; gravity = Gravity.CENTER
                setTextColor(if (signedIn) t.inkDim else 0xFFF5F2FF.toInt())
                setPadding(dp(16), dp(9), dp(16), dp(9))
                background = GradientDrawable().apply {
                    setColor(if (signedIn) 0x22000000 else accent); cornerRadius = dp(11).toFloat()
                }
                isClickable = true
                setOnClickListener {
                    if (signedIn) {
                        auth.signOut()
                        prefs().edit().putBoolean(GeminiProxy.ACCOUNT_MODE_PREF, false).apply()
                        GeminiClient.proxy = null
                        recreate()
                    } else {
                        auth.startSignIn(this@ImeSettingsActivity)
                    }
                }
            })
        }
    }

    private fun inputRow(label: String, pref: String, hint: String, secret: Boolean): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(2))
            addView(text(label, 12.5f, t.inkDim))
            addView(EditText(context).apply {
                setText(prefs().getString(pref, "") ?: "")
                this.hint = hint
                setHintTextColor(t.inkFaint)
                setTextColor(t.ink)
                textSize = 14.5f
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = Neu.drawable(t, dp(11).toFloat(), NeuLevel.PRESSED_SM)
                inputType = if (secret)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else InputType.TYPE_CLASS_TEXT
                setSingleLine()
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        prefs().edit().putString(pref, s?.toString()?.trim().orEmpty()).apply()
                    }
                })
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(5) }
            })
        }
    }

    private fun languageChips(): View {
        val labels = listOf("en" to "EN", "es" to "ES", "fr" to "FR", "de" to "DE", "pt" to "PT", "it" to "IT")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun repaint() {
            row.removeAllViews()
            val selected = DictionaryLoader.enabledLanguages(this).toSet()
            labels.forEach { (code, label) ->
                val on = code in selected
                row.addView(TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 13f
                    letterSpacing = 0.06f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(if (on) t.ink else t.inkDim)
                    background = Neu.drawable(t, dp(12).toFloat(), if (on) NeuLevel.PRESSED_SM else NeuLevel.RAISED_SM)
                    setPadding(0, dp(10), 0, dp(10))
                    isClickable = true
                    setOnClickListener { toggleLanguage(code); repaint() }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (code != "en") marginStart = dp(7)
                })
            }
        }
        repaint()
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun toggleLanguage(code: String) {
        val order = DictionaryLoader.available()
        val cur = DictionaryLoader.enabledLanguages(this).toMutableSet()
        if (code in cur) { if (cur.size > 1) cur.remove(code) } else cur.add(code)
        val ordered = order.filter { it in cur }
        prefs().edit().putString(DictionaryLoader.LANGUAGES_PREF, ordered.joinToString(",")).apply()
    }

    private fun divider() = View(this).apply {
        setBackgroundColor((t.inkFaint and 0x00FFFFFF) or 0x22000000)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }
}
