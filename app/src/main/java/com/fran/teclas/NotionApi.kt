package com.fran.teclas

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Notion Summon: type a keyword, find the matching Notion page, drop its link into the chat.
 *
 * Connect (later): create an internal integration at notion.so/my-integrations, share the pages you
 * want searchable with it, and paste its secret in Keyboard Settings. Uses the Notion search API.
 * Returns (title, url) for the top page, or null. Blocking; call off the main thread.
 */
object NotionApi {
    const val KEY_PREF = "notion_token"
    private const val VERSION = "2022-06-28"

    fun summon(query: String, token: String): Pair<String, String>? {
        if (token.isBlank() || query.isBlank()) return null
        val body = JSONObject()
            .put("query", query)
            .put("page_size", 3)
            .put("filter", JSONObject().put("value", "page").put("property", "object"))
            .toString()
        val resp = post("https://api.notion.com/v1/search", token, body) ?: return null
        val results = runCatching { JSONObject(resp).optJSONArray("results") }.getOrNull() ?: return null
        if (results.length() == 0) return null
        val page = results.optJSONObject(0) ?: return null
        val url = page.optString("url")
        if (url.isBlank()) return null
        return pageTitle(page) to url
    }

    private fun pageTitle(page: JSONObject): String {
        val props = page.optJSONObject("properties") ?: return ""
        val keys = props.keys()
        while (keys.hasNext()) {
            val prop = props.optJSONObject(keys.next()) ?: continue
            if (prop.optString("type") == "title") {
                val arr = prop.optJSONArray("title") ?: continue
                if (arr.length() > 0) return arr.optJSONObject(0)?.optString("plain_text").orEmpty()
            }
        }
        return ""
    }

    private fun post(url: String, token: String, body: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 8_000; readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Notion-Version", VERSION)
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }
}
