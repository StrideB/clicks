package com.fran.teclas.llm

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-device text embeddings via the SAME llama.cpp runtime that runs the generative model — no
 * second SDK, no license gate, no separate tokenizer file (the GGUF carries it). Model: nomic-embed
 * -text-v1.5 (~84MB Q4, Apache 2.0, ungated), so semantic search auto-downloads with zero setup.
 *
 * nomic wants task prefixes ("search_query:" for queries, "search_document:" for stored items) — we
 * add them here. Output is one L2-normalized 768-d vector per text, ready for cosine similarity.
 */
object EmbedEngine {

    private const val TAG = "EmbedEngine"
    private const val DIR = "embed"
    private const val MODEL_FILE = "nomic-embed-text-v1.5.Q4_K_M.gguf"
    private const val MODEL_URL =
        "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF/resolve/main/nomic-embed-text-v1.5.Q4_K_M.gguf"
    private const val MODEL_BYTES = 84_106_624L

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
    val totalBytes: Long get() = MODEL_BYTES

    /** Download the embedder in the background (call off the main thread). Resumable via HTTP Range. */
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
            Log.w(TAG, "download failed: ${e.message}"); false
        } finally {
            downloading = false
        }
    }

    @Synchronized
    private fun loadedHandle(context: Context): Long? {
        if (handle != 0L) return handle
        val f = modelFile(context)
        if (f.length() != MODEL_BYTES) return null
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val h = runCatching { nativeLoadEmbedder(f.absolutePath, threads) }.getOrDefault(0L)
        if (h == 0L) { Log.w(TAG, "embedder load failed"); return null }
        handle = h
        return h
    }

    /** Embed [text] → an L2-normalized vector, or null if the model isn't ready. [isQuery] picks
     *  nomic's task prefix. Blocking + serialized in native; call from a background thread. */
    fun embed(context: Context, text: String, isQuery: Boolean): FloatArray? {
        if (text.isBlank()) return null
        val h = loadedHandle(context) ?: run { diag(context, "embed: model not loaded"); return null }
        val prefix = if (isQuery) "search_query: " else "search_document: "
        return runCatching { nativeEmbed(h, prefix + text) }
            .getOrElse { Log.w(TAG, "embed failed: ${it.message}"); diag(context, "embed exc: ${it.message}"); null }
            ?.also { if (!diagOnce) { diagOnce = true; diag(context, "embed OK dim=${it.size} [${it.take(3).joinToString(",") { v -> "%.3f".format(v) }}...]") } }
    }

    @Volatile private var diagOnce = false
    private fun diag(context: Context, line: String) {
        runCatching {
            val f = java.io.File(context.filesDir, "nano_status.txt")
            if (f.length() > 16_384) f.delete()
            f.appendText("[embed] $line at ${System.currentTimeMillis()}\n")
        }
    }

    @JvmStatic private external fun nativeLoadEmbedder(path: String, threads: Int): Long
    @JvmStatic private external fun nativeEmbed(handle: Long, text: String): FloatArray?
    @JvmStatic private external fun nativeFree(handle: Long)
}
