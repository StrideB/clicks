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
    // Effectively "don't forget": commitments are tiny text, so we keep a long history. A recorded
    // commitment stays recallable; recency just decides ordering, not deletion.
    private const val MAX = 500

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
        // Temporal words are recency signals, not content — handled below, not matched as terms.
        "today", "tonight", "now", "week", "this", "recent", "latest", "currently",
    )

    /**
     * Rank stored commitments against [query] by content overlap (person + task) AND recency, so a
     * fresh "what do I need to do with kelly today?" surfaces the recent Kelly item — not a stale one
     * — while still finding old commitments when nothing recent matches. Keyword-based (works with no
     * model loaded); a semantic index could layer on later. Nothing is deleted; recency only orders.
     */
    fun search(prefs: SharedPreferences, query: String): List<Commitment> {
        val lower = query.lowercase()
        val wantsRecent = listOf("today", "tonight", "now", "this week", "latest", "recent", "currently")
            .any { lower.contains(it) }
        val terms = lower.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in STOP }
        if (terms.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        return all(prefs)
            .mapNotNull { c ->
                val hay = "${c.person} ${c.text}".lowercase()
                val overlap = terms.count { hay.contains(it) }
                if (overlap == 0) return@mapNotNull null
                val ageHours = (now - c.whenMs) / 3_600_000f
                // Recency ranks recent matches above stale ones with the same content overlap. A
                // "today"/"now" query strongly favours the last ~2 days; older still shows if it's
                // the only match ("unless specified").
                val recency = when {
                    ageHours < 24f -> 2f
                    ageHours < 24f * 7 -> 1f
                    else -> 0f
                } + if (wantsRecent && ageHours < 48f) 3f else 0f
                c to (overlap * 2f + recency)
            }
            .sortedWith(compareByDescending<Pair<Commitment, Float>> { it.second }.thenByDescending { it.first.whenMs })
            .map { it.first }
            .take(5)
    }
}
