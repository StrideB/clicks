package com.fran.teclas.spacetoday.rank

import android.content.SharedPreferences
import com.fran.teclas.GeminiClient
import com.fran.teclas.spacetoday.model.Tier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stage B: a single strict, batched quality pass over the already pre-scored candidates.
 *
 * `quality=true` is the important bit: GeminiClient then chooses the most capable downloaded
 * local model first (Gemma quality tier if installed), then Nano/local/cloud fallbacks. No caller
 * hard-depends on the network, and unchanged notification content is cached by content hash.
 */
class LlmRanker(private val prefs: SharedPreferences) {
    data class Ranked(val ref: String, val tier: Tier, val summary: String, val action: String)
    data class BatchResult(val perItem: List<Ranked>, val hero: String)
    data class RankInput(
        val ref: String,
        val sender: String,
        val zone: String,
        val category: String,
        val time: String,
        val body: String,
        val contentHash: String,
        val score: Int,
        val fallbackSummary: String
    )

    suspend fun rank(spaceId: String, candidates: List<RankInput>, t1Cut: Int, t2Cut: Int): BatchResult =
        withContext(Dispatchers.IO) {
            if (candidates.isEmpty()) return@withContext BatchResult(emptyList(), "No urgent work in this Space.")
            val cached = cached()
            val ranked = ArrayList<Ranked>()
            val misses = ArrayList<RankInput>()
            candidates.forEach { input ->
                cached[input.contentHash]?.let { ranked += it.copy(ref = input.ref) } ?: run { misses += input }
            }
            if (misses.isNotEmpty() && GeminiClient.configured(prefs)) {
                callModel(spaceId, misses, t1Cut, t2Cut)?.let { result ->
                    ranked += result.perItem
                    val nextCache = cached.toMutableMap()
                    misses.forEach { input ->
                        result.perItem.firstOrNull { it.ref == input.ref }?.let { nextCache[input.contentHash] = it.copy(ref = "") }
                    }
                    saveCache(nextCache)
                    return@withContext BatchResult(
                        perItem = mergeRanked(candidates, ranked, t1Cut, t2Cut),
                        hero = result.hero.ifBlank { fallbackHero(candidates) }
                    )
                }
            }
            BatchResult(mergeRanked(candidates, ranked, t1Cut, t2Cut), fallbackHero(candidates))
        }

    private fun callModel(spaceId: String, candidates: List<RankInput>, t1Cut: Int, t2Cut: Int): BatchResult? {
        val payload = JSONArray().apply {
            candidates.forEach { c ->
                put(JSONObject()
                    .put("ref", c.ref)
                    .put("sender", c.sender)
                    .put("zone", c.zone)
                    .put("category", c.category)
                    .put("time", c.time)
                    .put("score", c.score)
                    .put("body", c.body.take(260)))
            }
        }
        val prompt = """
            You rank phone notification-derived tasks for Teclas Space Today.
            Active Space: $spaceId.
            Use ONLY the notification/event context below. Never invent people, links, dates, amounts, locations, or facts.
            Rank relative importance across this batch, not absolute importance.
            Keep every summary concrete and action-oriented. If the notification is vague or only FYI, keep it tier 3.
            Return ONLY valid compact JSON. No prose, no markdown, no code fence.
            Schema:
            {"hero":"<=160 chars, what needs doing, most urgent first","items":[{"ref":"candidate ref","tier":1|2|3,"summary":"<=90 chars, specific","action":"reply|join|open|snooze|dismiss|directions"}]}
            Tier guide with learned cutoffs: score >= $t1Cut is usually tier 1, >= $t2Cut tier 2, otherwise tier 3. You may downgrade if context is weak, but do not upgrade without evidence.
            Candidates:
            $payload
        """.trimIndent()
        val raw = GeminiClient.generate(
            apiKey = GeminiClient.apiKey(prefs),
            model = GeminiClient.model(prefs),
            prompt = prompt,
            maxTokens = 800,
            temperature = 0.1,
            json = true,
            quality = true
        ) ?: return null
        return parse(raw)
    }

    private fun parse(raw: String): BatchResult? = runCatching {
        val obj = JSONObject(extractObject(raw))
        val items = obj.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<Ranked>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val ref = item.optString("ref").trim()
            if (ref.isBlank()) continue
            val tier = when (item.optInt("tier", 3)) {
                1 -> Tier.T1_CRITICAL
                2 -> Tier.T2_IMPORTANT
                else -> Tier.T3_AMBIENT
            }
            val summary = item.optString("summary").trim().take(90)
            val action = item.optString("action").trim().lowercase().ifBlank { "open" }
            out += Ranked(ref, tier, summary, action)
        }
        BatchResult(out, obj.optString("hero").trim().take(160))
    }.getOrNull()

    private fun mergeRanked(
        candidates: List<RankInput>,
        ranked: List<Ranked>,
        t1Cut: Int,
        t2Cut: Int
    ): List<Ranked> {
        val byRef = ranked.associateBy { it.ref }
        return candidates.map { input ->
            byRef[input.ref]?.copy(summary = byRef[input.ref]?.summary?.ifBlank { input.fallbackSummary } ?: input.fallbackSummary)
                ?: Ranked(input.ref, tierFromScore(input.score, t1Cut, t2Cut), input.fallbackSummary, "open")
        }
    }

    private fun fallbackHero(candidates: List<RankInput>): String {
        val top = candidates.maxByOrNull { it.score } ?: return "No urgent work in this Space."
        return when {
            top.score >= 58 -> "${top.sender}: ${top.fallbackSummary}".take(160)
            candidates.isNotEmpty() -> "${candidates.size} items are waiting. ${top.fallbackSummary}".take(160)
            else -> "No urgent work in this Space."
        }
    }

    private fun cached(): Map<String, Ranked> {
        val raw = prefs.getString(PREF, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { key ->
                val r = obj.optJSONObject(key) ?: JSONObject()
                Ranked(
                    ref = "",
                    tier = runCatching { Tier.valueOf(r.optString("tier")) }.getOrDefault(Tier.T3_AMBIENT),
                    summary = r.optString("summary"),
                    action = r.optString("action", "open")
                )
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveCache(map: Map<String, Ranked>) {
        val obj = JSONObject()
        map.entries.toList().takeLast(MAX_CACHE).forEach { (hash, ranked) ->
            obj.put(hash, JSONObject()
                .put("tier", ranked.tier.name)
                .put("summary", ranked.summary)
                .put("action", ranked.action))
        }
        prefs.edit().putString(PREF, obj.toString()).apply()
    }

    private fun extractObject(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else "{}"
    }

    companion object {
        fun tierFromScore(score: Int, t1Cut: Int, t2Cut: Int): Tier = when {
            score >= t1Cut -> Tier.T1_CRITICAL
            score >= t2Cut -> Tier.T2_IMPORTANT
            else -> Tier.T3_AMBIENT
        }

        private const val PREF = "space_today_llm_cache_json"
        private const val MAX_CACHE = 160
    }
}
