package com.fran.clicks.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Synchronous DAO — every method must be called off the main thread (the router uses a background
 * Thread, the settings screen uses a background coroutine). Kept non-suspend so the process-wide
 * router cache can be filled without a coroutine scope.
 */
@Dao
interface SkillDao {

    @Query("SELECT * FROM agentic_skills ORDER BY sortOrder ASC, id ASC")
    fun getAll(): List<SkillEntity>

    @Query("SELECT * FROM agentic_skills WHERE enabled = 1 ORDER BY sortOrder ASC, id ASC")
    fun getEnabled(): List<SkillEntity>

    @Query("SELECT COUNT(*) FROM agentic_skills")
    fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(skill: SkillEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(skills: List<SkillEntity>)

    @Update
    fun update(skill: SkillEntity)

    @Query("UPDATE agentic_skills SET enabled = :enabled WHERE id = :id")
    fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE agentic_skills SET usageCount = usageCount + 1, lastUsed = :ts WHERE id = :id")
    fun recordUse(id: Long, ts: Long)

    @Query("DELETE FROM agentic_skills WHERE id = :id AND builtin = 0")
    fun deleteCustom(id: Long)
}
