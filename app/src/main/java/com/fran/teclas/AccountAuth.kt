package com.fran.teclas

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * "Sign in with Google" for identity only — so users get AI features WITHOUT ever handling an API
 * key. We do a PKCE OAuth with the minimal `openid email profile` scopes and keep the resulting
 * Google **ID token**; the app sends it to the Teclas AI proxy (Cloudflare Worker), which verifies it
 * and calls Gemini with the server-held key. See server/gemini-proxy/README.md.
 *
 * Reuses the same reversed-scheme OAuth client as Gmail (so the ID token's `aud` matches the Worker's
 * GOOGLE_CLIENT_ID), just with a distinct redirect path so callbacks route here, not to Gmail.
 */

// The OAuth client the ID token is minted for. Reuses the Gmail iOS client by default; the Worker's
// GOOGLE_CLIENT_ID secret MUST equal this value (that's the audience it checks).
val ACCOUNT_CLIENT_ID: String get() = GMAIL_CLIENT_ID

private const val ACCOUNT_SCOPES = "openid email profile"
private val ACCOUNT_REDIRECT_SCHEME: String get() = GMAIL_REDIRECT_SCHEME
private val ACCOUNT_REDIRECT_URI: String get() = "$ACCOUNT_REDIRECT_SCHEME:/accountredirect"

/** Proxy endpoint config. The URL is NOT a secret (safe to ship); paste your deployed Worker URL. */
object GeminiProxy {
    const val URL_PREF = "gemini_proxy_url"
    const val ACCOUNT_MODE_PREF = "ai_account_mode"   // true = use the account proxy, not a local key
    // Your deployed Worker, e.g. https://teclas-gemini.<subdomain>.workers.dev . Overridable via pref.
    const val DEFAULT_PROXY_URL = ""

    fun url(prefs: android.content.SharedPreferences): String =
        prefs.getString(URL_PREF, null)?.trim().orEmpty().ifBlank { DEFAULT_PROXY_URL }

    fun accountMode(prefs: android.content.SharedPreferences): Boolean =
        prefs.getBoolean(ACCOUNT_MODE_PREF, false)

    /** Build the GeminiClient proxy binding for [context], or null when account mode isn't set up. */
    fun binding(context: Context): GeminiClient.Proxy? {
        val prefs = context.getSharedPreferences("teclas", Context.MODE_PRIVATE)
        if (!accountMode(prefs)) return null
        val u = url(prefs)
        if (u.isBlank()) return null
        val auth = AccountAuth(context.applicationContext)
        if (!auth.isSignedIn) return null
        return GeminiClient.Proxy(u) { auth.blockingIdToken() }
    }
}

class AccountAuth(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("account_auth", Context.MODE_PRIVATE)

    // Persisted (not instance fields): the sign-in may START in one activity (IME settings) and the
    // OAuth callback LANDS in another (MainActivity, which owns the redirect scheme), so a fresh
    // AccountAuth instance there must still find the PKCE verifier/state.
    private var pendingVerifier: String?
        get() = prefs.getString("pending_verifier", null)
        set(v) { prefs.edit().putString("pending_verifier", v).apply() }
    private var pendingState: String?
        get() = prefs.getString("pending_state", null)
        set(v) { prefs.edit().putString("pending_state", v).apply() }

    val isSignedIn: Boolean get() = prefs.contains("id_token")
    val email: String? get() = prefs.getString("email", null)

    fun isConfigured(): Boolean = !ACCOUNT_CLIENT_ID.startsWith("YOUR_")

    fun startSignIn(activity: Activity) {
        if (!isConfigured()) {
            android.widget.Toast.makeText(
                activity, "Add your Google OAuth client ID (GmailAuth.kt) first", android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        val verifier = randomBase64(32)
        pendingVerifier = verifier
        pendingState = randomBase64(16)
        val authUrl = Uri.Builder()
            .scheme("https").authority("accounts.google.com").path("/o/oauth2/v2/auth")
            .appendQueryParameter("client_id", ACCOUNT_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", ACCOUNT_REDIRECT_URI)
            .appendQueryParameter("scope", ACCOUNT_SCOPES)
            .appendQueryParameter("state", pendingState)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", sha256Base64(verifier))
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .build()
        CustomTabsIntent.Builder().build().launchUrl(activity, authUrl)
    }

    /** Distinct from Gmail's callback by the redirect PATH, so both can share the scheme. */
    fun isCallback(uri: Uri): Boolean =
        uri.scheme == ACCOUNT_REDIRECT_SCHEME && uri.path == "/accountredirect"

    /** Blocking — call off the main thread. Exchanges the auth code for tokens. */
    fun handleCallback(uri: Uri): Boolean {
        if (uri.getQueryParameter("error") != null) return false
        val code = uri.getQueryParameter("code") ?: return false
        if (uri.getQueryParameter("state") != pendingState) return false
        val verifier = pendingVerifier ?: return false
        val body = "grant_type=authorization_code&code=${Uri.encode(code)}" +
            "&redirect_uri=${Uri.encode(ACCOUNT_REDIRECT_URI)}&client_id=$ACCOUNT_CLIENT_ID&code_verifier=$verifier"
        val json = post("https://oauth2.googleapis.com/token", body) ?: return false
        saveTokens(json)
        return true
    }

    /** A currently-valid Google ID token, refreshing synchronously if it's near expiry. Blocking. */
    fun blockingIdToken(): String? {
        val token = prefs.getString("id_token", null) ?: return null
        if (System.currentTimeMillis() < prefs.getLong("expires_at", 0L) - 60_000L) return token
        return refresh()
    }

    fun signOut() {
        prefs.edit().clear().apply()
        pendingVerifier = null; pendingState = null
    }

    private fun refresh(): String? {
        val rt = prefs.getString("refresh_token", null) ?: run { signOut(); return null }
        val body = "grant_type=refresh_token&refresh_token=${Uri.encode(rt)}&client_id=$ACCOUNT_CLIENT_ID"
        val json = post("https://oauth2.googleapis.com/token", body) ?: return null
        saveTokens(json)
        return prefs.getString("id_token", null)
    }

    private fun saveTokens(json: JSONObject) {
        val editor = prefs.edit()
        json.optString("id_token").takeIf { it.isNotBlank() }?.let { editor.putString("id_token", it) }
        json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { editor.putString("refresh_token", it) }
        editor.putLong("expires_at", System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000L)
        decodeEmail(json.optString("id_token"))?.let { editor.putString("email", it) }
        editor.apply()
    }

    /** Best-effort read of the `email` claim from the ID token's payload (for display only). */
    private fun decodeEmail(idToken: String): String? = runCatching {
        val payload = idToken.split(".").getOrNull(1) ?: return null
        val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        JSONObject(String(bytes)).optString("email").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun randomBase64(bytes: Int): String {
        val buf = ByteArray(bytes); SecureRandom().nextBytes(buf)
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun sha256Base64(input: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(d, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun post(urlStr: String, body: String): JSONObject? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return runCatching {
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) return null
            JSONObject(conn.inputStream.bufferedReader().readText())
        }.getOrNull().also { conn.disconnect() }
    }
}
