package com.fran.teclas.brief

import android.content.Context
import android.content.SharedPreferences
import com.fran.teclas.CalendarEvent
import com.fran.teclas.GeminiClient
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The twice-daily brief behind the freeform home widget. Fires exactly once per edition:
 * MORNING (5:00–11:59) summarizes what arrived overnight + today's shape; EVENING
 * (20:00–23:59) closes the day and tees up tomorrow. Outside the windows — or after the
 * user dismisses an edition — the widget simply isn't there.
 *
 * The lede + notification summary come from the on-device chain (Nano → local → cloud);
 * a deterministic fallback keeps the widget alive when no model can serve. Generated
 * editions persist in prefs so re-renders never re-run the model.
 */
object DailyBrief {

    private const val KEY_EDITION = "brief_edition_key"
    private const val KEY_LEDE = "brief_edition_lede"
    private const val KEY_ROWS = "brief_edition_rows"
    private const val KEY_DISMISSED = "brief_dismissed_key"
    private const val KEY_FORCED = "brief_edition_forced"
    @Volatile private var generating = false

    data class Row(val glyph: String, val title: String, val sub: String)
    data class Edition(val key: String, val morning: Boolean, val lede: String, val rows: List<Row>)

    /** "20260710-am" while inside a window, else null (no widget). */
    fun windowKey(now: Calendar = Calendar.getInstance()): String? {
        val h = now.get(Calendar.HOUR_OF_DAY)
        val day = "%04d%02d%02d".format(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        return when {
            h in 5..11 -> "$day-am"
            h >= 20 -> "$day-pm"
            else -> null
        }
    }

    /** Dev/preview fallback key when outside both windows: am before 17:00, else pm. */
    private fun forcedKey(now: Calendar = Calendar.getInstance()): String {
        val day = "%04d%02d%02d".format(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        return if (now.get(Calendar.HOUR_OF_DAY) < 17) "$day-am" else "$day-pm"
    }

    /** The edition to show right now, or null (outside window / dismissed / not generated). */
    fun current(prefs: SharedPreferences): Edition? {
        val stored = prefs.getString(KEY_EDITION, null)
        val key = windowKey() ?: (stored.takeIf { prefs.getBoolean(KEY_FORCED, false) }) ?: return null
        if (stored != key) return null
        if (prefs.getString(KEY_DISMISSED, null) == key) return null
        val lede = prefs.getString(KEY_LEDE, null)?.ifBlank { null } ?: return null
        val rows = runCatching {
            val arr = JSONArray(prefs.getString(KEY_ROWS, "[]"))
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Row(o.optString("g"), o.optString("t"), o.optString("s"))
            }
        }.getOrDefault(emptyList())
        return Edition(key, key.endsWith("-am"), lede, rows)
    }

    fun dismiss(prefs: SharedPreferences) {
        prefs.getString(KEY_EDITION, null)?.let { prefs.edit().putString(KEY_DISMISSED, it).apply() }
    }

    /** True when we're in a window and this edition hasn't been generated yet. */
    fun due(prefs: SharedPreferences): Boolean {
        val key = windowKey() ?: return false
        return prefs.getString(KEY_EDITION, null) != key && !generating
    }

    /**
     * Generate the pending edition. [notifications] = (sender, preview, lastUpdatedMs) of hub
     * messages; [weatherLine] like "62° drizzle" or blank. Suspends for the model call; call
     * from a background scope, then re-render the home on true.
     */
    suspend fun generate(
        context: Context,
        prefs: SharedPreferences,
        notifications: List<Triple<String, String, Long>>,
        events: List<CalendarEvent>,
        weatherLine: String,
        force: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        val key = windowKey() ?: (if (force) forcedKey() else return@withContext false)
        if ((!force && prefs.getString(KEY_EDITION, null) == key) || generating) return@withContext false
        generating = true
        try {
            val morning = key.endsWith("-am")
            val now = System.currentTimeMillis()
            // Morning: everything since 21:00 yesterday. Evening: today from 05:00.
            val cutoff = Calendar.getInstance().apply {
                if (morning) { add(Calendar.DAY_OF_YEAR, -1); set(Calendar.HOUR_OF_DAY, 21) }
                else set(Calendar.HOUR_OF_DAY, 5)
                set(Calendar.MINUTE, 0)
            }.timeInMillis
            val fresh = notifications.filter { it.third in cutoff..now }.take(24)
            val todayEnd = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59) }.timeInMillis
            val todays = events.filter { it.beginMs in now..todayEnd }.take(3)
            val tomorrows = events.filter { it.beginMs > todayEnd }.take(2)

            val prompt = buildString {
                append("You write a phone home-screen brief. Reply with EXACTLY two lines, no labels, no markdown.\n")
                append("Line 1: one warm sentence (max 20 words) — ")
                append(if (morning) "the single most useful thing about this morning.\n"
                    else "close the day and mention tomorrow's first commitment if any.\n")
                append("Line 2: one sentence (max 16 words) summarizing the notifications — what matters, what can be ignored.\n")
                if (weatherLine.isNotBlank()) append("Weather: $weatherLine\n")
                if (todays.isNotEmpty()) append("Today's events: ${todays.joinToString("; ") { "${it.title} ${it.timeLabel}" }}\n")
                if (tomorrows.isNotEmpty()) append("Tomorrow: ${tomorrows.joinToString("; ") { "${it.title} ${it.timeLabel}" }}\n")
                append(if (fresh.isEmpty()) "Notifications: none\n"
                    else "Notifications (${fresh.size}): ${fresh.joinToString("; ") { "${it.first}: ${it.second.take(50)}" }.take(1200)}\n")
            }
            val out = GeminiClient.generate(
                GeminiClient.apiKey(prefs), GeminiClient.model(prefs), prompt,
                maxTokens = 90, temperature = 0.3,
            )
            val lines = out?.lines()?.map { it.trim().trim('"') }?.filter { it.isNotBlank() }.orEmpty()
            val lede = lines.firstOrNull()
                ?: if (morning) "Good morning — ${fresh.size} notifications overnight" +
                    (todays.firstOrNull()?.let { ", first up ${it.title} ${it.timeLabel}" } ?: ".")
                else "Day's done — ${fresh.size} notifications today" +
                    (tomorrows.firstOrNull()?.let { ", tomorrow ${it.title} ${it.timeLabel}" } ?: ".")
            val summary = lines.getOrNull(1)

            val rows = buildList {
                summary?.let { add(Row("◎", if (morning) "Overnight" else "Today", it)) }
                (if (morning) todays.firstOrNull() else tomorrows.firstOrNull())?.let {
                    add(Row("◷", it.title, "${if (morning) "" else "tomorrow · "}${it.timeLabel}".trim()))
                }
                if (fresh.isNotEmpty()) {
                    val top = fresh.groupBy { it.first }.maxByOrNull { it.value.size }
                    top?.let { add(Row("✉", "Most active", "${it.key} · ${it.value.size} message${if (it.value.size == 1) "" else "s"}")) }
                }
            }.take(3)

            prefs.edit()
                .putBoolean(KEY_FORCED, force)
                .remove(KEY_DISMISSED)
                .putString(KEY_EDITION, key)
                .putString(KEY_LEDE, lede.take(140))
                .putString(KEY_ROWS, JSONArray(rows.map {
                    JSONObject().put("g", it.glyph).put("t", it.title).put("s", it.sub)
                }).toString())
                .apply()
            true
        } finally {
            generating = false
        }
    }
}
