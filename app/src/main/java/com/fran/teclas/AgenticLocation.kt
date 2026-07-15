package com.fran.teclas

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

    /** Demo override pref: "lat,lng" forces every location read in the app to that point, so a
     *  demo shows the launcher as if you were standing there (Paris landmark cards, local weather,
     *  ride offers). Set via `adb ... put demo_location "48.8584,2.2945"`, clear to go live again.
     *  App-only — the OS and other apps still see the real device location. */
    const val DEMO_LOCATION_PREF = "demo_location"

    private fun demoOverride(context: Context): Location? {
        val raw = context.getSharedPreferences("teclas", Context.MODE_PRIVATE)
            .getString(DEMO_LOCATION_PREF, null)?.trim().orEmpty()
        if (raw.isBlank()) return null
        val parts = raw.split(",")
        val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
        val lng = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
        return Location("demo").apply {
            latitude = lat; longitude = lng; accuracy = 10f; time = System.currentTimeMillis()
        }
    }

    /** Most recent cached fix across providers; also feeds the prediction engine's place clustering. */
    fun lastKnown(context: Context): Location? {
        demoOverride(context)?.let { return it }
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
