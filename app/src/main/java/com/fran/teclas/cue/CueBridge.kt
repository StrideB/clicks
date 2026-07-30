package com.fran.teclas.cue

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.fran.teclas.BuildConfig
import com.fran.teclas.MainActivity
import com.fran.teclas.brief.CueSignal
import com.fran.teclas.brief.Launch
import com.fran.teclas.brief.Signal
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The single entry point the launcher's search surface touches.
 *
 * This is the `cue-aba` branch: the integration is simply part of the launcher,
 * with no flavor to select and no flag to set. `main` and the Play Store branch
 * do not carry this package at all, which is what keeps the ABA code out of a
 * consumer build.
 *
 * Concurrency: searches run on one background thread, results land on the main
 * thread. A monotonically increasing request id means a slow response for an
 * older query can never overwrite a newer one.
 */
internal object CueBridge {

    /** Free text needs a longer stem than a type noun before it earns a request. */
    private const val MIN_FREE_TEXT_LENGTH = 3
    private const val DEBOUNCE_MS = 280L

    /** How long a fetched brief stays good. Cue's own brief is a daily artifact. */
    private const val BRIEF_TTL_MS = 15 * 60_000L

    private const val SETTINGS_PREFS = "cue_settings"
    private const val KEY_MASKING = "phi_masking"

    /** How long a tap-to-reveal lasts before masking closes over again. */
    private const val REVEAL_WINDOW_MS = 120_000L

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

    /** Wall-clock deadline for a tap-to-reveal. Never persisted. */
    @Volatile private var revealedUntil = 0L

    /** Mirrors the window flag so we only touch it on an actual transition. */
    @Volatile private var windowSecured = false

    private var pending: Runnable? = null

    /** Last fetched brief, and when. Refreshed in the background, never awaited. */
    @Volatile private var briefItems: List<CueBriefItem> = emptyList()
    @Volatile private var briefFetchedAt = 0L
    @Volatile private var briefInFlight = false

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

        // "cue" is the way back to your own account — sign out, toggle masking.
        if (trimmed.equals("cue", ignoreCase = true)) {
            cancelPending()
            return CueResults.account(trimmed)
        }

        if (!namesType) {
            if (trimmed.length < MIN_FREE_TEXT_LENGTH) {
                cancelPending()
                return CueResults.NONE
            }
            // Free text that matches nothing in the local index is almost
            // certainly not a Cue record — don't spend a request on it. A cold
            // or empty index means "unknown", so we still ask.
            if (!CueIndex.isEmpty() && !CueIndex.matches(trimmed)) {
                cancelPending()
                return CueResults.NONE
            }
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
    fun views(activity: MainActivity, results: CueResults): List<View> {
        val built = CueCardViews.build(activity, results, blurPhi = isPhiBlurred(activity))
        applyWindowSecurity(activity, built.isNotEmpty() && results.cards.any { it.phi })
        return built
    }

    /**
     * Whether patient-identifying text is masked right now.
     *
     * Masking is ON by default. A launcher search overlay is the most
     * shoulder-surfable surface on the phone — the safe default is the one that
     * shows nothing until you ask. Tapping a masked card reveals it for
     * REVEAL_WINDOW_MS, and the reveal is torn down on screen-off, so walking
     * away always re-arms it.
     */
    fun isPhiBlurred(context: Context): Boolean {
        if (!isMaskingEnabled(context)) return false
        return System.currentTimeMillis() >= revealedUntil
    }

    fun isMaskingEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_MASKING, true)

    fun setMaskingEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MASKING, enabled).apply()
        if (enabled) revealedUntil = 0L
    }

    /** Temporarily lift the mask. Expires on its own, and on screen-off. */
    fun revealPhi() {
        revealedUntil = System.currentTimeMillis() + REVEAL_WINDOW_MS
    }

    /**
     * Keep Cue records out of screenshots and the recents thumbnail while they
     * are on screen. Cleared as soon as the surface renders without them, so the
     * rest of the launcher stays screenshot-able.
     */
    fun applyWindowSecurity(activity: MainActivity, secure: Boolean) {
        if (secure == windowSecured) return
        windowSecured = secure
        runCatching {
            if (secure) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    /**
     * Confirm, then perform a state change.
     *
     * Clocking in creates a billable, auditable record, and a homescreen is
     * exactly where a mis-tap happens — so a write is never one tap. The dialog
     * names the record and the consequence, and the request runs off the main
     * thread with the result reported back as a toast.
     *
     * The server re-checks ownership, date and legal transition regardless of
     * what this offers.
     */
    fun confirmAndRun(activity: MainActivity, action: CueAction) {
        val write = action.writes ?: return
        val app = activity.applicationContext

        AlertDialog.Builder(activity)
            .setTitle(action.label)
            .setMessage(consequenceOf(write))
            .setNegativeButton("Cancel", null)
            .setPositiveButton(action.label) { _, _ ->
                Toast.makeText(app, "${action.label}…", Toast.LENGTH_SHORT).show()
                worker.execute {
                    val failure = runCatching { CueApi.post(app, write) }
                        .getOrElse { "Could not reach Cue" }
                    main.post {
                        if (failure == null) {
                            Toast.makeText(app, "${action.label} recorded", Toast.LENGTH_SHORT).show()
                            // The card that offered this is now stale — its EVV
                            // state changed, so drop it and re-query.
                            cached = CueResults.NONE
                            activity.render()
                        } else {
                            Toast.makeText(app, failure, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .show()
    }

    /** Plain-language consequence, so the dialog is not just a second tap. */
    private fun consequenceOf(write: CueWrite): String = when (write.action) {
        "clock_in" -> "This starts EVV for the visit and begins a billable record."
        "clock_out" -> "This ends the visit and closes its EVV window."
        "confirm", "accept" -> "This confirms you are taking the session."
        "decline" -> "This releases the session back to the scheduler."
        else -> "This records a change in Cue."
    }

    /**
     * Today's Cue items for the launcher's brief.
     *
     * Returns whatever was last fetched and refreshes in the background — the
     * brief is assembled on the main thread and cannot wait on a network call.
     */
    fun briefItems(context: Context): List<CueBriefItem> {
        prime(context)
        if (!signedIn) return emptyList()

        val now = System.currentTimeMillis()
        if (now - briefFetchedAt > BRIEF_TTL_MS && !briefInFlight) {
            briefInFlight = true
            val app = context.applicationContext
            worker.execute {
                val fetched = runCatching { CueApi.briefItems(app) }.getOrDefault(emptyList())
                main.post {
                    briefItems = fetched
                    briefFetchedAt = System.currentTimeMillis()
                    briefInFlight = false
                }
            }
        }
        return briefItems
    }

    /**
     * Cue's items as launcher [Signal]s, ready for the Today ranker.
     *
     * Non-blocking by construction: [briefItems] returns the last fetch and
     * refreshes in the background, so an empty list here means "nothing yet",
     * never "waiting on the network".
     */
    fun briefSignals(context: Context): List<Signal> {
        val now = System.currentTimeMillis()
        return briefItems(context).map { item ->
            val open = openIntentFor(item)
            CueSignal(
                id = "cue:${item.id}",
                timestamp = if (item.dueAtMillis > 0) item.dueAtMillis else now,
                title = item.title,
                body = item.body,
                dueAtMillis = item.dueAtMillis,
                actions = if (open != null) listOf(Launch("Open in Cue", open)) else emptyList(),
            )
        }
    }

    /** Where a brief item opens: the Cue app first, then the same record on the web. */
    private fun openIntentFor(item: CueBriefItem): Intent? {
        val target = item.deeplink ?: item.href?.let { href ->
            if (href.startsWith("http")) href else BuildConfig.CUE_API_BASE_URL.trimEnd('/') + href
        } ?: BuildConfig.CUE_API_BASE_URL.takeIf { it.isNotBlank() }
        ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun openSignIn(context: Context) {
        context.startActivity(
            Intent(context, CueSignInActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun signOut(context: Context) {
        val app = context.applicationContext
        // The index holds patient names. It goes with the session, not after it.
        CueIndex.clear(app)
        CueSession.signOut(context)
        signedIn = false
        primed = false
        cached = CueResults.NONE
        cancelPending()
    }

    /** Called after sign-in so the snapshot picks up the new session immediately. */
    fun onSessionChanged(context: Context) {
        val app = context.applicationContext
        worker.execute {
            signedIn = CueSession.isSignedIn(app)
            CueSession.identity(app)
            // A different clinic is a different caseload — re-index rather than
            // matching the previous org's names.
            CueIndex.clear(app)
            if (signedIn) runCatching { CueIndex.sync(app) }
        }
    }

    /** Drop everything held in memory. Called when the launcher locks or backgrounds. */
    fun clearCache() {
        cached = CueResults.NONE
        briefItems = emptyList()
        briefFetchedAt = 0L
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

    /**
     * Re-arm masking and drop cached records when the screen goes off.
     *
     * Registered on the application context, so it lives as long as the process
     * — the launcher has no other lifecycle hook that reliably covers "the phone
     * is now in someone's pocket". ACTION_SCREEN_OFF is a protected system
     * broadcast, hence NOT_EXPORTED on API 33+.
     */
    private fun registerScreenOffTeardown(app: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                revealedUntil = 0L
                cached = CueResults.NONE
                briefItems = emptyList()
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
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
        registerScreenOffTeardown(app)
        worker.execute {
            signedIn = CueSession.isSignedIn(app)
            if (signedIn) {
                CueSession.identity(app)
                CueIndex.load(app)
                if (CueVocabulary.isStale(app)) runCatching { CueVocabulary.sync(app) }
                if (CueIndex.isStale(app)) runCatching { CueIndex.sync(app) }
            }
        }
    }
}
