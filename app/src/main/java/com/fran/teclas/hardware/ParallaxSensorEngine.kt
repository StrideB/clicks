package com.fran.teclas.hardware

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Reads device tilt from TYPE_ROTATION_VECTOR, low-pass filters it, and reports pitch/roll deltas
 * relative to a slowly-drifting baseline — so the effect centers on however the phone is currently
 * held and eases back to neutral when held steady (no permanent skew). Used only for the favorites
 * dock parallax; registers on demand and stops when not needed to keep it cheap.
 */
class ParallaxSensorEngine(
    context: Context,
    private val onTilt: (pitch: Float, roll: Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private val filterFactor = 0.15f   // low-pass: higher = smoother but laggier
    private val baselineRate = 0.02f   // how fast neutral re-centers to the held pose (~1.5s)
    private var smoothPitch = 0f
    private var smoothRoll = 0f
    private var basePitch = Float.NaN
    private var baseRoll = Float.NaN
    private var sentPitch = Float.NaN
    private var sentRoll = Float.NaN
    private var registered = false

    val isSupported: Boolean get() = rotationSensor != null

    fun start() {
        if (registered || rotationSensor == null) return
        // ~15 Hz — the low-pass filter smooths the motion anyway, and halving the fused
        // rotation-vector rate (accel+gyro+mag) halves this purely cosmetic sensor's cost.
        registered = sensorManager.registerListener(this, rotationSensor, 66_000)
    }

    fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
        basePitch = Float.NaN
        baseRoll = Float.NaN
        sentPitch = Float.NaN
        sentRoll = Float.NaN
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        smoothPitch += filterFactor * (orientation[1] - smoothPitch)
        smoothRoll += filterFactor * (orientation[2] - smoothRoll)
        if (basePitch.isNaN()) { basePitch = smoothPitch; baseRoll = smoothRoll }
        basePitch += baselineRate * (smoothPitch - basePitch)
        baseRoll += baselineRate * (smoothRoll - baseRoll)
        val pitch = smoothPitch - basePitch
        val roll = smoothRoll - baseRoll
        // A phone lying on a table (or held steady, once the baseline catches up) produces the
        // same delta every event — skip the callback then, since each one mutates transforms on
        // the dock and all its children and forces a GPU recomposite 15×/s.
        if (!sentPitch.isNaN() &&
            kotlin.math.abs(pitch - sentPitch) < 0.0015f && kotlin.math.abs(roll - sentRoll) < 0.0015f
        ) return
        sentPitch = pitch
        sentRoll = roll
        onTilt(pitch, roll)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
