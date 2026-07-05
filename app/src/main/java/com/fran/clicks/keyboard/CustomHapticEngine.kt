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

    /**
     * User-controlled intensity multiplier (0f–1f) applied to every haptic amplitude. The host sets
     * this from its own settings so the keyboard's vibration strength is independent of the system
     * default. 1f = full designed strength; lower = softer. Values are clamped when applied.
     */
    @Volatile var intensity: Float = 1.0f

    private fun scaled(base: Float): Float = (base * intensity).coerceIn(0f, 1f)
    private fun scaledAmp(base: Int): Int = (base * intensity).toInt().coerceIn(1, 255)

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
        return scaled(if (w > 50f) 0.88f else 1.0f)
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

    /** Firm click the instant a swipe crosses the slop threshold into glide mode. */
    fun glideStart() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                v.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scaled(0.7f))
                        .compose()
                )
            }.onFailure { pulse(10L, scaledAmp(150)) }
        } else pulse(10L, scaledAmp(150))
    }

    /** Firm detent when a down-swipe inserts a key's alternate symbol (Apple-style flick). Distinct
     *  from a plain key tap so "sailing down to a symbol" is clearly felt. */
    fun symbolFlick() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                v.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scaled(0.85f))
                        .compose()
                )
            }.onFailure { pulse(14L, scaledAmp(190)) }
        } else pulse(14L, scaledAmp(190))
    }

    /** Light confirmation when a glided word is recognized and committed. */
    fun glideCommit() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                v.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scaled(0.55f))
                        .compose()
                )
            }.onFailure { pulse(6L, scaledAmp(110)) }
        } else pulse(6L, scaledAmp(110))
    }

    /** Progressive hold feedback for the widget-mode DOCK key. */
    fun dockHoldStage(stage: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val clamped = stage.coerceIn(1, 3)
        val scale = when (clamped) {
            1 -> 0.34f
            2 -> 0.56f
            else -> 0.78f
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                v.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scaled(scale))
                        .compose()
                )
            }.onFailure { pulse(7L + clamped * 3L, scaledAmp(80 + clamped * 40)) }
        } else {
            pulse(7L + clamped * 3L, scaledAmp(80 + clamped * 40))
        }
    }

    /** Strong confirmation when the widget keyboard physically detaches. */
    fun dockDetachSnap() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                v.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scaled(1.0f))
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scaled(0.72f))
                        .compose()
                )
            }.onFailure { pulse(24L, scaledAmp(255)) }
        } else {
            pulse(24L, scaledAmp(255))
        }
    }

    private fun fallback(label: String) {
        val durationMs = if (label == "space" || label == "back") 12L else 8L
        val amp = (150 * amplitude()).toInt()
        pulse(durationMs, amp)
    }

    private fun pulse(durationMs: Long, amp: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, amp.coerceIn(1, 255)))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(durationMs)
        }
    }
}
