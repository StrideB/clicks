package com.fran.teclas.brief

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap

/**
 * "Today" brief models.
 *
 * Two layers, kept deliberately separate:
 *  - [Signal] / [BriefAction] are the RAW, collected data. They hold live [PendingIntent]s — the
 *    whole point of the feature — so they are never serialized. They live only while the process
 *    does; on a cold start we re-collect to rebuild them.
 *  - [BriefItem] / [Brief] are the RANKED + PHRASED output. Gemini (or the rule-based fallback)
 *    only ranks and re-words; it never touches the intents. The app re-binds the real [Signal] to
 *    each item by [BriefItem.signalRef] + [BriefItem.primaryActionLabel].
 */
sealed interface Signal {
    /** Stable reference the ranker echoes back as [BriefItem.signalRef]. */
    val id: String
    val timestamp: Long
    val actions: List<BriefAction>
}

data class NotificationSignal(
    override val id: String,          // == StatusBarNotification.key
    override val timestamp: Long,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val contentHash: String,
    val taskDraft: String?,
    val category: String,             // normalized: message | email | call | other
    val personName: String?,
    /** The notification's contentIntent, surfaced as the "Open" action. */
    val contentIntent: PendingIntent?,
    /** One [BriefAction] per Notification.actions[] entry, plus "Open" if [contentIntent] exists. */
    override val actions: List<BriefAction>,
    val avatar: Bitmap? = null
) : Signal

data class CalendarSignal(
    override val id: String,          // "cal:<eventId>"
    override val timestamp: Long,     // begin time (for proximity ranking)
    val title: String,
    val beginMillis: Long,
    val timeLabel: String,
    val location: String?,
    override val actions: List<BriefAction>
) : Signal

data class WeatherSignal(
    override val id: String,          // "weather"
    override val timestamp: Long,
    val summary: String,
    override val actions: List<BriefAction> = emptyList()
) : Signal

/** Optional now-playing media. Modeled for completeness; not collected in v1 (widget stack owns it). */
data class MediaSignal(
    override val id: String,          // "media"
    override val timestamp: Long,
    val title: String,
    val artist: String,
    override val actions: List<BriefAction>
) : Signal

sealed interface BriefAction {
    val label: String
}

/**
 * A real notification action: fire its [pendingIntent]. If it carries a [remoteInput] the card
 * lets the user type a reply and we send it via RemoteInput.addResultsToIntent + PendingIntent.send.
 */
data class Fire(
    override val label: String,
    val pendingIntent: PendingIntent,
    val remoteInput: RemoteInput? = null,
    val extraInputs: Array<RemoteInput> = emptyArray()
) : BriefAction {
    val isReply: Boolean get() = remoteInput != null

    // Array field ⇒ hand-write equals/hashCode so data-class semantics stay sane.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Fire) return false
        return label == other.label &&
            pendingIntent == other.pendingIntent &&
            remoteInput == other.remoteInput &&
            extraInputs.contentEquals(other.extraInputs)
    }

    override fun hashCode(): Int {
        var result = label.hashCode()
        result = 31 * result + pendingIntent.hashCode()
        result = 31 * result + (remoteInput?.hashCode() ?: 0)
        result = 31 * result + extraInputs.contentHashCode()
        return result
    }
}

/** An app / deep-link Intent we start ourselves (open calendar event, join link, etc.). */
data class Launch(
    override val label: String,
    val intent: Intent
) : BriefAction

// -------------------------------------------------------------------------------------------------
// Raw notification capture (produced by TeclasNotificationListener, consumed by BriefCollector).
// Held in a process-lifetime registry; never serialized.
// -------------------------------------------------------------------------------------------------

data class RawAction(
    val label: String,
    val pendingIntent: PendingIntent,
    val remoteInput: RemoteInput?,
    val extraInputs: Array<RemoteInput>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawAction) return false
        return label == other.label &&
            pendingIntent == other.pendingIntent &&
            remoteInput == other.remoteInput &&
            extraInputs.contentEquals(other.extraInputs)
    }

    override fun hashCode(): Int {
        var result = label.hashCode()
        result = 31 * result + pendingIntent.hashCode()
        result = 31 * result + (remoteInput?.hashCode() ?: 0)
        result = 31 * result + extraInputs.contentHashCode()
        return result
    }
}

data class NotificationRecord(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val contentHash: String,
    val category: String,             // message | email | call | other
    val personName: String?,
    val whenMs: Long,
    val contentIntent: PendingIntent?,
    val actions: List<RawAction>,
    val avatar: Bitmap? = null
)

// -------------------------------------------------------------------------------------------------
// Ranked + phrased output.
// -------------------------------------------------------------------------------------------------

enum class BriefCategory {
    MESSAGE, EMAIL, CALL, CALENDAR, WEATHER, MUSIC, OTHER;

    companion object {
        fun from(raw: String?): BriefCategory = when (raw?.trim()?.lowercase()) {
            "message", "sms", "chat" -> MESSAGE
            "email", "mail" -> EMAIL
            "call", "phone", "missed_call" -> CALL
            "calendar", "event" -> CALENDAR
            "weather" -> WEATHER
            "music", "media" -> MUSIC
            else -> OTHER
        }
    }
}

data class BriefItem(
    val signalRef: String,            // == Signal.id
    val title: String,                // <= 60 chars, imperative
    val subtitle: String,
    val category: BriefCategory,
    val primaryActionLabel: String,   // matches one of the signal's action labels (incl. "Open")
    /**
     * The re-bound live signal. Null only for a cache-restored brief on cold start (text renders
     * instantly; a background refresh rebinds real intents moments later).
     */
    val signal: Signal? = null
)

data class Brief(
    val items: List<BriefItem>,
    val generatedAt: Long,
    val source: Source
) {
    enum class Source { GEMINI, RULES, CACHE, EMPTY }

    companion object {
        val EMPTY = Brief(emptyList(), 0L, Source.EMPTY)
    }
}
