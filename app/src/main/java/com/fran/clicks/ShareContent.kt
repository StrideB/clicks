package com.fran.clicks

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.location.Geocoder
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import java.util.Locale

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
        val component = ComponentName(context, ClicksNotificationListener::class.java)
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
