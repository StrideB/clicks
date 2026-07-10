package com.fran.teclas.predict

import android.content.Context
import com.fran.teclas.semantic.SemanticSearchEngine

/**
 * Embedding-based cold-start priors for Spaces: each Space's description is embedded once and
 * scored against the semantic index's existing app vectors, giving a continuous 0..1 affinity
 * per (space, package) — a big step up from the binary category match ("Fitness" ↔ Strava works
 * even when the category table has never heard of the app). Precomputed off the hot path;
 * [score] is a lock-free map read safe to call from the predictor's ranking loop.
 */
object SemanticPriors {

    @Volatile private var byMinScore: Map<String, Map<String, Float>> = emptyMap()

    fun score(spaceId: String, pkg: String): Float = byMinScore[spaceId]?.get(pkg) ?: 0f

    /** Recompute all Space→app affinities. Cheap no-op when the semantic index is empty
     *  (model not imported yet). Call from a background scope after the index refreshes. */
    suspend fun rebuild(context: Context, spaces: List<Space>) {
        val fresh = HashMap<String, Map<String, Float>>(spaces.size)
        for (space in spaces) {
            if (!space.enabled) continue
            val description = buildString {
                append(space.name)
                if (space.categoryAffinity.isNotEmpty()) {
                    append(". ")
                    append(space.categoryAffinity.joinToString(", ") { it.name.lowercase().replace('_', ' ') })
                }
            }
            val affinity = runCatching {
                SemanticSearchEngine.affinity(context, description, "app:", minScore = 0.30f)
            }.getOrDefault(emptyMap())
            if (affinity.isNotEmpty()) fresh[space.id] = affinity
        }
        if (fresh.isNotEmpty()) byMinScore = fresh
    }
}
