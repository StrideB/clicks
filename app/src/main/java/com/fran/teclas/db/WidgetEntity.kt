package com.fran.teclas.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "launcher_widgets")
data class WidgetEntity(
    @PrimaryKey val appWidgetId: Int,
    val cellX: Int,
    val cellY: Int,
    val spanX: Int,
    val spanY: Int,
    val minSpanX: Int,
    val minSpanY: Int
)
