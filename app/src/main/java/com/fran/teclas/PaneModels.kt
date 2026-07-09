package com.fran.teclas

import android.net.Uri

// Pure value types extracted verbatim from MainActivity (nested `private` declarations) to trim
// that file. Behaviour is unchanged: none of these touch Activity state. They are widened from
// `private` to `internal` so MainActivity keeps resolving them by the same simple names, while
// staying module-scoped. No fields, ordering, or logic changed. (The original file-end model
// types — PaneTarget, SearchResult, … — live in LauncherModels.kt from an earlier extraction.)

internal enum class WidgetKeyboardSwapState { SEATED, DETACHING, DETACHED, SEATING }

internal data class InnerFocusAction(
    val label: String,
    val accent: Int,
    val run: () -> Unit
)

internal data class InnerFocusHero(
    val eyebrow: String,
    val title: String,
    val subtitle: String,
    val glyph: String,
    val accent: Int,
    val secondaryAccent: Int,
    val sideNowTitle: String,
    val sideNowBody: String,
    val sideMediaTitle: String,
    val sideMediaBody: String,
    val actions: List<InnerFocusAction>,
    val run: () -> Unit
)

internal data class WidgetBoardMetrics(
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

internal data class SearchCommandPreview(val title: String, val subtitle: String, val glyph: String)

internal enum class MusicMode { SPOTIFY_FULL, APPLE_FULL, SIMPLE }

internal enum class WheelZone { CENTER, LEFT, RIGHT, TOP, BOTTOM }

internal data class HomeDockItem(
    val app: AppEntry?,
    val target: PaneTarget,
    val label: String,
    val accent: Int
)

internal data class SettingSearchEntry(
    val title: String,
    val state: String,
    val keywords: List<String>,
    val perform: () -> Unit
)

internal data class LocalFileHit(val name: String, val uri: Uri, val mimeType: String?)

internal data class GeminiAction(
    val action: String,
    val target: String = "",
    val message: String = "",
    val answer: String = ""
)

internal data class ColorOption(val name: String, val color: Int)
