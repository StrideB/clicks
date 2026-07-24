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

data class KbDepthStyle(
    val riseDp: Int,
    val wall: Int,
    val wallMid: Int = 0,
    val wallDark: Int = 0,
    val functionWall: Int = 0,
    val accentWall: Int = 0,
    val shadow: Int,
    val stroke: Int,
    val rim: Int,
    val sheen: Int = 0x00FFFFFF,
    val glow: Int = 0,
    val glossy: Boolean = false,
    val hardShadow: Boolean = false,
    val functionText: Int = 0,
    val enterText: Int = 0,
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
    val depthStyle: KbDepthStyle? = null,
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
    const val THREE_D_DEPTH_ID = "3ddepth"
    const val THREE_D_GLASS_ID = "3dglass"
    const val THREE_D_DEPTH_CLASSIC_ID = "3ddepth_classic"
    const val THREE_D_DEPTH_SLATE_ID = "3ddepth_slate"
    const val THREE_D_DEPTH_CHICLET_ID = "3ddepth_chiclet"
    const val THREE_D_DEPTH_PILL_ID = "3ddepth_pill"
    const val THREE_D_DEPTH_NEON_ID = "3ddepth_neon"
    const val THREE_D_DEPTH_WOOD_ID = "3ddepth_wood"
    const val THREE_D_DEPTH_CANDY_ID = "3ddepth_candy"
    const val THREE_D_DEPTH_MECH_ID = "3ddepth_mech"
    const val THREE_D_DEPTH_MINT_ID = "3ddepth_mint"
    const val THREE_D_DEPTH_MONO_ID = "3ddepth_mono"

    val SANS = KbTheme("sans", "Sans", boxed = true, radiusDp = 7, shadow = true,
        light = KbPalette(bg = 0xFFECEFF3.toInt(), key = 0xFFFFFFFF.toInt(),
            keyText = 0xFF20242B.toInt(), functionKey = 0xFFDFE3E8.toInt(), accent = 0xFF1A73E8.toInt()),
        dark = KbPalette(bg = 0xFF202124.toInt(), key = 0xFF3C4043.toInt(),
            keyText = 0xFFE8EAED.toInt(), functionKey = 0xFF2A2C2F.toInt(), accent = 0xFF8AB4F8.toInt()))

    val THREE_D_DEPTH = KbTheme(THREE_D_DEPTH_ID, "3D Depth", boxed = true, radiusDp = 11, shadow = true,
        accentIsGoKeyColor = true,
        light = KbPalette(bg = 0xFFE5E9F0.toInt(), bgGradEnd = 0xFFC5CEDA.toInt(),
            key = 0xFFFBFCFF.toInt(), keyGradEnd = 0xFFDCE4EE.toInt(),
            keyText = 0xFF182230.toInt(), functionKey = 0xFFD0D8E4.toInt(), accent = 0xFF4D7DFF.toInt()),
        dark = KbPalette(bg = 0xFF171A22.toInt(), bgGradEnd = 0xFF05070A.toInt(),
            key = 0xFF343A45.toInt(), keyGradEnd = 0xFF141820.toInt(),
            keyText = 0xFFF2F5FA.toInt(), functionKey = 0xFF1A1F29.toInt(), accent = 0xFF7EA2FF.toInt()))

    val THREE_D_GLASS = KbTheme(THREE_D_GLASS_ID, "3D Glass", boxed = true, radiusDp = 12, shadow = true,
        accentIsGoKeyColor = true,
        light = KbPalette(bg = 0xCCF6FAFF.toInt(), bgGradEnd = 0xB8DDEBFA.toInt(),
            key = 0xEAFFFFFF.toInt(), keyGradEnd = 0xC8DCEBFA.toInt(),
            keyText = 0xFF111827.toInt(), functionKey = 0xD8F4F9FF.toInt(), accent = 0xFF6F90FF.toInt()),
        dark = KbPalette(bg = 0xD10B1018.toInt(), bgGradEnd = 0xE005070B.toInt(),
            key = 0xCC151B25.toInt(), keyGradEnd = 0xE0070B12.toInt(),
            keyText = 0xFFFBFDFF.toInt(), functionKey = 0xB91C2430.toInt(), accent = 0xFFAFC8FF.toInt()))

    val THREE_D_DEPTH_CLASSIC = KbTheme(THREE_D_DEPTH_CLASSIC_ID, "Classic Raised", boxed = true, radiusDp = 9, shadow = true,
        light = KbPalette(bg = 0xFFE6E9EF.toInt(), bgGradEnd = 0xFFD5DAE2.toInt(),
            key = 0xFFFFFFFF.toInt(), keyGradEnd = 0xFFE4E8EE.toInt(),
            keyText = 0xFF28303C.toInt(), functionKey = 0xFFC9D0DA.toInt(), accent = 0xFFFF5A3C.toInt()),
        dark = KbPalette(bg = 0xFFE6E9EF.toInt(), bgGradEnd = 0xFFD5DAE2.toInt(),
            key = 0xFFFFFFFF.toInt(), keyGradEnd = 0xFFE4E8EE.toInt(),
            keyText = 0xFF28303C.toInt(), functionKey = 0xFFC9D0DA.toInt(), accent = 0xFFFF5A3C.toInt()),
        depthStyle = KbDepthStyle(
            riseDp = 6, wall = 0xFFB9C0CC.toInt(), wallMid = 0xFFA7B0BE.toInt(), wallDark = 0xFF8792A1.toInt(),
            functionWall = 0xFF9AA3B2.toInt(), accentWall = 0xFFC9391F.toInt(), shadow = 0x52465064,
            stroke = 0xFFFFFFFF.toInt(), rim = 0xD9FFFFFF.toInt(), sheen = 0x28FFFFFF, enterText = 0xFFFFFFFF.toInt()
        ))

    val THREE_D_DEPTH_SLATE = KbTheme(THREE_D_DEPTH_SLATE_ID, "Dark Slate", boxed = true, radiusDp = 9, shadow = true,
        light = KbPalette(bg = 0xFF2A2F3A.toInt(), bgGradEnd = 0xFF1E222B.toInt(),
            key = 0xFF454C5A.toInt(), keyGradEnd = 0xFF333A47.toInt(),
            keyText = 0xFFEEF1F7.toInt(), functionKey = 0xFF3A4150.toInt(), accent = 0xFF7C5CFF.toInt()),
        dark = KbPalette(bg = 0xFF2A2F3A.toInt(), bgGradEnd = 0xFF1E222B.toInt(),
            key = 0xFF454C5A.toInt(), keyGradEnd = 0xFF333A47.toInt(),
            keyText = 0xFFEEF1F7.toInt(), functionKey = 0xFF3A4150.toInt(), accent = 0xFF7C5CFF.toInt()),
        depthStyle = KbDepthStyle(
            riseDp = 6, wall = 0xFF14171D.toInt(), wallMid = 0xFF1A1E26.toInt(), wallDark = 0xFF090B10.toInt(),
            functionWall = 0xFF11141A.toInt(), accentWall = 0xFF3D1F9E.toInt(), shadow = 0x8A000000.toInt(),
            stroke = 0x24FFFFFF, rim = 0x24FFFFFF, sheen = 0x12FFFFFF, enterText = 0xFFFFFFFF.toInt()
        ))

    val THREE_D_DEPTH_CHICLET = KbTheme(THREE_D_DEPTH_CHICLET_ID, "Chiclet", boxed = true, radiusDp = 7, shadow = true,
        light = KbPalette(bg = 0xFFCDD2DA.toInt(), bgGradEnd = 0xFFCDD2DA.toInt(),
            key = 0xFFFDFEFE.toInt(), keyGradEnd = 0xFFEEF1F5.toInt(),
            keyText = 0xFF2A3240.toInt(), functionKey = 0xFFD9DEE6.toInt(), accent = 0xFFFF5A3C.toInt()),
        dark = KbPalette(bg = 0xFF252A33.toInt(), bgGradEnd = 0xFF1A1E25.toInt(),
            key = 0xFFE7EBF2.toInt(), keyGradEnd = 0xFFD5DAE3.toInt(),
            keyText = 0xFF222A36.toInt(), functionKey = 0xFFBCC4D0.toInt(), accent = 0xFFFF5A3C.toInt()),
        depthStyle = KbDepthStyle(
            riseDp = 4, wall = 0xFFAAB2BF.toInt(), wallMid = 0xFF98A2B0.toInt(), wallDark = 0xFF808A99.toInt(),
            functionWall = 0xFF8C96A4.toInt(), accentWall = 0xFFC33D22.toInt(), shadow = 0x473C465A,
            stroke = 0xCFFFFFFF.toInt(), rim = 0xDFFFFFFF.toInt(), sheen = 0x18FFFFFF, enterText = 0xFFFFFFFF.toInt()
        ))

    val THREE_D_DEPTH_PILL = KbTheme(THREE_D_DEPTH_PILL_ID, "Pill", boxed = true, radiusDp = 21, shadow = true,
        light = KbPalette(bg = 0xFFEEF0F4.toInt(), bgGradEnd = 0xFFE0E3EA.toInt(),
            key = 0xFFFFFFFF.toInt(), keyGradEnd = 0xFFECEFF4.toInt(),
            keyText = 0xFF333B48.toInt(), functionKey = 0xFFDFE4EC.toInt(), accent = 0xFF4F9DFF.toInt()),
        dark = KbPalette(bg = 0xFF242A33.toInt(), bgGradEnd = 0xFF171B22.toInt(),
            key = 0xFFF2F5FA.toInt(), keyGradEnd = 0xFFDCE2EA.toInt(),
            keyText = 0xFF2B3440.toInt(), functionKey = 0xFFC9D2DE.toInt(), accent = 0xFF4F9DFF.toInt()),
        depthStyle = KbDepthStyle(
            riseDp = 5, wall = 0xFFC2C8D2.toInt(), wallMid = 0xFFACB4C0.toInt(), wallDark = 0xFF929CAA.toInt(),
            functionWall = 0xFFA4AEBB.toInt(), accentWall = 0xFF1C4FC0.toInt(), shadow = 0x38465064,
            stroke = 0xCCFFFFFF.toInt(), rim = 0xE6FFFFFF.toInt(), sheen = 0x24FFFFFF, enterText = 0xFFFFFFFF.toInt()
        ))

    val THREE_D_DEPTH_NEON = KbTheme(THREE_D_DEPTH_NEON_ID, "Neon Cyber", boxed = true, radiusDp = 9, shadow = true, glow = true,
        dark = KbPalette(bg = 0xFF1A1030.toInt(), bgGradEnd = 0xFF0C0A1A.toInt(),
            key = 0xFF241A44.toInt(), keyGradEnd = 0xFF170F2E.toInt(),
            keyText = 0xFFE9F6FF.toInt(), functionKey = 0xFF1B1436.toInt(), accent = 0xFFFF2FB0.toInt()),
        light = KbPalette(bg = 0xFF1A1030.toInt(), bgGradEnd = 0xFF0C0A1A.toInt(),
            key = 0xFF241A44.toInt(), keyGradEnd = 0xFF170F2E.toInt(),
            keyText = 0xFFE9F6FF.toInt(), functionKey = 0xFF1B1436.toInt(), accent = 0xFFFF2FB0.toInt()),
        depthStyle = KbDepthStyle(
            riseDp = 6, wall = 0xFF0A0718.toInt(), wallMid = 0xFF100A24.toInt(), wallDark = 0xFF05040C.toInt(),
            functionWall = 0xFF090613.toInt(), accentWall = 0xFF6E0F4E.toInt(), shadow = 0x99000000.toInt(),
            stroke = 0x6600E5FF, rim = 0x9900E5FF.toInt(), sheen = 0x2200E5FF, glow = 0x6600E5FF,
            enterText = 0xFFFFFFFF.toInt()
        ))

    val THREE_D_DEPTH_WOOD = KbTheme(THREE_D_DEPTH_WOOD_ID, "Warm Wood", boxed = true, radiusDp = 9, shadow = true, serif = true,
        light = KbPalette(bg = 0xFF5B4433.toInt(), bgGradEnd = 0xFF42301F.toInt(),
            key = 0xFFF6E2C2.toInt(), keyGradEnd = 0xFFE6C99B.toInt(),
            keyText = 0xFF3A2A17.toInt(), functionKey = 0xFFE3C398.toInt(), accent = 0xFFE07B3A.toInt()),
        dark = KbPalette(bg = 0xFF4B3628.toInt(), bgGradEnd = 0xFF2C1F14.toInt(),
            key = 0xFFEFD4A8.toInt(), keyGradEnd = 0xFFD8B174.toInt(),
            keyText = 0xFF33210F.toInt(), functionKey = 0xFFD2AC78.toInt(), accent = 0xFFE07B3A.toInt()),
        depthStyle = KbDepthStyle(
            riseDp = 6, wall = 0xFFB08A5A.toInt(), wallMid = 0xFF9A7347.toInt(), wallDark = 0xFF765331.toInt(),
            functionWall = 0xFF9B7349.toInt(), accentWall = 0xFF8F4212.toInt(), shadow = 0x8040280A.toInt(),
            stroke = 0x66FFF0D0, rim = 0x88FFF1C8.toInt(), sheen = 0x20FFFFFF, enterText = 0xFFFFFFFF.toInt()
        ))

    val THREE_D_DEPTH_CANDY = KbTheme(THREE_D_DEPTH_CANDY_ID, "Candy Gloss", boxed = true, radiusDp = 9, shadow = true, bold = true,
        light = KbPalette(bg = 0xFFFFE3F1.toInt(), bgGradEnd = 0xFFFFD0E6.toInt(),
            key = 0xFFFFFFFF.toInt(), keyGradEnd = 0xFFFFC2DF.toInt(),
            keyText = 0xFFA01F5C.toInt(), functionKey = 0xFFFFD9EC.toInt(), accent = 0xFFFF6FAE.toInt()),
        dark = KbPalette(bg = 0xFF3A1628.toInt(), bgGradEnd = 0xFF250B18.toInt(),
            key = 0xFFFFE8F3.toInt(), keyGradEnd = 0xFFFF9FCD.toInt(),
            keyText = 0xFF8F194F.toInt(), functionKey = 0xFFFFB9DB.toInt(), accent = 0xFFFF6FAE.toInt()),
        depthStyle = KbDepthStyle(
            riseDp = 6, wall = 0xFFF39BC4.toInt(), wallMid = 0xFFE578AE.toInt(), wallDark = 0xFFC85A92.toInt(),
            functionWall = 0xFFD66FA5.toInt(), accentWall = 0xFFD81A68.toInt(), shadow = 0x526C2048,
            stroke = 0xEFFFFFFF.toInt(), rim = 0xFFFFFFFF.toInt(), sheen = 0x88FFFFFF.toInt(), glossy = true,
            enterText = 0xFFFFFFFF.toInt()
        ))

    val THREE_D_DEPTH_MECH = KbTheme(THREE_D_DEPTH_MECH_ID, "Mechanical", boxed = true, radiusDp = 7, shadow = true, bold = true,
        light = KbPalette(bg = 0xFF3D4250.toInt(), bgGradEnd = 0xFF2C3039.toInt(),
            key = 0xFF6A7280.toInt(), keyGradEnd = 0xFF454B58.toInt(),
            keyText = 0xFFE8EBF1.toInt(), functionKey = 0xFF5C636F.toInt(), accent = 0xFFFFB347.toInt()),
        dark = KbPalette(bg = 0xFF3D4250.toInt(), bgGradEnd = 0xFF2C3039.toInt(),
            key = 0xFF6A7280.toInt(), keyGradEnd = 0xFF454B58.toInt(),
            keyText = 0xFFE8EBF1.toInt(), functionKey = 0xFF5C636F.toInt(), accent = 0xFFFFB347.toInt()),
        depthStyle = KbDepthStyle(
            riseDp = 8, wall = 0xFF23272F.toInt(), wallMid = 0xFF2D323B.toInt(), wallDark = 0xFF11141A.toInt(),
            functionWall = 0xFF1C2027.toInt(), accentWall = 0xFFA85E00.toInt(), shadow = 0x99000000.toInt(),
            stroke = 0x3310151C, rim = 0x5CFFFFFF, sheen = 0x1FFFFFFF, enterText = 0xFF3A2400.toInt()
        ))

    val THREE_D_DEPTH_MINT = KbTheme(THREE_D_DEPTH_MINT_ID, "Frosted Mint", boxed = true, radiusDp = 10, shadow = true,
        light = KbPalette(bg = 0xFFDFF3EC.toInt(), bgGradEnd = 0xFFC9E9DE.toInt(),
            key = 0xFFFFFFFF.toInt(), keyGradEnd = 0xFFE6F6F0.toInt(),
            keyText = 0xFF1F6D5A.toInt(), functionKey = 0xFFD3EFE6.toInt(), accent = 0xFF2FD6A6.toInt()),
        dark = KbPalette(bg = 0xFF193A31.toInt(), bgGradEnd = 0xFF102820.toInt(),
            key = 0xFFE9FBF5.toInt(), keyGradEnd = 0xFFCBEFE2.toInt(),
            keyText = 0xFF155745.toInt(), functionKey = 0xFFBFE6D9.toInt(), accent = 0xFF2FD6A6.toInt()),
        depthStyle = KbDepthStyle(
            riseDp = 4, wall = 0xFFA9D8C9.toInt(), wallMid = 0xFF92CABB.toInt(), wallDark = 0xFF77B4A2.toInt(),
            functionWall = 0xFF8DCBBC.toInt(), accentWall = 0xFF0A8266.toInt(), shadow = 0x2E1E6E5A,
            stroke = 0xCCFFFFFF.toInt(), rim = 0xDFFFFFFF.toInt(), sheen = 0x18FFFFFF, enterText = 0xFFFFFFFF.toInt()
        ))

    val THREE_D_DEPTH_MONO = KbTheme(THREE_D_DEPTH_MONO_ID, "Mono Brutal", boxed = true, radiusDp = 4, shadow = true, mono = true, bold = true,
        light = KbPalette(bg = 0xFFF2F2F0.toInt(), bgGradEnd = 0xFFF2F2F0.toInt(),
            key = 0xFFFFFFFF.toInt(), keyGradEnd = 0xFFFFFFFF.toInt(),
            keyText = 0xFF111111.toInt(), functionKey = 0xFF111111.toInt(), accent = 0xFFFF4D00.toInt()),
        dark = KbPalette(bg = 0xFF111111.toInt(), bgGradEnd = 0xFF111111.toInt(),
            key = 0xFFFFFFFF.toInt(), keyGradEnd = 0xFFFFFFFF.toInt(),
            keyText = 0xFF111111.toInt(), functionKey = 0xFF111111.toInt(), accent = 0xFFFF4D00.toInt()),
        depthStyle = KbDepthStyle(
            riseDp = 5, wall = 0xFF111111.toInt(), shadow = 0xFF111111.toInt(), stroke = 0xFF111111.toInt(),
            rim = 0x00FFFFFF, sheen = 0x00FFFFFF, hardShadow = true, functionText = 0xFFFFFFFF.toInt(),
            enterText = 0xFF111111.toInt()
        ))

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
        SANS, THREE_D_DEPTH, THREE_D_GLASS,
        THREE_D_DEPTH_CLASSIC, THREE_D_DEPTH_SLATE, THREE_D_DEPTH_CHICLET, THREE_D_DEPTH_PILL, THREE_D_DEPTH_NEON,
        THREE_D_DEPTH_WOOD, THREE_D_DEPTH_CANDY, THREE_D_DEPTH_MECH, THREE_D_DEPTH_MINT, THREE_D_DEPTH_MONO,
        UNI, CUPER, SAND, TECLAS_GLASS, SKEUO, NEON_ARCADE, MATCHA, BUBBLEGUM,
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
