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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Ink = Color(0xFFF5F7FA)
private val InkDim = Color(0xFFA3ABB4)
private val InkFaint = Color(0xFF646B74)
private val GreenSoft = Color(0xFF28E06A)
private val Amber = Color(0xFFE8B84B)
private val Purple = Color(0xFFA071FF)
private val Accent = Color(0xFFFF4B45)

data class CalendarEvent(
    val eventId: Long,
    val title: String,
    val timeLabel: String,
    val location: String,
    val beginMs: Long,
    val endMs: Long,
    val dayLabel: String = "",
    val joinUrl: String? = null
)

data class RecentPerson(
    val key: String,
    val sender: String,
    val preview: String,
    val packageName: String,
    val color: Int,
    val avatar: Bitmap?,
    val lastUpdated: Long = 0L
)

data class ContextWidgetItem(
    val key: String,
    val title: String,
    val preview: String,
    val packageName: String,
    val color: Int,
    val avatar: Bitmap?,
    val lastUpdated: Long = 0L
)

private sealed interface WidgetItem {
    val id: String
    val lastUpdated: Long
    val accent: Color

    data class Music(
        override val lastUpdated: Long,
        val title: String,
        val artist: String,
        val sourceApp: String,
        val sourceColor: Color,
        val albumArt: Bitmap?
    ) : WidgetItem {
        override val id = "music"
        override val accent = GreenSoft
    }

    data class People(
        override val lastUpdated: Long,
        val people: List<RecentPerson>
    ) : WidgetItem {
        override val id = "people"
        override val accent = Color(0xFFFF8A4C)
    }

    data class Email(
        override val lastUpdated: Long,
        val emails: List<ContextWidgetItem>
    ) : WidgetItem {
        override val id = "email"
        override val accent = Color(0xFF3B9DFF)
    }

    data class News(
        override val lastUpdated: Long,
        val item: ContextWidgetItem
    ) : WidgetItem {
        override val id = "news:${item.key}"
        override val accent = Accent
    }

    data class Maps(
        override val lastUpdated: Long,
        val item: ContextWidgetItem
    ) : WidgetItem {
        override val id = "maps:${item.key}"
        override val accent = GreenSoft
    }

    data class Calendar(
        override val lastUpdated: Long,
        val events: List<CalendarEvent>,
        val hasPermission: Boolean
    ) : WidgetItem {
        override val id = "calendar"
        override val accent = Amber
    }
}

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

    val now = System.currentTimeMillis()
    val pages = buildList {
        if (isMusicPlaying) {
            add(WidgetItem.Music(now, title, artist, sourceApp, Color(sourceColor), albumArt))
        }
        mapsItems.firstOrNull()?.let { add(WidgetItem.Maps(it.lastUpdated, it)) }
        if (emailItems.isNotEmpty()) {
            add(WidgetItem.Email(emailItems.maxOf { it.lastUpdated }, emailItems))
        }
        if (recentPeople.isNotEmpty()) {
            add(WidgetItem.People(recentPeople.maxOf { it.lastUpdated }, recentPeople))
        }
        newsItems.firstOrNull()?.let { add(WidgetItem.News(it.lastUpdated, it)) }
        add(WidgetItem.Calendar(calendarEvents.maxOfOrNull { it.beginMs } ?: 0L, calendarEvents, hasCalendarPermission))
    }.sortedByDescending { it.lastUpdated }
    val pageCount = pages.size
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
    ) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (val widgetPage = pages[page]) {
                is WidgetItem.Music -> {
                    MusicWidgetCard(
                        title = widgetPage.title,
                        artist = widgetPage.artist,
                        sourceApp = widgetPage.sourceApp,
                        sourceColor = widgetPage.sourceColor,
                        albumArt = widgetPage.albumArt,
                        onClick = onMusicClick,
                        onLongClick = onMusicLongClick
                    )
                }
                is WidgetItem.Calendar -> {
                    CalendarWidgetCard(
                        events = widgetPage.events,
                        hasPermission = widgetPage.hasPermission,
                        onClick = onCalendarClick,
                        onLongClick = onCalendarLongClick
                    )
                }
                is WidgetItem.Email -> {
                    EmailWidgetCard(
                        emails = widgetPage.emails,
                        onClick = onEmailClick,
                        onLongClick = onEmailLongClick
                    )
                }
                is WidgetItem.Maps -> {
                    MapsWidgetCard(
                        item = widgetPage.item,
                        onClick = { onMapsClick(widgetPage.item) },
                        onLongClick = { onMapsLongClick(widgetPage.item) }
                    )
                }
                is WidgetItem.News -> {
                    NewsWidgetCard(
                        item = widgetPage.item,
                        onClick = { onNewsClick(widgetPage.item) },
                        onLongClick = { onNewsLongClick(widgetPage.item) }
                    )
                }
                is WidgetItem.People -> {
                    RecentPeopleCard(
                        people = widgetPage.people,
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
                    val dotHeight = animateDpAsState(
                        targetValue = if (selected) 18.dp else 5.dp,
                        animationSpec = tween(durationMillis = 260),
                        label = "widgetDot"
                    )
                    Box(
                        Modifier
                            .width(5.dp)
                            .height(dotHeight.value)
                            .clip(RoundedCornerShape(99.dp))
                            .background(if (selected) GreenSoft else Color(0x668B8F99))
                            .clickable { scope.launch { pagerState.animateScrollToPage(index) } }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MusicWidgetCard(
    title: String,
    artist: String,
    sourceApp: String,
    sourceColor: Color,
    albumArt: Bitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .drawMusicSurface(sourceColor)
            .border(1.dp, sourceColor.copy(alpha = 0.14f), RoundedCornerShape(24.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(20.dp))
                .recessedTraySurface(sourceColor, 20)
                .border(1.dp, sourceColor.copy(alpha = 0.12f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (albumArt != null) {
                Image(
                    bitmap = albumArt.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(sourceColor.copy(alpha = 0.84f), radius = size.minDimension * 0.36f, center = center)
                    drawCircle(Color(0xFF0A0B0E), radius = size.minDimension * 0.13f, center = center)
                    drawCircle(Color.White.copy(alpha = 0.16f), radius = size.minDimension * 0.04f, center = center)
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            LabelText("NOW PLAYING", sourceColor)
            Spacer(Modifier.height(7.dp))
            BasicText(
                text = title.ifBlank { "Music" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
            )
            Spacer(Modifier.height(5.dp))
            BasicText(
                text = listOf(artist, sourceApp).filter { it.isNotBlank() }.joinToString(" . "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = InkDim, fontSize = 11.4.sp, fontFamily = FontFamily.SansSerif)
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                listOf(10.dp, 18.dp, 13.dp, 22.dp, 15.dp, 8.dp).forEach { height ->
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(height)
                            .clip(RoundedCornerShape(99.dp))
                            .background(sourceColor.copy(alpha = 0.78f))
                    )
                }
            }
        }
    }
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
            .border(1.dp, Color(0x18EA4335), RoundedCornerShape(24.dp))
            .combinedClickable(onClick = { onClick(primary) }, onLongClick = { onLongClick(primary) })
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .domedSurface(Color(0xFFFFD8D4), Color(0xFFEA4335), 10)
                    .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                BasicText("M", style = TextStyle(color = Color(0xFF260805), fontSize = 15.sp, fontWeight = FontWeight.Black))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                LabelText("INBOX", Color(0xFFEA4335))
                Spacer(Modifier.height(3.dp))
                BasicText(
                    text = "Latest email",
                    maxLines = 1,
                    style = TextStyle(color = InkDim, fontSize = 10.4.sp, fontFamily = FontFamily.SansSerif)
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(Color(0x22EA4335))
                    .border(1.dp, Color(0x24EA4335), RoundedCornerShape(99.dp))
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                BasicText(
                    text = emails.size.coerceAtMost(99).toString(),
                    maxLines = 1,
                    style = TextStyle(color = Color(0xFFFFB4AA), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .recessedTraySurface(Color(0xFFEA4335), 18)
                .border(1.dp, Color(0x14EA4335), RoundedCornerShape(18.dp))
                .padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ContextIcon(primary, fallback = "M", size = 42)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                BasicText(
                    text = primary.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
                )
                Spacer(Modifier.height(5.dp))
                BasicText(
                    text = primary.preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = InkDim, fontSize = 11.2.sp, fontFamily = FontFamily.SansSerif)
                )
            }
        }
        if (emails.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                emails.drop(1).take(2).forEach { email ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.size(5.dp).clip(RoundedCornerShape(99.dp)).background(Color(0xFFEA4335).copy(alpha = 0.74f)))
                        Spacer(Modifier.width(7.dp))
                        BasicText(
                            text = email.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = InkDim, fontSize = 10.4.sp, fontFamily = FontFamily.SansSerif)
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
            .border(1.dp, Color(0x143B82F6), RoundedCornerShape(24.dp))
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
            .border(1.dp, Color(0x1857C98A), RoundedCornerShape(24.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(86.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Brush.radialGradient(listOf(Color(0x2257C98A), Color(0xFF101318), Color(0xFF0A0B0E))))
                .border(1.dp, Color(0x1857C98A), RoundedCornerShape(22.dp))
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val road = Color(0xFF2A2F36)
                val roadLit = Color(0xFF57C98A)
                drawLine(road, Offset(size.width * 0.14f, size.height * 0.24f), Offset(size.width * 0.86f, size.height * 0.72f), strokeWidth = 7.dp.toPx())
                drawLine(road, Offset(size.width * 0.18f, size.height * 0.78f), Offset(size.width * 0.80f, size.height * 0.18f), strokeWidth = 5.dp.toPx())
                drawLine(roadLit.copy(alpha = 0.82f), Offset(size.width * 0.14f, size.height * 0.24f), Offset(size.width * 0.55f, size.height * 0.51f), strokeWidth = 3.dp.toPx())
                drawCircle(Color(0xFF0B1511), radius = 12.dp.toPx(), center = Offset(size.width * 0.60f, size.height * 0.55f))
                drawCircle(roadLit, radius = 7.dp.toPx(), center = Offset(size.width * 0.60f, size.height * 0.55f))
                drawCircle(Color.White.copy(alpha = 0.22f), radius = 2.dp.toPx(), center = Offset(size.width * 0.60f, size.height * 0.55f))
            }
        }
        Spacer(Modifier.width(14.dp))
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
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(99.dp)).background(GreenSoft))
                Spacer(Modifier.width(7.dp))
                BasicText(
                    text = "Tap to resume route",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = GreenSoft.copy(alpha = 0.9f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
                )
            }
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
            .domedSurface(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.42f), 16)
            .border(1.dp, accent.copy(alpha = 0.18f), RoundedCornerShape(16.dp)),
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
            .border(1.dp, Color(0x0EFFFFFF), RoundedCornerShape(24.dp))
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            LabelText("RECENT PEOPLE", Color(0xFF5FD0C4))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.height(1.dp).weight(1f).background(Color(0x1F5FD0C4)))
            if (people.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                BasicText(
                    text = people.size.coerceAtMost(6).toString(),
                    maxLines = 1,
                    style = TextStyle(color = Color(0xFF5FD0C4), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                )
            }
        }
        if (people.isEmpty()) {
            Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                    repeat(3) { index ->
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .background(Color(0x17191B20))
                                .border(1.dp, Color(0x145FD0C4), RoundedCornerShape(99.dp))
                        )
                    }
                }
                Spacer(Modifier.height(9.dp))
                BasicText(
                    text = "No recent people yet",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
                )
            }
        } else if (people.size == 1) {
            RecentPersonWideCard(
                person = people.first(),
                onClick = { onPersonClick(people.first()) },
                onLongClick = { onPersonLongClick(people.first()) }
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
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
            .height(82.dp)
            .clip(RoundedCornerShape(22.dp))
            .recessedTraySurface(accent, 22)
            .border(1.dp, accent.copy(alpha = 0.16f), RoundedCornerShape(22.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfileOrb(person, size = 54, fontSize = 15)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            BasicText(
                text = person.sender,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                text = person.preview,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = InkDim, fontSize = 11.4.sp, lineHeight = 13.sp, fontFamily = FontFamily.SansSerif)
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
            .height(78.dp)
            .clip(RoundedCornerShape(20.dp))
            .recessedTraySurface(Color(person.color), 20)
            .border(1.dp, Color(person.color).copy(alpha = 0.12f), RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 7.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            ProfileOrb(person, size = 38, fontSize = 11)
            Spacer(Modifier.height(7.dp))
            BasicText(
                text = person.sender,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Ink, fontSize = 11.4.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
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
            .domedSurface(accent.copy(alpha = 0.96f), Color(0xFF111318), 99)
            .border(1.dp, accent.copy(alpha = 0.18f), RoundedCornerShape(99.dp)),
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
            .border(1.dp, Color(0x182A2C33), RoundedCornerShape(22.dp))
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
            .recessedTraySurface(accent, 18)
            .border(1.dp, accent.copy(alpha = 0.13f), RoundedCornerShape(18.dp))
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
                .recessedTraySurface(Amber, 17)
                .border(1.dp, Color(0x142A2C33), RoundedCornerShape(17.dp))
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

private fun Modifier.drawCalendarSurface(): Modifier = raisedGlassSurface(Amber, 22)

private fun Modifier.drawNeutralSurface(): Modifier = raisedGlassSurface(GreenSoft, 22)

private fun Modifier.drawMusicSurface(accent: Color): Modifier = raisedGlassSurface(accent, 24)

private fun Modifier.drawPeopleSurface(): Modifier = raisedGlassSurface(Purple, 24)

private fun Modifier.drawEmailSurface(): Modifier = raisedGlassSurface(Color(0xFFEA4335), 24)

private fun Modifier.drawNewsSurface(): Modifier = raisedGlassSurface(Color(0xFF3B9DFF), 24)

private fun Modifier.drawMapsSurface(): Modifier = raisedGlassSurface(GreenSoft, 24)

private fun Modifier.raisedGlassSurface(accent: Color, radiusDp: Int): Modifier = this.drawWithContent {
    val radius = radiusDp.dp.toPx()
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.58f),
        topLeft = Offset(0f, 2.dp.toPx()),
        cornerRadius = CornerRadius(radius, radius)
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(
                Color(0xFF191C21),
                Color(0xFF14171B),
                Color(0xFF101216)
            )
        ),
        cornerRadius = CornerRadius(radius, radius)
    )
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.22f),
        topLeft = Offset(0f, 0f),
        size = Size(size.width, 1.4.dp.toPx())
    )
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.48f),
        topLeft = Offset(0f, size.height - 4.dp.toPx()),
        size = Size(size.width, 4.dp.toPx()),
        cornerRadius = CornerRadius(radius, radius)
    )
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(accent.copy(alpha = 0.70f), accent.copy(alpha = 0.30f))),
        size = Size(4.dp.toPx(), size.height),
        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.025f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
        cornerRadius = CornerRadius(radius, radius)
    )
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.42f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
        cornerRadius = CornerRadius(radius, radius)
    )
    drawContent()
}

private fun Modifier.recessedTraySurface(accent: Color, radiusDp: Int): Modifier = this.drawWithContent {
    val radius = radiusDp.dp.toPx()
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(0xFF0B0D10), Color(0xFF101319))),
        cornerRadius = CornerRadius(radius, radius)
    )
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.40f),
        size = Size(size.width, 2.dp.toPx()),
        cornerRadius = CornerRadius(radius, radius)
    )
    drawRoundRect(
        color = accent.copy(alpha = 0.045f),
        cornerRadius = CornerRadius(radius, radius)
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.018f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
        cornerRadius = CornerRadius(radius, radius)
    )
    drawContent()
}

private fun Modifier.domedSurface(light: Color, dark: Color, radiusDp: Int): Modifier = this.drawWithContent {
    val radius = radiusDp.dp.toPx()
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(light.copy(alpha = 0.86f), dark.copy(alpha = 0.72f), Color(0xFF0B0D11))
        ),
        cornerRadius = CornerRadius(radius, radius)
    )
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.25f),
        topLeft = Offset(0f, size.height - 2.dp.toPx()),
        size = Size(size.width, 2.dp.toPx())
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.035f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
        cornerRadius = CornerRadius(radius, radius)
    )
    drawContent()
}

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
