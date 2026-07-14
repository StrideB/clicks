package com.fran.teclas.theme

/**
 * A launcher theme is a named bundle of the visual layers Teclas already exposes separately.
 * The repository maps these friendly buckets onto the existing widget/theme prefs.
 */
data class LauncherTheme(
    val id: String,
    val name: String,
    val briefStyle: BriefStyle,
    val briefThemeId: String,
    val briefVisible: Boolean,
    val weatherStyle: WeatherStyle,
    val weatherStyleId: String,
    val weatherVisible: Boolean,
    val keyboardTheme: String,
    val iconPack: IconPackRef,
    val wallpaperId: String,
    val accentColor: Int,
    val builtIn: Boolean = false
)

enum class BriefStyle { AGENDA, FLIP, MINIMAL, COMMITMENTS }

enum class WeatherStyle { HEADER, CARD, COMPACT }

sealed interface IconPackRef {
    data object System : IconPackRef
    data class BuiltInStyle(val id: String) : IconPackRef
    data class InstalledPack(val packageId: String) : IconPackRef
}

data class WallpaperEntry(
    val id: String,
    val name: String,
    val source: WallpaperSource
)

sealed interface WallpaperSource {
    data class Asset(val path: String) : WallpaperSource
    data class UserFile(val uri: String) : WallpaperSource
    data object System : WallpaperSource
}
