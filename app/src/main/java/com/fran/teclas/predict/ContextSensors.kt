package com.fran.teclas.predict

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.CalendarContract
import com.fran.teclas.AgenticLocation
import java.util.Calendar

/**
 * Builds a [ContextSnapshot] from signals the launcher already has access to. Every read is
 * permission-guarded and cheap; the expensive ones (calendar, location cluster) are cached
 * for a couple of minutes. No new permission is *required* — missing signals just collapse
 * to their neutral value, and prediction quality degrades gracefully.
 */
object ContextSensors {

    private const val CACHE_MS = 2 * 60 * 1000L
    private const val DRIVING_SPEED_MS = 6f          // ~13 mph sustained fix speed
    private const val FRESH_FIX_MS = 10 * 60 * 1000L

    private const val AWAY_DISTANCE_M = 300_000f     // 300 km from Home → FAR (also the tz-flip band)
    private const val REGIONAL_DISTANCE_M = 40_000f  // 40 km  → REGIONAL (day-trip range begins)
    private const val LOCAL_DISTANCE_M = 15_000f     // 15 km  → LOCAL (still around town)
    private const val TZ_MIN_SAMPLES = 24            // ~a day of awake-hours before a "usual" timezone exists

    private var cachedAt = 0L
    private var cachedPlace: Pair<String, PlaceKind> = "unknown" to PlaceKind.UNKNOWN
    private var cachedSpeedDriving = false
    private var cachedCalendar = CalendarProximity.FREE
    private var cachedAway = false
    private var cachedBand = DistanceBand.HOME
    private var cachedFamiliar = true
    private var cachedFlew = false

    fun snapshot(
        context: Context,
        lastApp: String?,
        prevApp: String?,
        mediaPlaying: Boolean,
    ): ContextSnapshot {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Calendar.MONDAY(2) -> 0
        val now = SystemClock.elapsedRealtime()
        if (now - cachedAt > CACHE_MS) {
            refreshSlowSignals(context)
            cachedAt = now
        }
        val bt = bluetoothDevice(context)
        return ContextSnapshot(
            hourBucket = hourBucket(hour),
            dayOfWeek = dow,
            isWeekend = dow >= 5,
            placeId = cachedPlace.first,
            placeKind = cachedPlace.second,
            driving = cachedSpeedDriving || bt == BtDevice.CAR,
            btDevice = bt,
            headphones = headphonesConnected(context),
            charging = isCharging(context),
            mediaPlaying = mediaPlaying,
            calendar = cachedCalendar,
            lastApp = lastApp,
            prevApp = prevApp,
            timestamp = System.currentTimeMillis(),
            awayFromHome = cachedAway,
            distanceBand = cachedBand,
            placeFamiliar = cachedFamiliar,
            recentlyFlew = cachedFlew,
        )
    }

    fun hourBucket(hour: Int): Int = when (hour) {
        in 0..4 -> 0   // night
        in 5..7 -> 1   // early AM
        in 8..11 -> 2  // AM
        in 12..14 -> 3 // midday
        in 15..17 -> 4 // PM
        else -> 5      // evening (18..23)
    }

    private fun refreshSlowSignals(context: Context) {
        var freshFix: android.location.Location? = null
        runCatching {
            val fix = AgenticLocation.lastKnown(context)
            val fresh = fix != null && (System.currentTimeMillis() - fix.time) < FRESH_FIX_MS
            if (fresh) freshFix = fix
            cachedPlace = PlaceStore.clusterFor(context, if (fresh) fix else null)
            cachedSpeedDriving = fresh && fix!!.hasSpeed() && fix.speed > DRIVING_SPEED_MS
        }
        cachedCalendar = runCatching { calendarProximity(context) }.getOrDefault(CalendarProximity.FREE)
        cachedAway = runCatching { awayFromHome(context, freshFix) }.getOrDefault(false)
        // Familiar = at a recognized cluster (a saved place or a spot the user dwells); "unknown"
        // means somewhere new — a strong travel cue even under 300 km / same timezone.
        cachedFamiliar = cachedPlace.first != "unknown"
        cachedBand = runCatching { distanceBand(context, freshFix) }.getOrDefault(DistanceBand.HOME)
        cachedFlew = runCatching { PlaceInference.flewRecently(context) }.getOrDefault(false)
    }

    /**
     * Graduated distance-from-Home ring. At a saved HOME/WORK place it's always HOME. Otherwise
     * derived from the fresh fix's distance to Home; with no fix, a timezone flip still reports FAR.
     */
    private fun distanceBand(context: Context, freshFix: android.location.Location?): DistanceBand {
        if (cachedPlace.second == PlaceKind.HOME || cachedPlace.second == PlaceKind.WORK) return DistanceBand.HOME
        val home = PlaceStore.places(context).find { it.kind == PlaceKind.HOME }
        if (home != null && freshFix != null) {
            val d = PlaceStore.distanceM(home.lat, home.lng, freshFix.latitude, freshFix.longitude)
            return when {
                d > AWAY_DISTANCE_M -> DistanceBand.FAR
                d > REGIONAL_DISTANCE_M -> DistanceBand.REGIONAL
                d > LOCAL_DISTANCE_M -> DistanceBand.LOCAL
                else -> DistanceBand.HOME
            }
        }
        // No usable fix: a learned-timezone shift is the only away signal left, and it means FAR.
        return if (cachedAway) DistanceBand.FAR else DistanceBand.HOME
    }

    /**
     * Away from normal life? Two independent detectors, either one fires:
     *  - the device timezone differs from the learned usual timezone (works with no
     *    location permission at all — an overseas trip flips this within one refresh);
     *  - a fresh fix is 300+ km from the saved Home place.
     * Never true while actually inside a saved HOME/WORK place.
     */
    private fun awayFromHome(context: Context, freshFix: android.location.Location?): Boolean {
        if (cachedPlace.second == PlaceKind.HOME || cachedPlace.second == PlaceKind.WORK) {
            trackTimezone(context) // keep learning the usual zone while at home
            return false
        }
        val tzAway = trackTimezone(context)
        val distanceAway = freshFix?.let { fix ->
            PlaceStore.places(context).find { it.kind == PlaceKind.HOME }?.let { home ->
                PlaceStore.distanceM(home.lat, home.lng, fix.latitude, fix.longitude) > AWAY_DISTANCE_M
            }
        } ?: false
        return tzAway || distanceAway
    }

    /**
     * Bump the current timezone's observation count and report whether the device is in a
     * zone other than the usual (most-observed) one. Needs [TZ_MIN_SAMPLES] observations
     * before it ever reports away, so a fresh install can't misfire.
     */
    private fun trackTimezone(context: Context): Boolean {
        val prefs = PredictCrypto.prefs(context)
        val zone = java.util.TimeZone.getDefault().id
        val obj = runCatching { org.json.JSONObject(prefs.getString("predict_timezones", "{}") ?: "{}") }
            .getOrDefault(org.json.JSONObject())
        // At most one observation per hour, so weeks of a trip can't dethrone the home zone quickly.
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("predict_tz_last_bump", 0L) > 60 * 60 * 1000L) {
            obj.put(zone, obj.optInt(zone, 0) + 1)
            prefs.edit().putString("predict_timezones", obj.toString())
                .putLong("predict_tz_last_bump", now).apply()
        }
        var total = 0
        var usual: String? = null
        var usualCount = -1
        obj.keys().forEach { key ->
            val count = obj.optInt(key, 0)
            total += count
            if (count > usualCount) { usual = key; usualCount = count }
        }
        return total >= TZ_MIN_SAMPLES && usual != null && usual != zone
    }

    /** Connected classic-BT audio device, classified as car kit vs headphones. */
    private fun bluetoothDevice(context: Context): BtDevice {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return BtDevice.NONE
        }
        return runCatching {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: return BtDevice.NONE
            if (adapter.getProfileConnectionState(BluetoothProfile.A2DP) != BluetoothProfile.STATE_CONNECTED &&
                adapter.getProfileConnectionState(BluetoothProfile.HEADSET) != BluetoothProfile.STATE_CONNECTED
            ) return BtDevice.NONE
            val bonded = adapter.bondedDevices ?: return BtDevice.OTHER
            var seen = BtDevice.OTHER
            for (device in bonded) {
                val major = device.bluetoothClass?.majorDeviceClass ?: continue
                val minor = device.bluetoothClass?.deviceClass ?: continue
                if (major != BluetoothClass.Device.Major.AUDIO_VIDEO) continue
                seen = when (minor) {
                    BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
                    BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> return BtDevice.CAR
                    BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
                    BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> BtDevice.HEADPHONES
                    else -> seen
                }
            }
            seen
        }.getOrDefault(BtDevice.NONE)
    }

    private fun headphonesConnected(context: Context): Boolean = runCatching {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }.getOrDefault(false)

    private fun isCharging(context: Context): Boolean = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)

    /** In a calendar event now, one starting within 30 min, or free. Same source the brief uses. */
    private fun calendarProximity(context: Context): CalendarProximity {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return CalendarProximity.FREE
        }
        val now = System.currentTimeMillis()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(builder, now - 60 * 60 * 1000L)
        android.content.ContentUris.appendId(builder, now + 60 * 60 * 1000L)
        val projection = arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY)
        context.contentResolver.query(builder.build(), projection, null, null, null)?.use { cursor ->
            var result = CalendarProximity.FREE
            while (cursor.moveToNext()) {
                if (cursor.getInt(2) == 1) continue
                val begin = cursor.getLong(0)
                val end = cursor.getLong(1)
                if (now in begin..end) return CalendarProximity.IN_MEETING
                if (begin in now..(now + 30 * 60 * 1000L)) result = CalendarProximity.MEETING_SOON
            }
            return result
        }
        return CalendarProximity.FREE
    }
}
