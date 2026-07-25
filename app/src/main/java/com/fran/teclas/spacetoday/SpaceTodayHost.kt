package com.fran.teclas.spacetoday

import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
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
import com.fran.teclas.predict.SpaceManager
import com.fran.teclas.spacetoday.data.PriorityFeedRepository
import com.fran.teclas.spacetoday.data.WorkItemCache
import com.fran.teclas.spacetoday.data.ZoneResolver
import com.fran.teclas.spacetoday.model.WorkAction
import com.fran.teclas.spacetoday.model.WorkItem
import com.fran.teclas.spacetoday.rank.LearningStore
import com.fran.teclas.spacetoday.rank.LlmRanker
import com.fran.teclas.spacetoday.rank.PreScorer
import com.fran.teclas.spacetoday.theme.TodayThemeStore
import com.fran.teclas.spacetoday.theme.TodayThemes
import com.fran.teclas.spacetoday.ui.HomeEdgeHint
import com.fran.teclas.spacetoday.ui.SpaceTodayScreen
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
    private val edgeHint = HomeEdgeHint(activity) { openActive() }

    private var overlay: FrameLayout? = null
    private var themeOverlay: View? = null
    private var viewedSpace by mutableStateOf(activeSpaceId())
    private var themeTick by mutableIntStateOf(0)
    private var downX = 0f
    private var downY = 0f

    fun shouldHandleActiveSpace(): Boolean = repository.hasWorkload(activeSpaceId())

    fun openActive() = open(activeSpaceId())

    fun open(spaceId: String) {
        if (!activity.hasContentFrame() || activity.libraryOpen || activity.openPane != null) return
        viewedSpace = spaceId
        repository.refresh()
        activity.cancelWallpaperLongPress()
        activity.todayOpen = true
        val existing = overlay
        if (existing != null) {
            existing.bringToFront()
            return
        }
        val host = object : FrameLayout(activity) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.x
                        downY = ev.y
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        if (dx > activity.dp(72) && abs(dx) > abs(dy) * 1.25f) {
                            close()
                            return true
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
                        onClose = { close() }
                    )
                }
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        overlay = host
        activity.contentFrame.addView(host, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        val width = activity.contentFrame.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        host.translationX = width.toFloat()
        host.animate().translationX(0f).setDuration(250L).setInterpolator(DecelerateInterpolator()).start()
        activity.haptic(activity.contentFrame)
    }

    fun close(): Boolean {
        val host = overlay ?: return false
        overlay = null
        themeOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        themeOverlay = null
        activity.todayOpen = false
        val width = activity.contentFrame.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        host.animate().translationX(width.toFloat())
            .setDuration(210L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { (host.parent as? ViewGroup)?.removeView(host) }
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
}
