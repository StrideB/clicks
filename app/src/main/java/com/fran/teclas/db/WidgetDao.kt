package com.fran.teclas.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WidgetDao {
    @Query("SELECT * FROM launcher_widgets ORDER BY cellY ASC, cellX ASC")
    fun getAllWidgetsFlow(): Flow<List<WidgetEntity>>

    @Query("SELECT * FROM launcher_widgets ORDER BY cellY ASC, cellX ASC")
    suspend fun getAllWidgetsDirect(): List<WidgetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveOrUpdateWidget(widget: WidgetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveOrUpdateWidgets(widgets: List<WidgetEntity>)

    @Query("DELETE FROM launcher_widgets WHERE appWidgetId = :widgetId")
    suspend fun deleteWidgetById(widgetId: Int)

    @Query("DELETE FROM launcher_widgets")
    suspend fun clearAllStoredPlacements()
}
