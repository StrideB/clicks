package com.fran.teclas.predict

import com.fran.teclas.predict.SemanticSlots.Bucket
import com.fran.teclas.predict.SemanticSlots.Role
import com.fran.teclas.predict.SemanticSlots.Slot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A dock slot holds a role, not an app — so the app can change with context while the *position*
 * never moves. These tests are really about that promise: whatever the rules decide, the row must
 * come back the same length, in the same order, with nothing blank.
 */
class SemanticSlotsTest {

    private fun ctx(
        hourBucket: Int = 3,
        isWeekend: Boolean = false,
        placeKind: PlaceKind = PlaceKind.HOME,
        driving: Boolean = false,
        bt: BtDevice = BtDevice.NONE,
        calendar: CalendarProximity = CalendarProximity.FREE,
        band: DistanceBand = DistanceBand.HOME,
    ) = ContextSnapshot(
        hourBucket = hourBucket,
        dayOfWeek = if (isWeekend) 5 else 1,
        isWeekend = isWeekend,
        placeId = "home",
        placeKind = placeKind,
        driving = driving,
        btDevice = bt,
        headphones = bt == BtDevice.HEADPHONES,
        charging = false,
        mediaPlaying = false,
        calendar = calendar,
        lastApp = null,
        prevApp = null,
        timestamp = 0L,
        distanceBand = band,
    )

    // ---------------------------------------------------------------- buckets

    @Test fun `driving or a car connection is a commute`() {
        assertEquals(Bucket.COMMUTE, SemanticSlots.bucket(ctx(driving = true)))
        assertEquals(Bucket.COMMUTE, SemanticSlots.bucket(ctx(bt = BtDevice.CAR)))
    }

    @Test fun `being genuinely far away is travel`() {
        assertEquals(Bucket.TRAVEL, SemanticSlots.bucket(ctx(band = DistanceBand.REGIONAL)))
        assertEquals(Bucket.TRAVEL, SemanticSlots.bucket(ctx(band = DistanceBand.FAR)))
    }

    @Test fun `commute outranks travel`() {
        // Driving to another city is still a commute for dock purposes — the phone is in a car.
        assertEquals(Bucket.COMMUTE, SemanticSlots.bucket(ctx(driving = true, band = DistanceBand.FAR)))
    }

    @Test fun `work needs a workday`() {
        assertEquals(Bucket.WORK, SemanticSlots.bucket(ctx(placeKind = PlaceKind.WORK)))
        assertEquals(Bucket.WORK, SemanticSlots.bucket(ctx(calendar = CalendarProximity.MEETING_SOON)))
        // Same place on a Saturday is not work.
        assertEquals(Bucket.DEFAULT, SemanticSlots.bucket(ctx(placeKind = PlaceKind.WORK, isWeekend = true)))
    }

    @Test fun `evening and night wind down, weekends included`() {
        assertEquals(Bucket.EVENING, SemanticSlots.bucket(ctx(hourBucket = 5)))
        assertEquals(Bucket.EVENING, SemanticSlots.bucket(ctx(hourBucket = 0)))
        assertEquals(Bucket.EVENING, SemanticSlots.bucket(ctx(hourBucket = 5, isWeekend = true)))
    }

    @Test fun `an ordinary midday is the default`() {
        assertEquals(Bucket.DEFAULT, SemanticSlots.bucket(ctx()))
    }

    // ---------------------------------------------------------------- resolving

    private val audio = Slot(
        role = Role.AUDIO,
        byBucket = mapOf(Bucket.COMMUTE to "podcasts", Bucket.EVENING to "whitenoise"),
        fallback = "spotify",
    )

    @Test fun `the same slot means different apps in different contexts`() {
        assertEquals("podcasts", SemanticSlots.resolve(audio, ctx(driving = true)))
        assertEquals("whitenoise", SemanticSlots.resolve(audio, ctx(hourBucket = 5)))
    }

    @Test fun `an unmapped bucket falls back rather than blanking`() {
        // A blank dock position is worse than a stale one — the position is what the thumb aims at.
        assertEquals("spotify", SemanticSlots.resolve(audio, ctx(placeKind = PlaceKind.WORK)))
        assertEquals("spotify", SemanticSlots.resolve(audio, ctx()))
    }

    // ---------------------------------------------------------------- the row

    @Test fun `the row keeps its length and order whatever the context`() {
        val row = listOf(
            audio,
            Slot(Role.MESSAGING, mapOf(Bucket.WORK to "slack"), fallback = "whatsapp"),
            Slot(Role.CAMERA, fallback = "camera"),
        )
        listOf(ctx(), ctx(driving = true), ctx(hourBucket = 5), ctx(placeKind = PlaceKind.WORK)).forEach { c ->
            val out = SemanticSlots.resolveRow(row, c)
            assertEquals("positions must never move", 3, out.size)
            assertEquals("camera stays put", "camera", out[2])
        }
        assertEquals(listOf("podcasts", "whatsapp", "camera"), SemanticSlots.resolveRow(row, ctx(driving = true)))
        assertEquals(listOf("spotify", "slack", "camera"), SemanticSlots.resolveRow(row, ctx(placeKind = PlaceKind.WORK)))
    }

    @Test fun `two roles never resolve to the same app twice`() {
        // Two identical icons side by side reads as a bug and wastes the scarcest space on screen.
        val row = listOf(
            Slot(Role.AUDIO, mapOf(Bucket.DEFAULT to "spotify"), fallback = "spotify"),
            Slot(Role.COMMUTE, mapOf(Bucket.DEFAULT to "spotify"), fallback = "maps"),
        )
        assertEquals(listOf("spotify", "maps"), SemanticSlots.resolveRow(row, ctx()))
    }

    @Test fun `an empty row is not a crash`() {
        assertEquals(emptyList<String>(), SemanticSlots.resolveRow(emptyList(), ctx()))
    }
}
