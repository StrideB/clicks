package com.fran.teclas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

private val AiScrim = Color(0xFF181B21)
private val AiSurface = Color(0xFF181B21)
private val AiSurfaceHigh = Color(0xFF2A2F3A)
private val AiSurfaceLow = Color(0xFF05070A)
private val AiHairline = Color.White.copy(alpha = 0.13f)
private val AiText = Color(0xFFE8EBEF)
private val AiTextDim = Color(0xFF899099)
private val AiTextFaint = Color(0xFF565D66)
private val GeminiBlue = Color(0xFF4285F4)
private val GeminiViolet = Color(0xFF9B72F0)
private val GeminiPink = Color(0xFFE8639B)
private val GeminiBrush = Brush.linearGradient(listOf(GeminiBlue, GeminiViolet, GeminiPink))

@Composable
fun TeclasAiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val dark = darkColorScheme(
        background = AiScrim,
        surface = AiSurface,
        surfaceVariant = AiSurfaceHigh,
        primary = GeminiBlue,
        secondary = GeminiViolet,
        tertiary = GeminiPink,
        onBackground = AiText,
        onSurface = AiText,
        onSurfaceVariant = AiTextDim
    )
    val light = lightColorScheme(
        background = Color(0xFFF3F3F6),
        surface = Color(0xFFE7E8ED),
        surfaceVariant = Color(0xFFFFFFFF),
        primary = GeminiBlue,
        secondary = GeminiViolet,
        tertiary = GeminiPink,
        onBackground = Color(0xFF111217),
        onSurface = Color(0xFF111217),
        onSurfaceVariant = Color(0xFF535560)
    )
    MaterialTheme(colorScheme = if (darkTheme) dark else light, content = content)
}

object InlineMarkdown {
    fun render(raw: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < raw.length) {
            when {
                raw.startsWith("**", i) -> {
                    val end = raw.indexOf("**", startIndex = i + 2)
                    if (end > i) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = AiText))
                        append(raw.substring(i + 2, end))
                        pop()
                        i = end + 2
                    } else {
                        append(raw[i++])
                    }
                }
                raw[i] == '`' -> {
                    val end = raw.indexOf('`', startIndex = i + 1)
                    if (end > i) {
                        pushStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFD8E4FF),
                                background = Color.White.copy(alpha = 0.08f)
                            )
                        )
                        append(raw.substring(i + 1, end))
                        pop()
                        i = end + 1
                    } else {
                        append(raw[i++])
                    }
                }
                raw[i] == '*' -> {
                    val end = raw.indexOf('*', startIndex = i + 1)
                    if (end > i) {
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = AiText))
                        append(raw.substring(i + 1, end))
                        pop()
                        i = end + 1
                    } else {
                        append(raw[i++])
                    }
                }
                else -> append(raw[i++])
            }
        }
    }
}

object CalendarAnswerParser {
    fun parse(raw: String): CalendarEvent? {
        val clean = raw.trim()
        if (clean.isBlank()) return null
        val lower = clean.lowercase(Locale.US)
        val looksCalendar = listOf("appointment", "meeting", "event", "calendar", "tomorrow", "today", "at ")
            .count { lower.contains(it) } >= 2
        if (!looksCalendar) return null

        val joinUrl = Regex("""https?://\S+""").find(clean)?.value?.trimEnd('.', ')', ']')
        val timeRange = Regex("""(?i)\b(\d{1,2}(?::\d{2})?\s*(?:am|pm))\s*(?:-|to|–|—)\s*(\d{1,2}(?::\d{2})?\s*(?:am|pm))""")
            .find(clean)
        val singleTime = Regex("""(?i)\b(?:at\s+)?(\d{1,2}(?::\d{2})?\s*(?:am|pm))""").find(clean)
        val dayLabel = when {
            lower.contains("tomorrow") -> "TOMORROW"
            lower.contains("today") -> "TODAY"
            else -> Regex("""(?i)\b(mon|tue|wed|thu|fri|sat|sun)[a-z]*\b""").find(clean)?.value?.uppercase(Locale.US) ?: "UP NEXT"
        }
        val timeLabel = timeRange?.let { "${it.groupValues[1].uppercase(Locale.US)} - ${it.groupValues[2].uppercase(Locale.US)}" }
            ?: singleTime?.let { it.groupValues[1].uppercase(Locale.US) }
            ?: "Time not found"
        val title = titleFrom(clean)
        val location = Regex("""(?i)\b(?:at|in|location:)\s+([A-Z0-9][A-Za-z0-9 .,'&-]{2,})(?:\.|,|\n|$)""")
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeUnless { it.contains(Regex("""\b(am|pm)\b""", RegexOption.IGNORE_CASE)) }
            ?.trim()
            .orEmpty()
        val start = startMillis(dayLabel, singleTime?.groupValues?.getOrNull(1) ?: timeRange?.groupValues?.getOrNull(1))
        return CalendarEvent(
            eventId = -1L,
            title = title,
            timeLabel = timeLabel,
            location = location,
            beginMs = start,
            endMs = start + 60 * 60 * 1000L,
            dayLabel = dayLabel,
            joinUrl = joinUrl
        )
    }

    private fun titleFrom(raw: String): String {
        val withoutMarkdown = raw
            .replace("**", "")
            .replace("*", "")
            .replace("`", "")
        val quoted = Regex(""""([^"]{3,80})"""").find(withoutMarkdown)?.groupValues?.getOrNull(1)
        if (!quoted.isNullOrBlank()) return quoted.trim()
        val afterColon = withoutMarkdown.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains(":") && !it.startsWith("http", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
        if (!afterColon.isNullOrBlank()) return afterColon.take(64)
        return withoutMarkdown
            .replace(Regex("""(?i)\b(your|next|upcoming|calendar|appointment|meeting|event|is|on|at|tomorrow|today)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '.', ':', '-')
            .ifBlank { "Calendar event" }
            .take(64)
    }

    private fun startMillis(dayLabel: String, timeText: String?): Long {
        val baseDate = when (dayLabel) {
            "TOMORROW" -> LocalDate.now().plusDays(1)
            else -> LocalDate.now()
        }
        val time = parseTime(timeText) ?: LocalTime.NOON
        return baseDate.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun parseTime(raw: String?): LocalTime? {
        if (raw.isNullOrBlank()) return null
        val clean = raw.replace(" ", "").uppercase(Locale.US)
        return runCatching { LocalTime.parse(clean, DateTimeFormatter.ofPattern("h:mma", Locale.US)) }
            .getOrElse {
                runCatching { LocalTime.parse(clean, DateTimeFormatter.ofPattern("ha", Locale.US)) }.getOrNull()
            }
    }
}

@Composable
fun TeclasAiQueryFlow(
    query: String,
    answer: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    glassEffects: Boolean = true,
    loading: Boolean = false,
    askedActive: Boolean = false,
    calendarEvent: CalendarEvent? = null,
    onAskedClick: () -> Unit = {},
    onJoin: (String) -> Unit = {},
    onOpenCalendar: (CalendarEvent) -> Unit = {}
) {
    TeclasAiTheme {
        val parsedEvent = remember(answer, calendarEvent) { calendarEvent ?: CalendarAnswerParser.parse(answer) }
        Box(
            modifier
                .fillMaxSize()
                .background(AiScrim.copy(alpha = 0.42f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .shadow(22.dp, RoundedCornerShape(28.dp), ambientColor = Color.Black.copy(alpha = 0.55f), spotColor = Color.Black.copy(alpha = 0.85f))
                    .clip(RoundedCornerShape(28.dp))
                    .then(if (glassEffects) Modifier.machinedShell() else Modifier.machinedShellClassic())
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                TeclasAiHeader(onClose)
                Spacer(Modifier.height(12.dp))
                SectionLabel("ASKED")
                Spacer(Modifier.height(6.dp))
                PressedQueryField(query, active = askedActive, onClick = onAskedClick)
                Spacer(Modifier.height(16.dp))
                SectionLabel(if (loading) "GEMINI IS THINKING" else "GEMINI", glowing = true)
                Spacer(Modifier.height(8.dp))
                if (loading) {
                    AnswerTextCard("Thinking through it...")
                } else if (parsedEvent != null) {
                    CalendarEventCard(parsedEvent, onJoin = onJoin, onOpenCalendar = onOpenCalendar)
                } else {
                    AnswerTextCard(answer)
                }
            }
        }
    }
}

@Composable
private fun TeclasAiHeader(onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(GeminiBrush)
                .innerBevel(RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            BasicText("C", style = TextStyle(color = Color(0xFF08090C), fontSize = 16.sp, fontWeight = FontWeight.Black))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            BasicText("Teclas AI", style = TextStyle(color = AiText, fontSize = 15.sp, fontWeight = FontWeight.Bold))
            BasicText("Gemini", style = TextStyle(color = AiTextFaint, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp))
        }
        GlassButton("X", compact = true, onClick = onClose)
    }
}

@Composable
private fun SectionLabel(text: String, glowing: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (glowing) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(GeminiBrush)
            )
            Spacer(Modifier.width(7.dp))
        }
        BasicText(
            text,
            style = TextStyle(
                color = if (glowing) GeminiPink else AiTextFaint,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.2.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

@Composable
private fun PressedQueryField(
    query: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .pressedInset()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        BasicText(
            when {
                query.isNotBlank() && active -> "$query|"
                query.isNotBlank() -> query
                active -> "Ask a follow-up…|"
                else -> "Ask Gemini"
            },
            style = TextStyle(
                color = if (query.isBlank() && active) AiTextDim else AiText,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

@Composable
private fun AnswerTextCard(raw: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .shadow(10.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black.copy(alpha = 0.34f))
            .clip(RoundedCornerShape(22.dp))
            .raisedPanel()
            .padding(15.dp)
    ) {
        BasicText(
            InlineMarkdown.render(raw),
            style = TextStyle(color = AiText, fontSize = 14.5.sp, lineHeight = 21.sp)
        )
    }
}

@Composable
fun CalendarEventCard(
    event: CalendarEvent,
    modifier: Modifier = Modifier,
    onJoin: (String) -> Unit = {},
    onOpenCalendar: (CalendarEvent) -> Unit = {}
) {
    Row(
        modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(24.dp), ambientColor = Color.Black.copy(alpha = 0.40f))
            .clip(RoundedCornerShape(24.dp))
            .raisedPanel()
            .padding(0.dp)
    ) {
        Box(
            Modifier
                .width(7.dp)
                .height(184.dp)
                .background(GeminiBrush)
                .drawWithContent {
                    drawContent()
                    drawRect(Color.White.copy(alpha = 0.16f), topLeft = Offset(size.width - 1.dp.toPx(), 0f), size = Size(1.dp.toPx(), size.height))
                }
        )
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .pressedInset()
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    BasicText(
                        event.dayLabel.ifBlank { calendarDayLabel(event.beginMs) },
                        style = TextStyle(color = AiText, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                BasicText(
                    countdownLabel(event.beginMs),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = GeminiBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                )
            }
            BasicText(
                event.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = AiText, fontSize = 20.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
            )
            BasicText(
                "◷ ${event.timeLabel.ifBlank { calendarTimeLabel(event.beginMs, event.endMs) }}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = AiTextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            )
            if (event.location.isNotBlank()) {
                BasicText(
                    "⌖ ${event.location}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = AiTextFaint, fontSize = 12.sp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                GlassButton("Join", enabled = !event.joinUrl.isNullOrBlank(), onClick = { event.joinUrl?.let(onJoin) })
                GlassButton("Open calendar", onClick = { onOpenCalendar(event) })
            }
        }
    }
}

@Composable
private fun GlassButton(
    text: String,
    compact: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .graphicsLayer { translationY = if (pressed) 2.dp.toPx() else 0f }
            .shadow(if (pressed) 2.dp else 7.dp, RoundedCornerShape(if (compact) 12.dp else 16.dp))
            .clip(RoundedCornerShape(if (compact) 12.dp else 16.dp))
            .glassButtonSurface(enabled)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 14.dp, vertical = if (compact) 8.dp else 10.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text,
            maxLines = 1,
            style = TextStyle(color = if (enabled) AiText else AiTextFaint, fontSize = if (compact) 12.sp else 12.5.sp, fontWeight = FontWeight.Bold)
        )
    }
}

private fun Modifier.machinedShell(): Modifier = drawWithContent {
    val radius = 28.dp.toPx()
    drawRoundRect(
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.16f),
                AiSurfaceHigh.copy(alpha = 0.58f),
                AiSurface.copy(alpha = 0.50f),
                AiSurfaceLow.copy(alpha = 0.76f)
            )
        ),
        cornerRadius = CornerRadius(radius, radius)
    )
    drawRoundRect(
        Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.05f), Color.Transparent),
            center = Offset(size.width * 0.46f, 24.dp.toPx()),
            radius = size.width * 0.72f
        ),
        cornerRadius = CornerRadius(radius, radius)
    )
    drawRoundRect(Color.White.copy(alpha = 0.22f), size = Size(size.width, 1.4.dp.toPx()), cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(Color.Black.copy(alpha = 0.38f), topLeft = Offset(0f, size.height - 7.dp.toPx()), size = Size(size.width, 7.dp.toPx()), cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.42f))),
        style = Stroke(1.dp.toPx()),
        cornerRadius = CornerRadius(radius, radius)
    )
    var sx = -size.height
    while (sx < size.width + size.height) {
        drawLine(
            Color.White.copy(alpha = 0.07f),
            Offset(sx, 18.dp.toPx()),
            Offset(sx + size.height * 0.42f, size.height - 24.dp.toPx()),
            strokeWidth = 1.dp.toPx()
        )
        sx += 86.dp.toPx()
    }
    drawContent()
}

private fun Modifier.machinedShellClassic(): Modifier = drawWithContent {
    val radius = 28.dp.toPx()
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF26262C), Color(0xFF141417), Color(0xFF0D0D10))),
        cornerRadius = CornerRadius(radius, radius)
    )
    drawRoundRect(Color.White.copy(alpha = 0.08f), size = Size(size.width, 1.2.dp.toPx()), cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(Color.Black.copy(alpha = 0.46f), topLeft = Offset(0f, size.height - 9.dp.toPx()), size = Size(size.width, 9.dp.toPx()), cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(Color.White.copy(alpha = 0.08f), style = Stroke(1.dp.toPx()), cornerRadius = CornerRadius(radius, radius))
    drawContent()
}

private fun Modifier.raisedPanel(): Modifier = drawWithContent {
    val radius = 22.dp.toPx()
    drawRoundRect(
        Brush.verticalGradient(listOf(AiSurfaceHigh.copy(alpha = 0.72f), AiSurface.copy(alpha = 0.66f), AiSurfaceLow.copy(alpha = 0.82f))),
        cornerRadius = CornerRadius(radius, radius)
    )
    drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent)), size = Size(size.width, size.height * 0.42f), cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(Color.Black.copy(alpha = 0.30f), topLeft = Offset(0f, size.height - 5.dp.toPx()), size = Size(size.width, 5.dp.toPx()), cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.03f), Color.Black.copy(alpha = 0.38f))), style = Stroke(1.dp.toPx()), cornerRadius = CornerRadius(radius, radius))
    drawContent()
}

private fun Modifier.pressedInset(): Modifier = drawWithContent {
    val radius = 18.dp.toPx()
    drawRoundRect(Brush.verticalGradient(listOf(AiSurfaceLow.copy(alpha = 0.88f), AiSurface.copy(alpha = 0.62f))), cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(Color.Black.copy(alpha = 0.78f), size = Size(size.width, 3.dp.toPx()), cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(Color.White.copy(alpha = 0.07f), topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()), cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(Color.White.copy(alpha = 0.10f), style = Stroke(1.dp.toPx()), cornerRadius = CornerRadius(radius, radius))
    drawContent()
}

private fun Modifier.innerBevel(shape: RoundedCornerShape): Modifier = border(1.dp, Color.White.copy(alpha = 0.14f), shape)

private fun Modifier.glassButtonSurface(enabled: Boolean): Modifier = drawWithContent {
    val radius = 16.dp.toPx()
    val alpha = if (enabled) 1f else 0.45f
    drawRoundRect(
        Brush.verticalGradient(listOf(AiSurfaceHigh.copy(alpha = 0.72f * alpha), AiSurface.copy(alpha = 0.58f * alpha), AiSurfaceLow.copy(alpha = 0.72f * alpha))),
        cornerRadius = CornerRadius(radius, radius)
    )
    drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f * alpha), Color.Transparent)), size = Size(size.width, size.height * 0.42f), cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(Color.White.copy(alpha = 0.14f * alpha), style = Stroke(1.dp.toPx()), cornerRadius = CornerRadius(radius, radius))
    drawContent()
}

private fun calendarDayLabel(beginMs: Long): String {
    val date = Instant.ofEpochMilli(beginMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> "TODAY"
        today.plusDays(1) -> "TOMORROW"
        else -> date.dayOfWeek.name.take(3)
    }
}

private fun calendarTimeLabel(beginMs: Long, endMs: Long): String {
    val formatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    val begin = Instant.ofEpochMilli(beginMs).atZone(ZoneId.systemDefault()).format(formatter)
    val end = Instant.ofEpochMilli(endMs).atZone(ZoneId.systemDefault()).format(formatter)
    return "$begin - $end"
}

private fun countdownLabel(beginMs: Long): String {
    val minutes = max(0L, (beginMs - System.currentTimeMillis()) / 60_000L)
    return when {
        minutes <= 0L -> "Starting now"
        minutes < 60L -> "In ${minutes}m"
        minutes < 60L * 24L -> "In ${minutes / 60L}h ${minutes % 60L}m"
        else -> "In ${minutes / (60L * 24L)}d"
    }
}

@Preview(name = "Calendar Answer", widthDp = 390, heightDp = 720)
@Composable
private fun TeclasAiCalendarPreview() {
    TeclasAiQueryFlow(
        query = "next appointment",
        answer = "Your next appointment is **Product Review** tomorrow at 2:30 PM at Studio B. Join: https://meet.google.com/abc-defg-hij",
        onClose = {}
    )
}

@Preview(name = "Markdown Answer", widthDp = 390, heightDp = 720)
@Composable
private fun TeclasAiMarkdownPreview() {
    TeclasAiQueryFlow(
        query = "explain the new dock",
        answer = "The **dock** stays fixed while *context cards* change above it. Use `swipe up` for widgets.",
        onClose = {}
    )
}

@Preview(name = "Bold Regression", widthDp = 390, heightDp = 720)
@Composable
private fun TeclasAiBoldRegressionPreview() {
    TeclasAiQueryFlow(
        query = "why does markdown show asterisks?",
        answer = "**This text is bold now**, not literal asterisks.",
        onClose = {}
    )
}
