package com.fran.teclas.predict

import android.content.Context
import org.json.JSONObject

/**
 * Learns what each Wi-Fi network *means* — "this network is Home", "this one is Work" — by watching
 * which [PlaceKind] the location layer reports while connected to it.
 *
 * Why this exists: indoors, a GPS fix is often stale, coarse, or missing entirely, which is exactly
 * where "am I at home or at work?" matters most. The connected network answers that instantly, for
 * free, with no fix and no battery cost. Once a network has been seen enough times at a known kind,
 * it can stand in for the place on its own.
 *
 * Only a hash of the SSID is ever stored (see [ContextSensors.wifiId]) alongside per-kind counts —
 * never the network name, never a coordinate.
 */
object WifiPlaces {

    /** Sightings at one kind before the network is trusted to name the place by itself. */
    private const val MIN_OBSERVATIONS = 4

    /** Dominance required: the leading kind must hold this share of a network's sightings. */
    private const val MIN_SHARE = 0.6f

    /** Cap per counter so a long-lived network stays adaptable when you move house/office. */
    private const val MAX_COUNT = 40

    private const val PREF = "wifi_place_bindings_json"

    /**
     * Record that [wifiId] was seen while at [kind]. UNKNOWN sightings are ignored — they carry no
     * information and would otherwise dilute a real binding.
     */
    fun observe(context: Context, wifiId: String, kind: PlaceKind) {
        if (kind == PlaceKind.UNKNOWN) return
        val all = load(context)
        val counts = all.getOrPut(wifiId) { mutableMapOf() }
        val current = counts[kind.name] ?: 0
        // Saturated at a confident binding: stop rewriting prefs. This is called from the 2-minute
        // context refresh, so an unconditional write meant a JSON load+serialize+commit every two
        // minutes forever, long after the answer stopped changing.
        if (current >= MAX_COUNT) return
        val next = current + 1
        counts[kind.name] = next
        // Decay the others when one saturates, so a moved network can be re-learned instead of
        // being outvoted forever by history.
        if (next >= MAX_COUNT) {
            counts.keys.forEach { k -> if (k != kind.name) counts[k] = (counts[k] ?: 0) / 2 }
        }
        save(context, all)
    }

    /**
     * The place kind this network reliably means, or null when it hasn't been seen enough times or
     * no single kind dominates (e.g. a phone hotspot used everywhere).
     */
    fun kindFor(context: Context, wifiId: String): PlaceKind? {
        val counts = load(context)[wifiId] ?: return null
        val total = counts.values.sum()
        if (total < MIN_OBSERVATIONS) return null
        val best = counts.maxByOrNull { it.value } ?: return null
        if (best.value.toFloat() / total < MIN_SHARE) return null
        return runCatching { PlaceKind.valueOf(best.key) }.getOrNull()
    }

    fun forget(context: Context) = save(context, mutableMapOf())

    private fun load(context: Context): MutableMap<String, MutableMap<String, Int>> {
        val raw = prefs(context).getString(PREF, null) ?: return mutableMapOf()
        return runCatching {
            val obj = JSONObject(raw)
            val out = mutableMapOf<String, MutableMap<String, Int>>()
            obj.keys().forEach { wifi ->
                val inner = obj.optJSONObject(wifi) ?: return@forEach
                val counts = mutableMapOf<String, Int>()
                inner.keys().forEach { kind -> counts[kind] = inner.optInt(kind, 0) }
                out[wifi] = counts
            }
            out
        }.getOrDefault(mutableMapOf())
    }

    private fun save(context: Context, map: Map<String, Map<String, Int>>) {
        val obj = JSONObject()
        map.forEach { (wifi, counts) ->
            val inner = JSONObject()
            counts.forEach { (kind, n) -> if (n > 0) inner.put(kind, n) }
            if (inner.length() > 0) obj.put(wifi, inner)
        }
        prefs(context).edit().putString(PREF, obj.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("teclas", Context.MODE_PRIVATE)
}
