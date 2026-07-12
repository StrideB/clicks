package com.fran.teclas.keyboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * Key-press preview bubble, LatinIME-style: ONE TextView, created once, living inside the
 * keyboard's own window, repositioned and re-labeled per press. The old implementation created a
 * fresh PopupWindow — a whole new OS window with its own WindowManager round-trip — on every
 * keystroke, plus a one-frame `post` delay before it appeared; that window churn was a real chunk
 * of the per-key latency. Hosts call [attachHost] with an overlay container (the IME's root
 * FrameLayout) to get the in-window path; without a host it falls back to a single *reused*
 * PopupWindow rather than a new one per press.
 */
class KeyPreviewManager(private val context: Context) {

    private var host: FrameLayout? = null
    private var overlay: TextView? = null

    private var popup: PopupWindow? = null
    private var popupText: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable {
        overlay?.visibility = View.INVISIBLE
        popup?.dismiss()
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    private val size get() = dp(40)

    /**
     * Render previews inside [container] (which must cover the keys and extend above the top key
     * row) instead of spawning popup windows. Call once per input-view build; the preview view is
     * (re)created lazily inside the new container.
     */
    fun attachHost(container: FrameLayout) {
        if (host === container) return
        (overlay?.parent as? ViewGroup)?.removeView(overlay)
        overlay = null
        host = container
    }

    private fun buildPreviewText(): TextView = TextView(context).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        setTextColor(0xFFF3F0E7.toInt())
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        background = GradientDrawable().apply {
            setColor(0xFF3A3E4A.toInt())
            cornerRadius = dp(8).toFloat()
        }
    }

    fun show(anchor: View, label: String) {
        if (label.length != 1 || !label[0].isLetter()) return
        handler.removeCallbacks(hide)
        val h = host
        if (h != null && h.isAttachedToWindow && anchor.isAttachedToWindow) {
            showInWindow(h, anchor, label)
        } else {
            showPopup(anchor, label)
        }
    }

    // Same-window path: move the persistent bubble over the key. Position math is two cheap
    // getLocationInWindow calls; no window creation, no IPC, visible the same frame as the press.
    private fun showInWindow(h: FrameLayout, anchor: View, label: String) {
        val tv = overlay ?: buildPreviewText().also {
            overlay = it
            it.visibility = View.INVISIBLE
            h.addView(it, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START))
        }
        val up = label.uppercase()
        if (!tv.text.contentEquals(up)) tv.text = up
        val hostLoc = IntArray(2); val anchorLoc = IntArray(2)
        h.getLocationInWindow(hostLoc)
        anchor.getLocationInWindow(anchorLoc)
        tv.translationX = anchorLoc[0] - hostLoc[0] + (anchor.width - size) / 2f
        tv.translationY = (anchorLoc[1] - hostLoc[1] - size - dp(4)).toFloat()
        tv.bringToFront()
        tv.visibility = View.VISIBLE
    }

    // Fallback for hosts that haven't attached an overlay container (the launcher keyboard):
    // one PopupWindow, created on first use and then only moved/updated.
    private fun showPopup(anchor: View, label: String) {
        if (!anchor.isAttachedToWindow) return
        val text = popupText ?: buildPreviewText().also { popupText = it }
        text.text = label.uppercase()
        val xOff = (anchor.width - size) / 2
        val yOff = -(size + anchor.height + dp(4))
        val p = popup
        if (p == null) {
            val fresh = PopupWindow(text, size, size, false).apply {
                isOutsideTouchable = false
                isTouchable = false
                elevation = dp(4).toFloat()
            }
            popup = fresh
            runCatching { fresh.showAsDropDown(anchor, xOff, yOff) }
        } else {
            runCatching {
                if (p.isShowing) p.update(anchor, xOff, yOff, size, size)
                else p.showAsDropDown(anchor, xOff, yOff)
            }
        }
    }

    /** Hide the preview. A short linger (as Gboard uses) keeps fast typing from strobing. */
    fun dismiss() {
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, LINGER_MS)
    }

    private companion object {
        const val LINGER_MS = 60L
    }
}
