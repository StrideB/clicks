package com.fran.teclas.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gesture bar replaces the buttons that get a user off a screen, so a misread stroke is not a
 * cosmetic bug — it is "back went home" or "the bottom row of every app stopped responding". These
 * are the cases that decide which, kept away from the touch plumbing so they can actually be run.
 */
class GestureClassifierTest {

    private val tuning = GestureTuning(minTravelPx = 24f)

    private fun stroke(
        zone: NavZone,
        dx: Float = 0f,
        dy: Float = 0f,
        durationMs: Long = 120L,
        stationaryMs: Long = 0L,
        stationaryPx: Float = 0f,
    ) = NavStroke(zone, dx, dy, durationMs, stationaryMs, stationaryPx)

    private fun classify(s: NavStroke) = GestureClassifier.classify(s, tuning)

    // ---- edges -----------------------------------------------------------------------------

    @Test fun `an inward swipe from either edge is back`() {
        assertEquals(NavGesture.BACK, classify(stroke(NavZone.LEFT_EDGE, dx = 60f)))
        assertEquals(NavGesture.BACK, classify(stroke(NavZone.RIGHT_EDGE, dx = -60f)))
    }

    @Test fun `an outward swipe from an edge is left alone`() {
        // Sheets and edge-anchored sliders are dragged this way; stealing it would break them.
        assertEquals(NavGesture.NONE, classify(stroke(NavZone.LEFT_EDGE, dx = -60f)))
        assertEquals(NavGesture.NONE, classify(stroke(NavZone.RIGHT_EDGE, dx = 60f)))
    }

    @Test fun `a vertical drag inside an edge strip is not back`() {
        // Scrolling a list whose content runs to the bezel.
        assertEquals(NavGesture.NONE, classify(stroke(NavZone.LEFT_EDGE, dx = 30f, dy = -120f)))
    }

    @Test fun `a twitch short of the travel threshold does nothing`() {
        assertEquals(NavGesture.NONE, classify(stroke(NavZone.LEFT_EDGE, dx = 20f)))
    }

    // ---- bottom ----------------------------------------------------------------------------

    @Test fun `a swipe up from the bottom is home`() {
        assertEquals(NavGesture.HOME, classify(stroke(NavZone.BOTTOM, dy = -140f)))
    }

    @Test fun `a swipe up that ends in a hold is recents`() {
        assertEquals(
            NavGesture.RECENTS,
            classify(stroke(NavZone.BOTTOM, dy = -140f, durationMs = 500L, stationaryMs = 300L, stationaryPx = 4f))
        )
    }

    @Test fun `a slow swipe that never actually stops is still home`() {
        // Duration alone must not promote home to recents: a deliberate, unhurried swipe up is the
        // single most common gesture on the device.
        assertEquals(
            NavGesture.HOME,
            classify(stroke(NavZone.BOTTOM, dy = -140f, durationMs = 600L, stationaryMs = 300L, stationaryPx = 90f))
        )
    }

    @Test fun `a swipe down on the bottom strip opens notifications`() {
        assertEquals(NavGesture.NOTIFICATIONS, classify(stroke(NavZone.BOTTOM, dy = 60f)))
    }

    @Test fun `a long sideways swipe along the bottom switches apps`() {
        assertEquals(NavGesture.SWITCH_APP, classify(stroke(NavZone.BOTTOM, dx = 200f)))
        assertEquals(NavGesture.SWITCH_APP, classify(stroke(NavZone.BOTTOM, dx = -200f)))
    }

    @Test fun `an up swipe that drifts sideways still goes home`() {
        // The strip is a few millimetres tall, so a hurried thumb always drifts. Home is what the
        // user meant, and landing on the app switcher instead is the failure this guards.
        assertEquals(NavGesture.HOME, classify(stroke(NavZone.BOTTOM, dx = 50f, dy = -160f)))
    }

    // ---- taps ------------------------------------------------------------------------------

    @Test fun `a bare tap classifies as nothing and is recognised as a tap`() {
        val tap = stroke(NavZone.BOTTOM, dx = 2f, dy = 3f, durationMs = 90L)
        assertEquals(NavGesture.NONE, classify(tap))
        assertTrue(GestureClassifier.isTap(tap, tuning))
    }

    @Test fun `a long press is not a tap to replay`() {
        // Replaying it as a 48ms tap would turn a long-press into a click on whatever is underneath.
        val press = stroke(NavZone.BOTTOM, durationMs = 900L)
        assertFalse(GestureClassifier.isTap(press, tuning))
    }

    @Test fun `a stroke that travelled is not a tap`() {
        assertFalse(GestureClassifier.isTap(stroke(NavZone.BOTTOM, dy = -80f), tuning))
    }
}
