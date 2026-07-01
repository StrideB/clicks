package com.fran.clicks.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface NgramDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entry: NgramEntry)

    @Query("UPDATE ngrams SET count = count + 1, lastUsed = :now WHERE prefix = :prefix AND word = :word")
    suspend fun increment(prefix: String, word: String, now: Long = System.currentTimeMillis())

    @Query("SELECT word FROM ngrams WHERE prefix = :prefix ORDER BY count DESC LIMIT :limit")
    suspend fun suggest(prefix: String, limit: Int = 3): List<String>

    /** Batch insert for seeding — entries that already exist are skipped. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(entries: List<NgramEntry>)

    /** Add delta to the count of an existing entry (used after batch seed to merge SMS frequency). */
    @Query("UPDATE ngrams SET count = count + :delta WHERE prefix = :prefix AND word = :word")
    suspend fun addCount(prefix: String, word: String, delta: Int)

    @Query("SELECT COUNT(*) FROM ngrams")
    suspend fun wordCount(): Int

    @Transaction
    suspend fun seedBatch(entries: List<NgramEntry>) {
        insertAllIgnore(entries)
        for (e in entries) addCount(e.prefix, e.word, e.count)
    }
}
