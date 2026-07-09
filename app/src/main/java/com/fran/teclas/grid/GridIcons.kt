package com.fran.teclas.grid

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/**
 * Icon resolution for the grid workspace, honoring the same icon-pack settings as the main
 * launcher: the `active_icon_pack` pref and per-app `icon_override_<package>` entries in the
 * shared "teclas" prefs file. Mirrors MainActivity's resolution order (override -> active pack
 * appfilter match -> activity icon -> application icon) without reaching into that class.
 */
object GridIcons {
    private const val PREFS_NAME = "teclas"
    private const val ACTIVE_ICON_PACK_PREF = "active_icon_pack"
    private const val ICON_OVERRIDE_PREFIX = "icon_override_"

    private val drawableCache = mutableMapOf<String, Drawable?>()
    private val packMatchCache = mutableMapOf<String, String?>()

    fun clearCache() {
        drawableCache.clear()
        packMatchCache.clear()
    }

    fun resolve(context: Context, packageName: String?, className: String?): Drawable? {
        val pkg = packageName ?: return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val activePack = prefs.getString(ACTIVE_ICON_PACK_PREF, null)
        val override = prefs.getString("$ICON_OVERRIDE_PREFIX$pkg", null)
        val key = "$pkg/$className|$activePack|$override"
        drawableCache[key]?.let { return it.constantState?.newDrawable(context.resources)?.mutate() ?: it }
        if (drawableCache.containsKey(key)) return null

        val resolved = override?.let { fromOverride(context, it) }
            ?: activePack?.let { pack ->
                className?.let { cls -> fromPack(context, pack, ComponentName(pkg, cls)) }
            }
            ?: runCatching {
                if (className != null) context.packageManager.getActivityIcon(ComponentName(pkg, className))
                else null
            }.getOrNull()
            ?: runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
        drawableCache[key] = resolved
        return resolved
    }

    private fun fromOverride(context: Context, value: String): Drawable? = when {
        value.startsWith("component:") -> ComponentName.unflattenFromString(value.removePrefix("component:"))
            ?.let { runCatching { context.packageManager.getActivityIcon(it) }.getOrNull() }
        value.startsWith("pack:") -> {
            val parts = value.split(":", limit = 3)
            if (parts.size == 3) loadPackDrawable(context, parts[1], parts[2]) else null
        }
        else -> null
    }

    private fun fromPack(context: Context, packPackage: String, component: ComponentName): Drawable? {
        val matchKey = "$packPackage|${component.flattenToString()}"
        val cachedName = if (packMatchCache.containsKey(matchKey)) packMatchCache[matchKey]
        else matchInAppFilter(context, packPackage, component).also { packMatchCache[matchKey] = it }
        return cachedName?.let { loadPackDrawable(context, packPackage, it) }
    }

    private fun matchInAppFilter(context: Context, packPackage: String, component: ComponentName): String? {
        val res = runCatching { context.packageManager.getResourcesForApplication(packPackage) }.getOrNull()
            ?: return null
        val names = setOf(
            "ComponentInfo{${component.packageName}/${component.className}}",
            "ComponentInfo{${component.flattenToString()}}"
        )
        return runCatching {
            res.assets.open("appfilter.xml").use { stream ->
                val parser = Xml.newPullParser()
                parser.setInput(stream, null)
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "item") {
                        val itemComponent = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (drawable != null && itemComponent in names) return@use drawable
                    }
                    event = parser.next()
                }
                null
            }
        }.getOrNull()
    }

    private fun loadPackDrawable(context: Context, packPackage: String, drawableName: String): Drawable? {
        val res = runCatching { context.packageManager.getResourcesForApplication(packPackage) }.getOrNull()
            ?: return null
        val id = res.getIdentifier(drawableName, "drawable", packPackage)
        return if (id == 0) null else runCatching { res.getDrawable(id, context.theme) }.getOrNull()
    }

    /** Rasterize for RemoteViews (the most-used widget can't ship Drawables across binder). */
    fun asBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bitmap
    }
}
