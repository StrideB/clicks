package com.fran.teclas.keyboard

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.rewriting.Rewriter
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewritingRequest
import kotlinx.coroutines.guava.await

/**
 * On-device message rewriting (Gemini Nano via the ML Kit Rewriting API) for the IME's AI Polish.
 * Unlike the raw Prompt API — which AICore blocks when the keyboard types into another app
 * (ErrorCode 30, "background usage") — the task-specific Rewriting API is built for keyboards and
 * serves from any host app. Mirrors [NanoProofreadEngine]: graceful everywhere, [isSupported]
 * stays false without AICore and callers keep the cloud path.
 */
class NanoRewriteEngine(context: Context) {

    private val appContext = context.applicationContext
    private val client: Rewriter by lazy {
        Rewriting.getClient(
            RewriterOptions.builder(appContext)
                .setOutputType(RewriterOptions.OutputType.REPHRASE)
                .setLanguage(RewriterOptions.Language.ENGLISH)
                .build()
        )
    }

    sealed class Result {
        data class Rewritten(val text: String) : Result()
        object Unchanged : Result()
        object Downloading : Result()   // model is fetching; try again shortly
        object Unsupported : Result()   // device has no AICore / not eligible
        object Error : Result()
    }

    @Volatile private var supported: Boolean? = null

    /** Cheap cached check so callers only try Nano where it can actually run. */
    suspend fun isSupported(): Boolean {
        supported?.let { return it }
        return runCatching {
            val s = client.checkFeatureStatus().await()
            (s == FeatureStatus.AVAILABLE || s == FeatureStatus.DOWNLOADABLE).also { supported = it }
        }.getOrElse { supported = false; false }
    }

    /** Rewrite [text] on-device. Never throws; returns a [Result] the caller can render. */
    suspend fun rewrite(text: String): Result {
        if (text.isBlank()) return Result.Unchanged
        return runCatching {
            when (client.checkFeatureStatus().await()) {
                FeatureStatus.UNAVAILABLE -> { supported = false; Result.Unsupported }
                FeatureStatus.DOWNLOADABLE -> { startDownload(); Result.Downloading }
                FeatureStatus.DOWNLOADING -> Result.Downloading
                else -> {
                    val out = client.runInference(RewritingRequest.builder(text).build())
                        .await().results.firstOrNull()?.text?.trim()
                    if (out.isNullOrEmpty() || out == text.trim()) Result.Unchanged
                    else Result.Rewritten(out)
                }
            }
        }.getOrElse { Log.w(TAG, "rewrite failed: ${it.message}"); Result.Error }
    }

    private fun startDownload() {
        runCatching {
            client.downloadFeature(object : DownloadCallback {
                override fun onDownloadStarted(bytesToDownload: Long) {}
                override fun onDownloadProgress(totalBytesDownloaded: Long) {}
                override fun onDownloadCompleted() { supported = true }
                override fun onDownloadFailed(e: com.google.mlkit.genai.common.GenAiException) {
                    Log.w(TAG, "nano rewrite download failed: ${e.message}")
                }
            })
        }
    }

    fun close() { runCatching { client.close() } }

    private companion object { const val TAG = "NanoRewrite" }
}
