package com.fran.teclas

import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * General-purpose on-device generation via Gemini Nano (Android AICore, ML Kit Prompt API).
 * This is the nano-first path [GeminiClient] tries before any network call: free, offline,
 * nothing leaves the device. Like [com.fran.teclas.keyboard.NanoProofreadEngine] it is graceful
 * everywhere — on a device without AICore [ready] stays false and callers fall through to cloud.
 *
 * The Nano base model downloads once via AICore; the first generation attempt triggers that
 * download and returns null until it completes.
 */
class NanoPromptEngine(context: android.content.Context) {

    private val appContext = context.applicationContext
    private val client by lazy { Generation.getClient() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True once AICore reports the model AVAILABLE. Cheap to read from any thread. */
    @Volatile var ready: Boolean = false
        private set
    @Volatile private var unsupported = false
    @Volatile private var downloading = false
    // AICore only serves the foreground app (ErrorCode 30). When the IME types into another app
    // every keystroke would burn a blocked call — back off briefly instead of retrying each key.
    @Volatile private var backgroundBlockedUntil = 0L

    /** Kick the availability check (and model download if needed) without blocking anyone. */
    fun warmUp() {
        scope.launch { ensureReady() }
    }

    private suspend fun ensureReady(): Boolean {
        if (ready) return true
        if (unsupported) return false
        return runCatching {
            val status = client.checkStatus()
            Log.i(TAG, "nano feature status: $status (0=unavailable 1=downloadable 2=downloading 3=available)")
            writeStatusFile("status=$status ready=${status == FeatureStatus.AVAILABLE}")
            when (status) {
                FeatureStatus.AVAILABLE -> { ready = true; true }
                FeatureStatus.DOWNLOADABLE -> { startDownload(); false }
                FeatureStatus.DOWNLOADING -> false
                else -> { unsupported = true; false }
            }
        }.getOrElse {
            Log.w(TAG, "nano status check failed: ${it.message}")
            writeStatusFile("status=error ${it.javaClass.simpleName}: ${it.message}")
            unsupported = true
            false
        }
    }

    /** Vivo (and some other OEMs) suppress app logcat entirely, so the availability probe also
     *  lands in files/nano_status.txt — readable via `adb shell run-as` on debug builds. */
    private fun writeStatusFile(line: String) {
        runCatching {
            val f = java.io.File(appContext.filesDir, "nano_status.txt")
            if (f.length() > 16_384) f.delete() // keep the diagnostic log tiny
            f.appendText("$line at ${System.currentTimeMillis()}\n")
        }
    }

    private fun startDownload() {
        if (downloading) return
        downloading = true
        scope.launch {
            runCatching { client.download().collect { } }
                .onFailure { Log.w(TAG, "nano download failed: ${it.message}") }
            downloading = false
            runCatching { if (client.checkStatus() == FeatureStatus.AVAILABLE) ready = true }
        }
    }

    /**
     * One prompt → Nano's reply, or null when Nano can't serve (unsupported device, model still
     * downloading, inference error) so the caller falls through to its cloud path. Blocking —
     * call off the main thread (matches [GeminiClient]'s blocking contract).
     */
    fun generateBlocking(prompt: String, maxTokens: Int, temperature: Double): String? = runBlocking {
        if (System.currentTimeMillis() < backgroundBlockedUntil) return@runBlocking null
        if (!ensureReady()) return@runBlocking null
        runCatching {
            val response = client.generateContent(
                generateContentRequest(TextPart(prompt)) {
                    // Nano hard-caps output at 256 tokens (cloud callers ask for up to 900) —
                    // clamp instead of erroring; shorter on-device answers beat no answer.
                    this.temperature = temperature.toFloat().coerceIn(0f, 1f)
                    maxOutputTokens = maxTokens.coerceIn(1, 256)
                }
            )
            val candidate = response.candidates.firstOrNull()
            val out = candidate?.text?.trim()?.ifBlank { null }
            if (out == null) writeStatusFile("generate empty: finishReason=${candidate?.finishReason} candidates=${response.candidates.size}")
            out
        }.getOrElse {
            Log.w(TAG, "nano generate failed: ${it.message}")
            writeStatusFile("generate error: ${it.javaClass.simpleName}: ${it.message}")
            if (it.message?.contains("Background usage", ignoreCase = true) == true) {
                backgroundBlockedUntil = System.currentTimeMillis() + 5_000L
            }
            null
        }
    }

    private companion object { const val TAG = "NanoPrompt" }
}
