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

    private var cachedAt = 0L
    private var cachedPlace: Pair<String, PlaceKind> = "unknown" to PlaceKind.UNKNOWN
    private var cachedSpeedDriving = false
    private var cachedCalendar = CalendarProximity.FREE

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
        runCatching {
            val fix = AgenticLocation.lastKnown(context)
            val fresh = fix != null && (System.currentTimeMillis() - fix.time) < FRESH_FIX_MS
            cachedPlace = PlaceStore.clusterFor(context, if (fresh) fix else null)
            cachedSpeedDriving = fresh && fix!!.hasSpeed() && fix.speed > DRIVING_SPEED_MS
        }
        cachedCalendar = runCatching { calendarProximity(context) }.getOrDefault(CalendarProximity.FREE)
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
