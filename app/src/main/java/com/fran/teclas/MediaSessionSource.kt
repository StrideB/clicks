package com.fran.teclas

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NowPlayingInfo(
    val title: String,
    val artist: String,
    val album: String,
    val sourcePackage: String,
    val sourceApp: String,
    val appIconColor: Int,
    val appIcon: Bitmap?,
    val albumArt: Bitmap?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val lastUpdateElapsedMs: Long
)

class MediaSessionSource(private val context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listenerComponent = ComponentName(context, TeclasNotificationListener::class.java)
    private var controller: MediaController? = null

    // publish() runs on the main thread for every session callback. Uncached, each call cost two
    // PackageManager binder round trips plus a fresh Bitmap allocation + Canvas draw for the app
    // icon (adaptive icons never take the BitmapDrawable shortcut) — a steady GC/binder drip for
    // the whole listening session. One entry per media app the user actually plays.
    private val labelCache = HashMap<String, String>()
    private val iconCache = HashMap<String, Bitmap?>()

    private val _nowPlaying = MutableStateFlow<NowPlayingInfo?>(null)
    val nowPlaying: StateFlow<NowPlayingInfo?> = _nowPlaying

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        selectController(sessions.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = refreshActiveSessions()
    }

    fun start() {
        // Requires Notification Listener access for TeclasNotificationListener.
        runCatching {
            manager.addOnActiveSessionsChangedListener(activeSessionsListener, listenerComponent, mainHandler)
        }
        refreshActiveSessions()
    }

    fun stop() {
        runCatching { manager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
        controller?.unregisterCallback(controllerCallback)
        controller = null
        _nowPlaying.value = null
    }

    fun refreshActiveSessions() {
        val sessions = runCatching { manager.getActiveSessions(listenerComponent) }.getOrDefault(emptyList())
        selectController(sessions)
    }

    fun togglePlayPause() {
        val current = controller ?: return
        if (_nowPlaying.value?.isPlaying == true) current.transportControls.pause()
        else current.transportControls.play()
    }

    fun skipToPrevious() {
        controller?.transportControls?.skipToPrevious()
    }

    fun skipToNext() {
        controller?.transportControls?.skipToNext()
    }

    fun seekTo(positionMs: Long) {
        controller?.transportControls?.seekTo(positionMs)
    }

    // Routes through the active session so remote playback (e.g. Spotify Connect)
    // is adjusted, not just the local stream. Steps are capped per gesture tick.
    fun adjustVolume(steps: Int) {
        if (steps == 0) return
        val direction = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        val repeats = minOf(kotlin.math.abs(steps), 3)
        val current = controller
        if (current != null) {
            repeat(repeats) { runCatching { current.adjustVolume(direction, 0) } }
        } else {
            val audio = context.getSystemService(AudioManager::class.java) ?: return
            repeat(repeats) { runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) } }
        }
    }

    fun volumeFraction(): Float? {
        controller?.playbackInfo?.let { info ->
            if (info.maxVolume > 0) return info.currentVolume.toFloat() / info.maxVolume
        }
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max else null
    }

    fun openSourceApp() {
        val pkg = _nowPlaying.value?.sourcePackage ?: return
        context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it) }
    }

    private fun selectController(sessions: List<MediaController>) {
        val selected = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.playbackState?.state in activeStates }
            ?: sessions.firstOrNull()
        if (selected?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCallback)
            controller = selected
            controller?.registerCallback(controllerCallback, mainHandler)
        }
        publish()
    }

    private fun publish() {
        val current = controller ?: run {
            _nowPlaying.value = null
            return
        }
        val state = current.playbackState
        val metadata = current.metadata
        if (state == null && metadata == null) {
            _nowPlaying.value = null
            return
        }
        if (state?.state == PlaybackState.STATE_STOPPED || state?.state == PlaybackState.STATE_NONE) {
            _nowPlaying.value = null
            return
        }

        val packageName = current.packageName
        val next = NowPlayingInfo(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.ifBlank { null } ?: "Untitled",
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.ifBlank { null } ?: "Unknown artist",
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.ifBlank { null } ?: "",
            sourcePackage = packageName,
            sourceApp = appLabel(packageName),
            appIconColor = appColor(packageName),
            appIcon = appIconBitmap(packageName),
            albumArt = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            // Skip/buffer transitions still mean "music is going": counting them as not-playing
            // made a track skip flip isPlaying false→true, and each flip rebuilds the home surface
            // downstream — two full view-tree rebuilds per skip.
            isPlaying = state?.state in playingLikeStates,
            positionMs = state?.position?.coerceAtLeast(0L) ?: 0L,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L,
            lastUpdateElapsedMs = state?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime()
        )
        // StateFlow dedups by equals(), but NowPlayingInfo carries Bitmaps (reference equality) and
        // an ever-advancing position, so without this gate every session callback became a distinct
        // emission — and each emission recomposes the home widget stack. Emit only when something
        // user-visible changed, or the position left the extrapolation track (a seek): progress UIs
        // extrapolate from (positionMs, lastUpdateElapsedMs), so steady ticks carry no information.
        val prev = _nowPlaying.value
        if (prev == null || shouldEmit(prev, next)) _nowPlaying.value = next
    }

    private fun shouldEmit(prev: NowPlayingInfo, next: NowPlayingInfo): Boolean {
        if (prev.title != next.title || prev.artist != next.artist || prev.album != next.album ||
            prev.sourcePackage != next.sourcePackage || prev.isPlaying != next.isPlaying ||
            prev.durationMs != next.durationMs ||
            prev.albumArt !== next.albumArt || prev.appIcon !== next.appIcon
        ) return true
        val expected = prev.positionMs +
            if (prev.isPlaying) (next.lastUpdateElapsedMs - prev.lastUpdateElapsedMs).coerceAtLeast(0L) else 0L
        return kotlin.math.abs(next.positionMs - expected) > 1_500L
    }

    private fun appLabel(packageName: String): String = labelCache.getOrPut(packageName) {
        runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName.substringAfterLast('.'))
    }

    private fun appColor(packageName: String): Int = when {
        packageName.contains("spotify", ignoreCase = true) -> 0xFF1ED760.toInt()
        packageName.contains("apple", ignoreCase = true) -> 0xFFFA586A.toInt()
        packageName.contains("youtube", ignoreCase = true) -> 0xFFFF0033.toInt()
        else -> 0xFF57C98A.toInt()
    }

    private fun appIconBitmap(packageName: String): Bitmap? {
        if (iconCache.containsKey(packageName)) return iconCache[packageName]
        val bitmap = runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            if (drawable is BitmapDrawable) return@runCatching drawable.bitmap
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        }.getOrNull()
        iconCache[packageName] = bitmap
        return bitmap
    }

    private companion object {
        private val activeStates = setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS
        )

        /** States that read as "music is going" for the isPlaying flag (see publish). */
        private val playingLikeStates = setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS
        )
    }
}
