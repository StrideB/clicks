package com.fran.clicks.predict

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast

/**
 * The "Is this Home?" system notification. One tap on Yes configures the place — the
 * matching Space starts triggering immediately — without ever opening settings.
 * Respects the suggestions toggle, POST_NOTIFICATIONS (API 33+), and posts at most one
 * suggestion notification per day.
 */
object PlaceSuggestionNotifier {

    private const val CHANNEL_ID = "spaces_suggestions"
    private const val LAST_NOTIFIED_KEY = "predict_last_notified"
    private const val MIN_GAP_MS = 24 * 60 * 60 * 1000L

    const val ACTION_ACCEPT = "com.fran.clicks.PLACE_SUGGESTION_ACCEPT"
    const val ACTION_DISMISS = "com.fran.clicks.PLACE_SUGGESTION_DISMISS"
    const val EXTRA_KEY = "suggestion_key"

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return true
    }

    fun show(context: Context, s: PlaceInference.Suggestion) {
        if (!canPost(context)) return
        val prefs = PredictCrypto.prefs(context)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(LAST_NOTIFIED_KEY, 0L) < MIN_GAP_MS) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Space suggestions", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "One-tap confirmations when Clicks spots your home, work or an airport" }
        )
        val id = s.key.hashCode()
        fun action(action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            id + if (action == ACTION_ACCEPT) 1 else 2,
            Intent(context, PlaceSuggestionReceiver::class.java)
                .setAction(action)
                .putExtra(EXTRA_KEY, s.key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            context, id,
            Intent().setClassName(context, "com.fran.clicks.SpacesSettingsActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = PlaceInference.defaultName(s.kind)
        val question = when (s.kind) {
            PlaceKind.HOME -> "Is this home?"
            PlaceKind.WORK -> "Is this where you work?"
            PlaceKind.AIRPORT -> "Was that an airport?"
            else -> "Save this place?"
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.fran.clicks.R.drawable.ic_launcher_foreground)
            .setContentTitle(question)
            .setContentText("${s.reason}. Yes = the $name Space activates here, learned on-device.")
            .setStyle(Notification.BigTextStyle().bigText(
                "${s.reason}. Tap Yes and Clicks saves this spot as $name — the $name Space " +
                    "will arrange your apps here automatically. Learned and stored on this phone only."
            ))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Yes", action(ACTION_ACCEPT)).build())
            .addAction(Notification.Action.Builder(null, "No", action(ACTION_DISMISS)).build())
            .build()
        runCatching { manager.notify(id, notification) }
            .onSuccess { prefs.edit().putLong(LAST_NOTIFIED_KEY, now).apply() }
    }

    fun cancel(context: Context, key: String) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(key.hashCode())
    }
}

/** Handles the notification's Yes/No taps without opening any UI. */
class PlaceSuggestionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(PlaceSuggestionNotifier.EXTRA_KEY) ?: return
        when (intent.action) {
            PlaceSuggestionNotifier.ACTION_ACCEPT -> {
                val place = PlaceInference.accept(context, key)
                if (place != null) {
                    Toast.makeText(
                        context,
                        "${place.name} saved — the ${place.name} Space will activate here",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            PlaceSuggestionNotifier.ACTION_DISMISS -> PlaceInference.dismiss(context, key)
        }
        PlaceSuggestionNotifier.cancel(context, key)
    }
}
