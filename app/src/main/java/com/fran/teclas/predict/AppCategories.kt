package com.fran.teclas.predict

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Resolves an installed package to a coarse [AppCategory] so Spaces can rank sensible apps
 * before any launch has been learned (the cold-start prior). Three layers, most-specific
 * first: a curated map of well-known apps, keyword heuristics on the package id, then the
 * manifest ApplicationInfo.category. Results are cached per process.
 */
object AppCategories {

    private val cache = HashMap<String, AppCategory>()

    /** Curated map for common apps whose manifest category is missing or too coarse. */
    private val known: Map<String, AppCategory> = buildMap {
        // Communication / work chat + mail
        listOf(
            "com.google.android.gm" to AppCategory.COMMUNICATION,          // Gmail
            "com.microsoft.office.outlook" to AppCategory.COMMUNICATION,
            "com.Slack" to AppCategory.COMMUNICATION,
            "com.microsoft.teams" to AppCategory.COMMUNICATION,
            "com.discord" to AppCategory.COMMUNICATION,
            "us.zoom.videomeetings" to AppCategory.COMMUNICATION,
            "com.google.android.apps.meetings" to AppCategory.COMMUNICATION,
            "com.google.android.apps.dynamite" to AppCategory.COMMUNICATION, // Google Chat
            "com.whatsapp" to AppCategory.COMMUNICATION,
            "org.telegram.messenger" to AppCategory.COMMUNICATION,
            "com.google.android.apps.messaging" to AppCategory.COMMUNICATION,
            "org.thoughtcrime.securesms" to AppCategory.COMMUNICATION,       // Signal
        ).forEach { put(it.first, it.second) }
        // Productivity / office
        listOf(
            "com.google.android.calendar" to AppCategory.PRODUCTIVITY,
            "com.google.android.keep" to AppCategory.PRODUCTIVITY,
            "com.notion.id" to AppCategory.PRODUCTIVITY,
            "com.microsoft.office.word" to AppCategory.PRODUCTIVITY,
            "com.microsoft.office.excel" to AppCategory.PRODUCTIVITY,
            "com.google.android.apps.docs" to AppCategory.PRODUCTIVITY,
            "com.google.android.apps.docs.editors.docs" to AppCategory.PRODUCTIVITY,
            "com.google.android.apps.docs.editors.sheets" to AppCategory.PRODUCTIVITY,
            "com.trello" to AppCategory.PRODUCTIVITY,
            "com.linkedin.android" to AppCategory.PRODUCTIVITY,
            "com.todoist" to AppCategory.PRODUCTIVITY,
        ).forEach { put(it.first, it.second) }
        // Finance
        listOf(
            "com.google.android.apps.walletnfcrel" to AppCategory.FINANCE,
            "com.paypal.android.p2pmobile" to AppCategory.FINANCE,
        ).forEach { put(it.first, it.second) }
        // Social
        listOf(
            "com.instagram.android" to AppCategory.SOCIAL,
            "com.zhiliaoapp.musically" to AppCategory.SOCIAL,               // TikTok
            "com.facebook.katana" to AppCategory.SOCIAL,
            "com.twitter.android" to AppCategory.SOCIAL,
            "com.reddit.frontpage" to AppCategory.SOCIAL,
            "com.snapchat.android" to AppCategory.SOCIAL,
            "com.pinterest" to AppCategory.SOCIAL,
        ).forEach { put(it.first, it.second) }
        // Music / audio
        listOf(
            "com.spotify.music" to AppCategory.MUSIC,
            "com.google.android.apps.youtube.music" to AppCategory.MUSIC,
            "com.apple.android.music" to AppCategory.MUSIC,
            "com.soundcloud.android" to AppCategory.MUSIC,
            "com.audible.application" to AppCategory.MUSIC,
        ).forEach { put(it.first, it.second) }
        // Video
        listOf(
            "com.google.android.youtube" to AppCategory.VIDEO,
            "com.netflix.mediaclient" to AppCategory.VIDEO,
            "com.disney.disneyplus" to AppCategory.VIDEO,
            "com.amazon.avod.thirdpartyclient" to AppCategory.VIDEO,
        ).forEach { put(it.first, it.second) }
        // Maps / travel
        listOf(
            "com.google.android.apps.maps" to AppCategory.MAPS,
            "com.waze" to AppCategory.MAPS,
            "com.google.android.apps.mapslite" to AppCategory.MAPS,
            "com.ubercab" to AppCategory.TRAVEL,
            "com.lyft.android" to AppCategory.TRAVEL,
            "com.airbnb.android" to AppCategory.TRAVEL,
            "com.booking" to AppCategory.TRAVEL,
            "com.tripadvisor.tripadvisor" to AppCategory.TRAVEL,
        ).forEach { put(it.first, it.second) }
        // Health / fitness
        listOf(
            "com.google.android.apps.fitness" to AppCategory.HEALTH,
            "com.strava" to AppCategory.HEALTH,
            "com.fitbit.FitbitMobile" to AppCategory.HEALTH,
            "com.myfitnesspal.android" to AppCategory.HEALTH,
            "com.nike.plusgps" to AppCategory.HEALTH,
        ).forEach { put(it.first, it.second) }
        // Reading / news
        listOf(
            "com.google.android.apps.magazines" to AppCategory.NEWS,
            "flipboard.app" to AppCategory.NEWS,
            "com.medium.reader" to AppCategory.READING,
            "com.amazon.kindle" to AppCategory.READING,
            "com.pocket" to AppCategory.READING,
        ).forEach { put(it.first, it.second) }
        // Shopping
        listOf(
            "com.amazon.mShop.android.shopping" to AppCategory.SHOPPING,
            "com.ebay.mobile" to AppCategory.SHOPPING,
            "com.einnovation.temu" to AppCategory.SHOPPING,
        ).forEach { put(it.first, it.second) }
    }

    /** Keyword fragments in a package id → category, checked when not in [known]. */
    private val keywords: List<Pair<String, AppCategory>> = listOf(
        "mail" to AppCategory.COMMUNICATION, "messeng" to AppCategory.COMMUNICATION,
        "chat" to AppCategory.COMMUNICATION, "sms" to AppCategory.COMMUNICATION,
        "calendar" to AppCategory.PRODUCTIVITY, "office" to AppCategory.PRODUCTIVITY,
        "docs" to AppCategory.PRODUCTIVITY, "note" to AppCategory.PRODUCTIVITY,
        "bank" to AppCategory.FINANCE, "wallet" to AppCategory.FINANCE, "pay" to AppCategory.FINANCE,
        "music" to AppCategory.MUSIC, "podcast" to AppCategory.MUSIC, "audio" to AppCategory.MUSIC,
        "maps" to AppCategory.MAPS, "navi" to AppCategory.MAPS,
        "fit" to AppCategory.HEALTH, "health" to AppCategory.HEALTH, "workout" to AppCategory.HEALTH,
        "news" to AppCategory.NEWS, "shop" to AppCategory.SHOPPING, "store" to AppCategory.SHOPPING,
        "camera" to AppCategory.PHOTOS, "photo" to AppCategory.PHOTOS, "gallery" to AppCategory.PHOTOS,
    )

    fun of(context: Context, pkg: String): AppCategory {
        cache[pkg]?.let { return it }
        val resolved = resolve(context, pkg)
        cache[pkg] = resolved
        return resolved
    }

    private fun resolve(context: Context, pkg: String): AppCategory {
        known[pkg]?.let { return it }
        val lower = pkg.lowercase()
        keywords.firstOrNull { lower.contains(it.first) }?.let { return it.second }
        val manifest = runCatching {
            context.packageManager.getApplicationInfo(pkg, 0).category
        }.getOrNull()
        return when (manifest) {
            ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.SOCIAL
            ApplicationInfo.CATEGORY_AUDIO -> AppCategory.MUSIC
            ApplicationInfo.CATEGORY_VIDEO -> AppCategory.VIDEO
            ApplicationInfo.CATEGORY_IMAGE -> AppCategory.PHOTOS
            ApplicationInfo.CATEGORY_MAPS -> AppCategory.MAPS
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppCategory.PRODUCTIVITY
            ApplicationInfo.CATEGORY_GAME -> AppCategory.GAMES
            ApplicationInfo.CATEGORY_NEWS -> AppCategory.NEWS
            else -> AppCategory.OTHER
        }
    }

    fun clearCache() = cache.clear()
}
