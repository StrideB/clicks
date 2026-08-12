package com.fran.teclas

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.ceil
import kotlin.math.min

/**
 * Fits any widget into any cell box the user asks for — a wide widget squeezed into a square,
 * a tall one flattened into a strip — without clipping and without lying to the provider.
 *
 * Two layers, applied together:
 *
 * 1. The provider is always told the size it is actually being rendered at (the virtual size
 *    below), as an exact [SizeF]. Responsive widgets — anything using Android 12 size-mapped
 *    RemoteViews, or re-rendering on option changes — reflow their own content natively, which
 *    is always the best outcome.
 *
 * 2. Widgets whose provider minimum is bigger than the box get a uniform downscale via
 *    [WidgetScaleFrame]: the widget view is measured at the smallest size the provider can
 *    render and scaled down to fill the box exactly. Content stays proportional and whole
 *    instead of being cropped. One axis is never stretched independently.
 *
 * The "virtual size" ties the layers together: a scaled widget is told the *pre-scale* size
 * (box / scale), which is the canvas it genuinely draws on.
 */
object WidgetFit {

    // Below this the content is unreadable anyway; better to clip a corner than shrink to dust.
    private const val MIN_SCALE = 0.4f

    /** Uniform downscale needed to fit a widget whose provider minimum exceeds the box. 1 = fits. */
    fun fitScale(info: AppWidgetProviderInfo?, boxWidthPx: Int, boxHeightPx: Int): Float {
        if (info == null || boxWidthPx <= 0 || boxHeightPx <= 0) return 1f
        var scale = 1f
        if (info.minWidth > boxWidthPx) scale = min(scale, boxWidthPx / info.minWidth.toFloat())
        if (info.minHeight > boxHeightPx) scale = min(scale, boxHeightPx / info.minHeight.toFloat())
        return scale.coerceAtLeast(MIN_SCALE)
    }

    /** The size in dp the provider should render for: the box itself, or the pre-scale canvas. */
    fun virtualSizeDp(context: Context, info: AppWidgetProviderInfo?, boxWidthPx: Int, boxHeightPx: Int): SizeF {
        val density = context.resources.displayMetrics.density
        val scale = fitScale(info, boxWidthPx, boxHeightPx)
        return SizeF(boxWidthPx / scale / density, boxHeightPx / scale / density)
    }

    /** Report the render size straight to a live host view. */
    fun reportSize(hostView: AppWidgetHostView, boxWidthPx: Int, boxHeightPx: Int) {
        if (boxWidthPx <= 0 || boxHeightPx <= 0) return
        val size = virtualSizeDp(hostView.context, hostView.appWidgetInfo, boxWidthPx, boxHeightPx)
        runCatching { hostView.updateAppWidgetSize(Bundle(), listOf(size)) }
    }

    /** Same report for hosts that only hold the widget id, via [AppWidgetManager] options. */
    fun reportSize(context: Context, manager: AppWidgetManager, widgetId: Int, boxWidthPx: Int, boxHeightPx: Int) {
        if (boxWidthPx <= 0 || boxHeightPx <= 0) return
        val info = runCatching { manager.getAppWidgetInfo(widgetId) }.getOrNull()
        val size = virtualSizeDp(context, info, boxWidthPx, boxHeightPx)
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, size.width.toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, ceil(size.width).toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, size.height.toInt())
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, ceil(size.height).toInt())
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(size))
        }
        runCatching { manager.updateAppWidgetOptions(widgetId, options) }
    }

    /** First [AppWidgetHostView] in [view]'s subtree, or null. */
    fun findHostView(view: View?): AppWidgetHostView? {
        if (view == null) return null
        if (view is AppWidgetHostView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findHostView(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}

/**
 * The scale layer of [WidgetFit]: hosts a widget view inside a fixed box, measuring the child
 * at the provider's minimum renderable size and uniformly scaling it down so the whole layout
 * fills the box exactly. When the widget fits natively this is a plain pass-through frame.
 *
 * Also owns size reporting: every time the box changes, the provider hears about it, so
 * responsive widgets reflow on first placement and on every resize with no caller wiring.
 */
class WidgetScaleFrame(context: Context) : FrameLayout(context) {

    init {
        clipChildren = true
        clipToPadding = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val boxW = MeasureSpec.getSize(widthMeasureSpec)
        val boxH = MeasureSpec.getSize(heightMeasureSpec)
        val scale = WidgetFit.fitScale(WidgetFit.findHostView(this)?.appWidgetInfo, boxW, boxH)
        val childW = if (scale < 1f) ceil(boxW / scale).toInt() else boxW
        val childH = if (scale < 1f) ceil(boxH / scale).toInt() else boxH
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(
                MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childH, MeasureSpec.EXACTLY)
            )
            child.pivotX = 0f
            child.pivotY = 0f
            child.scaleX = scale
            child.scaleY = scale
        }
        setMeasuredDimension(boxW, boxH)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        WidgetFit.findHostView(this)?.let { WidgetFit.reportSize(it, w, h) }
    }
}
