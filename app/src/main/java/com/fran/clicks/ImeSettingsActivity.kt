package com.fran.clicks

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
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
import android.widget.Switch
import android.widget.TextView
import com.fran.clicks.keyboard.DictionaryLoader

/** Toggle live autocorrect in the IME. Shared "clicks" pref, default on. */
const val IME_AUTOCORRECT_PREF = "autocorrect_enabled"

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
            c.addView(inputRow("Gemini API key", GeminiClient.API_KEY_PREF, "Paste your API key", secret = true))
            c.addView(inputRow("Model", GeminiClient.MODEL_PREF, GeminiClient.DEFAULT_MODEL, secret = false))
            val pro = ProManager.isUnlocked(this)
            c.addView(text(
                if (pro) "Pro unlocked — ✨ Polish and //commands are available."
                else "✨ Polish and //commands require Clicks Pro.",
                12f, if (pro) proAccent else t.inkDim).apply {
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
