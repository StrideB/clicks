package com.fran.teclas.keyboard

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.proofreading.Proofreader
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreadingRequest
import kotlinx.coroutines.guava.await

/**
 * On-device grammar/spelling proofreading powered by Gemini Nano (Android AICore) via the ML Kit
 * GenAI Proofreading API. Free, fully offline, and a real language model — a big step up from the
 * dictionary-only [Proofreader]. It is graceful everywhere: on a device without AICore (or with an
 * unlocked bootloader) [isSupported] stays false and callers keep the dictionary path.
 *
 * The feature-specific model downloads once via AICore; [proofread] triggers that download on first
 * use and reports [Result.Downloading] so the UI can say so.
 */
class NanoProofreadEngine(context: Context) {

    private val appContext = context.applicationContext
    private val client: Proofreader by lazy {
        Proofreading.getClient(
            ProofreaderOptions.builder(appContext)
                .setInputType(ProofreaderOptions.InputType.KEYBOARD)
                .setLanguage(ProofreaderOptions.Language.ENGLISH)
                .build()
        )
    }

    sealed class Result {
        data class Corrected(val text: String) : Result()
        object Unchanged : Result()
        object Downloading : Result()   // model is fetching; try again shortly
        object Unsupported : Result()   // device has no AICore / not eligible
        object Error : Result()
    }

    @Volatile private var supported: Boolean? = null

    /** Cheap cached check so the UI only offers proofread where Gemini Nano can actually run. */
    suspend fun isSupported(): Boolean {
        supported?.let { return it }
        return runCatching {
            val s = client.checkFeatureStatus().await()
            (s == FeatureStatus.AVAILABLE || s == FeatureStatus.DOWNLOADABLE).also { supported = it }
        }.getOrElse { supported = false; false }
    }

    /** Proofread [text] on-device. Never throws; returns a [Result] the caller can render. */
    suspend fun proofread(text: String): Result {
        if (text.isBlank()) return Result.Unchanged
        return runCatching {
            when (client.checkFeatureStatus().await()) {
                FeatureStatus.UNAVAILABLE -> { supported = false; Result.Unsupported }
                FeatureStatus.DOWNLOADABLE -> { startDownload(); Result.Downloading }
                FeatureStatus.DOWNLOADING -> Result.Downloading
                else -> {
                    val out = client.runInference(ProofreadingRequest.builder(text).build())
                        .await().results.firstOrNull()?.text?.trim()
                    if (out.isNullOrEmpty() || out == text.trim()) Result.Unchanged
                    else Result.Corrected(out)
                }
            }
        }.getOrElse { Log.w(TAG, "proofread failed: ${it.message}"); Result.Error }
    }

    private fun startDownload() {
        runCatching {
            client.downloadFeature(object : DownloadCallback {
                override fun onDownloadStarted(bytesToDownload: Long) {}
                override fun onDownloadProgress(totalBytesDownloaded: Long) {}
                override fun onDownloadCompleted() { supported = true }
                override fun onDownloadFailed(e: com.google.mlkit.genai.common.GenAiException) {
                    Log.w(TAG, "nano download failed: ${e.message}")
                }
            })
        }
    }

    fun close() { runCatching { client.close() } }

    private companion object { const val TAG = "NanoProofread" }
}
