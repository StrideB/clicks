package com.fran.clicks.brief

import android.content.SharedPreferences
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

    /** Fire a refresh immediately (used when Today opens so live signals bind at once). */
    fun refresh() {
        debounceJob?.cancel()
        debounceJob = scope.launch { refreshNow() }
    }

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
        val signals = withContext(Dispatchers.Default) { collector.collect() }
        if (signals.isEmpty()) {
            val empty = Brief(emptyList(), now, Brief.Source.EMPTY)
            _brief.value = empty
            saveCache(empty)
            return
        }
        // Phase 1: instant, deterministic, fully actionable (live signals bound). No network.
        val rules = withContext(Dispatchers.Default) { generator.ruleOnly(signals, now) }
        publish(Brief(rules, now, Brief.Source.RULES))
        // Phase 2: upgrade phrasing with Gemini if configured. Still binds the same live signals.
        if (generator.canUseGemini()) {
            val gem = withContext(Dispatchers.Default) { generator.geminiOnly(signals, now) }
            if (!gem.isNullOrEmpty()) publish(Brief(gem, now, Brief.Source.GEMINI))
        }
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
        const val DEBOUNCE_MS = 900L
        const val RECONCILE_MS = 400L
        const val PERIODIC_MS = 45 * 60_000L
    }
}
