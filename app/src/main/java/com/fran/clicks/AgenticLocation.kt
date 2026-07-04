package com.fran.clicks

import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import java.util.Locale

/**
 * Agentic action: drop the user's current location straight into whatever text field they're in —
 * no maps app, no leaving the conversation. Shared by both keyboards. Reuses the same last-known
 * location approach the launcher's weather already uses (no new permissions).
 */
object AgenticLocation {

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun lastKnown(context: Context): Location? {
        if (!hasPermission(context)) return null
        val mgr = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        return providers
            .mapNotNull { runCatching { mgr.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /**
     * Blocking: the current location as "<street address> <maps link>" (or just the tappable link
     * when reverse-geocoding is unavailable), or null if there's no fix / permission. Call off the
     * main thread — it may hit the geocoder network.
     */
    fun currentLocationText(context: Context): String? {
        val loc = lastKnown(context) ?: return null
        val lat = loc.latitude
        val lng = loc.longitude
        val link = "https://maps.google.com/?q=$lat,$lng"
        val address = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault())
                .getFromLocation(lat, lng, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        }.getOrNull()
        return if (!address.isNullOrBlank()) "$address $link" else link
    }
}
