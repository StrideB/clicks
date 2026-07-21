package com.fran.teclas.keyboard

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * CI TYPING-ACCURACY GATE. This suite simulates thousands of realistic (biased + noisy) taps
 * through the REAL production resolution path — [TapResolver] + [SpatialScorer] — and fails the
 * build if typing gets worse. It exists because typing accuracy regressed silently more than once:
 * layout changes invalidated persisted per-key touch offsets, and the IME and docked keyboard
 * clobbered each other's learned state. Those exact failure modes are encoded below as tests.
 *
 * If a change makes this suite fail, typing got worse on-device. Fix the change, not the test;
 * thresholds were calibrated against the shipped math (see tools history) and carry safety margin.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TypingAccuracyGateTest {

    // ── deterministic simulation plumbing ────────────────────────────────────────────────────

    /** Explicit LCG + Box–Muller so the stream is identical on every run and every machine. */
    private class Rng(private var s: Long) {
        fun uniform(): Double {
            s = s * 6364136223846793005L + 1442695040888963407L
            return (s ushr 11).toDouble() / (1L shl 53).toDouble()
        }
        fun gaussian(): Double {
            val u1 = maxOf(uniform(), 1e-12)
            return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * uniform())
        }
    }

    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    /** A QWERTY letter-key layout as the deck captures it: edge-to-edge touch cells. */
    private fun layout(keyW: Int, keyH: Int, x0: Int = 0, y0: Int = 0): LinkedHashMap<String, Rect> {
        val out = LinkedHashMap<String, Rect>()
        rows.forEachIndexed { ri, r ->
            val off = when (ri) { 1 -> 0.5; 2 -> 1.5; else -> 0.0 }
            r.forEachIndexed { ci, ch ->
                val l = x0 + ((ci + off) * keyW).toInt()
                val t = y0 + ri * keyH
                out[ch.toString()] = Rect(l, t, l + keyW, t + keyH)
            }
        }
        return out
    }

    private data class Tap(val intended: Char, val x: Float, val y: Float)

    /** Common-word tap stream from a thumb-typist: constant bias + gaussian slop, both
     *  proportional to key size (which is how real finger error actually scales). */
    private fun tapStream(keys: Map<String, Rect>, count: Int, rng: Rng): List<Tap> {
        val words = ("the and you that was for are with his they this have from one had word " +
                "what were when your said there use each which she how their will other about " +
                "out many then them these some her would make like him into time has look two " +
                "more write see number way could people than first water been call who oil its " +
                "now find long down day did get come made may part over new sound take only " +
                "little work know place year live back give most very").split(" ")
        val kw = keys.getValue("q").width().toDouble()
        val kh = keys.getValue("q").height().toDouble()
        val bx = kw * 0.14; val by = kh * 0.16          // systematic thumb bias (right + low)
        val nx = kw * 0.30; val ny = kh * 0.26          // per-tap slop
        val taps = ArrayList<Tap>(count)
        var wi = 0
        while (taps.size < count) {
            for (ch in words[wi % words.size]) {
                if (taps.size >= count) break
                val c = keys.getValue(ch.toString())
                taps.add(Tap(ch,
                    (c.exactCenterX() + bx + rng.gaussian() * nx).toFloat(),
                    (c.exactCenterY() + by + rng.gaussian() * ny).toFloat()))
            }
            wi++
        }
        return taps
    }

    /** The label the touch cell reports on DOWN — containing cell, else nearest center. */
    private fun pressedLabel(keys: Map<String, Rect>, x: Float, y: Float): String =
        keys.entries.firstOrNull { it.value.contains(x.toInt(), y.toInt()) }?.key
            ?: keys.entries.minByOrNull {
                val dx = it.value.exactCenterX() - x; val dy = it.value.exactCenterY() - y
                dx * dx + dy * dy
            }!!.key

    /** Drive one tap through the REAL production path exactly as TeclasImeService does. */
    private fun resolveOne(resolver: TapResolver, keys: Map<String, Rect>, t: Tap): String =
        resolver.resolve(pressedLabel(keys, t.x, t.y), t.x, t.y, keys, true,
            nextCharWeights = { emptyMap() },
            fallback = { x, y -> pressedLabel(keys, x, y) })

    private fun freshPair(keys: Map<String, Rect>): Pair<SpatialScorer, TapResolver> {
        val s = SpatialScorer(); s.setKeys(keys); return s to TapResolver(s)
    }

    // ── the gates ────────────────────────────────────────────────────────────────────────────

    /** Floor + relative gate: smart resolution must clearly beat raw hitboxes for a biased
     *  typist, and stay above an absolute accuracy floor. Catches any future change that
     *  degrades the scorer, the near-edge gate, or the learning loop. */
    @Test fun smartTouchBeatsRawHitboxesAndClearsFloor() {
        val keys = layout(108, 160)
        val (_, resolver) = freshPair(keys)
        val taps = tapStream(keys, 6000, Rng(20260721L))
        var smart = 0; var raw = 0
        for (t in taps) {
            if (pressedLabel(keys, t.x, t.y) == t.intended.toString()) raw++
            if (resolveOne(resolver, keys, t) == t.intended.toString()) smart++
        }
        val smartAcc = smart / taps.size.toDouble()
        val rawAcc = raw / taps.size.toDouble()
        assertTrue("smart accuracy $smartAcc below floor 0.83 — typing resolution regressed",
            smartAcc >= 0.83)
        assertTrue("smart ($smartAcc) no longer beats raw hitboxes ($rawAcc) — learning is not helping",
            smartAcc >= rawAcc + 0.02)
    }

    /** THE spacing-update regression, encoded forever: offsets learned on one layout must be
     *  discarded when the layout changes. A scorer that trained on layout A and then receives
     *  layout B must behave IDENTICALLY to a scorer that never saw A. */
    @Test fun learnedOffsetsResetWhenLayoutChanges() {
        val oldKeys = layout(108, 160)
        val newKeys = layout(96, 150, x0 = 30, y0 = 12)   // the "spacing update": moved + resized
        val (veteranScorer, veteranResolver) = freshPair(oldKeys)
        for (t in tapStream(oldKeys, 3000, Rng(1L))) resolveOne(veteranResolver, oldKeys, t)
        veteranScorer.setKeys(newKeys)                     // update installs; deck recaptures bounds
        val (_, freshResolver) = freshPair(newKeys)
        val stream = tapStream(newKeys, 1500, Rng(2L))
        for (t in stream) {
            assertEquals("stale-layout offsets leaked through a layout change (the 'constant " +
                "misspelling after an update' bug) — SpatialScorer must reset on geometry change",
                resolveOne(freshResolver, newKeys, t), resolveOne(veteranResolver, newKeys, t))
        }
    }

    /** THE cross-keyboard clobbering bug, encoded forever: persisted state from a keyboard with
     *  different geometry (IME vs docked) must be rejected on import, not applied. */
    @Test fun foreignKeyboardStateIsRejected() {
        val imeKeys = layout(108, 160)
        val dockKeys = layout(72, 110, x0 = 200, y0 = 40)
        val (donorScorer, donorResolver) = freshPair(imeKeys)
        for (t in tapStream(imeKeys, 3000, Rng(3L))) resolveOne(donorResolver, imeKeys, t)
        val foreign = donorScorer.exportState()

        val poisoned = SpatialScorer()
        poisoned.setKeys(dockKeys)
        poisoned.importState(foreign)                      // must be discarded: wrong geometry
        val poisonedResolver = TapResolver(poisoned)
        val (_, cleanResolver) = freshPair(dockKeys)
        val stream = tapStream(dockKeys, 1500, Rng(4L))
        for (t in stream) {
            assertEquals("another keyboard's touch model was applied to this keyboard's geometry — " +
                "importState must reject state with a mismatched layout fingerprint",
                resolveOne(cleanResolver, dockKeys, t), resolveOne(poisonedResolver, dockKeys, t))
        }
    }

    /** The guard must not overcorrect: state exported and re-imported on the SAME layout (a
     *  normal app restart) must keep the learning — restarts should never wipe progress. */
    @Test fun sameLayoutStateSurvivesRestart() {
        val keys = layout(108, 160)
        val (donorScorer, donorResolver) = freshPair(keys)
        for (t in tapStream(keys, 3000, Rng(5L))) resolveOne(donorResolver, keys, t)
        val saved = donorScorer.exportState()

        val restarted = SpatialScorer()
        restarted.importState(saved)                       // app start: import happens first
        restarted.setKeys(keys)                            // then the deck lays out
        val restartedResolver = TapResolver(restarted)
        val stream = tapStream(keys, 1500, Rng(6L))
        for (t in stream) {
            assertEquals("learned touch state was lost across an ordinary restart — the geometry " +
                "guard is resetting state it should keep",
                resolveOne(donorResolver, keys, t), resolveOne(restartedResolver, keys, t))
        }
    }
}
