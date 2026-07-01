package com.fran.clicks.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NgramDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entry: NgramEntry)

    @Query("UPDATE ngrams SET count = count + 1, lastUsed = :now WHERE prefix = :prefix AND word = :word")
    suspend fun increment(prefix: String, word: String, now: Long = System.currentTimeMillis())

    @Query("SELECT word FROM ngrams WHERE prefix = :prefix ORDER BY count DESC LIMIT :limit")
    suspend fun suggest(prefix: String, limit: Int = 3): List<String>

    @Query("SELECT COUNT(*) FROM ngrams")
    suspend fun wordCount(): Int
}
