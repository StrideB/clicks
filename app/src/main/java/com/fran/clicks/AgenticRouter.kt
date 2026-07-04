package com.fran.clicks

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore

/**
 * The agentic brain: turn a line of typed text into an intent to execute. This is what makes the
 * space bar powerful — you type "play drake" or "nearest best buy", hold space, and Clicks routes
 * it to the right thing (your default music player, maps, a timer, a location drop, a web search).
 *
 * On-device and deterministic: plain verb/pattern rules cover the common commands with no network
 * and no surprises. [SHARE_LOCATION] is handled by the caller (it inserts text into the field);
 * every other kind carries a ready-to-fire [Intent]. Callers show [label] as a preview and only
 * [execute] on an explicit tap — nothing launches without confirmation.
 */
object AgenticRouter {

    enum class Kind { MUSIC, MAPS_SEARCH, MAPS_NAV, TIMER, WEB, SHARE_LOCATION }

    data class Command(val kind: Kind, val label: String, val arg: String, val intent: Intent?)

    private val LOCATION_PHRASES = setOf(
        "my location", "send location", "share location", "send my location",
        "share my location", "where i am", "location", "drop a pin"
    )

    /** Classify [raw] into a command, or null when nothing confidently matches. */
    fun classify(raw: String): Command? {
        val q = raw.trim()
        if (q.isEmpty()) return Command(Kind.SHARE_LOCATION, "📍 Share your location", "", null)
        val lower = q.lowercase()

        strip(q, lower, "play ", "listen to ", "put on ")?.let {
            return Command(Kind.MUSIC, "▶  Play $it", it, musicIntent(it))
        }
        strip(q, lower, "navigate to ", "directions to ", "take me to ", "drive to ", "navigate ")?.let {
            return Command(Kind.MAPS_NAV, "🧭  Navigate to $it", it, navIntent(it))
        }
        val place = strip(q, lower, "nearest ", "closest ", "find ", "where is ", "where's ", "map ", "maps ")
            ?: if (lower.endsWith(" near me")) q.dropLast(8).trim() else null
        if (!place.isNullOrBlank()) {
            return Command(Kind.MAPS_SEARCH, "📍  Find $place", place, mapsSearchIntent(place))
        }
        strip(q, lower, "timer ", "set timer ", "set a timer ")?.let { spec ->
            timerIntent(spec)?.let { return Command(Kind.TIMER, "⏱  Timer $spec", spec, it) }
        }
        if (lower in LOCATION_PHRASES) return Command(Kind.SHARE_LOCATION, "📍 Share your location", "", null)
        strip(q, lower, "search ", "google ", "look up ")?.let {
            return Command(Kind.WEB, "🔍  Search $it", it, webIntent(it))
        }
        return null
    }

    /** Fire a launch command. Returns a status string to flash, or null if it couldn't run. */
    fun execute(context: Context, cmd: Command): String? {
        val intent = cmd.intent ?: return null
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            cmd.label
        } catch (_: ActivityNotFoundException) {
            // No app handled it (e.g. no music player for PLAY_FROM_SEARCH) — fall back to the web.
            runCatching {
                context.startActivity(webIntent(cmd.arg.ifBlank { cmd.label })
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.getOrNull()?.let { "🔍  Searching ${cmd.arg}" }
        } catch (_: Exception) {
            null
        }
    }

    private fun strip(q: String, lower: String, vararg prefixes: String): String? {
        for (p in prefixes) if (lower.startsWith(p)) {
            val rest = q.substring(p.length).trim()
            if (rest.isNotBlank()) return rest
        }
        return null
    }

    private fun musicIntent(q: String) = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        putExtra(SearchManager.QUERY, q)
    }

    private fun mapsSearchIntent(q: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q)))

    private fun navIntent(q: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(q)))

    private fun webIntent(q: String) =
        Intent(Intent.ACTION_WEB_SEARCH).apply { putExtra(SearchManager.QUERY, q) }

    private fun timerIntent(spec: String): Intent? {
        val n = Regex("(\\d+)").find(spec)?.groupValues?.get(1)?.toIntOrNull() ?: return null
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
}
