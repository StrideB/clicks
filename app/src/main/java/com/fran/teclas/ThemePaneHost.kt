package com.fran.teclas

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import com.fran.teclas.MainActivity.Companion.ACTIVE_ICON_PACK_PREF
import com.fran.teclas.MainActivity.Companion.BRIEF_THEME_PREF
import com.fran.teclas.MainActivity.Companion.CursorViolet
import com.fran.teclas.MainActivity.Companion.GO_COLORS
import com.fran.teclas.MainActivity.Companion.GO_KEY_COLOR_PREF
import com.fran.teclas.MainActivity.Companion.Ink
import com.fran.teclas.MainActivity.Companion.InkDim
import com.fran.teclas.MainActivity.Companion.KEYBOARD_THEME_PREF
import com.fran.teclas.MainActivity.Companion.Line
import com.fran.teclas.MainActivity.Companion.Panel2
import com.fran.teclas.MainActivity.Companion.THEME_MODE_DARK
import com.fran.teclas.MainActivity.Companion.THEME_MODE_LIGHT
import com.fran.teclas.MainActivity.Companion.THEME_MODE_PREF
import com.fran.teclas.MainActivity.Companion.THEME_MODE_SYSTEM
import com.fran.teclas.MainActivity.Companion.THEME_STUDIO_TARGET_ID
import com.fran.teclas.theme.ThemeRepository
import java.util.Locale

/**
 * MainActivity-side host for the Theme Studio / theming wiring: launching the studio
 * (the studio UI itself is theme/ThemeStudioActivity), applying built-in theme bundles,
 * the Theme Studio library-app/pane targets, the Daily Brief theme-picker overlay, the
 * theme-color (accent) swatch row, the launcher look (system/dark/light) menu, and the
 * icon-pack pickers. Bodies are moved verbatim from MainActivity and run with the
 * activity as receiver, so view helpers/prefs/render calls keep resolving as before.
 * Core render-path theming (applyTheme/updateLauncherTheme, wallpaper decode/render,
 * keyboard theme drawables) and the weather style picker (tied to the weather-widget
 * placement overlay) stay in MainActivity.
 */
internal class ThemePaneHost(private val activity: MainActivity) {

    private var briefThemePickerView: View? = null

    fun briefThemePickerShowing(): Boolean = briefThemePickerView?.isAttachedToWindow == true

    // ── Theme Studio + theme bundles ─────────────────────────────────────────

    fun openThemeStudio() { with(activity) {
        startActivity(Intent(this, com.fran.teclas.theme.ThemeStudioActivity::class.java))
    } }

    fun applyLauncherThemeBundle(themeId: String) { with(activity) {
        val applied = ThemeRepository(this).applyBuiltIn(themeId)
        keyboardTheme = prefs().getString(KEYBOARD_THEME_PREF, keyboardTheme) ?: keyboardTheme
        goKeyColor = prefs().getInt(GO_KEY_COLOR_PREF, goKeyColor)
        homeWallpaperDrawable = null
        invalidateLibraryCaches()
        libraryViewDirty = true
        libraryContentReady = false
        renderFavoritesDock()
        updateLauncherTheme(animated = true, forceRender = true)
        Toast.makeText(this, "${applied.name} theme applied", Toast.LENGTH_SHORT).show()
    } }

    fun themeStudioLibraryApp() = LibraryApp("Theme Studio", CursorViolet, themeStudioTarget(), null)

    fun themeStudioTarget() = PaneTarget(THEME_STUDIO_TARGET_ID, "Theme Studio", CursorViolet, PaneKind.SETTINGS, null, null, "Themes")

    // ── Daily Brief theme picker ─────────────────────────────────────────────

    // Twin of openWeatherStylePicker: same Compose bottom sheet, same open/close animation.
    fun openBriefThemePicker() { with(activity) {
        if (briefThemePickerView?.isAttachedToWindow == true) return
        val overlay = ComposeView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setContent {
                com.fran.teclas.brief.BriefThemePickerSheet(
                    accent = ComposeColor(goKeyColor),
                    currentThemeId = briefThemeId(),
                    onSelect = { id ->
                        prefs().edit().putString(homeScopedKey(BRIEF_THEME_PREF), id).apply()
                        closeBriefThemePicker()
                        if (!libraryOpen && openPane == null) render()
                    },
                    onCancel = { closeBriefThemePicker() },
                )
            }
        }
        briefThemePickerView = overlay
        contentFrame.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        overlay.alpha = 0f
        overlay.translationY = dp(40).toFloat()
        overlay.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
    } }

    fun closeBriefThemePicker() { with(activity) {
        val overlay = briefThemePickerView ?: return
        briefThemePickerView = null
        overlay.animate().alpha(0f).translationY(dp(24).toFloat()).setDuration(160)
            .withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay) }
            .start()
    } }

    // ── Theme color (accent) ─────────────────────────────────────────────────

    fun themeColorSelector(): View = with(activity) {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), 0)
            addView(mono("THEME COLOR", 9.5f, InkDim).apply {
                letterSpacing = 0.10f
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            GO_COLORS.forEach { option ->
                addView(TextView(context).apply {
                    text = if (goKeyColor == option.color) "✓" else ""
                    gravity = Gravity.CENTER
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFF10110F.toInt())
                    background = themeColorSwatchBackground(option.color, goKeyColor == option.color)
                    isClickable = true
                    setOnClickListener {
                        haptic(this)
                        applyGoKeyColor(option.color, refreshSettings = true)
                    }
                }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(7) })
            }
        }
    }

    private fun themeColorSwatchBackground(color: Int, selected: Boolean): Drawable = with(activity) {
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(brighten(color), color, darken(color))).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(if (selected) 2 else 1), if (selected) Ink else brighten(color))
        }
        val glow = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(adjustAlpha(color, if (selected) 0.42f else 0.18f))
        }
        return LayerDrawable(arrayOf(glow, face)).apply {
            setLayerInset(0, 0, 0, 0, 0)
            setLayerInset(1, dp(3), dp(3), dp(3), dp(3))
        }
    }

    fun currentGoColorName(): String = with(activity) {
        GO_COLORS.firstOrNull { it.color == goKeyColor }?.name?.lowercase(Locale.US)?.replaceFirstChar { it.uppercase() } ?: "Custom"
    }

    fun cycleGoColor() { with(activity) {
        val idx = GO_COLORS.indexOfFirst { it.color == goKeyColor }
        applyGoKeyColor(GO_COLORS[(idx + 1).mod(GO_COLORS.size)].color, refreshSettings = false)
    } }

    // ── Launcher look (system/dark/light) ────────────────────────────────────

    fun themeModeName(): String = with(activity) {
        when (themeMode) {
            THEME_MODE_DARK -> "Dark"
            THEME_MODE_LIGHT -> "Light"
            else -> "System"
        }
    }

    fun launcherLookSearchState(): String = with(activity) {
        if (themeMode == THEME_MODE_SYSTEM) {
            "System · follows device"
        } else {
            "${themeModeName()} · tap to follow system"
        }
    }

    fun setThemeMode(mode: String, animated: Boolean) { with(activity) {
        themeMode = mode
        prefs().edit().putString(THEME_MODE_PREF, themeMode).apply()
        updateLauncherTheme(animated = animated, forceRender = true)
    } }

    fun showLauncherLookMenu(anchor: View) { with(activity) {
        val labels = arrayOf("System", "Dark", "Light")
        val values = arrayOf(THEME_MODE_SYSTEM, THEME_MODE_DARK, THEME_MODE_LIGHT)
        val current = values.indexOf(themeMode).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Launcher look")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                setThemeMode(values[which], animated = true)
                dialog.dismiss()
                haptic(anchor)
                if (openPane?.kind == PaneKind.SETTINGS) {
                    renderPaneContent(teclasSettingsTarget())
                }
            }
            .show()
    } }

    fun cycleThemeMode() { with(activity) {
        val order = listOf(THEME_MODE_SYSTEM, THEME_MODE_DARK, THEME_MODE_LIGHT)
        setThemeMode(order[(order.indexOf(themeMode) + 1).mod(order.size)], animated = true)
    } }

    // ── Icon pack pickers ────────────────────────────────────────────────────

    fun activeIconPackLabel(): String = with(activity) {
        val active = prefs().getString(ACTIVE_ICON_PACK_PREF, null) ?: return "System"
        return iconPacks().firstOrNull { it.packageName == active }?.name ?: "System"
    }

    fun showIconPackMenu(anchor: View) { with(activity) {
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                setColor(Panel2)
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Line)
            }
        }
        val popup = PopupWindow(menu, dp(244), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
        }
        menu.addView(menuItem("System icons", true) {
            prefs().edit().remove(ACTIVE_ICON_PACK_PREF).apply()
            popup.dismiss()
            renderPaneContent(teclasSettingsTarget())
            renderRibbon()
        })
        iconPacks().forEach { pack ->
            menu.addView(menuItem(pack.name, true) {
                prefs().edit().putString(ACTIVE_ICON_PACK_PREF, pack.packageName).apply()
                popup.dismiss()
                renderPaneContent(teclasSettingsTarget())
                renderRibbon()
            })
        }
        if (iconPacks().isEmpty()) {
            menu.addView(menuItem("No icon packs installed", false) {})
        }
        popup.showAsDropDown(anchor, -dp(80), -anchor.height)
    } }

    fun cycleIconPack() { with(activity) {
        val packs = iconPacks()
        if (packs.isEmpty()) {
            Toast.makeText(this, "No icon packs installed", Toast.LENGTH_SHORT).show()
            return
        }
        val options = listOf<String?>(null) + packs.map { it.packageName }
        val current = prefs().getString(ACTIVE_ICON_PACK_PREF, null)
        val next = options[(options.indexOf(current) + 1).mod(options.size)]
        if (next == null) prefs().edit().remove(ACTIVE_ICON_PACK_PREF).apply()
        else prefs().edit().putString(ACTIVE_ICON_PACK_PREF, next).apply()
        renderFavoritesDock()
        renderRibbon()
    } }
}
