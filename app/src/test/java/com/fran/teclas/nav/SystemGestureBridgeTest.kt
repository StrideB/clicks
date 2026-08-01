package com.fran.teclas.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The vendor split decides which settings key the launcher writes, and writing the wrong one is a
 * silent no-op — the toggle flips, nothing changes, and the user is left where they started. Build
 * identity is the only input, so it is worth pinning down what counts as a Xiaomi.
 */
class SystemGestureBridgeTest {

    @Test fun `xiaomi's own brands take the FSG path`() {
        // MANUFACTURER stays "Xiaomi" across all three, but BRAND is what the device reports as
        // itself, and either field alone has to be enough.
        assertEquals(SystemGestureBridge.Vendor.XIAOMI, SystemGestureBridge.vendorFor("Xiaomi", "Redmi"))
        assertEquals(SystemGestureBridge.Vendor.XIAOMI, SystemGestureBridge.vendorFor("Xiaomi", "POCO"))
        assertEquals(SystemGestureBridge.Vendor.XIAOMI, SystemGestureBridge.vendorFor("Redmi", "Redmi"))
    }

    @Test fun `brand casing does not matter`() {
        assertEquals(SystemGestureBridge.Vendor.XIAOMI, SystemGestureBridge.vendorFor("XIAOMI", "xiaomi"))
        assertEquals(SystemGestureBridge.Vendor.XIAOMI, SystemGestureBridge.vendorFor("xiaomi", "poco"))
    }

    @Test fun `everything else is treated as stock-shaped`() {
        assertEquals(SystemGestureBridge.Vendor.AOSP_LIKE, SystemGestureBridge.vendorFor("Google", "google"))
        assertEquals(SystemGestureBridge.Vendor.AOSP_LIKE, SystemGestureBridge.vendorFor("samsung", "samsung"))
        assertEquals(SystemGestureBridge.Vendor.AOSP_LIKE, SystemGestureBridge.vendorFor("HONOR", "HONOR"))
    }

    @Test fun `an unreported build identity falls back to stock-shaped`() {
        // Build statics are null in a plain JVM run and empty on some emulator images; neither may
        // throw, and neither may claim a device is a Xiaomi.
        assertEquals(SystemGestureBridge.Vendor.AOSP_LIKE, SystemGestureBridge.vendorFor(null, null))
        assertEquals(SystemGestureBridge.Vendor.AOSP_LIKE, SystemGestureBridge.vendorFor("", ""))
    }
}
