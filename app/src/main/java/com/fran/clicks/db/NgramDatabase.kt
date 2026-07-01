package com.fran.clicks.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [NgramEntry::class], version = 1, exportSchema = false)
abstract class NgramDatabase : RoomDatabase() {
    abstract fun ngramDao(): NgramDao

    companion object {
        @Volatile private var INSTANCE: NgramDatabase? = null

        fun get(context: Context): NgramDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                NgramDatabase::class.java,
                "ngrams.db"
            ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}
