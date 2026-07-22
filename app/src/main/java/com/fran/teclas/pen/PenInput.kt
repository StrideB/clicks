package com.fran.teclas.pen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent

/**
 * Stylus / S Pen awareness. Everything here is hardware-gated: on a device with no stylus
 * digitizer none of it ever fires, so phones without a pen see zero behavior change.
 *
 * Two signals drive pen mode (mirroring Gboard's stylus handling):
 * - Tool type: a stylus hover or touch over the keyboard ([isStylus]) — works on any
 *   stylus-capable Android device (S26 Ultra, Tab S, styluses on tablets).
 * - Silo state: Samsung broadcasts `com.samsung.pen.INSERT` when the S Pen is pulled out of
 *   or returned to its slot (extra `penInsert`, false = detached). Long-stable OEM broadcast,
 *   no SDK required; simply never fires on non-Samsung hardware.
 */
object PenInput {

    /** Auto-switch to pen mode on stylus signals. Default-on; hardware-gated anyway. */
    const val AUTO_PREF = "pen_mode_auto"

    private const val SPEN_INSERT_ACTION = "com.samsung.pen.INSERT"
    private const val SPEN_INSERT_EXTRA = "penInsert"

    fun autoEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(AUTO_PREF, true)

    fun isStylus(event: MotionEvent): Boolean =
        event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
            event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER

    /** True when any connected input device reports a stylus source, or the device has an
     *  S Pen slot (Samsung feature flag — covers the pen resting in its silo, which doesn't
     *  enumerate as an InputDevice until it touches down on some models). */
    fun hasStylusHardware(context: Context): Boolean {
        val samsungSPen = runCatching {
            context.packageManager.hasSystemFeature("com.sec.feature.spen_usp")
        }.getOrDefault(false)
        if (samsungSPen) return true
        return runCatching {
            InputDevice.getDeviceIds().any { id ->
                InputDevice.getDevice(id)?.supportsSource(InputDevice.SOURCE_STYLUS) == true
            }
        }.getOrDefault(false)
    }

    /**
     * Register for S Pen silo events. [onDetachedChange] receives true when the pen is pulled
     * out, false when it's slotted back in. Returns the receiver to pass to [unregister], or
     * null when the device has no stylus hardware (nothing registered).
     */
    fun registerDetachListener(context: Context, onDetachedChange: (Boolean) -> Unit): BroadcastReceiver? {
        if (!hasStylusHardware(context)) return null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != SPEN_INSERT_ACTION) return
                if (!intent.hasExtra(SPEN_INSERT_EXTRA)) return
                onDetachedChange(!intent.getBooleanExtra(SPEN_INSERT_EXTRA, true))
            }
        }
        val filter = IntentFilter(SPEN_INSERT_ACTION)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        }.onFailure { return null }
        return receiver
    }

    fun unregister(context: Context, receiver: BroadcastReceiver?) {
        receiver ?: return
        runCatching { context.unregisterReceiver(receiver) }
    }
}
