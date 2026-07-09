package com.fran.teclas

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri

/** A result surfaced in a keyboard's result HUD: a titled body, an optional Insert, and follow-ups. */
data class AgenticResult(
    val kicker: String,
    val body: String,
    val insert: String?,
    val followUps: List<AgenticFollowUp> = emptyList()
)

data class AgenticFollowUp(val label: String, val run: () -> Unit)

/**
 * A share preview card: the "is this the thing?" beat between choosing a chirp and dropping it into
 * the conversation. Tap = insert [insertText] into the field (never auto-send); [followUp] appears
 * after the drop for the optional app-leaving action ("Directions for me →").
 */
data class ShareCard(
    val kicker: String,        // "NOW PLAYING · Spotify" / "MEETING SPOT" / "MY LOCATION"
    val title: String,
    val subtitle: String,
    val insertText: String,
    val art: android.graphics.Bitmap?,
    val followUp: AgenticFollowUp? = null
)

/**
 * The rendering + context surface a keyboard hands to [AgenticEngine]. Both [TeclasImeService] and the
 * launcher keyboard (MainActivity) implement this, so every agentic skill executes from ONE place and
 * the two keyboards can't drift. The engine owns the *logic*; the host owns *how it's shown* (the IME
 * paints a panel and commits via InputConnection; the launcher paints its strip and edits its own field).
 */
interface AgenticHost {
    val hostContext: Context
    fun prefs(): SharedPreferences
    fun clipboardText(): String
    fun isPro(): Boolean
    fun isGoogleConnected(): Boolean
    fun post(action: () -> Unit)               // marshal onto the main thread
    fun clearField(consumed: String)           // remove the typed trigger text
    fun showStatus(msg: String)                // in-flight working banner
    fun flashStatus(msg: String, ms: Long)     // transient message
    fun showResult(result: AgenticResult)      // the result HUD
    fun insertText(text: String)               // drop text into the field
    fun runAttach()                            // IME: the file picker; launcher: a "not here" note
    fun showShareCard(card: ShareCard)         // IME: full-bleed card over the keyboard; launcher: strip HUD
}

/**
 * The shared agentic brain for clip-skills and rich results. Routing of *typed* commands still lives in
 * [AgenticRouter]; this owns the bare-trigger skills (mood, translate, xreply, stock, odds, meet, notion,
 * attach) and the weather result-loop — the parts that were previously IME-only.
 */
object AgenticEngine {

    /** Detect and run a bare clip-skill trigger in [raw]. Returns true when it handled the input. */
    fun tryClipSkill(host: AgenticHost, raw: String): Boolean {
        val t = raw.trim().lowercase()
        val skill = when {
            t == "mood" || t == "emotion" || t == "vibe" || t == "vibe check" || t == "read mood" -> "mood"
            t == "translate" || t == "translate this" || t == "translate clipboard" || t == "tl" -> "translate"
            t == "xreply" || t.startsWith("xreply ") || t.startsWith("x reply") || t.startsWith("reply x") -> "xreply"
            t.startsWith("stock ") || t.startsWith("ticker ") ||
                (t.startsWith("$") && t.length in 2..6 && t.drop(1).all { it.isLetterOrDigit() }) -> "stock"
            t == "worldcup" || t == "world cup" || t == "wc" || t == "wc odds" || t == "world cup odds" || t == "odds" -> "odds"
            t == "meet" || t == "google meet" || t == "new meet" || t == "gmeet" -> "meet"
            t.startsWith("notion ") || t == "notion" -> "notion"
            t == "attach" || t == "file" || t == "files" || t == "photo" || t == "photos" ||
                t == "attach file" || t == "attach photo" || t == "add file" || t == "send file" -> "attach"
            t == "song" || t == "share song" || t == "np" || t == "now playing" || t.startsWith("song ") -> "song"
            t == "place" || t == "share place" || t == "meet here" ||
                t.startsWith("place ") || t.startsWith("meet at ") -> "place"
            else -> null
        } ?: return false

        host.clearField(raw)
        when (skill) {
            "mood" -> emotion(host)
            "translate" -> translate(host)
            "xreply" -> xReply(host, t.removePrefix("xreply").removePrefix("x reply").removePrefix("reply x").trim())
            "stock" -> stock(host, when {
                t.startsWith("stock ") -> t.removePrefix("stock ")
                t.startsWith("ticker ") -> t.removePrefix("ticker ")
                else -> t.removePrefix("$")
            }.trim())
            "odds" -> odds(host)
            "meet" -> meet(host)
            "notion" -> notion(host, t.removePrefix("notion").trim())
            "attach" -> host.runAttach()
            "song" -> shareSong(host, t.removePrefix("song").trim())
            "place" -> sharePlace(host, t.removePrefix("place").removePrefix("meet at").trim())
        }
        return true
    }

    // --- share cards (song / place) ------------------------------------------------------------------
    // The go button drops things INTO the conversation; it never ejects the user. Both flows resolve a
    // shareable artifact, preview it as a card, and insert on tap. App-leaving actions (directions) are
    // demoted to an optional follow-up after the drop.

    /** Blank [query] → share what's playing right now; otherwise resolve the typed song name. */
    fun shareSong(host: AgenticHost, query: String = "") {
        host.showStatus(if (query.isBlank()) "🎵 Reading now playing…" else "🎵 Finding “$query”…")
        Thread {
            val song = ShareContent.resolveSong(host.hostContext, query)
            host.post {
                when {
                    song != null -> host.showShareCard(ShareCard(
                        kicker = song.appLabel?.let { "NOW PLAYING · ${it.uppercase()}" } ?: "SHARE SONG",
                        title = song.title,
                        subtitle = song.artist,
                        insertText = song.insertText,
                        art = song.art
                    ))
                    query.isBlank() -> host.flashStatus("Nothing playing — type a song name, then hold go", 2800)
                    else -> host.flashStatus("Couldn’t find “$query”", 2200)
                }
            }
        }.start()
    }

    /** Blank [query] → the user's current spot; otherwise geocode the typed place ("meet at blue bottle"). */
    fun sharePlace(host: AgenticHost, query: String = "") {
        if (query.isBlank() && !AgenticLocation.hasPermission(host.hostContext)) {
            host.flashStatus("📍 Enable location in Teclas", 2600); return
        }
        host.showStatus(if (query.isBlank()) "📍 Locating…" else "📍 Finding “$query”…")
        Thread {
            val place = if (query.isBlank()) ShareContent.myPlace(host.hostContext)
                else ShareContent.findPlace(host.hostContext, query)
            host.post {
                if (place != null) {
                    host.showShareCard(ShareCard(
                        kicker = if (place.isMyLocation) "MY LOCATION" else "MEETING SPOT",
                        title = place.name.ifBlank { place.address.substringBefore(',').ifBlank { "Here" } },
                        subtitle = if (place.name.isBlank()) "" else place.address,
                        insertText = place.insertText,
                        art = null,
                        followUp = AgenticFollowUp("🧭 Directions") { openDirections(host, place) }
                    ))
                } else if (query.isBlank()) host.flashStatus("📍 Location unavailable", 2200)
                else host.flashStatus("Couldn’t find “$query”", 2200)
            }
        }.start()
    }

    private fun openDirections(host: AgenticHost, place: ShareContent.PlaceCard) {
        runCatching {
            host.hostContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${place.lat},${place.lng}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            runCatching {
                host.hostContext.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(place.mapsUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    // --- AI (clipboard) skills ---------------------------------------------------------------------

    private fun aiReady(host: AgenticHost): Boolean {
        if (!host.isPro()) { host.flashStatus("✨ This is a Pro feature", 2000); return false }
        if (!GeminiClient.configured(host.prefs())) { host.flashStatus("Add a Gemini key in Teclas settings", 2400); return false }
        return true
    }

    private fun clipboardSkill(host: AgenticHost, need: String, status: String, kicker: String, instruction: String, insertable: Boolean) {
        val text = host.clipboardText()
        if (text.isBlank()) { host.flashStatus(need, 2600); return }
        if (!aiReady(host)) return
        host.showStatus(status)
        val key = GeminiClient.apiKey(host.prefs()); val model = GeminiClient.model(host.prefs())
        Thread {
            val out = GeminiClient.fetchTransform(key, model, text, instruction)
            host.post {
                if (!out.isNullOrBlank()) host.showResult(AgenticResult(kicker, out, if (insertable) out else null))
                else host.flashStatus("Couldn’t do that — try again", 1800)
            }
        }.start()
    }

    private fun emotion(host: AgenticHost) = clipboardSkill(
        host, "Copy a message first, then hold go", "🧠 Reading the mood…", "EMOTION",
        "Analyze the mood behind this message, then suggest how to respond. Reply in exactly two short " +
            "lines: 'Mood: <one line>' and 'Reply: <one line>'. Be concise.", insertable = false)

    private fun translate(host: AgenticHost) = clipboardSkill(
        host, "Copy text to translate, then hold go", "🌐 Translating…", "TRANSLATION",
        "Translate this into natural, everyday English. Reply with ONLY the translation.", insertable = true)

    private fun xReply(host: AgenticHost, tone: String) {
        val t = tone.ifBlank { "supportive" }
        clipboardSkill(
            host, "Copy the post text first, then hold go", "𝕏 Drafting reply…", "X REPLY · $t",
            "Write a short, context-aware reply to this social post in a $t tone. One or two sentences, " +
                "no hashtags unless natural. Reply with ONLY the reply text.", insertable = true)
    }

    // --- API skills (free key in settings) ---------------------------------------------------------

    private fun stock(host: AgenticHost, ticker: String) {
        val key = host.prefs().getString(StockApi.KEY_PREF, null)?.trim().orEmpty()
        if (key.isBlank()) { host.flashStatus("Add a Finnhub key in Teclas settings", 2600); return }
        val sym = ticker.ifBlank { host.clipboardText() }.trim()
        if (sym.isBlank()) { host.flashStatus("Type or copy a ticker first", 2400); return }
        host.showStatus("📈 Checking $sym…")
        Thread {
            val out = StockApi.lookup(sym, key)
            host.post {
                if (out != null) host.showResult(AgenticResult("STOCK", out, out))
                else host.flashStatus("Couldn’t find “$sym”", 2000)
            }
        }.start()
    }

    private fun odds(host: AgenticHost) {
        val key = host.prefs().getString(OddsApi.KEY_PREF, null)?.trim().orEmpty()
        if (key.isBlank()) { host.flashStatus("Add an odds API key in Teclas settings", 2600); return }
        host.showStatus("🏆 Getting odds…")
        Thread {
            val out = OddsApi.worldCup(key)
            host.post {
                if (out != null) host.showResult(AgenticResult("WORLD CUP", out, null))
                else host.flashStatus("Couldn’t get odds", 2000)
            }
        }.start()
    }

    // --- OAuth skills ------------------------------------------------------------------------------

    private fun meet(host: AgenticHost) {
        if (!host.isGoogleConnected()) { host.flashStatus("Connect Google in the Teclas app first", 2800); return }
        host.showStatus("📹 Creating Meet link…")
        Thread {
            val link = MeetApi.createMeeting(host.hostContext)
            host.post {
                if (link != null) host.insertText(link)
                else host.flashStatus("Couldn’t create a Meet link — reconnect Google (Calendar)", 2800)
            }
        }.start()
    }

    private fun notion(host: AgenticHost, query: String) {
        val token = host.prefs().getString(NotionApi.KEY_PREF, null)?.trim().orEmpty()
        if (token.isBlank()) { host.flashStatus("Add a Notion token in Teclas settings", 2800); return }
        val q = query.ifBlank { host.clipboardText() }.trim()
        if (q.isBlank()) { host.flashStatus("Type what to find, e.g. “notion roadmap”", 2600); return }
        host.showStatus("🔎 Finding in Notion…")
        Thread {
            val found = NotionApi.summon(q, token)
            host.post {
                if (found != null) {
                    val (title, url) = found
                    val body = if (title.isNotBlank()) "$title\n$url" else url
                    host.showResult(AgenticResult("NOTION", body, url))
                } else host.flashStatus("No Notion page for “$q”", 2200)
            }
        }.start()
    }

    // --- weather result-loop -----------------------------------------------------------------------

    /** Fetch weather for [query] and surface it with Insert + a "Full forecast" follow-up. */
    fun runWeather(host: AgenticHost, query: String) {
        host.showStatus("⛅ Checking weather…")
        Thread {
            val text = WeatherApi.lookup(query)
            host.post {
                if (text != null) {
                    host.showResult(AgenticResult("WEATHER", text, text, listOf(
                        AgenticFollowUp("Full forecast") { openWeatherSearch(host, query.ifBlank { text }) }
                    )))
                } else host.flashStatus("⛅ Couldn’t get weather for “$query”", 2200)
            }
        }.start()
    }

    private fun openWeatherSearch(host: AgenticHost, query: String) {
        val url = "https://www.google.com/search?q=" + Uri.encode("weather $query")
        runCatching {
            host.hostContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
