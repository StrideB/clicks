package com.fran.teclas

import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Nearby places via the Google Places API (New) Text Search. Unlike Google web search, Places
 * data MAY be rendered in your own UI without a Google Map — provided the "Google Maps"
 * attribution stays visible on the card (see PLACES_ATTRIBUTION; don't remove it).
 *
 * Connect: a Google Cloud project with "Places API (New)" enabled + an API key (billing account
 * required even for the free tier). Free tier is per-SKU per month (~5k Text Search Pro calls),
 * separate from the Brave credit — so local searches never eat Brave's ~1k.
 *
 * Caching: Google's terms allow storing place IDs indefinitely but NOT the rest of the payload,
 * so results are held only in memory for the live query and never persisted. Blocking; call off
 * the main thread.
 */
object PlacesApi {
    const val KEY_PREF = "places_api_key"
    const val ATTRIBUTION = "Google Maps"

    data class Place(
        val id: String,
        val name: String,
        val address: String,
        val rating: Double?,       // 0..5, null when unrated
        val ratingCount: Int,
        val priceLevel: String,    // "$$" / "" when unknown
        val openNow: Boolean?,     // null when the API didn't say
        val type: String,          // "Restaurant", "Coffee shop" …
        val lat: Double?,
        val lng: Double?
    ) {
        /** Deep link into Google Maps for this exact place (place_id is the stable identifier). */
        fun mapsUrl(): String =
            "https://www.google.com/maps/search/?api=1&query=${android.net.Uri.encode(name)}&query_place_id=$id"
    }

    fun isConfigured(prefs: SharedPreferences): Boolean =
        !prefs.getString(KEY_PREF, null).isNullOrBlank()

    /** Strip the local-intent phrasing so the API sees the subject: "restaurants near me" →
     *  "restaurants". The location comes from locationBias, not the text. */
    fun subjectOf(query: String): String {
        var q = query.trim().lowercase(java.util.Locale.US)
        listOf(" near me", " nearby", " near by", " close to me", " around me", " open now").forEach {
            q = q.replace(it, "")
        }
        listOf("nearest ", "closest ", "nearby ").forEach { if (q.startsWith(it)) q = q.removePrefix(it) }
        return q.trim().ifBlank { "places" }
    }

    /**
     * Text Search biased to [lat]/[lng]. Returns up to [limit] places ranked by Google's relevance.
     * The field mask decides the billing SKU — keep it to what the card actually renders.
     */
    fun search(query: String, apiKey: String, lat: Double, lng: Double, limit: Int = 5): List<Place> {
        if (query.isBlank() || apiKey.isBlank()) return emptyList()
        val body = JSONObject().apply {
            put("textQuery", subjectOf(query))
            put("maxResultCount", limit.coerceIn(1, 10))
            put("locationBias", JSONObject().apply {
                put("circle", JSONObject().apply {
                    put("center", JSONObject().apply { put("latitude", lat); put("longitude", lng) })
                    put("radius", 3000.0)
                })
            })
        }.toString()
        val fields = listOf(
            "places.id", "places.displayName", "places.formattedAddress", "places.rating",
            "places.userRatingCount", "places.priceLevel", "places.currentOpeningHours.openNow",
            "places.location", "places.primaryTypeDisplayName"
        ).joinToString(",")
        val json = post("https://places.googleapis.com/v1/places:searchText", body, apiKey, fields)
            ?: return emptyList()
        val items = runCatching { JSONObject(json).optJSONArray("places") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Place>(items.length())
        for (i in 0 until items.length()) {
            val p = items.optJSONObject(i) ?: continue
            val name = p.optJSONObject("displayName")?.optString("text")?.trim().orEmpty()
            if (name.isBlank()) continue
            val loc = p.optJSONObject("location")
            out.add(
                Place(
                    id = p.optString("id").trim(),
                    name = name,
                    address = p.optString("formattedAddress").trim(),
                    rating = p.optDouble("rating").takeIf { !it.isNaN() && it > 0 },
                    ratingCount = p.optInt("userRatingCount"),
                    priceLevel = priceSymbols(p.optString("priceLevel")),
                    openNow = p.optJSONObject("currentOpeningHours")?.let {
                        if (it.has("openNow")) it.optBoolean("openNow") else null
                    },
                    type = p.optJSONObject("primaryTypeDisplayName")?.optString("text")?.trim().orEmpty(),
                    lat = loc?.optDouble("latitude")?.takeIf { !it.isNaN() },
                    lng = loc?.optDouble("longitude")?.takeIf { !it.isNaN() }
                )
            )
        }
        return out
    }

    private fun priceSymbols(level: String): String = when (level) {
        "PRICE_LEVEL_FREE" -> "Free"
        "PRICE_LEVEL_INEXPENSIVE" -> "$"
        "PRICE_LEVEL_MODERATE" -> "$$"
        "PRICE_LEVEL_EXPENSIVE" -> "$$$"
        "PRICE_LEVEL_VERY_EXPENSIVE" -> "$$$$"
        else -> ""
    }

    private fun post(url: String, body: String, apiKey: String, fieldMask: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5_000; readTimeout = 8_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Goog-Api-Key", apiKey)
            setRequestProperty("X-Goog-FieldMask", fieldMask)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            // A 403 here is almost always the key's API restrictions (API_KEY_SERVICE_BLOCKED)
            // rather than a bad request — check that Places API (New) is in the key's allowed list.
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }
}
