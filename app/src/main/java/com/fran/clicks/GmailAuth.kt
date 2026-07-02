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
// Gmail setup (one-time):
//   1. https://console.cloud.google.com → create a project.
//   2. APIs & Services → Library → enable "Gmail API".
//   3. OAuth consent screen → External → add your Google account under
//      "Test users" (keeps you in Testing mode so the restricted gmail.readonly
//      scope works for your own account without Google's full verification).
//   4. Credentials → Create credentials → OAuth client ID → Application type: iOS.
//      (The iOS type gives a reversed-domain custom-scheme redirect that works in
//       a Custom Tab; no client secret, PKCE only.)
//      Bundle ID can be anything, e.g. com.fran.clicks.
//   5. Copy the client ID into GMAIL_CLIENT_ID below, then update the matching
//      <data android:scheme="..."> line in AndroidManifest.xml to the reversed
//      scheme (com.googleusercontent.apps.<SUFFIX>).
// ─────────────────────────────────────────────────────────────────────────────
const val GMAIL_CLIENT_ID = "YOUR_IOS_CLIENT_ID.apps.googleusercontent.com"

// gmail.readonly is enough to search + read messages and fetch attachments.
private const val GMAIL_SCOPES = "https://www.googleapis.com/auth/gmail.readonly"

// Reversed-client-id custom scheme, e.g. com.googleusercontent.apps.1234-abcd
val GMAIL_REDIRECT_SCHEME: String
    get() = "com.googleusercontent.apps." + GMAIL_CLIENT_ID.substringBefore(".apps.googleusercontent.com")
val GMAIL_REDIRECT_URI: String
    get() = "$GMAIL_REDIRECT_SCHEME:/oauth2redirect"

enum class GmailConnectState { DISCONNECTED, CONNECTING, CONNECTED }

class GmailAuth(private val context: Context) {
    private val prefs = context.getSharedPreferences("gmail_auth", Context.MODE_PRIVATE)

    private val _connectState = MutableStateFlow(loadPersistedState())
    val connectState: StateFlow<GmailConnectState> = _connectState

    private var pendingVerifier: String? = null
    private var pendingState: String? = null

    val isConnected: Boolean get() = _connectState.value == GmailConnectState.CONNECTED

    fun isConfigured(): Boolean = !GMAIL_CLIENT_ID.startsWith("YOUR_")

    // ── OAuth start ──────────────────────────────────────────────────────────

    fun startOAuth(activity: Activity) {
        if (!isConfigured()) {
            android.widget.Toast.makeText(
                activity,
                "Add your Gmail OAuth client ID to GmailAuth.kt first",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        val verifier = randomBase64(32)
        val challenge = sha256Base64(verifier)
        val state = randomBase64(16)
        pendingVerifier = verifier
        pendingState = state
        _connectState.value = GmailConnectState.CONNECTING

        val authUrl = Uri.Builder()
            .scheme("https").authority("accounts.google.com").path("/o/oauth2/v2/auth")
            .appendQueryParameter("client_id", GMAIL_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", GMAIL_REDIRECT_URI)
            .appendQueryParameter("scope", GMAIL_SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            // access_type=offline + prompt=consent so Google returns a refresh token.
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .build()

        CustomTabsIntent.Builder().build().launchUrl(activity, authUrl)
    }

    // ── Callback handler ─────────────────────────────────────────────────────

    fun isCallback(uri: Uri): Boolean = uri.scheme == GMAIL_REDIRECT_SCHEME

    suspend fun handleCallback(uri: Uri): Boolean {
        if (uri.getQueryParameter("error") != null) {
            _connectState.value = GmailConnectState.DISCONNECTED
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
                append("&redirect_uri=").append(Uri.encode(GMAIL_REDIRECT_URI))
                append("&client_id=").append(GMAIL_CLIENT_ID)
                append("&code_verifier=").append(verifier)
            }
            val json = post("https://oauth2.googleapis.com/token", body) ?: return@withContext false
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
            val body = "grant_type=refresh_token&refresh_token=${Uri.encode(rt)}&client_id=$GMAIL_CLIENT_ID"
            val json = post("https://oauth2.googleapis.com/token", body) ?: return@withContext null
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
        _connectState.value = GmailConnectState.CONNECTED
    }

    fun disconnect() {
        prefs.edit().clear().apply()
        pendingVerifier = null
        pendingState = null
        _connectState.value = GmailConnectState.DISCONNECTED
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun loadPersistedState() =
        if (prefs.contains("access_token")) GmailConnectState.CONNECTED else GmailConnectState.DISCONNECTED

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
