package com.fran.teclas

import android.Manifest
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.WallpaperManager
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
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RadialGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Typeface
import android.app.AlertDialog
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import com.fran.teclas.glide.KeyInfo
import com.fran.teclas.glide.StatisticalGlideTypingClassifier
import com.fran.teclas.keyboard.neural.TimedPoint
import com.fran.teclas.hardware.ParallaxSensorEngine
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
import android.graphics.Outline
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
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
import com.fran.teclas.keyboard.CustomHapticEngine
import com.fran.teclas.keyboard.DynamicFlickKeyView
import com.fran.teclas.keyboard.FlickDetector
import com.fran.teclas.keyboard.FlickDirection
import com.fran.teclas.keyboard.KeyPreviewManager
import com.fran.teclas.keyboard.LivePredictionRouter
import com.fran.teclas.keyboard.PredictionEngine
import com.fran.teclas.keyboard.PredictionOverlayManager
import com.fran.teclas.predict.ContextSnapshot
import com.fran.teclas.fold.FoldPosture
import com.fran.teclas.fold.observeFoldPosture
import com.fran.teclas.predict.LaunchSource
import com.fran.teclas.predict.Predictor
import com.fran.teclas.predict.Space
import com.fran.teclas.predict.SpaceManager
import com.fran.teclas.keyboard.SmsIngestionEngine
import com.fran.teclas.keyboard.SmsSeedingCoordinator
import com.fran.teclas.keyboard.SpatialScorer
import com.fran.teclas.keyboard.WordBoundaryDeleter
import com.fran.teclas.db.NgramRepository
import com.fran.teclas.db.WidgetPersistenceRepository
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fran.teclas.brief.Brief
import com.fran.teclas.brief.BriefAction
import com.fran.teclas.brief.BriefCategory
import com.fran.teclas.brief.BriefClassifier
import com.fran.teclas.brief.BriefCollector
import com.fran.teclas.brief.BriefGenerator
import com.fran.teclas.brief.BriefItem
import com.fran.teclas.brief.BriefRepository
import com.fran.teclas.brief.TodayKeyboardMode
import com.fran.teclas.brief.TodayPage
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

class MainActivity : ComponentActivity(), SpellCheckerSession.SpellCheckerSessionListener,
    com.fran.teclas.keyboard.KeyboardHost {

    // ── KeyboardHost (shared-core seam) — wraps the launcher's in-memory query/composeText ──
    private fun hostKind() = openPane?.kind
    private fun hostText(): String = when (hostKind()) {
        PaneKind.CHAT -> composeText
        PaneKind.AI -> aiDraftText
        else -> query
    }
    private fun setHostText(v: String) {
        when (hostKind()) {
            PaneKind.CHAT -> composeText = v
            PaneKind.AI -> {
                aiDraftText = v
                aiDraftActive = true
            }
            else -> query = v
        }
    }
    override fun commitText(text: String) {
        val cur = hostText(); val pos = cursorPos ?: cur.length
        setHostText(cur.substring(0, pos) + text + cur.substring(pos))
        if (cursorPos != null) cursorPos = pos + text.length
    }
    override fun deleteBeforeCursor(count: Int) {
        val cur = hostText(); val pos = cursorPos ?: cur.length
        val start = (pos - count).coerceAtLeast(0)
        setHostText(cur.substring(0, start) + cur.substring(pos))
        if (cursorPos != null) cursorPos = start
    }
    override fun textBeforeCursor(count: Int): String {
        val cur = hostText(); val pos = cursorPos ?: cur.length
        return cur.substring((pos - count).coerceAtLeast(0), pos)
    }
    override fun textAfterCursor(count: Int): String {
        val cur = hostText(); val pos = cursorPos ?: cur.length
        return cur.substring(pos, (pos + count).coerceAtMost(cur.length))
    }
    override fun moveCursor(right: Boolean) {
        val cur = hostText(); val pos = cursorPos ?: cur.length
        val np = (pos + if (right) 1 else -1).coerceIn(0, cur.length)
        cursorPos = if (np == cur.length) null else np
    }

    /** Step the launcher's in-field cursor and repaint the caret (two-finger trackpad panning). */
    private fun moveLauncherCursor(right: Boolean) {
        moveCursor(right)
        renderRibbon()
    }
    override fun editorPackage(): String? = null          // launcher edits its own field
    override fun isPasswordField(): Boolean = false
    override val hostHapticsEnabled: Boolean get() = hapticsEnabled
    override fun onAgenticCommand(text: String) { executeTypeToDoCommand(text) }
    override fun openHostKeyboardSettings() { keyboardSettingsOpen = true; render() }

    private val autocorrectCore by lazy {
        com.fran.teclas.keyboard.AutocorrectCore(
            host = this,
            engine = { predictionEngine },
            contextNextWords = { ngramRepo.cachedNextWords(it) },
            useFallback = true
        )
    }
    private val predictionCore by lazy {
        com.fran.teclas.keyboard.PredictionCore(this, { predictionEngine }, ngramRepo)
    }
    private val glideCore by lazy { com.fran.teclas.keyboard.GlideCore(this, ngramRepo) }

    private val collator = Collator.getInstance()
    internal var query = ""
    private var apps: List<AppEntry> = emptyList()
    private var keyboardSize = 0
    private var appIconSize = 0
    private var keyboardTheme = KEYBOARD_THEME_DEFAULT
    internal var keyboardPlacement = KEYBOARD_PLACEMENT_DOCKED
    private var themeMode = THEME_MODE_SYSTEM
    internal var activeNeuTokens = Neu.Dark
    private var hapticsEnabled = true
    private var keyboardTiltLighting = true
    internal var keyboardSettingsOpen = false
    private var goKeyColor = Accent
    private var messages: List<HubMessage> = emptyList()
    private var calendarEvents: List<CalendarEvent> = emptyList()
    internal var openPane: PaneTarget? = null
    private var paneView: View? = null
    internal var libraryOpen = false
    private var libraryGridMode = true
    private var libraryView: View? = null
    private var libraryViewMode: NeuMode? = null
    private var libraryViewGlass: Boolean? = null
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
    private lateinit var spaceBoardController: com.fran.teclas.grid.SpaceBoardController
    private var spaceBoardOverlay: View? = null
    private var spaceBoardDragActive = false
    private var spaceBoardTitleView: TextView? = null
    // Apple-style left widget page: swipe right pulls it in from the left, blurring home behind it.
    private lateinit var homeLeftController: com.fran.teclas.grid.SpaceBoardController
    private var homeLeftOverlay: View? = null
    private var homeLeftDragActive = false
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
    private var contextDockSignature: String? = null
    private var contextDockContextKey: String? = null
    private var contextDockRefreshRunnable: Runnable? = null
    private var foldPosture: FoldPosture = FoldPosture.Cover
    private var foldPreviousSpaceLock: String? = null
    private var foldPreviousKeyboardPlacement: String? = null
    private var foldAutoLockActive = false
    private var foldBannerSpace: Space? = null
    private var unfoldedLibraryContentArea: FrameLayout? = null
    private var unfoldedFocusContentArea: FrameLayout? = null
    private var unfoldedFocusDockView: View? = null
    private var innerKeyboardPreviewBoost: Int? = null
    private var innerKeyboardEditMode = false
    private var composeText = ""
    private var aiDraftText = ""
    private var aiDraftActive = false
    private val chatLinesById = mutableMapOf<String, MutableList<ChatLine>>()

    private var shiftState = ShiftState.ONCE
    private var lastSpaceMs = 0L
    private var lastShiftTapMs = 0L
    private var suggestions: List<String> = emptyList()
    private var launcherEmojiChips: List<String> = emptyList()
    private var launcherEmojiTriggerWord: String = ""
    // True right after a glide commits a word, while the strip shows the swipe's other decodings as
    // tap-to-correct alternatives. Any physical key press clears it (see handleKey).
    private var glideJustCommitted = false
    private val deleteRepeatHandler = Handler(Looper.getMainLooper())
    private var deleteRepeatRunnable: Runnable? = null
    private var deleteRepeatActive = false
    private var deleteRepeatFired = false
    // Autocorrect (correction, undo, rejected memory) now lives in the shared AutocorrectCore.
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
    // In-launcher Spotify music search: cached results for the current query so typing a song/artist
    // in the main search surfaces playable tracks (tap to play, no need to open Spotify).
    private var musicSearchDebounce: Runnable? = null
    private var spotifyQuickQuery: String = ""
    private var spotifyQuickResults: List<SearchResult> = emptyList()
    private var gmailSearchDebounce: Runnable? = null
    private var gmailQuickQuery: String = ""
    private var gmailQuickResults: List<SearchResult> = emptyList()
    private var fileSearchDebounce: Runnable? = null
    private var fileQuickQuery: String = ""
    private var fileQuickResults: List<SearchResult> = emptyList()
    private var lastSuggestWord = ""
    private val keyViews = mutableMapOf<String, TextView>()
    private val keyBounds = mutableMapOf<String, Rect>()
    private var widgetSwapState = WidgetKeyboardSwapState.SEATED
    private var widgetCommittedTheme = KEYBOARD_THEME_DEFAULT
    private var widgetPreviewTheme = KEYBOARD_THEME_DEFAULT
    private var widgetKeyboardHost: FrameLayout? = null
    private var widgetKeyboardModule: FrameLayout? = null
    private var widgetKeyboardSeatView: KeyboardSocketView? = null
    private var widgetKeyboardSliderHandleView: KeyboardSliderHandleView? = null
    private var widgetKeyboardHidden = false
    private var widgetKeyboardSliderAnimating = false
    private var widgetKeyboardHandleDownY = 0f
    private var widgetKeyboardHandleDragging = false
    private var widgetKeyboardTapDownX = 0f
    private var widgetKeyboardTapDownY = 0f
    private var widgetKeyboardMaybeTap = false
    private var widgetKeyboardHostTwoFingerStartY = 0f
    private var widgetKeyboardHostTwoFingerStartX = 0f
    private var widgetKeyboardHostTwoFingerActive = false
    private var widgetKeyboardDockHeightAnimator: ValueAnimator? = null
    private var widgetKeyboardHostHeightAnimator: ValueAnimator? = null
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
    private var neuralSwipe: com.fran.teclas.keyboard.NeuralSwipeEngine? = null
    // New encoder-decoder + beam-search decoder, wired via the same shared engine the IME uses.
    // Preferred over the legacy encoder-only [neuralSwipe] when a model is present and enabled.
    private var neuralGlideV2: com.fran.teclas.keyboard.neural.NeuralGlideEngine? = null
    // Hybrid decoder fuses neural + statistical (shared with the IME); null until engines load.
    private var hybridDecoderV2: com.fran.teclas.keyboard.neural.HybridGlideDecoder? = null
    private val glideLearning by lazy { com.fran.teclas.keyboard.neural.GlideLearningStore(this) }
    private var glideRecognizedColor: Int? = null   // app brand color when a glided word names an app
    @Volatile private var glideGestureActive = false   // true while a keyboard glide owns the touch
    private var numberPadOpen = false
    private var symbolsOpen = false
    private var lastMusicSourcePackage: String? = null
    private var lastMusicPlaying = false
    internal lateinit var mediaSessionSource: MediaSessionSource
    internal val mediaUiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Prediction engine: last computed context (refreshed off-main), which launch surface
    // is about to fire, and the drawer's Space icon for live updates.
    @Volatile private var predictContext: ContextSnapshot? = null
    private var pendingLaunchSource = LaunchSource.OTHER
    private var spaceIconView: TextView? = null

    // "Today" brief — a page to the left of home, plus a teaser below the widget stack.
    // Pane hosting + notification action firing live in TodayPaneHost.
    // Today is disabled for now: it drove frequent Gemini/brief generation and isn't shipping.
    // This single flag gates every entry point (open gesture, teaser, periodic brief refresh).
    internal val todayEnabled = false
    internal lateinit var briefRepository: BriefRepository
    internal val todayPaneHost = TodayPaneHost(this)
    // Spotify/Music library pane (compact + full library, click-wheel docks) lives in MusicPaneHost.
    internal val musicPaneHost = MusicPaneHost(this)
    internal val travelPaneHost = TravelPaneHost(this)
    private var todayAlertView: ComposeView? = null
    internal var todayOpen = false
    private var billingClient: com.android.billingclient.api.BillingClient? = null
    lateinit var spotifyAuth: SpotifyAuth
    lateinit var spotifyApi: SpotifyWebApi
    lateinit var gmailAuth: GmailAuth
    lateinit var gmailApi: GmailApi
    private val accountAuth by lazy { AccountAuth(this) }
    lateinit var travelRepo: TravelRepository
    private var volumeHudView: LinearLayout? = null
    private var volumeHudFill: View? = null
    private val volumeHudHideRunnable = Runnable { hideVolumeHud() }
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
    private lateinit var innerWallpaperPickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var hapticEngine: CustomHapticEngine
    private lateinit var spatialScorer: SpatialScorer
    private lateinit var keyPreviewManager: KeyPreviewManager
    private lateinit var predictionEngine: PredictionEngine
    // Parity with the IME: primary-language engine + latent flags drive languagePreferredOrder so a
    // swipe on the launcher keyboard also prefers the language you're writing (no English->Spanish).
    private var predictionEnginePrimary: PredictionEngine = PredictionEngine(emptyMap())
    private var hasLatentLanguages = false
    private var latentLanguageActive = false
    private lateinit var ngramRepo: NgramRepository
    private lateinit var flickDetector: FlickDetector
    private lateinit var predictionOverlay: PredictionOverlayManager
    private lateinit var liveRouter: LivePredictionRouter
    private var wordlistFrequencies: Map<String, Float> = emptyMap()
    private lateinit var weatherIconView: AnimatedWeatherIconView
    private var weatherAmbientView: WeatherAmbientView? = null
    private var weatherDripView: WeatherDripView? = null
    private var homeWallpaperDrawable: Drawable? = null
    private var innerWallpaperImageView: ImageView? = null
    private var innerWallpaperEditMode = false
    private var pendingWallpaperInnerScope: Boolean? = null
    private var wallpaperLongPressRunnable: Runnable? = null
    private lateinit var weatherTempView: TextView
    private lateinit var weatherMetaView: TextView
    private lateinit var weatherFeelsView: TextView
    private lateinit var weatherStatsView: TextView
    private lateinit var hubView: LinearLayout
    private lateinit var ribbonView: LinearLayout
    private lateinit var favoritesDockFrameView: FavoritesDockFlipFrame
    private lateinit var favoritesDockView: LinearLayout
    private lateinit var favoritesDockContextView: LinearLayout
    private var favoritesDockContextStampView: TextView? = null
    private var favoritesDockContextShowing = false
    private var favoritesDockContextPreferred = false
    private var parallaxEngine: ParallaxSensorEngine? = null
    private lateinit var homeGridView: FrameLayout
    private lateinit var rootView: LinearLayout
    internal lateinit var contentFrame: FrameLayout

    // TodayPaneHost (separate class) can't check lateinit ::isInitialized across class boundaries.
    internal fun hasContentFrame() = ::contentFrame.isInitialized
    internal fun hasBriefRepository() = ::briefRepository.isInitialized
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
        innerWallpaperPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data ?: return@registerForActivityResult
            val flags = result.data?.flags ?: 0
            val readFlag = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            runCatching { contentResolver.takePersistableUriPermission(uri, readFlag) }
            val innerScope = pendingWallpaperInnerScope ?: isUnfoldedInnerLayoutActive()
            pendingWallpaperInnerScope = null
            prefs().edit().putString(activeWallpaperUriPref(innerScope), uri.toString()).apply()
            homeWallpaperDrawable = null
            Toast.makeText(this, if (innerScope) "Inner wallpaper applied." else "Cover wallpaper applied.", Toast.LENGTH_SHORT).show()
            if (::rootView.isInitialized) render()
        }
        appWidgetManager = AppWidgetManager.getInstance(this)
        appWidgetHost = AppWidgetHost(this, WIDGET_HOST_ID)
        appWidgetHost.startListening()
        spaceBoardController = com.fran.teclas.grid.SpaceBoardController(this, SpaceBoardCallbacks())
        homeLeftController = com.fran.teclas.grid.SpaceBoardController(this, SpaceBoardCallbacks())
        // One-time reset: earlier builds auto-seeded boards with predicted apps; the board now
        // seeds only the Space's pinned apps as a top strip, so clear the old layouts once.
        if (!prefs().getBoolean("space_boards_pinned_seed_v2", false)) {
            com.fran.teclas.grid.GridWorkspaceStore.clearAllSpaceBoards(this)
            prefs().edit().putBoolean("space_boards_pinned_seed_v2", true).apply()
        }
        widgetPersistenceRepository = WidgetPersistenceRepository(this)
        widgetSpecsCache = loadWidgetSpecsFromStore()
        keyboardSize = prefs().getInt(KEYBOARD_SIZE_PREF, 28)
        appIconSize = prefs().getInt(APP_ICON_SIZE_PREF, 0)
        keyboardTheme = prefs().getString(KEYBOARD_THEME_PREF, KEYBOARD_THEME_DEFAULT) ?: KEYBOARD_THEME_DEFAULT
        keyboardPlacement = prefs().getString(KEYBOARD_PLACEMENT_PREF, KEYBOARD_PLACEMENT_DOCKED) ?: KEYBOARD_PLACEMENT_DOCKED
        widgetKeyboardHidden = loadWidgetKeyboardHiddenForCurrentPosture()
        themeMode = prefs().getString(THEME_MODE_PREF, THEME_MODE_SYSTEM) ?: THEME_MODE_SYSTEM
        homeWallpaperDrawable = loadHomeWallpaperDrawable()
        applyTheme()
        hapticsEnabled = prefs().getBoolean(HAPTICS_PREF, true)
        keyboardTiltLighting = prefs().getBoolean(KBD_TILT_LIGHT_PREF, true)
        libraryGridMode = prefs().getBoolean(LIBRARY_GRID_MODE_PREF, true)
        libraryOpen = appLibraryDefaultHome()
        goKeyColor = prefs().getInt(GO_KEY_COLOR_PREF, Accent)
        migrateWidgetGestureDefault()
        apps = loadLaunchableApps()
        lastAppsLoadMs = System.currentTimeMillis()
        // Let the prediction engine resolve app categories for Space cold-start priors
        // (e.g. Work leads with Gmail/Slack before any launch is learned).
        Predictor.categoryProvider = { pkg ->
            com.fran.teclas.predict.AppCategories.of(applicationContext, pkg)
        }
        registerAppPackageReceiver()
        messages = loadHubMessages()
        calendarEvents = loadCalendarEvents()
        prefs().registerOnSharedPreferenceChangeListener(prefsListener)
        mediaSessionSource = MediaSessionSource(this)
        briefRepository = BriefRepository(
            prefs = prefs(),
            collector = BriefCollector(
                calendarProvider = { calendarEvents }
            ),
            generator = BriefGenerator(prefs()),
            scope = mediaUiScope
        )
        // Listener runs in-process; the callback can land on a binder thread, so hop to main where
        // refreshDebounced mutates its Job field.
        TeclasNotificationListener.onBriefChanged = {
            runOnUiThread {
                if (todayEnabled) briefRepository.refreshDebounced()
                // Informational notifications feed the widget stack — refresh it too.
                if (::nowPlayingCardView.isInitialized) refreshNowPlayingCard()
            }
        }
        if (todayEnabled) {
            briefRepository.startPeriodic()
            briefRepository.refreshDebounced(300)
        }
        spotifyAuth = SpotifyAuth(this)
        spotifyApi = SpotifyWebApi(spotifyAuth)
        gmailAuth = GmailAuth(this)
        gmailApi = GmailApi(gmailAuth)
        // Account mode: route AI through the proxy with the user's Google ID token (no device key).
        GeminiClient.proxy = GeminiProxy.binding(this)
        travelRepo = TravelRepository(gmailApi)
        if (spotifyAuth.isConnected) musicPaneHost.preloadSpotifyLibrary()
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
        observeFoldPosture { posture -> handleFoldPosture(posture) }
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
        if (uri.scheme == "com.fran.teclas" && uri.host == "spotify-callback") {
            mediaUiScope.launch {
                val ok = spotifyAuth.handleCallback(uri)
                if (ok) {
                    prefs().edit().putString(SPOTIFY_INTEGRATION_PREF, INTEGRATION_API).apply()
                    // Connect happened mid-session — populate the library now, otherwise it stays
                    // empty until the next cold start and the music screen shows "reconnect".
                    musicPaneHost.preloadSpotifyLibrary()
                    renderPaneContent(teclasSettingsTarget())
                    Toast.makeText(this@MainActivity, "Spotify connected!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Spotify connection failed. Try again.", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (accountAuth.isCallback(uri)) {
            // Checked before Gmail: both share the reversed scheme, distinguished by redirect path.
            mediaUiScope.launch {
                val ok = withContext(Dispatchers.IO) { accountAuth.handleCallback(uri) }
                if (ok) {
                    prefs().edit().putBoolean(GeminiProxy.ACCOUNT_MODE_PREF, true).apply()
                    GeminiClient.proxy = GeminiProxy.binding(this@MainActivity)
                }
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "Signed in — AI is ready" else "Sign-in failed. Try again.",
                    Toast.LENGTH_SHORT
                ).show()
                if (openPane?.kind == PaneKind.SETTINGS) renderPaneContent(teclasSettingsTarget())
            }
        } else if (gmailAuth.isCallback(uri)) {
            mediaUiScope.launch {
                val ok = gmailAuth.handleCallback(uri)
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "Gmail connected!" else "Gmail connection failed. Try again.",
                    Toast.LENGTH_SHORT
                ).show()
                if (openPane?.kind == PaneKind.SETTINGS) renderPaneContent(teclasSettingsTarget())
            }
        }
    }

    private fun handleKeyboardActionIntent(intent: Intent?): Boolean {
        when (intent?.action) {
            TeclasKeyboardActions.OPEN_KEYBOARD_SETTINGS -> {
                VivoDockedExperiment.clearViewportTruncation(this)
                stopService(Intent(this, DockedKeyboardService::class.java))
                openPane = null
                libraryOpen = false
                keyboardSettingsOpen = true
                query = ""
                render()
                return true
            }
            TeclasKeyboardActions.SWITCH_TO_WIDGET_MODE -> {
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
        if (useLockscreenWallpaperOnHome() || isUnfoldedInnerLayoutActive()) {
            homeWallpaperDrawable = loadHomeWallpaperDrawable()
            if (::rootView.isInitialized && openPane == null && !libraryOpen) render()
        }
        ensureBillingConnected()
        val now = System.currentTimeMillis()
        if (now - lastHubLoadMs > 10_000) { messages = loadHubMessages(); lastHubLoadMs = now }
        if (now - lastCalendarLoadMs > 10_000) { calendarEvents = loadCalendarEvents(); lastCalendarLoadMs = now }
        if (now - lastContactsLoadMs > 5 * 60_000) { preloadContactsCache(); lastContactsLoadMs = now }
        if (todayEnabled && ::briefRepository.isInitialized) {
            briefRepository.startPeriodic()
            briefRepository.refreshDebounced(200)
        }
        // Resume the music progress tick paused in onPause (no-op when the bar isn't showing).
        musicProgressRunnable?.let { r -> musicProgressHandler?.let { h -> h.removeCallbacks(r); h.post(r) } }
        // Self-heal an empty Spotify library (e.g. connected in a previous session but never loaded).
        if (::spotifyAuth.isInitialized && spotifyAuth.isConnected &&
            musicPaneHost.spotifyCachedPlaylists.isEmpty() && musicPaneHost.spotifyCachedLikedSongs.isEmpty() && musicPaneHost.spotifyCachedRecent.isEmpty()) {
            musicPaneHost.preloadSpotifyLibrary()
        }
        if (::mediaSessionSource.isInitialized) mediaSessionSource.refreshActiveSessions()
        refreshPredictContext()
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
        widgetCoachAnimator?.cancel()
        // Halt periodic work while another app is in front: the 1 Hz music-progress tick and the
        // 45-minute brief refresh both resume in onResume; nothing needs them while backgrounded.
        musicProgressRunnable?.let { musicProgressHandler?.removeCallbacks(it) }
        if (::briefRepository.isInitialized) briefRepository.stopPeriodic()
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
        TeclasNotificationListener.onBriefChanged = null
        if (::briefRepository.isInitialized) briefRepository.stopPeriodic()
        mediaUiScope.cancel()
        neuralGlideV2?.close()
        spellChecker?.close()
        handler.removeCallbacksAndMessages(null)
        billingClient?.endConnection()
        billingClient = null
    }

    private fun ensureBillingConnected() {
        // The billing library posts delayed reconnect Messages on the main looper that keep the
        // state listener alive past endConnection(); capture only the app context and a weak
        // activity reference so a queued retry can't pin a destroyed MainActivity (LeakCanary
        // measured 17.8 MB retained exactly this way).
        val appCtx = applicationContext
        val self = java.lang.ref.WeakReference(this)
        val bc = billingClient ?: ProManager.buildBillingClient(appCtx) { token ->
            // Purchase verified — refresh any gated UI
            Toast.makeText(appCtx, "Teclas Pro unlocked. Welcome!", Toast.LENGTH_SHORT).show()
            self.get()?.takeIf { !it.isDestroyed }?.renderRibbon()
        }.also { billingClient = it }

        if (!bc.isReady) {
            bc.startConnection(object : com.android.billingclient.api.BillingClientStateListener {
                override fun onBillingSetupFinished(result: com.android.billingclient.api.BillingResult) {
                    if (result.responseCode == com.android.billingclient.api.BillingClient.BillingResponseCode.OK) {
                        val act = self.get()?.takeIf { !it.isDestroyed } ?: return
                        act.mediaUiScope.launch(Dispatchers.IO) {
                            ProManager.restorePurchases(bc, appCtx)
                        }
                    }
                }
                override fun onBillingServiceDisconnected() { self.get()?.billingClient = null }
            })
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::spaceBoardController.isInitialized &&
            spaceBoardController.onWidgetResult(requestCode, resultCode == RESULT_OK)) {
            return
        }
        if (::homeLeftController.isInitialized &&
            homeLeftController.onWidgetResult(requestCode, resultCode == RESULT_OK)) {
            return
        }
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
        if (homeLeftOverlay != null) { closeHomeLeftPage(); return }
        if (spaceBoardOverlay != null) { closeSpaceBoard(); return }
        if (todayOpen) { todayPaneHost.closeToday(); return }
        if (travelPaneHost.travelOverlay != null) { travelPaneHost.dismissTravelOverlay(); return }
        if (widgetPickerView != null) { closeWidgetPicker(); return }
        if (widgetBoardView != null) { closeWidgetBoard(); return }
        if (musicPaneHost.spotifyFullLibraryDismiss != null) { musicPaneHost.spotifyFullLibraryDismiss?.invoke(); return }
        if (musicPaneHost.spotifyCompactOverlay != null) { musicPaneHost.dismissCompactSpotifyLibrary(); return }
        if (libraryOpen) { closeLibrary(); return }
        if (openPane != null) { closePane(); return }
        super.onBackPressed()
    }

    private fun dismissToHome() {
        // Hide soft keyboard first so it doesn't flash during transition
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        // Dismiss overlays in order
        if (todayOpen) todayPaneHost.closeToday()
        if (travelPaneHost.travelOverlay != null) travelPaneHost.dismissTravelOverlay()
        musicPaneHost.spotifyFullLibraryDismiss?.invoke()
        if (musicPaneHost.spotifyCompactOverlay != null) musicPaneHost.dismissCompactSpotifyLibrary()
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
        val accent = 0xFF8AB4F8.toInt()   // Teclas blue

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
            text = "Teclas Pro"
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
        if (innerWallpaperEditMode) return super.dispatchTouchEvent(event)
        if (widgetKeyboardSwapActive()) return super.dispatchTouchEvent(event)
        if (handleVisibleWidgetKeyboardGlobalGesture(event)) return true
        if (handleHiddenWidgetKeyboardGlobalGesture(event)) return true
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
            }?.filter { it.isNotBlank() } ?: emptyList()
            // Merge, never overwrite: an empty spellchecker result (common for correctly-typed words)
            // must NOT wipe the on-device suggestions that are already showing — that blanked the box.
            if (words.isNotEmpty()) {
                suggestions = (words + suggestions).distinct().take(3)
                updateSuggestionBar()
            }
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
            .setMessage("Docked keeps the Teclas keyboard fixed at the bottom. Widget places it on the homescreen, with a DOCK key to return to the fixed layout.")
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
        syncSystemBars()
        val unfolded = isUnfoldedInnerLayoutActive()
        val phoneWidgetCanvas = !unfolded && keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET
        val wallpaperCanvas = launcherWallpaperCanvasActive()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(if (wallpaperCanvas) Color.TRANSPARENT else activeNeuTokens.base)
            setPadding(0, systemStatusBarHeight(), 0, keyboardBottomLift())
        }
        rootView = root
        contentFrame = FrameLayout(this).apply {
            addView(
                when {
                    unfolded -> unfoldedHome()
                    phoneWidgetCanvas -> phoneWidgetHome()
                    else -> home()
                },
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            post { applyLibraryEdgeGestureExclusion() }
        }
        root.addView(contentFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val hideDockForPane = !unfolded && openPane?.kind == PaneKind.SETTINGS
        val showRootDock = unfolded || keyboardPlacement == KEYBOARD_PLACEMENT_DOCKED || widgetPaneUsesRootDock()
        if (showRootDock && !hideDockForPane) {
            keyboardDockView = FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
                addView(rootDockInputView(), FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            root.addView(keyboardDockView, LinearLayout.LayoutParams.MATCH_PARENT, activeRootDockHeight())
        } else {
            keyboardDockView = FrameLayout(this)
        }
        if (wallpaperCanvas) {
            val shell = FrameLayout(this).apply {
                setBackgroundColor(activeNeuTokens.base)
                homeWallpaperLayer()?.let {
                    addView(it, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                }
                addView(root, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                if (innerWallpaperEditMode) {
                    addView(innerWallpaperEditOverlay(), FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                }
            }
            setContentView(shell)
        } else {
            setContentView(root)
        }
        updateClock()
        renderHub()
        renderFavoritesDock()
        syncDockParallax()
        renderRibbon()
        if (!unfolded) {
            openPane?.let { showPane(it, animate = false) }
            if (libraryOpen) showLibrary(animate = false)
            if (!libraryOpen && openPane == null) contentFrame.postDelayed({ prewarmLibraryView() }, 140L)
        }
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
                text = launcherTypingStripText()
                textSize = 15f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setTextColor(keyboardIndicatorTextColor())
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

    private fun launcherTypingStripText(): CharSequence {
        val pane = openPane
        val typedText = when {
            pane?.kind == PaneKind.CHAT -> composeText
            pane?.kind == PaneKind.AI -> aiDraftText
            else -> query
        }
        if (typedText.isNotBlank()) return styledTypedCommand(typedText)
        return when {
            libraryOpen -> "APP LIBRARY"
            keyboardSettingsOpen -> "KEYBOARD SETTINGS"
            numberPadOpen -> "TYPE NUMBER"
            pane?.kind == PaneKind.CHAT -> "→ ${pane.name.uppercase(Locale.US)}"
            pane?.kind == PaneKind.AI -> "ASK GEMINI"
            else -> "SEARCH"
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
        val radius = dp(11).toFloat()
        return when (keyboardTheme) {
            KEYBOARD_THEME_DEFAULT -> Neu.drawable(activeNeuTokens, radius, NeuLevel.PRESSED_SM)
            KEYBOARD_THEME_SEEME -> SeemeDrawables.panel(darkTint = true)
            KEYBOARD_THEME_BRUSHED -> BrushedDrawables.panel(
                selectedNeuTokens().mode == NeuMode.DARK,
                resources.displayMetrics.density
            )
            else -> keyboardThemedTypingWell(radius)
        }
    }

    private fun keyboardThemedTypingWell(radius: Float): Drawable {
        val light = keyboardLightMode()
        val colors = if (light) {
            when (keyboardTheme) {
                KEYBOARD_THEME_SKEUO -> intArrayOf(0xFFE2E6EC.toInt(), 0xFFD0D6DF.toInt(), 0xFFBAC3D0.toInt())
                KEYBOARD_THEME_TECLAS -> intArrayOf(0xFFE5E9EF.toInt(), 0xFFD2D9E3.toInt(), 0xFFB9C3D0.toInt())
                KEYBOARD_THEME_GOKEYS -> intArrayOf(0xFFE8ECF1.toInt(), 0xFFD2D8E1.toInt(), 0xFFBBC4D0.toInt())
                KEYBOARD_THEME_HYPER3D, KEYBOARD_THEME_HYPER3D_LIGHT -> intArrayOf(0xFFE1E5EC.toInt(), 0xFFC9D0DA.toInt(), 0xFFADB7C5.toInt())
                else -> intArrayOf(0xFFE5E9EF.toInt(), 0xFFD2D9E3.toInt(), 0xFFB9C3D0.toInt())
            }
        } else {
            when (keyboardTheme) {
                KEYBOARD_THEME_SKEUO -> intArrayOf(0xFF191B21.toInt(), 0xFF0D0F13.toInt(), 0xFF050608.toInt())
                KEYBOARD_THEME_TECLAS -> intArrayOf(0xFF15171D.toInt(), 0xFF0C0E12.toInt(), 0xFF040506.toInt())
                KEYBOARD_THEME_GOKEYS -> intArrayOf(0xFF171A1F.toInt(), 0xFF101216.toInt(), 0xFF08090B.toInt())
                KEYBOARD_THEME_HYPER3D_BLACK -> intArrayOf(0xFF101113.toInt(), 0xFF080809.toInt(), 0xFF020203.toInt())
                KEYBOARD_THEME_HYPER3D_LIGHT -> intArrayOf(0xFFE1E5EC.toInt(), 0xFFC9D0DA.toInt(), 0xFFADB7C5.toInt())
                KEYBOARD_THEME_HYPER3D -> intArrayOf(0xFF141820.toInt(), 0xFF0A0D12.toInt(), 0xFF030406.toInt())
                else -> intArrayOf(0xFF15171D.toInt(), 0xFF0C0E12.toInt(), 0xFF040506.toInt())
            }
        }
        val body = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = radius
            setStroke(dp(1), if (light) 0x70FFFFFF else 0x55323844)
        }
        val innerShade = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (light) 0x22000000 else 0x66000000,
            0x00000000,
            if (light) 0x16FFFFFF else 0x18FFFFFF
        )).apply { cornerRadius = radius }
        return LayerDrawable(arrayOf(body, innerShade))
    }

    private fun keyboardIndicatorTextColor(dim: Boolean = false): Int {
        val label = if (dim) "123" else "space"
        return if (dim) {
            adjustAlpha(keyTextColor(label), if (keyboardLightMode()) 0.72f else 0.64f)
        } else {
            keyTextColor(label)
        }
    }

    private fun teclasGlassDrawable(radiusDp: Int): Drawable {
        val radius = dp(radiusDp).toFloat()
        val light = glassLightMode()
        val ambientShadow = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (light) 0x18000000 else 0x52000000)
        }
        val glassWash = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (light) {
                intArrayOf(
                    adjustAlpha(Color.WHITE, 0.70f),
                    adjustAlpha(0xFFF0F4F8.toInt(), 0.56f),
                    adjustAlpha(0xFFE1E7EF.toInt(), 0.46f)
                )
            } else {
                intArrayOf(
                    adjustAlpha(0xFF20232A.toInt(), 0.72f),
                    adjustAlpha(0xFF181B21.toInt(), 0.54f),
                    adjustAlpha(0xFF14161B.toInt(), 0.62f)
                )
            }
        ).apply { cornerRadius = radius }
        val topSheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                adjustAlpha(Color.WHITE, if (light) 0.42f else 0.25f),
                adjustAlpha(Color.WHITE, if (light) 0.10f else 0.05f),
                Color.TRANSPARENT
            )
        ).apply { cornerRadius = radius }
        val rim = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), adjustAlpha(Color.WHITE, if (light) 0.48f else 0.30f))
        }
        val underside = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.TRANSPARENT,
                adjustAlpha(Color.BLACK, if (light) 0.08f else 0.24f)
            )
        ).apply { cornerRadius = radius }
        return LayerDrawable(arrayOf(ambientShadow, glassWash, topSheen, underside, rim)).apply {
            setLayerInset(0, 0, dp(2), 0, 0)
            setLayerInset(1, 0, 0, 0, dp(1))
            setLayerInset(2, dp(1), dp(1), dp(1), dp(radiusDp / 2))
            setLayerInset(3, dp(1), dp(radiusDp / 2), dp(1), dp(1))
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

    private fun isHonorDevice(): Boolean {
        val device = listOf(Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.MODEL)
            .joinToString(" ")
            .lowercase(Locale.US)
        return device.contains("honor")
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

    private fun useLockscreenWallpaperOnHome(): Boolean =
        prefs().getBoolean(HOME_LOCK_WALLPAPER_PREF, false)

    private fun innerHomeWallpaperUri(): Uri? =
        prefs().getString(HOME_INNER_WALLPAPER_URI_PREF, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    private fun coverHomeWallpaperUri(): Uri? =
        prefs().getString(HOME_COVER_WALLPAPER_URI_PREF, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    private fun activeHomeWallpaperUri(): Uri? =
        if (isUnfoldedInnerLayoutActive()) {
            innerHomeWallpaperUri()
        } else {
            coverHomeWallpaperUri() ?: innerHomeWallpaperUri()
        }

    private fun activeWallpaperUriPref(inner: Boolean = isUnfoldedInnerLayoutActive()): String =
        if (inner) HOME_INNER_WALLPAPER_URI_PREF else HOME_COVER_WALLPAPER_URI_PREF

    private fun activeWallpaperZoomPref(): String =
        if (isUnfoldedInnerLayoutActive()) HOME_INNER_WALLPAPER_ZOOM_PREF else HOME_COVER_WALLPAPER_ZOOM_PREF

    private fun activeWallpaperOffsetXPref(): String =
        if (isUnfoldedInnerLayoutActive()) HOME_INNER_WALLPAPER_OFFSET_X_PREF else HOME_COVER_WALLPAPER_OFFSET_X_PREF

    private fun activeWallpaperOffsetYPref(): String =
        if (isUnfoldedInnerLayoutActive()) HOME_INNER_WALLPAPER_OFFSET_Y_PREF else HOME_COVER_WALLPAPER_OFFSET_Y_PREF

    private fun innerWallpaperZoom(): Float =
        prefs().getInt(activeWallpaperZoomPref(), 100).coerceIn(100, 260) / 100f

    private fun innerWallpaperOffsetX(): Float =
        prefs().getInt(activeWallpaperOffsetXPref(), 0).coerceIn(-100, 100) / 100f

    private fun innerWallpaperOffsetY(): Float =
        prefs().getInt(activeWallpaperOffsetYPref(), 0).coerceIn(-100, 100) / 100f

    private fun innerWallpaperModeActive(): Boolean =
        openPane == null && activeHomeWallpaperUri() != null

    private fun launcherWallpaperCanvasActive(): Boolean = true

    private fun widgetModeNativeGlassActive(): Boolean =
        isUnfoldedInnerLayoutActive() || keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET

    private fun nativeGlassSurfaceActive(): Boolean =
        isHonorDevice() || widgetModeNativeGlassActive()

    private fun normalizeInnerWallpaperTransform() {
        val zoomPref = activeWallpaperZoomPref()
        val zoom = prefs().getInt(zoomPref, 100)
        if (zoom < 100) {
            prefs().edit().putInt(zoomPref, 100).apply()
        }
    }

    private fun glassEffectsEnabled(): Boolean =
        prefs().getBoolean(GLASS_EFFECTS_PREF, true)

    private fun appLibraryGlassEnabled(): Boolean =
        isHonorDevice() || glassEffectsEnabled() || widgetModeNativeGlassActive() || innerWallpaperModeActive()

    private fun focusSurfaceGlassEnabled(): Boolean =
        isHonorDevice() || glassEffectsEnabled() || widgetModeNativeGlassActive() || innerWallpaperModeActive()

    private fun gridWorkspaceLabEnabled(): Boolean =
        prefs().getBoolean(GRID_WORKSPACE_LAB_PREF, false)

    private fun gridHomeAliasComponent() =
        ComponentName(this, "com.fran.teclas.grid.GridHomeAlias")

    private fun gridHomeAliasEnabled(): Boolean =
        packageManager.getComponentEnabledSetting(gridHomeAliasComponent()) ==
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun setGridHomeAliasEnabled(enabled: Boolean) {
        packageManager.setComponentEnabledSetting(
            gridHomeAliasComponent(),
            if (enabled) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )
    }

    private fun appLibraryDefaultHome(): Boolean =
        prefs().getBoolean(APP_LIBRARY_DEFAULT_HOME_PREF, false)

    internal fun isUnfoldedInnerLayoutActive(): Boolean =
        foldPosture is FoldPosture.Inner && resources.configuration.screenWidthDp >= 600

    private fun innerKeyboardWidthPercent(): Int =
        prefs().getInt(INNER_KEYBOARD_WIDTH_PREF, 68).coerceIn(48, 100)

    private fun unfoldedKeyboardPanelWidth(): Int {
        val maxWidth = resources.displayMetrics.widthPixels
        return (maxWidth * innerKeyboardWidthPercent() / 100f).toInt().coerceIn(dp(720), maxWidth - dp(36))
    }

    private fun unfoldedKeyboardPanelSnapLimit(panelWidth: Int = unfoldedKeyboardPanelWidth()): Int =
        ((resources.displayMetrics.widthPixels - panelWidth) / 2).coerceAtLeast(0)

    private fun unfoldedKeyboardPanelOffsetX(panelWidth: Int = unfoldedKeyboardPanelWidth()): Int =
        innerKeyboardOffsetX().coerceIn(-unfoldedKeyboardPanelSnapLimit(panelWidth), unfoldedKeyboardPanelSnapLimit(panelWidth))

    private fun forceFoldableWidgetPlacement() {
        if (!isUnfoldedInnerLayoutActive() || keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET) return
        foldPreviousKeyboardPlacement = foldPreviousKeyboardPlacement ?: keyboardPlacement
        keyboardPlacement = KEYBOARD_PLACEMENT_WIDGET
        VivoDockedExperiment.clearViewportTruncation(this)
        stopService(Intent(this, DockedKeyboardService::class.java))
    }

    private fun innerKeyboardSizeBoost(): Int =
        (innerKeyboardPreviewBoost ?: prefs().getInt(INNER_KEYBOARD_SIZE_BOOST_PREF, 52)).coerceIn(-20, 150)

    private fun innerKeyboardOffsetX(): Int =
        prefs().getInt(INNER_KEYBOARD_OFFSET_X_PREF, 0)

    private fun innerKeyboardOffsetY(): Int =
        prefs().getInt(INNER_KEYBOARD_OFFSET_Y_PREF, 0)

    private fun effectiveKeyboardSize(): Int =
        (keyboardSize + if (isUnfoldedInnerLayoutActive()) innerKeyboardSizeBoost() + 12 else 0)
            .coerceIn(0, if (isUnfoldedInnerLayoutActive()) 190 else 100)

    private fun handleFoldPosture(posture: FoldPosture) {
        val wasInner = isUnfoldedInnerLayoutActive()
        saveWidgetKeyboardHiddenForCurrentPosture()
        foldPosture = posture
        val isInner = isUnfoldedInnerLayoutActive()
        homeWallpaperDrawable = null
        innerWallpaperImageView = null
        widgetKeyboardHidden = loadWidgetKeyboardHiddenForCurrentPosture()
        widgetKeyboardSliderAnimating = false
        if (!wasInner && isInner) {
            forceFoldableWidgetPlacement()
            applyUnfoldedContextSwitch()
        } else if (wasInner && !isInner) {
            restoreFoldedContextIfNeeded()
            restoreFoldedKeyboardPlacementIfNeeded()
        }
        if (wasInner != isInner && ::rootView.isInitialized) {
            libraryOpen = if (isInner) false else appLibraryDefaultHome()
            openPane = if (isInner) null else openPane
            keyboardSettingsOpen = false
            render()
        } else if (isInner) {
            refreshUnfoldedLibraryContent()
            refreshUnfoldedFocusContent()
            renderFavoritesDock()
        }
    }

    private fun applyUnfoldedContextSwitch() {
        val target = SpaceManager.space(this, "work")
            ?: SpaceManager.spaces(this).firstOrNull { it.enabled && it.id == "home" }
            ?: SpaceManager.spaces(this).firstOrNull { it.enabled }
            ?: return
        foldPreviousSpaceLock = SpaceManager.lockedSpaceId(this)
        foldAutoLockActive = true
        foldBannerSpace = null
        SpaceManager.lock(this, target.id)
        invalidateLibraryCaches()
        refreshPredictContext(rerender = true)
    }

    private fun restoreFoldedContextIfNeeded() {
        if (!foldAutoLockActive) return
        SpaceManager.lock(this, foldPreviousSpaceLock)
        foldPreviousSpaceLock = null
        foldAutoLockActive = false
        foldBannerSpace = null
        invalidateLibraryCaches()
        refreshPredictContext(rerender = true)
    }

    private fun restoreFoldedKeyboardPlacementIfNeeded() {
        val previous = foldPreviousKeyboardPlacement ?: return
        foldPreviousKeyboardPlacement = null
        keyboardPlacement = previous
        if (previous == KEYBOARD_PLACEMENT_DOCKED) syncVivoDockedExperiment()
    }

    private fun loadHomeWallpaperDrawable(): Drawable? {
        normalizeInnerWallpaperTransform()
        val selectedDrawable = activeHomeWallpaperUri()?.let { uri ->
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)?.let { bitmap -> BitmapDrawable(resources, bitmap) }
                }
            }.onFailure {
                android.util.Log.w("TeclasWallpaper", "Selected wallpaper load failed", it)
            }.getOrNull()
        }
        if (selectedDrawable != null) return selectedDrawable
        if (!launcherWallpaperCanvasActive() && !useLockscreenWallpaperOnHome()) return null
        val manager = WallpaperManager.getInstance(this)
        fun fileDrawable(which: Int): Drawable? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
            return runCatching {
                manager.getWallpaperFile(which)?.use { descriptor ->
                    BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor)
                        ?.let { bitmap -> BitmapDrawable(resources, bitmap) }
                }
            }.onFailure {
                android.util.Log.w("TeclasWallpaper", "Device wallpaper file load failed for $which", it)
            }.getOrNull()
        }
        val drawable = fileDrawable(WallpaperManager.FLAG_LOCK)
            ?: fileDrawable(WallpaperManager.FLAG_SYSTEM)
            ?: runCatching { manager.peekDrawable() }.getOrNull()
            ?: runCatching { manager.drawable }.getOrNull()
            ?: runCatching { manager.fastDrawable }.getOrNull()
        android.util.Log.i(
            "TeclasWallpaper",
            "device wallpaper loaded=${drawable != null} type=${drawable?.javaClass?.simpleName}"
        )
        return drawable?.constantState?.newDrawable(resources)?.mutate() ?: drawable
    }

    private fun homeWallpaperLayer(): View? {
        val wallpaper = homeWallpaperDrawable ?: loadHomeWallpaperDrawable()?.also { homeWallpaperDrawable = it }
        if (wallpaper == null && !launcherWallpaperCanvasActive()) return null
        return FrameLayout(this).apply {
            if (wallpaper != null) {
                if (activeHomeWallpaperUri() != null) {
                    addView(ImageView(context).apply {
                        setImageDrawable(wallpaper.constantState?.newDrawable(resources)?.mutate() ?: wallpaper)
                        scaleType = ImageView.ScaleType.MATRIX
                        alpha = 1f
                        innerWallpaperImageView = this
                        post { applyInnerWallpaperMatrix(this) }
                    }, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                } else {
                    addView(ImageView(context).apply {
                        setImageDrawable(wallpaper.constantState?.newDrawable(resources)?.mutate() ?: wallpaper)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        alpha = 1f
                    }, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                }
            } else {
                addView(View(context).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        if (activeNeuTokens.mode == NeuMode.LIGHT) {
                            intArrayOf(0xFFF1F5F7.toInt(), 0xFFE7F0F3.toInt(), 0xFFF4EFE8.toInt())
                        } else {
                            intArrayOf(0xFF111923.toInt(), 0xFF071015.toInt(), 0xFF121016.toInt())
                        }
                    )
                }, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            if (wallpaper == null) {
                addView(View(context).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        if (activeNeuTokens.mode == NeuMode.LIGHT) {
                            intArrayOf(0x10F4F5F1, 0x18EEF0EA, 0x22DADDD8)
                        } else {
                            intArrayOf(0x2206080B, 0x1A12161C, 0x2A050609)
                        }
                    )
                }, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
        }
    }

    private fun applyInnerWallpaperMatrix(image: ImageView) {
        val drawable = image.drawable ?: return
        val viewW = image.width.toFloat()
        val viewH = image.height.toFloat()
        val drawW = drawable.intrinsicWidth.takeIf { it > 0 }?.toFloat() ?: return
        val drawH = drawable.intrinsicHeight.takeIf { it > 0 }?.toFloat() ?: return
        if (viewW <= 0f || viewH <= 0f) return
        val fillScale = maxOf(viewW / drawW, viewH / drawH)
        val baseScale = fillScale * innerWallpaperZoom()
        val scaledW = drawW * baseScale
        val scaledH = drawH * baseScale
        val maxX = ((scaledW - viewW) / 2f).coerceAtLeast(0f)
        val maxY = ((scaledH - viewH) / 2f).coerceAtLeast(0f)
        image.imageMatrix = Matrix().apply {
            postScale(baseScale, baseScale)
            postTranslate(
                (viewW - scaledW) / 2f + maxX * innerWallpaperOffsetX(),
                (viewH - scaledH) / 2f + maxY * innerWallpaperOffsetY()
            )
        }
    }

    private fun innerWallpaperPanBounds(image: ImageView, zoom: Float = innerWallpaperZoom()): Pair<Float, Float> {
        val drawable = image.drawable ?: return 0f to 0f
        val viewW = image.width.toFloat()
        val viewH = image.height.toFloat()
        val drawW = drawable.intrinsicWidth.takeIf { it > 0 }?.toFloat() ?: return 0f to 0f
        val drawH = drawable.intrinsicHeight.takeIf { it > 0 }?.toFloat() ?: return 0f to 0f
        if (viewW <= 0f || viewH <= 0f) return 0f to 0f
        val fillScale = maxOf(viewW / drawW, viewH / drawH)
        val baseScale = fillScale * zoom
        return (((drawW * baseScale) - viewW) / 2f).coerceAtLeast(1f) to
            (((drawH * baseScale) - viewH) / 2f).coerceAtLeast(1f)
    }

    private fun setInnerWallpaperTransform(zoomPercent: Int, offsetXPercent: Int, offsetYPercent: Int) {
        prefs().edit()
            .putInt(activeWallpaperZoomPref(), zoomPercent.coerceIn(100, 260))
            .putInt(activeWallpaperOffsetXPref(), offsetXPercent.coerceIn(-100, 100))
            .putInt(activeWallpaperOffsetYPref(), offsetYPercent.coerceIn(-100, 100))
            .apply()
        innerWallpaperImageView?.let { applyInnerWallpaperMatrix(it) }
    }

    internal fun cancelWallpaperLongPress() {
        wallpaperLongPressRunnable?.let { handler.removeCallbacks(it) }
        wallpaperLongPressRunnable = null
    }

    private fun installWallpaperEditLongPress(surface: View) {
        // Hardened against accidental activation: a long, deliberate ~2s hold that stays put.
        // Any drift beyond a small slop, a second finger, or a lift cancels it.
        val cancelSlop = dp(8)
        val holdDelay = android.view.ViewConfiguration.getLongPressTimeout().toLong() + 1700L
        var downX = 0f
        var downY = 0f
        surface.isLongClickable = false
        surface.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelWallpaperLongPress()
                    downX = event.rawX
                    downY = event.rawY
                    val runnable = Runnable {
                        wallpaperLongPressRunnable = null
                        if (!todayOpen && !libraryOpen && !libraryDragActive && widgetBoardView == null && openPane == null) {
                            haptic(view)
                            openDeviceWallpaperPicker(view)
                        }
                    }
                    wallpaperLongPressRunnable = runnable
                    handler.postDelayed(runnable, holdDelay)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    cancelWallpaperLongPress()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount > 1 || abs(event.rawX - downX) > cancelSlop || abs(event.rawY - downY) > cancelSlop) {
                        cancelWallpaperLongPress()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelWallpaperLongPress()
            }
            false
        }
    }

    private fun innerWallpaperEditOverlay(): View = FrameLayout(this).apply {
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.TRANSPARENT)

        var startX = 0f
        var startY = 0f
        var startOffsetX = prefs().getInt(activeWallpaperOffsetXPref(), 0)
        var startOffsetY = prefs().getInt(activeWallpaperOffsetYPref(), 0)
        var startZoom = prefs().getInt(activeWallpaperZoomPref(), 100)
        var startDistance = 0f

        setOnTouchListener { _, event ->
            val image = innerWallpaperImageView ?: return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    startOffsetX = prefs().getInt(activeWallpaperOffsetXPref(), 0)
                    startOffsetY = prefs().getInt(activeWallpaperOffsetYPref(), 0)
                    startZoom = prefs().getInt(activeWallpaperZoomPref(), 100)
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        startDistance = distance(event)
                        startZoom = prefs().getInt(activeWallpaperZoomPref(), 100)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2 && startDistance > 0f) {
                        val ratio = (distance(event) / startDistance).takeIf { it.isFinite() } ?: 1f
                        val nextZoom = (startZoom * ratio).toInt().coerceIn(100, 260)
                        setInnerWallpaperTransform(
                            nextZoom,
                            prefs().getInt(activeWallpaperOffsetXPref(), 0),
                            prefs().getInt(activeWallpaperOffsetYPref(), 0)
                        )
                    } else {
                        val (maxX, maxY) = innerWallpaperPanBounds(image)
                        val nextX = (startOffsetX + ((event.x - startX) / maxX * 100f)).toInt().coerceIn(-100, 100)
                        val nextY = (startOffsetY + ((event.y - startY) / maxY * 100f)).toInt().coerceIn(-100, 100)
                        setInnerWallpaperTransform(
                            prefs().getInt(activeWallpaperZoomPref(), 100),
                            nextX,
                            nextY
                        )
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    haptic(this)
                    true
                }
                else -> true
            }
        }

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            background = Neu.drawable(activeNeuTokens, dp(18).toFloat(), NeuLevel.RAISED_SM)
            addView(mono("DRAG TO MOVE · PINCH TO SIZE", 9f, activeNeuTokens.inkDim).apply {
                letterSpacing = 0.12f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            fun chip(label: String, run: () -> Unit) = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 9f
                letterSpacing = 0.10f
                includeFontPadding = false
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(activeNeuTokens.ink)
                background = Neu.drawable(activeNeuTokens, dp(13).toFloat(), NeuLevel.PRESSED_SM)
                setOnClickListener {
                    haptic(this)
                    run()
                }
            }
            addView(chip("CHANGE") {
                innerWallpaperEditMode = false
                openInnerWallpaperPicker()
            }, LinearLayout.LayoutParams(dp(78), dp(30)).apply { marginStart = dp(8) })
            addView(chip("DONE") {
                innerWallpaperEditMode = false
                render()
            }, LinearLayout.LayoutParams(dp(66), dp(30)).apply { marginStart = dp(8) })
        }, FrameLayout.LayoutParams(
            minOf(dp(430), resources.displayMetrics.widthPixels - dp(28)),
            dp(46),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = systemStatusBarHeight() + dp(18)
        })
    }

    private fun distance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun openDeviceWallpaperPicker(anchor: View) {
        haptic(anchor)
        innerWallpaperEditMode = true
        homeWallpaperDrawable = loadHomeWallpaperDrawable()
        if (homeWallpaperDrawable == null) openInnerWallpaperPicker() else render()
    }

    private fun openInnerWallpaperPicker() {
        pendingWallpaperInnerScope = isUnfoldedInnerLayoutActive()
        homeWallpaperDrawable = null
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { innerWallpaperPickerLauncher.launch(intent) }
            .onFailure {
                Toast.makeText(this, "Image picker isn't available here.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showInnerWallpaperOptions(anchor: View) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Neu.drawable(activeNeuTokens, dp(22).toFloat(), NeuLevel.RAISED)
            addView(mono(if (isUnfoldedInnerLayoutActive()) "INNER WALLPAPER" else "COVER WALLPAPER", 9f, activeNeuTokens.inkFaint).apply {
                letterSpacing = 0.18f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams.MATCH_PARENT, dp(22))
        }
        fun addSlider(label: String, pref: String, value: Int, min: Int, max: Int) {
            panel.addView(TextView(this).apply {
                text = label
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(activeNeuTokens.inkDim)
                includeFontPadding = false
            }, LinearLayout.LayoutParams.MATCH_PARENT, dp(22).apply { })
            panel.addView(SeekBar(this).apply {
                this.max = max - min
                progress = value.coerceIn(min, max) - min
                progressDrawable?.alpha = 180
                thumb?.alpha = 220
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        prefs().edit().putInt(pref, min + progress).apply()
                        homeWallpaperDrawable = null
                        if (this@MainActivity::rootView.isInitialized) render()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) { seekBar?.let { haptic(it) } }
                })
            }, LinearLayout.LayoutParams.MATCH_PARENT, dp(34))
        }
        addSlider("Zoom", activeWallpaperZoomPref(), prefs().getInt(activeWallpaperZoomPref(), 100), 70, 220)
        addSlider("Move left / right", activeWallpaperOffsetXPref(), prefs().getInt(activeWallpaperOffsetXPref(), 0), -100, 100)
        addSlider("Move up / down", activeWallpaperOffsetYPref(), prefs().getInt(activeWallpaperOffsetYPref(), 0), -100, 100)
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        lateinit var popup: PopupWindow
        fun action(label: String, run: () -> Unit): TextView = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 10f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(activeNeuTokens.ink)
            includeFontPadding = false
            background = Neu.drawable(activeNeuTokens, dp(14).toFloat(), NeuLevel.PRESSED_SM)
            setOnClickListener {
                haptic(this)
                popup.dismiss()
                run()
            }
        }
        actions.addView(action("CHANGE") { openInnerWallpaperPicker() }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(8) })
        actions.addView(action("RESET") {
            prefs().edit()
                .putInt(activeWallpaperZoomPref(), 100)
                .putInt(activeWallpaperOffsetXPref(), 0)
                .putInt(activeWallpaperOffsetYPref(), 0)
                .apply()
            homeWallpaperDrawable = null
            render()
        }, LinearLayout.LayoutParams(0, dp(34), 1f))
        panel.addView(actions, LinearLayout.LayoutParams.MATCH_PARENT, dp(42).apply { })
        popup = PopupWindow(panel, dp(330), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(14).toFloat()
        }
        popup.showAtLocation(anchor, Gravity.CENTER, 0, 0)
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
                // "Today" teaser lives in the space below the widget stack; collapses to 0dp when empty.
                if (todayEnabled) {
                    todayAlertView = ComposeView(context).apply {
                        setBackgroundColor(Color.TRANSPARENT)
                        todayPaneHost.setTodayAlertContent(this)
                    }
                    addView(todayAlertView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(8)
                    })
                }
                addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, if (keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET) 0.14f else 0.22f))
                favoritesDockFrameView = favoritesDockFlipSurface(context)
                addView(favoritesDockFrameView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(62)).apply {
                    topMargin = dp(6)
                    // Small gap above the docked keyboard deck (its height now accounts for the
                    // suggestion strip, so it no longer bleeds up — see activeRootDockHeight()).
                    bottomMargin = if (keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET) dp(4) else dp(6)
                })
            }
            if (keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET && !widgetPaneUsesRootDock()) {
                addView(homeKeyboardWidget(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, widgetKeyboardSlotHeight()).apply {
                    leftMargin = -widgetKeyboardHorizontalBleed()
                    rightMargin = -widgetKeyboardHorizontalBleed()
                    bottomMargin = dp(0)
                })
            }
        }
        return FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            installWallpaperEditLongPress(this)
            weatherAmbientView = null
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            weatherDripView = WeatherDripView(context)
            addView(weatherDripView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            weatherDripView?.refresh()
        }
    }

    private fun phoneWidgetHome(): View {
        homeTileViews.clear()
        widgetSearchContentArea = null
        val widgetSearchActive = isWidgetUniversalSearchActive()
        widgetSearchRendered = widgetSearchActive
        homeEditMode = false
        homeEditChipView = null
        return FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            installWallpaperEditLongPress(this)
            weatherAmbientView = null

            val content = LinearLayout(context).apply {
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
                    addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                    favoritesDockFrameView = favoritesDockFlipSurface(context)
                    addView(favoritesDockFrameView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(62)).apply {
                        topMargin = dp(6)
                        bottomMargin = dp(4)
                    })
                }

                if (!widgetPaneUsesRootDock()) {
                    addView(homeKeyboardWidget(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, widgetKeyboardSlotHeight()).apply {
                        leftMargin = -widgetKeyboardHorizontalBleed()
                        rightMargin = -widgetKeyboardHorizontalBleed()
                    })
                }
            }
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun favoritesDockFlipSurface(
        context: Context,
        frontPaddingHorizontalDp: Int = 6,
        frontPaddingVerticalDp: Int = 9,
        backPaddingHorizontalDp: Int = 8,
        backPaddingVerticalDp: Int = 9,
        showAffordance: Boolean = true
    ): FavoritesDockFlipFrame {
        // Context-first by default; show pinned on launch only if the user has an active
        // pinned override (they last swiped to their own apps and haven't left that Space).
        favoritesDockContextShowing = dockPinnedOverrideSpace() == null
        favoritesDockContextPreferred = favoritesDockContextShowing
        val frame = FavoritesDockFlipFrame(context).apply {
            clipChildren = false
            clipToPadding = false
            cameraDistance = resources.displayMetrics.density * 9000f
            background = ColorDrawable(Color.TRANSPARENT)
        }
        favoritesDockView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(
                dp(frontPaddingHorizontalDp),
                dp(frontPaddingVerticalDp),
                dp(frontPaddingHorizontalDp),
                dp(frontPaddingVerticalDp)
            )
        }
        favoritesDockContextView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(
                dp(backPaddingHorizontalDp),
                dp(backPaddingVerticalDp),
                dp(backPaddingHorizontalDp),
                dp(backPaddingVerticalDp)
            )
            visibility = if (favoritesDockContextShowing) View.VISIBLE else View.GONE
            alpha = if (favoritesDockContextShowing) 1f else 0f
            rotationX = if (favoritesDockContextShowing) 0f else 90f
        }
        favoritesDockView.visibility = if (favoritesDockContextShowing) View.GONE else View.VISIBLE
        favoritesDockView.alpha = if (favoritesDockContextShowing) 0f else 1f
        favoritesDockView.rotationX = if (favoritesDockContextShowing) -90f else 0f
        frame.addView(foldAwareGlassPlate(context, radiusDp = 19), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(favoritesDockView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(favoritesDockContextView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (showAffordance) {
            frame.addView(View(context).apply {
                background = GradientDrawable().apply {
                    setColor(adjustAlpha(activeNeuTokens.inkDim, if (activeNeuTokens.mode == NeuMode.LIGHT) 0.32f else 0.22f))
                    cornerRadius = dp(1).toFloat()
                }
            }, FrameLayout.LayoutParams(dp(24), dp(2), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(5)
            })
        }
        favoritesDockContextStampView = contextDockStampView(context).apply {
            visibility = if (favoritesDockContextShowing) View.VISIBLE else View.GONE
            alpha = if (favoritesDockContextShowing) 1f else 0f
            translationY = if (favoritesDockContextShowing) 0f else dp(6).toFloat()
        }
        frame.addView(favoritesDockContextStampView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(28), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = -dp(31)
        })
        scheduleContextDockRefresh()
        return frame
    }

    private fun contextDockStampView(context: Context): TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        textSize = 10.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.12f
        setPadding(dp(13), 0, dp(13), 0)
        isClickable = false
        isFocusable = false
    }

    private fun unfoldedHome(): View {
        homeTileViews.clear()
        widgetSearchContentArea = null
        widgetSearchRendered = false
        homeEditMode = false
        homeEditChipView = null
        unfoldedLibraryContentArea = null
        unfoldedFocusContentArea = null
        unfoldedFocusDockView = null
        favoritesDockContextShowing = dockPinnedOverrideSpace() == null

        val focusArea = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
        }
        unfoldedFocusContentArea = focusArea
        val panelWidth = unfoldedKeyboardPanelWidth()
        val panelOffsetX = unfoldedKeyboardPanelOffsetX(panelWidth).toFloat()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(30), dp(14), dp(30), dp(18))
            addView(unfoldedFocusTopBar(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply {
                bottomMargin = dp(12)
            })
            addView(FrameLayout(context).apply {
                clipChildren = true
                clipToPadding = true
                translationX = panelOffsetX
                addView(focusArea, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(panelWidth, 0, 1f).apply {
                bottomMargin = dp(10)
            })
            val dock = unfoldedFocusDock()
            val dockStage = FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                translationX = panelOffsetX
                addView(dock, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
            unfoldedFocusDockView = dockStage
            addView(dockStage, LinearLayout.LayoutParams(panelWidth, dp(82)))
        }

        return FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            installWallpaperEditLongPress(this)
            weatherAmbientView = null
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            weatherDripView = WeatherDripView(context)
            addView(weatherDripView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            weatherDripView?.refresh(playMoment = false)
            post { refreshUnfoldedFocusContent() }
        }
    }

    internal fun refreshUnfoldedFocusContent() {
        val area = unfoldedFocusContentArea ?: return
        val searching = query.isNotBlank()
        val showLibrary = libraryOpen || innerLibraryLocked()
        val showToday = todayOpen
        val dockVisible = !searching && !showToday && (!libraryOpen || innerLibraryLocked())
        setUnfoldedFocusDockVisible(dockVisible)
        area.removeAllViews()
        val child = when {
            searching -> unfoldedSearchCanvas()
            showToday -> unfoldedTodayCanvas()
            showLibrary -> unfoldedAppLibraryCanvas()
            else -> null
        }
        child?.let {
            area.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun setUnfoldedFocusDockVisible(visible: Boolean) {
        val dock = unfoldedFocusDockView ?: return
        val lp = dock.layoutParams as? LinearLayout.LayoutParams
        val targetHeight = if (visible) dp(82) else 0
        if (lp != null && lp.height != targetHeight) {
            lp.height = targetHeight
            dock.layoutParams = lp
        }
        dock.animate().cancel()
        dock.visibility = if (visible) View.VISIBLE else View.GONE
        dock.alpha = if (visible) 1f else 0f
        dock.translationY = if (visible) 0f else dp(12).toFloat()
    }

    private fun innerLibraryLocked(): Boolean =
        prefs().getBoolean(INNER_LIBRARY_LOCKED_PREF, false)

    private fun setInnerLibraryLocked(locked: Boolean) {
        prefs().edit().putBoolean(INNER_LIBRARY_LOCKED_PREF, locked).apply()
        refreshUnfoldedFocusContent()
    }

    private fun innerGlassPanel(content: View, radiusDp: Int = 28): View = FrameLayout(this).apply {
        if (nativeGlassSurfaceActive()) {
            return NativeFoldGlassPanel(this@MainActivity, radiusDp).apply {
                addView(content, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
        }
        clipChildren = false
        clipToPadding = false
        addView(DynamicGlassPlate(context, radiusDp = radiusDp, strength = 2.25f, edgeInsetDp = 0).apply {
            setGlassProgress(1f)
        }, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        addView(View(context).apply {
            background = innerGlassFrostWash(radiusDp)
        }, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        addView(content, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }

    private fun innerGlassFrostWash(radiusDp: Int): Drawable {
        val light = glassLightMode()
        val radius = dp(radiusDp).toFloat()
        val body = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (light) {
                intArrayOf(
                    adjustAlpha(0xFFF8FAFC.toInt(), 0.58f),
                    adjustAlpha(0xFFE9EEF5.toInt(), 0.46f),
                    adjustAlpha(0xFFDCE4EE.toInt(), 0.54f)
                )
            } else {
                intArrayOf(
                    adjustAlpha(0xFF2A2F3A.toInt(), 0.54f),
                    adjustAlpha(0xFF181B21.toInt(), 0.44f),
                    adjustAlpha(0xFF05070A.toInt(), 0.58f)
                )
            }
        ).apply { cornerRadius = radius }
        val topBloom = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                adjustAlpha(Color.WHITE, if (light) 0.30f else 0.16f),
                adjustAlpha(Color.WHITE, if (light) 0.08f else 0.04f),
                Color.TRANSPARENT
            )
        ).apply { cornerRadius = radius }
        val edge = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = radius
            setStroke(dp(1), adjustAlpha(Color.WHITE, if (light) 0.28f else 0.11f))
        }
        return LayerDrawable(arrayOf(body, topBloom, edge)).apply {
            setLayerInset(1, dp(1), dp(1), dp(1), dp(18))
            setLayerInset(2, 0, 0, 0, 0)
        }
    }

    private fun foldAwareGlassPlate(context: Context, radiusDp: Int, strength: Float = 1.72f): View =
        if (nativeGlassSurfaceActive()) {
            NativeFoldGlassPanel(context, radiusDp, compactDockGlass = true)
        } else {
            DynamicGlassPlate(context, radiusDp = radiusDp, strength = strength, edgeInsetDp = 0).apply {
                setGlassProgress(1f)
            }
        }

    private fun rootViewWidthOrScreen(): Int =
        if (this::rootView.isInitialized && rootView.width > 0) rootView.width else resources.displayMetrics.widthPixels

    private fun rootViewHeightOrScreen(): Int =
        if (this::rootView.isInitialized && rootView.height > 0) rootView.height else resources.displayMetrics.heightPixels

    private fun rootViewScreenLocation(out: IntArray) {
        if (this::rootView.isInitialized) {
            rootView.getLocationOnScreen(out)
        } else {
            out[0] = 0
            out[1] = 0
        }
    }

    private inner class NativeFoldGlassPanel(
        context: Context,
        private val radiusDp: Int,
        private val compactDockGlass: Boolean = false
    ) : FrameLayout(context) {
        private val honorGlass = isHonorDevice()
        private val blurLayer = FoldGlassWallpaperLayer(context, radiusDp, compactDockGlass, honorGlass)
        private val washLayer = FoldGlassWashLayer(context, radiusDp, compactDockGlass, honorGlass)
        private val preDrawListener = android.view.ViewTreeObserver.OnPreDrawListener {
            blurLayer.syncToPanel(invalidateEvenIfStill = false)
            true
        }

        init {
            clipChildren = true
            clipToPadding = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, dp(radiusDp).toFloat())
                    }
                }
                clipToOutline = true
            }
            addView(blurLayer, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(washLayer, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnPreDrawListener(preDrawListener)
            refreshNativeGlass()
        }

        override fun onDetachedFromWindow() {
            if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            super.onDetachedFromWindow()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            refreshNativeGlass()
        }

        private fun refreshNativeGlass() {
            post {
                blurLayer.syncToPanel(invalidateEvenIfStill = true)
                washLayer.invalidate()
            }
        }
    }

    private inner class FoldGlassWallpaperLayer(
        context: Context,
        radiusDp: Int,
        private val compactDockGlass: Boolean,
        private val honorGlass: Boolean
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val clipPath = Path()
        private val rect = RectF()
        private val matrix = Matrix()
        private val radius = dp(radiusDp).toFloat()
        private var panelScreenX = 0
        private var panelScreenY = 0

        init {
            setWillNotDraw(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val blur = when {
                    honorGlass && compactDockGlass -> dp(92).toFloat()
                    honorGlass -> dp(78).toFloat()
                    compactDockGlass -> dp(48).toFloat()
                    else -> dp(34).toFloat()
                }
                setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP))
            }
            alpha = when {
                honorGlass && glassLightMode() -> 0.72f
                honorGlass -> 0.84f
                compactDockGlass -> 1.0f
                glassLightMode() -> 0.92f
                else -> 0.82f
            }
        }

        fun syncToPanel(invalidateEvenIfStill: Boolean = true) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val moved = panelScreenX != loc[0] || panelScreenY != loc[1]
            panelScreenX = loc[0]
            panelScreenY = loc[1]
            if (moved || invalidateEvenIfStill) invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            syncToPanel(invalidateEvenIfStill = false)
            val drawable = homeWallpaperDrawable ?: loadHomeWallpaperDrawable()?.also { homeWallpaperDrawable = it }
            if (drawable == null || width <= 0 || height <= 0) {
                drawFallback(canvas)
                return
            }
            val rootW = rootViewWidthOrScreen().toFloat()
            val rootH = rootViewHeightOrScreen().toFloat()
            val drawW = drawable.intrinsicWidth.takeIf { it > 0 }?.toFloat() ?: rootW
            val drawH = drawable.intrinsicHeight.takeIf { it > 0 }?.toFloat() ?: rootH
            val fillScale = maxOf(rootW / drawW, rootH / drawH) * if (activeHomeWallpaperUri() != null) innerWallpaperZoom() else 1f
            val scaledW = drawW * fillScale
            val scaledH = drawH * fillScale
            val maxX = ((scaledW - rootW) / 2f).coerceAtLeast(0f)
            val maxY = ((scaledH - rootH) / 2f).coerceAtLeast(0f)
            val rootLoc = IntArray(2)
            rootViewScreenLocation(rootLoc)
            val panelXInRoot = panelScreenX - rootLoc[0]
            val panelYInRoot = panelScreenY - rootLoc[1]

            matrix.reset()
            matrix.postScale(fillScale, fillScale)
            matrix.postTranslate(
                (rootW - scaledW) / 2f + maxX * innerWallpaperOffsetX() - panelXInRoot,
                (rootH - scaledH) / 2f + maxY * innerWallpaperOffsetY() - panelYInRoot
            )
            drawable.setBounds(0, 0, drawW.toInt(), drawH.toInt())
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            clipPath.reset()
            clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.concat(matrix)
            drawable.draw(canvas)
            canvas.restore()
        }

        private fun drawFallback(canvas: Canvas) {
            paint.shader = android.graphics.LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                if (honorGlass && glassLightMode()) {
                    intArrayOf(0xFFEAF2F8.toInt(), 0xFFD7E3ED.toInt(), 0xFFE7EEF4.toInt())
                } else if (honorGlass) {
                    intArrayOf(0xFF344253.toInt(), 0xFF202A36.toInt(), 0xFF111823.toInt())
                } else if (glassLightMode()) {
                    intArrayOf(0xFFE9EEF5.toInt(), 0xFFDCE5EE.toInt(), 0xFFEFF2F4.toInt())
                } else {
                    intArrayOf(0xFF1E2630.toInt(), 0xFF111820.toInt(), 0xFF05070A.toInt())
                },
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, paint)
            paint.shader = null
        }
    }

    private inner class FoldGlassWashLayer(
        context: Context,
        radiusDp: Int,
        private val compactDockGlass: Boolean,
        private val honorGlass: Boolean
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val radius = dp(radiusDp).toFloat()

        init {
            setWillNotDraw(false)
            alpha = 1f
        }

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            val light = glassLightMode()
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                if (honorGlass && light) {
                    intArrayOf(0x8AF1F7FF.toInt(), 0x60D9E6F2.toInt(), 0x78CAD5E2.toInt())
                } else if (honorGlass) {
                    intArrayOf(0x88475568.toInt(), 0x68313C4C.toInt(), 0x861D2531.toInt())
                } else if (compactDockGlass && light) {
                    intArrayOf(0x86E6FAFF.toInt(), 0x5AC5EFFF, 0x78E9D8FF.toInt())
                } else if (compactDockGlass) {
                    intArrayOf(0x66293440, 0x46101820, 0x7A040609)
                } else if (light) {
                    intArrayOf(0xA2F1FBFF.toInt(), 0x68D4F3FF, 0x7EECE0FF)
                } else {
                    intArrayOf(0xC42C3540.toInt(), 0x94141B24.toInt(), 0xD405070A.toInt())
                },
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null

            paint.shader = RadialGradient(
                width * 0.22f,
                height * 0.08f,
                width * 0.85f,
                intArrayOf(
                    adjustAlpha(Color.WHITE, when {
                        honorGlass && light -> 0.34f
                        honorGlass -> 0.17f
                        compactDockGlass && light -> 0.46f
                        compactDockGlass -> 0.22f
                        light -> 0.46f
                        else -> 0.20f
                    }),
                    adjustAlpha(Color.WHITE, when {
                        honorGlass && light -> 0.12f
                        honorGlass -> 0.06f
                        compactDockGlass && light -> 0.16f
                        compactDockGlass -> 0.07f
                        light -> 0.18f
                        else -> 0.09f
                    }),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.44f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.shader = android.graphics.LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                intArrayOf(
                    adjustAlpha(Color.WHITE, when {
                        honorGlass && light -> 0.54f
                        honorGlass -> 0.46f
                        compactDockGlass && light -> 0.78f
                        compactDockGlass -> 0.62f
                        light -> 0.70f
                        else -> 0.38f
                    }),
                    adjustAlpha(Color.WHITE, when {
                        honorGlass && light -> 0.16f
                        honorGlass -> 0.10f
                        compactDockGlass && light -> 0.24f
                        compactDockGlass -> 0.13f
                        light -> 0.22f
                        else -> 0.11f
                    }),
                    adjustAlpha(Color.BLACK, when {
                        honorGlass && light -> 0.22f
                        honorGlass -> 0.62f
                        compactDockGlass && light -> 0.20f
                        compactDockGlass -> 0.82f
                        light -> 0.18f
                        else -> 0.74f
                    })
                ),
                floatArrayOf(0f, 0.54f, 1f),
                Shader.TileMode.CLAMP
            )
            val inset = resources.displayMetrics.density * 0.5f
            canvas.drawRoundRect(RectF(inset, inset, width - inset, height - inset), radius, radius, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL
        }
    }

    private fun unfoldedSearchCanvas(): View = innerGlassPanel(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(20), dp(16), dp(20), dp(18))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "Search"
                textSize = 22f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                includeFontPadding = false
                setTextColor(activeNeuTokens.ink)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = query
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(activeNeuTokens.inkDim)
                gravity = Gravity.RIGHT
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(30)).apply {
            bottomMargin = dp(10)
        })
        addView(unfoldedSearchResultsList(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    })

    private fun unfoldedAppLibraryCanvas(): View = innerGlassPanel(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = true
        clipToPadding = true
        setPadding(dp(18), dp(14), dp(18), dp(16))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "App Library"
                textSize = 22f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                includeFontPadding = false
                setTextColor(activeNeuTokens.ink)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(spaceIcon(), LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                marginEnd = dp(8)
            })
            addView(TextView(context).apply {
                text = if (innerLibraryLocked()) "LOCKED" else "PIN"
                gravity = Gravity.CENTER
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                includeFontPadding = false
                setTextColor(activeNeuTokens.ink)
                background = Neu.drawable(activeNeuTokens, dp(15).toFloat(), if (innerLibraryLocked()) NeuLevel.PRESSED_SM else NeuLevel.RAISED_SM)
                isClickable = true
                setOnClickListener {
                    haptic(this)
                    setInnerLibraryLocked(!innerLibraryLocked())
                }
            }, LinearLayout.LayoutParams(dp(72), dp(30)).apply {
                marginEnd = dp(8)
            })
            addView(TextView(context).apply {
                text = if (libraryGridMode) "Categories" else "Grid"
                gravity = Gravity.CENTER
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setTextColor(activeNeuTokens.ink)
                background = Neu.drawable(activeNeuTokens, dp(15).toFloat(), NeuLevel.RAISED_SM)
                isClickable = true
                setOnClickListener {
                    haptic(this)
                    libraryGridMode = !libraryGridMode
                    prefs().edit().putBoolean(LIBRARY_GRID_MODE_PREF, libraryGridMode).apply()
                    refreshUnfoldedFocusContent()
                }
            }, LinearLayout.LayoutParams(dp(96), dp(30)))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)).apply {
            bottomMargin = dp(10)
        })
        val child = if (query.isNotBlank()) {
            searchResultsGrid()
        } else if (libraryGridMode) {
            libraryGrid()
        } else {
            bentoGrid()
        }
        addView(FrameLayout(context).apply {
            clipChildren = true
            clipToPadding = true
            addView(child, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    })

    private fun unfoldedTodayCanvas(): View = innerGlassPanel(FrameLayout(this).apply {
        clipChildren = false
        clipToPadding = false
        setPadding(dp(8), dp(8), dp(8), dp(8))
        addView(ComposeView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setContent {
                val brief by briefRepository.brief.collectAsState()
                TodayPage(
                    tokens = activeNeuTokens,
                    brief = brief,
                    hasListenerPermission = isNotificationAccessEnabled(),
                    keyboardMode = TodayKeyboardMode.WIDGET,
                    transparentShell = true,
                    onAction = { item, action, reply -> todayPaneHost.fireBriefAction(item, action, reply) },
                    onDismiss = { item -> todayPaneHost.dismissBriefItem(item) },
                    onGrantPermission = { openNotificationAccessSettings() }
                )
            }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    })

    private fun activeSpaceForUi(): Space? {
        val locked = SpaceManager.lockedSpaceId(this)?.let { SpaceManager.space(this, it) }
        if (locked != null) return locked
        return predictContext?.let { SpaceManager.detect(this, it).space }
            ?: SpaceManager.spaces(this).firstOrNull { it.enabled && it.id == "home" }
            ?: SpaceManager.spaces(this).firstOrNull { it.enabled }
    }

    private fun lockSpaceFromInnerCanvas(space: Space?) {
        haptic(contentFrame)
        SpaceManager.lock(this, space?.id)
        invalidateLibraryCaches()
        refreshPredictContext(rerender = false)
        render()
    }

    private fun briefAccent(category: BriefCategory): Int = when (category) {
        BriefCategory.MESSAGE -> 0xFF5FD0C4.toInt()
        BriefCategory.EMAIL -> 0xFFFF6B6B.toInt()
        BriefCategory.CALL -> 0xFF7DB7FF.toInt()
        BriefCategory.CALENDAR -> 0xFFF5C451.toInt()
        BriefCategory.WEATHER -> weatherAmbientLightColor()
        BriefCategory.MUSIC -> 0xFF8FD694.toInt()
        BriefCategory.OTHER -> Accent
    }

    private fun unfoldedFocusHero(): InnerFocusHero {
        val brief = if (::briefRepository.isInitialized) briefRepository.brief.value else Brief.EMPTY
        val briefItems = brief.items
        val media = if (::mediaSessionSource.isInitialized) mediaSessionSource.nowPlaying.value else null
        val nextEvent = calendarEvents.minByOrNull { it.beginMs }
        val weatherTemp = prefs().getString(WEATHER_TEMP_PREF, "--").orEmpty()
        val weatherMeta = prefs().getString(WEATHER_META_PREF, "Local weather").orEmpty()
        val space = activeSpaceForUi()
        if (briefItems.isNotEmpty()) {
            val primary = briefItems.first()
            val accent = briefAccent(primary.category)
            return InnerFocusHero(
                eyebrow = "Today",
                title = primary.title.ifBlank { "Review what needs action." },
                subtitle = primary.subtitle.ifBlank { "Tap to open your Today brief." },
                glyph = primary.category.name.take(3),
                accent = accent,
                secondaryAccent = 0xFF8FD694.toInt(),
                sideNowTitle = nextEvent?.title ?: "${weatherTemp} · ${weatherMeta}",
                sideNowBody = nextEvent?.let { "${it.dayLabel.ifBlank { "Today" }} · ${it.timeLabel}" } ?: "Weather stays calm until it matters.",
                sideMediaTitle = media?.sourceApp?.takeIf { it.isNotBlank() } ?: "Music",
                sideMediaBody = media?.let { "${it.title} · ${it.artist}" } ?: "Music appears when it becomes context.",
                actions = emptyList(),
                run = { todayPaneHost.openToday() }
            )
        }
        if (nextEvent != null) {
            return InnerFocusHero(
                eyebrow = nextEvent.dayLabel.ifBlank { "Calendar" },
                title = nextEvent.title.ifBlank { "Next event" },
                subtitle = "${nextEvent.timeLabel}${nextEvent.location.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}",
                glyph = "CAL",
                accent = 0xFFF5C451.toInt(),
                secondaryAccent = 0xFF7DB7FF.toInt(),
                sideNowTitle = "${weatherTemp} · ${weatherMeta}",
                sideNowBody = "Weather and calendar share the glance without crowding the canvas.",
                sideMediaTitle = media?.sourceApp ?: "Music",
                sideMediaBody = media?.let { "${it.title} · ${it.artist}" } ?: "No active playback.",
                actions = emptyList(),
                run = { openCalendarEventOrRequest(nextEvent) }
            )
        }
        if (media?.isPlaying == true) {
            return InnerFocusHero(
                eyebrow = "Now Playing",
                title = media.title.ifBlank { "Keep listening." },
                subtitle = listOf(media.artist, media.sourceApp).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Tap to open Music." },
                glyph = "♪",
                accent = media.appIconColor,
                secondaryAccent = 0xFF8FD694.toInt(),
                sideNowTitle = "${weatherTemp} · ${weatherMeta}",
                sideNowBody = "The rest of the launcher stays quiet while music owns the moment.",
                sideMediaTitle = media.sourceApp.ifBlank { "Music" },
                sideMediaBody = "Tap the hero to open the player.",
                actions = emptyList(),
                run = { openHere(musicTarget()) }
            )
        }
        return InnerFocusHero(
            eyebrow = space?.name ?: "Teclas",
            title = "Type to do anything.",
            subtitle = "The inner screen stays calm: one focus card, a few actions, favorite apps, and the keyboard always ready.",
            glyph = space?.emoji ?: "C",
            accent = weatherAmbientLightColor(),
            secondaryAccent = Accent,
            sideNowTitle = "${weatherTemp} · ${weatherMeta}",
            sideNowBody = "Weather is a quiet signal, not a dashboard.",
            sideMediaTitle = "App Library",
            sideMediaBody = "Type or swipe when you need every app.",
            actions = emptyList(),
            run = { }
        )
    }

    private fun unfoldedFocusTopBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        clipChildren = false
        clipToPadding = false
        addView(unfoldedWeatherChip(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    private fun unfoldedWeatherChip(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        setOnClickListener {
            haptic(this)
            if (hasWeatherPermission()) refreshWeather(force = true) else weatherPermissionLauncher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        addView(TextView(context).apply {
            text = prefs().getString(WEATHER_TEMP_PREF, "--")
            textSize = 38f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(activeNeuTokens.ink)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) })
        addView(TextView(context).apply {
            text = prefs().getString(WEATHER_META_PREF, "Local weather")
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(activeNeuTokens.inkDim)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun unfoldedContextSelector(): View = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        background = Neu.drawable(activeNeuTokens, dp(21).toFloat(), NeuLevel.PRESSED_SM)
        setPadding(dp(6), dp(5), dp(6), dp(5))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val spaces = SpaceManager.spaces(this@MainActivity).filter { it.enabled }.take(5)
        val active = activeSpaceForUi()
        row.addView(unfoldedContextPill(null, active == null), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
            marginEnd = dp(6)
        })
        spaces.forEachIndexed { index, space ->
            row.addView(unfoldedContextPill(space, active?.id == space.id), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                if (index < spaces.lastIndex) marginEnd = dp(6)
            })
        }
        addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun unfoldedContextPill(space: Space?, selected: Boolean): View = TextView(this).apply {
        text = if (space == null) "Auto" else "${space.emoji} ${space.name}"
        gravity = Gravity.CENTER
        textSize = 12.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        includeFontPadding = false
        setTextColor(if (selected) activeNeuTokens.ink else activeNeuTokens.inkDim)
        setPadding(dp(16), 0, dp(16), 0)
        background = if (selected) Neu.drawable(activeNeuTokens, dp(17).toFloat(), NeuLevel.RAISED_SM) else ColorDrawable(Color.TRANSPARENT)
        isClickable = true
        setOnClickListener { lockSpaceFromInnerCanvas(space) }
    }

    private fun unfoldedFocusMain(hero: InnerFocusHero): View = FrameLayout(this).apply {
        clipChildren = false
        clipToPadding = false
        val maxCardWidth = dp(920).coerceAtMost(resources.displayMetrics.widthPixels - dp(260)).coerceAtLeast(dp(560))
        addView(unfoldedHeroCard(hero), FrameLayout.LayoutParams(maxCardWidth, dp(136), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(10)
        })
    }

    private fun unfoldedHeroCard(hero: InnerFocusHero): View = FrameLayout(this).apply {
        clipChildren = false
        clipToPadding = false
        isClickable = true
        setOnClickListener { haptic(this); hero.run() }
        background = unfoldedHeroBackground(hero.accent, hero.secondaryAccent)
        elevation = dp(2).toFloat()
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                adjustAlpha(brighten(hero.accent), 0.92f),
                adjustAlpha(hero.accent, 0.72f),
                adjustAlpha(hero.secondaryAccent, 0.72f)
            )).apply {
                cornerRadius = dp(2).toFloat()
            }
        }, FrameLayout.LayoutParams(dp(3), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.LEFT or Gravity.CENTER_VERTICAL).apply {
            topMargin = dp(22)
            bottomMargin = dp(22)
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(24), dp(16), dp(18), dp(16))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = hero.eyebrow.uppercase(Locale.US)
                    textSize = 9.2f
                    letterSpacing = 0.20f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    includeFontPadding = false
                    setTextColor(hero.accent)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                })
                addView(TextView(context).apply {
                    text = hero.title
                    textSize = 18.5f
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    includeFontPadding = false
                    setTextColor(activeNeuTokens.ink)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    letterSpacing = 0f
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(7)
                })
                addView(TextView(context).apply {
                    text = hero.subtitle
                    textSize = 12.5f
                    setLineSpacing(dp(2).toFloat(), 1.0f)
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    includeFontPadding = false
                    setTextColor(activeNeuTokens.inkDim)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(14) })
            addView(unfoldedHeroGlyph(hero), LinearLayout.LayoutParams(dp(76), dp(76)))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun unfoldedHeroGlyph(hero: InnerFocusHero): View = FrameLayout(this).apply {
        background = unfoldedGlyphWellBackground(hero.accent)
        addView(TextView(context).apply {
            text = hero.glyph.take(3).uppercase(Locale.US)
            gravity = Gravity.CENTER
            textSize = if (hero.glyph.length <= 1) 23f else 16f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            includeFontPadding = false
            setTextColor(activeNeuTokens.ink)
            background = Neu.drawable(activeNeuTokens, dp(19).toFloat(), NeuLevel.RAISED_SM)
        }, FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER))
    }

    private fun unfoldedSideRail(hero: InnerFocusHero): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(unfoldedSideCard("Now", hero.sideNowTitle, hero.sideNowBody), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            bottomMargin = dp(12)
        })
        addView(unfoldedSideCard("Music", hero.sideMediaTitle, hero.sideMediaBody), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            bottomMargin = dp(12)
        })
        addView(unfoldedSideCard("Library", "Type or swipe up", "Apps stay hidden until the user asks for them."), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun unfoldedSideCard(label: String, title: String, body: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
        background = Neu.drawable(activeNeuTokens, dp(24).toFloat(), NeuLevel.RAISED_SM)
        addView(TextView(context).apply {
            text = label.uppercase(Locale.US)
            textSize = 9f
            letterSpacing = 0.16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(activeNeuTokens.inkFaint)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
        addView(TextView(context).apply {
            text = title
            textSize = 17f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(activeNeuTokens.ink)
            includeFontPadding = false
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(7)
        })
        addView(TextView(context).apply {
            text = body
            textSize = 12.5f
            setLineSpacing(dp(1).toFloat(), 1.0f)
            setTextColor(activeNeuTokens.inkDim)
            includeFontPadding = false
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
    }

    private fun unfoldedFocusActions(actions: List<InnerFocusAction>): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        clipChildren = false
        clipToPadding = false
        val visible = actions.take(3)
        visible.forEachIndexed { index, action ->
            addView(unfoldedActionChip(action), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                if (index > 0) marginStart = dp(14)
            })
        }
        repeat(3 - visible.size) { index ->
            addView(View(context), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                if (visible.isNotEmpty() || index > 0) marginStart = dp(14)
            })
        }
    }

    private fun unfoldedActionChip(action: InnerFocusAction): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        setOnClickListener { haptic(this); action.run() }
        setPadding(dp(18), 0, dp(18), 0)
        background = Neu.drawable(activeNeuTokens, dp(22).toFloat(), NeuLevel.RAISED_SM)
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(brighten(action.accent), action.accent)).apply {
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), adjustAlpha(Color.WHITE, 0.16f))
            }
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(14) })
        addView(TextView(context).apply {
            text = action.label
            textSize = 15f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            includeFontPadding = false
            setTextColor(activeNeuTokens.ink)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun unfoldedFocusDock(): View =
        favoritesDockFlipSurface(
            context = this,
            frontPaddingHorizontalDp = 14,
            frontPaddingVerticalDp = 10,
            backPaddingHorizontalDp = 14,
            backPaddingVerticalDp = 10
        )

    private fun unfoldedHeroBackground(accent: Int, secondary: Int): Drawable {
        val tray = Neu.drawable(activeNeuTokens, dp(24).toFloat(), NeuLevel.PRESSED_SM)
        val innerLift = Neu.drawable(activeNeuTokens, dp(22).toFloat(), NeuLevel.RAISED_SM)
        val tint = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
            adjustAlpha(accent, if (activeNeuTokens.mode == NeuMode.LIGHT) 0.05f else 0.08f),
            Color.TRANSPARENT,
            adjustAlpha(secondary, if (activeNeuTokens.mode == NeuMode.LIGHT) 0.025f else 0.04f)
        )).apply { cornerRadius = dp(22).toFloat() }
        return LayerDrawable(arrayOf(tray, innerLift, tint)).apply {
            val inset = dp(5)
            setLayerInset(1, inset, inset, inset, inset)
            setLayerInset(2, inset, inset, inset, inset)
        }
    }

    private fun unfoldedGlyphWellBackground(accent: Int): Drawable {
        val well = Neu.drawable(activeNeuTokens, dp(28).toFloat(), NeuLevel.PRESSED)
        val glow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(28).toFloat()
            setColor(adjustAlpha(accent, if (activeNeuTokens.mode == NeuMode.LIGHT) 0.045f else 0.08f))
        }
        return LayerDrawable(arrayOf(well, glow)).apply {
            val inset = dp(3)
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    private fun unfoldedGlancePane(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(homeHeader(), LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10)))
        nowPlayingCardView = ComposeView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setNowPlayingCardContent()
            elevation = dp(8).toFloat()
        }
        addView(nowPlayingCardView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        if (todayEnabled) {
            todayAlertView = ComposeView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                todayPaneHost.setTodayAlertContent(this)
            }
            addView(todayAlertView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        }
    }

    private fun unfoldedRightPane(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(unfoldedDockRows(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(132)).apply {
            bottomMargin = dp(12)
        })
        addView(unfoldedLibraryPane(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun unfoldedDockRows(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        favoritesDockFrameView = FavoritesDockFlipFrame(context).apply {
            clipChildren = false
            clipToPadding = false
            background = recessedDockBackground()
        }
        favoritesDockView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(dp(6), dp(9), dp(6), dp(9))
        }
        favoritesDockFrameView.addView(foldAwareGlassPlate(context, radiusDp = 19), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        favoritesDockFrameView.addView(favoritesDockView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(favoritesDockFrameView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        favoritesDockContextShowing = dockPinnedOverrideSpace() == null
        favoritesDockContextView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(dp(8), dp(9), dp(8), dp(9))
            visibility = View.VISIBLE
            alpha = 1f
            rotationX = 0f
            background = recessedDockBackground()
        }
        addView(favoritesDockContextView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(8)
        })
        scheduleContextDockRefresh()
    }

    private fun unfoldedLibraryPane(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = teclasGlassDrawable(24)
        addView(libraryHeader(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
            bottomMargin = dp(10)
        })
        val area = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        unfoldedLibraryContentArea = area
        addView(area, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        post { refreshUnfoldedLibraryContent() }
    }

    private fun refreshUnfoldedLibraryContent() {
        val area = unfoldedLibraryContentArea ?: return
        area.removeAllViews()
        val child = if (query.isNotBlank()) {
            if (appLibraryGlassEnabled()) glassSearchBackground(searchResultsGrid()) else searchResultsGrid()
        } else {
            if (libraryGridMode) libraryGrid() else bentoGrid()
        }
        area.addView(child, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }

    private fun foldContextBanner(space: Space): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(8), 0)
        background = Neu.drawable(activeNeuTokens, dp(18).toFloat(), NeuLevel.RAISED_SM)
        elevation = dp(8).toFloat()
        addView(TextView(context).apply {
            text = "${space.emoji}  ${space.name}"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(activeNeuTokens.ink)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = "UNDO"
            gravity = Gravity.CENTER
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(Neu.GREEN)
            includeFontPadding = false
            background = Neu.drawable(activeNeuTokens, dp(12).toFloat(), NeuLevel.PRESSED_SM)
            isClickable = true
            setOnClickListener {
                haptic(this)
                SpaceManager.lock(this@MainActivity, foldPreviousSpaceLock)
                foldPreviousSpaceLock = null
                foldAutoLockActive = false
                foldBannerSpace = null
                invalidateLibraryCaches()
                refreshPredictContext(rerender = true)
                render()
            }
        }, LinearLayout.LayoutParams(dp(64), dp(26)))
        alpha = 0f
        translationY = -dp(8).toFloat()
        animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
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

            widgetKeyboardSeatView = KeyboardSocketView(context).apply {
                alpha = 0.32f
            }
            addView(widgetKeyboardSeatView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

            val socket = KeyboardSocketView(context).apply {
                alpha = 0f
                visibility = View.GONE
            }
            widgetSocketView = socket
            addView(socket, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

            val module = FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                background = homeKeyboardWidgetBackground(activeNeuTokens)
                setOnTouchListener { _, event -> handleWidgetKeyboardDetachedTouch(event) }
            }
            widgetKeyboardModule = module
            populateWidgetKeyboardModule(module)
            addView(module, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

            widgetKeyboardSliderHandleView = KeyboardSliderHandleView(context).apply {
                visibility = if (widgetKeyboardHidden) View.VISIBLE else View.GONE
                alpha = if (widgetKeyboardHidden) 1f else 0f
                setOnTouchListener { _, event -> handleWidgetKeyboardSliderHandleTouch(event) }
            }
            addView(widgetKeyboardSliderHandleView, FrameLayout.LayoutParams(dp(132), dp(34), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(7)
            })
            setOnTouchListener { _, event ->
                if (widgetKeyboardHidden) handleWidgetKeyboardSliderHostTouch(event) else false
            }
            applyWidgetKeyboardHiddenState(animate = false)

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
        module.background = homeKeyboardWidgetBackground(activeNeuTokens)
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
            KEYBOARD_THEME_TECLAS,
            KEYBOARD_THEME_SKEUO,
            KEYBOARD_THEME_GOKEYS,
            KEYBOARD_THEME_HYPER3D,
            KEYBOARD_THEME_HYPER3D_BLACK,
            KEYBOARD_THEME_HYPER3D_LIGHT,
            KEYBOARD_THEME_BRUSHED,
            KEYBOARD_THEME_SEEME
        )

    private fun widgetThemeName(theme: String): String = when (theme) {
        KEYBOARD_THEME_TECLAS -> "TECLAS"
        KEYBOARD_THEME_SKEUO -> "SKEUO"
        KEYBOARD_THEME_GOKEYS -> "GOKEYS"
        KEYBOARD_THEME_HYPER3D -> "HYPER3D"
        KEYBOARD_THEME_HYPER3D_BLACK -> "HYPER BLACK"
        KEYBOARD_THEME_HYPER3D_LIGHT -> "HYPER LIGHT"
        KEYBOARD_THEME_BRUSHED -> "BRUSHED"
        KEYBOARD_THEME_SEEME -> "SEEME"
        else -> "DEFAULT"
    }

    private fun keyboardSwapAnimationMode(): String =
        prefs().getString(KEYBOARD_SWAP_ANIMATION_PREF, KEYBOARD_SWAP_ANIMATION_DEFAULT)
            ?: KEYBOARD_SWAP_ANIMATION_DEFAULT

    private fun keyboardSwapPopOutEnabled(): Boolean =
        keyboardSwapAnimationMode() == KEYBOARD_SWAP_ANIMATION_POPOUT

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
        if (keyboardSwapPopOutEnabled()) {
            beginWidgetKeyboardDetachPopOut(module)
            return
        }
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

    private fun beginWidgetKeyboardDetachPopOut(module: View) {
        module.pivotX = module.width / 2f
        module.pivotY = module.height.toFloat()
        module.rotation = 0f
        module.rotationX = 0f
        module.scaleX = 1f
        module.scaleY = 1f
        module.translationX = 0f
        module.translationY = 0f
        widgetSocketView?.pulseGlow()
        module.animate()
            .translationY(-dp(22).toFloat())
            .scaleX(1.012f)
            .scaleY(1.018f)
            .rotationX(-5f)
            .setDuration(150L)
            .setInterpolator(DecelerateInterpolator(1.8f))
            .withEndAction {
                if (widgetSwapState != WidgetKeyboardSwapState.DETACHING) return@withEndAction
                haptic(module)
                module.animate()
                    .translationY(-dp(138).toFloat())
                    .translationX(0f)
                    .rotation(0f)
                    .rotationX(15f)
                    .scaleX(0.956f)
                    .scaleY(0.948f)
                    .setDuration(380L)
                    .setInterpolator(android.view.animation.OvershootInterpolator(0.92f))
                    .withEndAction {
                        if (widgetSwapState != WidgetKeyboardSwapState.DETACHING) return@withEndAction
                        widgetSwapState = WidgetKeyboardSwapState.DETACHED
                        module.animate()
                            .translationY(-dp(130).toFloat())
                            .rotationX(12f)
                            .scaleX(0.962f)
                            .scaleY(0.952f)
                            .setDuration(140L)
                            .setInterpolator(DecelerateInterpolator(2.0f))
                            .withEndAction {
                                if (widgetSwapState == WidgetKeyboardSwapState.DETACHED) snapWidgetHoverPose()
                            }
                            .start()
                    }
                    .start()
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
        // A few nudge cycles is enough of a hint; an INFINITE animator here kept the GPU
        // redrawing this view for as long as the coach stayed on screen.
        widgetCoachAnimator = ValueAnimator.ofFloat(-1f, 1f).apply {
            duration = 900L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = 5
            addUpdateListener {
                coach.translationX = (it.animatedValue as Float) * dp(5)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    coach.animate().translationX(0f).setDuration(180L).start()
                }
            })
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
            if (keyboardSwapPopOutEnabled()) {
                module.translationY = -dp(132).toFloat()
                module.rotationX = 12f
                module.scaleX = 0.962f
                module.scaleY = 0.952f
            } else {
                module.translationY = -dp(118).toFloat()
                module.rotationX = 9f
                module.scaleX = 0.94f
                module.scaleY = 0.94f
            }
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
        if (keyboardSwapPopOutEnabled()) {
            module.pivotX = module.width / 2f
            module.pivotY = module.height.toFloat()
            module.animate()
                .translationY(-dp(132).toFloat())
                .translationX(0f)
                .rotation(0f)
                .rotationX(12f)
                .scaleX(0.962f)
                .scaleY(0.952f)
                .setDuration(190L)
                .setInterpolator(DecelerateInterpolator(1.8f))
                .start()
            return
        }
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
        if (keyboardSwapPopOutEnabled()) {
            seatWidgetKeyboardPopOut(module)
            return
        }
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

    private fun seatWidgetKeyboardPopOut(module: View) {
        module.pivotX = module.width / 2f
        module.pivotY = module.height.toFloat()
        module.animate()
            .translationY(dp(20).toFloat())
            .translationX(0f)
            .rotation(0f)
            .rotationX(-13f)
            .scaleX(1.018f)
            .scaleY(1.025f)
            .setDuration(210L)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.45f))
            .withEndAction {
                haptic(module)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    module.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
                widgetProngsView?.pulse()
                widgetSocketView?.pulseGlow()
                module.animate()
                    .translationY(dp(4).toFloat())
                    .rotationX(-6f)
                    .scaleX(1.006f)
                    .scaleY(1.012f)
                    .setDuration(105L)
                    .setInterpolator(DecelerateInterpolator(2.2f))
                    .withEndAction {
                        module.animate()
                            .translationY(0f)
                            .rotationX(0f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(210L)
                            .setInterpolator(android.view.animation.OvershootInterpolator(0.74f))
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

    private fun widgetKeyboardSliderAvailable(): Boolean =
        keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET || isUnfoldedInnerLayoutActive()

    private fun widgetKeyboardHiddenPref(inner: Boolean = isUnfoldedInnerLayoutActive()): String =
        if (inner) INNER_WIDGET_KEYBOARD_HIDDEN_PREF else COVER_WIDGET_KEYBOARD_HIDDEN_PREF

    private fun loadWidgetKeyboardHiddenForCurrentPosture(): Boolean =
        prefs().getBoolean(widgetKeyboardHiddenPref(), false)

    private fun saveWidgetKeyboardHiddenForCurrentPosture(hidden: Boolean = widgetKeyboardHidden) {
        prefs().edit().putBoolean(widgetKeyboardHiddenPref(), hidden).apply()
    }

    private fun widgetKeyboardBaseTranslationY(): Float =
        if (isUnfoldedInnerLayoutActive()) innerKeyboardOffsetY().toFloat() else 0f

    private fun widgetKeyboardHiddenTranslationY(module: View): Float {
        val hostHeight = (widgetKeyboardHost?.height ?: 0).coerceAtLeast(module.height)
        return widgetKeyboardBaseTranslationY() + (hostHeight - dp(28)).coerceAtLeast(dp(72)).toFloat()
    }

    private fun animateRootDockHeight(targetHeight: Int, durationMs: Long, startDelayMs: Long = 0L) {
        if (!::keyboardDockView.isInitialized || keyboardDockView.parent == null) return
        val lp = keyboardDockView.layoutParams ?: return
        val start = (lp.height.takeIf { it > 0 } ?: keyboardDockView.height).coerceAtLeast(1)
        if (start == targetHeight) return
        widgetKeyboardDockHeightAnimator?.cancel()
        widgetKeyboardDockHeightAnimator = ValueAnimator.ofInt(start, targetHeight).apply {
            duration = durationMs
            startDelay = startDelayMs
            interpolator = DecelerateInterpolator(1.55f)
            addUpdateListener { animator ->
                val h = animator.animatedValue as Int
                lp.height = h
                keyboardDockView.layoutParams = lp
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    widgetKeyboardDockHeightAnimator = null
                    if (!widgetKeyboardHidden) resetWidgetKeyboardTouchGeometry()
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    widgetKeyboardDockHeightAnimator = null
                }
            })
            start()
        }
    }

    private fun animateWidgetKeyboardHostHeight(targetHeight: Int, durationMs: Long, startDelayMs: Long = 0L) {
        val host = widgetKeyboardHost ?: return
        if (host === keyboardDockView || host.parent == null) return
        val lp = host.layoutParams ?: return
        val start = (lp.height.takeIf { it > 0 } ?: host.height).coerceAtLeast(1)
        if (start == targetHeight) return
        widgetKeyboardHostHeightAnimator?.cancel()
        widgetKeyboardHostHeightAnimator = ValueAnimator.ofInt(start, targetHeight).apply {
            duration = durationMs
            startDelay = startDelayMs
            interpolator = DecelerateInterpolator(1.55f)
            addUpdateListener { animator ->
                lp.height = animator.animatedValue as Int
                host.layoutParams = lp
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    widgetKeyboardHostHeightAnimator = null
                    if (!widgetKeyboardHidden) resetWidgetKeyboardTouchGeometry()
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    widgetKeyboardHostHeightAnimator = null
                }
            })
            start()
        }
    }

    private fun animateWidgetKeyboardSlotHeight(targetHeight: Int, durationMs: Long, startDelayMs: Long = 0L) {
        if (::keyboardDockView.isInitialized && keyboardDockView.parent != null) {
            animateRootDockHeight(targetHeight, durationMs, startDelayMs)
        } else {
            animateWidgetKeyboardHostHeight(targetHeight, durationMs, startDelayMs)
        }
    }

    private fun applyWidgetKeyboardHiddenState(animate: Boolean) {
        val module = widgetKeyboardModule ?: return
        if (widgetKeyboardHidden) {
            module.animate().cancel()
            module.visibility = View.INVISIBLE
            module.alpha = 0f
            module.translationY = widgetKeyboardHiddenTranslationY(module)
            module.rotationX = 0f
            module.scaleX = 1f
            module.scaleY = 1f
            module.isEnabled = false
            widgetKeyboardSeatView?.apply {
                animate().cancel()
                visibility = View.GONE
                alpha = 0f
            }
            widgetKeyboardSliderHandleView?.apply {
                visibility = View.VISIBLE
                alpha = 1f
                translationY = 0f
                isEnabled = true
            }
            if (!animate) return
        } else {
            module.visibility = View.VISIBLE
            module.alpha = 1f
            module.translationY = widgetKeyboardBaseTranslationY()
            module.rotationX = 0f
            module.scaleX = 1f
            module.scaleY = 1f
            module.isEnabled = true
            widgetKeyboardSeatView?.apply {
                visibility = View.VISIBLE
                alpha = 0.32f
            }
            widgetKeyboardSliderHandleView?.apply {
                alpha = 0f
                visibility = View.GONE
                isEnabled = false
            }
        }
    }

    private fun hideWidgetKeyboardSlider() {
        if (!widgetKeyboardSliderAvailable() || widgetKeyboardHidden || widgetKeyboardSliderAnimating || widgetSwapState != WidgetKeyboardSwapState.SEATED) return
        val module = widgetKeyboardModule ?: return
        val handle = widgetKeyboardSliderHandleView
        widgetKeyboardSliderAnimating = true
        module.animate().cancel()
        handle?.apply {
            visibility = View.VISIBLE
            alpha = 0f
            translationY = dp(16).toFloat()
        }
        if (hapticsEnabled) module.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        module.visibility = View.VISIBLE
        module.isEnabled = false
        module.pivotX = module.width / 2f
        module.pivotY = 0f
        module.animate()
            .translationY(widgetKeyboardHiddenTranslationY(module))
            .rotationX(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(0f)
            .setDuration(430L)
            .setInterpolator(DecelerateInterpolator(1.65f))
            .withEndAction {
                widgetKeyboardHidden = true
                saveWidgetKeyboardHiddenForCurrentPosture(true)
                widgetKeyboardSliderAnimating = false
                keyBounds.clear()
                module.visibility = View.INVISIBLE
                module.isEnabled = false
                animateWidgetKeyboardSlotHeight(widgetKeyboardCollapsedDockHeight(), 300L)
                widgetKeyboardSeatView?.apply {
                    animate().cancel()
                    visibility = View.GONE
                    alpha = 0f
                }
                handle?.animate()
                    ?.alpha(1f)
                    ?.translationY(0f)
                    ?.setDuration(180L)
                    ?.setInterpolator(DecelerateInterpolator(1.9f))
                    ?.start()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    module.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                } else {
                    haptic(module)
                }
            }
            .start()
    }

    private fun showWidgetKeyboardSlider() {
        if (!widgetKeyboardSliderAvailable() || !widgetKeyboardHidden || widgetKeyboardSliderAnimating || widgetSwapState != WidgetKeyboardSwapState.SEATED) return
        val module = widgetKeyboardModule ?: return
        val handle = widgetKeyboardSliderHandleView
        widgetKeyboardSliderAnimating = true
        animateWidgetKeyboardSlotHeight(expandedRootDockHeight(), 300L)
        widgetKeyboardSeatView?.apply {
            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(0.32f).setDuration(220L).setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
        module.animate().cancel()
        handle?.animate()?.alpha(0f)?.translationY(dp(10).toFloat())?.setDuration(120L)?.start()
        module.visibility = View.VISIBLE
        module.isEnabled = false
        module.alpha = 0.18f
        module.translationY = widgetKeyboardHiddenTranslationY(module)
        module.rotationX = 0f
        module.scaleX = 1f
        module.scaleY = 1f
        if (hapticsEnabled) module.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        module.animate()
            .translationY(widgetKeyboardBaseTranslationY() - dp(8))
            .rotationX(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(360L)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.72f))
            .withEndAction {
                module.animate()
                    .translationY(widgetKeyboardBaseTranslationY())
                    .rotationX(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(130L)
                    .setInterpolator(DecelerateInterpolator(2.2f))
                    .withEndAction {
                        widgetKeyboardHidden = false
                        saveWidgetKeyboardHiddenForCurrentPosture(false)
                        widgetKeyboardSliderAnimating = false
                        module.isEnabled = true
                        resetWidgetKeyboardTouchGeometry()
                        handle?.visibility = View.GONE
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            module.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        } else {
                            haptic(module)
                        }
                    }
                    .start()
            }
            .start()
    }

    private fun resetWidgetKeyboardTouchGeometry() {
        val module = widgetKeyboardModule ?: return
        module.clearAnimation()
        module.rotation = 0f
        module.rotationX = 0f
        module.rotationY = 0f
        module.scaleX = 1f
        module.scaleY = 1f
        module.alpha = 1f
        module.translationX = 0f
        module.translationY = widgetKeyboardBaseTranslationY()
        keyViews.values.forEach { key ->
            key.animate().cancel()
            key.clearAnimation()
            key.translationX = 0f
            key.translationY = 0f
            key.rotation = 0f
            key.rotationX = 0f
            key.rotationY = 0f
            key.scaleX = 1f
            key.scaleY = 1f
            key.isPressed = false
        }
        keyBounds.clear()
        module.requestLayout()
        module.invalidate()
        module.post {
            captureKeyBounds()
            module.post { captureKeyBounds() }
        }
    }

    private fun handleWidgetKeyboardSliderHandleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                widgetKeyboardHandleDownY = event.rawY
                widgetKeyboardHandleDragging = false
                widgetKeyboardSliderHandleView?.animate()?.scaleX(1.04f)?.scaleY(1.04f)?.setDuration(90L)?.start()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - widgetKeyboardHandleDownY
                if (dy < -dp(18)) widgetKeyboardHandleDragging = true
                widgetKeyboardSliderHandleView?.translationY = dy.coerceIn(-dp(22).toFloat(), dp(10).toFloat())
                return true
            }
            MotionEvent.ACTION_UP -> {
                widgetKeyboardSliderHandleView?.animate()?.translationY(0f)?.scaleX(1f)?.scaleY(1f)?.setDuration(120L)?.start()
                val dy = event.rawY - widgetKeyboardHandleDownY
                if (dy < -dp(20) || !widgetKeyboardHandleDragging) showWidgetKeyboardSlider()
                widgetKeyboardHandleDragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                widgetKeyboardSliderHandleView?.animate()?.translationY(0f)?.scaleX(1f)?.scaleY(1f)?.setDuration(120L)?.start()
                widgetKeyboardHandleDragging = false
                return true
            }
        }
        return true
    }

    private fun handleWidgetKeyboardSliderHostTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    widgetKeyboardHostTwoFingerActive = true
                    widgetKeyboardHostTwoFingerStartX = (event.getX(0) + event.getX(1)) / 2f
                    widgetKeyboardHostTwoFingerStartY = (event.getY(0) + event.getY(1)) / 2f
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (widgetKeyboardHostTwoFingerActive && event.pointerCount >= 2) {
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    val dx = midX - widgetKeyboardHostTwoFingerStartX
                    val dy = midY - widgetKeyboardHostTwoFingerStartY
                    if (dy < -dp(42) && abs(dy) > abs(dx) * 1.25f) {
                        widgetKeyboardHostTwoFingerActive = false
                        showWidgetKeyboardSlider()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                widgetKeyboardHostTwoFingerActive = false
            }
        }
        return widgetKeyboardHidden
    }

    private fun handleVisibleWidgetKeyboardGlobalGesture(event: MotionEvent): Boolean {
        if (!widgetKeyboardSliderAvailable() ||
            widgetKeyboardHidden ||
            widgetKeyboardSliderAnimating ||
            widgetSwapState != WidgetKeyboardSwapState.SEATED
        ) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    cancelWallpaperLongPress()
                    widgetKeyboardHostTwoFingerActive = true
                    widgetKeyboardHostTwoFingerStartX = (event.getX(0) + event.getX(1)) / 2f
                    widgetKeyboardHostTwoFingerStartY = (event.getY(0) + event.getY(1)) / 2f
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (widgetKeyboardHostTwoFingerActive && event.pointerCount >= 2) {
                    cancelWallpaperLongPress()
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    val dx = midX - widgetKeyboardHostTwoFingerStartX
                    val dy = midY - widgetKeyboardHostTwoFingerStartY
                    if (dy > dp(42) && abs(dy) > abs(dx) * 1.18f) {
                        widgetKeyboardHostTwoFingerActive = false
                        hideWidgetKeyboardSlider()
                    }
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consumed = widgetKeyboardHostTwoFingerActive
                widgetKeyboardHostTwoFingerActive = false
                return consumed
            }
        }
        return false
    }

    private fun handleHiddenWidgetKeyboardGlobalGesture(event: MotionEvent): Boolean {
        if (!widgetKeyboardHidden || !widgetKeyboardSliderAvailable() || widgetKeyboardSliderAnimating) {
            widgetKeyboardHostTwoFingerActive = false
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Arm a possible tap-to-reveal if the touch starts on the handle pill.
                widgetKeyboardTapDownX = event.rawX
                widgetKeyboardTapDownY = event.rawY
                widgetKeyboardMaybeTap = isInsideKeyboardHandle(event.rawX, event.rawY)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                widgetKeyboardMaybeTap = false
                if (event.pointerCount >= 2) {
                    widgetKeyboardHostTwoFingerActive = true
                    widgetKeyboardHostTwoFingerStartX = (event.getX(0) + event.getX(1)) / 2f
                    widgetKeyboardHostTwoFingerStartY = (event.getY(0) + event.getY(1)) / 2f
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (widgetKeyboardMaybeTap &&
                    (abs(event.rawX - widgetKeyboardTapDownX) > dp(16) || abs(event.rawY - widgetKeyboardTapDownY) > dp(16))) {
                    widgetKeyboardMaybeTap = false
                }
                if (widgetKeyboardHostTwoFingerActive && event.pointerCount >= 2) {
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    val dx = midX - widgetKeyboardHostTwoFingerStartX
                    val dy = midY - widgetKeyboardHostTwoFingerStartY
                    if (dy < -dp(34) && abs(dy) > abs(dx) * 1.15f) {
                        widgetKeyboardHostTwoFingerActive = false
                        showWidgetKeyboardSlider()
                    }
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                // A clean tap on the handle brings the keyboard back up.
                if (widgetKeyboardMaybeTap && isInsideKeyboardHandle(event.rawX, event.rawY)) {
                    widgetKeyboardMaybeTap = false
                    widgetKeyboardHostTwoFingerActive = false
                    showWidgetKeyboardSlider()
                    return true
                }
                widgetKeyboardMaybeTap = false
                val consumed = widgetKeyboardHostTwoFingerActive
                widgetKeyboardHostTwoFingerActive = false
                return consumed
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                widgetKeyboardMaybeTap = false
                val consumed = widgetKeyboardHostTwoFingerActive
                widgetKeyboardHostTwoFingerActive = false
                return consumed
            }
        }
        return false
    }

    /** Screen-space hit-test for the keyboard handle pill, with a generous touch pad. */
    private fun isInsideKeyboardHandle(rawX: Float, rawY: Float): Boolean {
        val handle = widgetKeyboardSliderHandleView ?: return false
        if (handle.visibility != View.VISIBLE || handle.width == 0) return false
        val loc = IntArray(2)
        handle.getLocationOnScreen(loc)
        val pad = dp(14)
        return rawX >= loc[0] - pad && rawX <= loc[0] + handle.width + pad &&
            rawY >= loc[1] - pad && rawY <= loc[1] + handle.height + pad
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
                alertItems = informationalAlerts(),
                onAlertClick = { item ->
                    haptic(this@setNowPlayingCardContent)
                    openAlertItem(item)
                },
                onAlertLongClick = { item ->
                    haptic(this@setNowPlayingCardContent)
                    dismissAlertItem(item)
                },
                widgetModes = widgetStackModes(),
                onStackEdit = {
                    haptic(this@setNowPlayingCardContent)
                    showWidgetStackEditor()
                }
            )
        }
    }

    // ── Today brief ──────────────────────────────────────────────────────────
    // Pane hosting, teaser content, and brief action firing moved to TodayPaneHost.

    // Informational (non-actionable) notifications routed to the widget stack. Excludes anything the
    // dedicated news/maps widgets already show, and anything the brief classifies as actionable.
    private fun informationalAlerts(): List<ContextWidgetItem> {
        val shownKeys = (contextItems(HUB_KIND_NEWS) + contextItems(HUB_KIND_MAPS)).map { it.key }.toSet()
        return TeclasNotificationListener.briefSnapshot()
            .filterNot { BriefClassifier.isActionable(it) }
            .filterNot { it.key in shownKeys }
            .take(6)
            .map { r ->
                ContextWidgetItem(
                    key = r.key,
                    title = r.appLabel,
                    preview = r.title.ifBlank { r.text },
                    packageName = r.packageName,
                    color = Neu.PURPLE,
                    avatar = r.avatar ?: appIconBitmap(r.packageName),
                    lastUpdated = r.whenMs
                )
            }
    }

    private fun appIconBitmap(pkg: String): Bitmap? = runCatching {
        val d = packageManager.getApplicationIcon(pkg)
        val w = d.intrinsicWidth.coerceIn(1, 144)
        val h = d.intrinsicHeight.coerceIn(1, 144)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        b
    }.getOrNull()

    private fun openAlertItem(item: ContextWidgetItem) {
        val ci = TeclasNotificationListener.briefRecord(item.key)?.contentIntent
        if (ci != null) {
            val sent = runCatching { ci.send() }.isSuccess
            if (sent) return
        }
        packageManager.getLaunchIntentForPackage(item.packageName)?.let { runCatching { startActivity(it) } }
    }

    private fun dismissAlertItem(item: ContextWidgetItem) {
        TeclasNotificationListener.dismiss(item.key)
        refreshNowPlayingCard()
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

    private fun homeWidgetStackVisible() =
        openPane == null && !(keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET && !isUnfoldedInnerLayoutActive())

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
        if (innerWallpaperEditMode) return false
        // Board / left page open (and not being dragged): let its own container handle scroll/tap/close.
        if (spaceBoardOverlay != null && !spaceBoardDragActive) return false
        if (homeLeftOverlay != null && !homeLeftDragActive) return false
        if (widgetKeyboardSwapActive()) {
            librarySwipeTriggered = false
            librarySwipeBlockedByWidget = false
            return false
        }
        if (!::contentFrame.isInitialized) return false
        // While Today is open, don't let home swipes (library/widgets) fire underneath it. Sniff for
        // a decisive swipe-left to close; return false so taps/scroll still reach the Today page.
        if (todayOpen) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    librarySwipeStartX = event.rawX
                    librarySwipeStartY = event.rawY
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - librarySwipeStartX
                    val dy = event.rawY - librarySwipeStartY
                    if (dx < -dp(40) && abs(dx) > abs(dy) * 1.2f) todayPaneHost.closeToday()
                }
            }
            return false
        }
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
        val sideLibraryEnabled = isUnfoldedInnerLayoutActive() || !upOpensLibrary
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
                spaceBoardDragActive = false
                homeLeftDragActive = false
                libraryDragVertical = false
                libraryDragHapticStage = 0
                librarySwipeBlockedByWidget = isInsideHomeWidget(event.rawX, event.rawY)
                librarySwipeFromKeyboard = isInsideKeyboard(event.rawX, event.rawY)
                // The old right-edge fast-path began an interactive *library* drag on touch-down.
                // The drawer surface is now the Space board (an instant open), so we let the edge
                // swipe fall through to the MOVE handler, which opens the board once the swipe is
                // intentional. Only reserve the disallow-intercept so the horizontal swipe wins.
                if (rightEdgeStart && !isUnfoldedInnerLayoutActive() && !librarySwipeFromKeyboard && !libraryOpen && openPane == null) {
                    librarySwipeBlockedByWidget = false
                    contentFrame.parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!inContent) return false
                val dx = event.rawX - librarySwipeStartX
                val dy = event.rawY - librarySwipeStartY
                if (abs(dx) > dp(8) || abs(dy) > dp(8)) cancelWallpaperLongPress()
                libraryDragTouchX = event.rawX
                if (librarySwipeBlockedByWidget) {
                    val wantsSideLibraryDrag = abs(dx) > dp(18) && abs(dx) > abs(dy) * 1.2f &&
                        (((sideLibraryEnabled && dx < 0 && !libraryOpen && openPane == null)) || (dx > 0 && libraryOpen))
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
                if (spaceBoardDragActive) {
                    updateSpaceBoardDrag(dx)
                    return true
                }
                if (homeLeftDragActive) {
                    updateHomeLeftDrag(dx)
                    return true
                }
                if (upOpensLibrary && !librarySwipeTriggered && abs(dy) > dp(18) && abs(dy) > abs(dx) * 1.2f) {
                    when {
                        dy < 0 && !libraryOpen && openPane == null -> {
                            // Up-swipe opens the active Space's board (the drawer surface),
                            // same as swipe-left. Search stays the way to reach anything else.
                            librarySwipeTriggered = true
                            openSpaceBoard()
                            return true
                        }
                        dy > 0 && libraryOpen -> {
                            librarySwipeTriggered = true
                            if (isUnfoldedInnerLayoutActive()) {
                                closeLibrary()
                                return true
                            }
                            beginLibraryDrag(startedOpen = true, vertical = true)
                            updateLibraryDrag(dy)
                            return true
                        }
                    }
                }
                // Start horizontal library motion as soon as the swipe is intentional, not on finger-up.
                if (!librarySwipeTriggered && abs(dx) > dp(18) && abs(dx) > abs(dy) * 1.2f) {
                    when {
                        sideLibraryEnabled && dx < 0 && !libraryOpen && openPane == null -> {
                            // Swipe left during a Space drags that Space's board in from the
                            // right, following the finger like the old app library did.
                            librarySwipeTriggered = true
                            if (beginSpaceBoardDrag()) updateSpaceBoardDrag(dx)
                            return true
                        }
                        dx > 0 && libraryOpen -> {
                            librarySwipeTriggered = true
                            if (isUnfoldedInnerLayoutActive()) {
                                closeLibrary()
                                return true
                            }
                            beginLibraryDrag(startedOpen = true)
                            updateLibraryDrag(dx)
                            return true
                        }
                        dx > 0 && !libraryOpen && openPane == null && spaceBoardOverlay == null -> {
                            // Swipe right drags in the personal widget page from the left,
                            // blurring the homescreen behind it (Apple Today-View style).
                            librarySwipeTriggered = true
                            if (beginHomeLeftDrag()) updateHomeLeftDrag(dx)
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
                if (spaceBoardDragActive) {
                    settleSpaceBoardDrag(event.rawX - librarySwipeStartX)
                    librarySwipeTriggered = false
                    return true
                }
                if (homeLeftDragActive) {
                    settleHomeLeftDrag(event.rawX - librarySwipeStartX)
                    librarySwipeTriggered = false
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
                if (abs(dx) > dp(24) && abs(dx) > abs(dy) * 1.2f) {
                    when {
                        sideLibraryEnabled && dx < 0 && !libraryOpen && openPane == null -> {
                            librarySwipeTriggered = true
                            openSpaceBoard()
                            return true
                        }
                        dx > 0 && libraryOpen -> {
                            librarySwipeTriggered = true
                            closeLibrary()
                            return true
                        }
                        dx > 0 && openPane == null && spaceBoardOverlay == null -> {
                            // Swipe right opens the personal left widget page.
                            librarySwipeTriggered = true
                            openHomeLeftPage()
                            return true
                        }
                    }
                } else if (!libraryOpen && openPane == null && abs(dy) > dp(42) && abs(dy) > abs(dx) * 1.2f) {
                    librarySwipeTriggered = true
                    if (dy < 0 && upOpensLibrary) {
                        openSpaceBoard()
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
                if (spaceBoardDragActive) settleSpaceBoardDrag(event.rawX - librarySwipeStartX)
                if (homeLeftDragActive) settleHomeLeftDrag(event.rawX - librarySwipeStartX)
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
        if (innerWallpaperEditMode) return false
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
                if (paneKind == PaneKind.MUSIC && dx > dp(60) && dx > abs(dy) * 1.4f && musicPaneHost.spotifyCompactOverlay == null && activeMusicMode() != MusicMode.SIMPLE) {
                    paneSwipeTriggered = true
                    haptic(contentFrame)
                    musicPaneHost.showCompactSpotifyLibrary()
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
        if (innerWallpaperEditMode) return false
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
                if (!stripSwipeTriggered && (abs(dx) > dp(14) || abs(dy) > dp(14))) {
                    stripSwipeTriggered = true
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!stripSwipeTriggered) {
                    placeCursorFromTypingStrip(event.rawX)
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

    private fun placeCursorFromTypingStrip(rawX: Float) {
        val text = hostText()
        if (text.isEmpty()) {
            cursorPos = null
            haptic(searchHintView)
            return
        }
        val loc = IntArray(2)
        searchHintView.getLocationOnScreen(loc)
        val contentLeft = loc[0] + searchHintView.totalPaddingLeft
        val contentRight = loc[0] + searchHintView.width - searchHintView.totalPaddingRight
        val width = (contentRight - contentLeft).coerceAtLeast(1)
        val x = (rawX - contentLeft).coerceIn(0f, width.toFloat())
        val ratio = (x / width.toFloat()).coerceIn(0f, 1f)
        val pos = (ratio * text.length).toInt().coerceIn(0, text.length)
        cursorPos = if (pos == text.length) null else pos
        keyHaptic("space")
        renderRibbon()
    }

    private fun openLibrary() {
        if (libraryOpen || openPane != null) return
        libraryOpen = true
        query = ""
        keyboardSettingsOpen = false
        if (isUnfoldedInnerLayoutActive()) {
            todayOpen = false
            refreshUnfoldedFocusContent()
            renderRibbon()
            syncNowPlayingCardVisibility()
            refreshNowPlayingCard()
            return
        }
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
        if (isUnfoldedInnerLayoutActive()) {
            refreshUnfoldedFocusContent()
            return
        }
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
        val startY = resources.displayMetrics.heightPixels.toFloat()
        val board = widgetBoard().apply {
            alpha = 1f
            translationY = startY
            (this as? WidgetBoardFrame)?.setGlassProgress(0f)
        }
        widgetBoardView = board
        addContentView(
            board,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360L
            interpolator = android.view.animation.OvershootInterpolator(0.65f)
            addUpdateListener { animator ->
                val progress = (animator.animatedValue as Float).coerceIn(0f, 1f)
                (board as? WidgetBoardFrame)?.setGlassProgress(progress)
                board.alpha = 1f
                board.translationY = startY * (1f - progress)
            }
            start()
        }
    }

    private fun closeWidgetBoard() {
        closeWidgetPicker()
        val closing = widgetBoardView ?: return
        widgetBoardView = null
        val startY = closing.translationY.coerceAtLeast(0f)
        val endY = (closing.height.takeIf { it > 0 } ?: contentFrame.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels).toFloat()
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 230L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val progress = (animator.animatedValue as Float).coerceIn(0f, 1f)
                (closing as? WidgetBoardFrame)?.setGlassProgress(progress)
                val closeProgress = 1f - progress
                closing.alpha = progress
                closing.translationY = startY + (endY - startY) * closeProgress
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    (closing.parent as? ViewGroup)?.removeView(closing)
                    setLauncherBlurred(false)
                }
            })
            start()
        }
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
            setGlassProgress(1f)
            installGlassPlate()
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
        private val glassPlate = DynamicGlassPlate(context, radiusDp = 0, strength = 1.75f, edgeInsetDp = 0)
        private var downX = 0f
        private var downY = 0f
        private var dragStartY = 0f
        private var dragging = false
        private var velocityTracker: VelocityTracker? = null
        private var closing = false

        init {
            setWillNotDraw(false)
            clipToOutline = false
            clipChildren = false
            clipToPadding = false
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        fun installGlassPlate() {
            if (glassPlate.parent == null) {
                addView(glassPlate, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        }

        fun setGlassProgress(value: Float) {
            glassPlate.setGlassProgress(value)
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            velocityTracker?.addMovement(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    dragStartY = translationY
                    dragging = false
                    closing = false
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (widgetPickerView != null && !closing && dy > dp(84) && dy > abs(dx) * 1.2f) {
                        closing = true
                        haptic(this)
                        closeWidgetPicker()
                        return true
                    }
                    if (widgetPickerView == null && !closing) {
                        if (!dragging && dy > dp(14) && dy > abs(dx) * 1.18f) {
                            dragging = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                            haptic(this)
                        }
                        if (dragging) {
                            val maxY = (height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels).toFloat()
                            val offset = (dragStartY + dy).coerceIn(0f, maxY)
                            val progress = (1f - offset / maxY).coerceIn(0f, 1f)
                            translationY = offset
                            alpha = (0.74f + progress * 0.26f).coerceIn(0.74f, 1f)
                            setGlassProgress(progress)
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        velocityTracker?.computeCurrentVelocity(1000)
                        val velocityY = velocityTracker?.yVelocity ?: 0f
                        velocityTracker?.recycle()
                        velocityTracker = null
                        dragging = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                        val shouldClose = event.actionMasked == MotionEvent.ACTION_CANCEL ||
                            translationY > height * 0.22f ||
                            velocityY > 1300f
                        if (shouldClose) {
                            haptic(this)
                            closeWidgetBoard()
                        } else {
                            animateWidgetBoardToSeat(this)
                        }
                        return true
                    }
                    velocityTracker?.recycle()
                    velocityTracker = null
                }
            }
            return super.dispatchTouchEvent(event)
        }
    }

    private fun animateWidgetBoardToSeat(board: WidgetBoardFrame) {
        val startY = board.translationY.coerceAtLeast(0f)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = android.view.animation.OvershootInterpolator(0.55f)
            addUpdateListener { animator ->
                val progress = (animator.animatedValue as Float).coerceIn(0f, 1f)
                board.translationY = startY * (1f - progress)
                board.alpha = (0.74f + progress * 0.26f).coerceIn(0.74f, 1f)
                board.setGlassProgress(progress)
            }
            start()
        }
    }

    private inner class WidgetCellCanvas(context: Context) : FrameLayout(context) {
        private val snapAnchor = GridSnappingAnchorView(context)

        init {
            setWillNotDraw(false)
            background = null
            addView(snapAnchor, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
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
            background = widgetEmptyBackground(activeNeuTokens)
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

    private fun applyHighContrastTextProtection(widgetContainer: ViewGroup, textViews: List<TextView>) {
        val blurSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).isCrossWindowBlurEnabled
            }.getOrDefault(true)
        } else {
            true
        }
        if (!blurSupported) {
            textViews.forEach { text ->
                text.setTextColor(if (activeNeuTokens.mode == NeuMode.LIGHT) 0xFF20242B.toInt() else Color.WHITE)
                text.setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), if (activeNeuTokens.mode == NeuMode.LIGHT) 0x55FFFFFF else 0x99000000.toInt())
            }
            return
        }
        textViews.forEach { text ->
            text.setShadowLayer(dp(6).toFloat(), 0f, dp(2).toFloat(), 0x80000000.toInt())
        }
    }

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
            setPadding(0, 0, 0, 0)
            background = null
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
            val radius = dp(20).toFloat()
            handlePaint.style = Paint.Style.FILL
            handlePaint.shader = android.graphics.LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                intArrayOf(
                    adjustAlpha(Color.WHITE, if (activeNeuTokens.mode == NeuMode.LIGHT) 0.12f else 0.08f),
                    adjustAlpha(activeNeuTokens.baseLo, if (activeNeuTokens.mode == NeuMode.LIGHT) 0.08f else 0.22f)
                ),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(dp(3).toFloat(), dp(3).toFloat(), width - dp(3).toFloat(), height - dp(3).toFloat(), radius, radius, handlePaint)
            handlePaint.shader = null
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
                background = widgetPickerSheetBackground(activeNeuTokens)
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
                background = dockIconButtonBackground(activeNeuTokens)
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
            background = widgetProviderRowBackground(activeNeuTokens)
            isClickable = true
            addView(FrameLayout(context).apply {
                background = dockIconButtonBackground(activeNeuTokens)
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
            android.util.Log.d("TeclasWidgetBoard", "API36 widget metric hook: $actionType ${spec.id} ${spec.spanX}x${spec.spanY}")
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
            action == GESTURE_LIBRARY -> openSpaceBoard()
            action == GESTURE_WIDGETS -> openWidgetBoard()
            action == GESTURE_NOTIFICATIONS -> expandNotificationShade()
            action == GESTURE_MUSIC -> openHere(musicTarget())
            action == GESTURE_SETTINGS -> openHere(teclasSettingsTarget())
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
            animateLibraryOpenSeamlessly(overlay)
        }
        scheduleLibraryPopulate(if (animate) 48L else 0L)
    }

    private fun animateLibraryOpenSeamlessly(overlay: View) {
        val axis = (contentFrame.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500L
            interpolator = android.view.animation.OvershootInterpolator(0.8f)
            addUpdateListener { animator ->
                val progress = (animator.animatedValue as Float).coerceIn(0f, 1f)
                overlay.translationX = axis * (1f - progress)
                overlay.alpha = (0.90f + progress * 0.10f).coerceIn(0.90f, 1f)
                updateLibraryGridDragEffects(progress, contentFrame.width * 0.5f)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    overlay.translationX = 0f
                    overlay.alpha = 1f
                    overlay.setLayerType(View.LAYER_TYPE_NONE, null)
                    updateLibraryGridDragEffects(1f, contentFrame.width * 0.5f)
                }
            })
            start()
        }
    }

    private fun appLibrary(): View {
        val glass = appLibraryGlassEnabled()
        val nativeGlass = glass && nativeGlassSurfaceActive()
        val cached = libraryView as? FrameLayout
        if (
            !libraryViewDirty &&
            cached != null &&
            libraryViewMode == activeNeuTokens.mode &&
            libraryViewGlass == glass &&
            (cached is NativeFoldGlassPanel) == nativeGlass &&
            (cached is LibraryDrawerView) == (glass && !nativeGlass)
        ) {
            libraryContentArea = cached.findViewWithTag("library_content") as? FrameLayout
            (cached as? LibraryDrawerView)?.setAmbient(activeNeuTokens.baseHi, 0f, activeNeuTokens.mode)
            if (!glass) cached.setBackgroundColor(activeNeuTokens.base)
            return cached
        }
        val shell = when {
            nativeGlass -> NativeFoldGlassPanel(this, radiusDp = 28)
            glass -> LibraryDrawerView(this)
            else -> FrameLayout(this).apply { setBackgroundColor(activeNeuTokens.base) }
        }
        return shell.apply {
            setPadding(dp(14), dp(14), dp(14), dp(10))
            (this as? LibraryDrawerView)?.setAmbient(activeNeuTokens.baseHi, 0f, activeNeuTokens.mode)
            val contentArea = FrameLayout(context).apply { tag = "library_content" }
            libraryContentArea = contentArea
            showLibraryLoading(contentArea)
            addView(contentArea, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(if (glass) 60 else 38)
            })
            val headerParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(if (glass) 44 else 46), Gravity.TOP)
            if (glass) {
                headerParams.topMargin = dp(8)
                headerParams.leftMargin = dp(4)
                headerParams.rightMargin = dp(4)
            }
            addView(libraryHeader(), headerParams)
            libraryViewMode = activeNeuTokens.mode
            libraryViewGlass = glass
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
        val gridChild = if (query.isNotBlank()) {
            if (appLibraryGlassEnabled()) glassSearchBackground(searchResultsGrid()) else searchResultsGrid()
        }
                    else if (libraryGridMode) libraryGrid() else bentoGrid()
        // When the App Library IS the home surface, it has no favorites dock of its own, so mount
        // one (plus an "often used" row) above the grid. When it's just an overlay opened from the
        // real homescreen, that homescreen already shows the dock — so we skip it here.
        val homeStrip = if (query.isBlank()) libraryHomeStrip() else null
        val child = if (homeStrip == null) gridChild else LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(homeStrip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
            addView(gridChild, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        area.addView(child, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        libraryContentReady = query.isBlank()
    }

    /**
     * Favorites dock + "often used" row shown at the top of the App Library, but ONLY when the
     * library is the default home surface. Returns null otherwise (the real homescreen owns the
     * dock), or when there's nothing to show.
     */
    private fun libraryHomeStrip(): View? {
        if (!appLibraryDefaultHome()) return null
        val hidden = hiddenHomePackages()
        val favPkgs = favoritePackages()
        val favApps = apps.filter { it.packageName in favPkgs && it.packageName !in hidden }.take(DOCK_APP_LIMIT)
        val usage = appUsageCounts()
        val oftenApps = apps
            .filter { it.packageName !in favPkgs && it.packageName !in hidden && (usage[it.packageName] ?: 0) > 0 }
            .sortedWith { left, right ->
                val byUsage = (usage[right.packageName] ?: 0).compareTo(usage[left.packageName] ?: 0)
                if (byUsage != 0) byUsage else collator.compare(left.label, right.label)
            }
            .take(OFTEN_USED_LIMIT)

        // Fave dock = Music + favorites only (no often-used filler — those live in the row below).
        val music = HomeDockItem(null, musicTarget(), "Music", 0xFF57C98A.toInt())
            .takeUnless { it.target.id in hidden }
        val favItems = (listOfNotNull(music) + favApps.map { HomeDockItem(it, it.toPaneTarget(), it.shortName, it.brandColor) })
            .take(DOCK_APP_LIMIT)
        if (favItems.isEmpty() && oftenApps.isEmpty()) return null

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = recessedDockBackground()
                favItems.forEachIndexed { index, item ->
                    addView(dockItemButton(item, index), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        if (index > 0) marginStart = dp(6)
                    })
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)))

            if (oftenApps.isNotEmpty()) {
                addView(mono("OFTEN USED", 8.5f, InkDim).apply {
                    letterSpacing = 0.12f
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(12)
                    bottomMargin = dp(4)
                    marginStart = dp(4)
                })
                addView(HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    clipChildren = false
                    clipToPadding = false
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        clipChildren = false
                        clipToPadding = false
                        oftenApps.forEachIndexed { index, app ->
                            val item = HomeDockItem(app, app.toPaneTarget(), app.shortName, app.brandColor)
                            addView(dockItemButton(item, index), LinearLayout.LayoutParams(dp(66), dp(64)).apply {
                                if (index > 0) marginStart = dp(4)
                            })
                        }
                    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)))
            }
        }
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
        (libraryGridBlurView as? DynamicGlassPlate)?.setGlassProgress(p)
            ?: run { libraryGridBlurView?.alpha = (0.72f * p).coerceIn(0f, 0.72f) }
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
        val glass = appLibraryGlassEnabled()
        setPadding(if (glass) dp(16) else dp(12), 0, if (glass) dp(12) else dp(10), 0)
        background = libraryHeaderBackground()
        elevation = dp(if (glass) 3 else 10).toFloat()
        addView(TextView(context).apply {
            text = "App Library"; textSize = if (glass) 16.5f else 17f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setTextColor(activeNeuTokens.ink); includeFontPadding = false
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(spaceIcon(), LinearLayout.LayoutParams(dp(if (glass) 27 else 30), dp(if (glass) 27 else 30)).apply {
            marginEnd = dp(8)
        })
        addView(TextView(context).apply {
            text = if (libraryGridMode) "Categories" else "Grid"
            gravity = Gravity.CENTER
            textSize = if (glass) 10.5f else 11f
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
                if (isUnfoldedInnerLayoutActive()) refreshUnfoldedLibraryContent() else showLibrary(animate = false)
            }
        }, LinearLayout.LayoutParams(dp(if (glass) 92 else 94), dp(if (glass) 27 else 30)).apply {
            marginEnd = dp(if (glass) 2 else 8)
        })
    }

    /**
     * The drawer's one new element: a domed neumorphic badge showing the active Space.
     * Tap to switch or lock the Space (Auto returns to detection); the drawer and dock
     * rearrange to match. Drawn with the same Neu language as the rest of the shell.
     */
    private fun spaceIcon(): View = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 13f
        includeFontPadding = false
        background = Neu.drawable(activeNeuTokens, dp(15).toFloat(), NeuLevel.RAISED_SM)
        isClickable = true
        spaceIconView = this
        updateSpaceIcon()
        setOnClickListener { view ->
            showSpaceSwitcher(view) {
                updateSpaceIcon()
                libraryViewDirty = true
                libraryContentReady = false
                if (isUnfoldedInnerLayoutActive()) refreshUnfoldedLibraryContent() else showLibrary(animate = false)
                renderFavoritesDock()
            }
        }
        // Long-press opens this Space's unified board (its apps + widgets in one canvas).
        setOnLongClickListener { view -> haptic(view); openSpaceBoard(); true }
    }

    /**
     * Shows the Space chooser anchored to [anchor]: "Auto" (follow detection) plus every Space,
     * with the current lock checked. Picking one locks the launcher to that Space (or clears the
     * lock for Auto), then runs [onChanged] so the caller can refresh its surface. This is how
     * you correct a bad detection — e.g. it says Home but you're travelling.
     */
    private fun showSpaceSwitcher(anchor: View, onChanged: () -> Unit) {
        haptic(anchor)
        val spaces = SpaceManager.spaces(this).filter { it.enabled }
        val lockedId = SpaceManager.lockedSpaceId(this)
        val detected = predictContext?.let { SpaceManager.detect(this, it).space }
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, -1, 0, "Auto" + (detected?.let { "  ·  ${it.emoji} ${it.name}" } ?: ""))
            .isCheckable = true
        popup.menu.getItem(0).isChecked = lockedId == null
        spaces.forEachIndexed { i, space ->
            popup.menu.add(0, i, i + 1, "${space.emoji}  ${space.name}").isCheckable = true
            popup.menu.getItem(i + 1).isChecked = lockedId == space.id
        }
        popup.setOnMenuItemClickListener { item ->
            SpaceManager.lock(this, if (item.itemId < 0) null else spaces[item.itemId].id)
            refreshPredictContext(rerender = true)
            onChanged()
            true
        }
        popup.show()
    }

    // ---- per-Space unified board (apps + widgets in one canvas) --------------------------

    private inner class SpaceBoardCallbacks : com.fran.teclas.grid.SpaceBoardController.Callbacks {
        override fun launchAppFromBoard(packageName: String, className: String?, label: String?) {
            val entry = apps.firstOrNull { it.packageName == packageName }
            pendingLaunchSource = LaunchSource.DRAWER
            if (entry != null) openExternal(entry.toPaneTarget())
            else runCatching {
                packageManager.getLaunchIntentForPackage(packageName)?.let { launchExternalIntent(it, packageName) }
            }
        }
        override fun loadIcon(packageName: String?, className: String?): Drawable? =
            com.fran.teclas.grid.GridIcons.resolve(this@MainActivity, packageName, className)
        override fun createWidgetHostView(widgetId: Int): View? {
            val info = appWidgetManager.getAppWidgetInfo(widgetId) ?: return null
            return runCatching {
                appWidgetHost.createView(this@MainActivity, widgetId, info).apply { setAppWidget(widgetId, info) }
            }.getOrNull()
        }
        override fun allocateWidgetId(): Int = appWidgetHost.allocateAppWidgetId()
        override fun bindWidgetIfAllowed(widgetId: Int, provider: AppWidgetProviderInfo): Boolean =
            runCatching { appWidgetManager.bindAppWidgetIdIfAllowed(widgetId, provider.provider) }.getOrDefault(false)
        override fun deleteWidgetId(widgetId: Int) { runCatching { appWidgetHost.deleteAppWidgetId(widgetId) } }
        override fun updateWidgetSize(widgetId: Int, widthDp: Int, heightDp: Int) {
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
            }
            runCatching { appWidgetManager.updateAppWidgetOptions(widgetId, options) }
        }
        @Suppress("DEPRECATION")
        override fun startWidgetResultIntent(intent: Intent, requestCode: Int) {
            runCatching { startActivityForResult(intent, requestCode) }
        }
        override fun showAddChooser(onAddApp: () -> Unit, onAddWidget: () -> Unit) {
            AlertDialog.Builder(this@MainActivity)
                .setItems(arrayOf("Add app", "Add widget")) { _, which -> if (which == 0) onAddApp() else onAddWidget() }
                .show()
        }
        override fun showWidgetPicker(onPick: (AppWidgetProviderInfo) -> Unit) {
            val providers = appWidgetManager.installedProviders
                .sortedBy { it.loadLabel(packageManager).lowercase(Locale.US) }
            val labels = providers.map { it.loadLabel(packageManager).toString() }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Add widget")
                .setItems(labels) { _, which -> onPick(providers[which]) }
                .show()
        }
        override fun showAppPicker(onPick: (String, String?, String?) -> Unit) {
            val choices = apps.filter { it.packageName != packageName }
            val labels = choices.map { it.label }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Add app")
                .setItems(labels) { _, which ->
                    val a = choices[which]; onPick(a.packageName, a.componentName.className, a.label)
                }
                .show()
        }
    }

    /**
     * The top pinned-apps strip for a fresh board — the apps the user pinned to this Space
     * (in Spaces settings), not a guess. Empty until they pin some; the board hint tells
     * them how. Everything below is theirs to fill with widgets.
     */
    private fun spaceBoardSeedApps(space: Space): List<com.fran.teclas.grid.SpaceBoardSeed.SeedApp> {
        val byPkg = apps.associateBy { it.packageName }
        return space.pinned.mapNotNull { byPkg[it] }.take(com.fran.teclas.grid.GRID_COLS).map {
            com.fran.teclas.grid.SpaceBoardSeed.SeedApp(it.packageName, it.componentName.className, it.shortName)
        }
    }

    /** Build the board overlay, mount it off-screen to the right, and return it (or null). */
    private fun mountSpaceBoard(): View? {
        if (spaceBoardOverlay != null || !::spaceBoardController.isInitialized) return null
        if (libraryOpen) closeLibrary()
        if (openPane != null) closePane()
        val space = activeSpaceForUi() ?: return null
        spaceBoardController.view.setLightMode(glassLightMode())
        spaceBoardController.open(space.id, spaceBoardSeedApps(space))
        val container = object : FrameLayout(this) {
            private var downX = 0f; private var downY = 0f; private var swiping = false
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) { downX = ev.x; downY = ev.y; swiping = false }
                return false
            }
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
                    MotionEvent.ACTION_UP -> {
                        val dx = ev.x - downX; val dy = ev.y - downY
                        // Swipe right (back the way it came) or a firm swipe down closes the board —
                        // but never while a widget is being dragged/resized, or the resize gesture
                        // gets stolen and the board closes instead.
                        if (!spaceBoardController.isEditing() &&
                            ((dx > dp(70) && dx > abs(dy) * 1.3f) || (dy > dp(110) && dy > abs(dx) * 1.3f))) {
                            closeSpaceBoard(); return true
                        }
                    }
                }
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setPadding(0, 0, 0, 0)
        }
        container.addView(
            if (nativeGlassSurfaceActive()) {
                NativeFoldGlassPanel(this, radiusDp = 0)
            } else {
                DynamicGlassPlate(this, radiusDp = 0, strength = 1.72f, edgeInsetDp = 0).apply {
                    setGlassProgress(1f)
                }
            },
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), systemStatusBarHeight() + dp(12), dp(12), dp(14))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // Tappable Space label = the switcher. Tap to jump to another Space (e.g. Travel
                // when detection wrongly says Home); the board reloads for it. "▾" hints it's a menu.
                addView(mono("${space.emoji}  ${space.name.uppercase(Locale.US)}  ▾", 11f, Accent).apply {
                    letterSpacing = 0.2f
                    isClickable = true
                    setPadding(dp(2), dp(6), dp(10), dp(6))
                    spaceBoardTitleView = this
                    setOnClickListener { anchor -> showSpaceSwitcher(anchor) { reloadSpaceBoardForActiveSpace() } }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(mono("DONE", 11f, InkDim).apply {
                    setPadding(dp(10), dp(6), dp(6), dp(6)); isClickable = true
                    setOnClickListener { closeSpaceBoard() }
                }, LinearLayout.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(30)))
            addView(mono("Pin the apps you want up top · long-press an empty cell to add apps & widgets", 9f, InkDim).apply {
                setPadding(dp(2), 0, 0, dp(8)); letterSpacing = 0.06f
            }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            // The board view is a single reused instance; a prior container (possibly still
            // animating out) may still hold it, so detach before re-parenting or addView throws
            // "child already has a parent" and takes the launcher down.
            (spaceBoardController.view.parent as? ViewGroup)?.removeView(spaceBoardController.view)
            addView(spaceBoardController.view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        spaceBoardOverlay = container
        addContentView(container, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        container.translationX = resources.displayMetrics.widthPixels.toFloat()
        return container
    }

    /** Reload the open board's apps/widgets + title for the (newly picked) active Space. */
    private fun reloadSpaceBoardForActiveSpace() {
        if (spaceBoardOverlay == null || !::spaceBoardController.isInitialized) return
        val space = activeSpaceForUi() ?: return
        spaceBoardController.open(space.id, spaceBoardSeedApps(space))
        spaceBoardTitleView?.text = "${space.emoji}  ${space.name.uppercase(Locale.US)}  ▾"
    }

    /** Instant open with a slide-in animation (up-swipe / gesture-action paths). */
    private fun openSpaceBoard() {
        val container = mountSpaceBoard() ?: return
        container.animate().translationX(0f).setDuration(260L)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    // --- interactive drag: the board follows the finger in from the right, like the old library ---

    private fun beginSpaceBoardDrag(): Boolean {
        // No hardware layer: the board's glass panel blur must keep updating live as it slides in.
        if (mountSpaceBoard() == null) return false
        spaceBoardDragActive = true
        keyHaptic("space")
        return true
    }

    /** [delta] is the horizontal finger travel (negative as it moves left). */
    private fun updateSpaceBoardDrag(delta: Float) {
        val overlay = spaceBoardOverlay ?: return
        val width = resources.displayMetrics.widthPixels.toFloat()
        overlay.translationX = (width + delta).coerceIn(0f, width)
    }

    private fun settleSpaceBoardDrag(delta: Float) {
        val overlay = spaceBoardOverlay ?: run { spaceBoardDragActive = false; return }
        spaceBoardDragActive = false
        val width = resources.displayMetrics.widthPixels.toFloat()
        val translation = overlay.translationX.coerceIn(0f, width)
        val openProgress = 1f - translation / width
        // Open if dragged in past ~22% or flicked left hard; otherwise slide back out and remove.
        val shouldOpen = openProgress > 0.22f || delta < -dp(80)
        overlay.animate().cancel()
        if (shouldOpen) {
            overlay.animate().translationX(0f).setDuration(200L)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            spaceBoardOverlay = null
            overlay.animate().translationX(width).setDuration(180L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay) }.start()
        }
    }

    private fun closeSpaceBoard(): Boolean {
        val overlay = spaceBoardOverlay ?: return false
        spaceBoardOverlay = null
        spaceBoardDragActive = false
        overlay.animate().translationX(resources.displayMetrics.widthPixels.toFloat())
            .setDuration(200L).setInterpolator(DecelerateInterpolator())
            .withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay) }.start()
        return true
    }

    // ---- Apple-style left widget page ----------------------------------------------------

    /**
     * Build the personal widget page and mount it off-screen to the LEFT. It's a normal
     * unified board (a fixed "home_left" layout, not per-Space) on the universal glass, so
     * the homescreen behind it blurs through as it slides in.
     */
    private fun mountHomeLeftPage(): View? {
        if (homeLeftOverlay != null || !::homeLeftController.isInitialized) return null
        if (libraryOpen) closeLibrary()
        if (openPane != null) closePane()
        homeLeftController.open(HOME_LEFT_BOARD_ID, emptyList())
        val container = object : FrameLayout(this) {
            private var downX = 0f; private var downY = 0f
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
                    MotionEvent.ACTION_UP -> {
                        val dx = ev.x - downX; val dy = ev.y - downY
                        // Swipe left (back the way it came) closes — never while resizing a widget.
                        if (!homeLeftController.isEditing() && dx < -dp(70) && abs(dx) > abs(dy) * 1.3f) {
                            closeHomeLeftPage(); return true
                        }
                    }
                }
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
        }
        container.addView(
            if (nativeGlassSurfaceActive()) NativeFoldGlassPanel(this, radiusDp = 0)
            else DynamicGlassPlate(this, radiusDp = 0, strength = 1.72f, edgeInsetDp = 0).apply { setGlassProgress(1f) },
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        )
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), systemStatusBarHeight() + dp(12), dp(12), dp(14))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(mono("WIDGETS", 11f, Accent).apply { letterSpacing = 0.2f },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(mono("DONE", 11f, InkDim).apply {
                    setPadding(dp(10), dp(6), dp(6), dp(6)); isClickable = true
                    setOnClickListener { closeHomeLeftPage() }
                }, LinearLayout.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(30)))
            addView(mono("Your personal widgets · long-press an empty cell to add", 9f, InkDim).apply {
                setPadding(dp(2), 0, 0, dp(8)); letterSpacing = 0.06f
            }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            (homeLeftController.view.parent as? ViewGroup)?.removeView(homeLeftController.view)
            addView(homeLeftController.view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        homeLeftOverlay = container
        addContentView(container, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        container.translationX = -resources.displayMetrics.widthPixels.toFloat()
        return container
    }

    private fun openHomeLeftPage() {
        val container = mountHomeLeftPage() ?: return
        container.animate().translationX(0f).setDuration(260L).setInterpolator(DecelerateInterpolator())
            .setUpdateListener { setLauncherBlurProgress(1f - abs(container.translationX) / resources.displayMetrics.widthPixels) }
            .start()
    }

    private fun beginHomeLeftDrag(): Boolean {
        if (mountHomeLeftPage() == null) return false
        homeLeftDragActive = true
        keyHaptic("space")
        return true
    }

    /** [delta] is horizontal finger travel (positive as it moves right, pulling the page in). */
    private fun updateHomeLeftDrag(delta: Float) {
        val overlay = homeLeftOverlay ?: return
        val width = resources.displayMetrics.widthPixels.toFloat()
        val translation = (-width + delta).coerceIn(-width, 0f)
        overlay.translationX = translation
        setLauncherBlurProgress(1f + translation / width) // 0 off-screen → 1 fully in
    }

    private fun settleHomeLeftDrag(delta: Float) {
        val overlay = homeLeftOverlay ?: run { homeLeftDragActive = false; return }
        homeLeftDragActive = false
        val width = resources.displayMetrics.widthPixels.toFloat()
        val openProgress = 1f + overlay.translationX / width
        val shouldOpen = openProgress > 0.22f || delta > dp(80)
        overlay.animate().cancel()
        if (shouldOpen) {
            overlay.animate().translationX(0f).setDuration(200L).setInterpolator(DecelerateInterpolator())
                .setUpdateListener { setLauncherBlurProgress(1f + overlay.translationX / width) }.start()
        } else {
            homeLeftOverlay = null
            overlay.animate().translationX(-width).setDuration(180L).setInterpolator(DecelerateInterpolator())
                .setUpdateListener { setLauncherBlurProgress(1f + overlay.translationX / width) }
                .withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay); setLauncherBlurProgress(0f) }.start()
        }
    }

    private fun closeHomeLeftPage(): Boolean {
        val overlay = homeLeftOverlay ?: return false
        homeLeftOverlay = null
        homeLeftDragActive = false
        val width = resources.displayMetrics.widthPixels.toFloat()
        overlay.animate().translationX(-width).setDuration(200L).setInterpolator(DecelerateInterpolator())
            .setUpdateListener { setLauncherBlurProgress(1f + overlay.translationX / width) }
            .withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay); setLauncherBlurProgress(0f) }.start()
        return true
    }

    /** Progressive blur+fade of the homescreen behind the left page (0 = clear, 1 = full glass). */
    private fun setLauncherBlurProgress(progress: Float) {
        if (!::rootView.isInitialized || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val p = progress.coerceIn(0f, 1f)
        if (p <= 0.01f) {
            rootView.setRenderEffect(null)
            rootView.alpha = 1f
            return
        }
        val radius = (dp(22).toFloat() * p).coerceAtLeast(0.5f)
        rootView.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        rootView.alpha = 1f - p * 0.4f
    }

    private fun updateSpaceIcon() {
        val icon = spaceIconView ?: return
        val snap = predictContext
        val locked = SpaceManager.lockedSpaceId(this) != null
        val space = when {
            snap != null -> SpaceManager.detect(this, snap).space
            locked -> SpaceManager.lockedSpaceId(this)?.let { SpaceManager.space(this, it) }
            else -> null
        }
        icon.text = space?.emoji ?: "◎"
        icon.alpha = if (locked) 1f else 0.88f
        icon.contentDescription = buildString {
            append("Space: ").append(space?.name ?: "detecting")
            if (locked) append(" (locked)")
        }
    }

    private fun libraryHeaderBackground(): Drawable {
        if (!appLibraryGlassEnabled()) {
            return Neu.drawable(activeNeuTokens, dp(18).toFloat(), NeuLevel.RAISED_SM)
        }
        return teclasGlassDrawable(18)
    }

    private fun libraryModeToggleBackground(): Drawable {
        if (!appLibraryGlassEnabled()) {
            return Neu.drawable(activeNeuTokens, dp(15).toFloat(), NeuLevel.PRESSED_SM)
        }
        return teclasGlassDrawable(15)
    }

    private fun isWidgetUniversalSearchActive(): Boolean =
        !isUnfoldedInnerLayoutActive() &&
            keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET &&
            openPane == null &&
            !libraryOpen &&
            !keyboardSettingsOpen &&
            query.isNotBlank()

    private fun refreshWidgetSearchContent() {
        val area = widgetSearchContentArea ?: return
        area.removeAllViews()
        val content = (if (focusSurfaceGlassEnabled()) glassSearchBackground(searchResultsList(widgetMode = true)) else searchResultsList(widgetMode = true)).apply {
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

    private fun glassSearchBackground(content: View): View {
        return FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            addView(
                if (nativeGlassSurfaceActive()) NativeFoldGlassPanel(context, radiusDp = 24)
                else DynamicGlassPlate(context, radiusDp = 24, strength = 1.72f, edgeInsetDp = 0).apply { setGlassProgress(1f) },
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            content.setPadding(
                content.paddingLeft + dp(10),
                content.paddingTop + dp(10),
                content.paddingRight + dp(10),
                content.paddingBottom + dp(10)
            )
            addView(content, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
    }

    private fun unfoldedSearchResultsList(): View = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        val results = universalSearchResults()
        val command = searchCommandPreview()
        val aiInline = searchAiInlineState()
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(18))
            if (results.isEmpty() && command == null && aiInline == null) {
                addView(TextView(context).apply {
                    text = "No results for \"$query\""
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(activeNeuTokens.inkDim)
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)))
                return@apply
            }
            command?.let {
                addView(searchCommandCard(it), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)).apply {
                    bottomMargin = dp(8)
                })
            }
            aiInline?.let {
                addView(searchAiAnswerCard(it), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                })
            }
            val visibleResults = if (aiInline != null) results.filterNot { it.kind == SearchKind.AI && it.title == "Ask Gemini" } else results
            visibleResults.take(6).chunked(2).forEachIndexed { rowIndex, rowItems ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    rowItems.forEachIndexed { columnIndex, result ->
                        val index = rowIndex * 2 + columnIndex
                        addView(unfoldedSearchResultTile(result, index == 0 && command == null && aiInline == null, index), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                            if (columnIndex > 0) marginStart = dp(10)
                        })
                    }
                    repeat(2 - rowItems.size) {
                        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(10) })
                    }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(74)).apply {
                    bottomMargin = dp(8)
                })
            }
        })
    }

    private fun unfoldedSearchResultTile(result: SearchResult, isBest: Boolean, index: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            alpha = 0f
            translationY = dp(6).toFloat()
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = searchCardBackground(result.kind, isBest, 18)
            postDelayed({
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }, (index * 20L).coerceAtMost(160L))
            addView(searchResultIcon(result), LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                marginEnd = dp(11)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = highlightedLabel(result.title, query)
                    textSize = 13.8f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(activeNeuTokens.ink)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    includeFontPadding = false
                }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(mono(result.subtitle.uppercase(Locale.US), 8.4f, activeNeuTokens.inkFaint).apply {
                    letterSpacing = 0.06f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(searchKindTag(result.kind), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(20)).apply {
                marginStart = dp(8)
            })
            setOnClickListener { haptic(this); openSearchResult(result) }
        }
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
            contextSuggestionResults(results.mapNotNull { it.target?.packageName }.toSet())?.let { (label, items) ->
                addView(mono(label.uppercase(Locale.US), 8.5f, activeNeuTokens.inkFaint).apply {
                    letterSpacing = 0.18f
                    setPadding(dp(2), dp(6), 0, dp(8))
                }, LinearLayout.LayoutParams.MATCH_PARENT, dp(28))
                items.chunked(2).forEach { rowItems ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        rowItems.forEachIndexed { columnIndex, result ->
                            addView(searchResultBentoCard(result, false, columnIndex), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
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
            contextSuggestionResults(results.mapNotNull { it.target?.packageName }.toSet())?.let { (label, items) ->
                addView(mono(label.uppercase(Locale.US), 8.5f, activeNeuTokens.inkFaint).apply {
                    letterSpacing = 0.18f
                    setPadding(dp(2), dp(6), 0, dp(8))
                }, LinearLayout.LayoutParams.MATCH_PARENT, dp(28))
                items.forEachIndexed { index, result ->
                    addView(searchResultRow(result, false, index), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(66)).apply {
                        bottomMargin = dp(8)
                    })
                }
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
            if (result.kind == SearchKind.APP && result.target?.packageName != null) {
                setOnLongClickListener { haptic(this); showSearchAppMenu(this, result); true }
            } else result.longAction?.let { longAction ->
                setOnLongClickListener { haptic(this); longAction(); true }
            }
        }
    }

    /**
     * Long-press menu for an app in search results: pin it straight to the favorites dock (the
     * fixed "your apps" dock, not the context one) — so searching "instagram" lets you pin it
     * without leaving search. Also offers pinning to the active Space's board.
     */
    private fun showSearchAppMenu(anchor: View, result: SearchResult) {
        val pkg = result.target?.packageName ?: return
        val onDock = pkg in favoritePackages()
        val space = activeSpaceForUi()
        val pinned = space != null && pkg in space.pinned
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, if (onDock) "Remove from dock" else "Pin to dock")
        if (space != null) {
            popup.menu.add(0, 2, 1,
                if (pinned) "Unpin from ${space.emoji} ${space.name} board" else "Pin to ${space.emoji} ${space.name} board")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    setHomePresence(pkg, !onDock)
                    Toast.makeText(
                        this,
                        if (onDock) "Removed from dock" else "Pinned ${result.title} to dock",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                2 -> if (space != null) {
                    val next = if (pinned) space.pinned - pkg else space.pinned + pkg
                    SpaceManager.update(this, space.copy(pinned = next))
                    Toast.makeText(
                        this,
                        if (pinned) "Unpinned from ${space.name}" else "Pinned ${result.title} to ${space.name}",
                        Toast.LENGTH_SHORT,
                    ).show()
                    if (spaceBoardOverlay != null) reloadSpaceBoardForActiveSpace()
                }
            }
            true
        }
        popup.show()
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
            if (result.kind == SearchKind.APP && result.target?.packageName != null) {
                setOnLongClickListener { haptic(this); showSearchAppMenu(this, result); true }
            } else result.longAction?.let { longAction ->
                setOnLongClickListener { haptic(this); longAction(); true }
            }
        }
    }

    private fun searchResultIcon(result: SearchResult): View {
        val app = result.target?.packageName?.let { pkg -> apps.firstOrNull { it.packageName == pkg } }
        val builtIn = result.target?.let { target -> builtInLauncherApps().firstOrNull { it.target.id == target.id } }
        return FrameLayout(this).apply {
            background = if (result.kind == SearchKind.APP && (app != null || builtIn != null)) {
                libraryIconButtonBackground(12, Line)
            } else {
                Neu.drawable(activeNeuTokens, dp(12).toFloat(), NeuLevel.PRESSED_SM)
            }
            setPadding(dp(5), dp(5), dp(5), dp(5))
            if (result.kind == SearchKind.APP && (app != null || builtIn != null)) {
                addView(ImageView(context).apply {
                    setImageDrawable(iconFor(app?.toLibraryApp() ?: builtIn!!))
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
        if (!focusSurfaceGlassEnabled()) {
            val base = Neu.drawable(activeNeuTokens, dp(radiusDp).toFloat(), if (isBest) NeuLevel.RAISED else NeuLevel.RAISED_SM)
            if (!isBest) return base
            val ring = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(radiusDp).toFloat()
                setStroke(dp(1), adjustAlpha(searchKindAccent(kind), 0.72f))
            }
            return LayerDrawable(arrayOf(base, ring))
        }
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
        SearchKind.MUSIC -> Neu.GREEN
        SearchKind.FILE -> Neu.BLUE
        SearchKind.SETTING -> goKeyColor
        SearchKind.WEB -> Neu.BLUE
    }

    private fun searchKindGlyph(kind: SearchKind): String = when (kind) {
        SearchKind.CONTACT -> "P"
        SearchKind.EMAIL -> "@"
        SearchKind.MESSAGE -> "M"
        SearchKind.CALENDAR -> "C"
        SearchKind.AI -> "AI"
        SearchKind.APP -> "A"
        SearchKind.TRAVEL -> "✈"
        SearchKind.MUSIC -> "♪"
        SearchKind.FILE -> "F"
        SearchKind.SETTING -> "⚙"
        SearchKind.WEB -> "W"
    }

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
            "search", "google", "web" -> SearchCommandPreview(body.ifBlank { clean }, "SEARCH GOOGLE IN TECLAS", "G")
            "ask", "ai", "gemini" -> SearchCommandPreview(body.ifBlank { clean }, "ASK TECLAS AI", "AI")
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
        if (!focusSurfaceGlassEnabled()) {
            val base = Neu.drawable(activeNeuTokens, dp(15).toFloat(), NeuLevel.RAISED)
            val ring = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(15).toFloat()
                setStroke(dp(1), adjustAlpha(Neu.GREEN, 0.72f))
            }
            return LayerDrawable(arrayOf(base, ring))
        }
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
        return aiAnswersById[target.id] ?: AiAnswerState(clean, "Tap to ask Gemini from Teclas.", false)
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
                        text = "Teclas AI"
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
        if (!appLibraryGlassEnabled()) {
            addView(classicBentoGridContent())
            return@apply
        }
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(18))
            libraryCategories().chunked(2).forEachIndexed { rowIndex, group ->
                addView(categoryBentoRow(group, rowIndex), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(142)).apply {
                    bottomMargin = dp(10)
                })
            }
        })
    }

    private fun classicBentoGridContent(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        libraryCategories().chunked(3).forEachIndexed { rowIndex, group ->
            addView(classicCategoryBentoRow(group, rowIndex), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(184)).apply {
                bottomMargin = dp(12)
            })
        }
    }

    private fun classicCategoryBentoRow(group: List<LibraryCategory>, rowIndex: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        group.getOrNull(0)?.let { category ->
            addView(classicCategoryBentoCard(category, prominent = true, rowIndex * 3), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.28f).apply {
                marginEnd = dp(8)
            })
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            group.drop(1).take(2).forEachIndexed { index, category ->
                addView(classicCategoryBentoCard(category, prominent = false, rowIndex * 3 + index + 1), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    if (index == 0) bottomMargin = dp(8)
                })
            }
            repeat(2 - group.drop(1).take(2).size) {
                addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun classicCategoryBentoCard(category: LibraryCategory, prominent: Boolean, index: Int): View = LinearLayout(this).apply {
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

    private fun categoryBentoRow(group: List<LibraryCategory>, rowIndex: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        group.forEachIndexed { index, category ->
            addView(categoryBentoCard(category, prominent = group.size == 1, rowIndex * 2 + index), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (index == 0 && group.size > 1) marginEnd = dp(10)
            })
        }
        if (group.size == 1) addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun categoryBentoCard(category: LibraryCategory, prominent: Boolean, index: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.TOP
        isClickable = true
        elevation = dp(5).toFloat()
        alpha = 0f
        translationY = dp(8).toFloat()
        setPadding(dp(13), dp(12), dp(13), dp(11))
        background = categoryBentoBackground(category.accent)
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
            }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
            addView(TextView(context).apply {
                text = category.name
                textSize = 14.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(activeNeuTokens.ink)
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(TextView(context).apply {
            text = category.apps.take(3).joinToString("  ") { it.name }
            textSize = 10.5f
            setTextColor(activeNeuTokens.inkDim)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(5), 0, 0)
        }, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(categoryPreviewStrip(category.apps.take(if (prominent) 5 else 4)), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(12)
        })
    }

    private fun categoryBentoBackground(accent: Int): Drawable {
        if (!appLibraryGlassEnabled()) {
            return if (activeNeuTokens.mode == NeuMode.LIGHT) {
                Neu.drawable(activeNeuTokens, dp(18).toFloat(), NeuLevel.RAISED_SM)
            } else {
                roundedPanel(0xFF1A1D23.toInt(), dp(18), Line)
            }
        }
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                adjustAlpha(Color.WHITE, if (activeNeuTokens.mode == NeuMode.LIGHT) 0.24f else 0.12f),
                adjustAlpha(activeNeuTokens.baseHi, if (activeNeuTokens.mode == NeuMode.LIGHT) 0.70f else 0.34f),
                adjustAlpha(activeNeuTokens.baseLo, if (activeNeuTokens.mode == NeuMode.LIGHT) 0.58f else 0.50f)
            )
        ).apply {
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), adjustAlpha(Color.WHITE, if (activeNeuTokens.mode == NeuMode.LIGHT) 0.30f else 0.13f))
        }
        val glow = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            adjustAlpha(accent, 0.18f),
            Color.TRANSPARENT,
            adjustAlpha(Color.BLACK, if (activeNeuTokens.mode == NeuMode.LIGHT) 0.04f else 0.28f)
        )).apply { cornerRadius = dp(22).toFloat() }
        return LayerDrawable(arrayOf(base, glow))
    }

    private fun categoryPreviewStrip(items: List<LibraryApp>): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM or Gravity.LEFT
        val count = items.size.coerceAtLeast(1)
        items.forEach { app ->
            addView(FrameLayout(context).apply {
                elevation = dp(2).toFloat()
                background = libraryIconButtonBackground(12, Line)
                addView(ImageView(context).apply {
                    setImageDrawable(iconFor(app))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginEnd = if (items.indexOf(app) == items.lastIndex) 0 else dp(8)
            })
        }
        repeat((4 - count).coerceAtLeast(0)) {
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = dp(8) })
        }
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
                    pendingLaunchSource = LaunchSource.DRAWER
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
        if (!appLibraryGlassEnabled()) return classicLibraryGrid()
        val useGridPhysics = !isUnfoldedInnerLayoutActive()
        val plainGrid = this
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(8))
            predictedLibraryApps(8).takeIf { it.isNotEmpty() }?.let {
                addView(tileGrid(it, 4, gridPhysics = useGridPhysics))
            }
            addView(tileGrid((builtInLauncherApps() + apps.map { it.toLibraryApp() }).distinctBy { it.target.id }, 4, gridPhysics = useGridPhysics))
        })
    }.let { plainScroll ->
        if (isUnfoldedInnerLayoutActive()) {
            libraryGridLiquidBackdrop = null
            libraryGridBlurView = null
            libraryGridScrollView = null
            return@let ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipChildren = true
                clipToPadding = true
                setBackgroundColor(Color.TRANSPARENT)
                addView((plainScroll as ScrollView).getChildAt(0).also { plainScroll.removeView(it) })
                setPadding(0, 0, 0, dp(if (innerLibraryLocked()) 96 else 20))
            }
        }
        FrameLayout(this).apply {
            val liquid = LiquidGridBackdropView(context).apply {
                updateTokens(activeNeuTokens)
            }
            val scroll = GridFishEyeScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                addView((plainScroll as ScrollView).getChildAt(0).also { plainScroll.removeView(it) })
                setPadding(0, 0, 0, dp(8))
            }
            libraryGridLiquidBackdrop = liquid
            libraryGridBlurView = null
            libraryGridScrollView = scroll
            addView(liquid, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(scroll, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            post {
                val progress = if (libraryOpen || innerLibraryLocked() || isUnfoldedInnerLayoutActive()) 1f else 0f
                updateLibraryGridDragEffects(progress, width * 0.5f)
                scroll.applyFishEye()
            }
        }
    }

    private fun classicLibraryGrid(): View {
        libraryGridLiquidBackdrop = null
        libraryGridBlurView = null
        libraryGridScrollView = null
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setBackgroundColor(activeNeuTokens.base)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(12), 0, dp(8))
                predictedLibraryApps(8).takeIf { it.isNotEmpty() }?.let {
                    addView(tileGrid(it, 4, gridPhysics = false))
                }
                addView(tileGrid((builtInLauncherApps() + apps.map { it.toLibraryApp() }).distinctBy { it.target.id }, 4, gridPhysics = false))
            })
        }
    }

    /**
     * Leading drawer block: the active Space's predicted apps, rendered with the exact
     * same tiles as the grid below (reorder only — the full alphabetical grid stays
     * intact underneath). Empty until the prediction context is ready.
     */
    private fun predictedLibraryApps(n: Int): List<LibraryApp> {
        val eligible = apps.filter { it.packageName != packageName }
        val predicted = predictedPackages(eligible.map { it.packageName }, n) ?: return emptyList()
        val byPackage = eligible.associateBy { it.packageName }
        return predicted.mapNotNull { byPackage[it]?.toLibraryApp() }
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
                    pendingLaunchSource = LaunchSource.DRAWER
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
            hubView.addView(messageRow(message.color, message.sender, message.preview, message.toPaneTarget(), message))
        }
    }

    /** Dismiss a notification from the today hub: cancel it system-side and refresh the hub and the
     *  homescreen today widget instantly. */
    private fun dismissHubMessage(message: HubMessage) {
        TeclasNotificationListener.dismiss(this, message.key)
        messages = messages.filterNot { it.key == message.key }
        renderHub()
        refreshNowPlayingCard()
    }

    private fun refreshHubMessagesFromPrefs() {
        messages = loadHubMessages()
        renderHub()
        refreshNowPlayingCard()
    }

    private fun messageRow(color: Int, who: String, preview: String, target: PaneTarget?, message: HubMessage? = null): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8)); background = border(Line); isClickable = true
            setOnClickListener {
                haptic(this)
                when {
                    target == null -> openNotificationAccessSettings()
                    // Messages route through reply-or-open (Telegram/WhatsApp inline reply when docked).
                    message != null && message.kind == HUB_KIND_MESSAGE -> openMessageReplyOrApp(message)
                    else -> openHere(target)
                }
            }
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
            // Dismiss (✕): clears the notification from the hub and the homescreen today widget.
            if (message != null) {
                addView(TextView(context).apply {
                    text = "✕"; textSize = 13f; setTextColor(InkDim); gravity = Gravity.CENTER
                    includeFontPadding = false; isClickable = true
                    setOnClickListener { haptic(this); dismissHubMessage(message) }
                }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(4) })
            }
        }
    }

    // ── Keyboard ─────────────────────────────────────────────────────────────

    private fun dockedInputView(): View {
        return when (openPane?.kind) {
            PaneKind.MUSIC -> when {
                musicTheme() == MUSIC_THEME_BLACK -> musicPaneHost.musicBlackDock()
                // Click wheel is a Pro feature and only applies to a connected service
                // whose account matches what's actually playing. Everything else
                // (YouTube Music, podcasts, browser audio…) gets simple transport controls.
                activeMusicMode() == MusicMode.SIMPLE -> simpleTransportDock()
                else -> musicPaneHost.musicDockView()
            }
            PaneKind.PHOTOS -> photoAlbumsDock()
            else -> keyboard()
        }
    }

    private fun rootDockInputView(): View =
        if (isUnfoldedInnerLayoutActive() && openPane == null) unfoldedInnerKeyboardDock() else dockedInputView()

    private fun unfoldedInnerKeyboardDock(): View {
        val panelWidth = unfoldedKeyboardPanelWidth()
        return FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            background = ColorDrawable(Color.TRANSPARENT)
            widgetKeyboardHost = this

            widgetKeyboardSeatView = KeyboardSocketView(context).apply {
                alpha = 0.32f
            }
            addView(widgetKeyboardSeatView, FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

            val socket = KeyboardSocketView(context).apply {
                alpha = 0f
                visibility = View.GONE
            }
            widgetSocketView = socket
            addView(socket, FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

            val keyboardPanel = FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                translationX = unfoldedKeyboardPanelOffsetX(panelWidth).toFloat()
                translationY = innerKeyboardOffsetY().toFloat()
                if (innerKeyboardEditMode) {
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = unfoldedKeyboardEditFrameBackground()
                }
                addView(dockedInputView(), FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
            widgetKeyboardModule = keyboardPanel
            addView(keyboardPanel, FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

            widgetKeyboardSliderHandleView = KeyboardSliderHandleView(context).apply {
                visibility = if (widgetKeyboardHidden) View.VISIBLE else View.GONE
                alpha = if (widgetKeyboardHidden) 1f else 0f
                setOnTouchListener { _, event -> handleWidgetKeyboardSliderHandleTouch(event) }
            }
            addView(widgetKeyboardSliderHandleView, FrameLayout.LayoutParams(dp(132), dp(34), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(7)
            })
            setOnTouchListener { _, event ->
                if (widgetKeyboardHidden) handleWidgetKeyboardSliderHostTouch(event) else false
            }
            applyWidgetKeyboardHiddenState(animate = false)

            widgetCoachView = TextView(context).apply {
                text = "Swipe to browse · tap to install"
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
                topMargin = dp(12)
            })

            widgetDotsView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                alpha = 0f
                visibility = View.GONE
            }
            addView(widgetDotsView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(8)
            })
            updateWidgetThemeDots()

            val grabber = TextView(context).apply {
                text = ""
                gravity = Gravity.CENTER
                textSize = 8f
                letterSpacing = 0.14f
                includeFontPadding = false
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(activeNeuTokens.inkDim)
                background = unfoldedKeyboardHandleBackground()
                isClickable = true
                if (innerKeyboardEditMode) {
                    setOnTouchListener(innerKeyboardMoveListener(keyboardPanel))
                } else {
                    setOnLongClickListener {
                        haptic(this)
                        innerKeyboardEditMode = true
                        render()
                        true
                    }
                }
            }
            keyboardPanel.addView(grabber, FrameLayout.LayoutParams(if (innerKeyboardEditMode) dp(72) else dp(94), if (innerKeyboardEditMode) dp(24) else dp(10), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = if (innerKeyboardEditMode) dp(5) else dp(9)
            })

            if (innerKeyboardEditMode) {
                val doneHandle = TextView(context).apply {
                    text = "DONE"
                    gravity = Gravity.CENTER
                    textSize = 8f
                    letterSpacing = 0.14f
                    includeFontPadding = false
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(activeNeuTokens.inkDim)
                    background = unfoldedKeyboardHandleBackground()
                    isClickable = true
                    setOnClickListener {
                        haptic(this)
                        innerKeyboardEditMode = false
                        render()
                    }
                }
                keyboardPanel.addView(doneHandle, FrameLayout.LayoutParams(dp(68), dp(24), Gravity.TOP or Gravity.RIGHT).apply {
                    topMargin = dp(5)
                    rightMargin = dp(8)
                })
                val resizeHandle = TextView(context).apply {
                    text = "↘"
                    gravity = Gravity.CENTER
                    textSize = 17f
                    includeFontPadding = false
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    setTextColor(activeNeuTokens.ink)
                    background = unfoldedKeyboardHandleBackground()
                    setOnTouchListener(innerKeyboardResizeListener(keyboardPanel))
                }
                keyboardPanel.addView(resizeHandle, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.BOTTOM or Gravity.RIGHT).apply {
                    rightMargin = dp(8)
                    bottomMargin = dp(12)
                })
            }
        }
    }

    private fun unfoldedKeyboardHandleBackground(): Drawable =
        Neu.drawable(activeNeuTokens, dp(14).toFloat(), NeuLevel.RAISED_SM)

    private fun unfoldedKeyboardEditFrameBackground(): Drawable =
        Neu.drawable(activeNeuTokens, dp(24).toFloat(), NeuLevel.PRESSED_SM)

    private fun innerKeyboardSnapMaxX(panel: View): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        val panelWidth = panel.width.takeIf { it > 0 }
            ?: (panel.layoutParams?.width ?: 0).takeIf { it > 0 }
            ?: (screenWidth * innerKeyboardWidthPercent() / 100f).toInt()
        return ((screenWidth - panelWidth) / 2).coerceAtLeast(0)
    }

    private fun nearestInnerKeyboardSnap(rawX: Int, panel: View): Int {
        val maxX = innerKeyboardSnapMaxX(panel)
        if (maxX <= dp(6)) return 0
        val snaps = intArrayOf(-maxX, 0, maxX)
        return snaps.minBy { abs(rawX - it) }
    }

    private fun innerKeyboardMoveListener(panel: View): View.OnTouchListener {
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        return View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = innerKeyboardOffsetX()
                    startY = innerKeyboardOffsetY()
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    haptic(view)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = innerKeyboardSnapMaxX(panel).coerceAtLeast(dp(4))
                    val nextX = (startX + event.rawX - startRawX).toInt().coerceIn(-maxX, maxX)
                    val nextY = (startY + event.rawY - startRawY).toInt().coerceIn(-dp(72), dp(18))
                    panel.translationX = nextX.toFloat()
                    panel.translationY = nextY.toFloat()
                    prefs().edit()
                        .putInt(INNER_KEYBOARD_OFFSET_X_PREF, nextX)
                        .putInt(INNER_KEYBOARD_OFFSET_Y_PREF, nextY)
                        .apply()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    haptic(view)
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    val snapX = nearestInnerKeyboardSnap(panel.translationX.toInt(), panel)
                    prefs().edit()
                        .putInt(INNER_KEYBOARD_OFFSET_X_PREF, snapX)
                        .putInt(INNER_KEYBOARD_OFFSET_Y_PREF, panel.translationY.toInt())
                        .apply()
                    panel.animate()
                        .translationX(snapX.toFloat())
                        .setDuration(180L)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction { keyboardDockView.post { captureKeyBounds() } }
                        .start()
                    true
                }
                else -> true
            }
        }
    }

    private fun innerKeyboardResizeListener(panel: View): View.OnTouchListener {
        var startRawX = 0f
        var startRawY = 0f
        var startWidth = 0
        var startBoost = 0
        var nextWidth = 0
        var nextBoost = 0
        var dragged = false
        return View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startWidth = innerKeyboardWidthPercent()
                    startBoost = innerKeyboardSizeBoost()
                    nextWidth = startWidth
                    nextBoost = startBoost
                    dragged = false
                    innerKeyboardPreviewBoost = startBoost
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    haptic(view)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) dragged = true
                    val widthDelta = (dx / resources.displayMetrics.widthPixels * 100f).toInt()
                    nextWidth = (startWidth + widthDelta).coerceIn(48, 100)
                    nextBoost = (startBoost - (dy / dp(3).coerceAtLeast(1))).toInt().coerceIn(-20, 150)
                    innerKeyboardPreviewBoost = nextBoost
                    val lp = panel.layoutParams as? FrameLayout.LayoutParams
                    if (lp != null) {
                        lp.width = (resources.displayMetrics.widthPixels * nextWidth / 100f).toInt()
                            .coerceIn(dp(720), resources.displayMetrics.widthPixels - dp(36))
                        panel.layoutParams = lp
                    }
                    panel.pivotY = panel.height.toFloat().coerceAtLeast(1f)
                    panel.scaleY = (1f + ((nextBoost - startBoost) / 155f)).coerceIn(0.72f, 1.52f)
                    if (::keyboardDockView.isInitialized) {
                        val dockLp = keyboardDockView.layoutParams
                        if (dockLp != null) {
                            dockLp.height = activeRootDockHeight()
                            keyboardDockView.layoutParams = dockLp
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    innerKeyboardPreviewBoost = null
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (dragged && event.actionMasked == MotionEvent.ACTION_UP) {
                        prefs().edit()
                            .putInt(INNER_KEYBOARD_WIDTH_PREF, nextWidth)
                            .putInt(INNER_KEYBOARD_SIZE_BOOST_PREF, nextBoost)
                            .apply()
                        haptic(view)
                        render()
                    } else {
                        haptic(view)
                        keyboardDockView.post { captureKeyBounds() }
                    }
                    true
                }
                else -> true
            }
        }
    }

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
                    addView(musicPaneHost.musicBlackTransportButton("◀", accent = false) {
                        haptic(this); mediaSessionSource.skipToPrevious()
                    }, LinearLayout.LayoutParams(dp(60), dp(60)).apply { marginEnd = dp(22) })
                    addView(musicPaneHost.musicBlackTransportButton(if (playing) "Ⅱ" else "▶", accent = true) {
                        haptic(this); mediaSessionSource.togglePlayPause()
                    }, LinearLayout.LayoutParams(dp(76), dp(76)))
                    addView(musicPaneHost.musicBlackTransportButton("▶", accent = false) {
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
        keyboardDockView.addView(rootDockInputView(), FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        keyboardDockView.post { captureKeyBounds() }
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
        syncLauncherSuggestionStripHeightForBuild()
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
                    marginStart = dp(8)
                    marginEnd = dp(8)
                    topMargin = dp(8)
                    bottomMargin = dp(1)
                })
            }
            // Suggestion strip at the TOP of the keyboard (Gboard-style), above all key rows.
            if (showSuggestionStrip() && !numberPadOpen && !keyboardSettingsOpen) {
                val stripHeight = launcherSuggestionStripLayoutHeight()
                addView(suggestionStrip(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, stripHeight).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                    topMargin = if (stripHeight > 0) dp(2) else 0
                    bottomMargin = 0
                })
            }
            if (symbolsOpen) {
                addKeyRow(com.fran.teclas.keyboard.KeyboardSymbols.ROW_DIGITS)
                addKeyRow(com.fran.teclas.keyboard.KeyboardSymbols.ROW_SYMBOLS_1)
                addKeyRow(com.fran.teclas.keyboard.KeyboardSymbols.ROW_SYMBOLS_2 + listOf("back"), dp(8))
                addKeyRow(listOf("abc", "teclas", "space", "period", "enter"), dp(15))
            } else if (numberPadOpen) {
                addKeyRow(listOf("1", "2", "3"))
                addKeyRow(listOf("4", "5", "6"))
                addKeyRow(listOf("7", "8", "9"))
                addKeyRow(listOf("abc", "0", "back", "enter"))
            } else {
                addKeyRow("qwertyuiop".map { it.toString() })
                addKeyRow("asdfghjkl".map { it.toString() }, dp(18))
                addKeyRow(listOf("shift") + "zxcvbnm".map { it.toString() } + listOf("back"), dp(8))
                addKeyRow(listOf("123", "teclas", "space", "period", "enter"), dp(15))
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

            if (isUnfoldedInnerLayoutActive()) {
                addView(innerKeyboardWidthSetting(), LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(innerKeyboardHeightSetting(), LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

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
            addView(settingAction("TECLAS SETTINGS") {
                keyboardSettingsOpen = false
                openHere(teclasSettingsTarget())
            }, LinearLayout.LayoutParams.MATCH_PARENT, dp(30))
        }
    }

    private fun innerKeyboardWidthSetting(): View {
        var valueView: TextView? = null
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(mono("INNER WIDTH", 9f, InkDim).apply { letterSpacing = 0.12f }, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(SeekBar(context).apply {
                max = 52
                progress = innerKeyboardWidthPercent() - 48
                thumbTintList = android.content.res.ColorStateList.valueOf(Accent)
                progressTintList = android.content.res.ColorStateList.valueOf(Accent)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(KeyEdge)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        valueView?.text = "${p + 48}%"
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) = Unit
                    override fun onStopTrackingTouch(s: SeekBar?) {
                        val next = ((s?.progress ?: 20) + 48).coerceIn(48, 100)
                        prefs().edit().putInt(INNER_KEYBOARD_WIDTH_PREF, next).apply()
                        s?.let { haptic(it) }
                        render()
                    }
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            })
            valueView = mono("${innerKeyboardWidthPercent()}%", 9f, Accent2).apply { gravity = Gravity.RIGHT }
            addView(valueView, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun innerKeyboardHeightSetting(): View {
        var valueView: TextView? = null
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(mono("INNER HEIGHT", 9f, InkDim).apply { letterSpacing = 0.12f }, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(SeekBar(context).apply {
                max = 140
                progress = innerKeyboardSizeBoost() + 20
                thumbTintList = android.content.res.ColorStateList.valueOf(Accent)
                progressTintList = android.content.res.ColorStateList.valueOf(Accent)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(KeyEdge)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        valueView?.text = "${p - 20}"
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) = Unit
                    override fun onStopTrackingTouch(s: SeekBar?) {
                        val next = ((s?.progress ?: 56) - 20).coerceIn(-20, 120)
                        prefs().edit().putInt(INNER_KEYBOARD_SIZE_BOOST_PREF, next).apply()
                        s?.let { haptic(it) }
                        render()
                    }
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            })
            valueView = mono("${innerKeyboardSizeBoost()}", 9f, Accent2).apply { gravity = Gravity.RIGHT }
            addView(valueView, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT))
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
                        "TECLAS" to KEYBOARD_THEME_TECLAS,
                        "SKEUO" to KEYBOARD_THEME_SKEUO,
                        "GOKEYS" to KEYBOARD_THEME_GOKEYS,
                        "HYPER" to KEYBOARD_THEME_HYPER3D,
                        "BLACK" to KEYBOARD_THEME_HYPER3D_BLACK,
                        "LIGHT" to KEYBOARD_THEME_HYPER3D_LIGHT,
                        "BRUSHED" to KEYBOARD_THEME_BRUSHED,
                        "SEEME" to KEYBOARD_THEME_SEEME
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
        val selected = com.fran.teclas.keyboard.DictionaryLoader.enabledLanguages(this).toSet()
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
        val order = com.fran.teclas.keyboard.DictionaryLoader.available()
        val cur = com.fran.teclas.keyboard.DictionaryLoader.enabledLanguages(this).toMutableSet()
        if (code in cur) { if (cur.size > 1) cur.remove(code) } else cur.add(code)
        val ordered = order.filter { it in cur }
        prefs().edit().putString(com.fran.teclas.keyboard.DictionaryLoader.LANGUAGES_PREF, ordered.joinToString(",")).apply()
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
        widgetKeyboardHidden = false
        saveWidgetKeyboardHiddenForCurrentPosture(false)
        widgetKeyboardSliderAnimating = false
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
                        renderPaneContent(teclasSettingsTarget())
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
                    "teclas" -> 1.55f
                    "123" -> 1.02f
                    "back", "shift" -> 1.02f
                    "abc" -> 1.02f
                    else -> 1f
                }
                if (label == "enter") {
                    addView(key(label), LinearLayout.LayoutParams(themedGoKeySize(), themedGoKeySize()).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        marginStart = dp(2)
                    })
                } else if (label == "123") {
                    addView(key(label), LinearLayout.LayoutParams(themedGoKeySize(), themedGoKeySize()).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        marginEnd = dp(2)
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
        val isWidgetDockKey = label == "teclas" && keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET
        val isDockedTeclasKey = label == "teclas" && keyboardPlacement == KEYBOARD_PLACEMENT_DOCKED
        val useBrushedOpticalKey = keyboardTheme == KEYBOARD_THEME_BRUSHED && !isLetter && label != "teclas" && label != "space"
        return (if (isWidgetDockKey) DockKeyView(this) else if (label == "space") SpaceKeyView(this) else if (isLetter) DynamicFlickKeyView(this) else if (useBrushedOpticalKey) com.fran.teclas.keyboard.OpticalKeyTextView(this) else TextView(this)).apply {
            text = if (keyboardTheme == KEYBOARD_THEME_BRUSHED && label == "teclas") "" else keyLabel(label)
            if (keyboardTheme == KEYBOARD_THEME_BRUSHED && label == "teclas") {
                contentDescription = if (keyboardPlacement == KEYBOARD_PLACEMENT_DOCKED) "Docked, tap to undock" else "Undocked, tap to dock"
            }
            gravity = Gravity.CENTER
            textSize = keyTextSize(label)
            typeface = if (keyboardTheme == KEYBOARD_THEME_SEEME || keyboardTheme == KEYBOARD_THEME_BRUSHED) {
                Typeface.create(Typeface.MONOSPACE, if (label == "enter") Typeface.BOLD else Typeface.NORMAL)
            } else {
                Typeface.create("sans-serif", if (label == "shift" || label == "enter") Typeface.BOLD else Typeface.NORMAL)
            }
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, if (keyboardTheme == KEYBOARD_THEME_BRUSHED) {
                dp(
                    when (label) {
                        "enter" -> 0
                        "shift", "back", "space", "period" -> 0
                        "123", "abc" -> 0
                        else -> 0
                    }
                )
            } else 0)
            if (keyboardTheme == KEYBOARD_THEME_BRUSHED) {
                when (this) {
                    is com.fran.teclas.keyboard.OpticalKeyTextView -> opticalTextOffsetY = dp(
                        when (label) {
                            "enter" -> -2
                            "shift" -> -12
                            "back" -> -16
                            "123", "abc", "period" -> -5
                            else -> 0
                        }
                    ).toFloat().also {
                        opticalTextOffsetX = if (label == "back") -dp(4).toFloat() else 0f
                    }
                    is SpaceKeyView -> brushedTextOffsetY = -dp(16).toFloat()
                }
            }
            setTextColor(keyTextColor(label))
            val vInset = keyVerticalInset()
            val hInset = keyHorizontalInset()
            // Show the swipe-down symbol at the bottom of letter keys.
            if (isLetter && this is DynamicFlickKeyView) {
                setKeyFaceInsets(hInset, vInset)
                if (keyboardTheme == KEYBOARD_THEME_BRUSHED) {
                    setLabelPlacement(
                        labelBias = 0.28f,
                        symbolBias = 0.04f,
                        labelMaxScale = 0.52f,
                        symbolScale = 0.16f,
                        extraBottomInsetPx = 0,
                        engravedSymbols = true
                    )
                }
                val sym = com.fran.teclas.keyboard.KeyboardSymbols.keyUp[label.lowercase(Locale.US)]
                setSymbolHint(sym, (keyTextColor(label) and 0x00FFFFFF) or (0xD8 shl 24))
                if (keyboardTheme == KEYBOARD_THEME_BRUSHED) {
                    setDrawnPrimaryLabel(label.lowercase(Locale.US), keyTextColor(label), keyTextSize(label) * resources.displayMetrics.scaledDensity, typeface)
                } else {
                    setDrawnPrimaryLabel(
                        keyLabel(label),
                        keyTextColor(label),
                        keyTextSize(label) * resources.displayMetrics.scaledDensity,
                        typeface
                    )
                }
            }
            isClickable = true
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
            val cursorStepPx = dp(6).toFloat()
            // Manual long-press: the OnTouchListener consumes events (returns true), so the
            // View's built-in long-click detection never runs. Detect it ourselves.
            val longPressAction: (() -> Unit)? = when {
                isWidgetDockKey -> { -> beginWidgetKeyboardDetach(this@apply) }
                isDockedTeclasKey -> { -> beginDockedKeyboardPopToWidget(this@apply) }
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
                        v.animate().translationY(dp(4).toFloat()).setDuration(35L).start()
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
                        seemeReleaseHaptic(v)
                        v.animate().translationY(0f).rotationX(0f).scaleX(1f).scaleY(1f).setDuration(35L).start()
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
                        // Direct taps must trust the key view that received the event. Re-resolving
                        // against cached screen bounds after keyboard slot/move animations can map
                        // a correct tap to the wrong row (for example S -> E).
                        if (label == "shift") handleShiftTap() else handleKey(label)
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.background = idleBg()
                        if (isWidgetDockKey) (v as? DockKeyView)?.cancelHoldProgress()
                        v.animate().translationY(0f).rotationX(0f).scaleX(1f).scaleY(1f).setDuration(35L).start()
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
        "teclas" -> if (keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET) "DOCK" else "teclas"
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
    private var launcherSuggestionStripExpanded = false
    private var launcherSuggestionStripAnimatedHeight = 0
    private var launcherSuggestionStripAnimator: ValueAnimator? = null
    private var launcherPolishing = false
    private var pendingLauncherCommand: AgenticRouter.Command? = null
    private var pendingLauncherActions: List<Pair<String, String>> = emptyList()
    // Hold go over an empty field → discoverable starter chips instead of the old auto-location.
    private var pendingLauncherStarters: List<Pair<String, () -> Unit>> = emptyList()
    // Shared-engine surfaces (parity with the IME): a working-status banner and a result HUD.
    private var launcherAgenticStatus: String? = null
    private var pendingLauncherResult: AgenticResult? = null

    private fun showSuggestionStrip() = true
    private fun showKeyboardTypingWell() = !keyboardSettingsOpen
    private fun keyboardTypingWellHeight() = dp(36)
    private fun keyboardSuggestionStripHeight() = dp(28)
    private fun launcherSuggestionText(): String = when (openPane?.kind) {
        PaneKind.CHAT -> composeText
        PaneKind.AI -> aiDraftText
        else -> query
    }
    private fun launcherSuggestionStripShouldExpand(): Boolean =
        showSuggestionStrip() &&
            !numberPadOpen &&
            !keyboardSettingsOpen &&
            launcherSuggestionText().isNotBlank()

    private fun syncLauncherSuggestionStripHeightForBuild() {
        val target = if (launcherSuggestionStripShouldExpand()) keyboardSuggestionStripHeight() else 0
        if (launcherSuggestionStripAnimator?.isRunning == true) return
        launcherSuggestionStripExpanded = target > 0
        launcherSuggestionStripAnimatedHeight = target
    }

    private fun launcherSuggestionStripOuterHeight(): Int {
        val h = launcherSuggestionStripLayoutHeight()
        return if (h > 0) h + dp(2) else 0
    }
    private fun suggestionStripHeight() = if (showKeyboardTypingWell()) keyboardTypingWellHeight() + dp(9) else 0
    private fun launcherSuggestionStripReservesSpace(): Boolean =
        keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET || isUnfoldedInnerLayoutActive()

    private fun launcherSuggestionStripLayoutHeight(): Int {
        return if (launcherSuggestionStripReservesSpace()) {
            keyboardSuggestionStripHeight()
        } else {
            launcherSuggestionStripAnimatedHeight.coerceIn(0, keyboardSuggestionStripHeight())
        }
    }

    private fun suggestionStrip(): View {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(10), dp(2))
            visibility = if (launcherSuggestionStripLayoutHeight() > 0) View.VISIBLE else View.GONE
            alpha = if (launcherSuggestionStripAnimatedHeight > 0) 1f else 0f
        }
        suggestionStripView = strip
        updateSuggestionBar()
        return strip
    }

    private fun setLauncherSuggestionStripExpanded(expand: Boolean, animate: Boolean = true) {
        val target = if (expand) keyboardSuggestionStripHeight() else 0
        if (launcherSuggestionStripExpanded == expand && launcherSuggestionStripAnimatedHeight == target) return
        launcherSuggestionStripExpanded = expand
        launcherSuggestionStripAnimator?.cancel()
        val start = launcherSuggestionStripAnimatedHeight.coerceIn(0, keyboardSuggestionStripHeight())
        if (!animate || start == target) {
            launcherSuggestionStripAnimatedHeight = target
            applyLauncherSuggestionStripHeight(target)
            return
        }
        launcherSuggestionStripAnimator = ValueAnimator.ofInt(start, target).apply {
            duration = 170L
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                launcherSuggestionStripAnimatedHeight = it.animatedValue as Int
                applyLauncherSuggestionStripHeight(launcherSuggestionStripAnimatedHeight)
            }
            start()
        }
    }

    private fun applyLauncherSuggestionStripHeight(height: Int) {
        val h = height.coerceIn(0, keyboardSuggestionStripHeight())
        val layoutHeight = launcherSuggestionStripLayoutHeight()
        suggestionStripView?.let { strip ->
            val lp = (strip.layoutParams as? LinearLayout.LayoutParams)
            if (lp != null) {
                lp.height = layoutHeight
                lp.topMargin = if (layoutHeight > 0) dp(2) else 0
                strip.layoutParams = lp
            }
            strip.visibility = if (layoutHeight > 0) View.VISIBLE else View.GONE
            strip.alpha = if (layoutHeight > 0) h / keyboardSuggestionStripHeight().toFloat() else 0f
        }
        if (!launcherSuggestionStripReservesSpace() && ::keyboardDockView.isInitialized && keyboardDockView.parent != null) {
            val lp = keyboardDockView.layoutParams
            if (lp != null && !widgetPaneUsesRootDock()) {
                lp.height = activeRootDockHeight()
                keyboardDockView.layoutParams = lp
            }
        }
        if (!launcherSuggestionStripReservesSpace()) widgetKeyboardHost?.let { host ->
            val lp = host.layoutParams
            if (lp != null && keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET && !widgetPaneUsesRootDock()) {
                lp.height = widgetKeyboardHeight()
                host.layoutParams = lp
            }
        }
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
        val fieldBlank = launcherSuggestionText().isBlank()
        val forceExpanded =
            launcherPolishing ||
                launcherAgenticStatus != null ||
                pendingLauncherResult != null ||
                pendingLauncherCommand != null ||
                pendingLauncherActions.isNotEmpty() ||
                pendingLauncherStarters.isNotEmpty()
        setLauncherSuggestionStripExpanded(!fieldBlank || forceExpanded)
        if (fieldBlank && !forceExpanded) {
            strip.removeAllViews()
            strip.background = null
            return
        }
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
        // Shared-engine working banner (mood/stock/notion/weather in flight).
        val agenticStatus = launcherAgenticStatus
        if (agenticStatus != null) {
            strip.removeAllViews()
            strip.background = suggestionStripBackground(null, true)
            strip.addView(TextView(this).apply {
                text = agenticStatus; gravity = Gravity.CENTER; textSize = 14.5f
                includeFontPadding = false; setTextColor(0xFFCBB4FF.toInt())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            return
        }
        // Shared-engine result HUD: title + body, optional Insert, follow-up chips, close.
        val result = pendingLauncherResult
        if (result != null) {
            strip.removeAllViews()
            strip.background = suggestionStripBackground(null, true)
            strip.addView(TextView(this).apply {
                text = if (result.body.length > 60) result.body.take(58).trimEnd() + "…" else result.body
                gravity = Gravity.CENTER_VERTICAL; textSize = 13.5f; includeFontPadding = false
                setTextColor(activeNeuTokens.ink); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(8), 0, dp(6), 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            result.insert?.let { ins ->
                strip.addView(TextView(this).apply {
                    text = "INSERT"; gravity = Gravity.CENTER; textSize = 10.5f; letterSpacing = 0.1f
                    setTextColor(0xFFCBB4FF.toInt()); setPadding(dp(11), 0, dp(11), 0)
                    background = GradientDrawable().apply { setColor(0x338B5CF6); cornerRadius = dp(9).toFloat() }
                    isClickable = true
                    setOnClickListener {
                        keyHaptic("space"); pendingLauncherResult = null
                        launcherAgenticHost.insertText(ins)
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dp(4) })
            }
            result.followUps.take(2).forEach { fu ->
                strip.addView(TextView(this).apply {
                    text = fu.label; gravity = Gravity.CENTER; textSize = 12f; includeFontPadding = false
                    setTextColor(activeNeuTokens.ink); setPadding(dp(11), 0, dp(11), 0)
                    background = GradientDrawable().apply {
                        setColor((activeNeuTokens.inkFaint and 0x00FFFFFF) or 0x22000000); cornerRadius = dp(9).toFloat()
                        setStroke(dp(1), (activeNeuTokens.inkDim and 0x00FFFFFF) or 0x44000000)
                    }
                    isClickable = true
                    setOnClickListener { keyHaptic("space"); pendingLauncherResult = null; updateSuggestionBar(); fu.run() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dp(4) })
            }
            strip.addView(TextView(this).apply {
                text = "✕"; gravity = Gravity.CENTER; textSize = 14f
                setTextColor(activeNeuTokens.inkDim); setPadding(dp(10), 0, dp(6), 0)
                isClickable = true
                setOnClickListener { keyHaptic("back"); pendingLauncherResult = null; updateSuggestionBar() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dp(2) })
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
        val pendingActs = pendingLauncherActions
        if (pendingActs.isNotEmpty()) {
            strip.removeAllViews()
            strip.background = suggestionStripBackground(null, true)
            pendingActs.forEachIndexed { i, action ->
                strip.addView(TextView(this).apply {
                    text = action.first
                    gravity = Gravity.CENTER; textSize = 14f; includeFontPadding = false
                    setTextColor(activeNeuTokens.ink); maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    isClickable = true
                    setOnClickListener { keyHaptic("space"); runLauncherInlineTransform(action.second) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                if (i < pendingActs.lastIndex) {
                    strip.addView(View(this).apply { setBackgroundColor((activeNeuTokens.inkFaint and 0x00FFFFFF) or 0x30000000) },
                        LinearLayout.LayoutParams(dp(1), dp(16)))
                }
            }
            return
        }
        val pendingStart = pendingLauncherStarters
        if (pendingStart.isNotEmpty()) {
            strip.removeAllViews()
            strip.background = suggestionStripBackground(null, true)
            strip.addView(TextView(this).apply {
                text = "TRY"; gravity = Gravity.CENTER; textSize = 9f; letterSpacing = 0.12f
                includeFontPadding = false; setTextColor(activeNeuTokens.inkDim)
                setPadding(dp(4), 0, dp(6), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            pendingStart.forEach { (label, run) ->
                row.addView(TextView(this).apply {
                    text = label; gravity = Gravity.CENTER; textSize = 12.2f; includeFontPadding = false
                    setTextColor(activeNeuTokens.ink); maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    background = GradientDrawable().apply {
                        setColor((activeNeuTokens.inkFaint and 0x00FFFFFF) or 0x22000000); cornerRadius = dp(10).toFloat()
                        setStroke(dp(1), (activeNeuTokens.inkDim and 0x00FFFFFF) or 0x44000000)
                    }
                    isClickable = true
                    setOnClickListener { keyHaptic("space"); pendingLauncherStarters = emptyList(); run() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(5) })
            }
            strip.addView(android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                setPadding(0, 0, dp(12), 0)
                addView(row)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            return
        }
        var shown = suggestions.take(3)
        val emojiChips = launcherEmojiChips
        // Always keep the strip alive: if there are no word candidates, fall back to next-word
        // predictions for the last completed word so it "always shows what you'll type next".
        if (shown.isEmpty()) {
            val raw = if (openPane?.kind == PaneKind.CHAT) composeText else query
            if (raw.isNotEmpty() && raw.last() == ' ') {
                val lastWord = raw.trim().substringAfterLast(' ')
                if (lastWord.length >= 2) {
                    val next = ngramRepo.cachedNextWords(lastWord)
                    if (next.isEmpty()) ngramRepo.prefetchNextWords(lastWord) else shown = next.take(3)
                }
            }
        }
        val pro = ProManager.isUnlocked(this)
        val canPolish = launcherPolishAvailable()
        val appColor = suggestionStripAppColor(shown)
        // Persist: after a glide / word commit there may be nothing new to show, but keep the last
        // strip content (esp. the swipe result) until the user clears the field or fresh ones arrive.
        if (shown.isEmpty() && emojiChips.isEmpty() && appColor == null && !canPolish && !fieldBlank && strip.childCount > 0) return
        val wasEmpty = strip.childCount == 0
        strip.removeAllViews()
        strip.background = suggestionStripBackground(appColor, pro)
        if (shown.isNotEmpty() || emojiChips.isNotEmpty() || canPolish) {
            // Suggestions live under the persistent typing indicator. The typed text never moves
            // into this row; it stays in typingStripView().
        } else {
            strip.addView(TextView(this).apply {
                text = currentWordInCompose().ifBlank { launcherSuggestionText() }
                gravity = Gravity.CENTER
                textSize = 13f
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(activeNeuTokens.inkDim)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
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
                if (i < shown.lastIndex || emojiChips.isNotEmpty() || canPolish) {
                    strip.addView(View(this).apply { setBackgroundColor((activeNeuTokens.inkFaint and 0x00FFFFFF) or 0x30000000) },
                        LinearLayout.LayoutParams(dp(1), dp(16)))
                }
            }
        }
        emojiChips.forEachIndexed { i, emoji ->
            strip.addView(TextView(this).apply {
                text = emoji
                gravity = Gravity.CENTER
                textSize = 18f
                includeFontPadding = false
                maxLines = 1
                setTextColor(activeNeuTokens.ink)
                setPadding(dp(10), 0, dp(10), 0)
                isClickable = true
                setOnClickListener { insertLauncherEmojiChip(emoji) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (i < emojiChips.lastIndex || canPolish) {
                strip.addView(View(this).apply { setBackgroundColor((activeNeuTokens.inkFaint and 0x00FFFFFF) or 0x30000000) },
                    LinearLayout.LayoutParams(dp(1), dp(16)))
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
        when {
            // Mid-word: strip shows completions/corrections of the partial word → replace it.
            predictionCore.currentWord().isNotEmpty() -> predictionCore.replaceCurrentWord(word)
            // Just swiped: strip shows alternate decodings → replace the committed word in place.
            glideJustCommitted -> predictionCore.replaceCommittedWord(word)
            // Otherwise these are next-word predictions → append.
            else -> predictionCore.replaceCurrentWord(word)   // no partial + not a glide: safe append
        }
        if (pane?.kind == PaneKind.CHAT) {
            updateAutoCapState(); updateKeyLabels()
            renderPaneContent(pane)
        }
        // Learn the pick: record the accepted word against its preceding word so the n-gram ranks
        // what you actually choose, and warm next-word predictions for it.
        val accepted = (if (pane?.kind == PaneKind.CHAT) composeText else query).trim().split(" ").filter { it.isNotEmpty() }
        if (accepted.size >= 2) ngramRepo.recordWord(accepted.last(), accepted[accepted.size - 2])
        ngramRepo.prefetchNextWords(word)
        launcherEmojiTriggerWord = ""
        launcherEmojiChips = emptyList()
        suggestions = emptyList(); updateSuggestionBar()
        renderRibbon()
    }

    private fun computeLauncherEmojiChips(word: String) {
        if (word.length < 2) {
            launcherEmojiTriggerWord = ""
            launcherEmojiChips = emptyList()
            return
        }
        launcherEmojiTriggerWord = word.lowercase(Locale.US)
        launcherEmojiChips = SmartChips.emojiFor(prefs(), launcherEmojiTriggerWord).take(4)
    }

    private fun insertLauncherEmojiChip(emoji: String) {
        val trigger = launcherEmojiTriggerWord
        if (trigger.isBlank()) return
        keyHaptic("space")
        replaceCurrentTriggerWith(emoji, trigger)
        SmartChips.recordEmojiPick(prefs(), trigger, emoji)
        launcherEmojiTriggerWord = ""
        launcherEmojiChips = emptyList()
        suggestions = emptyList()
        updateSuggestionBar()
        openPane?.let {
            if (it.kind == PaneKind.CHAT || it.kind == PaneKind.AI) renderPaneContent(it)
        }
        renderRibbon()
    }

    private fun replaceCurrentTriggerWith(replacement: String, trigger: String) {
        val text = hostText()
        val cursor = (cursorPos ?: text.length).coerceIn(0, text.length)
        val start = (cursor - trigger.length).coerceAtLeast(0)
        val matches = text.substring(start, cursor).equals(trigger, ignoreCase = true)
        val replaceStart = if (matches) start else cursor
        val prefix = text.substring(0, replaceStart)
        val suffix = text.substring(cursor)
        val sep = if (prefix.isNotEmpty() && prefix.last() != ' ') " " else ""
        val next = prefix + sep + replacement + suffix
        val nextCursor = (prefix.length + sep.length + replacement.length).coerceIn(0, next.length)
        setHostText(next)
        cursorPos = if (nextCursor == next.length) null else nextCursor
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

    private fun launcherClipboardText(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return ""
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
    }

    private fun flashLauncherStatus(msg: String, ms: Long) {
        launcherAgenticStatus = msg; updateSuggestionBar()
        handler.postDelayed({ if (launcherAgenticStatus == msg) { launcherAgenticStatus = null; updateSuggestionBar() } }, ms)
    }

    // How the shared AgenticEngine renders into the launcher keyboard — parity with the IME's host.
    // Attach is the one non-portable skill: the launcher edits its own field, so there's no external
    // editor to commitContent a file into.
    private val launcherAgenticHost = object : AgenticHost {
        override val hostContext: Context get() = this@MainActivity
        override fun prefs() = this@MainActivity.prefs()
        override fun clipboardText() = launcherClipboardText()
        override fun isPro() = ProManager.isUnlocked(this@MainActivity)
        override fun isGoogleConnected() = GmailAuth(this@MainActivity).isConnected
        override fun post(action: () -> Unit) { runOnUiThread(action) }
        override fun clearField(consumed: String) {
            if (openPane?.kind == PaneKind.CHAT) { composeText = ""; openPane?.let { renderPaneContent(it) } } else query = ""
            updateAutoCapState(); updateKeyLabels(); render()
        }
        override fun showStatus(msg: String) { launcherAgenticStatus = msg; updateSuggestionBar() }
        override fun flashStatus(msg: String, ms: Long) { flashLauncherStatus(msg, ms) }
        override fun showResult(result: AgenticResult) {
            launcherAgenticStatus = null; pendingLauncherResult = result; updateSuggestionBar()
        }
        override fun insertText(text: String) {
            launcherAgenticStatus = null
            if (openPane?.kind == PaneKind.CHAT) { composeText += text; openPane?.let { renderPaneContent(it) } } else query += text
            updateAutoCapState(); updateKeyLabels(); render()
        }
        override fun runAttach() { flashLauncherStatus("📎 Attach works in the in-app keyboard", 2600) }
        override fun showShareCard(card: ShareCard) {
            // The launcher strip is too small for the full-bleed card; map it onto the result HUD —
            // same beats (preview → insert → follow-up), lower fidelity.
            launcherAgenticStatus = null
            pendingLauncherResult = AgenticResult(
                card.kicker,
                if (card.subtitle.isBlank()) card.title else "${card.title} — ${card.subtitle}",
                card.insertText,
                card.followUp?.let { listOf(it) } ?: emptyList()
            )
            updateSuggestionBar()
        }
    }

    private fun runLauncherAgenticCommand() {
        val raw = launcherCommandText()
        // Clip-skills (mood, stock, notion, attach…) run through the shared engine — identical to the IME.
        if (AgenticEngine.tryClipSkill(launcherAgenticHost, raw)) return
        // Empty field: show discoverable starter chips (Share location + top skills) instead of
        // silently classifying "" into the location command — the "selection of things to do".
        if (raw.isBlank()) {
            val starters = buildLauncherStarters()
            if (starters.isNotEmpty()) { pendingLauncherStarters = starters; updateSuggestionBar(); return }
        }
        val cmd = AgenticRouter.classify(raw)
        if (cmd != null) { pendingLauncherCommand = cmd; updateSuggestionBar(); return }
        val geminiReady = raw.isNotBlank() && ProManager.isUnlocked(this) && geminiConfigured()
        // A real message (not a command): offer inline AI actions — fix, translate — on it.
        if (geminiReady && raw.trim().split(Regex("\\s+")).count { it.isNotEmpty() } >= 3) {
            pendingLauncherActions = buildLauncherMessageActions()
            updateSuggestionBar()
            return
        }
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
        if (cmd.fetchWeather) { AgenticRouter.recordUse(this, cmd.skillId); AgenticEngine.runWeather(launcherAgenticHost, cmd.arg); return }
        if (cmd.insertText != null) {
            if (openPane?.kind == PaneKind.CHAT) composeText = cmd.insertText else query = cmd.insertText
            suggestions = emptyList(); updateAutoCapState(); updateKeyLabels(); render()
            return
        }
        val statusMsg = AgenticRouter.execute(this, cmd)
        if (openPane?.kind == PaneKind.CHAT) composeText = "" else query = ""
        suggestions = emptyList()
        updateAutoCapState(); updateKeyLabels(); render()
        if (statusMsg != null) android.widget.Toast.makeText(this, statusMsg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // Starter chips for the held-go-over-empty-field case (parity with the IME): share verbs that drop
    // a complete thing via a preview, not command verbs that seed text and eject the user.
    private fun buildLauncherStarters(): List<Pair<String, () -> Unit>> {
        val chips = ArrayList<Pair<String, () -> Unit>>()
        chips.add("🎵 Song" to { AgenticEngine.shareSong(launcherAgenticHost) })
        chips.add("📍 My location" to { AgenticEngine.sharePlace(launcherAgenticHost) })
        return chips
    }

    private fun insertLauncherLocation() {
        pendingLauncherStarters = emptyList()
        if (!AgenticLocation.hasPermission(this)) {
            android.widget.Toast.makeText(this, "Enable location for Teclas", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        mediaUiScope.launch {
            val text = withContext(Dispatchers.IO) { AgenticLocation.currentLocationText(this@MainActivity) } ?: return@launch
            if (openPane?.kind == PaneKind.CHAT) { composeText += text; openPane?.let { renderPaneContent(it) } }
            else query += text
            updateKeyLabels(); render()
        }
    }

    private val langNamesLauncher = mapOf("es" to "Spanish", "fr" to "French", "de" to "German", "pt" to "Portuguese", "it" to "Italian", "en" to "English")
    private fun launcherTranslateTarget(): String? {
        val enabled = com.fran.teclas.keyboard.DictionaryLoader.enabledLanguages(this)
        val primary = enabled.firstOrNull() ?: return null
        val other = enabled.firstOrNull { it != primary } ?: return null
        return langNamesLauncher[other]
    }
    private fun buildLauncherMessageActions(): List<Pair<String, String>> {
        val actions = ArrayList<Pair<String, String>>()
        actions.add("✨ Polish" to "Fix the spelling, grammar, capitalization and punctuation of this message. Keep the meaning and tone.")
        launcherTranslateTarget()?.let { actions.add("🌐 → $it" to "Translate this message to $it. Reply with only the translation, nothing else.") }
        return actions
    }
    private fun runLauncherInlineTransform(instruction: String) {
        pendingLauncherActions = emptyList()
        val text = launcherCommandText()
        if (text.trim().length < 2) return
        if (!ProManager.isUnlocked(this) || !geminiConfigured()) return
        val key = prefs().getString(GEMINI_API_KEY_PREF, null)?.trim().orEmpty()
        val model = prefs().getString(GEMINI_MODEL_PREF, GEMINI_DEFAULT_MODEL)?.trim().orEmpty().ifBlank { GEMINI_DEFAULT_MODEL }
        launcherPolishing = true; updateSuggestionBar()
        mediaUiScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { GeminiClient.fetchTransform(key, model, text, instruction) } }.getOrNull()
            launcherPolishing = false
            if (result != null && result != text) {
                if (openPane?.kind == PaneKind.CHAT) composeText = result else query = result
                updateAutoCapState(); updateKeyLabels(); openPane?.let { renderPaneContent(it) }
            }
            updateSuggestionBar(); render()
        }
    }

    private fun scheduleSpellCheck() {
        suggestDebounce?.let { handler.removeCallbacks(it) }
        scheduleLiveAutocorrect()
        val word = currentWordInCompose()
        if (word.length < 2) {
            computeLauncherEmojiChips("")
            suggestions = emptyList(); updateSuggestionBar()   // strip falls back to next-word preds
            liveRouter.onTextChanged("")
            return
        }
        liveRouter.onTextChanged(word)
        computeLauncherEmojiChips(word)
        val prev = previousWordInCompose()
        val r = Runnable {
            lastSuggestWord = word
            suggestions = predictionCore.computeSuggestions()   // shared candidate computation
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
    // Autocorrect now runs through the shared AutocorrectCore. `live` (mid-typing) rewriting stays
    // disabled — correct only on space/punctuation; the core commits the corrected word (no trailing
    // space) and the space handler adds the space, matching the previous behavior.
    private fun tryAutocorrect(live: Boolean = false) {
        if (live) return
        autocorrectCore.correctBeforeCommit()
    }

    private var liveCorrectDebounce: Runnable? = null

    // Silent, no-space autocorrect: after a brief pause, if the current word is a finished typo
    // (a dead-end that can't extend into any real word) with a confident fix, rewrite it in place.
    // The dead-end guard in tryAutocorrect(live=true) is what makes this safe mid-sentence.
    private fun scheduleLiveAutocorrect() {
        // Disabled: rewriting a word mid-typing (before you press space) fights the user — it changes
        // letters while you're still on the word and re-triggers when you go back to retype. Like
        // Gboard, we now only correct on space/punctuation, and surface candidates in the strip so
        // YOU choose. The on-space path (tryAutocorrect) still runs and is undoable via backspace.
        liveCorrectDebounce?.let { handler.removeCallbacks(it) }
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

    // Delegates to the shared GeminiClient so launcher and IME smart-compose stay in parity.
    private fun fetchGeminiCompletion(context: String): String {
        val key = prefs().getString(GEMINI_API_KEY_PREF, null)?.trim().orEmpty()
        if (key.isBlank()) return ""
        val model = prefs().getString(GEMINI_MODEL_PREF, GEMINI_DEFAULT_MODEL)?.trim().orEmpty().ifBlank { GEMINI_DEFAULT_MODEL }
        return GeminiClient.fetchCompose(key, model, context) ?: ""
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
            val label = c.toString()
            val shown = if (upper) c.uppercaseChar().toString() else c.lowercaseChar().toString()
            val key = keyViews[label]
            if (key is DynamicFlickKeyView) {
                if (keyboardTheme == KEYBOARD_THEME_BRUSHED) {
                    key.setLabelPlacement(
                        labelBias = 0.28f,
                        symbolBias = 0.04f,
                        labelMaxScale = 0.52f,
                        symbolScale = 0.16f,
                        extraBottomInsetPx = 0,
                        engravedSymbols = true
                    )
                    val brushedShown = if (shiftState == ShiftState.LOCK) label.uppercase(Locale.US) else label.lowercase(Locale.US)
                    key.setDrawnPrimaryLabel(brushedShown, keyTextColor(label), keyTextSize(label) * resources.displayMetrics.scaledDensity, key.typeface)
                    return@forEach
                }
                key.setDrawnPrimaryLabel(
                    shown,
                    keyTextColor(label),
                    keyTextSize(label) * resources.displayMetrics.scaledDensity,
                    key.typeface
                )
            } else {
                key?.text = shown
            }
        }
        keyViews["shift"]?.setTextColor(when (shiftState) {
            ShiftState.OFF -> 0xFF7D8078.toInt()
            ShiftState.ONCE -> Ink
            ShiftState.LOCK -> if (keyboardTheme == KEYBOARD_THEME_BRUSHED) 0xFFFFFFFF.toInt() else Accent
        })
        keyViews["shift"]?.let { shiftKey ->
            shiftKey.text = when (shiftState) {
                ShiftState.LOCK -> "⇪"
                ShiftState.ONCE -> "⇧"
                ShiftState.OFF -> "↑"
            }
            if (keyboardTheme == KEYBOARD_THEME_BRUSHED) {
                val inset = keyVerticalInset()
                shiftKey.background = android.graphics.drawable.InsetDrawable(
                    keyIdleBackground("shift"),
                    keyHorizontalInset(),
                    inset,
                    keyHorizontalInset(),
                    inset
                )
            }
        }
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
                // Adaptive dictionary (parity with the IME): primary language active by default, with
                // the phone's secondary bundled languages as a latent extended set — so multi-language
                // typing works but a secondary language never silently rewrites the primary one.
                val adaptive = com.fran.teclas.keyboard.DictionaryLoader.loadAdaptive(this@MainActivity)
                // Fold accepted-glide personal frequencies in so both decoders learn what you swipe (L3).
                val personal = glideLearning.personalFrequencyBoost()
                val freqs = if (personal.isEmpty()) adaptive.extendedFreqs else HashMap<String, Float>(adaptive.extendedFreqs).apply {
                    for ((w, b) in personal) put(w, minOf(1f, (this[w] ?: 0f) + b))
                }
                val clf = StatisticalGlideTypingClassifier()
                clf.setWordData(adaptive.extendedWords, freqs)
                // Make glide available immediately with the statistical classifier; the heavy neural
                // ONNX load must not block it (that stalls glide for seconds on a real device).
                launch(Dispatchers.Main) {
                    glideClassifier = clf
                    wordlistFrequencies = freqs
                    predictionEngine = PredictionEngine(freqs)
                    predictionEnginePrimary = PredictionEngine(adaptive.primaryFreqs)
                    hasLatentLanguages = adaptive.latentLangs.isNotEmpty()
                    latentLanguageActive = false
                    updateGlideLayout()
                }
                // Legacy encoder-only decoder (kept for A/B) + the new encoder-decoder engine load
                // off the critical path and join the hybrid when ready.
                val neural = com.fran.teclas.keyboard.NeuralSwipeEngine(this@MainActivity).also { it.tryLoad() }
                val neuralV2 = com.fran.teclas.keyboard.neural.NeuralGlideEngine(this@MainActivity).apply {
                    setDictionary(adaptive.extendedWords, freqs)
                    load()
                }
                android.util.Log.d("NeuralSwipe", "Widget neural engine ready=${neuralV2.isReady} personal=${personal.size}")
                launch(Dispatchers.Main) {
                    neuralSwipe = neural.takeIf { it.isReady }
                    neuralGlideV2 = neuralV2
                    hybridDecoderV2 = com.fran.teclas.keyboard.neural.HybridGlideDecoder(neuralV2)
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
    private val tapResolver by lazy { com.fran.teclas.keyboard.TapResolver(spatialScorer) }

    // Now uses the shared resolver — including the IME's near-edge gate, so a confident center tap
    // is never re-assigned (fixes "right key, wrong letter").
    private fun resolveTapKey(label: String, rawX: Float, rawY: Float): String = tapResolver.resolve(
        label, rawX, rawY, keyBounds, smartEnabled = true,
        nextCharWeights = { predictionEngine.nextCharWeights(currentWordInCompose()) },
        fallback = { x, y -> keyAtPoint(x, y) }
    )

    private fun keyAtPoint(rawX: Float, rawY: Float): String? {
        if (keyBounds.isEmpty()) {
            val x = rawX.toInt(); val y = rawY.toInt()
            return keyBounds.entries.firstOrNull { (_, r) -> r.contains(x, y) }?.key
        }
        return spatialScorer.bestKey(rawX, rawY)
    }

    // Glide context/rerank/placement now live in the shared GlideCore.

    // Screen-space bounding box [left, top, width, height] of the letter keys — used to normalize a
    // glide path into [0,1] so the neural decoder is independent of docked vs. widget size.
    private fun letterKeyBounds(): FloatArray? {
        val rects = keyBounds.filterKeys { it.length == 1 && it[0].isLetter() }.values
        if (rects.isEmpty()) return null
        val left = rects.minOf { it.left }.toFloat()
        val top = rects.minOf { it.top }.toFloat()
        val right = rects.maxOf { it.right }.toFloat()
        val bottom = rects.maxOf { it.bottom }.toFloat()
        return floatArrayOf(left, top, right - left, bottom - top)
    }

    // Letter-key centers (screen space) for the neural decoder's nearest-key feature.
    private fun letterKeyCenters(): List<KeyInfo> =
        keyBounds.mapNotNull { (label, rect) ->
            if (label.length != 1 || !label[0].isLetter()) return@mapNotNull null
            KeyInfo(label[0], rect.exactCenterX(), rect.exactCenterY(), rect.width().toFloat(), rect.height().toFloat())
        }

    /** Parity with the IME: prefer active-language words so a swipe doesn't resolve to a
     *  similarly-shaped secondary-language word (e.g. English -> Spanish). See TeclasImeService. */
    private fun languagePreferredOrder(words: List<String>): List<String> {
        if (words.size < 2 || latentLanguageActive || !hasLatentLanguages) return words
        val primary = words.filter { predictionEnginePrimary.isDictWord(it) }
        return if (primary.isEmpty() || primary.size == words.size) words
        else primary + words.filterNot { predictionEnginePrimary.isDictWord(it) }
    }

    private fun handleGlideResult(rawResults: List<String>) {
        val results = languagePreferredOrder(rawResults)
        if (hapticsEnabled) hapticEngine.glideCommit() else haptic(contentFrame)
        val pane = openPane
        val topWord = glideCore.rerank(results)         // shared context rerank
        glideCore.commitWord(topWord)                   // shared append-vs-replace placement
        if (pane?.kind == PaneKind.CHAT) { updateAutoCapState(); updateKeyLabels(); renderPaneContent(pane) }
        // Learn from the glide: record the committed word against its predecessor so the n-gram and
        // context re-ranking keep improving as you swipe.
        val toks = (if (pane?.kind == PaneKind.CHAT) composeText else query).trim().split(" ").filter { it.isNotEmpty() }
        if (toks.size >= 2) ngramRepo.recordWord(toks.last(), toks[toks.size - 2])
        ngramRepo.prefetchNextWords(topWord)
        suggestions = (listOf(topWord) + results.filter { it != topWord }).distinct().take(3)
        glideJustCommitted = true   // strip now shows tap-to-correct alternatives
        updateSuggestionBar()
        renderRibbon()
    }

    // A glide the decoder couldn't turn into a word must NOT dump the raw traced letters into the
    // field (the "swipe gives a bunch of letters" bug). Drop it — a lost gesture beats garbage.
    private fun handleSwipeFallback(keys: List<String>) {
        // intentionally a no-op — never commit the raw key trace
    }

    private fun glideTrailColors(): IntArray {
        glideRecognizedColor?.let { c ->
            return intArrayOf(adjustAlpha(c, 0x20), brighten(c), c, adjustAlpha(c, 0x22))
        }
        val core = when (keyboardTheme) {
            KEYBOARD_THEME_GOKEYS -> 0xFFF2691E.toInt()
            KEYBOARD_THEME_TECLAS -> Accent2
            KEYBOARD_THEME_SKEUO -> 0xFF8FD694.toInt()
            KEYBOARD_THEME_HYPER3D -> 0xFF7EA2FF.toInt()
            KEYBOARD_THEME_HYPER3D_BLACK -> 0xFFFF6B6B.toInt()
            KEYBOARD_THEME_HYPER3D_LIGHT -> 0xFF4E6FE7.toInt()
            KEYBOARD_THEME_BRUSHED -> if (activeNeuTokens.mode == NeuMode.LIGHT) 0xFF9FA7B2.toInt() else 0xFFC9CED6.toInt()
            KEYBOARD_THEME_SEEME -> 0xFFD71921.toInt()
            else -> goKeyColor
        }
        val tail = when (keyboardTheme) {
            KEYBOARD_THEME_HYPER3D_LIGHT -> 0xFF8FD6FF.toInt()
            KEYBOARD_THEME_HYPER3D_BLACK -> 0xFF9DB4FF.toInt()
            KEYBOARD_THEME_TECLAS -> 0xFFFF8A68.toInt()
            else -> brighten(core)
        }
        return intArrayOf(adjustAlpha(core, 0x20), tail, core, adjustAlpha(core, 0x22))
    }

    inner class SwipeKeyboardLayout(context: Context) : LinearLayout(context) {
        private var startRawX = 0f
        private var startRawY = 0f
        // Glide must clear this much travel before it steals the touch from a key tap. Matching the
        // IME (touchSlop*2, min dp(20)) keeps a slightly-imperfect tap from becoming a ghost swipe.
        private val glideStart = maxOf(
            android.view.ViewConfiguration.get(context).scaledTouchSlop * 2, dp(20)
        )
        private var tracking = false
        private val traced = mutableListOf<String>()
        private val trailLocal = mutableListOf<Pair<Float, Float>>()
        // Event timestamps parallel to trailLocal — for the neural decoder's velocity/accel features.
        private val trailTimes = mutableListOf<Long>()
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
            val r = Runnable { glidePersisting = false; trailLocal.clear(); trailTimes.clear(); glideRecognizedColor = null; invalidate() }
            glideFadeRunnable = r
            handler.postDelayed(r, 900)
        }

        private fun clearGlideTouchState() {
            tracking = false
            traced.clear()
            trailLocal.clear()
            trailTimes.clear()
            trackpadActive = false
            glideClassifier?.clear()
            invalidate()
        }

        /** Snapshot the raw screen-space path (with timestamps) for the neural decoder. */
        fun snapshotTimedPath(): List<TimedPoint> {
            val n = minOf(trailLocal.size, trailTimes.size)
            val out = ArrayList<TimedPoint>(n)
            for (i in 0 until n) {
                out.add(TimedPoint(trailLocal[i].first + screenX, trailLocal[i].second + screenY, trailTimes[i]))
            }
            return out
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
            // Intercept as soon as a second finger lands so we can drive the two-finger trackpad
            // (horizontal = cursor pan in any mode, vertical = scroll library results).
            if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN && ev.pointerCount == 2) {
                trackpadLastY = (ev.getY(0) + ev.getY(1)) / 2f
                trackpadLastX = (ev.getX(0) + ev.getX(1)) / 2f
                trackpadStartY = trackpadLastY
                trackpadStartX = trackpadLastX
                trackpadSliderTriggered = false
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
                    tracking = false; traced.clear(); trailLocal.clear(); trailTimes.clear()
                    val loc = IntArray(2); getLocationOnScreen(loc)
                    screenX = loc[0].toFloat(); screenY = loc[1].toFloat()
                    trackpadActive = false
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (trackpadActive) return true
                    if (!tracking) {
                        if (abs(ev.rawX - startRawX) > glideStart || abs(ev.rawY - startRawY) > glideStart) {
                            tracking = true
                            glideGestureActive = true
                            if (hapticsEnabled) hapticEngine.glideStart()   // firm click on glide activation
                            keyAtPoint(startRawX, startRawY)?.let { k -> if (k.length == 1) traced.add(k) }
                            trailLocal.add(startRawX - screenX to startRawY - screenY)
                            trailTimes.add(ev.eventTime)
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
        private var trackpadLastX = 0f
        private var trackpadStartY = 0f
        private var trackpadStartX = 0f
        private var trackpadSliderTriggered = false
        private var trackpadActive = false
        private val cursorPanStep = dp(13).toFloat()

        // Two-finger trackpad on the keyboard: horizontal drag steps the text cursor (any mode),
        // vertical drag scrolls the library results when it's open.
        private fun trackpad(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (ev.pointerCount == 2) {
                        trackpadLastY = (ev.getY(0) + ev.getY(1)) / 2f
                        trackpadLastX = (ev.getX(0) + ev.getX(1)) / 2f
                        trackpadStartY = trackpadLastY
                        trackpadStartX = trackpadLastX
                        trackpadSliderTriggered = false
                        trackpadActive = true
                        tracking = false; traced.clear(); trailLocal.clear(); invalidate()
                        glideClassifier?.clear()
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!trackpadActive || ev.pointerCount < 2) return false
                    val midX = (ev.getX(0) + ev.getX(1)) / 2f
                    val midY = (ev.getY(0) + ev.getY(1)) / 2f
                    val totalDx = midX - trackpadStartX
                    val totalDy = midY - trackpadStartY
                    if (!trackpadSliderTriggered &&
                        widgetKeyboardSliderAvailable() &&
                        widgetSwapState == WidgetKeyboardSwapState.SEATED &&
                        abs(totalDy) > dp(48) &&
                        abs(totalDy) > abs(totalDx) * 1.25f
                    ) {
                        trackpadSliderTriggered = true
                        if (totalDy > 0f) hideWidgetKeyboardSlider() else showWidgetKeyboardSlider()
                        return true
                    }
                    if (trackpadSliderTriggered) return true
                    var dx = midX - trackpadLastX
                    while (abs(dx) >= cursorPanStep) {
                        val right = dx > 0
                        moveLauncherCursor(right)
                        trackpadLastX += if (right) cursorPanStep else -cursorPanStep
                        dx = midX - trackpadLastX
                    }
                    if (libraryOpen) {
                        val dy = (trackpadLastY - midY) * 2.4f
                        (libraryContentArea?.getChildAt(0) as? ScrollView)?.scrollBy(0, dy.toInt())
                    }
                    trackpadLastY = midY
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (trackpadActive) {
                        trackpadActive = false
                        trackpadSliderTriggered = false
                        return true
                    }
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
            if (trackpad(ev)) return true
            if (!tracking) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val k = keyAtPoint(ev.rawX, ev.rawY)
                    if (k != null && k.length == 1 && (traced.isEmpty() || traced.last() != k)) traced.add(k)
                    trailLocal.add(ev.rawX - screenX to ev.rawY - screenY)
                    trailTimes.add(ev.eventTime)
                    glideClassifier?.addGesturePoint(ev.rawX, ev.rawY)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    glideGestureActive = false
                    val clf = glideClassifier
                    // Swipe-DOWN on a key = insert its symbol (Apple-style; the symbol shown at the
                    // key's bottom edge). Down doesn't collide with swipe-up-to-accept-prediction.
                    val dyDn = ev.rawY - startRawY; val dxDn = ev.rawX - startRawX
                    val dnKey = keyAtPoint(startRawX, startRawY)
                    val dnSym = if (dnKey != null && dnKey.length == 1) com.fran.teclas.keyboard.KeyboardSymbols.keyUp[dnKey] else null
                    if (dnSym != null && dyDn > dp(24) && dyDn >= abs(dxDn) * 1.4f && abs(dxDn) < dp(56) &&
                        (clf == null || !clf.hasEnoughPoints)) {
                        tracking = false; traced.clear(); trailLocal.clear(); glidePersisting = false; invalidate()
                        clf?.clear()
                        if (hapticsEnabled) hapticEngine.symbolFlick()
                        handleKey(dnSym)
                        return true
                    }
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
                    // Anti-ghost-swipe: a real glide crosses ≥2 distinct keys. A wiggle that stayed on
                    // one key must never decode to a word — drop it silently rather than type garbage.
                    if (clf != null && clf.hasEnoughPoints && t.size >= 2) {
                        val contextBoost = glideCore.contextBoost()
                        // Snapshot inputs for the hybrid decoder (neural + statistical fusion, shared
                        // with the IME). Falls back to statistical-only when no neural model is present.
                        val hybrid = hybridDecoderV2
                        val pathV2 = snapshotTimedPath()
                        val bounds = letterKeyBounds()
                        val centers = letterKeyCenters()
                        val freqs = wordlistFrequencies
                        // One coroutine on the UI scope; heavy work runs off-main. try/finally guarantees
                        // the trail is always cleaned up even if a decoder throws.
                        mediaUiScope.launch {
                            var res: com.fran.teclas.keyboard.neural.HybridResult? = null
                            try {
                                val statistical: suspend () -> List<String> = {
                                    kotlinx.coroutines.withContext(Dispatchers.Default) {
                                        try { clf.getSuggestions(3, contextBoost) } catch (_: Throwable) { emptyList() }
                                    }
                                }
                                res = if (hybrid != null) {
                                    hybrid.decode(pathV2, bounds, centers, freqs, 3, statistical)
                                } else {
                                    val s = statistical()
                                    com.fran.teclas.keyboard.neural.HybridResult(
                                        s, emptyList(), s, false, false,
                                        if (s.isEmpty()) com.fran.teclas.keyboard.neural.GlideSource.NONE
                                        else com.fran.teclas.keyboard.neural.GlideSource.STATISTICAL
                                    )
                                }
                            } finally {
                                runCatching { clf.clear() }
                                fadeGlideTrail()   // schedule the clear FIRST so a later throw can't strand the trail
                                val results = res?.words ?: emptyList()
                                if (results.isNotEmpty()) {
                                    val top = glideCore.rerank(results)
                                    handleGlideResult(results)
                                    glideRecognizedColor = suggestionStripAppColor(results)  // tint trail to app
                                    invalidate()
                                    res?.let { glideLearning.recordAcceptance(top, pathV2, bounds, it.source, it.agreed) }
                                } else if (t.size >= 3) handleSwipeFallback(t)
                            }
                        }
                    } else {
                        clf?.clear()
                        if (t.size >= 3) handleSwipeFallback(t)
                        fadeGlideTrail()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    tracking = false; glideGestureActive = false; traced.clear(); trailLocal.clear(); trailTimes.clear()
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
            val wasFlinging = flinging
            ringVelocity = 0.0
            ringAccum = 0.0
            if (!wasFlinging) return
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

    // ── Transient volume HUD (iPod-style) shown while the wheel adjusts volume ─

    internal fun showVolumeHud() {
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
            if (label == "teclas" || label == "back") closeCategoryFolder()
            return
        }
        pendingLauncherCommand = null
        pendingLauncherActions = emptyList()
        pendingLauncherStarters = emptyList()
        pendingLauncherResult = null
        glideJustCommitted = false   // leaving the post-swipe "tap an alternative" window
        val pane = openPane
        if (pane?.kind == PaneKind.CHAT) { handleChatKey(label, pane); return }
        if (pane?.kind == PaneKind.AI) { handleAiKey(label, pane); return }

        when (label) {
            "back" -> {
                // Undo autocorrect via the shared core (restore original + remember rejection).
                if (!libraryOpen && autocorrectCore.undoOnBackspace()) { renderRibbon(); return }
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
                scheduleSpellCheck()   // keep the strip in sync on backspace, docked or widget
            }
            "space" -> {
                autocorrectCore.clearPending()
                if (libraryOpen) {
                    if (query.isNotBlank()) query += " "
                } else {
                    cursorPos?.let { pos ->
                        query = query.substring(0, pos) + " " + query.substring(pos)
                        cursorPos = pos + 1
                        suggestions = emptyList()
                        updateSuggestionBar()
                        renderRibbon()
                        return
                    }
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
                autocorrectCore.clearPending()
                if (libraryOpen) { if (query.isNotBlank()) query += "." }
                else {
                    cursorPos?.let { pos ->
                        query = query.substring(0, pos) + "." + query.substring(pos)
                        cursorPos = pos + 1
                        suggestions = emptyList()
                        updateSuggestionBar()
                        renderRibbon()
                        return
                    }
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
            "teclas" -> {
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
                if (isUnfoldedInnerLayoutActive() && openPane == null) {
                    if (executeTypeToDoCommand(query)) {
                        query = ""
                        refreshUnfoldedFocusContent()
                        renderRibbon()
                        return
                    }
                    val result = bestLauncherResultForGo()
                    if (result != null) openSearchResult(result) else launchInAppGoogleSearch(query)
                    return
                }
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
                autocorrectCore.clearPending()
                if (shiftState == ShiftState.ONCE) { shiftState = ShiftState.OFF; updateKeyLabels() }
                if (!isUnfoldedInnerLayoutActive() && !libraryOpen && openPane == null && keyboardPlacement == KEYBOARD_PLACEMENT_DOCKED) {
                    // Auto-open library in docked mode so search shows full-screen results.
                    // Widget mode keeps the homescreen intact while typing.
                    libraryOpen = true
                    keyboardSettingsOpen = false
                    showLibrary(animate = true)
                    syncNowPlayingCardVisibility()
                    refreshNowPlayingCard()
                }
                // Feed the suggestion strip on every keystroke — docked (library open) and widget
                // search alike — so typed words get completions/corrections just like swipe does.
                scheduleSpellCheck()
                scheduleGeminiSuggestions()
            }
        }
        if (libraryOpen && !isUnfoldedInnerLayoutActive()) scheduleLibraryRefresh()
        if (isUnfoldedInnerLayoutActive() && openPane == null) refreshUnfoldedFocusContent()
        renderRibbon()
    }

    private fun handleAiKey(label: String, pane: PaneTarget) {
        when (label) {
            "back" -> {
                autocorrectCore.clearPending()
                val pos = cursorPos
                if (pos != null && pos > 0) {
                    aiDraftText = aiDraftText.removeRange(pos - 1, pos)
                    cursorPos = pos - 1
                } else {
                    cursorPos = null
                    aiDraftText = aiDraftText.dropLast(1)
                }
                aiDraftActive = true
            }
            "space" -> {
                aiDraftActive = true
                cursorPos?.let { pos ->
                    aiDraftText = aiDraftText.substring(0, pos) + " " + aiDraftText.substring(pos)
                    cursorPos = pos + 1
                    return@let
                } ?: run {
                val now = System.currentTimeMillis()
                if (now - lastSpaceMs < 500 && aiDraftText.isNotEmpty() && aiDraftText.last() == ' ') {
                    aiDraftText = aiDraftText.dropLast(1) + ". "
                    shiftState = ShiftState.ONCE; updateKeyLabels()
                } else if (!aiDraftText.endsWith(" ")) {
                    aiDraftText += " "
                }
                lastSpaceMs = now
                }
            }
            "period" -> {
                aiDraftActive = true
                cursorPos?.let { pos ->
                    aiDraftText = aiDraftText.substring(0, pos) + "." + aiDraftText.substring(pos)
                    cursorPos = pos + 1
                } ?: run {
                    aiDraftText = aiDraftText.trimEnd() + "."
                }
                shiftState = ShiftState.ONCE; updateKeyLabels()
            }
            "123" -> { numberPadOpen = true; ensureContactsPermission(); render(); return }
            "abc" -> {
                if (symbolsOpen) { symbolsOpen = false; render(); return }
                numberPadOpen = false; render(); return
            }
            "teclas" -> {
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
                    cursorPos = null
                    query = ""
                    askGemini(prompt)
                    return
                }
            }
            else -> {
                val char = if (shiftState != ShiftState.OFF) label.uppercase(Locale.US) else label
                val pos = cursorPos
                if (pos != null) {
                    aiDraftText = aiDraftText.substring(0, pos) + char + aiDraftText.substring(pos)
                    cursorPos = pos + 1
                } else {
                    aiDraftText += char
                }
                aiDraftActive = true
                autocorrectCore.clearPending()
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
        scheduleMusicSearch()
        scheduleEmailSearch()
        scheduleFileSearch()
    }

    /** Debounced in-launcher Spotify search. Populates [spotifyQuickResults] for the current query so
     *  universalSearchResults() can surface playable tracks, then re-renders the library. */
    private fun scheduleMusicSearch() {
        musicSearchDebounce?.let { handler.removeCallbacks(it) }
        val q = query.trim()
        if (q.length < 2 || !spotifyAuth.isConnected) {
            if (spotifyQuickResults.isNotEmpty()) { spotifyQuickResults = emptyList(); spotifyQuickQuery = "" }
            return
        }
        if (q == spotifyQuickQuery) return   // already have results for this query
        val r = Runnable {
            musicSearchDebounce = null
            mediaUiScope.launch {
                val tracks = withContext(Dispatchers.IO) { runCatching { spotifyApi.search(q, limit = 3) }.getOrDefault(emptyList()) }
                if (query.trim() != q) return@launch   // query moved on
                spotifyQuickQuery = q
                spotifyQuickResults = tracks.map { track ->
                    SearchResult(
                        title = track.name,
                        subtitle = "▶ ${track.artist}",
                        accent = 0xFF1DB954.toInt(),   // Spotify green
                        kind = SearchKind.MUSIC,
                        target = null
                    ) { playSpotifyTrackFromSearch(track) }
                }
                if (libraryOpen) refreshLibraryContent()
            }
        }
        musicSearchDebounce = r
        handler.postDelayed(r, 320L)
    }

    private fun scheduleEmailSearch() {
        gmailSearchDebounce?.let { handler.removeCallbacks(it) }
        val q = query.trim()
        if (q.length < 3 || !gmailAuth.isConnected) {
            if (gmailQuickResults.isNotEmpty()) { gmailQuickResults = emptyList(); gmailQuickQuery = "" }
            return
        }
        if (q == gmailQuickQuery) return
        val r = Runnable {
            gmailSearchDebounce = null
            mediaUiScope.launch {
                val messages = withContext(Dispatchers.IO) {
                    runCatching {
                        gmailApi.search(q, maxResults = 4)
                            .take(4)
                            .mapNotNull { gmailApi.message(it) }
                    }.getOrDefault(emptyList())
                }
                if (query.trim() != q) return@launch
                gmailQuickQuery = q
                gmailQuickResults = messages.map { msg ->
                    SearchResult(
                        title = msg.subject.ifBlank { travelPaneHost.travelFrom(msg.from).ifBlank { "Email" } },
                        subtitle = listOf(travelPaneHost.travelFrom(msg.from), msg.snippet)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                            .take(120),
                        accent = 0xFFF5C451.toInt(),
                        kind = SearchKind.EMAIL,
                        target = null
                    ) { travelPaneHost.openEmail(msg) }
                }
                if (libraryOpen) refreshLibraryContent()
            }
        }
        gmailSearchDebounce = r
        handler.postDelayed(r, 420L)
    }

    private fun scheduleFileSearch() {
        fileSearchDebounce?.let { handler.removeCallbacks(it) }
        val q = query.trim()
        if (q.length < 3) {
            if (fileQuickResults.isNotEmpty()) { fileQuickResults = emptyList(); fileQuickQuery = "" }
            return
        }
        if (q == fileQuickQuery) return
        val r = Runnable {
            fileSearchDebounce = null
            mediaUiScope.launch {
                val files = withContext(Dispatchers.IO) { searchLocalFiles(q, limit = 5) }
                if (query.trim() != q) return@launch
                fileQuickQuery = q
                fileQuickResults = files.map { file ->
                    SearchResult(
                        title = file.name,
                        subtitle = file.mimeType?.takeIf { it.isNotBlank() } ?: "File",
                        accent = Neu.BLUE,
                        kind = SearchKind.FILE,
                        target = null
                    ) { openLocalFile(file) }
                }
                if (libraryOpen) refreshLibraryContent()
            }
        }
        fileSearchDebounce = r
        handler.postDelayed(r, 360L)
    }

    private fun playSpotifyTrackFromSearch(track: SpotifyTrack) {
        mediaUiScope.launch {
            val started = withContext(Dispatchers.IO) {
                runCatching { spotifyApi.playTrack(track.uri) }.getOrDefault(false)
            }
            if (started) {
                Toast.makeText(this@MainActivity, "Playing ${track.name}", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val spotifyIntent = Intent(Intent.ACTION_VIEW, Uri.parse(track.uri)).apply {
                setPackage("com.spotify.music")
            }
            val opened = runCatching {
                startActivity(spotifyIntent)
                true
            }.getOrDefault(false)
            if (!opened) {
                val webUrl = if (track.id.startsWith("spotify:")) {
                    "https://open.spotify.com/search/${Uri.encode(track.name + " " + track.artist)}"
                } else {
                    "https://open.spotify.com/track/${Uri.encode(track.id)}"
                }
                startSafeIntent(Intent(Intent.ACTION_VIEW, Uri.parse(webUrl)), "Spotify can't play this track here")
            }
        }
    }

    private fun handleChatKey(label: String, pane: PaneTarget) {
        when (label) {
            "back" -> {
                if (autocorrectCore.undoOnBackspace()) { updateAutoCapState(); updateKeyLabels(); return }
                val pos = cursorPos
                if (pos != null && pos > 0) {
                    composeText = composeText.removeRange(pos - 1, pos)
                    cursorPos = pos - 1
                } else {
                    cursorPos = null
                    composeText = composeText.dropLast(1)
                }
                updateAutoCapState(); updateKeyLabels(); scheduleSpellCheck()
            }
            "space" -> {
                autocorrectCore.clearPending()
                if (maybeRunLauncherAiCommand()) return
                cursorPos?.let { pos ->
                    composeText = composeText.substring(0, pos) + " " + composeText.substring(pos)
                    cursorPos = pos + 1
                    suggestions = emptyList(); updateSuggestionBar()
                    updateAutoCapState(); updateKeyLabels()
                    renderPaneContent(pane); renderRibbon()
                    return
                }
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
                autocorrectCore.clearPending()
                cursorPos?.let { pos ->
                    composeText = composeText.substring(0, pos) + "." + composeText.substring(pos)
                    cursorPos = pos + 1
                    shiftState = ShiftState.ONCE; updateKeyLabels()
                    suggestions = emptyList(); updateSuggestionBar()
                    renderPaneContent(pane); renderRibbon()
                    return
                }
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
            "teclas" -> {
                if (keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET) setKeyboardPlacement(KEYBOARD_PLACEMENT_DOCKED)
                else if (openPane != null) { keyboardSettingsOpen = false; refreshKeyboardDock() }
                else { keyboardSettingsOpen = !keyboardSettingsOpen; render() }
                return
            }
            "enter" -> postComposeBubble(pane)
            else -> {
                autocorrectCore.clearPending()
                val char = if (shiftState != ShiftState.OFF) label.uppercase(Locale.US) else label
                val pos = cursorPos
                if (pos != null) {
                    composeText = composeText.substring(0, pos) + char + composeText.substring(pos)
                    cursorPos = pos + 1
                } else {
                    composeText += char
                }
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
        if (!target.id.startsWith("reply:")) replyingToKey = null
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
        syncSystemBars()
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
        replyingToKey = null
        shiftState = ShiftState.OFF; suggestions = emptyList()
        if (closingPane?.kind == PaneKind.PHOTOS) zeissButtonView?.animateShutterOpen()
        if (closingPane?.kind == PaneKind.MUSIC) hideMusicProgressFromHintBar()
        updateKeyLabels(); updateSuggestionBar(); renderRibbon(); refreshKeyboardDock(); syncNowPlayingCardVisibility(); refreshNowPlayingCard()
        syncSystemBars()
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
                    "Add a **Gemini API key** in Teclas Settings to ask questions without leaving the launcher."
                }
                TeclasAiQueryFlow(
                    query = displayedQuery,
                    answer = answer,
                    glassEffects = glassEffectsEnabled(),
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
                    renderPaneContent(teclasSettingsTarget())
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
            renderPaneContent(teclasSettingsTarget())
        }, LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
        parent.addView(settingAction("SPACES →") {
            haptic(this)
            startActivity(Intent(this@MainActivity, SpacesSettingsActivity::class.java))
        }, LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
        parent.addView(settingToggle("APP LIBRARY HOME", appLibraryDefaultHome()) {
            val next = !appLibraryDefaultHome()
            prefs().edit().putBoolean(APP_LIBRARY_DEFAULT_HOME_PREF, next).apply()
            haptic(this)
            // Rebuild library content so the home strip (fave dock + often-used) appears/disappears.
            libraryContentReady = false
            libraryViewDirty = true
            Toast.makeText(
                this@MainActivity,
                if (next) "App Library will open as home" else "Standard home restored",
                Toast.LENGTH_SHORT
            ).show()
            renderPaneContent(teclasSettingsTarget())
        }, LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
        parent.addView(settingToggle("ANIMATED WEATHER", animatedWeatherEnabled()) {
            val next = !animatedWeatherEnabled()
            prefs().edit().putBoolean(ANIMATED_WEATHER_PREF, next).apply()
            haptic(this)
            if (::weatherIconView.isInitialized) weatherIconView.setAnimationEnabled(next)
            renderPaneContent(teclasSettingsTarget())
        }, LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
        parent.addView(settingToggle("GLASS EFFECTS", glassEffectsEnabled()) {
            val next = !glassEffectsEnabled()
            prefs().edit().putBoolean(GLASS_EFFECTS_PREF, next).apply()
            haptic(this)
            libraryView?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
            libraryView = null
            libraryContentArea = null
            libraryViewMode = null
            libraryViewGlass = null
            libraryViewDirty = true
            libraryContentReady = false
            render()
            openHere(teclasSettingsTarget())
        }, LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
        parent.addView(settingToggle("GRID WORKSPACE LAB", gridWorkspaceLabEnabled()) {
            val next = !gridWorkspaceLabEnabled()
            prefs().edit().putBoolean(GRID_WORKSPACE_LAB_PREF, next).apply()
            haptic(this)
            renderPaneContent(teclasSettingsTarget())
        }, LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
        if (gridWorkspaceLabEnabled()) {
            parent.addView(settingAction("OPEN GRID WORKSPACE →") {
                haptic(this)
                startActivity(Intent(this@MainActivity, com.fran.teclas.grid.GridWorkspaceActivity::class.java))
            }, LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
            parent.addView(settingToggle("GRID AS HOMESCREEN", gridHomeAliasEnabled()) {
                val next = !gridHomeAliasEnabled()
                setGridHomeAliasEnabled(next)
                haptic(this)
                if (next) {
                    Toast.makeText(this@MainActivity, "Pick \"Teclas Grid\" as your home app", Toast.LENGTH_LONG).show()
                    runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                }
                renderPaneContent(teclasSettingsTarget())
            }, LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
        }
        parent.addView(settingToggle("LOCKSCREEN WALLPAPER HOME", useLockscreenWallpaperOnHome()) {
            val next = !useLockscreenWallpaperOnHome()
            prefs().edit().putBoolean(HOME_LOCK_WALLPAPER_PREF, next).apply()
            homeWallpaperDrawable = loadHomeWallpaperDrawable()
            haptic(this)
            render()
            openHere(teclasSettingsTarget())
        }, LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
        parent.addView(keyboardSwapAnimationSelector(), LinearLayout.LayoutParams.MATCH_PARENT, dp(40))
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
        parent.addView(mono("PLAYING MEDIA IS THE FAST PATH: TECLAS CAN READ THE PHONE'S ACTIVE MEDIA SESSION, THEN API CONNECTORS CAN ADD LIBRARY/PLAYLIST SYNC LATER.", 8.5f, InkDim).apply {
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
                        if (openPane?.kind == PaneKind.SETTINGS) renderPaneContent(teclasSettingsTarget())
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

    private fun keyboardSwapAnimationSelector(): View {
        val current = keyboardSwapAnimationMode()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), 0)
            addView(mono("POP OUT", 9.5f, InkDim).apply {
                letterSpacing = 0.10f
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.MATCH_PARENT))
            listOf(
                "DEFAULT" to KEYBOARD_SWAP_ANIMATION_DEFAULT,
                "POP OUT" to KEYBOARD_SWAP_ANIMATION_POPOUT
            ).forEach { (label, value) ->
                addView(TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 9.2f
                    letterSpacing = 0.08f
                    typeface = Typeface.MONOSPACE
                    includeFontPadding = false
                    setTextColor(if (current == value) activeNeuTokens.ink else activeNeuTokens.inkDim)
                    background = if (current == value) {
                        Neu.drawable(activeNeuTokens, dp(10).toFloat(), NeuLevel.PRESSED_SM)
                    } else {
                        Neu.drawable(activeNeuTokens, dp(10).toFloat(), NeuLevel.RAISED_SM)
                    }
                    isClickable = true
                    setOnClickListener {
                        haptic(this)
                        prefs().edit().putString(KEYBOARD_SWAP_ANIMATION_PREF, value).apply()
                        renderPaneContent(teclasSettingsTarget())
                    }
                }, LinearLayout.LayoutParams(0, dp(28), 1f).apply {
                    marginStart = dp(6)
                })
            }
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
                        if (openPane?.kind == PaneKind.SETTINGS) renderPaneContent(teclasSettingsTarget())
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
            "Teclas settings" to GESTURE_SETTINGS,
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
                    renderPaneContent(teclasSettingsTarget())
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
                renderPaneContent(teclasSettingsTarget())
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
            renderPaneContent(teclasSettingsTarget())
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
                    renderPaneContent(teclasSettingsTarget())
                })
            }
            "com.apple.android.music" -> {
                menu.addView(menuItem("About Apple Music", true) {
                    popup.dismiss()
                    Toast.makeText(this, "Apple Music on Android uses the Media Session API — just play music in the Apple Music app and Teclas picks it up automatically.", Toast.LENGTH_LONG).show()
                })
                menu.addView(menuItem("Disconnect", true) {
                    prefs().edit().putString(prefKey, INTEGRATION_OFF).apply()
                    popup.dismiss()
                    renderPaneContent(teclasSettingsTarget())
                })
            }
            else -> {
                menu.addView(menuItem("Disconnect", true) {
                    prefs().edit().putString(prefKey, INTEGRATION_OFF).apply()
                    popup.dismiss()
                    renderPaneContent(teclasSettingsTarget())
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
            renderPaneContent(teclasSettingsTarget())
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
            renderPaneContent(teclasSettingsTarget())
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
                    if (openPane?.kind == PaneKind.SETTINGS) renderPaneContent(teclasSettingsTarget())
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
                if (openPane?.kind == PaneKind.SETTINGS) renderPaneContent(teclasSettingsTarget())
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
            renderPaneContent(teclasSettingsTarget())
            renderRibbon()
        })
        iconPacks().forEach { pack ->
            menu.addView(menuItem(pack.name, true) {
                prefs().edit().putString(ACTIVE_ICON_PACK_PREF, pack.packageName).apply()
                popup.dismiss()
                renderPaneContent(teclasSettingsTarget())
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
                text = "Add a Gemini API key in Teclas Settings to ask questions without leaving the launcher."
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
        composeText = ""; cursorPos = null; shiftState = ShiftState.ONCE
        suggestions = emptyList(); updateSuggestionBar(); updateKeyLabels()
        // In a notification reply pane, actually deliver the message via the app's RemoteInput.
        val key = replyingToKey
        if (key != null && target.id == "reply:$key") {
            val sent = TeclasNotificationListener.sendReply(this, key, text)
            if (!sent) Toast.makeText(this, "Couldn't send — tap to open ${target.name}", Toast.LENGTH_SHORT).show()
        }
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

    internal fun renderRibbon() {
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
            else -> "SEARCH"
        }
        if (typedText.isNotBlank() && !keyboardSettingsOpen) {
            searchHintView.textSize = 15f
            searchHintView.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            searchHintView.letterSpacing = 0f
            searchHintView.ellipsize = android.text.TextUtils.TruncateAt.START
            searchHintView.gravity = Gravity.CENTER_VERTICAL
            searchHintView.includeFontPadding = true
            searchHintView.translationY = dp(1).toFloat()
            searchHintView.setPadding(dp(16), dp(5), dp(16), dp(1))
            searchHintView.setTextColor(keyboardIndicatorTextColor())
            searchHintView.text = styledTypedCommand(typedText)
        } else {
            searchHintView.translationY = 0f
            searchHintView.textSize = if (libraryOpen) 10.8f else if (isVivoDevice()) 8.6f else 9.5f
            searchHintView.typeface = Typeface.create(if (libraryOpen) "sans-serif-medium" else "sans-serif-thin", if (libraryOpen) Typeface.BOLD else Typeface.NORMAL)
            searchHintView.letterSpacing = if (libraryOpen) 0.11f else if (isVivoDevice()) 0.14f else 0.18f
            searchHintView.ellipsize = android.text.TextUtils.TruncateAt.END
            searchHintView.includeFontPadding = false
            searchHintView.gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            searchHintView.setPadding(dp(10), dp(2), dp(6), dp(3))
            searchHintView.setTextColor(if (libraryOpen) keyboardIndicatorTextColor() else keyboardIndicatorTextColor(dim = true))
            searchHintView.text = hint
        }
        val widgetSearchActive = isWidgetUniversalSearchActive()
        if (widgetSearchActive != widgetSearchRendered && ::contentFrame.isInitialized) {
            render()
            return
        }
        if (widgetSearchActive) refreshWidgetSearchContent()
        if (isUnfoldedInnerLayoutActive()) refreshUnfoldedLibraryContent()
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
            renderFavoritesDockContext()
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
        renderFavoritesDockContext()
    }

    private inner class FavoritesDockFlipFrame(context: Context) : FrameLayout(context) {
        private var startX = 0f
        private var startY = 0f
        private var interceptingFlip = false
        private var flipTriggered = false

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
            }
            return super.dispatchTouchEvent(event)
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    interceptingFlip = false
                    flipTriggered = false
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (abs(dy) > dp(18) && abs(dy) > abs(dx) * 1.25f) {
                        interceptingFlip = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    interceptingFlip = false
                    flipTriggered = false
                }
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (interceptingFlip && !flipTriggered && abs(event.y - startY) > dp(28)) {
                        flipTriggered = true
                        toggleFavoritesDockContext()
                        parent?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (interceptingFlip && !flipTriggered && abs(event.y - startY) > dp(28)) {
                        toggleFavoritesDockContext()
                    }
                    interceptingFlip = false
                    flipTriggered = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    interceptingFlip = false
                    flipTriggered = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            return true
        }
    }

    private fun toggleFavoritesDockContext() {
        val goingToContext = !favoritesDockContextShowing
        // Record the user's deliberate choice: pinning overrides auto for the current Space;
        // returning to context re-arms auto-follow.
        setDockPinnedOverrideSpace(
            com.fran.teclas.predict.DockContextPolicy.onUserSwipe(goingToContext, currentContextDockKey()))
        setFavoritesDockContextShowing(goingToContext, preferContext = goingToContext)
    }

    private fun setFavoritesDockContextShowing(show: Boolean, preferContext: Boolean = false) {
        if (!::favoritesDockView.isInitialized || !::favoritesDockContextView.isInitialized) return
        if (preferContext && show) favoritesDockContextPreferred = true
        if (!show) favoritesDockContextPreferred = false
        updateContextDockStamp()
        if (favoritesDockContextShowing == show) return
        favoritesDockContextShowing = show
        keyHaptic("space")
        val duration = 210L
        val front = favoritesDockView
        val back = favoritesDockContextView
        val stamp = favoritesDockContextStampView
        front.animate().cancel()
        back.animate().cancel()
        stamp?.animate()?.cancel()
        if (show) {
            renderFavoritesDockContext()
            updateContextDockStamp()
            stamp?.visibility = View.VISIBLE
            stamp?.animate()
                ?.alpha(1f)
                ?.translationY(0f)
                ?.setDuration(duration)
                ?.setInterpolator(DecelerateInterpolator())
                ?.start()
            back.visibility = View.VISIBLE
            back.rotationX = 90f
            back.alpha = 0f
            front.animate()
                .rotationX(-90f)
                .alpha(0f)
                .setDuration(duration / 2)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    front.visibility = View.GONE
                    back.animate()
                        .rotationX(0f)
                        .alpha(1f)
                        .setDuration(duration / 2)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                .start()
        } else {
            stamp?.animate()
                ?.alpha(0f)
                ?.translationY(dp(6).toFloat())
                ?.setDuration(duration / 2)
                ?.setInterpolator(DecelerateInterpolator())
                ?.withEndAction { stamp.visibility = View.GONE }
                ?.start()
            front.visibility = View.VISIBLE
            front.rotationX = -90f
            front.alpha = 0f
            back.animate()
                .rotationX(90f)
                .alpha(0f)
                .setDuration(duration / 2)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    back.visibility = View.GONE
                    front.animate()
                        .rotationX(0f)
                        .alpha(1f)
                        .setDuration(duration / 2)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                .start()
        }
    }

    private fun renderFavoritesDockContext() {
        if (!::favoritesDockContextView.isInitialized) return
        favoritesDockContextView.removeAllViews()
        val previousContextKey = contextDockContextKey
        val nextContextKey = currentContextDockKey()
        val contextChanged = previousContextKey != null && previousContextKey != nextContextKey
        contextDockContextKey = nextContextKey
        contextDockSignature = currentContextDockSignature()
        val apps = currentContextDockApps()
        apps.take(DOCK_APP_LIMIT).forEachIndexed { index, app ->
            favoritesDockContextView.addView(contextDockAppButton(app, index), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
        repeat(DOCK_APP_LIMIT - apps.size.coerceAtMost(DOCK_APP_LIMIT)) { index ->
            favoritesDockContextView.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (apps.isNotEmpty() || index > 0) marginStart = dp(6)
            })
        }
        updateContextDockStamp()
        val decision = com.fran.teclas.predict.DockContextPolicy.onSpaceObserved(
            override = dockPinnedOverrideSpace(), current = nextContextKey)
        // Drop a stale override (belongs to a Space we've left) so auto re-arms.
        if (dockPinnedOverrideSpace() != decision.override) setDockPinnedOverrideSpace(decision.override)
        // Only animate the face on a genuine Space change; between changes the dock stays put.
        if (contextChanged) {
            favoritesDockContextPreferred = decision.showContext
            favoritesDockContextView.post {
                setFavoritesDockContextShowing(decision.showContext, preferContext = decision.showContext)
            }
        }
    }

    /**
     * The context face: the active Space's predicted apps (this is what auto-shows on a
     * Space change). Falls back to category-contextual apps only while the prediction engine
     * is still warming up (no snapshot yet), so the dock is never empty.
     */
    private fun currentContextDockApps(): List<LibraryApp> {
        val eligible = apps.filter { it.packageName != packageName }
        val predicted = predictedPackages(eligible.map { it.packageName }, DOCK_APP_LIMIT)
        if (!predicted.isNullOrEmpty()) {
            val byPackage = eligible.associateBy { it.packageName }
            val ordered = predicted.mapNotNull { byPackage[it]?.toLibraryApp() }
            if (ordered.isNotEmpty()) return ordered.take(DOCK_APP_LIMIT)
        }
        val seen = linkedSetOf<String>()
        return libraryCategories()
            .flatMap { it.apps }
            .filter { app ->
                val key = app.target.packageName ?: app.target.id
                seen.add(key)
            }
            .take(DOCK_APP_LIMIT)
    }

    private fun currentContextDockSignature(): String {
        val appsSig = currentContextDockApps().joinToString("|") { it.target.packageName ?: it.target.id }
        return listOf(
            currentContextDockKey(),
            categoryContextBucket(),
            prefs().getString(APP_USAGE_PREF, "{}").orEmpty(),
            prefs().getString(APP_LAST_LAUNCH_PREF, "{}").orEmpty(),
            appsSig
        ).joinToString("::")
    }

    // Keyed on the active Space only. Deliberately NOT the time-of-day category bucket:
    // the dock should auto-flip to context when the *Space* changes, not every few hours,
    // which would otherwise stomp on a user who swiped back to their pinned apps.
    private fun currentContextDockKey(): String =
        activeSpaceForUi()?.id ?: "home"

    private fun currentContextDockLabel(): String {
        val space = activeSpaceForUi()
        return if (space != null) {
            "${space.emoji} ${space.name.uppercase(Locale.US)}"
        } else {
            categoryContextBucket().replace('_', ' ').uppercase(Locale.US)
        }
    }

    private fun currentContextDockAccent(): Int {
        val key = currentContextDockKey()
        return when (key) {
            "driving", "maps", "travel" -> 0xFF7DB7FF.toInt()
            "fitness" -> 0xFF8FD694.toInt()
            "work", "weekday_morning" -> 0xFFF5C451.toInt()
            "night", "late_night" -> 0xFFC4B5FF.toInt()
            "evening", "home" -> 0xFFFF8F8F.toInt()
            else -> weatherAmbientLightColor()
        }
    }

    private fun updateContextDockStamp() {
        favoritesDockContextStampView?.let { stamp ->
            val accent = currentContextDockAccent()
            stamp.text = currentContextDockLabel()
            stamp.setTextColor(activeNeuTokens.ink)
            stamp.background = contextDockStampBackground(accent)
        }
    }

    private fun contextDockStampBackground(accent: Int): Drawable {
        val radius = dp(14).toFloat()
        val tray = Neu.drawable(activeNeuTokens, radius, NeuLevel.RAISED_SM)
        val wash = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
            adjustAlpha(accent, if (activeNeuTokens.mode == NeuMode.LIGHT) 0.16f else 0.24f),
            adjustAlpha(activeNeuTokens.baseHi, if (activeNeuTokens.mode == NeuMode.LIGHT) 0.40f else 0.34f),
            adjustAlpha(accent, if (activeNeuTokens.mode == NeuMode.LIGHT) 0.08f else 0.14f)
        )).apply {
            cornerRadius = radius
            setStroke(dp(1), adjustAlpha(Color.WHITE, if (activeNeuTokens.mode == NeuMode.LIGHT) 0.30f else 0.12f))
        }
        val spine = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            brighten(accent),
            accent
        )).apply {
            cornerRadius = dp(2).toFloat()
        }
        return LayerDrawable(arrayOf(tray, wash, spine)).apply {
            setLayerInset(1, dp(2), dp(2), dp(2), dp(2))
            setLayerInset(2, dp(8), dp(8), dp(8), dp(18))
        }
    }

    private fun scheduleContextDockRefresh() {
        contextDockRefreshRunnable?.let { handler.removeCallbacks(it) }
        contextDockRefreshRunnable = object : Runnable {
            override fun run() {
                if (::favoritesDockContextView.isInitialized) {
                    val next = currentContextDockSignature()
                    if (next != contextDockSignature) renderFavoritesDockContext()
                    handler.postDelayed(this, 60_000L)
                }
            }
        }
        handler.postDelayed(contextDockRefreshRunnable!!, 60_000L)
    }

    private fun contextDockAppButton(app: LibraryApp, index: Int): View = FrameLayout(this).apply {
        isClickable = true
        setPadding(dp(3), 0, dp(3), 0)
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
        val iconFrameSize = if (isUnfoldedInnerLayoutActive()) dp(58) else dockIconFrameSize(showDockLabels())
        addView(FrameLayout(context).apply {
            elevation = dp(2).toFloat()
            background = dockIconButtonBackground(activeNeuTokens)
            addView(ImageView(context).apply {
                setImageDrawable(iconFor(app))
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                setPadding(appIconInnerPadding(), appIconInnerPadding(), appIconInnerPadding(), appIconInnerPadding())
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }, FrameLayout.LayoutParams(iconFrameSize, iconFrameSize, Gravity.CENTER))
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
            }
            false
        }
        setOnClickListener {
            haptic(this)
            val target = app.target
            setFavoritesDockContextShowing(false)
            if (target.kind == PaneKind.MUSIC || target.packageName == null) openHere(target)
            else { pendingLaunchSource = LaunchSource.DOCK; openExternal(target) }
        }
        setOnLongClickListener { haptic(this); showOpenMenu(this, app.target); true }
    }

    private fun homeDockItems(): List<HomeDockItem> {
        val hidden = hiddenHomePackages()
        val music = HomeDockItem(null, musicTarget(), "Music", 0xFF57C98A.toInt())
            .takeUnless { it.target.id in hidden }
        val appItems = homeDockApps()
            .filterNot { it.packageName == packageName }
            .map { HomeDockItem(it, it.toPaneTarget(), it.shortName, it.brandColor) }
        return (listOfNotNull(music) + appItems).take(DOCK_APP_LIMIT)
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
            val iconFrame = if (isUnfoldedInnerLayoutActive()) dp(58) else dockIconFrameSize(showLabel)
            addView(FrameLayout(context).apply {
                elevation = dp(2).toFloat()
                background = dockIconButtonBackground(activeNeuTokens)
                if (libraryApp != null) {
                    addView(ImageView(context).apply {
                        setImageDrawable(iconFor(libraryApp))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        adjustViewBounds = true
                        val innerPad = if (isUnfoldedInnerLayoutActive()) dp(8) else appIconInnerPadding()
                        setPadding(innerPad, innerPad, innerPad, innerPad)
                    }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                } else {
                    addView(TextView(context).apply {
                        text = "♪"
                        gravity = Gravity.CENTER
                        textSize = if (isUnfoldedInnerLayoutActive()) 28f else 23f
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
            setOnClickListener {
                haptic(this)
                if (target.kind == PaneKind.MUSIC || target.packageName == null) openHere(target)
                else { pendingLaunchSource = LaunchSource.DOCK; openExternal(target) }
            }
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
            setOnClickListener {
                haptic(this)
                if (target.kind == PaneKind.MUSIC || target.packageName == null) openHere(target)
                else { pendingLaunchSource = LaunchSource.SEARCH; openExternal(target) }
            }
            setOnLongClickListener { haptic(this); showOpenMenu(this, target); true }
        }
    }

    private fun styledTypedCommand(text: String): SpannableString {
        val cursor = (cursorPos ?: text.length).coerceIn(0, text.length)
        val display = text.substring(0, cursor) + "|" + text.substring(cursor)
        val styled = SpannableString(display)
        styled.setSpan(ScaleXSpan(0.35f), cursor, cursor + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        styled.setSpan(ForegroundColorSpan(0xFFFF5A3C.toInt()), cursor, cursor + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        // Color whole text with top app's accent when it's a clear match
        val topApp = filteredRibbonEntries().firstOrNull()
        if (topApp != null && text.trim().length >= 2) {
            if (cursor > 0) {
                styled.setSpan(ForegroundColorSpan(topApp.accent), 0, cursor, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                styled.setSpan(StyleSpan(Typeface.BOLD), 0, cursor, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (cursor < text.length) {
                styled.setSpan(ForegroundColorSpan(topApp.accent), cursor + 1, display.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                styled.setSpan(StyleSpan(Typeface.BOLD), cursor + 1, display.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return styled
        }
        // Fall back to verb coloring
        val verb = text.trimStart().substringBefore(' ').lowercase(Locale.US)
        val color = commandVerbColor(verb) ?: return styled
        val start = text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return styled
        val end = (start + verb.length).coerceAtMost(text.length)
        val displayStart = if (start >= cursor) start + 1 else start
        val displayEnd = if (end > cursor) end + 1 else end
        if (displayStart < displayEnd) {
            styled.setSpan(ForegroundColorSpan(color), displayStart, displayEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            styled.setSpan(StyleSpan(Typeface.BOLD), displayStart, displayEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
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
        dockPresenceKey(target)?.takeIf { target.packageName == null }?.let {
            val onHome = isDockTargetOnHome(target)
            menu.addView(menuItem(if (onHome) "Remove from dock" else "Add to dock", true) {
                setDockTargetHomePresence(target, !onHome)
                popup.dismiss()
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
                return launchExternalIntent(Intent(Intent.ACTION_VIEW, Uri.parse(target.deepLinkUri)).setPackage(packageName), packageName)
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
                Toast.makeText(this, "Allow Teclas overlay so the docked keyboard can stay visible.", Toast.LENGTH_LONG).show()
                runCatching { startActivity(DockedKeyboardService.overlaySettingsIntent(this)) }
                return false
            }
            if (!isTeclasAccessibilityEnabled()) {
                Toast.makeText(this, "Enable Teclas Accessibility so the docked keyboard can type into apps.", Toast.LENGTH_LONG).show()
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                return false
            }
            startService(Intent(this, DockedKeyboardService::class.java))
        } else {
            stopService(Intent(this, DockedKeyboardService::class.java))
        }
        if (!isTeclasImeEnabled()) {
            Toast.makeText(this, "Enable Teclas Keyboard, then select it for docked typing.", Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            return false
        }
        if (!isTeclasImeSelected()) {
            Toast.makeText(this, "Select Teclas Keyboard for docked mode.", Toast.LENGTH_LONG).show()
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

    private fun isTeclasAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val mine = ComponentName(this, InputInjectionService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(mine, ignoreCase = true) }
    }

    private fun isTeclasImeEnabled(): Boolean {
        val component = ComponentName(this, TeclasImeService::class.java)
        return getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.any { info ->
                info.packageName == component.packageName &&
                    info.serviceName == component.className
            } == true
    }

    private fun isTeclasImeSelected(): Boolean {
        val selected = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        }.getOrDefault("")
        return selected.isBlank() || selected.matchesTeclasImeComponent()
    }

    private fun String.matchesTeclasImeComponent(): Boolean {
        val component = ComponentName(this@MainActivity, TeclasImeService::class.java)
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
        MostUsedAppsWidget.refreshAll(this)
        val source = pendingLaunchSource
        pendingLaunchSource = LaunchSource.OTHER
        mediaUiScope.launch(Dispatchers.Default) {
            runCatching {
                Predictor.recordLaunch(this@MainActivity, packageName, source, currentPredictSnapshot())
            }
            refreshPredictContext(rerender = true)
        }
        // Both library modes depend on launch data now: bento sorts categories by usage,
        // and the grid leads with the predicted row — leaving grid mode out kept the
        // predictions frozen at whatever the first render saw.
        libraryViewDirty = true
        libraryContentReady = false
        renderFavoritesDock()
    }

    /** Fresh context snapshot; call off the main thread (calendar/location on cache miss). */
    private fun currentPredictSnapshot(): ContextSnapshot {
        val playing = if (::mediaSessionSource.isInitialized) {
            mediaSessionSource.nowPlaying.value?.isPlaying == true
        } else false
        return Predictor.snapshotNow(this, mediaPlaying = playing)
    }

    /** Recompute the prediction context off-main; optionally re-render dock + Space icon. */
    private fun refreshPredictContext(rerender: Boolean = false) {
        mediaUiScope.launch(Dispatchers.Default) {
            val snap = runCatching { currentPredictSnapshot() }.getOrNull() ?: return@launch
            // Piggyback the throttled place-inference pass (home/work/airport suggestions).
            runCatching { com.fran.teclas.predict.PlaceInference.maybeRun(this@MainActivity) }
            val changed = predictContext?.contextKey() != snap.contextKey()
            predictContext = snap
            if (rerender || changed) {
                withContext(Dispatchers.Main) {
                    if (::favoritesDockView.isInitialized) renderFavoritesDock()
                    updateSpaceIcon()
                }
            }
        }
    }

    /** Predictor ranking for the current context, or null when not ready (cold start path). */
    private fun predictedPackages(candidates: List<String>, n: Int): List<String>? {
        val snap = predictContext ?: return null
        return runCatching { Predictor.topApps(this, n, candidates, snap) }.getOrNull()
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
        val matches = apps
            .mapNotNull { app -> appSearchRelevance(app, q).takeIf { it > 0 }?.let { app to it } }
        // Typed intent wins: exact/prefix label matches outrank context every time. The active
        // Space prediction score is only a tie-breaker inside the same text-relevance tier.
        val predictScore: Map<String, Float> = predictContext?.let { snap ->
            runCatching {
                Predictor.scores(this, matches.map { it.first.packageName }, snap).toMap()
            }.getOrNull()
        } ?: emptyMap()
        return matches.sortedWith { leftPair, rightPair ->
            val left = leftPair.first
            val right = rightPair.first
            val relevanceCompare = rightPair.second.compareTo(leftPair.second)
            if (relevanceCompare != 0) return@sortedWith relevanceCompare
            val scoreCompare = (predictScore[right.packageName] ?: 0f)
                .compareTo(predictScore[left.packageName] ?: 0f)
            if (scoreCompare != 0) scoreCompare else collator.compare(left.label, right.label)
        }.map { it.first }
    }

    private fun builtInLauncherApps(): List<LibraryApp> =
        listOf(teclasSettingsLibraryApp(), musicLibraryApp(), photosLibraryApp())

    private fun builtInLauncherSearchResults(rawQuery: String): List<SearchResult> {
        val q = rawQuery.trim()
        if (q.isBlank()) return emptyList()
        return builtInLauncherApps()
            .mapNotNull { app ->
                val relevance = builtInAppSearchRelevance(app, q)
                if (relevance <= 0) null else app to relevance
            }
            .sortedWith(compareByDescending<Pair<LibraryApp, Int>> { it.second }.thenBy { it.first.name })
            .map { (app, _) ->
                SearchResult(app.name, "Teclas built-in", app.accent, SearchKind.APP, app.target)
            }
    }

    private fun builtInAppSearchRelevance(app: LibraryApp, rawQuery: String): Int {
        val q = rawQuery.lowercase(Locale.US)
        val name = app.name.lowercase(Locale.US)
        val id = app.target.id.lowercase(Locale.US)
        val aliases = when (app.target.kind) {
            PaneKind.SETTINGS -> listOf("settings", "teclas settings", "preferences", "customize", "launcher settings")
            PaneKind.MUSIC -> listOf("music", "teclas music", "player", "now playing")
            PaneKind.PHOTOS -> listOf("photos", "zeiss", "zeiss optics", "camera roll", "pictures")
            else -> emptyList()
        }
        return when {
            name == q || aliases.any { it == q } -> 1100
            name.startsWith(q) || aliases.any { it.startsWith(q) } -> 980
            name.contains(q) || aliases.any { it.contains(q) } -> 760
            id.contains(q) -> 420
            else -> 0
        }
    }

    private fun appSearchRelevance(app: AppEntry, rawQuery: String): Int {
        val q = rawQuery.lowercase(Locale.US)
        if (q.isBlank()) return 0
        val label = app.label.lowercase(Locale.US)
        val short = app.shortName.lowercase(Locale.US)
        val pkg = app.packageName.lowercase(Locale.US)
        val words = label.split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            label == q || short == q -> 1000
            label.startsWith(q) || short.startsWith(q) -> 900
            words.any { it == q } -> 860
            words.any { it.startsWith(q) } -> 820
            label.contains(q) || short.contains(q) -> 700
            pkg.substringAfterLast('.').startsWith(q) -> 260
            pkg.contains(q) -> 120
            else -> 0
        }
    }

    /**
     * The contextual suggestion row for search: only when the active Space was detected
     * from a strong signal (place / driving / bluetooth / headphones, or a manual lock)
     * AND that Space has actually learned launches. Weak or unlearned context -> null,
     * normal ranking untouched.
     */
    private fun contextSuggestionResults(exclude: Set<String>): Pair<String, List<SearchResult>>? {
        val snap = predictContext ?: return null
        val det = SpaceManager.detect(this, snap)
        if (!det.strong && !det.locked) return null
        val installed = apps.associateBy { it.packageName }
        val learned = runCatching {
            Predictor.spaceTopLearned(this, det.space.id, 6, installed.keys)
        }.getOrDefault(emptyList())
            .filter { it !in exclude && it != packageName }
            .take(3)
        if (learned.isEmpty()) return null
        val label = when {
            snap.driving -> "Because you're driving"
            det.space.id == "fitness" -> "Because you're at the gym"
            det.space.id == "travel" -> "Because you're at the airport"
            det.space.id == "work" -> "Because you're at work"
            det.space.id == "home" -> "Because you're home"
            det.space.id == "commute" -> "For your commute"
            det.space.id == "night" -> "For tonight"
            else -> "In ${det.space.name}"
        }
        return label to learned.mapNotNull { pkg ->
            installed[pkg]?.let {
                SearchResult(it.label, det.space.name, it.brandColor, SearchKind.APP, it.toPaneTarget())
            }
        }
    }

    private fun universalSearchResults(): List<SearchResult> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val results = mutableListOf<SearchResult>()
        librarySearchResults().take(5).forEach { app ->
            results.add(SearchResult(app.label, "Open", app.brandColor, SearchKind.APP, app.toPaneTarget()))
        }
        results.addAll(builtInLauncherSearchResults(q).take(3))
        // Type-to-customize: matching launcher settings surface as tappable results that apply
        // live in place — search is the settings screen.
        results.addAll(settingSearchResults())
        // Playable Spotify tracks for this query (fetched async in scheduleMusicSearch) — tap to play
        // in place, no need to leave the homescreen.
        if (spotifyQuickQuery == q) results.addAll(spotifyQuickResults)
        if (gmailQuickQuery == q) results.addAll(gmailQuickResults)
        if (fileQuickQuery == q) results.addAll(fileQuickResults)
        if (looksLikeTravelQuery(q)) {
            val boarding = q.lowercase(Locale.US).contains("boarding") || q.lowercase(Locale.US).contains("pass")
            results.add(0, SearchResult(
                if (boarding) "Boarding passes" else "Flights & boarding passes",
                "From your Gmail", 0xFF5FD0C4.toInt(), SearchKind.TRAVEL, null
            ) { travelPaneHost.openTravelOverlay(startOnBoardingPasses = boarding) })
        }
        results.addAll(searchContactResults(q))
        results.addAll(searchMessageResults(q))
        results.addAll(searchCalendarResults(q))
        // A typed URL ("theverge.com") is an unambiguous intent — rank opening the site itself
        // first, so GO fires it directly. Tap = in-launcher sheet, long-press = full browser.
        InAppGoogleSearchEngine.urlFromQuery(q)?.let { url ->
            val host = Uri.parse(url).host ?: q
            results.add(0, SearchResult(
                "Open $host", "Website · hold for browser", 0xFF4285F4.toInt(), SearchKind.WEB, null,
                action = { openUrlDirectly(url) },
                longAction = { startSafeIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "No browser available") }
            ))
        }
        if (q.length >= 2) {
            val directHits = results.isNotEmpty()
            val web = SearchResult("Search the web", q, 0xFF4285F4.toInt(), SearchKind.AI, aiTarget("web:$q")) { webSearch(q) }
            val ai = SearchResult("Ask Gemini", q, 0xFF8AB4F8.toInt(), SearchKind.AI, aiTarget(q)) { askGemini(q) }
            when {
                !directHits && looksLikeWebSearch(q) -> {
                    results.add(web)
                    results.add(ai.copy(title = "Ask Gemini instead"))
                }
                !directHits -> {
                    results.add(ai)
                    results.add(web.copy(title = "Search the web instead"))
                }
                looksLikeAiQuestion(q) -> results.add(ai)
                else -> results.add(web.copy(title = "Search web for \"$q\""))
            }
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

    // Auto-route a query to web-search vs Gemini. Strong web signals (navigational/current/lookup)
    // win even over a question phrasing; a compose/explain-style request or a plain question goes to
    // Gemini; and short keyword-y queries (a name, place, product) default to the web.
    private fun looksLikeWebSearch(text: String): Boolean {
        val lower = text.lowercase(Locale.US).trim()
        val webSignals = listOf(
            ".com", ".org", ".net", "http", "www.", "near me", "open now", "hours", "directions",
            "map", "menu", "reviews", "showtimes", "price", "cheapest", "buy ", "download", "login",
            " vs ", "reddit", "youtube", "amazon", "wikipedia", "news", "score", "stock ", "weather ",
            "how much", "who won", "release date"
        ).any { lower.contains(it) }
        if (webSignals) return true
        // Compose/generation or a genuine question → Gemini.
        val aiVerbs = listOf("write ", "draft ", "summarize", "explain", "translate", "help me", "give me", "rewrite")
        if (aiVerbs.any { lower.startsWith(it) || lower.contains(" $it") }) return false
        if (looksLikeAiQuestion(lower)) return false
        // Not a question, no AI verb: a keyword lookup → web (a name, a place, a thing).
        return true
    }

    private fun looksLikeTravelQuery(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        return listOf("flight", "flights", "boarding", "boarding pass", "trip", "trips", "travel", "itinerary")
            .any { lower.contains(it) }
    }

    // ── Type-to-customize ────────────────────────────────────────────────────
    // Launcher settings exposed as universal-search results. Typing "glass", "accent",
    // "icon pack"… surfaces the matching control as a result card; tapping applies it
    // immediately and the results re-render in place with the new state. Search is the
    // settings screen — the pane stays available for browsing, but is never required.

    private fun settingSearchResults(): List<SearchResult> {
        val q = query.trim().lowercase(Locale.US)
        if (q.length < 3) return emptyList()
        fun score(entry: SettingSearchEntry): Int = entry.keywords.maxOf { kw ->
            when {
                kw == q -> 3
                kw.startsWith(q) -> 2
                q.length >= 4 && kw.contains(q) -> 1
                else -> 0
            }
        }
        return settingSearchEntries()
            .map { it to score(it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(4)
            .map { (entry, _) ->
                SearchResult(entry.title, entry.state, goKeyColor, SearchKind.SETTING, null) {
                    entry.perform()
                }
            }
    }

    private fun toggleStateLabel(enabled: Boolean): String =
        if (enabled) "On · tap to turn off" else "Off · tap to turn on"

    private fun settingSearchEntries(): List<SettingSearchEntry> {
        val entries = mutableListOf<SettingSearchEntry>()
        entries.add(SettingSearchEntry(
            "Glass effects", toggleStateLabel(glassEffectsEnabled()),
            listOf("glass", "effects", "blur", "frosted")
        ) {
            prefs().edit().putBoolean(GLASS_EFFECTS_PREF, !glassEffectsEnabled()).apply()
            libraryView?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
            libraryView = null
            libraryContentArea = null
            libraryViewMode = null
            libraryViewGlass = null
            libraryViewDirty = true
            libraryContentReady = false
            render()
            refreshSearchSurfaces()
        })
        entries.add(SettingSearchEntry(
            "Dock labels", toggleStateLabel(showDockLabels()),
            listOf("dock", "labels", "dock labels", "app names")
        ) {
            prefs().edit().putBoolean(DOCK_LABELS_PREF, !showDockLabels()).apply()
            renderFavoritesDock()
            refreshSearchSurfaces()
        })
        entries.add(SettingSearchEntry(
            "Animated weather", toggleStateLabel(animatedWeatherEnabled()),
            listOf("weather", "animated weather", "animation", "rain")
        ) {
            val next = !animatedWeatherEnabled()
            prefs().edit().putBoolean(ANIMATED_WEATHER_PREF, next).apply()
            if (::weatherIconView.isInitialized) weatherIconView.setAnimationEnabled(next)
            refreshSearchSurfaces()
        })
        entries.add(SettingSearchEntry(
            "Haptic feedback", toggleStateLabel(hapticsEnabled),
            listOf("haptic", "haptics", "vibration", "feedback")
        ) {
            hapticsEnabled = !hapticsEnabled
            prefs().edit().putBoolean(HAPTICS_PREF, hapticsEnabled).apply()
            refreshSearchSurfaces()
        })
        entries.add(SettingSearchEntry(
            "App Library as home", toggleStateLabel(appLibraryDefaultHome()),
            listOf("library", "app library", "library home")
        ) {
            prefs().edit().putBoolean(APP_LIBRARY_DEFAULT_HOME_PREF, !appLibraryDefaultHome()).apply()
            libraryContentReady = false
            libraryViewDirty = true
            refreshSearchSurfaces()
        })
        entries.add(SettingSearchEntry(
            "Lockscreen wallpaper", toggleStateLabel(useLockscreenWallpaperOnHome()),
            listOf("wallpaper", "lockscreen", "background")
        ) {
            prefs().edit().putBoolean(HOME_LOCK_WALLPAPER_PREF, !useLockscreenWallpaperOnHome()).apply()
            homeWallpaperDrawable = loadHomeWallpaperDrawable()
            render()
            refreshSearchSurfaces()
        })
        entries.add(SettingSearchEntry(
            "Accent color", "${currentGoColorName()} · tap for next",
            listOf("accent", "color", "theme color", "highlight")
        ) {
            cycleGoColor()
            refreshSearchSurfaces()
        })
        entries.add(SettingSearchEntry(
            "Launcher look", "${themeModeName()} · tap for next",
            listOf("dark", "light", "look", "mode", "theme", "dark mode", "light mode")
        ) {
            cycleThemeMode()
            refreshSearchSurfaces()
        })
        entries.add(SettingSearchEntry(
            "Keyboard theme", "${widgetThemeName(keyboardTheme)} · tap for next",
            listOf("keyboard", "keys", "keyboard theme", "skeuo", "gokeys")
        ) {
            cycleKeyboardTheme()
            refreshSearchSurfaces()
        })
        entries.add(SettingSearchEntry(
            "Keyboard placement",
            if (keyboardPlacement == KEYBOARD_PLACEMENT_DOCKED) "Docked · tap for widget" else "Widget · tap for docked",
            listOf("keyboard", "placement", "docked", "widget keyboard", "place")
        ) {
            setKeyboardPlacement(
                if (keyboardPlacement == KEYBOARD_PLACEMENT_DOCKED) KEYBOARD_PLACEMENT_WIDGET
                else KEYBOARD_PLACEMENT_DOCKED
            )
        })
        entries.add(SettingSearchEntry(
            "Icon size", "${iconSizeLabel()} · tap for next",
            listOf("icon size", "icons", "size")
        ) {
            cycleIconSize()
            refreshSearchSurfaces()
        })
        entries.add(SettingSearchEntry(
            "Icon pack", "${activeIconPackLabel()} · tap for next",
            listOf("icon pack", "icons", "pack")
        ) {
            cycleIconPack()
            refreshSearchSurfaces()
        })
        WidgetStackModes.WIDGET_IDS.forEach { id ->
            val name = WidgetStackModes.displayName(id)
            val mode = widgetStackMode(id)
            entries.add(SettingSearchEntry(
                "$name widget",
                "${WidgetStackModes.modeLabel(mode)} · tap for ${WidgetStackModes.modeLabel(WidgetStackModes.nextMode(mode))}",
                listOf("widget", "widgets", "stack", name.lowercase(Locale.US), "${name.lowercase(Locale.US)} widget")
            ) {
                setWidgetStackMode(id, WidgetStackModes.nextMode(widgetStackMode(id)))
                refreshSearchSurfaces()
            })
        }
        entries.add(SettingSearchEntry(
            "Spaces", "Contextual home setup",
            listOf("spaces", "space", "places")
        ) {
            startActivity(Intent(this@MainActivity, SpacesSettingsActivity::class.java))
        })
        entries.add(SettingSearchEntry(
            "Gestures", "Swipe actions",
            listOf("gesture", "gestures", "swipe")
        ) {
            openHere(teclasSettingsTarget())
        })
        return entries
    }

    // Re-render whichever search surface is showing so an applied setting is visible
    // immediately, with the query and keyboard untouched.
    private fun refreshSearchSurfaces() {
        if (libraryOpen) refreshLibraryContent()
        if (isWidgetUniversalSearchActive()) refreshWidgetSearchContent()
        if (isUnfoldedInnerLayoutActive()) refreshUnfoldedLibraryContent()
    }

    private fun currentGoColorName(): String =
        GO_COLORS.firstOrNull { it.color == goKeyColor }?.name?.lowercase(Locale.US)?.replaceFirstChar { it.uppercase() } ?: "Custom"

    private fun cycleGoColor() {
        val idx = GO_COLORS.indexOfFirst { it.color == goKeyColor }
        applyGoKeyColor(GO_COLORS[(idx + 1).mod(GO_COLORS.size)].color, refreshSettings = false)
    }

    private fun themeModeName(): String = when (themeMode) {
        THEME_MODE_DARK -> "Dark"
        THEME_MODE_LIGHT -> "Light"
        else -> "System"
    }

    private fun cycleThemeMode() {
        val order = listOf(THEME_MODE_SYSTEM, THEME_MODE_DARK, THEME_MODE_LIGHT)
        themeMode = order[(order.indexOf(themeMode) + 1).mod(order.size)]
        prefs().edit().putString(THEME_MODE_PREF, themeMode).apply()
        updateLauncherTheme(animated = true, forceRender = true)
    }

    private fun cycleKeyboardTheme() {
        val order = listOf(
            KEYBOARD_THEME_DEFAULT, KEYBOARD_THEME_TECLAS, KEYBOARD_THEME_SKEUO, KEYBOARD_THEME_GOKEYS,
            KEYBOARD_THEME_HYPER3D, KEYBOARD_THEME_HYPER3D_BLACK, KEYBOARD_THEME_HYPER3D_LIGHT,
            KEYBOARD_THEME_BRUSHED, KEYBOARD_THEME_SEEME
        ).filter { it != KEYBOARD_THEME_SKEUO || ProManager.isUnlocked(this) }
        keyboardTheme = order[(order.indexOf(keyboardTheme) + 1).mod(order.size)]
        prefs().edit().putString(KEYBOARD_THEME_PREF, keyboardTheme).apply()
        render()
    }

    private fun iconSizeLabel(): String = when {
        appIconSize >= 70 -> "Large"
        appIconSize >= 35 -> "Medium"
        else -> "Default"
    }

    private fun cycleIconSize() {
        val presets = listOf(0, 35, 70)
        appIconSize = presets.firstOrNull { it > appIconSize } ?: presets.first()
        prefs().edit().putInt(APP_ICON_SIZE_PREF, appIconSize).apply()
        renderFavoritesDock()
    }

    private fun cycleIconPack() {
        val packs = iconPacks()
        if (packs.isEmpty()) {
            Toast.makeText(this, "No icon packs installed", Toast.LENGTH_SHORT).show()
            return
        }
        val options = listOf<String?>(null) + packs.map { it.packageName }
        val current = prefs().getString(ACTIVE_ICON_PACK_PREF, null)
        val next = options[(options.indexOf(current) + 1).mod(options.size)]
        if (next == null) prefs().edit().remove(ACTIVE_ICON_PACK_PREF).apply()
        else prefs().edit().putString(ACTIVE_ICON_PACK_PREF, next).apply()
        renderFavoritesDock()
        renderRibbon()
    }

    // ── Widget stack visibility ──────────────────────────────────────────────

    private fun widgetStackMode(id: String): String =
        prefs().getString(WIDGET_STACK_MODE_PREF_PREFIX + id, WidgetStackModes.AUTO) ?: WidgetStackModes.AUTO

    private fun setWidgetStackMode(id: String, mode: String) {
        prefs().edit().putString(WIDGET_STACK_MODE_PREF_PREFIX + id, mode).apply()
        refreshNowPlayingCard()
    }

    private fun widgetStackModes(): Map<String, String> =
        WidgetStackModes.WIDGET_IDS.associateWith { widgetStackMode(it) }

    // In-place editor for the stack, reached by long-pressing the pager-dot rail (or the
    // hidden-stack placeholder). Tapping a row cycles AUTO → PINNED → HIDDEN live.
    private fun showWidgetStackEditor() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(4))
        }
        content.addView(mono("AUTO SHOWS A WIDGET WHEN IT HAS CONTEXT · PIN KEEPS IT FIRST · HIDE REMOVES IT", 8.5f, InkDim).apply {
            letterSpacing = 0.08f
            setPadding(0, 0, 0, dp(10))
        })
        WidgetStackModes.WIDGET_IDS.forEach { id ->
            lateinit var modeView: TextView
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(11), dp(2), dp(11))
                background = border(Line)
                isClickable = true
                addView(TextView(context).apply {
                    text = WidgetStackModes.displayName(id)
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Ink)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                modeView = mono(WidgetStackModes.modeLabel(widgetStackMode(id)), 10f, Accent2).apply { letterSpacing = 0.08f }
                addView(modeView)
                setOnClickListener {
                    haptic(this)
                    val next = WidgetStackModes.nextMode(widgetStackMode(id))
                    setWidgetStackMode(id, next)
                    modeView.text = WidgetStackModes.modeLabel(next)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("Widget stack")
            .setView(content)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun searchLocalFiles(q: String, limit: Int): List<LocalFileHit> {
        val fileUri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        val results = mutableListOf<LocalFileHit>()
        runCatching {
            contentResolver.query(
                fileUri,
                projection,
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("%$q%"),
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                while (cursor.moveToNext() && results.size < limit) {
                    val name = cursor.getString(nameCol)?.takeIf { it.isNotBlank() } ?: continue
                    val uri = ContentUris.withAppendedId(fileUri, cursor.getLong(idCol))
                    results.add(LocalFileHit(name, uri, cursor.getString(mimeCol)))
                }
            }
        }
        return results
    }

    private fun openLocalFile(file: LocalFileHit) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(file.uri, file.mimeType?.takeIf { it.isNotBlank() } ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startSafeIntent(intent, "No app can open this file")
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
            else -> {
                if (pendingLaunchSource == LaunchSource.OTHER) pendingLaunchSource = LaunchSource.SEARCH
                openExternal(target)
            }
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
        // Typed a URL → open the site itself, not a Google search about it.
        InAppGoogleSearchEngine.urlFromQuery(search)?.let { openUrlDirectly(it); return }
        val toolbar = if (activeNeuTokens.mode == NeuMode.LIGHT) activeNeuTokens.baseHi else activeNeuTokens.base
        val nav = if (activeNeuTokens.mode == NeuMode.LIGHT) activeNeuTokens.base else activeNeuTokens.baseLo
        runCatching {
            InAppGoogleSearchEngine(this).launchInAppSearch(search, toolbar, nav)
        }.onFailure {
            startSafeIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(search)}")), "No browser available")
        }
    }

    // Opens a URL in the in-launcher Custom Tab sheet (same treatment as in-app Google search),
    // falling back to the default browser if no Custom Tabs provider is available.
    private fun openUrlDirectly(url: String) {
        val toolbar = if (activeNeuTokens.mode == NeuMode.LIGHT) activeNeuTokens.baseHi else activeNeuTokens.base
        val nav = if (activeNeuTokens.mode == NeuMode.LIGHT) activeNeuTokens.base else activeNeuTokens.baseLo
        runCatching {
            InAppGoogleSearchEngine(this).launchInAppUrl(url, toolbar, nav)
        }.onFailure {
            startSafeIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "No browser available")
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
            putExtra(Intent.EXTRA_SUBJECT, parsed.message.ifBlank { "Message from Teclas" })
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
        pendingLaunchSource = LaunchSource.COMMAND
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

    // Native Google results inside the launcher (Custom Search JSON API), rendered in the AI pane as
    // a markdown answer — real result links, no Custom Tab, no leaving. Needs a key + engine id set
    // in Settings; otherwise the pane explains how to connect.
    private fun webSearch(rawQuery: String) {
        val q = InAppGoogleSearchEngine.stripWebVerb(rawQuery).trim().ifBlank { return }
        val target = aiTarget("web:$q")
        aiDraftText = ""; aiDraftActive = false
        val configured = GoogleSearchApi.isConfigured(prefs())
        aiAnswersById[target.id] = AiAnswerState(
            "🔍 $q",
            if (configured) "Searching Google…"
            else "Add a **Google Search API key** and **engine ID** in Teclas Settings to see results here.",
            configured
        )
        openHere(target)
        if (!configured) return
        val key = prefs().getString(GoogleSearchApi.KEY_PREF, null)?.trim().orEmpty()
        val cx = prefs().getString(GoogleSearchApi.CX_PREF, null)?.trim().orEmpty()
        mediaUiScope.launch(Dispatchers.IO) {
            val results = runCatching { GoogleSearchApi.search(q, key, cx) }.getOrDefault(emptyList())
            val answer = if (results.isEmpty()) "No results for “$q”."
            else results.joinToString("\n\n") { r ->
                val snip = if (r.snippet.isNotBlank()) "\n${r.snippet}" else ""
                "**${r.title}**\n${r.link}$snip"
            }
            runOnUiThread {
                aiAnswersById[target.id] = AiAnswerState("🔍 $q", answer, false)
                if (openPane?.id == target.id) renderPaneContent(target)
            }
        }
    }

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

    private fun geminiActionPrompt(prompt: String): String {
        val appHints = apps.take(24).joinToString(", ") { it.label }
        val eventHints = calendarEvents.take(4).joinToString("; ") { "${it.title} ${it.timeLabel}" }
        val messageHints = messages.take(6).joinToString("; ") { "${it.sender}: ${it.preview}" }
        return """
            You are Teclas AI, an agent inside an Android launcher. Decide how to fulfil the user's request and reply with ONLY a JSON object — no markdown, no prose.

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
                        .putExtra(Intent.EXTRA_SUBJECT, a.message.ifBlank { "Message from Teclas" })
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
            You are Teclas AI inside an Android launcher. Be concise, helpful, and action-oriented.
            User request: $prompt

            Launcher context:
            Apps: $appHints
            Upcoming calendar: $eventHints
            Recent notifications/messages: $messageHints

            If the user is asking to perform a launcher action, suggest the direct Teclas command they can type.
        """.trimIndent()
    }

    internal fun geminiConfigured(): Boolean {
        // Enabled when AI is on AND we can reach Gemini — either account mode (proxy) or a local key.
        return prefs().getBoolean(GEMINI_ENABLED_PREF, false) &&
            (GeminiClient.proxy != null || !prefs().getString(GEMINI_API_KEY_PREF, null).isNullOrBlank())
    }

    internal fun startSafeIntent(intent: Intent, failureMessage: String) {
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
        return librarySearchResults()
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

    /**
     * The pinned face: the user's own apps, kept deliberately stable — favorites first, then
     * most-used to fill. Predictions are NOT blended in here; they live on the context face
     * (which auto-shows on a Space change). This is the page the user swipes back to and
     * expects to stay put.
     */
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

    /** The Space id the user pinned the dock to, or null when the dock auto-follows context. */
    private fun dockPinnedOverrideSpace(): String? =
        prefs().getString(DOCK_PINNED_OVERRIDE_SPACE_PREF, null)

    private fun setDockPinnedOverrideSpace(spaceId: String?) {
        prefs().edit().apply {
            if (spaceId == null) remove(DOCK_PINNED_OVERRIDE_SPACE_PREF)
            else putString(DOCK_PINNED_OVERRIDE_SPACE_PREF, spaceId)
        }.apply()
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

    private fun dockPresenceKey(target: PaneTarget): String? =
        target.packageName ?: target.id.takeIf { target.kind == PaneKind.MUSIC }

    private fun isDockTargetOnHome(target: PaneTarget): Boolean {
        val key = dockPresenceKey(target) ?: return false
        return key !in hiddenHomePackages() && when {
            target.packageName != null -> homeDockApps().any { it.packageName == target.packageName }
            else -> homeDockItems().any { it.target.id == target.id }
        }
    }

    private fun setDockTargetHomePresence(target: PaneTarget, showOnHome: Boolean) {
        val key = dockPresenceKey(target) ?: return
        if (target.packageName != null) {
            setHomePresence(key, showOnHome)
            return
        }
        val hidden = hiddenHomePackages().toMutableSet()
        if (showOnHome) hidden.remove(key) else hidden.add(key)
        prefs().edit().putStringSet(HIDDEN_HOME_APPS_PREF, hidden).apply()
        renderRibbon()
        renderFavoritesDock()
        if (libraryOpen) showLibrary(animate = false)
    }

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

    private fun teclasSettingsLibraryApp() = LibraryApp("Teclas Settings", Accent, teclasSettingsTarget(), null)

    private fun musicLibraryApp() = LibraryApp("Music", 0xFF57C98A.toInt(), musicTarget(), null)

    private fun photosLibraryApp() = LibraryApp("ZEISS Optics", 0xFFDCE6FF.toInt(), photosTarget(), null)

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
            .map { RecentPerson(it.key, it.sender, it.preview, it.packageName, it.color, TeclasNotificationListener.notificationAvatars[it.key], it.lastUpdated) }
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
                    avatar = TeclasNotificationListener.notificationAvatars[it.key],
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
        // Docked with a captured reply action → reply inline on the homescreen (half-screen chat).
        // Otherwise (widget mode, or no reply action) fall through to opening the conversation/app.
        if (keyboardPlacement == KEYBOARD_PLACEMENT_DOCKED && TeclasNotificationListener.canReply(person.key)) {
            val message = messages.firstOrNull { it.key == person.key }
                ?: messages.firstOrNull { it.sender == person.sender && it.packageName == person.packageName }
            if (message != null) { openReplyPane(message); return }
        }
        deliverNotificationIntent(
            key = person.key,
            fallback = { openRecentConversationFallback(person) },
            afterOpen = { clearHandledConversation(person) }
        )
    }

    // Message key we're currently replying to via notification RemoteInput (reply chat pane open).
    private var replyingToKey: String? = null

    /** Route a hub message tap: reply inline when docked and a reply action exists, else open the
     *  conversation (widget mode gets the full app experience, per the docked/widget split). */
    private fun openMessageReplyOrApp(message: HubMessage) {
        if (keyboardPlacement == KEYBOARD_PLACEMENT_DOCKED && TeclasNotificationListener.canReply(message.key)) {
            openReplyPane(message); return
        }
        deliverNotificationIntent(
            key = message.key,
            fallback = { openHere(message.toPaneTarget()); true },
            afterOpen = {}
        )
    }

    /** Open a half-screen chat pane seeded with the incoming message; sending replies via RemoteInput. */
    private fun openReplyPane(message: HubMessage) {
        replyingToKey = message.key
        val target = PaneTarget(
            id = "reply:${message.key}",
            name = message.sender.ifBlank { "Reply" },
            accent = message.color,
            kind = PaneKind.CHAT,
            packageName = message.packageName,
            deepLinkUri = null,
            preview = message.preview
        )
        openHere(target)
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
        val pending = TeclasNotificationListener.notificationIntents[key]
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
        TeclasNotificationListener.notificationIntents.remove(person.key)
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
        TeclasNotificationListener.notificationIntents.remove(item.key)
        TeclasNotificationListener.notificationAvatars.remove(item.key)
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

    private fun teclasSettingsTarget() = PaneTarget("teclas-settings", "Teclas Settings", Accent, PaneKind.SETTINGS, null, null, "Integrations")

    private fun musicTarget() = PaneTarget("teclas-music", "Music", 0xFF57C98A.toInt(), PaneKind.MUSIC, null, null, "Now playing")
    private fun photosTarget() = PaneTarget("teclas-photos", "ZEISS Optics", 0xFFDCE6FF.toInt(), PaneKind.PHOTOS, null, null, "Latest photos")
    private fun aiTarget(prompt: String) = PaneTarget("teclas-ai:${prompt.hashCode()}", "Teclas AI", 0xFF8AB4F8.toInt(), PaneKind.AI, null, null, prompt)

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

    internal fun isNotificationAccessEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val expected = ComponentName(this, TeclasNotificationListener::class.java).flattenToString()
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    internal fun openNotificationAccessSettings() { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

    private fun updateClock() {
        if (!::weatherTempView.isInitialized) return
        weatherTempView.text = prefs().getString(WEATHER_TEMP_PREF, "--")
        weatherMetaView.text = prefs().getString(WEATHER_META_PREF, "Tap for local weather")
        weatherFeelsView.text = prefs().getString(WEATHER_FEELS_PREF, "Feels --")
        weatherStatsView.text = prefs().getString(WEATHER_STATS_PREF, "Local")
        weatherIconView.setWeatherCode(prefs().getInt(WEATHER_CODE_PREF, 0))
        weatherIconView.setAnimationEnabled(animatedWeatherEnabled())
        weatherAmbientView?.setWeather(prefs().getInt(WEATHER_CODE_PREF, 0), activeNeuTokens.mode, animate = false)
        weatherDripView?.refresh(playMoment = false)
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
                    weatherDripView?.playLivePhotoBurst()
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

    // NOTE: Pure drawing helpers (mono, border, highlight, highlightedLabel, header glass
    // backgrounds, roundedPanel, colour math, …) moved verbatim to ThemeDrawables.kt as
    // top-level/Context-extension functions. Call sites below are unchanged.

    private fun widgetKeyboardHeight(): Int {
        return (keyboardHeight() + launcherSuggestionStripOuterHeight()).coerceAtMost(dp(360))
    }

    private fun widgetKeyboardSlotHeight(): Int =
        if (widgetKeyboardHidden && widgetKeyboardSliderAvailable()) widgetKeyboardCollapsedDockHeight() else widgetKeyboardHeight()

    private fun widgetPaneUsesRootDock(): Boolean {
        return keyboardPlacement == KEYBOARD_PLACEMENT_WIDGET && openPane?.usesMediaDock() == true
    }

    private fun activeRootDockHeight(): Int {
        if (widgetKeyboardHidden && widgetKeyboardSliderAvailable()) return widgetKeyboardCollapsedDockHeight()
        if (widgetPaneUsesRootDock()) return widgetKeyboardHeight()
        // keyboardHeight() budgets the typing well + key rows but NOT the Gboard-style suggestion
        // strip mounted at the top of the deck (see keyboard()). Without adding it, the deck is
        // ~30dp taller than this slot and bleeds upward over the favorites dock / typing strip.
        return keyboardHeight() + launcherSuggestionStripOuterHeight()
    }

    private fun expandedRootDockHeight(): Int {
        if (widgetPaneUsesRootDock()) return widgetKeyboardHeight()
        return keyboardHeight() + launcherSuggestionStripOuterHeight()
    }

    private fun widgetKeyboardCollapsedDockHeight(): Int = dp(48)

    private fun widgetKeyboardHorizontalBleed(): Int = dp(10)

    private fun recessedDockBackground(): Drawable {
        return teclasGlassDrawable(19)
    }

    private fun libraryIconButtonBackground(radiusDp: Int = 13, stroke: Int? = Line): Drawable {
        return if (activeNeuTokens.mode == NeuMode.LIGHT) {
            dockIconButtonBackground(activeNeuTokens)
        } else {
            roundedPanel(Panel2, dp(radiusDp), stroke)
        }
    }

    private fun widgetTileBackground(): Drawable {
        val light = activeNeuTokens.mode == NeuMode.LIGHT
        val pocket = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (light) {
            intArrayOf(
                adjustAlpha(Color.WHITE, 0.18f),
                adjustAlpha(activeNeuTokens.baseHi, 0.10f),
                adjustAlpha(activeNeuTokens.baseLo, 0.14f)
            )
        } else {
            intArrayOf(
                adjustAlpha(activeNeuTokens.baseHi, 0.30f),
                adjustAlpha(activeNeuTokens.base, 0.24f),
                adjustAlpha(activeNeuTokens.baseLo, 0.38f)
            )
        }).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), adjustAlpha(Color.WHITE, if (light) 0.34f else 0.16f))
        }
        val rim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            adjustAlpha(Color.WHITE, if (light) 0.24f else 0.14f),
            Color.TRANSPARENT,
            adjustAlpha(Color.BLACK, if (light) 0.10f else 0.34f)
        )).apply { cornerRadius = dp(24).toFloat() }
        val glow = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            adjustAlpha(Accent2, if (light) 0.10f else 0.08f),
            Color.TRANSPARENT,
            adjustAlpha(Color.BLACK, if (light) 0.04f else 0.18f)
        )).apply { cornerRadius = dp(24).toFloat() }
        return LayerDrawable(arrayOf(pocket, glow, rim)).apply {
            setLayerInset(1, 0, 0, 0, 0)
            setLayerInset(2, dp(1), dp(1), dp(1), dp(1))
        }
    }

    private fun libraryCategories(): List<LibraryCategory> {
        val signature = listOf(
            apps.joinToString("|") { it.componentName.flattenToShortString() },
            builtInLauncherApps().joinToString("|") { it.target.id },
            categoryContextBucket(),
            prefs().getString(APP_USAGE_PREF, "{}").orEmpty(),
            prefs().getString(APP_LAST_LAUNCH_PREF, "{}").orEmpty()
        ).joinToString("::")
        if (signature == libraryCategoriesCacheSignature) return libraryCategoriesCache
        val grouped = (builtInLauncherApps() + apps.map { it.toLibraryApp() })
            .distinctBy { it.target.id }
            .groupBy { app -> categoryNameForLibraryApp(app) }
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

    private fun categoryNameForLibraryApp(app: LibraryApp): String =
        when (app.target.kind) {
            PaneKind.SETTINGS -> "Tools"
            PaneKind.MUSIC -> "Music & Audio"
            PaneKind.PHOTOS -> "Photos"
            else -> categoryNameFor(app.target.packageName)
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
                    background = dockIconButtonBackground(activeNeuTokens)
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
                background = dockIconButtonBackground(activeNeuTokens)
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

    private inner class KeyboardSliderHandleView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val light = activeNeuTokens.mode == NeuMode.LIGHT
            val radius = height * 0.42f
            rect.set(dp(4).toFloat(), dp(5).toFloat(), width - dp(4).toFloat(), height - dp(5).toFloat())

            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.LinearGradient(
                0f,
                rect.top,
                0f,
                rect.bottom,
                if (light) {
                    intArrayOf(0xFFE5E9EF.toInt(), 0xFFC4CBD6.toInt(), 0xFFAEB8C5.toInt())
                } else {
                    intArrayOf(0xFF232832.toInt(), 0xFF0F1218.toInt(), 0xFF05070A.toInt())
                },
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = adjustAlpha(Color.WHITE, if (light) 0.56f else 0.16f)
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.color = adjustAlpha(Color.BLACK, if (light) 0.24f else 0.76f)
            canvas.drawLine(rect.left + dp(16), rect.bottom - dp(2), rect.right - dp(16), rect.bottom - dp(2), paint)

            val grooveWidth = width * 0.46f
            val grooveHeight = dp(4).toFloat()
            val grooveTop = height / 2f - grooveHeight / 2f
            val groove = RectF(
                width / 2f - grooveWidth / 2f,
                grooveTop,
                width / 2f + grooveWidth / 2f,
                grooveTop + grooveHeight
            )
            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.LinearGradient(
                0f,
                groove.top,
                0f,
                groove.bottom,
                intArrayOf(
                    adjustAlpha(Color.BLACK, if (light) 0.26f else 0.74f),
                    adjustAlpha(Color.WHITE, if (light) 0.40f else 0.12f)
                ),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(groove, grooveHeight, grooveHeight, paint)
            paint.shader = null
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
        var brushedTextOffsetY = 0f
            set(value) {
                field = value
                invalidate()
            }

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
            canvas.translate(0f, if (keyboardTheme == KEYBOARD_THEME_BRUSHED) brushedTextOffsetY else -dp(5).toFloat())
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
        if (keyboardTheme == KEYBOARD_THEME_SEEME) return SeemeDrawables.panel(darkTint = true)
        if (keyboardTheme == KEYBOARD_THEME_BRUSHED) return BrushedDrawables.panel(selectedNeuTokens().mode == NeuMode.DARK, resources.displayMetrics.density)
        if (keyboardTheme == KEYBOARD_THEME_DEFAULT) return Neu.drawable(activeNeuTokens, dp(16).toFloat(), NeuLevel.RAISED)
        val light = keyboardLightMode()
        val colors = if (light) {
            when (keyboardTheme) {
                KEYBOARD_THEME_SKEUO -> intArrayOf(0xFFF1F3F6.toInt(), 0xFFE0E4EA.toInt(), 0xFFC9D0DA.toInt())
                KEYBOARD_THEME_TECLAS -> intArrayOf(0xFFECEFF4.toInt(), 0xFFDDE2E9.toInt(), 0xFFC7CED9.toInt())
                KEYBOARD_THEME_GOKEYS -> intArrayOf(0xFFF3F5F8.toInt(), 0xFFE2E6EC.toInt(), 0xFFC9D0DA.toInt())
                KEYBOARD_THEME_HYPER3D, KEYBOARD_THEME_HYPER3D_LIGHT -> intArrayOf(0xFFE8EBF0.toInt(), 0xFFD4D8E0.toInt(), 0xFFBEC4CF.toInt())
                else -> intArrayOf(0xFFECEEF2.toInt(), 0xFFDDE1E8.toInt(), 0xFFC9D0DA.toInt())
            }
        } else {
            when (keyboardTheme) {
                KEYBOARD_THEME_SKEUO -> intArrayOf(0xFF24262D.toInt(), 0xFF101116.toInt(), 0xFF050506.toInt())
                KEYBOARD_THEME_TECLAS -> intArrayOf(0xFF1A1C21.toInt(), 0xFF111318.toInt(), 0xFF08090C.toInt(), 0xFF030304.toInt())
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
        if (keyboardTheme == KEYBOARD_THEME_SEEME) {
            return SeemeDrawables.key(label, pressed = false, density = resources.displayMetrics.density, goColor = goKeyColor)
        }
        if (keyboardTheme == KEYBOARD_THEME_BRUSHED) {
            val visualLabel = if (label == "shift" && shiftState == ShiftState.LOCK) "shift_lock" else label
            return BrushedDrawables.key(visualLabel, pressed = false, dark = selectedNeuTokens().mode == NeuMode.DARK, density = resources.displayMetrics.density, docked = keyboardPlacement == KEYBOARD_PLACEMENT_DOCKED, goColor = goKeyColor)
        }
        if (label == "enter") return themedGoKeyBackground(goKeyColor, pressed = false, skeuo = keyboardTheme == KEYBOARD_THEME_SKEUO)
        hyper3dKeyDrawable(label)?.let { return it }
        if (keyboardTheme == KEYBOARD_THEME_GOKEYS) return goKeysKeyBackground(label, pressed = false, light = keyboardLightMode())
        if (label == "123") return themed123KeyBackground(pressed = false)
        return when (keyboardTheme) {
            KEYBOARD_THEME_TECLAS -> physicalKeyBackground(pressed = false, premium = false, fn = isFnKey(label), teclas = keyboardTheme == KEYBOARD_THEME_TECLAS, light = keyboardLightMode())
            KEYBOARD_THEME_SKEUO -> physicalKeyBackground(pressed = false, premium = true, fn = isFnKey(label), teclas = keyboardTheme == KEYBOARD_THEME_TECLAS, light = keyboardLightMode())
            else -> keyBackground()
        }
    }

    private fun keyPressedBackground(label: String): Drawable {
        if (keyboardTheme == KEYBOARD_THEME_SEEME) {
            return SeemeDrawables.key(label, pressed = true, density = resources.displayMetrics.density, goColor = brighten(goKeyColor))
        }
        if (keyboardTheme == KEYBOARD_THEME_BRUSHED) {
            val visualLabel = if (label == "shift" && shiftState == ShiftState.LOCK) "shift_lock" else label
            return BrushedDrawables.key(visualLabel, pressed = true, dark = selectedNeuTokens().mode == NeuMode.DARK, density = resources.displayMetrics.density, docked = keyboardPlacement == KEYBOARD_PLACEMENT_DOCKED, goColor = brighten(goKeyColor))
        }
        if (label == "enter") return themedGoKeyBackground(brighten(goKeyColor), pressed = true, skeuo = keyboardTheme == KEYBOARD_THEME_SKEUO)
        hyper3dKeyDrawable(label)?.let { return it }
        if (keyboardTheme == KEYBOARD_THEME_GOKEYS) return goKeysKeyBackground(label, pressed = true, light = keyboardLightMode())
        if (label == "123") return themed123KeyBackground(pressed = true)
        return when (keyboardTheme) {
            KEYBOARD_THEME_TECLAS -> physicalKeyBackground(pressed = true, premium = false, fn = isFnKey(label), teclas = keyboardTheme == KEYBOARD_THEME_TECLAS, light = keyboardLightMode())
            KEYBOARD_THEME_SKEUO -> physicalKeyBackground(pressed = true, premium = true, fn = isFnKey(label), teclas = keyboardTheme == KEYBOARD_THEME_TECLAS, light = keyboardLightMode())
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
                label == "teclas" -> R.drawable.key_hyper3d_black_accent
                isFnKey(label) -> R.drawable.key_hyper3d_black_dark
                else -> R.drawable.key_hyper3d_black
            }
            KEYBOARD_THEME_HYPER3D_LIGHT -> when {
                label == "enter" -> R.drawable.key_hyper3d_light_go
                label == "teclas" -> R.drawable.key_hyper3d_light_accent
                isFnKey(label) -> R.drawable.key_hyper3d_light_dark
                else -> R.drawable.key_hyper3d_light
            }
            else -> return null
        }
        return runCatching { resources.getDrawable(id, theme) }.getOrNull()
    }

    private fun themed123KeyBackground(pressed: Boolean): Drawable {
        hyper3dKeyDrawable("123")?.let { return it }
        if (keyboardTheme == KEYBOARD_THEME_DEFAULT) return Neu.drawable(activeNeuTokens, dp(99).toFloat(), if (pressed) NeuLevel.PRESSED_SM else NeuLevel.RAISED_SM)
        val teclas = keyboardTheme == KEYBOARD_THEME_TECLAS
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
        val faceTop = if (pressed) 0xFF3A3E4A.toInt() else if (teclas) 0xFF22252B.toInt() else 0xFF34373E.toInt()
        val faceMid = if (pressed) 0xFF2A2D36.toInt() else if (teclas) 0xFF171A1F.toInt() else 0xFF202329.toInt()
        val faceBot = if (pressed) 0xFF151719.toInt() else if (teclas) 0xFF07080B.toInt() else 0xFF0B0C10.toInt()
        val skirt = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF050609.toInt()) }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(faceTop, faceMid, faceBot)).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), if (teclas) 0xFF111318.toInt() else 0xFF05060A.toInt())
        }
        val glint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (teclas) 0x10FFFFFF else 0x3DFFFFFF, 0x00FFFFFF
        )).apply { shape = GradientDrawable.OVAL }
        return LayerDrawable(arrayOf(skirt, face, glint)).apply {
            val drop = if (pressed) dp(1) else dp(if (teclas) 4 else 6)
            setLayerInset(0, dp(1), drop, dp(1), 0)
            setLayerInset(1, dp(2), 0, dp(2), drop)
            setLayerInset(2, dp(8), dp(5), dp(8), dp(if (teclas) 20 else 18))
        }
    }

    private fun keyBackground() = keyBackground(null)

    private fun keyBackground(fillColor: Int?): Drawable {
        return if (fillColor != null) {
            Neu.drawable(activeNeuTokens, dp(7).toFloat(), NeuLevel.PRESSED_SM)
        } else {
            Neu.drawable(activeNeuTokens, dp(7).toFloat(), NeuLevel.RAISED_SM)
        }
    }

    private fun keyboardThemePillBackground(value: String): Drawable {
        val accent = when (value) {
            KEYBOARD_THEME_TECLAS -> Accent2
            KEYBOARD_THEME_SKEUO -> 0xFF8FD694.toInt()
            KEYBOARD_THEME_GOKEYS -> 0xFFF2691E.toInt()
            KEYBOARD_THEME_HYPER3D -> 0xFF6C89D8.toInt()
            KEYBOARD_THEME_HYPER3D_BLACK -> 0xFF2C3038.toInt()
            KEYBOARD_THEME_HYPER3D_LIGHT -> 0xFFE7EAF0.toInt()
            KEYBOARD_THEME_BRUSHED -> if (activeNeuTokens.mode == NeuMode.LIGHT) 0xFFD2D6DB.toInt() else 0xFF4A4E55.toInt()
            KEYBOARD_THEME_SEEME -> 0xFFD71921.toInt()
            else -> Accent
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(brighten(accent), accent)).apply {
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), brighten(accent))
        }
    }

    private fun keyRowHeight(): Int {
        val size = effectiveKeyboardSize()
        val base = if (keyboardSettingsOpen) 44 else 56
        val growth = if (keyboardSettingsOpen) 8 else 20
        return dp(base + (size * growth / 100))
    }

    private fun keyVerticalInset(): Int {
        val size = effectiveKeyboardSize()
        if (keyboardTheme == KEYBOARD_THEME_TECLAS || keyboardTheme == KEYBOARD_THEME_GOKEYS || keyboardTheme == KEYBOARD_THEME_BRUSHED || isHyper3dTheme()) return dp(10 + size * 5 / 100)
        return dp(7 + size * 4 / 100)
    }

    private fun keyRowOverlap(): Int {
        val size = effectiveKeyboardSize()
        return dp(12 + size * 3 / 100)
    }

    private fun keyHorizontalInset(): Int {
        return 0
    }

    private fun goKeySize() = dp(43 + (effectiveKeyboardSize() * 8 / 100))

    private fun themedGoKeySize(): Int {
        return dp(39 + (effectiveKeyboardSize() * 6 / 100))
    }

    internal fun clickWheelSize(): Int {
        val size = effectiveKeyboardSize()
        val dockBound = keyboardHeight() - dp(16)
        val widthBound = resources.displayMetrics.widthPixels - dp(18)
        val preferred = dp(272 + (size * 16 / 100))
        return preferred.coerceAtMost(dockBound).coerceAtMost(widthBound).coerceAtLeast(dp(246))
    }

    private fun hintBottomGap() = dp(2 + (effectiveKeyboardSize() * 2 / 100))
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
        val size = effectiveKeyboardSize()
        if (numberPadOpen && label.length == 1 && label[0].isDigit()) return 26f + size * 2f / 100f
        if (keyboardTheme == KEYBOARD_THEME_BRUSHED) {
            val brushedBase = when (label) {
                "shift", "period" -> 22f
                "back" -> 19f
                "space" -> 18f
                "123", "abc", "enter" -> 15.5f
                "teclas" -> 13.5f
                else -> 19f
            }
            val brushedGrowth = when (label) {
                "123", "abc", "enter", "teclas" -> 1.5f
                "space" -> 2f
                else -> 2.5f
            }
            return brushedBase + (size * brushedGrowth / 100f)
        }
        val base = when (label) { "shift" -> 24f; "space" -> 18f; "123", "teclas", "enter", "back", "period", "abc" -> 13.5f; else -> 20f }
        val growth = when (label) { "shift" -> 2.5f; "space" -> 2f; "123", "teclas", "enter", "back", "period", "abc" -> 1.5f; else -> 2.5f }
        return base + (size * growth / 100f)
    }

    private fun goLegendColor(): Int =
        if (selectedNeuTokens().mode == NeuMode.LIGHT) 0xFFFFFFFF.toInt() else 0xFF050506.toInt()

    private fun keyTextColor(label: String) = if (label == "enter") {
        goLegendColor()
    } else if (keyboardTheme == KEYBOARD_THEME_BRUSHED) {
        BrushedDrawables.ink(label, selectedNeuTokens().mode == NeuMode.DARK)
    } else if (keyboardTheme == KEYBOARD_THEME_SEEME) {
        when (label) {
            "teclas" -> 0xFFFF5A60.toInt()
            "123", "back", "shift", "period", "abc" -> 0xFF8A8A8A.toInt()
            else -> 0xFFF2F2F2.toInt()
        }
    } else if (isHyper3dTheme()) {
        val visualTheme = hyper3dVisualTheme()
        when {
            visualTheme == KEYBOARD_THEME_HYPER3D_LIGHT && label == "enter" -> 0xFF104026.toInt()
            visualTheme == KEYBOARD_THEME_HYPER3D_LIGHT && isFnKey(label) -> 0xFF596170.toInt()
            visualTheme == KEYBOARD_THEME_HYPER3D_LIGHT -> 0xFF1E2633.toInt()
            visualTheme == KEYBOARD_THEME_HYPER3D_BLACK && label == "enter" -> 0xFFFF6B6B.toInt()
            visualTheme == KEYBOARD_THEME_HYPER3D_BLACK && label == "teclas" -> 0xFF9DB4FF.toInt()
            visualTheme == KEYBOARD_THEME_HYPER3D_BLACK && isFnKey(label) -> 0xFF9AA2B1.toInt()
            visualTheme == KEYBOARD_THEME_HYPER3D && label == "enter" -> 0xFFEAFFF2.toInt()
            visualTheme == KEYBOARD_THEME_HYPER3D && label == "teclas" -> 0xFFEAF0FF.toInt()
            visualTheme == KEYBOARD_THEME_HYPER3D && isFnKey(label) -> 0xFF9AA2B1.toInt()
            else -> 0xFFEEF1F7.toInt()
        }
    } else if (keyboardTheme == KEYBOARD_THEME_GOKEYS) {
        if (keyboardLightMode()) {
            when (label) {
                "enter" -> 0xFFFFFFFF.toInt()
                "shift" -> when (shiftState) { ShiftState.OFF -> 0xFF6F7884.toInt(); ShiftState.ONCE -> 0xFF242A33.toInt(); ShiftState.LOCK -> 0xFFF2691E.toInt() }
                "123", "teclas", "back", "period", "abc", "space" -> 0xFF6F7884.toInt()
                else -> 0xFF202733.toInt()
            }
        } else
        when (label) {
            "enter" -> 0xFFFFFFFF.toInt()
            "shift" -> when (shiftState) { ShiftState.OFF -> 0xFF9AA1AB.toInt(); ShiftState.ONCE -> Ink; ShiftState.LOCK -> 0xFFF2691E.toInt() }
            "123", "teclas", "back", "period", "abc", "space" -> 0xFF9AA1AB.toInt()
            else -> 0xFFF3F5F8.toInt()
        }
    } else if (keyboardLightMode()) {
        when (label) {
            "enter" -> Neu.ACCENT
            "teclas" -> 0xFF2F6C4C.toInt()
            "shift" -> when (shiftState) { ShiftState.OFF -> 0xFF6F7884.toInt(); ShiftState.ONCE -> 0xFF242A33.toInt(); ShiftState.LOCK -> Neu.ACCENT }
            "123", "back", "period", "abc" -> 0xFF6F7884.toInt()
            else -> 0xFF202733.toInt()
        }
    } else when (label) {
            "enter" -> Neu.ACCENT
            "teclas" -> Neu.GREEN
            "shift" -> when (shiftState) { ShiftState.OFF -> activeNeuTokens.inkDim; ShiftState.ONCE -> activeNeuTokens.ink; ShiftState.LOCK -> Neu.ACCENT }
            "123", "back", "period", "abc" -> activeNeuTokens.inkDim
            else -> activeNeuTokens.ink
    }

    private fun isHyper3dTheme(): Boolean {
        return keyboardTheme == KEYBOARD_THEME_HYPER3D ||
            keyboardTheme == KEYBOARD_THEME_HYPER3D_BLACK ||
            keyboardTheme == KEYBOARD_THEME_HYPER3D_LIGHT
    }

    internal fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun selectedNeuTokens(): NeuTokens {
        return when (themeMode) {
            THEME_MODE_DARK -> Neu.Dark
            THEME_MODE_LIGHT -> Neu.Light
            else -> if (isSystemDarkMode()) Neu.Dark else Neu.Light
        }
    }

    private fun glassLightMode(): Boolean {
        // Glass should follow the environment: if the device is dark, glass becomes dark-tinted
        // even if a stale/light token is active during a phone-mode render.
        return themeMode != THEME_MODE_DARK && !isSystemDarkMode()
    }

    private fun applyTheme(): Boolean {
        val next = selectedNeuTokens()
        val changed = activeNeuTokens.mode != next.mode || activeNeuTokens.base != next.base
        activeNeuTokens = next
        syncSystemBars()
        return changed
    }

    private fun syncSystemBars() {
        if (isFinishing) return
        val mediaDock = openPane?.usesMediaDock() == true
        val innerWallpaperCanvas = isUnfoldedInnerLayoutActive() && openPane == null && !libraryOpen
        val darkBars = mediaDock || activeNeuTokens.mode == NeuMode.DARK
        window.statusBarColor = if (innerWallpaperCanvas) {
            if (activeNeuTokens.mode == NeuMode.LIGHT) 0xFFE9ECEF.toInt() else 0xFF070A0E.toInt()
        } else {
            if (darkBars) Color.BLACK else activeNeuTokens.base
        }
        window.navigationBarColor = if (darkBars) Color.BLACK else activeNeuTokens.base
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        val lightStatus = activeNeuTokens.mode == NeuMode.LIGHT && !mediaDock && !(innerWallpaperCanvas && activeNeuTokens.mode == NeuMode.DARK)
        val lightNav = activeNeuTokens.mode == NeuMode.LIGHT && !mediaDock
        var flags = window.decorView.systemUiVisibility
        flags = if (lightStatus) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        flags = if (lightNav) flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            else flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        window.decorView.systemUiVisibility = flags

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                val appearance = (if (lightStatus) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0) or
                    (if (lightNav) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0)
                controller.setSystemBarsAppearance(
                    appearance,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (mediaDock) controller.hide(WindowInsets.Type.navigationBars())
                else controller.show(WindowInsets.Type.navigationBars())
            }
        }
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
            renderPaneContent(teclasSettingsTarget())
        }
        if (::rootView.isInitialized) {
            showThemeSplash(activeNeuTokens.base, activeNeuTokens.base, blendColors(oldAccent, color, 0.72f))
        }
    }

    private fun isSystemDarkMode(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    internal fun haptic(view: View) {
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
    internal fun popThenRun(view: View, action: () -> Unit) {
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

    private fun seemeReleaseHaptic(view: View) {
        if (!hapticsEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
        }
    }

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
            val light = mode == NeuMode.LIGHT
            val base = if (light) 0xFFEAF0F5.toInt() else 0xFF181B21.toInt()
            val baseHi = if (light) Color.WHITE else 0xFF20232A.toInt()
            val baseLo = if (light) 0xFFD9E0E8.toInt() else 0xFF14161B.toInt()
            val edgeInset = dp(3).toFloat()
            val bodyRadius = dp(30).toFloat()
            val body = RectF(edgeInset, edgeInset, width - edgeInset, height - edgeInset)

            paint.style = Paint.Style.STROKE
            repeat(4) { index ->
                val grow = dp(index + 1).toFloat()
                val alpha = (if (light) 0.14f else 0.42f) * dragProgress / (index + 1)
                paint.strokeWidth = grow
                paint.color = adjustAlpha(if (light) Color.WHITE else Color.BLACK, alpha)
                canvas.drawRoundRect(
                    RectF(body.left - grow, body.top - grow, body.right + grow, body.bottom + grow),
                    bodyRadius + grow,
                    bodyRadius + grow,
                    paint
                )
            }
            paint.style = Paint.Style.FILL

            paint.shader = android.graphics.LinearGradient(
                body.left, body.top, body.right, body.bottom,
                intArrayOf(
                    adjustAlpha(baseHi, if (light) 0.34f else 0.22f),
                    adjustAlpha(base, if (light) 0.16f else 0.14f),
                    adjustAlpha(baseLo, if (light) 0.25f else 0.30f)
                ),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(body, bodyRadius, bodyRadius, paint)
            paint.shader = null

            paint.shader = android.graphics.LinearGradient(
                0f,
                body.top,
                0f,
                body.bottom,
                intArrayOf(
                    adjustAlpha(Color.WHITE, if (light) 0.34f else 0.16f),
                    adjustAlpha(Color.WHITE, if (light) 0.10f else 0.03f),
                    adjustAlpha(Color.BLACK, if (light) 0.06f else 0.22f)
                ),
                floatArrayOf(0f, 0.28f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(body, bodyRadius, bodyRadius, paint)
            paint.shader = null

            paint.shader = RadialGradient(
                width * 0.52f,
                body.top + dp(28),
                width * 0.78f,
                intArrayOf(
                    adjustAlpha(Color.WHITE, if (light) 0.30f else 0.13f),
                    adjustAlpha(Color.WHITE, if (light) 0.08f else 0.035f),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.38f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(body, bodyRadius, bodyRadius, Path.Direction.CW) })
            canvas.drawCircle(width * 0.52f, body.top + dp(28), width * 0.78f, paint)
            canvas.restore()
            paint.shader = null

            val glowAlpha = (if (light) 0.16f else 0.18f) * ambientStrength * (0.45f + dragProgress * 0.55f)
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
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(body, bodyRadius, bodyRadius, Path.Direction.CW) })
            canvas.drawCircle(width * 0.24f, height * 0.20f, width * 0.82f, paint)
            canvas.restore()
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.shader = android.graphics.LinearGradient(
                0f, body.top, 0f, body.bottom,
                intArrayOf(
                    adjustAlpha(Color.WHITE, if (light) 0.58f else 0.38f),
                    adjustAlpha(Color.WHITE, if (light) 0.12f else 0.05f),
                    adjustAlpha(Color.BLACK, if (light) 0.18f else 0.62f)
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(body, bodyRadius, bodyRadius, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL

            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(body, bodyRadius, bodyRadius, Path.Direction.CW) })
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = adjustAlpha(Color.WHITE, if (light) 0.20f else 0.10f)
            val streakGap = dp(96).toFloat()
            var sx = -height.toFloat()
            while (sx < width + height) {
                canvas.drawLine(sx, body.top + dp(22), sx + height * 0.42f, body.bottom - dp(38), paint)
                sx += streakGap
            }
            paint.style = Paint.Style.FILL
            canvas.restore()

            val edgeWidth = dp(34).toFloat()
            rect.set(0f, dp(78).toFloat(), edgeWidth, height - dp(74).toFloat())
            paint.shader = android.graphics.LinearGradient(
                rect.left, 0f, rect.right, 0f,
                intArrayOf(
                    adjustAlpha(baseHi, if (light) 0.38f else 0.24f),
                    adjustAlpha(base, 0.86f),
                    adjustAlpha(baseLo, if (light) 0.34f else 0.72f)
                ),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, dp(18).toFloat(), dp(18).toFloat(), paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = adjustAlpha(0xFFFFFFFF.toInt(), if (light) 0.32f else 0.14f)
            canvas.drawLine(dp(4).toFloat(), rect.top + dp(16), dp(4).toFloat(), rect.bottom - dp(16), paint)
            paint.color = adjustAlpha(0xFF000000.toInt(), if (light) 0.18f else 0.58f)
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

    internal inner class DynamicGlassPlate(
        context: Context,
        private val radiusDp: Int,
        private val strength: Float = 1f,
        private val edgeInsetDp: Int = 3
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var progress = 1f

        init {
            setWillNotDraw(false)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        fun setGlassProgress(value: Float) {
            val next = value.coerceIn(0f, 1f)
            if (abs(next - progress) < 0.01f) return
            progress = next
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            val light = activeNeuTokens.mode == NeuMode.LIGHT
            val glassTop = if (light) Color.WHITE else 0xFF20232A.toInt()
            val glassMid = if (light) 0xFFEAF0F5.toInt() else 0xFF181B21.toInt()
            val glassBottom = if (light) 0xFFD9E0E8.toInt() else 0xFF14161B.toInt()
            val radius = dp(radiusDp).toFloat()
            val alphaBoost = strength.coerceIn(0.5f, 2.25f)
            val edgeInset = dp(edgeInsetDp).toFloat()
            rect.set(edgeInset, edgeInset, width - edgeInset, height - edgeInset)

            paint.style = Paint.Style.STROKE
            repeat(3) { index ->
                val grow = dp(index + 1).toFloat()
                paint.strokeWidth = grow
                paint.color = adjustAlpha(
                    if (light) Color.WHITE else Color.BLACK,
                    ((if (light) 0.14f else 0.28f) * alphaBoost).coerceAtMost(0.74f) * progress / (index + 1)
                )
                canvas.drawRoundRect(
                    RectF(rect.left - grow, rect.top - grow, rect.right + grow, rect.bottom + grow),
                    radius + grow,
                    radius + grow,
                    paint
                )
            }
            paint.style = Paint.Style.FILL

            paint.shader = android.graphics.LinearGradient(
                0f,
                rect.top,
                0f,
                rect.bottom,
                intArrayOf(
                    adjustAlpha(glassTop, ((if (light) 0.38f else 0.68f) * alphaBoost).coerceAtMost(if (light) 0.78f else 0.86f) * progress),
                    adjustAlpha(glassMid, ((if (light) 0.28f else 0.50f) * alphaBoost).coerceAtMost(if (light) 0.62f else 0.78f) * progress),
                    adjustAlpha(glassBottom, ((if (light) 0.34f else 0.70f) * alphaBoost).coerceAtMost(if (light) 0.68f else 0.90f) * progress)
                ),
                floatArrayOf(0f, 0.46f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null

            paint.shader = RadialGradient(
                width * 0.18f,
                height * 0.08f,
                width * 0.88f,
                intArrayOf(
                    adjustAlpha(Color.WHITE, ((if (light) 0.22f else 0.12f) * alphaBoost).coerceAtMost(if (light) 0.52f else 0.24f) * progress),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
            canvas.drawRoundRect(rect, radius, radius, paint)
            canvas.restore()
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.shader = android.graphics.LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat(),
                intArrayOf(
                    adjustAlpha(Color.WHITE, ((if (light) 0.48f else 0.34f) * alphaBoost).coerceAtMost(if (light) 0.82f else 0.56f) * progress),
                    adjustAlpha(Color.WHITE, (0.04f * alphaBoost).coerceAtMost(0.12f) * progress),
                    adjustAlpha(Color.BLACK, ((if (light) 0.12f else 0.54f) * alphaBoost).coerceAtMost(if (light) 0.24f else 0.88f) * progress)
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
            val hairlineInset = resources.displayMetrics.density * 0.5f
            canvas.drawRoundRect(
                RectF(rect.left + hairlineInset, rect.top + hairlineInset, rect.right - hairlineInset, rect.bottom - hairlineInset),
                radius,
                radius,
                paint
            )
            paint.shader = null
            paint.style = Paint.Style.FILL
        }
    }

    private class WeatherParticle(var x: Float, var y: Float, var vx: Float, var vy: Float,
                                  var len: Float, var size: Float, var sway: Float, var phase: Float, var a: Float)

    private inner class WeatherAmbientView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rainPath = Path()
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
        private var particleStartedAt = 0L
        private var particleUntilMs = 0L
        private var motionScale = 1f
        private var alphaScale = 1f
        private val rnd = java.util.Random()

        fun setWeather(code: Int, neuMode: NeuMode, animate: Boolean) {
            val changed = weatherCode != code || mode != neuMode
            weatherCode = code
            mode = neuMode
            animator?.cancel()
            val shouldAnimate = animate && changed && animatedWeatherEnabled()
            if (shouldAnimate) {
                particleStartedAt = System.currentTimeMillis()
                particleUntilMs = particleStartedAt + WEATHER_AMBIENT_BURST_MS
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
                particleUntilMs = 0L
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
            val canPlay = p != 0 && animatedWeatherEnabled() && particleUntilMs > System.currentTimeMillis()
            if (!canPlay) { stopLoop(); invalidate(); return }
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
            val now = System.currentTimeMillis()
            if (now >= particleUntilMs && parts.isEmpty() && fog.isEmpty()) {
                stopLoop()
                return
            }
            val fade = livePhotoFade(now - particleStartedAt, WEATHER_AMBIENT_BURST_MS)
            motionScale = 0.22f + 0.78f * fade
            alphaScale = fade
            if (now >= particleUntilMs && fade <= 0.02f) {
                stopLoop()
                return
            }
            if (parts.isEmpty() && fog.isEmpty()) seed(p)
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
            val color = if (mode == NeuMode.LIGHT) 0xFF2A94CA.toInt() else 0xFF8EDCFF.toInt()
            for (d in parts) {
                d.x += d.vx * dt * motionScale; d.y += d.vy * dt * motionScale
                if (d.y - d.len > h) { d.y = -rnd.nextFloat() * h * 0.15f; d.x = rnd.nextFloat() * w }
                if (d.x > w) d.x -= w
                drawRainDrop(canvas, d.x, d.y, d.vx, d.len, d.size, d.a * alphaScale, color)
            }
            paint.style = Paint.Style.FILL
            paint.shader = null
        }

        private fun drawRainDrop(canvas: Canvas, x: Float, y: Float, vx: Float, len: Float, size: Float, alpha: Float, color: Int) {
            val slant = vx * 0.017f
            val topX = x - slant
            val topY = y - len
            val half = (size * 1.25f).coerceAtLeast(resources.displayMetrics.density * 0.9f)
            rainPath.reset()
            rainPath.moveTo(topX, topY)
            rainPath.cubicTo(
                topX + half * 1.8f, topY + len * 0.32f,
                x + half * 1.4f, y - half * 1.6f,
                x + half * 0.35f, y
            )
            rainPath.cubicTo(
                x - half * 0.55f, y + half * 0.15f,
                x - half * 1.55f, y - half * 1.7f,
                topX, topY
            )
            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.LinearGradient(
                topX,
                topY,
                x,
                y,
                intArrayOf(
                    0x00000000,
                    adjustAlpha(color, alpha * 0.58f),
                    adjustAlpha(0xFFFFFFFF.toInt(), alpha * 0.46f)
                ),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(rainPath, paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (half * 0.26f).coerceAtLeast(0.7f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = adjustAlpha(0xFFFFFFFF.toInt(), alpha * 0.32f)
            canvas.drawLine(topX + half * 0.35f, topY + len * 0.30f, x - half * 0.15f, y - half * 1.1f, paint)
            paint.style = Paint.Style.FILL
        }

        private fun drawSnow(canvas: Canvas, dt: Float) {
            val w = width.toFloat(); val h = height.toFloat()
            val color = if (mode == NeuMode.LIGHT) 0xFFFFFFFF.toInt() else 0xFFEAF1FF.toInt()
            paint.style = Paint.Style.FILL
            for (d in parts) {
                d.phase += dt * 1.6f
                d.y += d.vy * dt * motionScale
                d.x += kotlin.math.sin(d.phase.toDouble()).toFloat() * d.sway * dt * motionScale
                if (d.y - d.size > h) { d.y = -d.size - rnd.nextFloat() * h * 0.1f; d.x = rnd.nextFloat() * w }
                paint.color = adjustAlpha(color, d.a * alphaScale)
                canvas.drawCircle(d.x, d.y, d.size, paint)
            }
        }

        private fun drawFog(canvas: Canvas, dt: Float) {
            val w = width.toFloat()
            val color = if (mode == NeuMode.LIGHT) 0xFFFFFFFF.toInt() else 0xFFB8C2D0.toInt()
            for (f in fog) {
                f.x += f.vx * dt * motionScale
                if (f.x - f.size > w) f.x = -f.size
                if (f.x + f.size < 0f) f.x = w + f.size
                paint.shader = RadialGradient(f.x, f.y, f.size.coerceAtLeast(1f),
                    intArrayOf(adjustAlpha(color, f.a * alphaScale), 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                canvas.drawCircle(f.x, f.y, f.size, paint)
                paint.shader = null
            }
        }

        private fun drawLightning(canvas: Canvas, dt: Float) {
            nextBolt -= dt
            if (nextBolt <= 0f) { flash = 1f; nextBolt = 3.5f + rnd.nextFloat() * 6f }
            if (flash > 0f) {
                paint.color = adjustAlpha(0xFFFFFFFF.toInt(), 0.11f * flash * alphaScale)
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
        55, 57, 65, 67, 82, 99 -> 9f
        53, 63, 81, 96 -> 6f
        else -> 3.6f
    }

    private class DripDrop(var x: Float, var edgeY: Float, var y: Float, var grow: Float,
                           var r: Float, var vy: Float, var released: Boolean, var a: Float)

    // Foreground overlay that lets rain "wet" the widgets: droplets bead up along a widget's bottom
    // edge, swell, then release and fall off. Active only on rainy codes + Animated Weather; passes
    // touches straight through and pauses when off-screen.
    private inner class WeatherDripView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dropPath = Path()
        private val drips = ArrayList<DripDrop>()
        private val edgeBuf = ArrayList<FloatArray>()
        private var running = false
        private var lastFrame = 0L
        private var spawnAcc = 0f
        private var burstStartedAt = 0L
        private var burstUntilMs = 0L
        private val rnd = java.util.Random()

        fun refresh(playMoment: Boolean = false) {
            val active = weatherIsRainy() && animatedWeatherEnabled() && homeWidgetStackVisible()
            if (active) {
                if (playMoment) playLivePhotoBurst()
            } else {
                stop()
            }
        }

        fun playLivePhotoBurst() {
            if (!weatherIsRainy() || !animatedWeatherEnabled() || !homeWidgetStackVisible()) {
                stop()
                return
            }
            burstStartedAt = System.currentTimeMillis()
            burstUntilMs = burstStartedAt + WEATHER_DRIP_BURST_MS
            running = true
            lastFrame = 0L
            spawnAcc = 0f
            postInvalidateOnAnimation()
        }

        private fun stop() {
            running = false
            burstUntilMs = 0L
            drips.clear()
            invalidate()
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
            val fade = livePhotoFade(now - burstStartedAt, WEATHER_DRIP_BURST_MS)
            val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
            lastFrame = now
            val d = resources.displayMetrics.density
            collectEdges()
            if (edgeBuf.isNotEmpty() && now < burstUntilMs && fade > 0.04f) {
                spawnAcc += dt * weatherDripRate() * fade
                while (spawnAcc >= 1f) {
                    spawnAcc -= 1f
                    val e = edgeBuf[rnd.nextInt(edgeBuf.size)]
                    val x = e[0] + rnd.nextFloat() * (e[1] - e[0])
                    drips.add(DripDrop(x, e[2], e[2], 0f, (2.8f + rnd.nextFloat() * 2.4f) * d, 0f, false, 0.68f + rnd.nextFloat() * 0.24f))
                }
            }
            val color = 0xFF34B7F4.toInt()
            val h = height.toFloat()
            paint.style = Paint.Style.FILL
            val iter = drips.iterator()
            while (iter.hasNext()) {
                val drop = iter.next()
                if (!drop.released) {
                    drop.grow += dt * (0.72f + 0.28f * fade)
                    val t = (drop.grow / 0.9f).coerceIn(0f, 1f)
                    val rr = drop.r * (0.45f + 0.55f * t)
                    drawBeadedDrop(canvas, drop.x, drop.edgeY + rr * 0.8f, rr, drop.a * (0.35f + 0.65f * fade), color, attached = true)
                    if (drop.grow >= 0.9f) { drop.released = true; drop.y = drop.edgeY + rr; drop.vy = 24f * d }
                } else {
                    drop.vy += 950f * d * dt
                    drop.y += drop.vy * dt
                    drop.a -= dt * 0.45f
                    if (drop.a <= 0f || drop.y - drop.r > h) { iter.remove(); continue }
                    drawBeadedDrop(canvas, drop.x, drop.y, drop.r * 0.95f, drop.a * (0.50f + 0.50f * fade), color, attached = false)
                }
            }
            if (now < burstUntilMs || drips.isNotEmpty()) {
                postInvalidateOnAnimation()
            } else {
                running = false
                invalidate()
            }
        }

        private fun drawBeadedDrop(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float, color: Int, attached: Boolean) {
            val top = cy - r * if (attached) 0.75f else 1.35f
            val bottom = cy + r * if (attached) 0.82f else 1.28f
            dropPath.reset()
            dropPath.moveTo(cx, top)
            dropPath.cubicTo(cx + r * 1.06f, cy - r * 0.45f, cx + r * 0.92f, bottom - r * 0.16f, cx, bottom)
            dropPath.cubicTo(cx - r * 0.92f, bottom - r * 0.16f, cx - r * 1.06f, cy - r * 0.45f, cx, top)
            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(
                cx - r * 0.35f,
                cy - r * 0.45f,
                r * 1.65f,
                intArrayOf(
                    adjustAlpha(0xFFFFFFFF.toInt(), alpha * 0.74f),
                    adjustAlpha(color, alpha * 0.88f),
                    adjustAlpha(0xFF0A5D88.toInt(), alpha * 0.34f)
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(dropPath, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (r * 0.16f).coerceAtLeast(0.75f)
            paint.color = adjustAlpha(0xFFFFFFFF.toInt(), alpha * 0.34f)
            canvas.drawPath(dropPath, paint)
            paint.style = Paint.Style.FILL
            paint.color = adjustAlpha(0xFFFFFFFF.toInt(), alpha * 0.58f)
            canvas.drawOval(cx - r * 0.35f, cy - r * 0.55f, cx - r * 0.06f, cy - r * 0.16f, paint)
            paint.color = adjustAlpha(0xFF9CE8FF.toInt(), alpha * 0.18f)
            canvas.drawCircle(cx, cy, r * 1.45f, paint)
        }
    }

    private fun isWeatherNight(): Boolean {
        val hour = LocalTime.now().hour
        return hour < 6 || hour >= 19
    }

    private inner class AnimatedWeatherIconView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val iconDropPath = Path()
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
            val fade = if (running) livePhotoFade(now - animationStartedAt, WEATHER_ANIMATION_BURST_MS) else 0.82f
            val cycleMs = (2600L + ((1f - fade) * 1600L).toLong()).coerceAtLeast(2600L)
            val t = if (running) ((now - animationStartedAt) % cycleMs) / cycleMs.toFloat() else 0.12f
            val cx = width / 2f
            val cy = height / 2f
            when (weatherCode) {
                45, 48 -> drawCloud(canvas, cx, cy, 0xFFB7BBC4.toInt(), t, fog = true)
                51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> drawCloud(canvas, cx, cy, 0xFF8FD694.toInt(), t, rain = true, weatherAlpha = fade)
                71, 73, 75, 77, 85, 86 -> drawCloud(canvas, cx, cy, 0xFFE8EDF7.toInt(), t, snow = true)
                95, 96, 99 -> drawCloud(canvas, cx, cy, Accent, t, rain = true, weatherAlpha = fade)
                1, 2, 3 -> {
                    if (isWeatherNight()) {
                        drawMoon(canvas, cx - dp(7), cy - dp(5), t)
                    } else {
                        drawSun(canvas, cx - dp(7), cy - dp(5), t)
                    }
                    drawCloud(canvas, cx + dp(4), cy + dp(5), 0xFFD8DCE6.toInt(), t)
                }
                else -> {
                    if (isWeatherNight()) drawMoon(canvas, cx, cy, t) else drawSun(canvas, cx, cy, t)
                }
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

        private fun drawMoon(canvas: Canvas, cx: Float, cy: Float, t: Float) {
            val pulse = 1f + kotlin.math.sin(t * Math.PI * 2).toFloat() * 0.018f
            paint.shader = RadialGradient(
                cx - dp(2),
                cy - dp(3),
                dp(22) * pulse,
                intArrayOf(0x559DB4FF, 0x1A9DB4FF, 0x00000000),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, dp(22) * pulse, paint)
            paint.shader = null
            paint.color = 0xFFEAF1FF.toInt()
            canvas.drawCircle(cx, cy, dp(12) * pulse, paint)
            paint.color = if (activeNeuTokens.mode == NeuMode.LIGHT) 0xFFE7ECF2.toInt() else 0xFF141820.toInt()
            canvas.drawCircle(cx + dp(5), cy - dp(4), dp(11) * pulse, paint)
            paint.color = 0x99DCE6FF.toInt()
            canvas.drawCircle(cx - dp(9), cy + dp(13), dp(1).toFloat(), paint)
            canvas.drawCircle(cx + dp(13), cy - dp(13), dp(1).toFloat(), paint)
        }

        private fun drawCloud(canvas: Canvas, cx: Float, cy: Float, color: Int, t: Float, rain: Boolean = false, snow: Boolean = false, fog: Boolean = false, weatherAlpha: Float = 1f) {
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
                    repeat(3) { i ->
                        val x = cx - dp(10) + i * dp(10) + drift
                        val y = cy + dp(18) + ((t * dp(8)) % dp(8))
                        drawIconRainDrop(canvas, x, y + dp(4), resources.displayMetrics.density * 3.15f, 0xFF34B7F4.toInt(), weatherAlpha)
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

        private fun drawIconRainDrop(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, alpha: Float) {
            iconDropPath.reset()
            iconDropPath.moveTo(cx, cy - r * 1.55f)
            iconDropPath.cubicTo(cx + r * 1.05f, cy - r * 0.55f, cx + r * 0.86f, cy + r * 0.72f, cx, cy + r * 1.18f)
            iconDropPath.cubicTo(cx - r * 0.86f, cy + r * 0.72f, cx - r * 1.05f, cy - r * 0.55f, cx, cy - r * 1.55f)
            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.LinearGradient(
                cx,
                cy - r * 1.5f,
                cx,
                cy + r * 1.2f,
                intArrayOf(adjustAlpha(0xFFFFFFFF.toInt(), 0.82f * alpha), adjustAlpha(color, 0.96f * alpha), adjustAlpha(0xFF045D8A.toInt(), 0.68f * alpha)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(iconDropPath, paint)
            paint.shader = null
            paint.color = adjustAlpha(0xFFFFFFFF.toInt(), 0.70f * alpha)
            canvas.drawOval(cx - r * 0.32f, cy - r * 0.78f, cx - r * 0.05f, cy - r * 0.34f, paint)
            paint.style = Paint.Style.FILL
        }
    }

    internal fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // ── Types ────────────────────────────────────────────────────────────────

    // NOTE: Pure model types (PaneKind, PaneTarget, AppEntry, SearchResult, FlightSegment, …)
    // moved verbatim to LauncherModels.kt. Behaviour unchanged; referenced here by simple name.

    companion object {
        private const val Screen = 0xFF0D0E11.toInt()
        private const val Panel = 0xFF16181D.toInt()
        private const val Panel2 = 0xFF1D2027.toInt()
        internal const val Ink = 0xFFF3F0E7.toInt()
        internal const val InkDim = 0xFF8B8F99.toInt()
        private const val Accent = 0xFFFF5A3C.toInt()
        private const val Accent2 = 0xFFF5C451.toInt()
        private const val Line = 0xFF2A2C33.toInt()
        private const val Key = 0xFF23262E.toInt()
        private const val KeyEdge = 0xFF34373F.toInt()
        private const val KeyHighlight = 0xFF3A3E4A.toInt()
        private const val PREFS_NAME = "teclas"
        private const val KEYBOARD_SIZE_PREF = "keyboard_size"
        private const val INNER_KEYBOARD_WIDTH_PREF = "inner_keyboard_width_percent"
        private const val INNER_KEYBOARD_SIZE_BOOST_PREF = "inner_keyboard_size_boost"
        private const val INNER_KEYBOARD_OFFSET_X_PREF = "inner_keyboard_offset_x"
        private const val INNER_KEYBOARD_OFFSET_Y_PREF = "inner_keyboard_offset_y"
        private const val TOUCH_MODEL_PREF = "touch_model_v1"
        private const val APP_ICON_SIZE_PREF = "app_icon_size"
        private const val KEYBOARD_PLACEMENT_PREF = "keyboard_placement"
        private const val KEYBOARD_PLACEMENT_INTRO_PREF = "keyboard_placement_intro_shown"
        private const val KEYBOARD_PLACEMENT_DOCKED = "docked"
        internal const val KEYBOARD_PLACEMENT_WIDGET = "widget"
        private const val THEME_MODE_PREF = "theme_mode"
        private const val THEME_MODE_DARK = "dark"
        private const val THEME_MODE_LIGHT = "light"
        private const val THEME_MODE_SYSTEM = "system"
        private const val KEYBOARD_THEME_PREF = "keyboard_theme"
        private const val KEYBOARD_THEME_DEFAULT = "default"
        private const val KEYBOARD_THEME_TECLAS = "teclas"
        private const val KEYBOARD_THEME_SKEUO = "skeuo"
        private const val KEYBOARD_THEME_GOKEYS = "gokeys"
        private const val KEYBOARD_THEME_HYPER3D = "hyper3d"
        private const val KEYBOARD_THEME_HYPER3D_BLACK = "hyper3d_black"
        private const val KEYBOARD_THEME_HYPER3D_LIGHT = "hyper3d_light"
        private const val KEYBOARD_THEME_BRUSHED = "brushed"
        private const val KEYBOARD_THEME_SEEME = "seeme"
        private const val KEYBOARD_SWAP_ANIMATION_PREF = "keyboard_swap_animation"
        private const val KEYBOARD_SWAP_ANIMATION_DEFAULT = "default"
        private const val KEYBOARD_SWAP_ANIMATION_POPOUT = "pop_out"
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
        // Space id the user last swiped the dock back to Pinned in. While the active Space
        // still equals this, the pinned view is respected (no auto-flip to context). Cleared
        // when the Space changes, which re-arms the auto-switch-to-context behavior.
        private const val DOCK_PINNED_OVERRIDE_SPACE_PREF = "dock_pinned_override_space"
        // Fixed layout id for the personal left widget page (not per-Space).
        private const val HOME_LEFT_BOARD_ID = "home_left"
        private const val APP_LIBRARY_DEFAULT_HOME_PREF = "app_library_default_home"
        private const val ANIMATED_WEATHER_PREF = "animated_weather"
        private const val GLASS_EFFECTS_PREF = "glass_effects"
        private const val HOME_LOCK_WALLPAPER_PREF = "home_lock_wallpaper"
        private const val HOME_COVER_WALLPAPER_URI_PREF = "home_cover_wallpaper_uri"
        private const val HOME_COVER_WALLPAPER_ZOOM_PREF = "home_cover_wallpaper_zoom"
        private const val HOME_COVER_WALLPAPER_OFFSET_X_PREF = "home_cover_wallpaper_offset_x"
        private const val HOME_COVER_WALLPAPER_OFFSET_Y_PREF = "home_cover_wallpaper_offset_y"
        private const val HOME_INNER_WALLPAPER_URI_PREF = "home_inner_wallpaper_uri"
        private const val HOME_INNER_WALLPAPER_ZOOM_PREF = "home_inner_wallpaper_zoom"
        private const val HOME_INNER_WALLPAPER_OFFSET_X_PREF = "home_inner_wallpaper_offset_x"
        private const val HOME_INNER_WALLPAPER_OFFSET_Y_PREF = "home_inner_wallpaper_offset_y"
        private const val COVER_WIDGET_KEYBOARD_HIDDEN_PREF = "cover_widget_keyboard_hidden"
        private const val INNER_WIDGET_KEYBOARD_HIDDEN_PREF = "inner_widget_keyboard_hidden"
        private const val INNER_LIBRARY_LOCKED_PREF = "inner_library_locked"
        private const val DEV_EXPERIMENTS_PREF = "dev_experiments"
        private const val GRID_WORKSPACE_LAB_PREF = "grid_workspace_lab"
        private const val DOCK_APP_LIMIT = 5
        private const val OFTEN_USED_LIMIT = 8
        private const val APP_USAGE_PREF = "app_usage_counts"
        private const val APP_LAST_LAUNCH_PREF = "app_last_launch_times"
        private const val WEATHER_TEMP_PREF = "weather_temp"
        private const val WEATHER_META_PREF = "weather_meta"
        private const val WEATHER_FEELS_PREF = "weather_feels"
        private const val WEATHER_STATS_PREF = "weather_stats"
        private const val WEATHER_CODE_PREF = "weather_code"
        private const val WEATHER_FETCHED_AT_PREF = "weather_fetched_at"
        private const val WEATHER_ANIMATION_BURST_MS = 6500L
        private const val WEATHER_AMBIENT_BURST_MS = 6500L
        private const val WEATHER_DRIP_BURST_MS = 6500L
        private const val WIDGET_HOST_ID = 1407
        private const val WIDGET_BIND_REQUEST_CODE = 501
        private const val WIDGET_CONFIGURE_REQUEST_CODE = 502
        private const val WIDGET_IDS_PREF = "widget_board_ids"
        private const val WIDGET_STACK_MODE_PREF_PREFIX = "widget_stack_mode_"
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
        internal const val GEMINI_API_KEY_PREF = "gemini_api_key"
        internal const val GEMINI_MODEL_PREF = "gemini_model"
        internal const val GEMINI_DEFAULT_MODEL = "gemini-2.5-flash"
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
}
