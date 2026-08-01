package com.fran.teclas.nav

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.hypot

/**
 * The launcher's own full-screen gesture bar: three transparent edge strips that turn swipes into
 * back / home / recents.
 *
 * ### Why an accessibility overlay
 *
 * The windows are `TYPE_ACCESSIBILITY_OVERLAY`, added from the launcher's already-running
 * [com.fran.teclas.InputInjectionService]. That is not a shortcut around SYSTEM_ALERT_WINDOW — it is
 * the only host that works. The service is the one component that can actually *perform* Back, it is
 * kept alive by the framework rather than by a foreground notification, and its window survives the
 * launcher process being trimmed. Hanging the strips off a plain overlay service instead would mean
 * a permanent notification, a second permission, and a bar that disappears when the launcher is
 * evicted — while still having to call back into the accessibility service to do anything.
 *
 * ### The cost, stated plainly
 *
 * A touchable strip consumes the DOWN before anyone knows what the stroke will become, so whatever
 * is underneath it never sees the touch. That is inherent to every third-party gesture bar on
 * Android, and it is why the strips default to roughly the size of the system's own gesture regions
 * (16dp at the sides, 20dp at the bottom), why each strip is individually switchable, and why
 * [GestureNavPrefs.tapPassthrough] replays a bare tap underneath the bottom strip instead of eating
 * the bottom row of every app.
 */
internal class GestureNavOverlay(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val uiHandler = Handler(Looper.getMainLooper())

    private var bottomStrip: EdgeStripView? = null
    private var leftStrip: EdgeStripView? = null
    private var rightStrip: EdgeStripView? = null
    private var indicator: BackIndicatorView? = null

    /**
     * Bring the on-screen windows in line with the current settings. Idempotent and cheap enough to
     * call on every preference change, on service connect and on configuration change — it tears
     * everything down and rebuilds only what is asked for, which is far easier to reason about than
     * diffing three windows whose geometry depends on rotation.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun sync() {
        teardown()
        val wm = windowManager ?: return
        if (!GestureNavPrefs.overlayEnabled(service)) return

        val density = service.resources.displayMetrics.density
        val edgePx = (GestureNavPrefs.edgeWidthDp(service) * density).toInt().coerceAtLeast(1)
        val bottomPx = (GestureNavPrefs.bottomHeightDp(service) * density).toInt().coerceAtLeast(1)
        val screenHeight = runCatching { wm.currentWindowMetrics.bounds.height() }
            .getOrDefault(service.resources.displayMetrics.heightPixels)

        // Leave the top of both side edges alone. That band is where the notification shade is
        // pulled from and where apps put their own back arrows and drawer handles; a back strip
        // running the full height fights all three for no benefit.
        val topSkip = (screenHeight * TOP_EXCLUSION_FRACTION).toInt()
        val edgeHeight = (screenHeight - topSkip - bottomPx).coerceAtLeast((120 * density).toInt())

        if (GestureNavPrefs.leftEdgeEnabled(service)) {
            leftStrip = addStrip(
                wm, NavZone.LEFT_EDGE,
                width = edgePx, height = edgeHeight,
                gravity = Gravity.START or Gravity.TOP, y = topSkip,
            )
        }
        if (GestureNavPrefs.rightEdgeEnabled(service)) {
            rightStrip = addStrip(
                wm, NavZone.RIGHT_EDGE,
                width = edgePx, height = edgeHeight,
                gravity = Gravity.END or Gravity.TOP, y = topSkip,
            )
        }
        // Added last so it wins the bottom corners over the side strips: a thumb starting in the
        // corner is reaching for home far more often than for back.
        if (GestureNavPrefs.bottomBarEnabled(service)) {
            bottomStrip = addStrip(
                wm, NavZone.BOTTOM,
                width = WindowManager.LayoutParams.MATCH_PARENT, height = bottomPx,
                gravity = Gravity.BOTTOM or Gravity.START, y = 0,
            )
        }
    }

    fun teardown() {
        indicator?.let { removeView(it) }
        indicator = null
        listOf(bottomStrip, leftStrip, rightStrip).forEach { it?.let(::removeView) }
        bottomStrip = null
        leftStrip = null
        rightStrip = null
    }

    private fun addStrip(
        wm: WindowManager,
        zone: NavZone,
        width: Int,
        height: Int,
        gravity: Int,
        y: Int,
    ): EdgeStripView? {
        val view = EdgeStripView(service, zone)
        val params = baseParams(width, height, touchable = true).apply {
            this.gravity = gravity
            this.y = y
        }
        return runCatching { wm.addView(view, params); view }
            .onFailure { Log.w(TAG, "addView($zone) failed", it) }
            .getOrNull()
    }

    private fun baseParams(width: Int, height: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Reach into the cutout so the side strips stay a constant width on a curved display
            // instead of stopping short of the bezel exactly where a thumb starts its back swipe.
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private fun removeView(view: View) {
        runCatching { windowManager?.removeViewImmediate(view) }
            .onFailure { Log.w(TAG, "removeView failed", it) }
    }

    // ------------------------------------------------------------------ back indicator

    /** Show (or move) the transient back arrow on [zone]'s side at [rawY], drawn [progress] deep. */
    private fun showIndicator(zone: NavZone, rawY: Float, progress: Float) {
        val wm = windowManager ?: return
        val fromLeft = zone == NavZone.LEFT_EDGE
        val gravity = (if (fromLeft) Gravity.START else Gravity.END) or Gravity.TOP

        val view = indicator ?: createIndicator(wm, gravity, fromLeft) ?: return
        if (view.fromLeft != fromLeft) {
            // The arrow window is shared between the two edges: one window that moves is cheaper
            // than two that idle, and only one back swipe can be in flight at a time anyway.
            view.fromLeft = fromLeft
            (view.layoutParams as? WindowManager.LayoutParams)?.let { params ->
                params.gravity = gravity
                runCatching { wm.updateViewLayout(view, params) }
            }
        }
        view.update(rawY, progress)
    }

    private fun createIndicator(
        wm: WindowManager,
        gravity: Int,
        startFromLeft: Boolean,
    ): BackIndicatorView? {
        val density = service.resources.displayMetrics.density
        val view = BackIndicatorView(service)
        view.fromLeft = startFromLeft
        val params = baseParams(
            (INDICATOR_WIDTH_DP * density).toInt(),
            WindowManager.LayoutParams.MATCH_PARENT,
            touchable = false,
        ).apply { this.gravity = gravity }
        return runCatching { wm.addView(view, params); view }
            .onFailure { Log.w(TAG, "indicator addView failed", it) }
            .getOrNull()
            ?.also { indicator = it }
    }

    private fun hideIndicator() {
        indicator?.update(0f, 0f)
    }

    // ------------------------------------------------------------------ tap passthrough

    /**
     * Replay a swallowed tap as a synthesised one underneath the strip.
     *
     * The strip goes non-touchable, the tap is dispatched, and the strip comes back. The restore is
     * armed as a delayed backstop *before* the dispatch and only rescheduled by the callback, so a
     * dispatch that is refused, dropped or never answered still leaves a live gesture bar — a
     * permanently dead strip would be a far worse failure than a lost tap.
     */
    private fun passThroughTap(strip: EdgeStripView, rawX: Float, rawY: Float) {
        val wm = windowManager ?: return
        if (!NavActions.isReady()) return
        val params = strip.layoutParams as? WindowManager.LayoutParams ?: return

        fun setTouchable(touchable: Boolean) {
            params.flags = if (touchable) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            runCatching { wm.updateViewLayout(strip, params) }
        }

        val restore = Runnable { setTouchable(true) }
        setTouchable(false)
        uiHandler.postDelayed(restore, PASSTHROUGH_BACKSTOP_MS)

        // One frame for the window update to reach the system before the synthetic touch is aimed
        // through the hole it just opened.
        uiHandler.postDelayed({
            val path = Path().apply { moveTo(rawX, rawY) }
            val description = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
                .build()
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = finish()
                override fun onCancelled(gestureDescription: GestureDescription?) = finish()
                private fun finish() {
                    uiHandler.removeCallbacks(restore)
                    uiHandler.postDelayed(restore, PASSTHROUGH_SETTLE_MS)
                }
            }
            if (!NavActions.dispatch(description, callback, uiHandler)) {
                uiHandler.removeCallbacks(restore)
                restore.run()
            }
        }, PASSTHROUGH_FRAME_MS)
    }

    // ------------------------------------------------------------------ views

    /**
     * One transparent edge strip. Tracks the stroke, hands it to [GestureClassifier] on lift, and
     * performs whatever comes back.
     */
    @SuppressLint("ViewConstructor")
    private inner class EdgeStripView(context: Context, val zone: NavZone) : View(context) {

        private val density = context.resources.displayMetrics.density
        private val tuning = GestureTuning(
            minTravelPx = MIN_TRAVEL_DP * density,
            holdSlopPx = HOLD_SLOP_DP * density,
        )
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private var downX = 0f
        private var downY = 0f
        private var downAt = 0L
        private var stillX = 0f
        private var stillY = 0f
        private var stillSince = 0L
        private var armed = false

        init {
            setBackgroundColor(Color.TRANSPARENT)
            setWillNotDraw(false)
            handlePaint.color = handleColor(context)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (zone != NavZone.BOTTOM) return
            if (!GestureNavPrefs.showHandle(context)) return
            // The handle is decoration, not a target: it marks where the bottom strip lives on a
            // device whose own gesture pill we just hid, so the swipe has something to aim at.
            val handleWidth = (width * 0.32f).coerceAtMost(HANDLE_MAX_WIDTH_DP * density)
            val handleHeight = HANDLE_HEIGHT_DP * density
            val left = (width - handleWidth) / 2f
            val top = height - handleHeight - HANDLE_BOTTOM_MARGIN_DP * density
            canvas.drawRoundRect(
                RectF(left, top, left + handleWidth, top + handleHeight),
                handleHeight / 2f, handleHeight / 2f, handlePaint,
            )
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downAt = SystemClock.uptimeMillis()
                    stillX = downX
                    stillY = downY
                    stillSince = downAt
                    armed = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val moved = hypot(event.rawX - stillX, event.rawY - stillY)
                    if (moved > tuning.holdSlopPx) {
                        stillX = event.rawX
                        stillY = event.rawY
                        stillSince = SystemClock.uptimeMillis()
                    }
                    if (zone != NavZone.BOTTOM) {
                        val travel = if (zone == NavZone.LEFT_EDGE) event.rawX - downX else downX - event.rawX
                        val progress = (travel / (BACK_COMMIT_DP * density)).coerceIn(0f, 1f)
                        showIndicator(zone, event.rawY, progress)
                        if (!armed && progress >= 1f) {
                            armed = true
                            haptic(HapticFeedbackConstants.GESTURE_START)
                        }
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    hideIndicator()
                    val now = SystemClock.uptimeMillis()
                    val stroke = NavStroke(
                        zone = zone,
                        dxPx = event.rawX - downX,
                        dyPx = event.rawY - downY,
                        durationMs = now - downAt,
                        stationaryMs = now - stillSince,
                        stationaryPx = hypot(event.rawX - stillX, event.rawY - stillY),
                    )
                    val gesture = GestureClassifier.classify(stroke, tuning)
                    if (gesture != NavGesture.NONE) {
                        if (NavActions.perform(gesture)) haptic(HapticFeedbackConstants.CONFIRM)
                    } else if (
                        zone == NavZone.BOTTOM &&
                        GestureNavPrefs.tapPassthrough(context) &&
                        GestureClassifier.isTap(stroke, tuning)
                    ) {
                        passThroughTap(this, event.rawX, event.rawY)
                    }
                    armed = false
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    hideIndicator()
                    armed = false
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun haptic(constant: Int) {
            if (!GestureNavPrefs.hapticsEnabled(context)) return
            runCatching { performHapticFeedback(constant) }
        }
    }

    /** The arrow that grows out of the edge while a back swipe is in progress. Never touchable. */
    @SuppressLint("ViewConstructor")
    private inner class BackIndicatorView(context: Context) : View(context) {

        var fromLeft = true
        private val density = context.resources.displayMetrics.density
        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = indicatorBodyColor(context) }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = indicatorArrowColor(context)
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private var centerY = 0f
        private var progress = 0f

        fun update(rawY: Float, newProgress: Float) {
            centerY = rawY
            progress = newProgress.coerceIn(0f, 1f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (progress <= 0.02f) return
            val depth = (INDICATOR_MIN_DEPTH_DP + (INDICATOR_MAX_DEPTH_DP - INDICATOR_MIN_DEPTH_DP) * progress) * density
            val tongueHeight = INDICATOR_HEIGHT_DP * density
            val top = centerY - tongueHeight / 2f
            val rect = if (fromLeft) {
                RectF(-depth, top, depth, top + tongueHeight)
            } else {
                RectF(width - depth, top, width + depth, top + tongueHeight)
            }
            bodyPaint.alpha = (200 * progress).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, depth, depth, bodyPaint)

            val chevronSize = 4f * density * progress
            val chevronX = if (fromLeft) depth * 0.45f else width - depth * 0.45f
            val direction = if (fromLeft) 1f else -1f
            arrowPaint.alpha = (255 * progress).toInt().coerceIn(0, 255)
            val path = Path().apply {
                moveTo(chevronX + chevronSize * direction, centerY - chevronSize)
                lineTo(chevronX - chevronSize * direction, centerY)
                lineTo(chevronX + chevronSize * direction, centerY + chevronSize)
            }
            canvas.drawPath(path, arrowPaint)
        }
    }

    private companion object {
        const val TAG = "GestureNavOverlay"

        /** Top slice of each side edge left to the app and the notification shade. */
        const val TOP_EXCLUSION_FRACTION = 0.18f

        const val MIN_TRAVEL_DP = 24f
        const val BACK_COMMIT_DP = 56f
        /** How far a finger may drift and still count as held in place. */
        const val HOLD_SLOP_DP = 8f

        const val HANDLE_MAX_WIDTH_DP = 108f
        const val HANDLE_HEIGHT_DP = 4f
        const val HANDLE_BOTTOM_MARGIN_DP = 6f

        const val INDICATOR_WIDTH_DP = 96
        const val INDICATOR_HEIGHT_DP = 96f
        const val INDICATOR_MIN_DEPTH_DP = 6f
        const val INDICATOR_MAX_DEPTH_DP = 22f

        const val TAP_DURATION_MS = 48L
        const val PASSTHROUGH_FRAME_MS = 24L
        const val PASSTHROUGH_SETTLE_MS = 60L
        const val PASSTHROUGH_BACKSTOP_MS = 700L

        fun isDark(context: Context): Boolean =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        fun handleColor(context: Context): Int =
            if (isDark(context)) 0x8CFFFFFF.toInt() else 0x66000000

        fun indicatorBodyColor(context: Context): Int =
            if (isDark(context)) 0xFFE8EAF0.toInt() else 0xFF2A2D33.toInt()

        fun indicatorArrowColor(context: Context): Int =
            if (isDark(context)) 0xFF15171B.toInt() else 0xFFF3F5F9.toInt()
    }
}
