package com.fran.teclas.spacetoday.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fran.teclas.brief.BriefCategory
import com.fran.teclas.brief.TodayKeyboardMode
import com.fran.teclas.spacetoday.model.Breakthrough
import com.fran.teclas.spacetoday.model.SpaceWorkload
import com.fran.teclas.spacetoday.model.Tier
import com.fran.teclas.spacetoday.model.WorkAction
import com.fran.teclas.spacetoday.model.WorkItem
import com.fran.teclas.spacetoday.theme.TodayTheme

@Composable
fun SpaceTodayScreen(
    viewedSpace: String,
    activeSpace: String,
    workloads: Map<String, SpaceWorkload>,
    theme: TodayTheme,
    breakthroughs: List<Breakthrough>,
    hasListenerPermission: Boolean,
    keyboardMode: TodayKeyboardMode,
    onSwitchSpace: (String) -> Unit,
    onAction: (WorkItem, WorkAction) -> Unit,
    onDismiss: (WorkItem) -> Unit,
    onOpenThemeStore: () -> Unit,
    onBreakthroughTap: (String) -> Unit,
    onGrantPermission: () -> Unit,
    onClose: () -> Unit
) {
    val tabs = workloads.values.sortedWith(compareBy<SpaceWorkload> { if (it.spaceId == activeSpace) 0 else 1 }.thenBy { it.spaceName })
    val workload = workloads[viewedSpace] ?: tabs.firstOrNull() ?: SpaceWorkload.ambient(viewedSpace, viewedSpace)
    val bottom = if (keyboardMode == TodayKeyboardMode.WIDGET) 94.dp else 20.dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.bg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "SPACE TODAY",
                color = theme.sub,
                fontSize = 9.sp,
                letterSpacing = 1.8.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f)
            )
            Text("THEMES", color = theme.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onOpenThemeStore() }.padding(8.dp))
            Text("DONE", color = theme.sub, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onClose() }.padding(8.dp))
        }
        SpaceSwitcher(tabs, viewedSpace, theme, onSwitchSpace)
        BreakoutBanner(breakthroughs, theme, onBreakthroughTap)
        when {
            !hasListenerPermission -> PermissionState(theme, onGrantPermission)
            !workload.hasWorkload -> WidgetBoardFallback(workload.spaceId, workload.spaceName, theme)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottom),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { Hero(workload, theme) }
                if (workload.items.isEmpty()) {
                    item { EmptyWorkload(theme, workload.spaceName) }
                } else {
                    items(workload.items, key = { it.signalRef }) { item ->
                        TimelineCard(item, theme, onAction, onDismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(workload: SpaceWorkload, theme: TodayTheme) {
    Box(
        Modifier
            .fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(theme.cornerRadius), ambientColor = Color.Black.copy(alpha = 0.22f))
            .background(theme.card, RoundedCornerShape(theme.cornerRadius))
            .border(1.dp, theme.cardBorder, RoundedCornerShape(theme.cornerRadius))
            .padding(16.dp)
    ) {
        Column {
            Text("AI SUMMARY", color = theme.accent, fontSize = 9.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Black)
            Text(
                workload.hero.ifBlank { "Nothing needs you right now." },
                color = theme.ink,
                fontFamily = theme.fontFamily,
                fontSize = 23.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatPill("NOW", workload.stat.needNow, theme)
                StatPill("TASKS", workload.stat.tasks, theme)
                StatPill("UPDATES", workload.stat.updates, theme)
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: Int, theme: TodayTheme) {
    Column(
        Modifier
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(value.toString(), color = theme.ink, fontSize = 16.sp, fontWeight = FontWeight.Black)
        Text(label, color = theme.sub, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
    }
}

@Composable
private fun TimelineCard(item: WorkItem, theme: TodayTheme, onAction: (WorkItem, WorkAction) -> Unit, onDismiss: (WorkItem) -> Unit) {
    val accent = categoryAccent(item.category)
    Row(
        Modifier
            .fillMaxWidth()
            .background(theme.card, RoundedCornerShape(theme.cornerRadius))
            .border(1.dp, theme.cardBorder, RoundedCornerShape(theme.cornerRadius))
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Canvas(Modifier.size(18.dp).padding(top = 4.dp)) {
            drawCircle(accent, radius = size.minDimension / 2f, center = Offset(size.width / 2f, size.height / 2f))
            drawCircle(Color.White.copy(alpha = 0.45f), radius = size.minDimension / 5f, center = Offset(size.width / 2f, size.height / 2f))
        }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.who, color = theme.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(tierLabel(item.tier), color = theme.accent, fontSize = 8.sp, fontWeight = FontWeight.Black)
            }
            Text(item.summary, color = theme.ink, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            Text("${item.time} · ${item.zone.name.lowercase()} · score ${item.score}", color = theme.sub, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip(item.primaryAction, theme, true) { onAction(item, item.primaryAction) }
                item.extraActions.take(1).forEach { action -> ActionChip(action, theme, false) { onAction(item, action) } }
                Text("DISMISS", color = theme.sub, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onDismiss(item) }.padding(horizontal = 8.dp, vertical = 7.dp))
            }
        }
    }
}

@Composable
private fun ActionChip(action: WorkAction, theme: TodayTheme, primary: Boolean, onClick: () -> Unit) {
    Text(
        text = action.label.uppercase(),
        color = if (primary) Color.Black else theme.accent,
        fontSize = 9.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier
            .background(if (primary) theme.accent else theme.accent.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    )
}

@Composable
private fun PermissionState(theme: TodayTheme, onGrantPermission: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(theme.card, RoundedCornerShape(theme.cornerRadius))
            .border(1.dp, theme.cardBorder, RoundedCornerShape(theme.cornerRadius))
            .clickable { onGrantPermission() }
            .padding(18.dp)
    ) {
        Text("NOTIFICATION ACCESS", color = theme.accent, fontSize = 9.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Black)
        Text("Turn on notification access so Space Today can summarize only actionable items.", color = theme.ink, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun EmptyWorkload(theme: TodayTheme, spaceName: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(theme.card, RoundedCornerShape(theme.cornerRadius))
            .border(1.dp, theme.cardBorder, RoundedCornerShape(theme.cornerRadius))
            .padding(18.dp)
    ) {
        Text(spaceName.uppercase(), color = theme.accent, fontSize = 9.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Black)
        Text("Clean slate. No action items need you here.", color = theme.ink, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    }
}

private fun tierLabel(tier: Tier): String = when (tier) {
    Tier.T1_CRITICAL -> "T1"
    Tier.T2_IMPORTANT -> "T2"
    Tier.T3_AMBIENT -> "T3"
}

private fun categoryAccent(category: BriefCategory): Color = when (category) {
    BriefCategory.MESSAGE -> Color(0xFF5FD0C4)
    BriefCategory.EMAIL -> Color(0xFFFF6B6B)
    BriefCategory.CALL -> Color(0xFF57C98A)
    BriefCategory.CALENDAR -> Color(0xFFF5C451)
    BriefCategory.WEATHER -> Color(0xFF7DB7FF)
    BriefCategory.MUSIC -> Color(0xFF8FD694)
    BriefCategory.OTHER -> Color(0xFFC9A7FF)
}
