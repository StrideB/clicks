package com.fran.teclas.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SkillEntity::class], version = 1, exportSchema = true)
abstract class SkillDatabase : RoomDatabase() {
    abstract fun skillDao(): SkillDao

    companion object {
        @Volatile private var INSTANCE: SkillDatabase? = null

        fun get(context: Context): SkillDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                SkillDatabase::class.java,
                "agentic_skills.db"
            ).build().also { INSTANCE = it }
        }
    }
}
