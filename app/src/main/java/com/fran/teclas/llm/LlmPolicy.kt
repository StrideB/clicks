package com.fran.teclas.llm

/**
 * The safety rules around the local models, kept deliberately free of Android types.
 *
 * [LocalLlmEngine] loads a native library in its initializer, so merely referencing it drags in
 * JNI and `android.util.Log` — neither of which exists on a JVM test runner. Policy this important
 * (it decides whether a 2.5 GB model may load at all) has to be testable without a device, so it
 * lives here as plain arithmetic and the engine delegates.
 */
object LlmPolicy {

    /**
     * Below this much total RAM, the quality model is refused and Bonsai serves instead.
     *
     * Gemma 3 4B Q4_K_M is ~2.5 GB of weights plus a KV cache and llama.cpp's own working set. The
     * weights are mmapped, so a device that cannot hold them does not cleanly OOM — it thrashes,
     * faulting pages back in on every token. That is the worst outcome available: slow *and* hot,
     * with no error to point at. 6 GB total leaves roughly 2-3 GB actually available on a modern
     * Android build, which is the floor where this stops paging constantly.
     */
    const val QUALITY_MIN_RAM_BYTES = 6L * 1024 * 1024 * 1024

    /**
     * How long a loaded model may sit unused before its memory goes back to the system.
     *
     * `unload()` existed but nothing ever called it, so the first generation of the day pinned the
     * weights for the lifetime of the process — and because the IME shares that process with the
     * launcher, it effectively never dies. A phone that ran one brief was still holding a
     * multi-gigabyte mapping hours later.
     */
    const val IDLE_RELEASE_MS = 5 * 60_000L

    /**
     * Headroom required *beyond* the weights before a model may be mapped in.
     *
     * llama.cpp needs a KV cache and a working set on top of the file, and the rest of the phone
     * still has to run. Without this margin the load "succeeds" and then everything, including the
     * launcher itself, pages against it.
     */
    const val LOAD_HEADROOM_BYTES = 1_200L * 1024 * 1024

    /** Whether a device with this much RAM may load the 4B model. */
    fun qualityAllowed(totalRamBytes: Long, lowRamDevice: Boolean): Boolean =
        !lowRamDevice && totalRamBytes >= QUALITY_MIN_RAM_BYTES

    /**
     * Whether a model of [modelBytes] can be afforded *right now*.
     *
     * [qualityAllowed] asks what the phone was built with; this asks what it has left this second,
     * and they are wildly different numbers on a device that has been awake for a while. Installed
     * RAM never changes, so that check was cached once and answered "yes, 11 GB" on a phone with
     * 462 MB free and 5 GB already in swap — and mapping 2.5 GB into that thrashes: page faults on
     * every token, kernel reclaim spinning, the SoC hot, and no error anywhere to point at.
     *
     * Must be consulted per load, never cached.
     */
    fun canAffordNow(availBytes: Long, lowMemory: Boolean, modelBytes: Long): Boolean =
        !lowMemory && availBytes >= modelBytes + LOAD_HEADROOM_BYTES

    /** Whether a loaded model has been idle long enough to free. Never true mid-generation. */
    fun shouldRelease(
        loaded: Boolean,
        busy: Boolean,
        lastUseMs: Long,
        nowMs: Long,
        idleMs: Long = IDLE_RELEASE_MS,
    ): Boolean = loaded && !busy && nowMs - lastUseMs >= idleMs
}
