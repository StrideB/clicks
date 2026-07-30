package com.fran.teclas.cue

import android.content.Context
import org.json.JSONObject

/**
 * The record-type nouns the search grammar understands.
 *
 * Kept client-side for one reason: latency. Teclas searches on every keystroke,
 * and typing "auths" must feel instant, so the launcher resolves the noun
 * locally and only then decides whether a network call is worth making. It
 * contains no PHI — just words — so it lives in ordinary SharedPreferences.
 *
 * The server is still the authority. `/api/native/v1/search-vocabulary` is
 * synced in the background and overwrites the built-in table, so adding a noun
 * on the server does not need an APK. BUILT_IN is only the cold-start seed.
 */
internal object CueVocabulary {

    private const val PREFS_NAME = "cue_vocabulary"
    private const val KEY_NOUNS = "nouns_json"
    private const val KEY_SYNCED_AT = "synced_at"
    private const val SYNC_INTERVAL_MS = 24 * 60 * 60 * 1000L

    /** Shortest word we will complete into a noun, so "aut" stays free text. */
    private const val MIN_PREFIX_LENGTH = 4

    private val BUILT_IN: Map<String, String> = buildMap {
        // `nouns(...)`, not `put(...)`: a local `put` here shadows MutableMap.put
        // and recurses into itself.
        fun nouns(type: String, vararg words: String) = words.forEach { this[it] = type }
        nouns("organization", "company", "org", "orgs", "organization", "organizations", "clinic", "practice", "business", "entity")
        nouns("auth", "auth", "auths", "authorization", "authorizations", "authorisation", "authorisations", "reauth", "reauths")
        nouns("kiddo", "kiddo", "kiddos", "kid", "kids", "client", "clients", "patient", "patients", "caseload")
        nouns("staff", "staff", "team", "tech", "techs", "rbt", "rbts", "bcba", "bcbas", "employee", "employees")
        nouns("session", "session", "sessions", "schedule", "appointment", "appointments", "appts", "shift", "shifts", "visit", "visits")
        nouns("claim", "claim", "claims", "billing", "invoice", "invoices")
        nouns("document", "doc", "docs", "document", "documents", "file", "files", "paperwork")
        nouns("incident", "incident", "incidents", "report", "reports")
        nouns("candidate", "candidate", "candidates", "applicant", "applicants", "recruiting", "hiring")
        nouns("task", "task", "tasks", "todo", "todos", "queue")
    }

    @Volatile private var nouns: Map<String, String> = BUILT_IN

    /** The record type a query names, or null when it is free text. */
    fun typeFor(query: String): String? {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        words.forEach { word -> nouns[word]?.let { return it } }
        // A lone half-typed noun still counts: "autho" -> auth.
        if (words.size == 1 && words[0].length >= MIN_PREFIX_LENGTH) {
            nouns.keys.firstOrNull { it.startsWith(words[0]) }?.let { return nouns[it] }
        }
        return null
    }

    private fun normalize(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9\\s'-]+"), " ").trim()

    fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_NOUNS, null) ?: return
        runCatching {
            val json = JSONObject(stored)
            val parsed = mutableMapOf<String, String>()
            json.keys().forEach { noun -> parsed[noun] = json.getString(noun) }
            if (parsed.isNotEmpty()) nouns = parsed
        }
    }

    fun isStale(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return System.currentTimeMillis() - prefs.getLong(KEY_SYNCED_AT, 0L) > SYNC_INTERVAL_MS
    }

    /** Background refresh from the server. Silent on failure — the seed still works. */
    fun sync(context: Context) {
        val payload = CueApi.get(context, "search-vocabulary") ?: return
        val types = payload.optJSONArray("types") ?: return
        val parsed = mutableMapOf<String, String>()
        for (index in 0 until types.length()) {
            val entry = types.optJSONObject(index) ?: continue
            val type = entry.optString("type")
            val list = entry.optJSONArray("nouns") ?: continue
            for (position in 0 until list.length()) {
                list.optString(position).takeIf { it.isNotBlank() }?.let { parsed[it] = type }
            }
        }
        if (parsed.isEmpty()) return

        nouns = parsed
        val json = JSONObject().apply { parsed.forEach { (noun, type) -> put(noun, type) } }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NOUNS, json.toString())
            .putLong(KEY_SYNCED_AT, System.currentTimeMillis())
            .apply()
    }
}
