package com.fran.teclas.spacetoday.theme

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Per-Space theme persistence. Writes are scoped to a single Space id so Work/Travel/Fitness can
 * each keep their own Today look without leaking across contexts.
 */
class TodayThemeStore(private val prefs: SharedPreferences) {
    fun themeIdFor(spaceId: String): String {
        val saved = themeMap()[spaceId]
        return saved?.takeIf { TodayThemes.ALL.any { t -> t.id == it } } ?: defaults()[spaceId] ?: "slate"
    }

    fun themeFor(spaceId: String): TodayTheme = TodayThemes.byId(themeIdFor(spaceId))

    fun setTheme(spaceId: String, themeId: String) {
        if (TodayThemes.ALL.none { it.id == themeId }) return
        val next = themeMap().toMutableMap()
        next[spaceId] = themeId
        val obj = JSONObject()
        next.forEach { (space, theme) -> obj.put(space, theme) }
        prefs.edit().putString(PREF_THEME_BY_SPACE, obj.toString()).apply()
    }

    fun defaults(): Map<String, String> =
        mapOf("work" to "slate", "travel" to "departure", "fitness" to "terminal", "gym" to "terminal")

    fun forkToCustom(base: TodayTheme, edits: TodayTheme): TodayTheme {
        val id = "custom_${System.currentTimeMillis()}"
        val custom = edits.copy(id = id, name = if (edits.name.startsWith("Custom")) edits.name else "Custom ${base.name}", builtIn = false)
        prefs.edit().putString(PREF_USER_THEMES, JSONObject().put(id, base.id).toString()).apply()
        return custom
    }

    fun customThemes(): List<TodayTheme> = emptyList()

    private fun themeMap(): Map<String, String> {
        val raw = prefs.getString(PREF_THEME_BY_SPACE, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { key -> obj.optString(key) }
        }.getOrDefault(emptyMap())
    }

    private companion object {
        const val PREF_THEME_BY_SPACE = "today_theme_by_space_json"
        const val PREF_USER_THEMES = "user_today_themes_json"
    }
}
