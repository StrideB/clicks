package com.fran.teclas

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
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
    const val THREE_D_DEPTH = KbThemes.THREE_D_DEPTH_ID
    const val THREE_D_GLASS = KbThemes.THREE_D_GLASS_ID
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

    fun panel(context: Context, theme: String, darkMode: Boolean): Drawable {
        KbThemes.renderableById(theme)?.let { t ->
            val p = t.palette(darkMode)
            val colors = if (p.bgGradEnd != 0) intArrayOf(p.bg, p.bgGradEnd) else intArrayOf(p.bg, p.bg)
            if (t.id == THREE_D_GLASS) {
                val radius = dp(context, if (t.boxed) 18 else 12).toFloat()
                val plate = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
                    cornerRadius = radius
                    setStroke(dp(context, 1), if (darkMode) 0x44FFFFFF else 0x78FFFFFF)
                }
                val frost = GradientDrawable(GradientDrawable.Orientation.TL_BR, if (darkMode) {
                    intArrayOf(0x24FFFFFF, 0x0AFFFFFF, 0x52000000)
                } else {
                    intArrayOf(0xB8FFFFFF.toInt(), 0x55FFFFFF, 0x2689A7D0)
                }).apply { cornerRadius = radius }
                val rim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (darkMode) {
                    intArrayOf(0x34FFFFFF, 0x00FFFFFF, 0x66000000)
                } else {
                    intArrayOf(0xE0FFFFFF.toInt(), 0x22FFFFFF, 0x14000000)
                }).apply { cornerRadius = radius }
                return LayerDrawable(arrayOf(plate, frost, rim))
            }
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
            if (t.id == THREE_D_DEPTH) return depthKeyLayer(context, t, label, pressed, darkMode, goColor)
            if (t.id == THREE_D_GLASS) return glassKeyLayer(context, t, label, pressed, darkMode, goColor)
            t.depthStyle?.let { return depthVariantKeyLayer(context, t, label, pressed, darkMode, goColor) }
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
            t.depthStyle?.let { depth ->
                return when {
                    isGo -> depth.enterText.takeIf { it != 0 }
                        ?: if (luminance(accent) > 0.55f) 0xFF101116.toInt() else 0xFFFFFFFF.toInt()
                    isFn -> depth.functionText.takeIf { it != 0 } ?: p.keyText
                    else -> p.keyText
                }
            }
            if (t.id == THREE_D_GLASS) {
                return when {
                    isGo -> if (luminance(accent) > 0.55f) 0xFF0A0D12.toInt() else 0xFFFFFFFF.toInt()
                    isFn -> if (darkMode) 0xFFEAF2FF.toInt() else 0xFF172033.toInt()
                    else -> p.keyText
                }
            }
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

    fun isThreeDTheme(theme: String): Boolean =
        KbThemes.canonicalId(theme) == THREE_D_DEPTH || KbThemes.canonicalId(theme) == THREE_D_GLASS

    fun isDepthVariant(theme: String): Boolean =
        KbThemes.renderableById(theme)?.depthStyle != null

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

    private fun depthVariantKeyLayer(
        context: Context,
        theme: KbTheme,
        label: String,
        pressed: Boolean,
        darkMode: Boolean,
        goColor: Int
    ): LayerDrawable {
        val depth = theme.depthStyle ?: return depthKeyLayer(context, theme, label, pressed, darkMode, goColor)
        val p = theme.palette(darkMode)
        val accent = theme.accent(goColor, p)
        val isGo = label == "enter"
        val isFn = label == "123" || label == "abc" || label == "shift" || label == "back" || label == "." || label == "period" || label == "teclas"
        val base = when {
            isGo -> accent
            isFn -> p.functionKey
            else -> p.key
        }
        val end = when {
            isGo -> darken(accent)
            isFn -> p.keyGradEnd.takeIf { it != 0 } ?: darken(p.functionKey)
            p.keyGradEnd != 0 -> p.keyGradEnd
            else -> darken(p.key)
        }
        val radius = dp(context, theme.radiusDp).toFloat()
        if (depth.hardShadow) return brutalDepthKeyLayer(context, depth, base, end, radius, pressed)

        val wall = when {
            isGo -> depth.accentWall.takeIf { it != 0 } ?: darken(accent)
            isFn -> depth.functionWall.takeIf { it != 0 } ?: depth.wall
            else -> depth.wall
        }
        val wallMid = depth.wallMid.takeIf { it != 0 } ?: adjust(wall, 0.86f)
        val wallDark = depth.wallDark.takeIf { it != 0 } ?: adjust(wall, 0.70f)
        val faceTop = if (pressed) adjust(base, 0.92f) else brighten(base)
        val faceMid = if (pressed) adjust(base, 0.88f) else base
        val faceBottom = if (pressed) adjust(end, 0.86f) else end
        val rise = dp(context, depth.riseDp.coerceAtLeast(1))
        val collapsed = dp(context, 1)
        val throwTop = if (pressed) rise else 0
        val bottomWall = if (pressed) collapsed else rise

        val ambient = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (pressed) adjustAlpha(depth.shadow, 0.48f) else depth.glow.takeIf { it != 0 } ?: depth.shadow)
        }
        val wallBack = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (pressed) adjust(wallDark, 0.78f) else wallDark)
        }
        val wallMidShape = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (pressed) adjust(wallMid, 0.78f) else wallMid)
        }
        val wallFront = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (pressed) adjust(wall, 0.78f) else wall)
        }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(faceTop, faceMid, faceBottom)).apply {
            cornerRadius = radius
            setStroke(dp(context, 1), depth.stroke)
        }
        val rim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(depth.rim, 0x00FFFFFF)).apply {
            cornerRadius = radius
        }
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (depth.glossy) depth.sheen else adjustAlpha(depth.sheen, if (pressed) 0.56f else 1f),
            if (depth.glossy) adjustAlpha(depth.sheen, 0.38f) else 0x00FFFFFF,
            0x00FFFFFF
        )).apply { cornerRadius = radius }

        return LayerDrawable(arrayOf(ambient, wallBack, wallMidShape, wallFront, face, rim, sheen)).apply {
            val riseThird = (rise / 3).coerceAtLeast(dp(context, 1))
            val riseTwoThird = (rise * 2 / 3).coerceAtLeast(dp(context, 2))
            setLayerInset(0, dp(context, 2), if (pressed) dp(context, 3) else rise + dp(context, 5), dp(context, 2), 0)
            setLayerInset(1, dp(context, 1), if (pressed) collapsed else rise + dp(context, 2), dp(context, 1), 0)
            setLayerInset(2, dp(context, 1), if (pressed) collapsed else riseTwoThird, dp(context, 1), dp(context, 1))
            setLayerInset(3, dp(context, 1), if (pressed) 0 else riseThird, dp(context, 1), dp(context, 2))
            setLayerInset(4, 0, throwTop, 0, bottomWall)
            setLayerInset(5, dp(context, 3), throwTop + dp(context, 1), dp(context, 3), bottomWall + dp(context, 18))
            setLayerInset(6, dp(context, 6), throwTop + dp(context, if (depth.glossy) 2 else 4), dp(context, 6), bottomWall + dp(context, if (depth.glossy) 22 else 16))
        }
    }

    private fun brutalDepthKeyLayer(
        context: Context,
        depth: KbDepthStyle,
        base: Int,
        end: Int,
        radius: Float,
        pressed: Boolean
    ): LayerDrawable {
        val shadow = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (pressed) Color.TRANSPARENT else depth.shadow)
        }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(base, end)).apply {
            cornerRadius = radius
            setStroke(dp(context, 2), depth.stroke)
        }
        return LayerDrawable(arrayOf(shadow, face)).apply {
            val x = dp(context, if (pressed) 0 else 4)
            val y = dp(context, if (pressed) 0 else depth.riseDp)
            val press = dp(context, if (pressed) depth.riseDp else 0)
            setLayerInset(0, x, y, 0, 0)
            setLayerInset(1, 0, press, x, y)
        }
    }

    private fun depthKeyLayer(
        context: Context,
        theme: KbTheme,
        label: String,
        pressed: Boolean,
        darkMode: Boolean,
        goColor: Int
    ): LayerDrawable {
        val p = theme.palette(darkMode)
        val accent = theme.accent(goColor, p)
        val isGo = label == "enter"
        val isFn = label == "123" || label == "abc" || label == "shift" || label == "back" || label == "." || label == "period" || label == "teclas"
        val base = when {
            isGo -> accent
            isFn -> p.functionKey
            else -> p.key
        }
        val end = when {
            isGo -> darken(accent)
            isFn -> darken(p.functionKey)
            p.keyGradEnd != 0 -> p.keyGradEnd
            else -> darken(p.key)
        }
        val faceTop = if (pressed) darken(base) else brighten(base)
        val faceBottom = if (pressed) darken(end) else end
        val radius = dp(context, theme.radiusDp).toFloat()
        val wallDrop = dp(context, if (pressed) 1 else 6)
        val ambient = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (darkMode) 0x78000000 else 0x33000000)
        }
        val wall3 = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (darkMode) 0xFF020305.toInt() else 0xFF8792A1.toInt())
        }
        val wall2 = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (darkMode) 0xFF070A10.toInt() else 0xFFA0AAB8.toInt())
        }
        val wall1 = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (darkMode) 0xFF0D1118.toInt() else 0xFFB7C0CC.toInt())
        }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(faceTop, base, faceBottom)).apply {
            cornerRadius = radius
            setStroke(dp(context, 1), if (darkMode) 0x66000000 else 0x55FFFFFF)
        }
        val rim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (darkMode) 0x36FFFFFF else 0x99FFFFFF.toInt(),
            0x00FFFFFF
        )).apply { cornerRadius = radius }
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (darkMode) 0x16FFFFFF else 0x55FFFFFF,
            0x00FFFFFF
        )).apply { cornerRadius = radius }
        return LayerDrawable(arrayOf(ambient, wall3, wall2, wall1, face, rim, sheen)).apply {
            val ambientTop = if (pressed) dp(context, 3) else dp(context, 10)
            setLayerInset(0, dp(context, 2), ambientTop, dp(context, 2), 0)
            setLayerInset(1, dp(context, 1), if (pressed) dp(context, 2) else dp(context, 8), dp(context, 1), 0)
            setLayerInset(2, dp(context, 1), if (pressed) dp(context, 1) else dp(context, 6), dp(context, 1), dp(context, 1))
            setLayerInset(3, dp(context, 1), if (pressed) 0 else dp(context, 4), dp(context, 1), dp(context, 2))
            setLayerInset(4, 0, if (pressed) dp(context, 4) else 0, 0, wallDrop)
            setLayerInset(5, dp(context, 3), if (pressed) dp(context, 5) else dp(context, 1), dp(context, 3), dp(context, 26))
            setLayerInset(6, dp(context, 6), if (pressed) dp(context, 8) else dp(context, 4), dp(context, 6), dp(context, 18))
        }
    }

    private fun glassKeyLayer(
        context: Context,
        theme: KbTheme,
        label: String,
        pressed: Boolean,
        darkMode: Boolean,
        goColor: Int
    ): LayerDrawable {
        val p = theme.palette(darkMode)
        val accent = theme.accent(goColor, p)
        val isGo = label == "enter"
        val isFn = label == "123" || label == "abc" || label == "shift" || label == "back" || label == "." || label == "period" || label == "teclas"
        val base = when {
            isGo -> withAlpha(accent, if (pressed) 0.92f else 0.80f)
            isFn -> p.functionKey
            else -> p.key
        }
        val end = when {
            isGo -> withAlpha(darken(accent), if (pressed) 0.86f else 0.66f)
            isFn -> withAlpha(p.keyGradEnd.takeIf { it != 0 } ?: p.key, if (darkMode) 0.84f else 0.72f)
            else -> p.keyGradEnd.takeIf { it != 0 } ?: withAlpha(p.key, 0.40f)
        }
        val radius = dp(context, theme.radiusDp).toFloat()
        val shadow = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (darkMode) 0x8A000000.toInt() else 0x33000000)
        }
        val frost = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (pressed) brighten(base) else withAlpha(Color.WHITE, if (darkMode) 0.30f else 0.82f),
            base,
            end
        )).apply {
            cornerRadius = radius
            setStroke(dp(context, 1), if (darkMode) 0x66FFFFFF else 0xB8FFFFFF.toInt())
        }
        val topRim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (darkMode) 0x8CFFFFFF.toInt() else 0xE8FFFFFF.toInt(),
            0x00FFFFFF
        )).apply { cornerRadius = radius }
        val diagonal = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            0x00FFFFFF,
            if (pressed) 0x52FFFFFF else 0x38FFFFFF,
            0x00FFFFFF
        )).apply { cornerRadius = radius }
        return LayerDrawable(arrayOf(shadow, frost, topRim, diagonal)).apply {
            val drop = if (pressed) dp(context, 1) else dp(context, 3)
            setLayerInset(0, dp(context, 1), dp(context, if (pressed) 2 else 5), dp(context, 1), 0)
            setLayerInset(1, 0, if (pressed) dp(context, 2) else 0, 0, drop)
            setLayerInset(2, dp(context, 4), if (pressed) dp(context, 3) else dp(context, 1), dp(context, 4), dp(context, 24))
            setLayerInset(3, dp(context, 5), if (pressed) dp(context, 5) else dp(context, 3), dp(context, 5), dp(context, 10))
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (255 * alpha).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun adjustAlpha(color: Int, alphaScale: Float): Int = Color.argb(
        (Color.alpha(color) * alphaScale).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

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
