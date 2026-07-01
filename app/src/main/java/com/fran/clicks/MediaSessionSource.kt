package com.fran.clicks

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
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
    private val listenerComponent = ComponentName(context, ClicksNotificationListener::class.java)
    private var controller: MediaController? = null

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
        // Requires Notification Listener access for ClicksNotificationListener.
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
        _nowPlaying.value = NowPlayingInfo(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.ifBlank { null } ?: "Untitled",
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.ifBlank { null } ?: "Unknown artist",
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)?.ifBlank { null } ?: "",
            sourcePackage = packageName,
            sourceApp = appLabel(packageName),
            appIconColor = appColor(packageName),
            appIcon = appIconBitmap(packageName),
            albumArt = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            positionMs = state?.position?.coerceAtLeast(0L) ?: 0L,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L,
            lastUpdateElapsedMs = state?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime()
        )
    }

    private fun appLabel(packageName: String): String {
        return runCatching {
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
        return runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            if (drawable is BitmapDrawable) return@runCatching drawable.bitmap
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        }.getOrNull()
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
    }
}
