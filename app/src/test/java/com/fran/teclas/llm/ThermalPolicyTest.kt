package com.fran.teclas.llm

import com.fran.teclas.llm.ThermalPolicy.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the phone may run a local model. These rules only ever matter on a device that is already
 * hot or nearly flat, which is precisely when they are hardest to test by hand — so they are
 * arithmetic, and this is where they get exercised.
 */
class ThermalPolicyTest {

    private fun tier(
        thermal: Int = ThermalPolicy.THERMAL_NONE,
        battery: Int = 80,
        powerSave: Boolean = false,
        charging: Boolean = false,
    ) = ThermalPolicy.tier(thermal, battery, powerSave, charging)

    @Test fun `a cool phone with charge runs everything`() {
        assertEquals(Tier.FULL, tier())
        assertEquals(Tier.FULL, tier(battery = 100, charging = true))
    }

    @Test fun `light thermal pressure is not worth degrading for`() {
        // This is the state a phone reaches from a video call; treating it as trouble would mean
        // the feature is off during ordinary use.
        assertEquals(Tier.FULL, tier(thermal = ThermalPolicy.THERMAL_LIGHT))
    }

    @Test fun `moderate heat drops to the cheap model`() {
        assertEquals(Tier.REDUCED, tier(thermal = ThermalPolicy.THERMAL_MODERATE))
    }

    @Test fun `severe heat stops local generation`() {
        assertEquals(Tier.FROZEN, tier(thermal = ThermalPolicy.THERMAL_SEVERE))
        assertEquals(Tier.FROZEN, tier(thermal = ThermalPolicy.THERMAL_SEVERE + 1))
    }

    @Test fun `being plugged in never excuses heat`() {
        // Charging is itself a heat source, so it is a reason to be more careful, not less.
        assertEquals(Tier.FROZEN, tier(thermal = ThermalPolicy.THERMAL_SEVERE, battery = 100, charging = true))
        assertEquals(Tier.REDUCED, tier(thermal = ThermalPolicy.THERMAL_MODERATE, battery = 100, charging = true))
    }

    @Test fun `power saver is an explicit request and is obeyed`() {
        assertEquals(Tier.FROZEN, tier(powerSave = true))
        assertEquals(Tier.FROZEN, tier(powerSave = true, battery = 100, charging = true))
    }

    @Test fun `a low battery degrades then stops`() {
        assertEquals(Tier.FULL, tier(battery = 31))
        assertEquals(Tier.REDUCED, tier(battery = 30))
        assertEquals(Tier.REDUCED, tier(battery = 16))
        assertEquals(Tier.FROZEN, tier(battery = 15))
        assertEquals(Tier.FROZEN, tier(battery = 3))
    }

    @Test fun `battery limits do not apply while charging`() {
        // A phone at 8% on the charger is filling up, not about to die. Refusing to work there
        // would be a bug the user could never explain.
        assertEquals(Tier.FULL, tier(battery = 8, charging = true))
    }

    @Test fun `an unknown battery level is not treated as empty`() {
        assertEquals(Tier.FULL, tier(battery = -1))
    }

    @Test fun `the derived questions match the tiers`() {
        assertFalse(ThermalPolicy.downgradeQuality(Tier.FULL))
        assertTrue(ThermalPolicy.downgradeQuality(Tier.REDUCED))
        assertTrue(ThermalPolicy.downgradeQuality(Tier.FROZEN))

        assertFalse(ThermalPolicy.blocked(Tier.FULL))
        assertFalse(ThermalPolicy.blocked(Tier.REDUCED))
        assertTrue(ThermalPolicy.blocked(Tier.FROZEN))
    }
}
