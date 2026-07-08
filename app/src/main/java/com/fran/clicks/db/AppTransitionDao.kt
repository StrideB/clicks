package com.fran.clicks.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AppTransitionDao {

    @Insert
    suspend fun insert(entry: AppTransitionEntry)

    @Query("SELECT COUNT(*) FROM app_transitions")
    suspend fun count(): Int

    @Query("SELECT * FROM app_transitions ORDER BY ts DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AppTransitionEntry>

    /** Keep the log bounded: drop the oldest rows beyond [keep]. */
    @Query(
        "DELETE FROM app_transitions WHERE id NOT IN " +
            "(SELECT id FROM app_transitions ORDER BY ts DESC LIMIT :keep)"
    )
    suspend fun pruneTo(keep: Int)

    @Query("DELETE FROM app_transitions")
    suspend fun clearAll()
}
