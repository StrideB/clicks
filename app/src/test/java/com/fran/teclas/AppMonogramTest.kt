package com.fran.teclas

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * In icon-less mode this character is the entire visual identity of an app, so the cases that used
 * to be cosmetic — an emoji-prefixed label, a bracketed tag, a stray leading space — are now the
 * difference between a typographic column and a page of punctuation.
 */
class AppMonogramTest {

    @Test fun `an ordinary name gives its first letter`() {
        assertEquals("S", AppMonogram.of("Spotify"))
        assertEquals("W", AppMonogram.of("WhatsApp"))
    }

    @Test fun `case is normalised`() {
        assertEquals("I", AppMonogram.of("iA Writer"))
        assertEquals("E", AppMonogram.of("eBay"))
    }

    @Test fun `leading whitespace is skipped`() {
        assertEquals("S", AppMonogram.of("  Spotify"))
        assertEquals("S", AppMonogram.of("\tSlack"))
    }

    @Test fun `leading decoration is skipped`() {
        // Real labels do all of these.
        assertEquals("N", AppMonogram.of("★ Notes"))
        assertEquals("B", AppMonogram.of("[Beta] Bank"))
        assertEquals("M", AppMonogram.of("• Maps"))
        assertEquals("D", AppMonogram.of("\"Drive\""))
    }

    @Test fun `a leading emoji is skipped rather than rendered`() {
        assertEquals("W", AppMonogram.of("🌈 Weather"))
        assertEquals("T", AppMonogram.of("📷 Timer"))
    }

    @Test fun `digits count as a letter`() {
        assertEquals("1", AppMonogram.of("1Password"))
        assertEquals("9", AppMonogram.of("9GAG"))
    }

    @Test fun `a name with no letters still gets a tile`() {
        // Emoji-only and symbol-only labels exist; a blank square is worse than a placeholder.
        assertEquals("#", AppMonogram.of("🌈"))
        assertEquals("#", AppMonogram.of("!!!"))
        assertEquals("#", AppMonogram.of(""))
        assertEquals("#", AppMonogram.of("   "))
    }

    @Test fun `a non-latin name keeps its own script`() {
        assertEquals("Д", AppMonogram.of("Дзен"))
        assertEquals("ت", AppMonogram.of("تطبيق"))
    }

    @Test fun `a supplementary-plane character is not sliced in half`() {
        // U+1D400 MATHEMATICAL BOLD CAPITAL A is a surrogate pair; take(1) would return half of it.
        val name = "𝐀pp"
        assertEquals(2, AppMonogram.of(name).length)
    }
}
