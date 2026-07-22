package com.fran.teclas.keyboard

import com.fran.teclas.keyboard.neural.CharTrie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/**
 * Proves the decode-at-commit core: a word is recovered from the GEOMETRY of the taps plus language
 * context, not from the literally-typed letters. Pure JVM — a QWERTY layout, a Gaussian spatial
 * model, and a tiny dictionary stand in for the on-device pieces, so the decode logic is validated
 * in CI independent of any device build.
 */
class TapLatticeDecoderTest {

    // ── a minimal QWERTY the test taps against ────────────────────────────────────────────────
    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val keyW = 100f
    private val keyH = 150f
    private val centers: Map<Char, Pair<Float, Float>> = buildMap {
        rows.forEachIndexed { ri, r ->
            val off = when (ri) { 1 -> 0.5f; 2 -> 1.5f; else -> 0f }
            r.forEachIndexed { ci, ch -> put(ch, (ci + off) * keyW + keyW / 2 to ri * keyH + keyH / 2) }
        }
    }
    private fun center(c: Char) = centers.getValue(c)

    // Gaussian P(tap belongs to key), same shape as the shipped SpatialScorer.
    private val sigmaX = keyW * 0.48
    private val sigmaY = keyH * 0.44
    private fun spatial(x: Float, y: Float, key: Char): Float {
        val (cx, cy) = centers[key] ?: return 0f
        val dx = x - cx; val dy = y - cy
        return exp(-((dx * dx) / (2 * sigmaX * sigmaX) + (dy * dy) / (2 * sigmaY * sigmaY))).toFloat()
    }

    /** Tap each letter of [typed] at that letter's key center (a clean, on-target typist). */
    private fun tapsFor(typed: String): List<Pair<Float, Float>> =
        typed.map { center(it) }

    private val words = listOf(
        "lets", "let", "let's", "rate", "rare", "rage", "hello", "help", "the", "cat", "car",
        "care", "core", "were", "here", "have", "gave", "test", "text", "best", "we", "i", "am",
    )
    private val trie = CharTrie().apply { addAll(words) }
    private val freq = mapOf(
        "the" to 1.0f, "lets" to 0.6f, "rate" to 0.55f, "hello" to 0.5f, "have" to 0.7f,
        "rare" to 0.2f, "rage" to 0.15f, "care" to 0.4f, "the" to 1.0f,
    )
    private fun wordFreq(w: String) = freq[w] ?: (if (w in words) 0.05f else 0f)

    private fun decoder(lm: (String, String) -> Float = { _, _ -> 0f }) =
        TapLatticeDecoder(trie, ::spatial, ::wordFreq, lm)

    // ── the claims ────────────────────────────────────────────────────────────────────────────

    /** THE headline: taps that literally spell "letw" (a non-word) decode to "lets", because the
     *  'w' tap lands near 's' and "lets" is the only real word the geometry supports. */
    @Test fun geometryRecoversWordFromMistypedLetters() {
        // Tap l-e-t, then a point at 'w' (the user's finger slipped up from 's').
        val taps = listOf(center('l'), center('e'), center('t'), center('w'))
        val top = decoder().decode(taps, prevWord = "", topK = 3)
        assertTrue("expected a decode", top.isNotEmpty())
        assertEquals("geometry should recover 'lets' from a slipped 's' tap", "lets", top.first().word)
    }

    /** A clean, on-target word must decode to itself — the decoder never "fixes" correct input. */
    @Test fun cleanInputIsUnchanged() {
        assertEquals("hello", decoder().decode(tapsFor("hello"), "", 3).first().word)
        assertEquals("have", decoder().decode(tapsFor("have"), "", 3).first().word)
    }

    /** When geometry is ambiguous between real words, the language model breaks the tie. Tapping a
     *  point between 'rare'/'rate'/'rage', context "flat" (→ rate) vs "quiet" (→ rare) should steer. */
    @Test fun languageModelBreaksSpatialTies() {
        // Tap r-a-?-e where the 3rd tap sits between t, r, and g (ambiguous middle key).
        val (tx, ty) = center('t'); val (rx, ry) = center('r'); val (gx, gy) = center('g')
        val mid = (tx + rx + gx) / 3 to (ty + ry + gy) / 3
        val taps = listOf(center('r'), center('a'), mid, center('e'))

        val prefersRate = decoder { _, w -> if (w == "rate") 0.9f else 0.01f }
        assertEquals("rate", prefersRate.decode(taps, "flat", 3).first().word)

        val prefersRare = decoder { _, w -> if (w == "rare") 0.9f else 0.01f }
        assertEquals("rare", prefersRare.decode(taps, "quiet", 3).first().word)
    }

    /** Predictive completion: a few taps of a prefix can offer the full word when asked. */
    @Test fun completionOffersLongerWords() {
        val top = decoder().decode(tapsFor("hel"), "", topK = 5, allowCompletion = true).map { it.word }
        assertTrue("completion should offer 'hello'/'help' from 'hel', got $top",
            "hello" in top || "help" in top)
    }

    /** Stage 3 predictive targeting: with the geometry a tie between two words, the LM's expected
     *  next letter wins — the "key grows toward what you'll type next". */
    @Test fun predictiveTargetingBreaksTiesTowardExpectedLetter() {
        val d = TapLatticeDecoder(trie, ::spatial, { 0.2f }, { _, _ -> 0f })   // flat freq/LM → geometry+prediction only
        val (sx, sy) = center('s'); val (xxx, xyy) = center('x')
        val mid = (sx + xxx) / 2 to (sy + xyy) / 2                              // tap between s and x
        val taps = listOf(center('t'), center('e'), mid, center('t'))          // t-e-?-t
        assertEquals("test", d.decode(taps, "", topK = 3,
            nextCharWeights = { p -> if (p == "te") mapOf('s' to 1.6) else emptyMap() }).first().word)
        assertEquals("text", d.decode(taps, "", topK = 3,
            nextCharWeights = { p -> if (p == "te") mapOf('x' to 1.6) else emptyMap() }).first().word)
    }

    /** Accent folding: a word tapped on plain a–z keys decodes to its accented Spanish form. */
    @Test fun accentFoldedWordDecodesToAccentedForm() {
        val accentTrie = CharTrie().apply {
            addAccentFolded("cómo"); addAccentFolded("come"); addAccentFolded("core")
        }
        val d = TapLatticeDecoder(accentTrie, ::spatial,
            { w -> if (w == "cómo") 0.6f else 0.1f }, { _, _ -> 0f })
        val top = d.decode(tapsFor("como"), "", topK = 3)   // taps land on c-o-m-o (a–z keys)
        assertTrue("expected the accented 'cómo' among results, got ${top.map { it.word }}",
            top.any { it.word == "cómo" })
    }

    /** Nonsense geometry (a tap far from every key path) yields no confident decode rather than a
     *  wrong one — the decoder declines instead of inventing. */
    @Test fun offGridTapsDoNotInventWords() {
        val taps = listOf(center('z'), center('x'), center('q'))   // "zxq" — no dictionary path
        assertTrue("no real word should be forced from junk", decoder().decode(taps, "", 3).isEmpty())
    }
}
