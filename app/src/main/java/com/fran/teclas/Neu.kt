package com.fran.teclas

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class NeuMode { DARK, LIGHT }
enum class NeuLevel { RAISED, RAISED_SM, PRESSED, PRESSED_SM }

data class NeuTokens(
    val mode: NeuMode,
    val base: Int,
    val baseHi: Int,
    val baseLo: Int,
    val ink: Int,
    val inkDim: Int,
    val inkFaint: Int
) {
    val baseCompose = ComposeColor(base)
    val hiCompose = ComposeColor(baseHi)
    val loCompose = ComposeColor(baseLo)
    val inkCompose = ComposeColor(ink)
    val inkDimCompose = ComposeColor(inkDim)
    val inkFaintCompose = ComposeColor(inkFaint)
}

object Neu {
    const val GREEN = 0xFF28E06A.toInt()
    const val ACCENT = 0xFFFF4B45.toInt()
    const val AMBER = 0xFFE8B84B.toInt()
    const val BLUE = 0xFF3B9DFF.toInt()
    const val TEAL = 0xFF5FD0C4.toInt()
    const val ORANGE = 0xFFFF8A4C.toInt()
    const val PURPLE = 0xFFA071FF.toInt()
    const val LIGHT_CHIP_TEXT = 0xFF20242B.toInt()
    const val DARK_CHIP_TEXT = 0xFF0C1310.toInt()

    val Dark = NeuTokens(
        mode = NeuMode.DARK,
        base = 0xFF181B21.toInt(),
        baseHi = 0xFF2A2F3A.toInt(),
        baseLo = 0xFF05070A.toInt(),
        ink = 0xFFE8EBEF.toInt(),
        inkDim = 0xFF899099.toInt(),
        inkFaint = 0xFF565D66.toInt()
    )

    val Light = NeuTokens(
        mode = NeuMode.LIGHT,
        base = 0xFFECEEF2.toInt(),
        baseHi = 0xFFFFFFFF.toInt(),
        baseLo = 0xFFD3D7DE.toInt(),
        ink = 0xFF3A3F47.toInt(),
        inkDim = 0xFF828892.toInt(),
        inkFaint = 0xFFA8ADB6.toInt()
    )

    fun drawable(tokens: NeuTokens, radiusPx: Float, level: NeuLevel): Drawable {
        return NeuDrawable(tokens, radiusPx, level)
    }

    fun apply(view: View, tokens: NeuTokens, radiusPx: Float, level: NeuLevel) {
        view.background = drawable(tokens, radiusPx, level)
    }

    private fun alpha(color: Int, alpha: Float): Int {
        return Color.argb((255 * alpha).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    private class NeuDrawable(
        private val tokens: NeuTokens,
        private val radiusPx: Float,
        private val level: NeuLevel
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val spec = shadowSpec(tokens.mode, level)
            val rawInset = spec.blur + kotlin.math.max(kotlin.math.abs(spec.lightDx), kotlin.math.abs(spec.darkDx)) + 1f
            val inset = kotlin.math.min(rawInset, kotlin.math.min(b.width(), b.height()) * 0.28f)
            rect.set(
                b.left + inset,
                b.top + inset,
                b.right - inset,
                b.bottom - inset
            )
            if (rect.width() <= 0f || rect.height() <= 0f) return

            if (level == NeuLevel.PRESSED || level == NeuLevel.PRESSED_SM) {
                paint.maskFilter = null
                paint.style = Paint.Style.FILL
                paint.color = tokens.base
                canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = spec.blur * 0.55f
                paint.color = alpha(tokens.baseHi, if (tokens.mode == NeuMode.LIGHT) 0.72f else 0.62f)
                canvas.drawRoundRect(
                    RectF(rect.left - spec.lightDx, rect.top - spec.lightDy, rect.right - spec.lightDx, rect.bottom - spec.lightDy),
                    radiusPx,
                    radiusPx,
                    paint
                )
                paint.color = alpha(tokens.baseLo, if (tokens.mode == NeuMode.LIGHT) 0.82f else 0.92f)
                canvas.drawRoundRect(
                    RectF(rect.left - spec.darkDx, rect.top - spec.darkDy, rect.right - spec.darkDx, rect.bottom - spec.darkDy),
                    radiusPx,
                    radiusPx,
                    paint
                )
                paint.style = Paint.Style.FILL
            } else {
                paint.style = Paint.Style.FILL
                paint.maskFilter = BlurMaskFilter(spec.blur, BlurMaskFilter.Blur.NORMAL)
                paint.color = alpha(tokens.baseLo, if (tokens.mode == NeuMode.LIGHT) 0.88f else 0.95f)
                canvas.drawRoundRect(
                    RectF(rect.left + spec.darkDx, rect.top + spec.darkDy, rect.right + spec.darkDx, rect.bottom + spec.darkDy),
                    radiusPx,
                    radiusPx,
                    paint
                )
                paint.color = alpha(tokens.baseHi, if (tokens.mode == NeuMode.LIGHT) 0.9f else 0.72f)
                canvas.drawRoundRect(
                    RectF(rect.left + spec.lightDx, rect.top + spec.lightDy, rect.right + spec.lightDx, rect.bottom + spec.lightDy),
                    radiusPx,
                    radiusPx,
                    paint
                )
                paint.maskFilter = null
                paint.color = tokens.base
                canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private data class ShadowSpec(
        val lightDx: Float,
        val lightDy: Float,
        val darkDx: Float,
        val darkDy: Float,
        val blur: Float
    )

    private fun shadowSpec(mode: NeuMode, level: NeuLevel): ShadowSpec {
        return when (mode) {
            NeuMode.DARK -> when (level) {
                NeuLevel.RAISED -> ShadowSpec(-5f, -5f, 6f, 6f, 13f)
                NeuLevel.RAISED_SM -> ShadowSpec(-3f, -3f, 4f, 4f, 8f)
                NeuLevel.PRESSED -> ShadowSpec(-4f, -4f, 5f, 5f, 10f)
                NeuLevel.PRESSED_SM -> ShadowSpec(-2f, -2f, 3f, 3f, 6f)
            }
            NeuMode.LIGHT -> when (level) {
                NeuLevel.RAISED -> ShadowSpec(-4f, -4f, 5f, 5f, 12f)
                NeuLevel.RAISED_SM -> ShadowSpec(-2f, -2f, 3f, 3f, 6f)
                NeuLevel.PRESSED -> ShadowSpec(-3f, -3f, 4f, 4f, 8f)
                NeuLevel.PRESSED_SM -> ShadowSpec(-2f, -2f, 2f, 2f, 4f)
            }
        }
    }
}

fun Modifier.neu(tokens: NeuTokens, radius: Dp, level: NeuLevel): Modifier = this.drawWithContent {
    val r = radius.toPx()
    val light = tokens.mode == NeuMode.LIGHT
    // offset+blur per level (dp values from the spec), scaled to px
    val (offNeg, offPos, blurDp) = when (level) {
        NeuLevel.RAISED     -> Triple(if (light) 4f else 5f, if (light) 5f else 6f, if (light) 12f else 13f)
        NeuLevel.RAISED_SM  -> Triple(if (light) 2f else 3f, if (light) 3f else 4f, if (light) 6f else 8f)
        NeuLevel.PRESSED    -> Triple(if (light) 3f else 4f, if (light) 4f else 5f, if (light) 8f else 10f)
        NeuLevel.PRESSED_SM -> Triple(2f, if (light) 2f else 3f, if (light) 4f else 6f)
    }
    val dLight = offNeg.dp.toPx()
    val dDark  = offPos.dp.toPx()
    val blur   = blurDp.dp.toPx()
    val hi = tokens.baseHi
    val lo = tokens.baseLo
    val hiA = if (light) 0.9f else 0.72f
    val loA = if (light) 0.85f else 0.95f
    val raised = level == NeuLevel.RAISED || level == NeuLevel.RAISED_SM

    if (raised) {
        drawIntoCanvas { c ->
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            // dark shadow bottom-right
            p.color = androidx.compose.ui.graphics.Color(lo).copy(alpha = loA).toArgb()
            c.nativeCanvas.drawRoundRect(dDark, dDark, size.width + dDark, size.height + dDark, r, r, p)
            // light shadow top-left
            p.color = androidx.compose.ui.graphics.Color(hi).copy(alpha = hiA).toArgb()
            c.nativeCanvas.drawRoundRect(-dLight, -dLight, size.width - dLight, size.height - dLight, r, r, p)
        }
        drawRoundRect(color = tokens.baseCompose, cornerRadius = CornerRadius(r, r))
    } else {
        // base first, then inner shadows CLIPPED to the shape → reads as carved
        drawRoundRect(color = tokens.baseCompose, cornerRadius = CornerRadius(r, r))
        val clip = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r)))
        }
        clipPath(clip) {
            drawIntoCanvas { c ->
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = blur * 1.6f
                    maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
                // dark inner shadow: pushed in from top-left → strong on top/left inner edges
                p.color = androidx.compose.ui.graphics.Color(lo).copy(alpha = if (light) 0.9f else 0.92f).toArgb()
                c.nativeCanvas.drawRoundRect(dDark, dDark, size.width + dDark, size.height + dDark, r, r, p)
                // light inner shadow: pushed in from bottom-right → highlight on bottom/right inner edges
                p.color = androidx.compose.ui.graphics.Color(hi).copy(alpha = if (light) 0.85f else 0.62f).toArgb()
                c.nativeCanvas.drawRoundRect(-dLight, -dLight, size.width - dLight, size.height - dLight, r, r, p)
            }
        }
    }
    drawContent()
}
