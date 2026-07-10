package com.fran.teclas

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sin

/**
 * Lightweight LiveSlider-inspired wallpaper parallax for the launcher canvas.
 *
 * This intentionally does not embed a live-wallpaper service or OpenGL renderer; Clicks already
 * owns the wallpaper ImageView. We reuse the same core idea from LiveSlider: rotation-vector
 * deltas relative to the current resting pose, smoothed and mapped into X/Y wallpaper offsets.
 */
class LiveWallpaperMotionController(
    context: Context,
    private val onOffset: (x: Float, y: Float) -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotationMatrix = FloatArray(9)
    private var referenceMatrix: FloatArray? = null
    private val angleChange = FloatArray(3)
    private var smoothX = 0f
    private var smoothY = 0f
    private var registered = false

    val isSupported: Boolean get() = rotationSensor != null

    fun start() {
        if (registered || rotationSensor == null) return
        referenceMatrix = null
        // 30 Hz is enough for wallpaper motion and much cheaper than raw display-rate sensors.
        registered = sensorManager.registerListener(this, rotationSensor, 33_000)
    }

    fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
        referenceMatrix = null
        smoothX = 0f
        smoothY = 0f
        onOffset(0f, 0f)
    }

    fun recalibrate() {
        referenceMatrix = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        if (!rotationMatrix.all { it.isFinite() }) return
        val reference = referenceMatrix
        if (reference == null) {
            referenceMatrix = rotationMatrix.clone()
            return
        }
        SensorManager.getAngleChange(angleChange, rotationMatrix, reference)
        if (!angleChange.all { it.isFinite() }) return

        val targetX = sin(angleChange[2]).coerceIn(-1f, 1f)
        val targetY = (-sin(angleChange[1])).coerceIn(-1f, 1f)
        smoothX += (targetX - smoothX) * 0.14f
        smoothY += (targetY - smoothY) * 0.14f

        if (abs(smoothX) < 0.002f && abs(smoothY) < 0.002f) return
        onOffset(smoothX, smoothY)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
