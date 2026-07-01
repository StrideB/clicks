package com.fran.clicks.db

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NgramRepository(context: Context) {

    private val dao = NgramDatabase.get(context).ngramDao()
    private val io = CoroutineScope(Dispatchers.IO)

    fun recordWord(word: String, previousWord: String) {
        if (word.length < 2 || previousWord.isEmpty()) return
        val prefix = previousWord.lowercase()
        val w = word.lowercase()
        io.launch {
            dao.insertIgnore(NgramEntry(prefix, w))
            dao.increment(prefix, w)
        }
    }

    suspend fun nextWordSuggestions(previousWord: String, limit: Int = 3): List<String> =
        dao.suggest(previousWord.lowercase(), limit)
}
