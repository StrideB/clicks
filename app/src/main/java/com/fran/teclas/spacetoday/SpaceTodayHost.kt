package com.fran.teclas.spacetoday

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fran.teclas.MainActivity
import com.fran.teclas.brief.BriefAction
import com.fran.teclas.brief.TodayKeyboardMode
import com.fran.teclas.galaxy.NowBarLiveUpdate
import com.fran.teclas.predict.SpaceManager
import com.fran.teclas.spacetoday.data.PriorityFeedRepository
import com.fran.teclas.spacetoday.data.WorkItemCache
import com.fran.teclas.spacetoday.data.ZoneResolver
import com.fran.teclas.spacetoday.model.WorkAction
import com.fran.teclas.spacetoday.model.WorkItem
import com.fran.teclas.spacetoday.model.SpaceWorkload
import com.fran.teclas.spacetoday.rank.LearningStore
import com.fran.teclas.spacetoday.rank.LlmRanker
import com.fran.teclas.spacetoday.rank.PreScorer
import com.fran.teclas.spacetoday.theme.TodayThemeStore
import com.fran.teclas.spacetoday.theme.TodayThemes
import com.fran.teclas.spacetoday.ui.HomeEdgeHint
import com.fran.teclas.spacetoday.ui.SpaceTodayScreen
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Thin MainActivity bridge for Space Today. Everything substantial lives in the spacetoday package;
 * Main only instantiates this host and routes the existing Space-board gesture here.
 */
internal class SpaceTodayHost(private val activity: MainActivity) {
    private val prefs = activity.prefs()
    private val themeStore = TodayThemeStore(prefs)
    private val learningStore = LearningStore(prefs)
    private val repository = PriorityFeedRepository(
        context = activity.applicationContext,
        briefRepository = activity.briefRepository,
        zoneResolver = ZoneResolver(prefs),
        preScorer = PreScorer(learningStore),
        llmRanker = LlmRanker(prefs),
        learningStore = learningStore,
        cache = WorkItemCache(prefs),
        scope = activity.mediaUiScope,
        activeSpaceProvider = { activeSpaceId() }
    )
    private val edgeHint = HomeEdgeHint(activity) { showSpaceFan() }

    private var overlay: FrameLayout? = null
    private var themeOverlay: View? = null
    private var fanOverlay: View? = null
    private var viewedSpace by mutableStateOf(activeSpaceId())
    private var themeTick by mutableIntStateOf(0)
    private var downX = 0f
    private var downY = 0f
    /**
     * True once THIS overlay has seen the ACTION_DOWN of the gesture currently in flight.
     *
     * The panel is opened BY a swipe, so that gesture's ACTION_DOWN lands on the launcher — the
     * overlay does not exist yet. The overlay is then added mid-gesture and receives the ACTION_UP
     * with no DOWN of its own, leaving downX at its default 0f. The dismiss test then computed
     * dx = ev.x - 0f — the finger's absolute X, always far beyond the dp(72) threshold — so the
     * panel closed itself the instant it opened. Only evaluate the swipe-to-dismiss when the
     * gesture actually began on this view.
     */
    private var gestureStartedHere = false

    /**
     * True when this gesture began in the panel's left edge strip, the only place a swipe means
     * "dismiss".
     *
     * dispatchTouchEvent runs BEFORE the Compose child, so the dismiss test saw every horizontal
     * drag in the panel — including scrolling the Space switcher, which is a horizontally scrolling
     * Row. Dragging it sideways to reach another Space produced a large dx on ACTION_UP and closed
     * the board, which made every Space except the active one unreachable. Compose consuming the
     * scroll does not help: the check happens on the same event either way.
     *
     * Requiring an edge origin is the conventional swipe-to-dismiss contract and cannot collide
     * with anything scrollable inside the panel.
     */
    private var gestureFromEdge = false

    /** Uptime of the last open, for the [close] grace window. */
    private var openedAtMs = 0L

    init {
        activity.mediaUiScope.launch {
            repository.workloads.collect { syncGalaxyNowBar(it) }
        }
    }

    fun shouldHandleActiveSpace(): Boolean = repository.hasWorkload(activeSpaceId())

    fun openActive() = open(activeSpaceId())

    fun open(spaceId: String) {
        if (!activity.hasContentFrame() || activity.libraryOpen || activity.openPane != null) return
        viewedSpace = spaceId
        gestureStartedHere = false   // the opening swipe is not a dismiss gesture for this overlay
        // Claim "open" BEFORE refreshing. refresh() emits a new brief, and MainActivity's brief
        // collector calls render(), which rebuilds contentFrame and would destroy this panel. The
        // flag is what tells that collector to stand down, so it has to be set first.
        activity.todayOpen = true
        android.util.Log.d(TAG, "open($spaceId) todayOpen=true, refreshing")
        repository.refresh()
        NowBarLiveUpdate.clearSpaceWork(activity)
        activity.cancelWallpaperLongPress()
        openedAtMs = android.os.SystemClock.uptimeMillis()
        val existing = overlay
        if (existing != null) {
            android.util.Log.d(TAG, "open(): already open, bringing to front")
            existing.bringToFront()
            return
        }
        val host = object : FrameLayout(activity) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.x
                        downY = ev.y
                        gestureStartedHere = true
                        // Left edge strip only — proportional so it scales with the panel, clamped
                        // so it is neither a hairline nor half the screen on a tablet/fold.
                        val edge = (width * 0.12f).coerceIn(activity.dp(20).toFloat(), activity.dp(56).toFloat())
                        gestureFromEdge = ev.x <= edge
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        gestureStartedHere = false
                        gestureFromEdge = false
                    }
                    MotionEvent.ACTION_UP -> {
                        val startedHere = gestureStartedHere
                        val fromEdge = gestureFromEdge
                        gestureStartedHere = false
                        gestureFromEdge = false
                        // An UP with no matching DOWN belongs to the gesture that opened this
                        // panel; treating it as a dismiss closed the panel immediately.
                        // And a drag that began mid-panel belongs to whatever is scrolling there —
                        // the Space switcher above all — never to dismiss.
                        if (startedHere && fromEdge) {
                            val dx = ev.x - downX
                            val dy = ev.y - downY
                            if (dx > activity.dp(72) && abs(dx) > abs(dy) * 1.25f) {
                                close()
                                return true
                            }
                        }
                    }
                }
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            addView(ComposeView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setContent {
                    @Suppress("UNUSED_EXPRESSION")
                    themeTick
                    val workloads by repository.workloads.collectAsState()
                    val active = activeSpaceId()
                    val theme = themeStore.themeFor(viewedSpace)
                    edgeHint.setAccent(theme.accent.toArgb())
                    edgeHint.setHasNewActivity(workloads[active]?.items?.isNotEmpty() == true)
                    SpaceTodayScreen(
                        viewedSpace = viewedSpace,
                        activeSpace = active,
                        workloads = workloads,
                        theme = theme,
                        breakthroughs = repository.breakthroughsFor(viewedSpace),
                        hasListenerPermission = activity.isNotificationAccessEnabled(),
                        keyboardMode = if (activity.keyboardPlacement == MainActivity.KEYBOARD_PLACEMENT_WIDGET || activity.isUnfoldedInnerLayoutActive()) {
                            TodayKeyboardMode.WIDGET
                        } else {
                            TodayKeyboardMode.DOCKED
                        },
                        onSwitchSpace = { viewedSpace = it },
                        onAction = { item, action -> fireAction(item, action) },
                        onDismiss = { item -> dismiss(item) },
                        onOpenThemeStore = { showThemeStore(viewedSpace) },
                        onBreakthroughTap = { fromSpace ->
                            learningStore.onBreakthroughTapped(fromSpace)
                            viewedSpace = fromSpace
                        },
                        onGrantPermission = { activity.openNotificationAccessSettings() },
                        onClose = { close(force = true) }
                    )
                }
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        overlay = host
        activity.contentFrame.addView(host, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        android.util.Log.d(TAG, "overlay attached to contentFrame@${System.identityHashCode(activity.contentFrame)}")
        val width = activity.contentFrame.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        host.translationX = width.toFloat()
        host.animate().translationX(0f).setDuration(250L).setInterpolator(DecelerateInterpolator()).start()
        activity.haptic(activity.contentFrame)
    }

    /**
     * [force] = an unambiguous user close (Back, the X button, HOME). Those always win.
     *
     * Everything else — swipe handlers in particular — is refused for [OPEN_GRACE_MS] after the
     * panel opens. The panel is opened BY a swipe, so the tail of that same gesture keeps reaching
     * close paths and killing it; three separate guards were added for three separate variants of
     * that race and it still happened. A human cannot open and then deliberately swipe-close inside
     * a fifth of a second, so refusing those is safe and closes the whole class of bug at once,
     * rather than one gesture path at a time.
     */
    @JvmOverloads
    fun close(force: Boolean = false): Boolean {
        val host = overlay ?: return false
        val sinceOpen = android.os.SystemClock.uptimeMillis() - openedAtMs
        if (!force && sinceOpen < OPEN_GRACE_MS) {
            android.util.Log.d(TAG, "close() REFUSED ${sinceOpen}ms after open", Throwable("close trace"))
            return false
        }
        android.util.Log.d(TAG, "close(force=$force) ${sinceOpen}ms after open", Throwable("close trace"))
        overlay = null
        gestureStartedHere = false
        themeOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        themeOverlay = null
        closeSpaceFan()
        activity.todayOpen = false
        val width = activity.contentFrame.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        host.animate().translationX(width.toFloat())
            .setDuration(210L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                (host.parent as? ViewGroup)?.removeView(host)
                // Any render suppressed while this panel was up runs now.
                activity.flushPendingRender()
            }
            .start()
        return true
    }

    fun isOpen(): Boolean = overlay != null

    fun onResume() {
        repository.startPeriodic()
        repository.refreshDebounced()
    }

    fun onPause() {
        repository.stopPeriodic()
    }

    fun refreshDebounced() {
        repository.refreshDebounced()
    }

    fun homeEdgeHintView(): View = edgeHint.view()

    private fun syncGalaxyNowBar(workloads: Map<String, SpaceWorkload>) {
        val active = activeSpaceId()
        val workload = workloads[active]
            ?: workloads.values.firstOrNull { it.hasWorkload && it.items.isNotEmpty() }
        val count = workload?.items?.size ?: 0
        edgeHint.setHasNewActivity(count > 0)
        if (workload == null || overlay != null) {
            NowBarLiveUpdate.clearSpaceWork(activity)
            return
        }
        val top = workload.items.firstOrNull()
        if (top == null) {
            NowBarLiveUpdate.clearSpaceWork(activity)
            return
        }
        val theme = themeStore.themeFor(workload.spaceId)
        edgeHint.setAccent(theme.accent.toArgb())
        val summary = buildString {
            append(top.summary)
            if (top.who.isNotBlank()) append(" · ").append(top.who)
        }.take(120)
        NowBarLiveUpdate.syncSpaceWork(
            context = activity,
            prefs = prefs,
            spaceName = workload.spaceName,
            summary = summary,
            count = count,
            accentColor = theme.accent.toArgb()
        )
    }

    private fun showSpaceFan() {
        if (!activity.hasContentFrame()) return
        closeSpaceFan()
        repository.refreshDebounced()
        val active = activeSpaceId()
        val spaces = SpaceManager.spaces(activity).filter { it.enabled }
        if (spaces.isEmpty()) {
            openActive()
            return
        }
        val dim = FrameLayout(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { closeSpaceFan() }
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(activity.dp(10), activity.dp(9), activity.dp(10), activity.dp(9))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                0xF01A1D24.toInt(),
                0xE0101217.toInt()
            )).apply {
                cornerRadius = activity.dp(24).toFloat()
                setStroke(1, 0x33FFFFFF)
            }
            elevation = activity.dp(18).toFloat()
        }
        spaces.forEachIndexed { index, space ->
            val selected = space.id == active
            val chip = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                isClickable = true
                setPadding(activity.dp(10), activity.dp(6), activity.dp(10), activity.dp(6))
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                    if (selected) 0x3345E7A4 else 0x16FFFFFF,
                    if (selected) 0x1645E7A4 else 0x0A000000
                )).apply {
                    cornerRadius = activity.dp(18).toFloat()
                    setStroke(1, if (selected) 0x6645E7A4 else 0x18FFFFFF)
                }
                addView(TextView(activity).apply {
                    text = space.emoji
                    textSize = 18f
                    gravity = android.view.Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(0xFFF4F1EB.toInt())
                }, LinearLayout.LayoutParams(activity.dp(42), activity.dp(26)))
                addView(TextView(activity).apply {
                    text = space.name.uppercase()
                    textSize = 8.2f
                    gravity = android.view.Gravity.CENTER
                    includeFontPadding = false
                    letterSpacing = 0.12f
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    setTextColor(if (selected) 0xFFE8FFF4.toInt() else 0xFF9EA4AF.toInt())
                }, LinearLayout.LayoutParams(activity.dp(58), activity.dp(15)))
                setOnClickListener {
                    activity.haptic(this)
                    closeSpaceFan()
                    open(space.id)
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, activity.dp(54)).apply {
                if (index != spaces.lastIndex) marginEnd = activity.dp(6)
            })
        }
        dim.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = activity.dp(if (activity.keyboardPlacement == MainActivity.KEYBOARD_PLACEMENT_WIDGET) 112 else 22)
        })
        fanOverlay = dim
        activity.contentFrame.addView(dim, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        row.scaleX = 0.90f
        row.scaleY = 0.90f
        row.alpha = 0f
        row.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L).setInterpolator(DecelerateInterpolator()).start()
        activity.haptic(activity.contentFrame)
    }

    private fun closeSpaceFan() {
        val view = fanOverlay ?: return
        fanOverlay = null
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun activeSpaceId(): String =
        activity.activeSpaceForUi()?.id
            ?: SpaceManager.spaces(activity).firstOrNull { it.enabled }?.id
            ?: "home"

    private fun fireAction(item: WorkItem, action: WorkAction) {
        val source = item.source
        val realAction: BriefAction? = source?.signal?.actions?.firstOrNull { it.label.equals(action.label, ignoreCase = true) }
        if (source != null && realAction != null) {
            activity.todayPaneHost.fireBriefAction(source, realAction, null)
        } else {
            repository.onAction(item.signalRef, action.kind, item.space)
        }
    }

    private fun dismiss(item: WorkItem) {
        if (item.source != null) {
            activity.todayPaneHost.dismissBriefItem(item.source)
        } else {
            repository.onDismiss(item)
        }
    }

    private fun showThemeStore(spaceId: String) {
        if (themeOverlay != null) return
        val dim = FrameLayout(activity).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            setOnClickListener { closeThemeStore() }
            addView(ComposeView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setContent {
                    val current = themeStore.themeIdFor(spaceId)
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp, vertical = 42.dp),
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(androidx.compose.ui.graphics.Color(0xEE101217), RoundedCornerShape(24.dp))
                                .border(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                                .padding(10.dp)
                        ) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                                Text(
                                    text = "APPLIES TO: ${spaceId.uppercase()} SPACE",
                                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.72f),
                                    fontSize = 10.sp,
                                    letterSpacing = 1.7.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                            items(TodayThemes.ALL, key = { it.id }) { theme ->
                                Column(
                                    Modifier
                                        .background(theme.bg, RoundedCornerShape(18.dp))
                                        .border(
                                            1.dp,
                                            if (theme.id == current) theme.accent else theme.cardBorder,
                                            RoundedCornerShape(18.dp)
                                        )
                                        .clickable {
                                            themeStore.setTheme(spaceId, theme.id)
                                            themeTick++
                                            closeThemeStore()
                                        }
                                        .padding(12.dp)
                                ) {
                                    Text(theme.name, color = theme.ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                                    Text(theme.desc, color = theme.sub, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                }
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        themeOverlay = dim
        activity.contentFrame.addView(dim, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }

    private fun closeThemeStore() {
        val view = themeOverlay ?: return
        themeOverlay = null
        (view.parent as? ViewGroup)?.removeView(view)
    }

    companion object {
        /** `adb logcat -s SpaceToday:D TeclasRender:D` to watch open/close vs view-tree rebuilds. */
        const val TAG = "SpaceToday"

        /** How long after opening a non-forced close is refused. See [close]. */
        private const val OPEN_GRACE_MS = 220L
    }
}
