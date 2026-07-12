package com.fran.teclas

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Free dictionary lookups (dictionaryapi.dev, Wiktionary-sourced) for bare-word definitions in
 * launcher search. No key and no metering, so it can fire on any word-shaped query without
 * touching the Brave credit — the result renders through the same rich-answer card as Brave's
 * verticals. Blocking; call off the main thread.
 */
object DictionaryApi {

    fun define(word: String): BraveSearchApi.RichAnswer? {
        val clean = word.trim().lowercase()
        if (clean.isBlank() || !clean.all { it.isLetter() }) return null
        val body = get("https://api.dictionaryapi.dev/api/v2/entries/en/${URLEncoder.encode(clean, "UTF-8")}")
            ?: return null
        val entry = runCatching { JSONArray(body).optJSONObject(0) }.getOrNull() ?: return null
        val headword = entry.optString("word").trim().ifBlank { return null }
        val phonetic = entry.optString("phonetic").trim()
        val meaning = entry.optJSONArray("meanings")?.optJSONObject(0) ?: return null
        val partOfSpeech = meaning.optString("partOfSpeech").trim()
        val firstDef = meaning.optJSONArray("definitions")?.optJSONObject(0) ?: return null
        val definition = firstDef.optString("definition").trim().ifBlank { return null }
        val example = firstDef.optString("example").trim()
        return BraveSearchApi.RichAnswer(
            vertical = "definitions",
            headline = headword,
            label = listOf("Definition", partOfSpeech, phonetic).filter { it.isNotBlank() }.joinToString(" · "),
            detail = if (example.isNotBlank()) "$definition\n“$example”" else definition,
            delta = null, deltaUp = true,
            provider = "Wiktionary",
            spark = emptyList(),
            glyph = "Aa",
            url = "https://en.wiktionary.org/wiki/${URLEncoder.encode(headword, "UTF-8")}"
        )
    }

    private fun get(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4_000; readTimeout = 6_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }
}
