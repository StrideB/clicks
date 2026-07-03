package com.fran.clicks.fold

import android.graphics.Rect
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo

sealed interface FoldPosture {
    data class Inner(val hingeGutter: Rect?) : FoldPosture
    data class HalfOpen(val hinge: Rect, val vertical: Boolean) : FoldPosture
    data object Cover : FoldPosture
}

fun WindowLayoutInfo.toFoldPosture(isNarrowCover: Boolean): FoldPosture {
    val fold = displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
    if (fold == null) {
        return if (isNarrowCover) FoldPosture.Cover else FoldPosture.Inner(hingeGutter = null)
    }

    val vertical = fold.orientation == FoldingFeature.Orientation.VERTICAL
    return when (fold.state) {
        FoldingFeature.State.HALF_OPENED -> FoldPosture.HalfOpen(fold.bounds, vertical)
        FoldingFeature.State.FLAT -> FoldPosture.Inner(
            hingeGutter = if (fold.isSeparating) fold.bounds else null
        )
        else -> if (isNarrowCover) FoldPosture.Cover else FoldPosture.Inner(null)
    }
}
