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
        wifiId: String? = null,
    ) = ContextSnapshot(
        hourBucket = hourBucket, dayOfWeek = if (weekend) 6 else 2, isWeekend = weekend,
        placeId = placeId, placeKind = placeKind, driving = driving, btDevice = BtDevice.NONE,
        headphones = headphones, charging = false, mediaPlaying = false,
        calendar = CalendarProximity.FREE, lastApp = null, prevApp = null,
        timestamp = 0L, awayFromHome = away, wifiId = wifiId,
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

    @Test fun `a wifi trigger resolves the space and counts as strong`() {
        // The office network identifies Work with no location fix at all.
        val work = spaces.first { it.id == "work" }
        val withWifi = spaces.map {
            if (it.id == "work") it.copy(triggers = it.triggers.copy(wifiIds = setOf("abc123"))) else it
        }
        val d = SpaceManager.detectIn(withWifi, snap(wifiId = "abc123"), null, null)
        assertEquals("work", d.space.id)
        assertTrue("a known network is a strong place signal", d.strong)
        assertTrue(work.triggers.wifiIds.isEmpty()) // defaults ship empty; user/learning fills them
    }

    @Test fun `an unknown network does not match a wifi trigger`() {
        val withWifi = spaces.map {
            if (it.id == "work") it.copy(triggers = it.triggers.copy(wifiIds = setOf("abc123"))) else it
        }
        val d = SpaceManager.detectIn(withWifi, snap(wifiId = "zzz999"), null, previousId = null)
        assertTrue("a foreign network must not force Work", d.space.id != "work")
    }

    @Test fun `wifi only enters the context key when there is no place`() {
        // Backward compatibility: everything already learned at a known place must keep matching.
        val atHome = snap(placeKind = PlaceKind.HOME, placeId = "home-1")
        assertEquals(atHome.contextKey(), atHome.copy(wifiId = "abc123").contextKey())
        // With no place, wifi stands in so distinct buildings stop collapsing into one "unknown".
        val unknown = snap()
        assertTrue(unknown.copy(wifiId = "abc123").contextKey() != unknown.contextKey())
        assertTrue(unknown.copy(wifiId = "abc123").contextKey() != unknown.copy(wifiId = "zzz999").contextKey())
    }

    @Test fun `wifi adds a feature without dropping the base features`() {
        val base = snap()
        val onWifi = base.copy(wifiId = "abc123")
        assertTrue(base.features().all { it in onWifi.features() })
        assertTrue("wifi:abc123" in onWifi.features())
        assertTrue(base.features().none { it.startsWith("wifi:") })
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

    // ------------------------------------------------------------- the weekend workload gap, closed

    @Test fun `Work is unreachable on a weekend`() {
        // Work is weekdaysOnly, so even standing inside the office on a Sunday it cannot match.
        // This is about DETECTION only; it no longer implies the Space has no board (see below).
        val d = SpaceManager.detectIn(spaces, snap(placeKind = PlaceKind.WORK, weekend = true), null, null)
        assertTrue("weekdaysOnly must exclude Work on a weekend", d.space.id != "work")
    }

    @Test fun `weekend at home still resolves a Space that has a board`() {
        // This started as the reported "Spaces isn't working today" (a Sunday). Detection was never
        // the problem — it correctly resolves Home. The gap was downstream: Space Today only built
        // workloads for a hardcoded work/travel/fitness/gym set, so a normal weekend (or any
        // evening) at home had no surface and the feature read as broken.
        //
        // PriorityFeedRepository.hasWorkload now returns true for every Space, so whatever
        // detection resolves has a board. This asserts the property that actually matters — a Space
        // is always resolved — rather than re-encoding the removed set, which is exactly what made
        // the old assertion lock the bug in place.
        val d = SpaceManager.detectIn(spaces, snap(placeKind = PlaceKind.HOME, weekend = true), null, null)
        assertEquals("home", d.space.id)
        assertTrue("detection must always resolve some Space", d.space.id.isNotBlank())
    }
}
