package com.fran.teclas.fold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract, stated in Fold 8 numbers. Its inner panel is 2448×1848 px (~933×704dp at the
 * 2.625 default density); the launcher's unfolded content column is inset 30dp per side, so the
 * geometry below runs against width 2290. dp(400) ≈ 1050 px, dp(28) ≈ 74 px.
 */
class InnerPageGeometryTest {

    private val fold8Width = 2448 - 158   // window minus 30dp horizontal insets per side
    private val fold8Height = 1848
    private val minPage = 1050            // dp(400)
    private val gutter = 74               // dp(28)

    @Test fun `fold 8 flat splits at the physical center with no reported hinge`() {
        val pages = innerPageGeometry(
            width = fold8Width, height = fold8Height,
            hingeCenterX = null, hingeWidth = 0,
            minPageWidth = minPage, fallbackGutterWidth = gutter,
        )!!
        assertEquals(false, pages.fromHinge)
        assertEquals(gutter, pages.gutterWidth)
        // Both pages are real phone-width canvases and together they tile the container exactly.
        assertTrue(pages.leftPageWidth >= minPage)
        assertTrue(pages.rightPageWidth >= minPage)
        assertEquals(fold8Width, pages.leftPageWidth + pages.gutterWidth + pages.rightPageWidth)
        // Center split: the pages differ by at most the integer-division remainder.
        assertTrue(Math.abs(pages.leftPageWidth - pages.rightPageWidth) <= 1)
    }

    @Test fun `a reported vertical hinge overrides the center split`() {
        val pages = innerPageGeometry(
            width = fold8Width, height = fold8Height,
            hingeCenterX = fold8Width / 2 - 20, hingeWidth = 120,
            minPageWidth = minPage, fallbackGutterWidth = gutter,
        )!!
        assertEquals(true, pages.fromHinge)
        assertEquals(120, pages.gutterWidth)
        assertEquals(fold8Width / 2 - 20, (pages.gutterLeft + pages.gutterRight) / 2)
    }

    @Test fun `a zero-width fold line still gets the fallback gutter`() {
        val pages = innerPageGeometry(
            width = fold8Width, height = fold8Height,
            hingeCenterX = fold8Width / 2, hingeWidth = 0,
            minPageWidth = minPage, fallbackGutterWidth = gutter,
        )!!
        assertEquals(true, pages.fromHinge)
        assertEquals(gutter, pages.gutterWidth)
    }

    @Test fun `portrait windows never page — rotated Fold 8 keeps the focus column`() {
        assertNull(innerPageGeometry(
            width = 1848 - 158, height = 2448,
            hingeCenterX = null, hingeWidth = 0,
            minPageWidth = minPage, fallbackGutterWidth = gutter,
        ))
    }

    @Test fun `squarish inners whose halves are slivers keep the focus column`() {
        // Honor Magic V class: ~2344 px landscape at 2.75 density → halves ≈ 382dp < 400dp.
        assertNull(innerPageGeometry(
            width = 2344 - 165, height = 2172,
            hingeCenterX = null, hingeWidth = 0,
            minPageWidth = 1100, fallbackGutterWidth = 77,
        ))
    }

    @Test fun `an off-center hinge that starves one page rejects pages`() {
        assertNull(innerPageGeometry(
            width = fold8Width, height = fold8Height,
            hingeCenterX = 900, hingeWidth = 0,   // left page would be ~863 px < minPage
            minPageWidth = minPage, fallbackGutterWidth = gutter,
        ))
    }

    @Test fun `degenerate sizes are rejected`() {
        assertNull(innerPageGeometry(0, 0, null, 0, minPage, gutter))
        assertNull(innerPageGeometry(-10, 100, null, 0, minPage, gutter))
    }
}
