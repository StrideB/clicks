package com.fran.teclas.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Almanac/editorial weather styles are deliberately boxless: no panels, fills, borders,
// animated glyphs, or looping transitions. Structure comes from serif typography and hairlines.

private const val ALMANAC = "Almanac"
private val ink = Color.White
private val dim = Color.White.copy(alpha = 0.68f)
private val rule = Color.White.copy(alpha = 0.42f)
private val serif = FontFamily.Serif

@Composable
private fun EText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ink,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) = androidx.compose.material3.Text(
    text = text,
    modifier = modifier,
    color = color,
    fontSize = fontSize,
    fontFamily = serif,
    fontWeight = fontWeight,
    fontStyle = fontStyle,
    letterSpacing = letterSpacing,
    lineHeight = lineHeight,
    textAlign = textAlign,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
    style = TextStyle(shadow = WeatherTextShadow)
)

@Composable
private fun Rule(modifier: Modifier = Modifier.fillMaxWidth(), alpha: Float = 0.42f) {
    Canvas(modifier.height(1.dp)) {
        drawLine(rule.copy(alpha = alpha), Offset.Zero, Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
    }
}

@Composable
private fun DoubleRule(modifier: Modifier = Modifier.fillMaxWidth()) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Rule(alpha = 0.48f)
        Rule(alpha = 0.24f)
    }
}

private fun placeDate(d: WeatherData): String =
    "${d.place.uppercase()} · TODAY"

private fun lead(d: WeatherData): String =
    "${d.conditionLabel} with a high of ${d.hi}°, low of ${d.lo}°, and ${d.windMph} mph wind."

private fun skyLine(d: WeatherData): String = when (d.condition) {
    Condition.SUNNY -> "The sky writes in bright, clear margins."
    Condition.CLOUDY -> "A soft ceiling settles over the day."
    Condition.RAIN -> "Rain keeps its quiet counsel outside."
    Condition.STORM -> "The sky is loud enough to answer back."
    Condition.SNOW -> "The air turns pale and deliberate."
}

@Composable private fun Masthead(d: WeatherData, accent: Color, m: Modifier) = Column(
    m.width(IntrinsicSize.Min),
    horizontalAlignment = Alignment.CenterHorizontally
) {
    Rule()
    EText("THE DAILY WEATHER", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp)
    Rule()
    EText("${d.temp}°", fontSize = 88.sp, fontWeight = FontWeight.Normal, lineHeight = 82.sp)
    EText(d.conditionLabel, fontSize = 15.sp, fontStyle = FontStyle.Italic, color = dim)
    Spacer(Modifier.height(7.dp))
    Rule()
    EText(placeDate(d), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = dim)
    Rule()
}

@Composable private fun Dateline(d: WeatherData, accent: Color, m: Modifier) = Column(m.width(250.dp)) {
    EText("${d.place.uppercase()} — TODAY", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = dim)
    Spacer(Modifier.height(6.dp))
    Rule()
    Spacer(Modifier.height(7.dp))
    EText("${d.temp}°", fontSize = 82.sp, lineHeight = 74.sp)
    EText(lead(d), fontSize = 17.sp, lineHeight = 21.sp, maxLines = 3)
}

@Composable private fun Report(d: WeatherData, accent: Color, m: Modifier) = Column(
    m.width(250.dp),
    horizontalAlignment = Alignment.CenterHorizontally
) {
    EText("WEATHER REPORT", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = dim)
    Spacer(Modifier.height(5.dp))
    DoubleRule()
    Spacer(Modifier.height(8.dp))
    EText("${d.conditionLabel} over ${d.place}", fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp, textAlign = TextAlign.Center, maxLines = 2)
    EText("${d.temp}°", fontSize = 56.sp, lineHeight = 54.sp)
    EText("${d.place.lowercase()} · feels ${d.feelsLike}°", fontSize = 14.sp, fontStyle = FontStyle.Italic, color = dim)
}

@Composable private fun Index(d: WeatherData, accent: Color, m: Modifier) = Column(m.width(270.dp)) {
    EText("TODAY AT A GLANCE", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
    Spacer(Modifier.height(8.dp))
    Rule()
    Spacer(Modifier.height(4.dp))
    IndexRow("Temperature", "${d.temp}°")
    IndexRow("Feels like", "${d.feelsLike}°")
    IndexRow("High / Low", "${d.hi}° / ${d.lo}°")
    IndexRow("Humidity", "${d.humidity}%")
    IndexRow("Wind", "${d.windMph} mph")
}

@Composable private fun IndexRow(label: String, value: String) = Row(
    Modifier.fillMaxWidth().padding(vertical = 3.dp),
    verticalAlignment = Alignment.Bottom
) {
    EText(label, fontSize = 14.sp, color = dim)
    Canvas(Modifier.weight(1f).padding(horizontal = 7.dp).height(1.dp)) {
        val step = 6.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawCircle(rule.copy(alpha = 0.45f), radius = 0.75.dp.toPx(), center = Offset(x, 0f))
            x += step
        }
    }
    EText(value, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}

@Composable private fun BigNumber(d: WeatherData, accent: Color, m: Modifier) = Column(m) {
    EText(d.place.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = dim)
    EText("${d.temp}°", fontSize = 116.sp, lineHeight = 100.sp, letterSpacing = (-4).sp)
    EText("${d.conditionLabel.lowercase()}, feels like ${d.feelsLike}°", fontSize = 16.sp, fontStyle = FontStyle.Italic, color = dim)
}

@Composable private fun EditorialBroadsheet(d: WeatherData, accent: Color, m: Modifier) = Row(
    m.width(278.dp),
    verticalAlignment = Alignment.CenterVertically
) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        EText("NOW", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = dim)
        EText("${d.temp}°", fontSize = 64.sp, lineHeight = 58.sp)
    }
    Canvas(Modifier.width(1.dp).height(86.dp)) {
        drawLine(rule, Offset.Zero, Offset(0f, size.height), strokeWidth = 1.dp.toPx())
    }
    Column(Modifier.weight(1f).padding(start = 18.dp)) {
        EText("DETAILS", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = dim)
        EText(d.conditionLabel, fontSize = 15.sp, maxLines = 1)
        EText("H ${d.hi}° / L ${d.lo}°", fontSize = 14.sp, color = dim)
        EText("${d.windMph} mph wind", fontSize = 14.sp, color = dim)
        EText("${d.humidity}% humidity", fontSize = 14.sp, color = dim)
    }
}

@Composable private fun Ornament(d: WeatherData, accent: Color, m: Modifier) = Column(
    m.width(248.dp),
    horizontalAlignment = Alignment.CenterHorizontally
) {
    EText("❦  ✦  ❦", fontSize = 18.sp, color = dim)
    Spacer(Modifier.height(6.dp))
    EText("${d.temp}°", fontSize = 78.sp, lineHeight = 72.sp)
    EText("${d.conditionLabel.uppercase()} · ${d.place.uppercase()}", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, color = dim, textAlign = TextAlign.Center)
    Spacer(Modifier.height(6.dp))
    EText("❦  ✦  ❦", fontSize = 18.sp, color = dim)
}

@Composable private fun Forecast(d: WeatherData, accent: Color, m: Modifier) = Column(m.width(270.dp)) {
    EText("OUTLOOK", fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
    Spacer(Modifier.height(5.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        EText("${d.temp}°", fontSize = 58.sp, lineHeight = 54.sp)
        Spacer(Modifier.width(12.dp))
        EText("today · ${d.conditionLabel.lowercase()}", fontSize = 14.sp, color = dim, modifier = Modifier.padding(bottom = 9.dp))
    }
    Rule()
    val rows = d.hourly.take(4).ifEmpty { listOf(HourSlot("Today", d.condition, d.temp)) }
    rows.forEach { slot ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            EText(slot.label.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = dim, modifier = Modifier.weight(1f))
            EText("${slot.temp}°", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            EText(slot.condition.name.lowercase(), fontSize = 13.sp, color = dim, modifier = Modifier.width(70.dp))
        }
    }
}

@Composable private fun Quotation(d: WeatherData, accent: Color, m: Modifier) = Column(
    m.width(260.dp),
    horizontalAlignment = Alignment.CenterHorizontally
) {
    EText("“${skyLine(d)}”", fontSize = 20.sp, fontStyle = FontStyle.Italic, lineHeight = 23.sp, textAlign = TextAlign.Center, maxLines = 3)
    Spacer(Modifier.height(8.dp))
    EText("${d.temp}°", fontSize = 70.sp, lineHeight = 64.sp)
    EText("${d.place.uppercase()} · NOW", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.7.sp, color = dim)
}

@Composable private fun Monogram(d: WeatherData, accent: Color, m: Modifier) = Row(
    m.width(278.dp),
    verticalAlignment = Alignment.Top
) {
    EText(d.temp.toString(), fontSize = 80.sp, lineHeight = 70.sp, fontWeight = FontWeight.Bold)
    Column(Modifier.padding(start = 10.dp, top = 8.dp)) {
        EText("° ${d.conditionLabel.lowercase()} in ${d.place}", fontSize = 18.sp, lineHeight = 21.sp, maxLines = 2)
        Spacer(Modifier.height(5.dp))
        EText("Feels ${d.feelsLike}°. High ${d.hi}°, low ${d.lo}°. Wind ${d.windMph} mph.", fontSize = 14.sp, lineHeight = 18.sp, color = dim, maxLines = 3)
    }
}

val ALMANAC_STYLES: List<WeatherStyle> = listOf(
    WeatherStyle("almanac_masthead", "Masthead", ALMANAC) { d, a, m -> Masthead(d, a, m) },
    WeatherStyle("almanac_dateline", "Dateline", ALMANAC) { d, a, m -> Dateline(d, a, m) },
    WeatherStyle("almanac_report", "Report", ALMANAC) { d, a, m -> Report(d, a, m) },
    WeatherStyle("almanac_index", "Index", ALMANAC) { d, a, m -> Index(d, a, m) },
    WeatherStyle("almanac_big_number", "Big Number", ALMANAC) { d, a, m -> BigNumber(d, a, m) },
    WeatherStyle("almanac_broadsheet", "Broadsheet", ALMANAC) { d, a, m -> EditorialBroadsheet(d, a, m) },
    WeatherStyle("almanac_ornament", "Ornament", ALMANAC) { d, a, m -> Ornament(d, a, m) },
    WeatherStyle("almanac_forecast", "Forecast", ALMANAC) { d, a, m -> Forecast(d, a, m) },
    WeatherStyle("almanac_quotation", "Quotation", ALMANAC) { d, a, m -> Quotation(d, a, m) },
    WeatherStyle("almanac_monogram", "Monogram", ALMANAC) { d, a, m -> Monogram(d, a, m) },
)
