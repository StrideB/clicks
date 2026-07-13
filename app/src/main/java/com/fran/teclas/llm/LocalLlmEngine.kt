package com.fran.teclas.llm

import android.content.Context
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

    private fun fileFor(context: Context, spec: ModelSpec): File =
        File(File(context.filesDir, DIR).apply { mkdirs() }, spec.file)

    private fun installed(context: Context, spec: ModelSpec) = fileFor(context, spec).length() == spec.bytes

    /** Prefer the quality model when downloaded, else the fast one, else none. */
    private fun activeSpec(context: Context): ModelSpec? =
        if (installed(context, GEMMA)) GEMMA else if (installed(context, BONSAI)) BONSAI else null

    fun modelInstalled(context: Context): Boolean = activeSpec(context) != null
    fun fastInstalled(context: Context): Boolean = installed(context, BONSAI)
    fun qualityInstalled(context: Context): Boolean = installed(context, GEMMA)
    val totalBytes: Long get() = BONSAI.bytes
    val qualityBytes: Long get() = GEMMA.bytes

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
                synchronized(this) { if (handle != 0L) { runCatching { nativeFree(handle) }; handle = 0 } }
                true
            } else { Log.w(TAG, "download incomplete: ${tmp.length()}/${spec.bytes}"); false }
        } catch (e: Exception) {
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

    fun generateBlocking(
        context: Context, prompt: String, maxTokens: Int, temperature: Double,
        json: Boolean = false, grammar: String? = null,
    ): String? {
        // Single-flight: llama.cpp generation takes seconds; if a call is already running, drop this
        // one instead of queueing on the native mutex. Piling requests up is what pinned every core
        // and heated the phone — one at a time, and callers get null (their own fallback) when busy.
        if (generating) { diag(context, "local generate SKIP (busy)"); return null }
        val h = loadedHandle(context) ?: return null
        generating = true
        val t0 = System.currentTimeMillis()
        return try {
            runCatching {
                val gbnf = grammar ?: if (json) JSON_GRAMMAR else null
                val out = nativeGenerate(h, prompt, maxTokens.coerceIn(1, 512), temperature.toFloat(), gbnf)
                    ?.trim()?.ifBlank { null }
                diag(context, "local generate: ${if (out == null) "EMPTY" else "ok len=${out.length}"} in ${System.currentTimeMillis() - t0}ms")
                out
            }.getOrElse {
                Log.w(TAG, "generate failed: ${it.message}")
                diag(context, "local generate failed: ${it.javaClass.simpleName}: ${it.message}")
                null
            }
        } finally {
            generating = false
        }
    }

    @Synchronized
    private fun loadedHandle(context: Context): Long? {
        if (handle != 0L) return handle
        val spec = activeSpec(context) ?: return null
        val f = fileFor(context, spec)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val t0 = System.currentTimeMillis()
        val h = runCatching { nativeLoad(f.absolutePath, N_CTX, threads) }.getOrDefault(0L)
        diag(context, "local model load: ${if (h == 0L) "FAILED" else "ok"} in ${System.currentTimeMillis() - t0}ms threads=$threads")
        if (h == 0L) { Log.w(TAG, "model load failed"); return null }
        handle = h
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
        }
    }

    @JvmStatic private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
    @JvmStatic private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, grammar: String?): String?
    @JvmStatic private external fun nativeFree(handle: Long)
}
