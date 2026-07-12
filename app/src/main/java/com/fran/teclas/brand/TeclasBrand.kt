package com.fran.teclas.brand

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Teclas brand palette, mirrored from res/values/colors.xml for Compose surfaces. Brand colors
 * are absolute (theme-independent); [Ink]/[Paper] pick the readable one for the surface.
 */
object TeclasBrand {
    val Ink = Color(0xFF0B0D12)
    val Paper = Color(0xFFF4F1EA)
    /** Primary brand accent. Also the default goKeyColor. */
    val CursorViolet = Color(0xFFC9A7FF)
    // Signal-only accents: live-state motion/status ONLY, never static chrome/text/background.
    val SignalPlay = Color(0xFF1DB954)
    val SignalMessage = Color(0xFF64B5F6)
    val SignalCapture = Color(0xFFF6C453)
    val SignalRoute = Color(0xFFFF8A65)
    // Glass Raised material gradient stops.
    val GlassHi = Color(0xFF20232A)
    val GlassLo = Color(0xFF14161B)

    const val TAGLINE = "type. done."
}

/**
 * The Teclas wordmark: lowercase "teclas" set in a heavy geometric sans (tracking −0.04em),
 * followed by a Cursor Violet caret block. This is the logo — no mascot or symbol.
 *
 * The caret may blink IN-APP ([blink] = true, a slow ~1.6s cycle). The LAUNCHER ICON stays
 * static per the brand requirement; that mark lives in res/drawable/ic_launcher_foreground.xml.
 */
@Composable
fun TeclasWordmark(
    modifier: Modifier = Modifier,
    color: Color = TeclasBrand.Paper,
    fontSize: TextUnit = 28.sp,
    blink: Boolean = true,
) {
    val caretAlpha = if (blink) {
        val transition = rememberInfiniteTransition(label = "teclas-caret")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1600
                    1f at 0
                    1f at 750
                    0f at 950
                    0f at 1500
                },
                repeatMode = RepeatMode.Restart,
            ),
            label = "teclas-caret-alpha",
        )
        a
    } else {
        1f
    }

    val sz = fontSize.value
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "teclas",
            color = color,
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = (-0.04).em,
        )
        Spacer(Modifier.width((sz * 0.14f).dp))
        Box(
            Modifier
                .padding(bottom = (sz * 0.04f).dp)
                .size(width = (sz * 0.16f).dp, height = (sz * 0.6f).dp)
                .alpha(caretAlpha)
                .background(TeclasBrand.CursorViolet),
        )
    }
}
