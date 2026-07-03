package com.fran.clicks

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.drawscope.Stroke
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

    val Dark = NeuTokens(
        mode = NeuMode.DARK,
        base = 0xFF181B21.toInt(),
        baseHi = 0xFF20242C.toInt(),
        baseLo = 0xFF0E1014.toInt(),
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
            val inset = spec.blur + kotlin.math.max(kotlin.math.abs(spec.lightDx), kotlin.math.abs(spec.darkDx)) + 1f
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
                paint.color = alpha(tokens.baseHi, if (tokens.mode == NeuMode.LIGHT) 0.72f else 0.46f)
                canvas.drawRoundRect(
                    RectF(rect.left - spec.lightDx, rect.top - spec.lightDy, rect.right - spec.lightDx, rect.bottom - spec.lightDy),
                    radiusPx,
                    radiusPx,
                    paint
                )
                paint.color = alpha(tokens.baseLo, if (tokens.mode == NeuMode.LIGHT) 0.82f else 0.72f)
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
                paint.color = alpha(tokens.baseLo, if (tokens.mode == NeuMode.LIGHT) 0.88f else 0.84f)
                canvas.drawRoundRect(
                    RectF(rect.left + spec.darkDx, rect.top + spec.darkDy, rect.right + spec.darkDx, rect.bottom + spec.darkDy),
                    radiusPx,
                    radiusPx,
                    paint
                )
                paint.color = alpha(tokens.baseHi, if (tokens.mode == NeuMode.LIGHT) 0.9f else 0.58f)
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
                NeuLevel.RAISED -> ShadowSpec(-5f, -5f, 6f, 6f, 11f)
                NeuLevel.RAISED_SM -> ShadowSpec(-3f, -3f, 4f, 4f, 7f)
                NeuLevel.PRESSED -> ShadowSpec(-4f, -4f, 5f, 5f, 9f)
                NeuLevel.PRESSED_SM -> ShadowSpec(-2f, -2f, 3f, 3f, 5f)
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
    val spec = when (tokens.mode) {
        NeuMode.DARK -> when (level) {
            NeuLevel.RAISED -> listOf(-5.dp, -5.dp, 6.dp, 6.dp)
            NeuLevel.RAISED_SM -> listOf(-3.dp, -3.dp, 4.dp, 4.dp)
            NeuLevel.PRESSED -> listOf(-4.dp, -4.dp, 5.dp, 5.dp)
            NeuLevel.PRESSED_SM -> listOf(-2.dp, -2.dp, 3.dp, 3.dp)
        }
        NeuMode.LIGHT -> when (level) {
            NeuLevel.RAISED -> listOf(-4.dp, -4.dp, 5.dp, 5.dp)
            NeuLevel.RAISED_SM -> listOf(-2.dp, -2.dp, 3.dp, 3.dp)
            NeuLevel.PRESSED -> listOf(-3.dp, -3.dp, 4.dp, 4.dp)
            NeuLevel.PRESSED_SM -> listOf(-2.dp, -2.dp, 2.dp, 2.dp)
        }
    }
    val raised = level == NeuLevel.RAISED || level == NeuLevel.RAISED_SM
    if (raised) {
        drawRoundRect(
            color = tokens.loCompose.copy(alpha = if (tokens.mode == NeuMode.LIGHT) 0.84f else 0.78f),
            topLeft = Offset(spec[2].toPx(), spec[3].toPx()),
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(r, r)
        )
        drawRoundRect(
            color = tokens.hiCompose.copy(alpha = if (tokens.mode == NeuMode.LIGHT) 0.9f else 0.52f),
            topLeft = Offset(spec[0].toPx(), spec[1].toPx()),
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(r, r)
        )
        drawRoundRect(color = tokens.baseCompose, cornerRadius = CornerRadius(r, r))
    } else {
        drawRoundRect(color = tokens.baseCompose, cornerRadius = CornerRadius(r, r))
        drawRoundRect(
            color = tokens.hiCompose.copy(alpha = if (tokens.mode == NeuMode.LIGHT) 0.74f else 0.38f),
            topLeft = Offset(spec[0].toPx(), spec[1].toPx()),
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = kotlin.math.abs(spec[0].toPx()) + 2.dp.toPx())
        )
        drawRoundRect(
            color = tokens.loCompose.copy(alpha = if (tokens.mode == NeuMode.LIGHT) 0.8f else 0.68f),
            topLeft = Offset(spec[2].toPx(), spec[3].toPx()),
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = kotlin.math.abs(spec[2].toPx()) + 2.dp.toPx())
        )
    }
    drawContent()
}
