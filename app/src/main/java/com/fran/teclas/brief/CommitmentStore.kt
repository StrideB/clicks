package com.fran.teclas.brief

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * A tiny on-device memory of things the user was asked to do or agreed to do, harvested from real
 * conversations. It is deliberately cheap: nothing runs in the background for it — commitments are
 * extracted during the twice-daily brief LLM pass that already happens, so it costs no extra
 * wakeups or battery. The user recalls them by typing a natural question ("what was I supposed to
 * do with ana?") and [search] surfaces the matches; tapping one opens the source thread.
 */
object CommitmentStore {

    private const val KEY = "commitments_v1"
    private const val MAX = 60

    data class Commitment(
        val text: String,     // the task itself ("send the Q1 draft")
        val person: String,   // who it involves ("Ethan")
        val pkg: String,      // source app package (to reopen)
        val key: String,      // source notification key (exact thread when still live)
        val whenMs: Long,
    )

    // Messaging apps only: promos, system, and app-noise notifications never become commitments.
    private val CONVERSATION_PKGS = setOf(
        "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.Slack", "com.facebook.orca", "com.instagram.android",
        "com.google.android.gm", "com.microsoft.office.outlook",
        "com.discord", "com.linkedin.android", "com.google.android.apps.dynamite",
    )

    fun isConversation(pkg: String, kind: String): Boolean =
        pkg in CONVERSATION_PKGS || kind == "email"

    fun all(prefs: SharedPreferences): List<Commitment> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Commitment(o.optString("t"), o.optString("p"), o.optString("k"), o.optString("n"), o.optLong("w"))
        }
    }.getOrDefault(emptyList())

    /** Merge [items] in, newest first, de-duplicated by person+task, capped at [MAX]. */
    fun add(prefs: SharedPreferences, items: List<Commitment>) {
        if (items.isEmpty()) return
        val seen = HashSet<String>()
        val merged = ArrayList<Commitment>(MAX)
        (items + all(prefs)).forEach { c ->
            val id = (c.person + "|" + c.text).lowercase().trim()
            if (c.text.isNotBlank() && seen.add(id)) merged.add(c)
        }
        val capped = merged.take(MAX)
        prefs.edit().putString(KEY, JSONArray(capped.map {
            JSONObject().put("t", it.text).put("p", it.person).put("k", it.pkg).put("n", it.key).put("w", it.whenMs)
        }).toString()).apply()
    }

    fun clear(prefs: SharedPreferences) = prefs.edit().remove(KEY).apply()

    private val STOP = setOf(
        "what", "was", "were", "did", "with", "the", "for", "did", "i", "do", "to", "supposed",
        "have", "had", "am", "is", "are", "my", "me", "about", "need", "should", "again", "and",
        "remind", "tell", "whats", "what's", "any", "todo", "todos",
    )

    /**
     * Rank stored commitments against [query] by token overlap (person + task). Keyword-based so it
     * works with no model loaded; if a semantic index is later available it can layer on top. Returns
     * best matches first, or empty when nothing meaningfully overlaps.
     */
    fun search(prefs: SharedPreferences, query: String): List<Commitment> {
        val terms = query.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in STOP }
        if (terms.isEmpty()) return emptyList()
        return all(prefs)
            .map { c ->
                val hay = "${c.person} ${c.text}".lowercase()
                c to terms.count { hay.contains(it) }
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Commitment, Int>> { it.second }.thenByDescending { it.first.whenMs })
            .map { it.first }
            .take(4)
    }
}
