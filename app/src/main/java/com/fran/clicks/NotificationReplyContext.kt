package com.fran.clicks

import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Zero-keystroke reply chips. Reads the messages the existing ClicksNotificationListener already
 * persisted to the shared "clicks" prefs (no second notification service), takes the most recent
 * incoming message, and extracts quick replies from it (proposed times, yes/no, "on my way"). The
 * keyboard surfaces these when you open it on an empty field to reply.
 */
object NotificationReplyContext {

    private const val HUB_MESSAGES_PREF = "hub_messages"
    private const val RECENCY_MS = 15 * 60 * 1000L

    fun quickReplies(prefs: SharedPreferences, now: Long): List<String> {
        val raw = prefs.getString(HUB_MESSAGES_PREF, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        var bestPreview: String? = null
        var bestTime = 0L
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kind = o.optString("kind")
            if (kind != "message" && kind != "email") continue
            val t = o.optLong("lastUpdated", 0L)
            if (t > bestTime) { bestTime = t; bestPreview = o.optString("preview") }
        }
        val preview = bestPreview ?: return emptyList()
        if (now - bestTime > RECENCY_MS) return emptyList()
        return extractReplies(preview)
    }

    private fun extractReplies(text: String): List<String> {
        val out = LinkedHashSet<String>()
        // Times like 2:00, 3:30 pm, 4pm — requires ":" or am/pm so plain numbers don't match.
        val timeRegex = Regex("""\b\d{1,2}(:\d{2}\s?(am|pm)?|\s?(am|pm))\b""", RegexOption.IGNORE_CASE)
        timeRegex.findAll(text).map { it.value.trim() }.take(2).forEach { out.add(it) }

        val lower = text.lowercase()
        when {
            lower.contains("where are you") || lower.contains("you coming") ||
                lower.contains("here yet") || lower.contains("otw?") -> out.add("On my way")
        }
        if (text.contains("?") && out.size < 3) {
            // A yes/no-style question with no times — offer quick affirmatives.
            if (out.isEmpty()) { out.add("Yes"); out.add("No"); out.add("Maybe") }
        }
        return out.take(3).toList()
    }
}
