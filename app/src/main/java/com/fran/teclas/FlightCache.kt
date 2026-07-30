package com.fran.teclas

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last itineraries parsed out of Gmail, kept so search can answer instantly.
 *
 * [TravelPaneHost] already turns flight emails into [FlightSegment]s via Gemini, but it held them in
 * a local field that only existed while the Travel overlay was open — so typing a flight number into
 * search found nothing, and re-parsing per keystroke would mean a network round trip and an LLM call
 * per character. Persisting the parse once makes the search path free: no network, no Gemini, no
 * battery cost, just a prefs read.
 *
 * Only what the confirmation email already told us is stored. No live status, no tracking, nothing
 * fetched — and it stays on the device.
 */
internal object FlightCache {

    private const val PREF = "flight_itineraries_json"
    private const val PREF_AT = "flight_itineraries_at"

    /** Segments are dropped after this long — an itinerary nobody re-opened is probably stale. */
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    fun save(prefs: SharedPreferences, segments: List<FlightSegment>) {
        val arr = JSONArray()
        segments.forEach { s ->
            arr.put(JSONObject().apply {
                put("airline", s.airline); put("flightNumber", s.flightNumber)
                put("from", s.from); put("to", s.to)
                put("depart", s.depart); put("arrive", s.arrive)
                put("date", s.date); put("confirmation", s.confirmation); put("seat", s.seat)
            })
        }
        prefs.edit()
            .putString(PREF, arr.toString())
            .putLong(PREF_AT, System.currentTimeMillis())
            .apply()
    }

    fun load(prefs: SharedPreferences): List<FlightSegment> {
        val savedAt = prefs.getLong(PREF_AT, 0L)
        if (savedAt <= 0L || System.currentTimeMillis() - savedAt > MAX_AGE_MS) return emptyList()
        val raw = prefs.getString(PREF, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                FlightSegment(
                    airline = o.optString("airline"),
                    flightNumber = o.optString("flightNumber"),
                    from = o.optString("from"),
                    to = o.optString("to"),
                    depart = o.optString("depart"),
                    arrive = o.optString("arrive"),
                    date = o.optString("date"),
                    confirmation = o.optString("confirmation"),
                    seat = o.optString("seat"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun clear(prefs: SharedPreferences) {
        prefs.edit().remove(PREF).remove(PREF_AT).apply()
    }
}
