package com.fran.teclas.predict

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM checks for the ranking blend behind "I open apps but they don't stick around".
 * Uses [Predictor.blendScore] directly so no engine state or Android context is involved.
 */
class PredictorScoringTest {

    // Neutral linear term and mid alpha so the frequency/recency prior is what's under test.
    private fun score(
        globalFreqNorm: Float,
        ageHours: Float?,
        engaged: Boolean,
        spaceFreqNorm: Float = 0f,
        categoryMatch: Float = 0f,
    ) = Predictor.blendScore(
        lin = 0.5f, ctxFreqNorm = 0f, spaceFreqNorm = spaceFreqNorm,
        globalFreqNorm = globalFreqNorm, ageHours = ageHours, categoryMatch = categoryMatch,
        alpha = 0.5f, engaged = engaged,
    )

    @Test fun `a just-opened trip app beats a months-old home favorite when engaged`() {
        // A: rarely used globally, but opened an hour ago in this (locked/strong) Space.
        val tripApp = score(globalFreqNorm = 0.1f, ageHours = 1f, engaged = true, spaceFreqNorm = 0.5f)
        // B: a dominant global habit never opened in this Space.
        val homeFavorite = score(globalFreqNorm = 0.9f, ageHours = null, engaged = true)
        assertTrue("freshly used app must outrank the stale global favorite when engaged",
            tripApp > homeFavorite)
    }

    @Test fun `recency decays - a day-old open outranks a month-old open`() {
        val yesterday = score(globalFreqNorm = 0f, ageHours = 24f, engaged = true)
        val monthAgo = score(globalFreqNorm = 0f, ageHours = 24f * 30, engaged = true)
        assertTrue(yesterday > monthAgo)
    }

    @Test fun `engaged mode weights recency more than the default home mode`() {
        val engagedGain = score(globalFreqNorm = 0f, ageHours = 1f, engaged = true) -
            score(globalFreqNorm = 0f, ageHours = null, engaged = true)
        val homeGain = score(globalFreqNorm = 0f, ageHours = 1f, engaged = false) -
            score(globalFreqNorm = 0f, ageHours = null, engaged = false)
        assertTrue("recency should count for more in a deliberately-engaged Space",
            engagedGain > homeGain)
    }

    @Test fun `cold start - a category-matching app outranks a bigger global favorite`() {
        // The reported gap: brand-new Work Space, nothing learned. A work app (low global use,
        // matches the Space's categories) should still beat a heavily-used non-work favorite.
        val workApp = score(globalFreqNorm = 0.05f, ageHours = null, engaged = true, categoryMatch = 1f)
        val socialFavorite = score(globalFreqNorm = 0.4f, ageHours = null, engaged = true, categoryMatch = 0f)
        assertTrue("a category-matching app must lead an unlearned Space", workApp > socialFavorite)
    }

    @Test fun `a genuinely learned app overtakes a mere category match`() {
        // Category is only a cold-start nudge: once you actually open an off-category app in
        // this Space a few times, it should beat an unopened category-matching app.
        val learned = score(globalFreqNorm = 0.1f, ageHours = 2f, engaged = true, spaceFreqNorm = 0.6f, categoryMatch = 0f)
        val categoryOnly = score(globalFreqNorm = 0.05f, ageHours = null, engaged = true, categoryMatch = 1f)
        assertTrue("learned launches must overtake a standing category prior", learned > categoryOnly)
    }

    @Test fun `never-opened app gets zero recency contribution`() {
        val a = score(globalFreqNorm = 0.5f, ageHours = null, engaged = true)
        val b = score(globalFreqNorm = 0.5f, ageHours = 100000f, engaged = true) // ~11 years
        // Both effectively no recency; scores are within floating tolerance.
        assertTrue(kotlin.math.abs(a - b) < 1e-4f)
    }
}
