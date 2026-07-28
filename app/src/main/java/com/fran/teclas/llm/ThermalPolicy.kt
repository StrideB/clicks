package com.fran.teclas.llm

/**
 * How much local model work the phone can afford right now.
 *
 * Generation is the single most expensive thing this app does, and until now it ran identically on
 * a cool phone at 90% and a hot one at 8%. This is the gate: pure arithmetic over the signals
 * Android already publishes, so the rules are testable without a device and without waiting for a
 * phone to actually get hot.
 */
object ThermalPolicy {

    enum class Tier {
        /** Anything goes, including the 4B quality model. */
        FULL,

        /** Cheap work only — the fast model, never the quality one. */
        REDUCED,

        /** No local generation at all. Callers fall back to their non-model path. */
        FROZEN,
    }

    // Mirrors android.os.PowerManager.THERMAL_STATUS_*, kept as plain ints so this file needs no
    // Android imports and the tests need no device.
    const val THERMAL_NONE = 0
    const val THERMAL_LIGHT = 1
    const val THERMAL_MODERATE = 2
    const val THERMAL_SEVERE = 3

    const val BATTERY_REDUCED_PCT = 30
    const val BATTERY_FROZEN_PCT = 15

    /**
     * @param thermalStatus `PowerManager.getCurrentThermalStatus()`, or [THERMAL_NONE] when the
     *   platform does not report it. Deliberately the platform's own signal rather than a skin
     *   temperature read out of sysfs: the thresholds differ per device, the vendor already knows
     *   them, and a hardcoded "42°C" is wrong on most phones in one direction or the other.
     * @param batteryPct 0-100, or -1 when unknown.
     * @param powerSaveMode the user has explicitly asked the system to conserve.
     * @param charging on external power.
     */
    fun tier(thermalStatus: Int, batteryPct: Int, powerSaveMode: Boolean, charging: Boolean): Tier {
        // Heat outranks everything, including being plugged in — charging is itself a heat source,
        // so "it's on the charger" is a reason to be more careful here, never less.
        if (thermalStatus >= THERMAL_SEVERE) return Tier.FROZEN
        if (thermalStatus >= THERMAL_MODERATE) return Tier.REDUCED

        // An explicit user request to conserve is not something to reason around.
        if (powerSaveMode) return Tier.FROZEN

        // Battery limits only matter on battery. Charging at 8% is a phone filling up, not a phone
        // about to die, and refusing to work there would be a bug the user could never explain.
        if (!charging && batteryPct >= 0) {
            if (batteryPct <= BATTERY_FROZEN_PCT) return Tier.FROZEN
            if (batteryPct <= BATTERY_REDUCED_PCT) return Tier.REDUCED
        }

        // Light thermal pressure is normal under any sustained use and is not worth degrading for;
        // it is the state a phone reaches from a video call.
        return Tier.FULL
    }

    /** Whether a request for the big model should be served by the fast one instead. */
    fun downgradeQuality(tier: Tier): Boolean = tier != Tier.FULL

    /** Whether to refuse local generation entirely. */
    fun blocked(tier: Tier): Boolean = tier == Tier.FROZEN
}
