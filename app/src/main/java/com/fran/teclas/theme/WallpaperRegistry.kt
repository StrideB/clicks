package com.fran.teclas.theme

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.util.Locale

class WallpaperRegistry(private val context: Context) {
    fun entries(): List<WallpaperEntry> {
        val assets = assetWallpapers()
        val user = userWallpaperIds().map { id ->
            WallpaperEntry(id, "Picked wallpaper", WallpaperSource.UserFile(id.removePrefix(USER_PREFIX)), builtin = false)
        }
        return listOf(
            WallpaperEntry(SYSTEM_WALLPAPER_ID, "System wallpaper", WallpaperSource.System),
            WallpaperEntry(FLUID_HOURS_ID, "Fluid Hours", WallpaperSource.FluidHours, WallpaperType.DYNAMIC)
        ) + assets + user
    }

    fun loadDrawable(id: String): Drawable? {
        val entry = entries().firstOrNull { it.id == id } ?: return null
        return when (val source = entry.source) {
            is WallpaperSource.Asset -> runCatching {
                context.assets.open(source.path).use { stream ->
                    BitmapFactory.decodeStream(stream)?.let { BitmapDrawable(context.resources, it) }
                }
            }.getOrNull()
            is WallpaperSource.UserFile -> null
            WallpaperSource.System -> null
            WallpaperSource.FluidHours -> FluidHoursDrawable(context)
        }
    }

    private fun assetWallpapers(): List<WallpaperEntry> {
        val files = runCatching { context.assets.list(WALLPAPER_DIR).orEmpty().toList() }.getOrDefault(emptyList())
        return files
            .filter { name -> name.substringAfterLast('.', "").lowercase(Locale.US) in SUPPORTED_EXTENSIONS }
            .sorted()
            .map { file ->
                val id = file.substringBeforeLast('.')
                WallpaperEntry(
                    id = id,
                    name = id.replace('-', ' ').replace('_', ' ').split(' ')
                        .filter { it.isNotBlank() }
                        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase(Locale.US) } },
                    source = WallpaperSource.Asset("$WALLPAPER_DIR/$file")
                )
            }
    }

    private fun userWallpaperIds(): List<String> = emptyList()

    companion object {
        const val WALLPAPER_DIR = "wallpapers"
        const val SYSTEM_WALLPAPER_ID = "system"
        const val FLUID_HOURS_ID = FluidHours.ID
        const val USER_PREFIX = "user:"
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        fun userWallpaperId(uri: String): String = "$USER_PREFIX$uri"
        fun uriFromUserWallpaperId(id: String): String? = id.removePrefix(USER_PREFIX).takeIf { id.startsWith(USER_PREFIX) }
    }
}
