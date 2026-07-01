package com.fran.clicks

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import com.fran.clicks.glide.KeyInfo
import com.fran.clicks.glide.StatisticalGlideTypingClassifier
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.os.Bundle
import android.speech.RecognizerIntent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.fran.clicks.keyboard.CustomHapticEngine
import com.fran.clicks.keyboard.DynamicFlickKeyView
import com.fran.clicks.keyboard.FlickDetector
import com.fran.clicks.keyboard.FlickDirection
import com.fran.clicks.keyboard.KeyPreviewManager
import com.fran.clicks.keyboard.LivePredictionRouter
import com.fran.clicks.keyboard.PredictionEngine
import com.fran.clicks.keyboard.PredictionOverlayManager
import com.fran.clicks.keyboard.SpatialScorer
import com.fran.clicks.keyboard.WordBoundaryDeleter
import com.fran.clicks.db.NgramRepository
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import java.text.Collator
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : ComponentActivity(), SpellCheckerSession.SpellCheckerSessionListener {
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
    private var libraryGridMode = true
    private var librarySearchQuery = ""
    private var libraryView: View? = null
    private var categoryFolderView: View? = null
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
    private var glideClassifier: StatisticalGlideTypingClassifier? = null
    private var numberPadOpen = false
    private lateinit var mediaSessionSource: MediaSessionSource
    private val mediaUiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var contactsLauncher: ActivityResultLauncher<String>
    private lateinit var hapticEngine: CustomHapticEngine
    private lateinit var spatialScorer: SpatialScorer
    private lateinit var keyPreviewManager: KeyPreviewManager
    private lateinit var predictionEngine: PredictionEngine
    private lateinit var ngramRepo: NgramRepository
    private lateinit var flickDetector: FlickDetector
    private lateinit var predictionOverlay: PredictionOverlayManager
    private lateinit var liveRouter: LivePredictionRouter
    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var hubView: LinearLayout
    private lateinit var ribbonView: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private lateinit var nowPlayingCardView: ComposeView
    private lateinit var searchHintView: TextView
    private lateinit var sizeValueView: TextView
    private lateinit var suggestionBarView: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contactsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { renderRibbon() }
        keyboardSize = prefs().getInt(KEYBOARD_SIZE_PREF, 28)
        hapticsEnabled = prefs().getBoolean(HAPTICS_PREF, true)
        libraryGridMode = prefs().getBoolean(LIBRARY_GRID_MODE_PREF, true)
        goKeyColor = prefs().getInt(GO_KEY_COLOR_PREF, Accent)
        apps = loadLaunchableApps()
        messages = loadHubMessages()
        mediaSessionSource = MediaSessionSource(this)
        initSpellChecker()
        hapticEngine = CustomHapticEngine(this)
        spatialScorer = SpatialScorer()
        keyPreviewManager = KeyPreviewManager(this)
        ngramRepo = NgramRepository(this)
        predictionEngine = PredictionEngine(emptyMap())
        flickDetector = FlickDetector()
        predictionOverlay = PredictionOverlayManager(this)
        liveRouter = LivePredictionRouter(
            scope = mediaUiScope,
            getKeyView = { label -> keyViews[label] as? DynamicFlickKeyView },
            getSuggestions = { word -> predictionEngine.getSuggestions(word, maxCount = 8) }
        )
        liveRouter.start()
        loadGlideWords()
        render()
        mediaSessionSource.start()
        mediaUiScope.launch {
            mediaSessionSource.nowPlaying.collect {
                syncNowPlayingCardVisibility()
                refreshNowPlayingCard()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        apps = loadLaunchableApps()
        messages = loadHubMessages()
        if (::mediaSessionSource.isInitialized) mediaSessionSource.refreshActiveSessions()
        if (::ribbonView.isInitialized) {
            updateClock()
            renderHub()
            renderRibbon()
            refreshNowPlayingCard()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::mediaSessionSource.isInitialized) mediaSessionSource.stop()
        mediaUiScope.cancel()
        spellChecker?.close()
        handler.removeCallbacksAndMessages(null)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOICE_REQUEST_CODE && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull() ?: return
            val pane = openPane
            if (pane?.kind == PaneKind.CHAT) {
                if (composeText.isNotBlank() && !composeText.endsWith(' ')) composeText += " "
                composeText += spoken
                updateAutoCapState(); updateKeyLabels()
                renderPaneContent(pane)
            } else {
                if (query.isNotBlank() && !query.endsWith(' ')) query += " "
                query += spoken
            }
            renderRibbon()
        }
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(intent, VOICE_REQUEST_CODE) }
            .onFailure { Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show() }
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
            setPadding(0, systemStatusBarHeight(), 0, 0)
        }
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
        syncNowPlayingCardVisibility()
        refreshNowPlayingCard()
        root.post { captureKeyBounds() }
    }

    private fun home(): View {
        return FrameLayout(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(20), dp(8), dp(6), dp(88))
                addView(homeLeft(), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                ribbonView = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
                }
                addView(ribbonView, LinearLayout.LayoutParams(dp(94), ViewGroup.LayoutParams.MATCH_PARENT))
            }, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

            nowPlayingCardView = ComposeView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setNowPlayingCardContent()
                elevation = dp(8).toFloat()
            }
            addView(nowPlayingCardView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, nowPlayingCardHeight(), Gravity.BOTTOM).apply {
                leftMargin = dp(14)
                rightMargin = dp(14)
                bottomMargin = dp(10)
            })
        }
    }

    private fun homeLeft(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Top-aligned by design: bottom space is reserved for the now-playing overlay.
            gravity = Gravity.TOP
            setPadding(0, 0, dp(10), 0)
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

    private fun ComposeView.setNowPlayingCardContent() {
        setContent {
            val media by mediaSessionSource.nowPlaying.collectAsState()
            val current = media
            NowPlayingCard(
                visible = current?.isPlaying == true && openPane == null && !libraryOpen,
                title = current?.title.orEmpty(),
                artist = current?.artist.orEmpty(),
                sourceApp = current?.sourceApp.orEmpty(),
                albumArt = current?.albumArt,
                isPlaying = current?.isPlaying == true,
                onClick = {
                    haptic(this@setNowPlayingCardContent)
                    openHere(musicTarget())
                }
            )
        }
    }

    private fun refreshNowPlayingCard() {
        if (::nowPlayingCardView.isInitialized && ::mediaSessionSource.isInitialized) {
            nowPlayingCardView.setNowPlayingCardContent()
        }
    }

    private fun syncNowPlayingCardVisibility() {
        if (!::nowPlayingCardView.isInitialized || !::mediaSessionSource.isInitialized) return
        val targetHeight = nowPlayingCardHeight()
        if (nowPlayingCardView.layoutParams?.height == targetHeight) return
        nowPlayingCardView.layoutParams = nowPlayingCardView.layoutParams.apply { height = targetHeight }
    }

    private fun nowPlayingCardHeight(): Int {
        val shouldShow = ::mediaSessionSource.isInitialized &&
            mediaSessionSource.nowPlaying.value?.isPlaying == true &&
            openPane == null &&
            !libraryOpen
        return if (shouldShow) dp(78) else 0
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
        librarySearchQuery = ""
        keyboardSettingsOpen = false
        showLibrary(animate = true)
        renderRibbon()
        syncNowPlayingCardVisibility()
        refreshNowPlayingCard()
    }

    private fun closeLibrary() {
        if (!libraryOpen) return
        libraryOpen = false
        librarySearchQuery = ""
        closeCategoryFolder()
        renderRibbon()
        syncNowPlayingCardVisibility()
        refreshNowPlayingCard()
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
        categoryFolderView = null
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
        if (librarySearchQuery.isNotBlank()) {
            addView(librarySearchBar(), LinearLayout.LayoutParams.MATCH_PARENT, dp(46))
            addView(searchResultsGrid(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            addView(if (libraryGridMode) libraryGrid() else bentoGrid(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
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
            text = if (libraryGridMode) "Categories" else "Grid"
            gravity = Gravity.CENTER
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ink)
            includeFontPadding = false
            background = roundedPanel(Panel2, dp(15), Line)
            isClickable = true
            setOnClickListener {
                haptic(this)
                libraryGridMode = !libraryGridMode
                prefs().edit().putBoolean(LIBRARY_GRID_MODE_PREF, libraryGridMode).apply()
                showLibrary(animate = false)
            }
        }, LinearLayout.LayoutParams(dp(94), dp(30)).apply { marginEnd = dp(8) })
        addView(TextView(context).apply {
            text = "X"; gravity = Gravity.CENTER; textSize = 14f; setTextColor(InkDim)
            background = roundedPanel(Panel2, dp(15), Line); isClickable = true
            setOnClickListener { haptic(this); closeLibrary() }
        }, LinearLayout.LayoutParams(dp(30), dp(30)))
    }

    private fun librarySearchBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(6), 0)
        background = roundedPanel(Panel2, dp(14), KeyEdge)
        alpha = 0f
        translationY = -dp(6).toFloat()
        post { animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start() }
        addView(TextView(context).apply {
            text = "S"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Accent)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(6) })
        addView(TextView(context).apply {
            text = "${librarySearchQuery}|"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Ink)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = "X"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(InkDim)
            includeFontPadding = false
            isClickable = true
            setOnClickListener {
                haptic(this)
                librarySearchQuery = ""
                closeCategoryFolder()
                showLibrary(animate = false)
                renderRibbon()
            }
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
    }

    private fun searchResultsGrid(): View = ScrollView(this).apply {
        val results = librarySearchResults()
        if (results.isEmpty()) {
            addView(TextView(context).apply {
                text = "No apps match \"$librarySearchQuery\""
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(InkDim)
                includeFontPadding = false
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))
            return@apply
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(8))
            results.map { it.toLibraryApp() }.chunked(4).forEachIndexed { rowIndex, rowItems ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    rowItems.forEachIndexed { columnIndex, app ->
                        val index = rowIndex * 4 + columnIndex
                        addView(resultTile(app, index == 0, index), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                    }
                    repeat(4 - rowItems.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(92)).apply { bottomMargin = dp(10) })
            }
        })
    }

    private fun resultTile(app: LibraryApp, isBest: Boolean, index: Int): View {
        val target = app.target
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            alpha = 0f
            scaleX = 0.9f
            scaleY = 0.9f
            translationY = dp(8).toFloat()
            setPadding(dp(3), dp(4), dp(3), dp(2))
            postDelayed({
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(260)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }, (index * 35L).coerceAtMost(280L))
            addView(FrameLayout(context).apply {
                elevation = dp(if (isBest) 8 else 3).toFloat()
                background = roundedPanel(Panel2, dp(15), if (isBest) Accent else Line)
                addView(ImageView(context).apply {
                    setImageDrawable(iconFor(app))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(TextView(context).apply {
                text = highlightedLabel(app.name, librarySearchQuery)
                textSize = 12.5f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(0xFFDFE2E7.toInt())
                includeFontPadding = false
                setPadding(0, dp(7), 0, 0)
            }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                haptic(this)
                openLibraryResult(target)
            }
        }
    }

    private fun bentoGrid(): View = ScrollView(this).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            libraryCategories().chunked(3).forEachIndexed { rowIndex, group ->
                addView(categoryBentoRow(group, rowIndex), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(184)).apply {
                    bottomMargin = dp(12)
                })
            }
        })
    }

    private fun categoryBentoRow(group: List<LibraryCategory>, rowIndex: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        group.getOrNull(0)?.let { category ->
            addView(categoryBentoCard(category, prominent = true, rowIndex * 3), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.28f).apply {
                marginEnd = dp(8)
            })
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            group.drop(1).take(2).forEachIndexed { index, category ->
                addView(categoryBentoCard(category, prominent = false, rowIndex * 3 + index + 1), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    if (index == 0) bottomMargin = dp(8)
                })
            }
            repeat(2 - group.drop(1).take(2).size) {
                addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun categoryBentoCard(category: LibraryCategory, prominent: Boolean, index: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        elevation = dp(if (prominent) 7 else 4).toFloat()
        alpha = 0f
        translationY = dp(8).toFloat()
        setPadding(dp(if (prominent) 14 else 11), dp(10), dp(if (prominent) 14 else 11), dp(10))
        background = roundedPanel(if (prominent) 0xFF202329.toInt() else 0xFF1A1D23.toInt(), dp(if (prominent) 24 else 18), Line)
        postDelayed({
            animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        }, (index * 40L).coerceAtMost(260L))
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(70).start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                    haptic(this)
                    showCategoryFolder(category)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                    true
                }
                else -> true
            }
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(category.accent)
                }
            }, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(8) })
            addView(TextView(context).apply {
                text = category.name
                textSize = if (prominent) 16f else 12.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(Ink)
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (prominent) {
            addView(TextView(context).apply {
                text = category.apps.take(4).joinToString("  ") { it.name }
                textSize = 11.5f
                setTextColor(InkDim)
                includeFontPadding = false
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(8), 0, 0)
            }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        addView(categoryMiniGrid(category.apps.take(if (prominent) 8 else 2), if (prominent) 4 else 2), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(if (prominent) 8 else 7)
        })
    }

    private fun categoryMiniGrid(items: List<LibraryApp>, columns: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        items.chunked(columns).forEachIndexed { rowIndex, rowItems ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                rowItems.forEach { app ->
                    addView(FrameLayout(context).apply {
                        elevation = dp(2).toFloat()
                        background = roundedPanel(Panel2, dp(9), Line)
                        addView(ImageView(context).apply {
                            setImageDrawable(iconFor(app))
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            adjustViewBounds = true
                            setPadding(dp(4), dp(4), dp(4), dp(4))
                        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(8) })
                }
                repeat(columns - rowItems.size) {
                    addView(View(context), LinearLayout.LayoutParams(dp(38), 1).apply { marginEnd = dp(8) })
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply {
                if (rowIndex > 0) topMargin = dp(5)
            })
        }
    }

    private fun showCategoryFolder(category: LibraryCategory) {
        closeCategoryFolder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            libraryView?.setRenderEffect(RenderEffect.createBlurEffect(10f, 10f, Shader.TileMode.CLAMP))
        }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xAA000000.toInt())
            isClickable = true
            alpha = 0f
            setOnClickListener {
                haptic(this)
                closeCategoryFolder()
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                elevation = dp(12).toFloat()
                setPadding(dp(16), dp(14), dp(16), dp(12))
                background = roundedPanel(0xFF181B21.toInt(), dp(24), brighten(Line))
                setOnClickListener { }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(View(context).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(category.accent)
                        }
                    }, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(8) })
                    addView(TextView(context).apply {
                        text = category.name
                        textSize = 18f
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        setTextColor(Ink)
                        includeFontPadding = false
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(context).apply {
                        text = "X"
                        textSize = 15f
                        gravity = Gravity.CENTER
                        setTextColor(InkDim)
                        includeFontPadding = false
                        isClickable = true
                        background = roundedPanel(Panel2, dp(16), Line)
                        setOnClickListener {
                            haptic(this)
                            closeCategoryFolder()
                        }
                    }, LinearLayout.LayoutParams(dp(34), dp(34)))
                }, LinearLayout.LayoutParams.MATCH_PARENT, dp(38))
                addView(ScrollView(context).apply {
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, dp(12), 0, 0)
                        addView(tileGrid(category.apps, 4))
                    })
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
            })
        }
        categoryFolderView = overlay
        contentFrame.addView(overlay, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        overlay.post {
            overlay.animate().alpha(1f).setDuration(170).setInterpolator(DecelerateInterpolator()).start()
            overlay.getChildAt(0)?.apply {
                scaleX = 0.96f
                scaleY = 0.96f
                translationY = dp(10).toFloat()
                animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(210).setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    private fun closeCategoryFolder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            libraryView?.setRenderEffect(null)
        }
        val closing = categoryFolderView ?: return
        contentFrame.removeView(closing)
        categoryFolderView = null
    }

    private fun categoryCard(category: LibraryCategory): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        elevation = dp(4).toFloat()
        background = roundedPanel(Panel, dp(20), Line)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(category.accent)
                }
            }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(7) })
            addView(TextView(context).apply {
                text = category.name.uppercase(Locale.US)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.12f
                setTextColor(InkDim)
                includeFontPadding = false
            })
        }, LinearLayout.LayoutParams.MATCH_PARENT, dp(20))
        addView(tileGrid(category.apps, 4), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
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
                if (target.kind == PaneKind.MUSIC || target.packageName == null) openHere(target) else openExternal(target)
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
            return
        }
        val visibleMessages = messages.take(4)
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
            addView(TextView(context).apply {
                text = who; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ink); includeFontPadding = false
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.48f))
            addView(TextView(context).apply { text = "  $preview"; textSize = 11f; maxLines = 1; setTextColor(InkDim); includeFontPadding = false },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.52f))
        }
    }

    // ── Keyboard ─────────────────────────────────────────────────────────────

    private fun keyboard(): View {
        val overlayLayer = FrameLayout(this)
        predictionOverlay.overlayLayer = overlayLayer

        val swipeLayout = SwipeKeyboardLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(0xFF000000.toInt())
            setPadding(dp(7), keyboardTopPadding(), dp(7), keyboardBottomPadding())

            searchHintView = TextView(context).apply {
                textSize = 16f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setTextColor(Ink)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.START
                setPadding(dp(12), 0, dp(12), 0)
            }
            addView(searchHintView, LinearLayout.LayoutParams.MATCH_PARENT, dp(44))

            suggestionBarView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), 0, dp(2), dp(4))
            }
            addView(suggestionBarView, LinearLayout.LayoutParams.MATCH_PARENT, dp(32))

            if (keyboardSettingsOpen) addView(keyboardSettings(), LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (numberPadOpen) {
                addKeyRow(listOf("1", "2", "3"))
                addKeyRow(listOf("4", "5", "6"))
                addKeyRow(listOf("7", "8", "9"))
                addKeyRow(listOf("abc", "0", "back", "enter"))
            } else {
                addKeyRow("qwertyuiop".map { it.toString() })
                addKeyRow("asdfghjkl".map { it.toString() }, dp(18))
                addKeyRow(listOf("shift") + "zxcvbnm".map { it.toString() } + listOf("back"), dp(8))
                addKeyRow(listOf("123", "mic", "clicks", "space", "period", "enter"), dp(15))
            }
        }

        return FrameLayout(this).apply {
            addView(swipeLayout, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(overlayLayer, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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

            addView(settingAction("CLICKS SETTINGS") {
                keyboardSettingsOpen = false
                openHere(clicksSettingsTarget())
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

    private fun settingAction(label: String, onClick: TextView.() -> Unit): View {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER_VERTICAL
            textSize = 10f
            letterSpacing = 0.12f
            typeface = Typeface.MONOSPACE
            setTextColor(InkDim)
            setPadding(0, dp(8), 0, 0)
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun LinearLayout.addKeyRow(labels: List<String>, horizontalInset: Int = 0) {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(horizontalInset, 0, horizontalInset, 0)
            labels.forEach { label ->
                val weight = when (label) {
                    "space" -> 3.65f
                    "enter" -> if (numberPadOpen) 1f else 0.82f
                    "period" -> 0.86f
                    "clicks" -> 1.55f
                    "123", "mic" -> 1.02f
                    "back", "shift", "abc" -> 1.02f
                    else -> 1f
                }
                if (label == "enter") {
                    addView(key(label), LinearLayout.LayoutParams(goKeySize(), goKeySize()).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        marginStart = dp(4)
                    })
                } else {
                    addView(key(label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight))
                }
            }
        }, LinearLayout.LayoutParams.MATCH_PARENT, keyRowHeight())
    }

    private fun key(label: String): TextView {
        val isLetter = label.length == 1 && label[0].isLetter()
        return (if (isLetter) DynamicFlickKeyView(this) else TextView(this)).apply {
            text = keyLabel(label)
            gravity = Gravity.CENTER
            textSize = keyTextSize(label)
            typeface = Typeface.create("sans-serif", if (label == "shift" || label == "enter") Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(keyTextColor(label))
            isClickable = true
            if (label == "enter") background = goKeyBackground(goKeyColor)

            var touchDownX = 0f; var touchDownY = 0f
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownX = event.x; touchDownY = event.y
                        v.background = when (label) {
                            "enter" -> goKeyBackground(brighten(goKeyColor))
                            else -> keyBackground(KeyHighlight)
                        }
                        keyHaptic(label)
                        keyPreviewManager.show(v, label)
                    }
                    MotionEvent.ACTION_UP -> {
                        v.background = when (label) {
                            "enter" -> goKeyBackground(goKeyColor)
                            else -> null
                        }
                        val flick = flickDetector.classify(touchDownX, touchDownY, event.x, event.y)
                        if (flick == FlickDirection.UP) {
                            val prediction = (keyViews[label] as? DynamicFlickKeyView)?.upWordHint
                                ?: predictionOverlay.predictionFor(label)
                            if (prediction != null) {
                                keyHaptic("space")
                                acceptSuggestion(prediction)
                                return@setOnTouchListener true
                            }
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> v.background = when (label) {
                        "enter" -> goKeyBackground(goKeyColor)
                        else -> null
                    }
                }
                false
            }
            setOnClickListener {
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
        "shift" -> "↑"
        "mic" -> "🎤"
        "abc" -> "ABC"
        "123" -> "123"
        else -> if (label.length == 1) {
            if (numberPadOpen || shiftState == ShiftState.OFF) label else label.uppercase(Locale.US)
        } else label
    }

    // ── Suggestions ──────────────────────────────────────────────────────────

    private fun updateSuggestionBar() {
        if (!::suggestionBarView.isInitialized) return
        predictionOverlay.update(suggestions, keyBounds)
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
        val pane = openPane
        if (pane?.kind == PaneKind.CHAT) {
            val text = composeText.trimEnd()
            val lastSpace = text.lastIndexOf(' ')
            composeText = (if (lastSpace < 0) "" else text.substring(0, lastSpace + 1)) + word + " "
            updateAutoCapState(); updateKeyLabels()
            renderPaneContent(pane)
        } else {
            val text = query.trimEnd()
            val lastSpace = text.lastIndexOf(' ')
            query = (if (lastSpace < 0) "" else text.substring(0, lastSpace + 1)) + word + " "
        }
        suggestions = emptyList(); updateSuggestionBar()
        renderRibbon()
    }

    private fun scheduleSpellCheck() {
        suggestDebounce?.let { handler.removeCallbacks(it) }
        val word = currentWordInCompose()
        if (word.length < 2) {
            if (suggestions.isNotEmpty()) { suggestions = emptyList(); updateSuggestionBar() }
            liveRouter.onTextChanged("")
            return
        }
        liveRouter.onTextChanged(word)
        val r = Runnable {
            lastSuggestWord = word
            val localSuggs = predictionEngine.getSuggestions(word, 3)
            if (localSuggs.isNotEmpty()) {
                suggestions = localSuggs; updateSuggestionBar()
            }
            spellChecker?.getSuggestions(TextInfo(word), 5)
        }
        suggestDebounce = r
        handler.postDelayed(r, 160)
    }

    private fun currentWordInCompose(): String {
        val text = (if (openPane?.kind == PaneKind.CHAT) composeText else query).trimEnd()
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
        if (keyViews.isEmpty()) return
        keyBounds.clear()
        val loc = IntArray(2)
        keyViews.forEach { (label, view) ->
            if (view.width > 0 && view.height > 0) {
                view.getLocationOnScreen(loc)
                keyBounds[label] = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
            }
        }
        spatialScorer.setKeys(keyBounds)
        val overlayLoc = IntArray(2)
        predictionOverlay.overlayLayer?.getLocationOnScreen(overlayLoc)
        predictionOverlay.rootScreenY = overlayLoc[1]
        predictionOverlay.update(suggestions, keyBounds)
        updateGlideLayout()
    }

    private fun loadGlideWords() {
        Thread {
            runCatching {
                val clf = StatisticalGlideTypingClassifier()
                val words = mutableListOf<String>()
                val counts = mutableMapOf<String, Long>()
                assets.open("dict/en_wordlist.txt").bufferedReader().forEachLine { line ->
                    val sp = line.trim().split(" ")
                    if (sp.size >= 2) {
                        val w = sp[0].lowercase()
                        if (w.length in 2..20 && w.all { it.isLetter() }) {
                            words.add(w)
                            counts[w] = sp[1].toLongOrNull() ?: 1L
                        }
                    }
                }
                val maxCount = counts.values.maxOrNull() ?: 1L
                val freqs = counts.mapValues { it.value.toFloat() / maxCount }
                clf.setWordData(words, freqs)
                val freqMap = freqs.mapValues { it.value }
                handler.post {
                    glideClassifier = clf
                    predictionEngine = PredictionEngine(freqMap)
                    updateGlideLayout()
                }
            }
        }.start()
    }

    private fun updateGlideLayout() {
        val clf = glideClassifier ?: return
        if (keyBounds.isEmpty()) return
        val keyInfos = keyBounds.mapNotNull { (label, rect) ->
            if (label.length != 1) return@mapNotNull null
            KeyInfo(
                char = label[0],
                centerX = rect.exactCenterX(),
                centerY = rect.exactCenterY(),
                width = rect.width().toFloat(),
                height = rect.height().toFloat()
            )
        }
        clf.setLayout(keyInfos)
    }

    private fun keyAtPoint(rawX: Float, rawY: Float): String? {
        if (keyBounds.isEmpty()) {
            val x = rawX.toInt(); val y = rawY.toInt()
            return keyBounds.entries.firstOrNull { (_, r) -> r.contains(x, y) }?.key
        }
        return spatialScorer.bestKey(rawX, rawY)
    }

    private fun handleGlideResult(results: List<String>) {
        haptic(contentFrame)
        val topWord = results[0]
        val pane = openPane
        if (pane?.kind == PaneKind.CHAT) {
            val trimmed = composeText.trimEnd()
            val lastSpace = trimmed.lastIndexOf(' ')
            composeText = (if (lastSpace < 0) "" else trimmed.substring(0, lastSpace + 1)) + topWord + " "
            updateAutoCapState(); updateKeyLabels()
            renderPaneContent(pane)
        } else {
            val trimmed = query.trimEnd()
            val lastSpace = trimmed.lastIndexOf(' ')
            query = (if (lastSpace < 0) "" else trimmed.substring(0, lastSpace + 1)) + topWord + " "
        }
        suggestions = results.take(3)
        updateSuggestionBar()
        renderRibbon()
    }

    private fun handleSwipeFallback(keys: List<String>) {
        haptic(contentFrame)
        val rawWord = keys.joinToString("")
        val pane = openPane
        if (pane?.kind == PaneKind.CHAT) {
            composeText += rawWord
            scheduleSpellCheck()
            renderPaneContent(pane)
        } else {
            query += rawWord
            scheduleSpellCheck()
        }
        renderRibbon()
    }

    inner class SwipeKeyboardLayout(context: Context) : LinearLayout(context) {
        private var startRawX = 0f
        private var startRawY = 0f
        private var tracking = false
        private val traced = mutableListOf<String>()
        private val trailLocal = mutableListOf<Pair<Float, Float>>()
        private var screenX = 0f
        private var screenY = 0f
        private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Accent
            strokeWidth = 8f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            alpha = 150
        }
        private val trailPath = Path()

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (tracking && trailLocal.size > 1) {
                trailPath.reset()
                trailPath.moveTo(trailLocal[0].first, trailLocal[0].second)
                for (i in 1 until trailLocal.size) trailPath.lineTo(trailLocal[i].first, trailLocal[i].second)
                canvas.drawPath(trailPath, trailPaint)
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX; startRawY = ev.rawY
                    tracking = false; traced.clear(); trailLocal.clear()
                    val loc = IntArray(2); getLocationOnScreen(loc)
                    screenX = loc[0].toFloat(); screenY = loc[1].toFloat()
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) {
                        if (abs(ev.rawX - startRawX) > dp(10) || abs(ev.rawY - startRawY) > dp(10)) {
                            tracking = true
                            keyAtPoint(startRawX, startRawY)?.let { k -> if (k.length == 1) traced.add(k) }
                            trailLocal.add(startRawX - screenX to startRawY - screenY)
                            glideClassifier?.addGesturePoint(startRawX, startRawY)
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
                    trailLocal.add(ev.rawX - screenX to ev.rawY - screenY)
                    glideClassifier?.addGesturePoint(ev.rawX, ev.rawY)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    // Swipe-left = whole-word delete (must be dominant horizontal, not a glide word path)
                    if (flickDetector.isLeftSwipe(startRawX, startRawY, ev.rawX, ev.rawY) && traced.size <= 2) {
                        tracking = false; traced.clear(); trailLocal.clear(); invalidate()
                        glideClassifier?.clear()
                        keyHaptic("back")
                        deleteWord()
                        return true
                    }
                    val clf = glideClassifier
                    val t = traced.toList()
                    tracking = false; traced.clear(); trailLocal.clear(); invalidate()
                    if (clf != null && clf.hasEnoughPoints) {
                        Thread {
                            val results = clf.getSuggestions(3)
                            clf.clear()
                            handler.post {
                                if (results.isNotEmpty()) handleGlideResult(results)
                                else if (t.size >= 3) handleSwipeFallback(t)
                            }
                        }.start()
                    } else {
                        clf?.clear()
                        if (t.size >= 3) handleSwipeFallback(t)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    tracking = false; traced.clear(); trailLocal.clear()
                    glideClassifier?.clear(); invalidate()
                }
            }
            return true
        }
    }

    // ── Key handling ─────────────────────────────────────────────────────────

    private fun handleKey(label: String) {
        if (libraryOpen && openPane == null) {
            handleLibrarySearchKey(label)
            return
        }
        val pane = openPane
        if (pane?.kind == PaneKind.CHAT) { handleChatKey(label, pane); return }

        when (label) {
            "back" -> {
                query = query.dropLast(1)
                scheduleSpellCheck()
            }
            "space" -> {
                val now = System.currentTimeMillis()
                if (now - lastSpaceMs < 500 && query.isNotEmpty() && query.last() == ' ') {
                    query = query.dropLast(1) + ". "
                    suggestions = emptyList(); updateSuggestionBar()
                } else {
                    val words = query.trimEnd().split(" ")
                    if (words.size >= 2) ngramRepo.recordWord(words.last(), words[words.size - 2])
                    query += " "
                    suggestions = emptyList(); updateSuggestionBar()
                }
                lastSpaceMs = now
            }
            "period" -> { query += "."; suggestions = emptyList(); updateSuggestionBar() }
            "123" -> { numberPadOpen = true; query = ""; ensureContactsPermission(); render(); return }
            "abc" -> { numberPadOpen = false; query = ""; render(); return }
            "clicks" -> { keyboardSettingsOpen = !keyboardSettingsOpen; query = ""; render(); return }
            "enter" -> {
                if (numberPadOpen) {
                    val dialUri = searchContacts(query).firstOrNull()?.target?.deepLinkUri ?: if (query.isNotBlank()) "tel:$query" else null
                    dialUri?.let { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(it))) }
                } else {
                    filteredRibbonEntries().firstOrNull()?.let { if (it.target.kind == PaneKind.MUSIC || it.target.packageName == null) openHere(it.target) else openExternal(it.target) }
                }
            }
            "mic" -> { startVoiceInput(); return }
            else -> {
                val char = if (shiftState != ShiftState.OFF) label.uppercase(Locale.US) else label
                query += char
                if (shiftState == ShiftState.ONCE) { shiftState = ShiftState.OFF; updateKeyLabels() }
                scheduleSpellCheck()
            }
        }
        renderRibbon()
    }

    private fun handleLibrarySearchKey(label: String) {
        if (categoryFolderView != null) {
            if (label == "clicks" || label == "back") closeCategoryFolder()
            return
        }
        when (label) {
            "back" -> librarySearchQuery = librarySearchQuery.dropLast(1)
            "space" -> if (librarySearchQuery.isNotBlank()) librarySearchQuery += " "
            "period" -> if (librarySearchQuery.isNotBlank()) librarySearchQuery += "."
            "enter" -> {
                librarySearchResults().firstOrNull()?.let { openLibraryResult(it.toPaneTarget()) }
                return
            }
            "clicks" -> {
                if (librarySearchQuery.isNotBlank()) {
                    librarySearchQuery = ""
                    showLibrary(animate = false)
                    renderRibbon()
                } else {
                    closeLibrary()
                }
                return
            }
            "mic" -> return
            else -> {
                if (label.length != 1) return
                val char = if (shiftState != ShiftState.OFF) label.uppercase(Locale.US) else label.lowercase(Locale.US)
                librarySearchQuery += char
                if (shiftState == ShiftState.ONCE) {
                    shiftState = ShiftState.OFF
                    updateKeyLabels()
                }
            }
        }
        showLibrary(animate = false)
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
            "123" -> { numberPadOpen = true; ensureContactsPermission(); render(); return }
            "abc" -> { numberPadOpen = false; render(); return }
            "clicks" -> { keyboardSettingsOpen = !keyboardSettingsOpen; render(); return }
            "enter" -> postComposeBubble(pane)
            "mic" -> { startVoiceInput(); return }
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
            composeText = WordBoundaryDeleter.deleteWord(composeText)
            updateAutoCapState(); updateKeyLabels()
            suggestions = emptyList(); updateSuggestionBar()
            renderPaneContent(pane); renderRibbon()
            return
        }
        query = WordBoundaryDeleter.deleteWord(query)
        suggestions = emptyList(); updateSuggestionBar()
        renderRibbon()
    }

    // ── Pane / navigation ────────────────────────────────────────────────────

    private fun openHere(target: PaneTarget) {
        closeCategoryFolder()
        libraryOpen = false
        librarySearchQuery = ""
        libraryView?.let { contentFrame.removeView(it) }
        libraryView = null
        keyboardSettingsOpen = false; query = ""; composeText = ""
        shiftState = ShiftState.ONCE; suggestions = emptyList()
        updateKeyLabels(); updateSuggestionBar()
        openPane = target; showPane(target, animate = true); renderRibbon()
        syncNowPlayingCardVisibility()
        refreshNowPlayingCard()
    }

    private fun openLibraryResult(target: PaneTarget) {
        closeCategoryFolder()
        libraryOpen = false
        librarySearchQuery = ""
        libraryView?.let { contentFrame.removeView(it) }
        libraryView = null
        renderRibbon()
        syncNowPlayingCardVisibility()
        refreshNowPlayingCard()
        if (target.kind == PaneKind.MUSIC || target.packageName == null) openHere(target) else openExternal(target)
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
        updateKeyLabels(); updateSuggestionBar(); renderRibbon(); syncNowPlayingCardVisibility(); refreshNowPlayingCard()
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
        if (target.kind == PaneKind.MUSIC) return musicPane()
        return ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
                when (target.kind) {
                    PaneKind.CHAT -> chatLines(target).forEach { addView(bubble(it)) }
                    PaneKind.MAIL -> mailLines(target).forEach { addView(listRow(it.first, it.second)) }
                    PaneKind.MUSIC -> Unit
                    PaneKind.SETTINGS -> settingsPaneContent(this)
                    PaneKind.LIST -> {
                        addView(listRow("Inbox", "now"))
                        addView(listRow(target.preview.ifBlank { "Open ${target.name}" }, "item"))
                        addView(listRow("Open in real app from long press", "external"))
                    }
                }
            })
        }
    }

    private fun musicPane(): View {
        return ComposeView(this).apply {
            setBackgroundColor(Panel)
            setContent {
                val media by mediaSessionSource.nowPlaying.collectAsState()
                MusicPlayer(
                    media = media,
                    onOpenSource = {
                        haptic(this@apply)
                        mediaSessionSource.openSourceApp()
                    },
                    onTogglePlayPause = {
                        haptic(this@apply)
                        mediaSessionSource.togglePlayPause()
                    },
                    onPrevious = {
                        haptic(this@apply)
                        mediaSessionSource.skipToPrevious()
                    },
                    onNext = {
                        haptic(this@apply)
                        mediaSessionSource.skipToNext()
                    }
                )
            }
        }
    }

    private fun settingsPaneContent(parent: LinearLayout) {
        parent.addView(mono("LAUNCHER", 10f, Accent).apply {
            letterSpacing = 0.22f
            setPadding(0, 0, 0, dp(8))
        })
        parent.addView(settingAction("ICON PACK   ${activeIconPackLabel().uppercase(Locale.US)}") {
            haptic(this)
            showIconPackMenu(this)
        }, LinearLayout.LayoutParams.MATCH_PARENT, dp(32))

        parent.addView(mono("INTEGRATIONS", 10f, Accent).apply {
            letterSpacing = 0.22f
            setPadding(0, dp(18), 0, dp(8))
        })
        parent.addView(integrationRow("Spotify", "com.spotify.music", SPOTIFY_INTEGRATION_PREF))
        parent.addView(integrationRow("Apple Music", "com.apple.android.music", APPLE_MUSIC_INTEGRATION_PREF))
        parent.addView(mono("PLAYING MEDIA IS THE FAST PATH: CLICKS CAN READ THE PHONE'S ACTIVE MEDIA SESSION, THEN API CONNECTORS CAN ADD LIBRARY/PLAYLIST SYNC LATER.", 8.5f, InkDim).apply {
            setPadding(0, dp(14), 0, 0)
            letterSpacing = 0.08f
        })
    }

    private fun integrationRow(name: String, packageName: String, prefKey: String): View {
        val mode = prefs().getString(prefKey, INTEGRATION_OFF) ?: INTEGRATION_OFF
        val installed = isInstalled(packageName)
        val state = when (mode) {
            INTEGRATION_MEDIA -> "MEDIA SESSION"
            INTEGRATION_API -> "API"
            else -> if (installed) "READY" else "NOT INSTALLED"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(12), dp(2), dp(12))
            background = border(Line)
            isClickable = true
            setOnClickListener {
                haptic(this)
                showIntegrationMenu(this, name, packageName, prefKey)
            }
            addView(TextView(context).apply {
                text = name
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ink)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(mono(state, 10f, if (mode == INTEGRATION_OFF) InkDim else Accent2).apply {
                letterSpacing = 0.08f
            })
        }
    }

    private fun showIntegrationMenu(anchor: View, name: String, packageName: String, prefKey: String) {
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(Panel2)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Line)
            }
        }
        val popup = PopupWindow(menu, dp(228), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
        }
        menu.addView(menuItem("Use playing media", true) {
            prefs().edit().putString(prefKey, INTEGRATION_MEDIA).apply()
            popup.dismiss()
            renderPaneContent(clicksSettingsTarget())
        })
        menu.addView(menuItem("Connect account", true) {
            popup.dismiss()
            Toast.makeText(this, "$name OAuth needs developer credentials before account sync can run", Toast.LENGTH_LONG).show()
        })
        menu.addView(menuItem("Open $name", isInstalled(packageName)) {
            popup.dismiss()
            packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
        })
        menu.addView(menuItem("Disconnect", true) {
            prefs().edit().putString(prefKey, INTEGRATION_OFF).apply()
            popup.dismiss()
            renderPaneContent(clicksSettingsTarget())
        })
        popup.showAsDropDown(anchor, -dp(64), -anchor.height)
    }

    private fun activeIconPackLabel(): String {
        val active = prefs().getString(ACTIVE_ICON_PACK_PREF, null) ?: return "System"
        return iconPacks().firstOrNull { it.packageName == active }?.name ?: "System"
    }

    private fun showIconPackMenu(anchor: View) {
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(Panel2)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Line)
            }
        }
        val popup = PopupWindow(menu, dp(244), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
        }
        menu.addView(menuItem("System icons", true) {
            prefs().edit().remove(ACTIVE_ICON_PACK_PREF).apply()
            popup.dismiss()
            renderPaneContent(clicksSettingsTarget())
            renderRibbon()
        })
        iconPacks().forEach { pack ->
            menu.addView(menuItem(pack.name, true) {
                prefs().edit().putString(ACTIVE_ICON_PACK_PREF, pack.packageName).apply()
                popup.dismiss()
                renderPaneContent(clicksSettingsTarget())
                renderRibbon()
            })
        }
        if (iconPacks().isEmpty()) {
            menu.addView(menuItem("No icon packs installed", false) {})
        }
        popup.showAsDropDown(anchor, -dp(80), -anchor.height)
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

    private fun mailLines(target: PaneTarget): List<Pair<String, String>> {
        val source = target.packageName?.let { pkg -> apps.firstOrNull { it.packageName == pkg }?.label } ?: "Mail"
        return listOf(
            "From" to target.name,
            "Preview" to target.preview.ifBlank { "No preview available" },
            "Source" to source
        )
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
        filteredRibbonEntries().forEachIndexed { index, app -> ribbonView.addView(ribbonButton(app, index == 0)) }
        val pane = openPane
        val typedText = when {
            pane?.kind == PaneKind.CHAT -> composeText
            else -> query
        }
        val hint = when {
            libraryOpen -> "APP LIBRARY  ·  TAP TOP BUTTON FOR CATEGORY / GRID"
            keyboardSettingsOpen -> "KEYBOARD SETTINGS"
            numberPadOpen && typedText.isBlank() -> "TYPE NUMBER  ·  CONTACTS APPEAR ABOVE  ·  GO = DIAL"
            pane?.kind == PaneKind.CHAT && typedText.isBlank() -> "→ ${pane.name.uppercase(Locale.US)}"
            else -> "SEARCHING APPS  ·  CLICKS FOR SETTINGS"
        }
        if (typedText.isNotBlank() && !libraryOpen && !keyboardSettingsOpen) {
            searchHintView.setTextColor(Ink)
            searchHintView.text = typedText + "│"
        } else {
            searchHintView.setTextColor(0xFF55585F.toInt())
            searchHintView.text = hint
        }
    }

    private fun ribbonButton(app: RibbonEntry, isFirst: Boolean): View {
        val target = app.target
        return TextView(this).apply {
            text = app.label; gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            textSize = 14.5f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); letterSpacing = 0.02f
            setTextColor(if (isFirst && query.isNotBlank()) Ink else InkDim)
            setPadding(dp(2), dp(9), dp(9), dp(9))
            background = if (isFirst && query.isNotBlank()) highlight(app.accent) else null
            isClickable = true
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().translationX(-dp(3).toFloat()).setDuration(70).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().translationX(0f).setDuration(110).start()
                }
                false
            }
            setOnClickListener { haptic(this); if (target.kind == PaneKind.MUSIC || target.packageName == null) openHere(target) else openExternal(target) }
            setOnLongClickListener { haptic(this); showOpenMenu(this, target); true }
        }
    }

    // ── Menus ────────────────────────────────────────────────────────────────

    private fun showOpenMenu(anchor: View, target: PaneTarget) {
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply { setColor(Panel2); cornerRadius = dp(8).toFloat(); setStroke(dp(1), Line) }
        }
        val popup = PopupWindow(menu, dp(204), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply { isOutsideTouchable = true }
        val canOpenHere = target.kind != PaneKind.LIST || target.packageName == null
        menu.addView(menuItem("Open here", canOpenHere) { popup.dismiss(); openHere(target) })
        menu.addView(menuItem("Open app", target.packageName != null) { popup.dismiss(); openExternal(target) })
        target.packageName?.let { packageName ->
            val onHome = isOnHome(packageName)
            menu.addView(menuItem(if (onHome) "Remove from home" else "Add to home", true) {
                setHomePresence(packageName, !onHome)
                popup.dismiss()
            })
            apps.firstOrNull { it.packageName == packageName }?.let { app ->
                menu.addView(menuItem("Change icon", true) {
                    popup.dismiss()
                    showIconMenu(anchor, app.toLibraryApp())
                })
            }
        }
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
        showKeyColorMenu(anchor, goKeyColor) { color ->
            goKeyColor = color
            prefs().edit().putInt(GO_KEY_COLOR_PREF, goKeyColor).apply()
        }
    }

    private fun showKeyColorMenu(anchor: View, selectedColor: Int, onSelect: (Int) -> Unit) {
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply { setColor(Panel2); cornerRadius = dp(8).toFloat(); setStroke(dp(1), Line) }
        }
        val popup = PopupWindow(menu, dp(152), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply { isOutsideTouchable = true }
        GO_COLORS.forEach { option ->
            menu.addView(TextView(this).apply {
                text = option.name; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER_VERTICAL
                setTextColor(option.color); setPadding(dp(10), dp(9), dp(10), dp(9))
                background = if (selectedColor == option.color) highlight(option.color) else null
                isClickable = true
                setOnClickListener { haptic(this); onSelect(option.color); popup.dismiss(); render() }
            })
        }
        popup.showAsDropDown(anchor, -dp(96), -dp(180))
    }

    // ── App loading ──────────────────────────────────────────────────────────

    private fun openExternal(target: PaneTarget) {
        target.deepLinkUri?.takeIf { it.startsWith("tel:") }?.let {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(it))); return
        }
        val packageName = target.packageName
        if (packageName == null) { openHere(target); return }
        try {
            if (target.deepLinkUri != null && isInstalled(packageName)) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.deepLinkUri)))
                return
            }
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                startActivity(launchIntent)
                return
            }
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

    private fun librarySearchResults(): List<AppEntry> {
        val q = librarySearchQuery.trim()
        if (q.isBlank()) return emptyList()
        return apps
            .filter { it.label.contains(q, ignoreCase = true) }
            .sortedWith { left, right ->
                val leftStarts = left.label.startsWith(q, ignoreCase = true)
                val rightStarts = right.label.startsWith(q, ignoreCase = true)
                when {
                    leftStarts != rightStarts -> if (leftStarts) -1 else 1
                    else -> collator.compare(left.label, right.label)
                }
            }
    }

    private fun searchContacts(digits: String): List<RibbonEntry> {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
        val clean = digits.filter { it.isDigit() }
        if (clean.isBlank()) return emptyList()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        val sel = "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        val cursor = contentResolver.query(uri, projection, sel, arrayOf("%$clean%"), ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME) ?: return emptyList()
        val results = mutableListOf<RibbonEntry>()
        val seen = mutableSetOf<String>()
        cursor.use {
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext() && results.size < 8) {
                val name = cursor.getString(nameIdx) ?: continue
                val number = cursor.getString(numIdx) ?: continue
                if (seen.add(name)) {
                    val target = PaneTarget("tel:$number", name, Accent2, PaneKind.LIST, null, "tel:${number.filter { it.isDigit() || it == '+' }}", number)
                    results.add(RibbonEntry(name.take(10), Accent2, target))
                }
            }
        }
        return results
    }

    private fun ensureContactsPermission() {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            contactsLauncher.launch(android.Manifest.permission.READ_CONTACTS)
        }
    }

    private fun filteredRibbonEntries(): List<RibbonEntry> {
        if (numberPadOpen) return searchContacts(query)
        if (query.isBlank()) return homeRibbonEntries()
        return apps
            .filter { app ->
                app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
            .take(8)
            .map { app -> RibbonEntry(app.shortName, app.brandColor, app.toPaneTarget()) }
            .ifEmpty {
                homeRibbonEntries().filter { entry ->
                    entry.label.contains(query, ignoreCase = true) ||
                        entry.target.name.contains(query, ignoreCase = true) ||
                        entry.target.packageName?.contains(query, ignoreCase = true) == true
                }
            }
    }

    private fun homeRibbonEntries(): List<RibbonEntry> {
        val hidden = hiddenHomePackages()
        val favoriteEntries = apps
            .filter { it.packageName in favoritePackages() && it.packageName !in hidden }
            .take(8)
            .map { app -> RibbonEntry(app.shortName, app.brandColor, app.toPaneTarget()) }
        return favoriteEntries.ifEmpty {
            apps
                .filterNot { it.packageName in hidden }
                .take(6)
                .map { app -> RibbonEntry(app.shortName, app.brandColor, app.toPaneTarget()) }
        }
    }

    private fun favoritePackages(): Set<String> = prefs().getStringSet(FAVORITE_APPS_PREF, emptySet()) ?: emptySet()

    private fun hiddenHomePackages(): Set<String> = prefs().getStringSet(HIDDEN_HOME_APPS_PREF, emptySet()) ?: emptySet()

    private fun isOnHome(packageName: String?) = packageName != null &&
        homeRibbonEntries().any { it.target.packageName == packageName }

    private fun setHomePresence(packageName: String, showOnHome: Boolean) {
        val favorites = favoritePackages().toMutableSet()
        val hidden = hiddenHomePackages().toMutableSet()
        if (showOnHome) {
            favorites.add(packageName)
            hidden.remove(packageName)
        } else {
            favorites.remove(packageName)
            hidden.add(packageName)
        }
        prefs().edit()
            .putStringSet(FAVORITE_APPS_PREF, favorites)
            .putStringSet(HIDDEN_HOME_APPS_PREF, hidden)
            .apply()
        renderRibbon()
        if (libraryOpen) showLibrary(animate = false)
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

    private fun HubMessage.toPaneTarget() = PaneTarget(
        "$packageName:$sender",
        sender,
        color,
        if (kind == HUB_KIND_EMAIL) PaneKind.MAIL else PaneKind.CHAT,
        packageName,
        null,
        preview
    )

    private fun clicksSettingsTarget() = PaneTarget("clicks-settings", "Clicks Settings", Accent, PaneKind.SETTINGS, null, null, "Integrations")

    private fun musicTarget() = PaneTarget("clicks-music", "Music", 0xFF57C98A.toInt(), PaneKind.MUSIC, null, null, "Now playing")

    private fun loadHubMessages(): List<HubMessage> {
        val raw = prefs().getString(HUB_MESSAGES_PREF, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(HubMessage(item.optString("sender", "Message").ifBlank { "Message" },
                    item.optString("preview", ""), item.optString("packageName", ""),
                    item.optString("kind", inferHubKind(item.optString("packageName", ""))),
                    item.optInt("color", Accent2)))
            }
        }
    }

    private fun inferHubKind(packageName: String): String {
        return if (packageName in EMAIL_PACKAGES) HUB_KIND_EMAIL else HUB_KIND_MESSAGE
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

    private fun highlightedLabel(label: String, match: String): SpannableString {
        val styled = SpannableString(label)
        val q = match.trim()
        if (q.isBlank()) return styled
        val start = label.lowercase(Locale.US).indexOf(q.lowercase(Locale.US))
        if (start < 0) return styled
        val end = (start + q.length).coerceAtMost(label.length)
        styled.setSpan(ForegroundColorSpan(Accent2), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        styled.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return styled
    }

    private fun roundedPanel(fill: Int, radius: Int, stroke: Int? = null) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(brighten(fill), fill)).apply {
            cornerRadius = radius.toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun libraryCategories(): List<LibraryCategory> {
        val grouped = apps.map { it.toLibraryApp() }.groupBy { app -> categoryNameFor(app.target.packageName) }
        val preferredOrder = listOf("Social", "Music & Audio", "Video", "Photos", "Maps", "Productivity", "Games", "News", "Tools", "Other")
        return preferredOrder.mapNotNull { name ->
            grouped[name]?.takeIf { it.isNotEmpty() }?.let { LibraryCategory(name, categoryAccent(name), it) }
        } + grouped.keys
            .filterNot { it in preferredOrder }
            .sortedWith(collator)
            .mapNotNull { name -> grouped[name]?.takeIf { it.isNotEmpty() }?.let { LibraryCategory(name, categoryAccent(name), it) } }
    }

    private fun categoryNameFor(packageName: String?): String {
        val category = packageName?.let { pkg ->
            runCatching { packageManager.getApplicationInfo(pkg, 0).category }.getOrNull()
        }
        return when (category) {
            android.content.pm.ApplicationInfo.CATEGORY_SOCIAL -> "Social"
            android.content.pm.ApplicationInfo.CATEGORY_AUDIO -> "Music & Audio"
            android.content.pm.ApplicationInfo.CATEGORY_VIDEO -> "Video"
            android.content.pm.ApplicationInfo.CATEGORY_IMAGE -> "Photos"
            android.content.pm.ApplicationInfo.CATEGORY_MAPS -> "Maps"
            android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
            android.content.pm.ApplicationInfo.CATEGORY_GAME -> "Games"
            android.content.pm.ApplicationInfo.CATEGORY_NEWS -> "News"
            else -> "Other"
        }
    }

    private fun categoryAccent(name: String) = when (name) {
        "Social" -> 0xFF5FD0C4.toInt()
        "Music & Audio" -> 0xFF8FD694.toInt()
        "Video" -> 0xFFFF8A4C.toInt()
        "Photos" -> 0xFF72D4FF.toInt()
        "Maps" -> 0xFF79D39D.toInt()
        "Productivity" -> Accent2
        "Games" -> 0xFFC4B5FF.toInt()
        "News" -> 0xFF54A9EB.toInt()
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
        app.target.packageName?.let { packageName ->
            val onHome = isOnHome(packageName)
            menu.addView(menuItem(if (onHome) "Remove from home" else "Add to home", true) {
                setHomePresence(packageName, !onHome)
                popup.dismiss()
            })
        }
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
                apps.forEach { candidate ->
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

    private fun goKeyBackground(fillColor: Int): GradientDrawable {
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(brighten(fillColor), fillColor)).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), brighten(fillColor))
        }
    }

    private fun keyRowHeight(): Int {
        val base = if (keyboardSettingsOpen) 44 else 56
        val growth = if (keyboardSettingsOpen) 8 else 20
        return dp(base + (keyboardSize * growth / 100))
    }

    private fun goKeySize() = dp(43 + (keyboardSize * 8 / 100))

    private fun hintBottomGap() = dp(2 + (keyboardSize * 2 / 100))
    private fun keyboardHeight() = dp(272 + keyboardSize * 80 / 100)
    private fun keyboardTopPadding() = dp(4)
    private fun keyboardBottomPadding() = dp(12)

    private fun keyTextSize(label: String): Float {
        if (numberPadOpen && label.length == 1 && label[0].isDigit()) return 26f + keyboardSize * 2f / 100f
        val base = when (label) { "shift" -> 24f; "space" -> 18f; "123", "mic", "clicks", "enter", "back", "period", "abc" -> 13.5f; else -> 20f }
        val growth = when (label) { "shift" -> 2.5f; "space" -> 2f; "123", "mic", "clicks", "enter", "back", "period", "abc" -> 1.5f; else -> 2.5f }
        return base + (keyboardSize * growth / 100f)
    }

    private fun keyTextColor(label: String) = when (label) {
        "enter" -> 0xFF180A06.toInt()
        "shift" -> when (shiftState) { ShiftState.OFF -> 0xFF7D8078.toInt(); ShiftState.ONCE -> Ink; ShiftState.LOCK -> Accent }
        "123", "mic", "clicks", "back", "period", "abc" -> 0xFF7D8078.toInt()
        else -> Ink
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun haptic(view: View) {
        if (!hapticsEnabled) return
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun keyHaptic(label: String) {
        if (!hapticsEnabled) return
        hapticEngine.tap(label)
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

    private fun systemStatusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    // ── Types ────────────────────────────────────────────────────────────────

    private enum class PaneKind { CHAT, MAIL, LIST, SETTINGS, MUSIC }
    private enum class ShiftState { OFF, ONCE, LOCK }

    private data class PaneTarget(val id: String, val name: String, val accent: Int, val kind: PaneKind,
        val packageName: String?, val deepLinkUri: String?, val preview: String)
    private data class AppEntry(val label: String, val shortName: String, val packageName: String, val componentName: ComponentName, val brandColor: Int)
    private data class LibraryCategory(val name: String, val accent: Int, val apps: List<LibraryApp>)
    private data class LibraryApp(val name: String, val accent: Int, val target: PaneTarget, val componentName: ComponentName?)
    private data class IconPack(val name: String, val packageName: String)
    private data class IconPackIcon(val packageName: String, val drawableName: String)
    private data class RibbonEntry(val label: String, val accent: Int, val target: PaneTarget)
    private data class HubMessage(val sender: String, val preview: String, val packageName: String, val kind: String, val color: Int)
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
        private const val LIBRARY_GRID_MODE_PREF = "library_grid_mode"
        private const val GO_KEY_COLOR_PREF = "go_key_color"
        private const val FAVORITE_APPS_PREF = "favorite_apps"
        private const val HIDDEN_HOME_APPS_PREF = "hidden_home_apps"
        private const val HUB_MESSAGES_PREF = "hub_messages"
        private const val SPOTIFY_INTEGRATION_PREF = "spotify_integration"
        private const val APPLE_MUSIC_INTEGRATION_PREF = "apple_music_integration"
        private const val INTEGRATION_OFF = "off"
        private const val INTEGRATION_MEDIA = "media"
        private const val INTEGRATION_API = "api"
        private const val ACTIVE_ICON_PACK_PREF = "active_icon_pack"
        private const val ICON_OVERRIDE_PREFIX = "icon_override_"
        private const val HUB_KIND_MESSAGE = "message"
        private const val HUB_KIND_EMAIL = "email"
        private const val PANE_BODY_TAG = "pane"
        private const val VOICE_REQUEST_CODE = 9001
        private const val CONTACTS_PERMISSION_CODE = 9002

        private val EMAIL_PACKAGES = setOf(
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            "com.yahoo.mobile.client.android.mail",
            "com.readdle.spark",
            "com.protonmail.android",
            "me.bluemail.mail"
        )

        private val GO_COLORS = listOf(
            ColorOption("ORANGE", Accent), ColorOption("AMBER", Accent2),
            ColorOption("TEAL", 0xFF5FD0C4.toInt()), ColorOption("BLUE", 0xFF54A9EB.toInt()),
            ColorOption("LILAC", 0xFFC4B5FF.toInt()), ColorOption("GREEN", 0xFF8FD694.toInt())
        )
    }

    private data class ColorOption(val name: String, val color: Int)
}
