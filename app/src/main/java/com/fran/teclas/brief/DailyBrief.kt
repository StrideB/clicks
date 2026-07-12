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
 * MORNING (5:00–11:59) summarizes what arrived overnight + surfaces anything asking for a reply;
 * EVENING (20:00–23:59) closes the day with a "what you did today" checklist. Outside the
 * windows — or after the user dismisses an edition — the widget simply isn't there.
 *
 * The model returns structured JSON (grammar-constrained on the local tier, so it can't be
 * malformed): a lede, up to three rows, and — evening only — a todo list. A row can carry a
 * notification key: those are the actionable asks ("your boss is asking about the Q1 report"),
 * and tapping them opens the exact thread. A deterministic fallback keeps the widget alive when
 * no model can serve. Generated editions persist so re-renders never re-run the model.
 */
object DailyBrief {

    private const val KEY_EDITION = "brief_edition_key"
    private const val KEY_LEDE = "brief_edition_lede"
    private const val KEY_ROWS = "brief_edition_rows"
    private const val KEY_TODOS = "brief_edition_todos"
    private const val KEY_DISMISSED = "brief_dismissed_key"
    private const val KEY_FORCED = "brief_edition_forced"
    @Volatile private var generating = false

    /** A brief row. [pkg]/[key] non-empty ⇒ actionable: tapping opens that notification's thread. */
    data class Row(val glyph: String, val title: String, val sub: String, val pkg: String = "", val key: String = "") {
        val actionable get() = key.isNotEmpty() || pkg.isNotEmpty()
    }
    data class Todo(val text: String, val done: Boolean)
    data class Edition(val key: String, val morning: Boolean, val lede: String, val rows: List<Row>, val todos: List<Todo>)

    /** One live notification, with the ids needed to reopen its thread. */
    data class Notif(val sender: String, val preview: String, val whenMs: Long, val pkg: String, val key: String)

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

    /** Dev/preview fallback key. [morning] forces the edition; null = am before 17:00, else pm. */
    private fun forcedKey(morning: Boolean?, now: Calendar = Calendar.getInstance()): String {
        val day = "%04d%02d%02d".format(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))
        val am = morning ?: (now.get(Calendar.HOUR_OF_DAY) < 17)
        return "$day-${if (am) "am" else "pm"}"
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
                Row(o.optString("g"), o.optString("t"), o.optString("s"), o.optString("p"), o.optString("k"))
            }
        }.getOrDefault(emptyList())
        val todos = runCatching {
            val arr = JSONArray(prefs.getString(KEY_TODOS, "[]"))
            List(arr.length()) { i -> arr.getJSONObject(i).let { Todo(it.optString("x"), it.optBoolean("d")) } }
        }.getOrDefault(emptyList())
        return Edition(key, key.endsWith("-am"), lede, rows, todos)
    }

    fun dismiss(prefs: SharedPreferences) {
        prefs.getString(KEY_EDITION, null)?.let { prefs.edit().putString(KEY_DISMISSED, it).apply() }
    }

    /** Flip a todo's checkbox and persist (rewrites the stored list). */
    fun toggleTodo(prefs: SharedPreferences, index: Int) {
        val arr = runCatching { JSONArray(prefs.getString(KEY_TODOS, "[]")) }.getOrNull() ?: return
        if (index !in 0 until arr.length()) return
        val o = arr.getJSONObject(index)
        o.put("d", !o.optBoolean("d"))
        prefs.edit().putString(KEY_TODOS, arr.toString()).apply()
    }

    /** True when we're in a window and this edition hasn't been generated yet. */
    fun due(prefs: SharedPreferences): Boolean {
        val key = windowKey() ?: return false
        return prefs.getString(KEY_EDITION, null) != key && !generating
    }

    suspend fun generate(
        context: Context,
        prefs: SharedPreferences,
        notifications: List<Notif>,
        events: List<CalendarEvent>,
        weatherLine: String,
        force: Boolean = false,
        forceMorning: Boolean? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val key = if (force) forcedKey(forceMorning) else windowKey() ?: return@withContext false
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
            val fresh = notifications.filter { it.whenMs in cutoff..now }.take(20)
            val todayEnd = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59) }.timeInMillis
            val todays = events.filter { it.beginMs in now..todayEnd }.take(3)
            val tomorrows = events.filter { it.beginMs > todayEnd }.take(2)

            val prompt = buildString {
                append("You write a phone home-screen brief. Reply ONLY as JSON, no markdown:\n")
                append("{\"lede\":\"one warm sentence, max 18 words\",")
                append("\"rows\":[{\"title\":\"short label\",\"sub\":\"detail\",\"ask\":<notification number this is about, or 0>}],")
                append("\"commitments\":[{\"who\":\"person name\",\"task\":\"what you were asked to do or agreed to do\",\"src\":<notification number>}],")
                append(if (morning) "\"todos\":[]}\n" else "\"todos\":[\"past-tense thing you handled today\"]}\n")
                append("Rules: at most 3 rows. If a notification is a QUESTION or REQUEST directed at the user ")
                append("(someone asking them to reply, decide, or do something), make it a row phrased like ")
                append("\"<Person> is asking about <topic>\" and set \"ask\" to that notification's number. ")
                append("For commitments: from real person-to-person conversations only, extract anything the user was asked to do or agreed to do that isn't finished, INCLUDING social invitations or plans someone proposed (e.g. \"want to get dinner tonight?\", \"coffee tomorrow?\") — phrase task like \"dinner tonight\" with who = the person, and set \"src\" to the notification number. Empty array if none. ")
                append(if (morning) "Leave todos empty.\n" else "Fill todos with up to 6 short past-tense items summarizing what today involved, based on the notifications and events.\n")
                if (weatherLine.isNotBlank()) append("Weather: $weatherLine\n")
                if (todays.isNotEmpty()) append("Today's events: ${todays.joinToString("; ") { "${it.title} ${it.timeLabel}" }}\n")
                if (tomorrows.isNotEmpty()) append("Tomorrow: ${tomorrows.joinToString("; ") { "${it.title} ${it.timeLabel}" }}\n")
                if (fresh.isEmpty()) append("Notifications: none\n")
                else {
                    append("Notifications (numbered):\n")
                    fresh.forEachIndexed { i, n -> append("${i + 1}. ${n.sender}: ${n.preview.take(70)}\n") }
                }
            }.take(2400)

            val out = GeminiClient.generate(
                GeminiClient.apiKey(prefs), GeminiClient.model(prefs), prompt,
                maxTokens = 240, temperature = 0.3, json = true,
            )
            val obj = out?.let { raw ->
                val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
                if (s >= 0 && e > s) runCatching { JSONObject(raw.substring(s, e + 1)) }.getOrNull() else null
            }

            val lede = obj?.optString("lede")?.trim()?.ifBlank { null }
                ?: if (morning) "Good morning — ${fresh.size} notification${plural(fresh.size)} overnight" +
                    (todays.firstOrNull()?.let { ", first up ${it.title} ${it.timeLabel}" } ?: ".")
                else "Day's done — ${fresh.size} notification${plural(fresh.size)} today" +
                    (tomorrows.firstOrNull()?.let { ", tomorrow ${it.title} ${it.timeLabel}" } ?: ".")

            val rows = buildList {
                obj?.optJSONArray("rows")?.let { arr ->
                    for (i in 0 until minOf(arr.length(), 3)) {
                        val r = arr.optJSONObject(i) ?: continue
                        val title = r.optString("title").trim().ifBlank { continue }
                        val askIdx = r.optInt("ask", 0)
                        val src = fresh.getOrNull(askIdx - 1)
                        add(Row(
                            glyph = if (src != null) "›" else "◦",
                            title = title,
                            sub = r.optString("sub").trim(),
                            pkg = src?.pkg.orEmpty(),
                            key = src?.key.orEmpty(),
                        ))
                    }
                }
                if (isEmpty()) {
                    (if (morning) todays.firstOrNull() else tomorrows.firstOrNull())?.let {
                        add(Row("◷", it.title, "${if (morning) "" else "tomorrow · "}${it.timeLabel}".trim()))
                    }
                    if (fresh.isNotEmpty()) {
                        val top = fresh.groupBy { it.sender }.maxByOrNull { it.value.size }
                        top?.let { add(Row("✉", "Most active", "${it.key} · ${it.value.size} message${plural(it.value.size)}")) }
                    }
                }
            }.take(3)

            // Harvest commitments in the same pass — no extra model call, no background work.
            obj?.optJSONArray("commitments")?.let { arr ->
                val found = buildList {
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        val task = c.optString("task").trim().ifBlank { continue }
                        val src = fresh.getOrNull(c.optInt("src", 0) - 1)
                        // Conversations only: skip anything not from a messaging app.
                        if (src != null && !CommitmentStore.isConversation(src.pkg, "")) continue
                        add(CommitmentStore.Commitment(
                            text = task, person = c.optString("who").trim(),
                            pkg = src?.pkg.orEmpty(), key = src?.key.orEmpty(), whenMs = now,
                        ))
                    }
                }
                if (found.isNotEmpty()) CommitmentStore.add(prefs, found)
            }

            val todos = if (morning) emptyList() else buildList {
                obj?.optJSONArray("todos")?.let { arr ->
                    for (i in 0 until minOf(arr.length(), 6)) {
                        arr.optString(i).trim().ifBlank { null }?.let { add(Todo(it, false)) }
                    }
                }
            }

            prefs.edit()
                .putBoolean(KEY_FORCED, force)
                .remove(KEY_DISMISSED)
                .putString(KEY_EDITION, key)
                .putString(KEY_LEDE, lede.take(140))
                .putString(KEY_ROWS, JSONArray(rows.map {
                    JSONObject().put("g", it.glyph).put("t", it.title).put("s", it.sub).put("p", it.pkg).put("k", it.key)
                }).toString())
                .putString(KEY_TODOS, JSONArray(todos.map { JSONObject().put("x", it.text).put("d", it.done) }).toString())
                .apply()
            true
        } finally {
            generating = false
        }
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
