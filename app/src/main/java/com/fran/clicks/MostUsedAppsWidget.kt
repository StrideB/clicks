package com.fran.clicks

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.fran.clicks.grid.GridIcons
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * "Clicks · Most Used" home-screen widget: the user's top-launched apps as tappable icons.
 * Ranks by the same launch counter the launcher's favorites dock uses (app_usage_counts in the
 * "clicks" prefs), so it needs no extra permissions and works in any launcher — including the
 * grid workspace, where it can live in a widget stack.
 */
class MostUsedAppsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context))
        }
    }

    private fun buildViews(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_most_used)
        val top = topApps(context)
        views.setViewVisibility(R.id.most_used_empty, if (top.isEmpty()) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.most_used_row2, if (top.size > 4) View.VISIBLE else View.GONE)

        val iconIds = intArrayOf(
            R.id.icon_0, R.id.icon_1, R.id.icon_2, R.id.icon_3,
            R.id.icon_4, R.id.icon_5, R.id.icon_6, R.id.icon_7,
        )
        val iconPx = (44 * context.resources.displayMetrics.density).roundToInt()
        iconIds.forEachIndexed { index, viewId ->
            val pkg = top.getOrNull(index)
            if (pkg == null) {
                views.setViewVisibility(viewId, View.GONE)
                return@forEachIndexed
            }
            val drawable = GridIcons.resolve(context, pkg, null)
            if (drawable == null) {
                views.setViewVisibility(viewId, View.GONE)
                return@forEachIndexed
            }
            views.setViewVisibility(viewId, View.VISIBLE)
            views.setImageViewBitmap(viewId, GridIcons.asBitmap(drawable, iconPx))
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                views.setOnClickPendingIntent(viewId, PendingIntent.getActivity(
                    context, index, launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ))
            }
        }
        return views
    }

    /** Top launchable packages by launch count, capped at 8. */
    private fun topApps(context: Context): List<String> {
        val raw = context.getSharedPreferences("clicks", Context.MODE_PRIVATE)
            .getString("app_usage_counts", "{}") ?: "{}"
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val counts = buildList {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                add(key to json.optInt(key, 0))
            }
        }
        return counts
            .sortedByDescending { it.second }
            .map { it.first }
            .filter { context.packageManager.getLaunchIntentForPackage(it) != null }
            .take(8)
    }

    companion object {
        /** Push fresh data into every placed instance (call after usage counts change). */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                android.content.ComponentName(context, MostUsedAppsWidget::class.java)
            )
            if (ids.isNotEmpty()) MostUsedAppsWidget().onUpdate(context, manager, ids)
        }
    }
}
