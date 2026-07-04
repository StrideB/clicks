package com.fran.clicks

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Stock Sniffer: quick price/trend/peers for a ticker via Finnhub (free API key).
 * The user pastes a key in Keyboard Settings; blank key -> skill prompts to add one. Blocking.
 */
object StockApi {
    const val KEY_PREF = "finnhub_api_key"

    fun lookup(rawSymbol: String, apiKey: String): String? {
        if (apiKey.isBlank()) return null
        val sym = rawSymbol.trim().uppercase(Locale.US).removePrefix("$")
        if (sym.isEmpty() || !sym.all { it.isLetterOrDigit() || it == '.' }) return null
        val quote = get("https://finnhub.io/api/v1/quote?symbol=$sym&token=$apiKey") ?: return null
        val q = runCatching { JSONObject(quote) }.getOrNull() ?: return null
        val price = q.optDouble("c", 0.0)
        if (price <= 0.0) return null            // unknown ticker -> current price is 0
        val change = q.optDouble("d", 0.0)
        val pct = q.optDouble("dp", 0.0)

        val name = get("https://finnhub.io/api/v1/stock/profile2?symbol=$sym&token=$apiKey")
            ?.let { runCatching { JSONObject(it).optString("name") }.getOrNull() }.orEmpty()
        val peers = get("https://finnhub.io/api/v1/stock/peers?symbol=$sym&token=$apiKey")
            ?.let { runCatching { JSONArray(it) }.getOrNull() }
        val related = if (peers == null) emptyList() else
            (0 until peers.length()).map { peers.optString(it) }
                .filter { it.isNotBlank() && !it.equals(sym, ignoreCase = true) }.take(3)

        val arrow = if (change >= 0) "📈" else "📉"
        val sign = if (change >= 0) "+" else ""
        val namePart = if (name.isNotBlank()) " $name" else ""
        val relPart = if (related.isNotEmpty()) " · peers: ${related.joinToString(" ")}" else ""
        return "\$$sym$namePart $arrow \$${fmt(price)} $sign${fmt(change)} (${sign}${fmt(pct)}%)$relPart"
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun get(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 6_000; readTimeout = 10_000
        }
        try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }
}
