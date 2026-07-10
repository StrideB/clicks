package com.fran.teclas

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
    private const val TAG = "GeminiClient"
    /** User-facing reason the last call failed (rate limit, bad key, no connection), or null on success. */
    @Volatile var lastErrorMessage: String? = null
    const val API_KEY_PREF = "gemini_api_key"
    const val MODEL_PREF = "gemini_model"
    // gemini-flash-latest has its own free-tier quota bucket (per-model limits), so it keeps working
    // when a specific pinned model's daily free quota is exhausted. For a shipped product, enable
    // billing on the key (or use account-mode) — the free tier is too small for real usage.
    const val DEFAULT_MODEL = "gemini-flash-latest"

    /**
     * Account mode: when set, every call routes through the Teclas AI proxy using the signed-in
     * user's Google ID token instead of a device-local API key. Set once at app/IME startup from
     * [GeminiProxy.binding]; null = fall back to the pasted-key path. Process-global so the launcher
     * and IME share it.
     */
    @Volatile
    var proxy: Proxy? = null

    /**
     * On-device Gemini Nano (AICore). When set, every [call] tries Nano FIRST — free, offline,
     * nothing leaves the device — and only falls through to the cloud paths when Nano can't serve
     * (unsupported device, model downloading, inference error). Process-global like [proxy] so the
     * launcher and IME share one engine.
     */
    @Volatile
    var nano: NanoPromptEngine? = null

    class Proxy(val url: String, val idTokenProvider: () -> String?)

    fun apiKey(prefs: SharedPreferences): String = prefs.getString(API_KEY_PREF, null)?.trim().orEmpty()
    fun model(prefs: SharedPreferences): String =
        prefs.getString(MODEL_PREF, DEFAULT_MODEL)?.trim().orEmpty().ifBlank { DEFAULT_MODEL }
    fun configured(prefs: SharedPreferences): Boolean =
        nano?.ready == true || proxy != null || apiKey(prefs).isNotBlank()

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
    fun generate(
        apiKey: String, model: String, prompt: String,
        maxTokens: Int = 512, temperature: Double = 0.2, json: Boolean = false
    ): String? = call(apiKey, model, prompt, maxTokens, temperature, json)

    /** One request → the model's raw text reply, or null. Always disconnects. Blocking. */
    private fun call(
        apiKey: String, model: String, prompt: String, maxTokens: Int, temperature: Double,
        json: Boolean = false
    ): String? {
        // On-device first: Gemini Nano costs nothing and sends nothing. Any failure (device
        // unsupported, model still downloading, inference error) falls through to the cloud paths.
        nano?.generateBlocking(prompt, maxTokens, temperature)?.let { out ->
            lastErrorMessage = null
            return out
        }
        // Prefer the user's own API key when they've set one. The account-mode proxy is only the
        // fallback (no key on device) — trying it first let a stale/empty proxy binding hijack a
        // perfectly good key and fail silently.
        if (apiKey.isBlank()) {
            val p = proxy
            when {
                p == null -> android.util.Log.w(TAG, "AI: no API key set and no account-mode proxy")
                p.idTokenProvider() == null -> android.util.Log.w(TAG, "AI: not signed in (account mode) and no API key")
                p.url.isBlank() -> android.util.Log.w(TAG, "AI: account-mode proxy URL not configured")
                else -> return callProxy(p.url, p.idTokenProvider()!!, model, prompt, maxTokens, temperature)
            }
            return null
        }
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "${URLEncoder.encode(model, "UTF-8")}:generateContent?key=${URLEncoder.encode(apiKey, "UTF-8")}"
        )
        val generationConfig = JSONObject().put("temperature", temperature).put("maxOutputTokens", maxTokens)
        if (json) generationConfig.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", generationConfig)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 6_000; readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json"); doOutput = true
        }
        return try {
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                android.util.Log.w(TAG, "AI: Gemini HTTP $code — ${err?.take(240)}")
                lastErrorMessage = when (code) {
                    429 -> "Gemini free-tier limit hit — wait a moment, or enable billing on your key."
                    400, 403 -> "Gemini rejected your key — check it's valid & has access."
                    in 500..599 -> "Gemini is having issues — try again shortly."
                    else -> "Gemini error $code."
                }
                return null
            }
            lastErrorMessage = null
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(raw)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?.optJSONObject(0)?.optString("text")?.trim()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "AI: Gemini call failed — ${e.javaClass.simpleName}: ${e.message}")
            lastErrorMessage = "No connection to the AI — check your internet."
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Account-mode request → the Teclas AI proxy (Cloudflare Worker), authenticated with the user's
     *  Google ID token. The proxy holds the real Gemini key. Returns the reply text or null. Blocking. */
    private fun callProxy(
        proxyUrl: String, idToken: String, model: String, prompt: String, maxTokens: Int, temperature: Double
    ): String? {
        val endpoint = proxyUrl.trimEnd('/') + "/v1/generate"
        val body = JSONObject()
            .put("model", model).put("prompt", prompt)
            .put("maxTokens", maxTokens).put("temperature", temperature)
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 6_000; readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $idToken")
            doOutput = true
        }
        return try {
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            if (conn.responseCode !in 200..299) return null
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(raw).optString("text").trim().ifBlank { null }
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
