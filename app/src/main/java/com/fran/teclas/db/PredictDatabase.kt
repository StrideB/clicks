package com.fran.teclas.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [AppTransitionEntry::class], version = 1, exportSchema = true)
abstract class PredictDatabase : RoomDatabase() {
    abstract fun transitionDao(): AppTransitionDao

    companion object {
        @Volatile private var INSTANCE: PredictDatabase? = null

        fun get(context: Context): PredictDatabase = INSTANCE ?: synchronized(this) {
            // Same policy as NgramDatabase: no destructive fallback — the transition log is
            // learned user data. On a future schema bump, export the new schema and add an
            // explicit Migration via .addMigrations(...).
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                PredictDatabase::class.java,
                "predict.db"
            ).build().also { INSTANCE = it }
        }
    }
}
