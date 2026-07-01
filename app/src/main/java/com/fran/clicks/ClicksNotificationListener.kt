package com.fran.clicks

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class ClicksNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!sbn.isHubCandidate()) return

        val extras = sbn.notification.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val preview = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (sender.isBlank() && preview.isBlank()) return

        val item = JSONObject()
            .put("key", sbn.key)
            .put("sender", sender.ifBlank { packageManagerLabel(sbn.packageName) })
            .put("preview", preview)
            .put("packageName", sbn.packageName)
            .put("kind", sbn.hubKind())
            .put("color", colorForPackage(sbn.packageName))

        val current = readMessages().filterNot { it.optString("key") == sbn.key }
        val next = JSONArray()
        next.put(item)
        current.take(MAX_MESSAGES - 1).forEach { next.put(it) }
        prefs().edit().putString(HUB_MESSAGES_PREF, next.toString()).apply()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val next = JSONArray()
        readMessages()
            .filterNot { it.optString("key") == sbn.key }
            .forEach { next.put(it) }
        prefs().edit().putString(HUB_MESSAGES_PREF, next.toString()).apply()
    }

    private fun StatusBarNotification.isHubCandidate(): Boolean {
        if (notification.category == Notification.CATEGORY_MESSAGE) return true
        if (notification.category == Notification.CATEGORY_EMAIL) return true
        return packageName in MESSAGE_PACKAGES || packageName in EMAIL_PACKAGES
    }

    private fun StatusBarNotification.hubKind(): String {
        if (notification.category == Notification.CATEGORY_EMAIL || packageName in EMAIL_PACKAGES) return HUB_KIND_EMAIL
        return HUB_KIND_MESSAGE
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

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "clicks"
        private const val HUB_MESSAGES_PREF = "hub_messages"
        private const val HUB_KIND_MESSAGE = "message"
        private const val HUB_KIND_EMAIL = "email"
        private const val MAX_MESSAGES = 12

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
    }
}
