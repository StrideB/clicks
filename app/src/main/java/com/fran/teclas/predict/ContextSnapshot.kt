package com.fran.teclas.predict

enum class BtDevice { NONE, CAR, HEADPHONES, OTHER }
enum class CalendarProximity { IN_MEETING, MEETING_SOON, FREE }
enum class LaunchSource { DOCK, DRAWER, SEARCH, COMMAND, OTHER }

/**
 * Graduated distance from the user's Home place. `awayFromHome` is a single 300km/timezone flip;
 * these rings also catch regional and same-timezone trips it misses, and let Travel scale its
 * confidence instead of switching on a hard edge.
 */
enum class DistanceBand { HOME, LOCAL, REGIONAL, FAR }

/**
 * One reading of "where/when/how" the user is right now. Feeds both the bandit's feature
 * vector and Space detection. Cheap value object — build via [ContextSensors.snapshot].
 */
data class ContextSnapshot(
    val hourBucket: Int,          // 0 night / 1 earlyAM / 2 AM / 3 midday / 4 PM / 5 evening
    val dayOfWeek: Int,           // 0=Mon .. 6=Sun
    val isWeekend: Boolean,
    val placeId: String,          // manual place id, "auto:<cell>" or "unknown"
    val placeKind: PlaceKind,
    val driving: Boolean,
    val btDevice: BtDevice,
    val headphones: Boolean,      // any wired/BT audio output attached
    val charging: Boolean,
    val mediaPlaying: Boolean,
    val calendar: CalendarProximity,
    val lastApp: String?,         // package opened most recently
    val prevApp: String?,         // package opened before that
    val timestamp: Long,
    /**
     * Far from the user's normal life: the device timezone differs from the usual one,
     * or the fix is 300+ km from the saved Home place. Drives Travel auto-detection.
     */
    val awayFromHome: Boolean = false,
    /** Graduated distance-from-Home ring; catches regional/same-timezone travel awayFromHome misses. */
    val distanceBand: DistanceBand = DistanceBand.HOME,
    /** False when the current cluster is somewhere the user doesn't usually go (unknown dwell). */
    val placeFamiliar: Boolean = true,
    /** A 100km+ hop (a flight) was detected within the last ~18h — you're mid-trip, not commuting. */
    val recentlyFlew: Boolean = false,
    /**
     * Stable hash of the connected Wi-Fi network's SSID, or null when not on Wi-Fi / unreadable.
     * Hashed, never the raw name: it only ever needs to answer "same network as before?", and the
     * launcher shouldn't hold a list of the places you've been in plain text. This is the sharpest
     * home-vs-work signal available and costs no GPS fix.
     */
    val wifiId: String? = null,
) {

    /**
     * Sparse binary feature vector as the set of active feature names; the linear scorer's
     * w·x is then just a sum over these keys. Names are stable — they are the feature index.
     */
    fun features(): List<String> = buildList {
        add("hour:$hourBucket")
        add("dow:$dayOfWeek")
        if (isWeekend) add("weekend")
        add("place:$placeId")
        if (placeKind != PlaceKind.UNKNOWN) add("pkind:${placeKind.name}")
        if (driving) add("driving")
        if (btDevice != BtDevice.NONE) add("bt:${btDevice.name}")
        if (headphones) add("headphones")
        if (charging) add("charging")
        if (mediaPlaying) add("media")
        if (calendar != CalendarProximity.FREE) add("cal:${calendar.name}")
        if (awayFromHome) add("away")
        if (distanceBand != DistanceBand.HOME) add("dband:${distanceBand.name}")
        if (!placeFamiliar) add("unfamiliar")
        wifiId?.let { add("wifi:$it") }
        lastApp?.let { add("last:${it.hashCode()}") }
        if (lastApp != null && prevApp != null) add("bigram:${prevApp.hashCode()}>${lastApp.hashCode()}")
        add("bias")
    }

    /** Coarse bucket key for the frequency prior table (must stay low-cardinality). */
    fun contextKey(): String {
        val day = if (isWeekend) "we" else "wd"
        val motion = if (driving) "drv" else "still"
        // "|away" is appended only when true so every context key learned at home keeps matching.
        val away = if (awayFromHome) "|away" else ""
        // Wi-Fi stands in for the place only when there is no place — indoors with a stale/absent
        // fix, "unknown" otherwise collapses every distinct room into one bucket. Appending it only
        // in that case keeps cardinality flat and leaves existing learned keys matching.
        val place = if (placeId == "unknown" && wifiId != null) "w:$wifiId" else placeId
        return "h$hourBucket|$day|$place|$motion|bt${btDevice.name}$away"
    }
}
