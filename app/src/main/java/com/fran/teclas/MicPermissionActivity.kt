package com.fran.teclas

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Tiny, invisible helper so the keyboard (an InputMethodService, which can't call requestPermissions
 * itself) can get RECORD_AUDIO for voice typing. The IME launches this when the mic is tapped without
 * the permission; it asks, then finishes straight back to whatever the user was typing in.
 */
class MicPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        finish()
        overridePendingTransition(0, 0)
    }
}
