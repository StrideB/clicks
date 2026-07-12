package com.fran.teclas.brief

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Deliberately identical palette + structure to WeatherWidgetPicker so the two pickers are twins.
private val PanelTop = Color(0xFF20232A)
private val PanelBottom = Color(0xFF14161B)
private val Tray = Color(0xFF0F1116)
private val InkBright = Color(0xFFE8EBEF)
private val InkMuted = Color(0xFF8B8F99)
private val HairLine = Color(0x22FFFFFF)

const val BRIEF_THEME_GLASS_ID = "glass"
const val BRIEF_THEME_DOT_ID = "dotmatrix"

/**
 * Long-press picker for the daily-brief widget — the exact bottom-sheet look and behaviour as the
 * weather style picker: scrim, rounded panel, drag handle, header + ✕, and a 2-column grid of
 * previewed cells. Selecting a theme applies it and closes; scrim tap, ✕ and back all cancel.
 */
@Composable
fun BriefThemePickerSheet(
    accent: Color,
    currentThemeId: String,
    onSelect: (String) -> Unit,
    onCancel: () -> Unit,
) {
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
                .fillMaxHeight(0.74f)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Brush.verticalGradient(listOf(PanelTop, PanelBottom)))
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
                    Text("DAILY BRIEF", color = InkBright, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("Pick a theme for the brief widget", color = InkMuted, fontSize = 11.sp)
                }
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Tray).border(1.dp, HairLine, CircleShape)
                        .clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center
                ) { Text("✕", color = InkBright, fontSize = 13.sp) }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "hdr") { CategoryHeader("Theme", 2, accent) }
                item(key = BRIEF_THEME_GLASS_ID) {
                    Cell("Glass", currentThemeId == BRIEF_THEME_GLASS_ID, accent, { onSelect(BRIEF_THEME_GLASS_ID) }) { GlassPreview() }
                }
                item(key = BRIEF_THEME_DOT_ID) {
                    Cell("Dot matrix", currentThemeId == BRIEF_THEME_DOT_ID, accent, { onSelect(BRIEF_THEME_DOT_ID) }) { DotPreview(accent) }
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
        Text(title.uppercase(), color = InkBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.width(8.dp))
        Text(count.toString(), color = InkMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Cell(name: String, selected: Boolean, accent: Color, onClick: () -> Unit, preview: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.clip(shape).background(Tray).border(1.dp, if (selected) accent else HairLine, shape).clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().height(112.dp).padding(12.dp), contentAlignment = Alignment.Center) { preview() }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
        ) {
            if (selected) { Box(Modifier.size(5.dp).clip(CircleShape).background(accent)); Spacer(Modifier.width(6.dp)) }
            Text(name.uppercase(), color = if (selected) InkBright else InkMuted, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun GlassPreview() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0x33101418)).border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Text("TODAY · MORNING", color = Color(0x82FFFFFF), fontSize = 6.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Text("Rain until 11 — leave by 8:10.", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(5.dp))
        Text("◦  Standup · 9:00", color = Color(0xB3FFFFFF), fontSize = 7.sp)
    }
}

@Composable
private fun DotPreview(accent: Color) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0xF00A0A0A)).border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(4.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(5.dp))
            Text("MORNING", color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text("Rain until 11.", color = Color.White, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(5.dp))
        Text("> Standup 9:00", color = Color(0xB3FFFFFF), fontSize = 7.sp, fontFamily = FontFamily.Monospace)
    }
}
