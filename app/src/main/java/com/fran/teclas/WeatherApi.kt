package com.fran.teclas

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Weather via Open-Meteo — free, no API key, no signup, so it works on a fresh install with zero
 * setup. Serves both the agentic keyboard skill ([lookup], a one-line summary) and launcher
 * search ([card], the full rich answer). Search used to route weather through Brave, which cost
 * two metered requests and needed a key for something a free API answers better.
 * Blocking; call off the main thread.
 */
object WeatherApi {

    /** Full weather card for launcher search. [place] is a typed place ("bogota") or null to use
     *  [atLat]/[atLng] (the device / demo location). Returns null if it can't resolve either. */
    fun card(place: String?, atLat: Double? = null, atLng: Double? = null): BraveSearchApi.RichAnswer? {
        var lat = atLat; var lng = atLng; var label: String? = null
        if (!place.isNullOrBlank()) {
            val geo = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name=" +
                URLEncoder.encode(place.trim(), "UTF-8")) ?: return null
            val hit = runCatching { JSONObject(geo).optJSONArray("results")?.optJSONObject(0) }.getOrNull()
                ?: return null
            lat = hit.optDouble("latitude", Double.NaN).takeIf { !it.isNaN() } ?: return null
            lng = hit.optDouble("longitude", Double.NaN).takeIf { !it.isNaN() } ?: return null
            label = listOfNotNull(
                hit.optString("name").takeIf { it.isNotBlank() },
                hit.optString("admin1").takeIf { it.isNotBlank() } ?: hit.optString("country").takeIf { it.isNotBlank() }
            ).joinToString(", ")
        }
        if (lat == null || lng == null) return null
        val fahrenheit = java.util.Locale.getDefault().country in setOf("US", "BS", "BZ", "KY", "PW", "LR", "MM")
        val unit = if (fahrenheit) "fahrenheit" else "celsius"
        val body = get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                "&hourly=temperature_2m&forecast_days=5&forecast_hours=24" +
                "&temperature_unit=$unit&timezone=auto"
        ) ?: return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val cur = root.optJSONObject("current") ?: return null
        val temp = cur.optDouble("temperature_2m", Double.NaN).takeIf { !it.isNaN() } ?: return null
        val code = cur.optInt("weather_code", -1)
        val (glyph, desc) = describe(code)
        val deg = if (fahrenheit) "°F" else "°C"

        // Today's hi/lo + the next-days strip.
        val daily = root.optJSONObject("daily")
        val dMax = daily?.optJSONArray("temperature_2m_max")
        val dMin = daily?.optJSONArray("temperature_2m_min")
        val dCode = daily?.optJSONArray("weather_code")
        val dTime = daily?.optJSONArray("time")
        val hiLo = if (dMax != null && dMin != null && dMax.length() > 0)
            "H:${dMax.optDouble(0).roundToInt()}°  L:${dMin.optDouble(0).roundToInt()}°" else null
        val forecast = (0 until minOf(dMax?.length() ?: 0, 5)).mapNotNull { i ->
            val hi = dMax?.optDouble(i)?.takeIf { !it.isNaN() } ?: return@mapNotNull null
            val lo = dMin?.optDouble(i)?.takeIf { !it.isNaN() } ?: return@mapNotNull null
            BraveSearchApi.DayForecast(
                day = if (i == 0) "NOW" else dayLabel(dTime?.optString(i)),
                glyph = describe(dCode?.optInt(i) ?: -1).first,
                hi = "${hi.roundToInt()}°", lo = "${lo.roundToInt()}°"
            )
        }
        // 24h curve for the sparkline.
        val curve = root.optJSONObject("hourly")?.optJSONArray("temperature_2m")?.let { arr ->
            (0 until minOf(arr.length(), 24)).mapNotNull { arr.optDouble(it).takeIf { v -> !v.isNaN() }?.toFloat() }
        }.orEmpty()

        val feels = cur.optDouble("apparent_temperature", Double.NaN).takeIf { !it.isNaN() }
            ?.let { "Feels ${it.roundToInt()}°" }
        val hum = cur.optInt("relative_humidity_2m", -1).takeIf { it >= 0 }?.let { "Humidity $it%" }
        return BraveSearchApi.RichAnswer(
            vertical = "weather",
            headline = "${temp.roundToInt()}$deg ${desc.replaceFirstChar { it.uppercase() }}",
            label = listOfNotNull("Weather", label).joinToString(" · "),
            detail = listOfNotNull(hiLo, feels, hum).joinToString("  ·  "),
            delta = null, deltaUp = true,
            provider = "Open-Meteo",
            spark = curve,
            glyph = glyph,
            forecast = forecast
        )
    }

    /** "2026-07-16" → "THU". */
    private fun dayLabel(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return runCatching {
            val d = java.time.LocalDate.parse(iso)
            d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
                .uppercase(java.util.Locale.getDefault()).take(3)
        }.getOrDefault("")
    }

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
