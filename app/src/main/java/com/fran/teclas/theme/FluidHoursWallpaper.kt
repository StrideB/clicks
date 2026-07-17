package com.fran.teclas.theme

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

enum class FluidHoursPhase(val label: String, val window: String) {
    DAWN("Dawn", "5 AM - 7 AM"),
    DAY("Day", "7 AM - 5 PM"),
    DUSK("Dusk", "5 PM - 8 PM"),
    NIGHT("Night", "after 8 PM")
}

data class FluidHoursOrb(
    val kind: String,
    val x: Float,
    val y: Float,
    val sizeDp: Float,
    val color: Int,
    val glow: Int
)

data class FluidHoursKeyframe(
    val phase: FluidHoursPhase,
    val w1: Int,
    val w2: Int,
    val w3: Int,
    val w4: Int,
    val w5: Int,
    val w6: Int,
    val skyA: Pair<Float, Float>,
    val skyB: Pair<Float, Float>,
    val orb: FluidHoursOrb,
    val stars: Boolean
)

object FluidHours {
    const val ID = "fluid-hours"

    private fun c(hex: String): Int = Color.parseColor(hex)

    val keyframes: Map<FluidHoursPhase, FluidHoursKeyframe> = mapOf(
        FluidHoursPhase.DAWN to FluidHoursKeyframe(
            FluidHoursPhase.DAWN, c("#ffb37a"), c("#ff6f91"), c("#ffd29b"), c("#e98fb0"), c("#6a5a9c"), c("#2b2a52"),
            0.80f to 0.12f, 0.18f to 0.88f,
            FluidHoursOrb("sun", 0.88f, 0.08f, 120f, c("#ffb37a"), c("#ffcf9e")),
            stars = false
        ),
        FluidHoursPhase.DAY to FluidHoursKeyframe(
            FluidHoursPhase.DAY, c("#ff2e88"), c("#3ad1c9"), c("#4fe0d6"), c("#159fd0"), c("#0e5f9c"), c("#0a2d55"),
            0.82f to 0.86f, 0.16f to 0.14f,
            FluidHoursOrb("sun", 0.84f, 0.06f, 90f, c("#ffe9a8"), c("#fff2c4")),
            stars = false
        ),
        FluidHoursPhase.DUSK to FluidHoursKeyframe(
            FluidHoursPhase.DUSK, c("#ff5e62"), c("#8a2be2"), c("#ff9a6b"), c("#c0468f"), c("#4a2b7a"), c("#1a1338"),
            0.76f to 0.20f, 0.24f to 0.90f,
            FluidHoursOrb("sun", 0.86f, 0.30f, 120f, c("#ff7e5f"), c("#ff8a6b")),
            stars = false
        ),
        FluidHoursPhase.NIGHT to FluidHoursKeyframe(
            FluidHoursPhase.NIGHT, c("#2e6bff"), c("#7a2bd6"), c("#164a8c"), c("#0e2f66"), c("#0a1a3c"), c("#04070f"),
            0.70f to 0.18f, 0.28f to 0.86f,
            FluidHoursOrb("moon", 0.80f, 0.09f, 70f, c("#c9d6ff"), c("#b9c8ff")),
            stars = true
        )
    )

    fun keyframe(phase: FluidHoursPhase): FluidHoursKeyframe =
        keyframes.getValue(phase)

    fun currentPhase(calendar: Calendar = Calendar.getInstance()): FluidHoursPhase {
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 5 -> FluidHoursPhase.NIGHT
            hour < 7 -> FluidHoursPhase.DAWN
            hour < 17 -> FluidHoursPhase.DAY
            hour < 20 -> FluidHoursPhase.DUSK
            else -> FluidHoursPhase.NIGHT
        }
    }

    fun dominantTint(phase: FluidHoursPhase = currentPhase()): Int = keyframe(phase).w2

    fun nextBoundaryDelayMillis(calendar: Calendar = Calendar.getInstance()): Long {
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val nextHour = when {
            hour < 5 -> 5
            hour < 7 -> 7
            hour < 17 -> 17
            hour < 20 -> 20
            else -> 24
        }
        val next = calendar.clone() as Calendar
        next.set(Calendar.HOUR_OF_DAY, nextHour)
        next.set(Calendar.MINUTE, 0)
        next.set(Calendar.SECOND, 1)
        next.set(Calendar.MILLISECOND, 0)
        return (next.timeInMillis - calendar.timeInMillis).coerceAtLeast(60_000L)
    }
}

class FluidHoursDrawable(
    private val context: Context,
    private var phase: FluidHoursPhase = FluidHours.currentPhase()
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private var alphaValue: Int = 255

    fun setPhase(next: FluidHoursPhase) {
        if (phase != next) {
            phase = next
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds.takeIf { !it.isEmpty } ?: return
        val frame = FluidHours.keyframe(phase)
        drawBase(canvas, b, frame)
        drawInkTexture(canvas, b, frame)
        if (frame.stars) drawStars(canvas, b)
        drawOrb(canvas, b, frame)
    }

    private fun drawBase(canvas: Canvas, b: Rect, frame: FluidHoursKeyframe) {
        paint.alpha = alphaValue
        paint.shader = LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(frame.w3, frame.w4, frame.w5, frame.w6),
            floatArrayOf(0f, 0.30f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(b, paint)

        drawRadial(canvas, b, frame.skyB, frame.w2, 0.55f, 0.50f)
        drawRadial(canvas, b, frame.skyA, frame.w1, 0.55f, 0.60f)
        paint.shader = null
    }

    private fun drawRadial(canvas: Canvas, b: Rect, at: Pair<Float, Float>, color: Int, stop: Float, radiusFactor: Float) {
        val cx = b.left + b.width() * at.first
        val cy = b.top + b.height() * at.second
        val radius = max(b.width(), b.height()) * radiusFactor
        paint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(applyAlpha(color, 0.96f), applyAlpha(color, 0.34f), Color.TRANSPARENT),
            floatArrayOf(0f, stop, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(b, paint)
    }

    private fun drawInkTexture(canvas: Canvas, b: Rect, frame: FluidHoursKeyframe) {
        texturePaint.shader = null
        texturePaint.color = applyAlpha(Color.WHITE, if (phase == FluidHoursPhase.NIGHT) 0.055f else 0.075f)
        texturePaint.strokeWidth = max(1f, b.width() / 520f)
        val count = 13
        for (i in 0 until count) {
            val t = i / (count - 1f)
            val y = b.top + b.height() * (0.10f + t * 0.82f)
            val inset = b.width() * (0.03f + (i % 3) * 0.018f)
            val path = Path().apply {
                moveTo(b.left + inset, y)
                cubicTo(
                    b.left + b.width() * 0.28f, y - b.height() * (0.06f + t * 0.03f),
                    b.left + b.width() * 0.60f, y + b.height() * (0.07f - t * 0.04f),
                    b.right - inset, y - b.height() * 0.025f
                )
            }
            canvas.drawPath(path, texturePaint)
        }

        texturePaint.color = applyAlpha(frame.w1, 0.12f)
        texturePaint.strokeWidth = max(1.2f, b.width() / 360f)
        canvas.drawOval(
            RectF(
                b.left - b.width() * 0.20f,
                b.top + b.height() * 0.10f,
                b.right + b.width() * 0.15f,
                b.bottom + b.height() * 0.20f
            ),
            texturePaint
        )
    }

    private fun drawOrb(canvas: Canvas, b: Rect, frame: FluidHoursKeyframe) {
        val density = context.resources.displayMetrics.density
        val orb = frame.orb
        val radius = orb.sizeDp * density / 2f
        val cx = b.left + b.width() * orb.x
        val cy = b.top + b.height() * orb.y
        paint.shader = RadialGradient(
            cx, cy, radius * 2.2f,
            intArrayOf(applyAlpha(orb.glow, 0.56f), applyAlpha(orb.glow, 0.16f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 2.2f, paint)

        paint.shader = RadialGradient(
            cx - radius * 0.25f, cy - radius * 0.28f, radius * 1.25f,
            intArrayOf(Color.WHITE, orb.color, darken(orb.color, 0.82f)),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, paint)

        if (orb.kind == "moon") {
            paint.shader = null
            paint.color = applyAlpha(frame.w6, 0.62f)
            canvas.drawCircle(cx + radius * 0.34f, cy - radius * 0.18f, radius * 0.88f, paint)
        }
        paint.shader = null
    }

    private fun drawStars(canvas: Canvas, b: Rect) {
        paint.shader = null
        val stars = floatArrayOf(
            .12f, .16f, .70f, .12f, .26f, .28f, .88f, .23f, .18f, .48f,
            .60f, .36f, .78f, .54f, .34f, .70f, .50f, .82f, .90f, .76f
        )
        for (i in stars.indices step 2) {
            val size = 1.6f + (i % 6) * 0.45f
            paint.color = applyAlpha(Color.WHITE, 0.32f + (i % 4) * 0.08f)
            canvas.drawCircle(b.left + b.width() * stars[i], b.top + b.height() * stars[i + 1], size, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        texturePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun applyAlpha(color: Int, alpha: Float): Int =
        Color.argb((min(1f, max(0f, alpha)) * Color.alpha(color) * (alphaValue / 255f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun darken(color: Int, amount: Float): Int =
        Color.rgb((Color.red(color) * amount).toInt(), (Color.green(color) * amount).toInt(), (Color.blue(color) * amount).toInt())
}

class FluidHoursView(context: Context) : View(context) {
    private var fromDrawable: FluidHoursDrawable? = null
    private var toDrawable = FluidHoursDrawable(context)
    private var progress = 1f
    private var animator: ValueAnimator? = null

    var phase: FluidHoursPhase = FluidHours.currentPhase()
        set(value) {
            if (field == value) return
            val previous = field
            field = value
            animator?.cancel()
            fromDrawable = FluidHoursDrawable(context, previous)
            toDrawable = FluidHoursDrawable(context, value)
            if (reducedMotion()) {
                progress = 1f
                fromDrawable = null
                invalidate()
                return
            }
            progress = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1_200L
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener {
                    progress = it.animatedValue as Float
                    if (progress >= 1f) fromDrawable = null
                    invalidate()
                }
                start()
            }
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = Rect(0, 0, width, height)
        fromDrawable?.let {
            it.bounds = rect
            it.alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
            it.draw(canvas)
        }
        toDrawable.bounds = rect
        toDrawable.alpha = (progress * 255).toInt().coerceIn(0, 255)
        toDrawable.draw(canvas)
    }

    private fun reducedMotion(): Boolean {
        val scale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        }.getOrDefault(1f)
        return scale == 0f
    }
}
