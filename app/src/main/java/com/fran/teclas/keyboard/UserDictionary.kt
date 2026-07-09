package com.fran.teclas.keyboard

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * The user's own words — slang, abbreviations, names — that proofreading must never flag or "fix".
 *
 * A non-dictionary word the user types repeatedly is promoted automatically after [PROMOTE_AT] uses,
 * so their shorthand becomes theirs with no manual "add to dictionary" step. Backed by the shared
 * "teclas" prefs so both keyboards see the same personal vocabulary.
 */
class UserDictionary(private val prefs: SharedPreferences) {

    /** True when [word] is one the user has approved (typed enough, or added explicitly). */
    fun contains(word: String): Boolean = approved().contains(word.lowercase())

    /** Count a typed non-dictionary word toward auto-promotion; promotes past the threshold. */
    fun noteTyped(word: String) {
        val w = word.lowercase()
        if (w.length < 2 || !w.all { it.isLetter() } || contains(w)) return
        val counts = JSONObject(prefs.getString(COUNTS, "{}") ?: "{}")
        val n = counts.optInt(w, 0) + 1
        if (n >= PROMOTE_AT) {
            add(w)
            counts.remove(w)
        } else {
            counts.put(w, n)
        }
        prefs.edit().putString(COUNTS, counts.toString()).apply()
    }

    /** Add [word] to the user's dictionary immediately (e.g. an explicit "keep this word"). */
    fun add(word: String) {
        val w = word.lowercase()
        val set = approved().toMutableSet()
        if (set.add(w)) prefs.edit().putStringSet(APPROVED, set).apply()
    }

    fun all(): Set<String> = approved()

    private fun approved(): Set<String> = prefs.getStringSet(APPROVED, emptySet()) ?: emptySet()

    companion object {
        const val APPROVED = "user_dict_words"
        const val COUNTS = "user_dict_counts"
        const val PROMOTE_AT = 3
    }
}
