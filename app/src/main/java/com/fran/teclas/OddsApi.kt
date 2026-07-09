package com.fran.teclas

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * World Cup Odds Snapshot via the-odds-api.com (free API key). Pulls current World Cup soccer
 * matches with moneyline (h2h) odds and formats a compact, readable snapshot for the HUD.
 * The user pastes a key in Keyboard Settings; blank key -> skill prompts to add one. Blocking.
 */
object OddsApi {
    const val KEY_PREF = "odds_api_key"

    fun worldCup(apiKey: String): String? {
        if (apiKey.isBlank()) return null
        val body = get(
            "https://api.the-odds-api.com/v4/sports/soccer_fifa_world_cup/odds/" +
                "?regions=us&markets=h2h&oddsFormat=american&apiKey=$apiKey"
        ) ?: return null
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return null
        if (arr.length() == 0) return "🏆 No World Cup matches with odds right now."
        val sb = StringBuilder("🏆 World Cup — moneyline\n")
        var shown = 0
        var i = 0
        while (i < arr.length() && shown < 4) {
            val ev = arr.optJSONObject(i); i++
            if (ev == null) continue
            val home = ev.optString("home_team")
            val away = ev.optString("away_team")
            if (home.isBlank() || away.isBlank()) continue
            val outcomes = ev.optJSONArray("bookmakers")?.optJSONObject(0)
                ?.optJSONArray("markets")?.optJSONObject(0)?.optJSONArray("outcomes")
            val odds = HashMap<String, Int>()
            if (outcomes != null) for (j in 0 until outcomes.length()) {
                val o = outcomes.optJSONObject(j) ?: continue
                odds[o.optString("name")] = o.optInt("price")
            }
            sb.append("• $home ${odd(odds[home])} · Draw ${odd(odds["Draw"])} · $away ${odd(odds[away])}\n")
            shown++
        }
        return sb.toString().trim()
    }

    private fun odd(price: Int?): String = when {
        price == null -> "—"
        price > 0 -> "+$price"
        else -> price.toString()
    }

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
