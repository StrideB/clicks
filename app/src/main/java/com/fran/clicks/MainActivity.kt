package com.fran.clicks

import android.Manifest
import android.animation.ValueAnimator
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.location.Location
import android.location.LocationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.app.AlertDialog
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import com.fran.clicks.glide.KeyInfo
import com.fran.clicks.glide.StatisticalGlideTypingClassifier
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.os.Bundle
import android.speech.RecognizerIntent
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.InputType
import android.text.style.ForegroundColorSpan
import android.text.style.ScaleXSpan
import android.text.style.StyleSpan
import android.util.Xml
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Scroller
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.fran.clicks.keyboard.CustomHapticEngine
import com.fran.clicks.keyboard.DynamicFlickKeyView
import com.fran.clicks.keyboard.FlickDetector
import com.fran.clicks.keyboard.FlickDirection
import com.fran.clicks.keyboard.KeyPreviewManager
import com.fran.clicks.keyboard.LivePredictionRouter
import com.fran.clicks.keyboard.PredictionEngine
import com.fran.clicks.keyboard.PredictionOverlayManager
import com.fran.clicks.keyboard.SmsIngestionEngine
import com.fran.clicks.keyboard.SmsSeedingCoordinator
import com.fran.clicks.keyboard.SpatialScorer
import com.fran.clicks.keyboard.WordBoundaryDeleter
import com.fran.clicks.db.NgramRepository
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.URL
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.io.OutputStreamWriter
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SpellCheckerSession.SpellCheckerSessionListener {
    private val collator = Collator.getInstance()
    private var query = ""
    private var apps: List<AppEntry> = emptyList()
    private var keyboardSize = 0
    private var keyboardTheme = KEYBOARD_THEME_DEFAULT
    private var keyboardPlacement = KEYBOARD_PLACEMENT_DOCKED
    private var hapticsEnabled = true
    private var keyboardSettingsOpen = false
    private var goKeyColor = Accent
    private var messages: List<HubMessage> = emptyList()
    private var calendarEvents: List<CalendarEvent> = emptyList()
    private var openPane: PaneTarget? = null
    private var paneView: View? = null
    private var libraryOpen = false
    private var libraryGridMode = true
    private var libraryView: View? = null
    private var libraryContentArea: FrameLayout? = null
    private var categoryFolderView: View? = null
    private var widgetBoardView: View? = null
    private var widgetPickerView: View? = null
    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: AppWidgetHost
    private var pendingWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var pendingWidgetProvider: AppWidgetProviderInfo? = null
    private var librarySwipeStartX = 0f
    private var librarySwipeStartY = 0f
    private var librarySwipeTriggered = false
    private var librarySwipeBlockedByWidget = false
    private var stripSwipeStartX = 0f
    private var stripSwipeStartY = 0f
    private var stripSwipeTriggered = false
    private var paneSwipeStartX = 0f
    private var paneSwipeStartY = 0f
    private var paneSwipeTriggered = false
    private var paneSwipeFromDock = false
    private var iconPacksCache: List<IconPack>? = null
    private val iconPackMatchCache = mutableMapOf<String, IconPackIcon?>()
    private var composeText = ""
    private val chatLinesById = mutableMapOf<String, MutableList<ChatLine>>()

    private var shiftState = ShiftState.ONCE
    private var lastSpaceMs = 0L
    private var lastShiftTapMs = 0L
    private var suggestions: List<String> = emptyList()
    // Autocorrect undo — stores what was replaced so backspace immediately after reverts it
    private data class AutocorrectUndo(val original: String, val corrected: String)
    private var pendingAutocorrectUndo: AutocorrectUndo? = null
    // Cursor position within query/composeText (null = end)
    private var cursorPos: Int? = null
    // Gemini suggestions debounce
    private var geminiSuggestJob: kotlinx.coroutines.Job? = null
    private var pendingTypeToDoCommand: String? = null
    private val aiAnswersById = mutableMapOf<String, AiAnswerState>()
    private var spellChecker: SpellCheckerSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var suggestDebounce: Runnable? = null
    private var libraryRefreshDebounce: Runnable? = null
    private var lastSuggestWord = ""
    private val keyViews = mutableMapOf<String, TextView>()
    private val keyBounds = mutableMapOf<String, Rect>()
    private var glideClassifier: StatisticalGlideTypingClassifier? = null
    private var numberPadOpen = false
    private var symbolsOpen = false
    private lateinit var mediaSessionSource: MediaSessionSource
    private val mediaUiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var billingClient: com.android.billingclient.api.BillingClient? = null
    lateinit var spotifyAuth: SpotifyAuth
    lateinit var spotifyApi: SpotifyWebApi
    private var spotifyCachedRecent = listOf<SpotifyTrack>()
    private var spotifyCachedRecentArts = listOf<android.graphics.Bitmap?>()
    private var spotifyCachedPlaylists = listOf<SpotifyPlaylist>()
    private var spotifyCachedPlaylistArts = listOf<android.graphics.Bitmap?>()
    private var spotifyCachedTopTracks = listOf<SpotifyTrack>()
    private var spotifyCachedTopArts = listOf<android.graphics.Bitmap?>()
    private var spotifyCachedLikedSongs = listOf<SpotifyTrack>()
    private var spotifyCachedLikedArts = listOf<android.graphics.Bitmap?>()
    private var compactLibraryScrollRef: ScrollView? = null
    private var compactLibraryTargetY = 0
    private var volumeHudView: LinearLayout? = null
    private var volumeHudFill: View? = null
    private val volumeHudHideRunnable = Runnable { hideVolumeHud() }
    private var spotifyCompactOverlay: View? = null
    private var spotifyFullLibraryDismiss: (() -> Unit)? = null
    private val fallbackIconCache = android.util.LruCache<String, Drawable>(64)
    private var lastAppsLoadMs = 0L
    private var lastHubLoadMs = 0L
    private var lastCalendarLoadMs = 0L

    private lateinit var contactsLauncher: ActivityResultLauncher<String>
    private lateinit var smsPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var calendarPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var weatherPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var photosPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var photoTrashLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var hapticEngine: CustomHapticEngine
    private lateinit var spatialScorer: SpatialScorer
    private lateinit var keyPreviewManager: KeyPreviewManager
    private lateinit var predictionEngine: PredictionEngine
    private lateinit var ngramRepo: NgramRepository
    private lateinit var flickDetector: FlickDetector
    private lateinit var predictionOverlay: PredictionOverlayManager
    private lateinit var liveRouter: LivePredictionRouter
    private var wordlistFrequencies: Map<String, Float> = emptyMap()
    private lateinit var weatherIconView: AnimatedWeatherIconView
    private lateinit var weatherTempView: TextView
    private lateinit var weatherMetaView: TextView
    private lateinit var weatherFeelsView: TextView
    private lateinit var weatherStatsView: TextView
    private lateinit var hubView: LinearLayout
    private lateinit var ribbonView: LinearLayout
    private lateinit var favoritesDockView: LinearLayout
    private lateinit var homeGridView: FrameLayout
    private lateinit var rootView: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private lateinit var nowPlayingCardView: ComposeView
    private lateinit var keyboardDockView: FrameLayout
    private lateinit var searchHintView: TextView
    private var zeissButtonView: ZeissCameraButtonView? = null
    private lateinit var hintBar: LinearLayout
    private var musicProgressBar: View? = null
    private var musicProgressHandler: android.os.Handler? = null
    private var musicProgressRunnable: Runnable? = null
    private var homeEditChipView: TextView? = null
    private var homeEditMode = false
    private var pendingTrashPhotoId: Long? = null
    private val homeTileViews = mutableMapOf<String, FrameLayout>()
    private lateinit var sizeValueView: TextView
    private lateinit var suggestionBarView: LinearLayout
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == HUB_MESSAGES_PREF) {
            runOnUiThread { refreshHubMessagesFromPrefs() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contactsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            val pending = pendingTypeToDoCommand
            pendingTypeToDoCommand = null
            if (pending != null) executeTypeToDoCommand(pending) else renderRibbon()
        }
        smsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) triggerSmsSeeding()
        }
        calendarPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            calendarEvents = loadCalendarEvents()
            syncNowPlayingCardVisibility()
            refreshNowPlayingCard()
        }
        weatherPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) refreshWeather(force = true) else showWeatherNeedsPermission()
        }
        photosPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                zeissButtonView?.animateShutterClosed()
                openHere(photosTarget())
            }
            else Toast.makeText(this, "Photo access is needed for ZEISS Optics.", Toast.LENGTH_SHORT).show()
        }
        photoTrashLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val deletedId = pendingTrashPhotoId
            pendingTrashPhotoId = null
            if (result.resultCode == RESULT_OK && deletedId != null) {
                removeFavoritePhoto(deletedId)
                if (selectedPhotoId() == deletedId) prefs().edit().remove(PHOTO_SELECTED_ID_PREF).apply()
                Toast.makeText(this, "Moved to Photos trash.", Toast.LENGTH_SHORT).show()
                if (openPane?.kind == PaneKind.PHOTOS) showPane(photosTarget(), animate = false)
                refreshKeyboardDock()
            }
        }
        appWidgetManager = AppWidgetManager.getInstance(this)
        appWidgetHost = AppWidgetHost(this, WIDGET_HOST_ID)
        appWidgetHost.startListening()
        keyboardSize = prefs().getInt(KEYBOARD_SIZE_PREF, 28)
        keyboardTheme = prefs().getString(KEYBOARD_THEME_PREF, KEYBOARD_THEME_DEFAULT) ?: KEYBOARD_THEME_DEFAULT
        keyboardPlacement = prefs().getString(KEYBOARD_PLACEMENT_PREF, KEYBOARD_PLACEMENT_DOCKED) ?: KEYBOARD_PLACEMENT_DOCKED
        hapticsEnabled = prefs().getBoolean(HAPTICS_PREF, true)
        libraryGridMode = prefs().getBoolean(LIBRARY_GRID_MODE_PREF, true)
        goKeyColor = prefs().getInt(GO_KEY_COLOR_PREF, Accent)
        migrateWidgetGestureDefault()
        apps = loadLaunchableApps()
        messages = loadHubMessages()
        calendarEvents = loadCalendarEvents()
        prefs().registerOnSharedPreferenceChangeListener(prefsListener)
        mediaSessionSource = MediaSessionSource(this)
        spotifyAuth = SpotifyAuth(this)
        spotifyApi = SpotifyWebApi(spotifyAuth)
        if (spotifyAuth.isConnected) preloadSpotifyLibrary()
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
        rootView.post { maybeShowKeyboardPlacementIntro() }
        refreshWeather(force = false)
        maybeRequestSmsPermission()
        mediaSessionSource.start()
        mediaUiScope.launch {
            mediaSessionSource.nowPlaying.collect { info ->
                syncNowPlayingCardVisibility()
                refreshNowPlayingCard()
                // Restart progress ticker when playback resumes (ticker stops itself when paused)
                if (info?.isPlaying == true && musicProgressBar != null) {
                    musicProgressRunnable?.let { r ->
                        musicProgressHandler?.removeCallbacks(r)
                        musicProgressHandler?.post(r)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            dismissToHome()
            return
        }
        val uri = intent.data ?: return
        if (uri.scheme == "com.fran.clicks" && uri.host == "spotify-callback") {
            mediaUiScope.launch {
                val ok = spotifyAuth.handleCallback(uri)
                if (ok) {
                    prefs().edit().putString(SPOTIFY_INTEGRATION_PREF, INTEGRATION_API).apply()
                    renderPaneContent(clicksSettingsTarget())
                    Toast.makeText(this@MainActivity, "Spotify connected!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Spotify connection failed. Try again.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ensureBillingConnected()
        val now = System.currentTimeMillis()
        if (now - lastAppsLoadMs > 10_000) { apps = loadLaunchableApps(); lastAppsLoadMs = now }
        if (now - lastHubLoadMs > 10_000) { messages = loadHubMessages(); lastHubLoadMs = now }
        if (now - lastCalendarLoadMs > 10_000) { calendarEvents = loadCalendarEvents(); lastCalendarLoadMs = now }
        if (::mediaSessionSource.isInitialized) mediaSessionSource.refreshActiveSessions()
        if (::ribbonView.isInitialized) {
            updateClock()
            renderHub()
            renderRibbon()
            syncNowPlayingCardVisibility()
            refreshNowPlayingCard()
            refreshWeather(force = false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs().unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (::mediaSessionSource.isInitialized) mediaSessionSource.stop()
        if (::appWidgetHost.isInitialized) appWidgetHost.stopListening()
        mediaUiScope.cancel()
        spellChecker?.close()
        handler.removeCallbacksAndMessages(null)
        billingClient?.endConnection()
        billingClient = null
    }

    private fun ensureBillingConnected() {
        val bc = billingClient ?: ProManager.buildBillingClient(this) { token ->
            // Purchase verified — refresh any gated UI
            Toast.makeText(this, "Clicks Pro unlocked. Welcome!", Toast.LENGTH_SHORT).show()
            renderRibbon()
        }.also { billingClient = it }

        if (!bc.isReady) {
            bc.startConnection(object : com.android.billingclient.api.BillingClientStateListener {
                override fun onBillingSetupFinished(result: com.android.billingclient.api.BillingResult) {
                    if (result.responseCode == com.android.billingclient.api.BillingClient.BillingResponseCode.OK) {
                        mediaUiScope.launch(Dispatchers.IO) {
                            ProManager.restorePurchases(bc, this@MainActivity)
                        }
                    }
                }
                override fun onBillingServiceDisconnected() { billingClient = null }
            })
        }
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
        } else if (requestCode == WIDGET_BIND_REQUEST_CODE) {
            val widgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId) ?: pendingWidgetId
            val provider = pendingWidgetProvider
            if (resultCode == RESULT_OK && provider != null && widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                configureOrSaveWidget(widgetId, provider)
            } else {
                runCatching { appWidgetHost.deleteAppWidgetId(widgetId) }
            }
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            pendingWidgetProvider = null
        } else if (requestCode == WIDGET_CONFIGURE_REQUEST_CODE) {
            val widgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId) ?: pendingWidgetId
            if (resultCode == RESULT_OK && widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                saveWidgetId(widgetId)
                refreshWidgetBoard()
            } else {
                runCatching { appWidgetHost.deleteAppWidgetId(widgetId) }
            }
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
            pendingWidgetProvider = null
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
        if (widgetPickerView != null) { closeWidgetPicker(); return }
        if (widgetBoardView != null) { closeWidgetBoard(); return }
        if (spotifyFullLibraryDismiss != null) { spotifyFullLibraryDismiss?.invoke(); return }
        if (spotifyCompactOverlay != null) { dismissCompactSpotifyLibrary(); return }
        if (libraryOpen) { closeLibrary(); return }
        if (openPane != null) { closePane(); return }
        super.onBackPressed()
    }

    private fun dismissToHome() {
        // Hide soft keyboard first so it doesn't flash during transition
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        // Dismiss overlays in order
        spotifyFullLibraryDismiss?.invoke()
        if (spotifyCompactOverlay != null) dismissCompactSpotifyLibrary()
        if (openPane != null) closePane()
    }

    // ── Pro gate ─────────────────────────────────────────────────────────────

    /** Returns true if the feature is accessible. If not, shows the upgrade sheet and returns false. */
    fun requirePro(feature: ProFeature, onUnlocked: (() -> Unit)? = null): Boolean {
        if (ProManager.isUnlocked(this)) { onUnlocked?.invoke(); return true }
        showUpgradeSheet(feature, onUnlocked)
        return false
    }

    private fun showUpgradeSheet(triggerFeature: ProFeature? = null, onPurchased: (() -> Unit)? = null) {
        val decorView = window.decorView as FrameLayout
        val screenH = resources.displayMetrics.heightPixels
        val accent = 0xFF8AB4F8.toInt()   // Clicks blue

        // Scrim
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x00000000)
            isClickable = false
        }

        // Sheet
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF0F1215.toInt())
                cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
                setStroke(dp(1), 0xFF1E2330.toInt())
            }
            setPadding(dp(24), dp(20), dp(24), dp(36))
            translationY = screenH.toFloat()
        }

        fun dismiss() {
            sheet.animate().translationY(screenH.toFloat()).setDuration(280)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.8f))
                .withEndAction { decorView.removeView(scrim) }.start()
            scrim.animate().alpha(0f).setDuration(220).start()
        }

        // Drag pill
        sheet.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(0xFF2A2E38.toInt()); cornerRadius = dp(2).toFloat() }
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(20) })

        // Heading
        sheet.addView(TextView(this).apply {
            text = "Clicks Pro"
            textSize = 22f; setTextColor(Ink)
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            includeFontPadding = false
        })

        // Trigger context — what they tried to use
        if (triggerFeature != null) {
            sheet.addView(TextView(this).apply {
                text = "${triggerFeature.label} is a Pro feature."
                textSize = 13f; setTextColor(0xFF8AB4F8.toInt())
                setPadding(0, dp(4), 0, 0)
            })
        }

        sheet.addView(View(this), LinearLayout.LayoutParams.MATCH_PARENT, dp(16))

        // Feature bullets — what Pro includes
        val bullets = listOf(
            "Gemini AI keyboard suggestions",
            "Full Spotify library & search",
            "Smart autocorrect & compose",
            "Premium keyboard themes",
            "All future Pro features"
        )
        bullets.forEach { line ->
            sheet.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
                addView(View(this@MainActivity).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
                }, LinearLayout.LayoutParams(dp(5), dp(5)).apply { marginEnd = dp(10) })
                addView(TextView(this@MainActivity).apply {
                    text = line; textSize = 13.5f; setTextColor(0xFFCED2DA.toInt())
                })
            })
        }

        sheet.addView(View(this), LinearLayout.LayoutParams.MATCH_PARENT, dp(20))

        // CTA button
        sheet.addView(TextView(this).apply {
            text = "Unlock Pro"
            textSize = 15f; setTextColor(0xFF000000.toInt())
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(14).toFloat() }
            setPadding(0, dp(16), 0, dp(16))
            isClickable = true
            setOnClickListener {
                val bc = billingClient
                if (bc == null || !bc.isReady) {
                    Toast.makeText(this@MainActivity, "Store unavailable — try again shortly.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                mediaUiScope.launch {
                    val result = ProManager.launchBillingFlow(bc, this@MainActivity)
                    if (result.responseCode == com.android.billingclient.api.BillingClient.BillingResponseCode.OK) {
                        dismiss()
                        onPurchased?.invoke()
                    }
                }
            }
        }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        // Restore link
        sheet.addView(TextView(this).apply {
            text = "Restore purchase"
            textSize = 11.5f; setTextColor(InkDim); gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
            isClickable = true
            setOnClickListener {
                val bc = billingClient ?: return@setOnClickListener
                mediaUiScope.launch(Dispatchers.IO) {
                    val restored = ProManager.restorePurchases(bc, this@MainActivity)
                    runOnUiThread {
                        if (restored) { dismiss(); onPurchased?.invoke()
                            Toast.makeText(this@MainActivity, "Pro restored.", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "No purchase found.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })

        scrim.setOnClickListener { dismiss() }
        scrim.addView(sheet, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        decorView.addView(scrim, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(200).start()
        sheet.animate().translationY(0f).setDuration(340)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (handlePaneSwipe(event)) return true
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

    private fun maybeShowKeyboardPlacementIntro() {
        if (prefs().getBoolean(KEYBOARD_PLACEMENT_INTRO_PREF, false)) return
        prefs().edit().putBoolean(KEYBOARD_PLACEMENT_INTRO_PREF, true).apply()
        AlertDialog.Builder(this)
            .setTitle("Choose keyboard style")
            .setMessage("Docked keeps the Clicks keyboard fixed at the bottom. Widget places it on the homescreen, with a DOCK key to return to the fixed layout.")
            .setPositiveButton("Docked") { dialog, _ ->
                dialog.dismiss()
                setKeyboardPlacement(KEYBOARD_PLACEMENT_DOCKED)
            }
            .setNegativeButton("Widget") { dialog, _ ->
                dialog.dismiss()
                setKeyboardPlacement(KEYBOARD_PLACEMENT_WIDGET)
            }
            .show()
    }

    private fun render() {
        keyViews.clear()
        keyBounds.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Screen)
            setPadding(0, systemStatusBarHeight(), 0, 0)
        }
        rootView = root
        contentFrame = FrameLayout(this).apply {
            addView(home(), FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(contentFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (keyboardPlacement == KEYBOARD_PLACEMENT_DOCKED) {
            root.addView(typingStripView(), LinearLayout.LayoutParams.MATCH_PARENT, dp(34))
            keyboardDockView = FrameLayout(this).apply {
                addView(dockedInputView(), FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            root.addView(keyboardDockView, LinearLayout.LayoutParams.MATCH_PARENT, keyboardHeight())
        } else {
            keyboardDockView = FrameLayout(this)
        }
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

    // Typing display — lives with whichever keyboard placement is active. Shows typed
    // text + blinking cursor; hint text when idle.
    private fun typingStripView(): LinearLayout {
        return LinearLayout(this).apply {
            hintBar = this
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = typingStripBackground()
            elevation = dp(3).toFloat()
            isClickable = true
            setOnTouchListener { _, event -> handleTypingStripGesture(event) }
            searchHintView = TextView(context).apply {
                textSize = 15f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setTextColor(Ink)
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.START
                setPadding(dp(16), dp(3), dp(8), dp(3))
                isClickable = true
                setOnTouchListener { _, event -> handleTypingStripGesture(event) }
            }
            addView(searchHintView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            if (isVivoDevice()) {
                addView(zeissCameraButton(), LinearLayout.LayoutParams(dp(56), dp(22)).apply {
                    marginEnd = dp(10)
                })
            }
        }
    }

    private fun zeissCameraButton(): View {
        return ZeissCameraButtonView(this).apply {
            zeissButtonView = this
            text = "ZEISS"
            textSize = 8.8f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, 0, 0, dp(1))
            setTextColor(0xFFDCE6FF.toInt())
            background = zeissRecessedBackground()
            isClickable = true
            setOnClickListener {
                haptic(this)
                openVivoCamera()
            }
            setOnLongClickListener {
                haptic(this)
                openPhotosExperience()
                true
            }
            setShutterClosed(openPane?.kind == PaneKind.PHOTOS)
        }
    }

    private fun openPhotosExperience() {
        if (hasPhotoPermission()) {
            zeissButtonView?.animateShutterClosed()
            openHere(photosTarget())
        } else {
            photosPermissionLauncher.launch(photoPermissionName())
        }
    }

    private fun hasPhotoPermission(): Boolean {
        return checkSelfPermission(photoPermissionName()) == PackageManager.PERMISSION_GRANTED
    }

    private fun photoPermissionName(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun typingStripBackground(): Drawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xFF16181D.toInt(),
            0xFF08090C.toInt(),
            0xFF000000.toInt()
        )).apply {
            setStroke(dp(1), 0xFF20232A.toInt())
        }
        val topLight = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0x26FFFFFF,
            0x00FFFFFF
        ))
        val bottomShade = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(
            0x99000000.toInt(),
            0x00000000
        ))
        return LayerDrawable(arrayOf(base, topLight, bottomShade)).apply {
            setLayerInset(1, 0, 0, 0, dp(22))
            setLayerInset(2, 0, dp(20), 0, 0)
        }
    }

    private fun zeissRecessedBackground(): Drawable {
        val pocket = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xFF02040B.toInt(),
            0xFF07143B.toInt(),
            0xFF02030A.toInt()
        )).apply {
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), 0xFF05070D.toInt())
        }
        val blueInsert = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xFF173A9A.toInt(),
            0xFF0B2570.toInt(),
            0xFF071849.toInt()
        )).apply {
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), 0xFF2B4FA8.toInt())
        }
        val innerShade = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(
            0x77000000,
            0x00000000
        )).apply {
            cornerRadius = dp(8).toFloat()
        }
        val topRim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0x337DA0FF,
            0x007DA0FF
        )).apply {
            cornerRadius = dp(8).toFloat()
        }
        return LayerDrawable(arrayOf(pocket, blueInsert, innerShade, topRim)).apply {
            setLayerInset(0, 0, 0, 0, 0)
            setLayerInset(1, dp(2), dp(2), dp(2), dp(2))
            setLayerInset(2, dp(2), dp(2), dp(2), dp(2))
            setLayerInset(3, dp(3), dp(2), dp(3), dp(13))
        }
    }

    private fun zeissHeaderLogo(): View {
        return TextView(this).apply {
            text = "ZEISS"
            textSize = 8.6f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.11f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(0xFFEAF0FF.toInt())
            background = zeissRecessedBackground()
            isClickable = true
            setOnClickListener {
                haptic(this)
                openVivoCamera()
            }
        }
    }

    private fun zeissHeaderBadge(): View {
        return TextView(this).apply {
            text = "Z"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.02f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(0xFFEAF0FF.toInt())
            background = zeissRecessedBackground()
        }
    }

    private fun zeissHeaderGlassBg(): Drawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0x70151922,
            0x50101820,
            0x7205060A
        ))
        val blueVeil = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
            0x1A2D5DFF,
            0x08000000,
            0x182D5DFF
        ))
        val edge = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0x18FFFFFF,
            0x00000000,
            0x30000000
        ))
        return LayerDrawable(arrayOf(base, blueVeil, edge))
    }

    private fun isVivoDevice(): Boolean {
        val device = listOf(Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.MODEL)
            .joinToString(" ")
            .lowercase(Locale.US)
        return device.contains("vivo") || device.contains("iqoo")
    }

    private inner class ZeissCameraButtonView(context: Context) : TextView(context) {
        private val shutterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var shutterProgress = 0f
        private var shutterAnimator: ValueAnimator? = null

        fun setShutterClosed(closed: Boolean) {
            shutterAnimator?.cancel()
            shutterProgress = if (closed) 1f else 0f
            invalidate()
        }

        fun animateShutterClosed() = animateShutterTo(1f, 820L)

        fun animateShutterOpen() = animateShutterTo(0f, 980L)

        private fun animateShutterTo(target: Float, durationMs: Long) {
            shutterAnimator?.cancel()
            shutterAnimator = ValueAnimator.ofFloat(shutterProgress, target).apply {
                duration = durationMs
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    shutterProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val originalTextAlpha = paint.alpha
            if (shutterProgress > 0.01f) {
                paint.alpha = (255 * (1f - shutterProgress * 0.92f)).toInt().coerceIn(0, 255)
            }
            super.onDraw(canvas)
            paint.alpha = originalTextAlpha
            if (shutterProgress <= 0.01f) return
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height).toFloat() * 0.56f
            val outer = maxOf(width, height).toFloat() * 1.35f
            val bladeCount = 6
            val segment = Math.PI.toFloat() * 2f / bladeCount
            val aperture = (radius * 0.78f * (1f - shutterProgress)).coerceAtLeast(if (shutterProgress > 0.96f) 0f else dp(1).toFloat())
            val twist = shutterProgress * segment * 0.92f

            val clip = Path().apply {
                addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(9).toFloat(), dp(9).toFloat(), Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clip)

            shutterPaint.style = Paint.Style.FILL
            shutterPaint.shader = android.graphics.RadialGradient(
                cx,
                cy,
                outer,
                intArrayOf(0xFF141A28.toInt(), 0xFF05070D.toInt(), 0xFF000000.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(9).toFloat(), dp(9).toFloat(), shutterPaint)
            shutterPaint.shader = null

            shutterPaint.shader = android.graphics.RadialGradient(
                cx,
                cy,
                radius * 1.18f,
                intArrayOf(
                    Color.argb((16 + shutterProgress * 24).toInt(), 170, 198, 255),
                    Color.argb((160 + shutterProgress * 70).toInt(), 0, 0, 0)
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dp(9).toFloat(), dp(9).toFloat(), shutterPaint)
            shutterPaint.shader = null

            repeat(bladeCount) { index ->
                val start = -Math.PI.toFloat() / 2f + index * segment + twist
                val end = start + segment * 1.35f
                val innerStart = start - segment * 0.28f
                val innerEnd = end - segment * 0.28f
                val p1x = cx + kotlin.math.cos(start) * outer
                val p1y = cy + kotlin.math.sin(start) * outer
                val p2x = cx + kotlin.math.cos(end) * outer
                val p2y = cy + kotlin.math.sin(end) * outer
                val p3x = cx + kotlin.math.cos(innerEnd) * aperture
                val p3y = cy + kotlin.math.sin(innerEnd) * aperture
                val p4x = cx + kotlin.math.cos(innerStart) * aperture
                val p4y = cy + kotlin.math.sin(innerStart) * aperture
                val blade = Path().apply {
                    moveTo(p1x, p1y)
                    lineTo(p2x, p2y)
                    lineTo(p3x, p3y)
                    lineTo(p4x, p4y)
                    close()
                }
                shutterPaint.style = Paint.Style.FILL
                shutterPaint.color = if (index % 2 == 0) 0xFF05070D.toInt() else 0xFF111724.toInt()
                canvas.drawPath(blade, shutterPaint)
                shutterPaint.style = Paint.Style.STROKE
                shutterPaint.strokeWidth = dp(1).toFloat()
                shutterPaint.color = Color.argb(145, 94, 116, 154)
                canvas.drawPath(blade, shutterPaint)
            }

            canvas.restore()

            shutterPaint.style = Paint.Style.STROKE
            shutterPaint.strokeWidth = dp(2).toFloat()
            shutterPaint.color = Color.argb(190, 0, 0, 0)
            canvas.drawCircle(cx, cy, radius, shutterPaint)

            shutterPaint.strokeWidth = dp(1).toFloat()
            shutterPaint.color = Color.argb(92, 180, 205, 255)
            canvas.drawCircle(cx, cy, radius - dp(2), shutterPaint)

            shutterPaint.style = Paint.Style.FILL
            shutterPaint.color = Color.argb(235, 0, 0, 0)
            canvas.drawCircle(cx, cy, aperture.coerceAtLeast(dp(1).toFloat()), shutterPaint)
            if (shutterProgress > 0.72f) {
                shutterPaint.style = Paint.Style.STROKE
                shutterPaint.strokeWidth = dp(1).toFloat()
                shutterPaint.color = Color.argb((180 * shutterProgress).toInt(), 150, 170, 210)
                repeat(bladeCount) { index ->
                    val angle = -Math.PI.toFloat() / 2f + index * segment + twist - segment * 0.22f
                    canvas.drawLine(
                        cx,
                        cy,
                        cx + kotlin.math.cos(angle) * radius * 0.92f,
                        cy + kotlin.math.sin(angle) * radius * 0.92f,
                        shutterPaint
                    )
                }
            }
        }
    }

    private fun photoStampLabel(): String {
        if (isVivoDevice()) return "ZEISS"
        val maker = Build.MANUFACTURER.ifBlank { Build.BRAND }.ifBlank { "ANDROID" }
        return maker.uppercase(Locale.US)
    }

    private fun openVivoCamera() {
        val intents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_CAMERA"),
            Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        )
        intents.firstOrNull { it.resolveActivity(packageManager) != null }?.let {
            startSafeIntent(it, "Camera isn't available here")
            return
        }
        listOf("com.android.camera", "com.vivo.camera", "com.bbk.camera").forEach { pkg ->
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                startSafeIntent(it, "Camera isn't available here")
                return
            }
        }
        Toast.makeText(this, "Camera isn't available here", Toast.LENGTH_SHORT).show()
    }

    private fun home(): View {
        homeTileViews.clear()
        homeEditMode = false
        homeEditChipView = null
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(20), dp(10), dp(20), dp(10))
            addView(homeHeader(), LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.9f))
            nowPlayingCardView = ComposeView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setNowPlayingCardContent()
                elevation = dp(8).toFloat()
            }
            addView(nowPlayingCardView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, nowPlayingCardHeight()).apply {
                topMargin = dp(2)
                bottomMargin = dp(2)
            })
            addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, if (keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET) 0.22f else 1.1f))
            if (keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET) {
                addView(homeKeyboardWidget(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, widgetKeyboardHeight()).apply {
                    bottomMargin = dp(8)
                })
            }
            favoritesDockView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
                setPadding(dp(18), dp(8), dp(18), dp(8))
                background = recessedDockBackground()
            }
            addView(favoritesDockView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(84)).apply {
                bottomMargin = dp(2)
            })
        }
    }

    private fun homeTile(id: String, child: View): FrameLayout {
        return HomeTileFrame(this, id).apply {
            tag = id
            clipChildren = id != HOME_TILE_DOCK
            clipToPadding = id != HOME_TILE_DOCK
            isLongClickable = true
            addView(child, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setOnLongClickListener {
                enterHomeEditMode()
                true
            }
            homeTileViews[id] = this
        }
    }

    private fun homeKeyboardWidget(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            background = homeKeyboardWidgetBackground()
            setPadding(dp(5), dp(5), dp(5), dp(5))
            addView(typingStripView(), LinearLayout.LayoutParams.MATCH_PARENT, dp(34))
            addView(keyboard(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun enterHomeEditMode() {
        if (homeEditMode) return
        homeEditMode = true
        haptic(homeGridView)
        updateHomeEditChrome()
    }

    private fun exitHomeEditMode() {
        if (!homeEditMode) return
        homeEditMode = false
        updateHomeEditChrome()
    }

    private fun updateHomeEditChrome() {
        homeEditChipView?.animate()?.alpha(if (homeEditMode) 1f else 0f)?.setDuration(140)?.start()
        homeTileViews.values.forEach { tile ->
            tile.foreground = if (homeEditMode) homeTileEditForeground() else null
            tile.isPressed = false
        }
    }

    private fun homeTileEditForeground(): Drawable {
        return GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), 0x88F5C451.toInt())
        }
    }

    private fun layoutHomeGrid() {
        if (!::homeGridView.isInitialized || homeGridView.width <= 0 || homeGridView.height <= 0) return
        val contentWidth = homeGridView.width - homeGridView.paddingLeft - homeGridView.paddingRight
        val contentHeight = homeGridView.height - homeGridView.paddingTop - homeGridView.paddingBottom
        if (contentWidth <= 0 || contentHeight <= 0) return
        val gap = dp(10)
        val cellW = (contentWidth - gap * (HOME_GRID_COLUMNS - 1)) / HOME_GRID_COLUMNS.toFloat()
        val cellH = (contentHeight - gap * (HOME_GRID_ROWS - 1)) / HOME_GRID_ROWS.toFloat()
        homeTileViews.forEach { (id, tile) ->
            val spec = homeTileSpec(id)
            val left = homeGridView.paddingLeft + ((cellW + gap) * spec.col).toInt()
            val top = homeGridView.paddingTop + ((cellH + gap) * spec.row).toInt()
            val width = (cellW * spec.colSpan + gap * (spec.colSpan - 1)).toInt()
            val height = (cellH * spec.rowSpan + gap * (spec.rowSpan - 1)).toInt()
            val lp = (tile.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(width, height)
            lp.width = width
            lp.height = height
            lp.leftMargin = left
            lp.topMargin = top
            tile.layoutParams = lp
        }
    }

    private fun homeTileSpec(id: String): HomeTileSpec {
        val fallback = defaultHomeTileSpec(id)
        val raw = prefs().getString("$HOME_TILE_PREF_PREFIX$id", null) ?: return fallback
        return runCatching {
            val json = JSONObject(raw)
            sanitizeHomeTileSpec(
                HomeTileSpec(
                    id = id,
                    col = json.optInt("col", fallback.col),
                    row = json.optInt("row", fallback.row),
                    colSpan = json.optInt("colSpan", fallback.colSpan),
                    rowSpan = json.optInt("rowSpan", fallback.rowSpan)
                )
            )
        }.getOrDefault(fallback)
    }

    private fun defaultHomeTileSpec(id: String): HomeTileSpec {
        return when (id) {
            HOME_TILE_WEATHER -> HomeTileSpec(id, 0, 0, 4, 3)
            HOME_TILE_WIDGETS -> HomeTileSpec(id, 0, 4, 4, 4)
            HOME_TILE_DOCK -> HomeTileSpec(id, 0, 9, 4, 3)
            else -> HomeTileSpec(id, 0, 0, 4, 2)
        }
    }

    private fun sanitizeHomeTileSpec(spec: HomeTileSpec): HomeTileSpec {
        val minRows = when (spec.id) {
            HOME_TILE_WIDGETS -> 4
            HOME_TILE_WEATHER -> 3
            HOME_TILE_DOCK -> 3
            else -> 2
        }
        val colSpan = spec.colSpan.coerceIn(2, HOME_GRID_COLUMNS)
        val rowSpan = spec.rowSpan.coerceIn(minRows, HOME_GRID_ROWS)
        return spec.copy(
            colSpan = colSpan,
            rowSpan = rowSpan,
            col = spec.col.coerceIn(0, HOME_GRID_COLUMNS - colSpan),
            row = spec.row.coerceIn(0, HOME_GRID_ROWS - rowSpan)
        )
    }

    private fun saveHomeTileSpec(spec: HomeTileSpec) {
        val safe = sanitizeHomeTileSpec(spec)
        val json = JSONObject()
            .put("col", safe.col)
            .put("row", safe.row)
            .put("colSpan", safe.colSpan)
            .put("rowSpan", safe.rowSpan)
        prefs().edit().putString("$HOME_TILE_PREF_PREFIX${safe.id}", json.toString()).apply()
    }

    private inner class HomeTileTouchListener(private val id: String) : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startLeft = 0
        private var startTop = 0
        private var dragged = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (!homeEditMode) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    startRawX = event.rawX
                    startRawY = event.rawY
                    val lp = view.layoutParams as FrameLayout.LayoutParams
                    startLeft = lp.leftMargin
                    startTop = lp.topMargin
                    dragged = false
                    view.animate().scaleX(1.025f).scaleY(1.025f).setDuration(90).start()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startRawX).toInt()
                    val dy = (event.rawY - startRawY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) dragged = true
                    moveHomeTile(view, startLeft + dx, startTop + dy)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                    snapHomeTile(id, view)
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (!dragged && event.actionMasked == MotionEvent.ACTION_UP) haptic(view)
                    return true
                }
            }
            return true
        }
    }

    private inner class HomeTileFrame(context: Context, private val tileId: String) : FrameLayout(context) {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startLeft = 0
        private var startTop = 0
        private var dragging = false

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (!homeEditMode) return super.dispatchTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    startRawX = event.rawX
                    startRawY = event.rawY
                    val lp = layoutParams as FrameLayout.LayoutParams
                    startLeft = lp.leftMargin
                    startTop = lp.topMargin
                    dragging = false
                    animate().scaleX(1.025f).scaleY(1.025f).setDuration(90).start()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startRawX).toInt()
                    val dy = (event.rawY - startRawY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) dragging = true
                    moveHomeTile(this, startLeft + dx, startTop + dy)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                    snapHomeTile(tileId, this)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (!dragging && event.actionMasked == MotionEvent.ACTION_UP) haptic(this)
                    return true
                }
            }
            return true
        }
    }

    private fun moveHomeTile(view: View, left: Int, top: Int) {
        val lp = view.layoutParams as FrameLayout.LayoutParams
        val minLeft = homeGridView.paddingLeft
        val minTop = homeGridView.paddingTop
        val maxLeft = (homeGridView.width - homeGridView.paddingRight - view.width).coerceAtLeast(minLeft)
        val maxTop = (homeGridView.height - homeGridView.paddingBottom - view.height).coerceAtLeast(minTop)
        lp.leftMargin = left.coerceIn(minLeft, maxLeft)
        lp.topMargin = top.coerceIn(minTop, maxTop)
        view.layoutParams = lp
    }

    private fun snapHomeTile(id: String, view: View) {
        val spec = homeTileSpec(id)
        val contentWidth = homeGridView.width - homeGridView.paddingLeft - homeGridView.paddingRight
        val contentHeight = homeGridView.height - homeGridView.paddingTop - homeGridView.paddingBottom
        val gap = dp(10)
        val cellW = (contentWidth - gap * (HOME_GRID_COLUMNS - 1)) / HOME_GRID_COLUMNS.toFloat()
        val cellH = (contentHeight - gap * (HOME_GRID_ROWS - 1)) / HOME_GRID_ROWS.toFloat()
        val lp = view.layoutParams as FrameLayout.LayoutParams
        val localLeft = (lp.leftMargin - homeGridView.paddingLeft).coerceAtLeast(0)
        val localTop = (lp.topMargin - homeGridView.paddingTop).coerceAtLeast(0)
        val next = sanitizeHomeTileSpec(spec.copy(
            col = (localLeft / (cellW + gap)).toInt().coerceIn(0, HOME_GRID_COLUMNS - spec.colSpan),
            row = (localTop / (cellH + gap)).toInt().coerceIn(0, HOME_GRID_ROWS - spec.rowSpan)
        ))
        saveHomeTileSpec(next)
        layoutHomeGrid()
    }

    private fun homeHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(14), dp(10))
            isClickable = true
            setOnClickListener {
                haptic(this)
                if (hasWeatherPermission()) refreshWeather(force = true) else weatherPermissionLauncher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            background = weatherHeaderBackground()
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                weatherTempView = TextView(context).apply {
                    text = prefs().getString(WEATHER_TEMP_PREF, "--")
                    textSize = 44f
                    typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
                    setTextColor(Ink)
                    includeFontPadding = false
                }
                addView(weatherTempView)
                weatherMetaView = TextView(context).apply {
                    text = prefs().getString(WEATHER_META_PREF, "Tap for local weather")
                    textSize = 10.6f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    letterSpacing = 0.22f
                    setTextColor(InkDim)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                addView(weatherMetaView)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
                weatherFeelsView = TextView(context).apply {
                    text = prefs().getString(WEATHER_FEELS_PREF, "Feels --")
                    textSize = 10.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(Ink)
                    includeFontPadding = false
                    gravity = Gravity.RIGHT
                }
                addView(weatherFeelsView)
                weatherStatsView = TextView(context).apply {
                    text = prefs().getString(WEATHER_STATS_PREF, "Local")
                    textSize = 9f
                    letterSpacing = 0.08f
                    setTextColor(InkDim)
                    includeFontPadding = false
                    gravity = Gravity.RIGHT
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                addView(weatherStatsView)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.72f).apply { marginEnd = dp(10) })
            weatherIconView = AnimatedWeatherIconView(context).apply {
                setWeatherCode(prefs().getInt(WEATHER_CODE_PREF, 0))
                setAnimationEnabled(animatedWeatherEnabled())
            }
            addView(weatherIconView, LinearLayout.LayoutParams(dp(50), dp(50)))
        }
    }

    private fun ComposeView.setNowPlayingCardContent() {
        setContent {
            val media by mediaSessionSource.nowPlaying.collectAsState()
            val current = media
            HomeWidgetStack(
                visible = homeWidgetStackVisible(),
                isMusicPlaying = current?.isPlaying == true,
                title = current?.title.orEmpty(),
                artist = current?.artist.orEmpty(),
                sourceApp = current?.sourceApp.orEmpty(),
                sourceColor = current?.appIconColor ?: 0xFF57C98A.toInt(),
                albumArt = current?.albumArt,
                calendarEvents = calendarEvents,
                recentPeople = recentPeople(),
                emailItems = contextItems(HUB_KIND_EMAIL),
                newsItems = contextItems(HUB_KIND_NEWS),
                mapsItems = contextItems(HUB_KIND_MAPS),
                hasCalendarPermission = hasCalendarPermission(),
                onMusicClick = {
                    haptic(this@setNowPlayingCardContent)
                    openHere(musicTarget())
                },
                onMusicLongClick = {
                    haptic(this@setNowPlayingCardContent)
                    mediaSessionSource.openSourceApp()
                },
                onCalendarClick = {
                    haptic(this@setNowPlayingCardContent)
                    openCalendarEventOrRequest(calendarEvents.firstOrNull())
                },
                onCalendarLongClick = {
                    haptic(this@setNowPlayingCardContent)
                    createCalendarEvent()
                },
                onRecentPersonClick = { person ->
                    haptic(this@setNowPlayingCardContent)
                    openRecentPerson(person)
                },
                onRecentPersonLongClick = { person ->
                    haptic(this@setNowPlayingCardContent)
                    openRecentPersonApp(person)
                },
                onEmailClick = { item ->
                    haptic(this@setNowPlayingCardContent)
                    openContextItem(item, clearAfterOpen = true)
                },
                onEmailLongClick = { item ->
                    haptic(this@setNowPlayingCardContent)
                    openContextItemApp(item)
                },
                onNewsClick = { item ->
                    haptic(this@setNowPlayingCardContent)
                    openContextItem(item, clearAfterOpen = true)
                },
                onNewsLongClick = { item ->
                    haptic(this@setNowPlayingCardContent)
                    openContextItemApp(item)
                },
                onMapsClick = { item ->
                    haptic(this@setNowPlayingCardContent)
                    openContextItem(item, clearAfterOpen = false)
                },
                onMapsLongClick = { item ->
                    haptic(this@setNowPlayingCardContent)
                    openContextItemApp(item)
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
        if (!homeWidgetStackVisible()) return 0
        val metrics = resources.displayMetrics
        val contentHeight = metrics.heightPixels -
            systemStatusBarHeight() -
            dp(34) -
            keyboardHeight()
        val reservedHomeChrome = dp(20) + dp(70) + dp(80) + dp(28)
        val flexibleSpace = (contentHeight - reservedHomeChrome).coerceAtLeast(dp(120))
        return (flexibleSpace * 0.74f).toInt().coerceIn(dp(126), dp(214))
    }

    private fun homeWidgetStackVisible() = openPane == null && !libraryOpen

    private fun handleLibrarySwipe(event: MotionEvent) {
        if (!::contentFrame.isInitialized) return
        if (widgetBoardView != null) return
        if (homeEditMode) {
            librarySwipeTriggered = false
            librarySwipeBlockedByWidget = false
            return
        }
        val loc = IntArray(2)
        contentFrame.getLocationOnScreen(loc)
        val inContent = event.rawY >= loc[1] && event.rawY <= loc[1] + contentFrame.height

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                librarySwipeStartX = event.rawX
                librarySwipeStartY = event.rawY
                librarySwipeTriggered = false
                librarySwipeBlockedByWidget = isInsideHomeWidget(event.rawX, event.rawY)
            }
            MotionEvent.ACTION_UP -> {
                if (librarySwipeBlockedByWidget) {
                    librarySwipeBlockedByWidget = false
                    return
                }
                if (!inContent || librarySwipeTriggered) return
                val dx = event.rawX - librarySwipeStartX
                val dy = event.rawY - librarySwipeStartY
                // Deliberate horizontal threshold keeps vertical library scrolls and keyboard swipes separate.
                if (abs(dx) > dp(24) && abs(dx) > abs(dy) * 1.2f) {
                    when {
                        dx < 0 && !libraryOpen && openPane == null -> {
                            librarySwipeTriggered = true
                            openLibrary()
                        }
                        dx > 0 && libraryOpen -> {
                            librarySwipeTriggered = true
                            closeLibrary()
                        }
                        dx < 0 && libraryOpen -> {
                            librarySwipeTriggered = true
                            closeLibrary(slideLeft = true)
                        }
                        dx > 0 && openPane == null && spotifyAuth.isConnected && spotifyCompactOverlay == null -> {
                            librarySwipeTriggered = true
                            showCompactSpotifyLibrary()
                        }
                        dx > 0 && openPane == null && !spotifyAuth.isConnected -> {
                            librarySwipeTriggered = true
                            performHomeGesture(gestureAction(GESTURE_RIGHT_PREF, GESTURE_NONE))
                        }
                    }
                } else if (!libraryOpen && openPane == null && abs(dy) > dp(42) && abs(dy) > abs(dx) * 1.2f) {
                    librarySwipeTriggered = true
                    if (dy < 0) {
                        performHomeGesture(gestureAction(GESTURE_UP_PREF, GESTURE_WIDGETS))
                    } else {
                        performHomeGesture(gestureAction(GESTURE_DOWN_PREF, GESTURE_NOTIFICATIONS))
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                librarySwipeTriggered = false
                librarySwipeBlockedByWidget = false
            }
        }
    }

    private fun handlePaneSwipe(event: MotionEvent): Boolean {
        val paneKind = openPane?.kind
        if (!::contentFrame.isInitialized || (paneKind != PaneKind.MUSIC && paneKind != PaneKind.PHOTOS) || libraryOpen || widgetBoardView != null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                paneSwipeStartX = event.rawX
                paneSwipeStartY = event.rawY
                paneSwipeTriggered = false
                // A drag that starts on the dock is click-wheel input, not a pane swipe.
                paneSwipeFromDock = isInsideKeyboardDock(event.rawX, event.rawY)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (paneSwipeFromDock) return false
                if (paneSwipeTriggered) return true
                val dx = event.rawX - paneSwipeStartX
                val dy = event.rawY - paneSwipeStartY
                // Swipe right on music pane → slide in compact library
                if (paneKind == PaneKind.MUSIC && dx > dp(60) && dx > abs(dy) * 1.4f && spotifyCompactOverlay == null && spotifyAuth.isConnected) {
                    paneSwipeTriggered = true
                    haptic(contentFrame)
                    showCompactSpotifyLibrary()
                    return true
                }
                if (paneKind != PaneKind.MUSIC && dy > dp(72) && dy > abs(dx) * 1.2f) {
                    paneSwipeTriggered = true
                    haptic(contentFrame)
                    closePane()
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> { paneSwipeTriggered = false; paneSwipeFromDock = false }
        }
        return false
    }

    private fun isInsideHomeWidget(rawX: Float, rawY: Float): Boolean {
        if (!homeWidgetStackVisible() || !::nowPlayingCardView.isInitialized || nowPlayingCardView.height <= 0) return false
        val loc = IntArray(2)
        nowPlayingCardView.getLocationOnScreen(loc)
        return rawX >= loc[0] &&
            rawX <= loc[0] + nowPlayingCardView.width &&
            rawY >= loc[1] &&
            rawY <= loc[1] + nowPlayingCardView.height
    }

    private fun isInsideKeyboardDock(rawX: Float, rawY: Float): Boolean {
        if (!::keyboardDockView.isInitialized || keyboardDockView.height <= 0) return false
        val loc = IntArray(2)
        keyboardDockView.getLocationOnScreen(loc)
        return rawX >= loc[0] &&
            rawX <= loc[0] + keyboardDockView.width &&
            rawY >= loc[1] &&
            rawY <= loc[1] + keyboardDockView.height
    }

    private fun handleTypingStripGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stripSwipeStartX = event.rawX
                stripSwipeStartY = event.rawY
                stripSwipeTriggered = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - stripSwipeStartX
                val dy = event.rawY - stripSwipeStartY
                if (!stripSwipeTriggered && (abs(dx) > dp(18) || abs(dy) > dp(18))) {
                    stripSwipeTriggered = true
                    when {
                        abs(dy) > abs(dx) * 1.15f && dy < 0 -> performHomeGesture(gestureAction(GESTURE_UP_PREF, GESTURE_WIDGETS))
                        abs(dy) > abs(dx) * 1.15f && dy > 0 -> performHomeGesture(gestureAction(GESTURE_DOWN_PREF, GESTURE_NOTIFICATIONS))
                        abs(dx) > abs(dy) * 1.15f && dx > 0 -> performHomeGesture(gestureAction(GESTURE_RIGHT_PREF, GESTURE_NONE))
                        abs(dx) > abs(dy) * 1.15f && dx < 0 && openPane == null && widgetBoardView == null -> openLibrary()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!stripSwipeTriggered) {
                    haptic(searchHintView)
                    keyboardSettingsOpen = !keyboardSettingsOpen
                    refreshKeyboardDock()
                }
                stripSwipeTriggered = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                stripSwipeTriggered = false
                return true
            }
        }
        return true
    }

    private fun openLibrary() {
        if (libraryOpen || openPane != null) return
        libraryOpen = true
        query = ""
        keyboardSettingsOpen = false
        showLibrary(animate = true)
        renderRibbon()
        syncNowPlayingCardVisibility()
        refreshNowPlayingCard()
    }

    private fun closeLibrary(slideLeft: Boolean = false) {
        if (!libraryOpen) return
        libraryOpen = false
        query = ""
        closeCategoryFolder()
        renderRibbon()
        syncNowPlayingCardVisibility()
        refreshNowPlayingCard()
        val closing = libraryView ?: return
        val targetX = if (slideLeft) -closing.width.toFloat() else closing.width.toFloat()
        closing.animate().translationX(targetX).setDuration(240)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                contentFrame.removeView(closing)
                if (libraryView === closing) libraryView = null
                libraryContentArea = null
            }.start()
    }

    private fun openWidgetBoard() {
        if (widgetBoardView != null) return
        if (libraryOpen) closeLibrary()
        if (openPane != null) closePane()
        keyboardSettingsOpen = false
        setLauncherBlurred(true)
        val board = widgetBoard().apply {
            alpha = 0f
            translationY = dp(18).toFloat()
        }
        widgetBoardView = board
        addContentView(board, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        board.animate().alpha(1f).translationY(0f).setDuration(210).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun closeWidgetBoard() {
        closeWidgetPicker()
        val closing = widgetBoardView ?: return
        widgetBoardView = null
        closing.animate().alpha(0f).translationY(dp(18).toFloat()).setDuration(170).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                (closing.parent as? ViewGroup)?.removeView(closing)
                setLauncherBlurred(false)
            }
            .start()
    }

    private fun setLauncherBlurred(blurred: Boolean) {
        if (!::rootView.isInitialized || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        rootView.setRenderEffect(
            if (blurred) RenderEffect.createBlurEffect(dp(18).toFloat(), dp(18).toFloat(), Shader.TileMode.CLAMP)
            else null
        )
    }

    private fun widgetBoard(): View {
        return WidgetBoardFrame(this).apply {
            setBackgroundColor(0x99070A0E.toInt())
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), systemStatusBarHeight() + dp(12), dp(18), dp(18))
                addView(widgetBoardHeader(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))
                addView(widgetGridScroll(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private inner class WidgetBoardFrame(context: Context) : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var closing = false

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    closing = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!closing && dy > dp(84) && dy > abs(dx) * 1.2f) {
                        closing = true
                        haptic(this)
                        if (widgetPickerView != null) closeWidgetPicker() else closeWidgetBoard()
                        return true
                    }
                }
            }
            return super.dispatchTouchEvent(event)
        }
    }

    private fun widgetBoardHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(widgetCircleButton("+") {
                haptic(this)
                showWidgetPicker()
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "Widgets"
                    textSize = 25f
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    setTextColor(Ink)
                })
                addView(mono("FULL-SCREEN BOARD", 8.5f, InkDim).apply {
                    letterSpacing = 0.16f
                    includeFontPadding = false
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(14) })
            addView(mono("SWIPE DOWN", 8.5f, InkDim).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
                letterSpacing = 0.14f
            }, LinearLayout.LayoutParams(dp(108), ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun widgetCircleButton(label: String, action: TextView.() -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = if (label == "+") 26f else 28f
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(Ink)
            background = widgetCircleBackground()
            isClickable = true
            setOnClickListener { action() }
        }
    }

    private fun widgetGridScroll(): View {
        return ScrollView(this).apply {
            isFillViewport = true
            addView(GridLayout(context).apply {
                columnCount = 2
                setPadding(0, dp(12), 0, dp(26))
                val widgets = savedWidgetSpecs()
                if (widgets.isEmpty()) {
                    addView(emptyWidgetHint(), GridLayout.LayoutParams().apply {
                        width = GridLayout.LayoutParams.MATCH_PARENT
                        height = dp(240)
                        columnSpec = GridLayout.spec(0, 2)
                        setMargins(0, dp(26), 0, 0)
                    })
                } else {
                    widgets.forEach { spec ->
                        val span = widgetColumnSpan(spec.size)
                        addView(widgetTile(spec), GridLayout.LayoutParams().apply {
                            width = if (span == 2) resources.displayMetrics.widthPixels - dp(46) else (resources.displayMetrics.widthPixels - dp(58)) / 2
                            height = widgetTileHeight(spec.size)
                            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, span)
                            setMargins(dp(5), dp(6), dp(5), dp(6))
                        })
                    }
                }
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun emptyWidgetHint(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = widgetEmptyBackground()
            addView(TextView(context).apply {
                text = "+"
                textSize = 34f
                gravity = Gravity.CENTER
                setTextColor(Accent2)
                includeFontPadding = false
            })
            addView(mono("ADD YOUR FIRST WIDGET", 10f, InkDim).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.14f
                setPadding(0, dp(8), 0, 0)
            })
            isClickable = true
            setOnClickListener {
                haptic(this)
                showWidgetPicker()
            }
        }
    }

    private fun widgetTile(spec: WidgetSpec): View {
        val widgetId = spec.id
        val info = runCatching { appWidgetManager.getAppWidgetInfo(widgetId) }.getOrNull()
        val hostView = if (info != null) {
            appWidgetHost.createView(this, widgetId, info).apply {
                setAppWidget(widgetId, info)
                updateAppWidgetSize(null, widgetMinWidthDp(spec.size), widgetMinHeightDp(spec.size), widgetMaxWidthDp(spec.size), widgetMaxHeightDp(spec.size))
            }
        } else null
        return FrameLayout(this).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = widgetTileBackground()
            if (hostView != null) {
                addView(hostView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            } else {
                addView(mono("WIDGET UNAVAILABLE", 9f, InkDim).apply { gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
            setOnLongClickListener {
                haptic(this)
                showWidgetOptions(widgetId)
                true
            }
        }
    }

    private fun showWidgetPicker() {
        val board = widgetBoardView as? ViewGroup ?: return
        closeWidgetPicker()
        val picker = FrameLayout(this).apply {
            setBackgroundColor(0xEE050608.toInt())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), systemStatusBarHeight() + dp(12), dp(18), dp(18))
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    addView(widgetCircleButton("×") {
                        haptic(this)
                        closeWidgetPicker()
                    }, LinearLayout.LayoutParams(dp(42), dp(42)))
                    addView(TextView(context).apply {
                        text = "Add Widget"
                        textSize = 25f
                        typeface = Typeface.DEFAULT_BOLD
                        includeFontPadding = false
                        setTextColor(Ink)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(14) })
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))
                addView(widgetProviderList(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        widgetPickerView = picker
        board.addView(picker, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun closeWidgetPicker() {
        val picker = widgetPickerView ?: return
        widgetPickerView = null
        (picker.parent as? ViewGroup)?.removeView(picker)
    }

    private fun widgetProviderList(): View {
        return ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, dp(28))
                installedWidgetProviders().forEach { provider ->
                    addView(widgetProviderRow(provider), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76)).apply {
                        bottomMargin = dp(8)
                    })
                }
                if (childCount == 0) {
                    addView(mono("NO WIDGETS AVAILABLE ON THIS DEVICE", 10f, InkDim).apply {
                        gravity = Gravity.CENTER
                        letterSpacing = 0.12f
                    }, LinearLayout.LayoutParams.MATCH_PARENT, dp(180))
                }
            })
        }
    }

    private fun widgetProviderRow(provider: AppWidgetProviderInfo): View {
        val label = provider.loadLabel(packageManager).ifBlank { "Widget" }
        val appLabel = runCatching {
            val appInfo = packageManager.getApplicationInfo(provider.provider.packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        }.getOrDefault(provider.provider.packageName)
        val icon = runCatching { provider.loadIcon(this, resources.displayMetrics.densityDpi) }.getOrNull()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = widgetProviderRowBackground()
            isClickable = true
            addView(ImageView(context).apply {
                setImageDrawable(icon ?: packageManager.getApplicationIcon(provider.provider.packageName))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = label
                    textSize = 14.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Ink)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(mono(appLabel.uppercase(Locale.US), 8.5f, InkDim).apply {
                    letterSpacing = 0.10f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(12) })
            addView(mono("+", 20f, Accent2).apply { gravity = Gravity.CENTER })
            setOnClickListener {
                haptic(this)
                closeWidgetPicker()
                addWidgetProvider(provider)
            }
        }
    }

    private fun installedWidgetProviders(): List<AppWidgetProviderInfo> {
        return appWidgetManager.installedProviders.sortedWith { left, right ->
            collator.compare(left.loadLabel(packageManager), right.loadLabel(packageManager))
        }
    }

    private fun addWidgetProvider(provider: AppWidgetProviderInfo) {
        val widgetId = appWidgetHost.allocateAppWidgetId()
        pendingWidgetId = widgetId
        pendingWidgetProvider = provider
        val bound = runCatching {
            appWidgetManager.bindAppWidgetIdIfAllowed(widgetId, provider.provider)
        }.getOrDefault(false)
        if (bound) {
            configureOrSaveWidget(widgetId, provider)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
            }
            @Suppress("DEPRECATION")
            runCatching { startActivityForResult(intent, WIDGET_BIND_REQUEST_CODE) }
                .onFailure {
                    appWidgetHost.deleteAppWidgetId(widgetId)
                    Toast.makeText(this, "Widget permission is needed.", Toast.LENGTH_SHORT).show()
                }
        }
    }

    @Suppress("DEPRECATION")
    private fun configureOrSaveWidget(widgetId: Int, provider: AppWidgetProviderInfo) {
        if (provider.configure != null) {
            runCatching {
                startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                    component = provider.configure
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                }, WIDGET_CONFIGURE_REQUEST_CODE)
            }.onFailure {
                saveWidgetId(widgetId)
                refreshWidgetBoard()
            }
        } else {
            saveWidgetId(widgetId)
            refreshWidgetBoard()
        }
    }

    private fun saveWidgetId(widgetId: Int) {
        val next = savedWidgetSpecs().filterNot { it.id == widgetId } + WidgetSpec(widgetId, WIDGET_SIZE_LARGE)
        saveWidgetSpecs(next)
    }

    private fun savedWidgetSpecs(): List<WidgetSpec> {
        val raw = prefs().getString(WIDGET_IDS_PREF, "[]") ?: "[]"
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { index ->
                when (val item = json.opt(index)) {
                    is JSONObject -> {
                        val id = item.optInt("id", AppWidgetManager.INVALID_APPWIDGET_ID)
                        val size = normalizeWidgetSize(item.optString("size", WIDGET_SIZE_LARGE))
                        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) WidgetSpec(id, size) else null
                    }
                    is Number -> {
                        val id = item.toInt()
                        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) WidgetSpec(id, WIDGET_SIZE_LARGE) else null
                    }
                    else -> {
                        val id = json.optInt(index, AppWidgetManager.INVALID_APPWIDGET_ID)
                        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) WidgetSpec(id, WIDGET_SIZE_LARGE) else null
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveWidgetSpecs(specs: List<WidgetSpec>) {
        val json = JSONArray()
        specs.forEach { spec ->
            json.put(JSONObject().apply {
                put("id", spec.id)
                put("size", normalizeWidgetSize(spec.size))
            })
        }
        prefs().edit().putString(WIDGET_IDS_PREF, json.toString()).apply()
    }

    private fun showWidgetOptions(widgetId: Int) {
        val labels = arrayOf("Compact", "Wide", "Tall", "Large", "Remove")
        AlertDialog.Builder(this)
            .setTitle("Widget")
            .setItems(labels) { dialog, which ->
                when (labels[which]) {
                    "Compact" -> resizeWidget(widgetId, WIDGET_SIZE_COMPACT)
                    "Wide" -> resizeWidget(widgetId, WIDGET_SIZE_WIDE)
                    "Tall" -> resizeWidget(widgetId, WIDGET_SIZE_TALL)
                    "Large" -> resizeWidget(widgetId, WIDGET_SIZE_LARGE)
                    "Remove" -> confirmRemoveWidget(widgetId)
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun resizeWidget(widgetId: Int, size: String) {
        val specs = savedWidgetSpecs().map { spec ->
            if (spec.id == widgetId) spec.copy(size = normalizeWidgetSize(size)) else spec
        }
        saveWidgetSpecs(specs)
        refreshWidgetBoard()
    }

    private fun confirmRemoveWidget(widgetId: Int) {
        AlertDialog.Builder(this)
            .setTitle("Remove widget?")
            .setPositiveButton("Remove") { dialog, _ ->
                removeWidgetId(widgetId)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeWidgetId(widgetId: Int) {
        saveWidgetSpecs(savedWidgetSpecs().filterNot { it.id == widgetId })
        runCatching { appWidgetHost.deleteAppWidgetId(widgetId) }
        refreshWidgetBoard()
    }

    private fun normalizeWidgetSize(size: String): String {
        return when (size) {
            WIDGET_SIZE_COMPACT, WIDGET_SIZE_WIDE, WIDGET_SIZE_TALL, WIDGET_SIZE_LARGE -> size
            else -> WIDGET_SIZE_LARGE
        }
    }

    private fun widgetColumnSpan(size: String): Int = when (normalizeWidgetSize(size)) {
        WIDGET_SIZE_COMPACT, WIDGET_SIZE_TALL -> 1
        else -> 2
    }

    private fun widgetTileHeight(size: String): Int = when (normalizeWidgetSize(size)) {
        WIDGET_SIZE_COMPACT -> dp(170)
        WIDGET_SIZE_WIDE -> dp(220)
        WIDGET_SIZE_TALL -> dp(310)
        else -> dp(330)
    }

    private fun widgetMinWidthDp(size: String): Int = when (widgetColumnSpan(size)) {
        1 -> 140
        else -> 320
    }

    private fun widgetMaxWidthDp(size: String): Int = when (widgetColumnSpan(size)) {
        1 -> 300
        else -> 680
    }

    private fun widgetMinHeightDp(size: String): Int = when (normalizeWidgetSize(size)) {
        WIDGET_SIZE_COMPACT -> 120
        WIDGET_SIZE_WIDE -> 140
        WIDGET_SIZE_TALL -> 250
        else -> 260
    }

    private fun widgetMaxHeightDp(size: String): Int = when (normalizeWidgetSize(size)) {
        WIDGET_SIZE_COMPACT -> 180
        WIDGET_SIZE_WIDE -> 240
        WIDGET_SIZE_TALL -> 360
        else -> 390
    }

    private fun refreshWidgetBoard() {
        val old = widgetBoardView ?: return
        val parent = old.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(old)
        parent.removeView(old)
        val fresh = widgetBoard()
        widgetBoardView = fresh
        parent.addView(fresh, index, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun gestureAction(prefKey: String, fallback: String): String = prefs().getString(prefKey, fallback) ?: fallback

    private fun migrateWidgetGestureDefault() {
        val current = prefs().getString(GESTURE_UP_PREF, null)
        if (current == null || current == GESTURE_LIBRARY) {
            prefs().edit().putString(GESTURE_UP_PREF, GESTURE_WIDGETS).apply()
        }
    }

    private fun performHomeGesture(action: String) {
        when {
            action == GESTURE_NONE -> Unit
            action == GESTURE_LIBRARY -> openLibrary()
            action == GESTURE_WIDGETS -> openWidgetBoard()
            action == GESTURE_NOTIFICATIONS -> expandNotificationShade()
            action == GESTURE_MUSIC -> openHere(musicTarget())
            action == GESTURE_SETTINGS -> openHere(clicksSettingsTarget())
            action.startsWith(GESTURE_APP_PREFIX) -> {
                val packageName = action.removePrefix(GESTURE_APP_PREFIX)
                apps.firstOrNull { it.packageName == packageName }?.let { openExternal(it.toPaneTarget()) }
                    ?: Toast.makeText(this, "App isn't available here", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun expandNotificationShade() {
        val statusBar = getSystemService("statusbar")
        val methodName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            "expandNotificationsPanel"
        } else {
            "expand"
        }
        runCatching {
            statusBar?.javaClass?.getMethod(methodName)?.invoke(statusBar)
        }.onFailure {
            Toast.makeText(this, "Notification shade isn't available here", Toast.LENGTH_SHORT).show()
        }
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

    private fun appLibrary(): View = FrameLayout(this).apply {
        setPadding(dp(14), dp(14), dp(14), dp(10))
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF22242B.toInt(), 0xFF131419.toInt(), Screen)
        )
        val contentArea = FrameLayout(context)
        libraryContentArea = contentArea
        fillLibraryContent(contentArea)
        addView(contentArea, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(38)
        })
        addView(libraryHeader(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(46), Gravity.TOP))
    }

    private fun fillLibraryContent(area: FrameLayout) {
        area.removeAllViews()
        val child = if (query.isNotBlank()) searchResultsGrid()
                    else if (libraryGridMode) libraryGrid() else bentoGrid()
        area.addView(child, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }

    private fun refreshLibraryContent() {
        val area = libraryContentArea ?: run { showLibrary(animate = false); return }
        fillLibraryContent(area)
    }

    private fun libraryHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(10), 0)
        background = libraryHeaderGlassBg()
        elevation = dp(10).toFloat()
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
    }

    private fun searchResultsGrid(): View = ScrollView(this).apply {
        val results = universalSearchResults()
        if (results.isEmpty()) {
            addView(TextView(context).apply {
                text = "No results for \"$query\""
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(InkDim)
                includeFontPadding = false
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))
            return@apply
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(10))
            results.chunked(2).forEachIndexed { rowIndex, rowItems ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    rowItems.forEachIndexed { columnIndex, result ->
                        val index = rowIndex * 2 + columnIndex
                        addView(searchResultBentoCard(result, index == 0, index), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                            if (columnIndex > 0) marginStart = dp(10)
                        })
                    }
                    repeat(2 - rowItems.size) {
                        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(10) })
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(104)).apply {
                    bottomMargin = dp(10)
                })
            }
        })
    }

    private fun searchResultBentoCard(result: SearchResult, isBest: Boolean, index: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
            translationY = dp(8).toFloat()
            setPadding(dp(10), dp(9), dp(10), dp(8))
            background = roundedPanel(if (isBest) 0xFF20242B.toInt() else 0xFF191C22.toInt(), dp(20), if (isBest) result.accent else Line)
            postDelayed({
                animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            }, (index * 28L).coerceAtMost(240L))
            addView(searchResultIcon(result), LinearLayout.LayoutParams(dp(if (result.kind == SearchKind.APP) 48 else 42), dp(if (result.kind == SearchKind.APP) 48 else 42)))
            addView(TextView(context).apply {
                text = highlightedLabel(result.title, query)
                textSize = if (result.kind == SearchKind.APP) 12.5f else 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Ink)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(0, dp(8), 0, 0)
            }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (result.kind != SearchKind.APP) {
                addView(mono(result.subtitle.uppercase(Locale.US), 8f, InkDim).apply {
                    letterSpacing = 0.06f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            setOnClickListener { haptic(this); openSearchResult(result) }
        }
    }

    private fun searchResultRow(result: SearchResult, isBest: Boolean, index: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            alpha = 0f
            translationY = dp(8).toFloat()
            setPadding(dp(10), dp(8), dp(12), dp(8))
            background = roundedPanel(if (isBest) 0xFF20242B.toInt() else Panel2, dp(18), if (isBest) result.accent else Line)
            postDelayed({
                animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            }, (index * 28L).coerceAtMost(240L))
            addView(searchResultIcon(result), LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = highlightedLabel(result.title, query)
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(Ink)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    includeFontPadding = false
                }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(mono(result.subtitle.uppercase(Locale.US), 8.5f, InkDim).apply {
                    letterSpacing = 0.08f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(5), 0, 0)
                }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { haptic(this); openSearchResult(result) }
        }
    }

    private fun searchResultIcon(result: SearchResult): View {
        val app = result.target?.packageName?.let { pkg -> apps.firstOrNull { it.packageName == pkg } }
        return FrameLayout(this).apply {
            background = roundedPanel(0xFF171A20.toInt(), dp(14), adjustAlpha(result.accent, 0.55f))
            if (result.kind == SearchKind.APP && app != null) {
                addView(ImageView(context).apply {
                    setImageDrawable(iconFor(app.toLibraryApp()))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            } else {
                addView(TextView(context).apply {
                    text = when (result.kind) {
                        SearchKind.CONTACT -> "P"
                        SearchKind.EMAIL -> "@"
                        SearchKind.MESSAGE -> "M"
                        SearchKind.CALENDAR -> "C"
                        SearchKind.AI -> "AI"
                        SearchKind.APP -> "A"
                    }
                    gravity = Gravity.CENTER
                    textSize = if (result.kind == SearchKind.AI) 11f else 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(result.accent)
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        }
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
                text = highlightedLabel(app.name, query)
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

    private fun refreshHubMessagesFromPrefs() {
        messages = loadHubMessages()
        renderHub()
        refreshNowPlayingCard()
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

    private fun dockedInputView(): View {
        return when (openPane?.kind) {
            PaneKind.MUSIC -> if (musicTheme() == MUSIC_THEME_BLACK) musicBlackDock() else musicDockView()
            PaneKind.PHOTOS -> photoAlbumsDock()
            else -> keyboard()
        }
    }

    private fun selectedPhotoBucket(): String? {
        return prefs().getString(PHOTO_BUCKET_PREF, null)?.takeIf { it.isNotBlank() }
    }

    private fun selectedPhotoId(): Long? {
        val id = prefs().getLong(PHOTO_SELECTED_ID_PREF, -1L)
        return id.takeIf { it > 0L }
    }

    private fun persistSelectedPhoto(id: Long) {
        prefs().edit().putLong(PHOTO_SELECTED_ID_PREF, id).apply()
    }

    private fun favoritePhotoIds(): Set<Long> {
        return prefs().getString(PHOTO_FAVORITES_PREF, "").orEmpty()
            .split(',')
            .mapNotNull { it.toLongOrNull() }
            .toSet()
    }

    private fun saveFavoritePhotoIds(ids: Set<Long>) {
        prefs().edit().putString(PHOTO_FAVORITES_PREF, ids.sortedDescending().joinToString(",")).apply()
    }

    private fun toggleFavoritePhoto(photoId: Long) {
        val next = favoritePhotoIds().toMutableSet()
        val added = if (photoId in next) {
            next.remove(photoId)
            false
        } else {
            next.add(photoId)
            true
        }
        saveFavoritePhotoIds(next)
        Toast.makeText(this, if (added) "Added to ZEISS Favorites." else "Removed from ZEISS Favorites.", Toast.LENGTH_SHORT).show()
        if (openPane?.kind == PaneKind.PHOTOS) showPane(photosTarget(), animate = false)
        refreshKeyboardDock()
    }

    private fun removeFavoritePhoto(photoId: Long) {
        val next = favoritePhotoIds().filterNot { it == photoId }.toSet()
        saveFavoritePhotoIds(next)
    }

    private fun requestTrashPhoto(photo: LauncherPhoto) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, "Photo trash requires Android 11 or newer.", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            pendingTrashPhotoId = photo.id
            val request = MediaStore.createTrashRequest(contentResolver, listOf(photo.uri), true)
            photoTrashLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }.onFailure {
            pendingTrashPhotoId = null
            Toast.makeText(this, "Couldn't move this image to trash.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun musicTheme(): String {
        return prefs().getString(MUSIC_THEME_PREF, MUSIC_THEME_MUSIC1) ?: MUSIC_THEME_MUSIC1
    }

    private fun refreshKeyboardDock() {
        if (!::keyboardDockView.isInitialized) return
        if (keyboardDockView.parent == null) return
        keyViews.clear()
        keyBounds.clear()
        keyboardDockView.removeAllViews()
        keyboardDockView.addView(dockedInputView(), FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        keyboardDockView.post { captureKeyBounds() }
    }

    private fun clickWheelDock(): View {
        return FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                0xFF101216.toInt(),
                0xFF050506.toInt(),
                0xFF000000.toInt()
            )).apply {
                setStroke(dp(1), 0xFF20232A.toInt())
            }
            val wheelSize = clickWheelSize()
            addView(WheelWellView(context), FrameLayout.LayoutParams(wheelSize + dp(18), wheelSize + dp(18), Gravity.CENTER))
            addView(ClickWheelView(context).apply {
                onCenter = {
                    haptic(this)
                    mediaSessionSource.togglePlayPause()
                }
                onLeft = {
                    haptic(this)
                    mediaSessionSource.skipToPrevious()
                }
                onRight = {
                    haptic(this)
                    mediaSessionSource.skipToNext()
                }
                onBottom = {
                    haptic(this)
                    mediaSessionSource.togglePlayPause()
                }
                onTop = {
                    haptic(this)
                    mediaSessionSource.openSourceApp()
                }
                onScroll = { steps ->
                    mediaSessionSource.adjustVolume(steps)
                    showVolumeHud()
                }
            }, FrameLayout.LayoutParams(wheelSize, wheelSize, Gravity.CENTER))
        }
    }

    // ── Spotify preload ───────────────────────────────────────────────────────

    private fun preloadSpotifyLibrary() {
        mediaUiScope.launch(Dispatchers.IO) {
            try {
                coroutineScope {
                    val recentD = async { spotifyApi.getRecentlyPlayed(limit = 50) }
                    val playlistsD = async { spotifyApi.getPlaylists(limit = 50) }
                    val topD = async { spotifyApi.getTopTracks(limit = 50, timeRange = "long_term") }
                    val likedD = async { spotifyApi.getLikedSongs(limit = 50) }
                    val recent = recentD.await()
                    val playlists = playlistsD.await()
                    val top = topD.await()
                    val liked = likedD.await()
                    val recentArts = recent.map { t -> async { t.albumArtUrl?.let { runCatching { spotifyApi.fetchAlbumArt(it) }.getOrNull() } } }.awaitAll()
                    val playlistArts = playlists.map { p -> async { p.imageUrl?.let { runCatching { spotifyApi.fetchAlbumArt(it) }.getOrNull() } } }.awaitAll()
                    val topArts = top.map { t -> async { t.albumArtUrl?.let { runCatching { spotifyApi.fetchAlbumArt(it) }.getOrNull() } } }.awaitAll()
                    val likedArts = liked.map { t -> async { t.albumArtUrl?.let { runCatching { spotifyApi.fetchAlbumArt(it) }.getOrNull() } } }.awaitAll()
                    spotifyCachedRecent = recent
                    spotifyCachedRecentArts = recentArts
                    spotifyCachedPlaylists = playlists
                    spotifyCachedPlaylistArts = playlistArts
                    spotifyCachedTopTracks = top
                    spotifyCachedTopArts = topArts
                    spotifyCachedLikedSongs = liked
                    spotifyCachedLikedArts = likedArts
                }
            } catch (_: Exception) {}
        }
    }

    // ── Compact playlist / track-list drill-down ─────────────────────────────

    fun showCompactPlaylistDetail(playlist: SpotifyPlaylist) {
        val overlay = spotifyCompactOverlay as? android.view.ViewGroup ?: return
        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0A0C0F.toInt())
            translationX = overlay.width.toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(14), dp(10))
        }
        header.addView(TextView(this).apply {
            text = "‹"; textSize = 24f; setTextColor(0xFF6B7280.toInt())
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { haptic(this); closeCompactPlaylistDetail() }
        })
        header.addView(TextView(this).apply {
            text = playlist.name; textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(0xFFF3F0E7.toInt()); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val actRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(10))
        }
        fun aBtn(lbl: String, bg: Int, fg: Int, click: () -> Unit) = TextView(this).apply {
            text = lbl; textSize = 11f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(fg)
            background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(18).toFloat() }
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { haptic(this); click() }
        }
        actRow.addView(aBtn("▶  Play All", 0xFF1ED760.toInt(), 0xFF000000.toInt()) {
            mediaUiScope.launch { spotifyApi.playContext(playlist.uri, 0) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        actRow.addView(aBtn("⇄  Shuffle", 0xFF1A1D22.toInt(), 0xFFCED2DA.toInt()) {
            mediaUiScope.launch { spotifyApi.setShuffle(true); spotifyApi.playContext(playlist.uri, 0) }
        })

        val trackScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
        }
        val trackList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(24))
        }
        trackList.addView(TextView(this).apply {
            text = "Loading…"; textSize = 12f; setTextColor(0xFF6B7280.toInt())
            setPadding(dp(16), dp(12), 0, 0)
        })
        trackScroll.addView(trackList)

        detail.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        detail.addView(View(this).apply { setBackgroundColor(0x12FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        detail.addView(actRow, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        detail.addView(View(this).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        detail.addView(trackScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        overlay.addView(detail, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        detail.animate().translationX(0f).setDuration(280)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        compactDetailView = detail
        compactLibraryScrollRef = trackScroll; compactLibraryTargetY = 0
        compactSelectedView = null

        mediaUiScope.launch {
            val tracks = withContext(Dispatchers.IO) { spotifyApi.getPlaylistTracks(playlist.id, limit = 100) }
            val arts = withContext(Dispatchers.IO) {
                coroutineScope { tracks.map { t -> async { t.albumArtUrl?.let { runCatching { spotifyApi.fetchAlbumArt(it) }.getOrNull() } } }.awaitAll() }
            }
            withContext(Dispatchers.Main) {
                trackList.removeAllViews()
                if (tracks.isEmpty()) {
                    trackList.addView(TextView(this@MainActivity).apply {
                        text = "No tracks found"; textSize = 12f; setTextColor(0xFF6B7280.toInt()); setPadding(dp(16), dp(12), 0, 0)
                    })
                } else {
                    tracks.forEachIndexed { i, track ->
                        trackList.addView(compactTrackRow(i + 1, arts.getOrNull(i), track.name, track.artist, track.popularity) {
                            mediaUiScope.launch { spotifyApi.playContext(playlist.uri, i) }
                        })
                        if (i < tracks.lastIndex) trackList.addView(View(this@MainActivity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                    }
                }
            }
        }
    }

    fun compactTrackRow(num: Int?, art: android.graphics.Bitmap?, title: String, artist: String, popularity: Int = 0, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56); setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { haptic(this); popThenRun(this) { onClick() } }
            if (num != null) {
                addView(TextView(this@MainActivity).apply {
                    text = "$num"; textSize = 10f; gravity = Gravity.CENTER
                    setTextColor(0xFF4B5563.toInt()); minWidth = dp(28)
                })
            }
            val thumb = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (art != null) setImageBitmap(art) else setBackgroundColor(0xFF1A1D22.toInt())
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(4).toFloat()) }
                }
                clipToOutline = true
            }
            addView(thumb, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
            col.addView(TextView(this@MainActivity).apply {
                text = title; textSize = 12.5f; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(0xFFF3F0E7.toInt())
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            })
            val subRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            subRow.addView(TextView(this@MainActivity).apply {
                text = artist; textSize = 10.5f; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(0xFF6B7280.toInt())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (popularity > 0) {
                // Small popularity bar — Spotify doesn't share actual play counts
                val barTrack = FrameLayout(this@MainActivity).apply {
                    background = GradientDrawable().apply { setColor(0xFF1A1D22.toInt()); cornerRadius = dp(2).toFloat() }
                }
                val barFill = View(this@MainActivity).apply {
                    background = GradientDrawable().apply { setColor(0xFF1ED760.toInt()); cornerRadius = dp(2).toFloat() }
                }
                barTrack.addView(barFill, FrameLayout.LayoutParams((dp(40) * popularity / 100f).toInt(), dp(3)))
                barTrack.addView(View(this@MainActivity), FrameLayout.LayoutParams(dp(40), dp(3)))
                subRow.addView(barTrack, LinearLayout.LayoutParams(dp(40), dp(3)).apply { marginStart = dp(8) })
            }
            col.addView(subRow, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    // ── Compact Spotify overlay on contentFrame ───────────────────────────────

    fun showCompactSpotifyLibrary() {
        if (spotifyCompactOverlay != null) return
        val SpotifyGreen = 0xFF1ED760.toInt()
        val CardBg = 0xFF141720.toInt()

        val overlay = object : LinearLayout(this) {
            private var swipeStartX = 0f; private var swipeStartY = 0f
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> { swipeStartX = ev.rawX; swipeStartY = ev.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - swipeStartX; val dy = ev.rawY - swipeStartY
                        if (dx < -dp(40) && kotlin.math.abs(dy) < kotlin.math.abs(dx) * 0.65f) return true
                    }
                }
                return false
            }
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (ev.action == MotionEvent.ACTION_UP) {
                    val dx = ev.rawX - swipeStartX; val dy = ev.rawY - swipeStartY
                    if (dx < -dp(40) && kotlin.math.abs(dy) < kotlin.math.abs(dx) * 0.65f) {
                        dismissCompactSpotifyLibrary(); return true
                    }
                }
                return true
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                0xFF0B0D10.toInt(), 0xFF07080A.toInt()
            )).apply { setStroke(dp(1), 0xFF181B20.toInt()) }
            translationX = contentFrame.width.toFloat()
        }

        val tabLabels = listOf("TOP", "RECENT", "PLAYLISTS", "SEARCH")
        val tabViews = mutableListOf<TextView>()
        var activeTab = 0

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(4))
        }
        header.addView(ImageView(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(SpotifyGreen) }
        }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) })
        header.addView(mono("SPOTIFY", 8.5f, SpotifyGreen).apply { letterSpacing = 0.18f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        tabLabels.forEachIndexed { i, lbl ->
            val tv = mono(lbl, 8.5f, if (i == 0) SpotifyGreen else InkDim).apply {
                letterSpacing = 0.10f; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(if (i == 0) 10 else 14), dp(12), dp(8), dp(12))
                minimumHeight = dp(48); isClickable = true; isFocusable = false
            }
            tabViews.add(tv); header.addView(tv)
        }

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            // Let pop-scale overshoot draw past row bounds instead of clipping.
            clipChildren = false; clipToPadding = false
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(24))
            clipChildren = false; clipToPadding = false
        }
        scroll.addView(body)
        compactLibraryScrollRef = scroll; compactLibraryTargetY = 0
        compactMainScroll = scroll

        val searchField = EditText(this).apply {
            hint = "Search Spotify…"; textSize = 13f; setTextColor(Ink); setHintTextColor(InkDim)
            setSingleLine(); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply { setColor(0xFF1A1D22.toInt()); cornerRadius = dp(10).toFloat(); setStroke(dp(1), 0xFF2A2E36.toInt()) }
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }
        val searchWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(6))
            visibility = View.GONE; addView(searchField, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val personalizedKeys = listOf("discover weekly", "daily mix", "release radar", "on repeat", "repeat rewind", "time capsule", "your top songs", "wrapped")
        fun isPersonalized(name: String) = personalizedKeys.any { name.lowercase().contains(it) }

        fun gridRow(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; weightSum = 3f
            clipChildren = false; clipToPadding = false
            views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) }) }
            repeat(3 - views.size) { addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) }) }
        }

        fun squareCard(art: android.graphics.Bitmap?, fallback: IntArray, title: String, sub: String, onClick: () -> Unit): View =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; isClickable = true
                background = GradientDrawable().apply { setColor(CardBg); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFF22262E.toInt()) }
                setPadding(dp(8), dp(8), dp(8), dp(9))
                setOnClickListener { haptic(this); popThenRun(this) { onClick() } }
                val frame = object : FrameLayout(this@MainActivity) { override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w) }.also { f ->
                    f.clipToOutline = true
                    f.outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat()) } }
                    f.addView(ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        if (art != null) setImageBitmap(art) else background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, fallback).apply { cornerRadius = dp(8).toFloat() }
                    }, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                }
                addView(frame, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(TextView(context).apply { text = title; textSize = 10.5f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink); setPadding(dp(2), dp(6), dp(2), 0) }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(TextView(context).apply { text = sub; textSize = 9.5f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(InkDim); setPadding(dp(2), dp(2), dp(2), 0) }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

        fun populateGrid(items: List<Pair<String, String>>, arts: List<android.graphics.Bitmap?>, fallback: IntArray = intArrayOf(0xFF1A1D22.toInt(), 0xFF0D1014.toInt()), accent: Int = 0, onClick: (Int) -> Unit) {
            items.chunked(3).forEachIndexed { rowIdx, row ->
                val cards = row.mapIndexed { col, (t, s) -> squareCard(arts.getOrNull(rowIdx * 3 + col), fallback, t, s) { onClick(rowIdx * 3 + col) } }
                body.addView(gridRow(*cards.toTypedArray()), LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                body.addView(View(this@MainActivity), LinearLayout.LayoutParams.MATCH_PARENT, dp(10))
            }
        }

        fun sectionLabel(text: String, color: Int = InkDim) = mono(text, 8.5f, color).apply {
            letterSpacing = 0.18f; setPadding(dp(2), dp(6), 0, dp(8))
        }

        fun selectTab(idx: Int) {
            activeTab = idx
            compactLibraryActiveTab = idx
            tabViews.forEachIndexed { i, tv ->
                tv.setTextColor(if (i == idx) SpotifyGreen else InkDim)
                tv.typeface = if (i == idx) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.DEFAULT
            }
            searchWrap.visibility = if (idx == 3) View.VISIBLE else View.GONE
            body.removeAllViews(); scroll.scrollTo(0, 0); compactLibraryTargetY = 0
            when (idx) {
                0 -> { // TOP
                    val top = spotifyCachedTopTracks
                    if (top.isEmpty()) {
                        body.addView(TextView(this).apply { text = "Reconnect Spotify to load your top tracks"; textSize = 12f; setTextColor(InkDim); setPadding(dp(2), dp(8), 0, 0) })
                    } else {
                        body.addView(sectionLabel("YOUR TOP TRACKS  •  ALL TIME", 0xFF8BE8FF.toInt()))
                        val grid = top.take(9)
                        populateGrid(grid.map { it.name to it.artist }, spotifyCachedTopArts.take(9),
                            intArrayOf(0xFF1A2030.toInt(), 0xFF0A1020.toInt())) { i ->
                            mediaUiScope.launch { spotifyApi.playTrack(grid[i].uri) }
                        }
                        body.addView(sectionLabel("ALL ${top.size} TRACKS"))
                        top.forEachIndexed { i, track ->
                            body.addView(compactTrackRow(i + 1, spotifyCachedTopArts.getOrNull(i), track.name, track.artist, track.popularity) {
                                mediaUiScope.launch { spotifyApi.playTrack(track.uri) }
                            })
                            if (i < top.lastIndex) body.addView(View(this@MainActivity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                        }
                    }
                }
                1 -> { // RECENT
                    val recent = spotifyCachedRecent
                    if (recent.isEmpty()) {
                        body.addView(TextView(this).apply { text = "Play something in Spotify to see it here"; textSize = 12f; setTextColor(InkDim); setPadding(dp(2), dp(8), 0, 0) })
                    } else {
                        body.addView(sectionLabel("RECENTLY PLAYED"))
                        populateGrid(recent.map { it.name to it.artist }, spotifyCachedRecentArts) { i ->
                            recent.getOrNull(i)?.let { t -> mediaUiScope.launch { spotifyApi.playTrack(t.uri) } }
                        }
                    }
                }
                2 -> { // PLAYLISTS
                    val liked = spotifyCachedLikedSongs
                    if (liked.isNotEmpty()) {
                        body.addView(compactTrackRow(null, null, "Liked Songs", "${liked.size} songs") {
                            showCompactPlaylistDetail(SpotifyPlaylist("liked", "Liked Songs", "", liked.size, null, "liked"))
                        })
                        body.addView(View(this@MainActivity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                    }
                    val personalized = spotifyCachedPlaylists.indices.filter { isPersonalized(spotifyCachedPlaylists[it].name) }
                    val mine = spotifyCachedPlaylists.indices.filter { !isPersonalized(spotifyCachedPlaylists[it].name) }
                    if (personalized.isNotEmpty()) {
                        body.addView(sectionLabel("MADE FOR YOU", 0xFF8BE8FF.toInt()))
                        populateGrid(personalized.map { spotifyCachedPlaylists[it].name to spotifyCachedPlaylists[it].ownerName.ifBlank { "Spotify" } },
                            personalized.map { spotifyCachedPlaylistArts.getOrNull(it) }, intArrayOf(0xFF1A2830.toInt(), 0xFF0A1018.toInt())
                        ) { i -> personalized.getOrNull(i)?.let { idx2 -> showCompactPlaylistDetail(spotifyCachedPlaylists[idx2]) } }
                    }
                    if (mine.isNotEmpty()) {
                        body.addView(sectionLabel("YOUR PLAYLISTS"))
                        populateGrid(mine.map { spotifyCachedPlaylists[it].name to spotifyCachedPlaylists[it].ownerName.ifBlank { "My playlist" } },
                            mine.map { spotifyCachedPlaylistArts.getOrNull(it) }
                        ) { i -> mine.getOrNull(i)?.let { idx2 -> showCompactPlaylistDetail(spotifyCachedPlaylists[idx2]) } }
                    }
                }
                3 -> { // SEARCH
                    searchField.requestFocus()
                    searchField.postDelayed({
                        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        imm?.showSoftInput(searchField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }, 120)
                }
            }
        }

        tabViews.forEachIndexed { i, tv -> tv.setOnClickListener { selectTab(i) } }
        compactLibrarySelectTab = { idx -> selectTab(idx) }

        var searchJob: Job? = null
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim() ?: ""
                searchJob?.cancel(); body.removeAllViews()
                if (q.length < 2) return
                searchJob = mediaUiScope.launch {
                    delay(280)
                    val tracks = withContext(Dispatchers.IO) { spotifyApi.search(q, limit = 20) }
                    val arts = withContext(Dispatchers.IO) { coroutineScope { tracks.map { t -> async { t.albumArtUrl?.let { url -> runCatching { spotifyApi.fetchAlbumArt(url) }.getOrNull() } } }.awaitAll() } }
                    body.removeAllViews()
                    if (tracks.isEmpty()) {
                        body.addView(TextView(this@MainActivity).apply { text = "No results for \"$q\""; textSize = 12f; setTextColor(InkDim); setPadding(dp(4), dp(10), 0, 0) })
                    } else {
                        tracks.forEachIndexed { i, track ->
                            body.addView(compactTrackRow(null, arts.getOrNull(i), track.name, track.artist, track.popularity) {
                                mediaUiScope.launch { spotifyApi.playTrack(track.uri) }
                            })
                            if (i < tracks.lastIndex) body.addView(View(this@MainActivity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                        }
                    }
                }
            }
        })

        selectTab(0)

        overlay.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        overlay.addView(View(this).apply { setBackgroundColor(0x10FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        overlay.addView(searchWrap, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        overlay.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        spotifyCompactOverlay = overlay
        contentFrame.addView(overlay, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        overlay.animate().translationX(0f).setDuration(380)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2.0f)).start()
    }

    fun dismissCompactSpotifyLibrary() {
        val overlay = spotifyCompactOverlay ?: return
        spotifyCompactOverlay = null
        compactLibraryScrollRef = null; compactLibraryTargetY = 0; compactOverscroll = 0f
        compactSelectedView = null
        compactLibrarySelectTab = null; compactLibraryActiveTab = 0
        compactDetailView = null; compactMainScroll = null
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(overlay.windowToken, 0)
        overlay.animate().translationX(contentFrame.width.toFloat()).setDuration(280)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.8f))
            .withEndAction { contentFrame.removeView(overlay) }.start()
    }

    fun showSpotifySearchOverlay() {
        if (!spotifyAuth.isConnected) return
        // If compact library isn't open yet, open it first then add search on top
        if (spotifyCompactOverlay == null) showCompactSpotifyLibrary()

        val parent = spotifyCompactOverlay ?: return

        // Check if search overlay already exists
        if (parent.findViewWithTag<View>("search_overlay") != null) return

        val searchOverlay = LinearLayout(this).apply {
            tag = "search_overlay"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF5101215.toInt())
            translationY = -parent.height.toFloat().coerceAtLeast(dp(400).toFloat())
        }

        val searchField = EditText(this).apply {
            hint = "Search Spotify…"
            textSize = 15f
            setTextColor(0xFFF3F0E7.toInt())
            setHintTextColor(0xFF6B7280.toInt())
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply {
                setColor(0xFF1A1D22.toInt())
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0xFF2E333B.toInt())
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        val resultsScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val resultsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(16))
        }
        resultsScroll.addView(resultsList, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val dismissBtn = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(0xFF8B8F99.toInt())
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener {
                haptic(this)
                searchOverlay.animate().translationY(-searchOverlay.height.toFloat())
                    .setDuration(260).withEndAction { (parent as? android.view.ViewGroup)?.removeView(searchOverlay) }.start()
                // Hide soft keyboard
                val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                imm?.hideSoftInputFromWindow(searchField.windowToken, 0)
            }
        }
        headerRow.addView(searchField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(dismissBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        searchOverlay.addView(headerRow, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        searchOverlay.addView(resultsScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        (parent as? android.view.ViewGroup)?.addView(searchOverlay,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Register as scroll ref so click wheel navigates results
        compactLibraryScrollRef = resultsScroll; compactLibraryTargetY = 0

        // Animate in from top
        searchOverlay.post {
            searchOverlay.translationY = -searchOverlay.height.toFloat()
            searchOverlay.animate().translationY(0f).setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }

        // Auto-focus and show keyboard
        searchField.requestFocus()
        searchField.postDelayed({
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            imm?.showSoftInput(searchField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 180)

        fun buildResultRow(track: SpotifyTrack, art: android.graphics.Bitmap?, idx: Int): View =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setBackgroundColor(if (idx % 2 == 0) 0x00000000 else 0x08FFFFFF)
                setOnClickListener {
                    haptic(this)
                    mediaUiScope.launch { spotifyApi.playTrack(track.uri) }
                }
                val thumb = ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    if (art != null) setImageBitmap(art)
                    else setBackgroundColor(0xFF1A1D22.toInt())
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(4).toFloat()) }
                    }
                    clipToOutline = true
                }
                addView(thumb, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
                val col = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                col.addView(TextView(this@MainActivity).apply {
                    text = track.name; textSize = 13f; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(0xFFF3F0E7.toInt())
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                })
                col.addView(TextView(this@MainActivity).apply {
                    text = track.artist; textSize = 11f; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(0xFF8B8F99.toInt())
                })
                addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }

        var searchJob: Job? = null
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim() ?: ""
                searchJob?.cancel()
                resultsList.removeAllViews()
                if (q.length < 2) return
                searchJob = mediaUiScope.launch {
                    delay(260)
                    val tracks = spotifyApi.search(q, limit = 20)
                    val arts = tracks.map { t ->
                        t.albumArtUrl?.let { url -> runCatching { spotifyApi.fetchAlbumArt(url) }.getOrNull() }
                    }
                    withContext(Dispatchers.Main) {
                        resultsList.removeAllViews()
                        if (tracks.isEmpty()) {
                            resultsList.addView(TextView(this@MainActivity).apply {
                                text = "No results for \"$q\""; textSize = 12f
                                setTextColor(0xFF6B7280.toInt()); setPadding(dp(14), dp(12), 0, 0)
                            })
                        } else {
                            tracks.forEachIndexed { i, t -> resultsList.addView(buildResultRow(t, arts.getOrNull(i), i)) }
                        }
                    }
                }
            }
        })
    }

    // ── Music dock: library pager + click wheel ──────────────────────────────

    // ── iPod-style wheel selection over the compact library ──────────────────
    // Rotation moves a highlighted row/card instead of scrolling the page; the
    // center button activates it. Selectable items are discovered by walking
    // the active scroll container, so tab switches, playlist drill-down, and
    // search results all work without registration bookkeeping.
    private var compactSelectedView: View? = null
    // Bridge to the overlay's local selectTab() so the wheel's press-and-hold
    // ‹‹/›› can page through TOP / RECENT / PLAYLISTS / SEARCH.
    private var compactLibrarySelectTab: ((Int) -> Unit)? = null
    private var compactLibraryActiveTab = 0
    // Playlist/folder drill-down state: LIBRARY acts as back while one is open,
    // and the wheel's scroll target is restored to the main list on close.
    private var compactDetailView: View? = null
    private var compactMainScroll: ScrollView? = null

    private fun closeCompactPlaylistDetail(): Boolean {
        val detail = compactDetailView ?: return false
        compactDetailView = null
        val slideOut = (spotifyCompactOverlay?.width ?: detail.width).toFloat()
        detail.animate().translationX(slideOut).setDuration(240)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.8f))
            .withEndAction { (detail.parent as? ViewGroup)?.removeView(detail) }.start()
        compactLibraryScrollRef = compactMainScroll
        compactLibraryTargetY = compactMainScroll?.scrollY ?: 0
        compactSelectedView = null
        compactOverscroll = 0f
        return true
    }

    private fun compactLibraryTabStep(delta: Int): Boolean {
        val select = compactLibrarySelectTab ?: return false
        val next = (compactLibraryActiveTab + delta).coerceIn(0, 3)
        if (next == compactLibraryActiveTab) return false
        select(next)
        return true
    }

    private fun compactSelectableItems(): List<View> {
        val root = compactLibraryScrollRef?.getChildAt(0) as? ViewGroup ?: return emptyList()
        val out = mutableListOf<View>()
        fun walk(vg: ViewGroup) {
            for (i in 0 until vg.childCount) {
                val c = vg.getChildAt(i)
                if (c.visibility != View.VISIBLE) continue
                if (c.isClickable) out.add(c) else if (c is ViewGroup) walk(c)
            }
        }
        walk(root)
        return out
    }

    private fun compactItemTop(v: View): Int {
        val content = compactLibraryScrollRef?.getChildAt(0) ?: return 0
        var y = 0
        var cur: View = v
        while (cur !== content) {
            y += cur.top
            cur = cur.parent as? View ?: break
        }
        return y
    }

    private fun firstVisibleCompactIndex(sv: ScrollView, items: List<View>): Int {
        items.forEachIndexed { i, v ->
            if (compactItemTop(v) + v.height > sv.scrollY + dp(4)) return i
        }
        return 0
    }

    private fun setCompactSelection(items: List<View>, idx: Int) {
        val v = items.getOrNull(idx) ?: return
        if (compactSelectedView !== v) {
            compactSelectedView?.foreground = null
            compactSelectedView = v
            v.foreground = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(2), 0xFF1ED760.toInt())
                setColor(0x141ED760)
            }
        }
        scrollCompactSelectionIntoView(v)
    }

    private fun scrollCompactSelectionIntoView(v: View) {
        val sv = compactLibraryScrollRef ?: return
        val content = sv.getChildAt(0) ?: return
        val top = compactItemTop(v)
        val bottom = top + v.height
        val margin = dp(10)
        val cur = sv.scrollY
        var next = cur
        if (top < cur + margin) next = top - margin
        else if (bottom > cur + sv.height - margin) next = bottom - sv.height + margin
        val maxY = (content.height - sv.height).coerceAtLeast(0)
        next = next.coerceIn(0, maxY)
        compactLibraryTargetY = next
        if (next != cur) sv.smoothScrollTo(0, next)
    }

    // Elastic edge stretch: wheel ticks past the list edge translate the library
    // content with diminishing resistance, then spring back on release.
    private var compactOverscroll = 0f

    private fun applyCompactOverscroll(sv: ScrollView, overflowPx: Int) {
        val child = sv.getChildAt(0) ?: return
        val max = dp(48).toFloat()
        child.animate().cancel()
        val resistance = 1f - (kotlin.math.abs(compactOverscroll) / max).coerceIn(0f, 1f)
        compactOverscroll = (compactOverscroll - overflowPx * 0.45f * resistance).coerceIn(-max, max)
        child.translationY = compactOverscroll
    }

    private fun releaseCompactOverscroll() {
        if (compactOverscroll == 0f) return
        compactOverscroll = 0f
        val child = compactLibraryScrollRef?.getChildAt(0) ?: return
        child.animate().translationY(0f).setDuration(320)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
            .start()
    }

    private fun musicDockView(): View {
        val wheelBg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xFF101216.toInt(), 0xFF050506.toInt(), 0xFF000000.toInt()
        )).apply { setStroke(dp(1), 0xFF20232A.toInt()) }
        return FrameLayout(this).apply {
            background = wheelBg
            val wheelSize = clickWheelSize()
            addView(WheelWellView(context), FrameLayout.LayoutParams(wheelSize + dp(18), wheelSize + dp(18), Gravity.CENTER))
            addView(ClickWheelView(context).apply {
                sourceLabel = if (spotifyAuth.isConnected) "LIBRARY" else "SOURCE"
                onLibrary = if (spotifyAuth.isConnected) ({
                    haptic(this)
                    if (spotifyCompactOverlay != null) {
                        // Inside a playlist/folder → LIBRARY steps back first;
                        // at the top level it dismisses the overlay.
                        if (!closeCompactPlaylistDetail()) dismissCompactSpotifyLibrary()
                    } else {
                        // Single tap → compact library
                        showCompactSpotifyLibrary()
                    }
                }) else null
                // Press-and-hold LIBRARY → full-screen library.
                onLongLibrary = if (spotifyAuth.isConnected) ({ showSpotifyFullLibrary() }) else null
                // Press-and-hold OK → search.
                onLongCenter = if (spotifyAuth.isConnected) ({ showSpotifySearchOverlay() }) else null
                onCenter = {
                    haptic(this)
                    val sel = compactSelectedView
                    if (compactLibraryScrollRef != null && sel != null && sel.isAttachedToWindow) sel.performClick()
                    else mediaSessionSource.togglePlayPause()
                }
                onLeft = { haptic(this); mediaSessionSource.skipToPrevious() }
                onRight = { haptic(this); mediaSessionSource.skipToNext() }
                onBottom = { haptic(this); mediaSessionSource.togglePlayPause() }
                // Press-and-hold ‹‹/›› pages through library tabs — only while
                // the compact library is open; elsewhere a hold stays a skip.
                onLongLeft = {
                    if (spotifyCompactOverlay != null) { compactLibraryTabStep(-1); true } else false
                }
                onLongRight = {
                    if (spotifyCompactOverlay != null) { compactLibraryTabStep(1); true } else false
                }
                // Flywheel glide only makes sense on the library list — a seek
                // that keeps drifting after finger-up would feel broken.
                flingAllowed = { compactLibraryScrollRef != null }
                onScrollEnd = { releaseCompactOverscroll() }
                onScroll = { steps ->
                    val sv = compactLibraryScrollRef
                    if (sv != null) {
                        val items = compactSelectableItems()
                        if (items.isNotEmpty()) {
                            val curIdx = items.indexOf(compactSelectedView)
                            // No selection yet → start from the first visible item.
                            val target = if (curIdx >= 0) curIdx + steps
                                         else firstVisibleCompactIndex(sv, items) + if (steps > 0) steps - 1 else steps
                            val clamped = target.coerceIn(0, items.lastIndex)
                            if (target != clamped) {
                                // Pushed past the list edge: stretch, and kill any
                                // glide now so the spring-back doesn't wait for
                                // leftover momentum to decay.
                                applyCompactOverscroll(sv, (target - clamped) * dp(56))
                                cancelFling()
                            } else if (compactOverscroll != 0f) {
                                releaseCompactOverscroll()
                            }
                            setCompactSelection(items, clamped)
                        }
                    } else {
                        val info = mediaSessionSource.nowPlaying.value
                        if (info != null && info.durationMs > 0) {
                            val elapsed = android.os.SystemClock.elapsedRealtime() - info.lastUpdateElapsedMs
                            val pos = if (info.isPlaying) (info.positionMs + elapsed).coerceAtMost(info.durationMs) else info.positionMs
                            val seekMs = (pos + steps * 8_000L).coerceIn(0L, info.durationMs)
                            mediaSessionSource.seekTo(seekMs)
                        }
                    }
                }
            }, FrameLayout.LayoutParams(wheelSize, wheelSize, Gravity.CENTER))
        }
    }

    // Two-page swipe container: smooth paging with velocity snapping.
    inner class DockPageSwiper(context: Context) : ViewGroup(context) {
        private val scroller = Scroller(context, DecelerateInterpolator())
        private val velocity = VelocityTracker.obtain()
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var currentPage = 0

        fun addPage(v: View) { addView(v) }

        fun goToPage(page: Int, animate: Boolean = true) {
            currentPage = page.coerceIn(0, childCount - 1)
            val target = currentPage * width
            if (animate) {
                scroller.startScroll(scrollX, 0, target - scrollX, 0, 260)
                invalidate()
            } else {
                scroller.abortAnimation()
                scrollTo(target, 0)
            }
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) {
                scrollTo(scroller.currX, 0)
                invalidate()
            }
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            setMeasuredDimension(widthSpec, heightSpec)
            val pw = MeasureSpec.getSize(widthSpec)
            val ph = MeasureSpec.getSize(heightSpec)
            for (i in 0 until childCount) {
                getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ph, MeasureSpec.EXACTLY)
                )
            }
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val pw = r - l
            for (i in 0 until childCount) {
                getChildAt(i).layout(i * pw, 0, (i + 1) * pw, b - t)
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(ev.x - downX)
                    val dy = kotlin.math.abs(ev.y - downY)
                    if (!dragging && dx > dp(8) && dx > dy * 1.3f) {
                        dragging = true
                        if (!scroller.isFinished) scroller.abortAnimation()
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            velocity.addMovement(ev)
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!scroller.isFinished) scroller.abortAnimation()
                    downX = ev.x
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val raw = (downX - ev.x + currentPage * width).toInt()
                    scrollTo(raw.coerceIn(0, (childCount - 1) * width), 0)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    velocity.computeCurrentVelocity(1000)
                    val vx = velocity.xVelocity
                    val page = when {
                        vx < -600 -> currentPage + 1
                        vx > 600 -> currentPage - 1
                        scrollX > currentPage * width + width / 2 -> currentPage + 1
                        scrollX < currentPage * width - width / 2 -> currentPage - 1
                        else -> currentPage
                    }
                    goToPage(page)
                    velocity.clear()
                    dragging = false
                }
            }
            return true
        }
    }

    // Spotify library page: header + horizontal scrolling track cards.
    private fun spotifyLibraryPage(onSwipeToWheel: () -> Unit): View {
        val SpotifyGreen = 0xFF1ED760.toInt()
        val CardBg = 0xFF141720.toInt()
        val CardStroke = 0xFF22262E.toInt()

        val container = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                0xFF0B0D10.toInt(), 0xFF07080A.toInt(), 0xFF030304.toInt()
            )).apply { setStroke(dp(1), 0xFF181B20.toInt()) }
        }

        // ── Scrollable body (behind header) ──────────────────────────────────
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        scroll.addView(body)

        // ── Header row: ● SPOTIFY  RECENT · PLAYLISTS · SEARCH  WHEEL › ─────
        val tabLabels = listOf("RECENT", "PLAYLISTS", "SEARCH")
        val tabViews = mutableListOf<TextView>()
        var activeTab = 0

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(12), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF0B0D10.toInt(), 0x000B0D10)).apply { }
        }

        // Spotify dot + label (tap to expand full library)
        header.addView(ImageView(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(SpotifyGreen) }
        }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(5) })
        header.addView(mono("SPOTIFY  ↑", 8.5f, SpotifyGreen).apply {
            letterSpacing = 0.18f
            isClickable = true
            setOnClickListener {
                haptic(this)
                showSpotifyFullLibrary()
            }
        })

        // Spacer
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        // Tab links
        tabLabels.forEachIndexed { i, label ->
            val tv = mono(label, 8.5f, if (i == 0) SpotifyGreen else InkDim).apply {
                letterSpacing = 0.10f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(8), dp(12))
                minimumHeight = dp(48)
                isClickable = true; isFocusable = false
            }
            tabViews.add(tv)
            header.addView(tv)
        }

        // Wheel link
        header.addView(mono("WHEEL ›", 8.5f, InkDim).apply {
            letterSpacing = 0.08f
            setPadding(dp(10), dp(3), 0, dp(3))
            isClickable = true
            setOnClickListener { onSwipeToWheel() }
        })

        // Search field (hidden until SEARCH tab active)
        val searchField = EditText(this).apply {
            hint = "Search Spotify…"
            textSize = 12f
            setTextColor(Ink)
            setHintTextColor(InkDim)
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply {
                setColor(0xFF1A1D22.toInt()); cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0xFF2A2E36.toInt())
            }
            setPadding(dp(12), dp(7), dp(12), dp(7))
            visibility = View.GONE
        }
        val searchWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(6))
            visibility = View.GONE
            addView(searchField, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        outer.addView(View(this).apply { setBackgroundColor(0x10FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        outer.addView(searchWrap, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        outer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        container.addView(outer, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        // ── Card helpers ──────────────────────────────────────────────────────
        fun gridRow(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; weightSum = 2f
            views.forEachIndexed { i, v ->
                addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(10) })
            }
            if (views.size == 1) addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(10) })
        }

        fun squareCard(art: android.graphics.Bitmap?, fallback: IntArray, title: String, sub: String, stroke: Int = CardStroke, onClick: () -> Unit): View =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; isClickable = true
                background = GradientDrawable().apply { setColor(CardBg); cornerRadius = dp(14).toFloat(); setStroke(dp(1), stroke) }
                setPadding(dp(8), dp(8), dp(8), dp(9))
                setOnClickListener { haptic(this); onClick() }
                val frame = object : FrameLayout(this@MainActivity) { override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w) }.also { f ->
                    f.clipToOutline = true
                    f.outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat()) } }
                    f.addView(ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        if (art != null) setImageBitmap(art) else background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, fallback).apply { cornerRadius = dp(8).toFloat() }
                    }, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                }
                addView(frame, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(TextView(context).apply { text = title; textSize = 11f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink); setPadding(dp(2), dp(7), dp(2), 0) }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(TextView(context).apply { text = sub; textSize = 9.5f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(InkDim); setPadding(dp(2), dp(2), dp(2), 0) }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

        fun populateGrid(items: List<Pair<String, String>>, arts: List<android.graphics.Bitmap?>, fallback: IntArray = intArrayOf(0xFF1A1D22.toInt(), 0xFF0D1014.toInt()), onClick: (Int) -> Unit) {
            items.chunked(2).forEachIndexed { rowIdx, row ->
                val cards = row.mapIndexed { col, (t, s) -> squareCard(arts.getOrNull(rowIdx * 2 + col), fallback, t, s) { onClick(rowIdx * 2 + col) } }
                body.addView(gridRow(*cards.toTypedArray()), LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                body.addView(View(this@MainActivity), LinearLayout.LayoutParams.MATCH_PARENT, dp(10))
            }
        }


        val personalizedKeys = listOf("discover weekly", "daily mix", "release radar", "on repeat", "repeat rewind", "time capsule", "your top songs", "wrapped")
        fun isPersonalized(name: String) = personalizedKeys.any { name.lowercase().contains(it) }

        // ── Tab switching ─────────────────────────────────────────────────────
        var searchJob: Job? = null

        fun selectTab(idx: Int) {
            activeTab = idx
            tabViews.forEachIndexed { i, tv ->
                tv.setTextColor(if (i == idx) SpotifyGreen else InkDim)
                tv.typeface = if (i == idx) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.DEFAULT
            }
            searchWrap.visibility = if (idx == 2) View.VISIBLE else View.GONE
            body.removeAllViews()
            searchJob?.cancel()

            when (idx) {
                0 -> { // RECENT
                    if (spotifyCachedRecent.isEmpty()) {
                        body.addView(TextView(this@MainActivity).apply { text = "Loading…"; textSize = 11f; setTextColor(InkDim); setPadding(dp(2), dp(8), 0, 0) })
                    } else {
                        populateGrid(spotifyCachedRecent.map { it.name to it.artist }, spotifyCachedRecentArts) { i ->
                            spotifyCachedRecent.getOrNull(i)?.let { t -> mediaUiScope.launch { spotifyApi.playTrack(t.uri) } }
                        }
                    }
                }
                1 -> { // PLAYLISTS
                    if (spotifyCachedPlaylists.isEmpty()) {
                        body.addView(TextView(this@MainActivity).apply { text = "Loading…"; textSize = 11f; setTextColor(InkDim); setPadding(dp(2), dp(8), 0, 0) })
                    } else {
                        val personalized = spotifyCachedPlaylists.indices.filter { isPersonalized(spotifyCachedPlaylists[it].name) }
                        val mine = spotifyCachedPlaylists.indices.filter { !isPersonalized(spotifyCachedPlaylists[it].name) }
                        if (personalized.isNotEmpty()) {
                            body.addView(mono("MADE FOR YOU", 8.5f, 0xFF8BE8FF.toInt()).apply { letterSpacing = 0.18f; setPadding(dp(2), dp(6), 0, dp(6)) })
                            populateGrid(
                                personalized.map { spotifyCachedPlaylists[it].name to "${spotifyCachedPlaylists[it].trackCount} tracks" },
                                personalized.map { spotifyCachedPlaylistArts.getOrNull(it) },
                                intArrayOf(0xFF1A2830.toInt(), 0xFF0A1018.toInt())
                            ) { i -> personalized.getOrNull(i)?.let { idx2 -> mediaUiScope.launch { spotifyApi.playContext(spotifyCachedPlaylists[idx2].uri) } } }
                        }
                        if (mine.isNotEmpty()) {
                            body.addView(mono("YOUR PLAYLISTS", 8.5f, InkDim).apply { letterSpacing = 0.18f; setPadding(dp(2), if (personalized.isNotEmpty()) dp(4) else dp(6), 0, dp(6)) })
                            populateGrid(
                                mine.map { spotifyCachedPlaylists[it].name to "${spotifyCachedPlaylists[it].trackCount} tracks" },
                                mine.map { spotifyCachedPlaylistArts.getOrNull(it) }
                            ) { i -> mine.getOrNull(i)?.let { idx2 -> mediaUiScope.launch { spotifyApi.playContext(spotifyCachedPlaylists[idx2].uri) } } }
                        }
                        if (spotifyCachedPlaylists.isEmpty()) {
                            body.addView(TextView(this@MainActivity).apply { text = "No playlists found — reconnect Spotify"; textSize = 11f; setTextColor(InkDim); setPadding(dp(2), dp(8), 0, 0) })
                        }
                    }
                }
                2 -> { // SEARCH
                    searchField.requestFocus()
                    searchField.postDelayed({
                        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        imm?.showSoftInput(searchField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }, 120)
                }
            }
        }

        tabViews.forEachIndexed { i, tv -> tv.setOnClickListener { selectTab(i) } }

        // Search watcher
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim() ?: ""
                searchJob?.cancel()
                body.removeAllViews()
                if (q.length < 2) return
                searchJob = mediaUiScope.launch {
                    delay(320)
                    val tracks = withContext(Dispatchers.IO) { spotifyApi.search(q, limit = 10) }
                    val arts = withContext(Dispatchers.IO) { coroutineScope { tracks.map { t -> async { t.albumArtUrl?.let { url -> runCatching { spotifyApi.fetchAlbumArt(url) }.getOrNull() } } }.awaitAll() } }
                    if (tracks.isEmpty()) {
                        body.addView(TextView(this@MainActivity).apply { text = "No results"; textSize = 11f; setTextColor(InkDim); setPadding(dp(2), dp(8), 0, 0) })
                    } else {
                        populateGrid(tracks.map { it.name to it.artist }, arts) { i ->
                            tracks.getOrNull(i)?.let { t -> mediaUiScope.launch { spotifyApi.playTrack(t.uri) } }
                        }
                    }
                }
            }
        })

        // ── Fetch data ────────────────────────────────────────────────────────
        mediaUiScope.launch(Dispatchers.IO) {
            val recentDeferred = async { spotifyApi.getRecentlyPlayed(limit = 10) }
            val playlistsDeferred = async { spotifyApi.getPlaylists(limit = 50) }
            val recentTracks = recentDeferred.await()
            val playlists = playlistsDeferred.await()
            coroutineScope {
                val recentArts = recentTracks.map { t -> async { t.albumArtUrl?.let { url -> runCatching { spotifyApi.fetchAlbumArt(url) }.getOrNull() } } }.awaitAll()
                val playlistArts = playlists.map { p -> async { p.imageUrl?.let { url -> runCatching { spotifyApi.fetchAlbumArt(url) }.getOrNull() } } }.awaitAll()
                spotifyCachedRecent = recentTracks
                spotifyCachedRecentArts = recentArts
                spotifyCachedPlaylists = playlists
                spotifyCachedPlaylistArts = playlistArts
                launch(Dispatchers.Main) { selectTab(activeTab) }
            }
        }

        // Show loading state immediately
        body.addView(TextView(this).apply { text = "Loading…"; textSize = 11f; setTextColor(InkDim); setPadding(dp(2), dp(8), 0, 0) })

        return container
    }

    private fun showSpotifyFullLibrary() {
        if (!requirePro(ProFeature.SPOTIFY_LIBRARY)) return
        val SpotifyGreen = 0xFF1ED760.toInt()
        val CardBg = 0xFF141720.toInt()
        val screenH = resources.displayMetrics.heightPixels
        val statusBarH = run {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else 0
        }

        // ── Scrim + panel ─────────────────────────────────────────────────────
        val scrim = FrameLayout(this).apply { setBackgroundColor(0x00000000); isClickable = true }

        var panelSwipeStartX = 0f; var panelSwipeStartY = 0f
        var panelSwipeConsumed = false
        var panelDismiss: (() -> Unit)? = null
        var panelSelectTab: ((Int) -> Unit)? = null
        var panelActiveTab: (() -> Int)? = null
        val panel = object : LinearLayout(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        panelSwipeStartX = ev.rawX; panelSwipeStartY = ev.rawY; panelSwipeConsumed = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - panelSwipeStartX; val dy = ev.rawY - panelSwipeStartY
                        val adx = kotlin.math.abs(dx); val ady = kotlin.math.abs(dy)
                        // Intercept horizontal swipes (tab change) or long downward swipes (dismiss)
                        if (!panelSwipeConsumed && adx > dp(28) && adx > ady * 1.2f) return true
                        if (!panelSwipeConsumed && dy > dp(120) && ady > adx * 1.4f) return true
                    }
                }
                return false
            }
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (ev.action == MotionEvent.ACTION_UP && !panelSwipeConsumed) {
                    val dx = ev.rawX - panelSwipeStartX; val dy = ev.rawY - panelSwipeStartY
                    val adx = kotlin.math.abs(dx); val ady = kotlin.math.abs(dy)
                    when {
                        // Long swipe down → dismiss
                        dy > dp(120) && ady > adx * 1.4f -> { panelSwipeConsumed = true; panelDismiss?.invoke() }
                        // Swipe right → next tab
                        dx > dp(28) && adx > ady * 1.2f -> {
                            panelSwipeConsumed = true
                            val cur = panelActiveTab?.invoke() ?: 0
                            val next = (cur + 1).coerceAtMost(3)
                            if (next != cur) { haptic(this); panelSelectTab?.invoke(next) }
                        }
                        // Swipe left → previous tab
                        dx < -dp(28) && adx > ady * 1.2f -> {
                            panelSwipeConsumed = true
                            val cur = panelActiveTab?.invoke() ?: 0
                            val prev = (cur - 1).coerceAtLeast(0)
                            if (prev != cur) { haptic(this); panelSelectTab?.invoke(prev) }
                        }
                    }
                }
                return true
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF0F1215.toInt(), 0xFF090B0D.toInt()))
                .apply { setStroke(dp(1), 0xFF1E2228.toInt()) }
            setPadding(0, statusBarH, 0, 0)
            translationY = screenH.toFloat()
            isClickable = true; isFocusable = true
        }

        val decorView = window.decorView as FrameLayout
        scrim.addView(panel, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        decorView.addView(scrim, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        fun dismiss() {
            spotifyFullLibraryDismiss = null
            panel.animate().translationY(screenH.toFloat()).setDuration(300)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.8f))
                .withEndAction { decorView.removeView(scrim) }.start()
            scrim.animate().alpha(0f).setDuration(240).start()
        }
        panelDismiss = ::dismiss
        spotifyFullLibraryDismiss = ::dismiss

        // ── Header row: tabs + dismiss pill ──────────────────────────────────
        val tabLabels = listOf("TOP", "RECENT", "PLAYLISTS", "SEARCH")
        val tabViews = mutableListOf<TextView>()
        var activeTab = 0

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(4))
        }
        tabLabels.forEachIndexed { i, lbl ->
            val tv = mono(lbl, 9.5f, if (i == 0) SpotifyGreen else InkDim).apply {
                letterSpacing = 0.12f
                gravity = Gravity.CENTER_VERTICAL
                typeface = if (i == 0) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.DEFAULT
                // Large touch target — 48dp min height, generous horizontal padding
                setPadding(dp(if (i == 0) 4 else 18), dp(14), dp(12), dp(14))
                minimumHeight = dp(48)
                isClickable = true; isFocusable = false
            }
            tabViews.add(tv); headerRow.addView(tv)
        }
        headerRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        headerRow.addView(TextView(this).apply {
            text = "↓"; textSize = 16f; setTextColor(InkDim); gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(0xFF1A1D22.toInt()); cornerRadius = dp(20).toFloat(); setStroke(dp(1), 0xFF2A2E38.toInt()) }
            setPadding(dp(14), dp(5), dp(14), dp(7)); isClickable = true
            setOnClickListener { haptic(this); dismiss() }
        })

        val searchField = EditText(this).apply {
            hint = "Search Spotify…"; textSize = 14f; setTextColor(Ink); setHintTextColor(InkDim)
            setSingleLine(); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = GradientDrawable().apply { setColor(0xFF1A1D22.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), 0xFF2A2E36.toInt()) }
            setPadding(dp(16), dp(10), dp(16), dp(10)); visibility = View.GONE
        }
        val searchWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(8))
            visibility = View.GONE
            addView(searchField, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(32)) }
        scroll.addView(body)

        panel.addView(headerRow, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        panel.addView(View(this).apply { setBackgroundColor(0x12FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        panel.addView(searchWrap, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── Card / row helpers ────────────────────────────────────────────────
        fun gridRow(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; weightSum = 3f
            views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(10) }) }
            repeat(3 - views.size) { addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(10) }) }
        }

        fun squareCard(art: android.graphics.Bitmap?, fallback: IntArray, title: String, sub: String, stroke: Int = 0xFF22262E.toInt(), onClick: () -> Unit): View =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; isClickable = true
                background = GradientDrawable().apply { setColor(CardBg); cornerRadius = dp(16).toFloat(); setStroke(dp(1), stroke) }
                setPadding(dp(10), dp(10), dp(10), dp(11))
                setOnClickListener { haptic(this); onClick() }
                val frame = object : FrameLayout(this@MainActivity) { override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w) }.apply {
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat()) } }
                    addView(ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        if (art != null) setImageBitmap(art) else background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, fallback).apply { cornerRadius = dp(10).toFloat() }
                    }, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                }
                addView(frame, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(TextView(context).apply { text = title; textSize = 11.5f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink); setPadding(dp(2), dp(8), dp(2), 0) }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(TextView(context).apply { text = sub; textSize = 10f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(InkDim); setPadding(dp(2), dp(3), dp(2), 0) }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

        fun populateGrid(items: List<Pair<String, String>>, arts: List<android.graphics.Bitmap?>, fallback: IntArray = intArrayOf(0xFF1A1D22.toInt(), 0xFF0D1014.toInt()), stroke: Int = 0xFF22262E.toInt(), onClick: (Int) -> Unit) {
            items.chunked(3).forEachIndexed { rowIdx, row ->
                val cards = row.mapIndexed { col, (t, s) -> squareCard(arts.getOrNull(rowIdx * 3 + col), fallback, t, s, stroke) { onClick(rowIdx * 3 + col) } }
                body.addView(gridRow(*cards.toTypedArray()), LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                body.addView(View(this@MainActivity), LinearLayout.LayoutParams.MATCH_PARENT, dp(12))
            }
        }

        fun trackRow(rank: Int?, art: android.graphics.Bitmap?, title: String, sub: String, badge: String = "", onClick: () -> Unit) =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(16), dp(8)); isClickable = true
                setOnClickListener { haptic(this); onClick() }
                if (rank != null) {
                    addView(TextView(this@MainActivity).apply {
                        text = rank.toString(); textSize = 11f; setTextColor(InkDim)
                        gravity = Gravity.CENTER; minWidth = dp(28)
                    })
                }
                val thumb = FrameLayout(this@MainActivity).apply {
                    clipToOutline = true
                    outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(6).toFloat()) } }
                }
                thumb.addView(ImageView(this@MainActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    if (art != null) setImageBitmap(art) else setBackgroundColor(0xFF1A1D22.toInt())
                }, FrameLayout.LayoutParams(dp(44), dp(44)))
                addView(thumb, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4); marginEnd = dp(12) })
                val info = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                info.addView(TextView(this@MainActivity).apply { text = title; textSize = 13f; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                info.addView(TextView(this@MainActivity).apply { text = sub; textSize = 11f; setTextColor(InkDim); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (badge.isNotBlank()) {
                    addView(TextView(this@MainActivity).apply {
                        text = badge; textSize = 9f; setTextColor(SpotifyGreen)
                        background = GradientDrawable().apply { setColor(0x221ED760); cornerRadius = dp(10).toFloat() }
                        setPadding(dp(6), dp(3), dp(6), dp(3))
                    })
                }
            }

        fun sectionLabel(text: String, color: Int = InkDim) =
            mono(text, 9f, color).apply { letterSpacing = 0.18f; setPadding(dp(2), dp(6), 0, dp(10)) }

        // ── Playlist detail slide-in ──────────────────────────────────────────
        fun showPlaylistDetail(playlist: SpotifyPlaylist) {
            val detail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0B0E11.toInt())
                translationX = panel.width.toFloat()
            }
            val dh = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(14), dp(16), dp(10))
            }
            dh.addView(TextView(this).apply {
                text = "‹"; textSize = 26f; setTextColor(InkDim); setPadding(dp(10), 0, dp(10), 0); isClickable = true
                setOnClickListener { detail.animate().translationX(panel.width.toFloat()).setDuration(260).setInterpolator(android.view.animation.AccelerateInterpolator(1.6f)).withEndAction { panel.removeView(detail) }.start() }
            })
            dh.addView(TextView(this).apply {
                text = playlist.name; textSize = 15f; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(8), dp(16), dp(12)); gravity = Gravity.CENTER_VERTICAL }
            fun aBtn(lbl: String, bg: Int, fg: Int, click: () -> Unit) = TextView(this).apply {
                text = lbl; textSize = 12f; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(fg); gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(20).toFloat() }
                setPadding(dp(18), dp(9), dp(18), dp(9)); isClickable = true; setOnClickListener { haptic(this); click() }
            }
            acts.addView(aBtn("▶  Play All", SpotifyGreen, 0xFF000000.toInt()) { mediaUiScope.launch { spotifyApi.playContext(playlist.uri, 0) } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
            acts.addView(aBtn("⇄  Shuffle", 0xFF1A1D22.toInt(), Ink) { mediaUiScope.launch { spotifyApi.setShuffle(true); spotifyApi.playContext(playlist.uri, 0) } })
            val ts = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
            val tb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(32)) }
            ts.addView(tb)
            tb.addView(TextView(this).apply { text = "Loading tracks…"; textSize = 13f; setTextColor(InkDim); setPadding(dp(18), dp(12), 0, 0) })
            detail.addView(dh, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            detail.addView(View(this).apply { setBackgroundColor(0x10FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            detail.addView(acts, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            detail.addView(View(this).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            detail.addView(ts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            panel.addView(detail, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            detail.animate().translationX(0f).setDuration(300).setInterpolator(android.view.animation.DecelerateInterpolator(2.0f)).start()

            mediaUiScope.launch {
                val tracks = withContext(Dispatchers.IO) { spotifyApi.getPlaylistTracks(playlist.id, limit = 100) }
                val arts2 = withContext(Dispatchers.IO) { coroutineScope { tracks.map { t -> async { t.albumArtUrl?.let { runCatching { spotifyApi.fetchAlbumArt(it) }.getOrNull() } } }.awaitAll() } }
                tb.removeAllViews()
                if (tracks.isEmpty()) {
                    tb.addView(TextView(this@MainActivity).apply { text = "No tracks found"; textSize = 13f; setTextColor(InkDim); setPadding(dp(18), dp(12), 0, 0) })
                } else {
                    tracks.forEachIndexed { idx2, track ->
                        tb.addView(trackRow(idx2 + 1, arts2.getOrNull(idx2), track.name, track.artist) {
                            mediaUiScope.launch { spotifyApi.playContext(playlist.uri, idx2) }
                        })
                        tb.addView(View(this@MainActivity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                    }
                }
            }
        }

        // ── Track list detail (for TOP / RECENT / LIKED) ──────────────────────
        fun showTrackListDetail(title: String, tracks: List<SpotifyTrack>, arts: List<android.graphics.Bitmap?>, showRank: Boolean = false) {
            val detail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0B0E11.toInt())
                translationX = panel.width.toFloat()
            }
            val dh = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(14), dp(16), dp(10))
            }
            dh.addView(TextView(this).apply {
                text = "‹"; textSize = 26f; setTextColor(InkDim); setPadding(dp(10), 0, dp(10), 0); isClickable = true
                setOnClickListener { detail.animate().translationX(panel.width.toFloat()).setDuration(260).setInterpolator(android.view.animation.AccelerateInterpolator(1.6f)).withEndAction { panel.removeView(detail) }.start() }
            })
            dh.addView(TextView(this).apply {
                text = title; textSize = 15f; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val ts = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
            val tb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(32)) }
            ts.addView(tb)
            tracks.forEachIndexed { i, track ->
                val badge = if (showRank) "#${i + 1}" else ""
                tb.addView(trackRow(if (showRank) null else null, arts.getOrNull(i), track.name, track.artist, badge) {
                    mediaUiScope.launch { spotifyApi.playTrack(track.uri) }
                })
                tb.addView(View(this@MainActivity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            }
            detail.addView(dh, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            detail.addView(View(this).apply { setBackgroundColor(0x10FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            detail.addView(ts, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            panel.addView(detail, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            detail.animate().translationX(0f).setDuration(300).setInterpolator(android.view.animation.DecelerateInterpolator(2.0f)).start()
        }

        // ── Tab logic ─────────────────────────────────────────────────────────
        val personalizedKeys = listOf("discover weekly", "daily mix", "release radar", "on repeat", "repeat rewind", "time capsule", "your top songs", "wrapped")
        fun isPersonalized(name: String) = personalizedKeys.any { name.lowercase().contains(it) }

        var searchJob: Job? = null

        fun selectTab(idx: Int) {
            activeTab = idx
            tabViews.forEachIndexed { i, tv ->
                tv.setTextColor(if (i == idx) SpotifyGreen else InkDim)
                tv.typeface = if (i == idx) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.DEFAULT
            }
            searchWrap.visibility = if (idx == 3) View.VISIBLE else View.GONE
            body.removeAllViews()
            scroll.scrollTo(0, 0)

            when (idx) {
                0 -> { // TOP TRACKS
                    val top = spotifyCachedTopTracks
                    if (top.isEmpty()) {
                        body.addView(TextView(this).apply { text = "Reconnect Spotify to load your top tracks"; textSize = 13f; setTextColor(InkDim); setPadding(dp(2), dp(12), 0, 0) })
                    } else {
                        body.addView(sectionLabel("YOUR TOP TRACKS  •  ALL TIME", 0xFF8BE8FF.toInt()))
                        // Show top 9 as a grid (most visual)
                        val gridTracks = top.take(9)
                        populateGrid(gridTracks.map { it.name to it.artist }, spotifyCachedTopArts.take(9),
                            intArrayOf(0xFF1A2030.toInt(), 0xFF0A1020.toInt()), 0xFF1E3050.toInt()) { i ->
                            mediaUiScope.launch { spotifyApi.playTrack(gridTracks[i].uri) }
                        }
                        // Show all as a list below
                        body.addView(sectionLabel("ALL ${top.size} TRACKS"))
                        top.forEachIndexed { i, track ->
                            body.addView(trackRow(i + 1, spotifyCachedTopArts.getOrNull(i), track.name, track.artist,
                                if (i < 3) "★" else "") {
                                mediaUiScope.launch { spotifyApi.playTrack(track.uri) }
                            })
                            body.addView(View(this@MainActivity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                        }
                    }
                }
                1 -> { // RECENT
                    val recent = spotifyCachedRecent
                    if (recent.isEmpty()) {
                        body.addView(TextView(this).apply { text = "Play something in Spotify to see it here"; textSize = 13f; setTextColor(InkDim); setPadding(dp(2), dp(12), 0, 0) })
                    } else {
                        body.addView(sectionLabel("RECENTLY PLAYED"))
                        recent.forEachIndexed { i, track ->
                            body.addView(trackRow(null, spotifyCachedRecentArts.getOrNull(i), track.name, track.artist) {
                                mediaUiScope.launch { spotifyApi.playTrack(track.uri) }
                            })
                            body.addView(View(this@MainActivity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                        }
                    }
                }
                2 -> { // PLAYLISTS
                    val playlists = spotifyCachedPlaylists
                    val liked = spotifyCachedLikedSongs

                    // Liked songs virtual entry
                    if (liked.isNotEmpty()) {
                        body.addView(sectionLabel("LIBRARY"))
                        body.addView(LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(4), dp(10), dp(16), dp(10)); isClickable = true
                            setOnClickListener { haptic(this); showTrackListDetail("Liked Songs", liked, spotifyCachedLikedArts) }
                            val thumb = FrameLayout(this@MainActivity).apply {
                                clipToOutline = true
                                outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(6).toFloat()) } }
                            }
                            thumb.addView(View(this@MainActivity).apply { setBackgroundColor(SpotifyGreen) }, FrameLayout.LayoutParams(dp(44), dp(44)))
                            thumb.addView(TextView(this@MainActivity).apply { text = "♥"; textSize = 20f; setTextColor(0xFF000000.toInt()); gravity = Gravity.CENTER }, FrameLayout.LayoutParams(dp(44), dp(44)))
                            addView(thumb, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4); marginEnd = dp(12) })
                            val info = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                            info.addView(TextView(this@MainActivity).apply { text = "Liked Songs"; textSize = 13f; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink) })
                            info.addView(TextView(this@MainActivity).apply { text = "${liked.size} songs"; textSize = 11f; setTextColor(InkDim) })
                            addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                            addView(TextView(this@MainActivity).apply { text = "›"; textSize = 20f; setTextColor(InkDim) })
                        })
                        body.addView(View(this@MainActivity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                    }

                    val personalized = playlists.indices.filter { isPersonalized(playlists[it].name) }
                    val mine = playlists.indices.filter { !isPersonalized(playlists[it].name) }

                    if (personalized.isNotEmpty()) {
                        body.addView(sectionLabel("MADE FOR YOU", 0xFF8BE8FF.toInt()))
                        personalized.forEach { pidx ->
                            val p = playlists[pidx]; val art = spotifyCachedPlaylistArts.getOrNull(pidx)
                            body.addView(LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                                setPadding(dp(4), dp(10), dp(16), dp(10)); isClickable = true
                                setOnClickListener { haptic(this); showPlaylistDetail(p) }
                                val thumb = FrameLayout(this@MainActivity).apply {
                                    clipToOutline = true
                                    outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(6).toFloat()) } }
                                }
                                thumb.addView(if (art != null) ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(art) }
                                    else View(this@MainActivity).apply { setBackgroundColor(0xFF1A2830.toInt()) }, FrameLayout.LayoutParams(dp(44), dp(44)))
                                addView(thumb, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4); marginEnd = dp(12) })
                                val info = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                                info.addView(TextView(this@MainActivity).apply { text = p.name; textSize = 13f; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                                info.addView(TextView(this@MainActivity).apply { text = p.ownerName.ifBlank { "Spotify" }; textSize = 11f; setTextColor(InkDim) })
                                addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                                addView(TextView(this@MainActivity).apply { text = "›"; textSize = 20f; setTextColor(InkDim) })
                            })
                            body.addView(View(this@MainActivity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                        }
                    }
                    if (mine.isNotEmpty()) {
                        body.addView(sectionLabel("YOUR PLAYLISTS"))
                        mine.forEach { pidx ->
                            val p = playlists[pidx]; val art = spotifyCachedPlaylistArts.getOrNull(pidx)
                            body.addView(LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                                setPadding(dp(4), dp(10), dp(16), dp(10)); isClickable = true
                                setOnClickListener { haptic(this); showPlaylistDetail(p) }
                                val thumb = FrameLayout(this@MainActivity).apply {
                                    clipToOutline = true
                                    outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(6).toFloat()) } }
                                }
                                thumb.addView(if (art != null) ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(art) }
                                    else View(this@MainActivity).apply { setBackgroundColor(0xFF1A1D22.toInt()) }, FrameLayout.LayoutParams(dp(44), dp(44)))
                                addView(thumb, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4); marginEnd = dp(12) })
                                val info = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                                info.addView(TextView(this@MainActivity).apply { text = p.name; textSize = 13f; typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                                info.addView(TextView(this@MainActivity).apply { text = p.ownerName.ifBlank { "My playlist" }; textSize = 11f; setTextColor(InkDim) })
                                addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                                addView(TextView(this@MainActivity).apply { text = "›"; textSize = 20f; setTextColor(InkDim) })
                            })
                            body.addView(View(this@MainActivity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                        }
                    }
                    if (playlists.isEmpty() && liked.isEmpty()) {
                        body.addView(TextView(this).apply { text = "No playlists — reconnect Spotify to grant access"; textSize = 13f; setTextColor(InkDim); setPadding(dp(2), dp(12), 0, 0) })
                    }
                }
                3 -> { // SEARCH
                    searchField.requestFocus()
                    searchField.postDelayed({
                        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        imm?.showSoftInput(searchField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }, 120)
                }
            }
        }

        tabViews.forEachIndexed { i, tv -> tv.setOnClickListener { selectTab(i) } }
        panelSelectTab = ::selectTab
        panelActiveTab = { activeTab }

        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim() ?: ""
                searchJob?.cancel(); body.removeAllViews()
                if (q.length < 2) return
                searchJob = mediaUiScope.launch {
                    delay(320)
                    val tracks = withContext(Dispatchers.IO) { spotifyApi.search(q, limit = 20) }
                    val arts = withContext(Dispatchers.IO) { coroutineScope { tracks.map { t -> async { t.albumArtUrl?.let { runCatching { spotifyApi.fetchAlbumArt(it) }.getOrNull() } } }.awaitAll() } }
                    if (tracks.isEmpty()) {
                        body.addView(TextView(this@MainActivity).apply { text = "No results for \"$q\""; textSize = 13f; setTextColor(InkDim); setPadding(dp(2), dp(12), 0, 0) })
                    } else {
                        tracks.forEachIndexed { i, track ->
                            body.addView(trackRow(null, arts.getOrNull(i), track.name, track.artist) {
                                mediaUiScope.launch { spotifyApi.playTrack(track.uri) }
                            })
                            body.addView(View(this@MainActivity).apply { setBackgroundColor(0x08FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                        }
                    }
                }
            }
        })

        selectTab(0)

        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(260).start()
        panel.animate().translationY(0f).setDuration(420)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2.2f)).start()
    }



    // Click wheel page inside pager — SOURCE button navigates back to library.
    private fun clickWheelDockPage(onLibraryTapped: () -> Unit): View {
        return FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                0xFF101216.toInt(), 0xFF050506.toInt(), 0xFF000000.toInt()
            )).apply { setStroke(dp(1), 0xFF20232A.toInt()) }
            val wheelSize = clickWheelSize()
            addView(WheelWellView(context), FrameLayout.LayoutParams(wheelSize + dp(18), wheelSize + dp(18), Gravity.CENTER))
            addView(ClickWheelView(context).apply {
                sourceLabel = "LIBRARY"
                onLibrary = { haptic(this); onLibraryTapped() }
                onCenter = { haptic(this); mediaSessionSource.togglePlayPause() }
                onLeft = { haptic(this); mediaSessionSource.skipToPrevious() }
                onRight = { haptic(this); mediaSessionSource.skipToNext() }
                onBottom = { haptic(this); mediaSessionSource.togglePlayPause() }
                onScroll = { steps ->
                    mediaSessionSource.adjustVolume(steps)
                    showVolumeHud()
                }
            }, FrameLayout.LayoutParams(wheelSize, wheelSize, Gravity.CENTER))
        }
    }

    private fun musicBlackDock(): View {
        return FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                0xFF12151A.toInt(),
                0xFF08090B.toInt(),
                0xFF020203.toInt()
            )).apply {
                setStroke(dp(1), 0xFF20242C.toInt())
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(musicBlackTransportButton("◀", accent = false) {
                    haptic(this)
                    mediaSessionSource.skipToPrevious()
                }, LinearLayout.LayoutParams(dp(66), dp(66)).apply { marginEnd = dp(24) })
                addView(musicBlackTransportButton(if (mediaSessionSource.nowPlaying.value?.isPlaying == true) "Ⅱ" else "▶", accent = true) {
                    haptic(this)
                    mediaSessionSource.togglePlayPause()
                }, LinearLayout.LayoutParams(dp(82), dp(82)))
                addView(musicBlackTransportButton("▶", accent = false) {
                    haptic(this)
                    mediaSessionSource.skipToNext()
                }, LinearLayout.LayoutParams(dp(66), dp(66)).apply { marginStart = dp(24) })
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        }
    }

    private fun musicBlackTransportButton(label: String, accent: Boolean, action: TextView.() -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            includeFontPadding = false
            textSize = if (accent) 27f else 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (accent) 0xFFFFFFFF.toInt() else 0xFFC9CED6.toInt())
            background = musicBlackTransportBackground(accent)
            isClickable = true
            elevation = dp(if (accent) 10 else 7).toFloat()
            setOnClickListener { action() }
        }
    }

    private fun musicBlackTransportBackground(accent: Boolean): Drawable {
        val skirt = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF050608.toInt())
        }
        val rim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (accent) 0xFFFF7B2D.toInt() else 0xFF303640.toInt(),
            if (accent) 0xFF9D250D.toInt() else 0xFF10141A.toInt()
        )).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), if (accent) 0xFFFF9B4A.toInt() else 0xFF090B0F.toInt())
        }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (accent) 0xFFFF6A21.toInt() else 0xFF252B33.toInt(),
            if (accent) 0xFFE53910.toInt() else 0xFF171B21.toInt(),
            if (accent) 0xFFA6280B.toInt() else 0xFF0A0C10.toInt()
        )).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), if (accent) 0xFFFFB066.toInt() else 0xFF303741.toInt())
        }
        val glint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (accent) 0x66FFFFFF else 0x32FFFFFF,
            0x00FFFFFF
        )).apply {
            shape = GradientDrawable.OVAL
        }
        return LayerDrawable(arrayOf(skirt, rim, face, glint)).apply {
            val drop = dp(if (accent) 8 else 6)
            setLayerInset(0, dp(1), drop, dp(1), 0)
            setLayerInset(1, dp(2), dp(1), dp(2), drop)
            setLayerInset(2, dp(4), dp(3), dp(4), drop + dp(2))
            setLayerInset(3, dp(13), dp(8), dp(13), dp(if (accent) 48 else 40))
        }
    }

    private fun photoAlbumsDock(): View {
        return ComposeView(this).apply {
            setContent {
                LauncherPhotoAlbumsDock(
                    hasPermission = hasPhotoPermission(),
                    selectedBucket = selectedPhotoBucket(),
                    selectedPhotoId = selectedPhotoId(),
                    favoriteIds = favoritePhotoIds(),
                    onRequestPermission = { photosPermissionLauncher.launch(photoPermissionName()) },
                    onPhotoSelected = { id ->
                        persistSelectedPhoto(id)
                        if (openPane?.kind == PaneKind.PHOTOS) showPane(photosTarget(), animate = false)
                    },
                    onBucketSelected = { bucket ->
                        prefs().edit().apply {
                            if (bucket == null) remove(PHOTO_BUCKET_PREF) else putString(PHOTO_BUCKET_PREF, bucket)
                        }.apply()
                        if (openPane?.kind == PaneKind.PHOTOS) showPane(photosTarget(), animate = false)
                        refreshKeyboardDock()
                    }
                )
            }
        }
    }

    private fun keyboard(): View {
        val overlayLayer = FrameLayout(this)
        predictionOverlay.overlayLayer = overlayLayer

        val swipeLayout = SwipeKeyboardLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = keyboardDeckBackground()
            setPadding(dp(7), keyboardTopPadding(), dp(7), keyboardBottomPadding())

            if (keyboardSettingsOpen) addView(keyboardSettings(), LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (symbolsOpen) {
                addKeyRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
                addKeyRow(listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/"))
                addKeyRow(listOf("*", "\"", "'", ":", ";", "!", "?", ",", "back"), if (keyboardTheme == KEYBOARD_THEME_GOKEYS) 0 else dp(8))
                addKeyRow(listOf("abc", "clicks", "space", "period", "enter"), if (keyboardTheme == KEYBOARD_THEME_GOKEYS) 0 else dp(15))
            } else if (numberPadOpen) {
                addKeyRow(listOf("1", "2", "3"))
                addKeyRow(listOf("4", "5", "6"))
                addKeyRow(listOf("7", "8", "9"))
                addKeyRow(listOf("abc", "0", "back", "enter"))
            } else {
                addKeyRow("qwertyuiop".map { it.toString() })
                addKeyRow("asdfghjkl".map { it.toString() }, if (keyboardTheme == KEYBOARD_THEME_GOKEYS) dp(26) else dp(18))
                addKeyRow(listOf("shift") + "zxcvbnm".map { it.toString() } + listOf("back"), if (keyboardTheme == KEYBOARD_THEME_GOKEYS) 0 else dp(8))
                addKeyRow(listOf("123", "clicks", "space", "period", "enter"), if (keyboardTheme == KEYBOARD_THEME_GOKEYS) 0 else dp(15))
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

            addView(keyboardThemeSelector(), LinearLayout.LayoutParams.MATCH_PARENT, dp(38))
            addView(keyboardPlacementSelector(), LinearLayout.LayoutParams.MATCH_PARENT, dp(38))

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

    private fun keyboardThemeSelector(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(mono("THEME", 9f, InkDim).apply { letterSpacing = 0.12f },
                LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT))
            listOf(
                "DEFAULT" to KEYBOARD_THEME_DEFAULT,
                "CLICKS" to KEYBOARD_THEME_CLICKS,
                "SKEUO" to KEYBOARD_THEME_SKEUO,
                "GOKEYS" to KEYBOARD_THEME_GOKEYS
            ).forEach { (label, value) ->
                addView(TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 9.2f
                    letterSpacing = 0.08f
                    typeface = Typeface.MONOSPACE
                    setTextColor(if (keyboardTheme == value) Ink else InkDim)
                    background = if (keyboardTheme == value) keyboardThemePillBackground(value) else border(Line)
                    isClickable = true
                    setOnClickListener {
                        if (value == KEYBOARD_THEME_SKEUO && !ProManager.isUnlocked(this@MainActivity)) {
                            showUpgradeSheet(ProFeature.SKEUO_THEME); return@setOnClickListener
                        }
                        keyboardTheme = value
                        prefs().edit().putString(KEYBOARD_THEME_PREF, value).apply()
                        haptic(this); render()
                    }
                }, LinearLayout.LayoutParams(0, dp(28), 1f).apply { marginStart = dp(6) })
            }
        }
    }

    private fun keyboardPlacementSelector(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(mono("PLACE", 9f, InkDim).apply { letterSpacing = 0.12f },
                LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT))
            listOf(
                "DOCKED" to KEYBOARD_PLACEMENT_DOCKED,
                "WIDGET" to KEYBOARD_PLACEMENT_WIDGET
            ).forEach { (label, value) ->
                addView(TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 9.2f
                    letterSpacing = 0.08f
                    typeface = Typeface.MONOSPACE
                    setTextColor(if (keyboardPlacement == value) Ink else InkDim)
                    background = if (keyboardPlacement == value) keyboardThemePillBackground(KEYBOARD_THEME_GOKEYS) else border(Line)
                    isClickable = true
                    setOnClickListener {
                        haptic(this)
                        setKeyboardPlacement(value)
                    }
                }, LinearLayout.LayoutParams(0, dp(28), 1f).apply { marginStart = dp(6) })
            }
        }
    }

    private fun setKeyboardPlacement(value: String) {
        val safe = if (value == KEYBOARD_PLACEMENT_WIDGET) KEYBOARD_PLACEMENT_WIDGET else KEYBOARD_PLACEMENT_DOCKED
        keyboardPlacement = safe
        keyboardSettingsOpen = false
        prefs().edit().putString(KEYBOARD_PLACEMENT_PREF, safe).apply()
        render()
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
                    "space" -> if (keyboardTheme == KEYBOARD_THEME_GOKEYS) 3.23f else 3.65f
                    "enter" -> if (keyboardTheme == KEYBOARD_THEME_GOKEYS) 1.01f else if (numberPadOpen) 1f else 0.82f
                    "period" -> if (keyboardTheme == KEYBOARD_THEME_GOKEYS) 1.0f else 0.86f
                    "clicks" -> if (keyboardTheme == KEYBOARD_THEME_GOKEYS) 1.0f else 1.55f
                    "123" -> if (keyboardTheme == KEYBOARD_THEME_GOKEYS) 1.0f else 1.02f
                    "back", "shift" -> if (keyboardTheme == KEYBOARD_THEME_GOKEYS) 1.15f else 1.02f
                    "abc" -> 1.02f
                    else -> 1f
                }
                if (label == "enter" && keyboardTheme != KEYBOARD_THEME_GOKEYS) {
                    addView(key(label), LinearLayout.LayoutParams(themedGoKeySize(), themedGoKeySize()).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        marginStart = dp(4)
                    })
                } else if (label == "123" && keyboardTheme != KEYBOARD_THEME_GOKEYS) {
                    addView(key(label), LinearLayout.LayoutParams(themedGoKeySize(), themedGoKeySize()).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        marginEnd = dp(4)
                    })
                } else {
                    // Key fills full row height for a larger invisible touch target.
                    // Visual inset is applied via InsetDrawable inside key() instead.
                    addView(key(label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                        marginStart = keyHorizontalInset()
                        marginEnd = keyHorizontalInset()
                    })
                }
            }
        }, LinearLayout.LayoutParams.MATCH_PARENT, keyRowHeight())
    }

    private fun key(label: String): TextView {
        val isLetter = label.length == 1 && label[0].isLetter()
        return (if (label == "space") SpaceKeyView(this) else if (isLetter) DynamicFlickKeyView(this) else TextView(this)).apply {
            text = keyLabel(label)
            gravity = Gravity.CENTER
            textSize = keyTextSize(label)
            typeface = Typeface.create("sans-serif", if (label == "shift" || label == "enter") Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, 0)
            setTextColor(keyTextColor(label))
            isClickable = true
            val vInset = keyVerticalInset()
            val needsInset = keyboardTheme == KEYBOARD_THEME_GOKEYS || (label != "enter" && label != "123")
            fun idleBg() = if (needsInset) android.graphics.drawable.InsetDrawable(keyIdleBackground(label), 0, vInset, 0, vInset) else keyIdleBackground(label)
            fun pressedBg() = if (needsInset) android.graphics.drawable.InsetDrawable(keyPressedBackground(label), 0, vInset, 0, vInset) else keyPressedBackground(label)
            background = idleBg()
            if (keyboardTheme != KEYBOARD_THEME_DEFAULT) {
                elevation = dp(if (keyboardTheme == KEYBOARD_THEME_SKEUO) 5 else 3).toFloat()
                stateListAnimator = null
            }

            var touchDownX = 0f; var touchDownY = 0f
            var deleteRepeating = false
            var deleteRunnable: Runnable? = null
            val deleteHandler = if (label == "back") android.os.Handler(android.os.Looper.getMainLooper()) else null

            fun cancelRepeat() {
                deleteRunnable?.let { deleteHandler?.removeCallbacks(it) }
                deleteRunnable = null
            }

            var spaceCursorLastX = 0f
            var spaceCursorMoved = false
            val cursorStepPx = dp(8).toFloat()
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownX = event.x; touchDownY = event.y
                        spaceCursorLastX = event.rawX; spaceCursorMoved = false
                        v.background = pressedBg()
                        KeyPhysicsRegistry.activeSprings[v]?.cancel()
                        if (keyboardTheme != KEYBOARD_THEME_DEFAULT) v.translationY = dp(2).toFloat()
                        keyHaptic(label)
                        keyPreviewManager.show(v, label)
                        if (label == "back") {
                            deleteRepeating = false
                            deleteRunnable = object : Runnable {
                                override fun run() {
                                    deleteRepeating = true
                                    handleKey("back")
                                    keyHaptic("back")
                                    deleteHandler?.postDelayed(this, 45L)
                                }
                            }
                            deleteHandler?.postDelayed(deleteRunnable!!, 380L)
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (label == "space" && !libraryOpen) {
                            val delta = event.rawX - spaceCursorLastX
                            if (Math.abs(delta) >= cursorStepPx) {
                                spaceCursorMoved = true
                                val pane = openPane
                                val text = if (pane?.kind == PaneKind.CHAT) composeText else query
                                val len = text.length
                                val pos = cursorPos ?: len
                                val newPos = (pos + if (delta > 0) 1 else -1).coerceIn(0, len)
                                cursorPos = if (newPos == len) null else newPos
                                spaceCursorLastX = event.rawX
                                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                renderRibbon()
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        v.background = idleBg()
                        if (keyboardTheme != KEYBOARD_THEME_DEFAULT) v.animateSpringReturn(dp(2).toFloat())
                        if (label == "back") {
                            cancelRepeat()
                            if (deleteRepeating) { deleteRepeating = false; return@setOnTouchListener true }
                        }
                        if (label == "space" && spaceCursorMoved) { spaceCursorMoved = false; return@setOnTouchListener true }
                        val flick = flickDetector.classify(touchDownX, touchDownY, event.x, event.y)
                        if (flick == FlickDirection.UP) {
                            val prediction = (keyViews[label] as? DynamicFlickKeyView)?.upWordHint
                            if (prediction != null) {
                                keyHaptic("space")
                                acceptSuggestion(prediction)
                                return@setOnTouchListener true
                            }
                        }
                        // Fire key action here since we consume the event (return true below)
                        if (label == "shift") handleShiftTap() else handleKey(label)
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.background = idleBg()
                        if (keyboardTheme != KEYBOARD_THEME_DEFAULT) v.animateSpringReturn(dp(2).toFloat())
                        cancelRepeat(); deleteRepeating = false; spaceCursorMoved = false
                    }
                }
                true  // consume all key touches so they don't bubble to pane swipe handler
            }
            // Long-press handlers remain; click listener no longer needed (handled in touch UP above)
            if (label == "enter") setOnLongClickListener { haptic(this); triggerGeminiSmartCompose(); true }
            if (label == "123") setOnLongClickListener {
                haptic(this)
                symbolsOpen = true; numberPadOpen = false
                render(); true
            }
            if (isLetter) setOnLongClickListener { haptic(this); handleLetterLongPress(label.lowercase(Locale.US)); true }
        }.also { keyViews[label] = it }
    }

    private fun keyLabel(label: String): String = when (label) {
        "back" -> "⌫"
        "space" -> "space"
        "enter" -> "GO"
        "period" -> "."
        "shift" -> "↑"
        "clicks" -> if (keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET) "DOCK" else "clicks"
        "abc" -> "ABC"
        "123" -> "123"
        else -> if (label.length == 1) {
            if (keyboardTheme == KEYBOARD_THEME_GOKEYS && label[0].isLetter()) label.lowercase(Locale.US)
            else
            if (numberPadOpen || shiftState == ShiftState.OFF) label else label.uppercase(Locale.US)
        } else label
    }

    // ── Suggestions ──────────────────────────────────────────────────────────

    private fun updateSuggestionBar() {
        // Predictions are shown inline on keys via DynamicFlickKeyView — no separate chip bar.
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
        handler.postDelayed(r, 80)
    }

    private fun currentWordInCompose(): String {
        val text = (if (openPane?.kind == PaneKind.CHAT) composeText else query).trimEnd()
        val lastSpace = text.lastIndexOf(' ')
        return if (lastSpace < 0) text else text.substring(lastSpace + 1)
    }

    // ── Autocorrect ──────────────────────────────────────────────────────────

    private fun tryAutocorrect() {
        val word = currentWordInCompose().trimEnd()
        if (word.length < 2) return
        val top = predictionEngine.getSuggestions(word, 1).firstOrNull() ?: return
        if (top.equals(word, ignoreCase = true)) return
        // Only correct if edit distance is 1 (one transposition/substitution) — avoids aggressive changes
        if (editDistance(word, top) > 1) return
        pendingAutocorrectUndo = AutocorrectUndo(word, top)
        if (openPane?.kind == PaneKind.CHAT) {
            composeText = composeText.dropLast(word.length) + top + " "
        } else {
            query = query.dropLast(word.length) + top + " "
        }
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i-1].lowercaseChar() == b[j-1].lowercaseChar()) dp[i-1][j-1]
                        else 1 + minOf(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])
        }
        return dp[a.length][b.length]
    }

    // ── Gemini keyboard suggestions ──────────────────────────────────────────

    private fun scheduleGeminiSuggestions() {
        if (!geminiConfigured() || !ProManager.isUnlocked(this)) return
        geminiSuggestJob?.cancel()
        geminiSuggestJob = mediaUiScope.launch {
            delay(220)
            val context = if (openPane?.kind == PaneKind.CHAT) composeText else query
            if (context.isBlank()) return@launch
            val result = runCatching {
                withContext(Dispatchers.IO) { fetchGeminiSuggestions(context) }
            }.getOrNull() ?: return@launch
            if (result.isNotEmpty()) {
                suggestions = result
                updateSuggestionBar()
            }
        }
    }

    private fun fetchGeminiSuggestions(context: String): List<String> {
        val key = prefs().getString(GEMINI_API_KEY_PREF, null)?.trim().orEmpty()
        if (key.isBlank()) return emptyList()
        val model = prefs().getString(GEMINI_MODEL_PREF, GEMINI_DEFAULT_MODEL)?.trim().orEmpty().ifBlank { GEMINI_DEFAULT_MODEL }
        val prompt = """You are a keyboard autocomplete engine. Given this text the user is typing, reply with exactly 3 next-word predictions as a JSON array of strings. Nothing else — no explanation, just the array.
Text: "$context"
Reply format: ["word1","word2","word3"]"""
        val url = java.net.URL("https://generativelanguage.googleapis.com/v1beta/models/${java.net.URLEncoder.encode(model, "UTF-8")}:generateContent?key=${java.net.URLEncoder.encode(key, "UTF-8")}")
        val body = org.json.JSONObject()
            .put("contents", org.json.JSONArray().put(org.json.JSONObject().put("parts", org.json.JSONArray().put(org.json.JSONObject().put("text", prompt)))))
            .put("generationConfig", org.json.JSONObject().put("temperature", 0.2).put("maxOutputTokens", 24))
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 6_000; readTimeout = 8_000
            setRequestProperty("Content-Type", "application/json"); doOutput = true
        }
        java.io.OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
        if (conn.responseCode !in 200..299) return emptyList()
        val raw = conn.inputStream.bufferedReader().use { it.readText() }
        val text = org.json.JSONObject(raw)
            .optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?.optJSONObject(0)?.optString("text")?.trim() ?: return emptyList()
        // Parse ["word1","word2","word3"]
        val match = Regex(""""\s*([^"]+)\s*"""").findAll(text)
        return match.map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.take(3).toList()
    }

    // ── Gemini smart compose ─────────────────────────────────────────────────

    fun triggerGeminiSmartCompose() {
        if (!requirePro(ProFeature.GEMINI_SMART_COMPOSE)) return
        if (!geminiConfigured()) { Toast.makeText(this, "Add a Gemini API key in Settings.", Toast.LENGTH_SHORT).show(); return }
        val context = if (openPane?.kind == PaneKind.CHAT) composeText else query
        if (context.isBlank()) return
        mediaUiScope.launch {
            val completion = runCatching {
                withContext(Dispatchers.IO) { fetchGeminiCompletion(context) }
            }.getOrNull() ?: return@launch
            if (completion.isNotBlank()) {
                if (openPane?.kind == PaneKind.CHAT) {
                    composeText = composeText.trimEnd() + " " + completion
                    renderPaneContent(openPane!!)
                } else {
                    query = query.trimEnd() + " " + completion
                }
                renderRibbon()
            }
        }
    }

    private fun fetchGeminiCompletion(context: String): String {
        val key = prefs().getString(GEMINI_API_KEY_PREF, null)?.trim().orEmpty()
        if (key.isBlank()) return ""
        val model = prefs().getString(GEMINI_MODEL_PREF, GEMINI_DEFAULT_MODEL)?.trim().orEmpty().ifBlank { GEMINI_DEFAULT_MODEL }
        val prompt = "Continue this text naturally in one short sentence, no quotes, no explanation:\n\"$context\""
        val url = java.net.URL("https://generativelanguage.googleapis.com/v1beta/models/${java.net.URLEncoder.encode(model, "UTF-8")}:generateContent?key=${java.net.URLEncoder.encode(key, "UTF-8")}")
        val body = org.json.JSONObject()
            .put("contents", org.json.JSONArray().put(org.json.JSONObject().put("parts", org.json.JSONArray().put(org.json.JSONObject().put("text", prompt)))))
            .put("generationConfig", org.json.JSONObject().put("temperature", 0.7).put("maxOutputTokens", 60))
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 8_000; readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json"); doOutput = true
        }
        java.io.OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
        if (conn.responseCode !in 200..299) return ""
        val raw = conn.inputStream.bufferedReader().use { it.readText() }
        return org.json.JSONObject(raw)
            .optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?.optJSONObject(0)?.optString("text")?.trim() ?: ""
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
        updateGlideLayout()
    }

    private fun loadGlideWords() {
        mediaUiScope.launch(Dispatchers.IO) {
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
                launch(Dispatchers.Main) {
                    glideClassifier = clf
                    wordlistFrequencies = freqMap
                    predictionEngine = PredictionEngine(freqMap)
                    updateGlideLayout()
                }
            }
        }
    }

    private fun maybeRequestSmsPermission() {
        val seeder = SmsSeedingCoordinator(this)
        if (!seeder.needsSeeding()) return
        if (checkSelfPermission(android.Manifest.permission.READ_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            triggerSmsSeeding()
        } else {
            smsPermissionLauncher.launch(android.Manifest.permission.READ_SMS)
        }
    }

    private fun triggerSmsSeeding() {
        val seeder = SmsSeedingCoordinator(this)
        mediaUiScope.launch {
            seeder.runIfNeeded { smsFrequencies ->
                // Merge: wordlist is base, SMS boosts words the user actually uses
                val merged = HashMap<String, Float>(wordlistFrequencies)
                smsFrequencies.forEach { (word, smsScore) ->
                    val existing = merged[word] ?: 0f
                    merged[word] = minOf(1f, existing + smsScore * 0.4f)
                }
                predictionEngine = PredictionEngine(merged)
            }
        }
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
            // Intercept as soon as a second finger lands so we can drive trackpad scrolling
            if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN && libraryOpen) {
                trackpadLastY = (ev.getY(0) + ev.getY(1)) / 2f
                trackpadActive = true
                tracking = false; traced.clear(); trailLocal.clear(); invalidate()
                glideClassifier?.clear()
                return true   // steal from child key views
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX; startRawY = ev.rawY
                    tracking = false; traced.clear(); trailLocal.clear()
                    val loc = IntArray(2); getLocationOnScreen(loc)
                    screenX = loc[0].toFloat(); screenY = loc[1].toFloat()
                    trackpadActive = false
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (trackpadActive) return true
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

        private var trackpadLastY = 0f
        private var trackpadActive = false

        private fun trackpadScroll(ev: MotionEvent): Boolean {
            if (!libraryOpen) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (ev.pointerCount == 2) {
                        trackpadLastY = (ev.getY(0) + ev.getY(1)) / 2f
                        trackpadActive = true
                        tracking = false; traced.clear(); trailLocal.clear(); invalidate()
                        glideClassifier?.clear()
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!trackpadActive || ev.pointerCount < 2) return false
                    val midY = (ev.getY(0) + ev.getY(1)) / 2f
                    val dy = (trackpadLastY - midY) * 2.4f
                    trackpadLastY = midY
                    (libraryContentArea?.getChildAt(0) as? ScrollView)?.scrollBy(0, dy.toInt())
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (trackpadActive) { trackpadActive = false; return true }
                }
            }
            return trackpadActive
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (trackpadScroll(ev)) return true
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

    inner class WheelWellView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val outer = minOf(width, height) * 0.46f
            val inner = outer * 0.82f

            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.RadialGradient(
                cx, cy, outer,
                intArrayOf(0xFF030304.toInt(), 0xFF07090C.toInt(), 0xFF151921.toInt(), 0x00151921),
                floatArrayOf(0f, 0.54f, 0.8f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, outer, paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(6).toFloat()
            paint.color = 0x99000000.toInt()
            canvas.drawCircle(cx, cy, inner, paint)

            paint.strokeWidth = dp(2).toFloat()
            paint.color = 0x77000000
            canvas.drawArc(
                cx - inner,
                cy - inner,
                cx + inner,
                cy + inner,
                28f,
                124f,
                false,
                paint
            )

            paint.strokeWidth = dp(2).toFloat()
            paint.color = 0x551C222B
            canvas.drawCircle(cx, cy, inner + dp(4), paint)

            paint.strokeWidth = dp(1).toFloat()
            paint.color = 0x26FFFFFF
            canvas.drawArc(
                cx - inner,
                cy - inner,
                cx + inner,
                cy + inner,
                205f,
                130f,
                false,
                paint
            )

            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.RadialGradient(
                cx - outer * 0.26f, cy - outer * 0.32f, outer * 0.9f,
                0x12FFFFFF,
                0x00000000,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, outer * 0.92f, paint)
            paint.shader = null
        }
    }

    inner class ClickWheelView(context: Context) : View(context) {
        var onCenter: (() -> Unit)? = null
        var onLeft: (() -> Unit)? = null
        var onRight: (() -> Unit)? = null
        var onTop: (() -> Unit)? = null
        var onBottom: (() -> Unit)? = null
        var onLibrary: (() -> Unit)? = null
        var onLongLibrary: (() -> Unit)? = null
        // Long-press on ‹‹ / ›› zones. Return true to consume (suppresses the
        // normal tap action on release), false to fall through to a plain tap.
        var onLongLeft: (() -> Boolean)? = null
        var onLongRight: (() -> Boolean)? = null
        var onLongCenter: (() -> Unit)? = null
        var onScroll: ((steps: Int) -> Unit)? = null
        private val longPressHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var longPressRunnable: Runnable? = null
        private var longPressFired = false
        var sourceLabel: String = "SOURCE"
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private var pressedZone: WheelZone? = null
        private var ringDragging = false
        private var ringLastAngle = 0.0
        private var ringAccum = 0.0
        private val ringStepDeg = 10.0
        private var touchDownX = 0f; private var touchDownY = 0f
        // Only the finger that started the gesture drives the wheel; a second
        // resting thumb would otherwise make the angle jump wildly.
        private var activePointerId = MotionEvent.INVALID_POINTER_ID
        private var multiTouchLost = false
        // Flywheel: EMA-smoothed angular velocity in degrees per ms, sampled
        // from move events and decayed after a fast release. The decay loop is
        // driven by Choreographer vsync with real frame deltas, so the glide
        // speed and friction feel identical on 60/90/120Hz panels.
        var flingAllowed: (() -> Boolean)? = null
        var onScrollEnd: (() -> Unit)? = null
        private var ringVelocity = 0.0
        private var lastMoveTimeMs = 0L
        private var lastTickMs = 0L
        private var flinging = false
        private var flingLastFrameNanos = 0L
        private val flingCallback = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!flinging) return
                if (flingLastFrameNanos == 0L) {
                    flingLastFrameNanos = frameTimeNanos
                    android.view.Choreographer.getInstance().postFrameCallback(this)
                    return
                }
                val dtMs = (frameTimeNanos - flingLastFrameNanos) / 1_000_000.0
                flingLastFrameNanos = frameTimeNanos
                ringVelocity *= Math.pow(0.94, dtMs / 16.0)
                if (kotlin.math.abs(ringVelocity) < 0.02) {
                    flinging = false
                    flingLastFrameNanos = 0L
                    onScrollEnd?.invoke()
                    return
                }
                ringAccum += ringVelocity * dtMs
                val steps = (ringAccum / ringStepDeg).toInt()
                if (steps != 0) {
                    ringAccum -= steps * ringStepDeg
                    wheelTick()
                    onScroll?.invoke(steps)
                }
                android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        private fun startFling() {
            flinging = true
            flingLastFrameNanos = 0L
            android.view.Choreographer.getInstance().postFrameCallback(flingCallback)
        }

        private fun stopFling() {
            flinging = false
            flingLastFrameNanos = 0L
            android.view.Choreographer.getInstance().removeFrameCallback(flingCallback)
        }

        // For consumers that hit a hard boundary (list edge): kill leftover
        // momentum immediately and fire the end-of-scroll signal now instead
        // of letting the glide decay against the edge.
        fun cancelFling() {
            if (!flinging) return
            stopFling()
            onScrollEnd?.invoke()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            stopFling()
            longPressRunnable?.let { longPressHandler.removeCallbacks(it) }; longPressRunnable = null
        }
        private var outerRadialShader: android.graphics.RadialGradient? = null
        private var faceLinearShader: android.graphics.LinearGradient? = null
        private var centerShaderNormal: android.graphics.RadialGradient? = null
        private var centerShaderPressed: android.graphics.RadialGradient? = null
        private var glintShader: android.graphics.RadialGradient? = null

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val cx = w / 2f; val cy = h / 2f
            val outer = minOf(w, h) * 0.47f
            val inner = outer * 0.38f
            outerRadialShader = android.graphics.RadialGradient(
                cx, cy, outer * 1.1f,
                intArrayOf(0xFF171A20.toInt(), 0xFF08090C.toInt(), 0xFF020203.toInt()),
                floatArrayOf(0f, 0.66f, 1f), Shader.TileMode.CLAMP
            )
            faceLinearShader = android.graphics.LinearGradient(
                0f, cy - outer, 0f, cy + outer,
                intArrayOf(0xFF1B1F25.toInt(), 0xFF12161B.toInt(), 0xFF090A0D.toInt(), 0xFF030304.toInt()),
                floatArrayOf(0f, 0.28f, 0.72f, 1f), Shader.TileMode.CLAMP
            )
            centerShaderNormal = android.graphics.RadialGradient(
                cx, cy, inner * 1.14f,
                intArrayOf(0xFF242931.toInt(), 0xFF14181E.toInt(), 0xFF06070A.toInt()),
                floatArrayOf(0f, 0.64f, 1f), Shader.TileMode.CLAMP
            )
            centerShaderPressed = android.graphics.RadialGradient(
                cx, cy + dp(4).toFloat(), inner * 1.18f,
                intArrayOf(0xFF191D23.toInt(), 0xFF0D1015.toInt(), 0xFF030407.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            glintShader = android.graphics.RadialGradient(
                cx - outer * 0.28f, cy - outer * 0.32f, outer * 0.95f,
                0x08FFFFFF, 0x00000000, Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val outer = minOf(width, height) * 0.47f
            val inner = outer * 0.38f
            val centerPressed = pressedZone == WheelZone.CENTER

            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = 0x66000000
            canvas.drawCircle(cx, cy + dp(3), outer * 0.98f, paint)

            paint.shader = outerRadialShader
            canvas.drawCircle(cx, cy, outer * 1.0f, paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = 0xAA020305.toInt()
            canvas.drawCircle(cx, cy, outer * 0.99f, paint)

            paint.style = Paint.Style.FILL
            paint.shader = faceLinearShader
            canvas.drawCircle(cx, cy, outer * 0.965f, paint)
            paint.shader = null

            pressedZone?.takeIf { it != WheelZone.CENTER }?.let { zone ->
                val start = when (zone) {
                    WheelZone.TOP -> 225f
                    WheelZone.RIGHT -> 315f
                    WheelZone.BOTTOM -> 45f
                    WheelZone.LEFT -> 135f
                    WheelZone.CENTER -> 0f
                }
                val pressedR = outer * 0.94f
                val midAngle = Math.toRadians((start + 45.0))
                val shadowDx = kotlin.math.cos(midAngle).toFloat()
                val shadowDy = kotlin.math.sin(midAngle).toFloat()

                // Base depression: darker fill for the pressed sector
                paint.style = Paint.Style.FILL
                paint.color = 0x55000000
                canvas.drawArc(cx - pressedR, cy - pressedR, cx + pressedR, cy + pressedR, start, 90f, true, paint)

                // Inner shadow: a radial gradient centred on the midpoint of the arc,
                // making the area look sunken away from the light source
                val shadowCx = cx + shadowDx * outer * 0.70f
                val shadowCy = cy + shadowDy * outer * 0.70f
                paint.shader = android.graphics.RadialGradient(
                    shadowCx, shadowCy, outer * 0.55f,
                    intArrayOf(0x00000000, 0x22000000, 0x55000000),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawArc(cx - pressedR, cy - pressedR, cx + pressedR, cy + pressedR, start, 90f, true, paint)
                paint.shader = null

                // Bright rim at the outer edge of the pressed arc (the rim catches light
                // as the surface tilts away, making it look physically raised/hinged at the edge)
                paint.style = Paint.Style.STROKE
                val density = resources.displayMetrics.density
                paint.strokeWidth = density * 1.5f
                paint.color = 0x18FFFFFF
                canvas.drawArc(cx - pressedR * 0.968f, cy - pressedR * 0.968f,
                    cx + pressedR * 0.968f, cy + pressedR * 0.968f, start + 2f, 86f, false, paint)

                // Inner shadow lip where the button meets the ring — dark concave crease
                val lipR = outer * 0.405f
                paint.strokeWidth = density * 2.5f
                paint.color = 0x44000000
                canvas.drawArc(cx - lipR, cy - lipR, cx + lipR, cy + lipR, start, 90f, false, paint)

                // Thin highlight just inside that crease (light catching the far edge of the well)
                val hlR = outer * 0.415f
                paint.strokeWidth = density * 1f
                paint.color = 0x10FFFFFF
                canvas.drawArc(cx - hlR, cy - hlR, cx + hlR, cy + hlR, start + 5f, 80f, false, paint)
            }

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = 0x12FFFFFF
            canvas.drawCircle(cx, cy, outer * 0.90f, paint)
            paint.strokeWidth = dp(2).toFloat()
            paint.color = 0xEE010203.toInt()
            canvas.drawCircle(cx, cy, outer * 0.966f, paint)

            val centerOffY = cy + dp(if (centerPressed) 4 else 0)
            // Shadow beneath the center button (deeper when pressed)
            if (centerPressed) {
                paint.style = Paint.Style.FILL
                paint.shader = android.graphics.RadialGradient(
                    cx, cy + dp(6).toFloat(), inner * 1.1f,
                    intArrayOf(0x55000000, 0x00000000),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy + dp(5).toFloat(), inner * 1.06f, paint)
                paint.shader = null
            }
            paint.style = Paint.Style.FILL
            paint.shader = if (centerPressed) centerShaderPressed else centerShaderNormal
            canvas.drawCircle(cx, centerOffY, inner, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (if (centerPressed) 2.5f else 2f) * resources.displayMetrics.density
            paint.color = if (centerPressed) 0xFF040507.toInt() else 0xBB000000.toInt()
            canvas.drawCircle(cx, centerOffY, inner, paint)
            // Pressed: inner shadow ring (concave well lip)
            if (centerPressed) {
                paint.strokeWidth = resources.displayMetrics.density
                paint.color = 0x28FFFFFF
                canvas.drawCircle(cx, centerOffY, inner - dp(2), paint)
            }
            paint.strokeWidth = dp(1).toFloat()
            paint.color = 0x12FFFFFF
            canvas.drawCircle(cx, centerOffY, inner - dp(2), paint)

            paint.style = Paint.Style.FILL
            paint.shader = glintShader
            canvas.drawCircle(cx, cy, outer * 0.94f, paint)
            paint.shader = null

            // ── Glyphs carved straight into the wheel face — no background
            // wells. Deep deboss: a strong shadow cast down from the cut's
            // upper edge, a muted sunken face, and light catching the lower
            // edge of the carving. Pressed glyphs sink another pixel.
            val den = resources.displayMetrics.density
            fun engraved(text: String, x: Float, y: Float, sizePx: Float, pressed: Boolean) {
                paint.style = Paint.Style.FILL
                paint.textSize = sizePx
                val sink = if (pressed) den else 0f
                // Bright lower-edge catch first, so the face draws over it.
                paint.color = 0x3CFFFFFF
                canvas.drawText(text, x, y + sink + den * 1.3f, paint)
                // Sunken face with a deep soft shadow from the upper lip.
                paint.color = if (pressed) 0xFF666D77.toInt() else 0xFF7A828C.toInt()
                paint.setShadowLayer(den * 2.4f, 0f, -den * 1.4f, 0xE6000000.toInt())
                canvas.drawText(text, x, y + sink, paint)
                // Second tighter shadow pass to harden the top of the cut.
                paint.setShadowLayer(den * 0.9f, 0f, -den * 0.7f, 0x99000000.toInt())
                canvas.drawText(text, x, y + sink, paint)
                paint.clearShadowLayer()
            }

            engraved(sourceLabel, cx, cy - outer * 0.62f, dp(11).toFloat(), pressedZone == WheelZone.TOP)
            engraved("‹‹", cx - outer * 0.62f, cy + dp(6), dp(18).toFloat(), pressedZone == WheelZone.LEFT)
            engraved("››", cx + outer * 0.62f, cy + dp(6), dp(18).toFloat(), pressedZone == WheelZone.RIGHT)
            engraved("▶ Ⅱ", cx, cy + outer * 0.67f, dp(14).toFloat(), pressedZone == WheelZone.BOTTOM)

            // OK stamped into the center button's metal: muted face with a soft
            // shadow cast down onto the letters from the cut's upper edge.
            val centerSunk = pressedZone == WheelZone.CENTER
            paint.style = Paint.Style.FILL
            paint.textSize = dp(13).toFloat()
            paint.color = if (centerSunk) 0xFF767D87.toInt() else 0xFF8A919B.toInt()
            paint.setShadowLayer(den * 1.6f, 0f, -den * 0.9f, 0xB8000000.toInt())
            canvas.drawText("OK", cx, cy + dp(if (centerSunk) 7 else 5), paint)
            paint.clearShadowLayer()
            paint.color = 0x16FFFFFF
            canvas.drawText("OK", cx, cy + dp(if (centerSunk) 8 else 6), paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val cx = width / 2f; val cy = height / 2f
            val outer = minOf(width, height) * 0.47f

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Keep pagers/scroll parents from stealing the gesture mid-rotation.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    stopFling()
                    activePointerId = event.getPointerId(0)
                    multiTouchLost = false
                    val dx = event.x - cx; val dy = event.y - cy
                    touchDownX = event.x; touchDownY = event.y
                    ringDragging = false; ringAccum = 0.0; longPressFired = false
                    ringVelocity = 0.0; lastMoveTimeMs = event.eventTime
                    pressedZone = zoneFor(event.x, event.y)
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (dist >= outer * 0.36f && dist <= outer * 1.08f) {
                        ringLastAngle = Math.toDegrees(atan2(dy, dx).toDouble())
                    }
                    // Long-press detection: LIBRARY (TOP) plus ‹‹/›› zones.
                    val downZone = pressedZone
                    val longCb: (() -> Boolean)? = when (downZone) {
                        WheelZone.TOP -> onLongLibrary?.let { cb -> ({ cb(); true }) }
                        WheelZone.LEFT -> onLongLeft
                        WheelZone.RIGHT -> onLongRight
                        WheelZone.CENTER -> onLongCenter?.let { cb -> ({ cb(); true }) }
                        else -> null
                    }
                    if (longCb != null) {
                        val r = Runnable {
                            if (pressedZone == downZone && !ringDragging && longCb()) {
                                longPressFired = true
                                pressedZone = null; invalidate()
                                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            }
                        }
                        longPressRunnable = r
                        longPressHandler.postDelayed(r, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.getPointerId(event.actionIndex) == activePointerId) {
                        // The driving finger left while another still rests on the
                        // screen — end the gesture instead of jumping to the thumb.
                        multiTouchLost = true
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }; longPressRunnable = null
                        if (pressedZone != null) { pressedZone = null; invalidate() }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (multiTouchLost) return true
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx == -1) return true
                    val x = event.getX(idx); val y = event.getY(idx)
                    val dx = x - cx; val dy = y - cy
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    val inRing = dist >= outer * 0.36f && dist <= outer * 1.08f
                    if (inRing && onScroll != null) {
                        val angle = Math.toDegrees(atan2(dy, dx).toDouble())
                        var delta = angle - ringLastAngle
                        if (delta > 180) delta -= 360
                        if (delta < -180) delta += 360
                        ringAccum += delta
                        ringLastAngle = angle
                        val dt = (event.eventTime - lastMoveTimeMs).coerceAtLeast(1L)
                        lastMoveTimeMs = event.eventTime
                        ringVelocity = ringVelocity * 0.7 + (delta / dt) * 0.3
                        val steps = (ringAccum / ringStepDeg).toInt()
                        if (steps != 0) {
                            ringAccum -= steps * ringStepDeg
                            ringDragging = true
                            if (pressedZone != null) { pressedZone = null; invalidate() }
                            wheelTick()
                            onScroll!!.invoke(steps)
                        }
                    } else {
                        ringVelocity = 0.0
                        lastMoveTimeMs = event.eventTime
                    }
                    val mdx = x - touchDownX; val mdy = y - touchDownY
                    if (!ringDragging && kotlin.math.sqrt(mdx * mdx + mdy * mdy) > dp(18)) {
                        ringDragging = true
                        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }; longPressRunnable = null
                        if (pressedZone != null) { pressedZone = null; invalidate() }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }; longPressRunnable = null
                    val zone = pressedZone
                    pressedZone = null
                    invalidate()
                    performClick()
                    if (!ringDragging && !longPressFired && !multiTouchLost) handleZone(zone ?: zoneFor(event.x, event.y))
                    // Fast release on the ring → let the flywheel glide the list.
                    if (ringDragging && !multiTouchLost && kotlin.math.abs(ringVelocity) > 0.25 && flingAllowed?.invoke() == true) {
                        ringVelocity = ringVelocity.coerceIn(-1.2, 1.2)
                        startFling()
                    } else if (ringDragging) {
                        onScrollEnd?.invoke()
                    }
                    ringDragging = false; longPressFired = false; multiTouchLost = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }; longPressRunnable = null
                    if (ringDragging) onScrollEnd?.invoke()
                    pressedZone = null; ringDragging = false; longPressFired = false; multiTouchLost = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    ringVelocity = 0.0
                    invalidate()
                    return true
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        // Authentic per-detent feedback: haptic tick + soft key click per step,
        // throttled so fast spins/flings don't saturate the vibrator.
        private fun wheelTick() {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastTickMs < 30) return
            lastTickMs = now
            if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            playSoundEffect(android.view.SoundEffectConstants.CLICK)
        }

        private fun zoneFor(x: Float, y: Float): WheelZone {
            val cx = width / 2f
            val cy = height / 2f
            val dx = x - cx
            val dy = y - cy
            val distance = sqrt(dx * dx + dy * dy)
            val outer = minOf(width, height) * 0.47f
            if (distance < outer * 0.38f) {
                return WheelZone.CENTER
            }
            val angle = Math.toDegrees(atan2(dy, dx).toDouble())
            return when {
                angle < -45 && angle >= -135 -> WheelZone.TOP
                angle >= 45 && angle < 135 -> WheelZone.BOTTOM
                angle >= 135 || angle < -135 -> WheelZone.LEFT
                else -> WheelZone.RIGHT
            }
        }

        private fun handleZone(zone: WheelZone) {
            when (zone) {
                WheelZone.CENTER -> onCenter?.invoke()
                WheelZone.LEFT -> onLeft?.invoke()
                WheelZone.RIGHT -> onRight?.invoke()
                WheelZone.TOP -> if (onLibrary != null) onLibrary!!.invoke() else onTop?.invoke()
                WheelZone.BOTTOM -> onBottom?.invoke()
            }
        }
    }

    private enum class WheelZone { CENTER, LEFT, RIGHT, TOP, BOTTOM }

    // ── Transient volume HUD (iPod-style) shown while the wheel adjusts volume ─

    private fun showVolumeHud() {
        val frac = mediaSessionSource.volumeFraction() ?: return
        val decor = window.decorView as FrameLayout
        val trackWidth = dp(180)
        if (volumeHudView == null) {
            val fill = View(this).apply {
                background = GradientDrawable().apply { setColor(Accent); cornerRadius = dp(3).toFloat() }
            }
            val track = FrameLayout(this).apply {
                background = GradientDrawable().apply { setColor(0xFF23272F.toInt()); cornerRadius = dp(3).toFloat() }
                addView(fill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
            }
            val hud = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = GradientDrawable().apply {
                    setColor(0xF00B0D10.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFF23272F.toInt())
                }
                setPadding(dp(18), dp(11), dp(18), dp(13))
                elevation = dp(12).toFloat()
                alpha = 0f
                addView(mono("VOLUME", 9f, InkDim).apply { letterSpacing = 0.2f })
                addView(track, LinearLayout.LayoutParams(trackWidth, dp(6)).apply { topMargin = dp(9) })
            }
            decor.addView(hud, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(72) })
            volumeHudView = hud
            volumeHudFill = fill
        }
        volumeHudFill?.let {
            it.layoutParams = FrameLayout.LayoutParams((trackWidth * frac).toInt(), FrameLayout.LayoutParams.MATCH_PARENT)
            it.requestLayout()
        }
        volumeHudView?.let { hud ->
            hud.animate().alpha(1f).setDuration(120).start()
            hud.removeCallbacks(volumeHudHideRunnable)
            hud.postDelayed(volumeHudHideRunnable, 1100)
        }
    }

    private fun hideVolumeHud() {
        val hud = volumeHudView ?: return
        volumeHudView = null
        volumeHudFill = null
        hud.animate().alpha(0f).setDuration(260).withEndAction {
            (window.decorView as FrameLayout).removeView(hud)
        }.start()
    }

    // ── Key handling ─────────────────────────────────────────────────────────

    private fun handleKey(label: String) {
        if (categoryFolderView != null && libraryOpen) {
            if (label == "clicks" || label == "back") closeCategoryFolder()
            return
        }
        val pane = openPane
        if (pane?.kind == PaneKind.CHAT) { handleChatKey(label, pane); return }

        when (label) {
            "back" -> {
                // Undo autocorrect: if last action was a correction, restore original word
                val undo = pendingAutocorrectUndo
                if (undo != null && !libraryOpen) {
                    pendingAutocorrectUndo = null
                    query = query.dropLast(undo.corrected.length + 1) + undo.original  // strip "corrected " and restore
                    if (openPane?.kind == PaneKind.CHAT) {
                        composeText = composeText.dropLast(undo.corrected.length + 1) + undo.original
                    }
                    renderRibbon(); return
                }
                pendingAutocorrectUndo = null
                val pos = cursorPos
                if (pos != null && pos > 0) {
                    query = query.removeRange(pos - 1, pos)
                    cursorPos = pos - 1
                } else {
                    cursorPos = null
                    query = query.dropLast(1)
                }
                if (libraryOpen && query.isEmpty()) {
                    libraryRefreshDebounce?.let { handler.removeCallbacks(it) }; libraryRefreshDebounce = null
                    closeLibrary(); return
                }
                if (!libraryOpen) scheduleSpellCheck()
            }
            "space" -> {
                pendingAutocorrectUndo = null
                if (libraryOpen) {
                    if (query.isNotBlank()) query += " "
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastSpaceMs < 500 && query.isNotEmpty() && query.last() == ' ') {
                        query = query.dropLast(1) + ". "
                        suggestions = emptyList(); updateSuggestionBar()
                    } else {
                        val words = query.trimEnd().split(" ")
                        if (words.size >= 2) ngramRepo.recordWord(words.last(), words[words.size - 2])
                        tryAutocorrect()  // autocorrect current word before adding space
                        if (!query.endsWith(" ")) query += " "  // tryAutocorrect may have already appended the space
                        suggestions = emptyList(); updateSuggestionBar()
                    }
                    lastSpaceMs = System.currentTimeMillis()
                }
            }
            "period" -> {
                pendingAutocorrectUndo = null
                if (libraryOpen) { if (query.isNotBlank()) query += "." }
                else {
                    tryAutocorrect()
                    query = query.trimEnd() + "."
                    suggestions = emptyList(); updateSuggestionBar()
                }
            }
            "123" -> { if (!libraryOpen) { numberPadOpen = true; query = ""; ensureContactsPermission(); render(); return } }
            "abc" -> {
                if (symbolsOpen) { symbolsOpen = false; render(); return }
                if (!libraryOpen) { numberPadOpen = false; query = ""; render(); return }
            }
            "clicks" -> {
                if (keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET) {
                    setKeyboardPlacement(KEYBOARD_PLACEMENT_DOCKED)
                    return
                }
                if (libraryOpen) {
                    if (query.isNotBlank()) { query = ""; refreshLibraryContent(); renderRibbon() }
                    else closeLibrary()
                    return
                }
                keyboardSettingsOpen = !keyboardSettingsOpen; query = ""; render(); return
            }
            "enter" -> {
                if (libraryOpen) {
                    universalSearchResults().firstOrNull()?.let { openSearchResult(it) }
                    return
                }
                if (numberPadOpen) {
                    val dialUri = searchContacts(query).firstOrNull()?.target?.deepLinkUri ?: if (query.isNotBlank()) "tel:$query" else null
                    dialUri?.let { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(it))) }
                } else {
                    if (executeTypeToDoCommand(query)) {
                        query = ""
                        renderRibbon()
                        return
                    }
                    filteredRibbonEntries().firstOrNull()?.let { if (it.target.kind == PaneKind.MUSIC || it.target.packageName == null) openHere(it.target) else openExternal(it.target) }
                }
            }
            else -> {
                val char = if (shiftState != ShiftState.OFF) label.uppercase(Locale.US) else label
                val insertAt = cursorPos
                if (insertAt != null) {
                    query = query.substring(0, insertAt) + char + query.substring(insertAt)
                    cursorPos = insertAt + 1
                } else {
                    query += char
                }
                // Secret dev unlock code
                if (query == "devpro!!") {
                    query = ""
                    if (ProManager.isDev(this)) {
                        ProManager.deactivateDev(this)
                        Toast.makeText(this, "Dev Pro deactivated", Toast.LENGTH_SHORT).show()
                    } else {
                        ProManager.activateDev(this)
                        Toast.makeText(this, "Dev Pro activated ✓", Toast.LENGTH_SHORT).show()
                    }
                    renderRibbon(); return
                }
                pendingAutocorrectUndo = null
                if (shiftState == ShiftState.ONCE) { shiftState = ShiftState.OFF; updateKeyLabels() }
                if (!libraryOpen && openPane == null && keyboardPlacement == KEYBOARD_PLACEMENT_DOCKED) {
                    // Auto-open library in docked mode so search shows full-screen results.
                    // Widget mode keeps the homescreen intact while typing.
                    libraryOpen = true
                    keyboardSettingsOpen = false
                    showLibrary(animate = true)
                    syncNowPlayingCardVisibility()
                    refreshNowPlayingCard()
                } else if (!libraryOpen) {
                    scheduleGeminiSuggestions()
                }
            }
        }
        if (libraryOpen) scheduleLibraryRefresh()
        renderRibbon()
    }

    private fun scheduleLibraryRefresh() {
        libraryRefreshDebounce?.let { handler.removeCallbacks(it) }
        val r = Runnable { refreshLibraryContent() }
        libraryRefreshDebounce = r
        // Instant refresh for ≤2 chars (first letters feel snappy), debounce longer queries
        handler.postDelayed(r, if (query.length <= 2) 0L else 120L)
    }

    private fun handleChatKey(label: String, pane: PaneTarget) {
        when (label) {
            "back" -> {
                val undo = pendingAutocorrectUndo
                if (undo != null) {
                    pendingAutocorrectUndo = null
                    composeText = composeText.dropLast(undo.corrected.length + 1) + undo.original
                    updateAutoCapState(); updateKeyLabels(); return
                }
                pendingAutocorrectUndo = null
                composeText = composeText.dropLast(1)
                updateAutoCapState(); updateKeyLabels(); scheduleSpellCheck()
            }
            "space" -> {
                pendingAutocorrectUndo = null
                val now = System.currentTimeMillis()
                if (now - lastSpaceMs < 500 && composeText.isNotEmpty() && composeText.last() == ' ') {
                    composeText = composeText.dropLast(1) + ". "
                    shiftState = ShiftState.ONCE; updateKeyLabels()
                    suggestions = emptyList(); updateSuggestionBar()
                } else {
                    tryAutocorrect()
                    if (!composeText.endsWith(" ")) composeText += " "
                    suggestions = emptyList(); updateSuggestionBar()
                    updateAutoCapState(); updateKeyLabels()
                }
                lastSpaceMs = now
            }
            "period" -> {
                pendingAutocorrectUndo = null
                tryAutocorrect()
                composeText = composeText.trimEnd() + "."
                shiftState = ShiftState.ONCE; updateKeyLabels()
                suggestions = emptyList(); updateSuggestionBar()
            }
            "123" -> { numberPadOpen = true; ensureContactsPermission(); render(); return }
            "abc" -> {
                if (symbolsOpen) { symbolsOpen = false; render(); return }
                numberPadOpen = false; render(); return
            }
            "clicks" -> {
                if (keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET) setKeyboardPlacement(KEYBOARD_PLACEMENT_DOCKED)
                else { keyboardSettingsOpen = !keyboardSettingsOpen; render() }
                return
            }
            "enter" -> postComposeBubble(pane)
            else -> {
                pendingAutocorrectUndo = null
                val char = if (shiftState != ShiftState.OFF) label.uppercase(Locale.US) else label
                composeText += char
                if (shiftState == ShiftState.ONCE) { shiftState = ShiftState.OFF; updateKeyLabels() }
                scheduleSpellCheck()
                scheduleGeminiSuggestions()
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

    // ── Letter shortcuts ─────────────────────────────────────────────────────

    private fun handleLetterLongPress(letter: String) {
        val pkg = prefs().getString("letter_shortcut_$letter", null)
        if (pkg != null) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) { startActivity(intent); return }
            // Package gone — clear stale shortcut
            prefs().edit().remove("letter_shortcut_$letter").apply()
        }
        showLetterShortcutPicker(letter)
    }

    private fun showLetterShortcutPicker(letter: String) {
        val installed = apps.sortedBy { it.label }
        val labels = arrayOf("— Clear shortcut —") + installed.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Long-press \"${letter.uppercase(Locale.US)}\" opens...")
            .setItems(labels) { _, which ->
                if (which == 0) {
                    prefs().edit().remove("letter_shortcut_$letter").apply()
                } else {
                    prefs().edit().putString("letter_shortcut_$letter", installed[which - 1].packageName).apply()
                }
            }
            .show()
    }

    // ── Pane / navigation ────────────────────────────────────────────────────

    private fun openHere(target: PaneTarget) {
        closeCategoryFolder()
        libraryOpen = false
        libraryView?.let { contentFrame.removeView(it) }
        libraryView = null
        keyboardSettingsOpen = false; query = ""; composeText = ""
        shiftState = ShiftState.ONCE; suggestions = emptyList()
        updateKeyLabels(); updateSuggestionBar()
        openPane = target; showPane(target, animate = true); renderRibbon(); refreshKeyboardDock()
        syncNowPlayingCardVisibility()
        refreshNowPlayingCard()
    }

    private fun openLibraryResult(target: PaneTarget) {
        closeCategoryFolder()
        libraryOpen = false
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
        if (target.kind == PaneKind.MUSIC) showMusicProgressInHintBar()
        else hideMusicProgressFromHintBar()
    }

    // ── Music progress bar in hint bar ────────────────────────────────────────

    private fun showMusicProgressInHintBar() {
        if (!::hintBar.isInitialized) return
        for (i in 0 until hintBar.childCount) hintBar.getChildAt(i).visibility = View.GONE

        val trackNameView = TextView(this).apply {
            textSize = 9.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.04f
            setTextColor(0xFFCED2DA.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(8), 0)
        }

        val timeText = TextView(this).apply {
            textSize = 8.5f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(0xFF6B7280.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(14), 0)
            minWidth = dp(36)
        }

        // Scrubber bar — tappable/draggable, lives at the very bottom of the hintBar
        val scrubTrack = View(this).apply { setBackgroundColor(0xFF1E2229.toInt()) }
        val scrubFill = View(this).apply { setBackgroundColor(0xFF1ED760.toInt()) }
        val scrubContainer = FrameLayout(this).apply {
            addView(scrubTrack, FrameLayout.LayoutParams.MATCH_PARENT, dp(2))
            addView(scrubFill, FrameLayout.LayoutParams(0, dp(2)))
            setOnTouchListener { v, event ->
                val info = if (::mediaSessionSource.isInitialized) mediaSessionSource.nowPlaying.value else null
                val dur = info?.durationMs ?: 0L
                if (dur > 0 && (event.action == android.view.MotionEvent.ACTION_DOWN || event.action == android.view.MotionEvent.ACTION_MOVE)) {
                    val fraction = (event.x / v.width.toFloat()).coerceIn(0f, 1f)
                    mediaSessionSource.seekTo((dur * fraction).toLong())
                }
                true
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        row.addView(trackNameView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        row.addView(timeText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))

        val overlay = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        overlay.addView(row, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        overlay.addView(scrubContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(2), Gravity.BOTTOM))

        hintBar.addView(overlay)
        musicProgressBar = overlay

        fun updateProgress() {
            if (!::mediaSessionSource.isInitialized) return
            val info = mediaSessionSource.nowPlaying.value ?: return
            val elapsed = android.os.SystemClock.elapsedRealtime() - info.lastUpdateElapsedMs
            val pos = if (info.isPlaying) (info.positionMs + elapsed).coerceAtMost(info.durationMs) else info.positionMs
            val dur = info.durationMs.takeIf { it > 0 } ?: return

            trackNameView.text = info.title

            val remaining = ((dur - pos) / 1000L).coerceAtLeast(0)
            val m = remaining / 60; val s = remaining % 60
            timeText.text = "-${m}:${s.toString().padStart(2, '0')}"

            val fraction = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            val accent = if (info.appIconColor != 0) info.appIconColor else 0xFF1ED760.toInt()
            scrubFill.setBackgroundColor(accent)
            scrubTrack.post {
                val totalW = scrubTrack.width
                if (totalW > 0) {
                    scrubFill.layoutParams = FrameLayout.LayoutParams((totalW * fraction).toInt(), dp(2))
                    scrubFill.requestLayout()
                }
            }
        }

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (musicProgressBar == null) return
                updateProgress()
                val playing = (if (::mediaSessionSource.isInitialized) mediaSessionSource.nowPlaying.value?.isPlaying else null) ?: false
                if (playing) handler.postDelayed(this, 1000)
                // when paused: don't reschedule — nowPlaying collect will restart us on resume
            }
        }
        musicProgressHandler = handler
        musicProgressRunnable = runnable
        handler.post(runnable)
    }

    private fun hideMusicProgressFromHintBar() {
        if (!::hintBar.isInitialized) return
        musicProgressHandler?.removeCallbacks(musicProgressRunnable ?: return)
        musicProgressHandler = null; musicProgressRunnable = null
        // Remove progress overlay
        musicProgressBar?.let { hintBar.removeView(it) }
        musicProgressBar = null
        // Restore original children visibility
        for (i in 0 until hintBar.childCount) hintBar.getChildAt(i).visibility = View.VISIBLE
    }

    private fun closePane() {
        val closing = paneView
        val closingPane = openPane
        openPane = null; composeText = ""
        shiftState = ShiftState.OFF; suggestions = emptyList()
        if (closingPane?.kind == PaneKind.PHOTOS) zeissButtonView?.animateShutterOpen()
        if (closingPane?.kind == PaneKind.MUSIC) hideMusicProgressFromHintBar()
        updateKeyLabels(); updateSuggestionBar(); renderRibbon(); refreshKeyboardDock(); syncNowPlayingCardVisibility(); refreshNowPlayingCard()
        if (closing == null) return
        closing.animate().translationY(closing.height.toFloat()).setDuration(220).withEndAction {
            contentFrame.removeView(closing); if (paneView === closing) paneView = null
        }.start()
    }

    private fun pane(target: PaneTarget): View {
        if (target.kind == PaneKind.MUSIC || target.kind == PaneKind.PHOTOS) {
            return FrameLayout(this).apply {
                setBackgroundColor(Panel)
                tag = PANE_BODY_TAG
                addView(paneBody(target), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                addView(paneBar(target), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(54), Gravity.TOP))
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Panel); tag = PANE_BODY_TAG
            addView(paneBar(target), LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            addView(paneBody(target), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            if (target.kind == PaneKind.CHAT) addView(composeBar(), LinearLayout.LayoutParams.MATCH_PARENT, dp(50))
        }
    }

    private fun paneBar(target: PaneTarget): View {
        val media = if (target.kind == PaneKind.MUSIC && ::mediaSessionSource.isInitialized) mediaSessionSource.nowPlaying.value else null
        val headerTitle = media?.sourceApp?.ifBlank { null } ?: target.name
        val headerAccent = media?.appIconColor ?: target.accent
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(10), 0)
            background = when (target.kind) {
                PaneKind.MUSIC -> musicHeaderGlassBg()
                PaneKind.PHOTOS -> zeissHeaderGlassBg()
                else -> frostedHeaderBg()
            }
            if (target.kind == PaneKind.MUSIC) {
                elevation = dp(10).toFloat()
                isClickable = true
                setOnClickListener {
                    haptic(this)
                    mediaSessionSource.openSourceApp()
                }
            } else if (target.kind == PaneKind.PHOTOS) {
                elevation = dp(10).toFloat()
            }
            val iconBitmap = media?.appIcon
            if (target.kind == PaneKind.MUSIC && iconBitmap != null) {
                addView(ImageView(context).apply {
                    setImageBitmap(iconBitmap)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply { setColor(0x3316181D); cornerRadius = dp(8).toFloat() }
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) })
            } else if (target.kind == PaneKind.PHOTOS) {
                addView(zeissHeaderBadge(), LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) })
            } else {
                addView(TextView(context).apply {
                    text = headerTitle.take(1).uppercase(Locale.US); textSize = 13f; gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF10110F.toInt())
                    background = GradientDrawable().apply { setColor(headerAccent); cornerRadius = dp(7).toFloat() }
                }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) })
            }
            addView(TextView(context).apply {
                text = headerTitle; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.03f; setTextColor(Ink)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            when (target.kind) {
                PaneKind.MUSIC -> addView(View(context), LinearLayout.LayoutParams(dp(28), dp(28)))
                PaneKind.PHOTOS -> addView(View(context), LinearLayout.LayoutParams(dp(28), dp(28)))
                else -> {
                    addView(TextView(context).apply {
                        text = "X"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(InkDim)
                        background = keyBackground(); isClickable = true
                        setOnClickListener { haptic(this); closePane() }
                    }, LinearLayout.LayoutParams(dp(28), dp(28)))
                }
            }
        }
    }

    private fun paneBody(target: PaneTarget): View {
        if (target.kind == PaneKind.MUSIC) return musicPane()
        if (target.kind == PaneKind.PHOTOS) return photosPane()
        return ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
                when (target.kind) {
                    PaneKind.CHAT -> chatLines(target).forEach { addView(bubble(it)) }
                    PaneKind.MAIL -> mailLines(target).forEach { addView(listRow(it.first, it.second)) }
                    PaneKind.MUSIC -> Unit
                    PaneKind.PHOTOS -> Unit
                    PaneKind.AI -> aiPaneContent(this, target)
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

    private fun photosPane(): View {
        return ComposeView(this).apply {
            setBackgroundColor(Panel)
            setContent {
                LauncherPhotos(
                    hasPermission = hasPhotoPermission(),
                    selectedBucket = selectedPhotoBucket(),
                    selectedPhotoId = selectedPhotoId(),
                    favoriteIds = favoritePhotoIds(),
                    brandStamp = photoStampLabel(),
                    onPhotoSelected = { id -> persistSelectedPhoto(id) },
                    onOpenExternalPhoto = { uri -> openExternalPhoto(uri) },
                    onToggleFavorite = { photo -> toggleFavoritePhoto(photo.id) },
                    onDeletePhoto = { photo -> requestTrashPhoto(photo) },
                    onRequestPermission = { photosPermissionLauncher.launch(photoPermissionName()) }
                )
            }
        }
    }

    private fun openExternalPhoto(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "No photo viewer found.", Toast.LENGTH_SHORT).show() }
    }

    private fun musicPane(): View {
        return ComposeView(this).apply {
            setBackgroundColor(Panel)
            setContent {
                val media by mediaSessionSource.nowPlaying.collectAsState()
                MusicPlayer(
                    media = media,
                    initialTheme = musicTheme(),
                    onThemeChanged = { theme ->
                        prefs().edit().putString(MUSIC_THEME_PREF, theme).apply()
                        refreshKeyboardDock()
                    },
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
                    },
                    onSeekTo = { positionMs ->
                        haptic(this@apply)
                        mediaSessionSource.seekTo(positionMs)
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
        parent.addView(settingToggle("DOCK LABELS", showDockLabels()) {
            val next = !showDockLabels()
            prefs().edit().putBoolean(DOCK_LABELS_PREF, next).apply()
            haptic(this)
            renderFavoritesDock()
            renderPaneContent(clicksSettingsTarget())
        }, LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
        parent.addView(settingToggle("ANIMATED WEATHER", animatedWeatherEnabled()) {
            val next = !animatedWeatherEnabled()
            prefs().edit().putBoolean(ANIMATED_WEATHER_PREF, next).apply()
            haptic(this)
            if (::weatherIconView.isInitialized) weatherIconView.setAnimationEnabled(next)
            renderPaneContent(clicksSettingsTarget())
        }, LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
        parent.addView(themeColorSelector(), LinearLayout.LayoutParams.MATCH_PARENT, dp(46))

        parent.addView(mono("GESTURES", 10f, Accent).apply {
            letterSpacing = 0.22f
            setPadding(0, dp(18), 0, dp(8))
        })
        parent.addView(gestureSettingRow("SWIPE UP", GESTURE_UP_PREF, GESTURE_WIDGETS), LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
        parent.addView(gestureSettingRow("SWIPE RIGHT", GESTURE_RIGHT_PREF, GESTURE_NONE), LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
        parent.addView(gestureSettingRow("SWIPE DOWN", GESTURE_DOWN_PREF, GESTURE_NOTIFICATIONS), LinearLayout.LayoutParams.MATCH_PARENT, dp(32))

        parent.addView(mono("INTEGRATIONS", 10f, Accent).apply {
            letterSpacing = 0.22f
            setPadding(0, dp(18), 0, dp(8))
        })
        parent.addView(integrationRow("Spotify", "com.spotify.music", SPOTIFY_INTEGRATION_PREF))
        parent.addView(integrationRow("Apple Music", "com.apple.android.music", APPLE_MUSIC_INTEGRATION_PREF))
        parent.addView(geminiIntegrationRow())
        parent.addView(nativeIntegrationRow("Notifications", isNotificationAccessEnabled(), "MESSAGE, EMAIL, NEWS, MAPS WIDGETS") {
            openNotificationAccessSettings()
        })
        parent.addView(nativeIntegrationRow("Contacts", hasContactsPermission(), "PEOPLE SEARCH, CALL, TEXT, EMAIL") {
            contactsLauncher.launch(android.Manifest.permission.READ_CONTACTS)
        })
        parent.addView(nativeIntegrationRow("Calendar", hasCalendarPermission(), "EVENT SEARCH, CALENDAR WIDGET") {
            calendarPermissionLauncher.launch(android.Manifest.permission.READ_CALENDAR)
        })
        parent.addView(nativeIntegrationRow("Weather", hasWeatherPermission(), "LOCAL WEATHER HOME MODULE") {
            weatherPermissionLauncher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        })
        parent.addView(mono("PLAYING MEDIA IS THE FAST PATH: CLICKS CAN READ THE PHONE'S ACTIVE MEDIA SESSION, THEN API CONNECTORS CAN ADD LIBRARY/PLAYLIST SYNC LATER.", 8.5f, InkDim).apply {
            setPadding(0, dp(14), 0, 0)
            letterSpacing = 0.08f
        })
    }

    private fun geminiIntegrationRow(): View {
        val enabled = prefs().getBoolean(GEMINI_ENABLED_PREF, false)
        val configured = geminiConfigured()
        val state = when {
            enabled && configured -> "READY"
            configured -> "OFF"
            else -> "SET UP"
        }
        return integrationLikeRow("Gemini AI", state, if (enabled && configured) 0xFF8AB4F8.toInt() else InkDim) { anchor ->
            showGeminiMenu(anchor)
        }
    }

    private fun nativeIntegrationRow(name: String, enabled: Boolean, detail: String, onClick: () -> Unit): View {
        return integrationLikeRow(name, if (enabled) "ON" else "CONNECT", if (enabled) Accent2 else InkDim, { onClick() }).apply {
            contentDescription = detail
        }
    }

    private fun integrationLikeRow(name: String, state: String, stateColor: Int, onClick: (View) -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(12), dp(2), dp(12))
            background = border(Line)
            isClickable = true
            setOnClickListener { haptic(this); onClick(this) }
            addView(TextView(context).apply {
                text = name
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ink)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(mono(state, 10f, stateColor).apply { letterSpacing = 0.08f })
        }
    }

    private fun gestureSettingRow(label: String, prefKey: String, fallback: String): View {
        val action = gestureAction(prefKey, fallback)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(8))
            background = border(Line)
            isClickable = true
            setOnClickListener {
                haptic(this)
                showGestureMenu(label, prefKey, fallback)
            }
            addView(TextView(context).apply {
                text = label
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ink)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(mono(gestureActionLabel(action).uppercase(Locale.US), 9.5f, InkDim).apply {
                letterSpacing = 0.08f
                gravity = Gravity.RIGHT
            })
        }
    }

    private fun themeColorSelector(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), 0)
            addView(mono("THEME COLOR", 9.5f, InkDim).apply {
                letterSpacing = 0.10f
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            GO_COLORS.forEach { option ->
                addView(TextView(context).apply {
                    text = if (goKeyColor == option.color) "✓" else ""
                    gravity = Gravity.CENTER
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFF10110F.toInt())
                    background = themeColorSwatchBackground(option.color, goKeyColor == option.color)
                    isClickable = true
                    setOnClickListener {
                        haptic(this)
                        goKeyColor = option.color
                        prefs().edit().putInt(GO_KEY_COLOR_PREF, goKeyColor).apply()
                        refreshKeyboardDock()
                        renderPaneContent(clicksSettingsTarget())
                    }
                }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(7) })
            }
        }
    }

    private fun themeColorSwatchBackground(color: Int, selected: Boolean): Drawable {
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(brighten(color), color, darken(color))).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(if (selected) 2 else 1), if (selected) Ink else brighten(color))
        }
        val glow = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(adjustAlpha(color, if (selected) 0.42f else 0.18f))
        }
        return LayerDrawable(arrayOf(glow, face)).apply {
            setLayerInset(0, 0, 0, 0, 0)
            setLayerInset(1, dp(3), dp(3), dp(3), dp(3))
        }
    }

    private fun showGestureMenu(title: String, prefKey: String, fallback: String) {
        val actions = listOf(
            "No action" to GESTURE_NONE,
            "Widget screen" to GESTURE_WIDGETS,
            "Open app library" to GESTURE_LIBRARY,
            "Notification shade" to GESTURE_NOTIFICATIONS,
            "Open Music" to GESTURE_MUSIC,
            "Clicks settings" to GESTURE_SETTINGS,
            "Open app..." to "choose_app"
        )
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { dialog, which ->
                val value = actions[which].second
                dialog.dismiss()
                if (value == "choose_app") {
                    showGestureAppPicker(title, prefKey)
                } else {
                    prefs().edit().putString(prefKey, value).apply()
                    renderPaneContent(clicksSettingsTarget())
                }
            }
            .show()
    }

    private fun showGestureAppPicker(title: String, prefKey: String) {
        val sorted = apps.sortedWith { left, right -> collator.compare(left.label, right.label) }
        AlertDialog.Builder(this)
            .setTitle("$title app")
            .setItems(sorted.map { it.label }.toTypedArray()) { dialog, which ->
                prefs().edit().putString(prefKey, "$GESTURE_APP_PREFIX${sorted[which].packageName}").apply()
                dialog.dismiss()
                renderPaneContent(clicksSettingsTarget())
            }
            .show()
    }

    private fun gestureActionLabel(action: String): String {
        return when {
            action == GESTURE_NONE -> "None"
            action == GESTURE_WIDGETS -> "Widgets"
            action == GESTURE_LIBRARY -> "App library"
            action == GESTURE_NOTIFICATIONS -> "Notifications"
            action == GESTURE_MUSIC -> "Music"
            action == GESTURE_SETTINGS -> "Settings"
            action.startsWith(GESTURE_APP_PREFIX) -> {
                val packageName = action.removePrefix(GESTURE_APP_PREFIX)
                apps.firstOrNull { it.packageName == packageName }?.label ?: "App"
            }
            else -> "None"
        }
    }

    private fun integrationRow(name: String, packageName: String, prefKey: String): View {
        val mode = prefs().getString(prefKey, INTEGRATION_OFF) ?: INTEGRATION_OFF
        val installed = isInstalled(packageName)
        val spotifyConnected = packageName == "com.spotify.music" && spotifyAuth.isConnected
        val appleMusicAuto = packageName == "com.apple.android.music"
        val state = when {
            spotifyConnected -> "CONNECTED"
            mode == INTEGRATION_MEDIA -> "MEDIA SESSION"
            mode == INTEGRATION_API -> "API"
            appleMusicAuto -> "AUTO"
            installed -> "READY"
            else -> "NOT INSTALLED"
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
            val stateColor = when {
                spotifyConnected -> 0xFF1ED760.toInt()
                appleMusicAuto -> 0xFFFA586A.toInt()
                mode == INTEGRATION_OFF -> InkDim
                else -> Accent2
            }
            addView(mono(state, 10f, stateColor).apply {
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
        when (packageName) {
            "com.spotify.music" -> {
                val connected = spotifyAuth.isConnected
                menu.addView(menuItem(if (connected) "Reconnect Spotify" else "Connect Spotify", true) {
                    popup.dismiss()
                    spotifyAuth.startOAuth(this@MainActivity)
                })
                menu.addView(menuItem("Disconnect Spotify", connected) {
                    spotifyAuth.disconnect()
                    prefs().edit().putString(prefKey, INTEGRATION_OFF).apply()
                    popup.dismiss()
                    renderPaneContent(clicksSettingsTarget())
                })
            }
            "com.apple.android.music" -> {
                menu.addView(menuItem("About Apple Music", true) {
                    popup.dismiss()
                    Toast.makeText(this, "Apple Music on Android uses the Media Session API — just play music in the Apple Music app and Clicks picks it up automatically.", Toast.LENGTH_LONG).show()
                })
                menu.addView(menuItem("Disconnect", true) {
                    prefs().edit().putString(prefKey, INTEGRATION_OFF).apply()
                    popup.dismiss()
                    renderPaneContent(clicksSettingsTarget())
                })
            }
            else -> {
                menu.addView(menuItem("Disconnect", true) {
                    prefs().edit().putString(prefKey, INTEGRATION_OFF).apply()
                    popup.dismiss()
                    renderPaneContent(clicksSettingsTarget())
                })
            }
        }
        menu.addView(menuItem("Open $name", isInstalled(packageName)) {
            popup.dismiss()
            packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
        })
        popup.showAsDropDown(anchor, -dp(64), -anchor.height)
    }

    private fun showGeminiMenu(anchor: View) {
        val configured = geminiConfigured()
        val enabled = prefs().getBoolean(GEMINI_ENABLED_PREF, false)
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
        menu.addView(menuItem(if (enabled) "Disable Gemini" else "Enable Gemini", configured) {
            prefs().edit().putBoolean(GEMINI_ENABLED_PREF, !enabled).apply()
            popup.dismiss()
            renderPaneContent(clicksSettingsTarget())
        })
        menu.addView(menuItem(if (configured) "Replace API key" else "Add API key", true) {
            popup.dismiss()
            promptGeminiApiKey()
        })
        menu.addView(menuItem("Set model", true) {
            popup.dismiss()
            promptGeminiModel()
        })
        menu.addView(menuItem("Clear Gemini key", configured) {
            prefs().edit()
                .remove(GEMINI_API_KEY_PREF)
                .putBoolean(GEMINI_ENABLED_PREF, false)
                .apply()
            popup.dismiss()
            renderPaneContent(clicksSettingsTarget())
        })
        popup.showAsDropDown(anchor, -dp(72), -anchor.height)
    }

    private fun promptGeminiApiKey() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            hint = "AIza..."
            setTextColor(Ink)
            setHintTextColor(InkDim)
        }
        AlertDialog.Builder(this)
            .setTitle("Gemini API key")
            .setMessage("For this prototype the key is stored locally on this device. Later we can move it behind Firebase AI Logic or a backend proxy.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val key = input.text?.toString()?.trim().orEmpty()
                if (key.isNotBlank()) {
                    prefs().edit()
                        .putString(GEMINI_API_KEY_PREF, key)
                        .putBoolean(GEMINI_ENABLED_PREF, true)
                        .apply()
                    Toast.makeText(this, "Gemini is ready.", Toast.LENGTH_SHORT).show()
                    if (openPane?.kind == PaneKind.SETTINGS) renderPaneContent(clicksSettingsTarget())
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptGeminiModel() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setText(prefs().getString(GEMINI_MODEL_PREF, GEMINI_DEFAULT_MODEL) ?: GEMINI_DEFAULT_MODEL)
            setTextColor(Ink)
            setHintTextColor(InkDim)
        }
        AlertDialog.Builder(this)
            .setTitle("Gemini model")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val model = input.text?.toString()?.trim().orEmpty().ifBlank { GEMINI_DEFAULT_MODEL }
                prefs().edit().putString(GEMINI_MODEL_PREF, model).apply()
                if (openPane?.kind == PaneKind.SETTINGS) renderPaneContent(clicksSettingsTarget())
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun aiPaneContent(parent: LinearLayout, target: PaneTarget) {
        val state = aiAnswersById[target.id] ?: AiAnswerState(target.preview, "Thinking...", true)
        if (!geminiConfigured()) {
            parent.addView(mono("GEMINI", 10f, 0xFF8AB4F8.toInt()).apply {
                letterSpacing = 0.22f
                setPadding(0, 0, 0, dp(10))
            })
            parent.addView(TextView(this).apply {
                text = "Add a Gemini API key in Clicks Settings to ask questions without leaving the launcher."
                textSize = 14f
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextColor(Ink)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = roundedPanel(Panel2, dp(16), Line)
            }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            parent.addView(settingAction("ADD GEMINI KEY") {
                haptic(this)
                promptGeminiApiKey()
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(10) })
            return
        }
        parent.addView(mono("ASKED", 10f, 0xFF8AB4F8.toInt()).apply {
            letterSpacing = 0.18f
            setPadding(0, 0, 0, dp(8))
        })
        parent.addView(TextView(this).apply {
            text = state.prompt
            textSize = 13f
            setTextColor(Ink)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedPanel(0xFF20242B.toInt(), dp(16), 0xFF303744.toInt())
        }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        parent.addView(mono(if (state.loading) "GEMINI IS THINKING" else "GEMINI", 10f, if (state.loading) Accent2 else Accent).apply {
            letterSpacing = 0.18f
            setPadding(0, dp(18), 0, dp(8))
        })
        parent.addView(TextView(this).apply {
            text = state.answer
            textSize = 14.5f
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(if (state.loading) InkDim else Ink)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = roundedPanel(Panel2, dp(18), Line)
        }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
        if (::ribbonView.isInitialized) {
            ribbonView.removeAllViews()
        }
        renderFavoritesDock()
        val pane = openPane
        val typedText = when {
            pane?.kind == PaneKind.CHAT -> composeText
            else -> query
        }
        val hint = when {
            libraryOpen && typedText.isBlank() -> "APP LIBRARY  ·  TAP TOP BUTTON FOR CATEGORY / GRID"
            keyboardSettingsOpen -> "KEYBOARD SETTINGS"
            numberPadOpen && typedText.isBlank() -> "TYPE NUMBER  ·  CONTACTS APPEAR ABOVE  ·  GO = DIAL"
            pane?.kind == PaneKind.CHAT && typedText.isBlank() -> "→ ${pane.name.uppercase(Locale.US)}"
            else -> "SEARCHING APPS  ·  CLICKS FOR SETTINGS"
        }
        if (typedText.isNotBlank() && !keyboardSettingsOpen) {
            searchHintView.textSize = 15f
            searchHintView.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            searchHintView.letterSpacing = 0f
            searchHintView.ellipsize = android.text.TextUtils.TruncateAt.START
            searchHintView.gravity = Gravity.CENTER_VERTICAL
            searchHintView.includeFontPadding = false
            searchHintView.setPadding(dp(16), dp(3), dp(16), dp(4))
            searchHintView.setTextColor(Ink)
            searchHintView.text = styledTypedCommand(typedText)
        } else {
            searchHintView.textSize = if (isVivoDevice()) 8.6f else 9.5f
            searchHintView.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            searchHintView.letterSpacing = if (isVivoDevice()) 0.14f else 0.18f
            searchHintView.ellipsize = android.text.TextUtils.TruncateAt.END
            searchHintView.includeFontPadding = false
            searchHintView.gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            searchHintView.setPadding(dp(10), dp(2), dp(6), dp(3))
            searchHintView.setTextColor(0xFF44474E.toInt())
            searchHintView.text = hint
        }
    }

    private fun renderFavoritesDock() {
        if (!::favoritesDockView.isInitialized) return
        favoritesDockView.removeAllViews()
        val dockApps = homeDockApps()
        if (dockApps.isEmpty()) {
            favoritesDockView.addView(mono("LONG-PRESS APPS IN THE LIBRARY TO ADD FAVORITES", 8.5f, InkDim).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.12f
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return
        }
        dockApps.take(DOCK_APP_LIMIT).forEachIndexed { index, app ->
            favoritesDockView.addView(dockAppButton(app, index), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
        repeat(DOCK_APP_LIMIT - dockApps.size.coerceAtMost(DOCK_APP_LIMIT)) { index ->
            favoritesDockView.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (dockApps.isNotEmpty() || index > 0) marginStart = dp(6)
            })
        }
    }

    private fun dockAppButton(app: AppEntry, index: Int): View {
        val target = app.toPaneTarget()
        val showLabel = showDockLabels()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(2), 0, dp(2), 0)
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
            postDelayed({
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }, (index * 24L).coerceAtMost(120L))
            addView(FrameLayout(context).apply {
                elevation = dp(2).toFloat()
                background = dockIconButtonBackground()
                addView(ImageView(context).apply {
                    setImageDrawable(iconFor(app.toLibraryApp()))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(dp(if (showLabel) 40 else 44), dp(if (showLabel) 40 else 44)))
            if (showLabel) {
                addView(TextView(context).apply {
                    text = app.shortName
                    textSize = 9.5f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(InkDim)
                    includeFontPadding = false
                    setPadding(0, dp(5), 0, 0)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                }
                false
            }
            setOnClickListener { haptic(this); openExternal(target) }
            setOnLongClickListener { haptic(this); showOpenMenu(this, target); true }
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

    private fun styledTypedCommand(text: String): SpannableString {
        val styled = SpannableString("$text|")
        styled.setSpan(ScaleXSpan(0.35f), text.length, text.length + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        styled.setSpan(ForegroundColorSpan(0xFFFF5A3C.toInt()), text.length, text.length + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        // Color whole text with top app's accent when it's a clear match
        val topApp = filteredRibbonEntries().firstOrNull()
        if (topApp != null && text.trim().length >= 2) {
            styled.setSpan(ForegroundColorSpan(topApp.accent), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            styled.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return styled
        }
        // Fall back to verb coloring
        val verb = text.trimStart().substringBefore(' ').lowercase(Locale.US)
        val color = commandVerbColor(verb) ?: return styled
        val start = text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return styled
        val end = (start + verb.length).coerceAtMost(text.length)
        styled.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        styled.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return styled
    }

    private fun commandVerbColor(verb: String): Int? = when (verb) {
        "call" -> 0xFF8FD694.toInt()
        "text", "sms", "message" -> 0xFF5FD0C4.toInt()
        "email", "mail" -> 0xFFF5C451.toInt()
        "open", "launch" -> 0xFFC4B5FF.toInt()
        "calendar", "schedule" -> 0xFFFF8F8F.toInt()
        "play" -> 0xFF57C98A.toInt()
        else -> null
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
            menu.addView(menuItem(if (onHome) "Remove from dock" else "Add to dock", true) {
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
                recordAppLaunch(packageName)
                return
            }
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                startActivity(launchIntent)
                recordAppLaunch(packageName)
                return
            }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "${target.name} isn't available here", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isInstalled(packageName: String) = packageManager.getLaunchIntentForPackage(packageName) != null

    private fun recordAppLaunch(packageName: String) {
        val counts = JSONObject(prefs().getString(APP_USAGE_PREF, "{}") ?: "{}")
        val nextCount = counts.optInt(packageName, 0) + 1
        counts.put(packageName, nextCount)
        prefs().edit().putString(APP_USAGE_PREF, counts.toString()).apply()
        renderFavoritesDock()
    }

    private fun appUsageCounts(): Map<String, Int> {
        val raw = prefs().getString(APP_USAGE_PREF, "{}") ?: "{}"
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, json.optInt(key, 0))
            }
        }
    }

    private fun filteredApps(): List<AppEntry> {
        if (query.isBlank()) return apps
        return apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    private fun librarySearchResults(): List<AppEntry> {
        val q = query.trim()
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

    private fun universalSearchResults(): List<SearchResult> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val results = mutableListOf<SearchResult>()
        if (looksLikeAiQuestion(q)) {
            results.add(SearchResult("Ask Gemini", q, 0xFF8AB4F8.toInt(), SearchKind.AI, aiTarget(q)) { askGemini(q) })
        }
        librarySearchResults().take(6).forEach { app ->
            results.add(SearchResult(app.label, "Open app", app.brandColor, SearchKind.APP, app.toPaneTarget()))
        }
        results.addAll(searchContactResults(q))
        results.addAll(searchMessageResults(q))
        results.addAll(searchCalendarResults(q))
        if (!looksLikeAiQuestion(q) && q.length >= 4) {
            results.add(SearchResult("Ask Gemini", q, 0xFF8AB4F8.toInt(), SearchKind.AI, aiTarget(q)) { askGemini(q) })
        }
        return results.distinctBy { "${it.kind}:${it.title}:${it.subtitle}" }.take(14)
    }

    private fun looksLikeAiQuestion(text: String): Boolean {
        val lower = text.lowercase(Locale.US).trim()
        return lower.endsWith("?") ||
            lower.startsWith("ask ") ||
            lower.startsWith("ai ") ||
            lower.startsWith("gemini ") ||
            listOf("what ", "why ", "how ", "when ", "where ", "who ", "summarize ", "explain ", "draft ").any { lower.startsWith(it) }
    }

    private fun searchContactResults(q: String): List<SearchResult> {
        if (!hasContactsPermission()) {
            if (q.length < 3) return emptyList()
            return listOf(SearchResult("Connect contacts", "Find people, phone numbers, and email addresses", Accent2, SearchKind.CONTACT, null) {
                contactsLauncher.launch(android.Manifest.permission.READ_CONTACTS)
            })
        }
        val results = mutableListOf<SearchResult>()
        queryContactPhones(q).take(3).forEach { contact ->
            results.add(SearchResult(contact.name, "Call or text . ${contact.value}", 0xFF5FD0C4.toInt(), SearchKind.CONTACT, null) {
                startSafeIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(contact.value)}")), "Phone isn't available here")
            })
        }
        queryContactEmails(q).take(3).forEach { contact ->
            results.add(SearchResult(contact.name, "Email . ${contact.value}", 0xFFF5C451.toInt(), SearchKind.EMAIL, null) {
                startSafeIntent(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(contact.value)}")), "Email isn't available here")
            })
        }
        return results
    }

    private fun searchMessageResults(q: String): List<SearchResult> {
        return messages
            .filter { it.sender.contains(q, ignoreCase = true) || it.preview.contains(q, ignoreCase = true) || appLabel(it.packageName).contains(q, ignoreCase = true) }
            .take(5)
            .map {
                val kind = if (it.kind == HUB_KIND_EMAIL) SearchKind.EMAIL else SearchKind.MESSAGE
                SearchResult(it.sender, "${it.preview} . ${appLabel(it.packageName)}", contextColor(it), kind, it.toPaneTarget())
            }
    }

    private fun searchCalendarResults(q: String): List<SearchResult> {
        if (!hasCalendarPermission()) {
            if (q.length < 3 || !"calendar".contains(q, ignoreCase = true)) return emptyList()
            return listOf(SearchResult("Connect calendar", "Search upcoming events", 0xFFFF8F8F.toInt(), SearchKind.CALENDAR, null) {
                calendarPermissionLauncher.launch(android.Manifest.permission.READ_CALENDAR)
            })
        }
        return calendarEvents
            .filter { it.title.contains(q, ignoreCase = true) || it.location.contains(q, ignoreCase = true) || it.timeLabel.contains(q, ignoreCase = true) }
            .take(4)
            .map { event ->
                SearchResult(event.title, "${event.timeLabel}${if (event.location.isNotBlank()) " . ${event.location}" else ""}", 0xFFFF8F8F.toInt(), SearchKind.CALENDAR, null) {
                    openCalendarEventOrRequest(event)
                }
            }
    }

    private fun openSearchResult(result: SearchResult) {
        result.action?.invoke()?.let { return }
        val target = result.target ?: return
        when {
            target.kind == PaneKind.AI -> askGemini(target.preview)
            target.kind == PaneKind.MUSIC || target.packageName == null -> openHere(target)
            else -> openExternal(target)
        }
    }

    private fun queryContactPhones(name: String): List<ContactMatch> {
        if (!hasContactsPermission()) return emptyList()
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
            arrayOf("%$name%", "%$name%"),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        ) ?: return emptyList()
        return contactMatches(cursor, name, projection[0], projection[1])
    }

    private fun queryContactEmails(name: String): List<ContactMatch> {
        if (!hasContactsPermission()) return emptyList()
        val projection = arrayOf(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME, ContactsContract.CommonDataKinds.Email.ADDRESS)
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Email.ADDRESS} LIKE ?",
            arrayOf("%$name%", "%$name%"),
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME
        ) ?: return emptyList()
        return contactMatches(cursor, name, projection[0], projection[1])
    }

    private fun contactMatches(cursor: android.database.Cursor, q: String, nameColumn: String, valueColumn: String): List<ContactMatch> {
        return buildList {
            val seen = mutableSetOf<String>()
            cursor.use {
                val nameIdx = it.getColumnIndexOrThrow(nameColumn)
                val valueIdx = it.getColumnIndexOrThrow(valueColumn)
                while (it.moveToNext() && size < 6) {
                    val displayName = it.getString(nameIdx)?.trim().orEmpty()
                    val value = it.getString(valueIdx)?.trim().orEmpty()
                    if (displayName.isBlank() || value.isBlank()) continue
                    val key = "$displayName:$value"
                    if (seen.add(key)) add(ContactMatch(displayName, value))
                }
            }
        }.sortedWith { left, right ->
            val leftStarts = left.name.startsWith(q, ignoreCase = true)
            val rightStarts = right.name.startsWith(q, ignoreCase = true)
            when {
                leftStarts != rightStarts -> if (leftStarts) -1 else 1
                else -> collator.compare(left.name, right.name)
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

    private fun hasContactsPermission(): Boolean {
        return checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun executeTypeToDoCommand(queryText: String): Boolean {
        val command = queryText.trim()
        if (command.isBlank()) return false
        val verb = command.substringBefore(' ').lowercase(Locale.US)
        val body = command.substringAfter(' ', "").trim()
        return when (verb) {
            "ask", "ai", "gemini" -> executeAiCommand(body.ifBlank { command })
            "call" -> executeCallCommand(body, command)
            "text", "sms", "message" -> executeTextCommand(body, command)
            "email", "mail" -> executeEmailCommand(body, command)
            "open", "launch" -> executeOpenCommand(body)
            "calendar", "schedule" -> executeCalendarCommand(body)
            "play" -> executePlayCommand(body)
            else -> false
        }
    }

    private fun executeAiCommand(body: String): Boolean {
        val prompt = body.trim()
        if (prompt.isBlank()) return false
        askGemini(prompt)
        return true
    }

    private fun executeCallCommand(body: String, originalCommand: String): Boolean {
        if (body.isBlank()) return false
        if (requestContactsForCommand(originalCommand)) return true
        val phone = findPhoneContact(body, originalCommand) ?: return false
        startSafeIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone.value)}")), "Phone isn't available here")
        return true
    }

    private fun executeTextCommand(body: String, originalCommand: String): Boolean {
        if (body.isBlank()) return false
        if (requestContactsForCommand(originalCommand)) return true
        val parsed = parseContactPrefix(body, originalCommand, ::findPhoneContact) ?: return false
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(parsed.contact.value)}")).apply {
            putExtra("sms_body", parsed.message)
        }
        startSafeIntent(intent, "Messages isn't available here")
        return true
    }

    private fun executeEmailCommand(body: String, originalCommand: String): Boolean {
        if (body.isBlank()) return false
        if (requestContactsForCommand(originalCommand)) return true
        val parsed = parseEmailCommand(body, originalCommand) ?: return false
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(parsed.contact.value)}")).apply {
            putExtra(Intent.EXTRA_SUBJECT, parsed.message.ifBlank { "Message from Clicks" })
            putExtra(Intent.EXTRA_TEXT, parsed.message)
        }
        startSafeIntent(intent, "Email isn't available here")
        return true
    }

    private fun executeOpenCommand(body: String): Boolean {
        if (body.isBlank()) return false
        val target = apps
            .filter { it.label.contains(body, ignoreCase = true) || it.packageName.contains(body, ignoreCase = true) }
            .minWithOrNull { left, right ->
                val leftStarts = left.label.startsWith(body, ignoreCase = true)
                val rightStarts = right.label.startsWith(body, ignoreCase = true)
                when {
                    leftStarts != rightStarts -> if (leftStarts) -1 else 1
                    else -> collator.compare(left.label, right.label)
                }
            } ?: return false
        openExternal(target.toPaneTarget())
        return true
    }

    private fun executeCalendarCommand(body: String): Boolean {
        if (body.isBlank()) return false
        val parsed = parseCalendarCommand(body)
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, parsed.title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, parsed.startMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, parsed.endMs)
        }
        startSafeIntent(intent, "Calendar isn't available here")
        return true
    }

    private fun createCalendarEvent() {
        val now = System.currentTimeMillis()
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, now)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, now + 60L * 60L * 1000L)
        }
        startSafeIntent(intent, "Calendar isn't available here")
    }

    private fun executePlayCommand(body: String): Boolean {
        if (body.isBlank()) return false
        val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, android.provider.MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
            putExtra(android.app.SearchManager.QUERY, body)
        }
        runCatching { startActivity(intent) }
            .onFailure { executeOpenCommand("spotify") }
        return true
    }

    private fun askGemini(prompt: String) {
        if (!requirePro(ProFeature.AI_CHAT)) return
        val target = aiTarget(prompt)
        aiAnswersById[target.id] = AiAnswerState(prompt, if (geminiConfigured()) "Thinking..." else "Gemini needs an API key first.", geminiConfigured())
        openHere(target)
        if (!geminiConfigured()) return
        mediaUiScope.launch(Dispatchers.IO) {
            val answer = runCatching { fetchGeminiAnswer(prompt) }
                .getOrElse { "I couldn't reach Gemini: ${it.message ?: "network unavailable"}" }
            runOnUiThread {
                aiAnswersById[target.id] = AiAnswerState(prompt, answer, false)
                if (openPane?.id == target.id) renderPaneContent(target)
            }
        }
    }

    private fun fetchGeminiAnswer(prompt: String): String {
        val key = prefs().getString(GEMINI_API_KEY_PREF, null)?.trim().orEmpty()
        if (key.isBlank()) return "Gemini needs an API key first."
        val model = prefs().getString(GEMINI_MODEL_PREF, GEMINI_DEFAULT_MODEL)?.trim().orEmpty().ifBlank { GEMINI_DEFAULT_MODEL }
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${URLEncoder.encode(model, "UTF-8")}:generateContent?key=${URLEncoder.encode(key, "UTF-8")}")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", geminiPrompt(prompt))))))
            .put("generationConfig", JSONObject().put("temperature", 0.4).put("maxOutputTokens", 650))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }
        val raw = if (connection.responseCode in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IllegalStateException(error.ifBlank { "HTTP ${connection.responseCode}" })
        }
        val json = JSONObject(raw)
        val candidates = json.optJSONArray("candidates")
        val parts = candidates?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
        return parts?.optJSONObject(0)?.optString("text")?.trim().orEmpty().ifBlank { "Gemini returned no text." }
    }

    private fun geminiPrompt(prompt: String): String {
        val appHints = apps.take(12).joinToString(", ") { it.label }
        val eventHints = calendarEvents.take(4).joinToString("; ") { "${it.title} ${it.timeLabel}" }
        val messageHints = messages.take(6).joinToString("; ") { "${it.sender}: ${it.preview}" }
        return """
            You are Clicks AI inside an Android launcher. Be concise, helpful, and action-oriented.
            User request: $prompt

            Launcher context:
            Apps: $appHints
            Upcoming calendar: $eventHints
            Recent notifications/messages: $messageHints

            If the user is asking to perform a launcher action, suggest the direct Clicks command they can type.
        """.trimIndent()
    }

    private fun geminiConfigured(): Boolean {
        return prefs().getBoolean(GEMINI_ENABLED_PREF, false) &&
            !prefs().getString(GEMINI_API_KEY_PREF, null).isNullOrBlank()
    }

    private fun startSafeIntent(intent: Intent, failureMessage: String) {
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, failureMessage, Toast.LENGTH_SHORT).show() }
    }

    private fun requestContactsForCommand(originalCommand: String): Boolean {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        pendingTypeToDoCommand = originalCommand
        contactsLauncher.launch(android.Manifest.permission.READ_CONTACTS)
        return true
    }

    private fun parseContactPrefix(
        body: String,
        originalCommand: String,
        resolver: (String, String) -> ContactMatch?
    ): ContactCommand? {
        val words = body.split(Regex("\\s+")).filter { it.isNotBlank() }
        for (count in words.size downTo 1) {
            val name = words.take(count).joinToString(" ")
            val contact = resolver(name, originalCommand) ?: continue
            val message = words.drop(count).joinToString(" ")
            return ContactCommand(contact, message)
        }
        return null
    }

    private fun parseEmailCommand(body: String, originalCommand: String): ContactCommand? {
        val toMatch = Regex("\\s+to\\s+", RegexOption.IGNORE_CASE).find(body)
        if (toMatch != null) {
            val message = body.substring(0, toMatch.range.first).trim()
            val name = body.substring(toMatch.range.last + 1).trim()
            val contact = findEmailContact(name, originalCommand) ?: return null
            return ContactCommand(contact, message)
        }
        return parseContactPrefix(body, originalCommand, ::findEmailContact)
    }

    private fun findPhoneContact(name: String, originalCommand: String): ContactMatch? {
        if (name.isBlank()) return null
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingTypeToDoCommand = originalCommand
            contactsLauncher.launch(android.Manifest.permission.READ_CONTACTS)
            return null
        }
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        ) ?: return null
        cursor.use {
            val nameIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val valueIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            return bestContactMatch(it, name, nameIdx, valueIdx)
        }
    }

    private fun findEmailContact(name: String, originalCommand: String): ContactMatch? {
        if (name.isBlank()) return null
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingTypeToDoCommand = originalCommand
            contactsLauncher.launch(android.Manifest.permission.READ_CONTACTS)
            return null
        }
        val projection = arrayOf(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME, ContactsContract.CommonDataKinds.Email.ADDRESS)
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME
        ) ?: return null
        cursor.use {
            val nameIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
            val valueIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
            return bestContactMatch(it, name, nameIdx, valueIdx)
        }
    }

    private fun bestContactMatch(cursor: android.database.Cursor, queryName: String, nameIdx: Int, valueIdx: Int): ContactMatch? {
        var best: ContactMatch? = null
        while (cursor.moveToNext()) {
            val displayName = cursor.getString(nameIdx) ?: continue
            val value = cursor.getString(valueIdx) ?: continue
            if (value.isBlank()) continue
            val starts = displayName.startsWith(queryName, ignoreCase = true)
            val current = ContactMatch(displayName, value)
            if (best == null || starts) best = current
            if (starts) break
        }
        return best
    }

    private fun parseCalendarCommand(body: String): CalendarCommand {
        val zone = ZoneId.systemDefault()
        val lower = body.lowercase(Locale.US)
        val date = when {
            "tomorrow" in lower -> LocalDate.now(zone).plusDays(1)
            "today" in lower -> LocalDate.now(zone)
            else -> LocalDate.now(zone)
        }
        val timeMatch = Regex("\\b(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b", RegexOption.IGNORE_CASE).find(lower)
        val hourRaw = timeMatch?.groups?.get(1)?.value?.toIntOrNull()
        val minute = timeMatch?.groups?.get(2)?.value?.toIntOrNull() ?: 0
        val suffix = timeMatch?.groups?.get(3)?.value?.lowercase(Locale.US)
        val hour = when {
            hourRaw == null -> 12
            suffix == "pm" && hourRaw < 12 -> hourRaw + 12
            suffix == "am" && hourRaw == 12 -> 0
            else -> hourRaw.coerceIn(0, 23)
        }
        val cleanTitle = lower
            .replace(Regex("\\btoday\\b|\\btomorrow\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bat\\s+\\d{1,2}(?::\\d{2})?\\s*(am|pm)?\\b", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { body.trim() }
        val start = LocalDateTime.of(date, LocalTime.of(hour, minute.coerceIn(0, 59)))
        val end = start.plusHours(1)
        return CalendarCommand(
            cleanTitle.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() },
            start.atZone(zone).toInstant().toEpochMilli(),
            end.atZone(zone).toInstant().toEpochMilli()
        )
    }

    private fun hasCalendarPermission(): Boolean {
        return checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun openCalendarEventOrRequest(event: CalendarEvent?) {
        if (!hasCalendarPermission()) {
            calendarPermissionLauncher.launch(android.Manifest.permission.READ_CALENDAR)
            return
        }
        if (event != null) {
            val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId)
            val eventIntent = Intent(Intent.ACTION_VIEW, eventUri).apply {
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.beginMs)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endMs)
            }
            runCatching { startActivity(eventIntent) }
                .onFailure { openCalendarApp() }
            return
        }
        openCalendarApp()
    }

    private fun openCalendarApp() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "Calendar isn't available here", Toast.LENGTH_SHORT).show() }
    }

    private fun loadCalendarEvents(): List<CalendarEvent> {
        if (!hasCalendarPermission()) return emptyList()
        val startMs = System.currentTimeMillis()
        val endMs = startMs + 7L * 24L * 60L * 60L * 1000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, startMs)
            ContentUris.appendId(it, endMs)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY
        )
        val cursor = contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC"
        ) ?: return emptyList()

        return buildList {
            cursor.use {
                val titleIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val eventIdIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                val beginIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val locationIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
                val allDayIdx = it.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                while (it.moveToNext() && size < 4) {
                    val title = it.getString(titleIdx)?.ifBlank { null } ?: "Untitled event"
                    val eventId = it.getLong(eventIdIdx)
                    val begin = it.getLong(beginIdx)
                    val end = it.getLong(endIdx)
                    val location = it.getString(locationIdx).orEmpty()
                    val allDay = it.getInt(allDayIdx) == 1
                    add(CalendarEvent(eventId, title, calendarTimeLabel(begin, end, allDay), location, begin, end))
                }
            }
        }
    }

    private fun calendarTimeLabel(beginMs: Long, endMs: Long, allDay: Boolean): String {
        val zone = ZoneId.systemDefault()
        val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(beginMs), zone)
        val today = LocalDate.now(zone)
        val day = when (start.toLocalDate()) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> start.format(DateTimeFormatter.ofPattern("EEE", Locale.US))
        }
        if (allDay) return "$day all day"
        val time = start.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)).lowercase(Locale.US)
        val end = LocalDateTime.ofInstant(Instant.ofEpochMilli(endMs), zone)
        val endTime = end.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)).lowercase(Locale.US)
        return if (endMs > beginMs && start.toLocalDate() == end.toLocalDate()) "$day $time-$endTime" else "$day $time"
    }

    private fun filteredRibbonEntries(): List<RibbonEntry> {
        if (numberPadOpen) return searchContacts(query)
        if (query.isBlank()) return homeDockApps().map { app -> RibbonEntry(app.shortName, app.brandColor, app.toPaneTarget()) }
        return apps
            .filter { app ->
                app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
            .take(8)
            .map { app -> RibbonEntry(app.shortName, app.brandColor, app.toPaneTarget()) }
            .ifEmpty {
                homeDockApps().map { app -> RibbonEntry(app.shortName, app.brandColor, app.toPaneTarget()) }.filter { entry ->
                    entry.label.contains(query, ignoreCase = true) ||
                        entry.target.name.contains(query, ignoreCase = true) ||
                        entry.target.packageName?.contains(query, ignoreCase = true) == true
                }
            }
    }

    private fun homeDockApps(): List<AppEntry> {
        val hidden = hiddenHomePackages()
        val favorites = apps
            .filter { it.packageName in favoritePackages() && it.packageName !in hidden }
            .take(DOCK_APP_LIMIT)
        if (favorites.size >= DOCK_APP_LIMIT) return favorites
        val favoritePackages = favorites.map { it.packageName }.toSet()
        val usage = appUsageCounts()
        val oftenUsed = apps
            .filter { it.packageName !in favoritePackages && it.packageName !in hidden }
            .sortedWith { left, right ->
                val usageCompare = (usage[right.packageName] ?: 0).compareTo(usage[left.packageName] ?: 0)
                if (usageCompare != 0) usageCompare else collator.compare(left.label, right.label)
            }
            .filter { (usage[it.packageName] ?: 0) > 0 }
        return (favorites + oftenUsed).take(DOCK_APP_LIMIT)
    }

    private fun favoritePackages(): Set<String> = prefs().getStringSet(FAVORITE_APPS_PREF, emptySet()) ?: emptySet()

    private fun hiddenHomePackages(): Set<String> = prefs().getStringSet(HIDDEN_HOME_APPS_PREF, emptySet()) ?: emptySet()

    private fun showDockLabels(): Boolean = prefs().getBoolean(DOCK_LABELS_PREF, true)

    private fun animatedWeatherEnabled(): Boolean = prefs().getBoolean(ANIMATED_WEATHER_PREF, true)

    private fun isOnHome(packageName: String?) = packageName != null &&
        homeDockApps().any { it.packageName == packageName }

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
        renderFavoritesDock()
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

    private fun recentPeople(): List<RecentPerson> {
        val seen = mutableSetOf<String>()
        return messages
            .filter { it.isActualPersonMessage() }
            .filter { seen.add("${it.packageName}:${it.sender}") }
            .take(6)
            .map { RecentPerson(it.key, it.sender, it.preview, it.packageName, it.color, ClicksNotificationListener.notificationAvatars[it.key]) }
    }

    private fun contextItems(kind: String): List<ContextWidgetItem> {
        return messages
            .filter { it.kind == kind }
            .take(6)
            .map {
                ContextWidgetItem(
                    key = it.key,
                    title = it.sender,
                    preview = it.preview,
                    packageName = it.packageName,
                    color = contextColor(it),
                    avatar = ClicksNotificationListener.notificationAvatars[it.key]
                )
            }
    }

    private fun contextColor(message: HubMessage): Int {
        return when (message.kind) {
            HUB_KIND_EMAIL -> 0xFFEA4335.toInt()
            HUB_KIND_NEWS -> 0xFF8AB4F8.toInt()
            HUB_KIND_MAPS -> 0xFF57C98A.toInt()
            else -> message.color
        }
    }

    private fun openContextItem(item: ContextWidgetItem, clearAfterOpen: Boolean) {
        deliverNotificationIntent(
            key = item.key,
            fallback = { openContextItemApp(item) },
            afterOpen = { if (clearAfterOpen) clearHandledContextItem(item) }
        )
    }

    private fun openContextItemApp(item: ContextWidgetItem): Boolean {
        item.packageName.takeIf { it.isNotBlank() }?.let { pkg ->
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                return runCatching { startActivity(it) }
                    .onFailure { Toast.makeText(this, "App isn't available here", Toast.LENGTH_SHORT).show() }
                    .isSuccess
            }
        }
        Toast.makeText(this, "App isn't available here", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun openRecentPerson(person: RecentPerson) {
        deliverNotificationIntent(
            key = person.key,
            fallback = { openRecentConversationFallback(person) },
            afterOpen = { clearHandledConversation(person) }
        )
    }

    private fun openRecentConversationFallback(person: RecentPerson): Boolean {
        val message = messages.firstOrNull {
            it.sender == person.sender && it.packageName == person.packageName
        } ?: messages.firstOrNull { it.sender == person.sender }
        val packageName = message?.packageName?.takeIf { it.isNotBlank() } ?: person.packageName.takeIf { it.isNotBlank() }
        packageName?.let { pkg ->
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                return runCatching { startActivity(it) }
                    .onFailure { Toast.makeText(this, "Conversation isn't available here", Toast.LENGTH_SHORT).show() }
                    .isSuccess
            }
        }
        if (message != null) {
            openHere(message.toPaneTarget())
            return true
        }
        Toast.makeText(this, "No recent conversation found", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun openRecentPersonApp(person: RecentPerson): Boolean {
        person.packageName.takeIf { it.isNotBlank() }?.let { pkg ->
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                return runCatching { startActivity(it) }
                    .onFailure { Toast.makeText(this, "App isn't available here", Toast.LENGTH_SHORT).show() }
                    .isSuccess
            }
        }
        Toast.makeText(this, "App isn't available here", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun deliverNotificationIntent(key: String, fallback: () -> Boolean, afterOpen: () -> Unit) {
        val pending = ClicksNotificationListener.notificationIntents[key]
        if (pending == null) {
            if (fallback()) afterOpen()
            return
        }
        val sent = runCatching {
            pending.send(
                this,
                0,
                Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }.isSuccess
        if (!sent) {
            if (fallback()) afterOpen()
            return
        }
        handler.postDelayed({
            if (window.decorView.hasWindowFocus()) {
                fallback()
            }
            afterOpen()
        }, 650)
    }

    private fun clearHandledConversation(person: RecentPerson) {
        ClicksNotificationListener.notificationIntents.remove(person.key)
        val filtered = messages.filterNot {
            (person.key.isNotBlank() && it.key == person.key) ||
                (it.sender == person.sender && it.packageName == person.packageName)
        }
        if (filtered.size == messages.size) return
        messages = filtered
        val next = JSONArray()
        filtered.forEach { message ->
            next.put(org.json.JSONObject()
                .put("key", message.key)
                .put("sender", message.sender)
                .put("preview", message.preview)
                .put("packageName", message.packageName)
                .put("kind", message.kind)
                .put("color", message.color))
        }
        prefs().edit().putString(HUB_MESSAGES_PREF, next.toString()).apply()
        renderHub()
        refreshNowPlayingCard()
    }

    private fun clearHandledContextItem(item: ContextWidgetItem) {
        ClicksNotificationListener.notificationIntents.remove(item.key)
        ClicksNotificationListener.notificationAvatars.remove(item.key)
        val filtered = messages.filterNot { item.key.isNotBlank() && it.key == item.key }
        if (filtered.size == messages.size) return
        messages = filtered
        persistHubMessages(filtered)
        renderHub()
        refreshNowPlayingCard()
    }

    private fun persistHubMessages(nextMessages: List<HubMessage>) {
        val next = JSONArray()
        nextMessages.forEach { message ->
            next.put(org.json.JSONObject()
                .put("key", message.key)
                .put("sender", message.sender)
                .put("preview", message.preview)
                .put("packageName", message.packageName)
                .put("kind", message.kind)
                .put("color", message.color))
        }
        prefs().edit().putString(HUB_MESSAGES_PREF, next.toString()).apply()
    }

    private fun HubMessage.isActualPersonMessage(): Boolean {
        val cleanSender = sender.trim()
        val cleanPreview = preview.trim()
        if (kind != HUB_KIND_MESSAGE) return false
        if (cleanSender.isBlank()) return false
        if (cleanSender.length < 2 || cleanSender.length > 34) return false
        if (cleanSender.firstOrNull()?.isDigit() == true) return false
        if (preview.contains(Regex("\\b\\d+\\s+messages?\\s+from\\b", RegexOption.IGNORE_CASE))) return false
        if (sender.contains(Regex("\\b\\d+\\s+messages?\\b", RegexOption.IGNORE_CASE))) return false
        if (cleanSender.equals(appLabel(packageName), ignoreCase = true)) return false
        if (cleanSender.equals(packageName.substringAfterLast('.'), ignoreCase = true)) return false
        if (cleanPreview.startsWith("New from", ignoreCase = true)) return false
        if (SUMMARY_SENDER_WORDS.any { cleanSender.equals(it, ignoreCase = true) }) return false
        if (SUMMARY_SENDER_PATTERNS.any { it.containsMatchIn(cleanSender) }) return false
        return true
    }

    private fun appLabel(packageName: String): String {
        return runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName.substringAfterLast('.'))
    }

    private fun clicksSettingsTarget() = PaneTarget("clicks-settings", "Clicks Settings", Accent, PaneKind.SETTINGS, null, null, "Integrations")

    private fun musicTarget() = PaneTarget("clicks-music", "Music", 0xFF57C98A.toInt(), PaneKind.MUSIC, null, null, "Now playing")
    private fun photosTarget() = PaneTarget("clicks-photos", "ZEISS Optics", 0xFFDCE6FF.toInt(), PaneKind.PHOTOS, null, null, "Latest photos")
    private fun aiTarget(prompt: String) = PaneTarget("clicks-ai:${prompt.hashCode()}", "Clicks AI", 0xFF8AB4F8.toInt(), PaneKind.AI, null, null, prompt)

    private fun loadHubMessages(): List<HubMessage> {
        val raw = prefs().getString(HUB_MESSAGES_PREF, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(HubMessage(item.optString("key", ""), item.optString("sender", "Message").ifBlank { "Message" },
                    item.optString("preview", ""), item.optString("packageName", ""),
                    item.optString("kind", inferHubKind(item.optString("packageName", ""))),
                    item.optInt("color", Accent2)))
            }
        }
    }

    private fun inferHubKind(packageName: String): String {
        return when {
            packageName in EMAIL_PACKAGES -> HUB_KIND_EMAIL
            packageName in NEWS_PACKAGES -> HUB_KIND_NEWS
            packageName in MAPS_PACKAGES -> HUB_KIND_MAPS
            else -> HUB_KIND_MESSAGE
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
        if (!::weatherTempView.isInitialized) return
        weatherTempView.text = prefs().getString(WEATHER_TEMP_PREF, "--")
        weatherMetaView.text = prefs().getString(WEATHER_META_PREF, "Tap for local weather")
        weatherFeelsView.text = prefs().getString(WEATHER_FEELS_PREF, "Feels --")
        weatherStatsView.text = prefs().getString(WEATHER_STATS_PREF, "Local")
        weatherIconView.setWeatherCode(prefs().getInt(WEATHER_CODE_PREF, 0))
        weatherIconView.setAnimationEnabled(animatedWeatherEnabled())
    }

    private fun refreshWeather(force: Boolean) {
        if (!::weatherTempView.isInitialized) return
        if (!hasWeatherPermission()) {
            showWeatherNeedsPermission()
            return
        }
        val lastFetch = prefs().getLong(WEATHER_FETCHED_AT_PREF, 0L)
        if (!force && System.currentTimeMillis() - lastFetch < 30L * 60L * 1000L) {
            updateClock()
            return
        }
        val location = bestLastKnownLocation()
        if (location == null) {
            weatherTempView.text = "--"
            weatherMetaView.text = "Tap to locate weather"
            return
        }
        weatherMetaView.text = "Updating local weather"
        mediaUiScope.launch(Dispatchers.IO) {
            val result = runCatching { fetchWeather(location) }.getOrNull()
            runOnUiThread {
                if (result == null) {
                    weatherMetaView.text = "Weather unavailable"
                    return@runOnUiThread
                }
                val nextTemp = "${result.tempF}°"
                val nextMeta = result.label.uppercase(Locale.US)
                val nextFeels = "Feels ${result.feelsLikeF}°"
                val nextStats = "${result.humidity}% RH . ${result.windMph} mph"
                val weatherChanged =
                    prefs().getString(WEATHER_TEMP_PREF, null) != nextTemp ||
                        prefs().getString(WEATHER_META_PREF, null) != nextMeta ||
                        prefs().getString(WEATHER_FEELS_PREF, null) != nextFeels ||
                        prefs().getString(WEATHER_STATS_PREF, null) != nextStats ||
                        prefs().getInt(WEATHER_CODE_PREF, Int.MIN_VALUE) != result.code
                prefs().edit()
                    .putString(WEATHER_TEMP_PREF, nextTemp)
                    .putString(WEATHER_META_PREF, nextMeta)
                    .putString(WEATHER_FEELS_PREF, nextFeels)
                    .putString(WEATHER_STATS_PREF, nextStats)
                    .putInt(WEATHER_CODE_PREF, result.code)
                    .putLong(WEATHER_FETCHED_AT_PREF, System.currentTimeMillis())
                    .apply()
                updateClock()
                if (weatherChanged) weatherIconView.playLivePhotoBurst()
            }
        }
    }

    private fun showWeatherNeedsPermission() {
        if (!::weatherTempView.isInitialized) return
        weatherTempView.text = "--"
        weatherMetaView.text = "Tap for local weather"
        weatherFeelsView.text = "Feels --"
        weatherStatsView.text = "Local"
        weatherIconView.setWeatherCode(0)
        weatherIconView.setAnimationEnabled(animatedWeatherEnabled())
    }

    private fun hasWeatherPermission(): Boolean {
        return checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun bestLastKnownLocation(): Location? {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!hasWeatherPermission()) return null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        return providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    private fun fetchWeather(location: Location): WeatherSnapshot {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}&longitude=${location.longitude}&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto"
        val json = JSONObject(URL(url).readText())
        val current = json.getJSONObject("current")
        val temp = current.getDouble("temperature_2m").toInt()
        val feels = current.optDouble("apparent_temperature", temp.toDouble()).toInt()
        val humidity = current.optInt("relative_humidity_2m", 0)
        val wind = current.optDouble("wind_speed_10m", 0.0).toInt()
        val code = current.optInt("weather_code", 0)
        return WeatherSnapshot(temp, feels, humidity, wind, code, weatherCodeLabel(code))
    }

    private fun weatherCodeLabel(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        95, 96, 99 -> "Storm"
        else -> "Local weather"
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

    private fun frostedHeaderBg(): Drawable {
        val base = GradientDrawable().apply { setColor(0xCC16181D.toInt()) }
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x18FFFFFF, 0x00FFFFFF))
        return LayerDrawable(arrayOf(base, sheen))
    }

    private fun musicHeaderGlassBg(): Drawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0x6616181D,
            0x4A101217,
            0x6A07080A
        )).apply {
            cornerRadius = dp(0).toFloat()
        }
        val blurTint = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
            0x1FFFFFFF,
            0x08000000,
            0x22000000
        ))
        val lowerEdge = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0x00FFFFFF,
            0x24000000
        ))
        return LayerDrawable(arrayOf(base, blurTint, lowerEdge))
    }

    private fun libraryHeaderGlassBg(): Drawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0x7A171A20,
            0x55111318,
            0x7607080A
        ))
        val sheen = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
            0x22FFFFFF,
            0x06000000,
            0x18000000
        ))
        val edge = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0x14FFFFFF,
            0x00000000,
            0x2A000000
        ))
        return LayerDrawable(arrayOf(base, sheen, edge))
    }

    private fun roundedPanel(fill: Int, radius: Int, stroke: Int? = null) =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(brighten(fill), fill)).apply {
            cornerRadius = radius.toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun weatherHeaderBackground(): Drawable {
        val body = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xF020232A.toInt(),
            0xF0181B20.toInt(),
            0xF014161B.toInt()
        )).apply {
            cornerRadius = dp(26).toFloat()
            setStroke(dp(1), 0x14FFFFFF)
        }
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0x12FFFFFF,
            0x00FFFFFF
        )).apply { cornerRadius = dp(26).toFloat() }
        val lowerShade = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(
            0x66000000,
            0x00000000
        )).apply { cornerRadius = dp(26).toFloat() }
        return LayerDrawable(arrayOf(body, sheen, lowerShade)).apply {
            setLayerInset(1, dp(1), dp(1), dp(1), dp(42))
            setLayerInset(2, dp(1), dp(38), dp(1), dp(1))
        }
    }

    private fun homeKeyboardWidgetBackground(): Drawable {
        val body = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xF020232A.toInt(),
            0xF014161B.toInt(),
            0xF00B0C0F.toInt()
        )).apply {
            cornerRadius = dp(28).toFloat()
            setStroke(dp(1), 0x18FFFFFF)
        }
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0x12FFFFFF,
            0x00FFFFFF
        )).apply { cornerRadius = dp(28).toFloat() }
        return LayerDrawable(arrayOf(body, sheen)).apply {
            setLayerInset(1, dp(1), dp(1), dp(1), dp(96))
        }
    }

    private fun widgetKeyboardHeight(): Int {
        return (keyboardHeight() + dp(44)).coerceIn(dp(250), dp(380))
    }

    private fun recessedDockBackground(): Drawable {
        val pocket = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xFF030405.toInt(),
            0xFF05070A.toInt(),
            0xFF090B0F.toInt()
        )).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), 0xFF020304.toInt())
        }
        val innerTopShade = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xE6000000.toInt(),
            0x00000000
        )).apply { cornerRadius = dp(24).toFloat() }
        val innerSideShade = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
            0x8A000000.toInt(),
            0x00000000,
            0x00000000,
            0x8A000000.toInt()
        )).apply { cornerRadius = dp(24).toFloat() }
        val lowerInsetRim = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(
            0x181B2028,
            0x00000000
        )).apply { cornerRadius = dp(24).toFloat() }
        val carvedEdge = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), 0x331C222B)
        }
        return LayerDrawable(arrayOf(pocket, innerTopShade, innerSideShade, lowerInsetRim, carvedEdge)).apply {
            setLayerInset(1, dp(2), dp(2), dp(2), dp(46))
            setLayerInset(2, dp(1), dp(2), dp(1), dp(2))
            setLayerInset(3, dp(3), dp(44), dp(3), dp(2))
            setLayerInset(4, 0, 0, 0, 0)
        }
    }

    private fun dockIconButtonBackground(): Drawable {
        val skirt = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xFF050608.toInt(),
            0xFF020203.toInt()
        )).apply {
            cornerRadius = dp(14).toFloat()
        }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xFF252A32.toInt(),
            0xFF181C23.toInt(),
            0xFF0C0E12.toInt()
        )).apply {
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), 0xFF11151B.toInt())
        }
        val softTopEdge = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0x18FFFFFF,
            0x00FFFFFF
        )).apply { cornerRadius = dp(14).toFloat() }
        return LayerDrawable(arrayOf(skirt, face, softTopEdge)).apply {
            setLayerInset(0, 0, dp(3), 0, 0)
            setLayerInset(1, dp(1), 0, dp(1), dp(3))
            setLayerInset(2, dp(2), dp(1), dp(2), dp(24))
        }
    }

    private fun widgetCircleBackground(): Drawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xFF20242C.toInt(),
            0xFF111419.toInt(),
            0xFF07080B.toInt()
        )).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), 0xFF2A303A.toInt())
        }
        val shade = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0x33000000,
            0x00000000
        )).apply { shape = GradientDrawable.OVAL }
        return LayerDrawable(arrayOf(base, shade)).apply {
            setLayerInset(1, dp(2), dp(2), dp(2), dp(18))
        }
    }

    private fun widgetTileBackground(): Drawable {
        val pocket = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xFF0A0C10.toInt(),
            0xFF11141A.toInt(),
            0xFF08090C.toInt()
        )).apply {
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), 0xFF252A33.toInt())
        }
        val innerShade = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0x77000000,
            0x00000000
        )).apply { cornerRadius = dp(22).toFloat() }
        return LayerDrawable(arrayOf(pocket, innerShade)).apply {
            setLayerInset(1, dp(1), dp(1), dp(1), dp(96))
        }
    }

    private fun widgetEmptyBackground(): Drawable {
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0x3316181D,
            0x14000000
        )).apply {
            cornerRadius = dp(26).toFloat()
            setStroke(dp(1), 0x332A2C33)
        }
    }

    private fun widgetProviderRowBackground(): Drawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xEE16181D.toInt(),
            0xEE0C0E12.toInt()
        )).apply {
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), 0x332A2C33)
        }
        val top = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0x12FFFFFF,
            0x00FFFFFF
        )).apply { cornerRadius = dp(18).toFloat() }
        return LayerDrawable(arrayOf(base, top)).apply {
            setLayerInset(1, dp(1), dp(1), dp(1), dp(45))
        }
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
        val key = "${app.target.packageName}:${app.accent}"
        fallbackIconCache.get(key)?.let { return it }
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
        val drawable = android.graphics.drawable.BitmapDrawable(resources, bitmap)
        fallbackIconCache.put(key, drawable)
        return drawable
    }

    private fun showIconMenu(anchor: View, app: LibraryApp) {
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply { setColor(Panel2); cornerRadius = dp(8).toFloat(); setStroke(dp(1), Line) }
        }
        val popup = PopupWindow(menu, dp(224), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply { isOutsideTouchable = true }
        app.target.packageName?.let { packageName ->
            val onHome = isOnHome(packageName)
            menu.addView(menuItem(if (onHome) "Remove from dock" else "Add to dock", true) {
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

    private inner class SpaceKeyView(context: Context) : TextView(context) {
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
        }
        private var cachedLineShader: android.graphics.LinearGradient? = null
        private var cachedShaderWidth = 0
        private var cachedShaderColor = 0

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            cachedLineShader = null
        }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.translate(0f, -dp(5).toFloat())
            super.onDraw(canvas)
            canvas.restore()
            val centerX = width / 2f
            val y = (height / 2f + textSize * 0.62f).coerceAtMost(height - dp(10).toFloat())
            val halfWidth = (width * 0.11f).coerceIn(dp(24).toFloat(), dp(50).toFloat())

            if (cachedLineShader == null || cachedShaderWidth != width || cachedShaderColor != goKeyColor) {
                cachedShaderWidth = width
                cachedShaderColor = goKeyColor
                cachedLineShader = android.graphics.LinearGradient(
                    centerX - halfWidth, y, centerX + halfWidth, y,
                    intArrayOf(adjustAlpha(goKeyColor, 0x00), adjustAlpha(goKeyColor, 0x8A), adjustAlpha(goKeyColor, 0x00)),
                    floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
                )
            }
            linePaint.shader = cachedLineShader
            linePaint.strokeWidth = dp(4).toFloat()
            linePaint.alpha = 58
            canvas.drawLine(centerX - halfWidth, y, centerX + halfWidth, y, linePaint)

            linePaint.strokeWidth = dp(1).toFloat()
            linePaint.alpha = 150
            canvas.drawLine(centerX - halfWidth * 0.68f, y, centerX + halfWidth * 0.68f, y, linePaint)
            linePaint.shader = null
            linePaint.alpha = 255
        }
    }

    private fun keyboardDeckBackground(): Drawable {
        if (keyboardTheme == KEYBOARD_THEME_DEFAULT) return GradientDrawable().apply { setColor(0xFF000000.toInt()) }
        val colors = when (keyboardTheme) {
            KEYBOARD_THEME_SKEUO -> intArrayOf(0xFF24262D.toInt(), 0xFF101116.toInt(), 0xFF050506.toInt())
            KEYBOARD_THEME_CLICKS -> intArrayOf(0xFF1A1C21.toInt(), 0xFF111318.toInt(), 0xFF08090C.toInt(), 0xFF030304.toInt())
            KEYBOARD_THEME_GOKEYS -> intArrayOf(0xFF15171B.toInt(), 0xFF101115.toInt(), 0xFF0B0C0F.toInt())
            else -> intArrayOf(0xFF1F2127.toInt(), 0xFF0C0D10.toInt(), 0xFF030304.toInt())
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = if (keyboardTheme == KEYBOARD_THEME_GOKEYS) dp(22).toFloat() else 0f
            setStroke(dp(1), when (keyboardTheme) {
                KEYBOARD_THEME_SKEUO -> 0x303B4250
                KEYBOARD_THEME_GOKEYS -> 0x14FFFFFF
                else -> 0x181B20
            })
        }
    }

    private fun keyIdleBackground(label: String): Drawable? {
        if (keyboardTheme == KEYBOARD_THEME_GOKEYS) return goKeysKeyBackground(label, pressed = false)
        if (label == "enter") return themedGoKeyBackground(goKeyColor, pressed = false)
        if (label == "123") return themed123KeyBackground(pressed = false)
        return when (keyboardTheme) {
            KEYBOARD_THEME_CLICKS -> physicalKeyBackground(pressed = false, premium = false, fn = isFnKey(label))
            KEYBOARD_THEME_SKEUO -> physicalKeyBackground(pressed = false, premium = true, fn = isFnKey(label))
            else -> null
        }
    }

    private fun keyPressedBackground(label: String): Drawable {
        if (keyboardTheme == KEYBOARD_THEME_GOKEYS) return goKeysKeyBackground(label, pressed = true)
        if (label == "enter") return themedGoKeyBackground(brighten(goKeyColor), pressed = true)
        if (label == "123") return themed123KeyBackground(pressed = true)
        return when (keyboardTheme) {
            KEYBOARD_THEME_CLICKS -> physicalKeyBackground(pressed = true, premium = false, fn = isFnKey(label))
            KEYBOARD_THEME_SKEUO -> physicalKeyBackground(pressed = true, premium = true, fn = isFnKey(label))
            else -> keyBackground(KeyHighlight)
        }
    }

    private fun goKeysKeyBackground(label: String, pressed: Boolean): Drawable {
        val isGo = label == "enter"
        val radius = dp(if (isGo) 9 else 6).toFloat()
        if (isGo) {
            return GradientDrawable().apply {
                setColor(if (pressed) 0xFFE35C16.toInt() else 0xFFFF7A2A.toInt())
                cornerRadius = radius
                setStroke(dp(1), if (pressed) 0xFFE35C16.toInt() else 0xFFFF8A43.toInt())
            }
        }
        return GradientDrawable().apply {
            setColor(if (pressed) 0xFF1A1D22.toInt() else 0xFF22262D.toInt())
            cornerRadius = radius
            setStroke(dp(1), if (pressed) 0xFF16191E.toInt() else 0xFF2E333B.toInt())
        }
    }

    private fun themed123KeyBackground(pressed: Boolean): Drawable {
        if (keyboardTheme == KEYBOARD_THEME_DEFAULT) return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (pressed) KeyHighlight else Key)
            setStroke(dp(1), KeyEdge)
        }
        val clicks = keyboardTheme == KEYBOARD_THEME_CLICKS
        val faceTop = if (pressed) 0xFF3A3E4A.toInt() else if (clicks) 0xFF22252B.toInt() else 0xFF34373E.toInt()
        val faceMid = if (pressed) 0xFF2A2D36.toInt() else if (clicks) 0xFF171A1F.toInt() else 0xFF202329.toInt()
        val faceBot = if (pressed) 0xFF151719.toInt() else if (clicks) 0xFF07080B.toInt() else 0xFF0B0C10.toInt()
        val skirt = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF050609.toInt()) }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(faceTop, faceMid, faceBot)).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), if (clicks) 0xFF111318.toInt() else 0xFF05060A.toInt())
        }
        val glint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (clicks) 0x10FFFFFF else 0x3DFFFFFF, 0x00FFFFFF
        )).apply { shape = GradientDrawable.OVAL }
        return LayerDrawable(arrayOf(skirt, face, glint)).apply {
            val drop = if (pressed) dp(1) else dp(if (clicks) 4 else 6)
            setLayerInset(0, dp(1), drop, dp(1), 0)
            setLayerInset(1, dp(2), 0, dp(2), drop)
            setLayerInset(2, dp(8), dp(5), dp(8), dp(if (clicks) 20 else 18))
        }
    }

    private fun isFnKey(label: String) = label in setOf("123", "clicks", "back", "shift", "abc", "period")

    private fun keyBackground() = keyBackground(null)

    private fun keyBackground(fillColor: Int?): GradientDrawable {
        if (fillColor != null) return GradientDrawable().apply {
            setColor(fillColor); cornerRadius = dp(6).toFloat(); setStroke(dp(1), brighten(fillColor))
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF2A2D36.toInt(), Key)).apply {
            cornerRadius = dp(6).toFloat(); setStroke(dp(1), KeyEdge)
        }
    }

    private fun physicalKeyBackground(pressed: Boolean, premium: Boolean, fn: Boolean): Drawable {
        val clicks = keyboardTheme == KEYBOARD_THEME_CLICKS
        val radius = dp(when {
            premium -> 10
            clicks -> 5
            else -> 9
        }).toFloat()
        val skirt = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            when {
                premium -> 0xFF14171D.toInt()
                clicks -> 0xFF050609.toInt()
                else -> 0xFF07080B.toInt()
            },
            if (clicks) 0xFF020304.toInt() else 0xFF050609.toInt(),
            0xFF010102.toInt()
        )).apply {
            cornerRadius = radius
            setStroke(dp(1), 0xFF040507.toInt())
        }
        val outerRim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            when {
                pressed -> 0xFFFF8E68.toInt()
                premium -> 0xFF525866.toInt()
                clicks -> 0xFF24272F.toInt()
                else -> 0xFF34373E.toInt()
            },
            if (clicks) 0xFF05060A.toInt() else 0xFF090A0D.toInt()
        )).apply {
            cornerRadius = radius
            setStroke(dp(1), if (premium) 0xFF05060A.toInt() else 0xFF040507.toInt())
        }
        val faceColors = when {
            pressed -> intArrayOf(0xFFFF9B72.toInt(), 0xFFFF5A3C.toInt(), 0xFF9C2F11.toInt())
            premium -> intArrayOf(0xFF4B505B.toInt(), 0xFF2D323D.toInt(), 0xFF151821.toInt(), 0xFF08090D.toInt())
            clicks -> intArrayOf(0xFF2B2E35.toInt(), 0xFF202329.toInt(), 0xFF15171C.toInt(), 0xFF0A0B0E.toInt())
            else -> intArrayOf(0xFF34373E.toInt(), 0xFF202329.toInt(), 0xFF14161B.toInt(), 0xFF0B0C10.toInt())
        }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, faceColors).apply {
            cornerRadius = radius
            setStroke(dp(1), if (pressed) 0xFFFFB199.toInt() else if (premium) 0xFF3C4350.toInt() else if (clicks) 0xFF111318.toInt() else 0xFF05060A.toInt())
        }
        val topGlint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (pressed) 0x66FFFFFF else if (premium) 0x55FFFFFF else if (clicks) 0x20FFFFFF else 0x3DFFFFFF,
            0x00FFFFFF
        )).apply {
            cornerRadius = radius
        }
        val warmBleed = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(
            if (fn && !pressed) (if (clicks) 0x16F5C451 else 0x22F5C451) else if (pressed) 0x66FF5A3C else 0x00000000,
            0x00000000
        )).apply {
            cornerRadius = radius
        }
        return LayerDrawable(arrayOf(skirt, outerRim, face, warmBleed, topGlint)).apply {
            val drop = if (pressed) dp(1) else dp(when {
                premium -> 8
                clicks -> 8
                else -> 5
            })
            val side = if (premium) dp(2) else dp(if (clicks) 2 else 1)
            setLayerInset(0, dp(1), drop, dp(1), 0)
            setLayerInset(1, side, dp(if (pressed) 1 else 0), side, drop)
            setLayerInset(2, side + dp(1), dp(1), side + dp(1), drop + dp(if (premium) 1 else 0))
            setLayerInset(3, side + dp(2), dp(if (clicks) 5 else 3), side + dp(2), drop + dp(1))
            setLayerInset(4, side + dp(3), dp(2), side + dp(3), dp(if (premium) 19 else if (clicks) 24 else 20))
        }
    }

    private fun themedGoKeyBackground(fillColor: Int, pressed: Boolean): Drawable {
        if (keyboardTheme == KEYBOARD_THEME_DEFAULT) return goKeyBackground(fillColor)
        val skirt = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF050609.toInt())
        }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(brighten(fillColor), fillColor, darken(fillColor))).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), brighten(fillColor))
        }
        val glint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x55FFFFFF, 0x00FFFFFF)).apply {
            shape = GradientDrawable.OVAL
        }
        return LayerDrawable(arrayOf(skirt, face, glint)).apply {
            val drop = if (pressed) dp(1) else dp(if (keyboardTheme == KEYBOARD_THEME_SKEUO) 6 else 4)
            setLayerInset(0, dp(1), drop, dp(1), 0)
            setLayerInset(1, dp(2), 0, dp(2), drop)
            setLayerInset(2, dp(8), dp(5), dp(8), dp(if (keyboardTheme == KEYBOARD_THEME_SKEUO) 18 else 20))
        }
    }

    private fun keyboardThemePillBackground(value: String): Drawable {
        val accent = when (value) {
            KEYBOARD_THEME_CLICKS -> Accent2
            KEYBOARD_THEME_SKEUO -> 0xFF8FD694.toInt()
            KEYBOARD_THEME_GOKEYS -> 0xFFF2691E.toInt()
            else -> Accent
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(brighten(accent), accent)).apply {
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), brighten(accent))
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

    private fun keyVerticalInset(): Int {
        if (keyboardTheme == KEYBOARD_THEME_GOKEYS) return dp(3 + keyboardSize * 2 / 100)
        if (keyboardTheme == KEYBOARD_THEME_CLICKS) return dp(10 + keyboardSize * 5 / 100)
        return dp(7 + keyboardSize * 4 / 100)
    }

    private fun keyHorizontalInset(): Int {
        if (keyboardTheme == KEYBOARD_THEME_GOKEYS) return dp(1)
        return dp(1)
    }

    private fun goKeySize() = dp(43 + (keyboardSize * 8 / 100))

    private fun themedGoKeySize(): Int {
        return dp(39 + (keyboardSize * 6 / 100))
    }

    private fun clickWheelSize(): Int {
        val dockBound = keyboardHeight() - dp(16)
        val widthBound = resources.displayMetrics.widthPixels - dp(18)
        val preferred = dp(272 + (keyboardSize * 16 / 100))
        return preferred.coerceAtMost(dockBound).coerceAtMost(widthBound).coerceAtLeast(dp(246))
    }

    private fun hintBottomGap() = dp(2 + (keyboardSize * 2 / 100))
    private fun keyboardHeight() = dp(272 + keyboardSize * 80 / 100)
    private fun keyboardTopPadding() = dp(4)
    private fun keyboardBottomPadding() = dp(20)

    private fun keyTextSize(label: String): Float {
        if (keyboardTheme == KEYBOARD_THEME_GOKEYS) {
            return when (label) {
                "space", "123", "clicks", "period", "abc", "back", "shift" -> 11.2f + keyboardSize * 0.8f / 100f
                "enter" -> 13.2f + keyboardSize * 0.8f / 100f
                else -> 13.4f + keyboardSize * 1.0f / 100f
            }
        }
        if (numberPadOpen && label.length == 1 && label[0].isDigit()) return 26f + keyboardSize * 2f / 100f
        val base = when (label) { "shift" -> 24f; "space" -> 18f; "123", "clicks", "enter", "back", "period", "abc" -> 13.5f; else -> 20f }
        val growth = when (label) { "shift" -> 2.5f; "space" -> 2f; "123", "clicks", "enter", "back", "period", "abc" -> 1.5f; else -> 2.5f }
        return base + (keyboardSize * growth / 100f)
    }

    private fun keyTextColor(label: String) = if (keyboardTheme == KEYBOARD_THEME_GOKEYS) {
        when (label) {
            "enter" -> 0xFFFFFFFF.toInt()
            "shift" -> when (shiftState) { ShiftState.OFF -> 0xFF9AA1AB.toInt(); ShiftState.ONCE -> Ink; ShiftState.LOCK -> 0xFFF2691E.toInt() }
            "123", "clicks", "back", "period", "abc", "space" -> 0xFF9AA1AB.toInt()
            else -> 0xFFF3F5F8.toInt()
        }
    } else when (label) {
            "enter" -> 0xFF180A06.toInt()
            "shift" -> when (shiftState) { ShiftState.OFF -> 0xFF7D8078.toInt(); ShiftState.ONCE -> Ink; ShiftState.LOCK -> Accent }
            "123", "clicks", "back", "period", "abc" -> 0xFF7D8078.toInt()
            else -> Ink
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun haptic(view: View) {
        if (!hapticsEnabled) return
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    // Pop-select micro-interaction: quick press-in, fire the action, then an
    // overshoot spring back to rest. Scale-only, so neighbours never reflow.
    private fun popThenRun(view: View, action: () -> Unit) {
        view.animate().cancel()
        view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(85)
            .withEndAction {
                action()
                view.animate().scaleX(1f).scaleY(1f).setDuration(240)
                    .setInterpolator(android.view.animation.OvershootInterpolator(3f))
                    .start()
            }
            .start()
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

    private fun adjustAlpha(color: Int, alpha: Float): Int {
        return adjustAlpha(color, (255 * alpha).toInt().coerceIn(0, 255))
    }

    private fun adjustAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun darken(color: Int) = Color.rgb(
        (Color.red(color) - 34).coerceAtLeast(0),
        (Color.green(color) - 34).coerceAtLeast(0),
        (Color.blue(color) - 34).coerceAtLeast(0)
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

    private inner class AnimatedWeatherIconView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var weatherCode = 0
        private var animationEnabled = true
        private var animationUntilMs = 0L
        private var animationStartedAt = System.currentTimeMillis()

        fun setWeatherCode(code: Int) {
            weatherCode = code
            invalidate()
        }

        fun setAnimationEnabled(enabled: Boolean) {
            animationEnabled = enabled
            if (!enabled) {
                animationUntilMs = 0L
            }
            invalidate()
        }

        fun playLivePhotoBurst() {
            if (animationEnabled) startBurst() else invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            invalidate()
        }

        private fun startBurst() {
            animationStartedAt = System.currentTimeMillis()
            animationUntilMs = animationStartedAt + WEATHER_ANIMATION_BURST_MS
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val now = System.currentTimeMillis()
            val running = animationEnabled && now < animationUntilMs && isShown
            val t = if (running) ((now - animationStartedAt) % 2600L) / 2600f else 0.12f
            val cx = width / 2f
            val cy = height / 2f
            when (weatherCode) {
                45, 48 -> drawCloud(canvas, cx, cy, 0xFFB7BBC4.toInt(), t, fog = true)
                51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> drawCloud(canvas, cx, cy, 0xFF8FD694.toInt(), t, rain = true)
                71, 73, 75, 77, 85, 86 -> drawCloud(canvas, cx, cy, 0xFFE8EDF7.toInt(), t, snow = true)
                95, 96, 99 -> drawCloud(canvas, cx, cy, Accent, t, rain = true)
                1, 2, 3 -> {
                    drawSun(canvas, cx - dp(7), cy - dp(5), t)
                    drawCloud(canvas, cx + dp(4), cy + dp(5), 0xFFD8DCE6.toInt(), t)
                }
                else -> drawSun(canvas, cx, cy, t)
            }
            if (running) postInvalidateOnAnimation()
        }

        private fun drawSun(canvas: Canvas, cx: Float, cy: Float, t: Float) {
            val pulse = 1f + kotlin.math.sin(t * Math.PI * 2).toFloat() * 0.035f
            paint.color = 0xFFF5C451.toInt()
            canvas.drawCircle(cx, cy, dp(12) * pulse, paint)
            paint.color = 0x33F5C451.toInt()
            canvas.drawCircle(cx, cy, dp(18) * pulse, paint)
        }

        private fun drawCloud(canvas: Canvas, cx: Float, cy: Float, color: Int, t: Float, rain: Boolean = false, snow: Boolean = false, fog: Boolean = false) {
            val drift = kotlin.math.sin(t * Math.PI * 2).toFloat() * dp(1)
            paint.color = color
            canvas.drawCircle(cx - dp(9) + drift, cy + dp(1), dp(9).toFloat(), paint)
            canvas.drawCircle(cx + drift, cy - dp(5), dp(12).toFloat(), paint)
            canvas.drawCircle(cx + dp(12) + drift, cy + dp(2), dp(8).toFloat(), paint)
            canvas.drawRoundRect(cx - dp(19) + drift, cy, cx + dp(21) + drift, cy + dp(13), dp(8).toFloat(), dp(8).toFloat(), paint)
            paint.strokeWidth = dp(2).toFloat()
            paint.strokeCap = Paint.Cap.ROUND
            when {
                rain -> {
                    paint.color = 0xFF8FD694.toInt()
                    repeat(3) { i ->
                        val x = cx - dp(10) + i * dp(10) + drift
                        val y = cy + dp(18) + ((t * dp(8)) % dp(8))
                        canvas.drawLine(x, y, x - dp(2), y + dp(7), paint)
                    }
                }
                snow -> {
                    paint.color = 0xFFE8EDF7.toInt()
                    repeat(3) { i ->
                        canvas.drawCircle(cx - dp(10) + i * dp(10) + drift, cy + dp(22) + kotlin.math.sin((t + i) * Math.PI).toFloat() * dp(2), dp(2).toFloat(), paint)
                    }
                }
                fog -> {
                    paint.color = 0x778B8F99.toInt()
                    repeat(2) { i ->
                        val y = cy + dp(18) + i * dp(6)
                        canvas.drawLine(cx - dp(18), y, cx + dp(18), y, paint)
                    }
                }
            }
        }
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

    private enum class PaneKind { CHAT, MAIL, LIST, SETTINGS, MUSIC, PHOTOS, AI }
    private enum class ShiftState { OFF, ONCE, LOCK }

    private data class PaneTarget(val id: String, val name: String, val accent: Int, val kind: PaneKind,
        val packageName: String?, val deepLinkUri: String?, val preview: String)
    private data class AppEntry(val label: String, val shortName: String, val packageName: String, val componentName: ComponentName, val brandColor: Int)
    private data class LibraryCategory(val name: String, val accent: Int, val apps: List<LibraryApp>)
    private data class LibraryApp(val name: String, val accent: Int, val target: PaneTarget, val componentName: ComponentName?)
    private data class IconPack(val name: String, val packageName: String)
    private data class IconPackIcon(val packageName: String, val drawableName: String)
    private data class RibbonEntry(val label: String, val accent: Int, val target: PaneTarget)
    private data class HomeTileSpec(val id: String, val col: Int, val row: Int, val colSpan: Int, val rowSpan: Int)
    private data class HubMessage(val key: String, val sender: String, val preview: String, val packageName: String, val kind: String, val color: Int)
    private data class ChatLine(val text: String, val fromMe: Boolean)
    private data class ContactMatch(val name: String, val value: String)
    private data class ContactCommand(val contact: ContactMatch, val message: String)
    private data class CalendarCommand(val title: String, val startMs: Long, val endMs: Long)
    private data class AiAnswerState(val prompt: String, val answer: String, val loading: Boolean)
    private data class SearchResult(val title: String, val subtitle: String, val accent: Int, val kind: SearchKind, val target: PaneTarget?, val action: (() -> Unit)? = null)
    private enum class SearchKind { APP, CONTACT, EMAIL, MESSAGE, CALENDAR, AI }
    private data class WeatherSnapshot(val tempF: Int, val feelsLikeF: Int, val humidity: Int, val windMph: Int, val code: Int, val label: String)
    private data class WidgetSpec(val id: Int, val size: String)

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
        private const val KEYBOARD_PLACEMENT_PREF = "keyboard_placement"
        private const val KEYBOARD_PLACEMENT_INTRO_PREF = "keyboard_placement_intro_shown"
        private const val KEYBOARD_PLACEMENT_DOCKED = "docked"
        private const val KEYBOARD_PLACEMENT_WIDGET = "widget"
        private const val KEYBOARD_THEME_PREF = "keyboard_theme"
        private const val KEYBOARD_THEME_DEFAULT = "default"
        private const val KEYBOARD_THEME_CLICKS = "clicks"
        private const val KEYBOARD_THEME_SKEUO = "skeuo"
        private const val KEYBOARD_THEME_GOKEYS = "gokeys"
        private const val MUSIC_THEME_PREF = "music_theme"
        private const val MUSIC_THEME_MUSIC1 = "music1"
        private const val MUSIC_THEME_BLACK = "music_black"
        private const val PHOTO_BUCKET_PREF = "photo_bucket"
        private const val PHOTO_SELECTED_ID_PREF = "photo_selected_id"
        private const val PHOTO_FAVORITES_PREF = "photo_favorites"
        private const val HAPTICS_PREF = "haptics"
        private const val LIBRARY_GRID_MODE_PREF = "library_grid_mode"
        private const val GO_KEY_COLOR_PREF = "go_key_color"
        private const val FAVORITE_APPS_PREF = "favorite_apps"
        private const val HIDDEN_HOME_APPS_PREF = "hidden_home_apps"
        private const val DOCK_LABELS_PREF = "dock_labels"
        private const val ANIMATED_WEATHER_PREF = "animated_weather"
        private const val DOCK_APP_LIMIT = 5
        private const val APP_USAGE_PREF = "app_usage_counts"
        private const val WEATHER_TEMP_PREF = "weather_temp"
        private const val WEATHER_META_PREF = "weather_meta"
        private const val WEATHER_FEELS_PREF = "weather_feels"
        private const val WEATHER_STATS_PREF = "weather_stats"
        private const val WEATHER_CODE_PREF = "weather_code"
        private const val WEATHER_FETCHED_AT_PREF = "weather_fetched_at"
        private const val WEATHER_ANIMATION_BURST_MS = 4000L
        private const val WIDGET_HOST_ID = 1407
        private const val WIDGET_BIND_REQUEST_CODE = 501
        private const val WIDGET_CONFIGURE_REQUEST_CODE = 502
        private const val WIDGET_IDS_PREF = "widget_board_ids"
        private const val WIDGET_SIZE_COMPACT = "compact"
        private const val WIDGET_SIZE_WIDE = "wide"
        private const val WIDGET_SIZE_TALL = "tall"
        private const val WIDGET_SIZE_LARGE = "large"
        private const val HOME_GRID_COLUMNS = 4
        private const val HOME_GRID_ROWS = 12
        private const val HOME_TILE_PREF_PREFIX = "home_tile_v4_"
        private const val HOME_TILE_WEATHER = "weather"
        private const val HOME_TILE_WIDGETS = "widgets"
        private const val HOME_TILE_DOCK = "dock"
        private const val GESTURE_UP_PREF = "gesture_up_action"
        private const val GESTURE_RIGHT_PREF = "gesture_right_action"
        private const val GESTURE_DOWN_PREF = "gesture_down_action"
        private const val GESTURE_NONE = "none"
        private const val GESTURE_WIDGETS = "widgets"
        private const val GESTURE_LIBRARY = "library"
        private const val GESTURE_NOTIFICATIONS = "notifications"
        private const val GESTURE_MUSIC = "music"
        private const val GESTURE_SETTINGS = "settings"
        private const val GESTURE_APP_PREFIX = "app:"
        private const val HUB_MESSAGES_PREF = "hub_messages"
        private const val SPOTIFY_INTEGRATION_PREF = "spotify_integration"
        private const val APPLE_MUSIC_INTEGRATION_PREF = "apple_music_integration"
        private const val GEMINI_ENABLED_PREF = "gemini_enabled"
        private const val GEMINI_API_KEY_PREF = "gemini_api_key"
        private const val GEMINI_MODEL_PREF = "gemini_model"
        private const val GEMINI_DEFAULT_MODEL = "gemini-2.5-flash"
        private const val INTEGRATION_OFF = "off"
        private const val INTEGRATION_MEDIA = "media"
        private const val INTEGRATION_API = "api"
        private const val ACTIVE_ICON_PACK_PREF = "active_icon_pack"
        private const val ICON_OVERRIDE_PREFIX = "icon_override_"
        private const val HUB_KIND_MESSAGE = "message"
        private const val HUB_KIND_EMAIL = "email"
        private const val HUB_KIND_NEWS = "news"
        private const val HUB_KIND_MAPS = "maps"
        private val SUMMARY_SENDER_WORDS = setOf("Found", "Stocks", "News", "Updates", "Promotions", "Social", "Primary")
        private val SUMMARY_SENDER_PATTERNS = listOf(
            Regex("\\bnew\\b", RegexOption.IGNORE_CASE),
            Regex("\\bfound\\b", RegexOption.IGNORE_CASE),
            Regex("\\bstocks?\\b", RegexOption.IGNORE_CASE),
            Regex("\\balerts?\\b", RegexOption.IGNORE_CASE)
        )
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

        private val NEWS_PACKAGES = setOf(
            "com.google.android.apps.magazines"
        )

        private val MAPS_PACKAGES = setOf(
            "com.google.android.apps.maps"
        )

        private val GO_COLORS = listOf(
            ColorOption("ORANGE", Accent), ColorOption("AMBER", Accent2),
            ColorOption("TEAL", 0xFF5FD0C4.toInt()), ColorOption("BLUE", 0xFF54A9EB.toInt()),
            ColorOption("LILAC", 0xFFC4B5FF.toInt()), ColorOption("GREEN", 0xFF8FD694.toInt())
        )
    }

    private data class ColorOption(val name: String, val color: Int)
}
