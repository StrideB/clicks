package com.fran.teclas.keyboard

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * Key-press preview: intentionally disabled.
 *
 * The pop-up bubble over a pressed key was pure polish, and it cost real work on the single most
 * latency-critical path — a view-tree location walk on every ACTION_DOWN — and could get stuck on
 * screen whenever the matching key-up never arrived (e.g. the glide detector steals the touch mid-
 * press during fast typing). Both keyboards call [show]/[dismiss] on every keystroke; making them
 * no-ops removes that cost and the stuck-bubble bug outright. Kept as an inert class so the call
 * sites in both keyboards compile unchanged.
 */
class KeyPreviewManager(@Suppress("UNUSED_PARAMETER") context: Context) {
    fun attachHost(@Suppress("UNUSED_PARAMETER") container: FrameLayout) {}
    fun show(@Suppress("UNUSED_PARAMETER") anchor: View, @Suppress("UNUSED_PARAMETER") label: String) {}
    fun dismiss() {}
}
