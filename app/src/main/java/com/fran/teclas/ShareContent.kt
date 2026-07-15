package com.fran.teclas

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.location.Geocoder
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Data sources for the share cards: the "drop a complete thing into the conversation" flow behind the
 * go button. Sharing is modeled as *insert into the field* (the user still hits the app's own send) —
 * never auto-send.
 *
 * All lookups here are blocking; call them from a background thread (the engine already does).
 */
object ShareContent {

    // ── now playing ──────────────────────────────────────────────────────────────

    data class SongCard(
        val title: String,
        val artist: String,
        val appLabel: String?,   // "Spotify" — null when resolved from a typed query
        val link: String?,       // song.link universal URL; opens the receiver's own player
        val art: Bitmap?
    ) {
        /** One-line drop for the message field (single line: safe in every input type). */
        val insertText: String
            get() = buildString {
                append("🎧 ").append(title)
                if (artist.isNotBlank()) append(" — ").append(artist)
                link?.let { append(" ").append(it) }
            }
    }

    private data class NowPlayingSnap(val title: String, val artist: String, val appLabel: String?, val art: Bitmap?)

    /** One-shot read of the active media session (needs Notification Listener access, like the launcher card). */
    private fun nowPlayingSnap(context: Context): NowPlayingSnap? = runCatching {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
        val component = ComponentName(context, TeclasNotificationListener::class.java)
        val sessions = manager.getActiveSessions(component)
        val controller = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.metadata != null }
            ?: return null
        val meta = controller.metadata ?: return null
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        if (title.isBlank()) return null
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim()
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.trim().orEmpty()
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val label = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(controller.packageName, 0)).toString()
        }.getOrNull()
        NowPlayingSnap(title, artist.orEmpty(), label, art)
    }.getOrNull()

    /**
     * Resolve a shareable song. Blank [query] → the track playing right now; otherwise search the typed
     * name. The universal link comes from the iTunes catalog id via song.link, so the receiver's tap
     * opens *their* player. Returns null only when there is nothing playing and no query.
     */
    fun resolveSong(context: Context, query: String): SongCard? {
        val q = query.trim()
        if (q.isBlank()) {
            val np = nowPlayingSnap(context) ?: return null
            val track = runCatching {
                kotlinx.coroutines.runBlocking { ItunesMetadata.searchTrack(np.title, np.artist) }
            }.getOrNull()
            // Card is still worth showing without a link (text-only share beats nothing).
            return SongCard(np.title, np.artist, np.appLabel, track?.let { "https://song.link/i/${it.trackId}" }, np.art)
        }
        val track = runCatching {
            kotlinx.coroutines.runBlocking { ItunesMetadata.searchTrack(q, "") }
        }.getOrNull() ?: return null
        val art = runCatching {
            kotlinx.coroutines.runBlocking { ItunesMetadata.fetchArtwork(track.artworkUrl) }
        }.getOrNull()
        return SongCard(track.trackName, track.artistName, null, "https://song.link/i/${track.trackId}", art)
    }

    // ── places ───────────────────────────────────────────────────────────────────

    data class PlaceCard(
        val name: String,        // "Blue Bottle Coffee" or the address when unnamed
        val address: String,
        val lat: Double,
        val lng: Double,
        val isMyLocation: Boolean
    ) {
        val mapsUrl: String
            get() = "https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, lat, lng)
        val insertText: String
            get() = buildString {
                append("📍 ")
                // Skip the name when the address already leads with it ("golden gate bridge" vs
                // "Golden Gate Bridge, …") — no stuttering in the dropped text.
                if (name.isNotBlank() && !address.contains(name, ignoreCase = true)) append(name).append(", ")
                append(address.ifBlank { "%.6f,%.6f".format(Locale.US, lat, lng) }).append(" ").append(mapsUrl)
            }
    }

    /**
     * Static map thumbnail for the location share card. The card used to pass art = null, so the
     * preview had an empty image well — it read as a broken/blank map. Built by compositing OSM
     * tiles (no API key; staticmap.openstreetmap.de is defunct). Network: call off the main thread.
     * Null on any failure, and the card then renders without art rather than an empty frame.
     */
    fun mapThumb(lat: Double, lng: Double, zoom: Int = 15, wPx: Int = 420, hPx: Int = 220): Bitmap? = runCatching {
        val scale = 1 shl zoom
        val latRad = Math.toRadians(lat)
        // Slippy-map projection → global pixel coords at this zoom.
        val gx = (lng + 180.0) / 360.0 * scale * TILE
        val gy = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * scale * TILE
        val left = gx - wPx / 2.0
        val top = gy - hPx / 2.0
        val out = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(0xFFE8E4DC.toInt())   // map-paper base showing through any missing tile
        val tx0 = floor(left / TILE).toInt(); val tx1 = floor((left + wPx) / TILE).toInt()
        val ty0 = floor(top / TILE).toInt();  val ty1 = floor((top + hPx) / TILE).toInt()
        var drew = false
        for (tx in tx0..tx1) for (ty in ty0..ty1) {
            val wrapX = ((tx % scale) + scale) % scale
            if (ty < 0 || ty >= scale) continue
            val tile = fetchTile(zoom, wrapX, ty) ?: continue
            canvas.drawBitmap(tile, (tx * TILE - left).toFloat(), (ty * TILE - top).toFloat(), null)
            tile.recycle()
            drew = true
        }
        if (!drew) return@runCatching null
        // Pin at the exact spot (dead centre).
        val cx = wPx / 2f; val cy = hPx / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x33000000; canvas.drawCircle(cx, cy + 1f, 9f, p)
        p.color = 0xFFE5342A.toInt(); canvas.drawCircle(cx, cy, 7f, p)
        p.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(cx, cy, 2.5f, p)
        out
    }.getOrNull()

    private const val TILE = 256

    private fun fetchTile(z: Int, x: Int, y: Int): Bitmap? = runCatching {
        val conn = (URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 7_000; instanceFollowRedirects = true
            // OSM tile policy requires an identifying UA.
            setRequestProperty("User-Agent", "Teclas-Launcher/1.0 (on-device share card)")
        }
        try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally { conn.disconnect() }
    }.getOrNull()

    /** The user's current spot as a card (last-known fix + reverse geocode). Null without permission/fix. */
    fun myPlace(context: Context): PlaceCard? {
        val loc = AgenticLocation.lastKnown(context) ?: return null
        val address = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault())
                .getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.getAddressLine(0)
        }.getOrNull().orEmpty()
        return PlaceCard(name = "", address = address, lat = loc.latitude, lng = loc.longitude, isMyLocation = true)
    }

    /** Forward-geocode a typed place ("blue bottle sf") into a card, biased near the user when possible. */
    fun findPlace(context: Context, query: String): PlaceCard? {
        val q = query.trim()
        if (q.isBlank()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        val near = AgenticLocation.lastKnown(context)
        val results = runCatching {
            @Suppress("DEPRECATION")
            if (near != null) geocoder.getFromLocationName(
                q, 1,
                near.latitude - 0.7, near.longitude - 0.7, near.latitude + 0.7, near.longitude + 0.7
            ) else geocoder.getFromLocationName(q, 1)
        }.getOrNull()?.firstOrNull()
            // Bias box can miss entirely (place is elsewhere); retry unbounded before giving up.
            ?: runCatching { @Suppress("DEPRECATION") geocoder.getFromLocationName(q, 1) }.getOrNull()?.firstOrNull()
            ?: return null
        val address = results.getAddressLine(0).orEmpty()
        val name = results.featureName?.takeIf { it.isNotBlank() && !address.startsWith(it) } ?: q
        return PlaceCard(name, address, results.latitude, results.longitude, isMyLocation = false)
    }
}
