package com.fran.teclas

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ─────────────────────────────────────────────────────────────────────────────
// Setup (one-time, takes ~2 minutes):
//   1. Go to https://www.last.fm/api/account/create → Create an application
//   2. Copy the API Key and Shared Secret into the constants below
// ─────────────────────────────────────────────────────────────────────────────
const val LASTFM_API_KEY = "75a3678c7ac3f9462f33c820dd1075e4"
const val LASTFM_SHARED_SECRET = "d01e63add885a10d6ae6f7ce008a5756"

enum class LastFmConnectState { DISCONNECTED, CONNECTING, CONNECTED }

class LastFmAuth(private val context: Context) {
    private val prefs = context.getSharedPreferences("lastfm_auth", Context.MODE_PRIVATE)
    private val api = LastFmApi()

    private val _connectState = MutableStateFlow(loadPersistedState())
    val connectState: StateFlow<LastFmConnectState> = _connectState

    val isConnected: Boolean get() = _connectState.value == LastFmConnectState.CONNECTED
    val username: String? get() = prefs.getString("username", null)

    var scrobbleEnabled: Boolean
        get() = prefs.getBoolean("scrobble_enabled", true)
        set(value) = prefs.edit().putBoolean("scrobble_enabled", value).apply()

    fun isConfigured(): Boolean = LASTFM_API_KEY != "YOUR_LASTFM_API_KEY" && LASTFM_SHARED_SECRET != "YOUR_LASTFM_SHARED_SECRET"

    suspend fun login(username: String, password: String): Boolean {
        _connectState.value = LastFmConnectState.CONNECTING
        val session = api.mobileSession(username, password, LASTFM_API_KEY, LASTFM_SHARED_SECRET)
        if (session == null) {
            _connectState.value = LastFmConnectState.DISCONNECTED
            return false
        }
        prefs.edit()
            .putString("username", session.username)
            .putString("session_key", session.sessionKey)
            .apply()
        _connectState.value = LastFmConnectState.CONNECTED
        return true
    }

    fun disconnect() {
        prefs.edit().clear().apply()
        _connectState.value = LastFmConnectState.DISCONNECTED
    }

    suspend fun scrobble(artist: String, track: String): Boolean {
        val sessionKey = prefs.getString("session_key", null) ?: return false
        return api.scrobble(
            artist = artist,
            track = track,
            timestampSec = System.currentTimeMillis() / 1000L,
            sessionKey = sessionKey,
            apiKey = LASTFM_API_KEY,
            sharedSecret = LASTFM_SHARED_SECRET
        )
    }

    suspend fun topTracks(period: String = "overall", limit: Int = 25): List<LastFmTrackPlay> {
        val user = username ?: return emptyList()
        return api.topTracks(user, LASTFM_API_KEY, period, limit)
    }

    private fun loadPersistedState() =
        if (prefs.contains("session_key")) LastFmConnectState.CONNECTED else LastFmConnectState.DISCONNECTED
}
