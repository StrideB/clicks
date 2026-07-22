package com.fran.teclas.galaxy

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import com.fran.teclas.CalendarEvent
import com.fran.teclas.R

/**
 * Android 16 Live Update for the next calendar event. Promoted ongoing notifications render
 * in Samsung's Now Bar (lock screen + status chip) on One UI 8+, and pinned on the lock
 * screen on stock Android 16 — so the launcher's "what's next" travels with the user even
 * while another app is in front.
 *
 * Pure AOSP surface: `ProgressStyle` + `setRequestPromotedOngoing` + the
 * `POST_PROMOTED_NOTIFICATIONS` manifest permission. No Samsung SDK involved.
 *
 * Refresh model: [sync] is invoked from the launcher's existing foreground cadence (resume,
 * calendar refresh, the 60 s context tick) — no alarms, no background work, honoring the
 * repo's battery stance. The countdown chip stays live while backgrounded via the
 * notification chronometer (renders device-side, no re-posts); begin/end transitions settle
 * on the next foreground tick.
 */
object NowBarLiveUpdate {

    /** Default-on: the surface only exists on API 36+, and [sync] no-ops elsewhere. */
    const val ENABLED_PREF = "now_bar_live_updates"

    private const val CHANNEL_ID = "next_event_live"
    private const val NOTIFICATION_ID = 0x7EC1A5
    /** How far ahead of the event start the Live Update appears. */
    private const val LEAD_MS = 90 * 60 * 1000L

    /** Brand default accent (Cursor Violet) used when the caller doesn't pass its theme accent. */
    private const val DEFAULT_ACCENT = 0xFFC9A7FF.toInt()

    fun enabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(ENABLED_PREF, true)

    /**
     * Publish, update, or clear the Live Update for [events]. Safe to call often — it only
     * re-posts when there is something to show, and `setOnlyAlertOnce` keeps updates silent.
     */
    fun sync(
        context: Context,
        prefs: SharedPreferences,
        events: List<CalendarEvent>,
        accentColor: Int = DEFAULT_ACCENT,
    ) {
        if (!GalaxyDevice.supportsLiveUpdates()) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (!enabled(prefs) || !canPost(context)) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        val now = System.currentTimeMillis()
        val event = events
            .filter { it.endMs > now && it.beginMs - now <= LEAD_MS }
            .minByOrNull { it.beginMs }
        if (event == null) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        ensureChannel(manager)
        manager.notify(NOTIFICATION_ID, build(context, event, now, accentColor))
    }

    fun clear(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.cancel(NOTIFICATION_ID)
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(manager: NotificationManager) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Next event", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Live countdown to your next calendar event, shown on the " +
                    "${GalaxyDevice.liveUpdateSurfaceLabel()} while you're away from the launcher"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun build(context: Context, event: CalendarEvent, now: Long, accentColor: Int): android.app.Notification {
        val ongoing = now >= event.beginMs
        val progress = if (ongoing) {
            val span = (event.endMs - event.beginMs).coerceAtLeast(1L)
            (100L * (now - event.beginMs) / span).toInt().coerceIn(0, 100)
        } else {
            (100L * (LEAD_MS - (event.beginMs - now)) / LEAD_MS).toInt().coerceIn(0, 100)
        }
        val style = NotificationCompat.ProgressStyle()
            .setStyledByProgress(false)
            .setProgress(progress)
            .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(100).setColor(accentColor)))

        val subtitle = listOf(event.timeLabel, event.location).filter { it.isNotBlank() }.joinToString(" · ")
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(event.title.ifBlank { "Next event" })
            .setContentText(subtitle.ifBlank { if (ongoing) "Happening now" else "Coming up" })
            .setStyle(style)
            .setColor(accentColor)
            .setColorized(false)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setRequestPromotedOngoing(true)
            // Chronometer counts down to the start (then up from it) on the device clock, so
            // the visible countdown stays live even though we only re-post while foreground.
            .setWhen(event.beginMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(!ongoing)
            .setContentIntent(contentIntent(context, event))
        // The Now Bar's collapsed status chip shows this instead of the full notification.
        if (ongoing) builder.setShortCriticalText("Now")
        event.joinUrl?.takeIf { it.isNotBlank() }?.let { url ->
            builder.addAction(
                NotificationCompat.Action.Builder(
                    null, "Join",
                    PendingIntent.getActivity(
                        context, NOTIFICATION_ID + 1,
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                ).build()
            )
        }
        return builder.build()
    }

    private fun contentIntent(context: Context, event: CalendarEvent): PendingIntent {
        val intent = if (event.eventId > 0) {
            Intent(
                Intent.ACTION_VIEW,
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(context, com.fran.teclas.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
