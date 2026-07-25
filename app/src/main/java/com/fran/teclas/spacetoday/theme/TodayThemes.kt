package com.fran.teclas.spacetoday.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Built-in Space Today themes. These skin only the workload surface, never keyboard/wallpaper
 * themes. Add a new theme by appending it to [ALL] and referencing its id from [TodayThemeStore].
 */
object TodayThemes {
    val ALL: List<TodayTheme> = listOf(
        TodayTheme(
            id = "slate",
            name = "Slate",
            desc = "Clean workload glass",
            bg = Brush.verticalGradient(listOf(Color(0xFF141422), Color(0xFF0E0E18))),
            ink = Color(0xFFF4F2FA),
            sub = Color(0xFF9E9AAA),
            accent = Color(0xFFC9A7FF),
            card = Color(0xB81C1D26),
            cardBorder = Color(0x2EFFFFFF),
            cornerRadius = 16.dp,
            fontFamily = FontFamily.Default,
            heroBrush = Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFC9A7FF)))
        ),
        TodayTheme(
            id = "terminal",
            name = "Terminal",
            desc = "Green phosphor focus",
            bg = Brush.verticalGradient(listOf(Color(0xFF07110A), Color(0xFF030604))),
            ink = Color(0xFF8EFFB9),
            sub = Color(0xFF4FA871),
            accent = Color(0xFF39FF88),
            card = Color(0xD80A160D),
            cardBorder = Color(0x6639FF88),
            cornerRadius = 5.dp,
            fontFamily = FontFamily.Monospace,
            heroBrush = Brush.linearGradient(listOf(Color(0xFF39FF88), Color(0xFFA8FFC9)))
        ),
        TodayTheme(
            id = "departure",
            name = "Departure",
            desc = "Split-flap amber",
            bg = Brush.verticalGradient(listOf(Color(0xFF15170F), Color(0xFF0B0D08))),
            ink = Color(0xFFF5E7B7),
            sub = Color(0xFF9F936F),
            accent = Color(0xFFF4B400),
            card = Color(0xE111120D),
            cardBorder = Color(0x44F4B400),
            cornerRadius = 6.dp,
            fontFamily = FontFamily.Monospace,
            heroBrush = Brush.linearGradient(listOf(Color(0xFFFFD66E), Color(0xFFF4B400)))
        ),
        TodayTheme(
            id = "aurora",
            name = "Aurora",
            desc = "Frosted violet drift",
            bg = Brush.linearGradient(listOf(Color(0xFF1A1140), Color(0xFF6D3AA0), Color(0xFF102D3A))),
            ink = Color(0xFFF8F7FF),
            sub = Color(0xFFC8C2DA),
            accent = Color(0xFF8AFFEA),
            card = Color(0x70262936),
            cardBorder = Color(0x3DFFFFFF),
            cornerRadius = 18.dp,
            fontFamily = FontFamily.Default,
            heroBrush = Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFF8AFFEA)))
        ),
        TodayTheme(
            id = "editorial",
            name = "Editorial",
            desc = "Newsprint serif",
            bg = Brush.verticalGradient(listOf(Color(0xFFF4F0E6), Color(0xFFE7DFD0))),
            ink = Color(0xFF201C18),
            sub = Color(0xFF6E6256),
            accent = Color(0xFFD64525),
            card = Color(0xDDFBF7EE),
            cardBorder = Color(0xFFCCBEA6),
            cornerRadius = 3.dp,
            fontFamily = FontFamily.Serif,
            heroBrush = Brush.linearGradient(listOf(Color(0xFF201C18), Color(0xFFD64525)))
        ),
        TodayTheme(
            id = "midnight",
            name = "Midnight",
            desc = "True black OLED",
            bg = Brush.verticalGradient(listOf(Color.Black, Color(0xFF06070B))),
            ink = Color(0xFFF2F5FF),
            sub = Color(0xFF858B99),
            accent = Color(0xFF5B8CFF),
            card = Color(0xD50D0F14),
            cardBorder = Color(0x255B8CFF),
            cornerRadius = 14.dp,
            fontFamily = FontFamily.Default,
            heroBrush = Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFF5B8CFF)))
        ),
        TodayTheme(
            id = "candy",
            name = "Candy",
            desc = "Bright soft plastic",
            bg = Brush.linearGradient(listOf(Color(0xFFFFE3F1), Color(0xFFE0E7FF))),
            ink = Color(0xFF2F233C),
            sub = Color(0xFF806D92),
            accent = Color(0xFFFF5EA8),
            card = Color(0xB8FFFFFF.toInt()),
            cardBorder = Color(0x99FFFFFF),
            cornerRadius = 22.dp,
            fontFamily = FontFamily.Default,
            heroBrush = Brush.linearGradient(listOf(Color(0xFFFF5EA8), Color(0xFF7D5CFF)))
        ),
        TodayTheme(
            id = "forest",
            name = "Forest",
            desc = "Calm sage pages",
            bg = Brush.verticalGradient(listOf(Color(0xFFE4EEDA), Color(0xFFCFE0C2))),
            ink = Color(0xFF1D2B22),
            sub = Color(0xFF667264),
            accent = Color(0xFF4A8F3C),
            card = Color(0xCCF7FAF1.toInt()),
            cardBorder = Color(0x884A8F3C.toInt()),
            cornerRadius = 18.dp,
            fontFamily = FontFamily.Default,
            heroBrush = Brush.linearGradient(listOf(Color(0xFF1D2B22), Color(0xFF4A8F3C)))
        ),
        TodayTheme(
            id = "cyberpunk",
            name = "Cyberpunk",
            desc = "Neon city cards",
            bg = Brush.linearGradient(listOf(Color(0xFF0D0221), Color(0xFF1A0B3A), Color(0xFF061E32))),
            ink = Color(0xFFF8FAFF),
            sub = Color(0xFF9FB1D0),
            accent = Color(0xFFFF2E97),
            card = Color(0xE0120A2B.toInt()),
            cardBorder = Color(0x66FF2E97.toInt()),
            cornerRadius = 4.dp,
            fontFamily = FontFamily.Monospace,
            heroBrush = Brush.linearGradient(listOf(Color(0xFF00E5FF), Color(0xFFFF2E97)))
        ),
        TodayTheme(
            id = "luxe",
            name = "Luxe",
            desc = "Black and gold",
            bg = Brush.verticalGradient(listOf(Color(0xFF1A1712), Color(0xFF26201A))),
            ink = Color(0xFFF8ECD2),
            sub = Color(0xFFA99B80),
            accent = Color(0xFFD4AF57),
            card = Color(0xDD181512.toInt()),
            cardBorder = Color(0x55D4AF57),
            cornerRadius = 12.dp,
            fontFamily = FontFamily.Serif,
            heroBrush = Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFD4AF57)))
        ),
        TodayTheme(
            id = "clay",
            name = "Clay",
            desc = "Soft tactile lavender",
            bg = Brush.verticalGradient(listOf(Color(0xFFEFEBFF), Color(0xFFE0D9FB))),
            ink = Color(0xFF2D2940),
            sub = Color(0xFF716B89),
            accent = Color(0xFF7C5CFF),
            card = Color(0xDDF8F4FF.toInt()),
            cardBorder = Color(0x99FFFFFF.toInt()),
            cornerRadius = 22.dp,
            fontFamily = FontFamily.Default,
            heroBrush = Brush.linearGradient(listOf(Color(0xFF2D2940), Color(0xFF7C5CFF)))
        ),
        TodayTheme(
            id = "nord",
            name = "Nord",
            desc = "Arctic slate",
            bg = Brush.verticalGradient(listOf(Color(0xFF3B4252), Color(0xFF2E3440))),
            ink = Color(0xFFECEFF4),
            sub = Color(0xFFB7C1D3),
            accent = Color(0xFF88C0D0),
            card = Color(0xC72E3440.toInt()),
            cardBorder = Color(0x4488C0D0),
            cornerRadius = 14.dp,
            fontFamily = FontFamily.Default,
            heroBrush = Brush.linearGradient(listOf(Color(0xFFECEFF4), Color(0xFF88C0D0)))
        )
    )

    fun byId(id: String): TodayTheme = ALL.firstOrNull { it.id == id } ?: ALL.first()
}
