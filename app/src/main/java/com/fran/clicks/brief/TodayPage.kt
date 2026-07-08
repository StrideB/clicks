package com.fran.clicks.brief

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fran.clicks.Neu
import com.fran.clicks.NeuLevel
import com.fran.clicks.NeuMode
import com.fran.clicks.NeuTokens
import com.fran.clicks.neu
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TodayKeyboardMode {
    DOCKED,
    WIDGET
}

private val Mono = FontFamily.Monospace

// Semantic accent per category. Color lives only in labels, nodes, chips and primary action text.
private fun accentFor(category: BriefCategory): Color = Color(
    when (category) {
        BriefCategory.MESSAGE -> Neu.TEAL
        BriefCategory.EMAIL -> Neu.ACCENT
        BriefCategory.CALL -> Neu.GREEN
        BriefCategory.CALENDAR -> Neu.AMBER
        BriefCategory.WEATHER -> Neu.BLUE
        BriefCategory.MUSIC -> Neu.GREEN
        BriefCategory.OTHER -> Neu.PURPLE
    }
)

private fun labelFor(category: BriefCategory): String = when (category) {
    BriefCategory.MESSAGE -> "MESSAGE"
    BriefCategory.EMAIL -> "INBOX"
    BriefCategory.CALL -> "CALL"
    BriefCategory.CALENDAR -> "CALENDAR"
    BriefCategory.WEATHER -> "WEATHER"
    BriefCategory.MUSIC -> "MUSIC"
    BriefCategory.OTHER -> "ALERT"
}

private fun glyphFor(item: BriefItem): String {
    if (item.category == BriefCategory.MUSIC) return "♪"
    if (item.category == BriefCategory.CALENDAR) return "◆"
    val c = item.title.trim().firstOrNull { it.isLetterOrDigit() }
    return (c?.uppercaseChar() ?: '•').toString()
}

@Composable
fun TodayPage(
    tokens: NeuTokens,
    brief: Brief,
    hasListenerPermission: Boolean,
    keyboardMode: TodayKeyboardMode = TodayKeyboardMode.DOCKED,
    transparentShell: Boolean = false,
    onAction: (BriefItem, BriefAction, String?) -> Unit,
    onDismiss: (BriefItem) -> Unit,
    onGrantPermission: () -> Unit,
) {
    val timelineItems = remember(brief.items) {
        brief.items
            .filterNot { it.category == BriefCategory.WEATHER }
            .take(6)
    }
    val bottomPadding = if (keyboardMode == TodayKeyboardMode.WIDGET) 92.dp else 18.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (transparentShell) Modifier else Modifier.background(tokens.baseCompose))
            .padding(horizontal = 14.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        TodayHeader(tokens, timelineItems.size)

        when {
            !hasListenerPermission -> GrantState(tokens, onGrantPermission)
            timelineItems.isEmpty() -> EmptyState(tokens)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(timelineItems, key = { _, item -> item.signalRef }) { index, item ->
                    TimelineRow(
                        tokens = tokens,
                        item = item,
                        index = index,
                        first = index == 0,
                        last = index == timelineItems.lastIndex,
                        onAction = onAction,
                        onDismiss = onDismiss
                    )
                }
            }
        }
    }
}

@Composable
private fun TodayHeader(tokens: NeuTokens, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 4.dp, bottom = 8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "TODAY",
                color = tokens.inkCompose,
                fontSize = 13.sp,
                fontWeight = FontWeight.W700,
                fontFamily = Mono,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "LIVE TIMELINE",
                color = tokens.inkFaintCompose,
                fontSize = 8.sp,
                fontWeight = FontWeight.W700,
                fontFamily = Mono,
                letterSpacing = 1.6.sp
            )
        }
        if (count > 0) CountBadge(tokens, count.toString())
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineRow(
    tokens: NeuTokens,
    item: BriefItem,
    index: Int,
    first: Boolean,
    last: Boolean,
    onAction: (BriefItem, BriefAction, String?) -> Unit,
    onDismiss: (BriefItem) -> Unit,
) {
    val accent = accentFor(item.category)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top
    ) {
        TimeGutter(tokens, item, index)
        Rail(tokens, accent, first, last)
        TimelineCard(tokens, item, accent, onAction, onDismiss)
    }
}

@Composable
private fun TimeGutter(tokens: NeuTokens, item: BriefItem, index: Int) {
    Box(
        modifier = Modifier
            .width(46.dp)
            .fillMaxHeight(),
        contentAlignment = Alignment.TopEnd
    ) {
        Text(
            timelineTimeLabel(item, index),
            color = if (index == 0) Color(Neu.GREEN) else tokens.inkFaintCompose,
            fontSize = 8.sp,
            fontFamily = Mono,
            fontWeight = FontWeight.W700,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(top = 19.dp, end = 8.dp)
        )
    }
}

@Composable
private fun Rail(tokens: NeuTokens, accent: Color, first: Boolean, last: Boolean) {
    Box(
        modifier = Modifier
            .width(22.dp)
            .fillMaxHeight(),
        contentAlignment = Alignment.TopCenter
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width / 2f
            val top = if (first) 26.dp.toPx() else 0f
            val bottom = if (last) 26.dp.toPx() else size.height
            drawLine(
                color = tokens.inkFaintCompose.copy(alpha = if (tokens.mode == NeuMode.DARK) 0.34f else 0.45f),
                start = Offset(x, top),
                end = Offset(x, bottom),
                strokeWidth = 1.35.dp.toPx()
            )
        }
        Box(
            modifier = Modifier
                .padding(top = 18.dp)
                .size(16.dp)
                .neu(tokens, 8.dp, NeuLevel.RAISED_SM),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineCard(
    tokens: NeuTokens,
    item: BriefItem,
    accent: Color,
    onAction: (BriefItem, BriefAction, String?) -> Unit,
    onDismiss: (BriefItem) -> Unit,
) {
    var expanded by remember(item.signalRef) { mutableStateOf(false) }
    var replying by remember(item.signalRef) { mutableStateOf<Fire?>(null) }
    var replyText by remember(item.signalRef) { mutableStateOf("") }
    val appIcon = rememberAppIcon(item)
    val actions = orderedActions(item, item.signal?.actions.orEmpty()).take(3)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .neu(tokens, 22.dp, NeuLevel.RAISED_SM)
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = { onDismiss(item) }
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChipIcon(tokens, accent, glyphFor(item), appIcon)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    labelFor(item.category),
                    color = accent,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.W700,
                    fontFamily = Mono,
                    letterSpacing = 1.4.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    item.title,
                    color = tokens.inkCompose,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.W700,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.subtitle,
                        color = tokens.inkDimCompose,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            DismissPill(tokens) { onDismiss(item) }
        }

        if (replying != null) {
            Spacer(Modifier.height(10.dp))
            ReplyGroove(
                tokens = tokens,
                accent = accent,
                value = replyText,
                onValueChange = { replyText = it },
                onSend = {
                    val action = replying ?: return@ReplyGroove
                    if (replyText.isNotBlank()) {
                        onAction(item, action, replyText)
                        replyText = ""
                        replying = null
                    }
                },
                onCancel = { replying = null; replyText = "" }
            )
        } else if (expanded && actions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { action ->
                    val isPrimary = action.label.equals(item.primaryActionLabel, ignoreCase = true)
                    ActionKey(
                        tokens = tokens,
                        label = action.label,
                        textColor = if (isPrimary) accent else tokens.inkDimCompose,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        if (action is Fire && action.isReply) replying = action
                        else onAction(item, action, null)
                    }
                }
            }
        }
    }
}

private fun orderedActions(item: BriefItem, actions: List<BriefAction>): List<BriefAction> {
    val primary = actions.firstOrNull { it.label.equals(item.primaryActionLabel, ignoreCase = true) }
    return if (primary == null) actions else listOf(primary) + actions.filter { it !== primary }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionKey(
    tokens: NeuTokens,
    label: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .neu(tokens, 10.dp, if (pressed) NeuLevel.PRESSED_SM else NeuLevel.RAISED_SM)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.W600,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ChipIcon(tokens: NeuTokens, accent: Color, glyph: String, appIcon: ImageBitmap?) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .neu(tokens, 12.dp, NeuLevel.PRESSED_SM),
        contentAlignment = Alignment.Center
    ) {
        if (appIcon != null) {
            Image(
                bitmap = appIcon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(9.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    glyph,
                    color = Color(if (tokens.mode == NeuMode.LIGHT) Neu.LIGHT_CHIP_TEXT else Neu.DARK_CHIP_TEXT),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.W800
                )
            }
        }
    }
}

@Composable
private fun rememberAppIcon(item: BriefItem): ImageBitmap? {
    val pkg = (item.signal as? NotificationSignal)?.packageName ?: return null
    val ctx = LocalContext.current
    return remember(pkg) {
        runCatching {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            if (d is BitmapDrawable && d.bitmap != null) {
                d.bitmap.asImageBitmap()
            } else {
                val w = d.intrinsicWidth.coerceIn(1, 192)
                val h = d.intrinsicHeight.coerceIn(1, 192)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = AndroidCanvas(bmp)
                d.setBounds(0, 0, w, h)
                d.draw(c)
                bmp.asImageBitmap()
            }
        }.getOrNull()
    }
}

@Composable
private fun ReplyGroove(
    tokens: NeuTokens,
    accent: Color,
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .weight(1f)
                .neu(tokens, 11.dp, NeuLevel.PRESSED_SM)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BlinkingCaret()
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text("Type a reply...", color = tokens.inkDimCompose, fontSize = 13.sp, maxLines = 1)
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        textStyle = TextStyle(color = tokens.inkCompose, fontSize = 13.sp),
                        cursorBrush = SolidColor(Color(Neu.GREEN))
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        ActionKeyStatic(tokens, "Send", accent, onSend)
        Spacer(Modifier.width(6.dp))
        ActionKeyStatic(tokens, "x", tokens.inkDimCompose, onCancel)
    }
}

@Composable
private fun ActionKeyStatic(tokens: NeuTokens, label: String, textColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .neu(tokens, 10.dp, NeuLevel.RAISED_SM)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.W600, maxLines = 1)
    }
}

@Composable
private fun BlinkingCaret() {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "caretAlpha"
    )
    Box(
        modifier = Modifier
            .width(2.dp)
            .height(16.dp)
            .background(Color(Neu.GREEN).copy(alpha = alpha))
    )
}

@Composable
private fun CountBadge(tokens: NeuTokens, text: String) {
    Box(
        modifier = Modifier
            .neu(tokens, 7.dp, NeuLevel.PRESSED_SM)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = tokens.inkDimCompose, fontSize = 9.sp, fontFamily = Mono, letterSpacing = 1.sp)
    }
}

@Composable
private fun EmptyState(tokens: NeuTokens) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .neu(tokens, 22.dp, NeuLevel.PRESSED),
        contentAlignment = Alignment.Center
    ) {
        Text("You're all caught up.", color = tokens.inkDimCompose, fontSize = 14.sp, fontWeight = FontWeight.W500)
    }
}

@Composable
private fun GrantState(tokens: NeuTokens, onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .neu(tokens, 22.dp, NeuLevel.PRESSED)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Turn on notification access",
            color = tokens.inkCompose,
            fontSize = 15.sp,
            fontWeight = FontWeight.W600
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Today needs notification access to collect and act on messages, calls and email.",
            color = tokens.inkDimCompose,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(14.dp))
        ActionKeyStatic(tokens, "Grant access", Color(Neu.GREEN), onGrant)
    }
}

@Composable
fun TodayAlert(
    tokens: NeuTokens,
    brief: Brief,
    onOpen: () -> Unit,
    onDismiss: (BriefItem) -> Unit,
) {
    val top = brief.items.firstOrNull { it.category != BriefCategory.WEATHER } ?: return
    val accent = accentFor(top.category)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .neu(tokens, 16.dp, NeuLevel.RAISED)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "TODAY",
                color = accent,
                fontSize = 8.sp,
                fontWeight = FontWeight.W700,
                fontFamily = Mono,
                letterSpacing = 1.5.sp
            )
            Spacer(Modifier.height(1.dp))
            Text(
                top.title,
                color = tokens.inkCompose,
                fontSize = 13.sp,
                fontWeight = FontWeight.W600,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        val count = brief.items.count { it.category != BriefCategory.WEATHER }
        if (count > 1) {
            Spacer(Modifier.width(10.dp))
            CountBadge(tokens, "+${count - 1}")
        }
        Spacer(Modifier.width(8.dp))
        DismissPill(tokens) { onDismiss(top) }
    }
}

@Composable
private fun DismissPill(tokens: NeuTokens, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .neu(tokens, 14.dp, NeuLevel.PRESSED_SM)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Text("×", color = tokens.inkDimCompose, fontSize = 15.sp, fontWeight = FontWeight.W700)
    }
}

private fun timelineTimeLabel(item: BriefItem, index: Int): String {
    if (index == 0) return "NOW"
    val timestamp = item.signal?.timestamp ?: return "LATER"
    return runCatching {
        SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(timestamp)).uppercase(Locale.getDefault())
    }.getOrDefault("LATER")
}
