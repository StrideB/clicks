package com.fran.teclas.theme

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

class ThemeRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _activeTheme = MutableStateFlow(loadActiveTheme())
    val activeTheme: StateFlow<LauncherTheme> = _activeTheme

    fun allThemes(): List<LauncherTheme> = DefaultThemes.all + userThemes()

    fun active(): LauncherTheme = _activeTheme.value

    fun applyTheme(theme: LauncherTheme) {
        writeLayerPrefs(theme)
        persistTheme(theme)
        _activeTheme.value = theme
    }

    fun applyBuiltIn(id: String): LauncherTheme {
        val theme = DefaultThemes.byId(id) ?: DefaultThemes.all.first()
        applyTheme(theme)
        return theme
    }

    fun editActive(transform: (LauncherTheme) -> LauncherTheme): LauncherTheme {
        val base = active()
        val editable = if (base.builtIn) {
            base.copy(
                id = CUSTOM_THEME_ID,
                name = "Custom",
                builtIn = false
            )
        } else {
            base
        }
        val updated = transform(editable).copy(builtIn = false)
        upsertUserTheme(updated)
        applyTheme(updated)
        return updated
    }

    fun cycleWallpaper(): LauncherTheme {
        val wallpapers = WallpaperRegistry(context).entries()
        val ids = wallpapers.map { it.id }.ifEmpty { listOf(WallpaperRegistry.SYSTEM_WALLPAPER_ID) }
        val currentIndex = ids.indexOf(active().wallpaperId).takeIf { it >= 0 } ?: -1
        return editActive { it.copy(wallpaperId = ids[(currentIndex + 1).mod(ids.size)]) }
    }

    fun cycleKeyboard(allowedThemes: List<String>): LauncherTheme {
        val order = allowedThemes.ifEmpty { listOf("default", "teclas", "skeuo", "gokeys") }
        val currentIndex = order.indexOf(active().keyboardTheme).takeIf { it >= 0 } ?: -1
        return editActive { it.copy(keyboardTheme = order[(currentIndex + 1).mod(order.size)]) }
    }

    fun cycleAccent(colors: List<Int>): LauncherTheme {
        val order = colors.ifEmpty { listOf(Color.parseColor("#C9A7FF")) }
        val currentIndex = order.indexOf(active().accentColor).takeIf { it >= 0 } ?: -1
        return editActive { it.copy(accentColor = order[(currentIndex + 1).mod(order.size)]) }
    }

    fun setWallpaperUri(uri: String): LauncherTheme =
        editActive { it.copy(wallpaperId = WallpaperRegistry.userWallpaperId(uri)) }

    private fun loadActiveTheme(): LauncherTheme {
        val activeId = prefs.getString(ACTIVE_THEME_ID_PREF, null)
        return DefaultThemes.byId(activeId)
            ?: userThemes().firstOrNull { it.id == activeId }
            ?: themeFromCurrentPrefs()
    }

    private fun themeFromCurrentPrefs(): LauncherTheme {
        val keyboard = prefs.getString(KEYBOARD_THEME_PREF, "default") ?: "default"
        val accent = prefs.getInt(GO_KEY_COLOR_PREF, Color.parseColor("#C9A7FF"))
        val briefId = prefs.getString(BRIEF_THEME_PREF, "1") ?: "1"
        val weatherId = prefs.getString(WEATHER_WIDGET_STYLE_PREF, WEATHER_HEADER_ID) ?: WEATHER_HEADER_ID
        val wallpaperDefault = if (hasManualWallpaperChoice()) {
            WallpaperRegistry.SYSTEM_WALLPAPER_ID
        } else {
            WallpaperRegistry.FLUID_HOURS_ID
        }
        return LauncherTheme(
            id = CUSTOM_THEME_ID,
            name = "Custom",
            briefStyle = briefStyleForPref(briefId),
            briefThemeId = briefId,
            briefVisible = prefs.getBoolean(BRIEF_VISIBLE_PREF, true),
            weatherStyle = weatherStyleForPref(weatherId),
            weatherStyleId = weatherId,
            weatherVisible = prefs.getBoolean(WEATHER_VISIBLE_PREF, true),
            keyboardTheme = keyboard,
            iconPack = prefs.getString(ACTIVE_ICON_PACK_PREF, null)?.let { IconPackRef.InstalledPack(it) } ?: IconPackRef.System,
            wallpaperId = prefs.getString(THEME_WALLPAPER_ID_PREF, wallpaperDefault) ?: wallpaperDefault,
            accentColor = accent,
            builtIn = false
        )
    }

    private fun hasManualWallpaperChoice(): Boolean =
        prefs.contains(THEME_WALLPAPER_ID_PREF) ||
            prefs.contains(HOME_COVER_WALLPAPER_URI_PREF) ||
            prefs.contains(HOME_COVER_WALLPAPER_URI_PREF + DOCKED_HOME_SUFFIX) ||
            prefs.getBoolean(HOME_SYSTEM_WALLPAPER_PREF, false) ||
            prefs.getBoolean(HOME_SYSTEM_WALLPAPER_PREF + DOCKED_HOME_SUFFIX, false)

    private fun persistTheme(theme: LauncherTheme) {
        val edit = prefs.edit().putString(ACTIVE_THEME_ID_PREF, theme.id)
        if (!theme.builtIn) {
            upsertUserTheme(theme, edit)
        } else {
            edit.apply()
        }
    }

    private fun upsertUserTheme(theme: LauncherTheme, edit: SharedPreferences.Editor? = null) {
        val next = (userThemes().filterNot { it.id == theme.id } + theme.copy(builtIn = false))
            .sortedBy { it.name.lowercase() }
        val json = JSONArray()
        next.forEach { json.put(it.toJson()) }
        (edit ?: prefs.edit()).putString(USER_THEMES_JSON_PREF, json.toString()).apply()
    }

    private fun userThemes(): List<LauncherTheme> {
        val raw = prefs.getString(USER_THEMES_JSON_PREF, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.toLauncherTheme() }
        }.getOrDefault(emptyList())
    }

    private fun writeLayerPrefs(theme: LauncherTheme) {
        val wallpaperId = theme.wallpaperId
        val edit = prefs.edit()
            .putString(KEYBOARD_THEME_PREF, theme.keyboardTheme)
            .putInt(GO_KEY_COLOR_PREF, theme.accentColor)
            .putLong(THEME_APPLY_VERSION_PREF, System.currentTimeMillis())
            .putBoolean(BRIEF_VISIBLE_PREF, theme.briefVisible)
            .putBoolean(BRIEF_VISIBLE_PREF + DOCKED_HOME_SUFFIX, theme.briefVisible)
            .putString(BRIEF_THEME_PREF, theme.briefThemeId)
            .putString(BRIEF_THEME_PREF + DOCKED_HOME_SUFFIX, theme.briefThemeId)
            .putBoolean(WEATHER_VISIBLE_PREF, theme.weatherVisible)
            .putBoolean(WEATHER_VISIBLE_PREF + DOCKED_HOME_SUFFIX, theme.weatherVisible)
            .putString(WEATHER_WIDGET_STYLE_PREF, theme.weatherStyleId)
            .putString(WEATHER_WIDGET_STYLE_PREF + DOCKED_HOME_SUFFIX, theme.weatherStyleId)
            .putString(THEME_WALLPAPER_ID_PREF, wallpaperId)

        when (val icon = theme.iconPack) {
            IconPackRef.System -> edit.remove(ACTIVE_ICON_PACK_PREF)
            is IconPackRef.BuiltInStyle -> edit.putString(BUILT_IN_ICON_STYLE_PREF, icon.id).remove(ACTIVE_ICON_PACK_PREF)
            is IconPackRef.InstalledPack -> edit.putString(ACTIVE_ICON_PACK_PREF, icon.packageId)
        }

        val userWallpaperUri = WallpaperRegistry.uriFromUserWallpaperId(wallpaperId)
        if (wallpaperId == WallpaperRegistry.SYSTEM_WALLPAPER_ID) {
            edit.putBoolean(HOME_SYSTEM_WALLPAPER_PREF, true)
                .putBoolean(HOME_SYSTEM_WALLPAPER_PREF + DOCKED_HOME_SUFFIX, true)
                .remove(HOME_COVER_WALLPAPER_URI_PREF)
                .remove(HOME_COVER_WALLPAPER_URI_PREF + DOCKED_HOME_SUFFIX)
        } else if (userWallpaperUri != null) {
            edit.putBoolean(HOME_SYSTEM_WALLPAPER_PREF, false)
                .putBoolean(HOME_SYSTEM_WALLPAPER_PREF + DOCKED_HOME_SUFFIX, false)
                .putString(HOME_COVER_WALLPAPER_URI_PREF, userWallpaperUri)
                .putString(HOME_COVER_WALLPAPER_URI_PREF + DOCKED_HOME_SUFFIX, userWallpaperUri)
        } else {
            edit.putBoolean(HOME_SYSTEM_WALLPAPER_PREF, false)
                .putBoolean(HOME_SYSTEM_WALLPAPER_PREF + DOCKED_HOME_SUFFIX, false)
                .remove(HOME_COVER_WALLPAPER_URI_PREF)
                .remove(HOME_COVER_WALLPAPER_URI_PREF + DOCKED_HOME_SUFFIX)
        }
        edit.apply()
    }

    private fun LauncherTheme.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("briefStyle", briefStyle.name)
        .put("briefThemeId", briefThemeId)
        .put("briefVisible", briefVisible)
        .put("weatherStyle", weatherStyle.name)
        .put("weatherStyleId", weatherStyleId)
        .put("weatherVisible", weatherVisible)
        .put("keyboardTheme", keyboardTheme)
        .put("iconPackType", when (iconPack) {
            IconPackRef.System -> "system"
            is IconPackRef.BuiltInStyle -> "built_in"
            is IconPackRef.InstalledPack -> "installed"
        })
        .put("iconPackValue", when (iconPack) {
            IconPackRef.System -> ""
            is IconPackRef.BuiltInStyle -> iconPack.id
            is IconPackRef.InstalledPack -> iconPack.packageId
        })
        .put("wallpaperId", wallpaperId)
        .put("accentColor", accentColor)
        .put("builtIn", builtIn)

    private fun JSONObject.toLauncherTheme(): LauncherTheme? = runCatching {
        val iconPack = when (optString("iconPackType", "system")) {
            "built_in" -> IconPackRef.BuiltInStyle(optString("iconPackValue"))
            "installed" -> IconPackRef.InstalledPack(optString("iconPackValue"))
            else -> IconPackRef.System
        }
        LauncherTheme(
            id = getString("id"),
            name = optString("name", "Custom"),
            briefStyle = runCatching { BriefStyle.valueOf(optString("briefStyle")) }.getOrDefault(BriefStyle.AGENDA),
            briefThemeId = optString("briefThemeId").ifBlank { briefPrefForStyle(runCatching { BriefStyle.valueOf(optString("briefStyle")) }.getOrDefault(BriefStyle.AGENDA)) },
            briefVisible = optBoolean("briefVisible", true),
            weatherStyle = runCatching { WeatherStyle.valueOf(optString("weatherStyle")) }.getOrDefault(WeatherStyle.HEADER),
            weatherStyleId = optString("weatherStyleId").ifBlank { weatherPrefForStyle(runCatching { WeatherStyle.valueOf(optString("weatherStyle")) }.getOrDefault(WeatherStyle.HEADER)) },
            weatherVisible = optBoolean("weatherVisible", true),
            keyboardTheme = optString("keyboardTheme", "default"),
            iconPack = iconPack,
            wallpaperId = optString("wallpaperId", WallpaperRegistry.SYSTEM_WALLPAPER_ID),
            accentColor = optInt("accentColor", Color.parseColor("#C9A7FF")),
            builtIn = false
        )
    }.getOrNull()

    companion object {
        const val PREFS_NAME = "teclas"
        const val ACTIVE_THEME_ID_PREF = "active_theme_id"
        const val USER_THEMES_JSON_PREF = "user_themes_json"
        const val THEME_WALLPAPER_ID_PREF = "theme_wallpaper_id"
        const val THEME_APPLY_VERSION_PREF = "theme_apply_version"
        const val BRIEF_VISIBLE_PREF = "brief_visible"
        const val WEATHER_VISIBLE_PREF = "weather_visible"
        const val BUILT_IN_ICON_STYLE_PREF = "built_in_icon_style"

        const val KEYBOARD_THEME_PREF = "keyboard_theme"
        const val GO_KEY_COLOR_PREF = "go_key_color"
        const val ACTIVE_ICON_PACK_PREF = "active_icon_pack"
        const val BRIEF_THEME_PREF = "brief_theme"
        const val WEATHER_WIDGET_STYLE_PREF = "weather_widget_style"
        const val HOME_SYSTEM_WALLPAPER_PREF = "home_system_wallpaper"
        const val HOME_COVER_WALLPAPER_URI_PREF = "home_cover_wallpaper_uri"
        private const val DOCKED_HOME_SUFFIX = "_docked"

        private const val CUSTOM_THEME_ID = "custom"
        private const val WEATHER_HEADER_ID = "header"

        fun briefPrefForStyle(style: BriefStyle): String = when (style) {
            BriefStyle.AGENDA -> "1"
            BriefStyle.FLIP -> "3"
            BriefStyle.MINIMAL -> "10"
            BriefStyle.COMMITMENTS -> "5"
        }

        fun weatherPrefForStyle(style: WeatherStyle): String = when (style) {
            WeatherStyle.HEADER -> WEATHER_HEADER_ID
            WeatherStyle.CARD -> "almanac_masthead"
            WeatherStyle.COMPACT -> "line"
        }

        fun briefStyleForPref(pref: String): BriefStyle = when (pref) {
            "3", "8" -> BriefStyle.FLIP
            "10", "14", "19" -> BriefStyle.MINIMAL
            "5", "18", "20" -> BriefStyle.COMMITMENTS
            else -> BriefStyle.AGENDA
        }

        fun weatherStyleForPref(pref: String): WeatherStyle = when (pref) {
            WEATHER_HEADER_ID -> WeatherStyle.HEADER
            "line", "plain", "center", "stack", "faint" -> WeatherStyle.COMPACT
            else -> WeatherStyle.CARD
        }
    }
}
