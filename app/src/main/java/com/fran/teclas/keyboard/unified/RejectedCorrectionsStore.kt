package com.fran.teclas.keyboard.unified

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent "never make that correction again" memory. When the user backspaces an autocorrect
 * (the strongest negative signal a keyboard gets), the (typed → corrected) pair is recorded here
 * and every layer — AutocorrectCore's apply step AND UnifiedRanker's candidate scoring — refuses
 * it from then on. Previously this memory was per-session and per-keyboard; now it survives
 * restarts and is shared through the "teclas" prefs so both keyboards respect the same rejections.
 *
 * Capped: each typed word keeps at most a handful of rejected fixes, and the table keeps at most
 * [MAX_ENTRIES] typed words (oldest dropped first — insertion order is preserved in the JSON).
 */
class RejectedCorrectionsStore(private val prefs: SharedPreferences) {

    private val map = LinkedHashMap<String, MutableSet<String>>()
    private var loaded = false

    fun contains(typed: String, corrected: String): Boolean = synchronized(map) {
        ensureLoaded()
        map[typed.lowercase()]?.contains(corrected.lowercase()) == true
    }

    fun add(typed: String, corrected: String) {
        val t = typed.lowercase(); val c = corrected.lowercase()
        if (t.isEmpty() || c.isEmpty()) return
        synchronized(map) {
            ensureLoaded()
            val set = map.getOrPut(t) { LinkedHashSet() }
            if (!set.add(c)) return
            while (set.size > MAX_PER_WORD) set.remove(set.first())
            while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
            persistLocked()
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            val json = JSONObject(prefs.getString(KEY, "{}") ?: "{}")
            json.keys().forEach { k ->
                val arr = json.optJSONArray(k) ?: return@forEach
                val set = LinkedHashSet<String>()
                for (i in 0 until arr.length()) set.add(arr.optString(i))
                map[k] = set
            }
        }
    }

    private fun persistLocked() {
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k, JSONArray(v.toList())) }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private companion object {
        private const val KEY = "rejected_corrections"
        private const val MAX_PER_WORD = 4
        private const val MAX_ENTRIES = 200
    }
}
