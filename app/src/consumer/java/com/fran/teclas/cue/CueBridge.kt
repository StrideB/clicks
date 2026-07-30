package com.fran.teclas.cue

import android.content.Context
import android.view.View
import com.fran.teclas.MainActivity

/**
 * No-op Cue bridge for the `consumer` flavor.
 *
 * This exists so shared code in MainActivity / SearchResultsHost can call
 * CueBridge unconditionally and still compile into a launcher that contains no
 * ABA integration at all — no endpoints, no auth, no card renderer, no strings.
 * Nothing is stripped at runtime because nothing was ever compiled in.
 *
 * `ENABLED` is a compile-time constant, so every call site below folds away and
 * R8 drops the whole branch from the release APK.
 *
 * Keep this signature-identical to the cueAba implementation. A mismatch only
 * shows up when building the other flavor, which is easy to miss.
 */
internal object CueBridge {

    const val ENABLED = false

    fun isSignedIn(context: Context): Boolean = false

    fun isConfigured(): Boolean = false

    fun identity(context: Context): CueIdentity? = null

    fun claimsQuery(context: Context, query: String): Boolean = false

    fun results(context: Context, query: String, onUpdate: () -> Unit): CueResults = CueResults.NONE

    fun views(activity: MainActivity, results: CueResults): List<View> = emptyList()

    fun isPhiBlurred(context: Context): Boolean = false

    fun setPhiBlurred(context: Context, blurred: Boolean) = Unit

    fun openSignIn(context: Context) = Unit

    fun signOut(context: Context) = Unit

    fun clearCache() = Unit
}

/** Minimal stand-ins so shared call sites type-check in the consumer flavor. */
internal data class CueIdentity(
    val organizationId: String,
    val staffId: String,
    val displayName: String,
    val role: String,
    val permissions: Set<String>,
)

internal data class CueResults(
    val query: String,
    val cards: List<Unit> = emptyList(),
    val loading: Boolean = false,
) {
    val isEmpty: Boolean get() = true

    companion object {
        val NONE = CueResults("")
    }
}
