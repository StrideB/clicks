package com.fran.clicks.db

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class NgramRepository(context: Context) {

    private val dao = NgramDatabase.get(context).ngramDao()
    private val io = CoroutineScope(Dispatchers.IO)

    // Throttle for the prune check so we don't run COUNT(*) on every recorded word.
    private val recordsSincePruneCheck = AtomicInteger(0)

    fun recordWord(word: String, previousWord: String) {
        if (word.length < 2 || previousWord.isEmpty()) return
        val prefix = previousWord.lowercase()
        val w = word.lowercase()
        io.launch {
            dao.insertIgnore(NgramEntry(prefix, w))
            dao.increment(prefix, w)
            maybePrune()
        }
    }

    suspend fun nextWordSuggestions(previousWord: String, limit: Int = 3): List<String> =
        dao.suggest(previousWord.lowercase(), limit)

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
    }
}
