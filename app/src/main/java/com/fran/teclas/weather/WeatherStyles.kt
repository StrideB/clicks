package com.fran.teclas.weather

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray

// ---------- Data model (mapped from the launcher's weather prefs — see MainActivity) ----------
enum class Condition { SUNNY, CLOUDY, RAIN, STORM, SNOW }

data class HourSlot(val label: String, val condition: Condition, val temp: Int)

data class WeatherData(
    val temp: Int,
    val feelsLike: Int,
    val humidity: Int,
    val windMph: Int,
    val condition: Condition,
    val conditionLabel: String,   // e.g. "Partly cloudy"
    val place: String,
    val hi: Int,
    val lo: Int,
    val hourly: List<HourSlot> = emptyList()
)

// A style the picker can list, select, persist and render.
data class WeatherStyle(
    val id: String,               // persisted in prefs: weather_widget_style
    val name: String,
    val category: String,         // picker section, e.g. "Animated" / "Simple" / "Dot Matrix"
    val render: @Composable (WeatherData, Color, Modifier) -> Unit
)

// Category section order for the picker gallery (Classic is rendered as its own lead section).
// Add a new family's name here to give it a section; unknown families fall to the end.
val WEATHER_CATEGORY_ORDER = listOf("Simple", "Dot Matrix", "Animated")

// The built-in native header keeps this id; only non-classic ids resolve through WEATHER_STYLES.
const val WEATHER_STYLE_CLASSIC_ID = "header"

fun conditionForWmoCode(code: Int): Condition = when (code) {
    0, 1 -> Condition.SUNNY
    2, 3, 45, 48 -> Condition.CLOUDY
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Condition.RAIN
    71, 73, 75, 77, 85, 86 -> Condition.SNOW
    95, 96, 99 -> Condition.STORM
    else -> Condition.SUNNY
}

fun parseHourlyJson(raw: String?): List<HourSlot> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            HourSlot(
                label = o.optString("h", "--"),
                condition = conditionForWmoCode(o.optInt("c", 0)),
                temp = o.optInt("t", 0)
            )
        }
    }.getOrDefault(emptyList())
}

private val glyphColor = Color.White

// Boxless styles float straight on the wallpaper — every glyph across all families carries
// this shadow so it stays legible on any photo. Shared with the Simple/Dot Matrix file.
internal val WeatherTextShadow = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 3f), 7f)

// File-local Text that bakes the shadow in for all 20 animated styles below.
@Composable
private fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    style: TextStyle = TextStyle.Default
) = androidx.compose.material3.Text(
    text = text,
    modifier = modifier,
    color = color,
    fontSize = fontSize,
    fontWeight = fontWeight,
    fontFamily = fontFamily,
    letterSpacing = letterSpacing,
    textAlign = textAlign,
    style = style.merge(TextStyle(shadow = WeatherTextShadow))
)

// ---------- Animated condition icon (Canvas, no external assets) ----------
@Composable
fun WeatherGlyph(condition: Condition, sizeDp: Int, tint: Color = glyphColor, accent: Color) {
    val transition = rememberInfiniteTransition(label = "wx")
    val spin by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(26000, easing = LinearEasing)), label = "spin"
    )
    val bob by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(3000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob"
    )
    val fall by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "fall"
    )

    Canvas(Modifier.size(sizeDp.dp)) {
        val s = size.minDimension
        val c = Offset(s / 2f, s / 2f)
        when (condition) {
            Condition.SUNNY -> {
                rotate(spin, pivot = c) {
                    for (i in 0 until 8) {
                        val a = Math.toRadians((i * 45).toDouble())
                        val r1 = s * 0.34f; val r2 = s * 0.46f
                        drawLine(
                            tint,
                            Offset(c.x + r1 * Math.cos(a).toFloat(), c.y + r1 * Math.sin(a).toFloat()),
                            Offset(c.x + r2 * Math.cos(a).toFloat(), c.y + r2 * Math.sin(a).toFloat()),
                            strokeWidth = s * 0.03f, cap = StrokeCap.Round
                        )
                    }
                }
                drawCircle(tint, radius = s * 0.18f, center = c)
            }
            Condition.CLOUDY -> drawCloud(tint, s, bob)
            Condition.RAIN -> { drawCloud(tint, s, bob); drawFall(tint, s, fall, drop = true) }
            Condition.STORM -> { drawCloud(tint, s, bob); drawBolt(accent, s) }
            Condition.SNOW -> { drawCloud(tint, s, bob); drawFall(tint, s, fall, drop = false) }
        }
    }
}

private fun DrawScope.drawCloud(tint: Color, s: Float, bob: Float) {
    val dy = (bob - 0.5f) * s * 0.05f
    val path = Path().apply {
        moveTo(s * 0.27f, s * 0.62f + dy)
        cubicTo(s * 0.10f, s * 0.60f + dy, s * 0.12f, s * 0.34f + dy, s * 0.30f, s * 0.33f + dy)
        cubicTo(s * 0.33f, s * 0.16f + dy, s * 0.66f, s * 0.16f + dy, s * 0.68f, s * 0.36f + dy)
        cubicTo(s * 0.86f, s * 0.35f + dy, s * 0.86f, s * 0.60f + dy, s * 0.70f, s * 0.62f + dy)
        close()
    }
    drawPath(path, tint)
}

private fun DrawScope.drawFall(tint: Color, s: Float, t: Float, drop: Boolean) {
    val xs = listOf(0.36f, 0.5f, 0.64f)
    xs.forEachIndexed { i, x ->
        val phase = (t + i * 0.33f) % 1f
        val y = s * (0.66f + phase * 0.16f)
        val alpha = (if (phase < 0.2f) phase / 0.2f else (1f - phase)).coerceIn(0f, 1f)
        if (drop) drawLine(tint.copy(alpha = alpha), Offset(s * x, y), Offset(s * x - s * 0.03f, y + s * 0.10f), strokeWidth = s * 0.02f, cap = StrokeCap.Round)
        else drawCircle(tint.copy(alpha = alpha), radius = s * 0.025f, center = Offset(s * x, y))
    }
}

private fun DrawScope.drawBolt(accent: Color, s: Float) {
    val p = Path().apply {
        moveTo(s * 0.50f, s * 0.56f); lineTo(s * 0.40f, s * 0.78f); lineTo(s * 0.49f, s * 0.78f)
        lineTo(s * 0.44f, s * 0.92f); lineTo(s * 0.62f, s * 0.68f); lineTo(s * 0.52f, s * 0.68f)
        lineTo(s * 0.58f, s * 0.56f); close()
    }
    drawPath(p, accent)
}

private val mono = FontFamily.Monospace
private val serif = FontFamily.Serif

// ============================ 20 STYLES ============================
// Each is boxless: transparent, no card. Uses WeatherData + accent.

// 1 Aurora — big thin numerals, floating icon above
@Composable fun Aurora(d: WeatherData, accent: Color, m: Modifier) = Column(m) {
    WeatherGlyph(d.condition, 60, accent = accent)
    Text("${d.temp}°", fontSize = 82.sp, fontWeight = FontWeight.Thin, color = glyphColor)
    Text(d.place, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = glyphColor)
    Text(d.conditionLabel, fontSize = 13.sp, color = glyphColor.copy(alpha = .6f))
}

// 2 Halo — icon over temp, centered
@Composable fun Halo(d: WeatherData, accent: Color, m: Modifier) = Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
    WeatherGlyph(d.condition, 60, accent = accent)
    Text("${d.temp}°", fontSize = 64.sp, fontWeight = FontWeight.Light, color = glyphColor)
    Text(d.place.uppercase(), fontSize = 13.sp, letterSpacing = 2.sp, color = glyphColor.copy(alpha = .6f))
}

// 3 Ledger — single line + hairline divider
@Composable fun Ledger(d: WeatherData, accent: Color, m: Modifier) = Row(m, verticalAlignment = Alignment.CenterVertically) {
    WeatherGlyph(d.condition, 44, accent = accent)
    Spacer(Modifier.width(14.dp))
    Text("${d.temp}°", fontSize = 56.sp, fontWeight = FontWeight.Light, color = glyphColor)
    Spacer(Modifier.width(14.dp)); Box(Modifier.width(1.dp).height(48.dp).background(glyphColor.copy(alpha = .25f))); Spacer(Modifier.width(14.dp))
    Column { Text(d.place, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = glyphColor); Text(d.conditionLabel, fontSize = 13.sp, color = glyphColor.copy(alpha = .6f)) }
}

// 4 Broadsheet — oversized bold temp, tiny label
@Composable fun Broadsheet(d: WeatherData, accent: Color, m: Modifier) = Column(m) {
    Text("${d.temp}°", fontSize = 110.sp, fontWeight = FontWeight.Black, color = glyphColor, letterSpacing = (-4).sp)
    Text(d.place.uppercase(), fontSize = 12.sp, letterSpacing = 4.sp, fontWeight = FontWeight.SemiBold, color = glyphColor.copy(alpha = .7f))
}

// 5 Almanac — classic serif, italic condition
@Composable fun Almanac(d: WeatherData, accent: Color, m: Modifier) = Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
    WeatherGlyph(d.condition, 56, accent = accent)
    Text("${d.temp}°", fontSize = 74.sp, fontFamily = serif, color = glyphColor)
    Text("${d.place}, ${d.conditionLabel.lowercase()}", fontSize = 15.sp, fontFamily = serif, color = glyphColor.copy(alpha = .7f))
}

// 6 Console — monospace readout
@Composable fun Console(d: WeatherData, accent: Color, m: Modifier) = Column(m) {
    Text("${d.temp}°", fontSize = 52.sp, fontFamily = mono, fontWeight = FontWeight.Medium, color = glyphColor)
    Text(d.place.uppercase(), fontSize = 12.sp, fontFamily = mono, color = glyphColor.copy(alpha = .6f))
    Text("cond ${d.conditionLabel.lowercase()}", fontSize = 12.sp, fontFamily = mono, color = accent)
    Text("hi ${d.hi} · lo ${d.lo} · wind ${d.windMph}", fontSize = 12.sp, fontFamily = mono, color = glyphColor.copy(alpha = .6f))
}

// 7 Orbit — temp inside a ring
@Composable fun Orbit(d: WeatherData, accent: Color, m: Modifier) = Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(120.dp)) { drawCircle(glyphColor, radius = size.minDimension / 2 - 2.dp.toPx(), style = Stroke(width = 2.dp.toPx())) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${d.temp}°", fontSize = 44.sp, fontWeight = FontWeight.Light, color = glyphColor)
            Text(d.conditionLabel, fontSize = 11.sp, letterSpacing = 1.sp, color = glyphColor.copy(alpha = .6f))
        }
    }
    Spacer(Modifier.height(12.dp)); Text(d.place.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = glyphColor)
}

// 8 Duo — icon + temp + details, three columns
@Composable fun Duo(d: WeatherData, accent: Color, m: Modifier) = Row(m, verticalAlignment = Alignment.CenterVertically) {
    WeatherGlyph(d.condition, 56, accent = accent); Spacer(Modifier.width(20.dp))
    Text("${d.temp}°", fontSize = 70.sp, fontWeight = FontWeight.Thin, color = glyphColor); Spacer(Modifier.width(20.dp))
    Column { Text(d.place, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = glyphColor); Text("${d.conditionLabel} · H${d.hi} L${d.lo}", fontSize = 12.sp, color = glyphColor.copy(alpha = .6f)) }
}

// 9 Whisper — ultralight, minimal
@Composable fun Whisper(d: WeatherData, accent: Color, m: Modifier) = Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
    Text("${d.temp}°", fontSize = 96.sp, fontWeight = FontWeight.Thin, color = glyphColor, letterSpacing = (-4).sp)
    Text(d.place.uppercase(), fontSize = 14.sp, letterSpacing = 3.sp, color = glyphColor.copy(alpha = .5f))
}

// 10 Tally — centered, H/L chips
@Composable fun Tally(d: WeatherData, accent: Color, m: Modifier) = Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
    WeatherGlyph(d.condition, 56, accent = accent)
    Text("${d.temp}°", fontSize = 60.sp, fontWeight = FontWeight.Light, color = glyphColor)
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("H:${d.hi}°", fontSize = 13.sp, color = glyphColor.copy(alpha = .7f))
        Text("L:${d.lo}°", fontSize = 13.sp, color = glyphColor.copy(alpha = .7f))
    }
}

// 11 Impact — heavy condensed poster type
@Composable fun Impact(d: WeatherData, accent: Color, m: Modifier) = Column(m) {
    Text("${d.temp}°", fontSize = 92.sp, fontWeight = FontWeight.Black, color = glyphColor, letterSpacing = (-4).sp)
    Text(d.place.uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = glyphColor)
    Text(d.conditionLabel, fontSize = 12.sp, color = glyphColor.copy(alpha = .55f))
}

// 12 Lozenge — soft glass pill (the one subtle container)
@Composable fun Lozenge(d: WeatherData, accent: Color, m: Modifier) = Row(
    m.background(Color.White.copy(alpha = .12f), RoundedCornerShape(40.dp)).padding(horizontal = 22.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    WeatherGlyph(d.condition, 34, accent = accent); Spacer(Modifier.width(14.dp))
    Text("${d.temp}°", fontSize = 42.sp, fontWeight = FontWeight.Light, color = glyphColor); Spacer(Modifier.width(14.dp))
    Column { Text(d.place, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = glyphColor); Text(d.conditionLabel, fontSize = 11.sp, color = glyphColor.copy(alpha = .6f)) }
}

// 13 Marker — accent underline bar
@Composable fun Marker(d: WeatherData, accent: Color, m: Modifier) = Column(m) {
    Text("${d.temp}°", fontSize = 76.sp, fontWeight = FontWeight.Light, color = glyphColor)
    Box(Modifier.padding(vertical = 10.dp).width(56.dp).height(3.dp).background(accent, RoundedCornerShape(2.dp)))
    Text(d.place, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = glyphColor)
    Text(d.conditionLabel, fontSize = 12.sp, color = glyphColor.copy(alpha = .6f))
}

// 14 Accent — one digit highlighted
@Composable fun Accent(d: WeatherData, accent: Color, m: Modifier) = Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
    WeatherGlyph(d.condition, 56, accent = accent)
    val s = d.temp.toString()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (s.length > 1) Text(s.dropLast(1), fontSize = 80.sp, fontWeight = FontWeight.Thin, color = glyphColor)
        Text(s.takeLast(1), fontSize = 80.sp, fontWeight = FontWeight.SemiBold, color = accent)
        Text("°", fontSize = 40.sp, color = glyphColor)
    }
    Text(d.place.uppercase(), fontSize = 13.sp, letterSpacing = 2.sp, color = glyphColor.copy(alpha = .6f))
}

// 15 Margin — right aligned editorial
@Composable fun Margin(d: WeatherData, accent: Color, m: Modifier) = Column(m, horizontalAlignment = Alignment.End) {
    Text("${d.temp}°", fontSize = 72.sp, fontWeight = FontWeight.Thin, color = glyphColor)
    Text(d.place, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = glyphColor)
    Text(d.conditionLabel, fontSize = 12.sp, color = glyphColor.copy(alpha = .6f))
}

// 16 Timeline — horizontal hourly strip
@Composable fun Timeline(d: WeatherData, accent: Color, m: Modifier) = Row(m, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
    val slots = d.hourly.ifEmpty { listOf(HourSlot("Now", d.condition, d.temp)) }
    slots.forEachIndexed { i, h ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(h.label, fontSize = 11.sp, color = if (i == 0) accent else glyphColor.copy(alpha = .55f), fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal)
            WeatherGlyph(h.condition, 20, accent = accent)
            Text("${h.temp}°", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = glyphColor)
        }
    }
}

// 17 Prism — gradient-filled temperature
@Composable fun Prism(d: WeatherData, accent: Color, m: Modifier) = Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
        "${d.temp}°", fontSize = 92.sp, fontWeight = FontWeight.Bold, letterSpacing = (-3).sp,
        style = TextStyle(brush = Brush.linearGradient(listOf(Color.White, accent)))
    )
    Text(d.place.uppercase(), fontSize = 13.sp, letterSpacing = 2.sp, color = glyphColor.copy(alpha = .7f))
}

// 18 Docket — label-left, condition + hi/lo stacked
@Composable fun Docket(d: WeatherData, accent: Color, m: Modifier) = Row(m, verticalAlignment = Alignment.CenterVertically) {
    WeatherGlyph(d.condition, 56, accent = accent); Spacer(Modifier.width(18.dp))
    Text("${d.temp}°", fontSize = 58.sp, fontWeight = FontWeight.Light, color = glyphColor); Spacer(Modifier.width(18.dp))
    Column {
        Text(d.place, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = glyphColor)
        Text(d.conditionLabel, fontSize = 12.sp, color = glyphColor.copy(alpha = .6f))
        Text("H${d.hi} · L${d.lo}", fontSize = 12.sp, color = glyphColor.copy(alpha = .6f))
    }
}

// 19 Beacon — oversized icon, small temp
@Composable fun Beacon(d: WeatherData, accent: Color, m: Modifier) = Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
    WeatherGlyph(d.condition, 82, accent = accent)
    Spacer(Modifier.height(12.dp))
    Text("${d.temp}°", fontSize = 48.sp, fontWeight = FontWeight.Thin, color = glyphColor)
}

// 20 Dossier — temp with high/low/wind stat row
@Composable fun Dossier(d: WeatherData, accent: Color, m: Modifier) = Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
    Text("${d.temp}°", fontSize = 84.sp, fontWeight = FontWeight.Thin, color = glyphColor, letterSpacing = (-3).sp)
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Stat("${d.hi}°", "High"); Stat("${d.lo}°", "Low"); Stat("${d.windMph}mph", "Wind")
    }
}
@Composable private fun Stat(value: String, label: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = glyphColor)
    Text(label, fontSize = 11.sp, color = glyphColor.copy(alpha = .6f))
}

// ============================ REGISTRY ============================
// The animated family (each renders WeatherGlyph). Simple + Dot Matrix families live in
// WeatherStylesSimpleMatrix.kt and are folded into WEATHER_STYLES below.
private const val ANIMATED = "Animated"
val ANIMATED_STYLES: List<WeatherStyle> = listOf(
    WeatherStyle("aurora", "Aurora", ANIMATED) { d, a, m -> Aurora(d, a, m) },
    WeatherStyle("halo", "Halo", ANIMATED) { d, a, m -> Halo(d, a, m) },
    WeatherStyle("ledger", "Ledger", ANIMATED) { d, a, m -> Ledger(d, a, m) },
    WeatherStyle("broadsheet", "Broadsheet", ANIMATED) { d, a, m -> Broadsheet(d, a, m) },
    WeatherStyle("almanac", "Almanac", ANIMATED) { d, a, m -> Almanac(d, a, m) },
    WeatherStyle("console", "Console", ANIMATED) { d, a, m -> Console(d, a, m) },
    WeatherStyle("orbit", "Orbit", ANIMATED) { d, a, m -> Orbit(d, a, m) },
    WeatherStyle("duo", "Duo", ANIMATED) { d, a, m -> Duo(d, a, m) },
    WeatherStyle("whisper", "Whisper", ANIMATED) { d, a, m -> Whisper(d, a, m) },
    WeatherStyle("tally", "Tally", ANIMATED) { d, a, m -> Tally(d, a, m) },
    WeatherStyle("impact", "Impact", ANIMATED) { d, a, m -> Impact(d, a, m) },
    WeatherStyle("lozenge", "Lozenge", ANIMATED) { d, a, m -> Lozenge(d, a, m) },
    WeatherStyle("marker", "Marker", ANIMATED) { d, a, m -> Marker(d, a, m) },
    WeatherStyle("accent", "Accent", ANIMATED) { d, a, m -> Accent(d, a, m) },
    WeatherStyle("margin", "Margin", ANIMATED) { d, a, m -> Margin(d, a, m) },
    WeatherStyle("timeline", "Timeline", ANIMATED) { d, a, m -> Timeline(d, a, m) },
    WeatherStyle("prism", "Prism", ANIMATED) { d, a, m -> Prism(d, a, m) },
    WeatherStyle("docket", "Docket", ANIMATED) { d, a, m -> Docket(d, a, m) },
    WeatherStyle("beacon", "Beacon", ANIMATED) { d, a, m -> Beacon(d, a, m) },
    WeatherStyle("dossier", "Dossier", ANIMATED) { d, a, m -> Dossier(d, a, m) },
)

// Master registry the picker, placement flow and persistence all read. `by lazy` so it is
// robust to cross-file top-level init order (SIMPLE/DOT_MATRIX_STYLES live in another file).
val WEATHER_STYLES: List<WeatherStyle> by lazy {
    ANIMATED_STYLES + SIMPLE_STYLES + DOT_MATRIX_STYLES
}

fun weatherStyleById(id: String?): WeatherStyle =
    WEATHER_STYLES.firstOrNull { it.id == id } ?: WEATHER_STYLES.first()
