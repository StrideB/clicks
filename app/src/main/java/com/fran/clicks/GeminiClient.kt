package com.fran.clicks

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Shared, context-agnostic Gemini client used by BOTH the launcher keyboard and the IME so they
 * stay in parity. Network calls are blocking — call [fetchSuggestions] off the main thread.
 */
object GeminiClient {
    const val API_KEY_PREF = "gemini_api_key"
    const val MODEL_PREF = "gemini_model"
    const val DEFAULT_MODEL = "gemini-2.5-flash"

    fun apiKey(prefs: SharedPreferences): String = prefs.getString(API_KEY_PREF, null)?.trim().orEmpty()
    fun model(prefs: SharedPreferences): String =
        prefs.getString(MODEL_PREF, DEFAULT_MODEL)?.trim().orEmpty().ifBlank { DEFAULT_MODEL }
    fun configured(prefs: SharedPreferences): Boolean = apiKey(prefs).isNotBlank()

    /** Up to 3 next-word predictions for [context]. Empty on any failure. Blocking. */
    fun fetchSuggestions(apiKey: String, model: String, context: String): List<String> {
        if (apiKey.isBlank() || context.isBlank()) return emptyList()
        val prompt = """You are a keyboard autocomplete engine. Given this text the user is typing, reply with exactly 3 next-word predictions as a JSON array of strings. Nothing else — no explanation, just the array.
Text: "$context"
Reply format: ["word1","word2","word3"]"""
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "${URLEncoder.encode(model, "UTF-8")}:generateContent?key=${URLEncoder.encode(apiKey, "UTF-8")}"
        )
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 24))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 6_000; readTimeout = 8_000
            setRequestProperty("Content-Type", "application/json"); doOutput = true
        }
        return try {
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            if (conn.responseCode !in 200..299) return emptyList()
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            val text = JSONObject(raw)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?.optJSONObject(0)?.optString("text")?.trim() ?: return emptyList()
            parseArray(text)
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    /** Strict JSON array first, lenient regex fallback (handles fenced / prose-wrapped replies). */
    private fun parseArray(text: String): List<String> {
        runCatching {
            val arr = JSONArray(text)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val v = arr.optString(i).trim()
                if (v.isNotBlank()) out.add(v)
            }
            if (out.isNotEmpty()) return out.take(3)
        }
        return Regex(""""\s*([^"]+)\s*"""").findAll(text)
            .map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.take(3).toList()
    }
}
