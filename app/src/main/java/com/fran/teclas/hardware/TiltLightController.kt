package com.fran.teclas.hardware

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Lightweight device-tilt source for the keyboard's "tilt lighting" effect.
 *
 * Deliberately minimal: it reads ONLY the accelerometer (no magnetometer / rotation-vector
 * fusion), low-pass filters it into a stable gravity vector, and emits a normalised
 * (nx, ny) in roughly [-1, 1] describing which way the device is leaning. That pair is used
 * purely to slide a specular highlight across the keycaps — it never moves a key or a hitbox,
 * so typing accuracy is untouched even if the reading is noisy.
 *
 * Registration is gated by the caller (only while the effect is enabled and the keyboard is
 * on screen) and uses SENSOR_DELAY_UI so we're not draining battery at game rate while typing.
 */
class TiltLightController(
    context: Context,
    private val onTilt: (nx: Float, ny: Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Low-pass smoothed gravity vector, so the light glides instead of jittering.
    private val gravity = FloatArray(3)
    private var primed = false
    private var registered = false

    // Last emitted values — used to skip redundant callbacks when the phone is held still.
    private var lastNx = Float.NaN
    private var lastNy = Float.NaN

    /** True only if this device actually has an accelerometer to read. */
    val isSupported: Boolean get() = accelerometer != null

    fun register() {
        if (registered || accelerometer == null) return
        // ~11 Hz (90 ms). Each event triggers a redraw of every keycap, so the sampling rate IS
        // the battery/GPU cost. SENSOR_DELAY_UI (~16 Hz) is smoother than a subtle light glide
        // needs; SENSOR_DELAY_NORMAL (~5 Hz) is visibly choppy. 90 ms sits in between and, paired
        // with the low-pass filter below, still reads as fluid while cutting redraw work ~35% vs UI.
        // No maxReportLatency batching: this is a real-time visual, so we can't let the SoC defer
        // samples without the light lagging behind the device.
        registered = sensorManager?.registerListener(this, accelerometer, 90_000) ?: false
    }

    fun unregister() {
        if (!registered) return
        sensorManager?.unregisterListener(this)
        registered = false
        primed = false
        lastNx = Float.NaN
        lastNy = Float.NaN
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        if (!primed) {
            System.arraycopy(event.values, 0, gravity, 0, 3)
            primed = true
        } else {
            // alpha 0.78: at ~11 Hz this stays responsive while still smoothing hand jitter into a
            // fluid glide (lighter than the old 0.85, which felt laggy once the rate dropped).
            val a = 0.78f
            gravity[0] = a * gravity[0] + (1 - a) * event.values[0]
            gravity[1] = a * gravity[1] + (1 - a) * event.values[1]
            gravity[2] = a * gravity[2] + (1 - a) * event.values[2]
        }

        // x = left/right lean, y = forward/back lean. Normalise by g and clamp to a usable band.
        val nx = (gravity[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
        val ny = (gravity[1] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)

        // Dead-zone gate: only fire when the lean has meaningfully changed, so a phone held (near-)
        // still triggers zero redraws. Widened to 0.02 (~1.1° of tilt) so resting-hand micro-tremor
        // doesn't churn 30 keycap redraws for a light shift too small to see.
        if (lastNx.isNaN() || abs(nx - lastNx) > 0.02f || abs(ny - lastNy) > 0.02f) {
            lastNx = nx
            lastNy = ny
            onTilt(nx, ny)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
