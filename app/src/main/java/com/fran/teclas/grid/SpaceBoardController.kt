package com.fran.teclas.grid

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * Drives a per-Space unified board: one [GridWorkspaceView] where the Space's apps (seeded as
 * 1×1 tiles) and the user's hand-placed, resizable widgets live on the same grid — no separate
 * "apps section" vs "widgets section". Reuses the host launcher's existing AppWidgetHost and
 * icon loader via [Callbacks], and persists each Space's layout independently through
 * [GridWorkspaceStore]. Kept out of the giant MainActivity so the shipping surfaces are untouched.
 */
class SpaceBoardController(
    private val activity: Activity,
    private val callbacks: Callbacks,
) : GridWorkspaceView.Host {

    interface Callbacks {
        fun launchAppFromBoard(packageName: String, className: String?, label: String?)
        fun loadIcon(packageName: String?, className: String?): Drawable?
        fun createWidgetHostView(widgetId: Int): View?
        fun allocateWidgetId(): Int
        fun bindWidgetIfAllowed(widgetId: Int, provider: AppWidgetProviderInfo): Boolean
        fun deleteWidgetId(widgetId: Int)
        fun updateWidgetSize(widgetId: Int, widthDp: Int, heightDp: Int)
        /** Ask the launcher to run the bind/configure Intent under its result codes. */
        fun startWidgetResultIntent(intent: Intent, requestCode: Int)
        /** Show the app/widget add chooser. */
        fun showAddChooser(onAddApp: () -> Unit, onAddWidget: () -> Unit)
        fun showWidgetPicker(onPick: (AppWidgetProviderInfo) -> Unit)
        fun showAppPicker(onPick: (pkg: String, cls: String?, label: String?) -> Unit)
    }

    val view: GridWorkspaceView = GridWorkspaceView(activity, this)

    /** True while a widget is being dragged/resized — the board must not steal the gesture to close. */
    fun isEditing(): Boolean = view.isEditing()

    private var spaceId: String = "home"
    private var pendingWidgetId: Int = -1
    private var pendingProvider: AppWidgetProviderInfo? = null

    /** Show [spaceId]'s board, seeding it from [seedApps] the first time it's opened. */
    fun open(spaceId: String, seedApps: List<SpaceBoardSeed.SeedApp>) {
        this.spaceId = spaceId
        val items = if (GridWorkspaceStore.hasSpaceBoard(activity, spaceId)) {
            GridWorkspaceStore.loadSpace(activity, spaceId)
        } else {
            SpaceBoardSeed.seed(seedApps).also { GridWorkspaceStore.saveSpace(activity, spaceId, it) }
        }
        view.setItems(items)
    }

    // ---- GridWorkspaceView.Host ------------------------------------------------------------

    override fun launchApp(item: GridItem) {
        val pkg = item.packageName ?: return
        callbacks.launchAppFromBoard(pkg, item.className, item.label)
    }

    override fun openFolder(item: GridItem) { /* folders reuse the grid's own popup; no-op here */ }

    override fun itemsChanged(items: List<GridItem>) =
        GridWorkspaceStore.saveSpace(activity, spaceId, items)

    override fun widgetRemoved(widgetId: Int) = callbacks.deleteWidgetId(widgetId)

    override fun widgetResized(item: GridItem, widthPx: Int, heightPx: Int) {
        val density = activity.resources.displayMetrics.density
        val wDp = (widthPx / density).roundToInt()
        val hDp = (heightPx / density).roundToInt()
        widgetIdsOf(item).forEach { callbacks.updateWidgetSize(it, wDp, hDp) }
    }

    override fun addRequested() {
        callbacks.showAddChooser(
            onAddApp = { callbacks.showAppPicker { pkg, cls, label -> addApp(pkg, cls, label) } },
            onAddWidget = { callbacks.showWidgetPicker { provider -> addWidget(provider) } },
        )
    }

    override fun loadIcon(packageName: String?, className: String?): Drawable? =
        callbacks.loadIcon(packageName, className)

    override fun createWidgetView(widgetId: Int): View? = callbacks.createWidgetHostView(widgetId)

    // ---- adding apps / widgets -------------------------------------------------------------

    private fun addApp(pkg: String, cls: String?, label: String?) {
        val cell = firstFreeCell(1, 1) ?: run {
            Toast.makeText(activity, "Board is full", Toast.LENGTH_SHORT).show(); return
        }
        val item = GridItem(
            id = "app_${pkg}_${cell.first}_${cell.second}",
            type = GridItemType.APP, cellX = cell.first, cellY = cell.second,
            packageName = pkg, className = cls, label = label,
        )
        commit(view.currentItems() + item)
    }

    private fun addWidget(provider: AppWidgetProviderInfo) {
        val widgetId = callbacks.allocateWidgetId()
        pendingWidgetId = widgetId
        pendingProvider = provider
        if (callbacks.bindWidgetIfAllowed(widgetId, provider)) {
            configureOrPlace(widgetId, provider)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
            }
            callbacks.startWidgetResultIntent(intent, REQUEST_BIND)
        }
    }

    private fun configureOrPlace(widgetId: Int, provider: AppWidgetProviderInfo) {
        if (provider.configure != null) {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = provider.configure
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            runCatching { callbacks.startWidgetResultIntent(intent, REQUEST_CONFIGURE) }
                .onFailure { placeWidget(widgetId, provider) }
        } else {
            placeWidget(widgetId, provider)
        }
    }

    /** Forwarded from the launcher's onActivityResult. Returns true if it handled the code. */
    fun onWidgetResult(requestCode: Int, ok: Boolean): Boolean {
        val widgetId = pendingWidgetId
        val provider = pendingProvider
        when (requestCode) {
            REQUEST_BIND -> {
                if (ok && widgetId >= 0 && provider != null) configureOrPlace(widgetId, provider)
                else abandonPending()
                return true
            }
            REQUEST_CONFIGURE -> {
                if (ok && widgetId >= 0 && provider != null) placeWidget(widgetId, provider)
                else abandonPending()
                return true
            }
        }
        return false
    }

    private fun placeWidget(widgetId: Int, provider: AppWidgetProviderInfo) {
        val spanX = spanForCells(provider.minWidth).coerceIn(1, GRID_COLS)
        val spanY = spanForCells(provider.minHeight).coerceIn(1, GRID_ROWS)
        val cell = firstFreeCell(spanX, spanY) ?: firstFreeCell(1, 1)
        if (cell == null) {
            callbacks.deleteWidgetId(widgetId)
            Toast.makeText(activity, "No room for that widget", Toast.LENGTH_SHORT).show()
            abandonPending(); return
        }
        val fitX = spanX.coerceAtMost(GRID_COLS - cell.first)
        val fitY = spanY.coerceAtMost(GRID_ROWS - cell.second)
        val item = GridItem(
            id = "widget_$widgetId", type = GridItemType.WIDGET,
            cellX = cell.first, cellY = cell.second, spanX = fitX, spanY = fitY,
            widgetId = widgetId,
        )
        commit(view.currentItems() + item)
        pendingWidgetId = -1; pendingProvider = null
    }

    private fun abandonPending() {
        if (pendingWidgetId >= 0) callbacks.deleteWidgetId(pendingWidgetId)
        pendingWidgetId = -1; pendingProvider = null
    }

    private fun commit(items: List<GridItem>) {
        view.setItems(items)
        GridWorkspaceStore.saveSpace(activity, spaceId, items)
    }

    /** ~64dp per cell → how many cells a widget's min size wants. */
    private fun spanForCells(minPx: Int): Int {
        val cellDp = 74f
        val minDp = minPx / activity.resources.displayMetrics.density
        return (minDp / cellDp).roundToInt().coerceAtLeast(1)
    }

    private fun firstFreeCell(spanX: Int, spanY: Int): Pair<Int, Int>? {
        val items = view.currentItems()
        for (y in 0..(GRID_ROWS - spanY)) {
            for (x in 0..(GRID_COLS - spanX)) {
                val clash = items.any { cellsOverlap(x, y, spanX, spanY, it.cellX, it.cellY, it.spanX, it.spanY) }
                if (!clash) return x to y
            }
        }
        return null
    }

    companion object {
        const val REQUEST_BIND = 611
        const val REQUEST_CONFIGURE = 612
    }
}
