package com.fran.teclas.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Two non-animated boxless families that share the WeatherData model + WeatherStyle registry
// from WeatherStyles.kt: SIMPLE (clean type) and DOT MATRIX (5x7 LED pixel font). Both are
// folded into WEATHER_STYLES there. Style composables are file-private so the generic names
// (Grid/Line/Center/…) don't leak into the package — the registry vals below are the surface.

private val ink = Color.White
private fun soft(a: Float = .6f) = Color.White.copy(alpha = a)
private val mono = FontFamily.Monospace

// Same wallpaper-legibility shadow every weather family uses (defined in WeatherStyles.kt).
@Composable
private fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null
) = androidx.compose.material3.Text(
    text = text,
    modifier = modifier,
    color = color,
    fontSize = fontSize,
    fontWeight = fontWeight,
    fontFamily = fontFamily,
    letterSpacing = letterSpacing,
    textAlign = textAlign,
    style = TextStyle(shadow = WeatherTextShadow)
)

// ---------- Static condition glyph (no animation) ----------
@Composable
private fun StaticGlyph(condition: Condition, sizeDp: Int, tint: Color = ink, accent: Color) {
    Canvas(Modifier.size(sizeDp.dp)) {
        val s = size.minDimension
        val cx = s / 2f; val cy = s / 2f
        fun cloud(dy: Float = 0f) {
            val p = Path().apply {
                moveTo(s * .27f, s * .62f + dy)
                cubicTo(s * .10f, s * .60f + dy, s * .12f, s * .34f + dy, s * .30f, s * .33f + dy)
                cubicTo(s * .33f, s * .16f + dy, s * .66f, s * .16f + dy, s * .68f, s * .36f + dy)
                cubicTo(s * .86f, s * .35f + dy, s * .86f, s * .60f + dy, s * .70f, s * .62f + dy)
                close()
            }
            drawPath(p, tint)
        }
        when (condition) {
            Condition.SUNNY -> {
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    drawLine(
                        tint,
                        Offset(cx + s * .34f * Math.cos(a).toFloat(), cy + s * .34f * Math.sin(a).toFloat()),
                        Offset(cx + s * .46f * Math.cos(a).toFloat(), cy + s * .46f * Math.sin(a).toFloat()),
                        strokeWidth = s * .03f, cap = StrokeCap.Round
                    )
                }
                drawCircle(tint, radius = s * .18f, center = Offset(cx, cy))
            }
            Condition.CLOUDY -> cloud()
            Condition.RAIN, Condition.STORM -> {
                cloud()
                listOf(.36f, .5f, .64f).forEach { x ->
                    drawLine(
                        tint, Offset(s * x, s * .68f), Offset(s * x - s * .03f, s * .80f),
                        strokeWidth = s * .02f, cap = StrokeCap.Round
                    )
                }
            }
            Condition.SNOW -> {
                cloud()
                listOf(.36f, .5f, .64f).forEach { x ->
                    drawCircle(tint, radius = s * .025f, center = Offset(s * x, s * .74f))
                }
            }
        }
    }
}

// ==================== FAMILY: SIMPLE (boxless) ====================

// 1 Plain — icon, big thin temp, place, condition
@Composable private fun Plain(d: WeatherData, accent: Color, m: Modifier) = Column(m) {
    StaticGlyph(d.condition, 56, accent = accent)
    Spacer(Modifier.height(12.dp))
    Text("${d.temp}°", fontSize = 88.sp, fontWeight = FontWeight.Thin, letterSpacing = (-3).sp, color = ink)
    Text(d.place, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = ink)
    Text(d.conditionLabel, fontSize = 13.sp, color = soft())
}

// 2 Center — centered temp with H/L row
@Composable private fun Center(d: WeatherData, accent: Color, m: Modifier) = Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
    Text("${d.temp}°", fontSize = 72.sp, fontWeight = FontWeight.Thin, letterSpacing = (-3).sp, color = ink)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("H ${d.hi}°", fontSize = 13.sp, color = soft(.65f))
        Text("L ${d.lo}°", fontSize = 13.sp, color = soft(.65f))
    }
}

// 3 Line — icon + temp + place/condition in a row
@Composable private fun Line(d: WeatherData, accent: Color, m: Modifier) = Row(m, verticalAlignment = Alignment.CenterVertically) {
    StaticGlyph(d.condition, 48, accent = accent)
    Spacer(Modifier.width(18.dp))
    Text("${d.temp}°", fontSize = 64.sp, fontWeight = FontWeight.Thin, letterSpacing = (-3).sp, color = ink)
    Spacer(Modifier.width(18.dp))
    Column {
        Text(d.place, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ink)
        Text(d.conditionLabel, fontSize = 12.sp, color = soft())
    }
}

// 4 Stack — icon over temp over uppercase place, centered
@Composable private fun Stack(d: WeatherData, accent: Color, m: Modifier) = Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
    StaticGlyph(d.condition, 56, accent = accent)
    Spacer(Modifier.height(12.dp))
    Text("${d.temp}°", fontSize = 66.sp, fontWeight = FontWeight.Thin, letterSpacing = (-3).sp, color = ink)
    Text(d.place.uppercase(), fontSize = 12.sp, letterSpacing = 3.sp, color = soft(.55f))
}

// 5 Faint — ultralight, minimal
@Composable private fun Faint(d: WeatherData, accent: Color, m: Modifier) = Column(m) {
    Text("${d.temp}°", fontSize = 100.sp, fontWeight = FontWeight.Thin, letterSpacing = (-4).sp, color = ink)
    Text(d.place.uppercase(), fontSize = 14.sp, letterSpacing = 2.sp, color = soft(.5f))
}

// ==================== FAMILY: DOT MATRIX (boxless) ====================
// 5x7 pixel font. Digits + degree. Extend GLYPHS for letters if you want dotted labels.

private val GLYPHS: Map<Char, List<String>> = mapOf(
    '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
    '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
    '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
    '3' to listOf("11111", "00010", "00100", "00010", "00001", "10001", "01110"),
    '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
    '5' to listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
    '6' to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
    '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
    '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
    '9' to listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
    '°' to listOf("01100", "10010", "10010", "01100", "00000", "00000", "00000"),
    ' ' to listOf("00000", "00000", "00000", "00000", "00000", "00000", "00000"),
)

@Composable
private fun DotText(text: String, dot: Int = 7, gap: Int = 3, tint: Color = ink, dim: Float = .12f) {
    val glyphs = text.map { GLYPHS[it] ?: GLYPHS[' ']!! }
    Column(verticalArrangement = Arrangement.spacedBy(gap.dp)) {
        for (r in 0 until 7) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                glyphs.forEachIndexed { gi, g ->
                    for (c in 0 until 5) {
                        val on = g[r][c] == '1'
                        Box(
                            Modifier.size(dot.dp).clip(CircleShape)
                                .background(if (on) tint else tint.copy(alpha = dim))
                        )
                    }
                    if (gi < glyphs.size - 1) Spacer(Modifier.width(dot.dp)) // inter-char gap
                }
            }
        }
    }
}

// 6 Grid — dot temp + text label
@Composable private fun Grid(d: WeatherData, accent: Color, m: Modifier) = Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
    DotText("${d.temp}°", tint = ink)
    Spacer(Modifier.height(14.dp))
    Text(d.place.uppercase(), fontSize = 11.sp, letterSpacing = 2.sp, color = soft(.55f), fontFamily = mono)
}

// 7 Panel — glyph beside dot temp
@Composable private fun Panel(d: WeatherData, accent: Color, m: Modifier) = Row(m, verticalAlignment = Alignment.CenterVertically) {
    StaticGlyph(d.condition, 40, accent = accent)
    Spacer(Modifier.width(20.dp))
    DotText("${d.temp}°", tint = ink)
}

// 8 Station — dot temp over uppercase condition
@Composable private fun Station(d: WeatherData, accent: Color, m: Modifier) = Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
    DotText("${d.temp}°", tint = ink)
    Spacer(Modifier.height(12.dp))
    Text(d.conditionLabel.uppercase(), fontSize = 12.sp, letterSpacing = 3.sp, color = soft(), fontFamily = mono)
}

// 9 Stacked — dot temp with HI/LO segment
@Composable private fun Stacked(d: WeatherData, accent: Color, m: Modifier) = Column(m) {
    DotText("${d.temp}°", tint = ink)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("HI ${d.hi}", fontSize = 12.sp, letterSpacing = 1.sp, color = soft(), fontFamily = mono)
        Text("LO ${d.lo}", fontSize = 12.sp, letterSpacing = 1.sp, color = soft(), fontFamily = mono)
    }
}

// 10 Board — dot temp with condition + wind segment (departure-board feel)
@Composable private fun Board(d: WeatherData, accent: Color, m: Modifier) = Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
    DotText("${d.temp}°", tint = ink)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(d.condition.name, fontSize = 12.sp, letterSpacing = 1.sp, color = soft(), fontFamily = mono)
        Text("WIND ${d.windMph}", fontSize = 12.sp, letterSpacing = 1.sp, color = soft(), fontFamily = mono)
    }
}

// ==================== REGISTRY SLICES (merged into WEATHER_STYLES) ====================
private const val SIMPLE = "Simple"
private const val DOT_MATRIX = "Dot Matrix"

val SIMPLE_STYLES: List<WeatherStyle> = listOf(
    WeatherStyle("plain", "Plain", SIMPLE) { d, a, m -> Plain(d, a, m) },
    WeatherStyle("center", "Center", SIMPLE) { d, a, m -> Center(d, a, m) },
    WeatherStyle("line", "Line", SIMPLE) { d, a, m -> Line(d, a, m) },
    WeatherStyle("stack", "Stack", SIMPLE) { d, a, m -> Stack(d, a, m) },
    WeatherStyle("faint", "Faint", SIMPLE) { d, a, m -> Faint(d, a, m) },
)

val DOT_MATRIX_STYLES: List<WeatherStyle> = listOf(
    WeatherStyle("grid", "Grid", DOT_MATRIX) { d, a, m -> Grid(d, a, m) },
    WeatherStyle("panel", "Panel", DOT_MATRIX) { d, a, m -> Panel(d, a, m) },
    WeatherStyle("station", "Station", DOT_MATRIX) { d, a, m -> Station(d, a, m) },
    WeatherStyle("stacked", "Stacked", DOT_MATRIX) { d, a, m -> Stacked(d, a, m) },
    WeatherStyle("board", "Board", DOT_MATRIX) { d, a, m -> Board(d, a, m) },
)
