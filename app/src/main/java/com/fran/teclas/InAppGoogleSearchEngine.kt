package com.fran.teclas

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
        launchSheet(Uri.parse("https://www.google.com/search?q=$encoded"), toolbarColor, navigationColor)
    }

    // Direct site open in the same in-launcher sheet — used when the typed query IS a URL.
    fun launchInAppUrl(url: String, toolbarColor: Int, navigationColor: Int) {
        val clean = url.trim()
        if (clean.isBlank()) return
        launchSheet(Uri.parse(clean), toolbarColor, navigationColor)
    }

    private fun launchSheet(uri: Uri, toolbarColor: Int, navigationColor: Int) {
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

        // A typed query that IS a URL ("theverge.com", "https://x.com/foo"). Returns the openable
        // https URL, or null. Rejects anything with spaces or an '@' (emails full-match WEB_URL's
        // optional user-info form and must keep going to contact/email search instead).
        fun urlFromQuery(text: String): String? {
            val clean = text.trim()
            if (clean.isBlank() || clean.any { it.isWhitespace() } || clean.contains('@')) return null
            if (!clean.contains('.')) return null
            val candidate = if (clean.contains("://")) clean else "https://$clean"
            if (!candidate.startsWith("http://", true) && !candidate.startsWith("https://", true)) return null
            val host = Uri.parse(candidate).host.orEmpty()
            if (!host.contains('.') || host.startsWith('.') || host.endsWith('.')) return null
            return if (android.util.Patterns.WEB_URL.matcher(clean).matches()) candidate else null
        }
    }
}
