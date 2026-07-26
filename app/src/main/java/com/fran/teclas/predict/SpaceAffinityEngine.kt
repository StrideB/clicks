package com.fran.teclas.predict

import android.content.Context

/**
 * "Where does this app belong?" — a per-Space app-affinity scorer.
 *
 * The launcher never stored explicit app→Space membership; belonging was only ever *implicit* in the
 * bandit's per-Space launch counts, which meant a fresh Space (nothing launched yet, nothing pinned)
 * had nothing to show. This engine makes belonging explicit by fusing three signals the app already
 * produces into a single 0..1 score:
 *
 *   • learned    — genuinely-learned launches in that Space (`Predictor.spaceLearned`), normalized
 *   • semantic   — the app-name/category embedding prior for the Space (`SemanticPriors.score`)
 *   • category   — whether the app's category is in the Space's declared `categoryAffinity`
 *
 * It is purely additive and read-only with respect to the user's choices: it *suggests* apps for a
 * Space (e.g. to seed an empty board, or to recommend pins) but never mutates `pinned`/`excluded`.
 * Excluded apps always score 0.
 */
object SpaceAffinityEngine {

    private const val W_LEARNED = 0.55f
    private const val W_SEMANTIC = 0.30f
    private const val W_CATEGORY = 0.15f

    /** Minimum fused score for an app to count as "belonging" to a Space at all. */
    private const val MIN_AFFINITY = 0.05f

    /**
     * Top apps that belong to [space], best-first, drawn from [installed] and excluding [exclude]
     * (e.g. the apps already pinned). Used to auto-seed a fresh Space board so it isn't empty until
     * the user pins something.
     */
    fun suggestedApps(
        context: Context,
        space: Space,
        installed: Collection<String>,
        n: Int,
        exclude: Set<String> = emptySet(),
    ): List<String> {
        if (installed.isEmpty() || n <= 0) return emptyList()
        val learned = normalizedLearned(context, space.id)
        return installed.asSequence()
            .filter { it !in exclude && it !in space.excluded }
            .map { pkg -> pkg to score(space, pkg, learned) }
            .filter { it.second > MIN_AFFINITY }
            .sortedByDescending { it.second }
            .take(n)
            .map { it.first }
            .toList()
    }

    /** Fused belonging score for a single (space, app). 0 = no affinity / excluded. */
    fun affinity(context: Context, space: Space, pkg: String): Float =
        score(space, pkg, normalizedLearned(context, space.id))

    /**
     * The Space each app most belongs to across [spaces] (highest affinity), or null if nothing
     * clears [MIN_BEST]. A small floor keeps borderline apps unassigned rather than forcing a guess.
     */
    fun bestSpace(context: Context, spaces: List<Space>, pkg: String): String? {
        var bestId: String? = null
        var best = MIN_BEST
        spaces.forEach { s ->
            val a = affinity(context, s, pkg)
            if (a > best) { best = a; bestId = s.id }
        }
        return bestId
    }

    private fun score(space: Space, pkg: String, learned: Map<String, Float>): Float {
        if (pkg in space.excluded) return 0f
        val l = learned[pkg] ?: 0f
        val s = SemanticPriors.score(space.id, pkg).coerceIn(0f, 1f)
        val c = if (categoryMatches(space, pkg)) 1f else 0f
        return W_LEARNED * l + W_SEMANTIC * s + W_CATEGORY * c
    }

    /** Per-Space launch counts normalized to that Space's own max, so light and heavy Spaces both span 0..1. */
    private fun normalizedLearned(context: Context, spaceId: String): Map<String, Float> {
        val counts = Predictor.spaceLearned(context, spaceId, LEARNED_LOOKBACK)
        val max = (counts.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1).toFloat()
        return counts.associate { it.first to it.second / max }
    }

    private fun categoryMatches(space: Space, pkg: String): Boolean {
        if (space.categoryAffinity.isEmpty()) return false
        val cat = Predictor.categoryProvider?.invoke(pkg) ?: return false
        return cat in space.categoryAffinity
    }

    private const val LEARNED_LOOKBACK = 60
    private const val MIN_BEST = 0.08f
}
