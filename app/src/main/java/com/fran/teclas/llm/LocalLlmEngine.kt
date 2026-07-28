package com.fran.teclas.llm

import android.content.Context
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-device LLM via llama.cpp — the policy-free tier that serves the keyboard inside ANY app.
 * Android's AICore blocks every GenAI API for a non-foreground caller (ErrorCode 30), which an
 * IME typing into another app always is; llama.cpp runs in-process and answers to no one.
 *
 * Model: PrismML Bonsai 1.7B, 1-bit GGUF (~237MB, Apache 2.0, ungated) — downloaded once on
 * user request into filesDir/llm/, mmap-loaded lazily on first generation.
 */
object LocalLlmEngine {

    private const val TAG = "LocalLlm"
    private const val DIR = "llm"
    private const val N_CTX = 2048

    // Two swappable generative models. Bonsai = small, fast, 1-bit (the free default). Gemma 3 4B =
    // the quality tier (Pro): much better writing/reasoning, but ~2.5GB and slower. When both are
    // downloaded the quality model wins.
    private data class ModelSpec(val file: String, val url: String, val bytes: Long)
    private val BONSAI = ModelSpec(
        "bonsai-1.7b-q1_0.gguf",
        "https://huggingface.co/prism-ml/Bonsai-1.7B-gguf/resolve/main/Bonsai-1.7B-Q1_0.gguf",
        248_302_272L)
    private val GEMMA = ModelSpec(
        "gemma-3-4b-it-Q4_K_M.gguf",
        "https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf",
        2_489_894_016L)

    init {
        runCatching { System.loadLibrary("teclasllm") }
            .onFailure { Log.w(TAG, "native lib missing: ${it.message}") }
    }

    @Volatile private var handle: Long = 0
    @Volatile var downloading: Boolean = false
        private set
    @Volatile var downloadedBytes: Long = 0
        private set
    @Volatile private var installedCache: ModelSpec? = null
    @Volatile private var installCheckQueued: Boolean = false

    private fun fileFor(context: Context, spec: ModelSpec): File =
        File(File(context.filesDir, DIR).apply { mkdirs() }, spec.file)

    private fun installed(context: Context, spec: ModelSpec) = fileFor(context, spec).length() == spec.bytes

    /** Prefer the quality model when downloaded AND runnable here, else the fast one, else none.
     *  Checking RAM here keeps [ready] and [modelInstalled] honest: a 4B file sitting on a phone
     *  that will never load it is not a model the app has. */
    private fun activeSpec(context: Context): ModelSpec? =
        if (installed(context, GEMMA) && deviceSupportsQuality(context)) GEMMA
        else if (installed(context, BONSAI)) BONSAI
        else null

    private fun computeActiveSpec(context: Context): ModelSpec? =
        activeSpec(context).also { installedCache = it }

    private fun queueInstallCheck(context: Context) {
        if (installCheckQueued) return
        synchronized(this) {
            if (installCheckQueued) return
            installCheckQueued = true
        }
        Thread({
            try {
                computeActiveSpec(context.applicationContext)
            } finally {
                installCheckQueued = false
            }
        }, "teclas-llm-install-check").start()
    }

    fun modelInstalled(context: Context): Boolean {
        if (installedCache != null) return true
        if (Looper.myLooper() == Looper.getMainLooper()) {
            queueInstallCheck(context)
            return false
        }
        return computeActiveSpec(context) != null
    }

    fun fastInstalled(context: Context): Boolean = installed(context, BONSAI)
    fun qualityInstalled(context: Context): Boolean = installed(context, GEMMA)
    val totalBytes: Long get() = BONSAI.bytes
    val qualityBytes: Long get() = GEMMA.bytes

    // ---------------------------------------------------------------- hardware safety

    /**
     * Below this much total RAM, the quality model is refused and Bonsai serves instead.
     *
     * Gemma 3 4B Q4_K_M is ~2.5GB of weights plus a KV cache and llama.cpp's own working set. The
     * weights are mmapped, so a device that cannot hold them does not cleanly OOM — it thrashes,
     * faulting pages back in on every token. That is the worst outcome available: slow *and* hot,
     * with no error to point at. 6GB total leaves roughly 2-3GB actually available on a modern
     * Android build, which is the floor where this stops paging constantly.
     */
    const val QUALITY_MIN_RAM_BYTES = 6L * 1024 * 1024 * 1024

    /** Pure policy half of [deviceSupportsQuality], so the threshold can be tested without a device. */
    fun qualityAllowed(totalRamBytes: Long, lowRamDevice: Boolean): Boolean =
        !lowRamDevice && totalRamBytes >= QUALITY_MIN_RAM_BYTES

    // Installed RAM does not change while the app runs, so this is worth exactly one lookup.
    @Volatile private var qualityFits: Boolean? = null

    /** Whether this phone has the headroom to run the 4B model at all. */
    fun deviceSupportsQuality(context: Context): Boolean {
        qualityFits?.let { return it }
        val fits = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            qualityAllowed(info.totalMem, am.isLowRamDevice)
        }.getOrDefault(false)
        qualityFits = fits
        return fits
    }

    // ---------------------------------------------------------------- idle release

    /**
     * How long a loaded model may sit unused before its memory goes back to the system.
     *
     * [unload] existed but nothing ever called it, so the first generation of the day pinned the
     * weights for the lifetime of the process — and because the IME shares this process with the
     * launcher, that process effectively never dies. A phone that ran one brief was still holding
     * a multi-gigabyte mapping hours later.
     */
    private const val IDLE_RELEASE_MS = 5 * 60_000L

    @Volatile private var lastUseMs = 0L
    @Volatile private var releaseScheduled = false

    /** Pure decision half of [releaseIfIdle]. */
    fun shouldRelease(loaded: Boolean, busy: Boolean, lastUseMs: Long, nowMs: Long,
                      idleMs: Long = IDLE_RELEASE_MS): Boolean =
        loaded && !busy && nowMs - lastUseMs >= idleMs

    /** Free the model if it has been idle long enough. Returns true when it actually unloaded. */
    fun releaseIfIdle(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!shouldRelease(handle != 0L, generating, lastUseMs, nowMs)) return false
        unload()
        return true
    }

    /**
     * One sleeping thread per idle window, not a poll loop: generation is rare and bursty, so the
     * cheapest correct thing is to wait out the window once and re-check. If another generation
     * lands meanwhile, [lastUseMs] moves and the check re-arms instead of freeing a hot model.
     */
    private fun scheduleIdleRelease() {
        if (releaseScheduled) return
        synchronized(this) {
            if (releaseScheduled) return
            releaseScheduled = true
        }
        Thread({
            try {
                while (true) {
                    val waitMs = IDLE_RELEASE_MS - (System.currentTimeMillis() - lastUseMs)
                    if (waitMs <= 0) break
                    try { Thread.sleep(waitMs) } catch (_: InterruptedException) { return@Thread }
                }
                releaseIfIdle()
            } finally {
                releaseScheduled = false
            }
        }, "teclas-llm-idle-release").apply { isDaemon = true }.start()
    }

    /**
     * Memory pressure from the system. Deliberately NOT tied to activity onPause/onResume: this
     * engine exists to serve the keyboard while it types into *other* apps, so the launcher being
     * paused is the normal case, not an idle one. Releasing there would unload the model out from
     * under an active IME session — the process is shared.
     */
    fun onTrimMemory(level: Int) {
        val urgent = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        if (urgent && !generating) unload()
    }

    fun ready(context: Context): Boolean = handle != 0L || modelInstalled(context)

    /** Download a model in the background (call off the main thread). [quality] picks Gemma 3 4B
     *  (Pro) vs Bonsai. Resumable via HTTP Range. On success the active handle is reset so the new
     *  model loads on the next call. Returns true when complete. */
    fun downloadModel(context: Context, quality: Boolean = false): Boolean {
        val spec = if (quality) GEMMA else BONSAI
        if (installed(context, spec)) return true
        if (downloading) return false
        downloading = true
        downloadedBytes = 0
        val target = fileFor(context, spec)
        val tmp = File(target.parentFile, "${spec.file}.part")
        return try {
            val have = tmp.length()
            val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 60_000
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
                instanceFollowRedirects = true
            }
            val append = conn.responseCode == 206
            if (!append && have > 0) tmp.delete()
            conn.inputStream.use { input ->
                java.io.FileOutputStream(tmp, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var total = tmp.length()
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        downloadedBytes = total
                    }
                }
            }
            if (tmp.length() == spec.bytes) {
                tmp.renameTo(target)
                installedCache = spec
                synchronized(this) { if (handle != 0L) { runCatching { nativeFree(handle) }; handle = 0; loadedSpec = null } }
                true
            } else { installedCache = computeActiveSpec(context); Log.w(TAG, "download incomplete: ${tmp.length()}/${spec.bytes}"); false }
        } catch (e: Exception) {
            installedCache = computeActiveSpec(context)
            Log.w(TAG, "download failed: ${e.message}")
            false
        } finally {
            downloading = false
        }
    }

    /**
     * A permissive JSON GBNF: any object/array, used when callers ask for JSON output. The
     * grammar masks logits during sampling, so malformed JSON is impossible — stronger than
     * cloud "JSON mode", which merely asks nicely.
     */
    private val JSON_GRAMMAR = """
        root ::= object | array
        value ::= object | array | string | number | ("true" | "false" | "null") ws
        object ::= "{" ws ( string ":" ws value ("," ws string ":" ws value)* )? "}" ws
        array ::= "[" ws ( value ("," ws value)* )? "]" ws
        string ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}) )* "\"" ws
        number ::= ("-"? ([0-9] | [1-9] [0-9]{0,15})) ("." [0-9]+)? ([eE] [-+]? [0-9]{1,3})? ws
        ws ::= [ \t\n]{0,20}
    """.trimIndent()

    /** One prompt → the local model's reply, or null (model missing / load failed / error).
     *  [json] constrains sampling with the JSON grammar; [grammar] overrides with a custom GBNF.
     *  Blocking and serialized; call from a background thread. */
    @Volatile private var generating = false
    @Volatile private var loadedSpec: ModelSpec? = null

    /** Which model to serve: [quality] prefers Gemma (better, slower), else Bonsai (fast). Falls
     *  back to whichever is actually installed. */
    private fun specFor(context: Context, quality: Boolean): ModelSpec? {
        // A phone without the RAM for the 4B model never gets handed it, even if the file is on
        // disk — an mmapped model it cannot hold thrashes rather than fails, so the guard has to
        // sit here at load time and not only in the download UI.
        val wantQuality = quality && deviceSupportsQuality(context)
        val preferred = if (wantQuality) GEMMA else BONSAI
        val other = if (wantQuality) BONSAI else GEMMA
        return when {
            installed(context, preferred) -> preferred
            installed(context, other) && (other != GEMMA || deviceSupportsQuality(context)) -> other
            else -> null
        }
    }

    /** One model loaded at a time. Interactive callers pass quality=false (fast Bonsai); the brief
     *  passes quality=true (Gemma). If the requested tier differs from what's loaded, swap it in
     *  (~1-2s) — cheap since the quality model is only wanted a couple of times a day. */
    fun generateBlocking(
        context: Context, prompt: String, maxTokens: Int, temperature: Double,
        json: Boolean = false, grammar: String? = null, quality: Boolean = false,
    ): String? {
        // Single-flight: llama.cpp generation takes seconds; if a call is already running, drop this
        // one instead of queueing on the native mutex. Piling requests up is what pinned every core
        // and heated the phone — one at a time, and callers get null (their own fallback) when busy.
        if (generating) { diag(context, "local generate SKIP (busy)"); return null }
        val spec = specFor(context, quality) ?: return null
        val h = loadedHandle(context, spec) ?: return null
        generating = true
        val t0 = System.currentTimeMillis()
        return try {
            runCatching {
                val gbnf = grammar ?: if (json) JSON_GRAMMAR else null
                val out = nativeGenerate(h, prompt, maxTokens.coerceIn(1, 512), temperature.toFloat(), gbnf)
                    ?.trim()?.ifBlank { null }
                diag(context, "local generate (${spec.file.take(6)}): ${if (out == null) "EMPTY" else "ok len=${out.length}"} in ${System.currentTimeMillis() - t0}ms")
                out
            }.getOrElse {
                Log.w(TAG, "generate failed: ${it.message}")
                diag(context, "local generate failed: ${it.javaClass.simpleName}: ${it.message}")
                null
            }
        } finally {
            generating = false
            lastUseMs = System.currentTimeMillis()
            scheduleIdleRelease()
        }
    }

    @Synchronized
    private fun loadedHandle(context: Context, spec: ModelSpec): Long? {
        if (handle != 0L && loadedSpec == spec) return handle
        // Different model wanted (or first load) — free the old one, load the requested spec.
        if (handle != 0L) { runCatching { nativeFree(handle) }; handle = 0; loadedSpec = null }
        val f = fileFor(context, spec)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val t0 = System.currentTimeMillis()
        val h = runCatching { nativeLoad(f.absolutePath, N_CTX, threads) }.getOrDefault(0L)
        diag(context, "local model load (${spec.file.take(6)}): ${if (h == 0L) "FAILED" else "ok"} in ${System.currentTimeMillis() - t0}ms")
        if (h == 0L) { Log.w(TAG, "model load failed"); return null }
        handle = h
        loadedSpec = spec
        return h
    }

    /** Vivo suppresses app logcat — mirror into the shared diagnostic file. */
    private fun diag(context: Context, line: String) {
        runCatching {
            val f = File(context.filesDir, "nano_status.txt")
            if (f.length() > 16_384) f.delete()
            f.appendText("$line at ${System.currentTimeMillis()}\n")
        }
    }

    fun unload() {
        synchronized(this) {
            if (handle != 0L) { runCatching { nativeFree(handle) }; handle = 0 }
            // Clear the tier too, or a later load can match a spec whose handle is already gone.
            loadedSpec = null
        }
    }

    @JvmStatic private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
    @JvmStatic private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, grammar: String?): String?
    @JvmStatic private external fun nativeFree(handle: Long)
}
