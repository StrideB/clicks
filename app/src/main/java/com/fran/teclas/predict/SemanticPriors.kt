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

    /**
     * Signature of the inputs the last rebuild was computed from: the Space descriptions plus the
     * semantic index generation. Every embedding here is a full llama.cpp inference run, and the
     * descriptions (Space name + categories) change almost never — so without this guard a rebuild
     * re-embedded every Space on each index refresh (i.e. on unlock) for a byte-identical result.
     */
    @Volatile private var lastSignature: String? = null

    fun score(spaceId: String, pkg: String): Float = byMinScore[spaceId]?.get(pkg) ?: 0f

    /** Recompute all Space→app affinities. Cheap no-op when the semantic index is empty
     *  (model not imported yet) or when nothing that feeds the result has changed. */
    suspend fun rebuild(context: Context, spaces: List<Space>) {
        val descriptions = spaces.filter { it.enabled }.map { it.id to describe(it) }
        val signature = descriptions.joinToString("|") { "${it.first}=${it.second.hashCode()}" } +
            "#${SemanticSearchEngine.indexGeneration()}"
        if (signature == lastSignature && byMinScore.isNotEmpty()) return

        val fresh = HashMap<String, Map<String, Float>>(spaces.size)
        for (space in spaces) {
            if (!space.enabled) continue
            val affinity = runCatching {
                SemanticSearchEngine.affinity(context, describe(space), "app:", minScore = 0.30f)
            }.getOrDefault(emptyMap())
            if (affinity.isNotEmpty()) fresh[space.id] = affinity
        }
        if (fresh.isNotEmpty()) {
            byMinScore = fresh
            lastSignature = signature
        }
    }

    private fun describe(space: Space): String = buildString {
        append(space.name)
        if (space.categoryAffinity.isNotEmpty()) {
            append(". ")
            append(space.categoryAffinity.joinToString(", ") { it.name.lowercase().replace('_', ' ') })
        }
    }
}
