package com.fran.teclas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * On-device foreground-subject extraction for the depth wallpaper. Runs ML Kit Subject
 * Segmentation ONCE per wallpaper — when the image is set or first decoded — and caches the
 * transparent cutout PNG to disk keyed by the wallpaper signature. After that, showing depth is
 * just compositing two cached bitmaps: no model, no sensors, no per-frame work.
 *
 * The segmentation model ships as an optional Play Services module that downloads on first use.
 * Until it lands, process() fails — so a transient failure must RETRY, not mark the wallpaper
 * permanently subject-less. "Known empty" is reserved for the case where the segmenter ran fine
 * and genuinely found no foreground.
 */
object WallpaperDepth {
    private const val TAG = "TeclasWallpaperDepth"
    // v3: earlier builds could cache the WRONG subject under a wallpaper's key — a mid-change stale
    // bitmap segmented and stored as the new wallpaper's cutout (the floating-old-wallpaper artifact),
    // or a different-resolution source. Bumping the dir discards those poisoned cutouts; they
    // re-segment from the correct, current display bitmap.
    private const val CACHE_DIR = "wallpaper_depth_v3"
    private const val MAX_ATTEMPTS = 6          // ~ up to 6 tries while the model downloads
    private const val RETRY_DELAY_MS = 2500L

    @Volatile private var memoKey: String? = null
    @Volatile private var memoCutout: Bitmap? = null
    @Volatile private var emptyKey: String? = null

    private sealed interface SegOutcome {
        data class Done(val bitmap: Bitmap?) : SegOutcome   // segmenter ran; bitmap may be null (no subject)
        data object Retry : SegOutcome                       // transient (model downloading / error) — try again
    }

    fun cachedCutout(signature: String): Bitmap? =
        if (memoKey == signature) memoCutout?.takeUnless { it.isRecycled } else null

    fun knownEmpty(signature: String): Boolean = emptyKey == signature

    /**
     * Returns the subject cutout for [bitmap], from memory → disk → fresh segmentation (retried
     * while the model downloads). Null when there is genuinely no subject or the segmenter never
     * became available. Safe to call off the main thread.
     */
    suspend fun cutoutFor(context: Context, bitmap: Bitmap, signature: String): Bitmap? {
        memoCutout?.let { if (memoKey == signature && !it.isRecycled) return it }

        val file = cacheFile(context, signature)
        if (file.exists()) {
            val cached = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            if (cached != null) {
                Log.i(TAG, "cutout: disk cache hit ${cached.width}x${cached.height}")
                memoKey = signature; memoCutout = cached
                return cached
            }
        }

        Log.i(TAG, "cutout: segmenting ${bitmap.width}x${bitmap.height} …")
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            val outcome: SegOutcome = runCatching { segment(bitmap) }
                .onFailure { Log.w(TAG, "segment attempt $attempt threw", it) }
                .getOrElse { SegOutcome.Retry }
            when (outcome) {
                is SegOutcome.Done -> {
                    val cutout = outcome.bitmap
                    if (cutout == null) {
                        Log.i(TAG, "segment ran, no subject found — marking empty")
                        emptyKey = signature
                        return null
                    }
                    Log.i(TAG, "segment ok: cutout ${cutout.width}x${cutout.height} (attempt $attempt)")
                    runCatching {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { cutout.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }.onFailure { Log.w(TAG, "cutout cache write failed", it) }
                    memoKey = signature; memoCutout = cutout; emptyKey = null
                    return cutout
                }
                SegOutcome.Retry -> {
                    Log.i(TAG, "segment attempt $attempt: model not ready, retrying in ${RETRY_DELAY_MS}ms")
                    delay(RETRY_DELAY_MS)
                }
            }
        }
        // Ran out of attempts (model still downloading). Do NOT mark empty — a later toggle/resume
        // will re-kick and by then the model is usually present.
        Log.w(TAG, "segment: model never became available after $MAX_ATTEMPTS attempts")
        return null
    }

    fun clearMemory() {
        memoKey = null
        memoCutout = null
        emptyKey = null
    }

    private suspend fun segment(bitmap: Bitmap): SegOutcome = withContext(Dispatchers.Default) {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            suspendCancellableCoroutine<SegOutcome> { cont ->
                segmenter.process(image)
                    .addOnSuccessListener { result -> cont.resume(SegOutcome.Done(result.foregroundBitmap)) }
                    .addOnFailureListener { e ->
                        // A missing/loading module or a Play-services hiccup is transient → retry.
                        Log.w(TAG, "process failed (will retry): ${e.message}")
                        cont.resume(SegOutcome.Retry)
                    }
                cont.invokeOnCancellation { runCatching { segmenter.close() } }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "segment setup failed (will retry)", t)
            SegOutcome.Retry
        } finally {
            runCatching { segmenter.close() }
        }
    }

    private fun cacheFile(context: Context, signature: String): File =
        File(File(context.cacheDir, CACHE_DIR), hash(signature) + ".png")

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
}
