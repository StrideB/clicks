package com.fran.clicks

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.min

internal object BrushedDrawables {
    fun panel(dark: Boolean, density: Float): Drawable = BrushedPanelDrawable(palette(dark), density)

    fun key(
        label: String,
        pressed: Boolean,
        dark: Boolean,
        density: Float,
        docked: Boolean = true,
        goColor: Int? = null
    ): Drawable = BrushedKeyDrawable(label, pressed, palette(dark), density, docked, goColor)

    fun ink(label: String, dark: Boolean): Int {
        val p = palette(dark)
        return when (label) {
            "enter" -> if (dark) 0xFF050506.toInt() else 0xFFFFFFFF.toInt()
            "shift_lock" -> 0xFFFFFFFF.toInt()
            "123", "abc", "back", "shift", "period", "." -> p.utilInk
            else -> p.ink
        }
    }

    private data class Palette(
        val deckTop: Int,
        val deckBottom: Int,
        val faceTop: Int,
        val faceMid: Int,
        val faceBottom: Int,
        val wall: Int,
        val ink: Int,
        val utilTop: Int,
        val utilMid: Int,
        val utilBottom: Int,
        val utilInk: Int,
        val goTop: Int,
        val goMid: Int,
        val goBottom: Int,
        val goInk: Int,
        val rim: Int,
        val shadow: Int,
        val grainLight: Int,
        val grainDark: Int
    )

    private fun palette(dark: Boolean): Palette = if (dark) {
        Palette(
            deckTop = 0xFF17191C.toInt(),
            deckBottom = 0xFF0E1012.toInt(),
            faceTop = 0xFF4A4E55.toInt(),
            faceMid = 0xFF33373D.toInt(),
            faceBottom = 0xFF202327.toInt(),
            wall = 0xFF0C0D0F.toInt(),
            ink = 0xFFF4F6F8.toInt(),
            utilTop = 0xFF2C2F34.toInt(),
            utilMid = 0xFF212327.toInt(),
            utilBottom = 0xFF141518.toInt(),
            utilInk = 0xFFC9CED6.toInt(),
            goTop = 0xFFF2F4F7.toInt(),
            goMid = 0xFFD4D8DD.toInt(),
            goBottom = 0xFFB3B8BF.toInt(),
            goInk = 0xFF101215.toInt(),
            rim = 0x52FFFFFF,
            shadow = 0xAA000000.toInt(),
            grainLight = 0x12FFFFFF,
            grainDark = 0x12000000
        )
    } else {
        Palette(
            deckTop = 0xFFD3D6DA.toInt(),
            deckBottom = 0xFFC2C6CB.toInt(),
            faceTop = 0xFFFFFFFF.toInt(),
            faceMid = 0xFFECEEF1.toInt(),
            faceBottom = 0xFFD2D6DB.toInt(),
            wall = 0xFFA9AEB5.toInt(),
            ink = 0xFF1A1C1F.toInt(),
            utilTop = 0xFFE2E4E8.toInt(),
            utilMid = 0xFFD0D3D8.toInt(),
            utilBottom = 0xFFBCC0C6.toInt(),
            utilInk = 0xFF33373D.toInt(),
            goTop = 0xFF3A3D42.toInt(),
            goMid = 0xFF26282C.toInt(),
            goBottom = 0xFF141517.toInt(),
            goInk = 0xFFF4F6F8.toInt(),
            rim = 0xDFFFFFFF.toInt(),
            shadow = 0x46808A96,
            grainLight = 0x24FFFFFF,
            grainDark = 0x17000000
        )
    }

    private class BrushedPanelDrawable(
        private val p: Palette,
        private val density: Float
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return
            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f, 0f, 0f, h, p.deckTop, p.deckBottom, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            drawGrain(canvas, RectF(0f, 0f, w, h), p, density, 0.85f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = density.coerceAtLeast(1f)
            paint.color = p.rim
            canvas.drawLine(0f, density, w, density, paint)
            paint.color = p.shadow
            canvas.drawLine(0f, h - density, w, h - density, paint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated by Android Drawable; required for framework compatibility.")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private class BrushedKeyDrawable(
        private val label: String,
        private val pressed: Boolean,
        private val p: Palette,
        private val density: Float,
        private val docked: Boolean,
        private val goColor: Int?
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private fun dp(value: Float) = value * density

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return
            val isGo = label == "enter"
            val isShiftLock = label == "shift_lock"
            val isCircle = isGo || label == "123" || label == "abc"
            val isUtil = isShiftLock || label == "clicks" || label == "back" || label == "shift" || label == "period" || label == "." || label == "123" || label == "abc"
            val radius = if (isCircle) min(w, h) / 2f else dp(8f)
            val wallDepth = if (pressed) dp(1f) else dp(4f)
            val sideInset = if (isCircle) dp(2f) else dp(1.5f)
            val circleLift = if (isCircle) dp(3f) else 0f
            val face = RectF(sideInset, 0f, w - sideInset, h - wallDepth - circleLift)
            val wall = RectF(sideInset, wallDepth, w - sideInset, h - circleLift)

            paint.style = Paint.Style.FILL
            paint.color = p.shadow
            val shadow = RectF(sideInset + dp(1f), dp(if (pressed) 1f else 5f), w - sideInset - dp(1f), h)
            canvas.drawRoundRect(shadow, radius, radius, paint)

            paint.color = if (isGo && goColor != null) darken(goColor, 0.48f) else if (isGo) p.goBottom else p.wall
            canvas.drawRoundRect(wall, radius, radius, paint)

            if (label == "123" || label == "abc") {
                paint.shader = LinearGradient(0f, face.top, 0f, face.bottom, p.utilTop, p.utilBottom, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(face, radius, radius, paint)
                paint.shader = null
                drawGrain(canvas, face, p, density, 0.68f)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1.5f)
                paint.color = p.utilInk
                canvas.drawOval(face.insetCopy(dp(2f)), paint)
                return
            }

            val colors = when {
                isGo && goColor != null -> intArrayOf(lighten(goColor, if (pressed) 0.18f else 0.28f), goColor, darken(goColor, if (pressed) 0.34f else 0.24f))
                isGo -> intArrayOf(p.goTop, p.goMid, p.goBottom)
                isShiftLock -> intArrayOf(0xFF5B8CFF.toInt(), 0xFF2859D8.toInt(), 0xFF102E7A.toInt())
                isUtil -> intArrayOf(p.utilTop, p.utilMid, p.utilBottom)
                else -> intArrayOf(p.faceTop, p.faceMid, p.faceBottom)
            }
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f, face.top, 0f, face.bottom, colors, floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(face, radius, radius, paint)
            paint.shader = null
            drawGrain(canvas, face, p, density, if (pressed) 0.45f else 0.78f)

            paint.shader = LinearGradient(0f, face.top, 0f, face.top + face.height() * 0.45f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(face.insetCopy(dp(1.2f)), radius, radius, paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f)
            paint.color = if (isGo) 0x72FFFFFF else p.rim
            canvas.drawRoundRect(face.insetCopy(dp(0.5f)), radius, radius, paint)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = 0x72FFFFFF
            canvas.drawLine(face.left + radius * 0.38f, face.top + dp(1f), face.right - radius * 0.38f, face.top + dp(1f), paint)
            paint.color = 0x24FFFFFF
            canvas.drawLine(face.left + dp(1f), face.top + radius * 0.35f, face.left + dp(1f), face.bottom - radius * 0.35f, paint)

            if (label == "clicks") drawDockIcon(canvas, face, p.utilInk)
        }

        private fun drawDockIcon(canvas: Canvas, face: RectF, color: Int) {
            val size = min(face.width(), face.height()) * 0.52f
            val left = face.centerX() - size / 2f
            val top = face.centerY() - size / 2f
            val s = size / 24f
            iconPaint.strokeWidth = dp(1.6f)
            iconPaint.color = color
            iconPaint.style = Paint.Style.STROKE
            val phone = RectF(left + 4f * s, top + 3f * s, left + 20f * s, top + 21f * s)
            canvas.drawRoundRect(phone, 2.5f * s, 2.5f * s, iconPaint)
            iconPaint.style = Paint.Style.FILL
            if (docked) {
                canvas.drawRoundRect(RectF(left + 6.5f * s, top + 14.5f * s, left + 17.5f * s, top + 18.7f * s), 1.2f * s, 1.2f * s, iconPaint)
            } else {
                canvas.drawRoundRect(RectF(left + 7f * s, top + 9.5f * s, left + 17f * s, top + 13.5f * s), 1.2f * s, 1.2f * s, iconPaint)
                iconPaint.style = Paint.Style.STROKE
                canvas.drawLine(left + 8.5f * s, top + 18.5f * s, left + 15.5f * s, top + 18.5f * s, iconPaint)
            }
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha; iconPaint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
            iconPaint.colorFilter = colorFilter
        }
        @Deprecated("Deprecated by Android Drawable; required for framework compatibility.")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun drawGrain(canvas: Canvas, rect: RectF, p: Palette, density: Float, alpha: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val step = maxOf(2f, density * 2f)
        val light = withAlpha(p.grainLight, alpha)
        val dark = withAlpha(p.grainDark, alpha)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        var y = rect.top
        var i = 0
        while (y <= rect.bottom) {
            paint.color = if (i % 2 == 0) light else dark
            canvas.drawLine(rect.left, y, rect.right, y, paint)
            y += step
            i++
        }
    }

    private fun withAlpha(color: Int, multiplier: Float): Int {
        val alpha = (Color.alpha(color) * multiplier).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun lighten(color: Int, amount: Float): Int {
        val a = Color.alpha(color)
        val r = (Color.red(color) + (255 - Color.red(color)) * amount).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * amount).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    private fun darken(color: Int, amount: Float): Int {
        val a = Color.alpha(color)
        val r = (Color.red(color) * (1f - amount)).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * (1f - amount)).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * (1f - amount)).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }
}

private fun RectF.insetCopy(amount: Float): RectF =
    RectF(left + amount, top + amount, right - amount, bottom - amount)
