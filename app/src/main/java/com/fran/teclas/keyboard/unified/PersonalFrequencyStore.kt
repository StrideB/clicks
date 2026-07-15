package com.fran.teclas.keyboard.unified

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * The unified ranker's personal-usage signal: on-device counts of the words THIS user actually
 * commits, so their vocabulary rises in prediction/correction ranking over time. The learning
 * counterpart to the global wordlist — 100% local, capped, and decayed so it tracks who the user
 * is now rather than accumulating forever.
 *
 * Backed by the shared "teclas" prefs, so the IME and the launcher keyboard learn as one.
 * Thread-safe: counts live in a synchronized map loaded once; writes persist asynchronously via
 * [SharedPreferences.Editor.apply]. Reads ([boost]) are called from the background prediction
 * thread on every ranking pass, so they never touch storage.
 */
class PersonalFrequencyStore(private val prefs: SharedPreferences) {

    private val counts = HashMap<String, Int>()
    private var loaded = false

    /** Count one committed word. Call at word boundaries (space/punctuation), never per keystroke. */
    fun noteCommitted(word: String) {
        val w = word.lowercase()
        if (w.length < 2 || !w.all { it in 'a'..'z' }) return
        synchronized(counts) {
            ensureLoaded()
            counts[w] = (counts[w] ?: 0) + 1
            // Decay: when the table is full, halve everything and drop what hits zero. Old habits
            // fade, current vocabulary dominates, and the store can never grow unbounded.
            if (counts.size > MAX_WORDS) {
                val it = counts.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    val halved = e.value / 2
                    if (halved <= 0) it.remove() else e.setValue(halved)
                }
            }
            persistLocked()
        }
    }

    /**
     * Saturating 0..1 boost for [word]: 0 when never used, ~0.5 around [HALF_BOOST_AT] uses,
     * approaching 1 for the user's staple words. Bounded so personal usage can strengthen a real
     * word but never overturn the dictionary outright (a habitual typo still gets corrected).
     */
    fun boost(word: String): Float {
        val n = synchronized(counts) { ensureLoaded(); counts[word.lowercase()] ?: 0 }
        return n.toFloat() / (n + HALF_BOOST_AT)
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            val json = JSONObject(prefs.getString(KEY, "{}") ?: "{}")
            json.keys().forEach { k -> counts[k] = json.optInt(k, 0) }
        }
    }

    private fun persistLocked() {
        val json = JSONObject()
        counts.forEach { (w, n) -> json.put(w, n) }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private companion object {
        private const val KEY = "personal_word_counts"
        private const val MAX_WORDS = 400
        private const val HALF_BOOST_AT = 8f
    }
}
