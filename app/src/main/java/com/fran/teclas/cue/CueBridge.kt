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
import java.lang.ref.WeakReference
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

    /**
     * How long a fetched brief stays good. Cue's brief is a daily artifact, and
     * it is only ever fetched when the Today surface actually asks for it —
     * nothing polls, so a longer window simply means fewer wake-ups.
     */
    private const val BRIEF_TTL_MS = 30 * 60_000L

    /** Quiet period after which the integration stops holding anything open. */
    private const val IDLE_AFTER_MS = 90_000L

    private const val SETTINGS_PREFS = "cue_settings"
    private const val KEY_ENABLED = "cue_enabled"
    private const val KEY_MASKING = "phi_masking"

    /** How long a tap-to-reveal lasts before masking closes over again. */
    private const val REVEAL_WINDOW_MS = 120_000L

    /**
     * Searches only. Kept separate from [background] because it is what the user
     * is waiting on: the index sync decrypts every patient and staff name
     * server-side, and when both shared one single-threaded executor a search
     * typed during startup queued behind that sync and appeared to hang forever.
     */
    private val worker by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "cue-search").apply { isDaemon = true }
        }
    }

    /** Warm-up, vocabulary/index sync, brief refresh. Nothing here is awaited. */
    private val background by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "cue-sync").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val requestId = AtomicLong(0L)

    @Volatile private var cached: CueResults = CueResults.NONE
    @Volatile private var inFlightQuery: String? = null
    /** Process-lifetime: set once, never reset. Registration happens here. */
    @Volatile private var primed = false

    /** In-flight warm-up guard, so keystrokes cannot stack index syncs. */
    private val warmingUp = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Set once the screen-off receiver is registered; never unregistered. */
    @Volatile private var receiverRegistered = false

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
    private var idleTimer: Runnable? = null

    /**
     * The surface to repaint when results land — WEAKLY held.
     *
     * This object outlives every Activity in the process. It used to store the
     * repaint callback as a lambda, which captured MainActivity, and one was
     * retained per queued task, per in-flight request, and in `pending`. Every
     * keystroke added another: leaked Activities, heap dumps, and eventually
     * OutOfMemory. Nothing here may strongly reference an Activity.
     */
    @Volatile private var host: WeakReference<MainActivity>? = null

    /** Unexpected failures this process. Past the threshold, Cue stands down. */
    @Volatile private var faults = 0
    @Volatile private var disabled = false

    private const val MAX_FAULTS = 5

    /** Record an unexpected throwable and disable the integration if it persists. */
    private fun fault() {
        faults += 1
        if (faults >= MAX_FAULTS) {
            disabled = true
            cached = CueResults.NONE
            CueThrottle.clear()
        }
    }

    /** Last fetched brief, and when. Refreshed in the background, never awaited. */
    @Volatile private var briefItems: List<CueBriefItem> = emptyList()
    @Volatile private var briefFetchedAt = 0L
    @Volatile private var briefInFlight = false

    fun isSignedIn(context: Context): Boolean {
        if (!isEnabled(context)) return false
        prime(context)
        return signedIn
    }

    fun isConfigured(): Boolean = CueSession.isConfigured

    /**
     * The signed-in identity, from memory only.
     *
     * Never touches disk. This is read while building settings-search results,
     * i.e. on the main thread on every keystroke, and resolving it for real
     * opens EncryptedSharedPreferences and unlocks a Keystore key. It is primed
     * on the sync thread by prime(); until then it reads null and callers show
     * a neutral state rather than blocking the UI.
     */
    fun identity(context: Context): CueIdentity? = CueSession.cachedIdentity()

    /**
     * True when this query names a Cue record type. The router uses it to keep a
     * clinic query off the web — typing "auths" must never reach a search engine.
     */
    fun claimsQuery(context: Context, query: String): Boolean {
        if (disabled || !isEnabled(context)) return false
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
    /**
     * Master switch, OFF by default.
     *
     * This integration has crashed and leaked in the launcher process, which is
     * the one process on the phone that must not fail — when the homescreen
     * dies the device is unusable. Until it has earned trust it stays inert
     * unless deliberately switched on, and switching it off must return the
     * launcher to exactly its prior behaviour: no threads, no receiver, no
     * allocation, no code path entered at all.
     */
    fun isEnabled(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) {
            // Give everything back immediately rather than at next launch.
            cached = CueResults.NONE
            briefItems = emptyList()
            briefFetchedAt = 0L
            host = null
            CueThrottle.clear()
            CueIndex.clear(context)
            cancelPending()
        }
    }

    fun results(activity: MainActivity, query: String): CueResults {
        // Off by default: return before touching prefs, threads or the network.
        if (!isEnabled(activity)) return CueResults.NONE
        // A launcher must survive its integrations. If this one keeps throwing,
        // it takes itself out of the search surface for the rest of the process
        // rather than letting the homescreen keep failing.
        if (disabled) return CueResults.NONE
        host = WeakReference(activity)
        val context: Context = activity
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

        // A repeat of something already answered — typing forward and back over
        // the same stem does this constantly — costs nothing.
        CueThrottle.cached(trimmed)?.let {
            cached = it
            return it
        }

        // A shorter query already proved there is nothing here. Narrowing cannot
        // find more, so this costs nothing.
        if (CueThrottle.knownEmpty(trimmed, namesType)) {
            cancelPending()
            return CueResults.NONE
        }

        val current = cached
        if (current.query == trimmed && !current.loading) return current

        schedule(context, trimmed, immediate = namesType)

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
                            repaint()
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
        if (disabled || !isEnabled(context)) return emptyList()
        prime(context)
        if (!signedIn) return emptyList()

        val now = System.currentTimeMillis()
        if (now - briefFetchedAt > BRIEF_TTL_MS && !briefInFlight) {
            briefInFlight = true
            val app = context.applicationContext
            background.execute {
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
        CueThrottle.clear()
        CueSession.signOut(context)
        signedIn = false
        cached = CueResults.NONE
        cancelPending()
        cancelIdleTimer()
    }

    /** Called after sign-in so the snapshot picks up the new session immediately. */
    fun onSessionChanged(context: Context) {
        val app = context.applicationContext
        if (!warmingUp.compareAndSet(false, true)) return
        background.execute {
            try {
                signedIn = CueSession.isSignedIn(app)
                CueSession.identity(app)
                // A different clinic is a different caseload — re-index rather
                // than matching the previous org's names.
                CueIndex.clear(app)
                if (signedIn) runCatching { CueIndex.sync(app) }
            } finally {
                warmingUp.set(false)
            }
        }
    }

    /** Drop everything held in memory. Called when the launcher locks or backgrounds. */
    fun clearCache() {
        cached = CueResults.NONE
        briefItems = emptyList()
        briefFetchedAt = 0L
        CueThrottle.clear()
        cancelPending()
        cancelIdleTimer()
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun schedule(context: Context, query: String, immediate: Boolean) {
        if (inFlightQuery == query) return
        cancelPending()

        val id = requestId.incrementAndGet()
        val app = context.applicationContext
        val task = Runnable {
            // Checked here rather than at call time: by now the debounce has
            // elapsed, so this reflects whether a request is warranted NOW.
            if (!CueThrottle.allow()) {
                val waiting = CueThrottle.backoffSecondsRemaining()
                if (waiting > 0 && id == requestId.get()) {
                    cached = CueResults.error(query, "Cue is not responding — retrying in ${waiting}s")
                    main.post { repaint() }
                }
                return@Runnable
            }
            inFlightQuery = query
            worker.execute {
                val results = runCatching { CueApi.search(app, query) }
                    .getOrElse {
                        fault()
                        CueResults.error(query, "Cue search failed")
                    }
                if (results.error == null) CueThrottle.succeeded(query, results) else CueThrottle.failed()
                main.post {
                    inFlightQuery = null
                    armIdleTimer()
                    // A newer keystroke already superseded this request.
                    if (id != requestId.get()) return@post
                    cached = results
                    repaint()
                }
            }
        }

        // ALWAYS posted, never run inline. results() is called from inside the
        // launcher's render pass; running the task synchronously meant onUpdate
        // could re-enter render() while the previous one was still building its
        // view tree. "Immediate" means "no debounce", not "on this stack".
        pending = task
        main.postDelayed(task, if (immediate) 0L else DEBOUNCE_MS)
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
        if (receiverRegistered) return
        receiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                revealedUntil = 0L
                cached = CueResults.NONE
                briefItems = emptyList()
                // Cached results hold patient names; they go when the screen does.
                CueThrottle.clear()
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

    /**
     * Repaint the search surface, if one is still alive.
     *
     * Silently does nothing when the Activity has gone — a result arriving
     * after the user left is not worth reviving anything for.
     */
    private fun repaint() {
        val activity = host?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        runCatching {
            if (activity.libraryOpen) activity.refreshLibraryContent() else activity.render()
        }
    }

    /**
     * Let the connection go to sleep once searching stops.
     *
     * Re-armed after every search, so it only fires when the user has genuinely
     * moved on. Cached answers survive — a repeat search stays free — but
     * nothing is left in flight and the connection pool is allowed to expire.
     */
    private fun armIdleTimer() {
        cancelIdleTimer()
        val task = Runnable {
            idleTimer = null
            inFlightQuery = null
            CueThrottle.idle()
        }
        idleTimer = task
        main.postDelayed(task, IDLE_AFTER_MS)
    }

    /**
     * Teardown already clears everything the idle timer would clear, so leaving
     * it queued would only wake the main thread 90 seconds later to do nothing.
     */
    private fun cancelIdleTimer() {
        idleTimer?.let { main.removeCallbacks(it) }
        idleTimer = null
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
        registerScreenOffTeardown(app)
        warmUp(app)
    }

    /**
     * Resolve session state and refresh the synced tables, at most once at a time.
     *
     * The guard is not an optimisation. prime() is reached from results(), which
     * runs on every keystroke; when sign-out used to reset `primed`, each
     * character re-entered here and queued another full index fetch — several
     * megabytes allocated, parsed and written, per letter typed. That is how the
     * launcher ended up consuming the device's memory.
     */
    private fun warmUp(app: Context) {
        if (!warmingUp.compareAndSet(false, true)) return
        background.execute {
            try {
                CueVocabulary.load(app)
                signedIn = CueSession.isSignedIn(app)
                if (signedIn) {
                    CueSession.identity(app)
                    CueIndex.load(app)
                    if (CueVocabulary.isStale(app)) runCatching { CueVocabulary.sync(app) }
                    if (CueIndex.isStale(app)) runCatching { CueIndex.sync(app) }
                }
            } catch (throwable: Throwable) {
                fault()
            } finally {
                warmingUp.set(false)
            }
        }
    }
}
