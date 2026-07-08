package com.fran.clicks.predict

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline auto-labeling of places from GPS dwell patterns — no map data, no network:
 *  - HOME: the cluster where the phone dwells overnight (hours 22-05).
 *  - WORK: the top weekday 9-17 cluster that isn't home.
 *  - AIRPORT: a spot you were at right before/after a 100+ km hop within a few hours.
 *
 * A hit becomes a [Suggestion] which is surfaced as an actionable system notification
 * ("Is this Home?" → Yes configures the place and its Space starts triggering) and as a
 * card in Spaces settings. "No" is remembered per spot+kind so we never re-nag. All of
 * this stays in the encrypted prediction prefs.
 */
object PlaceInference {

    private const val SUGGESTIONS_KEY = "predict_place_suggestions"
    private const val DISMISSED_KEY = "predict_place_dismissed"
    private const val LAST_RUN_KEY = "predict_inference_last_run"
    private const val LAST_FIX_KEY = "predict_last_fix"

    /** "clicks" pref: master switch for suggestions (settings toggle), default on. */
    const val ENABLED_PREF = "spaces_place_suggestions"

    private const val RUN_EVERY_MS = 6 * 60 * 60 * 1000L
    private const val JUMP_DISTANCE_M = 100_000f
    private const val JUMP_WINDOW_MS = 6 * 60 * 60 * 1000L
    private const val MIN_OVERNIGHT = 8
    private const val MIN_OFFICE = 10

    data class Suggestion(
        val key: String,       // stable id: "<kind>@<cell>"
        val kind: PlaceKind,
        val lat: Double,
        val lng: Double,
        val reason: String,
        val ts: Long,
    )

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("clicks", Context.MODE_PRIVATE).getBoolean(ENABLED_PREF, true)

    // ---- signal intake ------------------------------------------------------------------

    /**
     * Called on every location refresh (from PlaceStore.clusterFor). Detects the airport
     * signature: a displacement of 100+ km between consecutive fixes within a few hours —
     * nothing but flying looks like that. The departure point becomes an AIRPORT candidate.
     */
    fun onFix(context: Context, location: Location) {
        if (!enabled(context)) return
        val prefs = PredictCrypto.prefs(context)
        val now = System.currentTimeMillis()
        val last = runCatching { JSONObject(prefs.getString(LAST_FIX_KEY, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        val lastTs = last.optLong("ts", 0L)
        val lastLat = last.optDouble("lat", Double.NaN)
        val lastLng = last.optDouble("lng", Double.NaN)
        if (lastTs > 0 && !lastLat.isNaN() && now - lastTs < JUMP_WINDOW_MS) {
            val dist = PlaceStore.distanceM(lastLat, lastLng, location.latitude, location.longitude)
            if (dist > JUMP_DISTANCE_M) {
                suggest(
                    context,
                    Suggestion(
                        key = "AIRPORT@${lastLat.round4()},${lastLng.round4()}",
                        kind = PlaceKind.AIRPORT,
                        lat = lastLat, lng = lastLng,
                        reason = "You hopped ${(dist / 1000).toInt()} km from here in one go",
                        ts = now,
                    )
                )
            }
        }
        prefs.edit().putString(LAST_FIX_KEY, JSONObject().apply {
            put("ts", now); put("lat", location.latitude); put("lng", location.longitude)
        }.toString()).apply()
    }

    /** Throttled dwell-pattern pass — call from any background refresh. */
    fun maybeRun(context: Context) {
        if (!enabled(context)) return
        val prefs = PredictCrypto.prefs(context)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(LAST_RUN_KEY, 0L) < RUN_EVERY_MS) return
        prefs.edit().putLong(LAST_RUN_KEY, now).apply()
        runCatching { inferDwellPlaces(context) }
    }

    private fun inferDwellPlaces(context: Context) {
        val clusters = PlaceStore.autoClusters(context)
        if (clusters.isEmpty()) return
        // Overnight (22-05, any day): the home signature.
        fun overnight(h: IntArray): Int =
            (22..23).sumOf { h[it] + h[it + 24] } + (0..5).sumOf { h[it] + h[it + 24] }
        // Weekday office hours (9-17): the work signature.
        fun office(h: IntArray): Int = (9..17).sumOf { h[it] }

        val totalOvernight = clusters.sumOf { overnight(it.hist) }
        val homeCluster = clusters.maxByOrNull { overnight(it.hist) }
        var homeCell: String? = null
        if (homeCluster != null && totalOvernight > 0) {
            val n = overnight(homeCluster.hist)
            if (n >= MIN_OVERNIGHT && n.toFloat() / totalOvernight >= 0.5f) {
                homeCell = homeCluster.cell
                suggestCell(context, homeCluster.cell, PlaceKind.HOME, "You spend most nights here")
            }
        }
        val totalOffice = clusters.sumOf { office(it.hist) }
        val workCluster = clusters.filter { it.cell != homeCell }.maxByOrNull { office(it.hist) }
        if (workCluster != null && totalOffice > 0) {
            val n = office(workCluster.hist)
            if (n >= MIN_OFFICE && n.toFloat() / totalOffice >= 0.4f) {
                suggestCell(context, workCluster.cell, PlaceKind.WORK, "You're here most weekday office hours")
            }
        }
    }

    private fun suggestCell(context: Context, cell: String, kind: PlaceKind, reason: String) {
        val (lat, lng) = PlaceStore.cellCenter(cell) ?: return
        suggest(context, Suggestion("${kind.name}@$cell", kind, lat, lng, reason, System.currentTimeMillis()))
    }

    // ---- suggestion lifecycle -------------------------------------------------------------

    private fun suggest(context: Context, s: Suggestion) {
        // Never re-suggest: declined before, already pending, already covered by a saved
        // place, or (home/work) that kind already exists.
        if (s.key in dismissedKeys(context)) return
        if (pending(context).any { it.key == s.key }) return
        if (PlaceStore.placeFor(context, s.lat, s.lng) != null) return
        val places = PlaceStore.places(context)
        when (s.kind) {
            PlaceKind.HOME, PlaceKind.WORK -> if (places.any { it.kind == s.kind }) return
            PlaceKind.AIRPORT -> if (places.any {
                    it.kind == PlaceKind.AIRPORT && PlaceStore.distanceM(it.lat, it.lng, s.lat, s.lng) < 3000f
                }) return
            else -> return
        }
        savePending(context, pending(context) + s)
        PlaceSuggestionNotifier.show(context, s)
    }

    fun pending(context: Context): List<Suggestion> {
        val raw = PredictCrypto.prefs(context).getString(SUGGESTIONS_KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Suggestion(
                    key = o.optString("key"),
                    kind = runCatching { PlaceKind.valueOf(o.optString("kind")) }.getOrNull() ?: return@mapNotNull null,
                    lat = o.optDouble("lat"), lng = o.optDouble("lng"),
                    reason = o.optString("reason"), ts = o.optLong("ts"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** User said yes (notification action or settings card): create the place for real. */
    fun accept(context: Context, key: String): Place? {
        val s = pending(context).find { it.key == key } ?: return null
        val place = PlaceStore.addPlace(
            context,
            name = defaultName(s.kind),
            kind = s.kind,
            lat = s.lat, lng = s.lng,
            radiusM = if (s.kind == PlaceKind.AIRPORT) 1500f else 250f,
        )
        savePending(context, pending(context).filter { it.key != key })
        return place
    }

    /** User said no: remember it so this spot+kind never comes back. */
    fun dismiss(context: Context, key: String) {
        val prefs = PredictCrypto.prefs(context)
        prefs.edit().putStringSet(DISMISSED_KEY, dismissedKeys(context) + key).apply()
        savePending(context, pending(context).filter { it.key != key })
    }

    fun defaultName(kind: PlaceKind): String = when (kind) {
        PlaceKind.HOME -> "Home"
        PlaceKind.WORK -> "Work"
        PlaceKind.AIRPORT -> "Airport"
        PlaceKind.GYM -> "Gym"
        else -> "Place"
    }

    private fun dismissedKeys(context: Context): Set<String> =
        PredictCrypto.prefs(context).getStringSet(DISMISSED_KEY, emptySet()) ?: emptySet()

    private fun savePending(context: Context, list: List<Suggestion>) {
        val arr = JSONArray()
        list.takeLast(6).forEach { s ->
            arr.put(JSONObject().apply {
                put("key", s.key); put("kind", s.kind.name)
                put("lat", s.lat); put("lng", s.lng)
                put("reason", s.reason); put("ts", s.ts)
            })
        }
        PredictCrypto.prefs(context).edit().putString(SUGGESTIONS_KEY, arr.toString()).apply()
    }

    private fun Double.round4(): Double = Math.round(this * 10_000.0) / 10_000.0
}
