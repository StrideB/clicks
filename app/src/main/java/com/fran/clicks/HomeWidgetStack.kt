package com.fran.clicks

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFFF3F0E7)
private val InkDim = Color(0xFF8B8F99)
private val GreenSoft = Color(0xFF8FD694)
private val Amber = Color(0xFFF5C451)
private val Accent = Color(0xFFFF5A3C)

data class CalendarEvent(
    val eventId: Long,
    val title: String,
    val timeLabel: String,
    val location: String,
    val beginMs: Long,
    val endMs: Long
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeWidgetStack(
    visible: Boolean,
    isMusicPlaying: Boolean,
    title: String,
    artist: String,
    sourceApp: String,
    albumArt: Bitmap?,
    calendarEvents: List<CalendarEvent>,
    hasCalendarPermission: Boolean,
    onMusicClick: () -> Unit,
    onCalendarClick: () -> Unit
) {
    if (!visible) return

    val pageCount = if (isMusicPlaying) 2 else 1
    val pagerState = rememberPagerState(pageCount = { pageCount })
    Box(Modifier.fillMaxWidth().height(68.dp)) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            if (isMusicPlaying && page == 0) {
                NowPlayingCard(
                    visible = true,
                    title = title,
                    artist = artist,
                    sourceApp = sourceApp,
                    albumArt = albumArt,
                    isPlaying = true,
                    onClick = onMusicClick
                )
            } else {
                CalendarWidgetCard(
                    events = calendarEvents,
                    hasPermission = hasCalendarPermission,
                    onClick = onCalendarClick
                )
            }
        }
        if (pageCount > 1) {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                repeat(pageCount) { index ->
                    val selected = pagerState.currentPage == index
                    Box(
                        Modifier
                            .size(if (selected) 6.dp else 4.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(if (selected) GreenSoft else Color(0x668B8F99))
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarWidgetCard(
    events: List<CalendarEvent>,
    hasPermission: Boolean,
    onClick: () -> Unit
) {
    val event = events.firstOrNull()
    val primary = when {
        !hasPermission -> "Calendar access"
        event == null -> "No upcoming events"
        else -> event.title
    }
    val secondary = when {
        !hasPermission -> "Tap to connect your calendar"
        event == null -> "Your next week is clear"
        else -> listOf(event.timeLabel, event.location).filter { it.isNotBlank() }.joinToString(" . ")
    }
    val (dayLabel, timeLabel) = calendarTimeParts(event?.timeLabel)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .drawCalendarSurface()
            .border(1.dp, Color(0x222A2C33), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CalendarDateMark(dayLabel, timeLabel, hasPermission)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            LabelText("CALENDAR . NEXT", Amber)
            BasicText(
                text = primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
            )
            BasicText(
                text = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = InkDim, fontSize = 10.8.sp, fontFamily = FontFamily.SansSerif)
            )
        }
        if (events.size > 1) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Color(0x14191B20))
                    .border(1.dp, Color(0x222A2C33), RoundedCornerShape(99.dp)),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = "+${events.size - 1}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Amber, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

private fun Modifier.drawCalendarSurface(): Modifier = this.background(
    brush = Brush.verticalGradient(listOf(Color(0xE61A1D23), Color(0xF00D0E11))),
    shape = RoundedCornerShape(16.dp)
)

private fun calendarTimeParts(timeLabel: String?): Pair<String, String> {
    val clean = timeLabel?.trim().orEmpty()
    if (clean.isBlank()) return "TODAY" to ""
    val firstSpace = clean.indexOf(' ')
    if (firstSpace < 0) return clean.uppercase() to ""
    return clean.substring(0, firstSpace).uppercase() to clean.substring(firstSpace + 1)
}

@Composable
private fun LabelText(text: String, color: Color) {
    BasicText(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            color = color,
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.4.sp
        )
    )
}

@Composable
private fun CalendarDateMark(dayLabel: String, timeLabel: String, hasPermission: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 7.dp, height = 42.dp)) {
            val line = if (hasPermission) Amber else InkDim
            drawLine(
                color = line.copy(alpha = 0.45f),
                start = Offset(size.width / 2f, 2.dp.toPx()),
                end = Offset(size.width / 2f, size.height - 2.dp.toPx()),
                strokeWidth = 1.dp.toPx()
            )
            drawCircle(Accent, radius = 2.4.dp.toPx(), center = Offset(size.width / 2f, size.height * 0.42f))
        }
        Spacer(Modifier.width(9.dp))
        Column(verticalArrangement = Arrangement.Center) {
            BasicText(
                text = dayLabel.take(8),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = if (hasPermission) Amber else InkDim, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp)
            )
            BasicText(
                text = timeLabel.ifBlank { "agenda" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = InkDim, fontSize = 9.5.sp, fontFamily = FontFamily.SansSerif)
            )
        }
    }
}
