package com.fran.teclas.fold

import android.app.Activity
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.Locale

/**
 * Honor/MagicOS-specific fold signal.
 *
 * MagicOS publishes the physical hinge state as a public [Settings.Global] integer
 * ("hn_fold_screen_state") that any app can read and observe with no SDK, no partner
 * agreement, and no special permission. This is useful as a *supplement* to
 * [observeFoldPosture] (Jetpack WindowInfoTracker): on some Honor ROMs the
 * androidx.window pipeline only ships the older `sidecar` backend, so a
 * [androidx.window.layout.FoldingFeature] may arrive late or not at all. Reading the
 * OEM key gives a fast, authoritative "am I open" answer we can fall back on.
 *
 * Values observed in Honor's foldable adaptation guide:
 *   0 = unknown, 1 = expanded (unfolded), 2 = folded, 3 = half-folded.
 *
 * NOTE: This is a coarse posture only — it carries no hinge bounds, so it cannot
 * replace the WindowManager path for tabletop/hinge-gutter layout. Treat it as a
 * reliability net, not the source of truth. If both signals disagree, prefer the
 * WindowManager [FoldPosture] when it carries a real FoldingFeature.
 */
enum class HonorFoldState {
    UNKNOWN, EXPANDED, FOLDED, HALF_FOLDED;

    /**
     * Best-effort mapping onto the app's [FoldPosture] model. Lossy: EXPANDED has no
     * hinge gutter, HALF_FOLDED has no hinge rect (callers that need real bounds must
     * keep using the WindowManager posture). Returns null for UNKNOWN so callers can
     * ignore it and keep their current posture.
     */
    fun toFoldPosture(): FoldPosture? = when (this) {
        EXPANDED -> FoldPosture.Inner(hingeGutter = null)
        FOLDED -> FoldPosture.Cover
        HALF_FOLDED -> null // no bounds available; let WindowManager own half-open
        UNKNOWN -> null
    }
}

object HonorFold {
    /** Public MagicOS global key for hinge state. */
    const val KEY_FOLD_SCREEN_STATE = "hn_fold_screen_state"

    private fun isHonor(): Boolean {
        val id = listOf(Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.MODEL)
            .joinToString(" ").lowercase(Locale.US)
        return id.contains("honor")
    }

    private fun readRaw(context: Context): Int =
        try {
            Settings.Global.getInt(context.contentResolver, KEY_FOLD_SCREEN_STATE, -1)
        } catch (_: Throwable) {
            -1
        }

    private fun Int.toHonorFoldState(): HonorFoldState = when (this) {
        1 -> HonorFoldState.EXPANDED
        2 -> HonorFoldState.FOLDED
        3 -> HonorFoldState.HALF_FOLDED
        else -> HonorFoldState.UNKNOWN
    }

    /**
     * True only on Honor hardware that actually publishes the key (some non-foldable
     * Honor phones won't). Cheap enough to gate a call to [observeHonorFoldState].
     */
    fun isSupported(context: Context): Boolean = isHonor() && readRaw(context) >= 0

    /** One-shot read. Returns [HonorFoldState.UNKNOWN] if unsupported or unreadable. */
    fun currentState(context: Context): HonorFoldState = readRaw(context).toHonorFoldState()
}

/**
 * Observe the Honor hinge key for the lifetime of [this] activity's STARTED..STOPPED
 * window. No-ops (and never registers anything) on non-Honor devices. The callback
 * fires once immediately with the current value, then on every change.
 */
fun Activity.observeHonorFoldState(onState: (HonorFoldState) -> Unit) {
    if (!HonorFold.isSupported(this)) return
    val owner = this as? LifecycleOwner ?: return
    val uri = Settings.Global.getUriFor(HonorFold.KEY_FOLD_SCREEN_STATE)
    val handler = Handler(Looper.getMainLooper())

    val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            onState(HonorFold.currentState(this@observeHonorFoldState))
        }
    }

    owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onStart(o: LifecycleOwner) {
            contentResolver.registerContentObserver(uri, false, observer)
            onState(HonorFold.currentState(this@observeHonorFoldState)) // prime
        }

        override fun onStop(o: LifecycleOwner) {
            runCatching { contentResolver.unregisterContentObserver(observer) }
        }

        override fun onDestroy(o: LifecycleOwner) {
            o.lifecycle.removeObserver(this)
        }
    })
}
