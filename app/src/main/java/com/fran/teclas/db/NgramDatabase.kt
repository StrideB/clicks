package com.fran.teclas.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [NgramEntry::class], version = 1, exportSchema = true)
abstract class NgramDatabase : RoomDatabase() {
    abstract fun ngramDao(): NgramDao

    companion object {
        @Volatile private var INSTANCE: NgramDatabase? = null

        fun get(context: Context): NgramDatabase = INSTANCE ?: synchronized(this) {
            // No destructive fallback: the keyboard's learned n-grams (typed + SMS-seeded) are
            // user data and must survive schema changes. The builder has no migrations because v1
            // has no predecessors; the exported schema under app/schemas lets us diff future bumps.
            // TODO: On the next schema bump (version = 2), export the new schema and add an explicit
            //       Migration(1, 2) via .addMigrations(...) here. Without one, opening the DB will
            //       throw IllegalStateException rather than silently wiping — which is the point.
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                NgramDatabase::class.java,
                "ngrams.db"
            ).build().also { INSTANCE = it }
        }
    }
}
