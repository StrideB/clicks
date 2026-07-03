package com.fran.clicks.fold

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

fun Activity.observeFoldPosture(onPosture: (FoldPosture) -> Unit) {
    val lifecycleOwner = this as? LifecycleOwner ?: return
    val tracker = WindowInfoTracker.getOrCreate(this)
    lifecycleOwner.lifecycleScope.launch {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            tracker.windowLayoutInfo(this@observeFoldPosture)
                .distinctUntilChanged()
                .collect { info ->
                    val smallestWidthDp = resources.configuration.smallestScreenWidthDp
                    val widthDp = resources.configuration.screenWidthDp
                    val isNarrowCover = minOf(smallestWidthDp, widthDp) < COVER_WIDTH_MAX_DP
                    onPosture(info.toFoldPosture(isNarrowCover))
                }
        }
    }
}

private const val COVER_WIDTH_MAX_DP = 600
