package com.fran.teclas.theme

import android.graphics.Color

object DefaultThemes {
    val all: List<LauncherTheme> = listOf(
        LauncherTheme(
            id = "midnight",
            name = "Midnight",
            briefStyle = BriefStyle.AGENDA,
            briefThemeId = "1",
            briefVisible = true,
            weatherStyle = WeatherStyle.HEADER,
            weatherStyleId = "header",
            weatherVisible = true,
            keyboardTheme = "default",
            iconPack = IconPackRef.System,
            wallpaperId = "midnight",
            accentColor = Color.parseColor("#6EA8FE"),
            builtIn = true
        ),
        LauncherTheme(
            id = "console",
            name = "Console",
            briefStyle = BriefStyle.FLIP,
            briefThemeId = "3",
            briefVisible = true,
            weatherStyle = WeatherStyle.COMPACT,
            weatherStyleId = "line",
            weatherVisible = true,
            keyboardTheme = "teclas",
            iconPack = IconPackRef.BuiltInStyle("mono"),
            wallpaperId = "slate",
            accentColor = Color.parseColor("#57E3B6"),
            builtIn = true
        ),
        LauncherTheme(
            id = "warm",
            name = "Warm",
            briefStyle = BriefStyle.COMMITMENTS,
            briefThemeId = "5",
            briefVisible = true,
            weatherStyle = WeatherStyle.CARD,
            weatherStyleId = "almanac_masthead",
            weatherVisible = true,
            keyboardTheme = "skeuo",
            iconPack = IconPackRef.BuiltInStyle("tinted"),
            wallpaperId = "ember",
            accentColor = Color.parseColor("#FFB454"),
            builtIn = true
        ),
        LauncherTheme(
            id = "mono",
            name = "Mono",
            briefStyle = BriefStyle.MINIMAL,
            briefThemeId = "10",
            briefVisible = true,
            weatherStyle = WeatherStyle.HEADER,
            weatherStyleId = "header",
            weatherVisible = false,
            keyboardTheme = "gokeys",
            iconPack = IconPackRef.System,
            wallpaperId = "mist",
            accentColor = Color.parseColor("#E9EDF5"),
            builtIn = true
        )
    )

    fun byId(id: String?): LauncherTheme? = all.firstOrNull { it.id == id }
}
