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
    private const val MODEL_FILE = "bonsai-1.7b-q1_0.gguf"
    private const val MODEL_URL =
        "https://huggingface.co/prism-ml/Bonsai-1.7B-gguf/resolve/main/Bonsai-1.7B-Q1_0.gguf"
    private const val MODEL_BYTES = 248_302_272L
    private const val N_CTX = 2048

    init {
        runCatching { System.loadLibrary("teclasllm") }
            .onFailure { Log.w(TAG, "native lib missing: ${it.message}") }
    }

    @Volatile private var handle: Long = 0
    @Volatile var downloading: Boolean = false
        private set
    @Volatile var downloadedBytes: Long = 0
        private set

    private fun modelFile(context: Context): File =
        File(File(context.filesDir, DIR).apply { mkdirs() }, MODEL_FILE)

    fun modelInstalled(context: Context): Boolean = modelFile(context).length() == MODEL_BYTES

    fun ready(context: Context): Boolean = handle != 0L || modelInstalled(context)

    /** Download the model in the background (caller runs this on a worker thread). Safe to
     *  re-invoke: resumes a partial file via HTTP Range. Returns true when complete. */
    fun downloadModel(context: Context): Boolean {
        if (modelInstalled(context)) return true
        if (downloading) return false
        downloading = true
        val target = modelFile(context)
        val tmp = File(target.parentFile, "$MODEL_FILE.part")
        return try {
            val have = tmp.length()
            val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
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
            if (tmp.length() == MODEL_BYTES) { tmp.renameTo(target); true }
            else { Log.w(TAG, "download incomplete: ${tmp.length()}/$MODEL_BYTES"); false }
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${e.message}")
            false
        } finally {
            downloading = false
        }
    }

    /** One prompt → the local model's reply, or null (model missing / load failed / error).
     *  Blocking and serialized; call from a background thread. */
    fun generateBlocking(context: Context, prompt: String, maxTokens: Int, temperature: Double): String? {
        val h = loadedHandle(context) ?: return null
        val t0 = System.currentTimeMillis()
        return runCatching {
            val out = nativeGenerate(h, prompt, maxTokens.coerceIn(1, 512), temperature.toFloat())
                ?.trim()?.ifBlank { null }
            diag(context, "local generate: ${if (out == null) "EMPTY" else "ok len=${out.length}"} in ${System.currentTimeMillis() - t0}ms")
            out
        }.getOrElse {
            Log.w(TAG, "generate failed: ${it.message}")
            diag(context, "local generate failed: ${it.javaClass.simpleName}: ${it.message}")
            null
        }
    }

    @Synchronized
    private fun loadedHandle(context: Context): Long? {
        if (handle != 0L) return handle
        val f = modelFile(context)
        if (f.length() != MODEL_BYTES) return null
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
    @JvmStatic private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float): String?
    @JvmStatic private external fun nativeFree(handle: Long)
}
