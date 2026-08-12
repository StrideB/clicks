package com.fran.teclas.fold

/**
 * Page-column geometry for book-class inner displays.
 *
 * The Fold 8 class opens into a true landscape ~4:3 canvas (~933×704dp) whose halves are each a
 * real phone-width portrait page (~420dp) — unlike the squarish inners (Honor / Pixel class) the
 * focus-canvas layout was shaped against. When halves that wide exist, the calm unfolded home can
 * be composed as two pages around the fold line instead of one stretched column.
 *
 * The split comes from the reported hinge when the platform provides one (book half-open), and
 * defaults to the physical center otherwise: Fold 8-class panels report NO FoldingFeature once
 * fully flat (see MainActivity.isUnfoldedInnerLayoutActive), and their fold line is at the center
 * by construction.
 *
 * Scalar columns, no Android types, so the math runs as a plain JVM unit test:
 * `[0, gutterLeft)` = left page, `[gutterLeft, gutterRight)` = gutter, `[gutterRight, width)` =
 * right page — all in the coordinate space of the container whose `width` was passed in.
 */
data class InnerPageGeometry(
    val width: Int,
    val gutterLeft: Int,
    val gutterRight: Int,
    val fromHinge: Boolean,
) {
    val leftPageWidth: Int get() = gutterLeft
    val rightPageWidth: Int get() = width - gutterRight
    val gutterWidth: Int get() = gutterRight - gutterLeft
}

/**
 * Returns the page geometry for a container of [width]×[height] px, or null when the canvas
 * should stay a single column:
 *
 * - Pages are a landscape composition — a portrait window (rotated device, tabletop) gets null.
 * - Both pages must be at least [minPageWidth] px, so squarish inners whose halves are slivers
 *   keep the existing focus layout. The gate is capability-driven, never model-sniffed.
 *
 * [hingeCenterX] is the hinge's center mapped into container coordinates when a FoldingFeature
 * was reported, null otherwise (Fold 8 flat). A reported hinge can be a zero-width fold line, so
 * the gutter is never narrower than [fallbackGutterWidth] — the pages need breathing room even
 * when the crease is invisible.
 */
fun innerPageGeometry(
    width: Int,
    height: Int,
    hingeCenterX: Int?,
    hingeWidth: Int,
    minPageWidth: Int,
    fallbackGutterWidth: Int,
): InnerPageGeometry? {
    if (width <= 0 || height <= 0) return null
    if (width <= height) return null
    val fromHinge = hingeCenterX != null
    val centerX = hingeCenterX ?: (width / 2)
    val gutterWidth = maxOf(if (fromHinge) hingeWidth else 0, fallbackGutterWidth)
    val gutterLeft = centerX - gutterWidth / 2
    val gutterRight = gutterLeft + gutterWidth
    if (gutterLeft < minPageWidth || width - gutterRight < minPageWidth) return null
    return InnerPageGeometry(width, gutterLeft, gutterRight, fromHinge)
}
