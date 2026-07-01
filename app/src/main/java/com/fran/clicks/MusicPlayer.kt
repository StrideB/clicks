package com.fran.clicks

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowPlayingCard(
    visible: Boolean,
    title: String,
    artist: String,
    sourceApp: String,
    sourceColor: Color = GreenBright,
    albumArt: Bitmap?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    if (!visible) return
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, label = "now-playing-press")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxSize()
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .drawBehind {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFF20242A), Color(0xFF14171C), Color(0xFF101115))
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx())
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(sourceColor.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.84f, size.height * 0.14f),
                        radius = size.width * 0.42f
                    )
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.08f),
                    start = Offset(18.dp.toPx(), 1.dp.toPx()),
                    end = Offset(size.width - 18.dp.toPx(), 1.dp.toPx()),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .border(
                width = 1.dp,
                color = if (pressed) sourceColor.copy(alpha = 0.62f) else Color(0xFF2E333B),
                shape = RoundedCornerShape(22.dp)
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MiniAlbumArt(albumArt, isPlaying, sourceColor)
        Spacer(Modifier.width(15.dp))
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabelText("NOW PLAYING", sourceColor)
                    Spacer(Modifier.width(7.dp))
                    Box(Modifier.size(4.dp).clip(CircleShape).background(sourceColor))
                    Spacer(Modifier.width(7.dp))
                    BasicText(
                        text = sourceApp.ifBlank { "Media" }.uppercase(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = InkDim, fontSize = 8.5.sp, letterSpacing = 0.8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    )
                }
                Spacer(Modifier.height(6.dp))
                BasicText(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
                )
                Spacer(Modifier.height(4.dp))
                BasicText(
                    text = artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = InkDim, fontSize = 12.2.sp, fontFamily = FontFamily.SansSerif)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.weight(1f).height(8.dp)) {
                    val y = size.height / 2f
                    drawLine(Color(0xFF2A2D34), Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(sourceColor.copy(alpha = 0.75f), Offset(0f, y), Offset(size.width * 0.56f, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                }
                Spacer(Modifier.width(12.dp))
                Equalizer(isPlaying, barColor = sourceColor, height = 28.dp)
            }
        }
    }
}

@Composable
fun MusicPlayer(
    media: NowPlayingInfo?,
    initialTheme: String = "music1",
    onThemeChanged: (String) -> Unit = {},
    onOpenSource: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit
) {
    val current = media
    if (current == null) {
        Box(Modifier.fillMaxSize().background(Panel), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LabelText("NO ACTIVE MEDIA SESSION", GreenSoft)
                Spacer(Modifier.height(8.dp))
                BasicText(
                    text = "Start music in any app, then return here.",
                    style = TextStyle(color = InkDim, fontSize = 12.sp, textAlign = TextAlign.Center)
                )
            }
        }
        return
    }

    val tick by produceState(SystemClock.elapsedRealtime(), current.isPlaying, current.positionMs, current.lastUpdateElapsedMs) {
        value = SystemClock.elapsedRealtime()
        while (current.isPlaying) {
            delay(500)
            value = SystemClock.elapsedRealtime()
        }
    }
    val position = currentPlaybackPosition(current, tick)
    val appColor = Color(current.appIconColor)
    var playerTheme by remember(initialTheme) { mutableStateOf(NowPlayingTheme.fromId(initialTheme)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Panel)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.055f), Color.White.copy(alpha = 0.018f), Color.Transparent),
                        center = Offset(size.width * 0.46f, size.height * 0.12f),
                        radius = size.minDimension * 0.86f
                    )
                )
            }
    ) {
        if (playerTheme == NowPlayingTheme.MUSIC1) {
            FullPaneAlbumArt(current.title, current.albumArt, appColor)
        }
        when (playerTheme) {
            NowPlayingTheme.MUSIC1 -> MusicOneLayout(
                title = current.title,
                artist = current.artist,
                album = current.album,
                position = position,
                duration = current.durationMs,
                appColor = appColor,
                gestureModifier = Modifier.recordGestures(
                    positionMs = position,
                    durationMs = current.durationMs,
                    onTogglePlayPause = onTogglePlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSeekTo = onSeekTo
                )
            )
            NowPlayingTheme.MUSIC_BLACK -> MusicBlackLayout(
                title = current.title,
                artist = current.artist,
                album = current.album,
                albumArt = current.albumArt,
                isPlaying = current.isPlaying,
                position = position,
                duration = current.durationMs,
                appColor = appColor,
                onPrevious = onPrevious,
                onTogglePlayPause = onTogglePlayPause,
                onNext = onNext
            )
        }
        MusicGlassHeader(
            theme = playerTheme,
            sourceApp = current.sourceApp,
            sourceIcon = current.appIcon,
            appColor = appColor,
            albumArt = current.albumArt,
            isPlaying = current.isPlaying,
            onThemeClick = {
                playerTheme = playerTheme.next()
                onThemeChanged(playerTheme.id)
            },
            onSourceClick = onOpenSource,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp, start = 12.dp, end = 12.dp)
        )
    }
}

private enum class NowPlayingTheme {
    MUSIC1,
    MUSIC_BLACK;

    val id: String
        get() = when (this) {
            MUSIC1 -> "music1"
            MUSIC_BLACK -> "music_black"
        }

    fun next() = when (this) {
        MUSIC1 -> MUSIC_BLACK
        MUSIC_BLACK -> MUSIC1
    }

    companion object {
        fun fromId(id: String) = when (id) {
            "music_black" -> MUSIC_BLACK
            else -> MUSIC1
        }
    }
}

@Composable
private fun MusicOneLayout(
    title: String,
    artist: String,
    album: String,
    position: Long,
    duration: Long,
    appColor: Color,
    gestureModifier: Modifier
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.size(222.dp).then(gestureModifier))
        Spacer(Modifier.height(13.dp))
        NowPlayingMeta(title, artist, album)
        Spacer(Modifier.height(11.dp))
        ClassicLineProgress(position, duration, appColor)
    }
}

@Composable
private fun MusicBlackLayout(
    title: String,
    artist: String,
    album: String,
    albumArt: Bitmap?,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    appColor: Color,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LabelText("PLAYING NOW", InkDim)
        Spacer(Modifier.height(18.dp))
        MusicBlackAlbumDisc(title, albumArt)
        Spacer(Modifier.height(18.dp))
        BasicText(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        )
        Spacer(Modifier.height(7.dp))
        BasicText(
            text = listOf(artist, album).filter { it.isNotBlank() }.joinToString("  ·  "),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = InkDim, fontSize = 14.sp, textAlign = TextAlign.Center)
        )
        Spacer(Modifier.height(26.dp))
        SimpleLineProgress(position, duration, appColor)
    }
}

@Composable
private fun MusicBlackAlbumDisc(title: String, albumArt: Bitmap?) {
    Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f
            drawCircle(Color.Black.copy(alpha = 0.46f), radius = r * 0.98f, center = Offset(center.x, center.y + 10.dp.toPx()))
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFF363B43), Color(0xFF1A1D22), Color(0xFF07080A)),
                    center = Offset(center.x - r * 0.25f, center.y - r * 0.28f),
                    radius = r
                ),
                radius = r,
                center = center
            )
            drawCircle(Color.Black.copy(alpha = 0.74f), radius = r, center = center, style = Stroke(width = 9.dp.toPx()))
            drawCircle(Color.White.copy(alpha = 0.09f), radius = r - 8.dp.toPx(), center = center, style = Stroke(width = 1.dp.toPx()))
        }
        Box(
            Modifier
                .size(172.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF2F343B), Color(0xFF07080A))))
        ) {
            if (albumArt != null) {
                Image(albumArt.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.radialGradient(listOf(Color(0xFF454A52), Color(0xFF15181D), Color(0xFF050506)))),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = title.take(16).uppercase(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = Ink,
                            fontSize = 18.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f), Color.Black.copy(alpha = 0.78f)),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.minDimension / 2f
                    )
                )
                drawArc(
                    brush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent)),
                    startAngle = 226f,
                    sweepAngle = 30f,
                    useCenter = true,
                    topLeft = Offset.Zero,
                    size = size
                )
            }
        }
    }
}

@Composable
private fun MusicBlackTransport(
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundTransportButton(label = "◀", size = 62.dp, background = Color(0xFF1B2026), labelColor = InkDim, onClick = onPrevious)
        RoundTransportButton(
            label = if (isPlaying) "Ⅱ" else "▶",
            size = 76.dp,
            background = Color(0xFFFF5A1F),
            labelColor = Color.White,
            onClick = onTogglePlayPause
        )
        RoundTransportButton(label = "▶", size = 62.dp, background = Color(0xFF1B2026), labelColor = InkDim, onClick = onNext)
    }
}

@Composable
private fun RoundTransportButton(
    label: String,
    size: Dp,
    background: Color,
    labelColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Transparent)
            .clickable(onClick = onClick)
            .drawBehind {
                val r = this.size.minDimension / 2f
                val c = Offset(this.size.width / 2f, this.size.height / 2f)
                drawCircle(Color.Black.copy(alpha = 0.48f), radius = r * 0.98f, center = Offset(c.x, c.y + 5.dp.toPx()))
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(background.copy(alpha = 1f), background.copy(alpha = 0.74f), Color(0xFF07080A)),
                        center = Offset(c.x - r * 0.24f, c.y - r * 0.28f),
                        radius = r * 1.15f
                    ),
                    radius = r,
                    center = c
                )
                drawCircle(Color.White.copy(alpha = 0.08f), radius = r - 3.dp.toPx(), center = c, style = Stroke(width = 1.dp.toPx()))
                drawCircle(Color.Black.copy(alpha = 0.42f), radius = r, center = c, style = Stroke(width = 2.dp.toPx()))
            },
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = labelColor, fontSize = if (size > 70.dp) 27.sp else 21.sp, fontWeight = FontWeight.Black)
        )
    }
}

@Composable
private fun BlackVinylStage(title: String, albumArt: Bitmap?, isPlaying: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(252.dp, 238.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.025f), Color.Transparent),
                    center = Offset(size.width * 0.42f, size.height * 0.18f),
                    radius = size.width * 0.64f
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(28.dp.toPx())
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.54f),
                radius = 104.dp.toPx(),
                center = Offset(size.width / 2f, size.height * 0.55f)
            )
        }
        BasicText(
            text = "★ ★ ★ ★ ✦",
            style = TextStyle(
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 16.sp,
                letterSpacing = 2.2.sp,
                fontWeight = FontWeight.Bold
            ),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
        )
        PortraitVinylDisc(
            title = title,
            albumArt = albumArt,
            isPlaying = isPlaying,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 18.dp)
        )
        Tonearm(isPlaying, Modifier.align(Alignment.TopEnd).padding(top = 44.dp, end = 8.dp))
    }
}

@Composable
private fun PortraitVinylDisc(
    title: String,
    albumArt: Bitmap?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "portrait-vinyl-spin")
    val rotation by if (isPlaying) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(3400, easing = LinearEasing)),
            label = "portrait-vinyl-rotation"
        )
    } else {
        animateFloatAsState(0f, label = "portrait-vinyl-paused")
    }

    Box(modifier.size(212.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xEEFFFFFF), Color(0x33191B20), Color.Transparent),
                    center = Offset(center.x - r * 0.18f, center.y - r * 0.24f),
                    radius = r * 1.18f
                ),
                radius = r
            )
            drawCircle(Color.Black.copy(alpha = 0.62f), radius = r, style = Stroke(width = 5.dp.toPx()))
            drawCircle(Color.White.copy(alpha = 0.18f), radius = r - 8.dp.toPx(), style = Stroke(width = 1.dp.toPx()))
        }
        Box(
            Modifier
                .size(196.dp)
                .rotate(rotation)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF343841), Color(0xFF07080A))))
        ) {
            if (albumArt != null) {
                Image(albumArt.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.radialGradient(listOf(Color(0xFF8FD694), Color(0xFF1A1D22), Color(0xFF050506)))),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = title.take(18).uppercase(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = Color(0xFF071009),
                            fontSize = 18.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val r = size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f), Color.Black.copy(alpha = 0.58f)),
                        center = center,
                        radius = r
                    )
                )
                for (ring in 34..176 step 9) {
                    drawCircle(
                        Color.White.copy(alpha = if (ring % 18 == 0) 0.20f else 0.11f),
                        radius = ring.dp.toPx() / 2f,
                        style = Stroke(width = 0.75.dp.toPx())
                    )
                }
                drawArc(
                    brush = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.34f), Color.Transparent)),
                    startAngle = 214f,
                    sweepAngle = 34f,
                    useCenter = true,
                    topLeft = Offset.Zero,
                    size = size
                )
            }
        }
        Box(Modifier.size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF111318)))
        }
    }
}

@Composable
private fun FullPaneAlbumArt(title: String, albumArt: Bitmap?, appColor: Color) {
    Box(Modifier.fillMaxSize()) {
        if (albumArt != null) {
            Image(albumArt.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.radialGradient(listOf(appColor.copy(alpha = 0.95f), Color(0xFF111318)))),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = title.take(34).uppercase(),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = Color(0xFF071009),
                        fontSize = 30.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.padding(28.dp)
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xCC16181D),
                            Color(0x4416181D),
                            Color(0xDD0D0E11)
                        )
                    )
                )
        )
    }
}

@Composable
private fun MusicGlassHeader(
    theme: NowPlayingTheme,
    sourceApp: String,
    sourceIcon: Bitmap?,
    appColor: Color,
    albumArt: Bitmap?,
    isPlaying: Boolean,
    onThemeClick: () -> Unit,
    onSourceClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(24.dp))
            .drawBehind {
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.26f),
                    topLeft = Offset(0f, 4.dp.toPx()),
                    size = size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx())
                )
            }
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
    ) {
        if (albumArt != null) {
            Image(
                albumArt.asImageBitmap(),
                null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(18.dp)
                    .graphicsLayer(alpha = 0.42f),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .blur(18.dp)
                    .background(Brush.radialGradient(listOf(appColor.copy(alpha = 0.34f), Color.Transparent)))
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x6616181D),
                            Color(0x4416181D),
                            Color(0x7A08090B)
                        )
                    )
                )
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThemeIconButton(theme, appColor, onThemeClick)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                LabelText("NOW PLAYING", Ink.copy(alpha = 0.82f))
            }
            SourceIconButton(
                appName = sourceApp,
                appIconColor = appColor,
                appIcon = sourceIcon,
                isPlaying = isPlaying,
                onClick = onSourceClick
            )
        }
    }
}

@Composable
private fun VinylDisc(title: String, albumArt: Bitmap?, isPlaying: Boolean) {
    val transition = rememberInfiniteTransition(label = "vinyl-spin")
    val rotation by if (isPlaying) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(3400, easing = LinearEasing)),
            label = "vinyl-rotation"
        )
    } else {
        animateFloatAsState(0f, label = "vinyl-paused")
    }

    Box(Modifier.size(186.dp).rotate(rotation), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f
            drawCircle(Brush.radialGradient(listOf(Color(0xFF222229), Color(0xFF0D0D11), Color(0xFF020203)), center, r))
            drawCircle(Color(0xCC000000), radius = r - 2.dp.toPx(), style = Stroke(width = 7.dp.toPx()))
            for (i in 18..92 step 6) {
                drawCircle(Color(0x7717181D), radius = i.dp.toPx() / 2f, style = Stroke(width = 0.9.dp.toPx()))
            }
            drawArc(
                brush = Brush.linearGradient(listOf(Color(0x52FFFFFF), Color.Transparent)),
                startAngle = 220f,
                sweepAngle = 32f,
                useCenter = true,
                topLeft = Offset.Zero,
                size = size
            )
        }
        AlbumLabel(title, albumArt)
    }
}

@Composable
private fun AlbumLabel(title: String, albumArt: Bitmap?) {
    Box(
        Modifier
            .size(138.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFFA9F0B6), Color(0xFF57C98A), Color(0xFF2F9F7A)))),
        contentAlignment = Alignment.Center
    ) {
        if (albumArt != null) {
            Image(albumArt.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            BasicText(
                text = title.take(20).uppercase(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = Color(0xFF0C2B1F),
                    fontSize = 13.5.sp,
                    lineHeight = 13.5.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier.padding(horizontal = 13.dp)
            )
        }
        Box(Modifier.size(11.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFFE8EFE6), Color(0xFF626C66)))))
    }
}

@Composable
private fun Tonearm(isPlaying: Boolean, modifier: Modifier = Modifier) {
    val angle by animateFloatAsState(
        targetValue = if (isPlaying) -6f else -32f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 210f),
        label = "tonearm-angle"
    )
    Canvas(modifier.size(72.dp, 62.dp).rotate(angle)) {
        val base = Offset(size.width - 15.dp.toPx(), 13.dp.toPx())
        val tip = Offset(17.dp.toPx(), size.height - 10.dp.toPx())
        drawCircle(Color(0xFF2D3038), radius = 14.dp.toPx(), center = base)
        drawCircle(Color(0xFF0B0C10), radius = 8.dp.toPx(), center = base)
        drawLine(Color(0xFFC7CDC4), base, tip, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Color(0xFF4E555A), base, tip, strokeWidth = 1.2.dp.toPx(), cap = StrokeCap.Round)
        val shell = Path().apply {
            moveTo(tip.x - 8.dp.toPx(), tip.y - 4.dp.toPx())
            lineTo(tip.x + 8.dp.toPx(), tip.y - 1.dp.toPx())
            lineTo(tip.x + 4.dp.toPx(), tip.y + 8.dp.toPx())
            lineTo(tip.x - 9.dp.toPx(), tip.y + 6.dp.toPx())
            close()
        }
        drawPath(shell, Color(0xFF1E2228))
    }
}

@Composable
private fun SourceChip(appName: String, appIconColor: Color, isPlaying: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(appIconColor.copy(alpha = 0.12f))
            .border(1.dp, appIconColor.copy(alpha = 0.3f), RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(17.dp).clip(CircleShape).background(appIconColor), contentAlignment = Alignment.Center) {
            BasicText(
                text = appName.take(1).uppercase(),
                style = TextStyle(color = Color(0xFF071009), fontSize = 10.sp, fontWeight = FontWeight.Black)
            )
        }
        Spacer(Modifier.width(7.dp))
        BasicText(
            text = "Playing from $appName",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Ink, fontSize = 11.sp, fontFamily = FontFamily.SansSerif)
        )
        Spacer(Modifier.width(8.dp))
        Equalizer(isPlaying, appIconColor, height = 16.dp)
    }
}

@Composable
private fun NowPlayingMeta(title: String, artist: String, album: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
        )
        Spacer(Modifier.height(3.dp))
        BasicText(
            text = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" . "),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = InkDim, fontSize = 10.8.sp, textAlign = TextAlign.Center)
        )
    }
}

@Composable
private fun ClassicLineProgress(positionMs: Long, durationMs: Long, appColor: Color = GreenBright) {
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(7.dp)) {
            val y = size.height / 2f
            drawLine(Color(0xFF2A2D34), Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            drawLine(
                Brush.horizontalGradient(listOf(appColor, appColor.copy(alpha = 0.72f), GreenSoft)),
                Offset(0f, y),
                Offset(size.width * progress, y),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TimeText(formatMs(positionMs))
            TimeText(formatMs(durationMs))
        }
    }
}

@Composable
private fun RadioFrequencyProgress(positionMs: Long, durationMs: Long, appColor: Color = GreenBright) {
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth(0.84f).height(48.dp)) {
            val barCount = 34
            val gap = size.width / (barCount - 1).coerceAtLeast(1)
            val activeCutoff = progress * (barCount - 1)
            val centerX = size.width * progress
            val levels = floatArrayOf(
                0.22f, 0.38f, 0.58f, 0.82f, 0.44f, 0.68f, 0.96f, 0.52f, 0.34f, 0.72f, 0.88f,
                0.46f, 0.28f, 0.62f, 0.78f, 0.55f, 0.36f
            )
            repeat(barCount) { index ->
                val x = gap * index
                val normalizedDistance = abs(index - activeCutoff) / barCount
                val heightBoost = (1f - normalizedDistance * 3.4f).coerceIn(0f, 0.34f)
                val h = size.height * (levels[index % levels.size] + heightBoost).coerceIn(0.18f, 1f)
                val active = index <= activeCutoff
                drawLine(
                    color = if (active) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.24f),
                    start = Offset(x, (size.height - h) / 2f),
                    end = Offset(x, (size.height + h) / 2f),
                    strokeWidth = if (active) 2.2.dp.toPx() else 1.7.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            drawLine(
                color = appColor.copy(alpha = 0.92f),
                start = Offset(centerX, 3.dp.toPx()),
                end = Offset(centerX, size.height - 3.dp.toPx()),
                strokeWidth = 1.4.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        Row(Modifier.fillMaxWidth(0.84f), horizontalArrangement = Arrangement.SpaceBetween) {
            TimeText(formatMs(positionMs))
            TimeText(formatMs(durationMs))
        }
    }
}

@Composable
private fun SimpleLineProgress(positionMs: Long, durationMs: Long, appColor: Color = GreenBright) {
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TimeText(formatMs(positionMs))
            TimeText(formatMs(durationMs))
        }
        Spacer(Modifier.height(7.dp))
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            val y = size.height / 2f
            drawLine(
                color = Color.Black.copy(alpha = 0.62f),
                start = Offset(0f, y + 2.dp.toPx()),
                end = Offset(size.width, y + 2.dp.toPx()),
                strokeWidth = 5.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color(0xFF090A0D),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                brush = Brush.horizontalGradient(listOf(Color(0xFFFF5A1F), Color(0xFFFFC451), appColor.copy(alpha = 0.82f))),
                start = Offset(0f, y),
                end = Offset(size.width * progress, y),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )
            val knobX = size.width * progress
            drawCircle(Color.Black.copy(alpha = 0.62f), radius = 11.dp.toPx(), center = Offset(knobX, y + 3.dp.toPx()))
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0xFFFFC451), Color(0xFF262A30), Color(0xFF08090B))),
                radius = 9.dp.toPx(),
                center = Offset(knobX, y)
            )
        }
    }
}

@Composable
private fun MiniAlbumArt(albumArt: Bitmap?, isPlaying: Boolean, sourceColor: Color) {
    Box(
        Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.radialGradient(listOf(sourceColor.copy(alpha = 0.95f), Color(0xFF101215)))),
        contentAlignment = Alignment.Center
    ) {
        if (albumArt != null) {
            Image(albumArt.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        VinylDot(isPlaying)
    }
}

@Composable
private fun SourceIconButton(
    appName: String,
    appIconColor: Color,
    appIcon: Bitmap?,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color(0xFF111318))
            .border(1.dp, appIconColor.copy(alpha = 0.42f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(appIconColor), contentAlignment = Alignment.Center) {
            if (appIcon != null) {
                Image(appIcon.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                BasicText(
                    text = appName.take(1).uppercase().ifBlank { "M" },
                    style = TextStyle(color = Color(0xFF071009), fontSize = 14.sp, fontWeight = FontWeight.Black)
                )
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(2.dp)) {
            Equalizer(isPlaying, appIconColor, height = 12.dp)
        }
    }
}

@Composable
private fun VinylDot(isPlaying: Boolean) {
    val transition = rememberInfiniteTransition(label = "mini-vinyl")
    val rotation by if (isPlaying) {
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(3400, easing = LinearEasing)), label = "mini-vinyl-spin")
    } else {
        animateFloatAsState(0f, label = "mini-vinyl-paused")
    }
    Canvas(Modifier.size(28.dp).rotate(rotation)) {
        drawCircle(Color(0xFF08090B))
        drawCircle(Color(0xFF15171B), radius = size.minDimension * 0.32f)
        drawLine(Color(0x55FFFFFF), Offset(size.width * 0.5f, 0f), Offset(size.width * 0.5f, size.height), strokeWidth = 1.dp.toPx())
    }
}

@Composable
private fun ThemeIconButton(
    theme: NowPlayingTheme,
    appColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color(0xFF111318).copy(alpha = 0.92f))
            .border(1.dp, appColor.copy(alpha = 0.38f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(24.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Color.White.copy(alpha = 0.13f), radius = size.minDimension * 0.48f, center = c)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(appColor.copy(alpha = 0.95f), Color(0xFF111318)),
                    center = c,
                    radius = size.minDimension * 0.5f
                ),
                radius = size.minDimension * 0.34f,
                center = c
            )
            drawCircle(Color(0xFF050506), radius = size.minDimension * 0.11f, center = c)
            if (theme == NowPlayingTheme.MUSIC_BLACK) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.78f),
                    topLeft = Offset(size.width * 0.60f, size.height * 0.10f),
                    size = Size(size.width * 0.28f, size.height * 0.28f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun Equalizer(isPlaying: Boolean, barColor: Color, height: Dp) {
    val transition = rememberInfiniteTransition(label = "eq")
    Row(Modifier.height(height).width(30.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        repeat(4) { index ->
            val animated by if (isPlaying) {
                transition.animateFloat(
                    initialValue = 0.28f + index * 0.08f,
                    targetValue = 1f - index * 0.08f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900, delayMillis = index * 110, easing = EaseInOutSine),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "eq-$index"
                )
            } else {
                animateFloatAsState(0.35f + index * 0.07f, label = "eq-paused-$index")
            }
            Box(
                Modifier
                    .width(2.5.dp)
                    .fillMaxHeight(animated.coerceIn(0.2f, 1f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor)
            )
        }
    }
}

@Composable
private fun LabelText(text: String, color: Color) {
    BasicText(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(color = color, fontSize = 8.5.sp, letterSpacing = 1.2.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    )
}

@Composable
private fun TimeText(text: String) {
    BasicText(text, style = TextStyle(color = InkDim, fontSize = 9.sp, fontFamily = FontFamily.Monospace))
}

private fun currentPlaybackPosition(info: NowPlayingInfo, nowElapsed: Long): Long {
    if (!info.isPlaying) return info.positionMs.coerceAtLeast(0L)
    val elapsed = (nowElapsed - info.lastUpdateElapsedMs).coerceAtLeast(0L)
    val current = info.positionMs + elapsed
    return if (info.durationMs > 0) current.coerceIn(0L, info.durationMs) else current
}

private fun Modifier.recordGestures(
    positionMs: Long,
    durationMs: Long,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekTo: (Long) -> Unit
): Modifier = pointerInput(positionMs, durationMs) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val center = Offset(size.width / 2f, size.height / 2f)
        val touchSlop = 12.dp.toPx()
        val scrubSlop = 28.dp.toPx()
        var last = down.position
        var totalDx = 0f
        var totalDy = 0f
        var maxTravel = 0f
        val startAngle = angleFor(down.position, center)
        var endAngle = startAngle
        var isPressed = true

        while (isPressed) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) {
                isPressed = false
            } else {
                val delta = change.position - last
                totalDx += delta.x
                totalDy += delta.y
                last = change.position
                endAngle = angleFor(change.position, center)
                maxTravel = maxTravel.coerceAtLeast((change.position - down.position).getDistance())
                change.consume()
            }
        }

        if (maxTravel < touchSlop) {
            onTogglePlayPause()
            return@awaitEachGesture
        }

        val angleDelta = shortestAngleDelta(startAngle, endAngle)
        val isArc = abs(angleDelta) > (PI.toFloat() * 0.72f) && abs(totalDy) > touchSlop
        if (isArc) {
            if (totalDx >= 0f) onNext() else onPrevious()
            return@awaitEachGesture
        }

        if (durationMs > 0L && abs(totalDx) > scrubSlop) {
            val scrubWindow = min(durationMs / 3L, 45_000L).coerceAtLeast(8_000L)
            val deltaMs = ((totalDx / size.width.toFloat()).coerceIn(-1f, 1f) * scrubWindow).toLong()
            onSeekTo((positionMs + deltaMs).coerceIn(0L, durationMs))
        }
    }
}

private fun angleFor(point: Offset, center: Offset): Float {
    return atan2(point.y - center.y, point.x - center.x)
}

private fun shortestAngleDelta(start: Float, end: Float): Float {
    var delta = end - start
    val full = (PI * 2.0).toFloat()
    while (delta > PI.toFloat()) delta -= full
    while (delta < -PI.toFloat()) delta += full
    return delta
}

private fun formatMs(value: Long): String {
    if (value <= 0L) return "0:00"
    val totalSeconds = value / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private val Ink = Color(0xFFF3F0E7)
private val InkDim = Color(0xFF8B8F99)
private val Panel = Color(0xFF16181D)
private val GreenSoft = Color(0xFF8FD694)
private val GreenBright = Color(0xFF57C98A)
