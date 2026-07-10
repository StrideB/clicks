package com.fran.teclas.grid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * AOSP-Launcher3-style workspace grid: a fixed COLSxROWS cell layout where apps, folders and
 * hosted app-widgets are placed by cell, long-press dragged to re-arrange, dropped onto each
 * other to form folders, and (for widgets) resized by edge handles. The view is agnostic about
 * available height — give it half the screen (docked keyboard) and cells compress exactly like
 * Launcher3's DeviceProfile does; give it the full screen (widget mode) and they relax.
 *
 * All mutation flows back to the host through [Host.itemsChanged]; this view never persists.
 */
class GridWorkspaceView(context: Context, private val host: Host) : FrameLayout(context) {

    interface Host {
        fun launchApp(item: GridItem)
        fun openFolder(item: GridItem)
        fun itemsChanged(items: List<GridItem>)
        fun widgetRemoved(widgetId: Int)
        /** Called after a committed resize so the host can updateAppWidgetSize(). */
        fun widgetResized(item: GridItem, widthPx: Int, heightPx: Int)
        /** Long-press on an empty cell — host shows its add menu. */
        fun addRequested()
        fun loadIcon(packageName: String?, className: String?): Drawable?
        /** Real AppWidgetHostView for a bound widget id, or null if it can't be built. */
        fun createWidgetView(widgetId: Int): View?
    }

    private val items = mutableListOf<GridItem>()
    private val childForId = mutableMapOf<String, View>()

    // Hosted widget views are expensive (each createView inflates RemoteViews + binds any
    // RemoteViewsService). rebuild() runs on every drag/resize/re-select, so we cache the host
    // view per widget id and re-attach it instead of building a fresh one each time — this both
    // stops the leak/flicker and prevents "child already has a parent" from a stale attachment.
    private val widgetViewCache = mutableMapOf<Int, View>()

    /** Reused host view for [widgetId], detached from any prior parent; created + cached on first use. */
    private fun obtainWidgetView(widgetId: Int): View? {
        widgetViewCache[widgetId]?.let { cached ->
            (cached.parent as? android.view.ViewGroup)?.removeView(cached)
            return cached
        }
        val created = host.createWidgetView(widgetId) ?: return null
        widgetViewCache[widgetId] = created
        return created
    }

    // Grid density is per-instance: the phone/cover uses the compact default, but a foldable's
    // big inner canvas can run a much finer grid so widgets place, stretch and dual-panel freely.
    var gridCols: Int = GRID_COLS
        private set
    var gridRows: Int = GRID_ROWS
        private set

    /** Resize the grid (e.g. open canvas on the fold's inner display) and relay out. */
    fun setGridSize(cols: Int, rows: Int) {
        if (cols == gridCols && rows == gridRows) return
        gridCols = cols.coerceAtLeast(1)
        gridRows = rows.coerceAtLeast(1)
        rebuild()
        requestLayout()
        invalidate()
    }

    // Free-canvas mode (foldable open canvas): a single long-press *selects* a widget so its
    // resize handles show immediately; dragging its body moves it anywhere (gaps + overlap
    // allowed, no auto-compaction) and the handles resize it to any size. Phones/cover keep the
    // classic auto-grid model where this is false.
    var freeCanvas: Boolean = false
    private var editDragArmed = false   // finger is down on the selected widget's body, may become a move

    /** In free-canvas, drop the widget wherever the finger lets go — no first-free-cell reshuffle. */
    private fun placeFreely(): Boolean = freeCanvas

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    private var lightMode = false
    private var ink = 0xFFEDEDED.toInt()
    private var inkDim = 0x8AFFFFFF.toInt()
    private var accent = 0xFF9CE7B4.toInt()
    private var danger = 0xFFFF5D5D.toInt()
    private var appPlateFill = 0x1FFFFFFF
    private var appPlateStroke = 0x30FFFFFF
    private var widgetFrameFill = 0x14FFFFFF
    private var widgetFrameStroke = 0x24FFFFFF
    private var stackDotInactive = 0x4DFFFFFF
    private var popChipText = 0xFF10110F.toInt()

    // ---- drag state ----
    private var downX = 0f
    private var downY = 0f
    private var dragItem: GridItem? = null
    private var dragView: View? = null
    private var dragging = false
    private var dragGrabDx = 0f
    private var dragGrabDy = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var pendingLongPress: Runnable? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // ---- resize state ----
    private var resizeItem: GridItem? = null
    private var resizeEdge = 0 // 0 none, 1 right, 2 bottom
    private var pendingSpanX = 0
    private var pendingSpanY = 0

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dpF(1.5f); color = accent
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val removePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = danger; textSize = dpF(11f); typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER; letterSpacing = 0.18f
    }
    private val removeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FF5D5D }

    private val removeZoneHeight: Int get() = dp(52)

    init {
        setWillNotDraw(false)
        clipChildren = false
        applyThemeColors()
    }

    fun setLightMode(light: Boolean) {
        if (lightMode == light) return
        lightMode = light
        applyThemeColors()
        rebuild()
        invalidate()
    }

    private fun applyThemeColors() {
        if (lightMode) {
            ink = 0xFF242A33.toInt()
            inkDim = 0x8A242A33.toInt()
            accent = 0xFF147A5C.toInt()
            danger = 0xFFE84B4B.toInt()
            appPlateFill = 0x2AFFFFFF
            appPlateStroke = 0x24000000
            widgetFrameFill = 0x26FFFFFF
            widgetFrameStroke = 0x2EFFFFFF
            stackDotInactive = 0x55242A33
            popChipText = 0xFFFFFFFF.toInt()
            gridPaint.color = 0x2A242A33
            removeBgPaint.color = 0x18E84B4B
        } else {
            ink = 0xFFEDEDED.toInt()
            inkDim = 0x8AFFFFFF.toInt()
            accent = 0xFF9CE7B4.toInt()
            danger = 0xFFFF5D5D.toInt()
            appPlateFill = 0x26000000
            appPlateStroke = 0x20FFFFFF
            widgetFrameFill = 0x30000000
            widgetFrameStroke = 0x20FFFFFF
            stackDotInactive = 0x38FFFFFF
            popChipText = 0xFF10110F.toInt()
            gridPaint.color = 0x18FFFFFF
            removeBgPaint.color = 0x22FF5D5D
        }
        framePaint.color = accent
        handlePaint.color = accent
        removePaint.color = danger
    }

    fun setItems(newItems: List<GridItem>) {
        // Drop cached host views for widgets that are no longer on the board so they can be GC'd
        // (e.g. switching this reused view to another Space's layout).
        val keep = newItems.flatMap { widgetIdsOf(it) }.toSet()
        widgetViewCache.keys.retainAll(keep)
        items.clear()
        items.addAll(newItems)
        rebuild()
    }

    fun currentItems(): List<GridItem> = items.toList()

    /** True while a widget is being dragged, resized, or selected for resize (edit mode). */
    fun isEditing(): Boolean = dragging || resizeEdge != 0 || resizeItem != null

    private fun cellW(): Float = if (width == 0) 1f else width.toFloat() / gridCols
    private fun cellH(): Float = if (height == 0) 1f else height.toFloat() / gridRows

    // ------------------------------------------------------------------ build

    private val widgetFrameRadius: Float get() = dpF(20f)

    /**
     * Uniform widget overlay: clip every hosted widget to the same rounded rectangle and sit
     * it on the same faint backing, so a square widget and a round-cornered one read as one
     * consistent set of cards instead of a jumble of shapes.
     */
    private fun applyWidgetFrame(frame: FrameLayout) {
        frame.background = GradientDrawable().apply {
            cornerRadius = widgetFrameRadius
            setColor(widgetFrameFill)                 // faint fill behind transparent widgets
            setStroke(dp(1), widgetFrameStroke)       // hairline edge
        }
        frame.clipToOutline = true
        frame.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, widgetFrameRadius)
            }
        }
    }

    private fun rebuild() {
        removeAllViews()
        childForId.clear()
        for (item in items) {
            val child: View = when (item.type) {
                GridItemType.APP -> AppItemView(context, item, false)
                GridItemType.FOLDER -> AppItemView(context, item, true)
                GridItemType.WIDGET -> FrameLayout(context).apply {
                    applyWidgetFrame(this)
                    val inner = obtainWidgetView(item.widgetId)
                    if (inner != null) {
                        addView(inner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                            val m = dp(3); setMargins(m, m, m, m)
                        })
                    } else {
                        addView(AppItemView(context, item, false), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                    }
                }
                GridItemType.STACK -> StackContainer(context, item)
            }
            // Widgets and stacks keep their own internal click handling.
            if (item.type != GridItemType.WIDGET && item.type != GridItemType.STACK) {
                child.setOnClickListener {
                    if (resizeItem != null) { clearResize() } else when (item.type) {
                        GridItemType.APP -> host.launchApp(item)
                        GridItemType.FOLDER -> host.openFolder(item)
                        else -> {}
                    }
                }
            }
            childForId[item.id] = child
            addView(child)
        }
        requestLayout()
        invalidate()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = cellW(); val ch = cellH()
        for (item in items) {
            val v = childForId[item.id] ?: continue
            var sx = item.spanX; var sy = item.spanY
            if (item.id == resizeItem?.id && pendingSpanX > 0) { sx = pendingSpanX; sy = pendingSpanY }
            val left = (item.cellX * cw).roundToInt()
            val top = (item.cellY * ch).roundToInt()
            val w = (sx * cw).roundToInt()
            val h = (sy * ch).roundToInt()
            v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            v.layout(left, top, left + w, top + h)
        }
    }

    // ------------------------------------------------------------------ touch

    private fun childAt(x: Float, y: Float): GridItem? {
        // Walk topmost-first so overlapping drag previews resolve sensibly.
        for (i in items.indices.reversed()) {
            val item = items[i]
            val v = childForId[item.id] ?: continue
            if (x >= v.left && x <= v.right && y >= v.top && y <= v.bottom) return item
        }
        return null
    }

    // Widgets consume touches; never let children forbid our interception, otherwise
    // long-press-drag would die inside scrollable widgets. We cancel our own long-press on
    // movement, so child scrolling keeps working.
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) { /* ignored */ }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastTouchX = ev.x; lastTouchY = ev.y
                editDragArmed = false
                if (resizeItem != null) {
                    popChipRect()?.let { chip ->
                        if (chip.contains(ev.x, ev.y)) {
                            resizeItem?.let { popActiveFromStack(it) }
                            return true
                        }
                    }
                    resizeEdge = hitResizeEdge(ev.x, ev.y)
                    if (resizeEdge != 0) return true
                    val onSelected = childAt(ev.x, ev.y)?.id == resizeItem?.id
                    // Free-canvas: pressing the selected widget's body arms a move — a drag relocates
                    // it anywhere, a tap keeps it selected. Capture the stream so MOVE routes to us.
                    if (freeCanvas && onSelected) { editDragArmed = true; return true }
                    // touch outside the frame exits resize mode; still let taps through
                    if (!onSelected) clearResize()
                }
                val target = childAt(ev.x, ev.y)
                pendingLongPress?.let { removeCallbacks(it) }
                pendingLongPress = Runnable {
                    when {
                        target == null -> host.addRequested()
                        // Free-canvas: long-press a widget/stack to SELECT it (handles appear) rather
                        // than starting a blind move — this is how resize becomes reachable.
                        freeCanvas && (target.type == GridItemType.WIDGET || target.type == GridItemType.STACK) ->
                            startResize(target.id)
                        else -> startDrag(target, downX, downY)
                    }
                    pendingLongPress = null
                }.also { postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong()) }
            }
            MotionEvent.ACTION_MOVE -> {
                lastTouchX = ev.x; lastTouchY = ev.y
                if (dragging) return true
                if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) cancelLongPress()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                // A long-press released with zero movement arrives here (interception never got
                // a MOVE to claim the stream), so the drop must be handled inline.
                if (dragging) {
                    if (ev.actionMasked == MotionEvent.ACTION_UP) finishDrag(ev.x, ev.y) else revertDrag()
                    return false
                }
            }
        }
        return dragging || resizeEdge != 0
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        pendingLongPress?.let { removeCallbacks(it) }
        pendingLongPress = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Empty-cell press lands here (no child claimed it): keep long-press alive.
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lastTouchX = ev.x; lastTouchY = ev.y
                if (dragging) {
                    dragView?.let {
                        it.translationX = ev.x - dragGrabDx - it.left
                        it.translationY = ev.y - dragGrabDy - it.top
                    }
                    invalidate()
                } else if (resizeEdge != 0) {
                    applyResizePreview(ev.x, ev.y)
                } else if (editDragArmed && (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop)) {
                    // The selected widget's body was dragged — begin moving it (free-canvas).
                    editDragArmed = false
                    resizeItem?.let { startDrag(it, downX, downY) }
                } else if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) {
                    cancelLongPress()
                }
            }
            MotionEvent.ACTION_UP -> {
                cancelLongPress()
                editDragArmed = false
                if (dragging) finishDrag(ev.x, ev.y)
                else if (resizeEdge != 0) commitResize()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                editDragArmed = false
                if (dragging) revertDrag()
                if (resizeEdge != 0) { resizeEdge = 0; pendingSpanX = 0; pendingSpanY = 0; requestLayout() }
            }
        }
        return true
    }

    // ------------------------------------------------------------------ drag

    private fun startDrag(item: GridItem, x: Float, y: Float) {
        val v = childForId[item.id] ?: return
        clearResize()
        // The child still owns the touch stream at this point; cancel it so releasing the
        // drag without movement can't also fire the child's click (app launch).
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        v.dispatchTouchEvent(cancel)
        cancel.recycle()
        dragItem = item
        dragView = v
        dragging = true
        dragGrabDx = x - v.left
        dragGrabDy = y - v.top
        v.animate().scaleX(1.06f).scaleY(1.06f).alpha(0.85f).setDuration(120).start()
        v.elevation = dpF(10f)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidate()
    }

    private fun resetDragVisual(v: View) {
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).translationX(0f).translationY(0f).setDuration(140).start()
        v.elevation = 0f
    }

    private fun revertDrag() {
        dragView?.let { resetDragVisual(it) }
        dragging = false; dragItem = null; dragView = null
        invalidate()
    }

    private fun finishDrag(x: Float, y: Float) {
        val item = dragItem ?: return revertDrag()
        val v = dragView

        // Remove zone along the top edge.
        if (y < removeZoneHeight) {
            widgetIdsOf(item).forEach { host.widgetRemoved(it); widgetViewCache.remove(it) }
            items.removeAll { it.id == item.id }
            dragging = false; dragItem = null; dragView = null
            host.itemsChanged(items.toList())
            rebuild()
            return
        }

        val cw = cellW(); val ch = cellH()
        val others = items.filter { it.id != item.id }

        // App dropped on another app -> new folder; on a folder -> join it.
        if (item.type == GridItemType.APP) {
            val target = others.firstOrNull { other ->
                val tv = childForId[other.id] ?: return@firstOrNull false
                x >= tv.left && x <= tv.right && y >= tv.top && y <= tv.bottom
            }
            if (target != null && target.type == GridItemType.APP) {
                val folder = GridItem(
                    id = "folder-${System.currentTimeMillis()}",
                    type = GridItemType.FOLDER,
                    cellX = target.cellX, cellY = target.cellY,
                    folderName = "Folder",
                    children = listOf(target.copy(cellX = 0, cellY = 0), item.copy(cellX = 0, cellY = 0)),
                )
                items.removeAll { it.id == item.id || it.id == target.id }
                items.add(folder)
                commitAndRebuild(); return
            }
            if (target != null && target.type == GridItemType.FOLDER) {
                val idx = items.indexOfFirst { it.id == target.id }
                items[idx] = target.copy(children = target.children + item.copy(cellX = 0, cellY = 0))
                items.removeAll { it.id == item.id }
                commitAndRebuild(); return
            }
        }

        // Widget dropped on another widget -> Pixel-style stack; on a stack -> join it.
        if (item.type == GridItemType.WIDGET) {
            val target = others.firstOrNull { other ->
                val tv = childForId[other.id] ?: return@firstOrNull false
                x >= tv.left && x <= tv.right && y >= tv.top && y <= tv.bottom
            }
            if (target != null && target.type == GridItemType.WIDGET) {
                val stack = GridItem(
                    id = "stack-${System.currentTimeMillis()}",
                    type = GridItemType.STACK,
                    cellX = target.cellX, cellY = target.cellY,
                    spanX = target.spanX, spanY = target.spanY,
                    children = listOf(target.copy(cellX = 0, cellY = 0), item.copy(cellX = 0, cellY = 0)),
                    activeChild = 1,
                )
                items.removeAll { it.id == item.id || it.id == target.id }
                items.add(stack)
                commitAndRebuild(); return
            }
            if (target != null && target.type == GridItemType.STACK) {
                val idx = items.indexOfFirst { it.id == target.id }
                items[idx] = target.copy(
                    children = target.children + item.copy(cellX = 0, cellY = 0),
                    activeChild = target.children.size,
                )
                items.removeAll { it.id == item.id }
                commitAndRebuild(); return
            }
        }

        // Plain move: snap the dragged view's top-left to the nearest cell.
        val proposedLeft = x - dragGrabDx
        val proposedTop = y - dragGrabDy
        val cellX = (proposedLeft / cw).roundToInt().coerceIn(0, gridCols - item.spanX)
        val cellY = (proposedTop / ch).roundToInt().coerceIn(0, gridRows - item.spanY)

        // Free-canvas: drop it exactly where the finger let go — gaps and overlap are allowed, so a
        // widget can sit dead-centre — and keep it selected so the user can resize right after.
        if (placeFreely()) {
            val idx = items.indexOfFirst { it.id == item.id }
            if (idx >= 0) {
                val moved = item.copy(cellX = cellX, cellY = cellY)
                items[idx] = moved
                dragging = false; dragItem = null; dragView = null
                host.itemsChanged(items.toList())
                rebuild()
                if (item.type == GridItemType.WIDGET || item.type == GridItemType.STACK) startResize(item.id)
            }
            return
        }

        // Widget dropped back on its own cell = "I long-pressed to resize, not move".
        if ((item.type == GridItemType.WIDGET || item.type == GridItemType.STACK) && cellX == item.cellX && cellY == item.cellY) {
            dragView?.let { resetDragVisual(it) }
            dragging = false; dragItem = null; dragView = null
            startResize(item.id)
            return
        }

        val collides = others.any { cellsOverlap(cellX, cellY, item.spanX, item.spanY, it.cellX, it.cellY, it.spanX, it.spanY) }
        if (!collides) {
            val idx = items.indexOfFirst { it.id == item.id }
            items[idx] = item.copy(cellX = cellX, cellY = cellY)
            commitAndRebuild()
        } else {
            v?.let { resetDragVisual(it) }
            dragging = false; dragItem = null; dragView = null
            invalidate()
        }
    }

    private fun commitAndRebuild() {
        dragging = false; dragItem = null; dragView = null
        host.itemsChanged(items.toList())
        rebuild()
    }

    // ---------------------------------------------------------------- resize

    /** Enter resize mode for a widget or stack (called after an in-place long-press drop). */
    fun startResize(itemId: String) {
        val item = items.firstOrNull {
            it.id == itemId && (it.type == GridItemType.WIDGET || it.type == GridItemType.STACK)
        } ?: return
        resizeItem = item
        pendingSpanX = item.spanX
        pendingSpanY = item.spanY
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        requestLayout(); invalidate()
    }

    fun clearResize() {
        if (resizeItem == null) return
        resizeItem = null; resizeEdge = 0; pendingSpanX = 0; pendingSpanY = 0
        requestLayout(); invalidate()
    }

    private fun resizeFrame(): RectF? {
        val item = resizeItem ?: return null
        val cw = cellW(); val ch = cellH()
        val sx = if (pendingSpanX > 0) pendingSpanX else item.spanX
        val sy = if (pendingSpanY > 0) pendingSpanY else item.spanY
        return RectF(item.cellX * cw, item.cellY * ch, (item.cellX + sx) * cw, (item.cellY + sy) * ch)
    }

    private fun hitResizeEdge(x: Float, y: Float): Int {
        val f = resizeFrame() ?: return 0
        val slop = dpF(26f)
        val nearRight = abs(x - f.right) < slop && y > f.top - slop && y < f.bottom + slop
        val nearBottom = abs(y - f.bottom) < slop && x > f.left - slop && x < f.right + slop
        return when {
            // The bottom-right corner resizes both axes at once — the natural "grab a corner" gesture.
            nearRight && nearBottom -> 3
            nearRight -> 1
            nearBottom -> 2
            else -> 0
        }
    }

    private fun applyResizePreview(x: Float, y: Float) {
        val item = resizeItem ?: return
        if (resizeEdge == 1 || resizeEdge == 3) {
            val span = ((x - item.cellX * cellW()) / cellW()).roundToInt().coerceIn(1, gridCols - item.cellX)
            if (span != pendingSpanX) { pendingSpanX = span; requestLayout(); invalidate() }
        }
        if (resizeEdge == 2 || resizeEdge == 3) {
            val span = ((y - item.cellY * cellH()) / cellH()).roundToInt().coerceIn(1, gridRows - item.cellY)
            if (span != pendingSpanY) { pendingSpanY = span; requestLayout(); invalidate() }
        }
    }

    private fun commitResize() {
        val item = resizeItem ?: return
        resizeEdge = 0
        val sx = if (pendingSpanX > 0) pendingSpanX else item.spanX
        val sy = if (pendingSpanY > 0) pendingSpanY else item.spanY
        // Free-canvas lets a widget grow over its neighbours; the auto-grid still forbids overlap.
        val collides = !placeFreely() && items.any {
            it.id != item.id && cellsOverlap(item.cellX, item.cellY, sx, sy, it.cellX, it.cellY, it.spanX, it.spanY)
        }
        if (!collides && (sx != item.spanX || sy != item.spanY)) {
            val idx = items.indexOfFirst { it.id == item.id }
            val updated = item.copy(spanX = sx, spanY = sy)
            items[idx] = updated
            resizeItem = updated
            host.itemsChanged(items.toList())
            host.widgetResized(updated, (sx * cellW()).roundToInt(), (sy * cellH()).roundToInt())
        }
        pendingSpanX = 0; pendingSpanY = 0
        requestLayout(); invalidate()
    }

    // ---------------------------------------------------------- external drag

    private var externalItem: GridItem? = null
    private var externalView: View? = null

    /** Begin a drag of an item that isn't on the grid yet (app-drawer drop, AOSP-style). */
    fun startExternalDrag(item: GridItem, x: Float, y: Float) {
        clearResize()
        externalDragCancel()
        val v = AppItemView(context, item, false)
        externalItem = item
        externalView = v
        addView(v)
        val w = cellW().roundToInt(); val h = cellH().roundToInt()
        v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        val left = (x - w / 2f).roundToInt(); val top = (y - h / 2f).roundToInt()
        v.layout(left, top, left + w, top + h)
        v.scaleX = 1.06f; v.scaleY = 1.06f; v.alpha = 0.85f; v.elevation = dpF(10f)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    fun externalDragMove(x: Float, y: Float) {
        externalView?.let {
            it.translationX = x - it.width / 2f - it.left
            it.translationY = y - it.height / 2f - it.top
        }
        invalidate()
    }

    fun externalDragDrop(x: Float, y: Float) {
        val item = externalItem ?: return
        val v = externalView
        externalItem = null; externalView = null
        if (v != null) removeView(v)

        if (y < removeZoneHeight) { invalidate(); return } // dropped on the cancel strip

        // Same folder semantics as an on-grid drag: app onto app merges, onto folder joins.
        val target = items.firstOrNull { other ->
            val tv = childForId[other.id] ?: return@firstOrNull false
            x >= tv.left && x <= tv.right && y >= tv.top && y <= tv.bottom
        }
        if (target != null && target.type == GridItemType.APP) {
            val folder = GridItem(
                id = "folder-${System.currentTimeMillis()}",
                type = GridItemType.FOLDER,
                cellX = target.cellX, cellY = target.cellY,
                folderName = "Folder",
                children = listOf(target.copy(cellX = 0, cellY = 0), item.copy(cellX = 0, cellY = 0)),
            )
            items.removeAll { it.id == target.id }
            items.add(folder)
            host.itemsChanged(items.toList()); rebuild(); return
        }
        if (target != null && target.type == GridItemType.FOLDER) {
            val idx = items.indexOfFirst { it.id == target.id }
            items[idx] = target.copy(children = target.children + item.copy(cellX = 0, cellY = 0))
            host.itemsChanged(items.toList()); rebuild(); return
        }

        val cw = cellW(); val ch = cellH()
        var cellX = ((x - cw / 2f) / cw).roundToInt().coerceIn(0, gridCols - 1)
        var cellY = ((y - ch / 2f) / ch).roundToInt().coerceIn(0, gridRows - 1)
        val collides = items.any { cellsOverlap(cellX, cellY, 1, 1, it.cellX, it.cellY, it.spanX, it.spanY) }
        if (collides) {
            val free = firstFreeCell(items, 1, 1)
            if (free == null) {
                android.widget.Toast.makeText(context, "Grid is full", android.widget.Toast.LENGTH_SHORT).show()
                invalidate(); return
            }
            cellX = free.first; cellY = free.second
        }
        items.add(item.copy(cellX = cellX, cellY = cellY))
        host.itemsChanged(items.toList())
        rebuild()
    }

    fun externalDragCancel() {
        externalView?.let { removeView(it) }
        externalItem = null; externalView = null
        invalidate()
    }

    // ---------------------------------------------------------------- stacks

    private fun cycleStack(container: StackContainer, delta: Int) {
        val idx = items.indexOfFirst { it.id == container.item.id }
        if (idx < 0) return
        val current = items[idx]
        if (current.children.isEmpty()) return
        val next = (current.activeChild + delta).mod(current.children.size)
        items[idx] = current.copy(activeChild = next)
        host.itemsChanged(items.toList())
        container.showChild(next)
    }

    private fun popActiveFromStack(stack: GridItem) {
        val active = stack.children.getOrNull(stack.activeChild.coerceIn(0, stack.children.size - 1)) ?: return
        val cell = firstFreeCell(items.filter { it.id != stack.id }, stack.spanX, stack.spanY)
            ?: firstFreeCell(items.filter { it.id != stack.id }, 1, 1)
        if (cell == null) {
            android.widget.Toast.makeText(context, "No room to pop the widget out", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val remaining = stack.children.filter { it.id != active.id }
        val idx = items.indexOfFirst { it.id == stack.id }
        if (idx < 0) return
        if (remaining.size == 1) {
            // A stack of one dissolves back into the plain widget.
            items[idx] = remaining[0].copy(cellX = stack.cellX, cellY = stack.cellY, spanX = stack.spanX, spanY = stack.spanY)
        } else {
            items[idx] = stack.copy(children = remaining, activeChild = 0)
        }
        val fitX = minOf(stack.spanX, gridCols - cell.first)
        val fitY = minOf(stack.spanY, gridRows - cell.second)
        items.add(active.copy(cellX = cell.first, cellY = cell.second, spanX = fitX, spanY = fitY))
        clearResize()
        host.itemsChanged(items.toList())
        rebuild()
    }

    private fun popChipRect(): RectF? {
        val item = resizeItem ?: return null
        if (item.type != GridItemType.STACK) return null
        val f = resizeFrame() ?: return null
        return RectF(f.left + dpF(8f), f.top + dpF(8f), f.left + dpF(64f), f.top + dpF(34f))
    }

    /** Swipeable pile of hosted widgets sharing one footprint — Pixel widget-stack behaviour. */
    private inner class StackContainer(context: Context, val item: GridItem) : FrameLayout(context) {
        private var active = item.activeChild.coerceIn(0, (item.children.size - 1).coerceAtLeast(0))
        private val stripW = dp(24)
        private var stripDownY = 0f
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            setWillNotDraw(false)
            item.children.forEachIndexed { i, child ->
                val v = obtainWidgetView(child.widgetId) ?: AppItemView(context, child, false)
                addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                    val m = dp(3); setMargins(m, m, m + stripW / 2, m)
                })
                v.visibility = if (i == active) VISIBLE else GONE
            }
        }

        fun showChild(index: Int) {
            active = index.coerceIn(0, childCount - 1)
            for (i in 0 until childCount) getChildAt(i).visibility = if (i == active) VISIBLE else GONE
            invalidate()
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            // Claim only the right-edge strip; the hosted widget keeps the rest.
            return ev.x > width - stripW
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { stripDownY = ev.y; return ev.x > width - stripW }
                MotionEvent.ACTION_UP -> {
                    val dy = ev.y - stripDownY
                    val delta = when {
                        dy < -dp(20) -> -1
                        dy > dp(20) -> 1
                        else -> 1 // tap on the strip advances the stack
                    }
                    cycleStack(this, delta)
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            if (childCount < 2) return
            val cx = width - stripW / 2f
            val gap = dpF(10f)
            val startY = height / 2f - gap * (childCount - 1) / 2f
            for (i in 0 until childCount) {
                dotPaint.color = if (i == active) accent else stackDotInactive
                canvas.drawCircle(cx, startY + i * gap, if (i == active) dpF(3f) else dpF(2.2f), dotPaint)
            }
        }
    }

    // ------------------------------------------------------------------ draw

    override fun dispatchDraw(canvas: Canvas) {
        if (dragging || resizeItem != null || externalItem != null) {
            val cw = cellW(); val ch = cellH()
            val r = dpF(1.6f)
            for (cx in 0..gridCols) for (cy in 0..gridRows) {
                canvas.drawCircle(cx * cw, cy * ch, r, gridPaint)
            }
        }
        super.dispatchDraw(canvas)
        if (dragging || externalItem != null) {
            canvas.drawRect(0f, 0f, width.toFloat(), removeZoneHeight.toFloat(), removeBgPaint)
            canvas.drawText(if (dragging) "REMOVE" else "CANCEL", width / 2f, removeZoneHeight / 2f + dpF(4f), removePaint)
        }
        resizeFrame()?.let { f ->
            canvas.drawRoundRect(f, dpF(10f), dpF(10f), framePaint)
            val hw = dpF(3f); val hl = dpF(16f)
            canvas.drawRoundRect(f.right - hw, f.centerY() - hl, f.right + hw, f.centerY() + hl, hw, hw, handlePaint)
            canvas.drawRoundRect(f.centerX() - hl, f.bottom - hw, f.centerX() + hl, f.bottom + hw, hw, hw, handlePaint)
            // Bottom-right corner grip (diagonal resize).
            canvas.drawCircle(f.right, f.bottom, dpF(7f), handlePaint)
            popChipRect()?.let { chip ->
                canvas.drawRoundRect(chip, dpF(8f), dpF(8f), handlePaint)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = popChipText; textSize = dpF(10f); typeface = Typeface.MONOSPACE
                    textAlign = Paint.Align.CENTER; letterSpacing = 0.1f
                }
                canvas.drawText("POP", chip.centerX(), chip.centerY() + dpF(3.5f), p)
            }
        }
    }

    // ------------------------------------------------------- item child views

    /** Icon+label tile that draws itself for any cell size; doubles as folder icon (2x2 minis). */
    private inner class AppItemView(context: Context, val item: GridItem, val isFolder: Boolean) : View(context) {
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; textSize = dpF(9.5f); typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER; letterSpacing = 0.04f
        }
        private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = appPlateFill }
        private val plateStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dpF(1f); color = appPlateStroke
        }
        private var icon: Drawable? = null
        private var folderIcons: List<Drawable> = emptyList()

        init {
            if (isFolder) {
                folderIcons = item.children.take(4).mapNotNull { host.loadIcon(it.packageName, it.className) }
            } else {
                icon = host.loadIcon(item.packageName, item.className)
            }
            isClickable = true
        }

        override fun onDraw(canvas: Canvas) {
            val labelSpace = dpF(18f)
            val iconArea = min(width.toFloat(), height - labelSpace)
            val iconSize = (iconArea * 0.62f).coerceAtLeast(dpF(20f))
            val cx = width / 2f
            val iconTop = ((height - labelSpace) - iconSize) / 2f

            if (isFolder) {
                val plate = RectF(cx - iconSize / 2, iconTop, cx + iconSize / 2, iconTop + iconSize)
                canvas.drawRoundRect(plate, iconSize * 0.24f, iconSize * 0.24f, platePaint)
                canvas.drawRoundRect(plate, iconSize * 0.24f, iconSize * 0.24f, plateStroke)
                val pad = iconSize * 0.12f
                val mini = (iconSize - pad * 3) / 2f
                folderIcons.forEachIndexed { i, d ->
                    val mx = plate.left + pad + (i % 2) * (mini + pad)
                    val my = plate.top + pad + (i / 2) * (mini + pad)
                    d.setBounds(mx.roundToInt(), my.roundToInt(), (mx + mini).roundToInt(), (my + mini).roundToInt())
                    d.draw(canvas)
                }
            } else {
                icon?.let {
                    val l = (cx - iconSize / 2).roundToInt()
                    val t = iconTop.roundToInt()
                    it.setBounds(l, t, l + iconSize.roundToInt(), t + iconSize.roundToInt())
                    it.draw(canvas)
                }
            }

            val label = (if (isFolder) item.folderName else item.label) ?: ""
            if (label.isNotEmpty() && height > dp(52)) {
                val shown = if (label.length > 10) label.take(9) + "…" else label
                canvas.drawText(shown.uppercase(), cx, height - dpF(6f), labelPaint)
            }
        }
    }
}
