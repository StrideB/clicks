package com.fran.clicks

import android.Manifest
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
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
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.app.AlertDialog
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import com.fran.clicks.glide.KeyInfo
import com.fran.clicks.glide.StatisticalGlideTypingClassifier
import com.fran.clicks.hardware.ParallaxSensorEngine
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
import android.text.Editable
import android.text.TextWatcher
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
import com.fran.clicks.db.WidgetPersistenceRepository
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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SpellCheckerSession.SpellCheckerSessionListener {
    private val collator = Collator.getInstance()
    private var query = ""
    private var apps: List<AppEntry> = emptyList()
    private var keyboardSize = 0
    private var appIconSize = 0
    private var keyboardTheme = KEYBOARD_THEME_DEFAULT
    private var keyboardPlacement = KEYBOARD_PLACEMENT_DOCKED
    private var themeMode = THEME_MODE_SYSTEM
    private var activeNeuTokens = Neu.Dark
    private var hapticsEnabled = true
    private var keyboardTiltLighting = true
    private var keyboardSettingsOpen = false
    private var goKeyColor = Accent
    private var messages: List<HubMessage> = emptyList()
    private var calendarEvents: List<CalendarEvent> = emptyList()
    private var openPane: PaneTarget? = null
    private var paneView: View? = null
    private var libraryOpen = false
    private var libraryGridMode = true
    private var libraryView: View? = null
    private var libraryViewMode: NeuMode? = null
    private var libraryContentArea: FrameLayout? = null
    private var libraryViewDirty = true
    private var libraryContentReady = false
    private var categoryFolderView: View? = null
    private var widgetBoardView: View? = null
    private var widgetPickerView: View? = null
    private var widgetPickerListHost: FrameLayout? = null
    private var widgetPickerQuery = ""
    private val widgetPickerExpandedApps = mutableSetOf<String>()
    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: AppWidgetHost
    private lateinit var widgetPersistenceRepository: WidgetPersistenceRepository
    private var widgetSpecsCache: List<WidgetSpec>? = null
    private var pendingWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var pendingWidgetProvider: AppWidgetProviderInfo? = null
    private var librarySwipeStartX = 0f
    private var librarySwipeStartY = 0f
    private var librarySwipeTriggered = false
    private var librarySwipeBlockedByWidget = false
    private var libraryDragActive = false
    private var libraryDragStartedOpen = false
    private var libraryDragVertical = false
    private var libraryDragHapticStage = 0
    private var libraryDragTouchX = 0f
    private var libraryGridLiquidBackdrop: LiquidGridBackdropView? = null
    private var libraryGridBlurView: View? = null
    private var libraryGridScrollView: GridFishEyeScrollView? = null
    private var stripSwipeStartX = 0f
    private var stripSwipeStartY = 0f
    private var stripSwipeTriggered = false
    private var paneSwipeStartX = 0f
    private var paneSwipeStartY = 0f
    private var paneSwipeTriggered = false
    private var paneSwipeFromDock = false
    private var iconPacksCache: List<IconPack>? = null
    private val iconPackMatchCache = mutableMapOf<String, IconPackIcon?>()
    private val iconPackDrawableCache = mutableMapOf<String, List<IconPackIcon>>()
    private var libraryCategoriesCacheSignature: String? = null
    private var libraryCategoriesCache: List<LibraryCategory> = emptyList()
    private var composeText = ""
    private var aiDraftText = ""
    private var aiDraftActive = false
    private val chatLinesById = mutableMapOf<String, MutableList<ChatLine>>()

    private var shiftState = ShiftState.ONCE
    private var lastSpaceMs = 0L
    private var lastShiftTapMs = 0L
    private var suggestions: List<String> = emptyList()
    private val deleteRepeatHandler = Handler(Looper.getMainLooper())
    private var deleteRepeatRunnable: Runnable? = null
    private var deleteRepeatActive = false
    private var deleteRepeatFired = false
    // Autocorrect undo — stores what was replaced so backspace immediately after reverts it
    private data class AutocorrectUndo(val original: String, val corrected: String, val trailingSpace: Boolean = true)
    private var pendingAutocorrectUndo: AutocorrectUndo? = null
    // Corrections the user explicitly undid (original word -> replacements they rejected). Once
    // rejected, that fix is never auto-applied again — the keyboard stops fighting the user.
    private val rejectedCorrections = HashMap<String, MutableSet<String>>()

    private fun rememberRejectedCorrection(undo: AutocorrectUndo) {
        rejectedCorrections.getOrPut(undo.original.lowercase(Locale.US)) { HashSet() }
            .add(undo.corrected.lowercase(Locale.US))
    }
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
    private var libraryPopulateRunnable: Runnable? = null
    private var lastSuggestWord = ""
    private val keyViews = mutableMapOf<String, TextView>()
    private val keyBounds = mutableMapOf<String, Rect>()
    private enum class WidgetKeyboardSwapState { SEATED, DETACHING, DETACHED, SEATING }
    private var widgetSwapState = WidgetKeyboardSwapState.SEATED
    private var widgetCommittedTheme = KEYBOARD_THEME_DEFAULT
    private var widgetPreviewTheme = KEYBOARD_THEME_DEFAULT
    private var widgetKeyboardHost: FrameLayout? = null
    private var widgetKeyboardModule: FrameLayout? = null
    private var pendingWidgetKeyboardPopIn = false
    private var widgetSocketView: KeyboardSocketView? = null
    private var widgetCoachView: TextView? = null
    private var widgetDotsView: LinearLayout? = null
    private var widgetProngsView: ConnectorProngsView? = null
    private var widgetLockPillView: TextView? = null
    private var widgetCoachAnimator: ValueAnimator? = null
    private var themeSplashView: View? = null
    private var themeSplashAnimator: ValueAnimator? = null
    private var widgetSwapDownX = 0f
    private var widgetSwapDownY = 0f
    private var widgetSwapHasDown = false
    private var glideClassifier: StatisticalGlideTypingClassifier? = null
    private var glideRecognizedColor: Int? = null   // app brand color when a glided word names an app
    @Volatile private var glideGestureActive = false   // true while a keyboard glide owns the touch
    private var numberPadOpen = false
    private var symbolsOpen = false
    private var lastMusicSourcePackage: String? = null
    private var lastMusicPlaying = false
    private lateinit var mediaSessionSource: MediaSessionSource
    private val mediaUiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var billingClient: com.android.billingclient.api.BillingClient? = null
    lateinit var spotifyAuth: SpotifyAuth
    lateinit var spotifyApi: SpotifyWebApi
    lateinit var gmailAuth: GmailAuth
    lateinit var gmailApi: GmailApi
    lateinit var travelRepo: TravelRepository
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
    private val appIconStateCache = android.util.LruCache<String, Drawable.ConstantState>(192)
    private var lastAppsLoadMs = 0L
    private var lastHubLoadMs = 0L
    private var lastCalendarLoadMs = 0L
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshAppsForPackageChange()
        }
    }

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
    private var weatherAmbientView: WeatherAmbientView? = null
    private var weatherDripView: WeatherDripView? = null
    private lateinit var weatherTempView: TextView
    private lateinit var weatherMetaView: TextView
    private lateinit var weatherFeelsView: TextView
    private lateinit var weatherStatsView: TextView
    private lateinit var hubView: LinearLayout
    private lateinit var ribbonView: LinearLayout
    private lateinit var favoritesDockView: LinearLayout
    private var parallaxEngine: ParallaxSensorEngine? = null
    private lateinit var homeGridView: FrameLayout
    private lateinit var rootView: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private lateinit var nowPlayingCardView: ComposeView
    private lateinit var keyboardDockView: FrameLayout
    private lateinit var searchHintView: TextView
    private var widgetSearchRendered = false
    private var widgetSearchContentArea: FrameLayout? = null
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
        } else if (key == ACTIVE_ICON_PACK_PREF || key?.startsWith(ICON_OVERRIDE_PREFIX) == true) {
            runOnUiThread {
                invalidateLibraryCaches()
                if (libraryOpen) scheduleLibraryRefresh()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contactsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            preloadContactsCache()
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
        widgetPersistenceRepository = WidgetPersistenceRepository(this)
        widgetSpecsCache = loadWidgetSpecsFromStore()
        keyboardSize = prefs().getInt(KEYBOARD_SIZE_PREF, 28)
        appIconSize = prefs().getInt(APP_ICON_SIZE_PREF, 0)
        keyboardTheme = prefs().getString(KEYBOARD_THEME_PREF, KEYBOARD_THEME_DEFAULT) ?: KEYBOARD_THEME_DEFAULT
        keyboardPlacement = prefs().getString(KEYBOARD_PLACEMENT_PREF, KEYBOARD_PLACEMENT_DOCKED) ?: KEYBOARD_PLACEMENT_DOCKED
        themeMode = prefs().getString(THEME_MODE_PREF, THEME_MODE_SYSTEM) ?: THEME_MODE_SYSTEM
        applyTheme()
        hapticsEnabled = prefs().getBoolean(HAPTICS_PREF, true)
        keyboardTiltLighting = prefs().getBoolean(KBD_TILT_LIGHT_PREF, true)
        libraryGridMode = prefs().getBoolean(LIBRARY_GRID_MODE_PREF, true)
        goKeyColor = prefs().getInt(GO_KEY_COLOR_PREF, Accent)
        migrateWidgetGestureDefault()
        apps = loadLaunchableApps()
        lastAppsLoadMs = System.currentTimeMillis()
        registerAppPackageReceiver()
        messages = loadHubMessages()
        calendarEvents = loadCalendarEvents()
        prefs().registerOnSharedPreferenceChangeListener(prefsListener)
        mediaSessionSource = MediaSessionSource(this)
        spotifyAuth = SpotifyAuth(this)
        spotifyApi = SpotifyWebApi(spotifyAuth)
        gmailAuth = GmailAuth(this)
        gmailApi = GmailApi(gmailAuth)
        travelRepo = TravelRepository(gmailApi)
        if (spotifyAuth.isConnected) preloadSpotifyLibrary()
        initSpellChecker()
        hapticEngine = CustomHapticEngine(this)
        spatialScorer = SpatialScorer()
        parallaxEngine = ParallaxSensorEngine(this) { pitch, roll -> applyDockParallax(pitch, roll) }
        spatialScorer.importState(prefs().getString(TOUCH_MODEL_PREF, "") ?: "")
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
        AgenticRouter.ensureLoaded(this)
        syncVivoDockedExperiment()
        render()
        handleKeyboardActionIntent(intent)
        rootView.post { maybeShowKeyboardPlacementIntro() }
        refreshWeather(force = false)
        maybeRequestSmsPermission()
        mediaSessionSource.start()
        mediaUiScope.launch {
            mediaSessionSource.nowPlaying.collect { info ->
                syncNowPlayingCardVisibility()
                refreshNowPlayingCard()
                // If the active source app changed while the music pane is open, rebuild the
                // dock so it can switch between the click wheel and simple transport controls.
                val src = info?.sourcePackage
                val sourceChanged = src != lastMusicSourcePackage
                val playChanged = (info?.isPlaying == true) != lastMusicPlaying
                lastMusicSourcePackage = src
                lastMusicPlaying = info?.isPlaying == true
                if (openPane?.kind == PaneKind.MUSIC) {
                    // Source change can flip wheel↔simple; play-state change updates the
                    // play/pause glyph on the simple / black transport docks.
                    val simpleDock = musicTheme() == MUSIC_THEME_BLACK || activeMusicMode() == MusicMode.SIMPLE
                    if (sourceChanged || (playChanged && simpleDock)) refreshKeyboardDock()
                }
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
        syncVivoDockedExperiment()
        if (handleKeyboardActionIntent(intent)) return
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
        } else if (gmailAuth.isCallback(uri)) {
            mediaUiScope.launch {
                val ok = gmailAuth.handleCallback(uri)
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "Gmail connected!" else "Gmail connection failed. Try again.",
                    Toast.LENGTH_SHORT
                ).show()
                if (openPane?.kind == PaneKind.SETTINGS) renderPaneContent(clicksSettingsTarget())
            }
        }
    }

    private fun handleKeyboardActionIntent(intent: Intent?): Boolean {
        when (intent?.action) {
            ClicksKeyboardActions.OPEN_KEYBOARD_SETTINGS -> {
                VivoDockedExperiment.clearViewportTruncation(this)
                stopService(Intent(this, DockedKeyboardService::class.java))
                openPane = null
                libraryOpen = false
                keyboardSettingsOpen = true
                query = ""
                render()
                return true
            }
            ClicksKeyboardActions.SWITCH_TO_WIDGET_MODE -> {
                VivoDockedExperiment.clearViewportTruncation(this)
                stopService(Intent(this, DockedKeyboardService::class.java))
                openPane = null
                libraryOpen = false
                keyboardSettingsOpen = false
                query = ""
                setKeyboardPlacement(KEYBOARD_PLACEMENT_WIDGET)
                return true
            }
        }
        return false
    }

    override fun onResume() {
        super.onResume()
        syncDockParallax()
        stopService(Intent(this, DockedKeyboardService::class.java))
        syncVivoDockedExperiment()
        updateLauncherTheme(animated = true)
        ensureBillingConnected()
        val now = System.currentTimeMillis()
        if (now - lastHubLoadMs > 10_000) { messages = loadHubMessages(); lastHubLoadMs = now }
        if (now - lastCalendarLoadMs > 10_000) { calendarEvents = loadCalendarEvents(); lastCalendarLoadMs = now }
        if (now - lastContactsLoadMs > 30_000) { preloadContactsCache(); lastContactsLoadMs = now }
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

    private fun syncVivoDockedExperiment() {
        android.util.Log.i("VivoDockedExperiment", "sync placement=$keyboardPlacement enabled=${VivoDockedExperiment.isEnabled(this)}")
        if (keyboardPlacement == KEYBOARD_PLACEMENT_DOCKED && VivoDockedExperiment.isEnabled(this)) {
            VivoDockedExperiment.applyViewportTruncation(this)
        } else {
            VivoDockedExperiment.clearViewportTruncation(this)
        }
    }

    override fun onPause() {
        cancelWidgetKeyboardSwap(resetTheme = true)
        parallaxEngine?.stop()
        if (::spatialScorer.isInitialized) {
            prefs().edit().putString(TOUCH_MODEL_PREF, spatialScorer.exportState()).apply()
        }
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLauncherTheme(animated = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs().unregisterOnSharedPreferenceChangeListener(prefsListener)
        runCatching { unregisterReceiver(packageChangeReceiver) }
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
            } else if (pane?.kind == PaneKind.AI) {
                if (aiDraftText.isNotBlank() && !aiDraftText.endsWith(' ')) aiDraftText += " "
                aiDraftText += spoken
                aiDraftActive = true
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
        if (travelOverlay != null) { dismissTravelOverlay(); return }
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
        if (travelOverlay != null) dismissTravelOverlay()
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
        if (widgetKeyboardSwapActive()) return super.dispatchTouchEvent(event)
        // While a glide/swipe-type gesture is in progress the keyboard owns the touch — suppress
        // launcher gestures (library, widget board, home swipes) so they don't fire mid-swipe.
        if (glideGestureActive) return super.dispatchTouchEvent(event)
        if (handlePaneSwipe(event)) return true
        val wasLibraryDragging = libraryDragActive
        if (handleLibrarySwipe(event)) {
            if (!wasLibraryDragging && libraryDragActive) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
            return true
        }
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

    // Start/stop the dock parallax sensor to match visibility — runs only on the home surface
    // (dock present, no pane/library over it), and stops otherwise to keep it cheap.
    private fun syncDockParallax() {
        val engine = parallaxEngine ?: return
        if (engine.isSupported && ::favoritesDockView.isInitialized && homeWidgetStackVisible() && !libraryOpen) {
            engine.start()
        } else {
            engine.stop()
            resetDockParallax()
        }
    }

    private fun resetDockParallax() {
        if (!::favoritesDockView.isInitialized) return
        val dock = favoritesDockView
        dock.translationX = 0f; dock.translationY = 0f; dock.rotationX = 0f; dock.rotationY = 0f
        for (i in 0 until dock.childCount) {
            dock.getChildAt(i).apply { translationX = 0f; translationY = 0f; rotationX = 0f; rotationY = 0f }
        }
    }

    // Favorites-dock-only tilt parallax: the dock plate drifts subtly one way while the icons move
    // the other with a slight 3D skew, for a floating, layered feel. Deliberately small.
    private fun applyDockParallax(pitch: Float, roll: Float) {
        if (!::favoritesDockView.isInitialized) return
        val dock = favoritesDockView
        if (dock.childCount == 0 || !dock.isShown) return
        val tiltX = (roll / 0.42f).coerceIn(-1f, 1f)
        val tiltY = (pitch / 0.42f).coerceIn(-1f, 1f)
        val density = resources.displayMetrics.density
        // Tilt the whole dock like one physical plate: perspective rotation about its center. A
        // close camera makes these small angles read as genuine 3D depth rather than a flat skew.
        dock.cameraDistance = density * 3200f
        dock.rotationY = tiltX * 4f
        dock.rotationX = -tiltY * 4f
        // Icons float a hair in front of the plate for layered parallax; the plate itself does the 3D.
        val maxT = density * 2.5f
        for (i in 0 until dock.childCount) {
            dock.getChildAt(i).apply {
                translationX = tiltX * maxT
                translationY = tiltY * maxT
                rotationX = 0f
                rotationY = 0f
            }
        }
    }

    private fun render() {
        stopDeleteRepeat(clearFired = true)
        keyViews.clear()
        keyBounds.clear()
        applyTheme()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(activeNeuTokens.base)
            setPadding(0, systemStatusBarHeight(), 0, keyboardBottomLift())
        }
        rootView = root
        contentFrame = FrameLayout(this).apply {
            addView(home(), FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            post { applyLibraryEdgeGestureExclusion() }
        }
        root.addView(contentFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val hideDockForPane = openPane?.kind == PaneKind.SETTINGS
        val showRootDock = keyboardPlacement == KEYBOARD_PLACEMENT_DOCKED || widgetPaneUsesRootDock()
        if (showRootDock && !hideDockForPane) {
            keyboardDockView = FrameLayout(this).apply {
                addView(dockedInputView(), FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            root.addView(keyboardDockView, LinearLayout.LayoutParams.MATCH_PARENT, activeRootDockHeight())
        } else {
            keyboardDockView = FrameLayout(this)
        }
        setContentView(root)
        updateClock()
        renderHub()
        renderFavoritesDock()
        syncDockParallax()
        renderRibbon()
        openPane?.let { showPane(it, animate = false) }
        if (libraryOpen) showLibrary(animate = false)
        if (!libraryOpen && openPane == null) contentFrame.postDelayed({ prewarmLibraryView() }, 140L)
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
                setTextColor(activeNeuTokens.ink)
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.START
                setPadding(dp(16), dp(3), dp(8), dp(3))
                isClickable = true
                setOnTouchListener { _, event -> handleTypingStripGesture(event) }
            }
            addView(searchHintView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
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
        return Neu.drawable(activeNeuTokens, dp(11).toFloat(), NeuLevel.PRESSED_SM)
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
        widgetSearchContentArea = null
        val widgetSearchActive = isWidgetUniversalSearchActive()
        widgetSearchRendered = widgetSearchActive
            homeEditMode = false
            homeEditChipView = null
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                clipChildren = false
                clipToPadding = false
            setPadding(dp(14), dp(6), dp(14), 0)
            if (widgetSearchActive) {
                val searchArea = FrameLayout(context).apply {
                    clipChildren = true
                    clipToPadding = true
                    setPadding(0, dp(4), 0, dp(8))
                }
                widgetSearchContentArea = searchArea
                refreshWidgetSearchContent()
                addView(searchArea, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                favoritesDockView = LinearLayout(context)
            } else {
                addView(homeHeader(), LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.08f))
                nowPlayingCardView = ComposeView(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    setNowPlayingCardContent()
                    elevation = dp(8).toFloat()
                }
                addView(nowPlayingCardView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, nowPlayingCardHeight()).apply {
                    topMargin = dp(2)
                    bottomMargin = dp(2)
                })
                addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, if (keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET) 0.14f else 0.22f))
                favoritesDockView = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    clipChildren = false
                    clipToPadding = false
                    setPadding(dp(14), dp(9), dp(14), dp(9))
                    background = recessedDockBackground()
                }
                addView(favoritesDockView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(62)).apply {
                    topMargin = dp(6)
                    bottomMargin = if (keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET) dp(4) else 0
                })
            }
            if (keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET && !widgetPaneUsesRootDock()) {
                addView(homeKeyboardWidget(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, widgetKeyboardHeight()).apply {
                    leftMargin = -widgetKeyboardHorizontalBleed()
                    rightMargin = -widgetKeyboardHorizontalBleed()
                    bottomMargin = dp(0)
                })
            }
        }
        return FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            weatherAmbientView = WeatherAmbientView(context).apply {
                setWeather(prefs().getInt(WEATHER_CODE_PREF, 0), activeNeuTokens.mode, animate = animatedWeatherEnabled())
            }
            addView(weatherAmbientView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            weatherDripView = WeatherDripView(context)
            addView(weatherDripView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            weatherDripView?.refresh()
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
        widgetCommittedTheme = keyboardTheme
        widgetPreviewTheme = keyboardTheme
        return FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            widgetKeyboardHost = this

            val socket = KeyboardSocketView(context).apply {
                alpha = 0f
                visibility = View.GONE
            }
            widgetSocketView = socket
            addView(socket, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

            val module = FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                background = homeKeyboardWidgetBackground()
                setOnTouchListener { _, event -> handleWidgetKeyboardDetachedTouch(event) }
            }
            widgetKeyboardModule = module
            populateWidgetKeyboardModule(module)
            addView(module, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

            widgetCoachView = TextView(context).apply {
                text = "⇆  Swipe to browse · tap to install"
                gravity = Gravity.CENTER
                includeFontPadding = false
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(0xFFEAF7EF.toInt())
                setPadding(dp(12), 0, dp(12), 0)
                background = swapPillBackground(0xCC111A16.toInt(), 0x6638D67A)
                alpha = 0f
                visibility = View.GONE
            }
            addView(widgetCoachView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(2)
            })

            widgetDotsView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                alpha = 0f
                visibility = View.GONE
            }
            addView(widgetDotsView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(4)
            })
            updateWidgetThemeDots()
        }
    }

    private fun populateWidgetKeyboardModule(module: FrameLayout) {
        module.removeAllViews()
        module.background = homeKeyboardWidgetBackground()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(2), dp(4), dp(2), dp(0))
            addView(keyboard(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        module.addView(content, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        val prongs = ConnectorProngsView(this).apply {
            alpha = if (widgetSwapState == WidgetKeyboardSwapState.SEATED) 0f else 1f
            visibility = if (widgetSwapState == WidgetKeyboardSwapState.SEATED) View.GONE else View.VISIBLE
        }
            widgetProngsView = prongs
            module.addView(prongs, FrameLayout.LayoutParams(dp(132), dp(18), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = -dp(3)
            })
            module.setOnTouchListener { _, event -> handleWidgetKeyboardDetachedTouch(event) }
            if (pendingWidgetKeyboardPopIn) {
                pendingWidgetKeyboardPopIn = false
                module.alpha = 0f
                module.translationY = dp(42).toFloat()
                module.scaleX = 0.965f
                module.scaleY = 0.94f
                module.post {
                    module.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(280L)
                        .setInterpolator(DecelerateInterpolator(1.55f))
                        .start()
                }
            }
    }

    private val widgetSwapThemes: List<String>
        get() = listOf(
            KEYBOARD_THEME_DEFAULT,
            KEYBOARD_THEME_CLICKS,
            KEYBOARD_THEME_SKEUO,
            KEYBOARD_THEME_GOKEYS,
            KEYBOARD_THEME_HYPER3D,
            KEYBOARD_THEME_HYPER3D_BLACK,
            KEYBOARD_THEME_HYPER3D_LIGHT
        )

    private fun widgetThemeName(theme: String): String = when (theme) {
        KEYBOARD_THEME_CLICKS -> "CLICKS"
        KEYBOARD_THEME_SKEUO -> "SKEUO"
        KEYBOARD_THEME_GOKEYS -> "GOKEYS"
        KEYBOARD_THEME_HYPER3D -> "HYPER3D"
        KEYBOARD_THEME_HYPER3D_BLACK -> "HYPER BLACK"
        KEYBOARD_THEME_HYPER3D_LIGHT -> "HYPER LIGHT"
        else -> "DEFAULT"
    }

    private fun beginWidgetKeyboardDetach(dockKey: TextView) {
        if (keyboardPlacement != KEYBOARD_PLACEMENT_WIDGET || widgetSwapState != WidgetKeyboardSwapState.SEATED) return
        val module = widgetKeyboardModule ?: return
        widgetSwapState = WidgetKeyboardSwapState.DETACHING
        widgetCommittedTheme = keyboardTheme
        widgetPreviewTheme = keyboardTheme
        widgetSwapHasDown = false
        dockDetachHaptic(dockKey)
        showWidgetSwapChrome()
        module.animate().cancel()
        module.elevation = dp(18).toFloat()
        module.cameraDistance = 12000f
        module.animate()
            .translationY(-dp(118).toFloat())
            .translationX(0f)
            .rotation(0f)
            .rotationX(9f)
            .scaleX(0.94f)
            .scaleY(0.94f)
            .setDuration(460L)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
            .withEndAction {
                if (widgetSwapState != WidgetKeyboardSwapState.DETACHING) return@withEndAction
                widgetSwapState = WidgetKeyboardSwapState.DETACHED
                module.animate().translationY(-dp(112).toFloat()).setDuration(95L).withEndAction {
                    if (widgetSwapState == WidgetKeyboardSwapState.DETACHED) snapWidgetHoverPose()
                }.start()
            }
            .start()
    }

    private fun showWidgetSwapChrome() {
        widgetSocketView?.apply {
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(180L).setInterpolator(DecelerateInterpolator()).start()
        }
        widgetProngsView?.apply {
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(160L).start()
        }
        widgetCoachView?.apply {
            visibility = View.VISIBLE
            alpha = 0f
            translationX = -dp(8).toFloat()
            animate().alpha(1f).translationX(0f).setDuration(180L).setInterpolator(DecelerateInterpolator()).start()
        }
        startWidgetCoachNudge()
        widgetDotsView?.apply {
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(180L).start()
        }
        updateWidgetThemeDots()
    }

    private fun hideWidgetSwapChrome() {
        widgetCoachAnimator?.cancel()
        widgetCoachAnimator = null
        listOf(widgetCoachView, widgetDotsView, widgetProngsView, widgetSocketView).forEach { view ->
            view?.animate()?.alpha(0f)?.setDuration(160L)?.withEndAction {
                view?.visibility = View.GONE
            }?.start()
        }
    }

    private fun startWidgetCoachNudge() {
        widgetCoachAnimator?.cancel()
        val coach = widgetCoachView ?: return
        widgetCoachAnimator = ValueAnimator.ofFloat(-1f, 1f).apply {
            duration = 900L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                coach.translationX = (it.animatedValue as Float) * dp(5)
            }
            start()
        }
    }

    private fun dismissWidgetCoach() {
        widgetCoachAnimator?.cancel()
        widgetCoachAnimator = null
        widgetCoachView?.animate()?.alpha(0f)?.setDuration(140L)?.withEndAction {
            widgetCoachView?.visibility = View.GONE
        }?.start()
    }

    private fun updateWidgetThemeDots() {
        val dots = widgetDotsView ?: return
        dots.removeAllViews()
        widgetSwapThemes.forEach { theme ->
            val selected = theme == widgetPreviewTheme
            val dot = TextView(this).apply {
                text = ""
                isClickable = true
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(5).toFloat()
                    setColor(if (selected) 0xFF7AF0A0.toInt() else 0x55727782)
                }
                setOnClickListener {
                    if (widgetSwapState == WidgetKeyboardSwapState.DETACHED) previewWidgetTheme(theme, fromLeft = null)
                }
            }
            dots.addView(dot, LinearLayout.LayoutParams(if (selected) dp(22) else dp(9), dp(9)).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            })
        }
    }

    private fun previewWidgetTheme(theme: String, fromLeft: Boolean?) {
        if (widgetSwapState != WidgetKeyboardSwapState.DETACHED) return
        if (theme == widgetPreviewTheme) {
            updateWidgetThemeDots()
            snapWidgetHoverPose()
            return
        }
        val module = widgetKeyboardModule ?: return
        widgetPreviewTheme = theme
        keyboardTheme = theme
        haptic(module)
        updateWidgetThemeDots()
        val outX = if (fromLeft == true) dp(54).toFloat() else -dp(54).toFloat()
        val inX = -outX
        module.animate().cancel()
        module.animate().alpha(0.18f).translationX(outX).rotation(outX * 0.03f).setDuration(120L).withEndAction {
            populateWidgetKeyboardModule(module)
            module.alpha = 0.18f
            module.translationX = inX
            module.rotation = inX * 0.03f
            module.translationY = -dp(118).toFloat()
            module.rotationX = 9f
            module.scaleX = 0.94f
            module.scaleY = 0.94f
            module.animate()
                .alpha(1f)
                .translationX(0f)
                .rotation(0f)
                .setDuration(300L)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                .withEndAction { snapWidgetHoverPose() }
                .start()
        }.start()
    }

    private fun handleWidgetKeyboardDetachedTouch(event: MotionEvent): Boolean {
        if (keyboardPlacement != KEYBOARD_PLACEMENT_WIDGET) return false
        if (widgetSwapState == WidgetKeyboardSwapState.DETACHING || widgetSwapState == WidgetKeyboardSwapState.SEATING) return true
        if (widgetSwapState != WidgetKeyboardSwapState.DETACHED) return false
        val module = widgetKeyboardModule ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                widgetSwapDownX = event.rawX
                widgetSwapDownY = event.rawY
                widgetSwapHasDown = true
                module.animate().cancel()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!widgetSwapHasDown) return true
                val dx = event.rawX - widgetSwapDownX
                val dy = event.rawY - widgetSwapDownY
                if (Math.abs(dx) > dp(8) && Math.abs(dx) > Math.abs(dy)) dismissWidgetCoach()
                module.translationX = dx * 0.5f
                module.rotation = dx * 0.03f
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!widgetSwapHasDown) {
                    snapWidgetHoverPose()
                    return true
                }
                widgetSwapHasDown = false
                val dx = event.rawX - widgetSwapDownX
                val dy = event.rawY - widgetSwapDownY
                val absDx = Math.abs(dx)
                val absDy = Math.abs(dy)
                return when {
                    absDx > dp(42) && absDx > absDy -> {
                        val current = widgetSwapThemes.indexOf(widgetPreviewTheme).coerceAtLeast(0)
                        val next = if (dx < 0f) (current + 1) % widgetSwapThemes.size else (current - 1 + widgetSwapThemes.size) % widgetSwapThemes.size
                        dismissWidgetCoach()
                        previewWidgetTheme(widgetSwapThemes[next], fromLeft = dx > 0f)
                        true
                    }
                    absDx < dp(8) && absDy < dp(8) -> {
                        seatWidgetKeyboard()
                        true
                    }
                    else -> {
                        snapWidgetHoverPose()
                        true
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                widgetSwapHasDown = false
                snapWidgetHoverPose()
                return true
            }
        }
        return true
    }

    private fun snapWidgetHoverPose() {
        val module = widgetKeyboardModule ?: return
        if (widgetSwapState != WidgetKeyboardSwapState.DETACHED) return
        module.animate()
            .translationY(-dp(118).toFloat())
            .translationX(0f)
            .rotation(0f)
            .rotationX(9f)
            .scaleX(0.94f)
            .scaleY(0.94f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun seatWidgetKeyboard() {
        if (widgetSwapState != WidgetKeyboardSwapState.DETACHED) return
        val module = widgetKeyboardModule ?: return
        widgetSwapState = WidgetKeyboardSwapState.SEATING
        widgetSwapHasDown = false
        dismissWidgetCoach()
        widgetDotsView?.animate()?.alpha(0f)?.setDuration(140L)?.withEndAction { widgetDotsView?.visibility = View.GONE }?.start()
        module.animate().cancel()
        module.animate()
            .translationY(dp(14).toFloat())
            .translationX(0f)
            .rotation(0f)
            .rotationX(0f)
            .scaleX(1.018f)
            .scaleY(0.992f)
            .setDuration(185L)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.65f))
            .withEndAction {
                haptic(module)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    module.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
                widgetProngsView?.pulse()
                widgetSocketView?.pulseGlow()
                module.animate()
                    .translationY(-dp(3).toFloat())
                    .scaleX(0.996f)
                    .scaleY(1.006f)
                    .setDuration(92L)
                    .setInterpolator(DecelerateInterpolator(2.4f))
                    .withEndAction {
                        module.animate()
                            .translationY(0f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(118L)
                            .setInterpolator(android.view.animation.OvershootInterpolator(0.55f))
                            .withEndAction {
                                keyboardTheme = widgetPreviewTheme
                                prefs().edit().putString(KEYBOARD_THEME_PREF, keyboardTheme).apply()
                                widgetCommittedTheme = keyboardTheme
                                module.elevation = 0f
                                showWidgetLockedPill()
                                widgetSwapState = WidgetKeyboardSwapState.SEATED
                                handler.postDelayed({
                                    hideWidgetSwapChrome()
                                    widgetKeyboardModule?.let { populateWidgetKeyboardModule(it) }
                                }, 680L)
                            }
                            .start()
                    }
                    .start()
            }
            .start()
    }

    private fun showWidgetLockedPill() {
        val host = widgetKeyboardHost ?: return
        widgetLockPillView?.let { host.removeView(it) }
        val pill = TextView(this).apply {
            text = "LOCKED"
            gravity = Gravity.CENTER
            includeFontPadding = false
            textSize = 9.5f
            letterSpacing = 0.22f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(0xCCB7F8C1.toInt())
            background = swapPillBackground(0xDD07090B.toInt(), 0x559AF5AE)
            alpha = 0f
            translationY = dp(7).toFloat()
            scaleX = 0.94f
            scaleY = 0.94f
        }
        widgetLockPillView = pill
        host.addView(pill, FrameLayout.LayoutParams(dp(92), dp(24), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
            bottomMargin = dp(11)
        })
        pill.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(115L).setInterpolator(DecelerateInterpolator(2.2f)).withEndAction {
            pill.animate().alpha(0f).translationY(dp(5).toFloat()).setStartDelay(430L).setDuration(180L).withEndAction {
                widgetLockPillView = null
                host.removeView(pill)
            }.start()
        }.start()
    }

    private fun cancelWidgetKeyboardSwap(resetTheme: Boolean) {
        if (widgetSwapState == WidgetKeyboardSwapState.SEATED) return
        widgetCoachAnimator?.cancel()
        widgetCoachAnimator = null
        widgetKeyboardModule?.animate()?.cancel()
        if (resetTheme) keyboardTheme = widgetCommittedTheme
        widgetSwapState = WidgetKeyboardSwapState.SEATED
        widgetSwapHasDown = false
        widgetKeyboardModule?.apply {
            alpha = 1f
            translationX = 0f
            translationY = 0f
            rotation = 0f
            rotationX = 0f
            scaleX = 1f
            scaleY = 1f
            elevation = 0f
        }
        widgetCoachView?.visibility = View.GONE
        widgetDotsView?.visibility = View.GONE
        widgetProngsView?.visibility = View.GONE
        widgetSocketView?.visibility = View.GONE
        widgetKeyboardModule?.let { populateWidgetKeyboardModule(it) }
    }

    private fun swapPillBackground(fill: Int, stroke: Int): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
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
            setPadding(dp(28), dp(3), dp(10), dp(1))
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
                    textSize = 34f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    setTextColor(activeNeuTokens.ink)
                    includeFontPadding = false
                }
                addView(weatherTempView)
                weatherMetaView = TextView(context).apply {
                    text = prefs().getString(WEATHER_META_PREF, "Tap for local weather")
                    textSize = 9.4f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    letterSpacing = 0.10f
                    setTextColor(activeNeuTokens.inkDim)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                addView(weatherMetaView)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.18f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
                weatherFeelsView = TextView(context).apply {
                    text = prefs().getString(WEATHER_FEELS_PREF, "Feels --")
                    textSize = 11.4f
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    setTextColor(activeNeuTokens.ink)
                    includeFontPadding = false
                    gravity = Gravity.RIGHT
                }
                addView(weatherFeelsView)
                weatherStatsView = TextView(context).apply {
                    text = prefs().getString(WEATHER_STATS_PREF, "Local")
                    textSize = 9.8f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    letterSpacing = 0.05f
                    setTextColor(activeNeuTokens.inkDim)
                    includeFontPadding = false
                    gravity = Gravity.RIGHT
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                addView(weatherStatsView)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.50f).apply { marginEnd = dp(2) })
            weatherIconView = AnimatedWeatherIconView(context).apply {
                setWeatherCode(prefs().getInt(WEATHER_CODE_PREF, 0))
                setAnimationEnabled(animatedWeatherEnabled())
            }
            addView(weatherIconView, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
    }

    private fun ComposeView.setNowPlayingCardContent() {
        setContent {
            val media by mediaSessionSource.nowPlaying.collectAsState()
            val current = media
            HomeWidgetStack(
                tokens = activeNeuTokens,
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
                },
                ambientLightColor = weatherAmbientLightColor(),
                ambientLightStrength = weatherAmbientLightStrength()
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

    private fun homeWidgetStackVisible() = openPane == null

    private fun widgetKeyboardSwapActive(): Boolean {
        return keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET && widgetSwapState != WidgetKeyboardSwapState.SEATED
    }

    private var librarySwipeFromKeyboard = false

    private fun applyLibraryEdgeGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !::contentFrame.isInitialized) return
        val width = contentFrame.width
        val height = contentFrame.height
        if (width <= 0 || height <= 0) return
        val edgeWidth = dp(64).coerceAtLeast((width * 0.08f).toInt())
        contentFrame.systemGestureExclusionRects = listOf(
            Rect((width - edgeWidth).coerceAtLeast(0), 0, width, height)
        )
    }

    private fun isLibraryRightEdgeStart(rawX: Float, rawY: Float, contentLeft: Int, contentTop: Int): Boolean {
        if (!::contentFrame.isInitialized || contentFrame.width <= 0 || contentFrame.height <= 0) return false
        if (rawY < contentTop || rawY > contentTop + contentFrame.height) return false
        val localX = rawX - contentLeft
        val edgeWidth = dp(64).toFloat().coerceAtLeast(contentFrame.width * 0.08f)
        return localX >= contentFrame.width - edgeWidth
    }

    private fun handleLibrarySwipe(event: MotionEvent): Boolean {
        if (widgetKeyboardSwapActive()) {
            librarySwipeTriggered = false
            librarySwipeBlockedByWidget = false
            return false
        }
        if (!::contentFrame.isInitialized) return false
        if (widgetBoardView != null) return false
        if (homeEditMode) {
            librarySwipeTriggered = false
            librarySwipeBlockedByWidget = false
            return false
        }
        val loc = IntArray(2)
        contentFrame.getLocationOnScreen(loc)
        val inContent = event.rawY >= loc[1] && event.rawY <= loc[1] + contentFrame.height
        val upOpensLibrary = gestureAction(GESTURE_UP_PREF, GESTURE_WIDGETS) == GESTURE_LIBRARY
        val sideLibraryEnabled = !upOpensLibrary
        val rightEdgeStart = sideLibraryEnabled &&
            isLibraryRightEdgeStart(event.rawX, event.rawY, loc[0], loc[1])

        // A gesture that starts on the keyboard (docked dock or widget keyboard module) is typing /
        // glide, not a launcher swipe — ignore it so widget-mode glides don't open the library or
        // widget board.
        if (event.actionMasked != MotionEvent.ACTION_DOWN && librarySwipeFromKeyboard) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                librarySwipeStartX = event.rawX
                librarySwipeStartY = event.rawY
                librarySwipeTriggered = false
                libraryDragActive = false
                libraryDragVertical = false
                libraryDragHapticStage = 0
                librarySwipeBlockedByWidget = isInsideHomeWidget(event.rawX, event.rawY)
                librarySwipeFromKeyboard = isInsideKeyboard(event.rawX, event.rawY)
                if (rightEdgeStart && !librarySwipeFromKeyboard && !libraryOpen && openPane == null) {
                    librarySwipeTriggered = true
                    librarySwipeBlockedByWidget = false
                    beginLibraryDrag(startedOpen = false, fastPath = true)
                    updateLibraryDrag(0f)
                    contentFrame.parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!inContent) return false
                val dx = event.rawX - librarySwipeStartX
                val dy = event.rawY - librarySwipeStartY
                libraryDragTouchX = event.rawX
                if (librarySwipeBlockedByWidget) {
                    val wantsSideLibraryDrag = sideLibraryEnabled && abs(dx) > dp(18) && abs(dx) > abs(dy) * 1.2f &&
                        ((dx < 0 && !libraryOpen && openPane == null) || (dx > 0 && libraryOpen))
                    if (wantsSideLibraryDrag) {
                        librarySwipeBlockedByWidget = false
                    } else {
                        return false
                    }
                }
                if (libraryDragActive) {
                    updateLibraryDrag(if (libraryDragVertical) dy else dx)
                    return true
                }
                if (upOpensLibrary && !librarySwipeTriggered && abs(dy) > dp(18) && abs(dy) > abs(dx) * 1.2f) {
                    when {
                        dy < 0 && !libraryOpen && openPane == null -> {
                            librarySwipeTriggered = true
                            beginLibraryDrag(startedOpen = false, vertical = true)
                            updateLibraryDrag(dy)
                            return true
                        }
                        dy > 0 && libraryOpen -> {
                            librarySwipeTriggered = true
                            beginLibraryDrag(startedOpen = true, vertical = true)
                            updateLibraryDrag(dy)
                            return true
                        }
                    }
                }
                // Start horizontal library motion as soon as the swipe is intentional, not on finger-up.
                if (sideLibraryEnabled && !librarySwipeTriggered && abs(dx) > dp(18) && abs(dx) > abs(dy) * 1.2f) {
                    when {
                        dx < 0 && !libraryOpen && openPane == null -> {
                            librarySwipeTriggered = true
                            beginLibraryDrag(startedOpen = false)
                            updateLibraryDrag(dx)
                            return true
                        }
                        dx > 0 && libraryOpen -> {
                            librarySwipeTriggered = true
                            beginLibraryDrag(startedOpen = true)
                            updateLibraryDrag(dx)
                            return true
                        }
                        dx < 0 && libraryOpen -> {
                            librarySwipeTriggered = true
                            closeLibrary(slideLeft = true)
                            return true
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (libraryDragActive) {
                    val delta = if (libraryDragVertical) event.rawY - librarySwipeStartY else event.rawX - librarySwipeStartX
                    settleLibraryDrag(delta)
                    return true
                }
                if (librarySwipeBlockedByWidget) {
                    librarySwipeBlockedByWidget = false
                    return false
                }
                if (!inContent || librarySwipeTriggered) return librarySwipeTriggered
                val dx = event.rawX - librarySwipeStartX
                val dy = event.rawY - librarySwipeStartY
                // Deliberate horizontal threshold keeps vertical library scrolls and keyboard swipes separate.
                if (sideLibraryEnabled && abs(dx) > dp(24) && abs(dx) > abs(dy) * 1.2f) {
                    when {
                        dx < 0 && !libraryOpen && openPane == null -> {
                            librarySwipeTriggered = true
                            openLibrary()
                            return true
                        }
                        dx > 0 && libraryOpen -> {
                            librarySwipeTriggered = true
                            closeLibrary()
                            return true
                        }
                        dx < 0 && libraryOpen -> {
                            librarySwipeTriggered = true
                            closeLibrary(slideLeft = true)
                            return true
                        }
                        dx > 0 && openPane == null -> {
                            librarySwipeTriggered = true
                            performHomeGesture(gestureAction(GESTURE_RIGHT_PREF, GESTURE_NONE))
                            return true
                        }
                    }
                } else if (!libraryOpen && openPane == null && abs(dy) > dp(42) && abs(dy) > abs(dx) * 1.2f) {
                    librarySwipeTriggered = true
                    if (dy < 0 && upOpensLibrary) {
                        openLibrary()
                    } else if (dy < 0) {
                        performHomeGesture(gestureAction(GESTURE_UP_PREF, GESTURE_WIDGETS))
                    } else {
                        performHomeGesture(gestureAction(GESTURE_DOWN_PREF, GESTURE_NOTIFICATIONS))
                    }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (libraryDragActive) settleLibraryDrag(if (libraryDragStartedOpen) 0f else libraryDragAxisSize())
                librarySwipeTriggered = false
                librarySwipeBlockedByWidget = false
                libraryDragActive = false
                libraryDragVertical = false
                return true
            }
        }
        return false
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
                if (paneKind == PaneKind.MUSIC && dx > dp(60) && dx > abs(dy) * 1.4f && spotifyCompactOverlay == null && activeMusicMode() != MusicMode.SIMPLE) {
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

    // True if the touch is over EITHER keyboard surface — the docked dock or the in-home widget
    // keyboard module. In widget mode the keyboard lives inside contentFrame, so launcher-swipe
    // handlers must exclude it explicitly (the docked dock is already below contentFrame).
    private fun isInsideKeyboard(rawX: Float, rawY: Float): Boolean {
        if (isInsideKeyboardDock(rawX, rawY)) return true
        val m = widgetKeyboardModule ?: return false
        if (m.height <= 0 || m.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        m.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX <= loc[0] + m.width && rawY >= loc[1] && rawY <= loc[1] + m.height
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
        if (widgetKeyboardSwapActive()) return false
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
                    val upOpensLibrary = gestureAction(GESTURE_UP_PREF, GESTURE_WIDGETS) == GESTURE_LIBRARY
                    when {
                        abs(dy) > abs(dx) * 1.15f && dy < 0 -> performHomeGesture(gestureAction(GESTURE_UP_PREF, GESTURE_WIDGETS))
                        abs(dy) > abs(dx) * 1.15f && dy > 0 -> performHomeGesture(gestureAction(GESTURE_DOWN_PREF, GESTURE_NOTIFICATIONS))
                        abs(dx) > abs(dy) * 1.15f && dx > 0 -> performHomeGesture(gestureAction(GESTURE_RIGHT_PREF, GESTURE_NONE))
                        !upOpensLibrary && abs(dx) > abs(dy) * 1.15f && dx < 0 && openPane == null && widgetBoardView == null -> openLibrary()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!stripSwipeTriggered) {
                    haptic(searchHintView)
                    if (openPane != null) {
                        keyboardSettingsOpen = false
                        refreshKeyboardDock()
                        stripSwipeTriggered = false
                        return true
                    }
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

    private fun beginLibraryDrag(startedOpen: Boolean, vertical: Boolean = false, fastPath: Boolean = false) {
        if (openPane != null && !startedOpen) return
        libraryDragActive = true
        libraryDragStartedOpen = startedOpen
        libraryDragVertical = vertical
        libraryDragHapticStage = 0
        libraryDragTouchX = librarySwipeStartX
        if (!startedOpen) {
            query = ""
            keyboardSettingsOpen = false
            libraryView?.let { contentFrame.removeView(it) }
            categoryFolderView = null
            val overlay = appLibrary()
            libraryView = overlay
            overlay.translationX = if (vertical) 0f else libraryDragAxisSize()
            overlay.translationY = if (vertical) libraryDragAxisSize() else 0f
            overlay.alpha = if (activeNeuTokens.mode == NeuMode.LIGHT) 0.92f else 0.88f
            overlay.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            contentFrame.addView(overlay, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scheduleLibraryPopulate(if (fastPath) 0L else 24L)
            updateLibraryGridDragEffects(0f, libraryDragTouchX)
            libraryDragHaptic(1)
        } else {
            libraryView?.apply {
                animate().cancel()
                if (vertical) translationX = 0f else translationY = 0f
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
            libraryDragHaptic(1)
        }
    }

    private fun updateLibraryDrag(delta: Float) {
        val overlay = libraryView ?: return
        val size = libraryDragAxisSize()
        val translation = if (libraryDragStartedOpen) {
            delta.coerceIn(0f, size)
        } else {
            (size + delta).coerceIn(0f, size)
        }
        if (libraryDragVertical) {
            overlay.translationY = translation
            overlay.translationX = 0f
        } else {
            overlay.translationX = translation
            overlay.translationY = 0f
        }
        val progress = 1f - (translation / size)
        if (query.isNotBlank()) {
            overlay.alpha = 1f
        } else if (!libraryDragStartedOpen) {
            overlay.alpha = ((if (activeNeuTokens.mode == NeuMode.LIGHT) 0.90f else 0.86f) + progress * 0.08f).coerceAtMost(0.98f)
        }
        updateLibraryGridDragEffects(progress, libraryDragTouchX)
        val stage = when {
            progress > 0.78f -> 3
            progress > 0.42f -> 2
            progress > 0.12f -> 1
            else -> 0
        }
        if (stage > libraryDragHapticStage) {
            libraryDragHapticStage = stage
            libraryDragHaptic(stage)
        }
    }

    private fun settleLibraryDrag(delta: Float) {
        val overlay = libraryView ?: run {
            libraryDragActive = false
            return
        }
        val size = libraryDragAxisSize()
        val translation = (if (libraryDragVertical) overlay.translationY else overlay.translationX).coerceIn(0f, size)
        val openProgress = 1f - (translation / size)
        val shouldOpen = if (libraryDragStartedOpen) {
            openProgress > 0.72f || delta < -dp(56)
        } else {
            openProgress > 0.22f || delta < -dp(88)
        }
        val vertical = libraryDragVertical
        libraryDragActive = false
        librarySwipeTriggered = false
        librarySwipeBlockedByWidget = false
        libraryDragVertical = false
        overlay.animate().cancel()
        overlay.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        if (shouldOpen) {
            libraryOpen = true
            val animation = overlay.animate()
                .alpha(1f)
                .setDuration(librarySettleDuration(translation, if (vertical) contentFrame.height else contentFrame.width))
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    overlay.translationX = 0f
                    overlay.translationY = 0f
                    overlay.setLayerType(View.LAYER_TYPE_NONE, null)
                    updateLibraryGridDragEffects(1f, libraryDragTouchX)
                    refreshLibraryContent()
                    renderRibbon()
                    syncNowPlayingCardVisibility()
                    refreshNowPlayingCard()
                    libraryLockHaptic(overlay)
                }
            if (vertical) animation.translationY(0f) else animation.translationX(0f)
            animation.start()
        } else {
            libraryOpen = false
            libraryPopulateRunnable?.let { handler.removeCallbacks(it) }
            libraryPopulateRunnable = null
            val animation = overlay.animate()
                .alpha(0.92f)
                .setDuration(librarySettleDuration(size - translation, if (vertical) contentFrame.height else contentFrame.width))
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    contentFrame.removeView(overlay)
                    overlay.translationX = 0f
                    overlay.translationY = 0f
                    overlay.setLayerType(View.LAYER_TYPE_NONE, null)
                    updateLibraryGridDragEffects(0f, libraryDragTouchX)
                    renderRibbon()
                    syncNowPlayingCardVisibility()
                    refreshNowPlayingCard()
                }
            if (vertical) animation.translationY(size) else animation.translationX(size)
            animation.start()
        }
    }

    private fun libraryDragAxisSize(): Float {
        return if (libraryDragVertical) {
            (contentFrame.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels).toFloat()
        } else {
            (contentFrame.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
        }
    }

    private fun librarySettleDuration(distancePx: Float, axisSizePx: Int? = null): Long {
        val size = (axisSizePx?.takeIf { it > 0 } ?: contentFrame.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
        return (90 + 130 * (distancePx / size).coerceIn(0f, 1f)).toLong()
    }

    private fun libraryDragHaptic(stage: Int) {
        if (!hapticsEnabled) return
        val view = libraryView ?: contentFrame
        val constant = when (stage) {
            1 -> HapticFeedbackConstants.CLOCK_TICK
            2 -> HapticFeedbackConstants.KEYBOARD_TAP
            else -> HapticFeedbackConstants.CONTEXT_CLICK
        }
        view.performHapticFeedback(constant)
    }

    private fun libraryLockHaptic(view: View) {
        if (!hapticsEnabled) return
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    private fun closeLibrary(slideLeft: Boolean = false) {
        if (!libraryOpen) return
        libraryOpen = false
        query = ""
        libraryDragActive = false
        libraryDragHapticStage = 0
        libraryPopulateRunnable?.let { handler.removeCallbacks(it) }
        libraryPopulateRunnable = null
        libraryRefreshDebounce?.let { handler.removeCallbacks(it) }
        libraryRefreshDebounce = null
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
                closing.setLayerType(View.LAYER_TYPE_NONE, null)
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
            background = widgetBoardScrimBackground()
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                clipToPadding = false
                setPadding(dp(16), systemStatusBarHeight() + dp(12), dp(16), dp(18))
                addView(widgetBoardHeader(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))
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

    private inner class WidgetCellCanvas(context: Context) : FrameLayout(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val snapAnchor = GridSnappingAnchorView(context)

        init {
            setWillNotDraw(false)
            background = Neu.drawable(activeNeuTokens, dp(22).toFloat(), NeuLevel.PRESSED_SM)
            addView(snapAnchor, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val specs = savedWidgetSpecs()
            val metrics = widgetBoardMetrics(specs)
            val dotRadius = dp(1).toFloat()
            paint.color = adjustAlpha(activeNeuTokens.inkFaint, if (activeNeuTokens.mode == NeuMode.LIGHT) 0.24f else 0.18f)
            for (row in 0 until metrics.rows) {
                for (col in 0 until WIDGET_BOARD_COLUMNS) {
                    val cx = paddingLeft + metrics.leftForCell(col) + metrics.cellWidth / 2f
                    val cy = paddingTop + metrics.topForCell(row) + metrics.cellHeight / 2f
                    canvas.drawCircle(cx, cy, dotRadius, paint)
                }
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = adjustAlpha(activeNeuTokens.baseHi, if (activeNeuTokens.mode == NeuMode.LIGHT) 0.35f else 0.12f)
            rect.set(0f, dp(4).toFloat(), width.toFloat(), height - dp(4).toFloat())
            canvas.drawRoundRect(rect, dp(22).toFloat(), dp(22).toFloat(), paint)
            paint.style = Paint.Style.FILL
        }

        fun showSnapAnchor(spec: WidgetSpec, valid: Boolean) {
            val metrics = widgetBoardMetrics()
            snapAnchor.updateAnchorPosition(
                paddingLeft + metrics.leftForCell(spec.cellX),
                paddingTop + metrics.topForCell(spec.cellY),
                metrics.widthForSpan(spec.spanX),
                metrics.heightForSpan(spec.spanY),
                valid
            )
        }

        fun clearSnapAnchor() {
            snapAnchor.clearAnchor()
        }
    }

    private inner class GridSnappingAnchorView(context: Context) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = RectF()
        private var validDrop = true

        fun updateAnchorPosition(leftPx: Int, topPx: Int, widthPx: Int, heightPx: Int, isValidDrop: Boolean) {
            validDrop = isValidDrop
            val inset = dp(5).toFloat()
            bounds.set(
                leftPx + inset,
                topPx + inset,
                leftPx + widthPx - inset,
                topPx + heightPx - inset
            )
            visibility = View.VISIBLE
            invalidate()
        }

        fun clearAnchor() {
            bounds.setEmpty()
            visibility = View.GONE
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (bounds.isEmpty) return
            val radius = dp(22).toFloat()
            val accent = if (validDrop) Accent2 else 0xFFFF5D5D.toInt()
            val tokens = activeNeuTokens

            glowPaint.shader = RadialGradient(
                bounds.centerX(),
                bounds.centerY(),
                bounds.width().coerceAtLeast(bounds.height()),
                intArrayOf(adjustAlpha(accent, 0.20f), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(bounds, radius, radius, glowPaint)
            glowPaint.shader = null

            fillPaint.shader = android.graphics.LinearGradient(
                0f,
                bounds.top,
                0f,
                bounds.bottom,
                intArrayOf(
                    adjustAlpha(tokens.baseHi, if (tokens.mode == NeuMode.LIGHT) 0.56f else 0.26f),
                    adjustAlpha(tokens.base, if (tokens.mode == NeuMode.LIGHT) 0.78f else 0.68f),
                    adjustAlpha(tokens.baseLo, if (tokens.mode == NeuMode.LIGHT) 0.42f else 0.72f)
                ),
                floatArrayOf(0f, 0.44f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(bounds, radius, radius, fillPaint)
            fillPaint.shader = null

            strokePaint.strokeWidth = dp(2).toFloat()
            strokePaint.color = adjustAlpha(accent, if (validDrop) 0.82f else 0.92f)
            canvas.drawRoundRect(bounds, radius, radius, strokePaint)
            strokePaint.strokeWidth = dp(1).toFloat()
            strokePaint.color = adjustAlpha(if (tokens.mode == NeuMode.LIGHT) Color.WHITE else tokens.baseHi, if (tokens.mode == NeuMode.LIGHT) 0.40f else 0.18f)
            canvas.drawRoundRect(RectF(bounds.left + dp(2), bounds.top + dp(2), bounds.right - dp(2), bounds.bottom - dp(2)), radius - dp(2), radius - dp(2), strokePaint)
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
                    textSize = 24f
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
            }, LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.MATCH_PARENT))
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
            clipToPadding = false
            isFillViewport = true
            addView(WidgetCellCanvas(context).apply {
                clipChildren = false
                clipToPadding = false
                setPadding(0, dp(14), 0, dp(30))
                val widgets = savedWidgetSpecs()
                if (widgets.isEmpty()) {
                    addView(emptyWidgetHint(), FrameLayout.LayoutParams(
                        resources.displayMetrics.widthPixels - dp(32),
                        dp(240)
                    ).apply {
                        width = resources.displayMetrics.widthPixels - dp(32)
                        height = dp(240)
                        setMargins(0, dp(26), 0, 0)
                    })
                } else {
                    val metrics = widgetBoardMetrics(widgets)
                    minimumHeight = metrics.canvasHeight + dp(44)
                    widgets.forEach { spec ->
                        addView(widgetTile(spec), FrameLayout.LayoutParams(
                            metrics.widthForSpan(spec.spanX),
                            metrics.heightForSpan(spec.spanY)
                        ).apply {
                            width = metrics.widthForSpan(spec.spanX)
                            height = metrics.heightForSpan(spec.spanY)
                            leftMargin = metrics.leftForCell(spec.cellX)
                            topMargin = metrics.topForCell(spec.cellY)
                        })
                    }
                }
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private data class WidgetBoardMetrics(
        val cellWidth: Int,
        val cellHeight: Int,
        val gutter: Int,
        val rows: Int
    ) {
        val canvasHeight: Int get() = rows * cellHeight + (rows - 1).coerceAtLeast(0) * gutter
        fun leftForCell(cellX: Int) = cellX * (cellWidth + gutter)
        fun topForCell(cellY: Int) = cellY * (cellHeight + gutter)
        fun widthForSpan(spanX: Int) = spanX * cellWidth + (spanX - 1).coerceAtLeast(0) * gutter
        fun heightForSpan(spanY: Int) = spanY * cellHeight + (spanY - 1).coerceAtLeast(0) * gutter
    }

    private fun widgetBoardMetrics(specs: List<WidgetSpec> = savedWidgetSpecs()): WidgetBoardMetrics {
        val gutter = dp(8)
        val available = resources.displayMetrics.widthPixels - dp(32)
        val cellWidth = ((available - gutter * (WIDGET_BOARD_COLUMNS - 1)) / WIDGET_BOARD_COLUMNS).coerceAtLeast(dp(64))
        val cellHeight = dp(98)
        val rows = maxOf(WIDGET_BOARD_MIN_ROWS, specs.maxOfOrNull { it.cellY + it.spanY + 1 } ?: WIDGET_BOARD_MIN_ROWS)
        return WidgetBoardMetrics(cellWidth, cellHeight, gutter, rows.coerceAtMost(WIDGET_BOARD_MAX_ROWS))
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
        val title = info?.loadLabel(packageManager)?.ifBlank { "Widget" } ?: "Widget"
        val hostView = if (info != null) {
            appWidgetHost.createView(this, widgetId, info).apply {
                setAppWidget(widgetId, info)
                optimizeWidgetHostView(this)
                updateAppWidgetSize(null, widgetMinWidthDp(spec), widgetMinHeightDp(spec), widgetMaxWidthDp(spec), widgetMaxHeightDp(spec))
            }
        } else null
        return ResizableWidgetContainer(this, spec).apply {
            if (hostView != null) {
                addView(hostView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            } else {
                addView(mono("WIDGET UNAVAILABLE", 9f, InkDim).apply { gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                background = Neu.drawable(activeNeuTokens, dp(13).toFloat(), NeuLevel.RAISED_SM)
                addView(mono(title.uppercase(Locale.US), 8f, InkDim).apply {
                    letterSpacing = 0.12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(mono("${spec.spanX}x${spec.spanY}", 8f, Accent2).apply {
                    gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                    letterSpacing = 0.08f
                }, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.MATCH_PARENT))
                setOnClickListener {
                    haptic(this)
                    showWidgetOptions(spec)
                }
                setOnLongClickListener {
                    (parent as? ResizableWidgetContainer)?.showQuickMenu()
                    true
                }
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(22), Gravity.TOP).apply {
                leftMargin = dp(10); rightMargin = dp(10); topMargin = dp(5)
            })
            setOnLongClickListener {
                showQuickMenu()
                true
            }
        }
    }

    private fun showWidgetQuickMenu(anchor: View, spec: WidgetSpec, onResize: () -> Unit) {
        val itemHeight = dp(44)
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = Neu.drawable(activeNeuTokens, dp(18).toFloat(), NeuLevel.RAISED)
            elevation = dp(18).toFloat()
            addView(widgetQuickMenuItem("Resize", 0xFF8FD694.toInt()), LinearLayout.LayoutParams(dp(178), itemHeight))
            addView(widgetQuickMenuDivider(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
            })
            addView(widgetQuickMenuItem("Remove", 0xFFFF6B6B.toInt()), LinearLayout.LayoutParams(dp(178), itemHeight))
        }
        val popup = PopupWindow(menu, dp(194), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = dp(18).toFloat()
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            animationStyle = android.R.style.Animation_Dialog
        }
        menu.childrenList().forEach { child ->
            child.setOnClickListener {
                popup.dismiss()
                when ((it as? TextView)?.text?.toString()) {
                    "Resize" -> onResize()
                    "Remove" -> confirmRemoveWidget(spec.id)
                }
            }
        }
        haptic(anchor)
        popup.showAsDropDown(anchor, dp(12), -anchor.height - dp(6), Gravity.TOP or Gravity.START)
    }

    private fun widgetQuickMenuItem(label: String, accent: Int): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER_VERTICAL
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setTextColor(if (label == "Remove") accent else activeNeuTokens.ink)
            setPadding(dp(14), 0, dp(14), 0)
            background = Neu.drawable(activeNeuTokens, dp(13).toFloat(), NeuLevel.PRESSED_SM)
            compoundDrawablePadding = dp(9)
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(adjustAlpha(accent, 0.92f))
                setSize(dp(10), dp(10))
            }
            setCompoundDrawablesWithIntrinsicBounds(dot, null, null, null)
        }
    }

    private fun widgetQuickMenuDivider(): View {
        return View(this).apply {
            setBackgroundColor(adjustAlpha(activeNeuTokens.inkFaint, if (activeNeuTokens.mode == NeuMode.LIGHT) 0.20f else 0.16f))
        }
    }

    private fun ViewGroup.childrenList(): List<View> = (0 until childCount).map { getChildAt(it) }.filterIsInstance<TextView>()

    private inner class ResizableWidgetContainer(context: Context, private var spec: WidgetSpec) : FrameLayout(context) {
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var editing = false
        private var moving = false
        private var resizing = false
        private var previewSpec: WidgetSpec? = null
        private var downRawX = 0f
        private var downRawY = 0f
        private var startCellX = 0
        private var startCellY = 0
        private var startSpanX = 1
        private var startSpanY = 1

        init {
            clipChildren = true
            clipToPadding = true
            setWillNotDraw(false)
            setPadding(dp(7), dp(28), dp(7), dp(7))
            background = widgetTileBackground()
            isClickable = true
            isLongClickable = true
        }

        fun enterEditMode() {
            editing = true
            haptic(this)
            invalidate()
        }

        fun showQuickMenu() {
            showWidgetQuickMenu(this, spec) {
                enterEditMode()
            }
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            return editing && event.actionMasked == MotionEvent.ACTION_DOWN
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!editing && !moving && !resizing) return
            val accent = Accent2
            handlePaint.style = Paint.Style.STROKE
            handlePaint.strokeWidth = dp(1).toFloat()
            handlePaint.color = adjustAlpha(accent, 0.82f)
            canvas.drawRoundRect(dp(4).toFloat(), dp(4).toFloat(), width - dp(4).toFloat(), height - dp(4).toFloat(), dp(18).toFloat(), dp(18).toFloat(), handlePaint)
            handlePaint.style = Paint.Style.FILL
            listOf(
                dp(14).toFloat() to height / 2f,
                width - dp(14).toFloat() to height / 2f,
                width / 2f to dp(14).toFloat(),
                width / 2f to height - dp(14).toFloat(),
                width - dp(14).toFloat() to height - dp(14).toFloat()
            ).forEach { (x, y) ->
                handlePaint.color = adjustAlpha(0xFF000000.toInt(), 0.34f)
                canvas.drawCircle(x, y + dp(1), dp(7).toFloat(), handlePaint)
                handlePaint.color = accent
                canvas.drawCircle(x, y, dp(6).toFloat(), handlePaint)
                handlePaint.color = adjustAlpha(0xFFFFFFFF.toInt(), 0.55f)
                canvas.drawCircle(x - dp(2), y - dp(2), dp(2).toFloat(), handlePaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startCellX = spec.cellX
                    startCellY = spec.cellY
                    startSpanX = spec.spanX
                    startSpanY = spec.spanY
                    resizing = isResizeHandle(event.x, event.y)
                    moving = editing && !resizing
                    if (resizing || moving) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        bringToFront()
                        (parent as? WidgetCellCanvas)?.showSnapAnchor(spec, true)
                        animate().alpha(0.76f).scaleX(0.985f).scaleY(0.985f).translationZ(dp(10).toFloat()).setDuration(80).start()
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moving && !resizing) return super.onTouchEvent(event)
                    val metrics = widgetBoardMetrics()
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (moving) {
                        val nextCellX = (startCellX + kotlin.math.round(dx / (metrics.cellWidth + metrics.gutter)).toInt())
                            .coerceIn(0, WIDGET_BOARD_COLUMNS - spec.spanX)
                        val nextCellY = (startCellY + kotlin.math.round(dy / (metrics.cellHeight + metrics.gutter)).toInt())
                            .coerceIn(0, WIDGET_BOARD_MAX_ROWS - spec.spanY)
                        val next = spec.copy(cellX = nextCellX, cellY = nextCellY)
                        previewSpec = next
                        translationX = dx
                        translationY = dy
                        (parent as? WidgetCellCanvas)?.showSnapAnchor(next, isWidgetDropValid(next, savedWidgetSpecs().filterNot { it.id == spec.id }))
                    } else if (resizing) {
                        val nextSpanX = (startSpanX + kotlin.math.round(dx / (metrics.cellWidth + metrics.gutter)).toInt())
                            .coerceIn(spec.minSpanX, WIDGET_BOARD_COLUMNS - spec.cellX)
                        val nextSpanY = (startSpanY + kotlin.math.round(dy / (metrics.cellHeight + metrics.gutter)).toInt())
                            .coerceIn(spec.minSpanY, WIDGET_BOARD_MAX_ROWS - spec.cellY)
                        val next = spec.copy(spanX = nextSpanX, spanY = nextSpanY)
                        (layoutParams as? FrameLayout.LayoutParams)?.let {
                            it.width = metrics.widthForSpan(nextSpanX)
                            it.height = metrics.heightForSpan(nextSpanY)
                            layoutParams = it
                        }
                        spec = next
                        previewSpec = next
                        (parent as? WidgetCellCanvas)?.showSnapAnchor(next, isWidgetDropValid(next, savedWidgetSpecs().filterNot { it.id == spec.id }))
                        updateHostedWidgetSize()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moving || resizing) {
                        val candidate = previewSpec ?: spec
                        val existing = savedWidgetSpecs().filterNot { it.id == candidate.id }
                        val finalSpec = if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                            candidate.copy(cellX = startCellX, cellY = startCellY, spanX = startSpanX, spanY = startSpanY)
                        } else {
                            resolveWidgetPlacement(candidate, existing)
                        }
                        val metrics = widgetBoardMetrics()
                        (layoutParams as? FrameLayout.LayoutParams)?.let {
                            val visualLeft = it.leftMargin + translationX
                            val visualTop = it.topMargin + translationY
                            it.width = metrics.widthForSpan(finalSpec.spanX)
                            it.height = metrics.heightForSpan(finalSpec.spanY)
                            it.leftMargin = metrics.leftForCell(finalSpec.cellX)
                            it.topMargin = metrics.topForCell(finalSpec.cellY)
                            layoutParams = it
                            translationX = visualLeft - it.leftMargin
                            translationY = visualTop - it.topMargin
                        }
                        spec = finalSpec
                        animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .translationX(0f)
                            .translationY(0f)
                            .translationZ(0f)
                            .setDuration(240)
                            .setInterpolator(android.view.animation.OvershootInterpolator(0.82f))
                            .start()
                        saveWidgetSpec(spec)
                        updateHostedWidgetSize()
                        moving = false
                        resizing = false
                        editing = false
                        previewSpec = null
                        (parent as? WidgetCellCanvas)?.clearSnapAnchor()
                        invalidate()
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                }
            }
            return super.onTouchEvent(event)
        }

        private fun isResizeHandle(x: Float, y: Float): Boolean {
            return x >= width - dp(40) && y >= height - dp(40)
        }

        private fun updateHostedWidgetSize() {
            val hostView = (0 until childCount).map { getChildAt(it) }.firstOrNull { it is AppWidgetHostView } as? AppWidgetHostView ?: return
            hostView.updateAppWidgetSize(null, widgetMinWidthDp(spec), widgetMinHeightDp(spec), widgetMaxWidthDp(spec), widgetMaxHeightDp(spec))
            trackWidgetInteractionSafe(hostView, spec, if (resizing) "resize" else "move")
        }
    }

    private fun showWidgetPicker() {
        val board = widgetBoardView as? ViewGroup ?: return
        closeWidgetPicker()
        widgetPickerQuery = ""
        val picker = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            isClickable = true
            setOnClickListener { closeWidgetPicker() }
            addView(LinearLayout(context).apply {
                isClickable = true
                orientation = LinearLayout.VERTICAL
                background = widgetPickerSheetBackground()
                elevation = dp(18).toFloat()
                setPadding(dp(16), dp(14), dp(16), dp(16))
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    addView(TextView(context).apply {
                        text = "Add widget"
                        textSize = 22f
                        typeface = Typeface.DEFAULT_BOLD
                        includeFontPadding = false
                        setTextColor(Ink)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                    addView(mono("TAP OUTSIDE", 8f, InkDim).apply {
                        gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                        letterSpacing = 0.12f
                    }, LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.MATCH_PARENT))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
                addView(mono("Choose a widget. It will land on the board as a clean resizable card.", 9f, InkDim).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, 0, 0, dp(8))
                }, LinearLayout.LayoutParams.MATCH_PARENT, dp(28))
                addView(widgetPickerSearchField(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                    bottomMargin = dp(12)
                })
                addView(FrameLayout(context).also { widgetPickerListHost = it }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.72f).toInt(), Gravity.BOTTOM).apply {
                leftMargin = dp(10)
                rightMargin = dp(10)
                bottomMargin = dp(10)
            })
        }
        widgetPickerView = picker
        board.addView(picker, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        rebuildWidgetProviderList()
    }

    private fun closeWidgetPicker() {
        val picker = widgetPickerView ?: return
        widgetPickerView = null
        widgetPickerListHost = null
        widgetPickerQuery = ""
        (picker.parent as? ViewGroup)?.removeView(picker)
    }

    private fun widgetPickerSearchField(): EditText {
        return EditText(this).apply {
            setSingleLine(true)
            hint = "Search widgets"
            textSize = 14f
            includeFontPadding = false
            setTextColor(activeNeuTokens.ink)
            setHintTextColor(activeNeuTokens.inkFaint)
            background = Neu.drawable(activeNeuTokens, dp(16).toFloat(), NeuLevel.PRESSED_SM)
            setPadding(dp(16), 0, dp(16), 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    widgetPickerQuery = s?.toString().orEmpty()
                    rebuildWidgetProviderList()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
    }

    private fun rebuildWidgetProviderList() {
        val host = widgetPickerListHost ?: return
        host.removeAllViews()
        host.addView(widgetProviderList(widgetPickerQuery), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun widgetProviderList(filter: String = ""): View {
        return ScrollView(this).apply {
            clipToPadding = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(20))
                val cleanFilter = filter.trim().lowercase(Locale.US)
                val providersByApp = installedWidgetProviders().groupBy { provider ->
                    runCatching {
                        val appInfo = packageManager.getApplicationInfo(provider.provider.packageName, 0)
                        packageManager.getApplicationLabel(appInfo).toString()
                    }.getOrDefault(provider.provider.packageName)
                }.mapValues { (_, providers) ->
                    if (cleanFilter.isBlank()) providers else providers.filter { provider ->
                        provider.loadLabel(packageManager).lowercase(Locale.US).contains(cleanFilter)
                    }
                }.filter { (appLabel, providers) ->
                    providers.isNotEmpty() || appLabel.lowercase(Locale.US).contains(cleanFilter)
                }.toSortedMap(collator)
                if (widgetPickerExpandedApps.isEmpty() && cleanFilter.isBlank()) {
                    providersByApp.keys.firstOrNull()?.let { widgetPickerExpandedApps.add(it) }
                }
                providersByApp.forEach { (appLabel, providers) ->
                    val expanded = cleanFilter.isNotBlank() || appLabel in widgetPickerExpandedApps
                    addView(widgetProviderGroupHeader(appLabel, providers, expanded), LinearLayout.LayoutParams.MATCH_PARENT, dp(58))
                    if (expanded) {
                        addView(GridLayout(context).apply {
                            columnCount = 2
                            setPadding(0, 0, 0, dp(7))
                            providers.forEach { provider ->
                                addView(widgetProviderCard(provider, appLabel), GridLayout.LayoutParams().apply {
                                    width = (resources.displayMetrics.widthPixels - dp(64)) / 2
                                    height = dp(126)
                                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1)
                                    setMargins(dp(4), dp(4), dp(4), dp(7))
                                })
                            }
                        }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                }
                if (providersByApp.isEmpty()) {
                    addView(mono("NO WIDGETS AVAILABLE ON THIS DEVICE", 10f, InkDim).apply {
                        gravity = Gravity.CENTER
                        letterSpacing = 0.12f
                    }, LinearLayout.LayoutParams.MATCH_PARENT, dp(180))
                }
            })
        }
    }

    private fun widgetProviderGroupHeader(appLabel: String, providers: List<AppWidgetProviderInfo>, expanded: Boolean): View {
        val packageName = providers.firstOrNull()?.provider?.packageName
        val icon = packageName?.let { runCatching { packageManager.getApplicationIcon(it) }.getOrNull() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = Neu.drawable(activeNeuTokens, dp(18).toFloat(), if (expanded) NeuLevel.PRESSED_SM else NeuLevel.RAISED_SM)
            isClickable = true
            addView(FrameLayout(context).apply {
                background = dockIconButtonBackground()
                setPadding(dp(7), dp(7), dp(7), dp(7))
                addView(ImageView(context).apply {
                    if (icon != null) setImageDrawable(icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = appLabel
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(activeNeuTokens.ink)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(mono("${providers.size} WIDGET${if (providers.size == 1) "" else "S"}", 8f, activeNeuTokens.inkFaint).apply {
                    letterSpacing = 0.12f
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(10) })
            addView(mono(if (expanded) "−" else "+", 17f, activeNeuTokens.inkDim).apply {
                gravity = Gravity.CENTER
                includeFontPadding = false
            }, LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.MATCH_PARENT))
            setOnClickListener {
                haptic(this)
                if (expanded) widgetPickerExpandedApps.remove(appLabel) else widgetPickerExpandedApps.add(appLabel)
                rebuildWidgetProviderList()
            }
        }
    }

    private fun widgetProviderCard(provider: AppWidgetProviderInfo, appLabel: String): View {
        val label = provider.loadLabel(packageManager).ifBlank { "Widget" }
        val icon = runCatching { provider.loadIcon(this, resources.displayMetrics.densityDpi) }.getOrNull()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(11), dp(12), dp(10))
            background = widgetProviderRowBackground()
            isClickable = true
            addView(FrameLayout(context).apply {
                background = dockIconButtonBackground()
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(ImageView(context).apply {
                    setImageDrawable(icon ?: packageManager.getApplicationIcon(provider.provider.packageName))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(TextView(context).apply {
                text = label
                textSize = 13.2f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ink)
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(9), 0, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(mono("ADD", 8.4f, Accent2).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.14f
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18)))
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
        val current = savedWidgetSpecs().filterNot { it.id == widgetId }
        val next = current + nextWidgetSpec(widgetId, current, 4, 3)
        saveWidgetSpecs(next)
    }

    private fun savedWidgetSpecs(): List<WidgetSpec> {
        widgetSpecsCache?.let { return it }
        return loadWidgetSpecsFromStore().also { widgetSpecsCache = it }
    }

    private fun loadWidgetSpecsFromStore(): List<WidgetSpec> {
        val roomSpecs = if (::widgetPersistenceRepository.isInitialized) {
            runCatching { widgetPersistenceRepository.loadBlocking() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        if (roomSpecs.isNotEmpty()) return roomSpecs
        val legacy = loadWidgetSpecsFromPrefs()
        if (legacy.isNotEmpty() && ::widgetPersistenceRepository.isInitialized) {
            mediaUiScope.launch { widgetPersistenceRepository.replaceAll(legacy) }
        }
        return legacy
    }

    private fun loadWidgetSpecsFromPrefs(): List<WidgetSpec> {
        val raw = prefs().getString(WIDGET_IDS_PREF, "[]") ?: "[]"
        return runCatching {
            val json = JSONArray(raw)
            val specs = mutableListOf<WidgetSpec>()
            (0 until json.length()).forEach { index ->
                when (val item = json.opt(index)) {
                    is JSONObject -> {
                        val id = item.optInt("id", AppWidgetManager.INVALID_APPWIDGET_ID)
                        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                            val span = parseWidgetGridSize(item.optString("size", widgetSizeString(item.optInt("spanX", 4), item.optInt("spanY", 3))))
                            val spanX = item.optInt("spanX", span.columns).coerceIn(1, WIDGET_BOARD_COLUMNS)
                            val spanY = item.optInt("spanY", span.rows).coerceIn(1, WIDGET_BOARD_MAX_ROWS)
                            val hasCells = item.has("cellX") && item.has("cellY")
                            val spec = if (hasCells) {
                                WidgetSpec(
                                    id = id,
                                    cellX = item.optInt("cellX", 0).coerceIn(0, WIDGET_BOARD_COLUMNS - spanX),
                                    cellY = item.optInt("cellY", 0).coerceIn(0, WIDGET_BOARD_MAX_ROWS - spanY),
                                    spanX = spanX,
                                    spanY = spanY,
                                    minSpanX = item.optInt("minSpanX", 1).coerceIn(1, WIDGET_BOARD_COLUMNS),
                                    minSpanY = item.optInt("minSpanY", 1).coerceIn(1, WIDGET_BOARD_MAX_ROWS)
                                )
                            } else {
                                nextWidgetSpec(id, specs, spanX, spanY)
                            }
                            specs.add(spec)
                        }
                    }
                    is Number -> {
                        val id = item.toInt()
                        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) specs.add(nextWidgetSpec(id, specs, 4, 3))
                    }
                    else -> {
                        val id = json.optInt(index, AppWidgetManager.INVALID_APPWIDGET_ID)
                        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) specs.add(nextWidgetSpec(id, specs, 4, 3))
                    }
                }
            }
            specs
        }.getOrDefault(emptyList())
    }

    private fun saveWidgetSpecs(specs: List<WidgetSpec>) {
        val safeSpecs = specs.map { spec ->
            val spanX = spec.spanX.coerceIn(spec.minSpanX, WIDGET_BOARD_COLUMNS)
            val spanY = spec.spanY.coerceIn(spec.minSpanY, WIDGET_BOARD_MAX_ROWS)
            spec.copy(
                cellX = spec.cellX.coerceIn(0, WIDGET_BOARD_COLUMNS - spanX),
                cellY = spec.cellY.coerceIn(0, WIDGET_BOARD_MAX_ROWS - spanY),
                spanX = spanX,
                spanY = spanY,
                minSpanX = spec.minSpanX.coerceIn(1, WIDGET_BOARD_COLUMNS),
                minSpanY = spec.minSpanY.coerceIn(1, WIDGET_BOARD_MAX_ROWS)
            )
        }
        widgetSpecsCache = safeSpecs
        val json = JSONArray()
        safeSpecs.forEach { spec ->
            json.put(JSONObject().apply {
                put("id", spec.id)
                put("cellX", spec.cellX.coerceIn(0, WIDGET_BOARD_COLUMNS - spec.spanX.coerceIn(1, WIDGET_BOARD_COLUMNS)))
                put("cellY", spec.cellY.coerceIn(0, WIDGET_BOARD_MAX_ROWS - spec.spanY.coerceIn(1, WIDGET_BOARD_MAX_ROWS)))
                put("spanX", spec.spanX.coerceIn(1, WIDGET_BOARD_COLUMNS))
                put("spanY", spec.spanY.coerceIn(1, WIDGET_BOARD_MAX_ROWS))
                put("minSpanX", spec.minSpanX.coerceIn(1, WIDGET_BOARD_COLUMNS))
                put("minSpanY", spec.minSpanY.coerceIn(1, WIDGET_BOARD_MAX_ROWS))
            })
        }
        prefs().edit().putString(WIDGET_IDS_PREF, json.toString()).apply()
        if (::widgetPersistenceRepository.isInitialized) {
            mediaUiScope.launch { widgetPersistenceRepository.replaceAll(safeSpecs) }
        }
    }

    private fun showWidgetOptions(spec: WidgetSpec) {
        val labels = arrayOf(
            "Wider",
            "Narrower",
            "Taller",
            "Shorter",
            "Compact 2x2",
            "Wide 4x2",
            "Large 4x3",
            "Remove"
        )
        AlertDialog.Builder(this)
            .setTitle("Widget ${spec.spanX}x${spec.spanY}")
            .setItems(labels) { dialog, which ->
                when (labels[which]) {
                    "Wider" -> resizeWidget(spec.id, (spec.spanX + 1).coerceAtMost(WIDGET_BOARD_COLUMNS), spec.spanY)
                    "Narrower" -> resizeWidget(spec.id, (spec.spanX - 1).coerceAtLeast(spec.minSpanX), spec.spanY)
                    "Taller" -> resizeWidget(spec.id, spec.spanX, (spec.spanY + 1).coerceAtMost(WIDGET_BOARD_MAX_ROWS))
                    "Shorter" -> resizeWidget(spec.id, spec.spanX, (spec.spanY - 1).coerceAtLeast(spec.minSpanY))
                    "Compact 2x2" -> resizeWidget(spec.id, 2, 2)
                    "Wide 4x2" -> resizeWidget(spec.id, 4, 2)
                    "Large 4x3" -> resizeWidget(spec.id, 4, 3)
                    "Remove" -> confirmRemoveWidget(spec.id)
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun resizeWidget(widgetId: Int, spanX: Int, spanY: Int) {
        val specs = savedWidgetSpecs().map { spec ->
            if (spec.id == widgetId) {
                val nextSpanX = spanX.coerceIn(spec.minSpanX, WIDGET_BOARD_COLUMNS)
                val nextSpanY = spanY.coerceIn(spec.minSpanY, WIDGET_BOARD_MAX_ROWS)
                spec.copy(
                    spanX = nextSpanX,
                    spanY = nextSpanY,
                    cellX = spec.cellX.coerceIn(0, WIDGET_BOARD_COLUMNS - nextSpanX),
                    cellY = spec.cellY.coerceIn(0, WIDGET_BOARD_MAX_ROWS - nextSpanY)
                )
            } else spec
        }
        saveWidgetSpecs(specs)
        refreshWidgetBoard()
    }

    private fun saveWidgetSpec(updated: WidgetSpec) {
        val existing = savedWidgetSpecs().filterNot { it.id == updated.id }
        val safe = resolveWidgetPlacement(updated, existing)
        saveWidgetSpecs(existing + safe)
    }

    private fun resolveWidgetPlacement(updated: WidgetSpec, existing: List<WidgetSpec>): WidgetSpec {
        val clamped = updated.copy(
            spanX = updated.spanX.coerceIn(updated.minSpanX, WIDGET_BOARD_COLUMNS),
            spanY = updated.spanY.coerceIn(updated.minSpanY, WIDGET_BOARD_MAX_ROWS)
        ).let {
            it.copy(
                cellX = it.cellX.coerceIn(0, WIDGET_BOARD_COLUMNS - it.spanX),
                cellY = it.cellY.coerceIn(0, WIDGET_BOARD_MAX_ROWS - it.spanY)
            )
        }
        if (!existing.any { widgetsOverlap(it, clamped) }) return clamped
        val candidates = mutableListOf<WidgetSpec>()
        for (row in 0..(WIDGET_BOARD_MAX_ROWS - clamped.spanY)) {
            for (col in 0..(WIDGET_BOARD_COLUMNS - clamped.spanX)) {
                candidates.add(clamped.copy(cellX = col, cellY = row))
            }
        }
        return candidates
            .filter { candidate -> existing.none { widgetsOverlap(it, candidate) } }
            .minByOrNull { kotlin.math.abs(it.cellX - clamped.cellX) + kotlin.math.abs(it.cellY - clamped.cellY) }
            ?: clamped
    }

    private fun nextWidgetSpec(widgetId: Int, existing: List<WidgetSpec>, preferredSpanX: Int, preferredSpanY: Int): WidgetSpec {
        val spanX = preferredSpanX.coerceIn(1, WIDGET_BOARD_COLUMNS)
        val spanY = preferredSpanY.coerceIn(1, WIDGET_BOARD_MAX_ROWS)
        for (row in 0..(WIDGET_BOARD_MAX_ROWS - spanY)) {
            for (col in 0..(WIDGET_BOARD_COLUMNS - spanX)) {
                val candidate = WidgetSpec(widgetId, col, row, spanX, spanY)
                if (!existing.any { widgetsOverlap(it, candidate) }) return candidate
            }
        }
        val lastY = existing.maxOfOrNull { it.cellY + it.spanY } ?: 0
        return WidgetSpec(widgetId, 0, lastY.coerceIn(0, WIDGET_BOARD_MAX_ROWS - spanY), spanX, spanY)
    }

    private fun widgetsOverlap(left: WidgetSpec, right: WidgetSpec): Boolean {
        if (left.id == right.id) return false
        return left.cellX < right.cellX + right.spanX &&
            left.cellX + left.spanX > right.cellX &&
            left.cellY < right.cellY + right.spanY &&
            left.cellY + left.spanY > right.cellY
    }

    private fun isWidgetDropValid(target: WidgetSpec, existing: List<WidgetSpec>): Boolean {
        val inBounds = target.cellX >= 0 &&
            target.cellY >= 0 &&
            target.cellX + target.spanX <= WIDGET_BOARD_COLUMNS &&
            target.cellY + target.spanY <= WIDGET_BOARD_MAX_ROWS
        return inBounds && existing.none { widgetsOverlap(it, target) }
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
        return when {
            size.matches(Regex("c[1-4]r([1-9]|1[0-2])")) -> size
            size == WIDGET_SIZE_COMPACT -> widgetSizeString(2, 2)
            size == WIDGET_SIZE_WIDE -> widgetSizeString(4, 2)
            size == WIDGET_SIZE_TALL -> widgetSizeString(2, 4)
            size == WIDGET_SIZE_LARGE -> widgetSizeString(4, 3)
            else -> widgetSizeString(4, 3)
        }
    }

    private fun widgetSizeString(columns: Int, rows: Int) =
        "c${columns.coerceIn(1, WIDGET_BOARD_COLUMNS)}r${rows.coerceIn(1, WIDGET_BOARD_MAX_ROWS)}"

    private fun parseWidgetGridSize(size: String): WidgetGridSize {
        val clean = normalizeWidgetSize(size)
        val match = Regex("c([1-4])r([1-9]|1[0-2])").matchEntire(clean)
        val columns = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 4
        val rows = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 3
        return WidgetGridSize(columns.coerceIn(1, WIDGET_BOARD_COLUMNS), rows.coerceIn(1, WIDGET_BOARD_MAX_ROWS))
    }

    private fun widgetMinWidthDp(spec: WidgetSpec): Int = spec.spanX.coerceIn(1, WIDGET_BOARD_COLUMNS) * 78

    private fun widgetMaxWidthDp(spec: WidgetSpec): Int = spec.spanX.coerceIn(1, WIDGET_BOARD_COLUMNS) * 132

    private fun widgetMinHeightDp(spec: WidgetSpec): Int = spec.spanY.coerceIn(1, WIDGET_BOARD_MAX_ROWS) * 72

    private fun widgetMaxHeightDp(spec: WidgetSpec): Int = spec.spanY.coerceIn(1, WIDGET_BOARD_MAX_ROWS) * 112

    private fun optimizeWidgetHostView(hostView: AppWidgetHostView) {
        if (Build.VERSION.SDK_INT < 36) return
        runCatching {
            hostView.clipChildren = false
            hostView.clipToPadding = false
            Class.forName("android.appwidget.AppWidgetEvent")
            Class.forName("android.appwidget.AppWidgetMetricsManager")
        }
    }

    private fun trackWidgetInteractionSafe(hostView: AppWidgetHostView, spec: WidgetSpec, actionType: String) {
        if (Build.VERSION.SDK_INT < 36) return
        runCatching {
            Class.forName("android.appwidget.AppWidgetEvent")
            Class.forName("android.appwidget.AppWidgetMetricsManager")
            hostView.context.getSystemService("appwidget_metrics") ?: return
            android.util.Log.d("ClicksWidgetBoard", "API36 widget metric hook: $actionType ${spec.id} ${spec.spanX}x${spec.spanY}")
        }
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
        if (animate) overlay.translationX = resources.displayMetrics.widthPixels.toFloat()
        // Overlay contentFrame only; the keyboard is a sibling below and stays docked.
        contentFrame.addView(overlay, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        if (animate) overlay.post {
            overlay.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            overlay.animate()
                .translationX(0f)
                .setDuration(190)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    overlay.setLayerType(View.LAYER_TYPE_NONE, null)
                }
                .start()
        }
        scheduleLibraryPopulate(if (animate) 48L else 0L)
    }

    private fun appLibrary(): View {
        val cached = libraryView as? FrameLayout
        if (!libraryViewDirty && cached != null && libraryViewMode == activeNeuTokens.mode) {
            libraryContentArea = cached.findViewWithTag("library_content") as? FrameLayout
            cached.setBackgroundColor(activeNeuTokens.base)
            return cached
        }
        return FrameLayout(this).apply {
            setPadding(dp(14), dp(14), dp(14), dp(10))
            setBackgroundColor(activeNeuTokens.base)
            val contentArea = FrameLayout(context).apply { tag = "library_content" }
            libraryContentArea = contentArea
            showLibraryLoading(contentArea)
            addView(contentArea, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(38)
            })
            addView(libraryHeader(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(46), Gravity.TOP))
            libraryViewMode = activeNeuTokens.mode
            libraryViewDirty = false
        }
    }

    private fun prewarmLibraryView() {
        if (libraryOpen || openPane != null || !::contentFrame.isInitialized || query.isNotBlank()) return
        if (!libraryViewDirty && libraryView is FrameLayout && libraryContentReady) return
        val previousView = libraryView
        val previousArea = libraryContentArea
        val overlay = appLibrary()
        libraryView = overlay
        libraryContentArea?.let { area ->
            libraryPopulateRunnable?.let { handler.removeCallbacks(it) }
            libraryPopulateRunnable = null
            fillLibraryContent(area)
            libraryContentReady = true
        }
        if (overlay.parent != null) {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
        if (libraryOpen || openPane != null) {
            libraryView = previousView
            libraryContentArea = previousArea
        }
    }

    private fun showLibraryLoading(area: FrameLayout) {
        area.removeAllViews()
        libraryContentReady = false
        area.addView(TextView(this).apply {
            text = ""
            setTextColor(activeNeuTokens.inkFaint)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }

    private fun scheduleLibraryPopulate(delayMs: Long) {
        if (libraryContentReady && query.isBlank()) return
        libraryPopulateRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            libraryPopulateRunnable = null
            if (!libraryOpen && !libraryDragActive) return@Runnable
            libraryContentArea?.let { fillLibraryContent(it) }
        }
        libraryPopulateRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun fillLibraryContent(area: FrameLayout) {
        area.removeAllViews()
        libraryGridLiquidBackdrop = null
        libraryGridBlurView = null
        libraryGridScrollView = null
        if (query.isNotBlank()) {
            libraryView?.alpha = 1f
        }
        val child = if (query.isNotBlank()) searchResultsGrid()
                    else if (libraryGridMode) libraryGrid() else bentoGrid()
        area.addView(child, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        libraryContentReady = query.isBlank()
    }

    private fun refreshLibraryContent() {
        val area = libraryContentArea ?: run { showLibrary(animate = false); return }
        libraryPopulateRunnable?.let { handler.removeCallbacks(it) }
        libraryPopulateRunnable = null
        fillLibraryContent(area)
    }

    private fun updateLibraryGridDragEffects(progress: Float, touchX: Float) {
        if (!libraryGridMode || query.isNotBlank()) return
        val p = progress.coerceIn(0f, 1f)
        libraryGridLiquidBackdrop?.updateDragProgress(p, touchX)
        libraryGridBlurView?.alpha = (0.72f * p).coerceIn(0f, 0.72f)
        libraryGridScrollView?.let { scroll ->
            scroll.translationY = dp(30).toFloat() * (1f - p)
            val scale = 0.965f + p * 0.035f
            scroll.scaleX = scale
            scroll.scaleY = scale
            scroll.applyFishEye()
        }
    }

    private fun libraryGridFrostBackground(): Drawable {
        val light = activeNeuTokens.mode == NeuMode.LIGHT
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (light) {
                intArrayOf(0xDDECEEF2.toInt(), 0xBFE3E6EC.toInt(), 0x99D8DDE6.toInt())
            } else {
                intArrayOf(adjustAlpha(activeNeuTokens.baseHi, 0.42f), adjustAlpha(activeNeuTokens.base, 0.46f), adjustAlpha(activeNeuTokens.baseLo, 0.58f))
            }
        )
        val glow = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(adjustAlpha(activeNeuTokens.baseHi, if (light) 0.46f else 0.16f), Color.TRANSPARENT, adjustAlpha(activeNeuTokens.baseLo, if (light) 0.16f else 0.28f))
        )
        return LayerDrawable(arrayOf(base, glow))
    }

    private fun libraryHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(10), 0)
        background = libraryHeaderBackground()
        elevation = dp(10).toFloat()
        addView(TextView(context).apply {
            text = "App Library"; textSize = 17f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setTextColor(activeNeuTokens.ink); includeFontPadding = false
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = if (libraryGridMode) "Categories" else "Grid"
            gravity = Gravity.CENTER
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(activeNeuTokens.ink)
            includeFontPadding = false
            background = libraryModeToggleBackground()
            isClickable = true
            setOnClickListener {
                haptic(this)
                libraryGridMode = !libraryGridMode
                prefs().edit().putBoolean(LIBRARY_GRID_MODE_PREF, libraryGridMode).apply()
                libraryViewDirty = true
                libraryContentReady = false
                showLibrary(animate = false)
            }
        }, LinearLayout.LayoutParams(dp(94), dp(30)).apply { marginEnd = dp(8) })
    }

    private fun libraryHeaderBackground(): Drawable {
        if (activeNeuTokens.mode == NeuMode.LIGHT) {
            return Neu.drawable(activeNeuTokens, dp(18).toFloat(), NeuLevel.RAISED_SM)
        }
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(activeNeuTokens.baseHi, activeNeuTokens.base, activeNeuTokens.baseLo)
        ).apply {
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), adjustAlpha(activeNeuTokens.baseHi, 0.22f))
        }
        val rim = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(adjustAlpha(activeNeuTokens.baseHi, 0.18f), Color.TRANSPARENT, adjustAlpha(activeNeuTokens.baseLo, 0.38f))
        ).apply { cornerRadius = dp(18).toFloat() }
        return LayerDrawable(arrayOf(base, rim))
    }

    private fun libraryModeToggleBackground(): Drawable {
        if (activeNeuTokens.mode == NeuMode.LIGHT) {
            return Neu.drawable(activeNeuTokens, dp(15).toFloat(), NeuLevel.PRESSED_SM)
        }
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(activeNeuTokens.baseLo, activeNeuTokens.base, activeNeuTokens.baseHi)
        ).apply {
            cornerRadius = dp(15).toFloat()
            setStroke(dp(1), adjustAlpha(activeNeuTokens.baseHi, 0.16f))
        }
        val wellShade = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(adjustAlpha(Color.BLACK, 0.42f), Color.TRANSPARENT)
        ).apply { cornerRadius = dp(15).toFloat() }
        return LayerDrawable(arrayOf(base, wellShade))
    }

    private fun isWidgetUniversalSearchActive(): Boolean =
        keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET &&
            openPane == null &&
            !libraryOpen &&
            !keyboardSettingsOpen &&
            query.isNotBlank()

    private fun refreshWidgetSearchContent() {
        val area = widgetSearchContentArea ?: return
        area.removeAllViews()
        val content = searchResultsList(widgetMode = true).apply {
            alpha = 0f
            translationY = dp(52).toFloat()
            animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(400)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        area.addView(content, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }

    private fun searchResultsGrid(): View = ScrollView(this).apply {
        val results = universalSearchResults()
        val command = searchCommandPreview()
        val aiInline = searchAiInlineState()
        if (results.isEmpty() && command == null && aiInline == null) {
            addView(TextView(context).apply {
                text = "No results for \"$query\""
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(activeNeuTokens.inkDim)
                includeFontPadding = false
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))
            return@apply
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(10))
            command?.let {
                addView(searchCommandCard(it), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68)).apply {
                    bottomMargin = dp(10)
                })
            }
            aiInline?.let {
                addView(searchAiAnswerCard(it), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                })
            }
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
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(120)).apply {
                    bottomMargin = dp(10)
                })
            }
        })
    }

    private fun searchResultsList(widgetMode: Boolean = false): View = ScrollView(this).apply {
        clipToPadding = false
        val results = universalSearchResults()
        val command = searchCommandPreview()
        val aiInline = searchAiInlineState()
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, if (widgetMode) dp(10) else dp(12), 0, if (widgetMode) dp(18) else dp(10))
            addView(mono(if (widgetMode) "UNIVERSAL SEARCH" else "RESULTS", 8.5f, activeNeuTokens.inkFaint).apply {
                letterSpacing = 0.18f
                setPadding(dp(2), 0, 0, dp(8))
            }, LinearLayout.LayoutParams.MATCH_PARENT, dp(22))
            command?.let {
                addView(searchCommandCard(it), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68)).apply {
                    bottomMargin = dp(9)
                })
            }
            aiInline?.let {
                addView(searchAiAnswerCard(it), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(9)
                })
            }
            val visibleResults = if (aiInline != null) results.filterNot { it.kind == SearchKind.AI && it.title == "Ask Gemini" } else results
            if (visibleResults.isEmpty() && command == null && aiInline == null) {
                addView(TextView(context).apply {
                    text = "No results for \"$query\""
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(activeNeuTokens.inkDim)
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)))
            }
            visibleResults.forEachIndexed { index, result ->
                addView(searchResultRow(result, index == 0 && command == null && aiInline == null, index), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(66)).apply {
                    bottomMargin = dp(8)
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
            background = searchCardBackground(result.kind, isBest, 20)
            postDelayed({
                animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            }, (index * 28L).coerceAtMost(240L))
            addView(searchResultIcon(result), LinearLayout.LayoutParams(dp(if (result.kind == SearchKind.APP) 46 else 38), dp(if (result.kind == SearchKind.APP) 46 else 38)))
            addView(TextView(context).apply {
                text = highlightedLabel(result.title, query)
                textSize = if (result.kind == SearchKind.APP) 12.5f else 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(activeNeuTokens.ink)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
                setPadding(0, dp(8), 0, 0)
            }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (result.kind != SearchKind.APP) {
                addView(mono(result.subtitle.uppercase(Locale.US), 8f, activeNeuTokens.inkFaint).apply {
                    letterSpacing = 0.06f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            addView(searchKindTag(result.kind), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(19)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(5)
            })
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
            setPadding(dp(9), dp(8), dp(9), dp(8))
            background = searchCardBackground(result.kind, isBest, 15)
            postDelayed({
                animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            }, (index * 28L).coerceAtMost(240L))
            addView(searchResultIcon(result), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(11) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = highlightedLabel(result.title, query)
                    textSize = 13.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(activeNeuTokens.ink)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    includeFontPadding = false
                }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(mono(result.subtitle.uppercase(Locale.US), 9f, activeNeuTokens.inkFaint).apply {
                    letterSpacing = 0.06f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(5), 0, 0)
                }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(searchKindTag(result.kind), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22)).apply {
                marginStart = dp(8)
            })
            setOnClickListener { haptic(this); openSearchResult(result) }
        }
    }

    private fun searchResultIcon(result: SearchResult): View {
        val app = result.target?.packageName?.let { pkg -> apps.firstOrNull { it.packageName == pkg } }
        return FrameLayout(this).apply {
            background = if (result.kind == SearchKind.APP && app != null) {
                libraryIconButtonBackground(12, Line)
            } else {
                Neu.drawable(activeNeuTokens, dp(12).toFloat(), NeuLevel.PRESSED_SM)
            }
            setPadding(dp(5), dp(5), dp(5), dp(5))
            if (result.kind == SearchKind.APP && app != null) {
                addView(ImageView(context).apply {
                    setImageDrawable(iconFor(app.toLibraryApp()))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            } else {
                addView(TextView(context).apply {
                    text = searchKindGlyph(result.kind)
                    gravity = Gravity.CENTER
                    textSize = if (result.kind == SearchKind.AI) 11f else 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(searchKindAccent(result.kind))
                    background = Neu.drawable(activeNeuTokens, dp(9).toFloat(), NeuLevel.RAISED_SM)
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        }
    }

    private fun searchCardBackground(kind: SearchKind, isBest: Boolean, radiusDp: Int): Drawable {
        val solid = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (activeNeuTokens.mode == NeuMode.LIGHT) 0xFFF1F3F6.toInt() else activeNeuTokens.baseHi,
            if (activeNeuTokens.mode == NeuMode.LIGHT) 0xFFE8EBF0.toInt() else activeNeuTokens.base,
            if (activeNeuTokens.mode == NeuMode.LIGHT) 0xFFDDE2E9.toInt() else activeNeuTokens.base
        )).apply {
            cornerRadius = dp(radiusDp).toFloat()
        }
        val base = Neu.drawable(activeNeuTokens, dp(radiusDp).toFloat(), if (isBest) NeuLevel.RAISED else NeuLevel.RAISED_SM)
        if (!isBest) return LayerDrawable(arrayOf(solid, base))
        val ring = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), searchKindAccent(kind))
        }
        return LayerDrawable(arrayOf(solid, base, ring))
    }

    private fun searchKindTag(kind: SearchKind): TextView = mono(kind.name, 7.5f, activeNeuTokens.inkFaint).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.1f
        setPadding(dp(7), 0, dp(7), 0)
        background = Neu.drawable(activeNeuTokens, dp(9).toFloat(), NeuLevel.PRESSED_SM)
    }

    private fun searchKindAccent(kind: SearchKind): Int = when (kind) {
        SearchKind.APP -> Neu.BLUE
        SearchKind.CONTACT -> Neu.ORANGE
        SearchKind.EMAIL -> Neu.ACCENT
        SearchKind.MESSAGE -> Neu.TEAL
        SearchKind.CALENDAR -> Neu.AMBER
        SearchKind.TRAVEL -> Neu.PURPLE
        SearchKind.AI -> Neu.PURPLE
    }

    private fun searchKindGlyph(kind: SearchKind): String = when (kind) {
        SearchKind.CONTACT -> "P"
        SearchKind.EMAIL -> "@"
        SearchKind.MESSAGE -> "M"
        SearchKind.CALENDAR -> "C"
        SearchKind.AI -> "AI"
        SearchKind.APP -> "A"
        SearchKind.TRAVEL -> "✈"
    }

    private data class SearchCommandPreview(val title: String, val subtitle: String, val glyph: String)

    private fun searchCommandPreview(): SearchCommandPreview? {
        val clean = query.trim()
        if (clean.isBlank()) return null
        val verb = clean.substringBefore(' ').lowercase(Locale.US)
        val body = clean.substringAfter(' ', "").trim()
        if (body.isBlank() && verb !in listOf("ask", "ai", "gemini")) return null
        return when (verb) {
            "text", "sms", "message" -> SearchCommandPreview("Message ${body.substringBefore(' ')}", "SEND MESSAGE · MESSAGES", "➤")
            "email", "mail" -> SearchCommandPreview("Email ${body.substringBefore(' ')}", "COMPOSE EMAIL", "➤")
            "call" -> SearchCommandPreview("Call $body", "START CALL · PHONE", "✆")
            "calendar", "schedule" -> SearchCommandPreview(body.ifBlank { "Create event" }, "CREATE EVENT · CALENDAR", "＋")
            "open", "launch" -> SearchCommandPreview("Open $body", "OPEN APP", "➤")
            "play" -> SearchCommandPreview("Play $body", "START MUSIC SEARCH", "▶")
            "search", "google", "web" -> SearchCommandPreview(body.ifBlank { clean }, "SEARCH GOOGLE IN CLICKS", "G")
            "ask", "ai", "gemini" -> SearchCommandPreview(body.ifBlank { clean }, "ASK CLICKS AI", "AI")
            else -> null
        }
    }

    private fun searchCommandCard(command: SearchCommandPreview): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(dp(10), dp(8), dp(12), dp(8))
            background = searchCommandBackground()
            addView(TextView(context).apply {
                text = command.glyph
                gravity = Gravity.CENTER
                textSize = if (command.glyph == "AI") 12f else 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Neu.GREEN)
                background = Neu.drawable(activeNeuTokens, dp(12).toFloat(), NeuLevel.RAISED_SM)
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(11) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = command.title
                    textSize = 14f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(activeNeuTokens.ink)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    includeFontPadding = false
                }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(mono(command.subtitle, 9f, Neu.GREEN).apply {
                    letterSpacing = 0.08f
                    setPadding(0, dp(5), 0, 0)
                }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(searchKindTag(SearchKind.AI).apply {
                text = "DO THIS"
                setTextColor(Neu.GREEN)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22)).apply { marginStart = dp(8) })
            setOnClickListener {
                haptic(this)
                if (executeTypeToDoCommand(query)) {
                    query = ""
                    if (libraryOpen) refreshLibraryContent() else render()
                }
            }
        }
    }

    private fun searchCommandBackground(): Drawable {
        val solid = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (activeNeuTokens.mode == NeuMode.LIGHT) 0xFFF1F3F6.toInt() else activeNeuTokens.baseHi,
            if (activeNeuTokens.mode == NeuMode.LIGHT) 0xFFE8EBF0.toInt() else activeNeuTokens.base,
            if (activeNeuTokens.mode == NeuMode.LIGHT) 0xFFDDE2E9.toInt() else activeNeuTokens.base
        )).apply {
            cornerRadius = dp(15).toFloat()
        }
        val base = Neu.drawable(activeNeuTokens, dp(15).toFloat(), NeuLevel.RAISED)
        val ring = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(15).toFloat()
            setStroke(dp(1), Neu.GREEN)
        }
        return LayerDrawable(arrayOf(solid, base, ring))
    }

    private fun searchAiInlineState(): AiAnswerState? {
        val clean = query.trim()
        if (clean.isBlank() || !looksLikeAiQuestion(clean)) return null
        val target = aiTarget(clean)
        return aiAnswersById[target.id] ?: AiAnswerState(clean, "Tap to ask Gemini from Clicks.", false)
    }

    private fun searchAiAnswerCard(state: AiAnswerState): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(12), dp(11), dp(12), dp(12))
            background = searchCardBackground(SearchKind.AI, true, 16)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "C"
                    gravity = Gravity.CENTER
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Neu.PURPLE)
                    background = Neu.drawable(activeNeuTokens, dp(9).toFloat(), NeuLevel.RAISED_SM)
                }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(9) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = "Clicks AI"
                        textSize = 12f
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        setTextColor(activeNeuTokens.ink)
                        includeFontPadding = false
                    }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    addView(mono(if (state.loading) "GEMINI · THINKING" else "GEMINI", 8f, Neu.PURPLE).apply {
                        letterSpacing = 0.12f
                        setPadding(0, dp(3), 0, 0)
                    }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
            addView(TextView(context).apply {
                text = state.prompt
                textSize = 11f
                setTextColor(activeNeuTokens.inkDim)
                includeFontPadding = false
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = Neu.drawable(activeNeuTokens, dp(11).toFloat(), NeuLevel.PRESSED_SM)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(context).apply {
                text = state.answer
                textSize = 12f
                setTextColor(activeNeuTokens.ink)
                includeFontPadding = false
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = Neu.drawable(activeNeuTokens, dp(11).toFloat(), NeuLevel.PRESSED_SM)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            setOnClickListener {
                haptic(this)
                askGemini(query)
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
            val iconFrame = searchAppIconFrameSize()
            addView(FrameLayout(context).apply {
                elevation = dp(if (isBest) 8 else 3).toFloat()
                background = libraryIconButtonBackground(15, if (isBest) Accent else Line)
                addView(ImageView(context).apply {
                    setImageDrawable(iconFor(app))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    setPadding(appIconInnerPadding(), appIconInnerPadding(), appIconInnerPadding(), appIconInnerPadding())
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(iconFrame, iconFrame))
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
        val maxLaunches = maxLaunchesForCategory(items)
        items.chunked(columns).forEachIndexed { rowIndex, rowItems ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                rowItems.forEach { app ->
                    addView(FrameLayout(context).apply {
                        elevation = dp(2).toFloat()
                        background = libraryIconButtonBackground(9, Line)
                        addView(ImageView(context).apply {
                            setImageDrawable(iconFor(app))
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            adjustViewBounds = true
                            setPadding(dp(4), dp(4), dp(4), dp(4))
                            applyCategoryUsageScale(app, maxLaunches, null, this)
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
        val (activeApps, archivedApps) = splitCategoryApps(category.apps)
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
                        addView(categoryTileGrid(activeApps, 4))
                        if (archivedApps.isNotEmpty()) {
                            addView(categoryArchiveSection(archivedApps), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                topMargin = dp(14)
                            })
                        }
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

    private fun categoryTileGrid(items: List<LibraryApp>, columns: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val maxLaunches = maxLaunchesForCategory(items)
        items.chunked(columns).forEach { rowItems ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                rowItems.forEach { app ->
                    addView(categoryAppTile(app, maxLaunches), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                }
                repeat(columns - rowItems.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, appTileRowHeight()).apply {
                bottomMargin = dp(8)
            })
        }
    }

    private fun categoryAppTile(app: LibraryApp, maxLaunches: Int): View {
        val target = app.target
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(3), dp(4), dp(3), dp(2))
            val iconFrame = appLibraryIconFrameSize()
            val labelView = TextView(context).apply {
                text = app.name
                textSize = 10.5f
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(activeNeuTokens.inkDim)
                includeFontPadding = false
                setPadding(0, dp(6), 0, 0)
            }
            addView(FrameLayout(context).apply {
                elevation = dp(3).toFloat()
                background = libraryIconButtonBackground(13, Line)
                addView(ImageView(context).apply {
                    setImageDrawable(iconFor(app))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    setPadding(appIconInnerPadding(), appIconInnerPadding(), appIconInnerPadding(), appIconInnerPadding())
                    applyCategoryUsageScale(app, maxLaunches, labelView, this)
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(iconFrame, iconFrame))
            addView(labelView, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
                false
            }
            setOnClickListener {
                haptic(this)
                val opened = if (target.kind == PaneKind.MUSIC || target.packageName == null) {
                    openHere(target)
                    true
                } else {
                    openExternal(target)
                }
                if (opened) {
                    closeCategoryFolder()
                    libraryOpen = false
                    libraryView?.let { contentFrame.removeView(it) }
                    libraryView = null
                    libraryContentArea = null
                }
            }
            setOnLongClickListener { haptic(this); showIconMenu(this, app); true }
        }
    }

    private fun categoryArchiveSection(archivedApps: List<LibraryApp>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val archivedGrid = categoryTileGrid(archivedApps, 4).apply { visibility = View.GONE }
        val chevron = TextView(context).apply {
            text = "+"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(activeNeuTokens.inkDim)
            includeFontPadding = false
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(dp(12), 0, dp(10), 0)
            background = Neu.drawable(activeNeuTokens, dp(18).toFloat(), NeuLevel.PRESSED_SM)
            addView(TextView(context).apply {
                text = "UNUSED APPS"
                textSize = 10f
                letterSpacing = 0.16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(activeNeuTokens.inkDim)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = archivedApps.size.toString()
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(activeNeuTokens.inkFaint)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(chevron, LinearLayout.LayoutParams(dp(26), dp(28)))
            setOnClickListener {
                haptic(this)
                val opening = archivedGrid.visibility != View.VISIBLE
                archivedGrid.visibility = if (opening) View.VISIBLE else View.GONE
                chevron.text = if (opening) "-" else "+"
            }
        }
        addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
        addView(archivedGrid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
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
        val plainGrid = this
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(8))
            addView(tileGrid(apps.map { it.toLibraryApp() }, 4, gridPhysics = true))
        })
    }.let { plainScroll ->
        FrameLayout(this).apply {
            val liquid = LiquidGridBackdropView(context).apply {
                updateTokens(activeNeuTokens)
            }
            val blur = View(context).apply {
                alpha = if (libraryOpen) 0.72f else 0f
                background = libraryGridFrostBackground()
            }
            val scroll = GridFishEyeScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                addView((plainScroll as ScrollView).getChildAt(0).also { plainScroll.removeView(it) })
                setPadding(0, 0, 0, dp(8))
            }
            libraryGridLiquidBackdrop = liquid
            libraryGridBlurView = blur
            libraryGridScrollView = scroll
            addView(liquid, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(blur, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(scroll, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            post {
                val progress = if (libraryOpen) 1f else 0f
                updateLibraryGridDragEffects(progress, width * 0.5f)
                scroll.applyFishEye()
            }
        }
    }

    private fun tileGrid(items: List<LibraryApp>, columns: Int, gridPhysics: Boolean = false): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        var tileIndex = 0
        items.chunked(columns).forEach { rowItems ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                rowItems.forEach { app ->
                    addView(appTile(app, gridPhysics, tileIndex++), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                }
                repeat(columns - rowItems.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, appTileRowHeight()).apply {
                bottomMargin = dp(if (libraryGridMode) 20 else 4)
            })
        }
    }

    private fun appTile(app: LibraryApp, gridPhysics: Boolean = false, index: Int = 0): View {
        val target = app.target
        return LinearLayout(this).apply {
            if (gridPhysics) tag = GRID_APP_TILE_TAG
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true
            setPadding(dp(3), dp(4), dp(3), dp(2))
            if (gridPhysics) {
                alpha = 0f
                translationY = dp(16).toFloat()
                postDelayed({
                    animate().alpha(1f).translationY(0f).setDuration(210).setInterpolator(DecelerateInterpolator(1.7f)).start()
                }, (index * 10L).coerceAtMost(220L))
            }
            val iconFrame = appLibraryIconFrameSize()
            addView(FrameLayout(context).apply {
                elevation = dp(3).toFloat(); background = libraryIconButtonBackground(13, Line)
                addView(ImageView(context).apply {
                    setImageDrawable(iconFor(app)); scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true; setPadding(appIconInnerPadding(), appIconInnerPadding(), appIconInnerPadding(), appIconInnerPadding())
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(iconFrame, iconFrame))
            addView(TextView(context).apply {
                text = app.name; textSize = 10.5f; gravity = Gravity.CENTER; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(activeNeuTokens.inkDim); includeFontPadding = false; setPadding(0, dp(6), 0, 0)
            }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
                false
            }
            setOnClickListener {
                haptic(this)
                val opened = if (target.kind == PaneKind.MUSIC || target.packageName == null) {
                    openHere(target)
                    true
                } else {
                    openExternal(target)
                }
                if (opened) {
                    libraryOpen = false
                    libraryView?.let { contentFrame.removeView(it) }
                    libraryView = null
                    libraryContentArea = null
                }
            }
            setOnLongClickListener { haptic(this); showIconMenu(this, app); true }
        }
    }

    private inner class LiquidGridBackdropView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
        }
        private val path = Path()
        private var progress = 0f
        private var pullX = 0f
        private var tokens = activeNeuTokens

        init {
            setWillNotDraw(false)
        }

        fun updateTokens(next: NeuTokens) {
            tokens = next
            invalidate()
        }

        fun updateDragProgress(nextProgress: Float, touchX: Float) {
            progress = nextProgress.coerceIn(0f, 1f)
            pullX = touchX.takeIf { it > 0f } ?: width * 0.5f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0 || progress <= 0f) return
            val w = width.toFloat()
            val h = height.toFloat()
            val top = h * (1f - progress)
            val stretch = dp(72).toFloat() * progress * (1f - progress * 0.22f)
            path.reset()
            path.moveTo(0f, h)
            path.lineTo(0f, top)
            path.cubicTo(
                pullX * 0.42f,
                top,
                pullX,
                top - stretch,
                pullX + (w - pullX) * 0.58f,
                top
            )
            path.lineTo(w, top)
            path.lineTo(w, h)
            path.close()
            paint.shader = android.graphics.LinearGradient(
                0f,
                top,
                0f,
                h,
                intArrayOf(
                    adjustAlpha(tokens.baseHi, if (tokens.mode == NeuMode.LIGHT) 0.88f else 0.62f),
                    adjustAlpha(tokens.base, if (tokens.mode == NeuMode.LIGHT) 0.94f else 0.78f),
                    adjustAlpha(tokens.baseLo, if (tokens.mode == NeuMode.LIGHT) 0.78f else 0.9f)
                ),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(path, paint)
            paint.shader = null
            rimPaint.color = adjustAlpha(if (tokens.mode == NeuMode.LIGHT) Color.WHITE else tokens.baseHi, if (tokens.mode == NeuMode.LIGHT) 0.42f else 0.18f)
            canvas.drawPath(path, rimPaint)
        }
    }

    private inner class GridFishEyeScrollView(context: Context) : ScrollView(context) {
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            applyFishEye()
        }

        fun applyFishEye() {
            val midY = height / 2f
            val radius = (height * 0.62f).coerceAtLeast(dp(1).toFloat())
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            applyFishEyeToChildren(this, loc[1].toFloat(), midY, radius)
        }

        private fun applyFishEyeToChildren(view: View, originY: Float, midY: Float, radius: Float) {
            if (view.tag == GRID_APP_TILE_TAG) {
                val childLoc = IntArray(2)
                view.getLocationOnScreen(childLoc)
                val childMid = childLoc[1] - originY + view.height / 2f
                val distance = abs(midY - childMid)
                val wave = if (distance < radius) {
                    cos((distance / radius) * (Math.PI / 2.0)).toFloat().coerceIn(0f, 1f)
                } else {
                    0f
                }
                val scale = 0.91f + wave * 0.105f
                view.scaleX = scale
                view.scaleY = scale
                view.translationX = dp(8).toFloat() * wave
                view.alpha = 0.74f + wave * 0.26f
                view.translationZ = dp(4).toFloat() * wave
                return
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) applyFishEyeToChildren(view.getChildAt(i), originY, midY, radius)
            }
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
            PaneKind.MUSIC -> when {
                musicTheme() == MUSIC_THEME_BLACK -> musicBlackDock()
                // Click wheel is a Pro feature and only applies to a connected service
                // whose account matches what's actually playing. Everything else
                // (YouTube Music, podcasts, browser audio…) gets simple transport controls.
                activeMusicMode() == MusicMode.SIMPLE -> simpleTransportDock()
                else -> musicDockView()
            }
            PaneKind.PHOTOS -> photoAlbumsDock()
            else -> keyboard()
        }
    }

    private enum class MusicMode { SPOTIFY_FULL, APPLE_FULL, SIMPLE }

    // Decides whether the full click-wheel experience applies. It requires Pro AND that the
    // active media source matches a connected service. If the user is playing from anything
    // that isn't their connected account, we fall back to a plain now-playing screen.
    private fun activeMusicMode(): MusicMode {
        if (!ProManager.isUnlocked(this)) return MusicMode.SIMPLE
        val src = if (::mediaSessionSource.isInitialized) mediaSessionSource.nowPlaying.value?.sourcePackage else null
        val fromSpotify = src?.contains("spotify", ignoreCase = true) == true
        val fromApple = src?.contains("apple", ignoreCase = true) == true
        return when {
            // Spotify connected → full wheel when playing from Spotify, or when nothing is
            // playing (so the wheel can still browse the connected library).
            spotifyAuth.isConnected && (src == null || fromSpotify) -> MusicMode.SPOTIFY_FULL
            appleMusicConnected() && fromApple -> MusicMode.APPLE_FULL
            else -> MusicMode.SIMPLE
        }
    }

    // Apple Music account integration isn't built yet — placeholder so the branch above is
    // ready the moment an Apple auth/library layer lands.
    private fun appleMusicConnected(): Boolean = false

    // Plain now-playing dock: source label + standard prev/play/next controls, no click wheel.
    private fun simpleTransportDock(): View {
        val info = if (::mediaSessionSource.isInitialized) mediaSessionSource.nowPlaying.value else null
        val playing = info?.isPlaying == true
        return FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                0xFF101216.toInt(), 0xFF050506.toInt(), 0xFF000000.toInt()
            )).apply { setStroke(dp(1), 0xFF20232A.toInt()) }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(TextView(context).apply {
                    text = (info?.sourceApp ?: "Not playing").uppercase(Locale.US)
                    textSize = 10f
                    letterSpacing = 0.14f
                    setTextColor(0xFF6B7280.toInt())
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    addView(musicBlackTransportButton("◀", accent = false) {
                        haptic(this); mediaSessionSource.skipToPrevious()
                    }, LinearLayout.LayoutParams(dp(60), dp(60)).apply { marginEnd = dp(22) })
                    addView(musicBlackTransportButton(if (playing) "Ⅱ" else "▶", accent = true) {
                        haptic(this); mediaSessionSource.togglePlayPause()
                    }, LinearLayout.LayoutParams(dp(76), dp(76)))
                    addView(musicBlackTransportButton("▶", accent = false) {
                        haptic(this); mediaSessionSource.skipToNext()
                    }, LinearLayout.LayoutParams(dp(60), dp(60)).apply { marginStart = dp(22) })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
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
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            background = keyboardDeckBackground()
            setPadding(dp(7), keyboardTopPadding(), dp(7), keyboardBottomPadding())

            if (keyboardSettingsOpen) addView(keyboardSettings(), LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (showKeyboardTypingWell()) {
                addView(typingStripView(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, keyboardTypingWellHeight()).apply {
                    bottomMargin = dp(2)
                })
            }
            if (symbolsOpen) {
                addKeyRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
                addKeyRow(listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/"))
                addKeyRow(listOf("*", "\"", "'", ":", ";", "!", "?", ",", "back"), dp(8))
                // FEATURE (disabled): strip just above the space row — thumb-height, closer to the
                // fingers. Uncomment this (and remove the top placement above) to move it down.
                // if (showSuggestionStrip()) addView(suggestionStrip(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
                addKeyRow(listOf("abc", "clicks", "space", "period", "enter"), dp(15))
            } else if (numberPadOpen) {
                addKeyRow(listOf("1", "2", "3"))
                addKeyRow(listOf("4", "5", "6"))
                addKeyRow(listOf("7", "8", "9"))
                addKeyRow(listOf("abc", "0", "back", "enter"))
            } else {
                addKeyRow("qwertyuiop".map { it.toString() })
                addKeyRow("asdfghjkl".map { it.toString() }, dp(18))
                addKeyRow(listOf("shift") + "zxcvbnm".map { it.toString() } + listOf("back"), dp(8))
                // FEATURE (disabled): strip just above the space row — thumb-height. See note above.
                // if (showSuggestionStrip()) addView(suggestionStrip(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
                addKeyRow(listOf("123", "clicks", "space", "period", "enter"), dp(15))
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
            addView(launcherThemeModeSelector(), LinearLayout.LayoutParams.MATCH_PARENT, dp(38))
            addView(keyboardLanguageSelector(), LinearLayout.LayoutParams.MATCH_PARENT, dp(38))

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

            addView(settingToggle("TILT LIGHTING", keyboardTiltLighting) {
                keyboardTiltLighting = !keyboardTiltLighting
                prefs().edit().putBoolean(KBD_TILT_LIGHT_PREF, keyboardTiltLighting).apply()
                haptic(this); render()
            }, LinearLayout.LayoutParams.MATCH_PARENT, dp(30))

            addView(settingAction("AGENTIC SKILLS") {
                keyboardSettingsOpen = false
                startActivity(android.content.Intent(this@MainActivity, AgenticSkillsActivity::class.java))
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
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    listOf(
                        "DEFAULT" to KEYBOARD_THEME_DEFAULT,
                        "CLICKS" to KEYBOARD_THEME_CLICKS,
                        "SKEUO" to KEYBOARD_THEME_SKEUO,
                        "GOKEYS" to KEYBOARD_THEME_GOKEYS,
                        "HYPER" to KEYBOARD_THEME_HYPER3D,
                        "BLACK" to KEYBOARD_THEME_HYPER3D_BLACK,
                        "LIGHT" to KEYBOARD_THEME_HYPER3D_LIGHT
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
                        }, LinearLayout.LayoutParams(dp(68), dp(28)).apply { marginStart = dp(6) })
                    }
                }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(0, dp(32), 1f))
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

    // Multi-language without switching: toggle which bundled languages are merged into the union
    // dictionary. Selecting several lets you type in all of them with no language key. Empty = auto
    // (follow the system locales). Applies to both the launcher keyboard and the IME.
    private fun keyboardLanguageSelector(): View {
        val labels = listOf("en" to "EN", "es" to "ES", "fr" to "FR", "de" to "DE", "pt" to "PT", "it" to "IT")
        val selected = com.fran.clicks.keyboard.DictionaryLoader.enabledLanguages(this).toSet()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(mono("LANGS", 9f, InkDim).apply { letterSpacing = 0.12f },
                LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    labels.forEach { (code, label) ->
                        val on = code in selected
                        addView(TextView(context).apply {
                            text = label
                            gravity = Gravity.CENTER
                            textSize = 9.2f
                            letterSpacing = 0.08f
                            typeface = Typeface.MONOSPACE
                            setTextColor(if (on) Ink else InkDim)
                            background = if (on) keyboardThemePillBackground(KEYBOARD_THEME_GOKEYS) else border(Line)
                            isClickable = true
                            setOnClickListener { haptic(this); toggleKeyboardLanguage(code) }
                        }, LinearLayout.LayoutParams(dp(42), dp(28)).apply { marginStart = dp(6) })
                    }
                }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(0, dp(32), 1f))
        }
    }

    private fun toggleKeyboardLanguage(code: String) {
        val order = com.fran.clicks.keyboard.DictionaryLoader.available()
        val cur = com.fran.clicks.keyboard.DictionaryLoader.enabledLanguages(this).toMutableSet()
        if (code in cur) { if (cur.size > 1) cur.remove(code) } else cur.add(code)
        val ordered = order.filter { it in cur }
        prefs().edit().putString(com.fran.clicks.keyboard.DictionaryLoader.LANGUAGES_PREF, ordered.joinToString(",")).apply()
        loadGlideWords()   // rebuild the union dictionary + glide model for the new language set
        render()
    }

    private fun launcherThemeModeSelector(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(mono("LOOK", 9f, activeNeuTokens.inkDim).apply { letterSpacing = 0.12f },
                LinearLayout.LayoutParams(dp(54), ViewGroup.LayoutParams.WRAP_CONTENT))
            listOf(
                "DARK" to THEME_MODE_DARK,
                "LIGHT" to THEME_MODE_LIGHT,
                "SYSTEM" to THEME_MODE_SYSTEM
            ).forEach { (label, value) ->
                addView(TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 9.2f
                    letterSpacing = 0.08f
                    typeface = Typeface.MONOSPACE
                    setTextColor(if (themeMode == value) activeNeuTokens.ink else activeNeuTokens.inkDim)
                    background = if (themeMode == value) {
                        Neu.drawable(activeNeuTokens, dp(10).toFloat(), NeuLevel.PRESSED_SM)
                    } else {
                        Neu.drawable(activeNeuTokens, dp(10).toFloat(), NeuLevel.RAISED_SM)
                    }
                    isClickable = true
                    setOnClickListener {
                        haptic(this)
                        themeMode = value
                        prefs().edit().putString(THEME_MODE_PREF, value).apply()
                        updateLauncherTheme(animated = true, forceRender = true)
                    }
                }, LinearLayout.LayoutParams(0, dp(28), 1f).apply { marginStart = dp(6) })
            }
        }
    }

    private fun setKeyboardPlacement(value: String) {
        val safe = if (value == KEYBOARD_PLACEMENT_WIDGET) KEYBOARD_PLACEMENT_WIDGET else KEYBOARD_PLACEMENT_DOCKED
        keyboardPlacement = safe
        keyboardSettingsOpen = false
        KeyboardSettings.setPlacementMode(this, safe)
        if (safe == KEYBOARD_PLACEMENT_WIDGET) {
            VivoDockedExperiment.clearViewportTruncation(this)
            stopService(Intent(this, DockedKeyboardService::class.java))
        } else if (VivoDockedExperiment.isEnabled(this)) {
            VivoDockedExperiment.applyViewportTruncation(this)
            if (Settings.canDrawOverlays(this)) {
                startService(Intent(this, DockedKeyboardService::class.java))
            }
        }
        render()
    }

    private fun beginDockedKeyboardPopToWidget(anchor: View) {
        if (keyboardPlacement != KEYBOARD_PLACEMENT_DOCKED || openPane != null || libraryOpen) return
        haptic(anchor)
        keyboardSettingsOpen = false
        query = ""
        val dock = if (::keyboardDockView.isInitialized && keyboardDockView.parent != null) keyboardDockView else null
        if (dock == null) {
            pendingWidgetKeyboardPopIn = true
            setKeyboardPlacement(KEYBOARD_PLACEMENT_WIDGET)
            return
        }
        dock.animate()
            .translationY(dp(54).toFloat())
            .scaleX(0.965f)
            .scaleY(0.92f)
            .alpha(0.42f)
            .setDuration(150L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                pendingWidgetKeyboardPopIn = true
                keyboardPlacement = KEYBOARD_PLACEMENT_WIDGET
                prefs().edit().putString(KEYBOARD_PLACEMENT_PREF, KEYBOARD_PLACEMENT_WIDGET).apply()
                render()
            }
            .start()
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

    private fun devExperimentsEnabled(): Boolean {
        return applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            prefs().getBoolean(DEV_EXPERIMENTS_PREF, false)
    }

    private fun vivoDockedExperimentSelector(): View {
        val current = VivoDockedExperiment.currentMode(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(mono("VIVO EXP", 9f, activeNeuTokens.inkDim).apply { letterSpacing = 0.12f },
                LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT))
            listOf(
                "OFF" to VivoDockedExperiment.MODE_OFF,
                "WM" to VivoDockedExperiment.MODE_WM_SIZE,
                "OVER" to VivoDockedExperiment.MODE_OVERSCAN
            ).forEach { (label, value) ->
                addView(TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 9.2f
                    letterSpacing = 0.08f
                    typeface = Typeface.MONOSPACE
                    setTextColor(if (current == value) activeNeuTokens.ink else activeNeuTokens.inkDim)
                    background = if (current == value) {
                        Neu.drawable(activeNeuTokens, dp(10).toFloat(), NeuLevel.PRESSED_SM)
                    } else {
                        Neu.drawable(activeNeuTokens, dp(10).toFloat(), NeuLevel.RAISED_SM)
                    }
                    isClickable = true
                    setOnClickListener {
                        haptic(this)
                        VivoDockedExperiment.clearViewportTruncation(this@MainActivity)
                        VivoDockedExperiment.setMode(this@MainActivity, value)
                        if (value != VivoDockedExperiment.MODE_OFF && keyboardPlacement == KEYBOARD_PLACEMENT_DOCKED) {
                            VivoDockedExperiment.applyViewportTruncation(this@MainActivity)
                        }
                        renderPaneContent(clicksSettingsTarget())
                    }
                }, LinearLayout.LayoutParams(0, dp(28), 1f).apply { marginStart = dp(6) })
            }
        }
    }

    private fun LinearLayout.addKeyRow(labels: List<String>, horizontalInset: Int = 0) {
        val hasPriorKeyRow = (0 until childCount).any { getChildAt(it).tag == "key_row" }
        val row = LinearLayout(context).apply {
            tag = "key_row"
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(horizontalInset, 0, horizontalInset, 0)
            labels.forEach { label ->
                val weight = when (label) {
                    "space" -> 3.65f
                    "enter" -> if (numberPadOpen) 1f else 0.82f
                    "period" -> 0.86f
                    "clicks" -> 1.55f
                    "123" -> 1.02f
                    "back", "shift" -> 1.02f
                    "abc" -> 1.02f
                    else -> 1f
                }
                if (label == "enter") {
                    addView(key(label), LinearLayout.LayoutParams(themedGoKeySize(), themedGoKeySize()).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        marginStart = dp(4)
                    })
                } else if (label == "123") {
                    addView(key(label), LinearLayout.LayoutParams(themedGoKeySize(), themedGoKeySize()).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        marginEnd = dp(4)
                    })
                } else {
                    // Touch target fills the full cell — no horizontal margins — so there are no
                    // dead gaps between keys. The visual gap is drawn by the InsetDrawable in key().
                    addView(key(label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight))
                }
            }
        }
        val rowParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, keyRowHeight()).apply {
            if (hasPriorKeyRow && !keyboardSettingsOpen) topMargin = -keyRowOverlap()
        }
        addView(row, rowParams)
    }

    private fun key(label: String): TextView {
        val isLetter = label.length == 1 && label[0].isLetter()
        val isWidgetDockKey = label == "clicks" && keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET
        val isDockedClicksKey = label == "clicks" && keyboardPlacement == KEYBOARD_PLACEMENT_DOCKED
        return (if (isWidgetDockKey) DockKeyView(this) else if (label == "space") SpaceKeyView(this) else if (isLetter) DynamicFlickKeyView(this) else TextView(this)).apply {
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
            val hInset = keyHorizontalInset()
            val needsInset = label != "enter" && label != "123"
            fun idleBg() = if (needsInset) android.graphics.drawable.InsetDrawable(keyIdleBackground(label), hInset, vInset, hInset, vInset) else keyIdleBackground(label)
            fun pressedBg() = if (needsInset) android.graphics.drawable.InsetDrawable(keyPressedBackground(label), hInset, vInset, hInset, vInset) else keyPressedBackground(label)
            background = idleBg()
            if (keyboardTheme != KEYBOARD_THEME_DEFAULT) {
                elevation = dp(if (keyboardTheme == KEYBOARD_THEME_SKEUO) 5 else 3).toFloat()
                stateListAnimator = null
                cameraDistance = 9000f
            }

            var touchDownX = 0f; var touchDownY = 0f
            var spaceCursorLastX = 0f
            var spaceCursorMoved = false
            val cursorStepPx = dp(8).toFloat()
            // Manual long-press: the OnTouchListener consumes events (returns true), so the
            // View's built-in long-click detection never runs. Detect it ourselves.
            val longPressAction: (() -> Unit)? = when {
                isWidgetDockKey -> { -> beginWidgetKeyboardDetach(this@apply) }
                isDockedClicksKey -> { -> beginDockedKeyboardPopToWidget(this@apply) }
                // Hold go/enter to run the typed line as an agentic command (the powerful trigger).
                label == "enter" -> { -> haptic(this@apply); runLauncherAgenticCommand() }
                label == "123" -> { -> haptic(this@apply); symbolsOpen = true; numberPadOpen = false; render() }
                // Space keeps cursor-drag; a stationary hold now offers Gemini smart-compose instead.
                label == "space" -> { -> haptic(this@apply); triggerGeminiSmartCompose() }
                isLetter -> { -> haptic(this@apply); handleLetterLongPress(label.lowercase(Locale.US)) }
                else -> null
            }
            val longPressHandler = if (longPressAction != null) android.os.Handler(android.os.Looper.getMainLooper()) else null
            var longPressRunnable: Runnable? = null
            var longPressFired = false
            val touchSlop = android.view.ViewConfiguration.get(this@MainActivity).scaledTouchSlop
            fun cancelLongPress() {
                longPressRunnable?.let { longPressHandler?.removeCallbacks(it) }
                longPressRunnable = null
            }
            setOnTouchListener { v, event ->
                if (widgetSwapState != WidgetKeyboardSwapState.SEATED && keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET) {
                    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        v.background = idleBg()
                        if (isWidgetDockKey) (v as? DockKeyView)?.cancelHoldProgress()
                        cancelLongPress()
                        if (label == "back") stopDeleteRepeat(clearFired = true)
                        spaceCursorMoved = false
                    }
                    return@setOnTouchListener handleWidgetKeyboardDetachedTouch(event)
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        touchDownX = event.x; touchDownY = event.y
                        spaceCursorLastX = event.rawX; spaceCursorMoved = false
                        v.background = pressedBg()
                        KeyPhysicsRegistry.activeSprings[v]?.cancel()
                        if (keyboardTheme != KEYBOARD_THEME_DEFAULT && keyboardTiltLighting) {
                            v.translationY = dp(2).toFloat()
                            v.rotationX = if (label == "space") -1.5f else -3.5f
                            v.scaleX = 0.985f
                            v.scaleY = 0.985f
                        }
                        keyHaptic(label)
                        keyPreviewManager.show(v, label)
                        if (longPressAction != null) {
                            longPressFired = false
                            val r = Runnable { longPressFired = true; longPressAction() }
                            longPressRunnable = r
                            val delay = if (isWidgetDockKey) 650L else android.view.ViewConfiguration.getLongPressTimeout().toLong()
                            if (isWidgetDockKey) (v as? DockKeyView)?.startHoldProgress(delay)
                            longPressHandler?.postDelayed(r, delay)
                        }
                        if (label == "back") {
                            startDeleteRepeat()
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (longPressRunnable != null &&
                            (Math.abs(event.x - touchDownX) > touchSlop || Math.abs(event.y - touchDownY) > touchSlop)) {
                            cancelLongPress()
                            if (isWidgetDockKey) (v as? DockKeyView)?.cancelHoldProgress()
                        }
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
                        if (isWidgetDockKey) (v as? DockKeyView)?.cancelHoldProgress()
                        if (keyboardTheme != KEYBOARD_THEME_DEFAULT && keyboardTiltLighting) v.animateSpringReturn(dp(2).toFloat())
                        if (keyboardTiltLighting) v.animate().rotationX(0f).scaleX(1f).scaleY(1f).setDuration(150L).start()
                        if (!keyboardTiltLighting) { v.translationY = 0f; v.rotationX = 0f; v.scaleX = 1f; v.scaleY = 1f }
                        cancelLongPress()
                        if (longPressFired) { longPressFired = false; return@setOnTouchListener true }
                        if (label == "back") {
                            val repeated = deleteRepeatFired
                            stopDeleteRepeat(clearFired = true)
                            if (repeated) return@setOnTouchListener true
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
                        // Fire key action. For letters, snap to the spatially-nearest key so edge /
                        // near-miss taps still land on the intended letter (kills the dead-zone feel)
                        // and feed the tap to the adaptive model so it learns this user's touch bias.
                        if (label == "shift") handleShiftTap() else handleKey(resolveTapKey(label, event.rawX, event.rawY))
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.background = idleBg()
                        if (isWidgetDockKey) (v as? DockKeyView)?.cancelHoldProgress()
                        if (keyboardTheme != KEYBOARD_THEME_DEFAULT && keyboardTiltLighting) v.animateSpringReturn(dp(2).toFloat())
                        if (keyboardTiltLighting) v.animate().rotationX(0f).scaleX(1f).scaleY(1f).setDuration(150L).start()
                        if (!keyboardTiltLighting) { v.translationY = 0f; v.rotationX = 0f; v.scaleX = 1f; v.scaleY = 1f }
                        if (label == "back") stopDeleteRepeat(clearFired = true)
                        spaceCursorMoved = false
                        cancelLongPress(); longPressFired = false
                    }
                }
                true  // consume all key touches so they don't bubble to pane swipe handler
            }
            if (label == "back") {
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = Unit
                    override fun onViewDetachedFromWindow(v: View) {
                        stopDeleteRepeat(clearFired = true)
                    }
                })
            }
        }.also { keyViews[label] = it }
    }

    private fun startDeleteRepeat() {
        stopDeleteRepeat(clearFired = true)
        deleteRepeatActive = true
        val repeat = object : Runnable {
            override fun run() {
                if (!deleteRepeatActive) return
                deleteRepeatFired = true
                handleKey("back")
                if (!deleteRepeatActive) return
                keyHaptic("back")
                deleteRepeatHandler.postDelayed(this, 45L)
            }
        }
        deleteRepeatRunnable = repeat
        deleteRepeatHandler.postDelayed(repeat, 380L)
    }

    private fun stopDeleteRepeat(clearFired: Boolean = false) {
        deleteRepeatRunnable?.let { deleteRepeatHandler.removeCallbacks(it) }
        deleteRepeatRunnable = null
        deleteRepeatActive = false
        if (clearFired) deleteRepeatFired = false
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

    private var suggestionStripView: LinearLayout? = null
    private var launcherPolishing = false
    private var pendingLauncherCommand: AgenticRouter.Command? = null

    private fun showSuggestionStrip() = false
    private fun showKeyboardTypingWell() = !keyboardSettingsOpen
    private fun keyboardTypingWellHeight() = dp(34)
    private fun suggestionStripHeight() = if (showKeyboardTypingWell()) keyboardTypingWellHeight() + dp(2) else 0

    private fun suggestionStrip(): View {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        suggestionStripView = strip
        updateSuggestionBar()
        return strip
    }

    // If a shown suggestion or the current word names an installed app (e.g. "spotify"), return that
    // app's brand color so the strip can tint to it. Prefix matches count once the user is 3+ chars in.
    private fun suggestionStripAppColor(shown: List<String>): Int? {
        val candidates = (shown + currentWordInCompose()).map { it.lowercase(Locale.US) }.filter { it.length >= 3 }
        if (candidates.isEmpty()) return null
        for (app in apps) {
            val label = app.label.lowercase(Locale.US)
            if (candidates.any { it == label || (label.startsWith(it) && it.length >= 3) }) return app.brandColor
        }
        return null
    }

    private fun updateSuggestionBar() {
        val strip = suggestionStripView ?: return
        // AI Polish in flight: hold the strip on a soft "Polishing…" banner until the rewrite lands.
        if (launcherPolishing) {
            strip.removeAllViews()
            strip.background = suggestionStripBackground(null, true)
            strip.addView(TextView(this).apply {
                text = "✨ Polishing…"; gravity = Gravity.CENTER; textSize = 14.5f
                includeFontPadding = false; setTextColor(0xFFCBB4FF.toInt())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            return
        }
        val pendingCmd = pendingLauncherCommand
        if (pendingCmd != null) {
            strip.removeAllViews()
            strip.background = suggestionStripBackground(null, true)
            strip.addView(TextView(this).apply {
                text = pendingCmd.label
                gravity = Gravity.CENTER_VERTICAL; textSize = 14.5f; includeFontPadding = false
                setTextColor(activeNeuTokens.ink); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(10), 0, dp(6), 0)
                isClickable = true
                setOnClickListener { keyHaptic("space"); applyLauncherCommand() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            strip.addView(TextView(this).apply {
                text = "APPLY"; gravity = Gravity.CENTER; textSize = 10.5f; letterSpacing = 0.12f
                setTextColor(0xFFCBB4FF.toInt()); setPadding(dp(12), 0, dp(12), 0)
                background = GradientDrawable().apply { setColor(0x338B5CF6); cornerRadius = dp(9).toFloat() }
                isClickable = true
                setOnClickListener { keyHaptic("space"); applyLauncherCommand() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dp(4) })
            return
        }
        val shown = suggestions.take(3)
        val pro = ProManager.isUnlocked(this)
        val canPolish = launcherPolishAvailable()
        val appColor = suggestionStripAppColor(shown)
        val fieldBlank = (if (openPane?.kind == PaneKind.CHAT) composeText else query).isBlank()
        // Persist: after a glide / word commit there may be nothing new to show, but keep the last
        // strip content (esp. the swipe result) until the user clears the field or fresh ones arrive.
        if (shown.isEmpty() && appColor == null && !canPolish && !fieldBlank && strip.childCount > 0) return
        val wasEmpty = strip.childCount == 0
        strip.removeAllViews()
        if (shown.isEmpty() && appColor == null && !canPolish) { strip.background = null; return }
        strip.background = suggestionStripBackground(appColor, pro)
        if (shown.isNotEmpty()) {
            val textColor = appColor ?: if (pro) 0xFFCBB4FF.toInt() else activeNeuTokens.ink
            shown.forEachIndexed { i, word ->
                strip.addView(TextView(this).apply {
                    text = word
                    gravity = Gravity.CENTER
                    textSize = 14.5f
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(textColor)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    isClickable = true
                    setOnClickListener { keyHaptic("space"); acceptSuggestion(word) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                if (i < shown.lastIndex || canPolish) {
                    strip.addView(View(this).apply { setBackgroundColor((activeNeuTokens.inkFaint and 0x00FFFFFF) or 0x30000000) },
                        LinearLayout.LayoutParams(dp(1), dp(16)))
                }
            }
        }
        // ✨ Polish the whole composed message (Pro + Gemini, 3+ words). Same sparkle as the IME.
        if (canPolish) {
            strip.addView(TextView(this).apply {
                text = "✨"; gravity = Gravity.CENTER; textSize = 16f; includeFontPadding = false
                background = GradientDrawable().apply { setColor(0x338B5CF6); cornerRadius = dp(9).toFloat() }
                isClickable = true
                setOnClickListener { keyHaptic("space"); polishLauncherField() }
            }, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dp(4) })
        }
        // Elegant slide-in when the strip goes from empty to showing content.
        if (wasEmpty) {
            strip.alpha = 0f
            strip.translationY = dp(9).toFloat()
            strip.animate().alpha(1f).translationY(0f).setDuration(190)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    // Neumorphic well matching the launcher's soft UI, with an optional color wash for app matches
    // (free) or an AI-style gradient (Pro). The wash is inset and low-alpha so the soft neu shadows
    // still read through it.
    private fun suggestionStripBackground(appColor: Int?, pro: Boolean): android.graphics.drawable.Drawable {
        val radius = dp(12).toFloat()
        val neu = Neu.drawable(activeNeuTokens, radius, NeuLevel.PRESSED_SM)
        if (appColor == null && !pro) return neu
        val wash = if (pro) {
            val a = appColor ?: 0xFF8B5CF6.toInt()
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                (darken(a) and 0x00FFFFFF) or 0x22000000,
                (a and 0x00FFFFFF) or 0x33000000,
                (brighten(a) and 0x00FFFFFF) or 0x22000000
            )).apply { cornerRadius = radius }
        } else {
            GradientDrawable().apply {
                setColor((appColor!! and 0x00FFFFFF) or 0x2E000000)
                cornerRadius = radius
            }
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(neu, wash)).apply {
            val inset = dp(2)
            setLayerInset(1, inset, inset, inset, inset)
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
        // Learn the pick: record the accepted word against its preceding word so the n-gram ranks
        // what you actually choose, and warm next-word predictions for it.
        val accepted = (if (pane?.kind == PaneKind.CHAT) composeText else query).trim().split(" ").filter { it.isNotEmpty() }
        if (accepted.size >= 2) ngramRepo.recordWord(accepted.last(), accepted[accepted.size - 2])
        ngramRepo.prefetchNextWords(word)
        suggestions = emptyList(); updateSuggestionBar()
        renderRibbon()
    }

    // ---- AI Polish + inline //commands on the launcher strip (parity with the IME) ----------------
    // The message-composition surface; null when the current surface isn't a composer (search/app
    // launch), so the sparkle never shows there.
    private fun composerText(): String? =
        if (openPane?.kind == PaneKind.CHAT) composeText else null

    private fun launcherPolishAvailable(): Boolean {
        val t = composerText() ?: return false
        return ProManager.isUnlocked(this) && geminiConfigured() &&
            t.trim().split(Regex("\\s+")).count { it.isNotEmpty() } >= 3
    }

    private fun geminiCreds(): Pair<String, String> {
        val key = prefs().getString(GEMINI_API_KEY_PREF, null)?.trim().orEmpty()
        val model = prefs().getString(GEMINI_MODEL_PREF, GEMINI_DEFAULT_MODEL)?.trim().orEmpty()
            .ifBlank { GEMINI_DEFAULT_MODEL }
        return key to model
    }

    private fun applyPolishResult(result: String?, fallback: String) {
        launcherPolishing = false
        val out = (result ?: fallback)
        if (out != composeText) {
            composeText = out
            updateAutoCapState(); updateKeyLabels()
            openPane?.let { renderPaneContent(it) }
        }
        updateSuggestionBar(); renderRibbon()
    }

    // ✨ chip: rewrite the whole composed message into clean prose.
    private fun polishLauncherField() {
        if (launcherPolishing) return
        val text = composerText() ?: return
        if (text.trim().length < 3) return
        val (key, model) = geminiCreds()
        if (key.isBlank()) return
        launcherPolishing = true
        keyHaptic("enter")
        updateSuggestionBar()
        mediaUiScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { GeminiClient.fetchRewrite(key, model, text) } }.getOrNull()
            applyPolishResult(result?.takeIf { it != text }, text)
        }
    }

    // "<text> //formal" + space -> transform the composed message with that style. Returns true when
    // it fired (caller should stop handling the space).
    private fun maybeRunLauncherAiCommand(): Boolean {
        if (launcherPolishing || openPane?.kind != PaneKind.CHAT) return false
        if (!ProManager.isUnlocked(this) || !geminiConfigured()) return false
        val (content, instruction) = AiStyleCommands.match(composeText) ?: return false
        val (key, model) = geminiCreds()
        if (key.isBlank()) return false
        launcherPolishing = true
        keyHaptic("enter")
        suggestions = emptyList(); updateSuggestionBar()
        mediaUiScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { GeminiClient.fetchTransform(key, model, content, instruction) } }.getOrNull()
            applyPolishResult((result ?: content) + " ", content)
        }
        return true
    }

    // Agentic command bar: hold the go/enter key in the type-first launcher to route the typed query
    // to an action (music, maps, timer, location, web). Shows a preview chip; only runs on APPLY.
    private fun launcherCommandText(): String =
        if (openPane?.kind == PaneKind.CHAT) composeText else query

    private fun runLauncherAgenticCommand() {
        val raw = launcherCommandText()
        val cmd = AgenticRouter.classify(raw)
        if (cmd != null) { pendingLauncherCommand = cmd; updateSuggestionBar(); return }
        // Free-form fallback: Gemini ranks the typed line onto a real skill (Pro + configured).
        if (raw.isNotBlank() && ProManager.isUnlocked(this) && geminiConfigured()) {
            val key = prefs().getString(GEMINI_API_KEY_PREF, null)?.trim().orEmpty()
            val model = prefs().getString(GEMINI_MODEL_PREF, GEMINI_DEFAULT_MODEL)?.trim().orEmpty().ifBlank { GEMINI_DEFAULT_MODEL }
            val names = AgenticRouter.catalogNames()
            android.widget.Toast.makeText(this, "\uD83E\uDD16 Thinking\u2026", android.widget.Toast.LENGTH_SHORT).show()
            mediaUiScope.launch {
                val match = withContext(Dispatchers.IO) { GeminiClient.fetchSkillMatch(key, model, raw, names) }
                val c = match?.let { AgenticRouter.commandForSkill(it.first, it.second) }
                if (c != null) { pendingLauncherCommand = c; updateSuggestionBar() }
                else android.widget.Toast.makeText(this@MainActivity, "No command matched", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }
        android.widget.Toast.makeText(this, "Type a command \u2014 play, nearest, timer\u2026", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun applyLauncherCommand() {
        val cmd = pendingLauncherCommand ?: return
        pendingLauncherCommand = null
        if (cmd.insertsLocation) { AgenticRouter.recordUse(this, cmd.skillId); insertLauncherLocation(); return }
        val statusMsg = AgenticRouter.execute(this, cmd)
        if (openPane?.kind == PaneKind.CHAT) composeText = "" else query = ""
        suggestions = emptyList()
        updateAutoCapState(); updateKeyLabels(); render()
        if (statusMsg != null) android.widget.Toast.makeText(this, statusMsg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun insertLauncherLocation() {
        if (!AgenticLocation.hasPermission(this)) {
            android.widget.Toast.makeText(this, "Enable location for Clicks", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        mediaUiScope.launch {
            val text = withContext(Dispatchers.IO) { AgenticLocation.currentLocationText(this@MainActivity) } ?: return@launch
            if (openPane?.kind == PaneKind.CHAT) { composeText += text; openPane?.let { renderPaneContent(it) } }
            else query += text
            updateKeyLabels(); render()
        }
    }

    private fun scheduleSpellCheck() {
        suggestDebounce?.let { handler.removeCallbacks(it) }
        scheduleLiveAutocorrect()
        val word = currentWordInCompose()
        if (word.length < 2) {
            suggestions = emptyList(); updateSuggestionBar()
            liveRouter.onTextChanged("")
            return
        }
        liveRouter.onTextChanged(word)
        val prev = previousWordInCompose()
        val r = Runnable {
            lastSuggestWord = word
            // Warm the n-gram cache: `prev` boosts the current word's completions now; `word` is
            // pre-fetched so its next-word predictions are ready the moment it's committed.
            if (prev.isNotEmpty()) ngramRepo.prefetchNextWords(prev)
            ngramRepo.prefetchNextWords(word)
            val chord = AbbreviationExpander.expand(word)
            val localSuggs = predictionEngine.getSuggestions(word, 3, ngramBoost = ngramRepo.cachedNextWords(prev))
            suggestions = ((if (chord != null) listOf(chord) else emptyList()) + localSuggs).distinct().take(3)
            updateSuggestionBar()
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

    // The word before the one currently being typed — the n-gram key for next-word predictions.
    // Handles the trailing-space case (a word was just committed) by treating the last token as
    // the previous word, so predictions key off the right context.
    private fun previousWordInCompose(): String {
        val raw = if (openPane?.kind == PaneKind.CHAT) composeText else query
        val trimmed = raw.trimEnd()
        val words = trimmed.split(' ').filter { it.isNotEmpty() }
        return when {
            raw.isNotEmpty() && raw != trimmed -> words.lastOrNull().orEmpty()
            words.size >= 2 -> words[words.size - 2]
            else -> ""
        }
    }

    // ── Autocorrect ──────────────────────────────────────────────────────────

    // [live] = fired from the pause debounce with no space typed. In that mode we ONLY rewrite a
    // "dead-end" word (one no real word extends), so we never rewrite a word still being typed, and
    // we don't add a trailing space. Non-live is the on-space path and behaves as before.
    private fun tryAutocorrect(live: Boolean = false) {
        val word = currentWordInCompose().trimEnd()
        if (word.length < 2) return
        if (live && (word.length < 3 || predictionEngine.isPrefixOfDictWord(word))) return
        // bestCorrection is already Damerau-aware (a transposition like teh→the or an adjacent-key
        // slip counts as ~1 edit) and confidence-bounded, so trust its result directly. Only the
        // looser getSuggestions fallback — which can return a longer completion — needs the plain
        // edit-distance<=1 guard to avoid over-correcting. Applying that guard to bestCorrection was
        // the bug: plain Levenshtein scores a transposition as 2, so common typos were never fixed.
        val context = ngramRepo.cachedNextWords(previousWordInCompose())
        val top = predictionEngine.bestCorrection(word, context) ?: run {
            if (live) return                    // live mode trusts only the confident correction
            val g = predictionEngine.getSuggestions(word, 1).firstOrNull() ?: return
            if (editDistance(word, g) > 1) return
            g
        }
        if (top.equals(word, ignoreCase = true)) return
        if (rejectedCorrections[word.lowercase(Locale.US)]?.contains(top.lowercase(Locale.US)) == true) return
        pendingAutocorrectUndo = AutocorrectUndo(word, top, trailingSpace = !live)
        val suffix = if (live) "" else " "
        if (openPane?.kind == PaneKind.CHAT) {
            composeText = composeText.dropLast(word.length) + top + suffix
        } else {
            query = query.dropLast(word.length) + top + suffix
        }
        if (live) { renderRibbon(); scheduleSpellCheck() }   // reflect the inline rewrite + refresh strip
    }

    private var liveCorrectDebounce: Runnable? = null

    // Silent, no-space autocorrect: after a brief pause, if the current word is a finished typo
    // (a dead-end that can't extend into any real word) with a confident fix, rewrite it in place.
    // The dead-end guard in tryAutocorrect(live=true) is what makes this safe mid-sentence.
    private fun scheduleLiveAutocorrect() {
        liveCorrectDebounce?.let { handler.removeCallbacks(it) }
        val raw = if (openPane?.kind == PaneKind.CHAT) composeText else query
        if (raw.isEmpty() || raw.last() == ' ' || libraryOpen) return
        val word = currentWordInCompose().trimEnd()
        if (word.length < 3) return
        val r = Runnable {
            val curRaw = if (openPane?.kind == PaneKind.CHAT) composeText else query
            if (curRaw.isEmpty() || curRaw.last() == ' ') return@Runnable
            if (currentWordInCompose().trimEnd() != word) return@Runnable   // user kept typing
            tryAutocorrect(live = true)
        }
        liveCorrectDebounce = r
        handler.postDelayed(r, 600)
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
                // Blend: Gemini's contextual predictions ride in front of the instant on-device ones.
                suggestions = (result + suggestions).distinct().take(3)
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
        try {
            java.io.OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            if (conn.responseCode !in 200..299) return emptyList()
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            val text = org.json.JSONObject(raw)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?.optJSONObject(0)?.optString("text")?.trim() ?: return emptyList()
            return parseGeminiSuggestionArray(text)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Extract up to 3 suggestions from the model's reply. Prefers strict JSON array parsing;
     * falls back to the original lenient regex so replies wrapped in markdown fences or extra
     * prose (which the regex tolerates) still work exactly as before.
     */
    private fun parseGeminiSuggestionArray(text: String): List<String> {
        runCatching {
            val arr = org.json.JSONArray(text)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotBlank()) out.add(s)
            }
            if (out.isNotEmpty()) return out.take(3)
        }
        // Fallback: original ["word1","word2","word3"] regex extraction.
        return Regex(""""\s*([^"]+)\s*"""").findAll(text)
            .map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.take(3).toList()
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
        try {
            java.io.OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            if (conn.responseCode !in 200..299) return ""
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            return org.json.JSONObject(raw)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?.optJSONObject(0)?.optString("text")?.trim() ?: ""
        } finally {
            conn.disconnect()
        }
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
                // Union dictionary across the system's enabled languages — multi-language typing with
                // no language switching (see DictionaryLoader).
                val loaded = com.fran.clicks.keyboard.DictionaryLoader.load(this@MainActivity)
                val clf = StatisticalGlideTypingClassifier()
                clf.setWordData(loaded.words, loaded.freqs)
                launch(Dispatchers.Main) {
                    glideClassifier = clf
                    wordlistFrequencies = loaded.freqs
                    predictionEngine = PredictionEngine(loaded.freqs)
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

    // Snap a letter tap to the spatially-nearest letter key (near-miss correction) and let the
    // adaptive model learn this user's touch offset. Non-letters pass through untouched.
    private fun resolveTapKey(label: String, rawX: Float, rawY: Float): String {
        if (label.length != 1 || !label[0].isLetter() || keyBounds.isEmpty()) return label
        spatialScorer.recordTap(rawX, rawY)
        val best = spatialScorer.bestKey(rawX, rawY, letterOnly = true) { predictionEngine.nextCharWeights(currentWordInCompose()) } ?: return label
        return if (best.length == 1 && best[0].isLetter()) best else label
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
        val ranked = rerankGlideByContext(results)
        val topWord = ranked[0]
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
        // Learn from the glide: record the committed word against its predecessor so the n-gram and
        // context re-ranking keep improving as you swipe.
        val toks = (if (pane?.kind == PaneKind.CHAT) composeText else query).trim().split(" ").filter { it.isNotEmpty() }
        if (toks.size >= 2) ngramRepo.recordWord(toks.last(), toks[toks.size - 2])
        ngramRepo.prefetchNextWords(topWord)
        suggestions = ranked.take(3)
        updateSuggestionBar()
        renderRibbon()
    }

    // Among the shape-vetted glide candidates, promote the one you most often type after the
    // preceding word (bigram context). It only reorders within the top few, so a strong shape match
    // is never overridden by an implausible word — it just breaks close calls the way you actually type.
    private fun rerankGlideByContext(results: List<String>): List<String> {
        if (results.size < 2) return results
        val ctx = ngramRepo.cachedNextWords(previousWordInCompose()).map { it.lowercase(Locale.US) }
        if (ctx.isEmpty()) return results
        val best = results.take(3).minByOrNull {
            val i = ctx.indexOf(it.lowercase(Locale.US)); if (i < 0) Int.MAX_VALUE else i
        }
        return if (best != null && ctx.contains(best.lowercase(Locale.US)) && best != results[0])
            listOf(best) + results.filter { it != best }
        else results
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

    private fun glideTrailColors(): IntArray {
        glideRecognizedColor?.let { c ->
            return intArrayOf(adjustAlpha(c, 0x20), brighten(c), c, adjustAlpha(c, 0x22))
        }
        val core = when (keyboardTheme) {
            KEYBOARD_THEME_GOKEYS -> 0xFFF2691E.toInt()
            KEYBOARD_THEME_CLICKS -> Accent2
            KEYBOARD_THEME_SKEUO -> 0xFF8FD694.toInt()
            KEYBOARD_THEME_HYPER3D -> 0xFF7EA2FF.toInt()
            KEYBOARD_THEME_HYPER3D_BLACK -> 0xFFFF6B6B.toInt()
            KEYBOARD_THEME_HYPER3D_LIGHT -> 0xFF4E6FE7.toInt()
            else -> goKeyColor
        }
        val tail = when (keyboardTheme) {
            KEYBOARD_THEME_HYPER3D_LIGHT -> 0xFF8FD6FF.toInt()
            KEYBOARD_THEME_HYPER3D_BLACK -> 0xFF9DB4FF.toInt()
            KEYBOARD_THEME_CLICKS -> 0xFFFF8A68.toInt()
            else -> brighten(core)
        }
        return intArrayOf(adjustAlpha(core, 0x20), tail, core, adjustAlpha(core, 0x22))
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
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val trailPath = Path()
        private var glidePersisting = false
        private var glideFadeRunnable: Runnable? = null

        // Keep the finished glide trail on screen briefly (recolored to the matched app / theme),
        // then fade it out — so it "stays after finishing" instead of vanishing the instant you lift.
        private fun fadeGlideTrail() {
            glideFadeRunnable?.let { handler.removeCallbacks(it) }
            val r = Runnable { glidePersisting = false; trailLocal.clear(); glideRecognizedColor = null; invalidate() }
            glideFadeRunnable = r
            handler.postDelayed(r, 900)
        }

        private fun clearGlideTouchState() {
            tracking = false
            traced.clear()
            trailLocal.clear()
            trackpadActive = false
            glideClassifier?.clear()
            invalidate()
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if ((tracking || glidePersisting) && trailLocal.size > 1) {
                trailPath.reset()
                trailPath.moveTo(trailLocal[0].first, trailLocal[0].second)
                for (i in 1 until trailLocal.size) trailPath.lineTo(trailLocal[i].first, trailLocal[i].second)
                val start = trailLocal.first()
                val end = trailLocal.last()
                val colors = glideTrailColors()
                trailPaint.shader = android.graphics.LinearGradient(
                    start.first, start.second, end.first, end.second,
                    colors, null, Shader.TileMode.CLAMP
                )
                trailPaint.strokeWidth = dp(12).toFloat()
                trailPaint.alpha = 58
                canvas.drawPath(trailPath, trailPaint)
                trailPaint.strokeWidth = dp(5).toFloat()
                trailPaint.alpha = 222
                canvas.drawPath(trailPath, trailPaint)
                trailPaint.shader = null
                trailPaint.alpha = 255
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (widgetKeyboardSwapActive()) {
                clearGlideTouchState()
                return true
            }
            if (keyboardSettingsOpen) {
                clearGlideTouchState()
                return false
            }
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
                    glideFadeRunnable?.let { handler.removeCallbacks(it) }
                    glidePersisting = false; glideRecognizedColor = null
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
                            glideGestureActive = true
                            keyAtPoint(startRawX, startRawY)?.let { k -> if (k.length == 1) traced.add(k) }
                            trailLocal.add(startRawX - screenX to startRawY - screenY)
                            glideClassifier?.addGesturePoint(startRawX, startRawY)
                            return true
                        }
                    }
                    return tracking
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { tracking = false; glideGestureActive = false; return false }
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
            if (widgetKeyboardSwapActive()) {
                clearGlideTouchState()
                return handleWidgetKeyboardDetachedTouch(ev)
            }
            if (keyboardSettingsOpen) return false
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
                    glideGestureActive = false
                    val clf = glideClassifier
                    // Deliberate quick left-swipe = whole-word delete. A glide that spells a word has
                    // many sampled points (hasEnoughPoints), so it must never be read as a delete even
                    // when its path happens to end left of where it started (common for later words).
                    if (flickDetector.isLeftSwipe(startRawX, startRawY, ev.rawX, ev.rawY) &&
                        (clf == null || !clf.hasEnoughPoints)) {
                        tracking = false; traced.clear(); trailLocal.clear(); glidePersisting = false; invalidate()
                        clf?.clear()
                        keyHaptic("back")
                        deleteWord()
                        return true
                    }
                    val t = traced.toList()
                    tracking = false; traced.clear()
                    glidePersisting = trailLocal.size > 1   // hold the trail through recognition
                    invalidate()
                    if (clf != null && clf.hasEnoughPoints) {
                        Thread {
                            val results = clf.getSuggestions(3)
                            clf.clear()
                            handler.post {
                                if (results.isNotEmpty()) {
                                    handleGlideResult(results)
                                    glideRecognizedColor = suggestionStripAppColor(results)  // tint trail to app
                                    invalidate()
                                } else if (t.size >= 3) handleSwipeFallback(t)
                                fadeGlideTrail()
                            }
                        }.start()
                    } else {
                        clf?.clear()
                        if (t.size >= 3) handleSwipeFallback(t)
                        fadeGlideTrail()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    tracking = false; glideGestureActive = false; traced.clear(); trailLocal.clear()
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
        pendingLauncherCommand = null
        val pane = openPane
        if (pane?.kind == PaneKind.CHAT) { handleChatKey(label, pane); return }
        if (pane?.kind == PaneKind.AI) { handleAiKey(label, pane); return }

        when (label) {
            "back" -> {
                // Undo autocorrect: if last action was a correction, restore original word
                val undo = pendingAutocorrectUndo
                if (undo != null && !libraryOpen) {
                    pendingAutocorrectUndo = null
                    rememberRejectedCorrection(undo)
                    query = query.dropLast(undo.corrected.length + (if (undo.trailingSpace) 1 else 0)) + undo.original  // strip "corrected " and restore
                    if (openPane?.kind == PaneKind.CHAT) {
                        composeText = composeText.dropLast(undo.corrected.length + (if (undo.trailingSpace) 1 else 0)) + undo.original
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
                        // Surface personalized next-word predictions for the word just committed
                        // (warmed during typing, so the strip fills instead of going blank).
                        val committed = query.trimEnd().substringAfterLast(' ')
                        if (committed.isNotEmpty()) ngramRepo.prefetchNextWords(committed)
                        suggestions = if (committed.isNotEmpty()) ngramRepo.cachedNextWords(committed).take(3) else emptyList()
                        updateSuggestionBar()
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
                if (openPane != null) {
                    keyboardSettingsOpen = false
                    refreshKeyboardDock()
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
                    if (executeTypeToDoCommand(query)) {
                        query = ""
                        refreshLibraryContent()
                        return
                    }
                    val result = bestLauncherResultForGo()
                    if (result != null) openSearchResult(result) else launchInAppGoogleSearch(query)
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
                    val ribbon = filteredRibbonEntries().firstOrNull()
                    if (ribbon != null && query.isNotBlank()) {
                        if (ribbon.target.kind == PaneKind.MUSIC || ribbon.target.packageName == null) openHere(ribbon.target) else openExternal(ribbon.target)
                    } else {
                        launchInAppGoogleSearch(query)
                    }
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
                    scheduleSpellCheck()   // feed the suggestion strip (completions + app-color) in widget search
                    scheduleGeminiSuggestions()
                }
            }
        }
        if (libraryOpen) scheduleLibraryRefresh()
        renderRibbon()
    }

    private fun handleAiKey(label: String, pane: PaneTarget) {
        when (label) {
            "back" -> {
                pendingAutocorrectUndo = null
                aiDraftText = aiDraftText.dropLast(1)
                aiDraftActive = true
            }
            "space" -> {
                aiDraftActive = true
                val now = System.currentTimeMillis()
                if (now - lastSpaceMs < 500 && aiDraftText.isNotEmpty() && aiDraftText.last() == ' ') {
                    aiDraftText = aiDraftText.dropLast(1) + ". "
                    shiftState = ShiftState.ONCE; updateKeyLabels()
                } else if (!aiDraftText.endsWith(" ")) {
                    aiDraftText += " "
                }
                lastSpaceMs = now
            }
            "period" -> {
                aiDraftActive = true
                aiDraftText = aiDraftText.trimEnd() + "."
                shiftState = ShiftState.ONCE; updateKeyLabels()
            }
            "123" -> { numberPadOpen = true; ensureContactsPermission(); render(); return }
            "abc" -> {
                if (symbolsOpen) { symbolsOpen = false; render(); return }
                numberPadOpen = false; render(); return
            }
            "clicks" -> {
                if (keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET) setKeyboardPlacement(KEYBOARD_PLACEMENT_DOCKED)
                else if (openPane != null) { keyboardSettingsOpen = false; refreshKeyboardDock() }
                else { keyboardSettingsOpen = !keyboardSettingsOpen; render() }
                return
            }
            "enter" -> {
                val prompt = aiDraftText.trim()
                if (prompt.isNotBlank()) {
                    aiDraftText = ""
                    aiDraftActive = false
                    query = ""
                    askGemini(prompt)
                    return
                }
            }
            else -> {
                val char = if (shiftState != ShiftState.OFF) label.uppercase(Locale.US) else label
                aiDraftText += char
                aiDraftActive = true
                pendingAutocorrectUndo = null
                if (shiftState == ShiftState.ONCE) { shiftState = ShiftState.OFF; updateKeyLabels() }
            }
        }
        renderPaneContent(pane)
        renderRibbon()
    }

    private fun scheduleLibraryRefresh() {
        if (!libraryOpen) return
        libraryRefreshDebounce?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            libraryRefreshDebounce = null
            if (libraryOpen) refreshLibraryContent()
        }
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
                    rememberRejectedCorrection(undo)
                    composeText = composeText.dropLast(undo.corrected.length + (if (undo.trailingSpace) 1 else 0)) + undo.original
                    updateAutoCapState(); updateKeyLabels(); return
                }
                pendingAutocorrectUndo = null
                composeText = composeText.dropLast(1)
                updateAutoCapState(); updateKeyLabels(); scheduleSpellCheck()
            }
            "space" -> {
                pendingAutocorrectUndo = null
                if (maybeRunLauncherAiCommand()) return
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
                else if (openPane != null) { keyboardSettingsOpen = false; refreshKeyboardDock() }
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
        if (pane?.kind == PaneKind.AI) {
            aiDraftText = WordBoundaryDeleter.deleteWord(aiDraftText)
            aiDraftActive = true
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
        keyboardSettingsOpen = false; query = ""; composeText = ""
        shiftState = ShiftState.ONCE; suggestions = emptyList()
        updateKeyLabels(); updateSuggestionBar()
        openPane = target
        if (target.kind == PaneKind.SETTINGS) {
            render()
            return
        }
        if (keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET && target.usesMediaDock()) {
            render()
            return
        }
        showPane(target, animate = true); renderRibbon(); refreshKeyboardDock()
        syncNowPlayingCardVisibility()
        refreshNowPlayingCard()
    }

    private fun openLibraryResult(target: PaneTarget) {
        closeCategoryFolder()
        libraryOpen = false
        libraryView?.let { contentFrame.removeView(it) }
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
        val restoreWidgetKeyboard = keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET && closingPane?.usesMediaDock() == true
        openPane = null; composeText = ""; aiDraftText = ""; aiDraftActive = false
        shiftState = ShiftState.OFF; suggestions = emptyList()
        if (closingPane?.kind == PaneKind.PHOTOS) zeissButtonView?.animateShutterOpen()
        if (closingPane?.kind == PaneKind.MUSIC) hideMusicProgressFromHintBar()
        updateKeyLabels(); updateSuggestionBar(); renderRibbon(); refreshKeyboardDock(); syncNowPlayingCardVisibility(); refreshNowPlayingCard()
        if (restoreWidgetKeyboard) {
            render()
            return
        }
        if (closingPane?.kind == PaneKind.SETTINGS) {
            render()
            return
        }
        if (closing == null) return
        closing.animate().translationY(closing.height.toFloat()).setDuration(220).withEndAction {
            contentFrame.removeView(closing); if (paneView === closing) paneView = null
        }.start()
    }

    private fun pane(target: PaneTarget): View {
        if (target.kind == PaneKind.AI) {
            return FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                tag = PANE_BODY_TAG
                addView(aiPane(target), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        }
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
        if (target.kind == PaneKind.AI) return aiPane(target)
        return ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14))
                when (target.kind) {
                    PaneKind.CHAT -> chatLines(target).forEach { addView(bubble(it)) }
                    PaneKind.MAIL -> mailLines(target).forEach { addView(listRow(it.first, it.second)) }
                    PaneKind.MUSIC -> Unit
                    PaneKind.PHOTOS -> Unit
                    PaneKind.AI -> Unit
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

    private fun aiPane(target: PaneTarget): View {
        return ComposeView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setContent {
                val state = aiAnswersById[target.id] ?: AiAnswerState(target.preview, "Thinking...", true)
                val configured = geminiConfigured()
                val displayedQuery = if (aiDraftActive || aiDraftText.isNotBlank()) aiDraftText else state.prompt
                val answer = if (configured) {
                    state.answer
                } else {
                    "Add a **Gemini API key** in Clicks Settings to ask questions without leaving the launcher."
                }
                ClicksAiQueryFlow(
                    query = displayedQuery,
                    answer = answer,
                    loading = configured && state.loading,
                    askedActive = aiDraftActive || aiDraftText.isNotBlank(),
                    onClose = {
                        haptic(this@apply)
                        closePane()
                    },
                    onAskedClick = {
                        haptic(this@apply)
                        aiDraftText = ""
                        aiDraftActive = true
                        renderPaneContent(target)
                        renderRibbon()
                    },
                    onJoin = { url ->
                        haptic(this@apply)
                        startSafeIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Meeting link isn't available")
                    },
                    onOpenCalendar = { event ->
                        haptic(this@apply)
                        openCalendarEventOrRequest(event)
                    }
                )
            }
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
            if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                isLongClickable = true
                setOnLongClickListener {
                    val next = !prefs().getBoolean(DEV_EXPERIMENTS_PREF, false)
                    prefs().edit().putBoolean(DEV_EXPERIMENTS_PREF, next).apply()
                    if (!next) {
                        VivoDockedExperiment.clearViewportTruncation(this@MainActivity)
                        VivoDockedExperiment.setMode(this@MainActivity, VivoDockedExperiment.MODE_OFF)
                    }
                    haptic(this)
                    Toast.makeText(this@MainActivity, if (next) "Dev experiments enabled" else "Dev experiments disabled", Toast.LENGTH_SHORT).show()
                    renderPaneContent(clicksSettingsTarget())
                    true
                }
            }
        })
        parent.addView(settingAction("ICON PACK   ${activeIconPackLabel().uppercase(Locale.US)}") {
            haptic(this)
            showIconPackMenu(this)
        }, LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
        parent.addView(iconSizeSetting(), LinearLayout.LayoutParams.MATCH_PARENT, dp(46))
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
        if (devExperimentsEnabled() && VivoDockedExperiment.isAvailable(this)) {
            parent.addView(vivoDockedExperimentSelector(), LinearLayout.LayoutParams.MATCH_PARENT, dp(40))
        }
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
        parent.addView(gmailIntegrationRow())
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

    private fun iconSizeSetting(): View {
        lateinit var valueView: TextView
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), 0)
            addView(mono("ICON SIZE", 9.5f, InkDim).apply {
                letterSpacing = 0.10f
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(SeekBar(context).apply {
                max = 100
                progress = appIconSize
                thumbTintList = android.content.res.ColorStateList.valueOf(Accent)
                progressTintList = android.content.res.ColorStateList.valueOf(Accent)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(KeyEdge)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        appIconSize = p
                        valueView.text = p.toString()
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) = Unit
                    override fun onStopTrackingTouch(s: SeekBar?) {
                        s?.let { haptic(it) }
                        prefs().edit().putInt(APP_ICON_SIZE_PREF, appIconSize).apply()
                        renderFavoritesDock()
                        if (libraryOpen) refreshLibraryContent()
                        if (openPane?.kind == PaneKind.SETTINGS) renderPaneContent(clicksSettingsTarget())
                    }
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            })
            valueView = mono(appIconSize.toString(), 9f, Accent2).apply {
                gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            }
            addView(valueView, LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.MATCH_PARENT))
        }
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

    private fun gmailIntegrationRow(): View {
        val connected = gmailAuth.isConnected
        val state = when {
            !gmailAuth.isConfigured() -> "SET UP"
            connected -> "CONNECTED"
            else -> "CONNECT"
        }
        return integrationLikeRow("Gmail", state, if (connected) Accent2 else InkDim) { anchor ->
            if (!gmailAuth.isConfigured()) {
                Toast.makeText(this, "Add your Gmail OAuth client ID to GmailAuth.kt first.", Toast.LENGTH_LONG).show()
                return@integrationLikeRow
            }
            AlertDialog.Builder(this)
                .setTitle("Gmail")
                .setMessage(if (connected)
                    "Gmail is connected. Flights and boarding passes appear when you search \"flights\" or \"boarding pass\"."
                else
                    "Connect Gmail to search your inbox for flights and boarding passes. Read-only, on this device.")
                .setPositiveButton(if (connected) "Reconnect" else "Connect") { _, _ -> gmailAuth.startOAuth(this) }
                .apply {
                    if (connected) setNeutralButton("Disconnect") { _, _ ->
                        gmailAuth.disconnect()
                        if (openPane?.kind == PaneKind.SETTINGS) renderPaneContent(clicksSettingsTarget())
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
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
                        applyGoKeyColor(option.color, refreshSettings = true)
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
        if (target.kind == PaneKind.AI) {
            val existing = paneView as? ViewGroup ?: return
            existing.removeAllViews()
            existing.addView(aiPane(target), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return
        }
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
        val pane = openPane
        val typedText = when {
            pane?.kind == PaneKind.CHAT -> composeText
            pane?.kind == PaneKind.AI -> aiDraftText
            else -> query
        }
        val hint = when {
            libraryOpen && typedText.isBlank() -> "APP LIBRARY  ·  TAP TOP BUTTON FOR CATEGORY / GRID"
            keyboardSettingsOpen -> "KEYBOARD SETTINGS"
            numberPadOpen && typedText.isBlank() -> "TYPE NUMBER  ·  CONTACTS APPEAR ABOVE  ·  GO = DIAL"
            pane?.kind == PaneKind.CHAT && typedText.isBlank() -> "→ ${pane.name.uppercase(Locale.US)}"
            pane?.kind == PaneKind.AI && typedText.isBlank() -> "ASK GEMINI  ·  TAP ASKED OR START TYPING"
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
            searchHintView.setTextColor(activeNeuTokens.ink)
            searchHintView.text = styledTypedCommand(typedText)
        } else {
            searchHintView.textSize = if (isVivoDevice()) 8.6f else 9.5f
            searchHintView.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            searchHintView.letterSpacing = if (isVivoDevice()) 0.14f else 0.18f
            searchHintView.ellipsize = android.text.TextUtils.TruncateAt.END
            searchHintView.includeFontPadding = false
            searchHintView.gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            searchHintView.setPadding(dp(10), dp(2), dp(6), dp(3))
            searchHintView.setTextColor(activeNeuTokens.inkDim)
            searchHintView.text = hint
        }
        val widgetSearchActive = isWidgetUniversalSearchActive()
        if (widgetSearchActive != widgetSearchRendered && ::contentFrame.isInitialized) {
            render()
            return
        }
        if (widgetSearchActive) refreshWidgetSearchContent()
    }

    private fun renderFavoritesDock() {
        if (!::favoritesDockView.isInitialized) return
        favoritesDockView.removeAllViews()
        val dockItems = homeDockItems()
        if (dockItems.isEmpty()) {
            favoritesDockView.addView(mono("LONG-PRESS APPS IN THE LIBRARY TO ADD FAVORITES", 8.5f, InkDim).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.12f
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return
        }
        dockItems.take(DOCK_APP_LIMIT).forEachIndexed { index, item ->
            favoritesDockView.addView(dockItemButton(item, index), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
        repeat(DOCK_APP_LIMIT - dockItems.size.coerceAtMost(DOCK_APP_LIMIT)) { index ->
            favoritesDockView.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (dockItems.isNotEmpty() || index > 0) marginStart = dp(6)
            })
        }
    }

    private data class HomeDockItem(
        val app: AppEntry?,
        val target: PaneTarget,
        val label: String,
        val accent: Int
    )

    private fun homeDockItems(): List<HomeDockItem> {
        val music = HomeDockItem(null, musicTarget(), "Music", 0xFF57C98A.toInt())
        val appItems = homeDockApps()
            .filterNot { it.packageName == packageName }
            .map { HomeDockItem(it, it.toPaneTarget(), it.shortName, it.brandColor) }
        return (listOf(music) + appItems).take(DOCK_APP_LIMIT)
    }

    private fun dockItemButton(item: HomeDockItem, index: Int): View {
        val target = item.target
        val showLabel = showDockLabels()
        val libraryApp = item.app?.toLibraryApp() ?: if (target.kind == PaneKind.MUSIC) musicLibraryApp() else null
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
            val iconFrame = dockIconFrameSize(showLabel)
            addView(FrameLayout(context).apply {
                elevation = dp(2).toFloat()
                background = dockIconButtonBackground()
                if (libraryApp != null) {
                    addView(ImageView(context).apply {
                        setImageDrawable(iconFor(libraryApp))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        adjustViewBounds = true
                        setPadding(appIconInnerPadding(), appIconInnerPadding(), appIconInnerPadding(), appIconInnerPadding())
                    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                } else {
                    addView(TextView(context).apply {
                        text = "♪"
                        gravity = Gravity.CENTER
                        textSize = 23f
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        setTextColor(item.accent)
                        includeFontPadding = false
                    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
            }, LinearLayout.LayoutParams(iconFrame, iconFrame))
            if (showLabel) {
                addView(TextView(context).apply {
                    text = item.label
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
            setOnClickListener { haptic(this); if (target.kind == PaneKind.MUSIC || target.packageName == null) openHere(target) else openExternal(target) }
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
        if (target.kind == PaneKind.MUSIC) {
            menu.addView(menuItem("Change icon", true) {
                popup.dismiss()
                showIconMenu(anchor, musicLibraryApp())
            })
        }
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
            applyGoKeyColor(color, refreshSettings = false)
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
                setOnClickListener { haptic(this); onSelect(option.color); popup.dismiss() }
            })
        }
        popup.showAsDropDown(anchor, -dp(96), -dp(180))
    }

    // ── App loading ──────────────────────────────────────────────────────────

    private fun openExternal(target: PaneTarget): Boolean {
        target.deepLinkUri?.takeIf { it.startsWith("tel:") }?.let {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(it))); return true
        }
        val packageName = target.packageName
        if (packageName == null) { openHere(target); return true }
        try {
            if (target.deepLinkUri != null && isInstalled(packageName)) {
                return launchExternalIntent(Intent(Intent.ACTION_VIEW, Uri.parse(target.deepLinkUri)), packageName)
            }
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                return launchExternalIntent(launchIntent, packageName)
            }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
            return true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "${target.name} isn't available here", Toast.LENGTH_SHORT).show()
        }
        return false
    }

    private fun launchExternalIntent(intent: Intent, packageName: String): Boolean {
        if (keyboardPlacement == KEYBOARD_PLACEMENT_DOCKED) {
            if (!prepareDockedExternalMode()) return false
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            val options = if (VivoDockedExperiment.isEnabled(this)) null else dockedExternalActivityOptions()
            if (options != null) {
                runCatching { startActivity(intent, options.toBundle()) }
                    .onFailure { startActivity(intent) }
            } else {
                startActivity(intent)
            }
        } else {
            VivoDockedExperiment.clearViewportTruncation(this)
            startActivity(intent)
        }
        recordAppLaunch(packageName)
        return true
    }

    private fun prepareDockedExternalMode(): Boolean {
        if (keyboardPlacement != KEYBOARD_PLACEMENT_DOCKED) return true
        if (VivoDockedExperiment.isEnabled(this)) {
            VivoDockedExperiment.applyViewportTruncation(this)
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow Clicks overlay so the docked keyboard can stay visible.", Toast.LENGTH_LONG).show()
                runCatching { startActivity(DockedKeyboardService.overlaySettingsIntent(this)) }
                return false
            }
            if (!isClicksAccessibilityEnabled()) {
                Toast.makeText(this, "Enable Clicks Accessibility so the docked keyboard can type into apps.", Toast.LENGTH_LONG).show()
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                return false
            }
            startService(Intent(this, DockedKeyboardService::class.java))
        } else {
            stopService(Intent(this, DockedKeyboardService::class.java))
        }
        if (!isClicksImeEnabled()) {
            Toast.makeText(this, "Enable Clicks Keyboard, then select it for docked typing.", Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            return false
        }
        if (!isClicksImeSelected()) {
            Toast.makeText(this, "Select Clicks Keyboard for docked mode.", Toast.LENGTH_LONG).show()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showInputMethodPicker()
            return false
        }
        return true
    }

    private fun requestDockedSplitFallbackIfNeeded() {
        if (keyboardPlacement != KEYBOARD_PLACEMENT_DOCKED || dockedFreeformLikelyEnabled()) return
        handler.postDelayed({
            sendBroadcast(Intent(InputInjectionService.ACTION_TOGGLE_SPLIT_SCREEN).apply {
                setPackage(this@MainActivity.packageName)
            })
        }, 650L)
    }

    private fun dockedFreeformLikelyEnabled(): Boolean {
        val freeform = Settings.Global.getInt(contentResolver, "enable_freeform_support", 0) == 1
        val resizable = Settings.Global.getInt(contentResolver, "force_resizable_activities", 0) == 1
        return freeform && resizable
    }

    private fun isClicksAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val mine = ComponentName(this, InputInjectionService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(mine, ignoreCase = true) }
    }

    private fun isClicksImeEnabled(): Boolean {
        val component = ComponentName(this, ClicksImeService::class.java)
        return getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.any { info ->
                info.packageName == component.packageName &&
                    info.serviceName == component.className
            } == true
    }

    private fun isClicksImeSelected(): Boolean {
        val selected = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        }.getOrDefault("")
        return selected.isBlank() || selected.matchesClicksImeComponent()
    }

    private fun String.matchesClicksImeComponent(): Boolean {
        val component = ComponentName(this@MainActivity, ClicksImeService::class.java)
        return equals(component.flattenToString(), ignoreCase = true) ||
            equals(component.flattenToShortString(), ignoreCase = true)
    }

    private fun dockedExternalActivityOptions(): ActivityOptions? {
        if (keyboardPlacement != KEYBOARD_PLACEMENT_DOCKED) return null
        val screen = resources.displayMetrics
        val keyboardHeight = dp(238 + (KeyboardSettings.keyboardSize(this) * 54 / 100))
        val topBounds = Rect(
            0,
            0,
            screen.widthPixels,
            (screen.heightPixels - keyboardHeight).coerceAtLeast(dp(360))
        )
        return ActivityOptions.makeBasic().apply {
            launchBounds = topBounds
            runCatching {
                javaClass.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(this, 5)
            }
        }
    }

    private fun isInstalled(packageName: String) = packageManager.getLaunchIntentForPackage(packageName) != null

    private fun recordAppLaunch(packageName: String) {
        val counts = JSONObject(prefs().getString(APP_USAGE_PREF, "{}") ?: "{}")
        val nextCount = counts.optInt(packageName, 0) + 1
        counts.put(packageName, nextCount)
        val launches = JSONObject(prefs().getString(APP_LAST_LAUNCH_PREF, "{}") ?: "{}")
        launches.put(packageName, System.currentTimeMillis())
        prefs().edit()
            .putString(APP_USAGE_PREF, counts.toString())
            .putString(APP_LAST_LAUNCH_PREF, launches.toString())
            .apply()
        if (!libraryGridMode) {
            libraryViewDirty = true
            libraryContentReady = false
        }
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

    private fun appLastLaunches(): Map<String, Long> {
        val raw = prefs().getString(APP_LAST_LAUNCH_PREF, "{}") ?: "{}"
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        return buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, json.optLong(key, 0L))
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
        if (looksLikeTravelQuery(q)) {
            val boarding = q.lowercase(Locale.US).contains("boarding") || q.lowercase(Locale.US).contains("pass")
            results.add(0, SearchResult(
                if (boarding) "Boarding passes" else "Flights & boarding passes",
                "From your Gmail", 0xFF5FD0C4.toInt(), SearchKind.TRAVEL, null
            ) { openTravelOverlay(startOnBoardingPasses = boarding) })
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

    private fun looksLikeTravelQuery(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        return listOf("flight", "flights", "boarding", "boarding pass", "trip", "trips", "travel", "itinerary")
            .any { lower.contains(it) }
    }

    // ── Travel: flights & boarding passes from Gmail ─────────────────────────

    private var travelOverlay: View? = null

    fun openTravelOverlay(startOnBoardingPasses: Boolean) {
        if (!requirePro(ProFeature.TRAVEL_SEARCH)) return
        if (!gmailAuth.isConfigured()) {
            Toast.makeText(this, "Add your Gmail OAuth client ID to GmailAuth.kt first.", Toast.LENGTH_LONG).show()
            return
        }
        if (!gmailAuth.isConnected) {
            AlertDialog.Builder(this)
                .setTitle("Connect Gmail")
                .setMessage("Search your inbox for flights and boarding passes. Clicks only reads flight-related emails, on your device.")
                .setPositiveButton("Connect") { _, _ -> gmailAuth.startOAuth(this) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        if (travelOverlay != null) return

        val decor = window.decorView as FrameLayout
        val screenH = resources.displayMetrics.heightPixels

        val scrim = View(this).apply {
            setBackgroundColor(0xCC000000.toInt()); alpha = 0f
            setOnClickListener { dismissTravelOverlay() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF07080B.toInt())
            translationY = screenH.toFloat()
        }

        // Header with two tabs.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(10))
        }
        header.addView(TextView(this).apply {
            text = "‹"; textSize = 24f; setTextColor(0xFF6B7280.toInt())
            setPadding(0, 0, dp(14), 0)
            setOnClickListener { haptic(this); dismissTravelOverlay() }
        })
        header.addView(TextView(this).apply {
            text = "Travel"; textSize = 18f
            typeface = Typeface.create("sans-serif", Typeface.BOLD); setTextColor(Ink)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val content = FrameLayout(this)
        val loading = TextView(this).apply {
            text = "Searching your inbox…"; textSize = 13f; setTextColor(0xFF6B7280.toInt())
            gravity = Gravity.CENTER
        }
        content.addView(loading, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        var currentTab = if (startOnBoardingPasses) 1 else 0
        var flightViews: List<GmailMessage> = emptyList()
        var itineraries: List<Pair<FlightSegment, GmailMessage>> = emptyList()
        var parsingFlights = false
        var passRefs: List<BoardingPassRef> = emptyList()
        var loaded = false

        fun tabButton(label: String, index: Int, onTab: () -> Unit): TextView = TextView(this).apply {
            text = label; textSize = 12f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            val active = index == currentTab
            setTextColor(if (active) 0xFF07080B.toInt() else 0xFFCED2DA.toInt())
            background = GradientDrawable().apply {
                setColor(if (active) 0xFF5FD0C4.toInt() else 0xFF161A20.toInt()); cornerRadius = dp(18).toFloat()
            }
            setOnClickListener { haptic(this); onTab() }
        }

        fun renderTab() {
            content.removeAllViews()
            if (!loaded) { content.addView(loading); return }
            val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(28)) }
            if (currentTab == 0) {
                when {
                    itineraries.isNotEmpty() ->
                        itineraries.forEach { (seg, msg) -> list.addView(flightSegmentCard(seg) { openEmail(msg) }) }
                    parsingFlights && flightViews.isNotEmpty() -> {
                        list.addView(travelEmpty("Reading your itineraries…"))
                        flightViews.forEach { list.addView(travelCard("✈", it.subject, travelFrom(it.from), formatTravelDate(it.date)) { openEmail(it) }) }
                    }
                    flightViews.isEmpty() -> list.addView(travelEmpty("No flight emails found in the last year."))
                    else -> flightViews.forEach { list.addView(travelCard("✈", it.subject, travelFrom(it.from), formatTravelDate(it.date)) { openEmail(it) }) }
                }
            } else {
                if (passRefs.isEmpty()) list.addView(travelEmpty("No boarding passes found in the last 60 days."))
                else passRefs.forEach { ref ->
                    val hasPass = ref.passAttachment != null
                    list.addView(travelCard(
                        if (hasPass) "🎫" else "✉",
                        ref.message.subject,
                        if (hasPass) "Tap to open pass · ${ref.passAttachment!!.filename}" else travelFrom(ref.message.from),
                        formatTravelDate(ref.message.date)
                    ) { openBoardingPass(ref) })
                }
            }
            scroll.addView(list)
            content.addView(scroll)
        }

        fun rebuildTabs() {
            tabRow.removeAllViews()
            tabRow.addView(tabButton("FLIGHTS", 0) { currentTab = 0; rebuildTabsAndRender() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            tabRow.addView(tabButton("BOARDING PASSES", 1) { currentTab = 1; rebuildTabsAndRender() })
        }

        // helper closures need to reference each other; use a holder
        fun rebuildTabsAndRenderImpl() { rebuildTabs(); renderTab() }
        rebuildTabsAndRenderRef = ::rebuildTabsAndRenderImpl
        rebuildTabs()

        panel.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        panel.addView(tabRow, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        panel.addView(View(this).apply { setBackgroundColor(0x12FFFFFF) }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        panel.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        decor.addView(scrim, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        decor.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (screenH * 0.92f).toInt(), Gravity.BOTTOM))
        travelOverlay = panel
        scrim.animate().alpha(1f).setDuration(200).start()
        panel.animate().translationY(0f).setDuration(300).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        travelOverlayScrim = scrim

        mediaUiScope.launch {
            val flightsD = async(Dispatchers.IO) { runCatching { travelRepo.findFlights() }.getOrDefault(emptyList()) }
            val passesD = async(Dispatchers.IO) { runCatching { travelRepo.findBoardingPasses() }.getOrDefault(emptyList()) }
            flightViews = flightsD.await()
            passRefs = passesD.await()
            loaded = true
            parsingFlights = geminiConfigured() && flightViews.isNotEmpty()
            renderTab()

            // Enrich flight emails into structured itineraries via Gemini (best-effort).
            if (parsingFlights) {
                val parsed = withContext(Dispatchers.IO) {
                    coroutineScope {
                        flightViews.map { msg ->
                            async { runCatching { fetchFlightSegments(msg) }.getOrDefault(emptyList()).map { it to msg } }
                        }.awaitAll().flatten()
                    }
                }
                if (travelOverlay != null) {
                    // Keep only future/undated segments, sorted by date when parseable.
                    itineraries = parsed
                    parsingFlights = false
                    renderTab()
                }
            }
        }
    }

    private fun flightSegmentCard(seg: FlightSegment, onClick: () -> Unit): View {
        val accent = 0xFF5FD0C4.toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF12151A.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFF1E2A2C.toInt())
            }
            setPadding(dp(15), dp(13), dp(15), dp(13))
            setOnClickListener { haptic(this); onClick() }
            // Top line: airline + flight number, date on the right.
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = listOf(seg.airline, seg.flightNumber).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Flight" }
                    textSize = 12f; setTextColor(accent); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (seg.date.isNotBlank()) addView(TextView(context).apply {
                    text = seg.date; textSize = 11f; setTextColor(0xFF8B8F99.toInt())
                })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            // Route line: FROM  ✈  TO with times underneath.
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                fun endpoint(code: String, time: String, alignEnd: Boolean) = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = if (alignEnd) Gravity.END else Gravity.START
                    addView(TextView(context).apply {
                        text = code.ifBlank { "—" }; textSize = 20f; setTextColor(Ink)
                        typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    })
                    addView(TextView(context).apply { text = time; textSize = 11f; setTextColor(0xFF8B8F99.toInt()) })
                }
                addView(endpoint(seg.from, seg.depart, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(context).apply {
                    text = "✈"; textSize = 14f; setTextColor(0xFF6B7280.toInt()); gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(endpoint(seg.to, seg.arrive, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Footer: confirmation + seat when present.
            val footer = listOfNotNull(
                seg.confirmation.takeIf { it.isNotBlank() }?.let { "Conf $it" },
                seg.seat.takeIf { it.isNotBlank() }?.let { "Seat $it" }
            ).joinToString("   ")
            if (footer.isNotBlank()) addView(TextView(context).apply {
                text = footer; textSize = 11f; setTextColor(0xFF6B7280.toInt())
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }.also { it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) } }
    }

    private var travelOverlayScrim: View? = null
    private var rebuildTabsAndRenderRef: (() -> Unit)? = null

    private fun rebuildTabsAndRender() { rebuildTabsAndRenderRef?.invoke() }

    fun dismissTravelOverlay() {
        val panel = travelOverlay ?: return
        travelOverlay = null
        val decor = window.decorView as FrameLayout
        val scrim = travelOverlayScrim
        panel.animate().translationY(resources.displayMetrics.heightPixels.toFloat()).setDuration(280)
            .withEndAction { decor.removeView(panel) }.start()
        scrim?.animate()?.alpha(0f)?.setDuration(240)?.withEndAction { decor.removeView(scrim) }?.start()
        travelOverlayScrim = null
    }

    private fun travelEmpty(msg: String): View = TextView(this).apply {
        text = msg; textSize = 13f; setTextColor(0xFF6B7280.toInt()); setPadding(dp(6), dp(24), dp(6), 0)
    }

    private fun travelCard(icon: String, title: String, subtitle: String, date: String, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(0xFF12151A.toInt()); cornerRadius = dp(14).toFloat() }
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setOnClickListener { haptic(this); onClick() }
            addView(TextView(context).apply { text = icon; textSize = 20f; setPadding(0, 0, dp(12), 0) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = title.ifBlank { "(no subject)" }; textSize = 14f; setTextColor(Ink)
                    typeface = Typeface.create("sans-serif", Typeface.BOLD); maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply {
                    text = subtitle; textSize = 11f; setTextColor(0xFF8B8F99.toInt()); maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply { text = date; textSize = 10f; setTextColor(0xFF6B7280.toInt()) })
        }.also { row ->
            row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
    }

    private fun travelFrom(from: String): String {
        // "Delta Air Lines <noreply@delta.com>" → "Delta Air Lines"
        val name = from.substringBefore('<').trim().trim('"')
        return name.ifBlank { from.substringAfter('<').substringBefore('>').ifBlank { from } }
    }

    private fun formatTravelDate(epochMs: Long): String {
        if (epochMs <= 0L) return ""
        return java.text.SimpleDateFormat("MMM d", Locale.US).format(java.util.Date(epochMs))
    }

    private fun openEmail(msg: GmailMessage) {
        // Open the message in Gmail on the web via a subject search (reliable without RFC id).
        val url = "https://mail.google.com/mail/u/0/#search/" + Uri.encode(msg.subject.ifBlank { travelFrom(msg.from) })
        startSafeIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "No browser available")
    }

    private fun openBoardingPass(ref: BoardingPassRef) {
        val pass = ref.passAttachment
        if (pass == null) { openEmail(ref.message); return }
        Toast.makeText(this, "Opening boarding pass…", Toast.LENGTH_SHORT).show()
        mediaUiScope.launch {
            val bytes = withContext(Dispatchers.IO) { gmailApi.attachmentBytes(pass.messageId, pass.attachmentId) }
            if (bytes == null) { Toast.makeText(this@MainActivity, "Couldn't download the pass.", Toast.LENGTH_SHORT).show(); return@launch }
            val file = withContext(Dispatchers.IO) {
                val f = java.io.File(cacheDir, "passes").apply { mkdirs() }.let { java.io.File(it, pass.filename.ifBlank { "boardingpass" }) }
                f.writeBytes(bytes); f
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
            val mime = when {
                pass.filename.endsWith(".pkpass", true) || pass.mimeType.contains("pkpass", true) -> "application/vnd.apple.pkpass"
                pass.filename.endsWith(".pdf", true) || pass.mimeType == "application/pdf" -> "application/pdf"
                else -> pass.mimeType.ifBlank { "*/*" }
            }
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startSafeIntent(view, "No app can open this pass (${mime}).")
        }
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

    private fun bestLauncherResultForGo(): SearchResult? {
        val q = query.trim()
        if (q.isBlank()) return null
        val results = universalSearchResults()
        val exactApp = results.firstOrNull {
            it.kind == SearchKind.APP && it.title.equals(q, ignoreCase = true)
        }
        return exactApp ?: results.firstOrNull { it.kind != SearchKind.AI }
    }

    private fun launchInAppGoogleSearch(rawQuery: String) {
        val search = InAppGoogleSearchEngine.stripWebVerb(rawQuery).ifBlank { return }
        val toolbar = if (activeNeuTokens.mode == NeuMode.LIGHT) activeNeuTokens.baseHi else activeNeuTokens.base
        val nav = if (activeNeuTokens.mode == NeuMode.LIGHT) activeNeuTokens.base else activeNeuTokens.baseLo
        runCatching {
            InAppGoogleSearchEngine(this).launchInAppSearch(search, toolbar, nav)
        }.onFailure {
            startSafeIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(search)}")), "No browser available")
        }
    }

    // Contacts are cached in memory and filtered locally per keystroke. The old code ran two
    // contentResolver LIKE '%q%' queries (leading-wildcard full scans) on the main thread on every
    // keystroke — that was the search typing lag. The cache warms off the main thread on permission
    // grant / resume / first use, and re-renders live search once it lands.
    private var cachedContactPhones: List<ContactMatch> = emptyList()
    private var cachedContactEmails: List<ContactMatch> = emptyList()
    @Volatile private var contactsCacheLoaded = false
    private var lastContactsLoadMs = 0L

    private fun preloadContactsCache() {
        if (!hasContactsPermission()) return
        mediaUiScope.launch(Dispatchers.IO) {
            val phones = loadAllContacts(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val emails = loadAllContacts(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Email.ADDRESS
            )
            withContext(Dispatchers.Main) {
                cachedContactPhones = phones
                cachedContactEmails = emails
                contactsCacheLoaded = true
                if (query.isNotBlank() && openPane == null) renderRibbon()  // show freshly loaded contacts
            }
        }
    }

    private fun loadAllContacts(uri: android.net.Uri, nameCol: String, valueCol: String): List<ContactMatch> {
        val cursor = contentResolver.query(uri, arrayOf(nameCol, valueCol), null, null, nameCol) ?: return emptyList()
        val out = ArrayList<ContactMatch>()
        val seen = HashSet<String>()
        cursor.use {
            val nIdx = it.getColumnIndexOrThrow(nameCol)
            val vIdx = it.getColumnIndexOrThrow(valueCol)
            while (it.moveToNext()) {
                val name = it.getString(nIdx)?.trim().orEmpty()
                val value = it.getString(vIdx)?.trim().orEmpty()
                if (name.isBlank() || value.isBlank()) continue
                if (seen.add("$name:$value")) out.add(ContactMatch(name, value))
            }
        }
        return out
    }

    private fun filterCachedContacts(source: List<ContactMatch>, q: String): List<ContactMatch> {
        val n = q.trim()
        if (n.isEmpty()) return emptyList()
        return source.filter { it.name.contains(n, ignoreCase = true) || it.value.contains(n, ignoreCase = true) }
            .sortedWith { left, right ->
                val leftStarts = left.name.startsWith(n, ignoreCase = true)
                val rightStarts = right.name.startsWith(n, ignoreCase = true)
                when {
                    leftStarts != rightStarts -> if (leftStarts) -1 else 1
                    else -> collator.compare(left.name, right.name)
                }
            }
            .take(6)
    }

    private fun queryContactPhones(name: String): List<ContactMatch> {
        if (!hasContactsPermission()) return emptyList()
        if (!contactsCacheLoaded) { preloadContactsCache(); return emptyList() }
        return filterCachedContacts(cachedContactPhones, name)
    }

    private fun queryContactEmails(name: String): List<ContactMatch> {
        if (!hasContactsPermission()) return emptyList()
        if (!contactsCacheLoaded) { preloadContactsCache(); return emptyList() }
        return filterCachedContacts(cachedContactEmails, name)
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
            "search", "google", "web" -> executeWebSearchCommand(body.ifBlank { command })
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

    private fun executeWebSearchCommand(body: String): Boolean {
        val search = InAppGoogleSearchEngine.stripWebVerb(body).ifBlank { return false }
        launchInAppGoogleSearch(search)
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
        aiDraftText = ""
        aiDraftActive = false
        aiAnswersById[target.id] = AiAnswerState(prompt, if (geminiConfigured()) "Thinking..." else "Gemini needs an API key first.", geminiConfigured())
        openHere(target)
        if (!geminiConfigured()) return
        mediaUiScope.launch(Dispatchers.IO) {
            // First ask Gemini to decide whether this is an executable launcher action.
            val action = runCatching { fetchGeminiAction(prompt) }.getOrNull()
            if (action != null && action.action.lowercase(Locale.US) != "answer") {
                runOnUiThread {
                    val confirmation = runGeminiAction(action)
                    aiAnswersById[target.id] = AiAnswerState(prompt, confirmation, false)
                    if (openPane?.id == target.id) renderPaneContent(target)
                }
                return@launch
            }
            // Plain question — answer in words (reuse the inline answer if Gemini already gave one).
            val answer = action?.answer?.takeIf { it.isNotBlank() }
                ?: runCatching { fetchGeminiAnswer(prompt) }
                    .getOrElse { "I couldn't reach Gemini: ${it.message ?: "network unavailable"}" }
            runOnUiThread {
                aiAnswersById[target.id] = AiAnswerState(prompt, answer, false)
                if (openPane?.id == target.id) renderPaneContent(target)
            }
        }
    }

    private data class GeminiAction(
        val action: String,
        val target: String = "",
        val message: String = "",
        val answer: String = ""
    )

    // Ask Gemini to classify a free-form request into a structured, executable action.
    private fun fetchGeminiAction(prompt: String): GeminiAction {
        val key = prefs().getString(GEMINI_API_KEY_PREF, null)?.trim().orEmpty()
        if (key.isBlank()) return GeminiAction("answer", answer = "Gemini needs an API key first.")
        val model = prefs().getString(GEMINI_MODEL_PREF, GEMINI_DEFAULT_MODEL)?.trim().orEmpty().ifBlank { GEMINI_DEFAULT_MODEL }
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${URLEncoder.encode(model, "UTF-8")}:generateContent?key=${URLEncoder.encode(key, "UTF-8")}")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", geminiActionPrompt(prompt))))))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.1)
                .put("maxOutputTokens", 500)
                .put("responseMimeType", "application/json"))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json"); doOutput = true
        }
        OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }
        val raw = if (connection.responseCode in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IllegalStateException(error.ifBlank { "HTTP ${connection.responseCode}" })
        }
        val text = JSONObject(raw).optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?.optJSONObject(0)?.optString("text")?.trim().orEmpty()
        // Strip markdown fences if the model added them despite the JSON mime type.
        val clean = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            .let { it.substring(it.indexOf('{').coerceAtLeast(0), (it.lastIndexOf('}') + 1).coerceAtLeast(1)) }
        val obj = runCatching { JSONObject(clean) }.getOrElse { return GeminiAction("answer", answer = text.ifBlank { "Gemini returned no text." }) }
        return GeminiAction(
            action = obj.optString("action", "answer"),
            target = obj.optString("target", ""),
            message = obj.optString("message", ""),
            answer = obj.optString("answer", "")
        )
    }

    // Extracts structured flight segments from one airline/itinerary email via Gemini.
    private fun fetchFlightSegments(msg: GmailMessage): List<FlightSegment> {
        val key = prefs().getString(GEMINI_API_KEY_PREF, null)?.trim().orEmpty()
        if (key.isBlank()) return emptyList()
        val model = prefs().getString(GEMINI_MODEL_PREF, GEMINI_DEFAULT_MODEL)?.trim().orEmpty().ifBlank { GEMINI_DEFAULT_MODEL }
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${URLEncoder.encode(model, "UTF-8")}:generateContent?key=${URLEncoder.encode(key, "UTF-8")}")
        val emailText = (msg.subject + "\n" + msg.bodyText).take(6000)
        val prompt = """Extract every flight segment from this airline email. Reply with ONLY a JSON array — no prose.
Each element: {"airline","flightNumber","from","to","depart","arrive","date","confirmation","seat"}.
Use IATA airport codes for "from"/"to" when present (else city name). "depart"/"arrive" are local times like "4:30 PM". "date" like "Jul 12". Use "" for any unknown field. If the email contains no flight, reply exactly [].

Email:
$emailText"""
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0.0).put("maxOutputTokens", 900).put("responseMimeType", "application/json"))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json"); doOutput = true
        }
        OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }
        val raw = if (connection.responseCode in 200..299) connection.inputStream.bufferedReader().use { it.readText() }
        else { connection.disconnect(); return emptyList() }
        connection.disconnect()
        val text = JSONObject(raw).optJSONArray("candidates")
            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?.optJSONObject(0)?.optString("text")?.trim().orEmpty()
        val clean = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('['); val end = clean.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val arr = runCatching { JSONArray(clean.substring(start, end + 1)) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(FlightSegment(
                    airline = o.optString("airline"), flightNumber = o.optString("flightNumber"),
                    from = o.optString("from"), to = o.optString("to"),
                    depart = o.optString("depart"), arrive = o.optString("arrive"),
                    date = o.optString("date"), confirmation = o.optString("confirmation"),
                    seat = o.optString("seat")
                ))
            }
        }
    }

    private fun geminiActionPrompt(prompt: String): String {
        val appHints = apps.take(24).joinToString(", ") { it.label }
        val eventHints = calendarEvents.take(4).joinToString("; ") { "${it.title} ${it.timeLabel}" }
        val messageHints = messages.take(6).joinToString("; ") { "${it.sender}: ${it.preview}" }
        return """
            You are Clicks AI, an agent inside an Android launcher. Decide how to fulfil the user's request and reply with ONLY a JSON object — no markdown, no prose.

            Allowed actions and their fields:
            - {"action":"open_app","target":"<app name>"}  — launch an installed app
            - {"action":"text","target":"<contact name>","message":"<sms body>"}  — draft a text message
            - {"action":"call","target":"<contact name>"}  — start a phone call
            - {"action":"email","target":"<contact name>","message":"<email body>"}  — draft an email
            - {"action":"play","target":"<song or artist>"}  — play music
            - {"action":"web","target":"<search query>"}  — search the web
            - {"action":"maps","target":"<place or address>"}  — open maps
            - {"action":"answer","answer":"<concise reply>"}  — just answer in words (default for questions)

            Only pick an executable action when the user is clearly asking to DO something. Otherwise use "answer".
            Prefer app names from this installed list when relevant: $appHints
            Upcoming calendar: $eventHints
            Recent notifications/messages: $messageHints

            User request: "$prompt"
        """.trimIndent()
    }

    // Executes a structured action on the UI thread; returns a short confirmation for the AI pane.
    private fun runGeminiAction(a: GeminiAction): String {
        val target = a.target.trim()
        return when (a.action.lowercase(Locale.US)) {
            "open_app" -> if (target.isNotBlank() && executeOpenCommand(target)) "Opening $target…"
                else "I couldn't find an app called \"$target\"."
            "play" -> if (target.isNotBlank() && executePlayCommand(target)) "Playing $target…"
                else "Tell me what to play."
            "web" -> {
                if (target.isBlank()) return "What should I search for?"
                launchInAppGoogleSearch(target)
                "Searching the web for \"$target\"…"
            }
            "maps" -> {
                if (target.isBlank()) return "Where to?"
                startSafeIntent(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(target)}")), "Maps isn't available here")
                "Opening maps for $target…"
            }
            "call" -> {
                if (target.isBlank()) return "Who should I call?"
                if (!hasContactsPermission()) { ensureContactsPermission(); return "Grant contacts access, then try again." }
                val contact = findPhoneContact(target, "call $target") ?: return "I couldn't find $target in your contacts."
                startSafeIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(contact.value)}")), "Phone isn't available here")
                "Calling ${contact.name}…"
            }
            "text" -> {
                if (target.isBlank()) return "Who should I text?"
                if (!hasContactsPermission()) { ensureContactsPermission(); return "Grant contacts access, then try again." }
                val contact = findPhoneContact(target, "text $target") ?: return "I couldn't find $target in your contacts."
                startSafeIntent(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(contact.value)}")).putExtra("sms_body", a.message),
                    "Messages isn't available here")
                if (a.message.isBlank()) "Opening a text to ${contact.name}…" else "Drafting a text to ${contact.name}: “${a.message}”"
            }
            "email" -> {
                if (target.isBlank()) return "Who should I email?"
                if (!hasContactsPermission()) { ensureContactsPermission(); return "Grant contacts access, then try again." }
                val contact = findEmailContact(target, "email $target") ?: return "I couldn't find an email for $target."
                startSafeIntent(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(contact.value)}"))
                        .putExtra(Intent.EXTRA_SUBJECT, a.message.ifBlank { "Message from Clicks" })
                        .putExtra(Intent.EXTRA_TEXT, a.message),
                    "Email isn't available here")
                "Drafting an email to ${contact.name}…"
            }
            else -> a.answer.ifBlank { "Done." }
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
        if (event != null && event.eventId > 0L) {
            val eventUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId)
            val eventIntent = Intent(Intent.ACTION_VIEW, eventUri).apply {
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.beginMs)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endMs)
            }
            runCatching { startActivity(eventIntent) }
                .onFailure { openCalendarApp() }
            return
        }
        if (event != null) {
            val insertIntent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).apply {
                putExtra(CalendarContract.Events.TITLE, event.title)
                putExtra(CalendarContract.Events.EVENT_LOCATION, event.location)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.beginMs)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.endMs)
            }
            runCatching { startActivity(insertIntent) }
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

    private fun invalidateLibraryCaches() {
        libraryCategoriesCacheSignature = null
        libraryCategoriesCache = emptyList()
        appIconStateCache.evictAll()
        libraryViewDirty = true
        libraryContentReady = false
        libraryContentArea = null
        libraryView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        libraryView = null
        libraryViewMode = null
    }

    private fun registerAppPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(packageChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(packageChangeReceiver, filter)
            }
        }
    }

    private fun refreshAppsForPackageChange() {
        handler.post {
            val updatedApps = loadLaunchableApps()
            val changed = updatedApps.map { it.componentName } != apps.map { it.componentName }
            apps = updatedApps
            lastAppsLoadMs = System.currentTimeMillis()
            if (changed) {
                invalidateLibraryCaches()
                renderRibbon()
                renderFavoritesDock()
            }
        }
    }

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

    private fun musicLibraryApp() = LibraryApp("Music", 0xFF57C98A.toInt(), musicTarget(), null)

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
            .map { RecentPerson(it.key, it.sender, it.preview, it.packageName, it.color, ClicksNotificationListener.notificationAvatars[it.key], it.lastUpdated) }
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
                    avatar = ClicksNotificationListener.notificationAvatars[it.key],
                    lastUpdated = it.lastUpdated
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
                .put("color", message.color)
                .put("lastUpdated", message.lastUpdated))
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
                .put("color", message.color)
                .put("lastUpdated", message.lastUpdated))
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
        val loadedAt = System.currentTimeMillis()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(HubMessage(item.optString("key", ""), item.optString("sender", "Message").ifBlank { "Message" },
                    item.optString("preview", ""), item.optString("packageName", ""),
                    item.optString("kind", inferHubKind(item.optString("packageName", ""))),
                    item.optInt("color", Accent2),
                    item.optLong("lastUpdated", loadedAt - i * 1_000L)))
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
        weatherAmbientView?.setWeather(prefs().getInt(WEATHER_CODE_PREF, 0), activeNeuTokens.mode, animate = false)
        weatherDripView?.refresh()
        refreshNowPlayingCard()
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
                if (weatherChanged) {
                    weatherIconView.playLivePhotoBurst()
                    weatherAmbientView?.setWeather(result.code, activeNeuTokens.mode, animate = animatedWeatherEnabled())
                    weatherDripView?.refresh()
                    refreshNowPlayingCard()
                }
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
        weatherAmbientView?.setWeather(0, activeNeuTokens.mode, animate = false)
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

    private fun weatherAmbientLightColor(): Int {
        val code = prefs().getInt(WEATHER_CODE_PREF, 0)
        return when {
            isWeatherNight() -> 0xFF9DB4FF.toInt()
            code in setOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99) -> 0xFF5FD0C4.toInt()
            code in setOf(71, 73, 75, 77, 85, 86) -> 0xFFDCE6FF.toInt()
            code in setOf(45, 48) -> 0xFFAEB6C4.toInt()
            else -> 0xFFF5C451.toInt()
        }
    }

    private fun weatherAmbientLightStrength(): Float {
        val code = prefs().getInt(WEATHER_CODE_PREF, 0)
        val base = when (code) {
            0 -> 0.78f
            1, 2, 3 -> 0.68f
            45, 48 -> 0.42f
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> 0.52f
            71, 73, 75, 77, 85, 86 -> 0.48f
            95, 96, 99 -> 0.62f
            else -> 0.52f
        }
        return if (activeNeuTokens.mode == NeuMode.LIGHT) base else base * 0.82f
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
        styled.setSpan(ForegroundColorSpan(Neu.GREEN), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
        return GradientDrawable().apply { setColor(Color.TRANSPARENT) }
    }

    private fun homeKeyboardWidgetBackground(): Drawable {
        return Neu.drawable(activeNeuTokens, dp(16).toFloat(), NeuLevel.RAISED)
    }

    private fun widgetKeyboardHeight(): Int {
        return (keyboardHeight() + dp(28)).coerceIn(dp(238), dp(360))
    }

    private fun PaneTarget.usesMediaDock(): Boolean {
        return kind == PaneKind.MUSIC || kind == PaneKind.PHOTOS
    }

    private fun widgetPaneUsesRootDock(): Boolean {
        return keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET && openPane?.usesMediaDock() == true
    }

    private fun activeRootDockHeight(): Int {
        return if (widgetPaneUsesRootDock()) widgetKeyboardHeight() else keyboardHeight()
    }

    private fun widgetKeyboardHorizontalBleed(): Int = dp(10)

    private fun recessedDockBackground(): Drawable {
        return Neu.drawable(activeNeuTokens, dp(19).toFloat(), NeuLevel.PRESSED_SM)
    }

    private fun dockIconButtonBackground(): Drawable {
        return Neu.drawable(activeNeuTokens, dp(99).toFloat(), NeuLevel.RAISED_SM)
    }

    private fun libraryIconButtonBackground(radiusDp: Int = 13, stroke: Int? = Line): Drawable {
        return if (activeNeuTokens.mode == NeuMode.LIGHT) {
            dockIconButtonBackground()
        } else {
            roundedPanel(Panel2, dp(radiusDp), stroke)
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

    private fun widgetBoardScrimBackground(): Drawable {
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xD0070A0E.toInt(),
            0xF0040508.toInt()
        ))
    }

    private fun widgetTileBackground(): Drawable {
        val pocket = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xF020232A.toInt(),
            0xF014171D.toInt(),
            0xF00A0B0F.toInt()
        )).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), 0x0DFFFFFF)
        }
        val lowerShade = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(
            0x56000000,
            0x00000000
        )).apply { cornerRadius = dp(24).toFloat() }
        return LayerDrawable(arrayOf(pocket, lowerShade)).apply {
            setLayerInset(1, dp(1), dp(80), dp(1), dp(1))
        }
    }

    private fun widgetPickerSheetBackground(): Drawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xFA20232A.toInt(),
            0xFA12151A.toInt(),
            0xFA08090D.toInt()
        )).apply {
            cornerRadius = dp(28).toFloat()
            setStroke(dp(1), 0x0DFFFFFF)
        }
        return base
    }

    private fun widgetEmptyBackground(): Drawable {
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xBB20232A.toInt(),
            0xAA111419.toInt(),
            0x9908090D.toInt()
        )).apply {
            cornerRadius = dp(26).toFloat()
            setStroke(dp(1), 0x0FFFFFFF)
        }
    }

    private fun widgetProviderRowBackground(): Drawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xF01D2027.toInt(),
            0xF013151A.toInt(),
            0xF00B0C10.toInt()
        )).apply {
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), 0x0AFFFFFF)
        }
        return base
    }

    private fun libraryCategories(): List<LibraryCategory> {
        val signature = listOf(
            apps.joinToString("|") { it.componentName.flattenToShortString() },
            categoryContextBucket(),
            prefs().getString(APP_USAGE_PREF, "{}").orEmpty(),
            prefs().getString(APP_LAST_LAUNCH_PREF, "{}").orEmpty()
        ).joinToString("::")
        if (signature == libraryCategoriesCacheSignature) return libraryCategoriesCache
        val grouped = apps.map { it.toLibraryApp() }.groupBy { app -> categoryNameFor(app.target.packageName) }
        val preferredOrder = listOf("Social", "Music & Audio", "Video", "Photos", "Maps", "Productivity", "Games", "News", "Tools", "Other")
        val baseCategories = preferredOrder.mapNotNull { name ->
            grouped[name]?.takeIf { it.isNotEmpty() }?.let { LibraryCategory(name, categoryAccent(name), it) }
        } + grouped.keys
            .filterNot { it in preferredOrder }
            .sortedWith(collator)
            .mapNotNull { name -> grouped[name]?.takeIf { it.isNotEmpty() }?.let { LibraryCategory(name, categoryAccent(name), it) } }
        val categories = baseCategories.sortedWith(
            compareByDescending<LibraryCategory> { contextualCategoryScore(it.name, preferredOrder.indexOf(it.name).takeIf { index -> index >= 0 } ?: preferredOrder.size) }
                .thenBy { preferredOrder.indexOf(it.name).takeIf { index -> index >= 0 } ?: preferredOrder.size }
                .thenBy { it.name }
        )
        libraryCategoriesCacheSignature = signature
        libraryCategoriesCache = categories
        return categories
    }

    private fun categoryContextBucket(): String {
        val now = java.util.Calendar.getInstance()
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val day = now.get(java.util.Calendar.DAY_OF_WEEK)
        val weekday = day !in setOf(java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY)
        return when {
            hour in 7..11 && weekday -> "weekday_morning"
            hour in 18..23 -> "evening"
            hour in 0..5 -> "late_night"
            else -> "neutral"
        }
    }

    private fun contextualCategoryScore(name: String, orderIndex: Int): Int {
        val basePriority = (100 - orderIndex).coerceAtLeast(0)
        return when (categoryContextBucket()) {
            "weekday_morning" -> basePriority + when (name) {
                "Productivity", "Tools", "News", "Maps" -> 100
                else -> 0
            }
            "evening" -> basePriority + when (name) {
                "Music & Audio", "Video", "Social", "Games", "Photos" -> 100
                else -> 0
            }
            "late_night" -> basePriority + when (name) {
                "Tools", "Productivity", "News" -> -50
                else -> 0
            }
            else -> basePriority
        }
    }

    private fun splitCategoryApps(apps: List<LibraryApp>): Pair<List<LibraryApp>, List<LibraryApp>> {
        val launches = appLastLaunches()
        val cutoff = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
        return apps.partition { app ->
            val packageName = app.target.packageName
            val lastLaunch = packageName?.let { launches[it] } ?: 0L
            lastLaunch == 0L || lastLaunch >= cutoff
        }
    }

    private fun maxLaunchesForCategory(apps: List<LibraryApp>): Int {
        val usage = appUsageCounts()
        return apps.maxOfOrNull { app -> app.target.packageName?.let { usage[it] } ?: 0 } ?: 0
    }

    private fun categoryUsageRatio(app: LibraryApp, maxLaunches: Int): Float {
        if (maxLaunches <= 0) return 0.5f
        val count = app.target.packageName?.let { appUsageCounts()[it] } ?: 0
        return (count.toFloat() / maxLaunches.toFloat()).coerceIn(0f, 1f)
    }

    private fun applyCategoryUsageScale(app: LibraryApp, maxLaunches: Int, labelView: TextView?, iconView: ImageView) {
        val ratio = categoryUsageRatio(app, maxLaunches)
        val scale = 0.85f + ratio * 0.30f
        iconView.scaleX = scale
        iconView.scaleY = scale
        labelView?.let { label ->
            if (maxLaunches > 0 && ratio < 0.15f) {
                label.visibility = View.GONE
            } else {
                label.visibility = View.VISIBLE
                label.textSize = 10f + ratio * 4f
            }
        }
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

    private fun iconSizeExtra(maxDp: Int): Int = dp(appIconSize.coerceIn(0, 100) * maxDp / 100)

    private fun appIconInnerPadding(): Int = dp((5 - appIconSize.coerceIn(0, 100) * 2 / 100).coerceAtLeast(2))

    private fun appLibraryIconFrameSize(): Int = dp(46) + iconSizeExtra(14)

    private fun searchAppIconFrameSize(): Int = dp(52) + iconSizeExtra(14)

    private fun dockIconFrameSize(showLabel: Boolean): Int {
        val base = if (showLabel) 40 else 44
        return dp(base) + iconSizeExtra(if (showLabel) 8 else 10)
    }

    private fun appTileRowHeight(): Int = dp(82) + iconSizeExtra(18)

    private fun iconFor(app: LibraryApp): Drawable {
        val override = prefs().getString(iconOverrideKey(app), null)
        val activePack = prefs().getString(ACTIVE_ICON_PACK_PREF, null)
        val cacheKey = listOf(
            app.componentName?.flattenToShortString().orEmpty(),
            app.target.id,
            app.target.packageName.orEmpty(),
            override.orEmpty(),
            activePack.orEmpty()
        ).joinToString("|")
        appIconStateCache.get(cacheKey)?.newDrawable(resources)?.mutate()?.let { return it }
        val resolved = override?.let { iconFromOverride(it) }
            ?: app.componentName?.let { component ->
                autoIconPackIcon(component)
                    ?: runCatching { packageManager.getActivityIcon(component) }.getOrNull()
            }
            ?: app.target.packageName?.let { pkg -> runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull() }
            ?: if (app.target.kind == PaneKind.MUSIC) fallbackMusicIcon(app) else null
            ?: return fallbackLetterIcon(app)
        resolved.constantState?.let { appIconStateCache.put(cacheKey, it) }
        return resolved
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
        val key = "${app.target.id}:${app.target.packageName}:${app.accent}"
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

    private fun fallbackMusicIcon(app: LibraryApp): Drawable {
        val key = "${app.target.id}:music:${app.accent}"
        fallbackIconCache.get(key)?.let { return it }
        val size = dp(46)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF22322B.toInt(), 0xFF101916.toInt())).apply {
            cornerRadius = dp(13).toFloat()
            setStroke(dp(1), adjustAlpha(app.accent, 0x60))
            setBounds(0, 0, size, size)
            draw(canvas)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = app.accent
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textSize = dp(22).toFloat()
            setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), adjustAlpha(app.accent, 0x55))
        }
        canvas.drawText("♪", size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
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
            prefs().edit().remove(iconOverrideKey(app)).apply(); popup.dismiss(); refreshIconSurfaces()
        })
        menu.addView(menuItem("System icons for all", true) {
            prefs().edit().remove(ACTIVE_ICON_PACK_PREF).apply(); popup.dismiss(); refreshIconSurfaces()
        })
        menu.addView(menuItem("Pick from icon pack...", iconPacks().isNotEmpty()) {
            popup.dismiss()
            showIconPackSourceChooser(anchor, app)
        })
        app.componentName?.let { component ->
            iconPacks().forEach { pack ->
                matchingIconPackIcon(pack, component)?.let { icon ->
                    menu.addView(menuItem("Use ${pack.name} here", true) {
                        prefs().edit().putString(iconOverrideKey(app), "pack:${icon.packageName}:${icon.drawableName}").apply()
                        popup.dismiss(); refreshIconSurfaces()
                    })
                    menu.addView(menuItem("Apply ${pack.name} to all", true) {
                        prefs().edit().putString(ACTIVE_ICON_PACK_PREF, pack.packageName).apply()
                        popup.dismiss(); refreshIconSurfaces()
                    })
                }
            }
        }
        menu.addView(menuItem("Use another app icon", apps.isNotEmpty()) { popup.dismiss(); showAppIconChooser(anchor, app) })
        popup.showAsDropDown(anchor, -dp(82), -anchor.height)
    }

    private fun showIconPackSourceChooser(anchor: View, app: LibraryApp) {
        lateinit var popup: PopupWindow
        val packs = iconPacks()
        val menu = ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = GradientDrawable().apply { setColor(Panel2); cornerRadius = dp(8).toFloat(); setStroke(dp(1), Line) }
                if (packs.isEmpty()) {
                    addView(menuItem("No icon packs installed", false) {})
                } else {
                    packs.forEach { pack ->
                        addView(menuItem(pack.name, true) {
                            popup.dismiss()
                            showIconPackDrawableChooser(anchor, app, pack)
                        })
                    }
                }
            })
        }
        popup = PopupWindow(menu, dp(260), dp(340), true).apply {
            isOutsideTouchable = true
            showAsDropDown(anchor, -dp(96), -dp(300))
        }
    }

    private fun showIconPackDrawableChooser(anchor: View, app: LibraryApp, pack: IconPack) {
        lateinit var popup: PopupWindow
        val matched = app.componentName?.let { matchingIconPackIcon(pack, it) }
        val icons = iconPackIcons(pack).sortedWith(
            compareBy<IconPackIcon> { if (it.drawableName == matched?.drawableName) 0 else 1 }
                .thenBy { iconPackDisplayName(it) }
        )
        val displayWidth = resources.displayMetrics.widthPixels
        val displayHeight = resources.displayMetrics.heightPixels
        val panelWidth = (displayWidth - dp(28)).coerceAtMost(dp(430))
        val panelHeight = (displayHeight * 0.76f).toInt().coerceAtMost(dp(620)).coerceAtLeast(dp(440))
        val columns = if (panelWidth >= dp(390)) 5 else 4
        val tileWidth = ((panelWidth - dp(28) - dp(8) * (columns - 1)) / columns).coerceAtLeast(dp(64))

        val grid = GridLayout(this).apply {
            columnCount = columns
            useDefaultMargins = false
            alignmentMode = GridLayout.ALIGN_BOUNDS
            setPadding(dp(10), dp(8), dp(10), dp(14))
        }
        val resultLabel = mono("", 8.5f, activeNeuTokens.inkFaint).apply {
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            letterSpacing = 0.06f
        }
        val emptyState = TextView(this).apply {
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(activeNeuTokens.inkDim)
            setPadding(dp(14), dp(26), dp(14), dp(26))
            text = "No icons found"
        }
        fun renderIconGrid(queryText: String) {
            grid.removeAllViews()
            val filtered = filteredIconPackIcons(icons, queryText, matched).take(ICON_PICKER_VISIBLE_LIMIT)
            resultLabel.text = if (icons.isEmpty()) "0 ICONS" else "${filtered.size.coerceAtMost(ICON_PICKER_VISIBLE_LIMIT)} OF ${icons.size}"
            if (filtered.isEmpty()) {
                grid.addView(emptyState, GridLayout.LayoutParams().apply {
                    width = panelWidth - dp(20)
                    height = dp(96)
                    columnSpec = GridLayout.spec(0, columns)
                })
                return
            }
            filtered.forEach { icon ->
                val drawable = drawableFromIconPack(icon.packageName, icon.drawableName)
                grid.addView(iconPackDrawableTile(icon, drawable, tileWidth, icon.drawableName == matched?.drawableName) {
                    prefs().edit().putString(iconOverrideKey(app), "pack:${icon.packageName}:${icon.drawableName}").apply()
                    popup.dismiss()
                    refreshIconSurfaces()
                }, GridLayout.LayoutParams().apply {
                    width = tileWidth
                    height = dp(92)
                    setMargins(dp(4), dp(4), dp(4), dp(8))
                })
            }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(10))
            background = Neu.drawable(activeNeuTokens, dp(22).toFloat(), NeuLevel.RAISED)

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(FrameLayout(context).apply {
                    background = dockIconButtonBackground()
                    setPadding(dp(7), dp(7), dp(7), dp(7))
                    addView(ImageView(context).apply {
                        setImageDrawable(iconFor(app))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(10) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(mono("ICON PACK", 8f, activeNeuTokens.inkFaint).apply {
                        letterSpacing = 0.16f
                    })
                    addView(TextView(context).apply {
                        text = pack.name
                        textSize = 17f
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        setTextColor(activeNeuTokens.ink)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        includeFontPadding = false
                    })
                    addView(mono("For ${app.name}", 9f, activeNeuTokens.inkDim).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(context).apply {
                    text = "×"
                    gravity = Gravity.CENTER
                    textSize = 22f
                    setTextColor(activeNeuTokens.inkDim)
                    background = Neu.drawable(activeNeuTokens, dp(99).toFloat(), NeuLevel.PRESSED_SM)
                    isClickable = true
                    setOnClickListener { haptic(this); popup.dismiss() }
                }, LinearLayout.LayoutParams(dp(38), dp(38)))
            }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            val search = EditText(context).apply {
                hint = "Search icons"
                setSingleLine(true)
                textSize = 14f
                setTextColor(activeNeuTokens.ink)
                setHintTextColor(activeNeuTokens.inkFaint)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setPadding(dp(14), 0, dp(14), 0)
                background = Neu.drawable(activeNeuTokens, dp(16).toFloat(), NeuLevel.PRESSED_SM)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        renderIconGrid(s?.toString().orEmpty())
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
                topMargin = dp(12)
            })
        }

        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(10), dp(2), 0)
            addView(mono("ALPHABETICAL", 8.2f, activeNeuTokens.inkFaint).apply {
                letterSpacing = 0.14f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(resultLabel, LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        panel.addView(ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            background = Neu.drawable(activeNeuTokens, dp(18).toFloat(), NeuLevel.PRESSED_SM)
            addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        renderIconGrid("")

        popup = PopupWindow(panel, panelWidth, panelHeight, true).apply {
            isOutsideTouchable = true
            elevation = dp(18).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        popup.showAtLocation(rootView, Gravity.CENTER, 0, 0)
    }

    private fun iconPackDrawableTile(icon: IconPackIcon, drawable: Drawable?, tileWidth: Int, isMatched: Boolean, onClick: () -> Unit): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(6), dp(4), dp(4))
            isClickable = true
            background = if (isMatched) Neu.drawable(activeNeuTokens, dp(16).toFloat(), NeuLevel.RAISED) else null
            addView(FrameLayout(context).apply {
                background = dockIconButtonBackground()
                setPadding(dp(6), dp(6), dp(6), dp(6))
                if (drawable != null) {
                    addView(ImageView(context).apply {
                        setImageDrawable(drawable)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                } else {
                    addView(TextView(context).apply {
                        text = iconPackDisplayName(icon).take(1).uppercase(Locale.US)
                        gravity = Gravity.CENTER
                        textSize = 17f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(activeNeuTokens.inkDim)
                    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(TextView(context).apply {
                text = iconPackDisplayName(icon)
                textSize = 9.2f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTextColor(if (isMatched) Accent2 else activeNeuTokens.inkDim)
                includeFontPadding = false
                setPadding(dp(2), dp(6), dp(2), 0)
            }, LinearLayout.LayoutParams(tileWidth - dp(8), ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener {
                haptic(this)
                onClick()
            }
        }
    }

    private fun iconPackDisplayName(icon: IconPackIcon): String {
        return icon.drawableName
            .replace(Regex("^(ic_|icon_|app_|drawable_)"), "")
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .replace(Regex("\\s+"), " ")
            .ifBlank { icon.drawableName }
    }

    private fun filteredIconPackIcons(
        icons: List<IconPackIcon>,
        queryText: String,
        matched: IconPackIcon?
    ): List<IconPackIcon> {
        val q = queryText.trim().lowercase(Locale.US)
        val filtered = if (q.isBlank()) {
            icons
        } else {
            val terms = q.split(Regex("\\s+")).filter { it.isNotBlank() }
            icons.filter { icon ->
                val label = iconPackDisplayName(icon).lowercase(Locale.US)
                val raw = icon.drawableName.lowercase(Locale.US).replace('_', ' ').replace('-', ' ')
                terms.all { term -> label.contains(term) || raw.contains(term) }
            }.sortedWith(compareBy<IconPackIcon> {
                val label = iconPackDisplayName(it).lowercase(Locale.US)
                when {
                    label == q -> 0
                    label.startsWith(q) -> 1
                    else -> 2
                }
            }.thenBy { iconPackDisplayName(it) })
        }
        if (matched == null || q.isNotBlank()) return filtered
        return listOf(matched) + filtered.filterNot { it.drawableName == matched.drawableName }
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
                        popup.dismiss(); refreshIconSurfaces()
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

    private fun refreshIconSurfaces() {
        invalidateLibraryCaches()
        if (libraryOpen) refreshLibraryContent()
        renderFavoritesDock()
        renderRibbon()
    }

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

    private fun iconPackIcons(pack: IconPack): List<IconPackIcon> {
        iconPackDrawableCache[pack.packageName]?.let { return it }
        val res = runCatching { packageManager.getResourcesForApplication(pack.packageName) }.getOrNull()
            ?: return emptyList<IconPackIcon>().also { iconPackDrawableCache[pack.packageName] = it }
        val icons = runCatching {
            res.assets.open("appfilter.xml").use { stream ->
                val parser = Xml.newPullParser()
                parser.setInput(stream, null)
                val names = linkedSetOf<String>()
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "item") {
                        parser.getAttributeValue(null, "drawable")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { names.add(it) }
                    }
                    event = parser.next()
                }
                names.map { IconPackIcon(pack.packageName, it) }
            }
        }.getOrDefault(emptyList())
        iconPackDrawableCache[pack.packageName] = icons
        return icons
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

    private inner class DockKeyView(context: Context) : TextView(context) {
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val arcRect = RectF()
        private var progressAnimator: ValueAnimator? = null
        private var holdProgress = 0f
        private var holdHapticStage = 0

        fun startHoldProgress(durationMs: Long) {
            progressAnimator?.cancel()
            holdProgress = 0f
            holdHapticStage = 0
            invalidate()
            progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener {
                    holdProgress = it.animatedValue as Float
                    val nextStage = when {
                        holdProgress >= 0.72f -> 3
                        holdProgress >= 0.46f -> 2
                        holdProgress >= 0.2f -> 1
                        else -> 0
                    }
                    if (nextStage > holdHapticStage) {
                        holdHapticStage = nextStage
                        dockHoldHaptic(nextStage)
                    }
                    invalidate()
                }
                start()
            }
        }

        fun cancelHoldProgress() {
            progressAnimator?.cancel()
            progressAnimator = null
            holdProgress = 0f
            holdHapticStage = 0
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (holdProgress <= 0f) return
            val accent = if (keyboardTheme == KEYBOARD_THEME_HYPER3D_LIGHT) 0xFF6D7FFF.toInt() else 0xFF7AF0A0.toInt()
            val cx = width / 2f
            val cy = height / 2f
            val diameter = (min(width, height) - dp(8)).coerceAtLeast(dp(28)).toFloat()
            val radius = diameter / 2f
            val trackRadius = radius * 0.98f
            arcRect.set(cx - trackRadius, cy - trackRadius, cx + trackRadius, cy + trackRadius)

            fillPaint.shader = null
            fillPaint.color = adjustAlpha(0xFF000000.toInt(), 0.36f)
            canvas.drawCircle(cx, cy + dp(2), radius * 1.1f, fillPaint)

            fillPaint.shader = android.graphics.RadialGradient(
                cx,
                cy,
                radius * 1.7f,
                intArrayOf(adjustAlpha(accent, 0.22f), adjustAlpha(accent, 0.06f), Color.TRANSPARENT),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius * 1.55f, fillPaint)

            fillPaint.shader = android.graphics.LinearGradient(
                0f,
                cy - radius,
                0f,
                cy + radius,
                intArrayOf(0xFF262A31.toInt(), 0xFF111319.toInt(), 0xFF050607.toInt()),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius * 0.82f, fillPaint)
            fillPaint.shader = null

            ringPaint.style = Paint.Style.STROKE
            ringPaint.strokeWidth = dp(1).toFloat()
            ringPaint.color = adjustAlpha(0xFFFFFFFF.toInt(), 0.12f)
            canvas.drawCircle(cx, cy - dp(1), radius * 0.8f, ringPaint)
            ringPaint.color = adjustAlpha(0xFF000000.toInt(), 0.48f)
            canvas.drawCircle(cx, cy + dp(1), radius * 0.83f, ringPaint)

            ringPaint.strokeWidth = dp(3).toFloat()
            ringPaint.color = adjustAlpha(0xFFFFFFFF.toInt(), 0.13f)
            canvas.drawCircle(cx, cy, trackRadius, ringPaint)
            ringPaint.strokeWidth = dp(4).toFloat()
            ringPaint.color = accent
            canvas.drawArc(arcRect, -90f, holdProgress * 360f, false, ringPaint)
        }

        override fun onDetachedFromWindow() {
            progressAnimator?.cancel()
            super.onDetachedFromWindow()
        }
    }

    private inner class KeyboardSocketView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var glow = 0f

        fun pulseGlow() {
            ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 520L
                addUpdateListener {
                    glow = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val light = activeNeuTokens.mode == NeuMode.LIGHT
            val side = dp(4).toFloat()
            val top = dp(3).toFloat()
            val bottom = dp(3).toFloat()
            val radius = dp(24).toFloat()

            // Outer shadow: the keyboard has lifted out, leaving a precise molded recess.
            rect.set(side, top + dp(5), width - side, height - bottom + dp(2))
            paint.shader = android.graphics.RadialGradient(
                width / 2f,
                height * 0.55f,
                width * 0.72f,
                intArrayOf(
                    adjustAlpha(0xFF000000.toInt(), if (light) 0.22f else 0.62f),
                    adjustAlpha(0xFF000000.toInt(), if (light) 0.10f else 0.32f),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, paint)

            rect.set(side, top, width - side, height - bottom)
            paint.shader = android.graphics.LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                if (light) intArrayOf(
                    0xFFD8DDE5.toInt(),
                    0xFFECEFF4.toInt(),
                    0xFFC1C8D3.toInt()
                ) else intArrayOf(
                    0xFF030405.toInt(),
                    0xFF0A0D11.toInt(),
                    0xFF151920.toInt(),
                    0xFF020203.toInt()
                ),
                if (light) floatArrayOf(0f, 0.46f, 1f) else floatArrayOf(0f, 0.30f, 0.58f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null

            // Inner bevels sell the "hole" rather than a blank strip.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = adjustAlpha(if (light) 0xFFFFFFFF.toInt() else 0xFF9DA6B4.toInt(), if (light) 0.54f else 0.12f + glow * 0.18f)
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.color = adjustAlpha(0xFF000000.toInt(), if (light) 0.16f else 0.72f)
            canvas.drawRoundRect(RectF(rect.left + dp(2), rect.top + dp(3), rect.right - dp(2), rect.bottom - dp(1)), radius - dp(3), radius - dp(3), paint)
            paint.style = Paint.Style.FILL

            val inner = RectF(rect.left + dp(8), rect.top + dp(8), rect.right - dp(8), rect.bottom - dp(8))
            paint.shader = android.graphics.LinearGradient(
                0f, inner.top, 0f, inner.bottom,
                if (light) intArrayOf(0xFFC7CDD7.toInt(), 0xFFE0E4EA.toInt(), 0xFFB4BCC8.toInt())
                else intArrayOf(0xFF010102.toInt(), 0xFF07090D.toInt(), 0xFF10141A.toInt(), 0xFF010102.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(inner, dp(20).toFloat(), dp(20).toFloat(), paint)
            paint.shader = null

            val stripChannel = RectF(inner.left + dp(6), inner.top + dp(6), inner.right - dp(6), inner.top + dp(36))
            paint.shader = android.graphics.LinearGradient(
                0f, stripChannel.top, 0f, stripChannel.bottom,
                if (light) intArrayOf(0xFFB8C0CB.toInt(), 0xFFE8EBF0.toInt(), 0xFFCBD1DA.toInt())
                else intArrayOf(0xFF010102.toInt(), 0xFF10151C.toInt(), 0xFF030405.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(stripChannel, dp(13).toFloat(), dp(13).toFloat(), paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = adjustAlpha(if (light) 0xFFFFFFFF.toInt() else 0xFFFFFFFF.toInt(), if (light) 0.42f else 0.08f)
            canvas.drawRoundRect(stripChannel, dp(13).toFloat(), dp(13).toFloat(), paint)
            paint.color = adjustAlpha(0xFF000000.toInt(), if (light) 0.12f else 0.52f)
            canvas.drawLine(stripChannel.left + dp(10), stripChannel.bottom - dp(1), stripChannel.right - dp(10), stripChannel.bottom - dp(1), paint)
            paint.style = Paint.Style.FILL

            val keyWellTop = stripChannel.bottom + dp(8)
            val keyWellBottom = inner.bottom - dp(26)
            val rowCount = 4
            val rowGap = dp(6).toFloat()
            val rowHeight = ((keyWellBottom - keyWellTop) - rowGap * (rowCount - 1)) / rowCount
            repeat(rowCount) { row ->
                val inset = dp(8 + row * 5).toFloat()
                val y = keyWellTop + row * (rowHeight + rowGap)
                val rowRect = RectF(inner.left + inset, y, inner.right - inset, y + rowHeight)
                paint.shader = android.graphics.LinearGradient(
                    0f, rowRect.top, 0f, rowRect.bottom,
                    if (light) intArrayOf(0xFFB7BEC9.toInt(), 0xFFDDE2EA.toInt(), 0xFFAAB3C0.toInt())
                    else intArrayOf(0xFF020304.toInt(), 0xFF0C1016.toInt(), 0xFF010102.toInt()),
                    null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(rowRect, dp(11).toFloat(), dp(11).toFloat(), paint)
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1).toFloat()
                paint.color = adjustAlpha(if (light) 0xFFFFFFFF.toInt() else 0xFF96A0AE.toInt(), if (light) 0.35f else 0.07f)
                canvas.drawRoundRect(rowRect, dp(11).toFloat(), dp(11).toFloat(), paint)
                paint.color = adjustAlpha(0xFF000000.toInt(), if (light) 0.11f else 0.58f)
                canvas.drawLine(rowRect.left + dp(8), rowRect.bottom, rowRect.right - dp(8), rowRect.bottom, paint)
                paint.style = Paint.Style.FILL
            }

            val stripWidth = (width * 0.42f).coerceAtMost(dp(178).toFloat())
            val stripHeight = dp(13).toFloat()
            val stripLeft = width / 2f - stripWidth / 2f
            val stripTop = height - dp(23).toFloat()
            val strip = RectF(stripLeft, stripTop, stripLeft + stripWidth, stripTop + stripHeight)
            paint.shader = android.graphics.LinearGradient(
                0f,
                strip.top,
                0f,
                strip.bottom,
                if (light) intArrayOf(0xFFB2BBC7.toInt(), 0xFFE6EAF0.toInt(), 0xFFA6B0BD.toInt())
                else intArrayOf(0xFF050608.toInt(), 0xFF15191E.toInt(), 0xFF030304.toInt()),
                if (light) null else floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(strip, stripHeight / 2f, stripHeight / 2f, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = adjustAlpha(0xFFFFFFFF.toInt(), if (light) 0.34f else 0.08f + glow * 0.2f)
            canvas.drawRoundRect(strip, stripHeight / 2f, stripHeight / 2f, paint)
            paint.style = Paint.Style.FILL
            val contactColor = if (glow > 0.05f) 0xFF9AF5AE.toInt() else 0xFF57606B.toInt()
            val gap = stripWidth / 7f
            repeat(6) { i ->
                val cx = stripLeft + gap * (i + 1)
                val cy = strip.centerY()
                paint.shader = android.graphics.RadialGradient(
                    cx,
                    cy,
                    dp(10).toFloat(),
                    intArrayOf(adjustAlpha(contactColor, 0.22f + glow * 0.42f), Color.TRANSPARENT),
                    null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, dp(9).toFloat(), paint)
                paint.shader = null
                paint.color = adjustAlpha(contactColor, if (glow > 0.05f) 0.7f else 0.32f)
                canvas.drawCircle(cx, cy, dp(2).toFloat(), paint)
            }
        }
    }

    private inner class ConnectorProngsView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var pulse = 0f

        fun pulse() {
            ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 520L
                addUpdateListener {
                    pulse = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val strip = RectF(dp(7).toFloat(), dp(3).toFloat(), width - dp(7).toFloat(), height - dp(3).toFloat())
            paint.shader = android.graphics.LinearGradient(
                0f,
                strip.top,
                0f,
                strip.bottom,
                intArrayOf(0x441E242C, 0xAA07090D.toInt(), 0x66000000),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(strip, strip.height() / 2f, strip.height() / 2f, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = adjustAlpha(0xFFFFFFFF.toInt(), 0.08f + pulse * 0.2f)
            canvas.drawRoundRect(strip, strip.height() / 2f, strip.height() / 2f, paint)
            paint.style = Paint.Style.FILL
            val contactColor = if (pulse > 0.05f) 0xFFB9FFC5.toInt() else 0xFF65707B.toInt()
            val gap = strip.width() / 7f
            repeat(6) { i ->
                val cx = strip.left + gap * (i + 1)
                val contact = RectF(cx - dp(3).toFloat(), strip.centerY() - dp(2).toFloat(), cx + dp(3).toFloat(), strip.centerY() + dp(2).toFloat())
                paint.shader = android.graphics.LinearGradient(
                    contact.left,
                    contact.top,
                    contact.right,
                    contact.bottom,
                    intArrayOf(adjustAlpha(contactColor, 0.52f + pulse * 0.34f), adjustAlpha(0xFFFFFFFF.toInt(), 0.16f + pulse * 0.24f), adjustAlpha(contactColor, 0.28f + pulse * 0.28f)),
                    null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(contact, dp(2).toFloat(), dp(2).toFloat(), paint)
            }
            paint.shader = null
        }
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
        if (keyboardTheme == KEYBOARD_THEME_DEFAULT) return Neu.drawable(activeNeuTokens, dp(16).toFloat(), NeuLevel.RAISED)
        val light = keyboardLightMode()
        val colors = if (light) {
            when (keyboardTheme) {
                KEYBOARD_THEME_SKEUO -> intArrayOf(0xFFF1F3F6.toInt(), 0xFFE0E4EA.toInt(), 0xFFC9D0DA.toInt())
                KEYBOARD_THEME_CLICKS -> intArrayOf(0xFFECEFF4.toInt(), 0xFFDDE2E9.toInt(), 0xFFC7CED9.toInt())
                KEYBOARD_THEME_GOKEYS -> intArrayOf(0xFFF3F5F8.toInt(), 0xFFE2E6EC.toInt(), 0xFFC9D0DA.toInt())
                KEYBOARD_THEME_HYPER3D, KEYBOARD_THEME_HYPER3D_LIGHT -> intArrayOf(0xFFE8EBF0.toInt(), 0xFFD4D8E0.toInt(), 0xFFBEC4CF.toInt())
                else -> intArrayOf(0xFFECEEF2.toInt(), 0xFFDDE1E8.toInt(), 0xFFC9D0DA.toInt())
            }
        } else {
            when (keyboardTheme) {
                KEYBOARD_THEME_SKEUO -> intArrayOf(0xFF24262D.toInt(), 0xFF101116.toInt(), 0xFF050506.toInt())
                KEYBOARD_THEME_CLICKS -> intArrayOf(0xFF1A1C21.toInt(), 0xFF111318.toInt(), 0xFF08090C.toInt(), 0xFF030304.toInt())
                KEYBOARD_THEME_GOKEYS -> intArrayOf(0xFF15171B.toInt(), 0xFF101115.toInt(), 0xFF0B0C0F.toInt())
                KEYBOARD_THEME_HYPER3D_BLACK -> intArrayOf(0xFF0C0C0E.toInt(), 0xFF080809.toInt(), 0xFF050506.toInt())
                KEYBOARD_THEME_HYPER3D_LIGHT -> intArrayOf(0xFFE8EBF0.toInt(), 0xFFD4D8E0.toInt(), 0xFFBEC4CF.toInt())
                KEYBOARD_THEME_HYPER3D -> intArrayOf(0xFF171A20.toInt(), 0xFF0D0F13.toInt(), 0xFF050608.toInt())
                else -> intArrayOf(0xFF1F2127.toInt(), 0xFF0C0D10.toInt(), 0xFF030304.toInt())
            }
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = if (keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET || keyboardTheme == KEYBOARD_THEME_GOKEYS) dp(22).toFloat() else 0f
            setStroke(dp(1), if (light) 0x66FFFFFF else when (keyboardTheme) {
                KEYBOARD_THEME_SKEUO -> 0x303B4250
                KEYBOARD_THEME_GOKEYS -> 0x14FFFFFF
                KEYBOARD_THEME_HYPER3D_LIGHT -> 0x66FFFFFF
                else -> 0x181B20
            })
        }
    }

    private fun keyboardLightMode(): Boolean {
        return activeNeuTokens.mode == NeuMode.LIGHT && keyboardTheme != KEYBOARD_THEME_HYPER3D_BLACK
    }

    private fun hyper3dVisualTheme(): String {
        return if (keyboardTheme == KEYBOARD_THEME_HYPER3D && keyboardLightMode()) KEYBOARD_THEME_HYPER3D_LIGHT else keyboardTheme
    }

    private fun keyIdleBackground(label: String): Drawable {
        hyper3dKeyDrawable(label)?.let { return it }
        if (keyboardTheme == KEYBOARD_THEME_GOKEYS) return goKeysKeyBackground(label, pressed = false)
        if (label == "enter") return themedGoKeyBackground(goKeyColor, pressed = false)
        if (label == "123") return themed123KeyBackground(pressed = false)
        return when (keyboardTheme) {
            KEYBOARD_THEME_CLICKS -> physicalKeyBackground(pressed = false, premium = false, fn = isFnKey(label))
            KEYBOARD_THEME_SKEUO -> physicalKeyBackground(pressed = false, premium = true, fn = isFnKey(label))
            else -> keyBackground()
        }
    }

    private fun keyPressedBackground(label: String): Drawable {
        hyper3dKeyDrawable(label)?.let { return it }
        if (keyboardTheme == KEYBOARD_THEME_GOKEYS) return goKeysKeyBackground(label, pressed = true)
        if (label == "enter") return themedGoKeyBackground(brighten(goKeyColor), pressed = true)
        if (label == "123") return themed123KeyBackground(pressed = true)
        return when (keyboardTheme) {
            KEYBOARD_THEME_CLICKS -> physicalKeyBackground(pressed = true, premium = false, fn = isFnKey(label))
            KEYBOARD_THEME_SKEUO -> physicalKeyBackground(pressed = true, premium = true, fn = isFnKey(label))
            else -> keyBackground(KeyHighlight)
        }
    }

    private fun hyper3dKeyDrawable(label: String): Drawable? {
        val id = when (hyper3dVisualTheme()) {
            KEYBOARD_THEME_HYPER3D -> when {
                label == "enter" -> R.drawable.key_hyper3d_go
                isFnKey(label) -> R.drawable.key_hyper3d_dark
                else -> R.drawable.key_hyper3d
            }
            KEYBOARD_THEME_HYPER3D_BLACK -> when {
                label == "enter" -> R.drawable.key_hyper3d_black_go
                label == "clicks" -> R.drawable.key_hyper3d_black_accent
                isFnKey(label) -> R.drawable.key_hyper3d_black_dark
                else -> R.drawable.key_hyper3d_black
            }
            KEYBOARD_THEME_HYPER3D_LIGHT -> when {
                label == "enter" -> R.drawable.key_hyper3d_light_go
                label == "clicks" -> R.drawable.key_hyper3d_light_accent
                isFnKey(label) -> R.drawable.key_hyper3d_light_dark
                else -> R.drawable.key_hyper3d_light
            }
            else -> return null
        }
        return runCatching { resources.getDrawable(id, theme) }.getOrNull()
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
        if (keyboardLightMode()) {
            return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                if (pressed) 0xFFD0D6DF.toInt() else 0xFFF3F5F8.toInt(),
                if (pressed) 0xFFB8C1CE.toInt() else 0xFFDCE2EA.toInt()
            )).apply {
                cornerRadius = radius
                setStroke(dp(1), if (pressed) 0xFFAAB4C1.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return GradientDrawable().apply {
            setColor(if (pressed) 0xFF1A1D22.toInt() else 0xFF22262D.toInt())
            cornerRadius = radius
            setStroke(dp(1), if (pressed) 0xFF16191E.toInt() else 0xFF2E333B.toInt())
        }
    }

    private fun themed123KeyBackground(pressed: Boolean): Drawable {
        hyper3dKeyDrawable("123")?.let { return it }
        if (keyboardTheme == KEYBOARD_THEME_DEFAULT) return Neu.drawable(activeNeuTokens, dp(99).toFloat(), if (pressed) NeuLevel.PRESSED_SM else NeuLevel.RAISED_SM)
        val clicks = keyboardTheme == KEYBOARD_THEME_CLICKS
        if (keyboardLightMode()) {
            val faceTop = if (pressed) 0xFFD1D8E2.toInt() else 0xFFF1F4F8.toInt()
            val faceMid = if (pressed) 0xFFBEC7D3.toInt() else 0xFFDCE3EC.toInt()
            val faceBot = if (pressed) 0xFFA8B3C0.toInt() else 0xFFC4CDD8.toInt()
            val skirt = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x668A96A6) }
            val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(faceTop, faceMid, faceBot)).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), 0xFFFFFFFF.toInt())
            }
            val glint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x88FFFFFF.toInt(), 0x00FFFFFF)).apply { shape = GradientDrawable.OVAL }
            return LayerDrawable(arrayOf(skirt, face, glint)).apply {
                val drop = if (pressed) dp(1) else dp(5)
                setLayerInset(0, dp(1), drop, dp(1), 0)
                setLayerInset(1, dp(2), 0, dp(2), drop)
                setLayerInset(2, dp(8), dp(5), dp(8), dp(18))
            }
        }
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

    private fun keyBackground(fillColor: Int?): Drawable {
        return if (fillColor != null) {
            Neu.drawable(activeNeuTokens, dp(7).toFloat(), NeuLevel.PRESSED_SM)
        } else {
            Neu.drawable(activeNeuTokens, dp(7).toFloat(), NeuLevel.RAISED_SM)
        }
    }

    private fun physicalKeyBackground(pressed: Boolean, premium: Boolean, fn: Boolean): Drawable {
        val clicks = keyboardTheme == KEYBOARD_THEME_CLICKS
        val radius = dp(when {
            premium -> 10
            clicks -> 5
            else -> 9
        }).toFloat()
        if (keyboardLightMode()) {
            val skirt = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                0xFFB3BCC9.toInt(),
                0xFF8E99A8.toInt(),
                0xFF687482.toInt()
            )).apply {
                cornerRadius = radius
                setStroke(dp(1), 0x88FFFFFF.toInt())
            }
            val outerRim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                if (pressed) 0xFFD3DBE5.toInt() else 0xFFFFFFFF.toInt(),
                if (pressed) 0xFFAEB8C6.toInt() else 0xFFCBD3DE.toInt()
            )).apply {
                cornerRadius = radius
                setStroke(dp(1), 0xFFFFFFFF.toInt())
            }
            val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                if (pressed) 0xFFD2DAE4.toInt() else 0xFFF5F7FA.toInt(),
                if (pressed) 0xFFBBC5D1.toInt() else 0xFFDCE3EC.toInt(),
                if (pressed) 0xFFA4AFBD.toInt() else 0xFFC2CCD8.toInt()
            )).apply {
                cornerRadius = radius
                setStroke(dp(1), if (pressed) 0xFFA6B1BE.toInt() else 0xFFFFFFFF.toInt())
            }
            val topGlint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99FFFFFF.toInt(), 0x00FFFFFF)).apply {
                cornerRadius = radius
            }
            val warmBleed = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(
                if (fn && !pressed) 0x22F5C451 else if (pressed) 0x33FF5A3C else 0x00000000,
                0x00000000
            )).apply {
                cornerRadius = radius
            }
            return LayerDrawable(arrayOf(skirt, outerRim, face, warmBleed, topGlint)).apply {
                val drop = if (pressed) dp(1) else dp(if (premium) 7 else 6)
                val side = dp(2)
                setLayerInset(0, dp(1), drop, dp(1), 0)
                setLayerInset(1, side, dp(if (pressed) 1 else 0), side, drop)
                setLayerInset(2, side + dp(1), dp(1), side + dp(1), drop + dp(1))
                setLayerInset(3, side + dp(2), dp(4), side + dp(2), drop + dp(1))
                setLayerInset(4, side + dp(3), dp(2), side + dp(3), dp(20))
            }
        }
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
        hyper3dKeyDrawable("enter")?.let { return it }
        if (keyboardTheme == KEYBOARD_THEME_DEFAULT) return Neu.drawable(activeNeuTokens, dp(99).toFloat(), if (pressed) NeuLevel.PRESSED_SM else NeuLevel.RAISED_SM)
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
            KEYBOARD_THEME_HYPER3D -> 0xFF6C89D8.toInt()
            KEYBOARD_THEME_HYPER3D_BLACK -> 0xFF2C3038.toInt()
            KEYBOARD_THEME_HYPER3D_LIGHT -> 0xFFE7EAF0.toInt()
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
        if (keyboardTheme == KEYBOARD_THEME_CLICKS || keyboardTheme == KEYBOARD_THEME_GOKEYS || isHyper3dTheme()) return dp(10 + keyboardSize * 5 / 100)
        return dp(7 + keyboardSize * 4 / 100)
    }

    private fun keyRowOverlap(): Int {
        return dp(8 + keyboardSize * 3 / 100)
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
    private fun keyboardHeight(): Int {
        val rowCount = 4
        val rowsHeight = keyRowHeight() * rowCount
        val overlap = if (keyboardSettingsOpen) 0 else keyRowOverlap() * (rowCount - 1)
        val settingsHeight = if (keyboardSettingsOpen) dp(312) else 0
        return rowsHeight - overlap + keyboardTopPadding() + keyboardBottomPadding() + settingsHeight + suggestionStripHeight()
    }
    private fun keyboardTopPadding() = dp(4)
    private fun keyboardBottomPadding() = dp(2)
    private fun keyboardBottomLift() = dp(3)

    private fun keyTextSize(label: String): Float {
        if (numberPadOpen && label.length == 1 && label[0].isDigit()) return 26f + keyboardSize * 2f / 100f
        val base = when (label) { "shift" -> 24f; "space" -> 18f; "123", "clicks", "enter", "back", "period", "abc" -> 13.5f; else -> 20f }
        val growth = when (label) { "shift" -> 2.5f; "space" -> 2f; "123", "clicks", "enter", "back", "period", "abc" -> 1.5f; else -> 2.5f }
        return base + (keyboardSize * growth / 100f)
    }

    private fun keyTextColor(label: String) = if (isHyper3dTheme()) {
        val visualTheme = hyper3dVisualTheme()
        when {
            visualTheme == KEYBOARD_THEME_HYPER3D_LIGHT && label == "enter" -> 0xFF104026.toInt()
            visualTheme == KEYBOARD_THEME_HYPER3D_LIGHT && isFnKey(label) -> 0xFF596170.toInt()
            visualTheme == KEYBOARD_THEME_HYPER3D_LIGHT -> 0xFF1E2633.toInt()
            visualTheme == KEYBOARD_THEME_HYPER3D_BLACK && label == "enter" -> 0xFFFF6B6B.toInt()
            visualTheme == KEYBOARD_THEME_HYPER3D_BLACK && label == "clicks" -> 0xFF9DB4FF.toInt()
            visualTheme == KEYBOARD_THEME_HYPER3D_BLACK && isFnKey(label) -> 0xFF9AA2B1.toInt()
            visualTheme == KEYBOARD_THEME_HYPER3D && label == "enter" -> 0xFFEAFFF2.toInt()
            visualTheme == KEYBOARD_THEME_HYPER3D && label == "clicks" -> 0xFFEAF0FF.toInt()
            visualTheme == KEYBOARD_THEME_HYPER3D && isFnKey(label) -> 0xFF9AA2B1.toInt()
            else -> 0xFFEEF1F7.toInt()
        }
    } else if (keyboardTheme == KEYBOARD_THEME_GOKEYS) {
        if (keyboardLightMode()) {
            when (label) {
                "enter" -> 0xFFFFFFFF.toInt()
                "shift" -> when (shiftState) { ShiftState.OFF -> 0xFF6F7884.toInt(); ShiftState.ONCE -> 0xFF242A33.toInt(); ShiftState.LOCK -> 0xFFF2691E.toInt() }
                "123", "clicks", "back", "period", "abc", "space" -> 0xFF6F7884.toInt()
                else -> 0xFF202733.toInt()
            }
        } else
        when (label) {
            "enter" -> 0xFFFFFFFF.toInt()
            "shift" -> when (shiftState) { ShiftState.OFF -> 0xFF9AA1AB.toInt(); ShiftState.ONCE -> Ink; ShiftState.LOCK -> 0xFFF2691E.toInt() }
            "123", "clicks", "back", "period", "abc", "space" -> 0xFF9AA1AB.toInt()
            else -> 0xFFF3F5F8.toInt()
        }
    } else if (keyboardLightMode()) {
        when (label) {
            "enter" -> Neu.ACCENT
            "clicks" -> 0xFF2F6C4C.toInt()
            "shift" -> when (shiftState) { ShiftState.OFF -> 0xFF6F7884.toInt(); ShiftState.ONCE -> 0xFF242A33.toInt(); ShiftState.LOCK -> Neu.ACCENT }
            "123", "back", "period", "abc" -> 0xFF6F7884.toInt()
            else -> 0xFF202733.toInt()
        }
    } else when (label) {
            "enter" -> Neu.ACCENT
            "clicks" -> Neu.GREEN
            "shift" -> when (shiftState) { ShiftState.OFF -> activeNeuTokens.inkDim; ShiftState.ONCE -> activeNeuTokens.ink; ShiftState.LOCK -> Neu.ACCENT }
            "123", "back", "period", "abc" -> activeNeuTokens.inkDim
            else -> activeNeuTokens.ink
    }

    private fun isHyper3dTheme(): Boolean {
        return keyboardTheme == KEYBOARD_THEME_HYPER3D ||
            keyboardTheme == KEYBOARD_THEME_HYPER3D_BLACK ||
            keyboardTheme == KEYBOARD_THEME_HYPER3D_LIGHT
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun selectedNeuTokens(): NeuTokens {
        return when (themeMode) {
            THEME_MODE_DARK -> Neu.Dark
            THEME_MODE_LIGHT -> Neu.Light
            else -> if (isSystemDarkMode()) Neu.Dark else Neu.Light
        }
    }

    private fun applyTheme(): Boolean {
        val next = selectedNeuTokens()
        val changed = activeNeuTokens.mode != next.mode || activeNeuTokens.base != next.base
        activeNeuTokens = next
        return changed
    }

    private fun updateLauncherTheme(animated: Boolean, forceRender: Boolean = false) {
        val oldColor = activeNeuTokens.base
        val changed = applyTheme()
        if (!::rootView.isInitialized) return
        if (!changed && !forceRender) return
        render()
        if (animated && changed) {
            showThemeSplash(oldColor, activeNeuTokens.base, goKeyColor)
        }
    }

    private fun showThemeSplash(fromColor: Int, toColor: Int, accentColor: Int) {
        themeSplashAnimator?.cancel()
        themeSplashView?.let { old ->
            (old.parent as? ViewGroup)?.removeView(old)
        }
        val parent = window.decorView as? ViewGroup ?: return
        val splash = ThemeSplashView(this, fromColor, toColor, accentColor).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        themeSplashView = splash
        parent.addView(splash, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        themeSplashAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 680L
            interpolator = DecelerateInterpolator(1.45f)
            addUpdateListener {
                splash.progress = it.animatedValue as Float
                splash.invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    (splash.parent as? ViewGroup)?.removeView(splash)
                    if (themeSplashView === splash) themeSplashView = null
                    if (themeSplashAnimator === this@apply) themeSplashAnimator = null
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    (splash.parent as? ViewGroup)?.removeView(splash)
                    if (themeSplashView === splash) themeSplashView = null
                }
            })
            start()
        }
    }

    private fun applyGoKeyColor(color: Int, refreshSettings: Boolean) {
        if (goKeyColor == color) return
        val oldAccent = goKeyColor
        goKeyColor = color
        prefs().edit().putInt(GO_KEY_COLOR_PREF, goKeyColor).apply()
        refreshKeyboardDock()
        if (refreshSettings && openPane?.kind == PaneKind.SETTINGS) {
            renderPaneContent(clicksSettingsTarget())
        }
        if (::rootView.isInitialized) {
            showThemeSplash(activeNeuTokens.base, activeNeuTokens.base, blendColors(oldAccent, color, 0.72f))
        }
    }

    private fun blendColors(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt().coerceIn(0, 255),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt().coerceIn(0, 255),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt().coerceIn(0, 255)
        )
    }

    private fun isSystemDarkMode(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun haptic(view: View) {
        if (!hapticsEnabled) return
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun dockHoldHaptic(stage: Int) {
        if (!hapticsEnabled) return
        hapticEngine.dockHoldStage(stage)
    }

    private fun dockDetachHaptic(view: View) {
        if (!hapticsEnabled) return
        hapticEngine.dockDetachSnap()
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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

    private inner class ThemeSplashView(
        context: Context,
        private val fromColor: Int,
        private val toColor: Int,
        private val accentColor: Int
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var progress: Float = 0f

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            val eased = (1f - (1f - progress) * (1f - progress)).coerceIn(0f, 1f)
            val fade = (1f - eased).coerceIn(0f, 1f)
            canvas.drawColor(adjustAlpha(fromColor, (248 * fade).toInt()))

            val maxRadius = kotlin.math.hypot(width.toFloat(), height.toFloat())
            val radius = maxRadius * (0.12f + eased * 0.96f)
            val cx = width * 0.58f
            val cy = height * 0.74f
            paint.shader = RadialGradient(
                cx,
                cy,
                radius,
                intArrayOf(
                    adjustAlpha(accentColor, 0.30f * fade),
                    adjustAlpha(toColor, 0.24f * fade),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.46f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius, paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = adjustAlpha(if (activeNeuTokens.mode == NeuMode.LIGHT) Color.WHITE else Color.BLACK, 0.18f * fade)
            canvas.drawCircle(cx, cy, radius * 0.46f, paint)
            paint.style = Paint.Style.FILL
        }
    }

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

    private inner class LibraryDrawerView(context: Context) : FrameLayout(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var ambientColor = Accent2
        private var ambientStrength = 0.45f
        private var mode = NeuMode.DARK
        private var dragProgress = 1f

        init {
            setWillNotDraw(false)
            clipToPadding = false
            clipChildren = false
        }

        fun setAmbient(color: Int, strength: Float, neuMode: NeuMode) {
            ambientColor = color
            ambientStrength = strength.coerceIn(0f, 1f)
            mode = neuMode
            invalidate()
        }

        fun setDragProgress(progress: Float) {
            dragProgress = progress.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            val base = activeNeuTokens.base
            val baseHi = activeNeuTokens.baseHi
            val baseLo = activeNeuTokens.baseLo
            paint.shader = android.graphics.LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(baseHi, base, baseLo),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null

            val glowAlpha = (if (mode == NeuMode.LIGHT) 0.12f else 0.23f) * ambientStrength * (0.45f + dragProgress * 0.55f)
            paint.shader = RadialGradient(
                width * 0.24f,
                height * 0.20f,
                width * 0.82f,
                intArrayOf(
                    adjustAlpha(ambientColor, glowAlpha),
                    adjustAlpha(ambientColor, glowAlpha * 0.34f),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(width * 0.24f, height * 0.20f, width * 0.82f, paint)
            paint.shader = null

            val edgeWidth = dp(34).toFloat()
            rect.set(0f, dp(78).toFloat(), edgeWidth, height - dp(74).toFloat())
            paint.shader = android.graphics.LinearGradient(
                rect.left, 0f, rect.right, 0f,
                intArrayOf(
                    adjustAlpha(baseHi, if (mode == NeuMode.LIGHT) 0.38f else 0.24f),
                    adjustAlpha(base, 0.86f),
                    adjustAlpha(baseLo, if (mode == NeuMode.LIGHT) 0.34f else 0.72f)
                ),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, dp(18).toFloat(), dp(18).toFloat(), paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = adjustAlpha(0xFFFFFFFF.toInt(), if (mode == NeuMode.LIGHT) 0.32f else 0.14f)
            canvas.drawLine(dp(4).toFloat(), rect.top + dp(16), dp(4).toFloat(), rect.bottom - dp(16), paint)
            paint.color = adjustAlpha(0xFF000000.toInt(), if (mode == NeuMode.LIGHT) 0.18f else 0.58f)
            canvas.drawLine(edgeWidth - dp(2).toFloat(), rect.top + dp(18), edgeWidth - dp(2).toFloat(), rect.bottom - dp(18), paint)

            paint.style = Paint.Style.FILL
            val gripHeight = dp(74).toFloat()
            val gripTop = height / 2f - gripHeight / 2f
            rect.set(dp(10).toFloat(), gripTop, dp(16).toFloat(), gripTop + gripHeight)
            paint.shader = android.graphics.LinearGradient(
                rect.left, 0f, rect.right, 0f,
                intArrayOf(adjustAlpha(baseLo, 0.78f), adjustAlpha(baseHi, 0.24f), adjustAlpha(ambientColor, 0.18f * dragProgress)),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, rect.width() / 2f, rect.width() / 2f, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL

            super.onDraw(canvas)
        }
    }

    private class WeatherParticle(var x: Float, var y: Float, var vx: Float, var vy: Float,
                                  var len: Float, var size: Float, var sway: Float, var phase: Float, var a: Float)

    private inner class WeatherAmbientView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var weatherCode = 0
        private var mode = NeuMode.DARK
        private var progress = 1f
        private var animator: ValueAnimator? = null

        // Precipitation particles: rain / snow / thunderstorm (rain + lightning) / fog.
        private val parts = ArrayList<WeatherParticle>()
        private val fog = ArrayList<WeatherParticle>()
        private var running = false
        private var lastFrame = 0L
        private var flash = 0f
        private var nextBolt = 2.5f
        private val rnd = java.util.Random()

        fun setWeather(code: Int, neuMode: NeuMode, animate: Boolean) {
            val changed = weatherCode != code || mode != neuMode
            weatherCode = code
            mode = neuMode
            animator?.cancel()
            if (animate && changed) {
                progress = 0f
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = WEATHER_AMBIENT_BURST_MS
                    interpolator = DecelerateInterpolator(1.6f)
                    addUpdateListener {
                        progress = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            } else {
                progress = 1f
                invalidate()
            }
            rebuildParticles()
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            rebuildParticles()
        }

        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            if (isVisible) rebuildParticles() else stopLoop()
        }

        override fun onDetachedFromWindow() {
            animator?.cancel()
            animator = null
            stopLoop()
            super.onDetachedFromWindow()
        }

        // 0 none, 1 rain/drizzle, 2 snow, 3 thunderstorm, 4 fog — WMO codes.
        private fun precip(): Int = when {
            weatherCode == 45 || weatherCode == 48 -> 4
            weatherCode == 95 || weatherCode == 96 || weatherCode == 99 -> 3
            weatherCode in intArrayOf(71, 73, 75, 77, 85, 86) -> 2
            weatherCode in intArrayOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82) -> 1
            else -> 0
        }

        private fun heavy(): Float = when (weatherCode) {
            55, 57, 65, 67, 75, 82, 86, 99 -> 1f
            53, 63, 73, 81, 85, 96 -> 0.62f
            else -> 0.38f
        }

        private fun startLoop() { if (!running) { running = true; lastFrame = 0L; postInvalidateOnAnimation() } }
        private fun stopLoop() { running = false }

        private fun rebuildParticles() {
            parts.clear(); fog.clear()
            val p = precip()
            if (p == 0 || !animatedWeatherEnabled()) { stopLoop(); invalidate(); return }
            if (width > 0 && height > 0) seed(p)
            startLoop()
        }

        private fun seed(p: Int) {
            parts.clear(); fog.clear()
            val d = resources.displayMetrics.density
            val w = width.toFloat(); val h = height.toFloat()
            if (p == 4) {
                repeat(7) {
                    fog.add(WeatherParticle(rnd.nextFloat() * w, h * (0.08f + rnd.nextFloat() * 0.55f),
                        (7 + rnd.nextFloat() * 13) * d * (if (rnd.nextBoolean()) 1f else -1f), 0f,
                        0f, (120 + rnd.nextFloat() * 170) * d, 0f, 0f, 0.05f + rnd.nextFloat() * 0.06f))
                }
                return
            }
            val n = if (p == 2) (42 + 60 * heavy()).toInt() else (72 + 120 * heavy()).toInt()
            repeat(n) { parts.add(spawn(p, d, w, h, true)) }
        }

        private fun spawn(p: Int, d: Float, w: Float, h: Float, anyY: Boolean): WeatherParticle {
            val x = rnd.nextFloat() * w
            val y = if (anyY) rnd.nextFloat() * h else -rnd.nextFloat() * h * 0.25f
            return if (p == 2) {
                WeatherParticle(x, y, 0f, (55 + rnd.nextFloat() * 55) * d, 0f, (1.6f + rnd.nextFloat() * 2.6f) * d,
                    (12 + rnd.nextFloat() * 22) * d, rnd.nextFloat() * 6.28f, 0.5f + rnd.nextFloat() * 0.5f)
            } else {
                WeatherParticle(x, y, 70f * d, (900 + rnd.nextFloat() * 500 + heavy() * 500) * d,
                    (14 + rnd.nextFloat() * 16 + heavy() * 12) * d, (1f + rnd.nextFloat()) * d, 0f, 0f,
                    0.22f + rnd.nextFloat() * 0.33f)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            drawGlows(canvas)
            val p = precip()
            if (!running || p == 0 || !animatedWeatherEnabled()) return
            if (parts.isEmpty() && fog.isEmpty()) seed(p)
            val now = System.currentTimeMillis()
            val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
            lastFrame = now
            when (p) {
                4 -> drawFog(canvas, dt)
                2 -> drawSnow(canvas, dt)
                else -> { drawRain(canvas, dt); if (p == 3) drawLightning(canvas, dt) }
            }
            postInvalidateOnAnimation()
        }

        private fun drawRain(canvas: Canvas, dt: Float) {
            val w = width.toFloat(); val h = height.toFloat()
            val color = if (mode == NeuMode.LIGHT) 0xFF6E7A88.toInt() else 0xFFBFD0E8.toInt()
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            for (d in parts) {
                d.x += d.vx * dt; d.y += d.vy * dt
                if (d.y - d.len > h) { d.y = -rnd.nextFloat() * h * 0.15f; d.x = rnd.nextFloat() * w }
                if (d.x > w) d.x -= w
                paint.strokeWidth = d.size
                paint.color = adjustAlpha(color, d.a)
                canvas.drawLine(d.x, d.y, d.x - d.vx * 0.016f, d.y - d.len, paint)
            }
            paint.style = Paint.Style.FILL
        }

        private fun drawSnow(canvas: Canvas, dt: Float) {
            val w = width.toFloat(); val h = height.toFloat()
            val color = if (mode == NeuMode.LIGHT) 0xFFFFFFFF.toInt() else 0xFFEAF1FF.toInt()
            paint.style = Paint.Style.FILL
            for (d in parts) {
                d.phase += dt * 1.6f
                d.y += d.vy * dt
                d.x += kotlin.math.sin(d.phase.toDouble()).toFloat() * d.sway * dt
                if (d.y - d.size > h) { d.y = -d.size - rnd.nextFloat() * h * 0.1f; d.x = rnd.nextFloat() * w }
                paint.color = adjustAlpha(color, d.a)
                canvas.drawCircle(d.x, d.y, d.size, paint)
            }
        }

        private fun drawFog(canvas: Canvas, dt: Float) {
            val w = width.toFloat()
            val color = if (mode == NeuMode.LIGHT) 0xFFFFFFFF.toInt() else 0xFFB8C2D0.toInt()
            for (f in fog) {
                f.x += f.vx * dt
                if (f.x - f.size > w) f.x = -f.size
                if (f.x + f.size < 0f) f.x = w + f.size
                paint.shader = RadialGradient(f.x, f.y, f.size.coerceAtLeast(1f),
                    intArrayOf(adjustAlpha(color, f.a), 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                canvas.drawCircle(f.x, f.y, f.size, paint)
                paint.shader = null
            }
        }

        private fun drawLightning(canvas: Canvas, dt: Float) {
            nextBolt -= dt
            if (nextBolt <= 0f) { flash = 1f; nextBolt = 3.5f + rnd.nextFloat() * 6f }
            if (flash > 0f) {
                paint.color = adjustAlpha(0xFFFFFFFF.toInt(), 0.11f * flash)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                flash -= dt * 3.2f
                if (flash < 0f) flash = 0f
            }
        }

        private fun drawGlows(canvas: Canvas) {
            val night = isWeatherNight()
            val cloudy = weatherCode in setOf(1, 2, 3, 45, 48)
            val wet = weatherCode in setOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99)
            val snow = weatherCode in setOf(71, 73, 75, 77, 85, 86)
            val baseAlpha = if (mode == NeuMode.LIGHT) 0.18f else 0.30f
            val settle = 0.72f + 0.28f * progress
            val x = if (night) width * 0.18f else width * 0.82f
            val startY = if (night) height * 0.22f else height * 0.24f
            val endY = if (night) height * 0.13f else height * 0.10f
            val y = startY + (endY - startY) * progress
            val mainColor = when {
                night -> 0xFF9DB4FF.toInt()
                wet -> 0xFF5FD0C4.toInt()
                snow -> 0xFFDCE6FF.toInt()
                cloudy -> 0xFFE8B84B.toInt()
                else -> 0xFFF5C451.toInt()
            }
            drawAmbientGlow(canvas, x, y, width * if (night) 0.46f else 0.42f, mainColor, baseAlpha * settle)
            if (wet || snow || cloudy) {
                drawAmbientGlow(
                    canvas,
                    width * 0.50f,
                    height * 0.34f,
                    width * 0.52f,
                    if (wet) 0xFF57C98A.toInt() else if (snow) 0xFFDCE6FF.toInt() else 0xFF8B8F99.toInt(),
                    (if (mode == NeuMode.LIGHT) 0.08f else 0.13f) * settle
                )
            }
        }

        private fun drawAmbientGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int, alpha: Float) {
            val inner = adjustAlpha(color, alpha.coerceIn(0f, 0.42f))
            val mid = adjustAlpha(color, (alpha * 0.34f).coerceIn(0f, 0.18f))
            paint.shader = RadialGradient(
                cx,
                cy,
                radius.coerceAtLeast(1f),
                intArrayOf(inner, mid, 0x00000000),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius, paint)
            paint.shader = null
        }
    }

    private fun weatherIsRainy(): Boolean {
        val c = prefs().getInt(WEATHER_CODE_PREF, 0)
        return c in intArrayOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99)
    }

    private fun weatherDripRate(): Float = when (prefs().getInt(WEATHER_CODE_PREF, 0)) {
        55, 57, 65, 67, 82, 99 -> 7f
        53, 63, 81, 96 -> 4.5f
        else -> 2.4f
    }

    private class DripDrop(var x: Float, var edgeY: Float, var y: Float, var grow: Float,
                           var r: Float, var vy: Float, var released: Boolean, var a: Float)

    // Foreground overlay that lets rain "wet" the widgets: droplets bead up along a widget's bottom
    // edge, swell, then release and fall off. Active only on rainy codes + Animated Weather; passes
    // touches straight through and pauses when off-screen.
    private inner class WeatherDripView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val drips = ArrayList<DripDrop>()
        private val edgeBuf = ArrayList<FloatArray>()
        private var running = false
        private var lastFrame = 0L
        private var spawnAcc = 0f
        private val rnd = java.util.Random()

        fun refresh() {
            val active = weatherIsRainy() && animatedWeatherEnabled() && homeWidgetStackVisible()
            if (active) {
                if (!running) { running = true; lastFrame = 0L; postInvalidateOnAnimation() }
            } else {
                running = false; drips.clear(); invalidate()
            }
        }

        override fun onVisibilityAggregated(isVisible: Boolean) {
            super.onVisibilityAggregated(isVisible)
            if (isVisible) refresh() else running = false
        }

        override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }

        override fun onTouchEvent(event: MotionEvent): Boolean = false   // never consume touches

        private fun collectEdges() {
            edgeBuf.clear()
            val my = IntArray(2); getLocationInWindow(my)
            val loc = IntArray(2)
            val views = ArrayList<View>()
            if (::nowPlayingCardView.isInitialized) views.add(nowPlayingCardView)
            if (::favoritesDockView.isInitialized) views.add(favoritesDockView)
            for (v in views) {
                if (v.width <= 0 || v.height <= 0 || !v.isShown) continue
                v.getLocationInWindow(loc)
                val left = (loc[0] - my[0]).toFloat()
                edgeBuf.add(floatArrayOf(left, left + v.width, (loc[1] - my[1] + v.height).toFloat()))
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!running) return
            val now = System.currentTimeMillis()
            val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
            lastFrame = now
            val d = resources.displayMetrics.density
            collectEdges()
            if (edgeBuf.isNotEmpty()) {
                spawnAcc += dt * weatherDripRate()
                while (spawnAcc >= 1f) {
                    spawnAcc -= 1f
                    val e = edgeBuf[rnd.nextInt(edgeBuf.size)]
                    val x = e[0] + rnd.nextFloat() * (e[1] - e[0])
                    drips.add(DripDrop(x, e[2], e[2], 0f, (2.2f + rnd.nextFloat() * 1.8f) * d, 0f, false, 0.5f + rnd.nextFloat() * 0.28f))
                }
            }
            val color = 0xFF6FA8CC.toInt()
            val h = height.toFloat()
            paint.style = Paint.Style.FILL
            val iter = drips.iterator()
            while (iter.hasNext()) {
                val drop = iter.next()
                if (!drop.released) {
                    drop.grow += dt
                    val t = (drop.grow / 0.9f).coerceIn(0f, 1f)
                    val rr = drop.r * (0.45f + 0.55f * t)
                    paint.color = adjustAlpha(color, drop.a)
                    canvas.drawCircle(drop.x, drop.edgeY + rr * 0.7f, rr, paint)
                    if (drop.grow >= 0.9f) { drop.released = true; drop.y = drop.edgeY + rr; drop.vy = 24f * d }
                } else {
                    drop.vy += 950f * d * dt
                    drop.y += drop.vy * dt
                    drop.a -= dt * 0.45f
                    if (drop.a <= 0f || drop.y - drop.r > h) { iter.remove(); continue }
                    paint.color = adjustAlpha(color, drop.a)
                    canvas.drawCircle(drop.x, drop.y, drop.r * 0.8f, paint)
                }
            }
            postInvalidateOnAnimation()
        }
    }

    private fun isWeatherNight(): Boolean {
        val hour = LocalTime.now().hour
        return hour < 6 || hour >= 19
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

    // NOTE: Pure model types (PaneKind, PaneTarget, AppEntry, SearchResult, FlightSegment, …)
    // moved verbatim to LauncherModels.kt. Behaviour unchanged; referenced here by simple name.

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
        private const val TOUCH_MODEL_PREF = "touch_model_v1"
        private const val APP_ICON_SIZE_PREF = "app_icon_size"
        private const val KEYBOARD_PLACEMENT_PREF = "keyboard_placement"
        private const val KEYBOARD_PLACEMENT_INTRO_PREF = "keyboard_placement_intro_shown"
        private const val KEYBOARD_PLACEMENT_DOCKED = "docked"
        private const val KEYBOARD_PLACEMENT_WIDGET = "widget"
        private const val THEME_MODE_PREF = "theme_mode"
        private const val THEME_MODE_DARK = "dark"
        private const val THEME_MODE_LIGHT = "light"
        private const val THEME_MODE_SYSTEM = "system"
        private const val KEYBOARD_THEME_PREF = "keyboard_theme"
        private const val KEYBOARD_THEME_DEFAULT = "default"
        private const val KEYBOARD_THEME_CLICKS = "clicks"
        private const val KEYBOARD_THEME_SKEUO = "skeuo"
        private const val KEYBOARD_THEME_GOKEYS = "gokeys"
        private const val KEYBOARD_THEME_HYPER3D = "hyper3d"
        private const val KEYBOARD_THEME_HYPER3D_BLACK = "hyper3d_black"
        private const val KEYBOARD_THEME_HYPER3D_LIGHT = "hyper3d_light"
        private const val MUSIC_THEME_PREF = "music_theme"
        private const val MUSIC_THEME_MUSIC1 = "music1"
        private const val MUSIC_THEME_BLACK = "music_black"
        private const val PHOTO_BUCKET_PREF = "photo_bucket"
        private const val PHOTO_SELECTED_ID_PREF = "photo_selected_id"
        private const val PHOTO_FAVORITES_PREF = "photo_favorites"
        private const val HAPTICS_PREF = "haptics"
        private const val KBD_TILT_LIGHT_PREF = "kbd_tilt_light"
        private const val LIBRARY_GRID_MODE_PREF = "library_grid_mode"
        private const val GRID_APP_TILE_TAG = "grid_app_tile"
        private const val GO_KEY_COLOR_PREF = "go_key_color"
        private const val FAVORITE_APPS_PREF = "favorite_apps"
        private const val HIDDEN_HOME_APPS_PREF = "hidden_home_apps"
        private const val DOCK_LABELS_PREF = "dock_labels"
        private const val ANIMATED_WEATHER_PREF = "animated_weather"
        private const val DEV_EXPERIMENTS_PREF = "dev_experiments"
        private const val DOCK_APP_LIMIT = 5
        private const val APP_USAGE_PREF = "app_usage_counts"
        private const val APP_LAST_LAUNCH_PREF = "app_last_launch_times"
        private const val WEATHER_TEMP_PREF = "weather_temp"
        private const val WEATHER_META_PREF = "weather_meta"
        private const val WEATHER_FEELS_PREF = "weather_feels"
        private const val WEATHER_STATS_PREF = "weather_stats"
        private const val WEATHER_CODE_PREF = "weather_code"
        private const val WEATHER_FETCHED_AT_PREF = "weather_fetched_at"
        private const val WEATHER_ANIMATION_BURST_MS = 4000L
        private const val WEATHER_AMBIENT_BURST_MS = 2600L
        private const val WIDGET_HOST_ID = 1407
        private const val WIDGET_BIND_REQUEST_CODE = 501
        private const val WIDGET_CONFIGURE_REQUEST_CODE = 502
        private const val WIDGET_IDS_PREF = "widget_board_ids"
        private const val WIDGET_BOARD_COLUMNS = 4
        private const val WIDGET_BOARD_MIN_ROWS = 8
        private const val WIDGET_BOARD_MAX_ROWS = 12
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
        private const val ICON_PICKER_VISIBLE_LIMIT = 120
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
