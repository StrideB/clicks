package com.fran.teclas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class SpotifyTrack(
    val id: String,
    val name: String,
    val artist: String,
    val album: String,
    val albumArtUrl: String?,
    val durationMs: Long,
    val uri: String = "spotify:track:$id",
    val popularity: Int = 0
)

data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val ownerName: String,
    val trackCount: Int,
    val imageUrl: String?,
    val uri: String = "spotify:playlist:$id"
)

data class SpotifyPlaylistTracksResult(
    val tracks: List<SpotifyTrack>,
    val total: Int = tracks.size,
    val skipped: Int = 0,
    val error: SpotifyApiError? = null
)

data class SpotifyApiError(
    val statusCode: Int,
    val userMessage: String,
    val apiMessage: String = ""
)

data class SpotifyPlaybackState(
    val track: SpotifyTrack,
    val isPlaying: Boolean,
    val progressMs: Long,
    val deviceName: String?,
    val shuffleOn: Boolean,
    val repeatMode: String
)

class SpotifyWebApi(private val auth: SpotifyAuth) {

    // ── Playback state ───────────────────────────────────────────────────────

    suspend fun getCurrentPlayback(): SpotifyPlaybackState? = withContext(Dispatchers.IO) {
        val token = auth.getValidToken() ?: return@withContext null
        runCatching {
            val conn = get("https://api.spotify.com/v1/me/player?additional_types=track", token)
                ?: return@withContext null
            if (conn.responseCode == 204) return@withContext null
            if (conn.responseCode !in 200..299) return@withContext null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            parsePlayback(json)
        }.getOrNull()
    }

    private fun parsePlayback(json: JSONObject): SpotifyPlaybackState? {
        val item = json.optJSONObject("item") ?: return null
        val artists = item.optJSONArray("artists")
        val artistName = artists.toStringList { it.optString("name") }.joinToString(", ")
        val album = item.optJSONObject("album")
        val artUrl = album?.optJSONArray("images")?.optJSONObject(0)?.optString("url")
        val device = json.optJSONObject("device")
        return SpotifyPlaybackState(
            track = SpotifyTrack(
                id = item.optString("id"),
                name = item.optString("name"),
                artist = artistName,
                album = album?.optString("name") ?: "",
                albumArtUrl = artUrl,
                durationMs = item.optLong("duration_ms"),
                popularity = item.optInt("popularity")
            ),
            isPlaying = json.optBoolean("is_playing"),
            progressMs = json.optLong("progress_ms"),
            deviceName = device?.optString("name"),
            shuffleOn = json.optBoolean("shuffle_state"),
            repeatMode = json.optString("repeat_state", "off")
        )
    }

    // ── Transport controls ───────────────────────────────────────────────────

    suspend fun play() = command("PUT", "https://api.spotify.com/v1/me/player/play")
    suspend fun pause() = command("PUT", "https://api.spotify.com/v1/me/player/pause")
    suspend fun skipToNext() = command("POST", "https://api.spotify.com/v1/me/player/next")
    suspend fun skipToPrevious() = command("POST", "https://api.spotify.com/v1/me/player/previous")

    suspend fun playTrack(trackUri: String): Boolean = withContext(Dispatchers.IO) {
        val token = auth.getValidToken() ?: return@withContext false
        commandWithBody("PUT", "https://api.spotify.com/v1/me/player/play", token,
            "{\"uris\":[\"$trackUri\"]}")
    }

    suspend fun playContext(contextUri: String, offsetIndex: Int = 0): Boolean = withContext(Dispatchers.IO) {
        val token = auth.getValidToken() ?: return@withContext false
        commandWithBody("PUT", "https://api.spotify.com/v1/me/player/play", token,
            "{\"context_uri\":\"$contextUri\",\"offset\":{\"position\":$offsetIndex}}")
    }

    suspend fun seekTo(positionMs: Long) = withContext(Dispatchers.IO) {
        val token = auth.getValidToken() ?: return@withContext
        command("PUT", "https://api.spotify.com/v1/me/player/seek?position_ms=$positionMs", token)
    }

    suspend fun setShuffle(enabled: Boolean) = withContext(Dispatchers.IO) {
        val token = auth.getValidToken() ?: return@withContext
        command("PUT", "https://api.spotify.com/v1/me/player/shuffle?state=$enabled", token)
    }

    // ── Album art ────────────────────────────────────────────────────────────

    suspend fun fetchAlbumArt(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.connect()
            // Spotify serves up to 640x640; thumbnails render far smaller. Downsampling at decode
            // time cuts each cached bitmap from ~1.6 MB to ~400 KB before it ever hits the heap.
            val bytes = conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / 320)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    // ── Recently played ──────────────────────────────────────────────────────

    suspend fun getRecentlyPlayed(limit: Int = 50): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val token = auth.getValidToken() ?: return@withContext emptyList()
        runCatching {
            val conn = get("https://api.spotify.com/v1/me/player/recently-played?limit=$limit", token)
                ?: return@withContext emptyList()
            if (conn.responseCode !in 200..299) { conn.disconnect(); return@withContext emptyList() }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            val items = json.optJSONArray("items") ?: return@withContext emptyList()
            val seen = mutableSetOf<String>()
            items.toList().mapNotNull { obj ->
                val track = obj.optJSONObject("track") ?: return@mapNotNull null
                val id = track.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (!seen.add(id)) return@mapNotNull null
                parseTrack(track)
            }
        }.getOrDefault(emptyList())
    }

    // ── Top tracks (most played) ─────────────────────────────────────────────

    suspend fun getTopTracks(limit: Int = 50, timeRange: String = "long_term"): List<SpotifyTrack> =
        withContext(Dispatchers.IO) {
            val token = auth.getValidToken() ?: return@withContext emptyList()
            runCatching {
                val conn = get(
                    "https://api.spotify.com/v1/me/top/tracks?limit=$limit&time_range=$timeRange",
                    token
                ) ?: return@withContext emptyList()
                if (conn.responseCode !in 200..299) { conn.disconnect(); return@withContext emptyList() }
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                val items = json.optJSONArray("items") ?: return@withContext emptyList()
                items.toList().mapNotNull { parseTrack(it) }
            }.getOrDefault(emptyList())
        }

    // ── Liked songs ──────────────────────────────────────────────────────────

    suspend fun getLikedSongs(limit: Int = 50): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val token = auth.getValidToken() ?: return@withContext emptyList()
        runCatching {
            val conn = get("https://api.spotify.com/v1/me/tracks?limit=$limit", token)
                ?: return@withContext emptyList()
            if (conn.responseCode !in 200..299) { conn.disconnect(); return@withContext emptyList() }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            val items = json.optJSONArray("items") ?: return@withContext emptyList()
            items.toList().mapNotNull { obj ->
                val track = obj.optJSONObject("track") ?: return@mapNotNull null
                parseTrack(track)
            }
        }.getOrDefault(emptyList())
    }

    // ── Playlists ────────────────────────────────────────────────────────────

    suspend fun getPlaylists(limit: Int = 50): List<SpotifyPlaylist> = withContext(Dispatchers.IO) {
        val token = auth.getValidToken() ?: return@withContext emptyList()
        runCatching {
            val conn = get("https://api.spotify.com/v1/me/playlists?limit=$limit", token)
                ?: return@withContext emptyList()
            if (conn.responseCode !in 200..299) { conn.disconnect(); return@withContext emptyList() }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            val items = json.optJSONArray("items") ?: return@withContext emptyList()
            items.toList().mapNotNull { p ->
                val id = p.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SpotifyPlaylist(
                    id = id,
                    name = p.optString("name"),
                    ownerName = p.optJSONObject("owner")?.optString("display_name") ?: "",
                    trackCount = p.optJSONObject("tracks")?.optInt("total") ?: 0,
                    imageUrl = p.optJSONArray("images")?.optJSONObject(0)?.optString("url"),
                    uri = p.optString("uri").takeIf { it.isNotBlank() } ?: "spotify:playlist:$id"
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun getPlaylistTracks(playlistId: String, limit: Int = 100): List<SpotifyTrack> =
        getPlaylistTracksResult(playlistId, limit).tracks

    suspend fun getPlaylistTracksResult(playlistId: String, limit: Int = 100): SpotifyPlaylistTracksResult =
        withContext(Dispatchers.IO) {
            val token = auth.getValidToken() ?: return@withContext SpotifyPlaylistTracksResult(
                tracks = emptyList(),
                error = SpotifyApiError(401, "Reconnect Spotify to refresh your library access.")
            )
            runCatching {
                val tracks = mutableListOf<SpotifyTrack>()
                var skipped = 0
                var total = 0
                var offset = 0
                val requested = limit.coerceIn(1, 300)
                val encodedId = URLEncoder.encode(playlistId, "UTF-8")
                while (tracks.size < requested) {
                    val pageLimit = minOf(100, requested - tracks.size)
                    val url = "https://api.spotify.com/v1/playlists/$encodedId/items" +
                        "?limit=$pageLimit&offset=$offset"
                    val conn = get(url, token) ?: return@withContext SpotifyPlaylistTracksResult(
                        tracks = tracks,
                        total = total,
                        skipped = skipped,
                        error = SpotifyApiError(0, "Couldn't reach Spotify. Check your connection and try again.")
                    )
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        val body = conn.readBody()
                        val apiMsg = spotifyApiMessage(body)
                        val msg = spotifyErrorMessage(code, apiMsg)
                        conn.disconnect()
                        return@withContext SpotifyPlaylistTracksResult(
                            tracks = tracks,
                            total = total,
                            skipped = skipped,
                            error = SpotifyApiError(code, msg, apiMsg)
                        )
                    }
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    conn.disconnect()
                    total = json.optInt("total", total)
                    val items = json.optJSONArray("items") ?: break
                    if (items.length() == 0) break
                    items.toList().forEach { obj ->
                        val track = obj.optJSONObject("track") ?: obj.optJSONObject("item")
                        val parsed = track?.takeIf { it.optString("type", "track") == "track" }?.let { parseTrack(it) }
                        if (parsed != null) tracks.add(parsed) else skipped += 1
                    }
                    offset += items.length()
                    if (offset >= total || json.optString("next").isBlank() || tracks.size >= requested) break
                }
                SpotifyPlaylistTracksResult(tracks = tracks, total = total, skipped = skipped)
            }.getOrElse {
                SpotifyPlaylistTracksResult(
                    tracks = emptyList(),
                    error = SpotifyApiError(0, "Spotify couldn't load this playlist. Try again in a moment.")
                )
            }
        }

    // ── Search ───────────────────────────────────────────────────────────────

    suspend fun search(query: String, limit: Int = 12): List<SpotifyTrack> = withContext(Dispatchers.IO) {
        val token = auth.getValidToken() ?: return@withContext emptyList()
        runCatching {
            val q = java.net.URLEncoder.encode(query, "UTF-8")
            val conn = get("https://api.spotify.com/v1/search?q=$q&type=track&limit=$limit", token)
                ?: return@withContext emptyList()
            if (conn.responseCode !in 200..299) { conn.disconnect(); return@withContext emptyList() }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            val items = json.optJSONObject("tracks")?.optJSONArray("items") ?: return@withContext emptyList()
            items.toList().mapNotNull { parseTrack(it) }
        }.getOrDefault(emptyList())
    }

    // ── Parsers ──────────────────────────────────────────────────────────────

    private fun parseTrack(t: JSONObject): SpotifyTrack? {
        val uri = t.optString("uri").takeIf { it.isNotBlank() }
        val id = t.optString("id").takeIf { it.isNotBlank() } ?: uri?.takeIf { it.startsWith("spotify:local:") } ?: return null
        val artists = t.optJSONArray("artists")
        val artistName = artists.toStringList { it.optString("name") }.joinToString(", ")
        val album = t.optJSONObject("album")
        val artUrl = album?.optJSONArray("images")?.optJSONObject(0)?.optString("url")
            ?.takeIf { it.isNotBlank() }
        return SpotifyTrack(
            id = id,
            name = t.optString("name"),
            artist = artistName,
            album = album?.optString("name") ?: "",
            albumArtUrl = artUrl,
            durationMs = t.optLong("duration_ms"),
            uri = uri ?: "spotify:track:$id",
            popularity = t.optInt("popularity")
        )
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private suspend fun command(method: String, url: String) = withContext(Dispatchers.IO) {
        val token = auth.getValidToken() ?: return@withContext
        command(method, url, token)
    }

    private fun command(method: String, url: String, token: String) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Length", "0")
                if (method == "PUT" || method == "POST") doOutput = true
                connectTimeout = 8000; readTimeout = 8000
            }
            conn.responseCode
            conn.disconnect()
        }
    }

    private fun commandWithBody(method: String, url: String, token: String, body: String): Boolean {
        return runCatching {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Content-Length", bytes.size.toString())
                doOutput = true; connectTimeout = 8000; readTimeout = 8000
            }
            conn.outputStream.use { it.write(bytes) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        }.getOrDefault(false)
    }

    private fun get(url: String, token: String): HttpURLConnection? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 10000; readTimeout = 10000
            connect()
        }
    }.getOrNull()

    private fun HttpURLConnection.readBody(): String =
        runCatching { (errorStream ?: inputStream)?.bufferedReader()?.readText().orEmpty() }.getOrDefault("")

    private fun spotifyApiMessage(body: String): String =
        runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()

    private fun spotifyErrorMessage(code: Int, apiMessage: String): String {
        return when (code) {
            401 -> "Spotify says this session expired. Reconnect once more."
            403 -> apiMessage.ifBlank { "Spotify denied playlist track access for this account." }
            404 -> "Spotify can't find this playlist anymore."
            429 -> "Spotify is rate-limiting requests. Try again shortly."
            else -> apiMessage.ifBlank { "Spotify couldn't load this playlist. Try again." }
        }
    }

    // ── JSONArray helpers ────────────────────────────────────────────────────

    private fun JSONArray?.toList(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun JSONArray?.toStringList(extract: (JSONObject) -> String): List<String> =
        toList().map(extract).filter { it.isNotBlank() }
}
