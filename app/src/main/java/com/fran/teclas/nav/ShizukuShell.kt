package com.fran.teclas.nav

import android.util.Log
import com.fran.teclas.ShizukuPinner
import rikka.shizuku.Shizuku

/**
 * Runs a shell command with Shizuku's adb-level uid.
 *
 * [SystemGestureBridge] gets most of what it needs from `Settings.Global`/`Settings.Secure` writes,
 * which only cost the one-time WRITE_SECURE_SETTINGS grant. Two things it cannot get that way:
 *
 *  - `cmd overlay enable-exclusive …` — the only lever that actually moves stock Android between
 *    3-button and gesture navigation. `Settings.Secure.navigation_mode` is an *output* of that
 *    choice in AOSP (SystemUI publishes it after reading `config_navBarInteractionMode` from the
 *    enabled overlay), so writing it alone changes nothing on a stock ROM.
 *  - `cmd statusbar disable-for-setup` — the one supported way left on Android 12+ to take the
 *    navigation bar off the screen system-wide.
 *
 * Both need shell uid, so both need Shizuku. Everything degrades: with Shizuku absent, [exec]
 * returns [Result.unavailable] and the caller falls back to the settings-write path and to showing
 * the user a command they can paste into adb themselves.
 *
 * `Shizuku.newProcess` is library-internal in the Shizuku API artifact (`@RestrictTo`), so it is
 * reached reflectively rather than by adding a compile-time dependency on a restricted symbol.
 */
internal object ShizukuShell {
    private const val TAG = "ShizukuShell"
    private const val TIMEOUT_MS = 8_000L

    data class Result(
        val ran: Boolean,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val ok: Boolean get() = ran && exitCode == 0

        /** stderr when there is one, else stdout — whichever actually explains a failure. */
        fun message(): String = stderr.trim().ifEmpty { stdout.trim() }

        companion object {
            fun unavailable(reason: String) = Result(ran = false, exitCode = -1, stdout = "", stderr = reason)
        }
    }

    fun isReady(): Boolean = ShizukuPinner.isReady()

    /**
     * Run [command] through `sh -c` and wait for it (bounded by [TIMEOUT_MS] — a hung privileged
     * process must never wedge the settings screen). Never throws.
     */
    fun exec(command: String): Result {
        if (!isReady()) return Result.unavailable("Shizuku is not running or not granted")
        return runCatching {
            val process = newProcess(arrayOf("sh", "-c", command))
                ?: return Result.unavailable("Shizuku process API unavailable")
            try {
                val out = process.inputStream.bufferedReader().use { it.readText() }
                val err = process.errorStream.bufferedReader().use { it.readText() }
                val finished = process.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroy()
                    return Result(ran = true, exitCode = -1, stdout = out, stderr = "timed out")
                }
                Result(ran = true, exitCode = process.exitValue(), stdout = out, stderr = err)
            } finally {
                runCatching { process.destroy() }
            }
        }.onFailure { Log.w(TAG, "exec failed: $command", it) }
            .getOrElse { Result.unavailable(it.message ?: it.javaClass.simpleName) }
    }

    private fun newProcess(cmd: Array<String>): Process? = runCatching {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        method.invoke(null, cmd, null, null) as? Process
    }.onFailure { Log.w(TAG, "newProcess reflection failed", it) }.getOrNull()
}
