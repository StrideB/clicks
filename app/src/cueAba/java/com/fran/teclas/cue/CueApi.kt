package com.fran.teclas.cue

import android.content.Context
import com.fran.teclas.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client for Cue's native dispatcher, `/api/native/v1/<resource>`.
 *
 * Auth is the signed-in staff member's Supabase JWT, never an org API key: a
 * `cue_live_…` key resolves to admin-equivalent, org-wide scope for whoever
 * holds the APK, which would put every kiddo in the organization on a
 * homescreen. The JWT path inherits the real person's role scope instead.
 *
 * Blocking — call from a background thread.
 */
internal object CueApi {

    fun get(context: Context, resource: String, query: Map<String, String> = emptyMap()): JSONObject? {
        val token = CueSession.accessToken(context) ?: return null
        val suffix = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
        val url = "${BuildConfig.CUE_API_BASE_URL.trimEnd('/')}/api/native/v1/$resource$suffix"

        return runCatching {
            (URL(url).openConnection() as HttpURLConnection).run {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                val ok = responseCode in 200..299
                val text = (if (ok) inputStream else errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                disconnect()
                if (text.isBlank()) null else JSONObject(text)
            }
        }.getOrNull()
    }

    /**
     * Run a search. The raw query goes to the server, which owns the grammar —
     * sort order, modifiers and permission gates all resolve there, so "soonest
     * expiry" can change meaning without shipping a new APK.
     */
    fun search(context: Context, query: String, limit: Int = 12): CueResults {
        val payload = get(context, "search", mapOf("q" to query, "limit" to limit.toString()))
            ?: return CueResults.error(query, "Cue is unreachable")

        payload.optString("error").takeIf { it.isNotBlank() }?.let {
            return CueResults.error(query, it)
        }

        return CueResults(
            query = query,
            mode = payload.optString("mode", "empty"),
            type = payload.optString("type").takeIf { it.isNotBlank() && it != "null" },
            sortedBy = payload.optString("sortedBy").takeIf { it.isNotBlank() && it != "null" },
            summary = payload.optJSONObject("summary")?.let(::parseSummary),
            cards = payload.optJSONArray("results")?.let(::parseCards).orEmpty(),
            gate = payload.optString("gate").takeIf { it.isNotBlank() },
        )
    }

    private fun parseSummary(json: JSONObject): CueSummary {
        val buckets = mutableListOf<CueBucket>()
        json.optJSONArray("distribution")?.let { array ->
            for (index in 0 until array.length()) {
                val bucket = array.optJSONObject(index) ?: continue
                buckets.add(CueBucket(
                    label = bucket.optString("label"),
                    count = bucket.optInt("count"),
                    tone = bucket.optString("tone"),
                ))
            }
        }
        return CueSummary(json.optInt("count"), json.optString("headline"), buckets)
    }

    private fun parseCards(array: JSONArray): List<CueCard> {
        val cards = mutableListOf<CueCard>()
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val badge = json.optJSONObject("badge")
            cards.add(CueCard(
                type = json.optString("type"),
                id = json.optString("id"),
                glyph = json.optString("glyph").ifBlank { "•" },
                title = json.optString("title"),
                subtitle = json.optString("subtitle"),
                sortValue = json.optInt("sortValue"),
                phi = json.optBoolean("phi", true),
                badgeText = badge?.optString("text")?.takeIf { it.isNotBlank() },
                badgeTone = badge?.optString("tone"),
                meter = json.optJSONObject("meter")?.let {
                    CueMeter(it.optString("label"), it.optInt("used"), it.optInt("total"), it.optString("tone"))
                },
                fields = parseFields(json.optJSONArray("fields")),
                detail = parseFields(json.optJSONArray("detail")),
                imageUrl = json.optString("imageUrl").takeIf { it.isNotBlank() && it != "null" },
                actions = parseActions(json.optJSONArray("actions")),
            ))
        }
        return cards
    }

    private fun parseFields(array: JSONArray?): List<CueField> {
        if (array == null) return emptyList()
        val fields = mutableListOf<CueField>()
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val label = json.optString("label")
            val value = json.optString("value")
            if (label.isNotBlank() && value.isNotBlank()) fields.add(CueField(label, value))
        }
        return fields
    }

    private fun parseActions(array: JSONArray?): List<CueAction> {
        if (array == null) return emptyList()
        val actions = mutableListOf<CueAction>()
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            actions.add(CueAction(
                label = json.optString("label"),
                deeplink = json.optString("deeplink").takeIf { it.isNotBlank() },
                tel = json.optString("tel").takeIf { it.isNotBlank() },
                geo = json.optString("geo").takeIf { it.isNotBlank() },
                primary = json.optBoolean("primary", false),
            ))
        }
        return actions.filter { it.label.isNotBlank() }
    }
}
