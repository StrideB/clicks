package com.fran.clicks

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
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

    suspend fun playTrack(trackUri: String) = withContext(Dispatchers.IO) {
        val token = auth.getValidToken() ?: return@withContext
        commandWithBody("PUT", "https://api.spotify.com/v1/me/player/play", token,
            "{\"uris\":[\"$trackUri\"]}")
    }

    suspend fun playContext(contextUri: String, offsetIndex: Int = 0) = withContext(Dispatchers.IO) {
        val token = auth.getValidToken() ?: return@withContext
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
            BitmapFactory.decodeStream(conn.inputStream).also { conn.disconnect() }
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
        withContext(Dispatchers.IO) {
            val token = auth.getValidToken() ?: return@withContext emptyList()
            runCatching {
                // No fields filter — fetch all data to avoid encoding issues
                val conn = get(
                    "https://api.spotify.com/v1/playlists/$playlistId/tracks?limit=$limit",
                    token
                ) ?: return@withContext emptyList()
                if (conn.responseCode !in 200..299) { conn.disconnect(); return@withContext emptyList() }
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                val items = json.optJSONArray("items") ?: return@withContext emptyList()
                items.toList().mapNotNull { obj ->
                    val track = obj.optJSONObject("track") ?: return@mapNotNull null
                    if (track.optString("id").isBlank()) return@mapNotNull null
                    parseTrack(track)
                }
            }.getOrDefault(emptyList())
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
        val id = t.optString("id").takeIf { it.isNotBlank() } ?: return null
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
            uri = t.optString("uri").takeIf { it.isNotBlank() } ?: "spotify:track:$id",
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

    private fun commandWithBody(method: String, url: String, token: String, body: String) {
        runCatching {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Content-Length", bytes.size.toString())
                doOutput = true; connectTimeout = 8000; readTimeout = 8000
            }
            conn.outputStream.use { it.write(bytes) }
            conn.responseCode
            conn.disconnect()
        }
    }

    private fun get(url: String, token: String): HttpURLConnection? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 10000; readTimeout = 10000
            connect()
        }
    }.getOrNull()

    // ── JSONArray helpers ────────────────────────────────────────────────────

    private fun JSONArray?.toList(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun JSONArray?.toStringList(extract: (JSONObject) -> String): List<String> =
        toList().map(extract).filter { it.isNotBlank() }
}
