package com.fran.teclas.spacetoday.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fran.teclas.spacetoday.model.SpaceWorkload
import com.fran.teclas.spacetoday.theme.TodayTheme

@Composable
fun SpaceSwitcher(
    tabs: List<SpaceWorkload>,
    viewedSpace: String,
    theme: TodayTheme,
    onSwitch: (String) -> Unit
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { tab ->
            val selected = tab.spaceId == viewedSpace
            Text(
                text = tab.spaceName.uppercase() + if (!tab.hasWorkload) " · BOARD" else "",
                color = if (selected) theme.ink else theme.sub,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                modifier = Modifier
                    .background(
                        if (selected) theme.accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.055f),
                        RoundedCornerShape(999.dp)
                    )
                    .clickable { onSwitch(tab.spaceId) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}
