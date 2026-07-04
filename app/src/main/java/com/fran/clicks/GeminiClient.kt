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
 * stay in parity. Network calls are blocking — call off the main thread.
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
        if (context.isBlank()) return emptyList()
        val prompt = """You are a keyboard autocomplete engine. Given this text the user is typing, reply with exactly 3 next-word predictions as a JSON array of strings. Nothing else — no explanation, just the array.
Text: "$context"
Reply format: ["word1","word2","word3"]"""
        val text = call(apiKey, model, prompt, maxTokens = 24, temperature = 0.2) ?: return emptyList()
        return parseArray(text)
    }

    /** Rewrite/polish [text] into clean, natural prose. Returns null on failure or no-op. Blocking. */
    fun fetchRewrite(apiKey: String, model: String, text: String): String? = fetchTransform(
        apiKey, model, text,
        "Rewrite this message so it reads clearly and naturally, with correct spelling, punctuation and capitalization. Keep the original meaning, tone, and roughly the same length."
    )

    /** Apply an arbitrary [instruction] to [text] (tone shift, shorten, fix, etc.). Blocking. */
    fun fetchTransform(apiKey: String, model: String, text: String, instruction: String): String? {
        if (text.isBlank()) return null
        val prompt = "$instruction Keep the original meaning. Reply with ONLY the rewritten message — no preamble, no quotes, no explanation.\n\n$text"
        val out = call(apiKey, model, prompt, maxTokens = 400, temperature = 0.5) ?: return null
        val cleaned = out.trim().removeSurrounding("\"").trim()
        return cleaned.ifBlank { null }
    }

    /**
     * Writing assist: continue [context] with a short, natural draft to append (compose / smart
     * autocomplete). Returns only the continuation, or null on failure/no-op. Blocking.
     */
    fun fetchCompose(apiKey: String, model: String, context: String): String? {
        if (context.isBlank()) return null
        val prompt = "Continue this text naturally in one short sentence. Reply with ONLY the text to " +
            "append — no preamble, no quotes, no explanation, and do not repeat what is already written.\n\n$context"
        val out = call(apiKey, model, prompt, maxTokens = 80, temperature = 0.7) ?: return null
        val cleaned = out.trim().removeSurrounding("\"").trim()
        return cleaned.ifBlank { null }
    }

    /**
     * Route a free-form command to exactly one skill by name and extract its argument. The model may
     * only pick from [skills] (or NONE), so it ranks — it can't invent an action. Blocking.
     */
    fun fetchSkillMatch(apiKey: String, model: String, query: String, skills: List<String>): Pair<String, String>? {
        if (query.isBlank() || skills.isEmpty()) return null
        val prompt = """You route a phone keyboard command to exactly one skill.
Skills: ${skills.joinToString(", ")}
Command: "$query"
Reply ONLY as compact JSON: {"skill":"<one skill name from the list, or NONE>","arg":"<the search text or target, may be empty>"}"""
        val out = call(apiKey, model, prompt, maxTokens = 60, temperature = 0.0) ?: return null
        val inner = out.substringAfter('{', "").substringBeforeLast('}')
        if (inner.isBlank()) return null
        return runCatching {
            val obj = JSONObject("{$inner}")
            val skill = obj.optString("skill").trim()
            val arg = obj.optString("arg").trim()
            if (skill.isBlank() || skill.equals("NONE", ignoreCase = true)) null else skill to arg
        }.getOrNull()
    }

    /**
     * General-purpose single-shot generation → the model's raw text reply, or null. Public wrapper
     * over [call] for callers (e.g. the Today brief ranker) that build their own prompt and parse
     * their own structured reply. Blocking — call off the main thread.
     */
    fun generate(apiKey: String, model: String, prompt: String, maxTokens: Int = 512, temperature: Double = 0.2): String? =
        call(apiKey, model, prompt, maxTokens, temperature)

    /** One request → the model's raw text reply, or null. Always disconnects. Blocking. */
    private fun call(apiKey: String, model: String, prompt: String, maxTokens: Int, temperature: Double): String? {
        if (apiKey.isBlank()) return null
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "${URLEncoder.encode(model, "UTF-8")}:generateContent?key=${URLEncoder.encode(apiKey, "UTF-8")}"
        )
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", temperature).put("maxOutputTokens", maxTokens))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 6_000; readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json"); doOutput = true
        }
        return try {
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            if (conn.responseCode !in 200..299) return null
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(raw)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?.optJSONObject(0)?.optString("text")?.trim()
        } catch (_: Exception) {
            null
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
