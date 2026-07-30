package com.fran.teclas.cue

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fran.teclas.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Supabase session for the Cue integration.
 *
 * The launcher signs in as a person, never as a clinic. Cue's `loadContext()`
 * reads `organization_id` off the caller's staff row on every request, so scope
 * is a server fact the client cannot invent. Where someone holds staff rows at
 * more than one clinic, the launcher may *choose among them* — see
 * selectOrganization() — but the server validates that choice against their own
 * memberships and rejects anything else, so the selection can only ever narrow.
 *
 * Persisted: a refresh token, the cached identity, and the chosen organization.
 * Revoke the Supabase session and this launcher loses access on its next
 * refresh, with no second credential to hunt down.
 *
 * Storage is EncryptedSharedPreferences (Keystore-backed) with no plaintext
 * fallback — see prefs() for why this differs from PredictCrypto.
 *
 * Blocking network calls — never call from the main thread.
 */
internal object CueSession {

    private const val PREFS_NAME = "cue_session"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_IDENTITY = "identity_json"
    private const val KEY_ORG = "selected_organization_id"

    /** Refresh a minute early so a long request can't start on a dying token. */
    private const val EXPIRY_SKEW_MS = 60_000L

    @Volatile private var accessToken: String? = null
    @Volatile private var accessTokenExpiresAt: Long = 0L
    @Volatile private var cachedIdentity: CueIdentity? = null

    val isConfigured: Boolean
        get() = BuildConfig.CUE_SUPABASE_URL.isNotBlank() &&
            BuildConfig.CUE_SUPABASE_ANON_KEY.isNotBlank() &&
            BuildConfig.CUE_API_BASE_URL.isNotBlank()

    @Volatile private var cachedPrefs: SharedPreferences? = null

    /**
     * Keystore-backed storage, or null when it is unavailable.
     *
     * PredictCrypto falls back to plain SharedPreferences when the Keystore
     * fails, because losing prediction weights is worse than storing them in the
     * clear. The opposite is true here: a refresh token in cleartext prefs is a
     * standing key to a clinic's PHI. So there is no fallback — if encryption is
     * unavailable the session simply lives in memory for this process and the
     * user signs in again next launch.
     */
    private fun prefs(context: Context): SharedPreferences? {
        cachedPrefs?.let { return it }
        synchronized(this) {
            cachedPrefs?.let { return it }
            val created = runCatching {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.getOrNull()
            cachedPrefs = created
            return created
        }
    }

    fun isSignedIn(context: Context): Boolean {
        if (!isConfigured) return false
        if (!prefs(context)?.getString(KEY_REFRESH, null).isNullOrBlank()) return true
        // No stored token, but this process may still hold a live one from a
        // sign-in that could not be persisted.
        return accessToken != null && System.currentTimeMillis() < accessTokenExpiresAt
    }

    fun identity(context: Context): CueIdentity? {
        cachedIdentity?.let { return it }
        val raw = prefs(context)?.getString(KEY_IDENTITY, null) ?: return null
        return runCatching { parseIdentity(JSONObject(raw)) }.getOrNull()?.also { cachedIdentity = it }
    }

    /** Email + password sign-in. Returns null on success, or a message to show. */
    fun signIn(context: Context, email: String, password: String): String? {
        if (!isConfigured) return "Cue endpoint is not configured in cue.properties."
        val body = JSONObject().put("email", email.trim()).put("password", password)
        val response = postAuth("token?grant_type=password", body)
            ?: return "Could not reach Cue. Check your connection."

        val refresh = response.optString("refresh_token").takeIf { it.isNotBlank() }
            ?: return response.optString("error_description")
                .ifBlank { response.optString("msg") }
                .ifBlank { "Sign-in failed. Check your email and password." }

        prefs(context)?.edit()?.putString(KEY_REFRESH, refresh)?.apply()
        adoptAccessToken(response)
        return loadIdentity(context)
    }

    fun signOut(context: Context) {
        prefs(context)?.edit()?.remove(KEY_REFRESH)?.remove(KEY_IDENTITY)?.remove(KEY_ORG)?.apply()
        accessToken = null
        accessTokenExpiresAt = 0L
        cachedIdentity = null
    }

    /**
     * A usable access token, refreshing if the cached one is spent. Returns null
     * when signed out or when the refresh token was revoked — in which case the
     * stored session is cleared so the UI falls back to sign-in.
     */
    fun accessToken(context: Context): String? {
        if (!isConfigured) return null
        accessToken?.takeIf { System.currentTimeMillis() < accessTokenExpiresAt - EXPIRY_SKEW_MS }?.let { return it }

        val refresh = prefs(context)?.getString(KEY_REFRESH, null) ?: return null
        val response = postAuth("token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh))
            ?: return null   // network blip: keep the stored session, retry next time

        val fresh = response.optString("access_token").takeIf { it.isNotBlank() }
        if (fresh == null) {
            // The server answered and rejected the token — it is genuinely dead.
            signOut(context)
            return null
        }
        adoptAccessToken(response)
        response.optString("refresh_token").takeIf { it.isNotBlank() }?.let {
            prefs(context)?.edit()?.putString(KEY_REFRESH, it)?.apply()
        }
        return accessToken
    }

    /** Fetch and persist identity. Returns null on success, or a message. */
    fun loadIdentity(context: Context): String? {
        val payload = CueApi.get(context, "me") ?: return "Signed in, but Cue did not return your profile."
        val user = payload.optJSONObject("user") ?: return "Cue did not return your profile."
        prefs(context)?.edit()?.putString(KEY_IDENTITY, user.toString())?.apply()
        cachedIdentity = parseIdentity(user)
        return null
    }

    private fun parseIdentity(user: JSONObject): CueIdentity {
        val permissions = mutableSetOf<String>()
        user.optJSONArray("explicit_permissions")?.let { array ->
            for (index in 0 until array.length()) permissions.add(array.optString(index))
        }
        val organizations = mutableListOf<CueOrganization>()
        user.optJSONArray("organizations")?.let { array ->
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                organizations.add(CueOrganization(
                    id = entry.optString("organization_id"),
                    name = entry.optString("organization_name").ifBlank { "Unnamed clinic" },
                    role = entry.optString("role"),
                    active = entry.optBoolean("active", false),
                ))
            }
        }
        return CueIdentity(
            organizationId = user.optString("organization_id"),
            organizationName = user.optString("organization_name").ifBlank { "Your clinic" },
            staffId = user.optString("staff_id"),
            displayName = user.optString("display_name").ifBlank { "Cue user" },
            role = user.optString("role"),
            permissions = permissions,
            organizations = organizations,
            ambiguous = user.optBoolean("organization_ambiguous", false),
        )
    }

    /**
     * The organization this launcher acts in, sent as X-Cue-Organization.
     *
     * Null until the user picks one. Cue validates it against their own staff
     * rows and rejects anything else outright, so a stale selection fails loudly
     * instead of quietly returning another clinic's records.
     */
    fun selectedOrganizationID(context: Context): String? =
        prefs(context)?.getString(KEY_ORG, null)?.takeIf { it.isNotBlank() }

    /** Choose an organization and re-resolve identity under it. */
    fun selectOrganization(context: Context, organizationID: String?): String? {
        prefs(context)?.edit()?.apply {
            if (organizationID.isNullOrBlank()) remove(KEY_ORG) else putString(KEY_ORG, organizationID)
        }?.apply()
        cachedIdentity = null
        return loadIdentity(context)
    }

    private fun adoptAccessToken(response: JSONObject) {
        accessToken = response.optString("access_token").takeIf { it.isNotBlank() }
        val lifetimeSeconds = response.optLong("expires_in", 3600L)
        accessTokenExpiresAt = System.currentTimeMillis() + lifetimeSeconds * 1000L
    }

    /** POST to Supabase GoTrue. Null means the request never completed. */
    private fun postAuth(path: String, body: JSONObject): JSONObject? {
        val url = "${BuildConfig.CUE_SUPABASE_URL.trimEnd('/')}/auth/v1/$path"
        return runCatching {
            (URL(url).openConnection() as HttpURLConnection).run {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", BuildConfig.CUE_SUPABASE_ANON_KEY)
                outputStream.use { it.write(body.toString().toByteArray()) }
                val text = (if (responseCode in 200..299) inputStream else errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                disconnect()
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        }.getOrNull()
    }
}
