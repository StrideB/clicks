package com.fran.clicks

import android.content.ComponentName

// Pure data/enum model types extracted verbatim from MainActivity to trim that file. Behaviour is
// unchanged: these were private nested types used only within MainActivity (verified: no external
// references). They are widened from `private` to `internal` so MainActivity keeps resolving them
// by the same simple names, while staying module-scoped. No fields, ordering, or logic changed.
internal enum class PaneKind { CHAT, MAIL, LIST, SETTINGS, MUSIC, PHOTOS, AI }
internal enum class ShiftState { OFF, ONCE, LOCK }

internal data class PaneTarget(val id: String, val name: String, val accent: Int, val kind: PaneKind,
    val packageName: String?, val deepLinkUri: String?, val preview: String)
internal data class AppEntry(val label: String, val shortName: String, val packageName: String, val componentName: ComponentName, val brandColor: Int)
internal data class LibraryCategory(val name: String, val accent: Int, val apps: List<LibraryApp>)
internal data class LibraryApp(val name: String, val accent: Int, val target: PaneTarget, val componentName: ComponentName?)
internal data class IconPack(val name: String, val packageName: String)
internal data class IconPackIcon(val packageName: String, val drawableName: String)
internal data class RibbonEntry(val label: String, val accent: Int, val target: PaneTarget)
internal data class HomeTileSpec(val id: String, val col: Int, val row: Int, val colSpan: Int, val rowSpan: Int)
internal data class HubMessage(val key: String, val sender: String, val preview: String, val packageName: String, val kind: String, val color: Int, val lastUpdated: Long)
internal data class ChatLine(val text: String, val fromMe: Boolean)
internal data class ContactMatch(val name: String, val value: String)
internal data class ContactCommand(val contact: ContactMatch, val message: String)
internal data class CalendarCommand(val title: String, val startMs: Long, val endMs: Long)
internal data class AiAnswerState(val prompt: String, val answer: String, val loading: Boolean)
internal data class SearchResult(val title: String, val subtitle: String, val accent: Int, val kind: SearchKind, val target: PaneTarget?, val action: (() -> Unit)? = null)
internal enum class SearchKind { APP, CONTACT, EMAIL, MESSAGE, CALENDAR, AI, TRAVEL, MUSIC, FILE }

internal data class FlightSegment(
    val airline: String, val flightNumber: String,
    val from: String, val to: String,
    val depart: String, val arrive: String,
    val date: String, val confirmation: String, val seat: String
)
internal data class WeatherSnapshot(val tempF: Int, val feelsLikeF: Int, val humidity: Int, val windMph: Int, val code: Int, val label: String)
data class WidgetSpec(
    val id: Int,
    val cellX: Int,
    val cellY: Int,
    val spanX: Int,
    val spanY: Int,
    val minSpanX: Int = 1,
    val minSpanY: Int = 1
)
internal data class WidgetGridSize(val columns: Int, val rows: Int)
