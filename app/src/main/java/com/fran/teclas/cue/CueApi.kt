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
                // Which clinic to act in, when this person works at more than
                // one. Cue rejects any value that is not one of their own.
                CueSession.selectedOrganizationID(context)?.let {
                    setRequestProperty("X-Cue-Organization", it)
                }
                val ok = responseCode in 200..299
                val stream = if (ok) inputStream else errorStream
                val text = stream?.let { readBounded(it) }.orEmpty()
                disconnect()
                if (text.isBlank()) null else JSONObject(text)
            }
        }.getOrNull()
    }

    /**
     * Largest response we will hold in memory.
     *
     * A search payload is a few KB; the record index for a large clinic is the
     * only thing that could approach this. Reading without a bound means the
     * JSON string, the parsed tree and the stored copy all coexist on the heap
     * at several times the wire size — an OutOfMemory on a phone, in a process
     * that is also the launcher.
     */
    private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

    /** Read at most [MAX_RESPONSE_BYTES], discarding anything beyond. */
    private fun readBounded(stream: java.io.InputStream): String {
        val buffer = ByteArray(16 * 1024)
        val out = java.io.ByteArrayOutputStream()
        stream.use {
            while (true) {
                val read = it.read(buffer)
                if (read <= 0) break
                if (out.size() + read > MAX_RESPONSE_BYTES) return ""
                out.write(buffer, 0, read)
            }
        }
        return out.toString("UTF-8")
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

    /**
     * Fire a state change — the EVV clock, and anything else Cue exposes as a
     * write. Returns null on success, or a message to show the user.
     *
     * The server re-checks everything: that this is your session, that it is
     * today, that the transition is legal. Nothing here is trusted.
     */
    fun post(context: Context, write: CueWrite, body: JSONObject = JSONObject()): String? {
        val token = CueSession.accessToken(context) ?: return "Not signed in to Cue"
        val url = "${BuildConfig.CUE_API_BASE_URL.trimEnd('/')}/api/native/v1/${write.resource}" +
            "?id=${URLEncoder.encode(write.id, "UTF-8")}&action=${URLEncoder.encode(write.action, "UTF-8")}"

        val response = runCatching {
            (URL(url).openConnection() as HttpURLConnection).run {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                CueSession.selectedOrganizationID(context)?.let {
                    setRequestProperty("X-Cue-Organization", it)
                }
                outputStream.use { it.write(body.toString().toByteArray()) }
                val ok = responseCode in 200..299
                val text = (if (ok) inputStream else errorStream)
                    ?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty()
                disconnect()
                ok to text
            }
        }.getOrNull() ?: return "Could not reach Cue"

        val (ok, text) = response
        if (ok) return null
        val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
        return message?.takeIf { it.isNotBlank() } ?: "Cue rejected the request"
    }

    /**
     * Today's brief, as items for the launcher's own ranker.
     *
     * Uses the existing `brief` resource rather than a new endpoint — Cue
     * already shapes these for the iOS widget and watch.
     */
    fun briefItems(context: Context, limit: Int = 8): List<CueBriefItem> {
        val payload = get(context, "brief") ?: return emptyList()
        val array = payload.optJSONArray("items")
            ?: payload.optJSONArray("tasks")
            ?: payload.optJSONArray("results")
            ?: return emptyList()

        val items = mutableListOf<CueBriefItem>()
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val title = json.optString("title")
            if (title.isBlank()) continue
            val metadata = json.optJSONObject("metadata")
            items.add(CueBriefItem(
                id = json.optString("id").ifBlank { "cue-brief-$index" },
                title = title,
                body = json.optString("body"),
                dueAtMillis = parseInstant(json.optString("dueAt")),
                href = metadata?.optString("href")?.takeIf { it.isNotBlank() },
                deeplink = metadata?.optString("route")?.takeIf { it.isNotBlank() }?.let { "cue://$it" },
            ))
        }
        return items.take(limit)
    }

    /**
     * Epoch millis from an ISO-8601 timestamp, or 0 when absent/unparseable.
     * Hand-rolled rather than pulling in a formatter: the shapes Cue emits are
     * `2026-07-30`, `2026-07-30T15:30:00`, and the same with a Z or offset.
     */
    private fun parseInstant(raw: String): Long {
        if (raw.isBlank()) return 0L
        return runCatching {
            val normalized = when {
                raw.length == 10 -> "${raw}T00:00:00Z"
                raw.endsWith("Z") || raw.contains('+') -> raw
                else -> "${raw}Z"
            }
            java.time.Instant.parse(normalized).toEpochMilli()
        }.getOrElse { 0L }
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
            val writes = json.optJSONObject("writes")?.let {
                CueWrite(it.optString("resource"), it.optString("id"), it.optString("action"))
            }
            actions.add(CueAction(
                label = json.optString("label"),
                deeplink = json.optString("deeplink").takeIf { it.isNotBlank() },
                href = json.optString("href").takeIf { it.isNotBlank() },
                tel = json.optString("tel").takeIf { it.isNotBlank() },
                geo = json.optString("geo").takeIf { it.isNotBlank() },
                writes = writes,
                primary = json.optBoolean("primary", false),
            ))
        }
        return actions.filter { it.label.isNotBlank() }
    }
}
