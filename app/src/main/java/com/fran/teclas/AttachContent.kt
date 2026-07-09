package com.fran.teclas

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File

/**
 * Attach-without-leaving: enumerate on-device media and drop the chosen file straight into the chat or
 * email the user is already writing, via the platform's rich-content path ([InputConnection.commitContent]).
 *
 * Two hard Android limits shape this and are enforced here rather than hidden:
 *  - The *target* editor decides what it accepts. [EditorInfoCompat.getContentMimeTypes] tells us; we
 *    only offer content the field will actually take, and [accepts] filters the media grid to match.
 *    Images are near-universally accepted (Gmail, WhatsApp, Signal, Messages…); arbitrary files rarely.
 *  - We can't grant read access on a MediaStore uri we don't own, so [commit] copies the bytes into our
 *    FileProvider cache and hands the target a grantable content:// uri.
 *
 * MediaStore (with READ_MEDIA_IMAGES, already in the manifest) lets us list media in-process — no system
 * picker, no leaving the app. Non-media documents aren't visible this way; those need the system picker.
 */
object AttachContent {

    data class MediaItem(val uri: Uri, val mime: String, val displayName: String)

    /** MIME types the current editor advertises it can receive as inline content (may be empty). */
    fun acceptedMimeTypes(editorInfo: EditorInfo?): Array<String> =
        editorInfo?.let { EditorInfoCompat.getContentMimeTypes(it) } ?: emptyArray()

    /** True when this field can receive content at all (so the Attach action is worth showing). */
    fun editorAcceptsContent(editorInfo: EditorInfo?): Boolean =
        acceptedMimeTypes(editorInfo).any { it.isNotBlank() }

    /** True when [mime] matches one of the editor's accepted patterns (wildcards honored). */
    fun accepts(editorInfo: EditorInfo?, mime: String): Boolean =
        acceptedMimeTypes(editorInfo).any { ClipDescription.compareMimeTypes(mime, it) }

    /**
     * Recent images from MediaStore, newest first. Cheap projection (no bytes) — thumbnails load lazily.
     * Returns empty if the media permission isn't granted (query throws) so callers can prompt.
     */
    fun recentImages(context: Context, limit: Int = 30): List<MediaItem> {
        val out = ArrayList<MediaItem>(limit)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE
        )
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        runCatching {
            context.contentResolver.query(collection, projection, null, null, order)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(idCol)
                    out.add(
                        MediaItem(
                            uri = Uri.withAppendedPath(collection, id.toString()),
                            mime = c.getString(mimeCol) ?: "image/*",
                            displayName = c.getString(nameCol) ?: "image"
                        )
                    )
                }
            }
        }
        return out
    }

    /**
     * Copy [source] (a MediaStore or picked-document uri) into our FileProvider cache and commit it into
     * the current field. Must run off the main thread (it reads bytes). Returns true on success.
     *
     * [requestFlags] carries the read grant on API 25+; the target app is granted temporary read access
     * to the staged uri for as long as it needs it.
     */
    fun commit(
        context: Context,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        source: Uri,
        mime: String,
        displayName: String
    ): Boolean {
        val staged = stage(context, source, displayName) ?: return false
        return commitFile(context, ic, editorInfo, staged, mime, displayName)
    }

    /** Commit a file that already lives under our FileProvider "attach" cache path. */
    fun commitFile(
        context: Context,
        ic: InputConnection?,
        editorInfo: EditorInfo?,
        file: File,
        mime: String,
        displayName: String
    ): Boolean {
        if (ic == null || editorInfo == null) return false
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return false
        val description = ClipDescription(displayName, arrayOf(mime))
        val info = InputContentInfoCompat(uri, description, null)
        val flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
        return runCatching { InputConnectionCompat.commitContent(ic, editorInfo, info, flags, null) }
            .getOrDefault(false)
    }

    /** Copy [source] into the FileProvider "attach" cache and return the staged file (or null). */
    fun stage(context: Context, source: Uri, displayName: String): File? = runCatching {
        val dir = File(context.cacheDir, "attach").apply { mkdirs() }
        // Keep the extension so the target infers type; sanitize the name for the filesystem.
        val safe = displayName.substringAfterLast('/').ifBlank { "file" }.take(80)
        val out = File(dir, "${System.nanoTime()}_$safe")
        context.contentResolver.openInputStream(source)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: return null
        out
    }.getOrNull()

    /** Best-effort display name + MIME for a picked document uri (from the system picker). */
    fun describe(context: Context, uri: Uri): Pair<String, String> {
        var name = "file"
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i)?.let { name = it }
            }
        }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        return name to mime
    }
}

/**
 * Single-process handoff from [AttachPickerActivity] (which runs the system document picker) back to the
 * running IME. The activity stages the picked file and invokes [pending]; the IME sets [pending] just
 * before launching. Kept tiny and volatile — it holds at most one in-flight pick.
 */
object AttachBridge {
    @Volatile var pending: ((File, String, String) -> Unit)? = null
}
