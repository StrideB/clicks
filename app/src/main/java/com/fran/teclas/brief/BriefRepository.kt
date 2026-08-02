package com.fran.teclas.brief

import android.content.SharedPreferences
import com.fran.teclas.predict.AppCategory
import com.fran.teclas.predict.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orchestrates the Today brief: collect → rank/phrase → publish → cache.
 *
 * - Renders instantly from a text cache on cold start ([Brief.Source.CACHE]); PendingIntents die
 *   with the process, so the cached items carry no live signal until the first background refresh
 *   rebinds them.
 * - Debounces/coalesces notification bursts so we don't spam Gemini.
 * - Runs a light in-process periodic refresh while the launcher is alive (no WorkManager dep).
 */
class BriefRepository(
    private val prefs: SharedPreferences,
    private val collector: BriefCollector,
    private val generator: BriefGenerator,
    private val scope: CoroutineScope
) {
    private val _brief = MutableStateFlow(loadCache())
    val brief: StateFlow<Brief> = _brief

    private var debounceJob: Job? = null
    private var periodicJob: Job? = null

    // Identity of the signal set that produced [lastGeminiItems]. Phase 2 is a full generation over
    // every signal, so re-running it for an unchanged set is pure heat with no visible difference.
    private var lastGeminiKey: String? = null
    private var lastGeminiItems: List<BriefItem>? = null

    /** Fire a refresh immediately (used when Today opens so live signals bind at once). */
    fun refresh() {
        debounceJob?.cancel()
        debounceJob = scope.launch { refreshNow() }
    }

    /** Space-aware read view: no extra collectors, no duplicated alerts, and no fabricated copy. */
    fun briefFor(space: Space?): Brief = _brief.value.forSpace(space)

    /** Coalesce a burst of triggers into one refresh. */
    fun refreshDebounced(delayMs: Long = DEBOUNCE_MS) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(delayMs)
            refreshNow()
        }
    }

    suspend fun refreshNow() {
        val now = System.currentTimeMillis()
        val signals = withContext(Dispatchers.Default) {
            val dismissed = dismissedKeys()
            collector.collect().filterNot { dismissalKey(it) in dismissed }
        }
        if (signals.isEmpty()) {
            val empty = Brief(emptyList(), now, Brief.Source.EMPTY)
            _brief.value = empty
            saveCache(empty)
            lastGeminiKey = null
            lastGeminiItems = null
            return
        }
        // Phase 1: instant, deterministic, fully actionable (live signals bound). No network.
        val rules = withContext(Dispatchers.Default) { generator.ruleOnly(signals, now) }
        publish(Brief(rules, now, Brief.Source.RULES))
        // Phase 2: upgrade phrasing with Gemini if configured. Still binds the same live signals.
        if (generator.canUseGemini()) {
            // Phase 2 is a ~700-token generation over the whole signal set, and it runs on-device
            // (Gemma/Nano) whenever a local model is installed. Re-phrasing an identical set burns
            // that for nothing, so reuse the previous output until the content — or the time bucket
            // the wording depends on ("in 10 min") — actually moves.
            val key = geminiKey(signals, now)
            val reusable = lastGeminiItems?.takeIf { key == lastGeminiKey && it.isNotEmpty() }
            if (reusable != null) {
                publish(Brief(reusable, now, Brief.Source.GEMINI))
                return
            }
            val gem = withContext(Dispatchers.Default) { generator.geminiOnly(signals, now) }
            if (!gem.isNullOrEmpty()) {
                lastGeminiKey = key
                lastGeminiItems = gem
                publish(Brief(gem, now, Brief.Source.GEMINI))
            }
        }
    }

    /**
     * Content identity of the signal set, plus a coarse time bucket. The bucket is what keeps
     * relative phrasing honest: an unchanged set is still re-phrased once a bucket rolls over,
     * instead of once per notification post.
     */
    private fun geminiKey(signals: List<Signal>, now: Long): String {
        val body = signals.sortedBy { it.id }.joinToString("|") { signal ->
            when (signal) {
                is NotificationSignal -> "n:${signal.id}:${signal.contentHash}"
                is CalendarSignal -> "c:${signal.id}:${signal.beginMillis}"
                is WeatherSignal -> "w:${signal.id}:${signal.summary}"
                is MediaSignal -> "m:${signal.id}:${signal.title}:${signal.artist}"
                // Due time is part of the identity: the same overdue note becomes
                // a different thing to say once its deadline passes.
                is CueSignal -> "q:${signal.id}:${signal.title}:${signal.dueAtMillis}"
            }
        }
        return "${now / GEMINI_BUCKET_MS}|${body.hashCode().toString(16)}"
    }

    private fun publish(brief: Brief) {
        _brief.value = brief
        saveCache(brief)
    }

    /** Optimistic removal after an action fires — clears the card immediately, then reconciles. */
    fun removeItem(signalRef: String) {
        val current = _brief.value
        val remaining = current.items.filterNot { it.signalRef == signalRef }
        if (remaining.size != current.items.size) {
            val next = current.copy(items = remaining)
            _brief.value = next
            saveCache(next)
        }
        refreshDebounced(RECONCILE_MS)
    }

    /**
     * User explicitly handled or dismissed this card. Hide this exact notification content even if
     * Android keeps the notification alive. If the notification updates with new text, its content
     * hash changes and it can surface again.
     */
    fun dismissItem(item: BriefItem) {
        item.signal?.let { persistDismissal(it) }
        removeItem(item.signalRef)
    }

    fun startPeriodic() {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            while (isActive) {
                delay(PERIODIC_MS)
                refreshNow()
            }
        }
    }

    fun stopPeriodic() {
        periodicJob?.cancel()
        periodicJob = null
    }

    // ------------------------------------------------------------------------------------- cache

    private fun saveCache(brief: Brief) {
        // Text only — never the live signal / intents.
        val arr = JSONArray()
        brief.items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("ref", item.signalRef)
                    .put("title", item.title)
                    .put("subtitle", item.subtitle)
                    .put("category", item.category.name)
                    .put("label", item.primaryActionLabel)
                    .put("hash", item.signal?.let { contentHash(it) }.orEmpty())
            )
        }
        prefs.edit()
            .putString(CACHE_PREF, JSONObject().put("at", brief.generatedAt).put("items", arr).toString())
            .apply()
    }

    private fun loadCache(): Brief {
        val raw = prefs.getString(CACHE_PREF, null) ?: return Brief.EMPTY
        return runCatching {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<BriefItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                items += BriefItem(
                    signalRef = o.optString("ref"),
                    title = o.optString("title"),
                    subtitle = o.optString("subtitle"),
                    category = runCatching { BriefCategory.valueOf(o.optString("category")) }
                        .getOrDefault(BriefCategory.OTHER),
                    primaryActionLabel = o.optString("label"),
                    signal = null // rebound on first live refresh
                )
            }
            Brief(items, obj.optLong("at"), Brief.Source.CACHE)
        }.getOrDefault(Brief.EMPTY)
    }

    private companion object {
        const val CACHE_PREF = "brief_cache"
        const val DISMISSED_PREF = "brief_dismissed_content"
        const val DEBOUNCE_MS = 900L
        const val RECONCILE_MS = 400L
        const val PERIODIC_MS = 45 * 60_000L

        /** How long an unchanged signal set may reuse its previous Gemini phrasing. */
        const val GEMINI_BUCKET_MS = 10 * 60_000L
        const val MAX_DISMISSED = 120
    }

    private fun persistDismissal(signal: Signal) {
        val key = dismissalKey(signal)
        if (key.isBlank()) return
        val next = dismissedKeys().toMutableSet()
        next += key
        // Keep the set bounded; notifications are live and short-lived, so old hashes are disposable.
        val bounded = next.toList().takeLast(MAX_DISMISSED).toSet()
        prefs.edit().putStringSet(DISMISSED_PREF, bounded).apply()
    }

    private fun dismissedKeys(): Set<String> =
        prefs.getStringSet(DISMISSED_PREF, emptySet()).orEmpty()

    private fun dismissalKey(signal: Signal): String {
        val hash = contentHash(signal)
        return if (hash.isBlank()) "" else "${signal.id}:$hash"
    }

    private fun contentHash(signal: Signal): String = when (signal) {
        is NotificationSignal -> signal.contentHash
        is CalendarSignal -> "${signal.title}|${signal.beginMillis}|${signal.location}".hashCode().toString(16)
        is WeatherSignal -> signal.summary.hashCode().toString(16)
        is MediaSignal -> "${signal.title}|${signal.artist}".hashCode().toString(16)
        is CueSignal -> "${signal.title}|${signal.body}|${signal.dueAtMillis}".hashCode().toString(16)
    }
}

fun Brief.forSpace(space: Space?): Brief {
    if (space == null || items.isEmpty()) return this
    val preferred = space.categoryAffinity.flatMap { it.briefCategories() }.toSet()
    if (preferred.isEmpty()) return this
    val scoped = items.filter { it.category in preferred }
    return if (scoped.isEmpty()) this else copy(items = scoped)
}

private fun AppCategory.briefCategories(): Set<BriefCategory> = when (this) {
    AppCategory.COMMUNICATION, AppCategory.SOCIAL -> setOf(BriefCategory.MESSAGE, BriefCategory.CALL)
    AppCategory.PRODUCTIVITY, AppCategory.FINANCE, AppCategory.TOOLS -> setOf(BriefCategory.EMAIL, BriefCategory.CALENDAR)
    AppCategory.MUSIC -> setOf(BriefCategory.MUSIC)
    AppCategory.MAPS, AppCategory.TRAVEL, AppCategory.HEALTH -> setOf(BriefCategory.CALENDAR, BriefCategory.WEATHER)
    AppCategory.NEWS, AppCategory.READING, AppCategory.SHOPPING, AppCategory.VIDEO, AppCategory.GAMES, AppCategory.PHOTOS, AppCategory.OTHER -> setOf(BriefCategory.OTHER)
}
