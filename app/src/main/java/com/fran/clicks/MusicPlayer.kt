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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlin.math.min

@Composable
fun NowPlayingCard(
    visible: Boolean,
    title: String,
    artist: String,
    sourceApp: String,
    albumArt: Bitmap?,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    if (!visible) return
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, label = "now-playing-press")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .drawBehind {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(Color(0x268FD694), Color(0x108FD694))
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx())
                )
            }
            .border(
                width = 1.dp,
                color = if (pressed) GreenBright else Color(0x4D8FD694),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MiniAlbumArt(albumArt, isPlaying)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            LabelText("NOW PLAYING . ${sourceApp.uppercase()}", GreenSoft)
            BasicText(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
            )
            BasicText(
                text = artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = InkDim, fontSize = 11.sp, fontFamily = FontFamily.SansSerif)
            )
        }
        Equalizer(isPlaying, barColor = GreenSoft, height = 25.dp)
    }
}

@Composable
fun MusicPlayer(
    media: NowPlayingInfo?,
    onOpenSource: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
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
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(if (current.isPlaying) 500 else 1200)
        }
    }
    val position = currentPlaybackPosition(current, tick)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Panel)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Turntable(current.title, current.albumArt, current.isPlaying)
        Spacer(Modifier.height(6.dp))
        SourceChip(
            appName = current.sourceApp,
            appIconColor = Color(current.appIconColor),
            isPlaying = current.isPlaying,
            onClick = onOpenSource
        )
        Spacer(Modifier.height(6.dp))
        NowPlayingMeta(current.title, current.artist, current.album)
        Spacer(Modifier.height(6.dp))
        PlayerProgress(position, current.durationMs)
        Spacer(Modifier.height(7.dp))
        Transport(current.isPlaying, onPrevious, onTogglePlayPause, onNext)
    }
}

@Composable
private fun Turntable(title: String, albumArt: Bitmap?, isPlaying: Boolean) {
    Box(Modifier.size(162.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(154.dp)) {
            val r = min(size.width, size.height) / 2f
            drawCircle(Brush.radialGradient(listOf(Color(0xFF2A2D34), Color(0xFF070709)), radius = r))
            drawCircle(Color(0xAA000000), radius = r, style = Stroke(width = 10.dp.toPx()))
        }
        VinylDisc(title, albumArt, isPlaying)
        Tonearm(isPlaying, Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 3.dp))
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

    Box(Modifier.size(132.dp).rotate(rotation), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f
            drawCircle(Brush.radialGradient(listOf(Color(0xFF17171B), Color(0xFF060608)), center, r))
            for (i in 12..66 step 5) {
                drawCircle(Color(0x66141418), radius = i.dp.toPx() / 2f, style = Stroke(width = 0.8.dp.toPx()))
            }
            drawArc(
                brush = Brush.linearGradient(listOf(Color(0x3DFFFFFF), Color.Transparent)),
                startAngle = 220f,
                sweepAngle = 26f,
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
            .size(56.dp)
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
                    fontSize = 8.sp,
                    lineHeight = 8.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier.padding(horizontal = 7.dp)
            )
        }
        Box(Modifier.size(7.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFFE8EFE6), Color(0xFF626C66)))))
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
private fun PlayerProgress(positionMs: Long, durationMs: Long) {
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(7.dp)) {
            val y = size.height / 2f
            drawLine(Color(0xFF2A2D34), Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            drawLine(
                Brush.horizontalGradient(listOf(GreenBright, GreenSoft)),
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
private fun Transport(isPlaying: Boolean, onPrevious: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        TransportButton("<<", 32.dp, false, onPrevious)
        Spacer(Modifier.width(16.dp))
        TransportButton(if (isPlaying) "II" else ">", 42.dp, true, onToggle)
        Spacer(Modifier.width(16.dp))
        TransportButton(">>", 32.dp, false, onNext)
    }
}

@Composable
private fun TransportButton(label: String, size: Dp, primary: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (primary) Brush.verticalGradient(listOf(GreenSoft, GreenBright)) else Brush.verticalGradient(listOf(Color(0xFF20232A), Color(0xFF111216))))
            .border(1.dp, if (primary) Color(0x668FD694) else Color(0xFF2A2C33), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = TextStyle(color = if (primary) Color(0xFF071009) else Ink, fontSize = if (primary) 17.sp else 13.sp, fontWeight = FontWeight.Black)
        )
    }
}

@Composable
private fun MiniAlbumArt(albumArt: Bitmap?, isPlaying: Boolean) {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(Brush.radialGradient(listOf(Color(0xFFA9F0B6), Color(0xFF2F9F7A)))), contentAlignment = Alignment.Center) {
        if (albumArt != null) {
            Image(albumArt.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        VinylDot(isPlaying)
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
    Canvas(Modifier.size(18.dp).rotate(rotation)) {
        drawCircle(Color(0xFF08090B))
        drawCircle(Color(0xFF15171B), radius = size.minDimension * 0.32f)
        drawLine(Color(0x55FFFFFF), Offset(size.width * 0.5f, 0f), Offset(size.width * 0.5f, size.height), strokeWidth = 1.dp.toPx())
    }
}

@Composable
private fun Equalizer(isPlaying: Boolean, barColor: Color, height: Dp) {
    val transition = rememberInfiniteTransition(label = "eq")
    Row(Modifier.height(height).width(22.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
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
