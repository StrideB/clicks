package com.fran.teclas.weather

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
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PanelTop = Color(0xFF20232A)
private val PanelBottom = Color(0xFF14161B)
private val Tray = Color(0xFF0F1116)
private val InkBright = Color(0xFFE8EBEF)
private val InkMuted = Color(0xFF8B8F99)
private val HairLine = Color(0x22FFFFFF)

/**
 * Long-press picker for the homescreen weather widget. Lists the classic native
 * header plus every registered [WeatherStyle], each previewed with the launcher's
 * live weather data. Selecting a style hands off to placement mode in MainActivity;
 * scrim tap, ✕ and back all cancel without touching the current widget.
 */
@Composable
fun WeatherStylePickerSheet(
    data: WeatherData,
    accent: Color,
    currentStyleId: String,
    onSelect: (String) -> Unit,
    onCancel: () -> Unit
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
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("WEATHER WIDGET", color = InkBright, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("Pick a style, then tap where it lives", color = InkMuted, fontSize = 11.sp)
                }
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Tray)
                        .border(1.dp, HairLine, CircleShape)
                        .clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = InkBright, fontSize = 13.sp)
                }
            }
            // Sections in WEATHER_CATEGORY_ORDER; any style whose family isn't listed there
            // still shows, under a trailing section — so a new family is never silently dropped.
            val grouped = WEATHER_STYLES.groupBy { it.category }
            val orderedCategories = WEATHER_CATEGORY_ORDER.filter { grouped.containsKey(it) } +
                grouped.keys.filter { it !in WEATHER_CATEGORY_ORDER }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "hdr_classic") {
                    CategoryHeader("Classic", 1, accent)
                }
                item(key = WEATHER_STYLE_CLASSIC_ID) {
                    PickerCell(
                        name = "Classic",
                        selected = currentStyleId == WEATHER_STYLE_CLASSIC_ID,
                        accent = accent,
                        onClick = { onSelect(WEATHER_STYLE_CLASSIC_ID) }
                    ) { ClassicHeaderPreview(data) }
                }
                orderedCategories.forEach { category ->
                    val styles = grouped[category].orEmpty()
                    item(span = { GridItemSpan(maxLineSpan) }, key = "hdr_$category") {
                        CategoryHeader(category, styles.size, accent)
                    }
                    items(styles, key = { it.id }) { style ->
                        PickerCell(
                            name = style.name,
                            selected = currentStyleId == style.id,
                            accent = accent,
                            onClick = { onSelect(style.id) }
                        ) { style.render(data, accent, Modifier) }
                    }
                }
            }
        }
    }
}

// Full-width section divider between families. Accent spine + label + count, matching the
// launcher's glass language.
@Composable
private fun CategoryHeader(title: String, count: Int, accent: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(title.uppercase(), color = InkBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.width(8.dp))
        Text(count.toString(), color = InkMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PickerCell(
    name: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    preview: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .clip(shape)
            .background(Tray)
            .border(1.dp, if (selected) accent else HairLine, shape)
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(112.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.graphicsLayer(scaleX = 0.5f, scaleY = 0.5f)) { preview() }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (selected) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(6.dp))
            }
            Text(name.uppercase(), color = if (selected) InkBright else InkMuted, fontSize = 10.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// Compact stand-in for the native header so "Classic" is choosable from the gallery.
@Composable
private fun ClassicHeaderPreview(d: WeatherData) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("${d.temp}°", color = InkBright, fontSize = 34.sp)
            Text(d.conditionLabel.uppercase(), color = InkMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        }
        Spacer(Modifier.width(26.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("Feels ${d.feelsLike}°", color = InkBright, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("${d.humidity}% RH · ${d.windMph} mph", color = InkMuted, fontSize = 10.sp)
        }
    }
}
