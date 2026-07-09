package com.fran.teclas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * A UI-less bridge to the system document picker. An IME can't run `startActivityForResult`, so when the
 * user taps "Files…" in the attach sheet we launch this transparent activity, it runs ACTION_OPEN_DOCUMENT,
 * stages the picked file into our FileProvider cache, and hands it back to the running keyboard via
 * [AttachBridge]. The one unavoidable trip out of the app for arbitrary (non-media) files.
 */
class AttachPickerActivity : ComponentActivity() {

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { finish(); return@registerForActivityResult }
        // We hold a read grant here; stage the bytes now while we can, off the main thread.
        Thread {
            val (name, mime) = AttachContent.describe(this, uri)
            val staged = AttachContent.stage(this, uri, name)
            runOnUiThread {
                if (staged != null) AttachBridge.pending?.invoke(staged, mime, name)
                AttachBridge.pending = null
                finish()
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        val mimes = intent.getStringArrayExtra(EXTRA_MIME_TYPES)?.takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
        runCatching { picker.launch(mimes) }.onFailure { finish() }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_MIME_TYPES = "mime_types"
    }
}
