package com.fran.teclas

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Demo-only: pushes the [AgenticLocation.DEMO_LOCATION_PREF] point into the SYSTEM location
 * providers as a mock, so every app (Uber pickup, Maps, weather) sees the demo city — not just
 * Teclas. Requires the mock-location app-op, which is granted either by picking Teclas in
 * Developer options → "Select mock location app", or over adb:
 *
 *   adb shell appops set com.fran.teclas android:mock_location allow
 *
 * Without the grant every call throws SecurityException and this silently no-ops (the in-app
 * demo override still works; only the system-wide spoof needs the op). Refreshes on a timer
 * because test-provider fixes go stale. Clearing the pref stops and removes the providers.
 */
object MockLocationInjector {
    private val PROVIDERS = listOf(
        LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
    private val handler = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null
    private var active = false

    /** Start pushing (or stop, if the demo pref was cleared). Safe to call repeatedly. */
    fun sync(context: Context) {
        val app = context.applicationContext
        val target = demoLatLng(app)
        if (target == null) { stop(app); return }
        val mgr = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        if (!ensureProviders(mgr)) return   // no mock op → give up quietly
        active = true
        tick?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                val cur = demoLatLng(app)
                if (cur == null) { stop(app); return }
                push(mgr, cur.first, cur.second)
                handler.postDelayed(this, 4_000)
            }
        }
        tick = r
        handler.post(r)
    }

    fun stop(context: Context) {
        tick?.let { handler.removeCallbacks(it) }
        tick = null
        if (!active) return
        active = false
        val mgr = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        PROVIDERS.forEach { p -> runCatching { mgr.removeTestProvider(p) } }
    }

    private fun demoLatLng(context: Context): Pair<Double, Double>? {
        val raw = context.getSharedPreferences("teclas", Context.MODE_PRIVATE)
            .getString(AgenticLocation.DEMO_LOCATION_PREF, null)?.trim().orEmpty()
        if (raw.isBlank()) return null
        val parts = raw.split(",")
        val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
        val lng = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
        return lat to lng
    }

    private fun ensureProviders(mgr: LocationManager): Boolean = runCatching {
        PROVIDERS.forEach { p ->
            runCatching { mgr.removeTestProvider(p) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mgr.addTestProvider(
                    p, false, false, false, false, true, true, true,
                    ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE
                )
            } else {
                @Suppress("DEPRECATION")
                mgr.addTestProvider(p, false, false, false, false, true, true, true, 1, 1)
            }
            mgr.setTestProviderEnabled(p, true)
        }
        true
    }.getOrElse { false }   // SecurityException when the mock op isn't granted

    private fun push(mgr: LocationManager, lat: Double, lng: Double) {
        PROVIDERS.forEach { p ->
            val loc = Location(p).apply {
                latitude = lat; longitude = lng
                accuracy = 8f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    bearingAccuracyDegrees = 0.1f; verticalAccuracyMeters = 0.1f; speedAccuracyMetersPerSecond = 0.1f
                }
            }
            runCatching { mgr.setTestProviderLocation(p, loc) }
        }
    }
}
