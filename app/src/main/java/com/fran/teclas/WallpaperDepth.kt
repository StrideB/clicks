package com.fran.teclas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * On-device foreground-subject extraction for the depth wallpaper. Runs ML Kit Subject
 * Segmentation ONCE per wallpaper — when the image is set or first decoded — and caches the
 * transparent cutout PNG to disk keyed by the wallpaper signature. After that, showing depth is
 * just compositing two cached bitmaps: no model, no sensors, no per-frame work. This is the
 * deliberate opposite of the parallax motion we removed for battery.
 *
 * Everything is wrapped so a device without the segmentation module (it downloads on demand via
 * Play services) simply yields no cutout — the launcher falls back to the flat wallpaper.
 */
object WallpaperDepth {
    private const val TAG = "TeclasWallpaperDepth"
    private const val CACHE_DIR = "wallpaper_depth"

    // In-process memo so repeated render passes for the same wallpaper never touch disk.
    @Volatile private var memoKey: String? = null
    @Volatile private var memoCutout: Bitmap? = null

    /** True once a segmentation attempt for [signature] came back empty (no subject / no module),
     *  so the render path stops re-kicking a job that will not produce a cutout. */
    @Volatile private var emptyKey: String? = null

    fun cachedCutout(signature: String): Bitmap? =
        if (memoKey == signature) memoCutout else null

    fun knownEmpty(signature: String): Boolean = emptyKey == signature

    /**
     * Returns the subject cutout for [bitmap], from memory → disk → a fresh segmentation pass.
     * Null when there is no confident subject or the segmenter is unavailable. Safe to call off
     * the main thread; the ML Kit call itself is async and awaited here.
     */
    suspend fun cutoutFor(context: Context, bitmap: Bitmap, signature: String): Bitmap? {
        memoCutout?.let { if (memoKey == signature && !it.isRecycled) return it }

        val file = cacheFile(context, signature)
        if (file.exists()) {
            val cached = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            if (cached != null) {
                memoKey = signature; memoCutout = cached
                return cached
            }
        }

        val cutout = runCatching { segment(bitmap) }
            .onFailure { Log.w(TAG, "segmentation failed", it) }
            .getOrNull()

        if (cutout == null) {
            emptyKey = signature
            return null
        }
        // Persist for next launch; a write failure is non-fatal (we still have it in memory).
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { cutout.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { Log.w(TAG, "cutout cache write failed", it) }
        memoKey = signature; memoCutout = cutout
        emptyKey = null
        return cutout
    }

    /** Drop cached cutouts (memory only) — called when depth is turned off so stale bitmaps free. */
    fun clearMemory() {
        memoKey = null
        memoCutout = null
        emptyKey = null
    }

    private suspend fun segment(bitmap: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            suspendCancellableCoroutine { cont ->
                segmenter.process(image)
                    .addOnSuccessListener { result -> cont.resume(result.foregroundBitmap) }
                    .addOnFailureListener { e -> Log.w(TAG, "process failed", e); cont.resume(null) }
                cont.invokeOnCancellation { runCatching { segmenter.close() } }
            }
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
