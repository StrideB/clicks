package com.fran.teclas

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface

data class KbPalette(
    val bg: Int,
    val bgGradEnd: Int = 0,
    val key: Int,
    val keyGradEnd: Int = 0,
    val keyText: Int,
    val functionKey: Int,
    val accent: Int,
)

data class KbTheme(
    val id: String,
    val displayName: String,
    val boxed: Boolean,
    val radiusDp: Int,
    val light: KbPalette,
    val dark: KbPalette,
    val shadow: Boolean = false,
    val bold: Boolean = false,
    val mono: Boolean = false,
    val serif: Boolean = false,
    val glow: Boolean = false,
    val accentIsGoKeyColor: Boolean = false,
) {
    fun palette(darkMode: Boolean): KbPalette = if (darkMode) dark else light

    fun palette(ctx: Context): KbPalette =
        palette(ctx.resolveTeclasNeuTokens(ctx.getSharedPreferences("teclas", Context.MODE_PRIVATE).getString(TECLAS_THEME_MODE_PREF, TECLAS_THEME_MODE_SYSTEM)).mode == NeuMode.DARK)

    fun accent(ctx: Context, p: KbPalette): Int =
        if (accentIsGoKeyColor) goKeyColorOrDefault(ctx, CURSOR_VIOLET) else p.accent

    fun accent(goKeyColor: Int, p: KbPalette): Int =
        if (accentIsGoKeyColor) goKeyColor.takeIf { it != 0 } ?: CURSOR_VIOLET else p.accent

    fun typeface(label: String): Typeface = when {
        mono -> Typeface.create(Typeface.MONOSPACE, if (bold || label == "enter") Typeface.BOLD else Typeface.NORMAL)
        serif -> Typeface.create(Typeface.SERIF, if (bold || label == "enter") Typeface.BOLD else Typeface.NORMAL)
        bold || label == "enter" -> Typeface.DEFAULT_BOLD
        else -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    companion object { val CURSOR_VIOLET = 0xFFC9A7FF.toInt() }
}

private fun goKeyColorOrDefault(ctx: Context, default: Int): Int =
    ctx.getSharedPreferences("teclas", Context.MODE_PRIVATE).getInt("go_key_color", default)

object KbThemes {
    val SANS = KbTheme("sans", "Sans", boxed = true, radiusDp = 7, shadow = true,
        light = KbPalette(bg = 0xFFECEFF3.toInt(), key = 0xFFFFFFFF.toInt(),
            keyText = 0xFF20242B.toInt(), functionKey = 0xFFDFE3E8.toInt(), accent = 0xFF1A73E8.toInt()),
        dark = KbPalette(bg = 0xFF202124.toInt(), key = 0xFF3C4043.toInt(),
            keyText = 0xFFE8EAED.toInt(), functionKey = 0xFF2A2C2F.toInt(), accent = 0xFF8AB4F8.toInt()))

    val UNI = KbTheme("uni", "Uni", boxed = true, radiusDp = 8,
        light = KbPalette(bg = 0xFFF0F2F5.toInt(), key = 0xFFFFFFFF.toInt(),
            keyText = 0xFF1B1C1E.toInt(), functionKey = 0xFFE2E6EC.toInt(), accent = 0xFF1C6FD6.toInt()),
        dark = KbPalette(bg = 0xFF1A1C1F.toInt(), key = 0xFF2C2F34.toInt(),
            keyText = 0xFFF2F4F7.toInt(), functionKey = 0xFF232529.toInt(), accent = 0xFF4A9EFF.toInt()))

    val CUPER = KbTheme("cuper", "Cuper", boxed = true, radiusDp = 6, shadow = true,
        light = KbPalette(bg = 0xFFD1D4DB.toInt(), key = 0xFFFFFFFF.toInt(),
            keyText = 0xFF0B0B0C.toInt(), functionKey = 0xFFAEB3BD.toInt(), accent = 0xFF0A84FF.toInt()),
        dark = KbPalette(bg = 0xFF0B0B0D.toInt(), key = 0xFF4B4D52.toInt(),
            keyText = 0xFFFFFFFF.toInt(), functionKey = 0xFF2C2E33.toInt(), accent = 0xFF0A84FF.toInt()))

    val SAND = KbTheme("sand", "Sand", boxed = true, radiusDp = 9,
        light = KbPalette(bg = 0xFFEFE6DC.toInt(), key = 0xFFFBF6F0.toInt(),
            keyText = 0xFF3B3128.toInt(), functionKey = 0xFFE3D7C8.toInt(), accent = 0xFFE8703A.toInt()),
        dark = KbPalette(bg = 0xFF2A2018.toInt(), key = 0xFF3A2E23.toInt(),
            keyText = 0xFFF3E7D8.toInt(), functionKey = 0xFF241C15.toInt(), accent = 0xFFE8703A.toInt()))

    val TECLAS_GLASS = KbTheme("teclas_glass", "Teclas Glass", boxed = true, radiusDp = 11,
        shadow = true, accentIsGoKeyColor = true,
        dark = KbPalette(bg = 0xFF20232A.toInt(), bgGradEnd = 0xFF14161B.toInt(),
            key = 0xFF2A2E37.toInt(), keyGradEnd = 0xFF191C22.toInt(),
            keyText = 0xFFEEF1F6.toInt(), functionKey = 0xFF1C1F26.toInt(), accent = KbTheme.CURSOR_VIOLET),
        light = KbPalette(bg = 0xFFEDEFF3.toInt(), key = 0xFFFFFFFF.toInt(),
            keyText = 0xFF1A1D23.toInt(), functionKey = 0xFFE2E5EC.toInt(), accent = KbTheme.CURSOR_VIOLET))

    val SKEUO = KbTheme("skeuo", "Skeuo", boxed = true, radiusDp = 9, shadow = true,
        dark = KbPalette(bg = 0xFF1B1712.toInt(), key = 0xFF40382E.toInt(), keyGradEnd = 0xFF2A241D.toInt(),
            keyText = 0xFFF4EAD6.toInt(), functionKey = 0xFF241F18.toInt(), accent = 0xFFE0A94F.toInt()),
        light = KbPalette(bg = 0xFFE8E0D2.toInt(), key = 0xFFFBF4E6.toInt(), keyGradEnd = 0xFFEBE0CC.toInt(),
            keyText = 0xFF3A2E1C.toInt(), functionKey = 0xFFDED2BC.toInt(), accent = 0xFFC8862F.toInt()))

    val NEON_ARCADE = KbTheme("neon_arcade", "Neon Arcade", boxed = true, radiusDp = 8, glow = true, bold = true,
        dark = KbPalette(bg = 0xFF07040F.toInt(), key = 0xFF2A0A3A.toInt(), keyGradEnd = 0xFF150522.toInt(),
            keyText = 0xFFFF7BE6.toInt(), functionKey = 0xFF12071E.toInt(), accent = 0xFF00EAFF.toInt()),
        light = KbPalette(bg = 0xFFF3E9FF.toInt(), key = 0xFFFFFFFF.toInt(),
            keyText = 0xFF7A1E6B.toInt(), functionKey = 0xFFE7D6F5.toInt(), accent = 0xFF00B4C6.toInt()))

    val MATCHA = KbTheme("matcha", "Matcha", boxed = true, radiusDp = 14,
        light = KbPalette(bg = 0xFFE9ECDD.toInt(), key = 0xFFF6F8EE.toInt(),
            keyText = 0xFF3A4230.toInt(), functionKey = 0xFFDBE0C9.toInt(), accent = 0xFF7A9A5A.toInt()),
        dark = KbPalette(bg = 0xFF171A14.toInt(), key = 0xFF232A1F.toInt(),
            keyText = 0xFFE7EEDA.toInt(), functionKey = 0xFF1B211A.toInt(), accent = 0xFF8FB56A.toInt()))

    val BUBBLEGUM = KbTheme("bubblegum", "Bubblegum", boxed = true, radiusDp = 16, bold = true,
        light = KbPalette(bg = 0xFFFFE3F1.toInt(), key = 0xFFFF8FC4.toInt(),
            keyText = 0xFF5A1F3F.toInt(), functionKey = 0xFFFFC2DF.toInt(), accent = 0xFF3DD0B8.toInt()),
        dark = KbPalette(bg = 0xFF2A1420.toInt(), key = 0xFFC25E8E.toInt(),
            keyText = 0xFFFFEAF4.toInt(), functionKey = 0xFF221019.toInt(), accent = 0xFF3DD0B8.toInt()))

    val CARBON = KbTheme("carbon", "Carbon", boxed = true, radiusDp = 6, shadow = true,
        dark = KbPalette(bg = 0xFF161719.toInt(), key = 0xFF2B2D31.toInt(), keyGradEnd = 0xFF202225.toInt(),
            keyText = 0xFFE6E8EC.toInt(), functionKey = 0xFF1D1F22.toInt(), accent = 0xFFFF4436.toInt()),
        light = KbPalette(bg = 0xFFEDEEF0.toInt(), key = 0xFFFFFFFF.toInt(),
            keyText = 0xFF1A1C1E.toInt(), functionKey = 0xFFE0E2E6.toInt(), accent = 0xFFFF4436.toInt()))

    val SOLARIS = KbTheme("solaris", "Solaris", boxed = true, radiusDp = 10, bold = true,
        dark = KbPalette(bg = 0xFF1A0D06.toInt(), key = 0xFFFF8A3D.toInt(), keyGradEnd = 0xFFC94F1A.toInt(),
            keyText = 0xFFFFF3E6.toInt(), functionKey = 0xFF2A1409.toInt(), accent = 0xFFFFD166.toInt()),
        light = KbPalette(bg = 0xFFFFF1E6.toInt(), key = 0xFFFF9A52.toInt(), keyGradEnd = 0xFFE0641F.toInt(),
            keyText = 0xFFFFF6EC.toInt(), functionKey = 0xFFF3DEC8.toInt(), accent = 0xFFC9541A.toInt()))

    val VAPORWAVE = KbTheme("vaporwave", "Vaporwave", boxed = true, radiusDp = 9, glow = true,
        dark = KbPalette(bg = 0xFF2B1055.toInt(), bgGradEnd = 0xFF7597DE.toInt(),
            key = 0xFFFF6EC7.toInt(), keyGradEnd = 0xFF8A5CFF.toInt(),
            keyText = 0xFFFFFFFF.toInt(), functionKey = 0xFF3A2170.toInt(), accent = 0xFF00F0FF.toInt()),
        light = KbPalette(bg = 0xFFFFE1F2.toInt(), bgGradEnd = 0xFFCFE0FF.toInt(),
            key = 0xFFFF8FD4.toInt(), keyGradEnd = 0xFFA98CFF.toInt(),
            keyText = 0xFF3A1B52.toInt(), functionKey = 0xFFE9D6FF.toInt(), accent = 0xFF00B8CC.toInt()))

    val OCEANIC = KbTheme("oceanic", "Oceanic", boxed = true, radiusDp = 12, shadow = true,
        dark = KbPalette(bg = 0xFF04252E.toInt(), bgGradEnd = 0xFF062F3D.toInt(),
            key = 0xFF0D4152.toInt(), keyGradEnd = 0xFF082E3A.toInt(),
            keyText = 0xFFD6F7FF.toInt(), functionKey = 0xFF062832.toInt(), accent = 0xFF3FF0C8.toInt()),
        light = KbPalette(bg = 0xFFE2F4F5.toInt(), key = 0xFFFFFFFF.toInt(),
            keyText = 0xFF103B42.toInt(), functionKey = 0xFFCDE8E9.toInt(), accent = 0xFF1F9F86.toInt()))

    val OBSIDIAN = KbTheme("obsidian", "Obsidian", boxed = true, radiusDp = 5, shadow = true,
        dark = KbPalette(bg = 0xFF080809.toInt(), key = 0xFF1C1C22.toInt(), keyGradEnd = 0xFF0E0E12.toInt(),
            keyText = 0xFFE9E6DD.toInt(), functionKey = 0xFF111114.toInt(), accent = 0xFFD9B16A.toInt()),
        light = KbPalette(bg = 0xFFEDEBE5.toInt(), key = 0xFFFFFFFF.toInt(),
            keyText = 0xFF1A1A1C.toInt(), functionKey = 0xFFE0DDD4.toInt(), accent = 0xFFB98F3F.toInt()))

    val CITRUS = KbTheme("citrus", "Citrus", boxed = true, radiusDp = 10, bold = true,
        light = KbPalette(bg = 0xFFF7FBE9.toInt(), key = 0xFFC8E65A.toInt(),
            keyText = 0xFF33420D.toInt(), functionKey = 0xFFE4F0B0.toInt(), accent = 0xFFFF7A1A.toInt()),
        dark = KbPalette(bg = 0xFF17190C.toInt(), key = 0xFF9CB63E.toInt(),
            keyText = 0xFF1E2606.toInt(), functionKey = 0xFF1C1F10.toInt(), accent = 0xFFFF7A1A.toInt()))

    val TERRACOTTA = KbTheme("terracotta", "Terracotta", boxed = true, radiusDp = 12,
        light = KbPalette(bg = 0xFFEFE0D2.toInt(), key = 0xFFC8734A.toInt(),
            keyText = 0xFFFFF2E8.toInt(), functionKey = 0xFFE0CBB6.toInt(), accent = 0xFF3F6F5F.toInt()),
        dark = KbPalette(bg = 0xFF211710.toInt(), key = 0xFFA85B37.toInt(),
            keyText = 0xFFFFEEE2.toInt(), functionKey = 0xFF1A120C.toInt(), accent = 0xFF57907D.toInt()))

    val RISO_POP = KbTheme("riso_pop", "Riso Pop", boxed = true, radiusDp = 5, bold = true,
        light = KbPalette(bg = 0xFFF3EDE1.toInt(), key = 0xFFFF5A4D.toInt(),
            keyText = 0xFF1A1A1A.toInt(), functionKey = 0xFFFFD23F.toInt(), accent = 0xFF2F5FE0.toInt()),
        dark = KbPalette(bg = 0xFF17130E.toInt(), key = 0xFFE04A3F.toInt(),
            keyText = 0xFFFFF3E8.toInt(), functionKey = 0xFFC9A233.toInt(), accent = 0xFF6E92FF.toInt()))

    val TERMINAL = KbTheme("terminal", "Terminal", boxed = false, radiusDp = 0, mono = true, glow = true,
        dark = KbPalette(bg = 0xFF04100A.toInt(), key = 0, keyText = 0xFF3BFFA0.toInt(), functionKey = 0, accent = 0xFF3BFFA0.toInt()),
        light = KbPalette(bg = 0xFFEAF4EE.toInt(), key = 0, keyText = 0xFF0B5A38.toInt(), functionKey = 0, accent = 0xFF0B7A48.toInt()))

    val BLUEPRINT = KbTheme("blueprint", "Blueprint", boxed = false, radiusDp = 0, mono = true,
        dark = KbPalette(bg = 0xFF0D2B52.toInt(), key = 0, keyText = 0xFFBFE3FF.toInt(), functionKey = 0, accent = 0xFF4FD1FF.toInt()),
        light = KbPalette(bg = 0xFFE7EFFA.toInt(), key = 0, keyText = 0xFF123A6B.toInt(), functionKey = 0, accent = 0xFF1C6FD6.toInt()))

    val NOIR = KbTheme("noir", "Noir", boxed = false, radiusDp = 0, serif = true,
        dark = KbPalette(bg = 0xFF0A0A0A.toInt(), key = 0, keyText = 0xFFF4F4F4.toInt(), functionKey = 0, accent = 0xFFD4AF37.toInt()),
        light = KbPalette(bg = 0xFFF2F1EE.toInt(), key = 0, keyText = 0xFF141414.toInt(), functionKey = 0, accent = 0xFFA8862A.toInt()))

    val AURORA = KbTheme("aurora", "Aurora", boxed = false, radiusDp = 0, glow = true,
        dark = KbPalette(bg = 0xFF0D2436.toInt(), bgGradEnd = 0xFF1A2F4D.toInt(), key = 0, keyText = 0xFFEAFFF6.toInt(), functionKey = 0, accent = 0xFF7CFFCB.toInt()),
        light = KbPalette(bg = 0xFFE6FBF3.toInt(), bgGradEnd = 0xFFDFF3F0.toInt(), key = 0, keyText = 0xFF12433A.toInt(), functionKey = 0, accent = 0xFF1FA588.toInt()))

    val SAKURA = KbTheme("sakura", "Sakura", boxed = false, radiusDp = 0,
        light = KbPalette(bg = 0xFFFFE9F0.toInt(), bgGradEnd = 0xFFFFD9E4.toInt(), key = 0, keyText = 0xFF6B2F47.toInt(), functionKey = 0, accent = 0xFFFF6F91.toInt()),
        dark = KbPalette(bg = 0xFF241119.toInt(), bgGradEnd = 0xFF1A0D12.toInt(), key = 0, keyText = 0xFFFBE0E8.toInt(), functionKey = 0, accent = 0xFFFF6F91.toInt()))

    val MARKER = KbTheme("marker", "Marker", boxed = false, radiusDp = 0, bold = true,
        light = KbPalette(bg = 0xFFFFFBEA.toInt(), key = 0, keyText = 0xFF222222.toInt(), functionKey = 0, accent = 0xFFFF5252.toInt()),
        dark = KbPalette(bg = 0xFF14130E.toInt(), key = 0, keyText = 0xFFF4F1E2.toInt(), functionKey = 0, accent = 0xFFFF5252.toInt()))

    val COSMOS = KbTheme("cosmos", "Cosmos", boxed = false, radiusDp = 0, glow = true,
        dark = KbPalette(bg = 0xFF1B1B4D.toInt(), bgGradEnd = 0xFF05050F.toInt(), key = 0, keyText = 0xFFEAE6FF.toInt(), functionKey = 0, accent = 0xFFB39DFF.toInt()),
        light = KbPalette(bg = 0xFFEAE7FF.toInt(), bgGradEnd = 0xFFDAD6F5.toInt(), key = 0, keyText = 0xFF2B2860.toInt(), functionKey = 0, accent = 0xFF7A5CE0.toInt()))

    val E_INK = KbTheme("e_ink", "E-Ink", boxed = false, radiusDp = 0,
        light = KbPalette(bg = 0xFFE8E6E1.toInt(), key = 0, keyText = 0xFF1C1B19.toInt(), functionKey = 0, accent = 0xFF1C1B19.toInt()),
        dark = KbPalette(bg = 0xFF141412.toInt(), key = 0, keyText = 0xFFE8E6E1.toInt(), functionKey = 0, accent = 0xFFE8E6E1.toInt()))

    val ALL: List<KbTheme> = listOf(
        SANS, UNI, CUPER, SAND, TECLAS_GLASS, SKEUO, NEON_ARCADE, MATCHA, BUBBLEGUM,
        CARBON, SOLARIS, VAPORWAVE, OCEANIC, OBSIDIAN, CITRUS, TERRACOTTA, RISO_POP,
        TERMINAL, BLUEPRINT, NOIR, AURORA, SAKURA, MARKER, COSMOS, E_INK,
    )

    val RENDERABLE: List<KbTheme> = ALL.filterNot { it.id == "skeuo" }

    fun byId(id: String): KbTheme? = ALL.firstOrNull { it.id == canonicalId(id) }

    fun renderableById(id: String): KbTheme? = RENDERABLE.firstOrNull { it.id == canonicalId(id) }

    fun canonicalId(id: String): String = when (id) {
        "google" -> "sans"
        "ios" -> "cuper"
        "pixel_sand" -> "sand"
        else -> id
    }
}
