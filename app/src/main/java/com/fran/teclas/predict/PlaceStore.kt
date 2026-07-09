package com.fran.teclas.predict

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
     * cluster once it has enough visits, else "unknown". Also bumps the visit count
     * (with an hour-of-day histogram feeding [PlaceInference]).
     */
    fun clusterFor(context: Context, location: Location?): Pair<String, PlaceKind> {
        if (location == null) return "unknown" to PlaceKind.UNKNOWN
        PlaceInference.onFix(context, location)
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

    /** Center coordinates of a grid cell key (inverse of [gridCell]). */
    fun cellCenter(cell: String): Pair<Double, Double>? {
        val parts = cell.split(",")
        if (parts.size != 2) return null
        val latIdx = parts[0].toIntOrNull() ?: return null
        val lngIdx = parts[1].toIntOrNull() ?: return null
        val lat = latIdx * 0.003
        val lngStep = 0.003 / cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        return lat to lngIdx * lngStep
    }

    /** One auto cluster's visit stats. [hist] is 48 buckets: hour 0-23 weekday, 24-47 weekend. */
    data class AutoCluster(val cell: String, val count: Int, val hist: IntArray)

    fun autoClusters(context: Context): List<AutoCluster> {
        val obj = runCatching { JSONObject(PredictCrypto.prefs(context).getString(AUTO_KEY, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        return obj.keys().asSequence().mapNotNull { cell ->
            val entry = obj.optJSONObject(cell)
            if (entry == null) {
                // Legacy plain-count entry from before histograms existed.
                AutoCluster(cell, obj.optInt(cell, 0), IntArray(48))
            } else {
                val arr = entry.optJSONArray("h")
                val hist = IntArray(48) { i -> arr?.optInt(i, 0) ?: 0 }
                AutoCluster(cell, entry.optInt("c", 0), hist)
            }
        }.toList()
    }

    private fun bumpAutoVisit(context: Context, cell: String): Int {
        val prefs = PredictCrypto.prefs(context)
        val obj = runCatching { JSONObject(prefs.getString(AUTO_KEY, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        // Entries are {c: total, h: [48]} — hour 0-23 weekday, 24-47 weekend — so the
        // inference pass can spot overnight/office dwell patterns. Legacy plain ints migrate.
        val entry = obj.optJSONObject(cell) ?: JSONObject().apply {
            put("c", obj.optInt(cell, 0))
            put("h", JSONArray(IntArray(48).toList()))
        }
        val next = entry.optInt("c", 0) + 1
        entry.put("c", next)
        val cal = java.util.Calendar.getInstance()
        val weekend = cal.get(java.util.Calendar.DAY_OF_WEEK).let {
            it == java.util.Calendar.SATURDAY || it == java.util.Calendar.SUNDAY
        }
        val idx = cal.get(java.util.Calendar.HOUR_OF_DAY) + if (weekend) 24 else 0
        val hist = entry.optJSONArray("h") ?: JSONArray(IntArray(48).toList())
        hist.put(idx, hist.optInt(idx, 0) + 1)
        entry.put("h", hist)
        obj.put(cell, entry)
        // Cap the auto-cluster map so a traveling user can't grow it unbounded.
        if (obj.length() > AUTO_CAP) {
            fun countOf(key: String): Int =
                obj.optJSONObject(key)?.optInt("c", 0) ?: obj.optInt(key, 0)
            val names = obj.keys().asSequence().toList()
            names.sortedBy { countOf(it) }.take(obj.length() - AUTO_CAP).forEach { obj.remove(it) }
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
