package com.fran.teclas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlin.math.min

/**
 * Rcaps — realistic keycaps rendered the "bake once, blit many" way.
 *
 * The 3D illusion (radial dish, soft contact shadow, chamfer rim, side wall) is expensive to
 * *compose* but cheap to *copy*. So each cap is painted a single time into an off-screen [Bitmap],
 * keyed by its exact pixel size + look, and cached. The typing hot path then only ever does one
 * [Canvas.drawBitmap] per key — realism costs the same as a flat rectangle.
 *
 * This mirrors the app's existing model: [TeclasImeService] already blits a cached [Drawable] per
 * cell and clears its `keyBgCache` when the theme/mode/accent signature changes. Rcaps slots into
 * that path by returning a [Drawable] whose bitmap is baked lazily to match its bounds — so a wide
 * space bar and a square Enter key each get a *pixel-accurate* cap instead of a stretched one.
 */
object RcapsKeycaps {

    enum class Kind { LETTER, FN, ACCENT }

    /** Everything that changes a cap's pixels. Two keys with the same spec + size share one bitmap. */
    data class Spec(
        val dark: Boolean,
        val pressed: Boolean,
        val kind: Kind,
        val baseColor: Int,
        val baseEnd: Int,
        val accent: Int,
        val radiusDp: Int,
    ) {
        val cacheKey: String
            get() = "$dark|$pressed|$kind|$baseColor|$baseEnd|$accent|$radiusDp"
    }

    // Bounded by bytes: a handful of distinct key widths × 2 states × 3 kinds is tiny, but the space
    // bar bitmap is wide, so cap the total instead of the count. ~6 MB holds every cap on screen many
    // times over; least-recently-used caps evict automatically.
    private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun drawable(density: Float, spec: Spec): Drawable = RcapsKeycapDrawable(density, spec)

    /** Fetch (or bake once) the cap bitmap for this exact size + spec. */
    fun bake(wPx: Int, hPx: Int, density: Float, spec: Spec): Bitmap {
        val key = "${wPx}x$hPx|${spec.cacheKey}"
        cache.get(key)?.let { return it }
        val bmp = Bitmap.createBitmap(wPx.coerceAtLeast(1), hPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        paint(Canvas(bmp), wPx.toFloat(), hPx.toFloat(), density, spec)
        cache.put(key, bmp)
        return bmp
    }

    private fun paint(c: Canvas, w: Float, h: Float, density: Float, spec: Spec) {
        val d = density
        val pressed = spec.pressed

        // Geometry: the cap sinks and its wall collapses when pressed — the mechanical travel.
        val padB = (if (pressed) 2f else 5f) * d      // bottom reserve for the drop shadow
        val wallH = (if (pressed) 2f else 6f) * d     // visible side/bottom wall thickness
        val capTop = (if (pressed) 3f else 0f) * d
        val faceLeft = 1f * d
        val faceRight = w - 1f * d
        val capBottom = h - padB
        val faceBottom = capBottom - wallH
        val faceH = faceBottom - capTop
        if (faceH <= 2f || faceRight - faceLeft <= 2f) return
        val r = min(spec.radiusDp * d, min((faceRight - faceLeft) / 2f, faceH / 2f)).coerceAtLeast(2f)
        val cx = (faceLeft + faceRight) / 2f

        val faceTopC = brighten(spec.baseColor, if (spec.dark) 1.15f else 1.06f)
        val faceMidC = spec.baseColor
        val faceBotC = spec.baseEnd

        // 1) Wall body — also casts the soft contact shadow in a single draw.
        val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, capTop, 0f, capBottom,
                darken(spec.baseEnd, 0.62f), darken(spec.baseEnd, 0.40f), Shader.TileMode.CLAMP)
            setShadowLayer(
                (if (pressed) 3f else 9f) * d, 0f, (if (pressed) 1.5f else 4.5f) * d,
                if (spec.dark) 0x8A000000.toInt() else 0x33304860
            )
        }
        c.drawRoundRect(faceLeft, capTop, faceRight, capBottom, r, r, wallPaint)

        // 2) Face — the top surface gradient.
        val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, capTop, 0f, faceBottom,
                intArrayOf(faceTopC, faceMidC, faceBotC), floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(faceLeft, capTop, faceRight, faceBottom, r, r, facePaint)

        // 3) Curvature — clip to the face and paint the crown.
        c.save()
        c.clipPath(Path().apply { addRoundRect(faceLeft, capTop, faceRight, faceBottom, r, r, Path.Direction.CW) })
        if (spec.kind == Kind.ACCENT) {
            // Convex dome — a glossy crown for the Enter cap.
            c.drawRect(faceLeft, capTop, faceRight, faceBottom, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, capTop + faceH * 0.34f, faceH * 0.98f,
                    intArrayOf(withAlpha(Color.WHITE, if (spec.dark) 0.24f else 0.36f), 0x00FFFFFF),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            })
        } else {
            // Concave scoop — soft shade up top, light pooling in the dish below.
            c.drawRect(faceLeft, capTop, faceRight, faceBottom, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, capTop + faceH * 0.30f, faceH * 1.15f,
                    intArrayOf(withAlpha(Color.BLACK, if (spec.dark) 0.20f else 0.08f), 0x00000000),
                    floatArrayOf(0f, 0.62f), Shader.TileMode.CLAMP)
            })
            c.drawRect(faceLeft, capTop + faceH * 0.55f, faceRight, faceBottom, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, capTop + faceH * 0.55f, 0f, faceBottom,
                    0x00FFFFFF, withAlpha(Color.WHITE, if (spec.dark) 0.07f else 0.28f), Shader.TileMode.CLAMP)
            })
        }
        c.restore()

        // 4) Chamfer rim — the bright top edge that sells injection-molded plastic.
        val inset = 0.9f * d
        c.drawRoundRect(faceLeft + inset, capTop + inset * 0.6f, faceRight - inset, faceBottom - inset,
            (r - inset).coerceAtLeast(1f), (r - inset).coerceAtLeast(1f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.3f * d
                shader = LinearGradient(0f, capTop, 0f, capTop + faceH * 0.5f,
                    withAlpha(Color.WHITE, if (spec.dark) 0.26f else 0.9f), 0x00FFFFFF, Shader.TileMode.CLAMP)
            })
    }

    private fun withAlpha(color: Int, a: Float): Int = Color.argb(
        (255 * a).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun brighten(color: Int, f: Float): Int = scale(color, f)
    private fun darken(color: Int, f: Float): Int = scale(color, f)
    private fun scale(color: Int, f: Float): Int = Color.argb(
        Color.alpha(color),
        (Color.red(color) * f).toInt().coerceIn(0, 255),
        (Color.green(color) * f).toInt().coerceIn(0, 255),
        (Color.blue(color) * f).toInt().coerceIn(0, 255))

    /**
     * A [Drawable] that bakes its cap to match the current [bounds] and blits it. Bounds are set by
     * the framework before each draw (the canvas keyboard hands each cell its size), so the bake
     * happens once per distinct size and every later frame is a single [Canvas.drawBitmap].
     */
    private class RcapsKeycapDrawable(
        private val density: Float,
        private val spec: Spec,
    ) : Drawable() {
        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val bmp = bake(b.width(), b.height(), density, spec)
            canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), null)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
