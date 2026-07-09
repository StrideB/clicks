package com.fran.teclas.grid

/**
 * Builds the initial layout for a Space's unified board so it never opens empty: the Space's
 * recognized apps flow across the top rows as 1×1 tiles, leaving the rest of the grid open for
 * the user to drop and resize widgets. Apps and widgets share one canvas — a seeded app tile
 * and a hand-placed widget are the same kind of object on the same grid.
 *
 * Pure (no Android): a list of (package, class, label) in rank order becomes placed GridItems.
 */
object SpaceBoardSeed {

    data class SeedApp(val packageName: String, val className: String?, val label: String?)

    /**
     * Lay [apps] left-to-right, top-to-bottom as APP tiles. [reserveBottomRows] keeps the
     * lower rows clear as an obvious "drop widgets here" zone. Caps at the space available.
     */
    fun seed(apps: List<SeedApp>, cols: Int = GRID_COLS, rows: Int = GRID_ROWS, reserveBottomRows: Int = 3): List<GridItem> {
        val appRows = (rows - reserveBottomRows).coerceAtLeast(1)
        val capacity = cols * appRows
        return apps.take(capacity).mapIndexed { i, app ->
            GridItem(
                id = "seed_${app.packageName}_$i",
                type = GridItemType.APP,
                cellX = i % cols,
                cellY = i / cols,
                packageName = app.packageName,
                className = app.className,
                label = app.label,
            )
        }
    }
}
