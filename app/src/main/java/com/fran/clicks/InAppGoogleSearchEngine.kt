package com.fran.clicks

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import java.net.URLEncoder
import java.util.Locale

class InAppGoogleSearchEngine(private val context: Context) {
    fun launchInAppSearch(query: String, toolbarColor: Int, navigationColor: Int) {
        val clean = query.trim()
        if (clean.isBlank()) return

        val encoded = URLEncoder.encode(clean, "UTF-8")
        val uri = Uri.parse("https://www.google.com/search?q=$encoded")
        val height = (context.resources.displayMetrics.heightPixels * 0.70f).toInt()
        val colorParams = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(toolbarColor)
            .setNavigationBarColor(navigationColor)
            .setNavigationBarDividerColor(navigationColor)
            .build()

        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setDefaultColorSchemeParams(colorParams)
            .setInitialActivityHeightPx(height, CustomTabsIntent.ACTIVITY_HEIGHT_ADJUSTABLE)
            .build()
            .launchUrl(context, uri)
    }

    companion object {
        fun looksLikeExplicitWebSearch(text: String): Boolean {
            val lower = text.trim().lowercase(Locale.US)
            return lower.startsWith("search ") ||
                lower.startsWith("google ") ||
                lower.startsWith("web ")
        }

        fun stripWebVerb(text: String): String {
            val clean = text.trim()
            val first = clean.substringBefore(' ')
            return if (first.equals("search", true) || first.equals("google", true) || first.equals("web", true)) {
                clean.substringAfter(' ', "").trim()
            } else {
                clean
            }
        }
    }
}
