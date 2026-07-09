package com.fran.teclas.keyboard

import android.graphics.Rect

/**
 * Shared spatial tap resolution for both keyboards. A confident *center* press is taken at face
 * value; only a near-boundary tap is re-assigned to the most likely key by the Gaussian
 * [SpatialScorer] (weighted by the language model's next-char probabilities). This near-edge gate —
 * previously only in the IME — is what stops "I hit the right key and got the wrong letter."
 */
class TapResolver(private val spatial: SpatialScorer) {

    fun resolve(
        label: String,
        rawX: Float,
        rawY: Float,
        keyBounds: Map<String, Rect>,
        smartEnabled: Boolean,
        nextCharWeights: () -> Map<Char, Double>,
        fallback: (Float, Float) -> String?
    ): String {
        if (label.length != 1 || !label[0].isLetter() || keyBounds.isEmpty()) return label
        if (!smartEnabled) return label
        spatial.recordTap(rawX, rawY)
        keyBounds[label]?.let { rect ->
            val marginX = rect.width() * 0.24f
            val marginY = rect.height() * 0.24f
            val nearEdge = rawX < rect.left + marginX || rawX > rect.right - marginX ||
                rawY < rect.top + marginY || rawY > rect.bottom - marginY
            if (!nearEdge) return label   // confident center press — never override a clean tap
        }
        val best = spatial.bestKey(rawX, rawY, letterOnly = true, nextCharWeights)
        return if (best != null && best.length == 1 && best[0].isLetter()) best
        else (fallback(rawX, rawY) ?: label)
    }
}
