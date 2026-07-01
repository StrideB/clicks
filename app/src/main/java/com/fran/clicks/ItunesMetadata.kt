package com.fran.clicks

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// iTunes Search API — public, no auth required.
// Used to enrich Apple Music metadata (album art, ISRC lookup) when
// the MediaSession doesn't provide artwork directly.
object ItunesMetadata {

    suspend fun searchTrack(title: String, artist: String): ItunesTrack? = withContext(Dispatchers.IO) {
        runCatching {
            val q = java.net.URLEncoder.encode("$title $artist", "UTF-8")
            val url = URL("https://itunes.apple.com/search?term=$q&media=music&limit=1&entity=song")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Clicks-Launcher/1.0")
            if (conn.responseCode != 200) return@withContext null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            val results = json.optJSONArray("results") ?: return@withContext null
            if (results.length() == 0) return@withContext null
            val r = results.getJSONObject(0)
            ItunesTrack(
                trackName = r.optString("trackName"),
                artistName = r.optString("artistName"),
                albumName = r.optString("collectionName"),
                artworkUrl = r.optString("artworkUrl100").replace("100x100", "600x600"),
                durationMs = r.optLong("trackTimeMillis"),
                trackId = r.optLong("trackId")
            )
        }.getOrNull()
    }

    suspend fun fetchArtwork(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connect()
            BitmapFactory.decodeStream(conn.inputStream).also { conn.disconnect() }
        }.getOrNull()
    }
}

data class ItunesTrack(
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val artworkUrl: String,
    val durationMs: Long,
    val trackId: Long
)
