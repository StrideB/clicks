package com.fran.clicks.predict

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.roundToInt

/** What a saved place *is*, so Spaces can key off it (gym -> Fitness, airport -> Travel...). */
enum class PlaceKind { HOME, WORK, GYM, AIRPORT, OTHER, UNKNOWN }

/**
 * A user-named location: "Home", "Gym", "SFO"... Added manually from the Spaces settings
 * screen (usually "save my current location as..."), matched by distance at snapshot time.
 */
data class Place(
    val id: String,
    val name: String,
    val kind: PlaceKind,
    val lat: Double,
    val lng: Double,
    val radiusM: Float,
)

/**
 * Location clustering for the prediction engine. Two tiers:
 *  - Manual places the user names in settings (home / gym / airport...). These win.
 *  - Auto clusters: coarse ~300m grid cells that accumulate visit counts, so frequently
 *    visited unnamed spots still become stable context features.
 * All coordinates persist only in the encrypted prediction prefs and never leave the device.
 */
object PlaceStore {

    private const val PLACES_KEY = "predict_places"
    private const val AUTO_KEY = "predict_auto_clusters"
    private const val AUTO_MIN_VISITS = 3
    private const val AUTO_CAP = 120

    @Volatile private var placesCache: List<Place>? = null

    fun places(context: Context): List<Place> {
        placesCache?.let { return it }
        val raw = PredictCrypto.prefs(context).getString(PLACES_KEY, "[]") ?: "[]"
        val parsed = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Place(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    kind = runCatching { PlaceKind.valueOf(o.optString("kind", "OTHER")) }
                        .getOrDefault(PlaceKind.OTHER),
                    lat = o.optDouble("lat"),
                    lng = o.optDouble("lng"),
                    radiusM = o.optDouble("radiusM", 250.0).toFloat(),
                )
            }
        }.getOrDefault(emptyList())
        placesCache = parsed
        return parsed
    }

    fun addPlace(context: Context, name: String, kind: PlaceKind, lat: Double, lng: Double, radiusM: Float): Place {
        val place = Place(
            id = "pl_${System.currentTimeMillis().toString(36)}",
            name = name.trim().ifEmpty { kind.name.lowercase().replaceFirstChar { it.uppercase() } },
            kind = kind, lat = lat, lng = lng, radiusM = radiusM.coerceIn(60f, 5000f),
        )
        save(context, places(context) + place)
        return place
    }

    fun updatePlace(context: Context, updated: Place) =
        save(context, places(context).map { if (it.id == updated.id) updated else it })

    fun removePlace(context: Context, id: String) =
        save(context, places(context).filter { it.id != id })

    private fun save(context: Context, list: List<Place>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("kind", p.kind.name)
                put("lat", p.lat); put("lng", p.lng); put("radiusM", p.radiusM.toDouble())
            })
        }
        PredictCrypto.prefs(context).edit().putString(PLACES_KEY, arr.toString()).apply()
        placesCache = list
    }

    /** The manual place covering this fix, or null. Nearest wins when radii overlap. */
    fun placeFor(context: Context, lat: Double, lng: Double): Place? =
        places(context)
            .map { it to distanceM(lat, lng, it.lat, it.lng) }
            .filter { (p, d) -> d <= p.radiusM }
            .minByOrNull { it.second }?.first

    /**
     * Stable cluster id for a fix: manual place id when inside one, else an auto grid
     * cluster once it has enough visits, else "unknown". Also bumps the visit count.
     */
    fun clusterFor(context: Context, location: Location?): Pair<String, PlaceKind> {
        if (location == null) return "unknown" to PlaceKind.UNKNOWN
        val manual = placeFor(context, location.latitude, location.longitude)
        if (manual != null) return manual.id to manual.kind
        val cell = gridCell(location.latitude, location.longitude)
        val visits = bumpAutoVisit(context, cell)
        return if (visits >= AUTO_MIN_VISITS) "auto:$cell" to PlaceKind.OTHER
        else "unknown" to PlaceKind.UNKNOWN
    }

    /** ~300m grid cell key (lat rounded to 0.003°, lng scaled by cos(lat)). */
    private fun gridCell(lat: Double, lng: Double): String {
        val latIdx = (lat / 0.003).roundToInt()
        val lngStep = 0.003 / cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        val lngIdx = (lng / lngStep).roundToInt()
        return "$latIdx,$lngIdx"
    }

    private fun bumpAutoVisit(context: Context, cell: String): Int {
        val prefs = PredictCrypto.prefs(context)
        val obj = runCatching { JSONObject(prefs.getString(AUTO_KEY, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        val next = obj.optInt(cell, 0) + 1
        obj.put(cell, next)
        // Cap the auto-cluster map so a traveling user can't grow it unbounded.
        if (obj.length() > AUTO_CAP) {
            val names = obj.keys().asSequence().toList()
            names.sortedBy { obj.optInt(it, 0) }.take(obj.length() - AUTO_CAP).forEach { obj.remove(it) }
        }
        prefs.edit().putString(AUTO_KEY, obj.toString()).apply()
        return next
    }

    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, out)
        return out[0]
    }

    /** Wipe learned auto clusters (manual places survive). */
    fun resetAutoClusters(context: Context) {
        PredictCrypto.prefs(context).edit().remove(AUTO_KEY).apply()
    }
}
