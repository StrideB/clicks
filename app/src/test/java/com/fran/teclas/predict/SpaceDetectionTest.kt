package com.fran.teclas.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM checks for Space detection — the exact logic behind "it keeps going to Home
 * even though I'm abroad". No Android dependencies: [SpaceManager.detectIn] takes explicit
 * inputs so the decision is deterministic and testable.
 */
class SpaceDetectionTest {

    private val spaces = SpaceManager.defaults()

    private fun snap(
        hourBucket: Int = 3,
        placeKind: PlaceKind = PlaceKind.UNKNOWN,
        placeId: String = "unknown",
        driving: Boolean = false,
        headphones: Boolean = false,
        away: Boolean = false,
        weekend: Boolean = false,
    ) = ContextSnapshot(
        hourBucket = hourBucket, dayOfWeek = if (weekend) 6 else 2, isWeekend = weekend,
        placeId = placeId, placeKind = placeKind, driving = driving, btDevice = BtDevice.NONE,
        headphones = headphones, charging = false, mediaPlaying = false,
        calendar = CalendarProximity.FREE, lastApp = null, prevApp = null,
        timestamp = 0L, awayFromHome = away,
    )

    @Test fun `at home resolves to Home`() {
        val d = SpaceManager.detectIn(spaces, snap(placeKind = PlaceKind.HOME), null, null)
        assertEquals("home", d.space.id)
        assertTrue(d.strong)
    }

    @Test fun `abroad with no place match resolves to Travel, not Home`() {
        // The reported bug: unknown place + away-from-home used to fall back to Home.
        val d = SpaceManager.detectIn(spaces, snap(away = true), null, previousId = "home")
        assertEquals("travel", d.space.id)
        assertTrue("away Travel must count as a strong context", d.strong)
    }

    @Test fun `airport place resolves to Travel`() {
        val d = SpaceManager.detectIn(spaces, snap(placeKind = PlaceKind.AIRPORT), null, null)
        assertEquals("travel", d.space.id)
    }

    @Test fun `manual lock overrides everything`() {
        val d = SpaceManager.detectIn(spaces, snap(placeKind = PlaceKind.HOME), lockedId = "travel", previousId = null)
        assertEquals("travel", d.space.id)
        assertTrue(d.locked)
    }

    @Test fun `driving beats a weaker match by specificity and priority`() {
        val d = SpaceManager.detectIn(spaces, snap(driving = true, headphones = true), null, null)
        assertEquals("driving", d.space.id)
    }

    @Test fun `home context is unaffected by the away feature key`() {
        // Home-learned context keys must still match once away=false again.
        assertEquals(snap(placeKind = PlaceKind.HOME).contextKey(),
            snap(placeKind = PlaceKind.HOME).contextKey())
        assertTrue(snap(away = true).contextKey().endsWith("|away"))
        assertTrue(!snap(away = false).contextKey().endsWith("|away"))
    }

    @Test fun `away adds a feature without dropping the base features`() {
        val home = snap()
        val away = snap(away = true)
        assertTrue(away.features().containsAll(home.features()))
        assertTrue(away.features().contains("away"))
    }
}
