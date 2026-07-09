package com.fran.teclas.predict

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Trigger signals for a Space. Group semantics: every *specified* group must match
 * (AND across groups), values inside a group are alternatives (OR within). Unspecified
 * (empty/null/false) groups are ignored. A space with more matching groups is more
 * specific and wins detection.
 */
data class SpaceTriggers(
    val hourBuckets: Set<Int> = emptySet(),      // ContextSensors.hourBucket values 0..5
    val weekdaysOnly: Boolean? = null,           // true = Mon-Fri, false = weekend only
    val placeKinds: Set<PlaceKind> = emptySet(), // matched against snapshot.placeKind
    val placeIds: Set<String> = emptySet(),      // specific saved places (settings: "at Gym")
    /** OR-alternative inside the place group: matches when far from normal life (trip abroad). */
    val awayFromHome: Boolean = false,
    val driving: Boolean = false,
    val btCar: Boolean = false,
    val headphones: Boolean = false,
    val calendarBusy: Boolean = false,           // in a meeting or one starting soon
)

/** A named context the drawer/dock arrange themselves around. */
data class Space(
    val id: String,
    val name: String,
    val emoji: String,
    val triggers: SpaceTriggers,
    val autoSwitch: Boolean = true,
    val pinned: List<String> = emptyList(),      // packages always surfaced first
    val excluded: List<String> = emptyList(),    // packages never predicted in this space
    val enabled: Boolean = true,
    val priority: Int = 50,                      // tie-break between equally specific matches
    val builtin: Boolean = true,
)

data class SpaceDetection(
    val space: Space,
    /** True when a non-time signal (place / driving / bluetooth / headphones) matched. */
    val strong: Boolean,
    val locked: Boolean,
)

/**
 * Owns the Space list (defaults + user edits from the Spaces settings screen), detects the
 * active Space from a [ContextSnapshot], and handles the manual lock that overrides
 * auto-switching. Config is plain JSON in the "teclas" prefs — it references saved-place ids
 * but never raw coordinates (those live encrypted in [PlaceStore]).
 */
object SpaceManager {

    private const val PREFS_NAME = "teclas"
    private const val CONFIG_KEY = "spaces_config_v1"
    const val LOCK_KEY = "spaces_lock"
    const val AI_LAYER_KEY = "spaces_ai_enabled"

    @Volatile private var cache: List<Space>? = null
    @Volatile private var lastActiveId: String? = null

    fun defaults(): List<Space> = listOf(
        Space("driving", "Driving", "🚗", SpaceTriggers(driving = true), priority = 100),
        Space("fitness", "Fitness", "💪", SpaceTriggers(placeKinds = setOf(PlaceKind.GYM)), priority = 90),
        Space(
            "travel", "Travel", "✈️",
            SpaceTriggers(placeKinds = setOf(PlaceKind.AIRPORT), awayFromHome = true),
            priority = 85,
        ),
        Space(
            "commute", "Commute", "🚇",
            SpaceTriggers(hourBuckets = setOf(1, 2, 4, 5), weekdaysOnly = true, headphones = true),
            priority = 80,
        ),
        Space(
            "work", "Work", "💼",
            SpaceTriggers(placeKinds = setOf(PlaceKind.WORK), weekdaysOnly = true),
            priority = 70,
        ),
        Space("night", "Night", "🌙", SpaceTriggers(hourBuckets = setOf(0)), priority = 60),
        Space("home", "Home", "🏠", SpaceTriggers(placeKinds = setOf(PlaceKind.HOME)), priority = 50),
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun spaces(context: Context): List<Space> {
        cache?.let { return it }
        val raw = prefs(context).getString(CONFIG_KEY, null)
        val stored = raw?.let { runCatching { parse(it) }.getOrNull() }
        val merged = if (stored == null) defaults() else {
            // Keep any built-in spaces added in later app versions that the stored config predates.
            val known = stored.map { it.id }.toSet()
            stored + defaults().filter { it.id !in known }
        }
        cache = merged
        return merged
    }

    fun save(context: Context, list: List<Space>) {
        prefs(context).edit().putString(CONFIG_KEY, serialize(list)).apply()
        cache = list
    }

    fun update(context: Context, space: Space) =
        save(context, spaces(context).map { if (it.id == space.id) space else it })

    fun space(context: Context, id: String): Space? = spaces(context).find { it.id == id }

    /** Manual lock: pin detection to one space (null returns to auto). */
    fun lock(context: Context, spaceId: String?) {
        prefs(context).edit().apply {
            if (spaceId == null) remove(LOCK_KEY) else putString(LOCK_KEY, spaceId)
        }.apply()
    }

    fun lockedSpaceId(context: Context): String? = prefs(context).getString(LOCK_KEY, null)

    fun aiLayerEnabled(context: Context): Boolean = prefs(context).getBoolean(AI_LAYER_KEY, false)

    fun detect(context: Context, snapshot: ContextSnapshot): SpaceDetection {
        val result = detectIn(spaces(context), snapshot, lockedSpaceId(context), lastActiveId)
        lastActiveId = result.space.id
        return result
    }

    /**
     * Pure detection core (no Android dependencies, hence unit-testable): given the full
     * Space list, a snapshot, the manual lock and the previously active id, decide the
     * active Space. Lock wins; else most-specific matching trigger wins with priority as
     * tie-break; else away-from-home prefers Travel; else sticky last / Home.
     */
    fun detectIn(
        allSpaces: List<Space>,
        snapshot: ContextSnapshot,
        lockedId: String?,
        previousId: String?,
    ): SpaceDetection {
        val all = allSpaces.filter { it.enabled }
        lockedId?.let { id ->
            all.find { it.id == id }?.let { return SpaceDetection(it, strong = true, locked = true) }
        }
        var best: Space? = null
        var bestSpecificity = 0
        var bestStrong = false
        for (space in all) {
            if (!space.autoSwitch) continue
            val (specificity, strong) = match(space.triggers, snapshot) ?: continue
            if (specificity > bestSpecificity ||
                (specificity == bestSpecificity && space.priority > (best?.priority ?: -1))
            ) {
                best = space; bestSpecificity = specificity; bestStrong = strong
            }
        }
        // No trigger matched. Being away from home is still a hard fact — falling back to
        // "Home" on a trip abroad is exactly wrong, so away prefers Travel.
        val awayTravel = if (best == null && snapshot.awayFromHome) all.find { it.id == "travel" } else null
        val fallback = best
            ?: awayTravel
            ?: all.find { it.id == previousId }
            ?: all.find { it.id == "home" }
            ?: all.firstOrNull()
            ?: defaults().first { it.id == "home" }
        return SpaceDetection(fallback, strong = (best != null && bestStrong) || awayTravel != null, locked = false)
    }

    /** Null when a specified group fails; otherwise (matched group count, any strong group). */
    private fun match(t: SpaceTriggers, s: ContextSnapshot): Pair<Int, Boolean>? {
        var groups = 0
        var strong = false
        if (t.hourBuckets.isNotEmpty()) {
            if (s.hourBucket !in t.hourBuckets) return null
            groups++
        }
        t.weekdaysOnly?.let {
            if (s.isWeekend == it) return null
            groups++
        }
        if (t.placeKinds.isNotEmpty() || t.placeIds.isNotEmpty() || t.awayFromHome) {
            val hit = s.placeKind in t.placeKinds || s.placeId in t.placeIds ||
                (t.awayFromHome && s.awayFromHome)
            if (!hit) return null
            groups++; strong = true
        }
        if (t.driving) {
            if (!s.driving) return null
            groups++; strong = true
        }
        if (t.btCar) {
            if (s.btDevice != BtDevice.CAR) return null
            groups++; strong = true
        }
        if (t.headphones) {
            if (!s.headphones) return null
            groups++; strong = true
        }
        if (t.calendarBusy) {
            if (s.calendar == CalendarProximity.FREE) return null
            groups++
        }
        if (groups == 0) return null
        return groups to strong
    }

    // --- JSON persistence -------------------------------------------------------------

    private fun serialize(list: List<Space>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("emoji", s.emoji)
                put("autoSwitch", s.autoSwitch); put("enabled", s.enabled)
                put("priority", s.priority); put("builtin", s.builtin)
                put("pinned", JSONArray(s.pinned)); put("excluded", JSONArray(s.excluded))
                put("triggers", JSONObject().apply {
                    put("hours", JSONArray(s.triggers.hourBuckets.toList()))
                    s.triggers.weekdaysOnly?.let { put("weekdaysOnly", it) }
                    put("placeKinds", JSONArray(s.triggers.placeKinds.map { it.name }))
                    put("placeIds", JSONArray(s.triggers.placeIds.toList()))
                    put("awayFromHome", s.triggers.awayFromHome)
                    put("driving", s.triggers.driving); put("btCar", s.triggers.btCar)
                    put("headphones", s.triggers.headphones); put("calendarBusy", s.triggers.calendarBusy)
                })
            })
        }
        return arr.toString()
    }

    private fun parse(raw: String): List<Space> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = o.optJSONObject("triggers") ?: JSONObject()
            Space(
                id = o.optString("id"),
                name = o.optString("name"),
                emoji = o.optString("emoji", "◦"),
                autoSwitch = o.optBoolean("autoSwitch", true),
                enabled = o.optBoolean("enabled", true),
                priority = o.optInt("priority", 50),
                builtin = o.optBoolean("builtin", true),
                pinned = strings(o.optJSONArray("pinned")),
                excluded = strings(o.optJSONArray("excluded")),
                triggers = SpaceTriggers(
                    hourBuckets = ints(t.optJSONArray("hours")).toSet(),
                    weekdaysOnly = if (t.has("weekdaysOnly")) t.optBoolean("weekdaysOnly") else null,
                    placeKinds = strings(t.optJSONArray("placeKinds"))
                        .mapNotNull { runCatching { PlaceKind.valueOf(it) }.getOrNull() }.toSet(),
                    placeIds = strings(t.optJSONArray("placeIds")).toSet(),
                    // Configs saved before the away signal existed lack the key; the built-in
                    // Travel space gains it on load so existing installs get trip detection.
                    awayFromHome = if (t.has("awayFromHome")) t.optBoolean("awayFromHome")
                    else o.optString("id") == "travel" && o.optBoolean("builtin", true),
                    driving = t.optBoolean("driving"),
                    btCar = t.optBoolean("btCar"),
                    headphones = t.optBoolean("headphones"),
                    calendarBusy = t.optBoolean("calendarBusy"),
                ),
            )
        }
    }

    private fun strings(arr: JSONArray?): List<String> =
        if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }

    private fun ints(arr: JSONArray?): List<Int> =
        if (arr == null) emptyList() else (0 until arr.length()).map { arr.optInt(it) }
}
