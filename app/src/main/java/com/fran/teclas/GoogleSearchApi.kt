package com.fran.teclas

import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * In-launcher Google search via the Programmable Search / Custom Search JSON API.
 *
 * Connect (later): create a Programmable Search Engine at programmablesearchengine.google.com
 * (turn on "Search the entire web"), copy its Search engine ID (cx), and get an API key from a
 * Google Cloud project with the "Custom Search API" enabled. Paste both in Teclas Settings.
 * Free tier: 100 queries/day. Blocking; call off the main thread.
 */
object GoogleSearchApi {
    const val KEY_PREF = "google_search_key"
    const val CX_PREF = "google_search_cx"

    data class Result(val title: String, val snippet: String, val link: String, val display: String)

    fun isConfigured(prefs: SharedPreferences): Boolean =
        !prefs.getString(KEY_PREF, null).isNullOrBlank() && !prefs.getString(CX_PREF, null).isNullOrBlank()

    fun search(query: String, apiKey: String, cx: String, count: Int = 6): List<Result> {
        if (query.isBlank() || apiKey.isBlank() || cx.isBlank()) return emptyList()
        val url = "https://www.googleapis.com/customsearch/v1?key=$apiKey&cx=$cx&num=${count.coerceIn(1, 10)}" +
            "&q=${URLEncoder.encode(query, "UTF-8")}"
        val body = get(url) ?: return emptyList()
        val items = runCatching { JSONObject(body).optJSONArray("items") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Result>(items.length())
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val title = it.optString("title").trim()
            val link = it.optString("link").trim()
            if (title.isBlank() || link.isBlank()) continue
            out.add(
                Result(
                    title = title,
                    snippet = it.optString("snippet").trim(),
                    link = link,
                    display = it.optString("displayLink").trim().ifBlank { link }
                )
            )
        }
        return out
    }

    private fun get(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 6_000; readTimeout = 12_000
        }
        try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }
}
