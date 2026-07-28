package com.fran.teclas

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.os.Process

/**
 * The shortcuts an app declares about itself — "New message", "Reorder", "Scan a code" — surfaced
 * on long-press, the way a launcher is supposed to.
 *
 * This is a platform feature the launcher simply wasn't asking for: every app publishes these, the
 * OS keeps them, and reading them costs nothing. It is also the supported answer to "make a dock
 * icon jump straight to the action I always take" — the deep link comes from the app's own author,
 * so it keeps working across their redesigns, which a screen-scraped script never would.
 *
 * Most people do not know their apps declare these at all, so surfacing them teaches something
 * about the phone rather than adding another thing the launcher invented.
 *
 * Requires being the default launcher; [available] reports that honestly instead of throwing.
 */
object AppShortcuts {

    /** A shortcut reduced to what a menu needs. [id] is stable per package. */
    data class Item(val id: String, val label: String, val rank: Int, val enabled: Boolean)

    /** Long-press menus are a glance, not a directory. */
    const val MAX_SHOWN = 4

    private fun service(context: Context): LauncherApps? =
        runCatching { context.getSystemService(LauncherApps::class.java) }.getOrNull()

    /** Whether this app may read shortcuts at all — true only while it is the active launcher. */
    fun available(context: Context): Boolean =
        runCatching { service(context)?.hasShortcutHostPermission() == true }.getOrDefault(false)

    /**
     * The shortcuts [pkg] publishes, best first, or empty when unavailable.
     *
     * Manifest, dynamic and pinned are all requested: an app may declare any mix, and from the
     * user's side the distinction is invisible — they are all just "things this app can do".
     */
    fun forPackage(context: Context, pkg: String): List<Item> {
        val launcher = service(context) ?: return emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(pkg)
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
        // Throws SecurityException when we are not the default launcher, which is an ordinary
        // state for this app rather than an error worth surfacing.
        val raw = runCatching { launcher.getShortcuts(query, Process.myUserHandle()) }.getOrNull()
            ?: return emptyList()
        return order(raw.map { it.toItem() })
    }

    /**
     * Sort, de-duplicate and cap — the pure half, so the part with rules is testable.
     *
     * Rank is the app author's own ordering and is respected first; ties fall back to the label so
     * the menu does not reshuffle between openings, which would make it impossible to build any
     * muscle memory. Disabled shortcuts are dropped rather than greyed out: a menu of things that
     * do nothing is worse than a shorter menu.
     */
    fun order(items: List<Item>, limit: Int = MAX_SHOWN): List<Item> =
        items.asSequence()
            .filter { it.enabled && it.label.isNotBlank() }
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.rank }, { it.label.lowercase() }))
            .take(limit)
            .toList()

    /** Launch [id] for [pkg]. Returns false when it could not be started. */
    fun launch(context: Context, pkg: String, id: String, bounds: Rect? = null): Boolean {
        val launcher = service(context) ?: return false
        return runCatching {
            launcher.startShortcut(pkg, id, bounds, null, Process.myUserHandle())
            true
        }.getOrDefault(false)
    }

    private fun ShortcutInfo.toItem(): Item = Item(
        id = id,
        // shortLabel is the one meant for a menu; longLabel is the fallback the API defines.
        label = (shortLabel ?: longLabel)?.toString().orEmpty().trim(),
        rank = rank,
        enabled = isEnabled,
    )
}
