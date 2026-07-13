package com.fran.teclas

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

object KeyboardThemeDrawables {
    const val SANS = "sans"
    const val UNI = "uni"
    const val CUPER = "cuper"
    const val SAND = "sand"
    const val GOOGLE = "google"
    const val IOS = "ios"
    const val PIXEL_SAND = "pixel_sand"
    const val TECLAS_GLASS = "teclas_glass"
    const val DEFAULT_ACCENT = 0xFFC9A7FF.toInt()

    val addedThemes = listOf(GOOGLE, IOS, PIXEL_SAND) + KbThemes.RENDERABLE.map { it.id }
    val cycleThemes = KbThemes.RENDERABLE.map { it.id }

    fun isAddedTheme(theme: String): Boolean = theme in addedThemes || KbThemes.renderableById(theme) != null

    fun isLight(theme: String, darkMode: Boolean): Boolean =
        if (KbThemes.renderableById(theme) != null) !darkMode else when (theme) {
            GOOGLE, IOS -> !darkMode
            PIXEL_SAND -> true
            TECLAS_GLASS -> false
            else -> !darkMode
        }

    fun panel(context: Context, theme: String, darkMode: Boolean): GradientDrawable {
        KbThemes.renderableById(theme)?.let { t ->
            val p = t.palette(darkMode)
            val colors = if (p.bgGradEnd != 0) intArrayOf(p.bg, p.bgGradEnd) else intArrayOf(p.bg, p.bg)
            return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
                cornerRadius = dp(context, if (t.boxed) 18 else 12).toFloat()
                setStroke(dp(context, 1), if (isLight(theme, darkMode)) 0x44FFFFFF else 0x242A2E38)
            }
        }
        val p = palette(theme, darkMode, DEFAULT_ACCENT)
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, p.background).apply {
            cornerRadius = dp(context, if (theme == TECLAS_GLASS) 18 else 16).toFloat()
            setStroke(dp(context, 1), if (isLight(theme, darkMode)) 0x66FFFFFF else 0x242A2E38)
        }
    }

    fun key(context: Context, theme: String, label: String, pressed: Boolean, darkMode: Boolean, goColor: Int): GradientDrawable {
        KbThemes.renderableById(theme)?.let { t ->
            val p = t.palette(darkMode)
            val accent = t.accent(goColor, p)
            if (!t.boxed) {
                return GradientDrawable().apply {
                    cornerRadius = dp(context, 7).toFloat()
                    setColor(Color.TRANSPARENT)
                }
            }
            val isGo = label == "enter"
            val isFn = label == "123" || label == "abc" || label == "shift" || label == "back" || label == "." || label == "period" || label == "teclas"
            val colors = when {
                isGo -> intArrayOf(if (pressed) brighten(accent) else accent, accent, darken(accent))
                isFn -> intArrayOf(p.functionKey, darken(p.functionKey))
                p.keyGradEnd != 0 -> intArrayOf(if (pressed) darken(p.key) else p.key, if (pressed) darken(p.keyGradEnd) else p.keyGradEnd)
                else -> intArrayOf(if (pressed) darken(p.key) else p.key, darken(if (pressed) darken(p.key) else p.key))
            }
            return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
                cornerRadius = dp(context, t.radiusDp).toFloat()
                setStroke(dp(context, 1), if (isLight(theme, darkMode)) 0x22000000 else 0x22FFFFFF)
            }
        }
        val p = palette(theme, darkMode, goColor.takeIf { it != 0 } ?: DEFAULT_ACCENT)
        val isGo = label == "enter"
        val isFn = label == "123" || label == "abc" || label == "shift" || label == "back" || label == "." || label == "period"
        val colors = when {
            isGo -> intArrayOf(if (pressed) darken(p.accent) else brighten(p.accent), p.accent, darken(p.accent))
            isFn -> p.functionKey
            else -> p.key
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadius = dp(context, p.radiusDp).toFloat()
            setStroke(dp(context, 1), p.stroke)
        }
    }

    fun keyLayer(context: Context, theme: String, label: String, pressed: Boolean, darkMode: Boolean, goColor: Int): LayerDrawable {
        KbThemes.renderableById(theme)?.let { t ->
            val face = key(context, theme, label, pressed, darkMode, goColor)
            if (!t.boxed) return LayerDrawable(arrayOf(face))
            val shadow = GradientDrawable().apply {
                cornerRadius = dp(context, t.radiusDp).toFloat()
                setColor(if (t.shadow || t.glow) 0x55000000 else 0x26000000)
            }
            val layers = if ((t.id == TECLAS_GLASS || t.glow) && !pressed) {
                val glint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x18FFFFFF, 0x00FFFFFF)).apply {
                    cornerRadius = dp(context, t.radiusDp).toFloat()
                }
                arrayOf(shadow, face, glint)
            } else {
                arrayOf(shadow, face)
            }
            return LayerDrawable(layers).apply {
                val drop = if (pressed) dp(context, 1) else dp(context, if (t.shadow) 3 else 2)
                setLayerInset(0, dp(context, 1), drop, dp(context, 1), 0)
                setLayerInset(1, 0, 0, 0, drop)
                if (layers.size > 2) setLayerInset(2, dp(context, 3), dp(context, 2), dp(context, 3), dp(context, 12))
            }
        }
        val p = palette(theme, darkMode, goColor.takeIf { it != 0 } ?: DEFAULT_ACCENT)
        val shadowAlpha = when (theme) {
            IOS -> if (isLight(theme, darkMode)) 0x47000000 else 0x55000000
            GOOGLE -> if (isLight(theme, darkMode)) 0x1F000000 else 0x66000000
            TECLAS_GLASS -> 0x66000000
            else -> 0x26000000
        }
        val shadow = GradientDrawable().apply {
            cornerRadius = dp(context, p.radiusDp).toFloat()
            setColor(shadowAlpha)
        }
        val face = key(context, theme, label, pressed, darkMode, goColor)
        val layers = if (theme == TECLAS_GLASS && !pressed) {
            val glint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x14FFFFFF, 0x00FFFFFF)).apply {
                cornerRadius = dp(context, p.radiusDp).toFloat()
            }
            arrayOf(shadow, face, glint)
        } else {
            arrayOf(shadow, face)
        }
        return LayerDrawable(layers).apply {
            val drop = if (pressed) dp(context, 1) else dp(context, 2)
            setLayerInset(0, dp(context, 1), drop, dp(context, 1), 0)
            setLayerInset(1, 0, 0, 0, drop)
            if (layers.size > 2) setLayerInset(2, dp(context, 3), dp(context, 2), dp(context, 3), dp(context, 12))
        }
    }

    fun textColor(theme: String, label: String, darkMode: Boolean): Int {
        return textColor(theme, label, darkMode, DEFAULT_ACCENT)
    }

    fun textColor(theme: String, label: String, darkMode: Boolean, goColor: Int): Int {
        KbThemes.renderableById(theme)?.let { t ->
            val p = t.palette(darkMode)
            val accent = t.accent(goColor, p)
            val isGo = label == "enter"
            val isFn = label == "123" || label == "abc" || label == "shift" || label == "back" || label == "." || label == "period" || label == "teclas"
            return when {
                isGo && t.boxed -> if (luminance(accent) > 0.55f) 0xFF101116.toInt() else 0xFFFFFFFF.toInt()
                isGo || isFn -> accent
                else -> p.keyText
            }
        }
        val p = palette(theme, darkMode, DEFAULT_ACCENT)
        val isGo = label == "enter"
        return if (isGo && theme == TECLAS_GLASS) {
            if (luminance(p.accent) > 0.55f) 0xFF101116.toInt() else 0xFFFFFFFF.toInt()
        } else if (isGo) {
            0xFFFFFFFF.toInt()
        } else {
            p.keyText
        }
    }

    fun accent(theme: String, darkMode: Boolean, goColor: Int): Int =
        KbThemes.renderableById(theme)?.let { t ->
            val p = t.palette(darkMode)
            t.accent(goColor, p)
        } ?: palette(theme, darkMode, goColor.takeIf { it != 0 } ?: DEFAULT_ACCENT).accent

    fun displayName(theme: String): String = when (theme) {
        SANS, GOOGLE -> "Sans"
        UNI -> "Uni"
        CUPER, IOS -> "Cuper"
        SAND, PIXEL_SAND -> "Sand"
        TECLAS_GLASS -> "Teclas Glass"
        else -> KbThemes.byId(theme)?.displayName ?: theme
    }

    fun typeface(theme: String, label: String): android.graphics.Typeface? =
        KbThemes.renderableById(theme)?.typeface(label)

    private data class Palette(
        val background: IntArray,
        val key: IntArray,
        val functionKey: IntArray,
        val keyText: Int,
        val accent: Int,
        val radiusDp: Int,
        val stroke: Int
    )

    private fun palette(theme: String, darkMode: Boolean, goColor: Int): Palette = when (theme) {
        GOOGLE -> if (darkMode) {
            Palette(intArrayOf(0xFF202124.toInt(), 0xFF202124.toInt()), intArrayOf(0xFF3C4043.toInt(), 0xFF34383B.toInt()), intArrayOf(0xFF2A2C2F.toInt(), 0xFF242629.toInt()), 0xFFE8EAED.toInt(), 0xFF8AB4F8.toInt(), 7, 0x223C4043)
        } else {
            Palette(intArrayOf(0xFFECEFF3.toInt(), 0xFFECEFF3.toInt()), intArrayOf(0xFFFFFFFF.toInt(), 0xFFF7F8FA.toInt()), intArrayOf(0xFFDFE3E8.toInt(), 0xFFD4DAE1.toInt()), 0xFF20242B.toInt(), 0xFF1A73E8.toInt(), 7, 0x22000000)
        }
        IOS -> if (darkMode) {
            Palette(intArrayOf(0xFF0B0B0D.toInt(), 0xFF0B0B0D.toInt()), intArrayOf(0xFF4B4D52.toInt(), 0xFF3F4146.toInt()), intArrayOf(0xFF2C2E33.toInt(), 0xFF24262A.toInt()), 0xFFFFFFFF.toInt(), 0xFF0A84FF.toInt(), 6, 0x333C4048)
        } else {
            Palette(intArrayOf(0xFFD1D4DB.toInt(), 0xFFD1D4DB.toInt()), intArrayOf(0xFFFFFFFF.toInt(), 0xFFF7F7F8.toInt()), intArrayOf(0xFFAEB3BD.toInt(), 0xFFA3A9B4.toInt()), 0xFF0B0B0C.toInt(), 0xFF0A84FF.toInt(), 6, 0x33000000)
        }
        PIXEL_SAND -> Palette(
            intArrayOf(0xFFEFE6DC.toInt(), 0xFFEFE6DC.toInt()),
            intArrayOf(0xFFFBF6F0.toInt(), 0xFFF0E7DE.toInt()),
            intArrayOf(0xFFE3D7C8.toInt(), 0xFFD8CBBB.toInt()),
            0xFF3B3128.toInt(),
            0xFFE8703A.toInt(),
            9,
            0x24A57758
        )
        TECLAS_GLASS -> Palette(
            intArrayOf(0xFF20232A.toInt(), 0xFF14161B.toInt()),
            intArrayOf(0xFF2A2E37.toInt(), 0xFF191C22.toInt()),
            intArrayOf(0xFF1C1F26.toInt(), 0xFF12151A.toInt()),
            0xFFEEF1F6.toInt(),
            goColor,
            11,
            0x22FFFFFF
        )
        else -> Palette(intArrayOf(0, 0), intArrayOf(0, 0), intArrayOf(0, 0), Color.WHITE, goColor, 8, 0)
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
    private fun brighten(color: Int): Int = adjust(color, 1.16f)
    private fun darken(color: Int): Int = adjust(color, 0.72f)
    private fun adjust(color: Int, factor: Float): Int = Color.argb(
        Color.alpha(color),
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )
    private fun luminance(color: Int): Float =
        (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f
}
