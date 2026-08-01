package com.fran.teclas.nav

import kotlin.math.abs

/** Which edge strip a stroke started in. */
internal enum class NavZone { LEFT_EDGE, RIGHT_EDGE, BOTTOM }

/** What a completed stroke means. [NONE] covers a stroke too small or too ambiguous to act on. */
internal enum class NavGesture { BACK, HOME, RECENTS, NOTIFICATIONS, SWITCH_APP, NONE }

/**
 * A finished stroke, in raw screen pixels.
 *
 * [dyPx] follows Android's convention — y grows downward — so a swipe *up* is negative. Keeping the
 * raw sign here rather than flipping it at the touch handler means the rules below read the same way
 * as the MotionEvents that produced them.
 *
 * [stationaryPx] is how far the pointer travelled during [stationaryMs], the tail of the stroke. The
 * pair is what separates "swipe up" from "swipe up and hold": both end at the same place, and only
 * the dwell tells them apart.
 */
internal data class NavStroke(
    val zone: NavZone,
    val dxPx: Float,
    val dyPx: Float,
    val durationMs: Long,
    val stationaryMs: Long,
    val stationaryPx: Float,
)

internal data class GestureTuning(
    /** Travel a stroke must cover before it counts as a swipe rather than a tap or a wobble. */
    val minTravelPx: Float,
    /** Dwell at the end of an upward stroke that promotes home into recents. */
    val holdMs: Long = 240L,
    /** How still "still" has to be during that dwell. */
    val holdSlopPx: Float = 24f,
    /** Everything shorter and smaller than [minTravelPx] within this window is a tap. */
    val tapMs: Long = 220L,
)

/**
 * Turns strokes into navigation actions.
 *
 * Split out from the overlay purely so the rules are testable without a device or a window — the
 * touch plumbing around it is untestable in a JVM run, and the rules are where the bugs live.
 *
 * The mapping matches what stock Android trained everyone's thumbs on, so nothing here has to be
 * learned:
 *   - inward swipe from either side edge -> back
 *   - short swipe up from the bottom -> home
 *   - swipe up from the bottom and hold -> recents
 *   - swipe down on the bottom strip -> notification shade
 *   - long horizontal swipe along the bottom -> app switch
 */
internal object GestureClassifier {

    fun classify(stroke: NavStroke, tuning: GestureTuning): NavGesture {
        val dx = stroke.dxPx
        val dy = stroke.dyPx
        val ax = abs(dx)
        val ay = abs(dy)
        val min = tuning.minTravelPx

        return when (stroke.zone) {
            // Inward only. An outward flick from the edge is how a user drags a sheet or scrubs a
            // slider that starts flush against the bezel, and stealing it for back would be worse
            // than missing the occasional back.
            NavZone.LEFT_EDGE -> if (dx >= min && ax > ay) NavGesture.BACK else NavGesture.NONE
            NavZone.RIGHT_EDGE -> if (dx <= -min && ax > ay) NavGesture.BACK else NavGesture.NONE

            NavZone.BOTTOM -> when {
                dy <= -min && ay >= ax -> if (isHold(stroke, tuning)) NavGesture.RECENTS else NavGesture.HOME
                dy >= min && ay > ax -> NavGesture.NOTIFICATIONS
                // A deliberately higher bar than a plain swipe: the bottom strip is a few
                // millimetres tall, so a hurried up-swipe drifts sideways easily and must not land
                // on app-switch instead of home.
                ax >= min * 1.6f && ax > ay * 1.5f -> NavGesture.SWITCH_APP
                else -> NavGesture.NONE
            }
        }
    }

    /** A bare tap — worth replaying underneath the strip rather than swallowing. See [GestureNavPrefs.tapPassthrough]. */
    fun isTap(stroke: NavStroke, tuning: GestureTuning): Boolean =
        stroke.durationMs <= tuning.tapMs &&
            abs(stroke.dxPx) < tuning.minTravelPx * 0.5f &&
            abs(stroke.dyPx) < tuning.minTravelPx * 0.5f

    private fun isHold(stroke: NavStroke, tuning: GestureTuning): Boolean =
        stroke.stationaryMs >= tuning.holdMs && stroke.stationaryPx <= tuning.holdSlopPx
}
