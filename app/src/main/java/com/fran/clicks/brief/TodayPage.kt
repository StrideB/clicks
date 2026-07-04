package com.fran.clicks.brief

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

// -------------------------------------------------------------------------------------------------
// Semantic accent per category. Color lives ONLY in the mono label + chip glyph + primary-action
// text, per the neumorphic spec. Everything else is one base color + the four shadow presets.
// -------------------------------------------------------------------------------------------------

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
    BriefCategory.MUSIC -> "NOW PLAYING"
    BriefCategory.OTHER -> "ALERT"
}

private fun glyphFor(item: BriefItem): String {
    if (item.category == BriefCategory.WEATHER) return "°"
    if (item.category == BriefCategory.MUSIC) return "♪"
    if (item.category == BriefCategory.CALENDAR) return "◆"
    val c = item.title.trim().firstOrNull { it.isLetterOrDigit() }
    return (c?.uppercaseChar() ?: '•').toString()
}

private val Mono = FontFamily.Monospace

// -------------------------------------------------------------------------------------------------
// Full Today page (the "page to the left" of home).
// -------------------------------------------------------------------------------------------------

@Composable
fun TodayPage(
    tokens: NeuTokens,
    brief: Brief,
    hasListenerPermission: Boolean,
    onAction: (BriefItem, BriefAction, String?) -> Unit,
    onDismiss: (BriefItem) -> Unit,
    onGrantPermission: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.baseCompose)
            .padding(horizontal = 14.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp)) {
            Text(
                "TODAY",
                color = tokens.inkCompose,
                fontSize = 13.sp,
                fontWeight = FontWeight.W700,
                fontFamily = Mono,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.width(8.dp))
            if (brief.items.isNotEmpty()) {
                CountBadge(tokens, brief.items.size.toString())
            }
        }

        when {
            !hasListenerPermission -> GrantState(tokens, onGrantPermission)
            brief.items.isEmpty() -> EmptyState(tokens)
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(9.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(brief.items, key = { it.signalRef }) { item ->
                    TodayCard(tokens, item, onAction, onDismiss)
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TodayCard(
    tokens: NeuTokens,
    item: BriefItem,
    onAction: (BriefItem, BriefAction, String?) -> Unit,
    onDismiss: (BriefItem) -> Unit,
) {
    val accent = accentFor(item.category)
    val appIcon = rememberAppIcon(item)
    var replying by remember(item.signalRef) { mutableStateOf<Fire?>(null) }
    var replyText by remember(item.signalRef) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .neu(tokens, 22.dp, NeuLevel.RAISED)
            .combinedClickable(
                onClick = {
                    item.signal?.actions
                        ?.firstOrNull { it.label.equals(item.primaryActionLabel, ignoreCase = true) }
                        ?.let { a ->
                            if (a is Fire && a.isReply) replying = a else onAction(item, a, null)
                        }
                },
                onLongClick = { onDismiss(item) }
            )
            .padding(horizontal = 16.dp, vertical = 15.dp)
    ) {
        // Label
        Text(
            labelFor(item.category),
            color = accent,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.W700,
            fontFamily = Mono,
            letterSpacing = 1.5.sp
        )
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            ChipIcon(tokens, accent, glyphFor(item), appIcon)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    color = tokens.inkCompose,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W600,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.subtitle,
                        color = tokens.inkDimCompose,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        val actions = item.signal?.actions.orEmpty()
        val current = replying
        if (current != null) {
            Spacer(Modifier.height(12.dp))
            ReplyGroove(
                tokens = tokens,
                accent = accent,
                value = replyText,
                onValueChange = { replyText = it },
                onSend = {
                    if (replyText.isNotBlank()) {
                        onAction(item, current, replyText)
                        replyText = ""
                        replying = null
                    }
                },
                onCancel = { replying = null; replyText = "" }
            )
        } else if (actions.isNotEmpty()) {
            Spacer(Modifier.height(13.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                orderedActions(item, actions).take(3).forEach { action ->
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

// Primary action first, then the rest in their existing order.
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
            .size(46.dp)
            .neu(tokens, 13.dp, NeuLevel.PRESSED_SM),
        contentAlignment = Alignment.Center
    ) {
        if (appIcon != null) {
            // Real launcher icon of the source app, tucked into the carved well.
            Image(
                bitmap = appIcon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(32.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
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

/** Launcher icon of the source app for a notification signal, cached per package. */
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
                val c = Canvas(bmp)
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
                        Text("Type a reply…", color = tokens.inkDimCompose, fontSize = 13.sp, maxLines = 1)
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
        ActionKeyStatic(tokens, "✕", tokens.inkDimCompose, onCancel)
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
            .height(140.dp)
            .neu(tokens, 22.dp, NeuLevel.PRESSED),
        contentAlignment = Alignment.Center
    ) {
        Text("You’re all caught up.", color = tokens.inkDimCompose, fontSize = 14.sp, fontWeight = FontWeight.W500)
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
            "Today needs notification access to collect and act on your messages, calls and email — right here.",
            color = tokens.inkDimCompose,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(14.dp))
        ActionKeyStatic(tokens, "Grant access", Color(Neu.GREEN), onGrant)
    }
}

// -------------------------------------------------------------------------------------------------
// Compact teaser shown in the space below the widget stack. Taps through to the full page.
// -------------------------------------------------------------------------------------------------

@Composable
fun TodayAlert(
    tokens: NeuTokens,
    brief: Brief,
    onOpen: () -> Unit,
) {
    val top = brief.items.firstOrNull() ?: return
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
                .clip(androidx.compose.foundation.shape.CircleShape)
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
        if (brief.items.size > 1) {
            Spacer(Modifier.width(10.dp))
            CountBadge(tokens, "+${brief.items.size - 1}")
        }
    }
}
