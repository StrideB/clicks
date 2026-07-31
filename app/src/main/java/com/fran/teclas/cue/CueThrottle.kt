package com.fran.teclas.cue

/**
 * Result cache and request governor for Cue search.
 *
 * Two jobs, both about not talking to the network more than necessary:
 *
 *  **Cache.** Typing "stride" means five queries, four of which are prefixes of
 *  the fifth, and backspacing replays them. An LRU keyed on the query answers
 *  repeats instantly. It is deliberately small — this is PHI held in memory,
 *  not a database — and it is dropped on screen-off, sign-out and clinic switch
 *  along with everything else.
 *
 *  **Governor.** A launcher search box can fire on every keystroke, and a bug
 *  anywhere in the render path can turn that into an unbounded loop against the
 *  API. Three independent brakes, each of which alone is enough to stop a
 *  runaway:
 *
 *   - a minimum gap between calls, so nothing bursts
 *   - a rolling per-minute ceiling, so nothing sustains
 *   - an exponential circuit breaker, so a failing endpoint is retried at a
 *     decaying rate instead of hammered
 *
 * Nothing here is persisted. All state is per-process and per-signed-in-session.
 */
internal object CueThrottle {

    /** Cached queries. Small on purpose: these hold patient names. */
    private const val MAX_ENTRIES = 24

    /** How long a cached result stays good. Long enough to cover typing, short
     *  enough that a clock-in shows up in the set almost immediately. */
    private const val TTL_MS = 60_000L

    /** Floor between two network calls, whatever the caller asks for. */
    private const val MIN_GAP_MS = 250L

    /** Rolling ceiling. Well above real typing, far below a runaway loop. */
    private const val MAX_PER_MINUTE = 40
    private const val WINDOW_MS = 60_000L

    /** Circuit breaker: first backoff, and the ceiling it doubles toward. */
    private const val BACKOFF_START_MS = 2_000L
    private const val BACKOFF_MAX_MS = 5 * 60_000L

    private class Entry(val results: CueResults, val storedAt: Long)

    private val cache = object : LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_ENTRIES
    }

    private val callTimestamps = ArrayDeque<Long>()

    @Volatile private var lastCallAt = 0L
    @Volatile private var consecutiveFailures = 0
    @Volatile private var openUntil = 0L

    /** A cached result for [query], or null when absent or stale. */
    @Synchronized
    fun cached(query: String): CueResults? {
        val entry = cache[query] ?: return null
        if (System.currentTimeMillis() - entry.storedAt > TTL_MS) {
            cache.remove(query)
            return null
        }
        return entry.results
    }

    /**
     * Whether a network call for [query] is allowed right now.
     *
     * Answers false while the breaker is open, inside the minimum gap, or over
     * the per-minute ceiling. Callers show whatever they have rather than
     * queueing — a delayed answer to a query the user has already moved past is
     * worth nothing.
     */
    @Synchronized
    fun allow(now: Long = System.currentTimeMillis()): Boolean {
        if (now < openUntil) return false
        if (now - lastCallAt < MIN_GAP_MS) return false

        while (callTimestamps.isNotEmpty() && now - callTimestamps.first() > WINDOW_MS) {
            callTimestamps.removeFirst()
        }
        if (callTimestamps.size >= MAX_PER_MINUTE) return false

        lastCallAt = now
        callTimestamps.addLast(now)
        return true
    }

    /** Record a successful call and store its result. Closes the breaker. */
    @Synchronized
    fun succeeded(query: String, results: CueResults) {
        consecutiveFailures = 0
        openUntil = 0L
        // Errors and loading states are not answers; caching them would pin a
        // transient failure in front of the user for a full TTL.
        if (results.error == null && !results.loading) {
            cache[query] = Entry(results, System.currentTimeMillis())
        }
    }

    /** Record a failure and open the breaker for an exponentially longer window. */
    @Synchronized
    fun failed() {
        consecutiveFailures += 1
        val backoff = (BACKOFF_START_MS shl (consecutiveFailures - 1).coerceAtMost(8))
            .coerceAtMost(BACKOFF_MAX_MS)
        openUntil = System.currentTimeMillis() + backoff
    }

    /** Seconds until the breaker closes, for telling the user why it is quiet. */
    @Synchronized
    fun backoffSecondsRemaining(): Long {
        val remaining = openUntil - System.currentTimeMillis()
        return if (remaining <= 0) 0 else (remaining + 999) / 1000
    }

    @Synchronized
    fun isCircuitOpen(): Boolean = System.currentTimeMillis() < openUntil

    /** Forget everything — cached names included. */
    @Synchronized
    fun clear() {
        cache.clear()
        callTimestamps.clear()
        lastCallAt = 0L
        consecutiveFailures = 0
        openUntil = 0L
    }
}
