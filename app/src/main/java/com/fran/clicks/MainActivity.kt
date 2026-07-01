package com.fran.clicks

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Xml
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import java.text.Collator
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity(), SpellCheckerSession.SpellCheckerSessionListener {
    private val collator = Collator.getInstance()
    private var query = ""
    private var apps: List<AppEntry> = emptyList()
    private var keyboardSize = 0
    private var hapticsEnabled = true
    private var keyboardSettingsOpen = false
    private var goKeyColor = Accent
    private var messages: List<HubMessage> = emptyList()
    private var openPane: PaneTarget? = null
    private var paneView: View? = null
    private var libraryOpen = false
    private var libraryGridMode = false
    private var libraryView: View? = null
    private var librarySwipeStartX = 0f
    private var librarySwipeStartY = 0f
    private var librarySwipeTriggered = false
    private var iconPacksCache: List<IconPack>? = null
    private val iconPackMatchCache = mutableMapOf<String, IconPackIcon?>()
    private var composeText = ""
    private val chatLinesById = mutableMapOf<String, MutableList<ChatLine>>()

    private var shiftState = ShiftState.ONCE
    private var lastSpaceMs = 0L
    private var lastShiftTapMs = 0L
    private var suggestions: List<String> = emptyList()
    private var spellChecker: SpellCheckerSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var suggestDebounce: Runnable? = null
    private var lastSuggestWord = ""
    private val keyViews = mutableMapOf<String, TextView>()
    private val keyBounds = mutableMapOf<String, Rect>()

    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var hubView: LinearLayout
    private lateinit var ribbonView: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private lateinit var searchHintView: TextView
    private lateinit var sizeValueView: TextView
    private lateinit var suggestionBarView: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keyboardSize = prefs().getInt(KEYBOARD_SIZE_PREF, 28)
        hapticsEnabled = prefs().getBoolean(HAPTICS_PREF, true)
        goKeyColor = prefs().getInt(GO_KEY_COLOR_PREF, Accent)
        apps = loadLaunchableApps()
        messages = loadHubMessages()
        initSpellChecker()
        render()
    }

    override fun onResume() {
        super.onResume()
        apps = loadLaunchableApps()
        messages = loadHubMessages()
        if (::ribbonView.isInitialized) {
            updateClock()
            renderHub()
            renderRibbon()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        spellChecker?.close()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onBackPressed() {
        if (libraryOpen) { closeLibrary(); return }
        if (openPane != null) { closePane(); return }
        super.onBackPressed()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        handleLibrarySwipe(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
        runOnUiThread {
            val current = currentWordInCompose()
            if (current.lowercase(Locale.US) != lastSuggestWord.lowercase(Locale.US)) return@runOnUiThread
            val words = results?.firstOrNull()?.let { info ->
                (0 until info.suggestionsCount).map { info.getSuggestionAt(it) }
            } ?: emptyList()
            suggestions = words.take(3)
            updateSuggestionBar()
        }
    }

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {}

    private fun initSpellChecker() {
        runCatching {
            val tsm = getSystemService(TEXT_SERVICES_MANAGER_SERVICE) as TextServicesManager
            spellChecker = tsm.newSpellCheckerSession(null, null, this, true)
        }
    }

    private fun render() {
        keyViews.clear()
        keyBounds.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Screen)
        }
        root.addView(statusBar(), LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
        contentFrame = FrameLayout(this).apply {
            addView(home(), FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(contentFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(keyboard(), LinearLayout.LayoutParams.MATCH_PARENT, keyboardHeight())
        setContentView(root)
        updateClock()
        renderHub()
        renderRibbon()
        openPane?.let { showPane(it, animate = false) }
        if (libraryOpen) showLibrary(animate = false)
        root.post { captureKeyBounds() }
    }

    private fun statusBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(7), dp(18), 0)
            addView(mono("CLICKS", 11f, InkDim).apply { letterSpacing = 0.05f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(mono("5G  .  82%", 11f, InkDim).apply { letterSpacing = 0.05f; setTextColor(Accent2) })
        }
    }

    private fun home(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(6), dp(6), dp(8))
            addView(homeLeft(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            ribbonView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            }
            addView(ribbonView, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun homeLeft(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(0, 0, dp(10), dp(8))
            addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            clockView = mono("", 44f, Ink).apply {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                includeFontPadding = false
            }
            addView(clockView)
            dateView = TextView(context).apply {
                textSize = 12f; letterSpacing = 0.08f; setTextColor(InkDim)
                includeFontPadding = false; setPadding(0, dp(4), 0, dp(20))
            }
            addView(dateView)
            addView(mono("MESSAGE HUB", 10f, Accent).apply {
                letterSpacing = 0.22f; setPadding(0, 0, 0, dp(4))
                isClickable = true; setOnClickListener { openNotificationAccessSettings() }
            })
            hubView = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(hubView, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun handleLibrarySwipe(event: MotionEvent) {
        if (!::contentFrame.isInitialized) return
        val loc = IntArray(2)
        contentFrame.getLocationOnScreen(loc)
        val inContent = event.rawY >= loc[1] && event.rawY <= loc[1] + contentFrame.height

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                librarySwipeStartX = event.rawX
                librarySwipeStartY = event.rawY
                librarySwipeTriggered = false
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (!inContent || librarySwipeTriggered) return
                val dx = event.rawX - librarySwipeStartX
                val dy = event.rawY - librarySwipeStartY
                // Deliberate horizontal threshold keeps vertical library scrolls and keyboard swipes separate.
                if (abs(dx) > dp(50) && abs(dx) > abs(dy) * 1.4f) {
                    when {
                        dx < 0 && !libraryOpen && openPane == null -> {
                            librarySwipeTriggered = true
                            openLibrary()
                        }
                        dx > 0 && libraryOpen -> {
                            librarySwipeTriggered = true
                            closeLibrary()
                        }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> librarySwipeTriggered = false
        }
    }

    private fun openLibrary() {
        if (libraryOpen || openPane != null) return
        libraryOpen = true
        keyboardSettingsOpen = false
        showLibrary(animate = true)
        renderRibbon()
    }

    private fun closeLibrary() {
        if (!libraryOpen) return
        libraryOpen = false
        renderRibbon()
        val closing = libraryView ?: return
        closing.animate().translationX(closing.width.toFloat()).setDuration(240)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                contentFrame.removeView(closing)
                if (libraryView === closing) libraryView = null
            }.start()
    }

    private fun showLibrary(animate: Boolean) {
        libraryView?.let { contentFrame.removeView(it) }
        val overlay = appLibrary()
        libraryView = overlay
        // Overlay contentFrame only; the keyboard is a sibling below and stays docked.
        contentFrame.addView(overlay, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        if (animate) overlay.post {
            overlay.translationX = overlay.width.toFloat()
            overlay.animate().translationX(0f).setDuration(360).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun appLibrary(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(10))
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF22242B.toInt(), 0xFF131419.toInt(), Screen)
        )
        addView(libraryHeader(), LinearLayout.LayoutParams.MATCH_PARENT, dp(42))
        addView(if (libraryGridMode) libraryGrid() else bentoGrid(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(libraryFooter(), LinearLayout.LayoutParams.MATCH_PARENT, dp(34))
    }

    private fun libraryHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = "App Library"; textSize = 17f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setTextColor(Ink); includeFontPadding = false
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = if (libraryGridMode) "▤ Categories" else "▦ Grid"
            gravity = Gravity.CENTER; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ink); includeFontPadding = false; background = roundedPanel(Panel2, dp(15), Line)
            isClickable = true
            setOnClickListener { haptic(this); libraryGridMode = !libraryGridMode; showLibrary(animate = false) }
        }, LinearLayout.LayoutParams(dp(118), dp(30)).apply { marginEnd = dp(8) })
        addView(TextView(context).apply {
            text = "X"; gravity = Gravity.CENTER; textSize = 14f; setTextColor(InkDim)
            background = roundedPanel(Panel2, dp(15), Line); isClickable = true
            setOnClickListener { haptic(this); closeLibrary() }
        }, LinearLayout.LayoutParams(dp(30), dp(30)))
    }

    private fun bentoGrid(): View = ScrollView(this).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            val cats = libraryCategories()
            addView(categoryCard(cats[0]), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(136)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(categoryCard(cats[1]), LinearLayout.LayoutParams(0, dp(226), 1f).apply { marginEnd = dp(5) })
                addView(categoryCard(cats[2]), LinearLayout.LayoutParams(0, dp(172), 1f).apply { marginStart = dp(5) })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
            addView(categoryCard(cats[3]), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(136)).apply { topMargin = dp(10) })
        })
    }

    private fun categoryCard(category: LibraryCategory): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        elevation = dp(4).toFloat()
        background = roundedPanel(Panel, dp(20), Line)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Accent) }
            }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(7) })
            addView(TextView(context).apply {
                text = category.name.uppercase(Locale.US); textSize = 11f
                typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.12f; setTextColor(InkDim); includeFontPadding = false
            })
        }, LinearLayout.LayoutParams.MATCH_PARENT, dp(20))
        addView(tileGrid(category.apps, if (category.span == LibrarySpan.WIDE) 4 else 2), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun libraryGrid(): View = ScrollView(this).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(8))
            addView(tileGrid(apps.map { it.toLibraryApp() }, 4))
        })
    }

    private fun tileGrid(items: List<LibraryApp>, columns: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        items.chunked(columns).forEach { rowItems ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                rowItems.forEach { app -> addView(appTile(app), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)) }
                repeat(columns - rowItems.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(82)).apply {
                bottomMargin = dp(if (libraryGridMode) 20 else 4)
            })
        }
    }

    private fun appTile(app: LibraryApp): View {
        val target = app.target
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true
            setPadding(dp(3), dp(4), dp(3), dp(2))
            addView(FrameLayout(context).apply {
                elevation = dp(3).toFloat(); background = roundedPanel(Panel2, dp(13), Line)
                addView(ImageView(context).apply {
                    setImageDrawable(iconFor(app)); scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true; setPadding(dp(4), dp(4), dp(4), dp(4))
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(TextView(context).apply {
                text = app.name; textSize = 10.5f; gravity = Gravity.CENTER; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(0xFFCDD0D6.toInt()); includeFontPadding = false; setPadding(0, dp(6), 0, 0)
            }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
                false
            }
            setOnClickListener {
                haptic(this); libraryOpen = false
                libraryView?.let { contentFrame.removeView(it) }; libraryView = null
                openHere(target)
            }
            setOnLongClickListener { haptic(this); showIconMenu(this, app); true }
        }
    }

    private fun libraryFooter(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(mono("← SWIPE RIGHT TO GO BACK", 8.5f, InkDim).apply { letterSpacing = 0.12f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = "X"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(InkDim); isClickable = true
            setOnClickListener { haptic(this); closeLibrary() }
        }, LinearLayout.LayoutParams(dp(34), dp(28)))
    }

    private fun renderHub() {
        if (!::hubView.isInitialized) return
        hubView.removeAllViews()
        if (!isNotificationAccessEnabled()) {
            hubView.addView(messageRow(Accent2, "Enable", "notification access for messages", null))
            seedPaneTargets().take(2).forEach { hubView.addView(messageRow(it.accent, it.name, it.preview, it)) }
            return
        }
        val visibleMessages = messages.take(4)
        if (visibleMessages.isEmpty()) {
            seedPaneTargets().forEach { hubView.addView(messageRow(it.accent, it.name, it.preview, it)) }
            return
        }
        visibleMessages.forEach { message ->
            hubView.addView(messageRow(message.color, message.sender, message.preview, message.toPaneTarget()))
        }
    }

    private fun messageRow(color: Int, who: String, preview: String, target: PaneTarget?): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8)); background = border(Line); isClickable = true
            setOnClickListener { haptic(this); if (target == null) openNotificationAccessSettings() else openHere(target) }
            setOnLongClickListener { haptic(this); if (target == null) openNotificationAccessSettings() else showOpenMenu(this, target); true }
            addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(8) })
            addView(TextView(context).apply { text = who; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ink); includeFontPadding = false })
            addView(TextView(context).apply { text = "  $preview"; textSize = 11f; maxLines = 1; setTextColor(InkDim); includeFontPadding = false },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ── Keyboard ─────────────────────────────────────────────────────────────

    private fun keyboard(): View {
        return SwipeKeyboardLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(7), keyboardTopPadding(), dp(7), keyboardBottomPadding())
            setBackgroundColor(0xFF000000.toInt())

            searchHintView = mono("", 8.5f, 0xFF55585F.toInt()).apply {
                gravity = Gravity.CENTER; letterSpacing = 0.16f; setPadding(0, 0, 0, hintBottomGap())
            }
            addView(searchHintView, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            suggestionBarView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), 0, dp(2), dp(4))
            }
            addView(suggestionBarView, LinearLayout.LayoutParams.MATCH_PARENT, dp(32))

            if (keyboardSettingsOpen) addView(keyboardSettings(), LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            addKeyRow("qwertyuiop".map { it.toString() })
            addKeyRow("asdfghjkl".map { it.toString() }, dp(22))
            addKeyRow(listOf("shift") + "zxcvbnm".map { it.toString() } + listOf("back"))
            addKeyRow(listOf("123", "clicks", "space", "period", "enter"))
        }
    }

    private fun keyboardSettings(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(10))
            background = GradientDrawable().apply { setColor(Panel); cornerRadius = dp(6).toFloat(); setStroke(dp(1), Line) }

            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL
                addView(mono("KEYBOARD SETTINGS", 9f, Accent).apply { letterSpacing = 0.16f },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(mono("DONE", 9f, InkDim).apply {
                    gravity = Gravity.RIGHT; isClickable = true; setPadding(dp(10), dp(4), 0, dp(4))
                    setOnClickListener { haptic(this); keyboardSettingsOpen = false; render() }
                })
            }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0)
                addView(mono("SIZE", 9f, InkDim).apply { letterSpacing = 0.12f })
                addView(SeekBar(context).apply {
                    max = 100; progress = keyboardSize
                    thumbTintList = android.content.res.ColorStateList.valueOf(Accent)
                    progressTintList = android.content.res.ColorStateList.valueOf(Accent)
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(KeyEdge)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (!fromUser) return; keyboardSize = p; sizeValueView.text = p.toString() }
                        override fun onStartTrackingTouch(s: SeekBar?) = Unit
                        override fun onStopTrackingTouch(s: SeekBar?) { s?.let { haptic(it) }; prefs().edit().putInt(KEYBOARD_SIZE_PREF, keyboardSize).apply(); render() }
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10); marginEnd = dp(8) })
                sizeValueView = mono(keyboardSize.toString(), 9f, Accent2).apply { gravity = Gravity.RIGHT }
                addView(sizeValueView, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
            }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            addView(settingToggle("HAPTIC FEEDBACK", hapticsEnabled) {
                hapticsEnabled = !hapticsEnabled
                prefs().edit().putBoolean(HAPTICS_PREF, hapticsEnabled).apply()
                haptic(this); render()
            }, LinearLayout.LayoutParams.MATCH_PARENT, dp(30))
        }
    }

    private fun settingToggle(label: String, enabled: Boolean, onClick: TextView.() -> Unit): View {
        return TextView(this).apply {
            text = "$label   ${if (enabled) "ON" else "OFF"}"; gravity = Gravity.CENTER_VERTICAL
            textSize = 10f; letterSpacing = 0.12f; typeface = Typeface.MONOSPACE
            setTextColor(if (enabled) Accent2 else InkDim); setPadding(0, dp(8), 0, 0)
            isClickable = true; setOnClickListener { onClick() }
        }
    }

    private fun LinearLayout.addKeyRow(labels: List<String>, horizontalInset: Int = 0) {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(horizontalInset, 0, horizontalInset, 0)
            labels.forEach { label ->
                val weight = when (label) {
                    "space" -> 3.7f
                    "123", "clicks", "enter", "back", "shift", "period" -> 1.15f
                    else -> 1f
                }
                addView(key(label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight))
            }
        }, LinearLayout.LayoutParams.MATCH_PARENT, keyRowHeight())
    }

    private fun key(label: String): TextView {
        return TextView(this).apply {
            text = keyLabel(label)
            gravity = Gravity.CENTER
            textSize = keyTextSize(label)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            includeFontPadding = false
            setTextColor(keyTextColor(label))
            isClickable = true
            if (label == "enter") background = keyBackground(goKeyColor)

            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.background = if (label == "enter") keyBackground(brighten(goKeyColor)) else keyBackground(KeyHighlight)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.background = if (label == "enter") keyBackground(goKeyColor) else null
                }
                false
            }
            setOnClickListener {
                haptic(this)
                if (label == "shift") handleShiftTap() else handleKey(label)
            }
            if (label == "enter") setOnLongClickListener { haptic(this); showGoColorMenu(this); true }
            if (label == "back") setOnLongClickListener { haptic(this); deleteWord(); true }
        }.also { keyViews[label] = it }
    }

    private fun keyLabel(label: String): String = when (label) {
        "back" -> "⌫"
        "space" -> "space"
        "enter" -> "GO"
        "period" -> "."
        "shift" -> "⬆"
        else -> if (label.length == 1) {
            if (shiftState != ShiftState.OFF) label.uppercase(Locale.US) else label.lowercase(Locale.US)
        } else label
    }

    // ── Suggestions ──────────────────────────────────────────────────────────

    private fun updateSuggestionBar() {
        if (!::suggestionBarView.isInitialized) return
        suggestionBarView.removeAllViews()
        suggestions.forEach { word ->
            suggestionBarView.addView(TextView(this).apply {
                text = word; textSize = 11.5f; gravity = Gravity.CENTER
                setTextColor(InkDim); typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setPadding(dp(4), dp(2), dp(4), dp(2))
                background = GradientDrawable().apply {
                    setColor(0xFF1A1C21.toInt()); cornerRadius = dp(8).toFloat(); setStroke(dp(1), KeyEdge)
                }
                isClickable = true; setOnClickListener { haptic(this); acceptSuggestion(word) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(3) })
        }
    }

    private fun acceptSuggestion(word: String) {
        val text = composeText.trimEnd()
        val lastSpace = text.lastIndexOf(' ')
        composeText = (if (lastSpace < 0) "" else text.substring(0, lastSpace + 1)) + word + " "
        suggestions = emptyList(); updateSuggestionBar()
        updateAutoCapState(); updateKeyLabels()
        openPane?.let { renderPaneContent(it) }
        renderRibbon()
    }

    private fun scheduleSpellCheck() {
        suggestDebounce?.let { handler.removeCallbacks(it) }
        val word = currentWordInCompose()
        if (word.length < 2) {
            if (suggestions.isNotEmpty()) { suggestions = emptyList(); updateSuggestionBar() }
            return
        }
        val r = Runnable { lastSuggestWord = word; spellChecker?.getSuggestions(TextInfo(word), 5) }
        suggestDebounce = r
        handler.postDelayed(r, 160)
    }

    private fun currentWordInCompose(): String {
        val text = composeText.trimEnd()
        val lastSpace = text.lastIndexOf(' ')
        return if (lastSpace < 0) text else text.substring(lastSpace + 1)
    }

    // ── Shift ────────────────────────────────────────────────────────────────

    private fun handleShiftTap() {
        val now = System.currentTimeMillis()
        shiftState = when {
            shiftState == ShiftState.ONCE && now - lastShiftTapMs < 400 -> ShiftState.LOCK
            shiftState == ShiftState.LOCK -> ShiftState.OFF
            else -> ShiftState.ONCE
        }
        lastShiftTapMs = now
        updateKeyLabels()
    }

    private fun updateKeyLabels() {
        val upper = shiftState != ShiftState.OFF
        "qwertyuiopasdfghjklzxcvbnm".forEach { c ->
            keyViews[c.toString()]?.text = if (upper) c.uppercaseChar().toString() else c.lowercaseChar().toString()
        }
        keyViews["shift"]?.setTextColor(when (shiftState) {
            ShiftState.OFF -> 0xFF7D8078.toInt()
            ShiftState.ONCE -> Ink
            ShiftState.LOCK -> Accent
        })
    }

    private fun updateAutoCapState() {
        if (shiftState == ShiftState.LOCK) return
        val t = composeText.trimEnd()
        val cap = t.isEmpty() || t.endsWith('.') || t.endsWith('!') || t.endsWith('?')
        shiftState = if (cap) ShiftState.ONCE else ShiftState.OFF
    }

    // ── Swipe ────────────────────────────────────────────────────────────────

    private fun captureKeyBounds() {
        keyBounds.clear()
        val loc = IntArray(2)
        keyViews.forEach { (label, view) ->
            if (view.width > 0 && view.height > 0) {
                view.getLocationOnScreen(loc)
                keyBounds[label] = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
            }
        }
    }

    private fun keyAtPoint(rawX: Float, rawY: Float): String? {
        val x = rawX.toInt(); val y = rawY.toInt()
        return keyBounds.entries.firstOrNull { (_, r) -> r.contains(x, y) }?.key
    }

    private fun handleSwipe(keys: List<String>) {
        val pane = openPane ?: return
        if (pane.kind != PaneKind.CHAT) return
        haptic(contentFrame)
        val rawWord = keys.joinToString("")
        composeText += rawWord
        scheduleSpellCheck()
        renderPaneContent(pane)
        renderRibbon()
    }

    inner class SwipeKeyboardLayout(context: Context) : LinearLayout(context) {
        private var startRawX = 0f
        private var startRawY = 0f
        private var tracking = false
        private val traced = mutableListOf<String>()

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX; startRawY = ev.rawY; tracking = false; traced.clear()
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) {
                        if (abs(ev.rawX - startRawX) > dp(10) || abs(ev.rawY - startRawY) > dp(10)) {
                            tracking = true
                            keyAtPoint(startRawX, startRawY)?.let { k -> if (k.length == 1) traced.add(k) }
                            return true
                        }
                    }
                    return tracking
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { tracking = false; return false }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!tracking) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val k = keyAtPoint(ev.rawX, ev.rawY)
                    if (k != null && k.length == 1 && (traced.isEmpty() || traced.last() != k)) traced.add(k)
                }
                MotionEvent.ACTION_UP -> { if (traced.size >= 3) handleSwipe(traced.toList()); tracking = false; traced.clear() }
                MotionEvent.ACTION_CANCEL -> { tracking = false; traced.clear() }
            }
            return true
        }
    }

    // ── Key handling ─────────────────────────────────────────────────────────

    private fun handleKey(label: String) {
        val pane = openPane
        if (pane?.kind == PaneKind.CHAT) { handleChatKey(label, pane); return }

        when (label) {
            "back" -> query = query.dropLast(1)
            "space" -> query += " "
            "period" -> query += "."
            "clicks" -> { keyboardSettingsOpen = !keyboardSettingsOpen; query = ""; render(); return }
            "enter" -> filteredApps().firstOrNull()?.let { openExternal(it.toPaneTarget()) }
            "123" -> Unit
            else -> query += label
        }
        renderRibbon()
    }

    private fun handleChatKey(label: String, pane: PaneTarget) {
        when (label) {
            "back" -> {
                composeText = composeText.dropLast(1)
                updateAutoCapState(); updateKeyLabels(); scheduleSpellCheck()
            }
            "space" -> {
                val now = System.currentTimeMillis()
                if (now - lastSpaceMs < 500 && composeText.isNotEmpty() && composeText.last() == ' ') {
                    composeText = composeText.dropLast(1) + ". "
                    shiftState = ShiftState.ONCE; updateKeyLabels()
                    suggestions = emptyList(); updateSuggestionBar()
                } else {
                    composeText += " "
                    suggestions = emptyList(); updateSuggestionBar()
                    updateAutoCapState(); updateKeyLabels()
                }
                lastSpaceMs = now
            }
            "period" -> {
                composeText += "."
                shiftState = ShiftState.ONCE; updateKeyLabels()
                suggestions = emptyList(); updateSuggestionBar()
            }
            "clicks" -> { keyboardSettingsOpen = !keyboardSettingsOpen; render(); return }
            "enter" -> postComposeBubble(pane)
            "123" -> Unit
            else -> {
                val char = if (shiftState != ShiftState.OFF) label.uppercase(Locale.US) else label
                composeText += char
                if (shiftState == ShiftState.ONCE) { shiftState = ShiftState.OFF; updateKeyLabels() }
                scheduleSpellCheck()
            }
        }
        renderPaneContent(pane)
        renderRibbon()
    }

    private fun deleteWord() {
        val pane = openPane
        if (pane?.kind == PaneKind.CHAT) {
            val trimmed = composeText.trimEnd()
            val lastSpace = trimmed.lastIndexOf(' ')
            composeText = if (lastSpace < 0) "" else trimmed.substring(0, lastSpace + 1)
            updateAutoCapState(); updateKeyLabels()
            suggestions = emptyList(); updateSuggestionBar()
            renderPaneContent(pane); renderRibbon()
            return
        }
        query = ""; renderRibbon()
    }

    // ── Pane / navigation ────────────────────────────────────────────────────

    private fun openHere(target: PaneTarget) {
        libraryOpen = false
        libraryView?.let { contentFrame.removeView(it) }
        libraryView = null
        keyboardSettingsOpen = false; query = ""; composeText = ""
        shiftState = ShiftState.ONCE; suggestions = emptyList()
        updateKeyLabels(); updateSuggestionBar()
        openPane = target; showPane(target, animate = true); renderRibbon()
    }

    private fun showPane(target: PaneTarget, animate: Boolean) {
        paneView?.let { contentFrame.removeView(it) }
        paneView = pane(target).also { p ->
            contentFrame.addView(p, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            if (animate) p.post { p.translationY = p.height.toFloat(); p.animate().translationY(0f).setDuration(340).start() }
        }
    }

    private fun closePane() {
        val closing = paneView
        openPane = null; composeText = ""
        shiftState = ShiftState.OFF; suggestions = emptyList()
        updateKeyLabels(); updateSuggestionBar(); renderRibbon()
        if (closing == null) return
        closing.animate().translationY(closing.height.toFloat()).setDuration(220).withEndAction {
            contentFrame.removeView(closing); if (paneView === closing) paneView = null
        }.start()
    }

    private fun pane(target: PaneTarget): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Panel); tag = PANE_BODY_TAG
            addView(paneBar(target), LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            addView(paneBody(target), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            if (target.kind == PaneKind.CHAT) addView(composeBar(), LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
        }
    }

    private fun paneBar(target: PaneTarget): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(10), 0); setBackgroundColor(Panel2)
            addView(TextView(context).apply {
                text = target.name.take(1).uppercase(Locale.US); textSize = 13f; gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF10110F.toInt())
                background = GradientDrawable().apply { setColor(target.accent); cornerRadius = dp(7).toFloat() }
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) })
            addView(TextView(context).apply {
                text = target.name; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.03f; setTextColor(Ink)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = "X"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(InkDim)
                background = keyBackground(); isClickable = true
                setOnClickListener { haptic(this); closePane() }
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
        }
    }

    private fun paneBody(target: PaneTarget): View {
        return ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
                if (target.kind == PaneKind.CHAT) {
                    chatLines(target).forEach { addView(bubble(it)) }
                } else {
                    addView(listRow("Inbox", "now"))
                    addView(listRow(target.preview.ifBlank { "Open ${target.name}" }, "item"))
                    addView(listRow("Open in real app from long press", "external"))
                }
            })
        }
    }

    private fun renderPaneContent(target: PaneTarget) {
        val existing = paneView as? LinearLayout ?: return
        existing.removeAllViews()
        existing.addView(paneBar(target), LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        existing.addView(paneBody(target), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        if (target.kind == PaneKind.CHAT) existing.addView(composeBar(), LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
    }

    private fun chatLines(target: PaneTarget): MutableList<ChatLine> {
        return chatLinesById.getOrPut(target.id) {
            mutableListOf(ChatLine(target.preview.ifBlank { "Ready when you are." }, false))
        }
    }

    private fun postComposeBubble(target: PaneTarget) {
        val text = composeText.trim(); if (text.isEmpty()) return
        chatLines(target).add(ChatLine(text, true))
        composeText = ""; shiftState = ShiftState.ONCE
        suggestions = emptyList(); updateSuggestionBar(); updateKeyLabels()
    }

    private fun bubble(line: ChatLine): View {
        return TextView(this).apply {
            text = line.text; textSize = 12.5f
            setTextColor(if (line.fromMe) 0xFF180A06.toInt() else Ink)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = GradientDrawable().apply {
                setColor(if (line.fromMe) Accent else Panel2); cornerRadius = dp(13).toFloat()
                if (!line.fromMe) setStroke(dp(1), Line)
            }
            layoutParams = LinearLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.72f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8); gravity = if (line.fromMe) Gravity.RIGHT else Gravity.LEFT }
        }
    }

    private fun listRow(label: String, meta: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(10), dp(2), dp(10)); background = border(Line)
            addView(TextView(context).apply { text = label; textSize = 12.5f; setTextColor(Ink) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(mono(meta, 11f, InkDim))
        }
    }

    private fun composeBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(Panel2)
            addView(TextView(context).apply {
                text = if (composeText.isBlank()) "|" else "$composeText|"
                textSize = 12.5f; setTextColor(if (composeText.isBlank()) Accent else Ink)
                setPadding(dp(12), 0, dp(12), 0); gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(Screen); cornerRadius = dp(16).toFloat(); setStroke(dp(1), KeyEdge)
                }
            }, LinearLayout.LayoutParams(0, dp(32), 1f).apply { marginEnd = dp(8) })
            addView(TextView(context).apply {
                text = "GO"; textSize = 11f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF180A06.toInt())
                background = GradientDrawable().apply { setColor(goKeyColor); shape = GradientDrawable.OVAL }
                isClickable = true
                setOnClickListener {
                    val target = openPane ?: return@setOnClickListener
                    haptic(this); postComposeBubble(target); renderPaneContent(target)
                }
            }, LinearLayout.LayoutParams(dp(34), dp(34)))
        }
    }

    private fun renderRibbon() {
        if (!::ribbonView.isInitialized) return
        ribbonView.removeAllViews()
        filteredApps().take(8).forEachIndexed { index, app -> ribbonView.addView(ribbonButton(app, index == 0)) }
        val pane = openPane
        searchHintView.text = when {
            libraryOpen -> "APP LIBRARY  .  TAP TOP BUTTON FOR CATEGORY / GRID"
            keyboardSettingsOpen -> "KEYBOARD SETTINGS OPEN"
            pane?.kind == PaneKind.CHAT -> "TYPING NOW GOES TO ${pane.name.uppercase(Locale.US)}"
            query.isBlank() -> "SEARCHING APPS:    TAP KEYS  .  CLICKS FOR SETTINGS"
            else -> "SEARCHING APPS:  ${query.uppercase(Locale.US)}  .  DEL TO CLEAR"
        }
    }

    private fun ribbonButton(app: AppEntry, isFirst: Boolean): View {
        val target = app.toPaneTarget()
        return TextView(this).apply {
            text = app.shortName; gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            textSize = 12f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.04f
            setTextColor(if (isFirst && query.isNotBlank()) Ink else InkDim)
            setPadding(dp(4), dp(8), dp(14), dp(8))
            background = if (isFirst && query.isNotBlank()) highlight(app.brandColor) else null
            isClickable = true
            setOnClickListener { haptic(this); openExternal(target) }
            setOnLongClickListener { haptic(this); showOpenMenu(this, target); true }
        }
    }

    // ── Menus ────────────────────────────────────────────────────────────────

    private fun showOpenMenu(anchor: View, target: PaneTarget) {
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply { setColor(Panel2); cornerRadius = dp(8).toFloat(); setStroke(dp(1), Line) }
        }
        val popup = PopupWindow(menu, dp(164), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply { isOutsideTouchable = true }
        menu.addView(menuItem("Open here", true) { popup.dismiss(); openHere(target) })
        menu.addView(menuItem("Open in ${target.name}", target.packageName != null) { popup.dismiss(); openExternal(target) })
        popup.showAsDropDown(anchor, -dp(82), -anchor.height)
    }

    private fun menuItem(label: String, enabled: Boolean, onClick: () -> Unit): View {
        return TextView(this).apply {
            text = label; textSize = 13f; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setTextColor(if (enabled) Ink else InkDim); isEnabled = enabled; isClickable = enabled
            setOnClickListener { haptic(this); onClick() }
        }
    }

    private fun showGoColorMenu(anchor: View) {
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply { setColor(Panel2); cornerRadius = dp(8).toFloat(); setStroke(dp(1), Line) }
        }
        val popup = PopupWindow(menu, dp(152), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply { isOutsideTouchable = true }
        GO_COLORS.forEach { option ->
            menu.addView(TextView(this).apply {
                text = option.name; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER_VERTICAL
                setTextColor(option.color); setPadding(dp(10), dp(9), dp(10), dp(9))
                background = if (goKeyColor == option.color) highlight(option.color) else null
                isClickable = true
                setOnClickListener { haptic(this); goKeyColor = option.color; prefs().edit().putInt(GO_KEY_COLOR_PREF, goKeyColor).apply(); popup.dismiss(); render() }
            })
        }
        popup.showAsDropDown(anchor, -dp(96), -dp(180))
    }

    // ── App loading ──────────────────────────────────────────────────────────

    private fun openExternal(target: PaneTarget) {
        val packageName = target.packageName
        if (packageName == null) { openHere(target); return }
        try {
            if (target.deepLinkUri != null && isInstalled(packageName)) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.deepLinkUri))); return
            }
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) { startActivity(launchIntent); return }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "${target.name} isn't available here", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isInstalled(packageName: String) = packageManager.getLaunchIntentForPackage(packageName) != null

    private fun filteredApps(): List<AppEntry> {
        if (query.isBlank()) return apps
        return apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    private fun loadLaunchableApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        return packageManager.queryIntentActivities(intent, 0)
            .filterNot { it.activityInfo.packageName == packageName }
            .map { it.toAppEntry() }.distinctBy { it.packageName }
            .sortedWith { l, r -> collator.compare(l.label, r.label) }
    }

    private fun ResolveInfo.toAppEntry(): AppEntry {
        val label = loadLabel(packageManager).toString()
        return AppEntry(label, label.take(8), activityInfo.packageName, ComponentName(activityInfo.packageName, activityInfo.name), appBrandColor(activityInfo.packageName))
    }

    private fun AppEntry.toPaneTarget() = PaneTarget(packageName, label, brandColor, PaneKind.LIST, packageName, null, "Open $label")

    private fun AppEntry.toLibraryApp() = LibraryApp(label, brandColor, toPaneTarget(), componentName)

    private fun HubMessage.toPaneTarget() = PaneTarget("$packageName:$sender", sender, color, PaneKind.CHAT, packageName, null, preview)

    private fun seedPaneTargets() = listOf(
        PaneTarget("mara", "Mara", 0xFF5FD0C4.toInt(), PaneKind.CHAT, "com.google.android.apps.messaging", null, "are we still on for 6?"),
        PaneTarget("devgrp", "Dev grp", 0xFF54A9EB.toInt(), PaneKind.CHAT, "org.telegram.messenger", null, "pushed the build"),
        PaneTarget("boss", "Boss", 0xFFF5C451.toInt(), PaneKind.LIST, "com.google.android.gm", null, "re: friday numbers")
    )

    private fun loadHubMessages(): List<HubMessage> {
        val raw = prefs().getString(HUB_MESSAGES_PREF, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(HubMessage(item.optString("sender", "Message").ifBlank { "Message" },
                    item.optString("preview", ""), item.optString("packageName", ""), item.optInt("color", Accent2)))
            }
        }
    }

    // ── Drawing / utils ──────────────────────────────────────────────────────

    private fun isNotificationAccessEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val expected = ComponentName(this, ClicksNotificationListener::class.java).flattenToString()
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    private fun openNotificationAccessSettings() { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

    private fun updateClock() {
        val now = LocalDateTime.now()
        clockView.text = now.format(DateTimeFormatter.ofPattern("H:mm", Locale.US))
        dateView.text = now.format(DateTimeFormatter.ofPattern("EEE . MMM d", Locale.US)).uppercase(Locale.US)
    }

    private fun mono(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text; textSize = size; typeface = Typeface.MONOSPACE; setTextColor(color); includeFontPadding = false
    }

    private fun border(color: Int) = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(1), color) }

    private fun highlight(color: Int) = GradientDrawable().apply {
        setColor(Color.argb(34, Color.red(color), Color.green(color), Color.blue(color))); setStroke(dp(2), color)
    }

    private fun roundedPanel(fill: Int, radius: Int, stroke: Int? = null) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(brighten(fill), fill)).apply {
            cornerRadius = radius.toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun libraryCategories() = listOf(
        LibraryCategory("Communication", LibrarySpan.WIDE, listOf("Messages", "Telegram", "Mail", "Phone").map { libraryApp(it) }),
        LibraryCategory("Productivity", LibrarySpan.TALL, listOf("Notes", "Search", "Calendar", "Files", "Tasks", "Docs").map { libraryApp(it) }),
        LibraryCategory("Media", LibrarySpan.NORMAL, listOf("Music", "Photos", "Camera", "Podcasts").map { libraryApp(it) }),
        LibraryCategory("Tools", LibrarySpan.WIDE, listOf("Maps", "Weather", "Clock", "Calc", "Settings").map { libraryApp(it) })
    )

    private fun libraryApp(seedName: String): LibraryApp {
        val installed = apps.firstOrNull { app ->
            app.label.equals(seedName, ignoreCase = true) ||
                app.label.contains(seedName, ignoreCase = true) ||
                seedName.contains(app.label, ignoreCase = true)
        }
        if (installed != null) return installed.toLibraryApp()
        val accent = seedAccent(seedName)
        val target = PaneTarget("library:${seedName.lowercase(Locale.US)}", seedName, accent, PaneKind.LIST, null, null, "Open $seedName")
        return LibraryApp(seedName, accent, target, null)
    }

    private fun seedAccent(name: String) = when (name.lowercase(Locale.US)) {
        "messages" -> 0xFF65D6B7.toInt(); "telegram" -> 0xFF54A9EB.toInt(); "mail" -> 0xFFF15F5F.toInt()
        "phone" -> 0xFF6DD77B.toInt(); "notes" -> Accent2; "search" -> 0xFF8AB4F8.toInt()
        "calendar" -> 0xFF5C7CFA.toInt(); "files", "docs" -> 0xFFB8A7FF.toInt(); "tasks" -> 0xFFFF8A4C.toInt()
        "music", "podcasts" -> 0xFFFF5A8A.toInt(); "photos", "camera" -> 0xFF72D4FF.toInt()
        "maps", "weather" -> 0xFF79D39D.toInt(); "clock", "calc", "settings" -> 0xFF9FA6B2.toInt()
        else -> Accent
    }

    private fun iconFor(app: LibraryApp): Drawable {
        prefs().getString(iconOverrideKey(app), null)?.let { iconFromOverride(it)?.let { icon -> return icon } }
        app.componentName?.let { component ->
            autoIconPackIcon(component)?.let { return it }
            runCatching { packageManager.getActivityIcon(component) }.getOrNull()?.let { return it }
        }
        app.target.packageName?.let { pkg -> runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()?.let { return it } }
        return fallbackLetterIcon(app)
    }

    private fun iconFromOverride(value: String): Drawable? = when {
        value.startsWith("component:") -> ComponentName.unflattenFromString(value.removePrefix("component:"))
            ?.let { runCatching { packageManager.getActivityIcon(it) }.getOrNull() }
        value.startsWith("pack:") -> {
            val parts = value.split(":", limit = 3)
            if (parts.size == 3) drawableFromIconPack(parts[1], parts[2]) else null
        }
        else -> null
    }

    private fun fallbackLetterIcon(app: LibraryApp): Drawable {
        val size = dp(46)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(brighten(app.accent), app.accent)).apply {
            cornerRadius = dp(13).toFloat(); setBounds(0, 0, size, size); draw(canvas)
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF10110F.toInt(); textAlign = android.graphics.Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD; textSize = dp(18).toFloat()
        }
        canvas.drawText(app.name.take(1).uppercase(Locale.US), size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        return android.graphics.drawable.BitmapDrawable(resources, bitmap)
    }

    private fun showIconMenu(anchor: View, app: LibraryApp) {
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply { setColor(Panel2); cornerRadius = dp(8).toFloat(); setStroke(dp(1), Line) }
        }
        val popup = PopupWindow(menu, dp(224), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply { isOutsideTouchable = true }
        menu.addView(menuItem("Default icon", true) {
            prefs().edit().remove(iconOverrideKey(app)).apply(); popup.dismiss(); showLibrary(animate = false)
        })
        menu.addView(menuItem("System icons for all", true) {
            prefs().edit().remove(ACTIVE_ICON_PACK_PREF).apply(); popup.dismiss(); showLibrary(animate = false)
        })
        app.componentName?.let { component ->
            iconPacks().forEach { pack ->
                matchingIconPackIcon(pack, component)?.let { icon ->
                    menu.addView(menuItem("Use ${pack.name} here", true) {
                        prefs().edit().putString(iconOverrideKey(app), "pack:${icon.packageName}:${icon.drawableName}").apply()
                        popup.dismiss(); showLibrary(animate = false)
                    })
                    menu.addView(menuItem("Apply ${pack.name} to all", true) {
                        prefs().edit().putString(ACTIVE_ICON_PACK_PREF, pack.packageName).apply()
                        popup.dismiss(); showLibrary(animate = false)
                    })
                }
            }
        }
        menu.addView(menuItem("Use another app icon", apps.isNotEmpty()) { popup.dismiss(); showAppIconChooser(anchor, app) })
        popup.showAsDropDown(anchor, -dp(82), -anchor.height)
    }

    private fun showAppIconChooser(anchor: View, app: LibraryApp) {
        lateinit var popup: PopupWindow
        val menu = ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6))
                background = GradientDrawable().apply { setColor(Panel2); cornerRadius = dp(8).toFloat(); setStroke(dp(1), Line) }
                apps.take(36).forEach { candidate ->
                    addView(menuItem(candidate.label, true) {
                        prefs().edit().putString(iconOverrideKey(app), "component:${candidate.componentName.flattenToString()}").apply()
                        popup.dismiss(); showLibrary(animate = false)
                    })
                }
            })
        }
        popup = PopupWindow(menu, dp(240), dp(360), true).apply {
            isOutsideTouchable = true
            showAsDropDown(anchor, -dp(96), -dp(320))
        }
    }

    private fun iconOverrideKey(app: LibraryApp) = "$ICON_OVERRIDE_PREFIX${app.target.packageName ?: app.target.id}"

    private fun iconPacks(): List<IconPack> {
        iconPacksCache?.let { return it }
        val packs = packageManager.getInstalledApplications(0).mapNotNull { info ->
            val res = runCatching { packageManager.getResourcesForApplication(info.packageName) }.getOrNull() ?: return@mapNotNull null
            val hasFilter = runCatching { res.assets.open("appfilter.xml").use { true } }.getOrDefault(false)
            if (!hasFilter) null else IconPack(packageManager.getApplicationLabel(info).toString(), info.packageName)
        }.sortedWith { l, r -> collator.compare(l.name, r.name) }
        iconPacksCache = packs
        return packs
    }

    private fun autoIconPackIcon(component: ComponentName): Drawable? {
        val active = prefs().getString(ACTIVE_ICON_PACK_PREF, null) ?: return null
        val pack = iconPacks().firstOrNull { it.packageName == active } ?: return null
        val icon = matchingIconPackIcon(pack, component) ?: return null
        return drawableFromIconPack(icon.packageName, icon.drawableName)
    }

    private fun matchingIconPackIcon(pack: IconPack, component: ComponentName): IconPackIcon? {
        val key = "${pack.packageName}|${component.flattenToString()}"
        if (iconPackMatchCache.containsKey(key)) return iconPackMatchCache[key]
        val res = runCatching { packageManager.getResourcesForApplication(pack.packageName) }.getOrNull()
            ?: return null.also { iconPackMatchCache[key] = null }
        val names = setOf(
            "ComponentInfo{${component.packageName}/${component.className}}",
            "ComponentInfo{${component.flattenToString()}}"
        )
        val result = runCatching {
            res.assets.open("appfilter.xml").use { stream ->
                val parser = Xml.newPullParser()
                parser.setInput(stream, null)
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "item") {
                        val itemComponent = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (drawable != null && itemComponent in names) return@use IconPackIcon(pack.packageName, drawable)
                    }
                    event = parser.next()
                }
                null
            }
        }.getOrNull()
        iconPackMatchCache[key] = result
        return result
    }

    private fun drawableFromIconPack(packPackage: String, drawableName: String): Drawable? {
        val res = runCatching { packageManager.getResourcesForApplication(packPackage) }.getOrNull() ?: return null
        val id = res.getIdentifier(drawableName, "drawable", packPackage)
        return if (id == 0) null else runCatching { res.getDrawable(id, theme) }.getOrNull()
    }

    private fun keyBackground() = keyBackground(null)

    private fun keyBackground(fillColor: Int?): GradientDrawable {
        if (fillColor != null) return GradientDrawable().apply {
            setColor(fillColor); cornerRadius = dp(6).toFloat(); setStroke(dp(1), brighten(fillColor))
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF2A2D36.toInt(), Key)).apply {
            cornerRadius = dp(6).toFloat(); setStroke(dp(1), KeyEdge)
        }
    }

    private fun keyRowHeight(): Int {
        val base = if (keyboardSettingsOpen) 44 else 56
        val growth = if (keyboardSettingsOpen) 8 else 20
        return dp(base + (keyboardSize * growth / 100))
    }

    private fun hintBottomGap() = dp(2 + (keyboardSize * 2 / 100))
    private fun keyboardHeight() = dp(272 + keyboardSize * 80 / 100)
    private fun keyboardTopPadding() = dp(4)
    private fun keyboardBottomPadding() = dp(12)

    private fun keyTextSize(label: String): Float {
        val base = when (label) { "space" -> 18f; "123", "clicks", "enter", "back", "shift", "period" -> 13.5f; else -> 20f }
        val growth = when (label) { "space" -> 2f; "123", "clicks", "enter", "back", "shift", "period" -> 1.5f; else -> 2.5f }
        return base + (keyboardSize * growth / 100f)
    }

    private fun keyTextColor(label: String) = when (label) {
        "enter" -> goKeyColor
        "shift" -> when (shiftState) { ShiftState.OFF -> 0xFF7D8078.toInt(); ShiftState.ONCE -> Ink; ShiftState.LOCK -> Accent }
        "123", "clicks", "back", "period" -> 0xFF7D8078.toInt()
        else -> Ink
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun haptic(view: View) {
        if (!hapticsEnabled) return
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun brighten(color: Int) = Color.rgb(
        (Color.red(color) + 36).coerceAtMost(255),
        (Color.green(color) + 36).coerceAtMost(255),
        (Color.blue(color) + 36).coerceAtMost(255)
    )

    private fun appBrandColor(packageName: String): Int {
        val icon = runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull() ?: return Accent
        val bitmap = icon.toBitmap(dp(36), dp(36))
        var red = 0L; var green = 0L; var blue = 0L; var count = 0L
        for (x in 0 until bitmap.width step 2) for (y in 0 until bitmap.height step 2) {
            val pixel = bitmap.getPixel(x, y); if (Color.alpha(pixel) < 80) continue
            val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
            val br = (r + g + b) / 3; if (br < 32 || br > 232) continue
            red += r; green += g; blue += b; count++
        }
        if (count == 0L) return Accent
        return Color.rgb((red/count).toInt().coerceIn(48,232), (green/count).toInt().coerceIn(48,232), (blue/count).toInt().coerceIn(48,232))
    }

    private fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); setBounds(0, 0, canvas.width, canvas.height); draw(canvas); return bitmap
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // ── Types ────────────────────────────────────────────────────────────────

    private enum class PaneKind { CHAT, LIST }
    private enum class ShiftState { OFF, ONCE, LOCK }

    private data class PaneTarget(val id: String, val name: String, val accent: Int, val kind: PaneKind,
        val packageName: String?, val deepLinkUri: String?, val preview: String)
    private data class AppEntry(val label: String, val shortName: String, val packageName: String, val componentName: ComponentName, val brandColor: Int)
    private enum class LibrarySpan { WIDE, TALL, NORMAL }
    private data class LibraryCategory(val name: String, val span: LibrarySpan, val apps: List<LibraryApp>)
    private data class LibraryApp(val name: String, val accent: Int, val target: PaneTarget, val componentName: ComponentName?)
    private data class IconPack(val name: String, val packageName: String)
    private data class IconPackIcon(val packageName: String, val drawableName: String)
    private data class HubMessage(val sender: String, val preview: String, val packageName: String, val color: Int)
    private data class ChatLine(val text: String, val fromMe: Boolean)

    companion object {
        private const val Screen = 0xFF0D0E11.toInt()
        private const val Panel = 0xFF16181D.toInt()
        private const val Panel2 = 0xFF1D2027.toInt()
        private const val Ink = 0xFFF3F0E7.toInt()
        private const val InkDim = 0xFF8B8F99.toInt()
        private const val Accent = 0xFFFF5A3C.toInt()
        private const val Accent2 = 0xFFF5C451.toInt()
        private const val Line = 0xFF2A2C33.toInt()
        private const val Key = 0xFF23262E.toInt()
        private const val KeyEdge = 0xFF34373F.toInt()
        private const val KeyHighlight = 0xFF3A3E4A.toInt()
        private const val PREFS_NAME = "clicks"
        private const val KEYBOARD_SIZE_PREF = "keyboard_size"
        private const val HAPTICS_PREF = "haptics"
        private const val GO_KEY_COLOR_PREF = "go_key_color"
        private const val HUB_MESSAGES_PREF = "hub_messages"
        private const val ACTIVE_ICON_PACK_PREF = "active_icon_pack"
        private const val ICON_OVERRIDE_PREFIX = "icon_override_"
        private const val PANE_BODY_TAG = "pane"

        private val GO_COLORS = listOf(
            ColorOption("ORANGE", Accent), ColorOption("AMBER", Accent2),
            ColorOption("TEAL", 0xFF5FD0C4.toInt()), ColorOption("BLUE", 0xFF54A9EB.toInt()),
            ColorOption("LILAC", 0xFFC4B5FF.toInt()), ColorOption("GREEN", 0xFF8FD694.toInt())
        )
    }

    private data class ColorOption(val name: String, val color: Int)
}
