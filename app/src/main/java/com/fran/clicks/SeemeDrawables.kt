package com.fran.clicks

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.min

internal object SeemeDrawables {
    fun panel(darkTint: Boolean): Drawable = SeemePanelDrawable(darkTint)
    fun key(label: String, pressed: Boolean, density: Float, goColor: Int? = null): Drawable =
        SeemeKeyDrawable(label, pressed, density, goColor)

    private class SeemePanelDrawable(private val darkTint: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            letterSpacing = 0.08f
        }
        private var cachedWidth = -1
        private var cachedHeight = -1
        private var cachedBlurredBoard: Bitmap? = null

        override fun draw(canvas: Canvas) {
            val b = bounds
            val width = b.width()
            val height = b.height()
            if (width <= 0 || height <= 0) return
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            canvas.drawBitmap(blurredCircuitBoard(width, height), null, RectF(0f, 0f, w, h), bitmapPaint)
            canvas.saveLayerAlpha(RectF(0f, 0f, w, h), if (darkTint) 92 else 108)
            drawCircuitBoard(canvas, w, h)
            canvas.restore()
            drawFrostedPane(canvas, w, h)
            canvas.restore()
        }

        private fun blurredCircuitBoard(width: Int, height: Int): Bitmap {
            cachedBlurredBoard?.let { cached ->
                if (!cached.isRecycled && cachedWidth == width && cachedHeight == height) return cached
            }
            val sample = if (darkTint) 0.035f else 0.038f
            val smallW = maxOf(1, (width * sample).toInt())
            val smallH = maxOf(1, (height * sample).toInt())
            val small = Bitmap.createBitmap(smallW, smallH, Bitmap.Config.ARGB_8888)
            Canvas(small).apply {
                scale(smallW.toFloat() / width.toFloat(), smallH.toFloat() / height.toFloat())
                drawCircuitBoard(this, width.toFloat(), height.toFloat())
            }
            val blurredSmall = boxBlur(small, if (darkTint) 7 else 8)
            val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(full).drawBitmap(blurredSmall, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), bitmapPaint)
            blurredSmall.recycle()
            cachedBlurredBoard?.recycle()
            cachedBlurredBoard = full
            cachedWidth = width
            cachedHeight = height
            return full
        }

        private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
            val w = src.width
            val h = src.height
            if (w <= 1 || h <= 1 || radius <= 0) return src
            val source = IntArray(w * h)
            val temp = IntArray(w * h)
            val out = IntArray(w * h)
            src.getPixels(source, 0, w, 0, 0, w, h)
            val window = radius * 2 + 1

            for (y in 0 until h) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (i in -radius..radius) {
                    val c = source[y * w + i.coerceIn(0, w - 1)]
                    a += c ushr 24 and 0xFF
                    r += c ushr 16 and 0xFF
                    g += c ushr 8 and 0xFF
                    b += c and 0xFF
                }
                for (x in 0 until w) {
                    temp[y * w + x] =
                        (a / window shl 24) or
                        (r / window shl 16) or
                        (g / window shl 8) or
                        (b / window)
                    val remove = source[y * w + (x - radius).coerceIn(0, w - 1)]
                    val add = source[y * w + (x + radius + 1).coerceIn(0, w - 1)]
                    a += (add ushr 24 and 0xFF) - (remove ushr 24 and 0xFF)
                    r += (add ushr 16 and 0xFF) - (remove ushr 16 and 0xFF)
                    g += (add ushr 8 and 0xFF) - (remove ushr 8 and 0xFF)
                    b += (add and 0xFF) - (remove and 0xFF)
                }
            }

            for (x in 0 until w) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (i in -radius..radius) {
                    val c = temp[i.coerceIn(0, h - 1) * w + x]
                    a += c ushr 24 and 0xFF
                    r += c ushr 16 and 0xFF
                    g += c ushr 8 and 0xFF
                    b += c and 0xFF
                }
                for (y in 0 until h) {
                    out[y * w + x] =
                        (a / window shl 24) or
                        (r / window shl 16) or
                        (g / window shl 8) or
                        (b / window)
                    val remove = temp[(y - radius).coerceIn(0, h - 1) * w + x]
                    val add = temp[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                    a += (add ushr 24 and 0xFF) - (remove ushr 24 and 0xFF)
                    r += (add ushr 16 and 0xFF) - (remove ushr 16 and 0xFF)
                    g += (add ushr 8 and 0xFF) - (remove ushr 8 and 0xFF)
                    b += (add and 0xFF) - (remove and 0xFF)
                }
            }

            return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888).also {
                src.recycle()
            }
        }

        private fun drawCircuitBoard(canvas: Canvas, w: Float, h: Float) {
            paint.shader = LinearGradient(0f, 0f, w, h,
                intArrayOf(Color.rgb(5, 6, 7), Color.rgb(10, 13, 15), Color.rgb(3, 3, 4)),
                floatArrayOf(0f, 0.68f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = RadialGradient(w * 0.82f, h * 0.3f, min(w, h) * 0.55f,
                intArrayOf(0x1E60707A, 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = RadialGradient(w * 0.5f, h * 0.55f, min(w, h) * 0.8f,
                intArrayOf(0x00000000, 0x9C000000.toInt()), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            fun sx(x: Float) = w * x / 400f
            fun sy(y: Float) = h * y / 300f

            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = min(w, h) / 155f
            paint.color = 0x47D6A878
            val trace = Path().apply {
                moveTo(sx(90f), sy(212f))
                cubicTo(sx(140f), sy(182f), sx(210f), sy(178f), sx(276f), sy(150f))
                cubicTo(sx(305f), sy(138f), sx(318f), sy(122f), sx(335f), sy(102f))
            }
            canvas.drawPath(trace, paint)
            paint.color = 0x14D2E1EB
            paint.strokeWidth = min(w, h) / 230f
            canvas.drawPath(trace, paint)

            val coilX = sx(308f)
            val coilY = sy(145f)
            paint.strokeWidth = min(w, h) / 170f
            paint.color = 0x48E0C496
            listOf(60f, 51f, 42f, 33f, 24f, 15f).forEach { r ->
                canvas.drawCircle(coilX, coilY, sx(r), paint)
            }
            paint.color = 0x80FFEBCD.toInt()
            paint.strokeWidth = min(w, h) / 150f
            canvas.drawArc(RectF(coilX - sx(60f), coilY - sx(60f), coilX + sx(60f), coilY + sx(60f)), 218f, 58f, false, paint)
            paint.style = Paint.Style.FILL
            paint.color = 0x99D71921.toInt()
            canvas.drawCircle(coilX, coilY, sx(6f), paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = min(w, h) / 185f
            paint.color = 0x29D2E1EB
            repeat(5) { i ->
                val y = sy(42f + i * 8f)
                canvas.drawLine(sx(26f), y, sx(126f), y + sy(16f), paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = 0x66101519
            canvas.drawRoundRect(RectF(sx(112f), sy(48f), sx(174f), sy(92f)), sx(8f), sx(8f), paint)
            paint.style = Paint.Style.STROKE
            paint.color = 0x2ED2E1EB
            paint.strokeWidth = min(w, h) / 220f
            canvas.drawRoundRect(RectF(sx(112f), sy(48f), sx(174f), sy(92f)), sx(8f), sx(8f), paint)
            repeat(7) { i ->
                val x = sx(120f + i * 7f)
                canvas.drawLine(x, sy(91f), x, sy(101f), paint)
            }

            drawChip(canvas, sx(44f), sy(185f), sx(134f), sy(258f), "CLICKS OS", "v3.1 // ARM", true)
            drawChip(canvas, sx(286f), sy(218f), sx(360f), sy(264f), "IC-04", "", false)

            paint.style = Paint.Style.FILL
            repeat(17) { i ->
                paint.color = if (i == 13) 0x99D71921.toInt() else 0x1FDCE6F0
                canvas.drawCircle(sx(42f + i * 19f), sy(284f), sx(2.3f), paint)
            }

            listOf(22f to 24f, 378f to 24f, 22f to 276f, 378f to 276f).forEach { (x, y) ->
                drawScrew(canvas, sx(x), sy(y), sx(10f))
            }
        }

        private fun drawFrostedPane(canvas: Canvas, w: Float, h: Float) {
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                0f, 0f, 0f, h,
                if (darkTint) {
                    intArrayOf(0x981A2028.toInt(), 0xB20A0D11.toInt(), 0xC2020304.toInt())
                } else {
                    intArrayOf(0xE2FFFFFF.toInt(), 0xD4F1F6FA.toInt(), 0xB8D7E0EA.toInt())
                },
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = RadialGradient(
                w * 0.5f, h * 0.02f, min(w, h) * 0.88f,
                if (darkTint) intArrayOf(0x34FFFFFF, 0x12000000, 0x00000000)
                else intArrayOf(0x9CFFFFFF.toInt(), 0x36FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.46f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = RadialGradient(
                w * 0.18f, h * 0.92f, min(w, h) * 0.78f,
                if (darkTint) intArrayOf(0x56000000, 0x00000000)
                else intArrayOf(0x18000000, 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            paint.color = if (darkTint) 0x5AFFFFFF else 0xB8FFFFFF.toInt()
            canvas.drawLine(0f, 1f, w, 1f, paint)
            paint.color = if (darkTint) 0xAA000000.toInt() else 0x44000000
            canvas.drawLine(0f, h - 1f, w, h - 1f, paint)
        }

        private fun drawChip(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, title: String, sub: String, power: Boolean) {
            paint.style = Paint.Style.FILL
            paint.color = 0xB30C1012.toInt()
            canvas.drawRoundRect(RectF(l, t, r, b), 9f, 9f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            paint.color = 0x2ED2E1EB
            canvas.drawRoundRect(RectF(l, t, r, b), 9f, 9f, paint)
            val pins = 7
            repeat(pins) { i ->
                val y = t + 9f + i * ((b - t - 18f) / (pins - 1))
                canvas.drawLine(l - 4f, y, l, y, paint)
                canvas.drawLine(r, y, r + 4f, y, paint)
            }
            textPaint.color = 0x47D2E1EB
            textPaint.textSize = 10f
            canvas.drawText(title, l + 9f, t + 23f, textPaint)
            if (sub.isNotBlank()) canvas.drawText(sub, l + 9f, t + 40f, textPaint)
            if (power) {
                textPaint.color = 0xCCD71921.toInt()
                canvas.drawText("● PWR", l + 9f, b - 12f, textPaint)
            }
        }

        private fun drawScrew(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            paint.style = Paint.Style.FILL
            paint.color = 0x59B4BEC8
            canvas.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.4f
            paint.color = 0x4CFFFFFF
            canvas.drawCircle(cx, cy, r, paint)
            paint.color = 0x80FFFFFF.toInt()
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 210f, 70f, false, paint)
            paint.color = 0x99000000.toInt()
            paint.strokeWidth = 2f
            canvas.drawLine(cx - r * 0.42f, cy, cx + r * 0.42f, cy, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated by Android Drawable; required for framework compatibility.")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private class SeemeKeyDrawable(
        private val label: String,
        private val pressed: Boolean,
        private val density: Float,
        private val goColor: Int?
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private fun dp(value: Float) = value * density

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return
            val travel = if (pressed) dp(4f) else 0f
            val bevel = if (pressed) 0f else dp(3f)
            val radius = dp(if (label == "enter") 99f else 8f)
            val isGo = label == "enter"
            val isClicks = label == "clicks"
            val isDark = label == "123" || label == "back" || label == "." || label == "abc" || label == "shift"

            val wall = RectF(0f, bevel, w, h)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f, wall.top, 0f, wall.bottom,
                if (isGo) {
                    val c = goColor ?: 0xFFD71921.toInt()
                    intArrayOf(withAlpha(darken(c, 0.48f), 0x33), withAlpha(darken(c, 0.42f), 0xCC))
                } else intArrayOf(0x22FFFFFF, 0x9C000000.toInt()),
                null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(wall, radius, radius, paint)
            paint.shader = null

            val faceBottom = h - bevel + travel
            val face = RectF(0f, travel, w, faceBottom.coerceAtMost(h))
            if (isGo) {
                val c = goColor ?: 0xFFD71921.toInt()
                paint.shader = LinearGradient(0f, face.top, 0f, face.bottom,
                    intArrayOf(lighten(c, 0.24f), c, darken(c, 0.30f)),
                    null, Shader.TileMode.CLAMP)
            } else {
                val top = when {
                    isClicks -> 0x36D71921
                    isDark -> 0x26FFFFFF
                    else -> 0x42FFFFFF
                }
                val mid = if (isClicks) 0x18D71921 else 0x20FFFFFF
                paint.shader = LinearGradient(0f, face.top, 0f, face.bottom,
                    intArrayOf(top, mid, 0x0BFFFFFF),
                    floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
            }
            canvas.drawRoundRect(face, radius, radius, paint)
            paint.shader = null

            if (!isGo) {
                // Static frosted haze: a cheap Gaussian-blur impression that obscures the board just
                // enough to read as glass without using RenderEffect/backdrop blur while idle.
                paint.shader = RadialGradient(
                    face.centerX(), face.top + face.height() * 0.22f,
                    maxOf(face.width(), face.height()) * 0.78f,
                    intArrayOf(0x38FFFFFF, 0x14FFFFFF, 0x00FFFFFF),
                    floatArrayOf(0f, 0.54f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(face.insetCopy(dp(1f)), radius, radius, paint)
                paint.shader = LinearGradient(
                    face.left, face.top, face.right, face.bottom,
                    intArrayOf(0x00FFFFFF, 0x1FFFFFFF, 0x00FFFFFF),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(face.insetCopy(dp(2f)), radius, radius, paint)
                paint.shader = null
            }

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f)
            paint.color = when {
                isGo -> withAlpha(lighten(goColor ?: 0xFFD71921.toInt(), 0.34f), 0xAA)
                isClicks -> 0xAAD71921.toInt()
                else -> 0x66FFFFFF
            }
            canvas.drawRoundRect(face.insetCopy(dp(0.5f)), radius, radius, paint)
            if (!pressed) {
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = 0x88FFFFFF.toInt()
                paint.strokeWidth = dp(1f)
                canvas.drawLine(face.left + radius * 0.45f, face.top + dp(1f), face.right - radius * 0.45f, face.top + dp(1f), paint)
                paint.color = 0x38FFFFFF
                canvas.drawLine(face.left + dp(1f), face.top + radius * 0.45f, face.left + dp(1f), face.bottom - radius * 0.45f, paint)
                paint.color = 0x70000000
                paint.strokeWidth = dp(5f)
                canvas.drawLine(face.left + radius * 0.45f, face.bottom - dp(2f), face.right - radius * 0.45f, face.bottom - dp(2f), paint)
            }
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated by Android Drawable; required for framework compatibility.")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }
}

private fun RectF.insetCopy(amount: Float): RectF =
    RectF(left + amount, top + amount, right - amount, bottom - amount)

private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

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
