package com.fran.teclas

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.provider.Settings
import java.util.Calendar

const val TECLAS_THEME_MODE_PREF = "theme_mode"
const val TECLAS_THEME_MODE_DARK = "dark"
const val TECLAS_THEME_MODE_LIGHT = "light"
const val TECLAS_THEME_MODE_SYSTEM = "system"

fun Context.teclasSystemDarkMode(): Boolean {
    val configNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    if (configNight == Configuration.UI_MODE_NIGHT_YES) return true
    val systemNight = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    if (systemNight == Configuration.UI_MODE_NIGHT_YES) return true
    val secureNight = runCatching {
        Settings.Secure.getInt(contentResolver, "ui_night_mode", -1)
    }.getOrDefault(-1)
    if (secureNight == UiModeManager.MODE_NIGHT_YES) return true
    if (secureNight == UiModeManager.MODE_NIGHT_NO) return false
    val vendorNight = runCatching {
        Settings.System.getInt(contentResolver, "vos_nightmode_state", 0) == 1
    }.getOrDefault(false)
    if (vendorNight) return true
    when (getSystemService(UiModeManager::class.java)?.nightMode) {
        UiModeManager.MODE_NIGHT_YES -> return true
        UiModeManager.MODE_NIGHT_NO -> return false
        UiModeManager.MODE_NIGHT_AUTO -> {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            return hour >= 18 || hour < 6
        }
    }
    return false
}

fun Context.resolveTeclasNeuTokens(mode: String?): NeuTokens {
    return when (mode ?: TECLAS_THEME_MODE_SYSTEM) {
        TECLAS_THEME_MODE_DARK -> Neu.Dark
        TECLAS_THEME_MODE_LIGHT -> Neu.Light
        else -> if (teclasSystemDarkMode()) Neu.Dark else Neu.Light
    }
}
