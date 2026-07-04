package com.fran.clicks

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Weather for the agentic keyboard: type "weather <place>" and drop a current-conditions line into
 * the chat. Uses Open-Meteo — free, no API key, no signup — so this skill works with zero setup.
 * Geocodes the query, then reads current conditions. Blocking; call off the main thread.
 */
object WeatherApi {

    fun lookup(query: String): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val geo = get(
            "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name=" +
                URLEncoder.encode(q, "UTF-8")
        ) ?: return null
        val place = runCatching { JSONObject(geo).optJSONArray("results")?.optJSONObject(0) }.getOrNull() ?: return null
        val lat = place.optDouble("latitude", Double.NaN)
        val lon = place.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        val name = place.optString("name", q)
        val country = place.optString("country_code", "")
        val fc = get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code" +
                "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto"
        ) ?: return null
        val cur = runCatching { JSONObject(fc).optJSONObject("current") }.getOrNull() ?: return null
        val temp = cur.optDouble("temperature_2m", Double.NaN)
        if (temp.isNaN()) return null
        val hum = cur.optInt("relative_humidity_2m", -1)
        val wind = cur.optDouble("wind_speed_10m", 0.0).roundToInt()
        val (emoji, desc) = describe(cur.optInt("weather_code", -1))
        val where = if (country.isNotBlank()) "$name, $country" else name
        val humPart = if (hum >= 0) " · 💧 $hum%" else ""
        return "$emoji $where: ${temp.roundToInt()}°F, $desc$humPart · 🌬 $wind mph"
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

    // WMO weather codes -> (emoji, short description).
    private fun describe(code: Int): Pair<String, String> = when (code) {
        0 -> "☀️" to "clear"
        1, 2 -> "🌤" to "partly cloudy"
        3 -> "☁️" to "overcast"
        45, 48 -> "🌫" to "fog"
        51, 53, 55, 56, 57 -> "🌦" to "drizzle"
        61, 63, 65, 66, 67 -> "🌧" to "rain"
        71, 73, 75, 77 -> "🌨" to "snow"
        80, 81, 82 -> "🌧" to "rain showers"
        85, 86 -> "🌨" to "snow showers"
        95, 96, 99 -> "⛈" to "thunderstorm"
        else -> "🌡" to "current conditions"
    }
}
