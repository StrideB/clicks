package com.fran.teclas.db

import android.content.Context
import com.fran.teclas.WidgetSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal class WidgetPersistenceRepository(context: Context) {
    private val dao = LauncherWidgetDatabase.get(context).widgetDao()

    fun loadBlocking(): List<WidgetSpec> = runBlocking(Dispatchers.IO) {
        dao.getAllWidgetsDirect().map { it.toSpec() }
    }

    suspend fun replaceAll(specs: List<WidgetSpec>) = withContext(Dispatchers.IO) {
        dao.clearAllStoredPlacements()
        dao.saveOrUpdateWidgets(specs.map { it.toEntity() })
    }

    suspend fun delete(widgetId: Int) = withContext(Dispatchers.IO) {
        dao.deleteWidgetById(widgetId)
    }

    private fun WidgetEntity.toSpec() = WidgetSpec(
        id = appWidgetId,
        cellX = cellX,
        cellY = cellY,
        spanX = spanX,
        spanY = spanY,
        minSpanX = minSpanX,
        minSpanY = minSpanY
    )

    private fun WidgetSpec.toEntity() = WidgetEntity(
        appWidgetId = id,
        cellX = cellX,
        cellY = cellY,
        spanX = spanX,
        spanY = spanY,
        minSpanX = minSpanX,
        minSpanY = minSpanY
    )
}
