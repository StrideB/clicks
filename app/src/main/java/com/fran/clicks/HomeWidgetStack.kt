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
import androidx.compose.foundation.layout.fillMaxHeight
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

private var WidgetNeuTokens = Neu.Dark
private val Ink: Color get() = WidgetNeuTokens.inkCompose
private val InkDim: Color get() = WidgetNeuTokens.inkDimCompose
private val InkFaint: Color get() = WidgetNeuTokens.inkFaintCompose
private val GreenSoft = Color(Neu.GREEN)
private val Amber = Color(Neu.AMBER)
private val Purple = Color(Neu.PURPLE)
private val Accent = Color(Neu.ACCENT)
private val Blue = Color(Neu.BLUE)
private val Teal = Color(Neu.TEAL)
private val Orange = Color(Neu.ORANGE)
private val DarkChipText = Color(Neu.DARK_CHIP_TEXT)

private fun chipTextColor(tokens: NeuTokens): Color {
    return if (tokens.mode == NeuMode.LIGHT) Color(Neu.LIGHT_CHIP_TEXT) else DarkChipText
}

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
        override val accent = Orange
    }

    data class Email(
        override val lastUpdated: Long,
        val emails: List<ContextWidgetItem>
    ) : WidgetItem {
        override val id = "email"
        override val accent = Accent
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
    tokens: NeuTokens = Neu.Dark,
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
    WidgetNeuTokens = tokens

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
            .background(tokens.baseCompose)
    ) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (val widgetPage = pages[page]) {
                is WidgetItem.Music -> {
                    MusicWidgetCard(
                        tokens = tokens,
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
                        tokens = tokens,
                        events = widgetPage.events,
                        hasPermission = widgetPage.hasPermission,
                        onClick = onCalendarClick,
                        onLongClick = onCalendarLongClick
                    )
                }
                is WidgetItem.Email -> {
                    EmailWidgetCard(
                        tokens = tokens,
                        emails = widgetPage.emails,
                        onClick = onEmailClick,
                        onLongClick = onEmailLongClick
                    )
                }
                is WidgetItem.Maps -> {
                    MapsWidgetCard(
                        tokens = tokens,
                        item = widgetPage.item,
                        onClick = { onMapsClick(widgetPage.item) },
                        onLongClick = { onMapsLongClick(widgetPage.item) }
                    )
                }
                is WidgetItem.News -> {
                    NewsWidgetCard(
                        tokens = tokens,
                        item = widgetPage.item,
                        onClick = { onNewsClick(widgetPage.item) },
                        onLongClick = { onNewsLongClick(widgetPage.item) }
                    )
                }
                is WidgetItem.People -> {
                    RecentPeopleCard(
                        tokens = tokens,
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
                            .background(if (selected) GreenSoft else InkFaint.copy(alpha = 0.5f))
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
    tokens: NeuTokens,
    title: String,
    artist: String,
    sourceApp: String,
    sourceColor: Color,
    albumArt: Bitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .drawMusicSurface(tokens)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 17.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        LabelText("NOW PLAYING", GreenSoft)
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(82.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .recessedTray(tokens, 18, deep = true)
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                if (albumArt != null) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp))
                            .neu(tokens, 16.dp, NeuLevel.RAISED_SM)
                            .padding(4.dp)
                    ) {
                        Image(
                            bitmap = albumArt.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        repeat(2) {
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                repeat(2) {
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(7.dp))
                                            .neu(tokens, 7.dp, NeuLevel.RAISED_SM)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                BasicText(
                    text = title.ifBlank { "Music" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
                )
                Spacer(Modifier.height(5.dp))
                BasicText(
                    text = listOf(artist, sourceApp).filter { it.isNotBlank() }.joinToString(" . "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = InkDim, fontSize = 11.sp, fontFamily = FontFamily.SansSerif)
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .recessedTray(tokens, 99, deep = false)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.44f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(99.dp))
                            .background(GreenSoft.copy(alpha = 0.7f))
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            listOf(10.dp, 18.dp, 13.dp, 20.dp, 15.dp, 8.dp).forEach { height ->
                Box(
                    Modifier
                        .width(3.dp)
                        .height(height)
                        .clip(RoundedCornerShape(2.dp))
                        .background(GreenSoft.copy(alpha = 0.85f))
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmailWidgetCard(
    tokens: NeuTokens,
    emails: List<ContextWidgetItem>,
    onClick: (ContextWidgetItem) -> Unit,
    onLongClick: (ContextWidgetItem) -> Unit
) {
    val primary = emails.first()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxSize()
            .drawEmailSurface(tokens)
            .combinedClickable(onClick = { onClick(primary) }, onLongClick = { onLongClick(primary) })
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .domedSurface(tokens, 10),
                contentAlignment = Alignment.Center
            ) {
                BasicText("M", style = TextStyle(color = chipTextColor(tokens), fontSize = 15.sp, fontWeight = FontWeight.Black))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                LabelText("INBOX", Accent)
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
                    .neu(tokens, 7.dp, NeuLevel.PRESSED_SM)
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                BasicText(
                    text = emails.size.coerceAtMost(99).toString(),
                    maxLines = 1,
                    style = TextStyle(color = Accent, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .recessedTray(tokens, 18, deep = true)
                .padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ContextIcon(tokens, primary, fallback = "M", size = 42)
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
                        Box(Modifier.size(5.dp).clip(RoundedCornerShape(99.dp)).background(Accent.copy(alpha = 0.74f)))
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
    tokens: NeuTokens,
    item: ContextWidgetItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxSize()
            .drawNewsSurface(tokens)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContextIcon(tokens, item, fallback = "N", size = 50)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            LabelText("GOOGLE NEWS", Blue)
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
    tokens: NeuTokens,
    item: ContextWidgetItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxSize()
            .drawMapsSurface(tokens)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(22.dp))
                .recessedTray(tokens, 18, deep = true)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val road = InkFaint.copy(alpha = 0.4f)
                drawLine(road, Offset(size.width * 0.14f, size.height * 0.24f), Offset(size.width * 0.86f, size.height * 0.72f), strokeWidth = 7.dp.toPx())
                drawLine(road, Offset(size.width * 0.18f, size.height * 0.78f), Offset(size.width * 0.80f, size.height * 0.18f), strokeWidth = 5.dp.toPx())
                drawLine(GreenSoft.copy(alpha = 0.82f), Offset(size.width * 0.14f, size.height * 0.24f), Offset(size.width * 0.55f, size.height * 0.51f), strokeWidth = 3.dp.toPx())
                drawCircle(tokens.baseCompose, radius = 12.dp.toPx(), center = Offset(size.width * 0.60f, size.height * 0.55f))
                drawCircle(GreenSoft, radius = 7.dp.toPx(), center = Offset(size.width * 0.60f, size.height * 0.55f))
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
private fun ContextIcon(tokens: NeuTokens, item: ContextWidgetItem, fallback: String, size: Int) {
    val accent = Color(item.color)
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(16.dp))
            .recessedTray(tokens, 13, deep = false),
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
                style = TextStyle(color = chipTextColor(tokens), fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.SansSerif)
            )
        }
    }
}

@Composable
private fun RecentPeopleCard(
    tokens: NeuTokens,
    people: List<RecentPerson>,
    onPersonClick: (RecentPerson) -> Unit,
    onPersonLongClick: (RecentPerson) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxSize()
            .drawPeopleSurface(tokens)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            LabelText("RECENT PEOPLE", Teal)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.height(1.dp).weight(1f).background(Teal.copy(alpha = 0.12f)))
            if (people.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                BasicText(
                    text = people.size.coerceAtMost(6).toString(),
                    maxLines = 1,
                    style = TextStyle(color = Teal, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                )
            }
        }
        if (people.isEmpty()) {
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                    repeat(3) { index ->
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .neu(tokens, 99.dp, NeuLevel.RAISED_SM)
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                RecentPersonWideCard(
                    tokens = tokens,
                    person = people.first(),
                    onClick = { onPersonClick(people.first()) },
                    onLongClick = { onPersonLongClick(people.first()) }
                )
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                people.take(3).forEach { person ->
                    RecentPersonChip(
                        tokens = tokens,
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
    tokens: NeuTokens,
    person: RecentPerson,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val accent = Color(person.color)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(RoundedCornerShape(22.dp))
            .recessedTray(tokens, 16, deep = false)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfileOrb(tokens, person, size = 54, fontSize = 15)
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
    tokens: NeuTokens,
    person: RecentPerson,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .recessedTray(tokens, 16, deep = false)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 7.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            ProfileOrb(tokens, person, size = 38, fontSize = 11)
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
private fun ProfileOrb(tokens: NeuTokens, person: RecentPerson, size: Int, fontSize: Int) {
    val accent = Color(person.color)
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(99.dp))
            .neu(tokens, 99.dp, NeuLevel.RAISED_SM),
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
    tokens: NeuTokens,
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
            .drawCalendarSurface(tokens)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CalendarNowBlock(
            tokens = tokens,
            event = current,
            hasPermission = hasPermission,
            modifier = Modifier.weight(1.08f)
        )
        Spacer(Modifier.width(10.dp))
        CalendarNextBlock(
            tokens = tokens,
            event = upcoming,
            hasPermission = hasPermission,
            count = events.size,
            modifier = Modifier.weight(0.92f)
        )
    }
}

@Composable
private fun CalendarNowBlock(tokens: NeuTokens, event: CalendarEvent?, hasPermission: Boolean, modifier: Modifier) {
    val accent = if (event == null) GreenSoft else Accent
    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .recessedTray(tokens, 16, deep = true)
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
private fun CalendarNextBlock(tokens: NeuTokens, event: CalendarEvent?, hasPermission: Boolean, count: Int, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LabelText("UP NEXT", Amber)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.height(1.dp).weight(1f).background(InkFaint.copy(alpha = 0.18f)))
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
                .recessedTray(tokens, 16, deep = true)
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

private fun Modifier.drawCalendarSurface(tokens: NeuTokens): Modifier = raisedGlassSurface(tokens, 22)

private fun Modifier.drawMusicSurface(tokens: NeuTokens): Modifier = raisedGlassSurface(tokens, 22)

private fun Modifier.drawPeopleSurface(tokens: NeuTokens): Modifier = raisedGlassSurface(tokens, 22)

private fun Modifier.drawEmailSurface(tokens: NeuTokens): Modifier = raisedGlassSurface(tokens, 22)

private fun Modifier.drawNewsSurface(tokens: NeuTokens): Modifier = raisedGlassSurface(tokens, 22)

private fun Modifier.drawMapsSurface(tokens: NeuTokens): Modifier = raisedGlassSurface(tokens, 22)

private fun Modifier.raisedGlassSurface(tokens: NeuTokens, radiusDp: Int): Modifier = this.neu(tokens, radiusDp.dp, NeuLevel.RAISED)

private fun Modifier.recessedTray(tokens: NeuTokens, radiusDp: Int, deep: Boolean = false): Modifier =
    this.neu(tokens, radiusDp.dp, if (deep) NeuLevel.PRESSED else NeuLevel.PRESSED_SM)

private fun Modifier.domedSurface(tokens: NeuTokens, radiusDp: Int): Modifier = this.neu(tokens, radiusDp.dp, NeuLevel.RAISED_SM)

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
