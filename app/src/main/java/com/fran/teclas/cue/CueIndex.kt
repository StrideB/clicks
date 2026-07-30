package com.fran.teclas.cue

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A local index of record names, so the first keystrokes answer instantly.
 *
 * Teclas searches on every character. A network round trip per character is not
 * viable, and the 280 ms debounce that makes it survivable is itself a visible
 * delay on a launcher whose entire premise is typing. This closes that gap: the
 * index answers locally and the network only runs to hydrate a full card.
 *
 * **This is PHI.** `label` is a patient's name. Consequences:
 *
 *  - stored through [CueSession]'s Keystore-backed preferences, never plain ones
 *  - dropped on sign-out and on clinic switch, alongside the session
 *  - names only — no diagnosis, no payer, no dates. A leaked index reveals that
 *    someone is a client, not anything about their care.
 *
 * Matching deliberately does NOT rank. It answers one question — "is this
 * string worth asking Cue about?" — and the server does the real ordering.
 */
internal object CueIndex {

    private const val KEY_ENTRIES = "search_index_json"
    private const val KEY_SYNCED_AT = "search_index_synced_at"

    /** Records change slowly; a stale name costs nothing because the card is fetched live. */
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    /** Bound on entries held in memory. Well above any single clinic's caseload. */
    private const val MAX_ENTRIES = 2_000

    internal data class Entry(val type: String, val id: String, val label: String, val code: String) {
        /** Lowercased haystack, precomputed — matching runs on every keystroke. */
        val haystack: String = "${label.lowercase()} ${code.lowercase()}"
    }

    @Volatile private var entries: List<Entry> = emptyList()
    @Volatile private var loaded = false

    /**
     * True when [query] names something this clinic actually has.
     *
     * Used to decide whether free text is worth a request. A miss is not proof
     * of absence — the index may be cold or stale — so callers treat it as
     * "probably nothing" rather than "definitely nothing".
     */
    fun matches(query: String): Boolean {
        val needle = query.trim().lowercase()
        if (needle.length < 2) return false
        val snapshot = entries
        if (snapshot.isEmpty()) return false
        return snapshot.any { it.haystack.contains(needle) }
    }

    /** Record types [query] hits, so a caller can narrow what it asks for. */
    fun typesFor(query: String): Set<String> {
        val needle = query.trim().lowercase()
        if (needle.length < 2) return emptySet()
        return entries.asSequence()
            .filter { it.haystack.contains(needle) }
            .map { it.type }
            .toSet()
    }

    fun isEmpty(): Boolean = entries.isEmpty()

    /** Load the cached index into memory. Cheap; safe to call repeatedly. */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = CueSession.secureString(context, KEY_ENTRIES) ?: return
        entries = runCatching { parse(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun isStale(context: Context): Boolean =
        System.currentTimeMillis() - CueSession.secureLong(context, KEY_SYNCED_AT) > TTL_MS

    /** Fetch and persist. Blocking — call from a worker. Silent on failure. */
    fun sync(context: Context) {
        val payload = CueApi.get(context, "search-index") ?: return
        val array = payload.optJSONArray("entries") ?: return
        val parsed = parse(array)
        if (parsed.isEmpty()) return

        entries = parsed
        loaded = true
        CueSession.putSecureString(context, KEY_ENTRIES, array.toString())
        CueSession.putSecureLong(context, KEY_SYNCED_AT, System.currentTimeMillis())
    }

    /** Forget everything, in memory and at rest. Called on sign-out and clinic switch. */
    fun clear(context: Context) {
        entries = emptyList()
        loaded = false
        CueSession.removeSecure(context, KEY_ENTRIES, KEY_SYNCED_AT)
    }

    private fun parse(array: JSONArray): List<Entry> {
        val out = ArrayList<Entry>(minOf(array.length(), MAX_ENTRIES))
        for (index in 0 until minOf(array.length(), MAX_ENTRIES)) {
            val json: JSONObject = array.optJSONObject(index) ?: continue
            val label = json.optString("label")
            if (label.isBlank()) continue
            out.add(Entry(
                type = json.optString("type"),
                id = json.optString("id"),
                label = label,
                code = json.optString("code"),
            ))
        }
        return out
    }
}
