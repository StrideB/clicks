package com.fran.clicks

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import kotlin.math.abs

class ClicksNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        activeNotifications.orEmpty().forEach { onNotificationPosted(it) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        // Iterating a synchronizedMap needs the lock held for the whole sweep, else a concurrent
        // post could mutate it mid-iteration and throw ConcurrentModificationException.
        synchronized(notificationAvatars) {
            notificationAvatars.values.forEach { runCatching { it.recycle() } }
            notificationAvatars.clear()
        }
        notificationIntents.clear()
        replyActions.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!sbn.isHubCandidate()) return

        val extras = sbn.notification.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val preview = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (sender.isBlank() && preview.isBlank()) return
        val kind = sbn.hubKind()
        if (kind == HUB_KIND_MESSAGE && !isConversationPerson(sender, preview, sbn.packageName)) return
        if (kind in DIRECT_OPEN_KINDS && sbn.notification.contentIntent == null) return

        sbn.notification.contentIntent?.let { notificationIntents[sbn.key] = it }
        captureReplyAction(sbn)
        notificationAvatar(sbn.notification)?.let { newBitmap ->
            // Atomic size-check + FIFO evict + put: without the lock, two posts could each see
            // size < MAX, both put, and overflow the cap — or evict the same key twice.
            synchronized(notificationAvatars) {
                if (notificationAvatars.size >= MAX_AVATARS) {
                    val evict = notificationAvatars.keys.firstOrNull()
                    if (evict != null) notificationAvatars.remove(evict)?.let { runCatching { it.recycle() } }
                }
                notificationAvatars[sbn.key] = newBitmap
            }
        }

        val item = JSONObject()
            .put("key", sbn.key)
            .put("sender", sender.trim())
            .put("preview", preview)
            .put("packageName", sbn.packageName)
            .put("kind", kind)
            .put("color", colorForPackage(sbn.packageName))
            .put("lastUpdated", System.currentTimeMillis())

        val current = readMessages().filterNot { it.optString("key") == sbn.key }
        val next = JSONArray()
        next.put(item)
        current.take(MAX_MESSAGES - 1).forEach { next.put(it) }
        prefs().edit().putString(HUB_MESSAGES_PREF, next.toString()).apply()
    }

    // Find a direct-reply action (RemoteInput) on this notification and remember it, so the launcher
    // can reply to a message (Telegram, WhatsApp, …) inline without opening the app.
    private fun captureReplyAction(sbn: StatusBarNotification) {
        val actions = sbn.notification.actions ?: return
        for (action in actions) {
            val intent = action.actionIntent ?: continue
            val remoteInput = action.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: continue
            replyActions[sbn.key] = ReplyAction(intent, remoteInput.resultKey, sbn.packageName)
            return
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        notificationIntents.remove(sbn.key)
        replyActions.remove(sbn.key)
        notificationAvatars.remove(sbn.key)?.let { runCatching { it.recycle() } }
        val next = JSONArray()
        readMessages()
            .filterNot { it.optString("key") == sbn.key }
            .forEach { next.put(it) }
        prefs().edit().putString(HUB_MESSAGES_PREF, next.toString()).apply()
    }

    private fun StatusBarNotification.isHubCandidate(): Boolean {
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (notification.category == Notification.CATEGORY_MESSAGE) return true
        if (notification.category == Notification.CATEGORY_EMAIL) return true
        if (packageName in NEWS_PACKAGES) return true
        if (packageName in MAPS_PACKAGES && isMapsContextNotification()) return true
        return packageName in MESSAGE_PACKAGES || packageName in EMAIL_PACKAGES
    }

    private fun StatusBarNotification.hubKind(): String {
        if (notification.category == Notification.CATEGORY_EMAIL || packageName in EMAIL_PACKAGES) return HUB_KIND_EMAIL
        if (packageName in NEWS_PACKAGES) return HUB_KIND_NEWS
        if (packageName in MAPS_PACKAGES) return HUB_KIND_MAPS
        return HUB_KIND_MESSAGE
    }

    private fun StatusBarNotification.isMapsContextNotification(): Boolean {
        val extras = notification.extras
        val text = listOf(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        ).joinToString(" ")
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return true
        return text.contains(Regex("\\b(navigat|driv|ETA|traffic|route|arrival)\\b", RegexOption.IGNORE_CASE))
    }

    private fun readMessages(): List<JSONObject> {
        val raw = prefs().getString(HUB_MESSAGES_PREF, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(it) }
            }
        }
    }

    private fun packageManagerLabel(packageName: String): String {
        return runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault("Message")
    }

    private fun isConversationPerson(sender: String, preview: String, packageName: String): Boolean {
        val cleanSender = sender.trim()
        val cleanPreview = preview.trim()
        if (cleanSender.isBlank()) return false
        if (cleanSender.equals(packageManagerLabel(packageName), ignoreCase = true)) return false
        if (cleanSender.equals(packageName.substringAfterLast('.'), ignoreCase = true)) return false
        if (cleanPreview.contains(Regex("\\b\\d+\\s+messages?\\s+from\\b", RegexOption.IGNORE_CASE))) return false
        if (cleanSender.contains(Regex("\\b\\d+\\s+messages?\\b", RegexOption.IGNORE_CASE))) return false
        return true
    }

    private fun colorForPackage(packageName: String): Int {
        val palette = intArrayOf(
            0xFF5FD0C4.toInt(),
            0xFF54A9EB.toInt(),
            0xFFF5C451.toInt(),
            0xFFFF5A3C.toInt(),
            0xFFC4B5FF.toInt(),
            0xFF8FD694.toInt()
        )
        return palette[abs(packageName.hashCode()) % palette.size]
    }

    private fun notificationAvatar(notification: Notification): Bitmap? {
        val largeIconDrawable = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            notification.getLargeIcon()?.loadDrawable(this)
        } else {
            @Suppress("DEPRECATION")
            notification.largeIcon?.let { android.graphics.drawable.BitmapDrawable(resources, it) }
        }
        return largeIconDrawable?.toBitmap(96, 96)
    }

    private fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
        if (this is android.graphics.drawable.BitmapDrawable && bitmap != null) return bitmap
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "clicks"
        private const val HUB_MESSAGES_PREF = "hub_messages"
        private const val HUB_KIND_MESSAGE = "message"
        private const val HUB_KIND_EMAIL = "email"
        private const val HUB_KIND_NEWS = "news"
        private const val HUB_KIND_MAPS = "maps"
        private const val MAX_MESSAGES = 12
        private const val MAX_AVATARS = 20
        private val DIRECT_OPEN_KINDS = setOf(HUB_KIND_EMAIL, HUB_KIND_NEWS, HUB_KIND_MAPS)
        // Shared between the listener service (writer) and MainActivity (reader). Wrapped in a
        // synchronized LinkedHashMap so per-op access is safe AND insertion order is preserved,
        // which the avatar FIFO eviction below relies on. Reads in MainActivity are single-key
        // get/remove (atomic here); the service's compound size-check-evict-put and the
        // clear-and-recycle sweep are guarded with synchronized(notificationAvatars) blocks.
        // TODO: These process-lifetime static maps are still a leak vector (bitmaps outlive any
        //       Activity). A lifecycle-scoped holder would be more robust, but that changes
        //       ownership/behavior, so it's out of scope for this thread-safety-only fix.
        val notificationIntents: MutableMap<String, PendingIntent> =
            Collections.synchronizedMap(LinkedHashMap())
        val notificationAvatars: MutableMap<String, Bitmap> =
            Collections.synchronizedMap(LinkedHashMap())

        // Live listener instance, set while connected, so the launcher can actually cancel a
        // notification when the user dismisses it from the today hub (not just hide it locally).
        @Volatile private var instance: ClicksNotificationListener? = null

        /** Dismiss a hub notification: drop it from the hub prefs, forget its intent/avatar, and —
         *  if we're connected — cancel the underlying system notification so it doesn't come back. */
        // A captured inline-reply action: the pending intent to fire plus the RemoteInput key the
        // target app expects the reply text under.
        data class ReplyAction(val intent: PendingIntent, val resultKey: String, val packageName: String)

        val replyActions: MutableMap<String, ReplyAction> =
            Collections.synchronizedMap(LinkedHashMap())

        /** True when the notification for [key] carries a direct-reply action we can drive. */
        fun canReply(key: String): Boolean = key.isNotBlank() && replyActions[key] != null

        /** Send [text] as an inline reply to the notification for [key] via its RemoteInput. */
        fun sendReply(context: Context, key: String, text: String): Boolean {
            val action = replyActions[key] ?: return false
            if (text.isBlank()) return false
            return runCatching {
                val fill = Intent()
                val results = android.os.Bundle().apply { putCharSequence(action.resultKey, text) }
                android.app.RemoteInput.addResultsToIntent(
                    arrayOf(android.app.RemoteInput.Builder(action.resultKey).build()), fill, results
                )
                action.intent.send(context, 0, fill)
                true
            }.getOrDefault(false)
        }

        fun dismiss(context: Context, key: String) {
            if (key.isBlank()) return
            runCatching { instance?.cancelNotification(key) }
            notificationIntents.remove(key)
            replyActions.remove(key)
            notificationAvatars.remove(key)?.let { runCatching { it.recycle() } }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(HUB_MESSAGES_PREF, "[]") ?: "[]"
            val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
            val next = JSONArray()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                if (o.optString("key") != key) next.put(o)
            }
            prefs.edit().putString(HUB_MESSAGES_PREF, next.toString()).apply()
        }

        private val MESSAGE_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.facebook.orca",
            "com.instagram.android",
            "com.Slack"
        )

        private val EMAIL_PACKAGES = setOf(
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            "com.yahoo.mobile.client.android.mail",
            "com.readdle.spark",
            "com.protonmail.android",
            "me.bluemail.mail"
        )

        private val NEWS_PACKAGES = setOf(
            "com.google.android.apps.magazines"
        )

        private val MAPS_PACKAGES = setOf(
            "com.google.android.apps.maps"
        )
    }
}
