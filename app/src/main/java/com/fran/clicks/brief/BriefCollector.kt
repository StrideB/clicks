package com.fran.clicks.brief

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import com.fran.clicks.CalendarEvent
import com.fran.clicks.ClicksNotificationListener

/**
 * Pure data layer for the Today brief. Gathers raw [Signal]s from the integrations the launcher
 * already owns — no UI, no ranking. Calendar/weather are supplied as providers so this class stays
 * free of Android query code and reuses MainActivity's existing loaders.
 *
 * PendingIntents are copied through untouched — that handle is the whole feature.
 */
class BriefCollector(
    private val calendarProvider: () -> List<CalendarEvent>,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    // Weather is intentionally NOT collected — the homescreen already shows it, so it would just be
    // noise in Today. WeatherSignal remains in the model for potential future use.
    fun collect(): List<Signal> {
        val out = ArrayList<Signal>()
        out += notificationSignals()
        out += calendarSignals()
        return out
    }

    private fun notificationSignals(): List<NotificationSignal> =
        ClicksNotificationListener.briefSnapshot()
            .filter { BriefClassifier.isActionable(it) } // Today = actionable only; the rest go to the widget stack.
            .map { r ->
            val actions = ArrayList<BriefAction>(r.actions.size + 1)
            r.actions.forEach { a ->
                actions += Fire(a.label, a.pendingIntent, a.remoteInput, a.extraInputs)
            }
            // Surface the contentIntent as "Open" unless an inline action already opens it.
            if (r.contentIntent != null && r.actions.none { it.label.equals("Open", ignoreCase = true) }) {
                actions += Fire("Open", r.contentIntent)
            }
            NotificationSignal(
                id = r.key,
                timestamp = r.whenMs,
                packageName = r.packageName,
                appLabel = r.appLabel,
                title = r.title,
                text = r.text,
                category = r.category,
                personName = r.personName,
                contentIntent = r.contentIntent,
                actions = actions,
                avatar = r.avatar
            )
        }

    private fun calendarSignals(): List<CalendarSignal> {
        val now = nowMs()
        // Upcoming or currently-running events only; drop long-finished ones.
        return calendarProvider()
            .filter { it.endMs >= now - GRACE_MS }
            .sortedBy { it.beginMs }
            .take(MAX_CALENDAR)
            .map { e ->
                val actions = ArrayList<BriefAction>(2)
                val viewUri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.eventId)
                actions += Launch("Open", Intent(Intent.ACTION_VIEW, viewUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                e.joinUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    actions += Launch("Join", Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
                CalendarSignal(
                    id = "cal:${e.eventId}",
                    timestamp = e.beginMs,
                    title = e.title,
                    beginMillis = e.beginMs,
                    timeLabel = e.timeLabel,
                    location = e.location.takeIf { it.isNotBlank() },
                    actions = actions
                )
            }
    }

    private companion object {
        const val MAX_CALENDAR = 3
        const val GRACE_MS = 5 * 60_000L
    }
}
