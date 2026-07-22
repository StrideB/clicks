package com.fran.teclas.clock

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.View
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class ClockContainer { GLASS_BOX, OPEN }

sealed interface ClockTheme {
    val id: String
    val label: String
    val container: ClockContainer
}

data class ClockState(
    val hour: Int,
    val minute: Int,
    val second: Int,
    val isAM: Boolean,
    val is24Hour: Boolean,
    val dayOfWeek: String,
    val date: String,
    val weatherTempF: Int? = null,
    val city: String? = null
)

enum class ClockKind {
    SERIF_GRAND,
    GLASS_SLAB,
    STACKED,
    MONO_TICKER,
    ANALOG_GLASS,
    ULTRA_THIN,
    OUTLINE,
    PILL_SPLIT,
    NEON_GLOW,
    VERTICAL_RAIL,
    DATE_CARD,
    DOT_MATRIX,
    RING,
    BIG_SMALL,
    WIDE_BAR,
    ROMAN_CAPS,
    GRADIENT_FILL,
    TILE_GRID,
    ITALIC_HAND,
    COMPACT_CHIP
}

data class ClockThemeSpec(
    override val id: String,
    override val label: String,
    override val container: ClockContainer,
    val kind: ClockKind
) : ClockTheme

object ClockThemes {
    const val DEFAULT_ID = "serif_grand"

    val all: List<ClockThemeSpec> = listOf(
        ClockThemeSpec("serif_grand", "Serif Grand", ClockContainer.OPEN, ClockKind.SERIF_GRAND),
        ClockThemeSpec("glass_slab", "Glass Slab", ClockContainer.GLASS_BOX, ClockKind.GLASS_SLAB),
        ClockThemeSpec("stacked", "Stacked", ClockContainer.OPEN, ClockKind.STACKED),
        ClockThemeSpec("mono_ticker", "Mono Ticker", ClockContainer.GLASS_BOX, ClockKind.MONO_TICKER),
        ClockThemeSpec("analog_glass", "Analog Glass", ClockContainer.GLASS_BOX, ClockKind.ANALOG_GLASS),
        ClockThemeSpec("ultra_thin", "Ultra Thin", ClockContainer.OPEN, ClockKind.ULTRA_THIN),
        ClockThemeSpec("outline", "Outline", ClockContainer.OPEN, ClockKind.OUTLINE),
        ClockThemeSpec("pill_split", "Pill Split", ClockContainer.GLASS_BOX, ClockKind.PILL_SPLIT),
        ClockThemeSpec("neon_glow", "Neon Glow", ClockContainer.OPEN, ClockKind.NEON_GLOW),
        ClockThemeSpec("vertical_rail", "Vertical Rail", ClockContainer.OPEN, ClockKind.VERTICAL_RAIL),
        ClockThemeSpec("date_card", "Date Card", ClockContainer.GLASS_BOX, ClockKind.DATE_CARD),
        ClockThemeSpec("dot_matrix", "Dot Matrix", ClockContainer.OPEN, ClockKind.DOT_MATRIX),
        ClockThemeSpec("ring", "Ring", ClockContainer.GLASS_BOX, ClockKind.RING),
        ClockThemeSpec("big_small", "Big + Small", ClockContainer.OPEN, ClockKind.BIG_SMALL),
        ClockThemeSpec("wide_bar", "Wide Bar", ClockContainer.GLASS_BOX, ClockKind.WIDE_BAR),
        ClockThemeSpec("roman_caps", "Roman Caps", ClockContainer.OPEN, ClockKind.ROMAN_CAPS),
        ClockThemeSpec("gradient_fill", "Gradient Fill", ClockContainer.OPEN, ClockKind.GRADIENT_FILL),
        ClockThemeSpec("tile_grid", "Tile Grid", ClockContainer.GLASS_BOX, ClockKind.TILE_GRID),
        ClockThemeSpec("italic_hand", "Italic Hand", ClockContainer.OPEN, ClockKind.ITALIC_HAND),
        ClockThemeSpec("compact_chip", "Compact Chip", ClockContainer.GLASS_BOX, ClockKind.COMPACT_CHIP)
    )

    fun byId(raw: String?): ClockThemeSpec =
        all.firstOrNull { it.id == raw } ?: all.first()

    fun needsSeconds(raw: String?): Boolean =
        byId(raw).kind == ClockKind.ANALOG_GLASS || byId(raw).kind == ClockKind.RING
}

class ClockWidgetView(context: Context) : View(context) {
    var themeId: String = ClockThemes.DEFAULT_ID
        set(value) {
            field = ClockThemes.byId(value).id
            rescheduleTicker()
            invalidate()
        }

    var accentColor: Int = Color.rgb(255, 95, 168)
        set(value) {
            field = value
            invalidate()
        }

    var isDarkMode: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var weatherTempF: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    var city: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var previewState: ClockState? = null
        set(value) {
            field = value
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, if (ClockThemes.needsSeconds(themeId)) 1_000L else nextMinuteDelay())
        }
    }

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rescheduleTicker()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    private fun rescheduleTicker() {
        removeCallbacks(ticker)
        if (isAttachedToWindow && previewState == null) post(ticker)
    }

    private fun nextMinuteDelay(): Long {
        val now = System.currentTimeMillis()
        return 60_000L - (now % 60_000L) + 80L
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val theme = ClockThemes.byId(themeId)
        val state = previewState ?: liveClockState()
        if (theme.container == ClockContainer.GLASS_BOX) drawGlassContainer(canvas)

        when (theme.kind) {
            ClockKind.SERIF_GRAND -> drawSerifGrand(canvas, state)
            ClockKind.GLASS_SLAB -> drawGlassSlab(canvas, state)
            ClockKind.STACKED -> drawStacked(canvas, state)
            ClockKind.MONO_TICKER -> drawMonoTicker(canvas, state)
            ClockKind.ANALOG_GLASS -> drawAnalog(canvas, state)
            ClockKind.ULTRA_THIN -> drawUltraThin(canvas, state)
            ClockKind.OUTLINE -> drawOutline(canvas, state)
            ClockKind.PILL_SPLIT -> drawPillSplit(canvas, state)
            ClockKind.NEON_GLOW -> drawNeonGlow(canvas, state)
            ClockKind.VERTICAL_RAIL -> drawVerticalRail(canvas, state)
            ClockKind.DATE_CARD -> drawDateCard(canvas, state)
            ClockKind.DOT_MATRIX -> drawDotMatrix(canvas, state)
            ClockKind.RING -> drawRing(canvas, state)
            ClockKind.BIG_SMALL -> drawBigSmall(canvas, state)
            ClockKind.WIDE_BAR -> drawWideBar(canvas, state)
            ClockKind.ROMAN_CAPS -> drawRomanCaps(canvas, state)
            ClockKind.GRADIENT_FILL -> drawGradientFill(canvas, state)
            ClockKind.TILE_GRID -> drawTileGrid(canvas, state)
            ClockKind.ITALIC_HAND -> drawItalicHand(canvas, state)
            ClockKind.COMPACT_CHIP -> drawCompactChip(canvas, state)
        }
    }

    private fun liveClockState(): ClockState {
        val locale = Locale.getDefault()
        val now = LocalDateTime.now()
        val day = now.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale)
        val date = now.format(DateTimeFormatter.ofPattern("MMM d", locale))
        return ClockState(
            hour = now.hour,
            minute = now.minute,
            second = now.second,
            isAM = now.hour < 12,
            is24Hour = DateFormat.is24HourFormat(context),
            dayOfWeek = day,
            date = date,
            weatherTempF = weatherTempF,
            city = city
        )
    }

    private fun drawGlassContainer(canvas: Canvas) {
        val radius = dp(24f)
        rect.set(dp(1f), dp(1f), width - dp(1f), height - dp(1f))
        fillPaint.shader = null
        fillPaint.color = Color.argb(if (isDarkMode) 92 else 130, 255, 255, 255)
        fillPaint.setShadowLayer(dp(14f), 0f, dp(6f), Color.argb(if (isDarkMode) 115 else 55, 0, 0, 0))
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        fillPaint.clearShadowLayer()
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(Color.argb(42, 255, 255, 255), Color.argb(if (isDarkMode) 16 else 28, 255, 255, 255), Color.argb(18, 0, 0, 0)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        fillPaint.shader = null
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = Color.argb(if (isDarkMode) 46 else 76, 255, 255, 255)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
    }

    private fun drawSerifGrand(canvas: Canvas, state: ClockState) {
        centeredText(canvas, time(state), cx(), height * 0.50f, spScaled(44f), serif(Typeface.BOLD), ink(), -0.035f)
        centeredText(canvas, "${state.dayOfWeek} ${partOfDay(state)}", cx(), height * 0.76f, spScaled(14f), serif(Typeface.ITALIC), muted(), 0f)
    }

    private fun drawGlassSlab(canvas: Canvas, state: ClockState) {
        val y = height * 0.58f
        centeredText(canvas, time(state), cx() - if (state.is24Hour) 0f else dp(12f), y, spScaled(40f), sans(200), ink(), -0.04f)
        if (!state.is24Hour) text(canvas, amPm(state), width - dp(44f), y - spScaled(14f), spScaled(10f), mono(Typeface.BOLD), accentColor, 0.18f, Paint.Align.CENTER)
    }

    private fun drawStacked(canvas: Canvas, state: ClockState) {
        centeredText(canvas, hourText(state), cx(), height * 0.43f, spScaled(43f), sans(Typeface.BOLD), ink(), -0.02f)
        line(canvas, cx() - dp(20f), height * 0.50f, cx() + dp(20f), height * 0.50f, accentColor, dp(2f))
        centeredText(canvas, "%02d".format(Locale.US, state.minute), cx(), height * 0.78f, spScaled(43f), sans(200), ink(), -0.02f)
    }

    private fun drawMonoTicker(canvas: Canvas, state: ClockState) {
        centeredText(canvas, time(state, forcePadHour = true), cx(), height * 0.51f, spScaled(32f), mono(Typeface.BOLD), ink(), 0.02f)
        centeredText(canvas, compactDate(state), cx(), height * 0.75f, spScaled(10f), mono(Typeface.NORMAL), muted(), 0.16f)
    }

    private fun drawAnalog(canvas: Canvas, state: ClockState) {
        val radius = min(width, height) * 0.34f
        val centerY = height * 0.50f
        fillPaint.shader = RadialGradient(cx(), centerY, radius, intArrayOf(Color.argb(80, 255, 255, 255), Color.argb(24, 255, 255, 255), Color.argb(20, 0, 0, 0)), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx(), centerY, radius, fillPaint)
        fillPaint.shader = null
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = Color.argb(70, 255, 255, 255)
        canvas.drawCircle(cx(), centerY, radius, strokePaint)
        for (i in 0 until 12) {
            val angle = Math.toRadians((i * 30 - 90).toDouble())
            val r1 = radius * 0.82f
            val r2 = radius * 0.92f
            line(canvas, cx() + cos(angle).toFloat() * r1, centerY + sin(angle).toFloat() * r1, cx() + cos(angle).toFloat() * r2, centerY + sin(angle).toFloat() * r2, muted(), dp(if (i % 3 == 0) 1.8f else 1f))
        }
        val minuteAngle = state.minute * 6f + state.second * 0.1f
        val hourAngle = (state.hour % 12) * 30f + state.minute * 0.5f
        hand(canvas, cx(), centerY, radius * 0.48f, hourAngle, ink(), dp(4f))
        hand(canvas, cx(), centerY, radius * 0.72f, minuteAngle, ink(), dp(2.5f))
        fillPaint.color = accentColor
        canvas.drawCircle(cx(), centerY, dp(4.5f), fillPaint)
    }

    private fun drawUltraThin(canvas: Canvas, state: ClockState) {
        centeredText(canvas, time(state), cx(), height * 0.62f, spScaled(52f), sans(100), ink(), -0.08f)
    }

    private fun drawOutline(canvas: Canvas, state: ClockState) {
        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = dp(1.5f)
        centeredText(canvas, time(state), cx(), height * 0.62f, spScaled(50f), serif(Typeface.BOLD), Color.argb(225, 255, 255, 255), -0.05f)
        textPaint.style = Paint.Style.FILL
    }

    private fun drawPillSplit(canvas: Canvas, state: ClockState) {
        val h = dp(54f)
        val gap = dp(8f)
        val cellW = (width - dp(38f) - gap) / 2f
        val top = (height - h) / 2f
        glassSegment(canvas, dp(19f), top, cellW, h)
        glassSegment(canvas, dp(19f) + cellW + gap, top, cellW, h)
        centeredText(canvas, hourText(state).padStart(2, '0'), dp(19f) + cellW / 2f, top + h * 0.69f, spScaled(28f), sans(Typeface.BOLD), ink(), 0f)
        centeredText(canvas, "%02d".format(Locale.US, state.minute), dp(19f) + cellW + gap + cellW / 2f, top + h * 0.69f, spScaled(28f), sans(250), ink(), 0f)
    }

    private fun drawNeonGlow(canvas: Canvas, state: ClockState) {
        val pink = Color.rgb(255, 151, 203)
        textPaint.setShadowLayer(dp(14f), 0f, 0f, Color.argb(190, 255, 68, 170))
        centeredText(canvas, time(state), cx(), height * 0.62f, spScaled(44f), sans(Typeface.BOLD), pink, -0.035f)
        textPaint.clearShadowLayer()
    }

    private fun drawVerticalRail(canvas: Canvas, state: ClockState) {
        centeredText(canvas, hourText(state).padStart(2, '0'), cx(), height * 0.38f, spScaled(34f), sans(Typeface.BOLD), ink(), 0f)
        line(canvas, cx() - dp(14f), height * 0.51f, cx() + dp(14f), height * 0.51f, accentColor, dp(3f))
        centeredText(canvas, "%02d".format(Locale.US, state.minute), cx(), height * 0.78f, spScaled(34f), sans(200), ink(), 0f)
    }

    private fun drawDateCard(canvas: Canvas, state: ClockState) {
        text(canvas, state.dayOfWeek.uppercase(Locale.getDefault()), dp(22f), height * 0.32f, spScaled(10f), mono(Typeface.BOLD), muted(), 0.18f, Paint.Align.LEFT)
        text(canvas, time(state), dp(22f), height * 0.63f, spScaled(38f), serif(Typeface.BOLD), ink(), -0.02f, Paint.Align.LEFT)
        text(canvas, state.date, dp(22f), height * 0.84f, spScaled(13f), serif(Typeface.ITALIC), muted(), 0f, Paint.Align.LEFT)
    }

    private fun drawDotMatrix(canvas: Canvas, state: ClockState) {
        centeredText(canvas, time(state, forcePadHour = true), cx(), height * 0.62f, spScaled(40f), mono(Typeface.BOLD), ink(), 0.06f)
        fillPaint.shader = null
        fillPaint.color = Color.argb(42, 255, 255, 255)
        var y = 0f
        while (y < height) {
            canvas.drawRect(0f, y, width.toFloat(), y + 1f, fillPaint)
            y += dp(6f)
        }
    }

    private fun drawRing(canvas: Canvas, state: ClockState) {
        val radius = min(width, height) * 0.34f
        val centerY = height * 0.50f
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = dp(7f)
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.color = Color.argb(42, 255, 255, 255)
        rect.set(cx() - radius, centerY - radius, cx() + radius, centerY + radius)
        canvas.drawArc(rect, -90f, 360f, false, strokePaint)
        strokePaint.color = accentColor
        val progress = (state.minute * 60f + state.second) / 3600f
        canvas.drawArc(rect, -90f, progress * 360f, false, strokePaint)
        strokePaint.strokeCap = Paint.Cap.BUTT
        centeredText(canvas, time(state), cx(), centerY + spScaled(10f), spScaled(23f), mono(Typeface.BOLD), ink(), 0f)
    }

    private fun drawBigSmall(canvas: Canvas, state: ClockState) {
        val leftX = width * 0.42f
        centeredText(canvas, hourText(state), leftX, height * 0.65f, spScaled(56f), sans(Typeface.BOLD), ink(), -0.06f)
        text(canvas, "%02d".format(Locale.US, state.minute), width * 0.62f, height * 0.53f, spScaled(24f), sans(Typeface.BOLD), ink(), 0f, Paint.Align.LEFT)
        if (!state.is24Hour) text(canvas, amPm(state), width * 0.63f, height * 0.71f, spScaled(10f), mono(Typeface.BOLD), muted(), 0.16f, Paint.Align.LEFT)
    }

    private fun drawWideBar(canvas: Canvas, state: ClockState) {
        text(canvas, time(state), dp(20f), height * 0.63f, spScaled(33f), sans(250), ink(), -0.03f, Paint.Align.LEFT)
        val right = listOf(compactDate(state), state.weatherTempF?.let { "$it°F" } ?: "").filter { it.isNotBlank() }.joinToString(" · ")
        text(canvas, right, width - dp(20f), height * 0.41f, spScaled(10f), mono(Typeface.BOLD), muted(), 0.12f, Paint.Align.RIGHT)
        text(canvas, state.city ?: "Local", width - dp(20f), height * 0.65f, spScaled(12f), sans(Typeface.BOLD), accentColor, 0f, Paint.Align.RIGHT)
    }

    private fun drawRomanCaps(canvas: Canvas, state: ClockState) {
        centeredText(canvas, time(state, forcePadHour = true).replace(':', '·'), cx(), height * 0.55f, spScaled(34f), serif(Typeface.BOLD), ink(), 0.16f)
        centeredText(canvas, (state.city ?: "LOCAL").uppercase(Locale.getDefault()), cx(), height * 0.78f, spScaled(10f), mono(Typeface.BOLD), muted(), 0.22f)
    }

    private fun drawGradientFill(canvas: Canvas, state: ClockState) {
        textPaint.shader = LinearGradient(0f, height * 0.25f, 0f, height * 0.78f, Color.WHITE, Color.rgb(255, 132, 192), Shader.TileMode.CLAMP)
        centeredText(canvas, time(state), cx(), height * 0.63f, spScaled(48f), sans(Typeface.BOLD), Color.WHITE, -0.05f)
        textPaint.shader = null
    }

    private fun drawTileGrid(canvas: Canvas, state: ClockState) {
        val digits = hourText(state).padStart(2, '0') + "%02d".format(Locale.US, state.minute)
        val tile = min((width - dp(58f)) / 4f, dp(38f))
        val gap = dp(7f)
        val total = tile * 4f + gap * 3f + dp(10f)
        var x = (width - total) / 2f
        val top = (height - tile) / 2f
        digits.forEachIndexed { i, c ->
            if (i == 2) {
                centeredText(canvas, ":", x - gap / 2f, top + tile * 0.70f, spScaled(22f), mono(Typeface.BOLD), accentColor, 0f)
                x += dp(10f)
            }
            glassSegment(canvas, x, top, tile, tile)
            centeredText(canvas, c.toString(), x + tile / 2f, top + tile * 0.70f, spScaled(21f), mono(Typeface.BOLD), ink(), 0f)
            x += tile + gap
        }
    }

    private fun drawItalicHand(canvas: Canvas, state: ClockState) {
        centeredText(canvas, time(state), cx(), height * 0.56f, spScaled(46f), serif(Typeface.ITALIC), ink(), -0.04f)
        centeredText(canvas, "a quiet ${partOfDay(state)}", cx(), height * 0.80f, spScaled(13f), serif(Typeface.ITALIC), accentColor, 0f)
    }

    private fun drawCompactChip(canvas: Canvas, state: ClockState) {
        fillPaint.shader = null
        fillPaint.color = accentColor
        canvas.drawCircle(dp(26f), height / 2f, dp(5f), fillPaint)
        text(canvas, time(state), dp(42f), height * 0.62f, spScaled(24f), sans(Typeface.BOLD), ink(), -0.02f, Paint.Align.LEFT)
    }

    private fun hand(canvas: Canvas, cx: Float, cy: Float, length: Float, degrees: Float, color: Int, width: Float) {
        val angle = Math.toRadians((degrees - 90f).toDouble())
        line(canvas, cx, cy, cx + cos(angle).toFloat() * length, cy + sin(angle).toFloat() * length, color, width)
    }

    private fun glassSegment(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        rect.set(x, y, x + w, y + h)
        val radius = dp(17f)
        fillPaint.shader = LinearGradient(0f, y, 0f, y + h, Color.argb(50, 255, 255, 255), Color.argb(16, 255, 255, 255), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        fillPaint.shader = null
        strokePaint.strokeWidth = dp(1f)
        strokePaint.color = Color.argb(58, 255, 255, 255)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
    }

    private fun centeredText(canvas: Canvas, value: String, x: Float, baseline: Float, size: Float, face: Typeface, color: Int, tracking: Float) {
        text(canvas, value, x, baseline, size, face, color, tracking, Paint.Align.CENTER)
    }

    private fun text(canvas: Canvas, value: String, x: Float, baseline: Float, size: Float, face: Typeface, color: Int, tracking: Float, align: Paint.Align) {
        textPaint.style = if (textPaint.style == Paint.Style.STROKE) Paint.Style.STROKE else Paint.Style.FILL
        textPaint.textSize = size
        textPaint.typeface = face
        textPaint.color = color
        textPaint.textAlign = align
        textPaint.letterSpacingCompat(tracking)
        canvas.drawText(value, x, baseline, textPaint)
        textPaint.shader = null
        textPaint.maskFilter = null
        textPaint.letterSpacingCompat(0f)
    }

    private fun Paint.letterSpacingCompat(value: Float) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            letterSpacing = value
        }
    }

    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
        strokePaint.shader = null
        strokePaint.color = color
        strokePaint.strokeWidth = width
        strokePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x1, y1, x2, y2, strokePaint)
        strokePaint.strokeCap = Paint.Cap.BUTT
    }

    private fun time(state: ClockState, forcePadHour: Boolean = false): String =
        "${hourText(state, forcePadHour)}:%02d".format(Locale.US, state.minute)

    private fun hourText(state: ClockState, forcePadHour: Boolean = false): String {
        val hour = if (state.is24Hour) state.hour else ((state.hour + 11) % 12) + 1
        return if (state.is24Hour || forcePadHour) "%02d".format(Locale.US, hour) else hour.toString()
    }

    private fun amPm(state: ClockState): String = if (state.isAM) "AM" else "PM"

    private fun compactDate(state: ClockState): String =
        "${state.dayOfWeek.take(3)} · ${state.date}".uppercase(Locale.getDefault())

    private fun partOfDay(state: ClockState): String = when (state.hour) {
        in 5..11 -> "morning"
        in 12..16 -> "afternoon"
        in 17..20 -> "evening"
        else -> "night"
    }

    private fun cx(): Float = width / 2f
    private fun ink(): Int = if (isDarkMode) Color.rgb(244, 241, 235) else Color.rgb(24, 27, 33)
    private fun muted(): Int = if (isDarkMode) Color.rgb(154, 155, 166) else Color.rgb(93, 99, 112)
    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
    private fun spScaled(value: Float): Float = sp(value * (height / dp(126f)).coerceIn(0.62f, 1.22f))
    private fun serif(style: Int): Typeface = Typeface.create(Typeface.SERIF, style)
    private fun mono(style: Int): Typeface = Typeface.create(Typeface.MONOSPACE, style)
    private fun sans(weightOrStyle: Int): Typeface = when (weightOrStyle) {
        Typeface.BOLD, Typeface.ITALIC, Typeface.NORMAL -> Typeface.create(Typeface.SANS_SERIF, weightOrStyle)
        else -> if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, weightOrStyle, false)
            else Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
}
