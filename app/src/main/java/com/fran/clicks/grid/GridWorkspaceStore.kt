package com.fran.clicks.grid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Self-contained data model + persistence for the experimental "Grid Workspace" surface.
 *
 * This is deliberately decoupled from MainActivity's launcher: it owns its own SharedPreferences
 * file and its own AppWidgetHost id (see [GridWorkspaceActivity]) so it can be toggled on for
 * testing without touching the shipping home. Layout is a flat list of [GridItem] placed on a
 * fixed logical grid of [GRID_COLS] x [GRID_ROWS] cells; a half-screen (docked) preview simply
 * renders the same grid into a shorter content area, which is exactly how DeviceProfile-style
 * sizing behaves.
 */

const val GRID_COLS = 4
const val GRID_ROWS = 6

enum class GridItemType { APP, FOLDER, WIDGET }

/**
 * One placed object. Apps carry a package/class; folders carry a name + a list of app children
 * (folders never nest); widgets carry an AppWidgetHost widget id and a cell span.
 */
data class GridItem(
    val id: String,
    val type: GridItemType,
    val cellX: Int,
    val cellY: Int,
    val spanX: Int = 1,
    val spanY: Int = 1,
    // APP
    val packageName: String? = null,
    val className: String? = null,
    val label: String? = null,
    // FOLDER
    val folderName: String? = null,
    val children: List<GridItem> = emptyList(),
    // WIDGET
    val widgetId: Int = -1,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("cellX", cellX)
        put("cellY", cellY)
        put("spanX", spanX)
        put("spanY", spanY)
        packageName?.let { put("pkg", it) }
        className?.let { put("cls", it) }
        label?.let { put("label", it) }
        folderName?.let { put("folderName", it) }
        if (children.isNotEmpty()) {
            put("children", JSONArray().apply { children.forEach { put(it.toJson()) } })
        }
        if (widgetId >= 0) put("widgetId", widgetId)
    }

    companion object {
        fun fromJson(o: JSONObject): GridItem {
            val childArr = o.optJSONArray("children")
            val kids = if (childArr != null) {
                (0 until childArr.length()).map { fromJson(childArr.getJSONObject(it)) }
            } else emptyList()
            return GridItem(
                id = o.optString("id"),
                type = runCatching { GridItemType.valueOf(o.optString("type")) }.getOrDefault(GridItemType.APP),
                cellX = o.optInt("cellX"),
                cellY = o.optInt("cellY"),
                spanX = o.optInt("spanX", 1),
                spanY = o.optInt("spanY", 1),
                packageName = o.optString("pkg").ifEmpty { null },
                className = o.optString("cls").ifEmpty { null },
                label = o.optString("label").ifEmpty { null },
                folderName = o.optString("folderName").ifEmpty { null },
                children = kids,
                widgetId = o.optInt("widgetId", -1),
            )
        }
    }
}

object GridWorkspaceStore {
    private const val PREFS = "grid_workspace"
    private const val KEY_LAYOUT = "layout_v1"

    fun load(context: Context): List<GridItem> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAYOUT, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { GridItem.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, items: List<GridItem>) {
        val arr = JSONArray().apply { items.forEach { put(it.toJson()) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAYOUT, arr.toString()).apply()
    }
}

/** True when [r1] (x,y,w,h in cells) overlaps [r2]. */
fun cellsOverlap(
    x1: Int, y1: Int, w1: Int, h1: Int,
    x2: Int, y2: Int, w2: Int, h2: Int,
): Boolean = x1 < x2 + w2 && x2 < x1 + w1 && y1 < y2 + h2 && y2 < y1 + h1

/** First free top-left cell for a [spanX] x [spanY] item, or null if the grid is full. */
fun firstFreeCell(items: List<GridItem>, spanX: Int, spanY: Int): Pair<Int, Int>? {
    for (y in 0..GRID_ROWS - spanY) {
        for (x in 0..GRID_COLS - spanX) {
            val clash = items.any { cellsOverlap(x, y, spanX, spanY, it.cellX, it.cellY, it.spanX, it.spanY) }
            if (!clash) return x to y
        }
    }
    return null
}
