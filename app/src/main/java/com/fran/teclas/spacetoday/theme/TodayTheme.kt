package com.fran.teclas.spacetoday.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp

data class TodayTheme(
    val id: String,
    val name: String,
    val desc: String,
    val builtIn: Boolean = true,
    val bg: Brush,
    val ink: Color,
    val sub: Color,
    val accent: Color,
    val card: Color,
    val cardBorder: Color,
    val cornerRadius: Dp,
    val fontFamily: FontFamily,
    val heroBrush: Brush
)

