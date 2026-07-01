package com.fran.clicks.keyboard

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Floats miniature prediction words above the Q, W, E keys (or the first 3 letter keys
 * in the top row) — exactly as BlackBerry's keyboard does.
 *
 * Call [update] whenever suggestions or keyBounds change.
 * The overlays live inside [overlayLayer] (a FrameLayout that covers the keyboard area).
 */
class PredictionOverlayManager(private val context: Context) {

    // Keys that host prediction overlays (first 3 of the top row)
    private val targetKeys = listOf("q", "w", "e")
    private val labels = mutableListOf<TextView>()

    /** The FrameLayout to add overlay labels into — set before calling update(). */
    var overlayLayer: FrameLayout? = null

    /** Root view's screen Y offset (so we can convert screen-abs keyBounds to view-local). */
    var rootScreenY: Int = 0

    fun update(suggestions: List<String>, keyBounds: Map<String, Rect>) {
        val layer = overlayLayer ?: return
        labels.forEach { layer.removeView(it) }
        labels.clear()

        targetKeys.forEachIndexed { idx, key ->
            val word = suggestions.getOrNull(idx) ?: return@forEachIndexed
            val rect = keyBounds[key] ?: return@forEachIndexed

            val tv = TextView(context).apply {
                text = word
                textSize = 10.5f
                typeface = Typeface.DEFAULT
                setTextColor(0xFFB8C4B2.toInt())
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                isClickable = false
            }

            val localTop = rect.top - rootScreenY - dp(22)
            val lp = FrameLayout.LayoutParams(rect.width(), dp(20)).apply {
                leftMargin = rect.left
                topMargin = maxOf(0, localTop)
            }
            layer.addView(tv, lp)
            labels.add(tv)
        }
    }

    fun clear() {
        val layer = overlayLayer ?: return
        labels.forEach { layer.removeView(it) }
        labels.clear()
    }

    /** Returns the prediction word for the given key, if an overlay exists. */
    fun predictionFor(keyLabel: String): String? {
        val idx = targetKeys.indexOf(keyLabel)
        return if (idx >= 0) labels.getOrNull(idx)?.text?.toString() else null
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
