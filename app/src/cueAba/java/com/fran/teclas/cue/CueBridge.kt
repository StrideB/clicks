package com.fran.teclas.cue

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import com.fran.teclas.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The single entry point the launcher's shared code touches.
 *
 * A matching no-op object exists in the `consumer` source set, so call sites in
 * MainActivity / SearchResultsHost compile identically in both flavors and a
 * consumer APK contains none of this.
 *
 * Concurrency: searches run on one background thread, results land on the main
 * thread. A monotonically increasing request id means a slow response for an
 * older query can never overwrite a newer one.
 */
internal object CueBridge {

    /** Compile-time truth. `consumer` returns false and gets dead-code stripped. */
    const val ENABLED = true

    /** Free text needs a longer stem than a type noun before it earns a request. */
    private const val MIN_FREE_TEXT_LENGTH = 3
    private const val DEBOUNCE_MS = 280L

    private const val SETTINGS_PREFS = "cue_settings"
    private const val KEY_PHI_BLUR = "phi_blur"

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "cue-search").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    private val main = Handler(Looper.getMainLooper())
    private val requestId = AtomicLong(0L)

    @Volatile private var cached: CueResults = CueResults.NONE
    @Volatile private var inFlightQuery: String? = null
    @Volatile private var primed = false

    /**
     * Snapshot of whether a session exists.
     *
     * Read on every keystroke, so it must never touch disk: resolving it for
     * real opens EncryptedSharedPreferences, which unlocks a Keystore key and
     * can cost tens of milliseconds on first use. In a launcher whose entire
     * premise is typing latency, that is not a cost the main thread can pay per
     * character. It is computed once on the worker and refreshed on sign-in and
     * sign-out; until then it reads false and Cue simply stays out of the way.
     */
    @Volatile private var signedIn = false

    private var pending: Runnable? = null

    fun isSignedIn(context: Context): Boolean {
        prime(context)
        return signedIn
    }

    fun isConfigured(): Boolean = CueSession.isConfigured

    fun identity(context: Context): CueIdentity? = CueSession.identity(context)

    /**
     * True when this query names a Cue record type. The router uses it to keep a
     * clinic query off the web — typing "auths" must never reach a search engine.
     */
    fun claimsQuery(context: Context, query: String): Boolean {
        prime(context)
        if (!signedIn) return false
        return CueVocabulary.typeFor(query) != null
    }

    /**
     * Results for [query]. Returns immediately with whatever is cached — possibly
     * a slightly stale set for a refinement of the same query — and schedules a
     * fetch when one is warranted. [onUpdate] fires on the main thread once new
     * results land.
     */
    fun results(context: Context, query: String, onUpdate: () -> Unit): CueResults {
        val trimmed = query.trim()
        prime(context)
        if (trimmed.length < 2) {
            cancelPending()
            return CueResults.NONE
        }

        val namesType = CueVocabulary.typeFor(trimmed) != null

        if (!signedIn) {
            cancelPending()
            // Offer the way in, but only when the query was clearly aimed at Cue.
            val invited = namesType || trimmed.equals("cue", ignoreCase = true)
            return if (invited && isConfigured()) CueResults.signInPrompt(trimmed) else CueResults.NONE
        }

        if (!namesType && trimmed.length < MIN_FREE_TEXT_LENGTH) {
            cancelPending()
            return CueResults.NONE
        }

        val current = cached
        if (current.query == trimmed && !current.loading) return current

        schedule(context, trimmed, immediate = namesType, onUpdate = onUpdate)

        // Keep a refinement of the same query on screen while the sharper one
        // loads, rather than blanking the surface on every keystroke. Diverging
        // queries drop immediately so a stale card never sits under a new query.
        return if (stillRelevant(current.query, trimmed)) current else CueResults.loading(trimmed)
    }

    /**
     * Render Cue results as launcher-native views. Empty list means nothing to
     * show. The PHI blur is read here rather than passed in, so the shared
     * search surface stays unaware that PHI is a concept.
     */
    fun views(activity: MainActivity, results: CueResults): List<View> =
        CueCardViews.build(activity, results, blurPhi = isPhiBlurred(activity))

    /**
     * Whether patient-identifying text is masked on screen. A launcher search
     * overlay is the most shoulder-surfable surface on the phone, so this is a
     * real setting rather than a debug flag.
     */
    fun isPhiBlurred(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_PHI_BLUR, false)

    fun setPhiBlurred(context: Context, blurred: Boolean) {
        context.applicationContext
            .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PHI_BLUR, blurred).apply()
    }

    fun openSignIn(context: Context) {
        context.startActivity(
            Intent(context, CueSignInActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun signOut(context: Context) {
        CueSession.signOut(context)
        signedIn = false
        cached = CueResults.NONE
        cancelPending()
    }

    /** Called after sign-in so the snapshot picks up the new session immediately. */
    fun onSessionChanged(context: Context) {
        val app = context.applicationContext
        worker.execute {
            signedIn = CueSession.isSignedIn(app)
            CueSession.identity(app)
        }
    }

    /** Drop everything held in memory. Called when the launcher locks or backgrounds. */
    fun clearCache() {
        cached = CueResults.NONE
        cancelPending()
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun schedule(context: Context, query: String, immediate: Boolean, onUpdate: () -> Unit) {
        if (inFlightQuery == query) return
        cancelPending()

        val id = requestId.incrementAndGet()
        val app = context.applicationContext
        val task = Runnable {
            inFlightQuery = query
            worker.execute {
                val results = runCatching { CueApi.search(app, query) }
                    .getOrElse { CueResults.error(query, "Cue search failed") }
                main.post {
                    inFlightQuery = null
                    // A newer keystroke already superseded this request.
                    if (id != requestId.get()) return@post
                    cached = results
                    onUpdate()
                }
            }
        }

        if (immediate) {
            task.run()
        } else {
            pending = task
            main.postDelayed(task, DEBOUNCE_MS)
        }
    }

    private fun cancelPending() {
        pending?.let { main.removeCallbacks(it) }
        pending = null
    }

    /** Same rule the launcher's Brave cards use: keep a card across a refinement. */
    private fun stillRelevant(cachedQuery: String, live: String): Boolean {
        if (cachedQuery.isBlank() || live.isBlank()) return false
        return cachedQuery == live || live.startsWith(cachedQuery) || cachedQuery.startsWith(live)
    }

    /**
     * One-time warm-up, safe to call from the main thread on every keystroke.
     * The only main-thread work is loading the noun table from ordinary prefs
     * (no PHI, no Keystore); the session check, identity fetch and vocabulary
     * sync all happen on the worker.
     */
    private fun prime(context: Context) {
        if (primed) return
        primed = true
        val app = context.applicationContext
        CueVocabulary.load(app)
        worker.execute {
            signedIn = CueSession.isSignedIn(app)
            if (signedIn) {
                CueSession.identity(app)
                if (CueVocabulary.isStale(app)) runCatching { CueVocabulary.sync(app) }
            }
        }
    }
}
