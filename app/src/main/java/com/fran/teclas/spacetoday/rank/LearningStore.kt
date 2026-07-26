package com.fran.teclas.spacetoday.rank

import android.content.SharedPreferences
import com.fran.teclas.spacetoday.model.Tier
import org.json.JSONObject

/**
 * Space Today learning shim.
 *
 * The launcher already owns the heavy app/context bandit in `predict/`; this store deliberately
 * keeps only the per-Space calibration knobs the workload ranker needs: feature weights, tier
 * cutoffs, and breakthrough porosity. Actions nudge those numbers on-device and persist as JSON in
 * `teclas` preferences. That lets the LLM propose importance while the device tunes thresholds to
 * this user's behavior over time.
 */
class LearningStore(private val prefs: SharedPreferences) {
    data class Weights(
        val sender: Double = 1.0,
        val channel: Double = 1.0,
        val burst: Double = 0.55,
        val keyword: Double = 1.0,
        val zone: Double = 1.0
    )

    data class State(
        val weights: Weights = Weights(),
        val t1Cut: Int = 58,
        val t2Cut: Int = 34,
        val breakthrough: Int = 74
    )

    fun weights(space: String): Weights = state(space).weights
    fun tierCuts(space: String): Pair<Int, Int> = state(space).let { it.t1Cut to it.t2Cut }
    fun breakthroughThreshold(space: String): Int = state(space).breakthrough

    fun onActed(space: String, tier: Tier, actionKind: String, wasBursty: Boolean = false) {
        val current = state(space)
        val reinforce = if (actionKind.contains("reply", true) || actionKind.contains("join", true)) 0.06 else 0.035
        save(space, current.copy(
            weights = current.weights.copy(
                sender = (current.weights.sender + reinforce).coerceAtMost(1.65),
                channel = (current.weights.channel + reinforce / 2).coerceAtMost(1.45),
                keyword = (current.weights.keyword + reinforce / 2).coerceAtMost(1.45),
                // Acting reinforces that the source app/account's Zone was a correct signal here.
                zone = (current.weights.zone + reinforce / 2).coerceAtMost(1.45),
                // Acting on a bursty item means this space's bursts are worth surfacing — ease the
                // burst penalty (lower weight). Untouched when the acted item wasn't part of a burst.
                burst = if (wasBursty) (current.weights.burst * 0.94).coerceIn(0.20, 1.40) else current.weights.burst
            ),
            t1Cut = (current.t1Cut - if (tier == Tier.T1_CRITICAL) 1 else 0).coerceIn(48, 72),
            t2Cut = (current.t2Cut - if (tier == Tier.T2_IMPORTANT) 1 else 0).coerceIn(24, 48)
        ))
    }

    fun onIgnored(space: String, tier: Tier, wasBursty: Boolean = false) {
        val current = state(space)
        val raise = if (tier == Tier.T1_CRITICAL) 2 else 1
        save(space, current.copy(
            weights = current.weights.copy(
                sender = (current.weights.sender * 0.985).coerceAtLeast(0.65),
                keyword = (current.weights.keyword * 0.985).coerceAtLeast(0.65),
                // Symmetric with onActed: channel and zone must be able to fall back, not only ratchet
                // up, or an early lucky reinforce pins them at the cap forever.
                channel = (current.weights.channel * 0.985).coerceAtLeast(0.65),
                zone = (current.weights.zone * 0.985).coerceAtLeast(0.65),
                // Dismissing a bursty item deepens this space's burst penalty (higher weight).
                burst = if (wasBursty) (current.weights.burst + 0.05).coerceIn(0.20, 1.40) else current.weights.burst
            ),
            t1Cut = (current.t1Cut + raise).coerceIn(48, 74),
            t2Cut = (current.t2Cut + 1).coerceIn(24, 54)
        ))
    }

    fun onBreakthroughTapped(fromSpace: String) {
        val current = state(fromSpace)
        save(fromSpace, current.copy(breakthrough = (current.breakthrough - 3).coerceIn(current.t1Cut + 6, 92)))
    }

    fun onBreakthroughIgnored(fromSpace: String) {
        val current = state(fromSpace)
        save(fromSpace, current.copy(breakthrough = (current.breakthrough + 2).coerceIn(current.t1Cut + 8, 96)))
    }

    fun resetSpace(space: String) {
        val all = all().toMutableMap()
        all.remove(space)
        saveAll(all)
    }

    private fun state(space: String): State = all()[space] ?: State()

    private fun save(space: String, state: State) {
        val all = all().toMutableMap()
        all[space] = state
        saveAll(all)
    }

    private fun all(): Map<String, State> {
        val raw = prefs.getString(PREF, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { key ->
                val s = obj.optJSONObject(key) ?: JSONObject()
                val w = s.optJSONObject("weights") ?: JSONObject()
                State(
                    weights = Weights(
                        sender = w.optDouble("sender", 1.0),
                        channel = w.optDouble("channel", 1.0),
                        burst = w.optDouble("burst", 0.55),
                        keyword = w.optDouble("keyword", 1.0),
                        zone = w.optDouble("zone", 1.0)
                    ),
                    t1Cut = s.optInt("t1", 58),
                    t2Cut = s.optInt("t2", 34),
                    breakthrough = s.optInt("breakthrough", 74)
                )
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveAll(map: Map<String, State>) {
        val obj = JSONObject()
        map.forEach { (space, state) ->
            obj.put(space, JSONObject()
                .put("weights", JSONObject()
                    .put("sender", state.weights.sender)
                    .put("channel", state.weights.channel)
                    .put("burst", state.weights.burst)
                    .put("keyword", state.weights.keyword)
                    .put("zone", state.weights.zone))
                .put("t1", state.t1Cut)
                .put("t2", state.t2Cut)
                .put("breakthrough", state.breakthrough))
        }
        prefs.edit().putString(PREF, obj.toString()).apply()
    }

    private companion object {
        const val PREF = "space_today_learning_json"
    }
}
