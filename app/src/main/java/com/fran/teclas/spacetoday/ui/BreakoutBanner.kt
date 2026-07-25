package com.fran.teclas.spacetoday.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fran.teclas.spacetoday.model.Breakthrough
import com.fran.teclas.spacetoday.theme.TodayTheme

@Composable
fun BreakoutBanner(
    breakthroughs: List<Breakthrough>,
    theme: TodayTheme,
    onTap: (String) -> Unit
) {
    if (breakthroughs.isEmpty()) return
    val first = breakthroughs.first()
    val line = if (breakthroughs.size == 1) {
        "Urgent in ${first.fromSpaceName} — ${first.who}: ${first.text}"
    } else {
        "${breakthroughs.size} urgent elsewhere"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.accent.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
            .clickable { onTap(first.fromSpace) }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("!", color = theme.accent, fontSize = 13.sp, fontWeight = FontWeight.Black)
            Text(
                text = line,
                color = theme.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
