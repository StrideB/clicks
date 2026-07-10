package com.fran.teclas

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * In-launcher web search via the Brave Search API — unlike Google, Brave licenses its index for
 * rendering results directly in your own UI, so these become native launcher rows/panes.
 * Also serves Rich Data Enrichments: instant answers (weather, stocks, crypto, currency,
 * calculator, definitions, unit conversions, sports scores) for a search query.
 *
 * Connect: create a key at api-dashboard.search.brave.com (Search plan). The recurring $5/month
 * credit covers ~1,000 requests — keep the "Results from Brave Search" attribution visible to
 * retain it. A rich answer costs TWO requests (search + callback), so callers gate rich lookups
 * on query intent instead of firing per keystroke.
 *
 * Rich flow per Brave's API: a web search with enable_rich_callback=1 returns
 * rich.hint.callback_key when the query has rich intent, then /res/v1/web/rich resolves it into
 * the typed payload. Brave doesn't publish per-vertical schemas, so parsing is tolerant: known
 * field names first, generic title/description scan as fallback. Blocking; call off main thread.
 */
object BraveSearchApi {
    const val KEY_PREF = "brave_search_key"
    const val ATTRIBUTION = "Results from Brave Search"

    data class Result(val title: String, val snippet: String, val link: String, val display: String)

    /** One instant answer, flattened for a search-result row. [subtitle] carries the provider
     *  attribution when the payload includes one — some of Brave's data providers require it. */
    data class RichAnswer(val vertical: String, val title: String, val subtitle: String)

    fun isConfigured(prefs: SharedPreferences): Boolean =
        !prefs.getString(KEY_PREF, null).isNullOrBlank()

    /** Standard web results — title/snippet/URL, safe to render natively in the launcher. */
    fun search(query: String, apiKey: String, count: Int = 6): List<Result> {
        if (query.isBlank() || apiKey.isBlank()) return emptyList()
        val body = get(
            "https://api.search.brave.com/res/v1/web/search?q=${URLEncoder.encode(query, "UTF-8")}" +
                "&count=${count.coerceIn(1, 20)}&result_filter=web",
            apiKey
        ) ?: return emptyList()
        val items = runCatching {
            JSONObject(body).optJSONObject("web")?.optJSONArray("results")
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<Result>(items.length())
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val title = it.optString("title").trim()
            val link = it.optString("url").trim()
            if (title.isBlank() || link.isBlank()) continue
            out.add(
                Result(
                    title = title,
                    snippet = it.optString("description").trim(),
                    link = link,
                    display = it.optJSONObject("meta_url")?.optString("hostname")?.trim()
                        .orEmpty().ifBlank { link }
                )
            )
        }
        return out
    }

    fun fetchRich(query: String, apiKey: String): RichAnswer? {
        if (query.isBlank() || apiKey.isBlank()) return null
        val search = get(
            "https://api.search.brave.com/res/v1/web/search?q=${URLEncoder.encode(query, "UTF-8")}" +
                "&enable_rich_callback=1&count=1",
            apiKey
        ) ?: return null
        val hint = runCatching { JSONObject(search).optJSONObject("rich")?.optJSONObject("hint") }
            .getOrNull() ?: return null
        val callbackKey = hint.optString("callback_key").trim()
        if (callbackKey.isBlank()) return null
        val vertical = hint.optString("vertical").trim()

        val rich = get(
            "https://api.search.brave.com/res/v1/web/rich?callback_key=${URLEncoder.encode(callbackKey, "UTF-8")}",
            apiKey
        ) ?: return null
        val results = runCatching { JSONObject(rich).optJSONArray("results") }.getOrNull() ?: return null
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            parse(item, vertical)?.let { return it }
        }
        return null
    }

    private fun parse(item: JSONObject, hintVertical: String): RichAnswer? {
        val subtype = item.optString("subtype").trim().ifBlank { hintVertical }.ifBlank { "answer" }
        val attribution = find(item, "attribution", "provider", "source")
        val answer = when (subtype) {
            "weather" -> {
                val temp = find(item, "temperature", "temp", "current_temperature") ?: return null
                val condition = find(item, "condition", "summary", "description", "weather")
                val place = find(item, "location", "city", "place", "name")
                RichAnswer(subtype, listOfNotNull(formatTemp(temp), condition).joinToString(" · "),
                    listOfNotNull("Weather", place).joinToString(" · "))
            }
            "stock", "cryptocurrency", "currency" -> {
                val price = find(item, "price", "last_price", "rate", "value", "amount") ?: return null
                val symbol = find(item, "symbol", "ticker", "code", "name") ?: return null
                val change = find(item, "change_percent", "price_change_percent", "change")
                RichAnswer(subtype, "$symbol  $price${change?.let { "  ($it)" } ?: ""}",
                    subtype.replaceFirstChar { it.uppercase() })
            }
            "calculator", "unit_conversion", "unix_timestamp" -> {
                val result = find(item, "answer", "result", "value", "output") ?: return null
                val expression = find(item, "expression", "input", "from", "query")
                RichAnswer(subtype, expression?.let { "$it = $result" } ?: result,
                    if (subtype == "calculator") "Calculator" else "Conversion")
            }
            "definitions" -> {
                val word = find(item, "word", "term", "title") ?: return null
                val definition = find(item, "definition", "text", "meaning") ?: return null
                RichAnswer(subtype, word, definition)
            }
            else -> {
                // Sports and anything Brave adds later: surface whatever headline the payload has.
                val title = find(item, "title", "answer", "result", "name", "text") ?: return null
                RichAnswer(subtype, title,
                    find(item, "description", "subtitle", "summary", "status") ?: subtype)
            }
        }
        val credit = attribution?.takeIf { it.isNotBlank() && !answer.subtitle.contains(it) }
        return answer.copy(
            title = answer.title.take(80),
            subtitle = (credit?.let { "${answer.subtitle} · $it" } ?: answer.subtitle).take(90)
        )
    }

    private fun formatTemp(raw: String): String =
        if (raw.toDoubleOrNull() != null) "$raw°" else raw

    /** Depth-limited search for the first non-blank scalar under any of [keys] — the payload
     *  schemas are undocumented, so values may sit at the top level or one object down. */
    private fun find(obj: JSONObject, vararg keys: String, depth: Int = 0): String? {
        for (key in keys) {
            val value = obj.opt(key)
            when (value) {
                is String -> if (value.isNotBlank()) return value.trim()
                is Number, is Boolean -> return value.toString()
                is JSONObject -> find(value, "value", "text", "label", "name")?.let { return it }
                else -> {}
            }
        }
        if (depth >= 2) return null
        val names = obj.keys()
        while (names.hasNext()) {
            val child = obj.opt(names.next())
            val nested = when (child) {
                is JSONObject -> find(child, *keys, depth = depth + 1)
                is JSONArray -> child.optJSONObject(0)?.let { find(it, *keys, depth = depth + 1) }
                else -> null
            }
            if (nested != null) return nested
        }
        return null
    }

    private fun get(url: String, apiKey: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000; readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-subscription-token", apiKey)
        }
        try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }
}
