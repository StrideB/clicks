package com.fran.teclas.galaxy

import android.os.Build

/**
 * Samsung Galaxy device detection. One UI ships Samsung-specific surfaces (Now Bar, DeX,
 * taskbar) on top of stock Android; everything Teclas integrates with is standard AOSP API,
 * but knowing we're on One UI lets copy and defaults name the surface the user will actually
 * see ("Now Bar" on a Galaxy, "lock screen" elsewhere).
 */
object GalaxyDevice {

    val isSamsung: Boolean
        get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /**
     * One UI version as Samsung encodes it (8.5 → 80500), or null off-Galaxy. Read from
     * `Build.VERSION.SEM_PLATFORM_INT`, a public field Samsung adds to every One UI build;
     * reflection because it doesn't exist in the AOSP SDK we compile against.
     */
    val oneUiVersion: Int? by lazy {
        if (!isSamsung) return@lazy null
        runCatching {
            val sem = Build.VERSION::class.java.getDeclaredField("SEM_PLATFORM_INT").getInt(null)
            if (sem >= 90000) sem - 90000 else null
        }.getOrNull()
    }

    /** "8.5" for 80500. */
    fun oneUiLabel(): String? = oneUiVersion?.let { "${it / 10000}.${(it % 10000) / 100}" }

    /**
     * Android 16 Live Updates (promoted ongoing notifications). One UI 8+ renders these in
     * the Now Bar on the lock screen and as a status chip; stock Android 16 pins them on the
     * lock screen and status bar.
     */
    fun supportsLiveUpdates(): Boolean = Build.VERSION.SDK_INT >= 36

    /** Where a Live Update lands on this device, for user-facing copy. */
    fun liveUpdateSurfaceLabel(): String =
        if (isSamsung && (oneUiVersion ?: 0) >= 80000) "Now Bar" else "lock screen"
}
