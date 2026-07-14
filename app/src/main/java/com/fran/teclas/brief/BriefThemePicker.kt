package com.fran.teclas.brief

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class BriefTheme(
    val id: Int,
    val name: String,
    val isBoxed: Boolean,
    val background: String,
    val textColor: String,
    val mutedColor: String,
    val accentColor: String,
    val titleColor: String,
    val font: Font,
    val cardFill: String?,
    val cardBorder: String?,
    val cornerRadius: Int,
    val titleTreatment: TitleTreatment,
    val effect: String? = null,
    val wallpaperId: String? = null
)

enum class Font { MONO, SERIF, SANS }
enum class TitleTreatment { PLAIN, UNDERLINE, LEFT_RULE, INVERTED_CHIP, BORDERED }

typealias BriefData = Brief

const val BRIEF_THEME_GLASS_ID = "1"
const val BRIEF_THEME_DOT_ID = "12"

object BriefThemes {
    const val DEFAULT_ID = 1

    val all: List<BriefTheme> = listOf(
        BriefTheme(1, "Sunset Glass", true, "gradient(160deg, #f7b26b@0%, #e77a5a@45%, #3a4a63@100%)", "#ffffff", "#b3ffffff", "#e8602c", "#ffffff", Font.SANS, "rgba(28,26,32,.55) blur", "1px solid #ffffff24", 14, TitleTreatment.PLAIN, "blur", "sunset_glass"),
        BriefTheme(2, "Paper Mono", false, "#f6f4ee", "#1a1a1a", "#b31a1a1a", "#1a1a1a", "#1a1a1a", Font.MONO, null, null, 0, TitleTreatment.UNDERLINE, "underline:#cfcabd"),
        BriefTheme(3, "Terminal Green", true, "#0b0f0b", "#39ff86", "#b339ff86", "#39ff86", "#39ff86", Font.MONO, "#0d140d", "1px solid #1f5a37", 6, TitleTreatment.PLAIN, "glow:green"),
        BriefTheme(4, "Blueprint", false, "#12365e grid", "#dbe8f6", "#b3dbe8f6", "#7fb2e8", "#ffffff", Font.MONO, null, null, 0, TitleTreatment.LEFT_RULE, "grid:22"),
        BriefTheme(5, "Cream Editorial", true, "#efe7d8", "#2b2622", "#b32b2622", "#a8632c", "#2b2622", Font.SERIF, "#fbf7ee", "1px solid #d8ccb4", 2, TitleTreatment.PLAIN, "soft-shadow"),
        BriefTheme(6, "Neon Pop", true, "#120a2e", "#ffffff", "#b3ffffff", "#ffd84e", "#7cf3ff", Font.SANS, "#1e1147", "2px solid #ff4ecd", 16, TitleTreatment.PLAIN, "glow:magenta"),
        BriefTheme(7, "Fog", false, "gradient(180deg, #cdd6dd, #aab6bf)", "#2c353c", "#b32c353c", "#5b6b76", "#2c353c", Font.SANS, null, null, 0, TitleTreatment.PLAIN, wallpaperId = "fog"),
        BriefTheme(8, "Amber CRT", true, "#1a1206", "#ffb648", "#b3ffb648", "#ffb648", "#ffb648", Font.MONO, "#241804", "1px dashed #7a5418", 4, TitleTreatment.PLAIN, "glow:amber"),
        BriefTheme(9, "Rose Quartz", true, "gradient(160deg, #ffd9e4, #ffc2d1@60%, #c9b8ff)", "#5a2a44", "#b35a2a44", "#d65a8a", "#5a2a44", Font.SANS, "rgba(255,255,255,.55) blur", "1px solid #ffffffb3", 20, TitleTreatment.PLAIN, "blur", "rose_quartz"),
        BriefTheme(10, "Pure Ink", false, "#101010", "#f2f2f2", "#b3f2f2f2", "#f2f2f2", "#f2f2f2", Font.SANS, null, null, 0, TitleTreatment.PLAIN, "heavy"),
        BriefTheme(11, "Forest Depth", true, "gradient(170deg, #1f3d2f, #0f261c)", "#d7efdc", "#b3d7efdc", "#7fe0a0", "#d7efdc", Font.SANS, "rgba(255,255,255,.08) blur", "1px solid #c8ffd22e", 12, TitleTreatment.PLAIN, "blur", "forest_depth"),
        BriefTheme(12, "Dot Matrix", false, "#e8e4d8 dots", "#2a2a2a", "#b32a2a2a", "#2a2a2a", "#2a2a2a", Font.MONO, null, null, 0, TitleTreatment.INVERTED_CHIP, "dots:8"),
        BriefTheme(13, "Coral Block", true, "#ff6b4a", "#ffffff", "#b3ffffff", "#ffd84e", "#ff6b4a", Font.SANS, "#141210", null, 10, TitleTreatment.PLAIN),
        BriefTheme(14, "Slate Minimal", false, "#e4e6e9", "#25292e", "#b325292e", "#6a7178", "#25292e", Font.SANS, null, null, 0, TitleTreatment.PLAIN, "dividers:#c9cdd2"),
        BriefTheme(15, "Midnight Glass", true, "gradient(160deg, #2b2d5e, #151633)", "#e6e8ff", "#b3e6e8ff", "#8f9bff", "#e6e8ff", Font.SANS, "rgba(255,255,255,.09) blur", "1px solid #ffffff29", 18, TitleTreatment.PLAIN, "blur", "midnight_glass"),
        BriefTheme(16, "Sunflower", true, "#ffd23f", "#3a2c00", "#b33a2c00", "#e8602c", "#3a2c00", Font.SANS, "#fff8e0", "2px solid #3a2c00", 12, TitleTreatment.PLAIN),
        BriefTheme(17, "Vapor", false, "gradient(160deg, #8ee3ff, #c79bff@55%, #ff9ecb)", "#241b3a", "#b3241b3a", "#ffffff", "#ffffff", Font.SANS, null, null, 0, TitleTreatment.PLAIN, "heavy-shadow", "vapor"),
        BriefTheme(18, "Newsprint", true, "#e6e2d6", "#1a1a1a", "#b31a1a1a", "#1a1a1a", "#1a1a1a", Font.SERIF, "#f4f1e8", "1px solid #1a1a1a", 0, TitleTreatment.PLAIN, "header-underline"),
        BriefTheme(19, "Ocean Line", false, "gradient(180deg, #0a5c7a, #063245)", "#d6f0f8", "#b3d6f0f8", "#4fd0e8", "#d6f0f8", Font.SANS, null, null, 0, TitleTreatment.UNDERLINE, "underline:#4fd0e8", "ocean_line"),
        BriefTheme(20, "Punchcard", true, "#1c1c1c", "#f5e6c8", "#b3f5e6c8", "#ff8a5c", "#f5e6c8", Font.MONO, "#242424", "2px solid #f5e6c8", 0, TitleTreatment.PLAIN, "offset-shadow")
    )

    fun themeForPref(raw: String?): BriefTheme {
        val normalized = when (raw?.trim()?.lowercase()) {
            null, "", "glass" -> DEFAULT_ID
            "dot", "dotmatrix" -> 12
            else -> raw.toIntOrNull()
        }
        return all.firstOrNull { it.id == normalized } ?: all.first { it.id == DEFAULT_ID }
    }
}

fun BriefTheme.textColorInt(): Int = parseCssColor(textColor, AndroidColor.WHITE)
fun BriefTheme.mutedColorInt(): Int = parseCssColor(mutedColor, applyAlpha(parseCssColor(textColor, AndroidColor.WHITE), 0.70f))
fun BriefTheme.accentColorInt(): Int = parseCssColor(accentColor, AndroidColor.WHITE)
fun BriefTheme.titleColorInt(): Int = parseCssColor(titleColor, parseCssColor(textColor, AndroidColor.WHITE))
fun BriefTheme.cardFillColorInt(): Int? = cardFill?.let { parseNullableCssColor(it) }
fun BriefTheme.glassTintColorInt(): Int {
    val base = cardFillColorInt()
        ?: gradientStops(background).firstOrNull()?.color
        ?: parseCssColor(background.substringBefore(' '), AndroidColor.WHITE)
    val alpha = if (isDarkColor(base)) 0.24f else 0.30f
    return applyAlpha(base, alpha)
}
fun BriefTheme.glassRimColorInt(): Int =
    applyAlpha(parseBorder(cardBorder)?.color ?: accentColorInt(), if (isDarkColor(glassTintColorInt())) 0.34f else 0.42f)

fun BriefTheme.typeface(weight: Int = Typeface.NORMAL): Typeface = when (font) {
    Font.MONO -> Typeface.MONOSPACE
    Font.SERIF -> Typeface.create(Typeface.SERIF, weight)
    Font.SANS -> Typeface.create("sans-serif", weight)
}

fun BriefTheme.backgroundDrawable(context: Context, radiusDp: Int = cornerRadius): GradientDrawable {
    return gradientDrawableFromSpec(context, background, radiusDp)
}

fun BriefTheme.cardDrawable(context: Context, fallbackRadiusDp: Int = cornerRadius): GradientDrawable {
    val drawable = GradientDrawable().apply {
        cornerRadius = dp(context, fallbackRadiusDp).toFloat()
        setColor(cardFillColorInt() ?: AndroidColor.TRANSPARENT)
    }
    val border = parseBorder(cardBorder)
    if (border != null) {
        if (border.dashed) {
            drawable.setStroke(dp(context, border.widthDp), border.color, dp(context, 4).toFloat(), dp(context, 3).toFloat())
        } else {
            drawable.setStroke(dp(context, border.widthDp), border.color)
        }
    }
    return drawable
}

@Composable
fun BriefThemePickerSheet(
    accent: Color,
    currentThemeId: String,
    onSelect: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val selected = BriefThemes.themeForPref(currentThemeId).id
    BackHandler(onBack = onCancel)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel)
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF20232A), Color(0xFF14161B))))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
        ) {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .align(Alignment.CenterHorizontally)
                    .width(44.dp)
                    .height(4.dp)
                    .background(accent, RoundedCornerShape(2.dp))
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("DAILY BRIEF", color = Color(0xFFE8EBEF), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("Pick a live theme for the brief widget", color = Color(0xFF8B8F99), fontSize = 11.sp)
                }
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF0F1116)).border(1.dp, Color(0x22FFFFFF), CircleShape)
                        .clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center
                ) { Text("x", color = Color(0xFFE8EBEF), fontSize = 13.sp) }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "hdr") { CategoryHeader("Themes", BriefThemes.all.size, accent) }
                items(BriefThemes.all, key = { it.id }) { theme ->
                    ThemeCell(
                        theme = theme,
                        selected = theme.id == selected,
                        accent = accent,
                        onClick = { onSelect(theme.id.toString()) }
                    )
                }
            }
        }
    }
}

@Composable
fun DailyBriefCard(theme: BriefTheme, brief: BriefData, modifier: Modifier = Modifier) {
    val visibleItems = remember(brief.items) { brief.items.filterNot { it.category == BriefCategory.WEATHER }.take(3) }
    val title = visibleItems.firstOrNull()?.title ?: "You're all caught up."
    val shape = RoundedCornerShape(theme.cornerRadius.dp)
    val base = modifier
        .fillMaxWidth()
        .themeBackground(theme)
        .padding(if (theme.isBoxed) 10.dp else 12.dp)
    Box(base) {
        val contentModifier = if (theme.isBoxed) {
            Modifier
                .fillMaxWidth()
                .themeCardEffect(theme)
                .clip(shape)
                .background(theme.cardBrush())
                .then(theme.cardBorderModifier(shape))
                .padding(12.dp)
        } else {
            Modifier.fillMaxWidth().padding(2.dp)
        }
        Column(contentModifier) {
            HeaderRow(theme)
            Spacer(Modifier.height(5.dp))
            Title(theme, title)
            Spacer(Modifier.height(7.dp))
            if (visibleItems.isEmpty()) {
                Text("No actions waiting", color = theme.composeMuted(), fontSize = 9.5.sp, fontFamily = theme.fontFamily())
            } else {
                visibleItems.forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("›", color = theme.composeAccent(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, color = theme.composeText(), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = theme.fontFamily(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (item.subtitle.isNotBlank()) {
                                Text(item.subtitle, color = theme.composeMuted(), fontSize = 9.5.sp, fontFamily = theme.fontFamily(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (theme.id == 14 && index != visibleItems.lastIndex) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFC9CDD2)))
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(title: String, count: Int, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(12.dp).clip(RoundedCornerShape(2.dp)).background(accent))
        Spacer(Modifier.width(8.dp))
        Text(title.uppercase(), color = Color(0xFFE8EBEF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.width(8.dp))
        Text(count.toString(), color = Color(0xFF8B8F99), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ThemeCell(theme: BriefTheme, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .clip(shape)
            .background(Color(0xFF0F1116))
            .border(1.dp, if (selected) accent else Color(0x22FFFFFF), shape)
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().height(132.dp).padding(9.dp), contentAlignment = Alignment.Center) {
            DailyBriefCard(theme, sampleBrief())
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (selected) { Box(Modifier.size(5.dp).clip(CircleShape).background(accent)); Spacer(Modifier.width(6.dp)) }
            Text(theme.name.uppercase(), color = if (selected) Color(0xFFE8EBEF) else Color(0xFF8B8F99), fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HeaderRow(theme: BriefTheme) {
    val headerColor = theme.composeMuted().copy(alpha = 0.8f)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("TODAY", color = headerColor, fontSize = 8.5.sp, letterSpacing = 1.5.sp, fontFamily = theme.fontFamily(), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("⇄  x", color = theme.composeAccent(), fontSize = 8.5.sp, letterSpacing = 1.2.sp, fontFamily = theme.fontFamily(), fontWeight = FontWeight.Bold)
    }
    if (theme.id == 18 || theme.id == 20) {
        Spacer(Modifier.height(5.dp))
        val dashed = theme.id == 20
        Canvas(Modifier.fillMaxWidth().height(1.dp)) {
            drawLine(
                color = theme.composeText(),
                start = Offset.Zero,
                end = Offset(size.width, 0f),
                strokeWidth = 1.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(7f, 5f)) else null
            )
        }
    }
}

@Composable
private fun Title(theme: BriefTheme, text: String) {
    when (theme.titleTreatment) {
        TitleTreatment.LEFT_RULE -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(24.dp).background(theme.composeAccent()))
            Spacer(Modifier.width(8.dp))
            TitleText(theme, text)
        }
        TitleTreatment.INVERTED_CHIP -> Box(Modifier.clip(RoundedCornerShape(6.dp)).background(theme.composeText()).padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(text, color = theme.backgroundBaseColor(), fontSize = 13.sp, fontFamily = theme.fontFamily(), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TitleTreatment.UNDERLINE -> Column {
            TitleText(theme, text)
            Spacer(Modifier.height(3.dp))
            Box(Modifier.width(58.dp).height(if (theme.id == 19) 2.dp else 1.dp).background(theme.composeAccent()))
        }
        TitleTreatment.BORDERED -> Box(Modifier.border(1.dp, theme.composeAccent(), RoundedCornerShape(4.dp)).padding(6.dp)) { TitleText(theme, text) }
        TitleTreatment.PLAIN -> TitleText(theme, text)
    }
}

@Composable
private fun TitleText(theme: BriefTheme, text: String) {
    Text(
        text,
        color = theme.composeTitle(),
        fontSize = 13.sp,
        fontFamily = theme.fontFamily(),
        fontWeight = if (theme.effect?.contains("heavy") == true) FontWeight.Black else FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

private fun Modifier.themeBackground(theme: BriefTheme): Modifier = drawBehind {
    val brush = theme.backgroundBrush()
    drawRoundRect(brush = brush, cornerRadius = androidx.compose.ui.geometry.CornerRadius(theme.cornerRadius.dp.toPx(), theme.cornerRadius.dp.toPx()))
    when {
        theme.effect?.startsWith("grid") == true || theme.id == 4 -> {
            val step = 22.dp.toPx()
            var x = 0f
            while (x < size.width) { drawLine(Color.White.copy(alpha = 0.06f), Offset(x, 0f), Offset(x, size.height), 1f); x += step }
            var y = 0f
            while (y < size.height) { drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y), 1f); y += step }
        }
        theme.effect?.startsWith("dots") == true || theme.id == 12 -> {
            val step = 8.dp.toPx()
            var y = step / 2f
            while (y < size.height) {
                var x = step / 2f
                while (x < size.width) { drawCircle(Color(0xFFC4BDA9), radius = 1.15.dp.toPx(), center = Offset(x, y)); x += step }
                y += step
            }
        }
    }
}

private fun Modifier.themeCardEffect(theme: BriefTheme): Modifier = when {
    theme.effect?.contains("offset-shadow") == true -> shadow(8.dp, RoundedCornerShape(theme.cornerRadius.dp), ambientColor = Color(0x40F5E6C8), spotColor = Color(0x40F5E6C8))
    theme.effect?.contains("soft-shadow") == true -> shadow(8.dp, RoundedCornerShape(theme.cornerRadius.dp), ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.16f))
    theme.effect?.contains("glow") == true -> shadow(10.dp, RoundedCornerShape(theme.cornerRadius.dp), ambientColor = theme.composeAccent().copy(alpha = 0.32f), spotColor = theme.composeAccent().copy(alpha = 0.25f))
    else -> this
}

private fun BriefTheme.cardBrush(): Brush {
    val fill = cardFillColorInt() ?: AndroidColor.TRANSPARENT
    return Brush.verticalGradient(listOf(Color(fill), Color(fill)))
}

private fun BriefTheme.cardBorderModifier(shape: RoundedCornerShape): Modifier {
    val border = parseBorder(cardBorder) ?: return Modifier
    return Modifier.border(border.widthDp.dp, Color(border.color), shape)
}

private fun BriefTheme.backgroundBrush(): Brush {
    val stops = gradientStops(background)
    if (stops.isNotEmpty()) {
        val angle = stops.first().angleRad.toDouble()
        return Brush.linearGradient(
            stops.map { Color(it.color) },
            start = Offset.Zero,
            end = Offset(900f * cos(angle).toFloat(), 900f * sin(angle).toFloat())
        )
    }
    val c = parseCssColor(background.substringBefore(' '), AndroidColor.TRANSPARENT)
    return Brush.verticalGradient(listOf(Color(c), Color(c)))
}

private fun BriefTheme.composeText(): Color = Color(textColorInt())
private fun BriefTheme.composeMuted(): Color = Color(mutedColorInt())
private fun BriefTheme.composeAccent(): Color = Color(accentColorInt())
private fun BriefTheme.composeTitle(): Color = Color(titleColorInt())
private fun BriefTheme.fontFamily(): FontFamily = when (font) {
    Font.MONO -> FontFamily.Monospace
    Font.SERIF -> FontFamily.Serif
    Font.SANS -> FontFamily.SansSerif
}
private fun BriefTheme.backgroundBaseColor(): Color = Color(parseCssColor(background.substringBefore(' '), AndroidColor.TRANSPARENT))

private data class BorderSpec(val widthDp: Int, val color: Int, val dashed: Boolean)
private data class StopSpec(val angleRad: Float, val color: Int)

private fun parseBorder(spec: String?): BorderSpec? {
    if (spec.isNullOrBlank() || spec.equals("none", true)) return null
    val width = Regex("(\\d+)px").find(spec)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
    val color = Regex("#[0-9a-fA-F]{6,8}").find(spec)?.value?.let { parseCssColor(it, AndroidColor.TRANSPARENT) } ?: return null
    val dashed = spec.contains("dashed", ignoreCase = true)
    return BorderSpec(width, color, dashed)
}

private fun gradientStops(spec: String): List<StopSpec> {
    if (!spec.startsWith("gradient", ignoreCase = true)) return emptyList()
    val inside = spec.substringAfter('(').substringBeforeLast(')')
    val angle = Regex("(\\d+)deg").find(inside)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 180f
    val angleRad = ((angle - 90f) / 180f * PI).toFloat()
    return Regex("#[0-9a-fA-F]{6,8}").findAll(inside).map { StopSpec(angleRad, parseCssColor(it.value, AndroidColor.TRANSPARENT)) }.toList()
}

private fun gradientDrawableFromSpec(context: Context, spec: String, radiusDp: Int): GradientDrawable {
    val parsed = gradientStops(spec).map { it.color }.ifEmpty { listOf(parseCssColor(spec.substringBefore(' '), AndroidColor.TRANSPARENT)) }
    // GradientDrawable's (orientation, colors) constructor builds a LinearGradient, which throws
    // "needs >= 2 number of colors" the instant it draws if given fewer than 2. A solid-color spec
    // (every non-"gradient(...)" theme, e.g. "#f6f4ee", "#12365e grid") parses to a single color —
    // duplicate it so it renders as a flat fill instead of crashing the whole launcher on theme select.
    val colors = if (parsed.size >= 2) parsed else List(2) { parsed.firstOrNull() ?: AndroidColor.TRANSPARENT }
    return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors.toIntArray()).apply {
        cornerRadius = dp(context, radiusDp).toFloat()
    }
}

private fun parseNullableCssColor(raw: String): Int? {
    val colorToken = when {
        raw.trim().startsWith("rgba", ignoreCase = true) -> raw.trim().substringBefore(" blur")
        else -> Regex("#[0-9a-fA-F]{6,8}").find(raw)?.value
    } ?: return null
    return parseCssColor(colorToken, AndroidColor.TRANSPARENT)
}

private fun parseCssColor(raw: String, fallback: Int): Int {
    val value = raw.trim()
    if (value.startsWith("rgba", ignoreCase = true)) {
        val nums = value.substringAfter('(').substringBefore(')').split(',').map { it.trim() }
        if (nums.size >= 4) {
            val r = nums[0].toIntOrNull() ?: return fallback
            val g = nums[1].toIntOrNull() ?: return fallback
            val b = nums[2].toIntOrNull() ?: return fallback
            val a = (nums[3].toFloatOrNull() ?: 1f).coerceIn(0f, 1f)
            return AndroidColor.argb((a * 255).toInt(), r, g, b)
        }
    }
    if (!value.startsWith("#")) return fallback
    return runCatching {
        when (value.length) {
            7 -> AndroidColor.parseColor(value)
            9 -> {
                val r = value.substring(1, 3).toInt(16)
                val g = value.substring(3, 5).toInt(16)
                val b = value.substring(5, 7).toInt(16)
                val a = value.substring(7, 9).toInt(16)
                AndroidColor.argb(a, r, g, b)
            }
            else -> fallback
        }
    }.getOrDefault(fallback)
}

private fun applyAlpha(color: Int, alpha: Float): Int = AndroidColor.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), AndroidColor.red(color), AndroidColor.green(color), AndroidColor.blue(color))
private fun isDarkColor(color: Int): Boolean =
    (0.299f * AndroidColor.red(color) + 0.587f * AndroidColor.green(color) + 0.114f * AndroidColor.blue(color)) < 130f
private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

private fun sampleBrief(): Brief = Brief(
    items = listOf(
        BriefItem("1", "Reply to Sarah about Q3 deck", "Gmail · before 2pm", BriefCategory.EMAIL, "Open"),
        BriefItem("2", "Review dinner plan with Mara", "Messages · tonight at 9", BriefCategory.MESSAGE, "Reply"),
        BriefItem("3", "Join product sync", "Calendar · 4:30", BriefCategory.CALENDAR, "Open")
    ),
    generatedAt = System.currentTimeMillis(),
    source = Brief.Source.RULES
)
