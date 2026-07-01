package com.fran.clicks

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

// ─────────────────────────────────────────────────────────────────────────────
// Setup (one-time, takes ~2 minutes):
//   1. Go to https://developer.spotify.com/dashboard → Create App
//   2. Set Redirect URI to:  com.fran.clicks://spotify-callback
//   3. Copy your Client ID into SPOTIFY_CLIENT_ID below
// ─────────────────────────────────────────────────────────────────────────────
const val SPOTIFY_CLIENT_ID = "YOUR_SPOTIFY_CLIENT_ID"
const val SPOTIFY_REDIRECT_URI = "com.fran.clicks://spotify-callback"
private const val SPOTIFY_SCOPES =
    "user-read-playback-state user-modify-playback-state user-read-currently-playing"

enum class SpotifyConnectState { DISCONNECTED, CONNECTING, CONNECTED }

class SpotifyAuth(private val context: Context) {
    private val prefs = context.getSharedPreferences("spotify_auth", Context.MODE_PRIVATE)

    private val _connectState = MutableStateFlow(loadPersistedState())
    val connectState: StateFlow<SpotifyConnectState> = _connectState

    private var pendingVerifier: String? = null
    private var pendingState: String? = null

    val isConnected: Boolean get() = _connectState.value == SpotifyConnectState.CONNECTED

    // ── OAuth start ──────────────────────────────────────────────────────────

    fun startOAuth(activity: Activity) {
        if (SPOTIFY_CLIENT_ID == "YOUR_SPOTIFY_CLIENT_ID") {
            android.widget.Toast.makeText(
                activity,
                "Add your Spotify Client ID to SpotifyAuth.kt first",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        val verifier = randomBase64(32)
        val challenge = sha256Base64(verifier)
        val state = randomBase64(16)
        pendingVerifier = verifier
        pendingState = state
        _connectState.value = SpotifyConnectState.CONNECTING

        val authUrl = Uri.Builder()
            .scheme("https").authority("accounts.spotify.com").path("/authorize")
            .appendQueryParameter("client_id", SPOTIFY_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", SPOTIFY_REDIRECT_URI)
            .appendQueryParameter("scope", SPOTIFY_SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build()

        CustomTabsIntent.Builder().build().launchUrl(activity, authUrl)
    }

    // ── Callback handler ─────────────────────────────────────────────────────

    suspend fun handleCallback(uri: Uri): Boolean {
        if (uri.getQueryParameter("error") != null) {
            _connectState.value = SpotifyConnectState.DISCONNECTED
            return false
        }
        val code = uri.getQueryParameter("code") ?: return false
        if (uri.getQueryParameter("state") != pendingState) return false
        val verifier = pendingVerifier ?: return false
        return exchangeCode(code, verifier)
    }

    private suspend fun exchangeCode(code: String, verifier: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildString {
                append("grant_type=authorization_code")
                append("&code=").append(Uri.encode(code))
                append("&redirect_uri=").append(Uri.encode(SPOTIFY_REDIRECT_URI))
                append("&client_id=").append(SPOTIFY_CLIENT_ID)
                append("&code_verifier=").append(verifier)
            }
            val json = post("https://accounts.spotify.com/api/token", body) ?: return@withContext false
            saveTokens(json)
            true
        }.getOrDefault(false)
    }

    // ── Token management ─────────────────────────────────────────────────────

    suspend fun getValidToken(): String? {
        val token = prefs.getString("access_token", null) ?: return null
        val expiresAt = prefs.getLong("expires_at", 0L)
        if (System.currentTimeMillis() < expiresAt - 60_000L) return token
        return refresh()
    }

    private suspend fun refresh(): String? = withContext(Dispatchers.IO) {
        val rt = prefs.getString("refresh_token", null) ?: run { disconnect(); return@withContext null }
        runCatching {
            val body = "grant_type=refresh_token&refresh_token=${Uri.encode(rt)}&client_id=$SPOTIFY_CLIENT_ID"
            val json = post("https://accounts.spotify.com/api/token", body) ?: return@withContext null
            saveTokens(json)
            prefs.getString("access_token", null)
        }.getOrElse { disconnect(); null }
    }

    private fun saveTokens(json: JSONObject) {
        prefs.edit()
            .putString("access_token", json.getString("access_token"))
            .putLong("expires_at", System.currentTimeMillis() + json.getLong("expires_in") * 1000L)
            .also { e -> json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { e.putString("refresh_token", it) } }
            .apply()
        _connectState.value = SpotifyConnectState.CONNECTED
    }

    fun disconnect() {
        prefs.edit().clear().apply()
        pendingVerifier = null
        pendingState = null
        _connectState.value = SpotifyConnectState.DISCONNECTED
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun loadPersistedState() =
        if (prefs.contains("access_token")) SpotifyConnectState.CONNECTED else SpotifyConnectState.DISCONNECTED

    private fun randomBase64(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun sha256Base64(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun post(urlStr: String, body: String): JSONObject? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return runCatching {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) return null
            JSONObject(conn.inputStream.bufferedReader().readText())
        }.getOrNull().also { conn.disconnect() }
    }
}
