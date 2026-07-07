package com.fran.clicks.brief

import android.content.SharedPreferences
import com.fran.clicks.GeminiClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns raw [Signal]s into a ranked, phrased [List] of [BriefItem]s.
 *
 * Gemini ONLY ranks and phrases. It selects [BriefItem.primaryActionLabel] from each signal's
 * existing action labels — it never invents actions, names, numbers, or facts. The app re-binds the
 * real [Signal] (and thus the real PendingIntents) by signalRef. On any Gemini failure or malformed
 * JSON we fall back to deterministic rule-based ranking, so Today always renders.
 */
class BriefGenerator(private val prefs: SharedPreferences) {

    fun canUseGemini(): Boolean = GeminiClient.configured(prefs)

    /** Instant, deterministic, fully-actionable ranking (signals bound). Never hits the network. */
    fun ruleOnly(signals: List<Signal>, now: Long): List<BriefItem> = ruleRank(signals, now)

    /** Gemini-phrased ranking (signals bound), or null on any failure. Blocking — off main thread. */
    fun geminiOnly(signals: List<Signal>, now: Long): List<BriefItem>? =
        runCatching { geminiRank(signals, now) }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** @return ranked items and which path produced them. Blocking — call off the main thread. */
    fun generate(signals: List<Signal>, now: Long): Pair<List<BriefItem>, Brief.Source> {
        if (signals.isEmpty()) return emptyList<BriefItem>() to Brief.Source.EMPTY
        if (canUseGemini()) {
            geminiOnly(signals, now)?.let { return it to Brief.Source.GEMINI }
        }
        return ruleRank(signals, now) to Brief.Source.RULES
    }

    // -------------------------------------------------------------------------------------- Gemini

    private fun geminiRank(signals: List<Signal>, now: Long): List<BriefItem> {
        val byId = signals.associateBy { it.id }
        val payload = JSONObject()
            .put("currentTimeMillis", now)
            .put("signals", JSONArray().apply { signals.forEach { put(serialize(it)) } })

        val prompt = SYSTEM_INSTRUCTION + "\n\nSIGNALS:\n" + payload.toString()
        val raw = GeminiClient.generate(
            apiKey = GeminiClient.apiKey(prefs),
            model = GeminiClient.model(prefs),
            prompt = prompt,
            maxTokens = 700,
            temperature = 0.2
        ) ?: return emptyList()

        val items = parseItems(raw)
        val out = ArrayList<BriefItem>(items.length())
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val ref = it.optString("signalRef").trim()
            val signal = byId[ref] ?: continue                 // reject invented refs
            val klass = it.optString("class", "action").trim().lowercase()
            if (klass != "action") continue
            val title = it.optString("title").trim().take(60).ifBlank { defaultTitle(signal) }
            val subtitle = it.optString("subtitle").trim().ifBlank { defaultSubtitle(signal) }
            val category = BriefCategory.from(it.optString("category")).takeIf { c -> c != BriefCategory.OTHER }
                ?: categoryOf(signal)
            // primaryActionLabel MUST be one the signal actually exposes; otherwise fall back.
            val requested = it.optString("primaryActionLabel").trim()
            val label = signal.actions.firstOrNull { a -> a.label.equals(requested, ignoreCase = true) }?.label
                ?: defaultActionLabel(signal)
            out += BriefItem(ref, title, subtitle, category, label, signal)
            if (out.size >= MAX_ITEMS) break
        }
        return out
    }

    private fun serialize(signal: Signal): JSONObject {
        val o = JSONObject()
            .put("id", signal.id)
            .put("category", categoryOf(signal).name.lowercase())
            .put("timestamp", signal.timestamp)
            .put("actions", JSONArray().apply { signal.actions.forEach { put(it.label) } })
        when (signal) {
            is NotificationSignal -> o
                .put("app", signal.appLabel)
                .put("from", signal.personName ?: signal.title)
                .put("title", signal.title)
                .put("text", signal.text.take(220))
                .put("taskDraft", signal.taskDraft.orEmpty())
                .put("contentHash", signal.contentHash)
            is CalendarSignal -> o
                .put("title", signal.title)
                .put("when", signal.timeLabel)
                .put("location", signal.location ?: "")
            is WeatherSignal -> o.put("text", signal.summary)
            is MediaSignal -> o.put("title", signal.title).put("artist", signal.artist)
        }
        return o
    }

    // -------------------------------------------------------------------------------- Rule-based

    /** Deterministic ranking: newest actionable notifications first, using local triage phrasing. */
    private fun ruleRank(signals: List<Signal>, now: Long): List<BriefItem> {
        return signals.sortedWith(compareBy({ priority(it, now) }, { proximityKey(it, now) }))
            .take(MAX_ITEMS)
            .map { s ->
                BriefItem(
                    signalRef = s.id,
                    title = defaultTitle(s),
                    subtitle = defaultSubtitle(s),
                    category = categoryOf(s),
                    primaryActionLabel = defaultActionLabel(s),
                    signal = s
                )
            }
    }

    private fun priority(s: Signal, now: Long): Int = when (s) {
        is CalendarSignal -> 0
        is NotificationSignal -> when (BriefCategory.from(s.category)) {
            BriefCategory.CALL -> 1
            BriefCategory.MESSAGE -> 2
            BriefCategory.EMAIL -> 3
            else -> 4
        }
        is MediaSignal -> 5
        is WeatherSignal -> 6
    }

    // Within a tier: soonest calendar event first; newest notification first.
    private fun proximityKey(s: Signal, now: Long): Long = when (s) {
        is CalendarSignal -> s.beginMillis
        else -> -s.timestamp
    }

    // ------------------------------------------------------------------------------------ Shared

    private fun categoryOf(s: Signal): BriefCategory = when (s) {
        is CalendarSignal -> BriefCategory.CALENDAR
        is WeatherSignal -> BriefCategory.WEATHER
        is MediaSignal -> BriefCategory.MUSIC
        is NotificationSignal -> BriefCategory.from(s.category).takeIf { it != BriefCategory.OTHER }
            ?: BriefCategory.MESSAGE
    }

    private fun defaultTitle(s: Signal): String = when (s) {
        is NotificationSignal -> (s.taskDraft ?: BriefClassifier.classify(
            NotificationRecord(
                key = s.id,
                packageName = s.packageName,
                appLabel = s.appLabel,
                title = s.title,
                text = s.text,
                contentHash = s.contentHash,
                category = s.category,
                personName = s.personName,
                whenMs = s.timestamp,
                contentIntent = s.contentIntent,
                actions = emptyList(),
                avatar = s.avatar
            )
        ).task ?: (s.personName ?: s.title).ifBlank { s.appLabel }).take(60)
        is CalendarSignal -> s.title.ifBlank { "Upcoming event" }.take(60)
        is WeatherSignal -> s.summary.take(60)
        is MediaSignal -> s.title.take(60)
    }

    private fun defaultSubtitle(s: Signal): String = when (s) {
        is NotificationSignal -> listOfNotNull((s.personName ?: s.title).takeIf { it.isNotBlank() }, s.appLabel)
            .distinct()
            .joinToString(" · ")
            .ifBlank { s.text.ifBlank { s.appLabel } }
        is CalendarSignal -> listOfNotNull(s.timeLabel.ifBlank { null }, s.location).joinToString(" · ")
        is WeatherSignal -> "Now"
        is MediaSignal -> s.artist
    }

    // Prefer a reply, then a "call back"/answer, then Open, then whatever exists first.
    private fun defaultActionLabel(s: Signal): String {
        val actions = s.actions
        actions.firstOrNull { it is Fire && it.isReply }?.let { return it.label }
        actions.firstOrNull { REPLYISH.any { r -> it.label.contains(r, ignoreCase = true) } }?.let { return it.label }
        actions.firstOrNull { it.label.equals("Open", ignoreCase = true) }?.let { return it.label }
        return actions.firstOrNull()?.label.orEmpty()
    }

    /** First balanced {...} block, tolerating fenced / prose-wrapped replies. */
    private fun extractJsonObject(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else "{}"
    }

    private fun extractJsonArray(text: String): String {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        return if (start in 0 until end) text.substring(start, end + 1) else "[]"
    }

    private fun parseItems(text: String): JSONArray {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) return JSONArray(extractJsonArray(trimmed))
        return runCatching {
            JSONObject(extractJsonObject(trimmed)).optJSONArray("items") ?: JSONArray()
        }.getOrElse {
            JSONArray(extractJsonArray(trimmed))
        }
    }

    private companion object {
        const val MAX_ITEMS = 5
        val REPLYISH = listOf("reply", "answer", "call back", "respond", "message")

        const val SYSTEM_INSTRUCTION =
            "You triage phone notifications for a launcher's Today brief. Today is NOT a second " +
            "notification drawer. For each signal, decide if it needs the user to DO something.\n" +
            "Classify exactly one: action = user must reply, decide, pay, attend, review, or respond; " +
            "fyi = worth knowing but no action; ignore = noise, promos, app nags, typing indicators.\n" +
            "Return only ACTION items, ranked up to 5 by what matters RIGHT NOW. Each signal has an " +
            "\"id\", \"actions\" labels, content fields, and often a local \"taskDraft\".\n" +
            "\n" +
            "For action items, write title as a verb-first imperative task, <= 10 words. Concrete. " +
            "Name the person/subject if present. Do NOT invent senders, amounts, dates, links, " +
            "relationships, or details not present. If text is ambiguous or truncated, classify fyi and omit.\n" +
            "\n" +
            "Examples (style, do not copy literally):\n" +
            "- Sarah: 'Can you send the Q3 deck before 2pm?' -> \"Send Q3 deck to Sarah before 2pm\"\n" +
            "- Sam: 'dinner tonight at 9?' -> \"Reply to Sam about dinner at 9\"\n" +
            "- Slack #eng: '@you PagerDuty alert: API latency high' -> \"Investigate API latency alert\"\n" +
            "- Medium Daily: '5 stories for you today' -> fyi, omit.\n" +
            "\n" +
            "The subtitle is one short supporting line (sender/app, or reply hint). Choose the " +
            "single best action and return its label in \"primaryActionLabel\" — it MUST be exactly one of " +
            "that signal's action labels. Prefer Reply/Respond for questions, then Open. Output STRICT JSON " +
            "only, no prose, no code fence: {\"items\":[{\"signalRef\":\"...\",\"class\":\"action\"," +
            "\"title\":\"...\",\"subtitle\":\"...\",\"category\":\"message|email|call|calendar|weather\"," +
            "\"primaryActionLabel\":\"...\"}]}. Empty/no actions -> {\"items\":[]}."
    }
}
