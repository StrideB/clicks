package com.fran.teclas

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import com.fran.teclas.db.SkillDatabase
import com.fran.teclas.db.SkillEntity

/**
 * The agentic brain. Turns a typed line into an action to run: "play drake", "nearest best buy",
 * "timer 5 min", "yt lofi", "translate hola"… hold the go/enter key and Teclas routes it.
 *
 * Skills live in a Room database (see [SkillEntity]) so the catalog grows over time — built-ins are
 * seeded on first run and users can add their own from the Skills screen, all without code changes.
 * A process-wide cache is filled off the main thread so [classify] stays synchronous for the tap.
 *
 * Matching is deterministic and on-device. When nothing matches, the caller can ask Gemini to pick a
 * skill from [catalogNames] and pass the choice back to [commandForSkill] — the model only ranks
 * among real skills, so it can't invent an action.
 */
object AgenticRouter {

    enum class ActionType { MUSIC, MAPS, NAV, TIMER, WEB_SEARCH, LOCATION, URI, EMAIL, WEATHER, ALARM, CALENDAR, CALL }

    data class Command(
        val skillId: Long,
        val label: String,
        val arg: String,
        val intent: Intent?,        // null when [insertsLocation], [insertText], or [fetchWeather]
        val insertsLocation: Boolean,
        val insertText: String? = null,  // computed result to drop straight into the field (SmartCompute)
        val fetchWeather: Boolean = false // async: fetch weather for [arg] and insert (WeatherApi)
    )

    private data class Skill(
        val id: Long, val name: String, val emoji: String, val actionType: String,
        val uriTemplate: String, val triggers: List<String>, val labelTemplate: String
    )

    @Volatile private var skills: List<Skill> = emptyList()
    @Volatile private var loaded = false

    /** Load the cache once (seeding built-ins if the table is empty). Cheap no-op after the first. */
    fun ensureLoaded(context: Context) { if (!loaded) reload(context) }

    /** Rebuild the cache from the DB, off the main thread. Call after editing skills. */
    fun reload(context: Context) {
        Thread {
            runCatching {
                ensureSeeded(context)
                skills = SkillDatabase.get(context).skillDao().getEnabled().map { it.toSkill() }
                loaded = true
            }
        }.start()
    }

    /** Insert any built-in skills not already present (by name). Synchronous — call off main. */
    fun ensureSeeded(context: Context) {
        runCatching {
            val dao = SkillDatabase.get(context).skillDao()
            val existing = dao.getAll().map { it.name }.toHashSet()
            val missing = BUILTINS.filter { it.name !in existing }
            if (missing.isNotEmpty()) dao.insertAll(missing)
        }
    }

    private fun SkillEntity.toSkill() = Skill(
        id, name, emoji, actionType, uriTemplate,
        triggers.split(",").map { it.trim() }.filter { it.isNotEmpty() }, labelTemplate
    )

    /** Classify [raw] against the enabled skills, or null when nothing confidently matches. */
    fun classify(raw: String): Command? {
        val q = raw.trim()
        val list = skills
        if (q.isEmpty()) return list.firstOrNull { it.actionType == "LOCATION" }?.let { locationCommand(it) }
        val lower = q.lowercase()
        for (s in list) for (t in s.triggers) {
            val arg = matchTrigger(q, lower, t) ?: continue
            return buildCommand(s, arg)
        }
        // On-device compute: a bare calculation or unit conversion becomes an inline insert.
        SmartCompute.evaluate(q)?.let { (label, text) ->
            return Command(0, label, text, null, false, insertText = text)
        }
        return null
    }

    /** Names of enabled skills, for the Gemini fallback prompt. */
    fun catalogNames(): List<String> = skills.map { it.name }

    /** A tappable go-button starter: a labelled skill and the text to seed the field with. */
    data class Starter(val label: String, val insert: String)

    /**
     * Starters to show when the held go button has nothing to run yet — discoverability without a
     * manual. Tapping one seeds the field with the skill's trigger (e.g. "play ") so the user just
     * finishes typing. Drawn from the top of the enabled catalog (sort order = most useful first).
     */
    fun starters(limit: Int = 4): List<Starter> = skills.take(limit).map { s ->
        val prefix = s.triggers.firstOrNull { it.endsWith(" ") }
            ?: s.triggers.firstOrNull()?.let { "$it " } ?: ""
        Starter("${s.emoji} ${s.name}", prefix)
    }

    /** Build a command from a Gemini-chosen skill name + extracted argument. */
    fun commandForSkill(name: String, arg: String): Command? {
        val s = skills.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: return null
        return buildCommand(s, arg.trim())
    }

    /** Fire a launch command; returns a status to flash, or null on failure. Records the use. */
    fun execute(context: Context, cmd: Command): String? {
        val intent = cmd.intent ?: return null
        recordUse(context, cmd.skillId)
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            cmd.label
        } catch (_: ActivityNotFoundException) {
            runCatching {
                context.startActivity(webIntent(cmd.arg.ifBlank { cmd.label })
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.getOrNull()?.let { "🔍  Searching ${cmd.arg}" }
        } catch (_: Exception) { null }
    }

    fun recordUse(context: Context, id: Long) {
        if (id <= 0) return
        Thread { runCatching { SkillDatabase.get(context).skillDao().recordUse(id, System.currentTimeMillis()) } }.start()
    }

    // --- matching + intent building ---------------------------------------------------------------

    private fun matchTrigger(q: String, lower: String, trigger: String): String? {
        val t = trigger.lowercase()
        return when {
            t.startsWith("*") -> {                       // suffix: "* near me"
                val suf = t.substring(1)
                if (lower.endsWith(suf)) q.substring(0, q.length - suf.length).trim().ifBlank { null } else null
            }
            t.endsWith(" ") -> {                          // prefix + arg: "play "
                if (lower.startsWith(t)) q.substring(t.length).trim().ifBlank { null } else null
            }
            else -> if (lower == t || lower.startsWith("$t ")) "" else null   // exact phrase, no arg
        }
    }

    private fun buildCommand(s: Skill, arg: String): Command {
        if (s.actionType == "LOCATION") return locationCommand(s)
        val label = s.labelTemplate.replace("{q}", arg).trim()
        if (s.actionType == "WEATHER") return Command(s.id, label, arg, null, false, fetchWeather = true)
        val intent = when (s.actionType) {
            "MUSIC" -> musicIntent(arg)
            "TIMER" -> timerIntent(arg)
            "EMAIL" -> emailIntent(arg)
            "WEB_SEARCH" -> webIntent(arg)
            "ALARM" -> alarmIntent(arg)
            "CALENDAR" -> calendarIntent(arg)
            "CALL" -> callIntent(arg)
            else -> uriIntent(s.uriTemplate, arg)        // URI, MAPS, NAV all use a template
        }
        return Command(s.id, label, arg, intent, false)
    }

    private fun locationCommand(s: Skill) =
        Command(s.id, s.labelTemplate.replace("{q}", "").trim().ifBlank { "📍 Share your location" }, "", null, true)

    private fun musicIntent(q: String) = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        putExtra(SearchManager.QUERY, q)
    }

    private fun webIntent(q: String) =
        Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra(SearchManager.QUERY, q) }

    private fun emailIntent(to: String) = Intent().apply {
        setClassName("com.fran.teclas", "com.fran.teclas.EmailComposeActivity")
        putExtra("to", to)
    }

    private fun uriIntent(template: String, arg: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(template.replace("{q}", Uri.encode(arg))))

    private fun timerIntent(spec: String): Intent {
        val n = Regex("(\\d+)").find(spec)?.groupValues?.get(1)?.toIntOrNull() ?: 5
        val seconds = when {
            spec.contains("hour") -> n * 3600
            spec.contains("sec") -> n
            else -> n * 60
        }
        return Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            putExtra(AlarmClock.EXTRA_MESSAGE, spec)
        }
    }

    // "7", "7:30", "6am", "6:30 pm", "wake me at 7" → the clock app's set-alarm screen, prefilled.
    // Falls back to the deep-link (no time) so the user just sets it, rather than failing outright.
    private fun alarmIntent(spec: String): Intent {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        parseTime(spec)?.let { (h, m) ->
            intent.putExtra(AlarmClock.EXTRA_HOUR, h)
            intent.putExtra(AlarmClock.EXTRA_MINUTES, m)
        }
        return intent
    }

    // Open the calendar's new-event composer with the title prefilled. No calendar permission needed —
    // the user reviews and saves in their own calendar app.
    private fun calendarIntent(title: String) = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.Events.TITLE, title.trim())
    }

    // Dial a number (ACTION_DIAL prefills the dialer, no CALL_PHONE permission). Digits only.
    private fun callIntent(spec: String): Intent {
        val number = spec.filter { it.isDigit() || it == '+' || it == '#' || it == '*' }
        return Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
    }

    /** Parse a clock time like "7", "7:30", "6am", "6:30 pm" into 24-hour (hour, minute). */
    private fun parseTime(spec: String): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE).find(spec) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        val mer = m.groupValues[3].lowercase()
        when (mer) {
            "pm" -> if (hour < 12) hour += 12
            "am" -> if (hour == 12) hour = 0
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    // --- seed catalog -----------------------------------------------------------------------------

    private val BUILTINS = listOf(
        SkillEntity(name = "Play music", emoji = "▶", actionType = "MUSIC",
            triggers = "play ,listen to ,put on ", labelTemplate = "▶  Play {q}", builtin = true, sortOrder = 0),
        SkillEntity(name = "Navigate", emoji = "🧭", actionType = "NAV",
            uriTemplate = "google.navigation:q={q}", triggers = "navigate to ,directions to ,drive to ,navigate ",
            labelTemplate = "🧭  Navigate to {q}", builtin = true, sortOrder = 1),
        SkillEntity(name = "Find place", emoji = "📍", actionType = "MAPS",
            uriTemplate = "geo:0,0?q={q}", triggers = "nearest ,closest ,find ,map ,maps ,* near me",
            labelTemplate = "📍  Find {q}", builtin = true, sortOrder = 2),
        SkillEntity(name = "Timer", emoji = "⏱", actionType = "TIMER",
            triggers = "timer ,set timer ,set a timer ", labelTemplate = "⏱  Timer {q}", builtin = true, sortOrder = 3),
        SkillEntity(name = "Share location", emoji = "📍", actionType = "LOCATION",
            triggers = "share location,send location,my location,share my location,drop a pin,send my location",
            labelTemplate = "📍 Share your location", builtin = true, sortOrder = 4),
        SkillEntity(name = "Web search", emoji = "🔍", actionType = "WEB_SEARCH",
            triggers = "search ,google ,look up ", labelTemplate = "🔍  Search {q}", builtin = true, sortOrder = 5),
        SkillEntity(name = "YouTube", emoji = "📺", actionType = "URI",
            uriTemplate = "https://www.youtube.com/results?search_query={q}", triggers = "youtube ,yt ",
            labelTemplate = "📺  YouTube {q}", builtin = true, sortOrder = 6),
        SkillEntity(name = "Wikipedia", emoji = "📖", actionType = "URI",
            uriTemplate = "https://en.wikipedia.org/wiki/Special:Search?search={q}", triggers = "wiki ,wikipedia ",
            labelTemplate = "📖  Wikipedia {q}", builtin = true, sortOrder = 7),
        SkillEntity(name = "Translate", emoji = "🌐", actionType = "URI",
            uriTemplate = "https://translate.google.com/?sl=auto&tl=en&op=translate&text={q}", triggers = "translate ",
            labelTemplate = "🌐  Translate {q}", builtin = true, sortOrder = 8),
        SkillEntity(name = "Send email", emoji = "✉️", actionType = "EMAIL",
            triggers = "email ,send email,compose email,mail ", labelTemplate = "✉️  Email {q}", builtin = true, sortOrder = 9),
        SkillEntity(name = "Weather", emoji = "⛅", actionType = "WEATHER",
            triggers = "weather ,forecast ,weather in ,weather for ", labelTemplate = "⛅  Weather {q}", builtin = true, sortOrder = 10),
        SkillEntity(name = "Set alarm", emoji = "⏰", actionType = "ALARM",
            triggers = "alarm ,set alarm ,set an alarm ,wake me at ,wake me up at ,alarm at ",
            labelTemplate = "⏰  Alarm {q}", builtin = true, sortOrder = 11),
        SkillEntity(name = "Add event", emoji = "📅", actionType = "CALENDAR",
            triggers = "add event ,new event ,calendar ,schedule ,remind me to ,event ",
            labelTemplate = "📅  Add “{q}”", builtin = true, sortOrder = 12),
        SkillEntity(name = "Call", emoji = "📞", actionType = "CALL",
            triggers = "call ,dial ,phone ", labelTemplate = "📞  Call {q}", builtin = true, sortOrder = 13)
    )
}
