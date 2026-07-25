package com.fran.teclas.spacetoday.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fran.teclas.spacetoday.theme.TodayTheme

@Composable
fun WidgetBoardFallback(spaceId: String, spaceName: String, theme: TodayTheme) {
    Column(
        Modifier
            .fillMaxSize()
            .background(theme.card, RoundedCornerShape(theme.cornerRadius))
            .padding(18.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(spaceName.uppercase(), color = theme.accent, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Text(
            "This Space uses the existing widget board.",
            color = theme.ink,
            fontSize = 22.sp,
            lineHeight = 25.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(top = 10.dp)
        )
        Text(
            "No actionable workload is available for $spaceId yet.",
            color = theme.sub,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
