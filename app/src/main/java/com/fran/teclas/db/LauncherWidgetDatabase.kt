package com.fran.teclas.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [WidgetEntity::class], version = 1, exportSchema = true)
abstract class LauncherWidgetDatabase : RoomDatabase() {
    abstract fun widgetDao(): WidgetDao

    companion object {
        @Volatile private var INSTANCE: LauncherWidgetDatabase? = null

        fun get(context: Context): LauncherWidgetDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                LauncherWidgetDatabase::class.java,
                "launcher_workspace.db"
            ).build().also { INSTANCE = it }
        }
    }
}
