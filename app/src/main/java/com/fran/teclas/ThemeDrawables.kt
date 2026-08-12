package com.fran.teclas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import java.util.Locale

// Pure drawing/geometry helpers extracted verbatim from MainActivity to trim that file.
// Behaviour is unchanged: every function here is either fully pure, or depends only on a
// Context receiver (density/resources/packageManager) or explicit parameters. Functions that
// previously read `activeNeuTokens` now take a `tokens: NeuTokens` parameter, supplied at the
// (few) call sites. Names are identical to the original members so call sites do not churn.

// Local density helper for the moved functions; MainActivity keeps its own private dp().
private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

// ── Pure colour math ────────────────────────────────────────────────────────

internal fun brighten(color: Int) = Color.rgb(
    (Color.red(color) + 36).coerceAtMost(255),
    (Color.green(color) + 36).coerceAtMost(255),
    (Color.blue(color) + 36).coerceAtMost(255)
)

internal fun darken(color: Int) = Color.rgb(
    (Color.red(color) - 34).coerceAtLeast(0),
    (Color.green(color) - 34).coerceAtLeast(0),
    (Color.blue(color) - 34).coerceAtLeast(0)
)

internal fun adjustAlpha(color: Int, alpha: Float): Int {
    return adjustAlpha(color, (255 * alpha).toInt().coerceIn(0, 255))
}

internal fun adjustAlpha(color: Int, alpha: Int): Int {
    return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}

internal fun blendColors(from: Int, to: Int, amount: Float): Int {
    val t = amount.coerceIn(0f, 1f)
    return Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt().coerceIn(0, 255),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt().coerceIn(0, 255),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt().coerceIn(0, 255)
    )
}

// ── Pure geometry/animation math ────────────────────────────────────────────

internal fun livePhotoFade(elapsedMs: Long, durationMs: Long): Float {
    val p = (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    if (p < 0.68f) return 1f
    val tail = ((p - 0.68f) / 0.32f).coerceIn(0f, 1f)
    val smooth = tail * tail * (3f - 2f * tail)
    return (1f - smooth).coerceIn(0f, 1f)
}

// ── Pure classification/label helpers ───────────────────────────────────────

internal fun isFnKey(label: String) = label in setOf("123", "teclas", "back", "shift", "abc", "period")

internal fun PaneTarget.usesMediaDock(): Boolean {
    return kind == PaneKind.MUSIC || kind == PaneKind.PHOTOS
}

internal fun weatherCodeLabel(code: Int): String = when (code) {
    0 -> "Clear"
    1, 2 -> "Partly cloudy"
    3 -> "Cloudy"
    45, 48 -> "Fog"
    51, 53, 55, 56, 57 -> "Drizzle"
    61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
    71, 73, 75, 77, 85, 86 -> "Snow"
    95, 96, 99 -> "Storm"
    else -> "Local weather"
}

internal fun highlightedLabel(label: String, match: String, matchColor: Int = Neu.GREEN): SpannableString {
    val styled = SpannableString(label)
    val q = match.trim()
    if (q.isBlank()) return styled
    val start = label.lowercase(Locale.US).indexOf(q.lowercase(Locale.US))
    if (start < 0) return styled
    val end = (start + q.length).coerceAtMost(label.length)
    styled.setSpan(ForegroundColorSpan(matchColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    styled.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return styled
}

internal fun iconPackDisplayName(icon: IconPackIcon): String {
    return icon.drawableName
        .replace(Regex("^(ic_|icon_|app_|drawable_)"), "")
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .replace(Regex("\\s+"), " ")
        .ifBlank { icon.drawableName }
}

internal fun filteredIconPackIcons(
    icons: List<IconPackIcon>,
    queryText: String,
    matched: IconPackIcon?
): List<IconPackIcon> {
    val q = queryText.trim().lowercase(Locale.US)
    val filtered = if (q.isBlank()) {
        icons
    } else {
        val terms = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        icons.filter { icon ->
            val label = iconPackDisplayName(icon).lowercase(Locale.US)
            val raw = icon.drawableName.lowercase(Locale.US).replace('_', ' ').replace('-', ' ')
            terms.all { term -> label.contains(term) || raw.contains(term) }
        }.sortedWith(compareBy<IconPackIcon> {
            val label = iconPackDisplayName(it).lowercase(Locale.US)
            when {
                label == q -> 0
                label.startsWith(q) -> 1
                else -> 2
            }
        }.thenBy { iconPackDisplayName(it) })
    }
    if (matched == null || q.isNotBlank()) return filtered
    return listOf(matched) + filtered.filterNot { it.drawableName == matched.drawableName }
}

// ── Bitmap/drawable utilities ───────────────────────────────────────────────

internal fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap); setBounds(0, 0, canvas.width, canvas.height); draw(canvas); return bitmap
}

internal fun Context.drawableFromIconPack(packPackage: String, drawableName: String): Drawable? {
    val res = runCatching { packageManager.getResourcesForApplication(packPackage) }.getOrNull() ?: return null
    val id = res.getIdentifier(drawableName, "drawable", packPackage)
    return if (id == 0) null else runCatching { res.getDrawable(id, theme) }.getOrNull()
}

// ── Fixed-palette drawable factories (no theme-state reads) ─────────────────

internal fun frostedHeaderBg(): Drawable {
    val base = GradientDrawable().apply { setColor(0xCC16181D.toInt()) }
    val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0x18FFFFFF, 0x00FFFFFF))
    return LayerDrawable(arrayOf(base, sheen))
}

internal fun libraryHeaderGlassBg(): Drawable {
    val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
        0x7A171A20,
        0x55111318,
        0x7607080A
    ))
    val sheen = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
        0x22FFFFFF,
        0x06000000,
        0x18000000
    ))
    val edge = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
        0x14FFFFFF,
        0x00000000,
        0x2A000000
    ))
    return LayerDrawable(arrayOf(base, sheen, edge))
}

internal fun weatherHeaderBackground(): Drawable {
    return GradientDrawable().apply { setColor(Color.TRANSPARENT) }
}

internal fun widgetBoardScrimBackground(): Drawable {
    return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
        0xD0070A0E.toInt(),
        0xF0040508.toInt()
    ))
}

// ── Density-dependent drawable/view factories (Context receiver) ────────────

internal fun Context.mono(text: String, size: Float, color: Int) = TextView(this).apply {
    this.text = text; textSize = size; typeface = Typeface.MONOSPACE; setTextColor(color); includeFontPadding = false
}

internal fun Context.border(color: Int) = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(1), color) }

internal fun Context.highlight(color: Int) = GradientDrawable().apply {
    setColor(Color.argb(34, Color.red(color), Color.green(color), Color.blue(color))); setStroke(dp(2), color)
}

internal fun Context.roundedPanel(fill: Int, radius: Int, stroke: Int? = null) =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(brighten(fill), fill)).apply {
        cornerRadius = radius.toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

internal fun Context.musicHeaderGlassBg(): Drawable {
    val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
        0x6616181D,
        0x4A101217,
        0x6A07080A
    )).apply {
        cornerRadius = dp(0).toFloat()
    }
    val blurTint = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
        0x1FFFFFFF,
        0x08000000,
        0x22000000
    ))
    val lowerEdge = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
        0x00FFFFFF,
        0x24000000
    ))
    return LayerDrawable(arrayOf(base, blurTint, lowerEdge))
}

internal fun Context.widgetCircleBackground(): Drawable {
    val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
        0xFF20242C.toInt(),
        0xFF111419.toInt(),
        0xFF07080B.toInt()
    )).apply {
        shape = GradientDrawable.OVAL
        setStroke(dp(1), 0xFF2A303A.toInt())
    }
    val shade = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
        0x33000000,
        0x00000000
    )).apply { shape = GradientDrawable.OVAL }
    return LayerDrawable(arrayOf(base, shade)).apply {
        setLayerInset(1, dp(2), dp(2), dp(2), dp(18))
    }
}

internal fun Context.goKeyBackground(fillColor: Int): GradientDrawable {
    return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(brighten(fillColor), fillColor)).apply {
        shape = GradientDrawable.OVAL
        setStroke(dp(1), brighten(fillColor))
    }
}

internal fun Context.systemStatusBarHeight(): Int {
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else 0
}

/**
 * Height (px) of the bottom band no bottom-anchored surface may draw into: the 3-button navigation
 * bar where there is one, plus the launcher's own gesture strip where that is switched on. 0 on a
 * plain gesture-navigation device with the strip off.
 *
 * Our windows draw to the physical bottom edge (the launcher pads the status bar in by hand; the
 * docked deck window uses FLAG_LAYOUT_NO_LIMITS), so with buttons enabled the system's
 * back/home/recents row landed on top of the keyboard's bottom key row. Bottom-anchored surfaces
 * reserve this band so the keyboard sits above the buttons instead.
 *
 * The *system's* gesture handle must NOT be lifted for: it floats harmlessly over the deck, and
 * reserving its ~24dp inset would open a dead strip beneath the full-bleed keyboard. Our own strip
 * is the opposite case — it is touchable, so it genuinely takes that band away from whatever is
 * under it, and it is added below.
 *
 * *Which mode* and *how tall* are answered separately, because deriving the mode from insets alone
 * is not dependable. The first cut inferred it from `tappableElement` — 0 under gestures, the whole
 * bar under buttons — and it shipped without lifting anything on a real device. So the mode now
 * comes from `Settings.Secure.navigation_mode`, the very setting the user toggles (0 = 3-button,
 * 1 = 2-button, 2 = gestural), and insets are used only to measure. tappableElement survives purely
 * as the fallback for a device that doesn't publish the setting.
 *
 * Height comes from real insets so a nav bar that isn't at the bottom (landscape, where it moves to
 * the side) correctly measures 0; the `navigation_bar_height` resource is the last resort, used only
 * when no window can be asked.
 */
internal fun Context.systemNavButtonsInset(fromView: android.view.View? = null): Int {
    // Prefer an attached view's own insets — the authoritative value. currentWindowMetrics is the
    // fallback for a Service (and for the first render, before the decor is attached); its insets are
    // documented as less accurate, which is part of why the first attempt missed.
    val insets = runCatching {
        fromView?.rootWindowInsets
            ?: (this as? android.app.Activity)?.window?.peekDecorView()?.rootWindowInsets
            ?: getSystemService(WindowManager::class.java)?.currentWindowMetrics?.windowInsets
    }.getOrNull()
    val bars = insets?.getInsets(WindowInsets.Type.navigationBars())?.bottom
    val tappable = insets?.getInsets(WindowInsets.Type.tappableElement())?.bottom

    val gestural = when (navigationModeSetting()) {
        0, 1 -> false          // 3-button / 2-button: a real bar owns the bottom
        2 -> true              // fully gestural: nothing to reserve
        else -> if (tappable != null && bars != null) tappable < bars else null
    }

    val systemBand = when {
        gestural == true -> 0
        bars != null -> bars   // real inset: already 0 when the bar is on the side or hidden
        gestural == false -> resourceNavBarHeight()
        else -> 0
    }
    // The launcher's own gesture strip eats the same band the buttons did, so bottom-anchored
    // surfaces have to clear it for exactly the same reason. Without this the docked keyboard's
    // bottom key row sits under the strip and every press on it arrives late, via tap passthrough,
    // or not at all. Additive rather than a replacement: on a button ROM with the bar still shown,
    // both are really there.
    val measured = systemBand + gestureStripInset()
    // Only on change: this is called on every keyboard show and every deck-height sync, and a line
    // per call would bury the one transition worth reading.
    if (measured != lastLoggedNavInset) {
        lastLoggedNavInset = measured
        android.util.Log.d(
            "TeclasNav",
            "navMode=${navigationModeSetting()} gestural=$gestural bars=$bars tappable=$tappable " +
                "res=${resourceNavBarHeight()} strip=${gestureStripInset()} -> $measured"
        )
    }
    return measured
}

/** Height of the launcher's own bottom gesture strip, or 0 when it isn't drawn. */
private fun Context.gestureStripInset(): Int {
    if (!com.fran.teclas.nav.GestureNavPrefs.overlayEnabled(this)) return 0
    if (!com.fran.teclas.nav.GestureNavPrefs.bottomBarEnabled(this)) return 0
    return (com.fran.teclas.nav.GestureNavPrefs.bottomHeightDp(this) * resources.displayMetrics.density)
        .toInt()
}

private var lastLoggedNavInset = Int.MIN_VALUE

/**
 * The colour a keyboard deck's background ends on at its bottom edge, by theme.
 *
 * Shared because three surfaces have to agree on it: the launcher's full-bleed dock, the docked
 * overlay, and the IME — which tints the system navigation bar with it, so the button strip is not a
 * different colour from the keys directly above it. Theme ids are the same strings all three classes
 * keep as private constants.
 */
internal fun keyboardDeckBottomEdgeColor(theme: String, light: Boolean): Int = when {
    theme == "brushed" -> if (light) 0xFFD4D8DF.toInt() else 0xFF101113.toInt()
    theme == "seeme" -> 0xFF050608.toInt()
    theme == "hyper3d_black" -> 0xFF050506.toInt()
    theme == "hyper3d" || theme == "teclas" -> if (light) 0xFFC7CED9.toInt() else 0xFF08090C.toInt()
    theme == "gokeys" -> if (light) 0xFFC9D0DA.toInt() else 0xFF0B0C0F.toInt()
    KeyboardThemeDrawables.isAddedTheme(theme) -> if (light) 0xFFDDE2E9.toInt() else 0xFF111318.toInt()
    else -> if (light) 0xFFE3E7EE.toInt() else 0xFF1B1D21.toInt()
}

/** `Settings.Secure.navigation_mode`: 0 = 3-button, 1 = 2-button, 2 = gestural. Null if absent. */
private fun Context.navigationModeSetting(): Int? = runCatching {
    android.provider.Settings.Secure.getInt(contentResolver, "navigation_mode")
}.getOrNull()

private fun Context.resourceNavBarHeight(): Int {
    val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
    return if (id > 0) runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
}

// ── Neu-token drawable factories (tokens passed explicitly) ─────────────────

internal fun Context.homeKeyboardWidgetBackground(tokens: NeuTokens): Drawable {
    return Neu.drawable(tokens, dp(16).toFloat(), NeuLevel.RAISED)
}

internal fun Context.dockIconButtonBackground(tokens: NeuTokens): Drawable {
    return Neu.drawable(tokens, dp(99).toFloat(), NeuLevel.RAISED_SM)
}

internal fun Context.widgetChromeBackground(tokens: NeuTokens): Drawable {
    val light = tokens.mode == NeuMode.LIGHT
    return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (light) {
        intArrayOf(adjustAlpha(Color.WHITE, 0.20f), adjustAlpha(Color.WHITE, 0.06f), Color.TRANSPARENT)
    } else {
        intArrayOf(adjustAlpha(Color.WHITE, 0.10f), adjustAlpha(tokens.baseLo, 0.18f), Color.TRANSPARENT)
    }).apply {
        cornerRadius = dp(13).toFloat()
        setStroke(dp(1), adjustAlpha(Color.WHITE, if (light) 0.30f else 0.10f))
    }
}

internal fun Context.widgetPickerSheetBackground(tokens: NeuTokens): Drawable {
    val light = tokens.mode == NeuMode.LIGHT
    // Near-opaque: the sheet is a reading surface — widget previews and labels have to sit on a
    // solid ground, not on whatever board content happens to be behind the sheet.
    val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (light) {
        intArrayOf(
            adjustAlpha(Color.WHITE, 0.97f),
            adjustAlpha(tokens.baseHi, 0.97f),
            adjustAlpha(tokens.baseLo, 0.97f)
        )
    } else {
        intArrayOf(
            adjustAlpha(tokens.baseHi, 0.97f),
            adjustAlpha(tokens.base, 0.97f),
            adjustAlpha(tokens.baseLo, 0.98f)
        )
    }).apply {
        cornerRadius = dp(28).toFloat()
        setStroke(dp(1), adjustAlpha(Color.WHITE, if (light) 0.40f else 0.16f))
    }
    return base
}

internal fun Context.widgetEmptyBackground(tokens: NeuTokens): Drawable {
    val light = tokens.mode == NeuMode.LIGHT
    return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (light) {
        intArrayOf(adjustAlpha(Color.WHITE, 0.24f), adjustAlpha(tokens.baseHi, 0.16f), adjustAlpha(tokens.baseLo, 0.18f))
    } else {
        intArrayOf(adjustAlpha(tokens.baseHi, 0.34f), adjustAlpha(tokens.base, 0.28f), adjustAlpha(tokens.baseLo, 0.40f))
    }).apply {
        cornerRadius = dp(26).toFloat()
        setStroke(dp(1), adjustAlpha(Color.WHITE, if (light) 0.34f else 0.16f))
    }
}

// ── Keyboard key drawable factories ─────────────────────────────────────────
// Moved from MainActivity; the former reads of `keyboardTheme`/`keyboardLightMode()` are now
// the explicit `teclas`/`skeuo`/`light` parameters supplied at the (few) call sites.

internal fun Context.goKeysKeyBackground(label: String, pressed: Boolean, light: Boolean): Drawable {
    val isGo = label == "enter"
    val radius = dp(if (isGo) 9 else 6).toFloat()
    if (isGo) {
        return GradientDrawable().apply {
            setColor(if (pressed) 0xFFE35C16.toInt() else 0xFFFF7A2A.toInt())
            cornerRadius = radius
            setStroke(dp(1), if (pressed) 0xFFE35C16.toInt() else 0xFFFF8A43.toInt())
        }
    }
    if (light) {
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (pressed) 0xFFD0D6DF.toInt() else 0xFFF3F5F8.toInt(),
            if (pressed) 0xFFB8C1CE.toInt() else 0xFFDCE2EA.toInt()
        )).apply {
            cornerRadius = radius
            setStroke(dp(1), if (pressed) 0xFFAAB4C1.toInt() else 0xFFFFFFFF.toInt())
        }
    }
    return GradientDrawable().apply {
        setColor(if (pressed) 0xFF1A1D22.toInt() else 0xFF22262D.toInt())
        cornerRadius = radius
        setStroke(dp(1), if (pressed) 0xFF16191E.toInt() else 0xFF2E333B.toInt())
    }
}

internal fun Context.physicalKeyBackground(pressed: Boolean, premium: Boolean, fn: Boolean, teclas: Boolean, light: Boolean): Drawable {
    val radius = dp(when {
        premium -> 10
        teclas -> 5
        else -> 9
    }).toFloat()
    if (light) {
        val skirt = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            0xFFB3BCC9.toInt(),
            0xFF8E99A8.toInt(),
            0xFF687482.toInt()
        )).apply {
            cornerRadius = radius
            setStroke(dp(1), 0x88FFFFFF.toInt())
        }
        val outerRim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (pressed) 0xFFD3DBE5.toInt() else 0xFFFFFFFF.toInt(),
            if (pressed) 0xFFAEB8C6.toInt() else 0xFFCBD3DE.toInt()
        )).apply {
            cornerRadius = radius
            setStroke(dp(1), 0xFFFFFFFF.toInt())
        }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (pressed) 0xFFD2DAE4.toInt() else 0xFFF5F7FA.toInt(),
            if (pressed) 0xFFBBC5D1.toInt() else 0xFFDCE3EC.toInt(),
            if (pressed) 0xFFA4AFBD.toInt() else 0xFFC2CCD8.toInt()
        )).apply {
            cornerRadius = radius
            setStroke(dp(1), if (pressed) 0xFFA6B1BE.toInt() else 0xFFFFFFFF.toInt())
        }
        val topGlint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99FFFFFF.toInt(), 0x00FFFFFF)).apply {
            cornerRadius = radius
        }
        val warmBleed = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(
            if (fn && !pressed) 0x22F5C451 else if (pressed) 0x33FF5A3C else 0x00000000,
            0x00000000
        )).apply {
            cornerRadius = radius
        }
        return LayerDrawable(arrayOf(skirt, outerRim, face, warmBleed, topGlint)).apply {
            val drop = if (pressed) dp(1) else dp(if (premium) 7 else 6)
            val side = dp(2)
            setLayerInset(0, dp(1), drop, dp(1), 0)
            setLayerInset(1, side, dp(if (pressed) 1 else 0), side, drop)
            setLayerInset(2, side + dp(1), dp(1), side + dp(1), drop + dp(1))
            setLayerInset(3, side + dp(2), dp(4), side + dp(2), drop + dp(1))
            setLayerInset(4, side + dp(3), dp(2), side + dp(3), dp(20))
        }
    }
    val skirt = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
        when {
            premium -> 0xFF14171D.toInt()
            teclas -> 0xFF050609.toInt()
            else -> 0xFF07080B.toInt()
        },
        if (teclas) 0xFF020304.toInt() else 0xFF050609.toInt(),
        0xFF010102.toInt()
    )).apply {
        cornerRadius = radius
        setStroke(dp(1), 0xFF040507.toInt())
    }
    val outerRim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
        when {
            pressed -> 0xFFFF8E68.toInt()
            premium -> 0xFF525866.toInt()
            teclas -> 0xFF24272F.toInt()
            else -> 0xFF34373E.toInt()
        },
        if (teclas) 0xFF05060A.toInt() else 0xFF090A0D.toInt()
    )).apply {
        cornerRadius = radius
        setStroke(dp(1), if (premium) 0xFF05060A.toInt() else 0xFF040507.toInt())
    }
    val faceColors = when {
        pressed -> intArrayOf(0xFFFF9B72.toInt(), 0xFFFF5A3C.toInt(), 0xFF9C2F11.toInt())
        premium -> intArrayOf(0xFF4B505B.toInt(), 0xFF2D323D.toInt(), 0xFF151821.toInt(), 0xFF08090D.toInt())
        teclas -> intArrayOf(0xFF2B2E35.toInt(), 0xFF202329.toInt(), 0xFF15171C.toInt(), 0xFF0A0B0E.toInt())
        else -> intArrayOf(0xFF34373E.toInt(), 0xFF202329.toInt(), 0xFF14161B.toInt(), 0xFF0B0C10.toInt())
    }
    val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, faceColors).apply {
        cornerRadius = radius
        setStroke(dp(1), if (pressed) 0xFFFFB199.toInt() else if (premium) 0xFF3C4350.toInt() else if (teclas) 0xFF111318.toInt() else 0xFF05060A.toInt())
    }
    val topGlint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
        if (pressed) 0x66FFFFFF else if (premium) 0x55FFFFFF else if (teclas) 0x20FFFFFF else 0x3DFFFFFF,
        0x00FFFFFF
    )).apply {
        cornerRadius = radius
    }
    val warmBleed = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(
        if (fn && !pressed) (if (teclas) 0x16F5C451 else 0x22F5C451) else if (pressed) 0x66FF5A3C else 0x00000000,
        0x00000000
    )).apply {
        cornerRadius = radius
    }
    return LayerDrawable(arrayOf(skirt, outerRim, face, warmBleed, topGlint)).apply {
        val drop = if (pressed) dp(1) else dp(when {
            premium -> 8
            teclas -> 8
            else -> 5
        })
        val side = if (premium) dp(2) else dp(if (teclas) 2 else 1)
        setLayerInset(0, dp(1), drop, dp(1), 0)
        setLayerInset(1, side, dp(if (pressed) 1 else 0), side, drop)
        setLayerInset(2, side + dp(1), dp(1), side + dp(1), drop + dp(if (premium) 1 else 0))
        setLayerInset(3, side + dp(2), dp(if (teclas) 5 else 3), side + dp(2), drop + dp(1))
        setLayerInset(4, side + dp(3), dp(2), side + dp(3), dp(if (premium) 19 else if (teclas) 24 else 20))
    }
}

internal fun Context.themedGoKeyBackground(fillColor: Int, pressed: Boolean, skeuo: Boolean): Drawable {
    val skirt = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xFF050609.toInt())
    }
    val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(brighten(fillColor), fillColor, darken(fillColor))).apply {
        shape = GradientDrawable.OVAL
        setStroke(dp(1), brighten(fillColor))
    }
    val glint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x55FFFFFF, 0x00FFFFFF)).apply {
        shape = GradientDrawable.OVAL
    }
    return LayerDrawable(arrayOf(skirt, face, glint)).apply {
        val drop = if (pressed) dp(1) else dp(if (skeuo) 6 else 4)
        setLayerInset(0, dp(1), drop, dp(1), 0)
        setLayerInset(1, dp(2), 0, dp(2), drop)
        setLayerInset(2, dp(8), dp(5), dp(8), dp(if (skeuo) 18 else 20))
    }
}

internal fun Context.widgetProviderRowBackground(tokens: NeuTokens): Drawable {
    val light = tokens.mode == NeuMode.LIGHT
    val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (light) {
        intArrayOf(adjustAlpha(Color.WHITE, 0.20f), adjustAlpha(tokens.baseHi, 0.13f), adjustAlpha(tokens.baseLo, 0.16f))
    } else {
        intArrayOf(adjustAlpha(tokens.baseHi, 0.30f), adjustAlpha(tokens.base, 0.24f), adjustAlpha(tokens.baseLo, 0.34f))
    }).apply {
        cornerRadius = dp(22).toFloat()
        setStroke(dp(1), adjustAlpha(Color.WHITE, if (light) 0.30f else 0.13f))
    }
    return base
}
