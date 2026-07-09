package com.fran.teclas.db

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class NgramRepository(context: Context) {

    private val dao = NgramDatabase.get(context).ngramDao()
    private val io = CoroutineScope(Dispatchers.IO)

    // Throttle for the prune check so we don't run COUNT(*) on every recorded word.
    private val recordsSincePruneCheck = AtomicInteger(0)

    // Hot cache of previousWord -> ranked next words, warmed off the main thread so the typing path
    // can read next-word predictions synchronously (Room is async; the suggestion strip is not).
    private val nextWordCache = ConcurrentHashMap<String, List<String>>()

    fun recordWord(word: String, previousWord: String) {
        if (word.length < 2 || previousWord.isEmpty()) return
        val prefix = previousWord.lowercase()
        val w = word.lowercase()
        io.launch {
            dao.insertIgnore(NgramEntry(prefix, w))
            dao.increment(prefix, w)
            // Keep the hot cache fresh so learning shows up in predictions immediately.
            if (nextWordCache.containsKey(prefix)) nextWordCache[prefix] = dao.suggest(prefix, CACHE_LIMIT)
            maybePrune()
        }
    }

    suspend fun nextWordSuggestions(previousWord: String, limit: Int = 3): List<String> =
        dao.suggest(previousWord.lowercase(), limit)

    /**
     * Synchronous, main-thread-safe read of next-word predictions for [previousWord]. Returns the
     * pre-warmed cache (empty until [prefetchNextWords] has run for this prefix). Never hits the DB.
     */
    fun cachedNextWords(previousWord: String): List<String> =
        nextWordCache[previousWord.lowercase()] ?: emptyList()

    /**
     * Warm [cachedNextWords] for [previousWord] off the main thread. Cheap to call repeatedly (e.g.
     * on every keystroke) — it just refreshes one prefix. Bounded so the cache can't grow forever.
     */
    fun prefetchNextWords(previousWord: String, limit: Int = CACHE_LIMIT) {
        val p = previousWord.lowercase()
        if (p.length < 2) return
        io.launch {
            if (nextWordCache.size > CACHE_MAX_PREFIXES) nextWordCache.clear()
            nextWordCache[p] = dao.suggest(p, limit)
        }
    }

    /**
     * Occasionally cap table growth. Runs on the repo's IO scope (never the main thread) and only
     * touches the DB once every [PRUNE_CHECK_INTERVAL] records, and only actually deletes when the
     * store is over [MAX_ROWS]. Trims back to [PRUNE_TARGET] by removing the least-useful rows, so
     * suggestion ordering (ORDER BY count DESC) is unaffected.
     */
    private suspend fun maybePrune() {
        if (recordsSincePruneCheck.incrementAndGet() < PRUNE_CHECK_INTERVAL) return
        recordsSincePruneCheck.set(0)
        val count = dao.wordCount()
        if (count > MAX_ROWS) dao.pruneLeastUseful(count - PRUNE_TARGET)
    }

    companion object {
        private const val MAX_ROWS = 25_000
        private const val PRUNE_TARGET = 20_000
        private const val PRUNE_CHECK_INTERVAL = 200
        private const val CACHE_LIMIT = 5
        private const val CACHE_MAX_PREFIXES = 500
    }
}
