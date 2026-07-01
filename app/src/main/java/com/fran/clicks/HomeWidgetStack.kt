package com.fran.clicks

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

data class RecentPerson(
    val key: String,
    val sender: String,
    val preview: String,
    val packageName: String,
    val color: Int,
    val avatar: Bitmap?
)

data class ContextWidgetItem(
    val key: String,
    val title: String,
    val preview: String,
    val packageName: String,
    val color: Int,
    val avatar: Bitmap?
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeWidgetStack(
    visible: Boolean,
    isMusicPlaying: Boolean,
    title: String,
    artist: String,
    sourceApp: String,
    sourceColor: Int,
    albumArt: Bitmap?,
    calendarEvents: List<CalendarEvent>,
    recentPeople: List<RecentPerson>,
    emailItems: List<ContextWidgetItem>,
    newsItems: List<ContextWidgetItem>,
    mapsItems: List<ContextWidgetItem>,
    hasCalendarPermission: Boolean,
    onMusicClick: () -> Unit,
    onMusicLongClick: () -> Unit,
    onCalendarClick: () -> Unit,
    onCalendarLongClick: () -> Unit,
    onRecentPersonClick: (RecentPerson) -> Unit,
    onRecentPersonLongClick: (RecentPerson) -> Unit,
    onEmailClick: (ContextWidgetItem) -> Unit,
    onEmailLongClick: (ContextWidgetItem) -> Unit,
    onNewsClick: (ContextWidgetItem) -> Unit,
    onNewsLongClick: (ContextWidgetItem) -> Unit,
    onMapsClick: (ContextWidgetItem) -> Unit,
    onMapsLongClick: (ContextWidgetItem) -> Unit
) {
    if (!visible) return

    val pages = buildList {
        if (isMusicPlaying) add(WidgetPage.Music)
        mapsItems.firstOrNull()?.let { add(WidgetPage.Maps(it)) }
        if (emailItems.isNotEmpty()) add(WidgetPage.Email)
        if (recentPeople.isNotEmpty()) add(WidgetPage.People)
        newsItems.firstOrNull()?.let { add(WidgetPage.News(it)) }
        add(WidgetPage.Calendar)
    }
    val pageCount = pages.size
    val pagerState = rememberPagerState(pageCount = { pageCount })
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
    ) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (val widgetPage = pages[page]) {
                WidgetPage.Music -> {
                    NowPlayingCard(
                        visible = true,
                        title = title,
                        artist = artist,
                        sourceApp = sourceApp,
                        sourceColor = Color(sourceColor),
                        albumArt = albumArt,
                        isPlaying = true,
                        onClick = onMusicClick,
                        onLongClick = onMusicLongClick
                    )
                }
                WidgetPage.Calendar -> {
                    CalendarWidgetCard(
                        events = calendarEvents,
                        hasPermission = hasCalendarPermission,
                        onClick = onCalendarClick,
                        onLongClick = onCalendarLongClick
                    )
                }
                WidgetPage.Email -> {
                    EmailWidgetCard(
                        emails = emailItems,
                        onClick = onEmailClick,
                        onLongClick = onEmailLongClick
                    )
                }
                is WidgetPage.Maps -> {
                    MapsWidgetCard(
                        item = widgetPage.item,
                        onClick = { onMapsClick(widgetPage.item) },
                        onLongClick = { onMapsLongClick(widgetPage.item) }
                    )
                }
                is WidgetPage.News -> {
                    NewsWidgetCard(
                        item = widgetPage.item,
                        onClick = { onNewsClick(widgetPage.item) },
                        onLongClick = { onNewsLongClick(widgetPage.item) }
                    )
                }
                WidgetPage.People -> {
                    RecentPeopleCard(
                        people = recentPeople,
                        onPersonClick = onRecentPersonClick,
                        onPersonLongClick = onRecentPersonLongClick
                    )
                }
            }
        }
        if (pageCount > 1) {
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
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

private sealed class WidgetPage {
    data object Music : WidgetPage()
    data object Calendar : WidgetPage()
    data object Email : WidgetPage()
    data object People : WidgetPage()
    data class News(val item: ContextWidgetItem) : WidgetPage()
    data class Maps(val item: ContextWidgetItem) : WidgetPage()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmailWidgetCard(
    emails: List<ContextWidgetItem>,
    onClick: (ContextWidgetItem) -> Unit,
    onLongClick: (ContextWidgetItem) -> Unit
) {
    val primary = emails.first()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxSize()
            .drawEmailSurface()
            .border(1.dp, Color(0x24F3F0E7), RoundedCornerShape(24.dp))
            .combinedClickable(onClick = { onClick(primary) }, onLongClick = { onLongClick(primary) })
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            LabelText("GMAIL", Color(0xFFEA4335))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.height(1.dp).weight(1f).background(Color(0x222A2C33)))
            Spacer(Modifier.width(8.dp))
            BasicText(
                text = emails.size.coerceAtMost(99).toString(),
                maxLines = 1,
                style = TextStyle(color = Color(0xFFEA4335), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ContextIcon(primary, fallback = "M", size = 46)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                BasicText(
                    text = primary.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
                )
                Spacer(Modifier.height(5.dp))
                BasicText(
                    text = primary.preview,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = InkDim, fontSize = 11.3.sp, lineHeight = 13.sp, fontFamily = FontFamily.SansSerif)
                )
            }
        }
        if (emails.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                emails.drop(1).take(3).forEach { email ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(22.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .background(Color(0x17191B20))
                            .border(1.dp, Color(0x182A2C33), RoundedCornerShape(99.dp))
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicText(
                            text = email.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = InkDim, fontSize = 9.5.sp, fontFamily = FontFamily.SansSerif)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewsWidgetCard(
    item: ContextWidgetItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxSize()
            .drawNewsSurface()
            .border(1.dp, Color(0x223B82F6), RoundedCornerShape(24.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContextIcon(item, fallback = "N", size = 50)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            LabelText("GOOGLE NEWS", Color(0xFF8AB4F8))
            Spacer(Modifier.height(7.dp))
            BasicText(
                text = item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Ink, fontSize = 16.8.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
            )
            Spacer(Modifier.height(5.dp))
            BasicText(
                text = item.preview,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = InkDim, fontSize = 11.sp, lineHeight = 12.6.sp, fontFamily = FontFamily.SansSerif)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MapsWidgetCard(
    item: ContextWidgetItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxSize()
            .drawMapsSurface()
            .border(1.dp, Color(0x2257C98A), RoundedCornerShape(24.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContextIcon(item, fallback = "G", size = 50)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            LabelText("ON THE ROAD", GreenSoft)
            Spacer(Modifier.height(7.dp))
            BasicText(
                text = item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
            )
            Spacer(Modifier.height(5.dp))
            BasicText(
                text = item.preview,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = InkDim, fontSize = 11.sp, lineHeight = 12.6.sp, fontFamily = FontFamily.SansSerif)
            )
        }
    }
}

@Composable
private fun ContextIcon(item: ContextWidgetItem, fallback: String, size: Int) {
    val accent = Color(item.color)
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.9f), accent.copy(alpha = 0.25f), Color(0xFF111318))))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (item.avatar != null) {
            Image(
                bitmap = item.avatar.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            BasicText(
                text = item.title.take(1).uppercase().ifBlank { fallback },
                maxLines = 1,
                style = TextStyle(color = Color(0xFF071009), fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif)
            )
        }
    }
}

@Composable
private fun RecentPeopleCard(
    people: List<RecentPerson>,
    onPersonClick: (RecentPerson) -> Unit,
    onPersonLongClick: (RecentPerson) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxSize()
            .drawPeopleSurface()
            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                LabelText("RECENT PEOPLE", Color(0xFF5FD0C4))
                Spacer(Modifier.height(3.dp))
                BasicText(
                    text = "Conversations from real contacts",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = InkDim, fontSize = 10.5.sp, fontFamily = FontFamily.SansSerif)
                )
            }
            if (people.isNotEmpty()) {
                BasicText(
                    text = people.size.coerceAtMost(6).toString(),
                    maxLines = 1,
                    style = TextStyle(color = Color(0xFF5FD0C4), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                )
            }
        }
        if (people.isEmpty()) {
            Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                BasicText(
                    text = "No recent people yet",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
                )
                Spacer(Modifier.height(4.dp))
                BasicText(
                    text = "People who message you will collect here",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = InkDim, fontSize = 11.5.sp, fontFamily = FontFamily.SansSerif)
                )
            }
        } else if (people.size == 1) {
            RecentPersonWideCard(
                person = people.first(),
                onClick = { onPersonClick(people.first()) },
                onLongClick = { onPersonLongClick(people.first()) }
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                people.take(3).forEach { person ->
                    RecentPersonChip(
                        person = person,
                        modifier = Modifier.weight(1f),
                        onClick = { onPersonClick(person) },
                        onLongClick = { onPersonLongClick(person) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentPersonWideCard(
    person: RecentPerson,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val accent = Color(person.color)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(70.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.16f), Color(0x24191B20), Color(0x10191B20))))
            .border(1.dp, accent.copy(alpha = 0.22f), RoundedCornerShape(22.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfileOrb(person, size = 42, fontSize = 13)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            BasicText(
                text = person.sender,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                text = person.preview,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = InkDim, fontSize = 12.sp, fontFamily = FontFamily.SansSerif)
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(accent.copy(alpha = 0.9f))
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentPersonChip(
    person: RecentPerson,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = modifier
            .height(62.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(Color(0x22191B20), Color(0x44101216))))
            .border(1.dp, Color(0x2E8B8F99), RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfileOrb(person, size = 34, fontSize = 11)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            BasicText(
                text = person.sender,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Ink, fontSize = 12.2.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
            )
            Spacer(Modifier.height(3.dp))
            BasicText(
                text = person.preview,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = InkDim, fontSize = 9.2.sp, fontFamily = FontFamily.SansSerif)
            )
        }
    }
}

@Composable
private fun ProfileOrb(person: RecentPerson, size: Int, fontSize: Int) {
    val accent = Color(person.color)
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(accent.copy(alpha = 0.16f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(99.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (person.avatar != null) {
            Image(
                bitmap = person.avatar.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            BasicText(
                text = initials(person.sender),
                maxLines = 1,
                style = TextStyle(color = accent, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalendarWidgetCard(
    events: List<CalendarEvent>,
    hasPermission: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val now = System.currentTimeMillis()
    val current = events.firstOrNull { now >= it.beginMs && now < it.endMs }
    val upcoming = events.firstOrNull { it.beginMs > now && it.eventId != current?.eventId }
        ?: if (current == null) events.firstOrNull() else null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxSize()
            .drawCalendarSurface()
            .border(1.dp, Color(0x332A2C33), RoundedCornerShape(22.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CalendarNowBlock(
            event = current,
            hasPermission = hasPermission,
            modifier = Modifier.weight(1.08f)
        )
        Spacer(Modifier.width(10.dp))
        CalendarNextBlock(
            event = upcoming,
            hasPermission = hasPermission,
            count = events.size,
            modifier = Modifier.weight(0.92f)
        )
    }
}

@Composable
private fun CalendarNowBlock(event: CalendarEvent?, hasPermission: Boolean, modifier: Modifier) {
    val accent = if (event == null) GreenSoft else Accent
    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.14f), Color(0x10191B20))))
            .border(1.dp, accent.copy(alpha = 0.24f), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        LabelText("RIGHT NOW", accent)
        Column {
            BasicText(
                text = when {
                    !hasPermission -> "Calendar access"
                    event == null -> "Free"
                    else -> event.title
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
            )
            Spacer(Modifier.height(5.dp))
            BasicText(
                text = when {
                    !hasPermission -> "Tap to connect"
                    event == null -> "No event in progress"
                    else -> "Until ${calendarEndTime(event.endMs)}"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = InkDim, fontSize = 11.5.sp, fontFamily = FontFamily.SansSerif)
            )
        }
    }
}

@Composable
private fun CalendarNextBlock(event: CalendarEvent?, hasPermission: Boolean, count: Int, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabelText("UP NEXT", Amber)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.height(1.dp).weight(1f).background(Color(0x242A2C33)))
            if (count > 1) {
                Spacer(Modifier.width(6.dp))
                BasicText(
                    text = "+${count - 1}",
                    maxLines = 1,
                    style = TextStyle(color = Amber, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(17.dp))
                .background(Color(0x17191B20))
                .border(1.dp, Color(0x202A2C33), RoundedCornerShape(17.dp))
                .padding(horizontal = 11.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center
        ) {
            BasicText(
                text = when {
                    !hasPermission -> "Connect calendar"
                    event == null -> "Clear"
                    else -> event.title
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Ink, fontSize = 13.4.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
            )
            Spacer(Modifier.height(5.dp))
            BasicText(
                text = when {
                    !hasPermission -> "Tap to allow access"
                    event == null -> "Nothing scheduled"
                    else -> listOf(event.timeLabel, event.location).filter { it.isNotBlank() }.joinToString(" . ")
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = InkDim, fontSize = 10.4.sp, fontFamily = FontFamily.SansSerif)
            )
        }
    }
}

private fun Modifier.drawCalendarSurface(): Modifier = this.background(
    brush = Brush.verticalGradient(listOf(Color(0xF01C2027), Color(0xF20D0E11))),
    shape = RoundedCornerShape(22.dp)
)

private fun Modifier.drawNeutralSurface(): Modifier = this.background(
    brush = Brush.verticalGradient(listOf(Color(0xF01A1D23), Color(0xF20D0E11))),
    shape = RoundedCornerShape(22.dp)
)

private fun Modifier.drawPeopleSurface(): Modifier = this.background(
    brush = Brush.verticalGradient(listOf(Color(0xEE1B1D23), Color(0xF3111216))),
    shape = RoundedCornerShape(24.dp)
)

private fun Modifier.drawEmailSurface(): Modifier = this.background(
    brush = Brush.verticalGradient(listOf(Color(0xF11D2027), Color(0xF5111216))),
    shape = RoundedCornerShape(24.dp)
)

private fun Modifier.drawNewsSurface(): Modifier = this.background(
    brush = Brush.radialGradient(listOf(Color(0x263B82F6), Color(0xF014171D), Color(0xF50D0E11))),
    shape = RoundedCornerShape(24.dp)
)

private fun Modifier.drawMapsSurface(): Modifier = this.background(
    brush = Brush.radialGradient(listOf(Color(0x2457C98A), Color(0xF014171D), Color(0xF50D0E11))),
    shape = RoundedCornerShape(24.dp)
)

private fun calendarTimeParts(timeLabel: String?): Pair<String, String> {
    val clean = timeLabel?.trim().orEmpty()
    if (clean.isBlank()) return "TODAY" to ""
    val firstSpace = clean.indexOf(' ')
    if (firstSpace < 0) return clean.uppercase() to ""
    return clean.substring(0, firstSpace).uppercase() to clean.substring(firstSpace + 1)
}

private fun calendarEndTime(endMs: Long): String {
    return Instant.ofEpochMilli(endMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
        .lowercase(Locale.US)
}

private fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase()
        parts.isNotEmpty() -> parts[0].take(2).uppercase()
        else -> "?"
    }
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
        Canvas(Modifier.size(width = 8.dp, height = 72.dp)) {
            val line = if (hasPermission) Amber else InkDim
            drawLine(
                color = line.copy(alpha = 0.45f),
                start = Offset(size.width / 2f, 2.dp.toPx()),
                end = Offset(size.width / 2f, size.height - 2.dp.toPx()),
                strokeWidth = 1.dp.toPx()
            )
            drawCircle(Accent, radius = 2.4.dp.toPx(), center = Offset(size.width / 2f, size.height * 0.42f))
        }
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.Center) {
            BasicText(
                text = dayLabel.take(8),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = if (hasPermission) Amber else InkDim, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                text = timeLabel.ifBlank { "agenda" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = InkDim, fontSize = 10.2.sp, fontFamily = FontFamily.SansSerif)
            )
        }
    }
}
