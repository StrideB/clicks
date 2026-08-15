package com.fran.teclas.keyboard

import com.fran.teclas.keyboard.unified.PhoneticPatterns
import com.fran.teclas.keyboard.unified.UnifiedRanker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Regression gate over the SHIPPED dictionary assets, not toy word lists. Every earlier
 * autocorrect test used a hand-picked vocabulary, which is exactly why three data-level failures
 * lived in production unnoticed: the per-bucket candidate budget hid ~45% of the real dictionary
 * ("tughteb" corrected to the wrong word), a third of the typo table pointed at words missing
 * from the 20k list (so those fixes never fired), and an empty dictionary load was
 * indistinguishable from a successful one. These tests parse the real assets from the module
 * source tree, so they run on plain JVM with no instrumentation.
 */
class RealDictionaryAutocorrectTest {

    @After fun clearBulk() = PhoneticPatterns.installBulk(emptyMap())

    private fun asset(name: String): File? =
        listOf("src/main/assets/dict/$name", "app/src/main/assets/dict/$name")
            .map { File(it) }
            .firstOrNull { it.isFile }

    private fun realEngine(): PredictionEngine {
        val file = asset("en_wordlist.txt")
        assumeTrue("en_wordlist.txt not found from test working dir", file != null)
        val counts = ArrayList<Pair<String, Long>>(22_000)
        var maxC = 1L
        file!!.bufferedReader().forEachLine { line ->
            val sp = line.trim().split(" ")
            if (sp.size >= 2) {
                val w = sp[0].lowercase()
                val c = sp[1].toLongOrNull() ?: return@forEachLine
                if (w.length in 2..20 && w.all { it.isLetter() }) {
                    counts.add(w to c)
                    if (c > maxC) maxC = c
                }
            }
        }
        val freqs = HashMap<String, Float>(counts.size * 2)
        for ((w, c) in counts) {
            val f = c.toFloat() / maxC
            val prev = freqs[w]
            if (prev == null || f > prev) freqs[w] = f
        }
        assertTrue("real dictionary should have >15k usable words, got ${freqs.size}", freqs.size > 15_000)
        return PredictionEngine(freqs)
    }

    private fun ranker(engine: PredictionEngine) = UnifiedRanker(
        engine = { engine },
        personalBoost = { 0f },
        isRejectedPair = { _, _ -> false },
        lmProb = { _, _ -> 0f },
        tapSpatial = { _, _ -> 0f }
    )

    private fun installRealTypoTable() {
        val file = asset("en_typos.txt")
        assumeTrue("en_typos.txt not found from test working dir", file != null)
        val bulk = HashMap<String, String>(5_000)
        file!!.bufferedReader().forEachLine { line ->
            val idx = line.indexOf("->")
            if (idx <= 0) return@forEachLine
            val wrong = line.substring(0, idx).trim().lowercase()
            val right = line.substring(idx + 2).trim().lowercase()
            // Multi-fix entries (comma alternatives) are ambiguous — the loader skips them too.
            if (wrong.isNotEmpty() && right.isNotEmpty() && ',' !in right) bulk[wrong] = right
        }
        assertTrue("typo table should have thousands of entries, got ${bulk.size}", bulk.size > 3_000)
        PhoneticPatterns.installBulk(bulk)
    }

    // ── The reported failures, verbatim ─────────────────────────────────────────────────────────

    @Test fun `juwt corrects to just`() {
        assertEquals("just", ranker(realEngine()).bestCorrection("juwt"))
    }

    @Test fun `tughteb corrects to tighten, not a nearer-indexed wrong word`() {
        // "tighten" sits past index 400 of the frequency-sorted t-bucket, so the old shared
        // 400-eval budget never scored it and confidently returned "tighter" (worse distance).
        assertEquals("tighten", ranker(realEngine()).bestCorrection("tughteb"))
    }

    @Test fun `mispelled corrects to misspelled via the typo table`() {
        // "misspelled" is NOT in the 20k wordlist; the fix only fires because the typo table
        // is trusted without a dictionary-membership gate.
        installRealTypoTable()
        assertEquals("misspelled", ranker(realEngine()).bestCorrection("mispelled"))
    }

    // ── Guardrails: real words must never be rewritten ──────────────────────────────────────────

    @Test fun `real words are left alone`() {
        val r = ranker(realEngine())
        for (w in listOf("hello", "keyboard", "dictionary", "typing", "search")) {
            assertNull("must not rewrite real word '$w'", r.bestCorrection(w))
        }
    }
}
