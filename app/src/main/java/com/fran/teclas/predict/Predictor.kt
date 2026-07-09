package com.fran.teclas.predict

import android.content.Context
import android.util.Log
import com.fran.teclas.db.AppTransitionEntry
import com.fran.teclas.db.PredictDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.exp
import kotlin.random.Random

/**
 * On-device next-app prediction: a contextual bandit that blends a per-context frequency
 * prior (strong from day one, seeded from the launcher's existing usage counts) with a
 * sparse linear scorer that learns online from every launch. No network, no training loop;
 * one launch = one cheap weight update. State persists encrypted via [PredictCrypto]; the
 * raw transition log lands encrypted in Room ([PredictDatabase]).
 *
 * Blend per app:  final = (1 - alpha) * sigmoid(w·x) + alpha * freqPrior
 * where alpha decays from 0.7 toward 0.15 as a context accumulates observations, and an
 * epsilon-greedy swap keeps exploring while epsilon decays from 0.2 toward 0.03.
 */
object Predictor {

    private const val TAG = "Predictor"
    private const val STATE_KEY = "predict_state_v1"
    private const val SEEDED_KEY = "predict_seeded"
    private const val LR = 0.05f
    private const val L2 = 1e-4f
    private const val LOG_CAP = 4000
    private const val MAX_NEGATIVES = 4
    private const val DAILY_DECAY = 0.98f

    /** Deterministic ranking for tests when set. */
    @Volatile var randomSeed: Long? = null

    /**
     * Resolves a package to its [AppCategory] for cold-start category priors. Set once by
     * the launcher (it owns PackageManager); null falls back to no category boost. See
     * [AppCategories].
     */
    @Volatile var categoryProvider: ((String) -> AppCategory)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ---- learned state (guarded by [lock]) --------------------------------------------
    private val lock = Any()
    private var loaded = false
    private var weights = HashMap<String, HashMap<String, Float>>() // pkg -> feature -> w
    private var freqCtx = HashMap<String, HashMap<String, Float>>() // ctxKey -> pkg -> count
    private var freqSpace = HashMap<String, HashMap<String, Float>>() // spaceId -> pkg -> count
    private var freqGlobal = HashMap<String, Float>()               // pkg -> count
    private var ctxCount = HashMap<String, Float>()                 // ctxKey -> observations
    private var totalLaunches = 0f
    private var lastDecayDay = 0L
    private var recentApps = ArrayDeque<String>()                   // newest first, max 2
    // Last launches per Space (pkg -> newest timestamp), so what you opened this morning in
    // a Space outranks months-old habits there. Capped per space.
    private var recentBySpace = HashMap<String, HashMap<String, Long>>()
    private var insertsSincePrune = 0

    // What we last surfaced (dock/drawer top row), for negative updates + confirm bonus.
    private var lastShown: List<String> = emptyList()

    // ---- public API --------------------------------------------------------------------

    /** Snapshot of the current context; maintains the recent-app chain internally. */
    fun snapshotNow(context: Context, mediaPlaying: Boolean = false): ContextSnapshot {
        ensureLoaded(context)
        val (last, prev) = synchronized(lock) {
            recentApps.firstOrNull() to recentApps.getOrNull(1)
        }
        return ContextSensors.snapshot(context, last, prev, mediaPlaying)
    }

    /** Active Space for this context (manual lock respected). */
    fun currentSpace(context: Context, snapshot: ContextSnapshot = snapshotNow(context)): SpaceDetection {
        ensureLoaded(context)
        return SpaceManager.detect(context, snapshot)
    }

    /**
     * Rank [candidates] (installed launchable packages) for this context. Pinned apps of
     * the active Space lead in pin order; excluded apps never appear. Never returns empty
     * while candidates exist — cold start falls back to seeded most-used ordering.
     */
    fun topApps(
        context: Context,
        n: Int,
        candidates: Collection<String>,
        snapshot: ContextSnapshot = snapshotNow(context),
    ): List<String> {
        ensureLoaded(context)
        if (candidates.isEmpty() || n <= 0) return emptyList()
        val detection = SpaceManager.detect(context, snapshot)
        val space = detection.space
        val ranked = rankedScores(detection, snapshot, candidates).map { it.first }
        val pinned = space.pinned.filter { it in candidates }
        val body = ranked.filter { it !in pinned }
        val ordered = (pinned + body).toMutableList()

        // Epsilon-greedy: occasionally promote a plausible lower-ranked app so the
        // linear layer keeps getting signal outside the frequency winners.
        val eps = maxOf(0.03f, 0.2f * exp(-totalLaunches / 300f))
        val rng = randomSeed?.let { Random(it) } ?: Random.Default
        if (ordered.size > n + 1 && rng.nextFloat() < eps) {
            val swapFrom = n + rng.nextInt(minOf(n, ordered.size - n))
            val swapTo = maxOf(pinned.size, n - 1)
            val candidate = ordered.removeAt(swapFrom)
            ordered.add(swapTo, candidate)
        }
        val result = ordered.take(n)
        synchronized(lock) { lastShown = result }
        return result
    }

    /** Scores for every candidate, best first — used by search biasing and debug. */
    fun scores(
        context: Context,
        candidates: Collection<String>,
        snapshot: ContextSnapshot = snapshotNow(context),
    ): List<Pair<String, Float>> {
        ensureLoaded(context)
        return rankedScores(SpaceManager.detect(context, snapshot), snapshot, candidates)
    }

    /**
     * The online update: the user opened [pkg] in [snapshot]'s context. Positive example
     * for the launched app (boosted when we predicted it, damped when found via search),
     * negative for apps we surfaced that were passed over. Also appends an encrypted row
     * to the transition log.
     */
    fun recordLaunch(
        context: Context,
        pkg: String,
        source: LaunchSource,
        snapshot: ContextSnapshot = snapshotNow(context),
    ) {
        ensureLoaded(context)
        val x = snapshot.features()
        val ctxKey = snapshot.contextKey()
        val space = SpaceManager.detect(context, snapshot).space
        val wasPredicted: Boolean
        synchronized(lock) {
            wasPredicted = pkg in lastShown
            val mult = when {
                source == LaunchSource.SEARCH -> 0.5f  // weaker signal: user hunted for it
                wasPredicted -> 1.5f                   // confirms the surfaced prediction
                else -> 1f
            }
            // Positive update for the launched app.
            val w = weights.getOrPut(pkg) { HashMap() }
            val err = (1f - sigmoid(dot(w, x))) * LR * mult
            x.forEach { f -> w[f] = (w[f] ?: 0f) * (1f - L2) + err }
            // Negative updates for surfaced-but-ignored apps.
            lastShown.asSequence().filter { it != pkg }.take(MAX_NEGATIVES).forEach { p ->
                val wp = weights.getOrPut(p) { HashMap() }
                val push = sigmoid(dot(wp, x)) * LR
                x.forEach { f -> wp[f] = (wp[f] ?: 0f) * (1f - L2) - push }
            }
            // Frequency priors.
            freqCtx.getOrPut(ctxKey) { HashMap() }.merge(pkg, 1f, Float::plus)
            freqSpace.getOrPut(space.id) { HashMap() }.merge(pkg, 1f, Float::plus)
            val recent = recentBySpace.getOrPut(space.id) { HashMap() }
            recent[pkg] = snapshot.timestamp
            if (recent.size > 30) recent.entries.minByOrNull { it.value }?.let { recent.remove(it.key) }
            freqGlobal.merge(pkg, 1f, Float::plus)
            ctxCount.merge(ctxKey, 1f, Float::plus)
            totalLaunches += 1f
            if (recentApps.firstOrNull() != pkg) {
                recentApps.addFirst(pkg)
                while (recentApps.size > 2) recentApps.removeLast()
            }
        }
        persistAsync(context)
        logTransition(context, snapshot, pkg, source, wasPredicted)
    }

    /**
     * Apps genuinely *learned* for a Space — read straight from its frequency table, never
     * from the global/seeded prior. Empty until the space has real signal, which is exactly
     * the "strong context" gate the search suggestion row needs (learned, not hard-coded).
     */
    fun spaceTopLearned(context: Context, spaceId: String, n: Int, candidates: Collection<String>? = null): List<String> {
        ensureLoaded(context)
        synchronized(lock) {
            val table = freqSpace[spaceId] ?: return emptyList()
            if (table.values.sum() < 5f) return emptyList()
            return table.entries.asSequence()
                .filter { candidates == null || it.key in candidates }
                .sortedByDescending { it.value }
                .take(n).map { it.key }.toList()
        }
    }

    /**
     * What a Space has learned, launch counts included, newest-signal first — the
     * transparency list shown in the Space editor. No minimum threshold: if you opened one
     * app once while the Space was active, it shows up here immediately.
     */
    fun spaceLearned(context: Context, spaceId: String, n: Int): List<Pair<String, Int>> {
        ensureLoaded(context)
        synchronized(lock) {
            val table = freqSpace[spaceId] ?: return emptyList()
            val recent = recentBySpace[spaceId] ?: emptyMap()
            return table.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Float>> { recent[it.key] ?: 0L }
                        .thenByDescending { it.value }
                )
                .take(n)
                .map { it.key to it.value.toInt().coerceAtLeast(1) }
        }
    }

    /** Forget a Space's learned app ranking (its frequency table); weights stay global. */
    fun resetSpaceLearning(context: Context, spaceId: String) {
        ensureLoaded(context)
        synchronized(lock) {
            freqSpace.remove(spaceId)
            recentBySpace.remove(spaceId)
        }
        persistAsync(context)
    }

    /** Full wipe: weights, priors, transition log, auto place clusters. */
    fun resetAllLearning(context: Context) {
        synchronized(lock) {
            weights.clear(); freqCtx.clear(); freqSpace.clear(); freqGlobal.clear()
            ctxCount.clear(); totalLaunches = 0f; recentApps.clear(); lastShown = emptyList()
            recentBySpace.clear()
        }
        PredictCrypto.prefs(context).edit().remove(STATE_KEY).remove(SEEDED_KEY).apply()
        PlaceStore.resetAutoClusters(context)
        scope.launch { runCatching { PredictDatabase.get(context).transitionDao().clearAll() } }
    }

    /** Human-readable top scores for the current context — for debugging/tuning. */
    fun debugDump(context: Context, candidates: Collection<String>): String {
        val snap = snapshotNow(context)
        val space = SpaceManager.detect(context, snap)
        val top = scores(context, candidates, snap).take(10)
        return buildString {
            appendLine("ctx=${snap.contextKey()} space=${space.space.name} strong=${space.strong} locked=${space.locked}")
            appendLine("features=${snap.features()}")
            appendLine("totalLaunches=$totalLaunches ctxObs=${ctxCount[snap.contextKey()] ?: 0f}")
            top.forEach { (pkg, s) -> appendLine("  %.4f  %s".format(s, pkg)) }
        }
    }

    // ---- scoring ------------------------------------------------------------------------

    private fun rankedScores(
        detection: SpaceDetection,
        snapshot: ContextSnapshot,
        candidates: Collection<String>,
    ): List<Pair<String, Float>> {
        val space = detection.space
        val x = snapshot.features()
        val ctxKey = snapshot.contextKey()
        synchronized(lock) {
            val ctxFreq = freqCtx[ctxKey] ?: emptyMap()
            val spaceFreq = freqSpace[space.id] ?: emptyMap()
            val recent = recentBySpace[space.id] ?: emptyMap()
            val ctxTotal = ctxFreq.values.sum().coerceAtLeast(1f)
            val spaceTotal = spaceFreq.values.sum().coerceAtLeast(1f)
            val globalTotal = freqGlobal.values.sum().coerceAtLeast(1f)
            val obs = ctxCount[ctxKey] ?: 0f
            val alpha = maxOf(0.15f, 0.7f * exp(-obs / 40f))
            // When the Space is a deliberate signal (manually locked, or detected from a
            // strong trigger like a place / driving / being away), what the user actually
            // does in that Space must dominate the months-old global habit prior — this is
            // what makes freshly opened apps on a trip stick instead of drowning under the
            // seeded home ranking.
            val engaged = detection.locked || detection.strong
            val now = snapshot.timestamp
            val affinity = space.categoryAffinity
            val provider = categoryProvider
            return candidates.asSequence()
                .filter { it !in space.excluded }
                .map { pkg ->
                    val ageHours = recent[pkg]?.let { (now - it) / 3_600_000f }
                    val categoryMatch = if (affinity.isNotEmpty() && provider != null &&
                        provider(pkg) in affinity) 1f else 0f
                    pkg to blendScore(
                        lin = sigmoid(dot(weights[pkg], x)),
                        ctxFreqNorm = (ctxFreq[pkg] ?: 0f) / ctxTotal,
                        spaceFreqNorm = (spaceFreq[pkg] ?: 0f) / spaceTotal,
                        globalFreqNorm = (freqGlobal[pkg] ?: 0f) / globalTotal,
                        ageHours = ageHours,
                        categoryMatch = categoryMatch,
                        alpha = alpha,
                        engaged = engaged,
                    )
                }
                .sortedByDescending { it.second }
                .toList()
        }
    }

    /**
     * Pure per-app score blend (no engine state — unit-testable). Frequency prior + recency,
     * mixed with the linear scorer by [alpha]. When [engaged] (Space locked or strongly
     * detected) the global habit prior is down-weighted and recency up-weighted, so apps you
     * actually open in this Space rise fast instead of drowning under the seeded home ranking.
     */
    internal fun blendScore(
        lin: Float,
        ctxFreqNorm: Float,
        spaceFreqNorm: Float,
        globalFreqNorm: Float,
        ageHours: Float?,
        categoryMatch: Float = 0f,
        alpha: Float,
        engaged: Boolean,
    ): Float {
        val wCtx = if (engaged) 0.25f else 0.40f
        val wSpace = if (engaged) 0.25f else 0.20f
        val wGlobal = if (engaged) 0.10f else 0.15f
        val wRecent = if (engaged) 0.25f else 0.10f
        // Standing cold-start boost for category-matching apps; small enough that a few real
        // launches (spaceFreq/recency) overtake it, big enough to lead an unlearned Space.
        val wCategory = 0.15f
        val recency = if (ageHours == null || ageHours < 0f) 0f else exp(-ageHours / 24f)
        val prior = wCtx * ctxFreqNorm + wSpace * spaceFreqNorm +
            wGlobal * globalFreqNorm + wRecent * recency + wCategory * categoryMatch
        return (1f - alpha) * lin + alpha * prior
    }

    private fun dot(w: Map<String, Float>?, x: List<String>): Float {
        if (w == null) return 0f
        var sum = 0f
        x.forEach { f -> sum += w[f] ?: 0f }
        return sum
    }

    private fun sigmoid(v: Float): Float = 1f / (1f + exp(-v))

    // ---- persistence ----------------------------------------------------------------------

    private fun ensureLoaded(context: Context) {
        if (loaded) {
            maybeDecay(context)
            return
        }
        synchronized(lock) {
            if (loaded) return
            val prefs = PredictCrypto.prefs(context)
            runCatching {
                val raw = prefs.getString(STATE_KEY, null) ?: return@runCatching
                val o = JSONObject(raw)
                weights = nestedFloatMap(o.optJSONObject("weights"))
                freqCtx = nestedFloatMap(o.optJSONObject("freqCtx"))
                freqSpace = nestedFloatMap(o.optJSONObject("freqSpace"))
                freqGlobal = floatMap(o.optJSONObject("global"))
                ctxCount = floatMap(o.optJSONObject("ctxCount"))
                totalLaunches = o.optDouble("total", 0.0).toFloat()
                lastDecayDay = o.optLong("lastDecayDay", 0L)
                recentApps = ArrayDeque(
                    (o.optJSONArray("recent") ?: org.json.JSONArray()).let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }
                    }
                )
                recentBySpace = HashMap()
                o.optJSONObject("recentSpace")?.let { rs ->
                    rs.keys().forEach { spaceId ->
                        val inner = rs.optJSONObject(spaceId) ?: return@forEach
                        val m = HashMap<String, Long>()
                        inner.keys().forEach { pkg -> m[pkg] = inner.optLong(pkg, 0L) }
                        recentBySpace[spaceId] = m
                    }
                }
            }.onFailure { Log.w(TAG, "state load failed, starting fresh", it) }
            if (!prefs.getBoolean(SEEDED_KEY, false)) {
                seedFromUsageCounts(context)
                prefs.edit().putBoolean(SEEDED_KEY, true).apply()
            }
            loaded = true
        }
        maybeDecay(context)
    }

    /**
     * Cold-start seed: the launcher has been counting launches in "app_usage_counts" long
     * before this engine existed — import those as the global prior so the first ranking
     * is already the user's real most-used order.
     */
    private fun seedFromUsageCounts(context: Context) {
        runCatching {
            val raw = context.applicationContext.getSharedPreferences("teclas", Context.MODE_PRIVATE)
                .getString("app_usage_counts", "{}") ?: "{}"
            val o = JSONObject(raw)
            o.keys().forEach { pkg ->
                val c = o.optInt(pkg, 0)
                if (c > 0) freqGlobal[pkg] = c.toFloat()
            }
            Log.i(TAG, "seeded global prior with ${freqGlobal.size} apps")
        }
    }

    /** Rolling daily decay so stale habits fade: counts *= 0.98 per elapsed day. */
    private fun maybeDecay(context: Context) {
        val today = System.currentTimeMillis() / (24 * 60 * 60 * 1000L)
        var changed = false
        synchronized(lock) {
            if (lastDecayDay == 0L) { lastDecayDay = today; changed = true }
            else if (today > lastDecayDay) {
                val factor = Math.pow(DAILY_DECAY.toDouble(), (today - lastDecayDay).toDouble()).toFloat()
                listOf(freqCtx, freqSpace).forEach { table ->
                    table.values.forEach { m -> m.keys.forEach { k -> m[k] = m[k]!! * factor } }
                }
                freqGlobal.keys.forEach { k -> freqGlobal[k] = freqGlobal[k]!! * factor }
                ctxCount.keys.forEach { k -> ctxCount[k] = ctxCount[k]!! * factor }
                lastDecayDay = today
                changed = true
            }
        }
        if (changed) persistAsync(context)
    }

    private fun persistAsync(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val json = synchronized(lock) {
                JSONObject().apply {
                    put("weights", toJson(weights))
                    put("freqCtx", toJson(freqCtx))
                    put("freqSpace", toJson(freqSpace))
                    put("global", JSONObject(freqGlobal.toMap()))
                    put("ctxCount", JSONObject(ctxCount.toMap()))
                    put("total", totalLaunches.toDouble())
                    put("lastDecayDay", lastDecayDay)
                    put("recent", org.json.JSONArray(recentApps.toList()))
                    put("recentSpace", JSONObject().also { rs ->
                        recentBySpace.forEach { (spaceId, m) -> rs.put(spaceId, JSONObject(m.toMap())) }
                    })
                }.toString()
            }
            runCatching { PredictCrypto.prefs(app).edit().putString(STATE_KEY, json).apply() }
                .onFailure { Log.w(TAG, "state persist failed", it) }
        }
    }

    private fun logTransition(
        context: Context,
        snapshot: ContextSnapshot,
        pkg: String,
        source: LaunchSource,
        wasPredicted: Boolean,
    ) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val payload = JSONObject().apply {
                    put("ctxKey", snapshot.contextKey())
                    put("features", org.json.JSONArray(snapshot.features()))
                    put("pkg", pkg)
                    put("source", source.name)
                    put("predicted", wasPredicted)
                }.toString()
                val dao = PredictDatabase.get(app).transitionDao()
                dao.insert(AppTransitionEntry(ts = snapshot.timestamp, blob = PredictCrypto.encrypt(payload)))
                if (++insertsSincePrune >= 50) {
                    insertsSincePrune = 0
                    dao.pruneTo(LOG_CAP)
                }
            }.onFailure { Log.w(TAG, "transition log failed", it) }
        }
    }

    // ---- JSON helpers -----------------------------------------------------------------------

    private fun toJson(map: Map<String, Map<String, Float>>): JSONObject {
        val o = JSONObject()
        map.forEach { (k, inner) -> o.put(k, JSONObject(inner.toMap())) }
        return o
    }

    private fun nestedFloatMap(o: JSONObject?): HashMap<String, HashMap<String, Float>> {
        val out = HashMap<String, HashMap<String, Float>>()
        o?.keys()?.forEach { k -> out[k] = floatMap(o.optJSONObject(k)) }
        return out
    }

    private fun floatMap(o: JSONObject?): HashMap<String, Float> {
        val out = HashMap<String, Float>()
        o?.keys()?.forEach { k -> out[k] = o.optDouble(k, 0.0).toFloat() }
        return out
    }
}
