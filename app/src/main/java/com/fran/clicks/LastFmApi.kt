package com.fran.clicks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

data class LastFmTrackPlay(
    val artist: String,
    val name: String,
    val playCount: Int
)

data class LastFmSession(
    val username: String,
    val sessionKey: String
)

class LastFmApi {
    suspend fun mobileSession(username: String, password: String, apiKey: String, sharedSecret: String): LastFmSession? =
        withContext(Dispatchers.IO) {
            if (username.isBlank() || password.isBlank() || apiKey.isBlank() || sharedSecret.isBlank()) return@withContext null
            runCatching {
                val params = linkedMapOf(
                    "method" to "auth.getMobileSession",
                    "username" to username.trim(),
                    "password" to password,
                    "api_key" to apiKey.trim()
                )
                val apiSig = sign(params, sharedSecret.trim())
                val body = (params + mapOf("api_sig" to apiSig, "format" to "json"))
                    .entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }
                val conn = (URL("https://ws.audioscrobbler.com/2.0/").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    doOutput = true
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    return@withContext null
                }
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                val session = json.optJSONObject("session") ?: return@withContext null
                val key = session.optString("key").trim()
                val name = session.optString("name").trim().ifBlank { username.trim() }
                if (key.isBlank()) null else LastFmSession(name, key)
            }.getOrNull()
        }

    suspend fun topTracks(username: String, apiKey: String, period: String = "overall", limit: Int = 200): List<LastFmTrackPlay> =
        withContext(Dispatchers.IO) {
            if (username.isBlank() || apiKey.isBlank()) return@withContext emptyList()
            runCatching {
                val url = "https://ws.audioscrobbler.com/2.0/?" +
                    "method=user.gettoptracks" +
                    "&user=${URLEncoder.encode(username.trim(), "UTF-8")}" +
                    "&api_key=${URLEncoder.encode(apiKey.trim(), "UTF-8")}" +
                    "&period=${URLEncoder.encode(period, "UTF-8")}" +
                    "&limit=${limit.coerceIn(1, 1000)}" +
                    "&format=json"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    connect()
                }
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    return@withContext emptyList()
                }
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                val tracks = json.optJSONObject("toptracks")?.optJSONArray("track") ?: return@withContext emptyList()
                buildList {
                    for (i in 0 until tracks.length()) {
                        val item = tracks.optJSONObject(i) ?: continue
                        val name = item.optString("name").trim()
                        val artist = item.optJSONObject("artist")?.optString("name")?.trim().orEmpty()
                        val plays = item.optInt("playcount", 0)
                        if (name.isNotBlank() && artist.isNotBlank() && plays > 0) {
                            add(LastFmTrackPlay(artist, name, plays))
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }

    companion object {
        fun key(artist: String, track: String): String =
            "${normalize(artist)}|${normalize(track)}"

        private fun sign(params: Map<String, String>, sharedSecret: String): String {
            val raw = params.toSortedMap().entries.joinToString("") { it.key + it.value } + sharedSecret
            val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun normalize(raw: String): String {
            return raw
                .lowercase(Locale.US)
                .replace(Regex("\\([^)]*(remaster|remastered|radio edit|edit|version|feat\\.?|featuring)[^)]*\\)"), " ")
                .replace(Regex("\\[[^]]*(remaster|remastered|radio edit|edit|version|feat\\.?|featuring)[^]]*]"), " ")
                .replace(Regex("\\b(feat\\.?|ft\\.?|featuring)\\b.*$"), " ")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")
        }
    }
}
