package com.fran.clicks.keyboard

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Crisp, latency-optimised haptics.
 * - Letter keys → PRIMITIVE_TICK (ultra-short pulse, ~5ms)
 * - Space / backspace → PRIMITIVE_CLICK (slightly deeper thump)
 * - Fired on ACTION_DOWN for zero perceived latency
 * - Amplitude scales down 12% when WPM exceeds 50 to prevent motor blur
 */
class CustomHapticEngine(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    // WPM tracking (sliding window of last 10 taps)
    private val tapTimes = LongArray(10)
    private var tapIdx = 0
    private var tapCount = 0

    private fun recordTap() {
        tapTimes[tapIdx % 10] = System.currentTimeMillis()
        tapIdx++; tapCount++
    }

    private fun wpm(): Float {
        val n = minOf(tapCount, 10)
        if (n < 2) return 0f
        val oldest = tapTimes[(tapIdx - n + 10) % 10]
        val newest = tapTimes[(tapIdx - 1 + 10) % 10]
        val ms = newest - oldest
        return if (ms <= 0) 0f else (n.toFloat() / ms) * 60000f / 5f
    }

    private fun amplitude(): Float {
        val w = wpm()
        return if (w > 50f) 0.88f else 1.0f
    }

    /** Call on ACTION_DOWN for the given key label. */
    fun tap(label: String) {
        recordTap()
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val primitive = when (label) {
                "space", "back" -> VibrationEffect.Composition.PRIMITIVE_CLICK
                else -> VibrationEffect.Composition.PRIMITIVE_TICK
            }
            runCatching {
                v.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(primitive, amplitude())
                        .compose()
                )
            }.onFailure { fallback(label) }
        } else {
            fallback(label)
        }
    }

    private fun fallback(label: String) {
        val durationMs = if (label == "space" || label == "back") 12L else 8L
        val amp = (150 * amplitude()).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, amp))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(durationMs)
        }
    }
}
