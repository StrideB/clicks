package com.fran.teclas

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import com.fran.teclas.brief.BriefAction
import com.fran.teclas.brief.BriefItem
import com.fran.teclas.brief.Fire
import com.fran.teclas.brief.Launch
import com.fran.teclas.brief.NotificationSignal
import com.fran.teclas.brief.TodayAlert
import com.fran.teclas.brief.TodayKeyboardMode
import com.fran.teclas.brief.TodayPage

/**
 * MainActivity-side host for the "Today" brief pane: the teaser alert content, the sliding
 * left-of-home overlay (openToday/closeToday), and firing notification actions (including
 * RemoteInput replies) from brief items. The Compose UI itself lives in brief/TodayPage.kt.
 */
internal class TodayPaneHost(private val activity: MainActivity) {

    private var todayPageView: View? = null

    fun setTodayAlertContent(view: ComposeView) {
        view.setContent {
            if (!activity.hasBriefRepository()) return@setContent
            val brief by activity.briefRepository.brief.collectAsState()
            TodayAlert(
                tokens = activity.activeNeuTokens,
                brief = brief,
                onOpen = {
                    activity.haptic(view)
                    openToday()
                },
                onDismiss = { item -> dismissBriefItem(item) }
            )
        }
    }

    fun openToday() {
        if (!activity.todayEnabled) return
        if (activity.todayOpen || activity.libraryOpen || activity.openPane != null || !activity.hasContentFrame()) return
        activity.cancelWallpaperLongPress()
        activity.todayOpen = true
        activity.briefRepository.refresh()
        if (activity.isUnfoldedInnerLayoutActive()) {
            activity.query = ""
            activity.keyboardSettingsOpen = false
            activity.refreshUnfoldedFocusContent()
            activity.renderRibbon()
            activity.haptic(activity.contentFrame)
            return
        }
        val overlay = FrameLayout(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true // swallow taps so they don't fall through to home
            addView(activity.DynamicGlassPlate(context, radiusDp = 0, strength = 1.9f, edgeInsetDp = 0).apply {
                setGlassProgress(1f)
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(ComposeView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setContent {
                    val brief by activity.briefRepository.brief.collectAsState()
                    TodayPage(
                        tokens = activity.activeNeuTokens,
                        brief = brief,
                        hasListenerPermission = activity.isNotificationAccessEnabled(),
                        keyboardMode = if (activity.keyboardPlacement == MainActivity.KEYBOARD_PLACEMENT_WIDGET) TodayKeyboardMode.WIDGET else TodayKeyboardMode.DOCKED,
                        transparentShell = true,
                        onAction = { item, action, reply -> fireBriefAction(item, action, reply) },
                        onDismiss = { item -> dismissBriefItem(item) },
                        onGrantPermission = { activity.openNotificationAccessSettings() }
                    )
                }
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        todayPageView = overlay
        activity.contentFrame.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val w = activity.contentFrame.width.takeIf { it > 0 }?.toFloat() ?: activity.resources.displayMetrics.widthPixels.toFloat()
        overlay.translationX = -w
        overlay.animate().translationX(0f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
        activity.haptic(activity.contentFrame)
    }

    fun closeToday() {
        activity.todayOpen = false
        if (activity.isUnfoldedInnerLayoutActive()) {
            activity.refreshUnfoldedFocusContent()
            activity.renderRibbon()
            return
        }
        val overlay = todayPageView ?: return
        todayPageView = null
        val w = activity.contentFrame.width.takeIf { it > 0 }?.toFloat() ?: activity.resources.displayMetrics.widthPixels.toFloat()
        overlay.animate().translationX(-w).setDuration(200).setInterpolator(DecelerateInterpolator())
            .withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay) }.start()
    }

    fun fireBriefAction(item: BriefItem, action: BriefAction, replyText: String?) {
        activity.haptic(activity.contentFrame)
        val ok = when (action) {
            is Launch -> runCatching { activity.startActivity(action.intent) }.isSuccess
            is Fire -> sendFire(action, replyText)
        }
        val isReply = action is Fire && action.isReply
        if (!ok) {
            // Canceled PendingIntent → fall back to the notification's contentIntent.
            (item.signal as? NotificationSignal)?.contentIntent?.let { runCatching { it.send() } }
        } else if (isReply) {
            Toast.makeText(activity, "Sent", Toast.LENGTH_SHORT).show()
        }
        // Firing resolves the card; clear it (the notification itself usually cancels too).
        activity.briefRepository.dismissItem(item)
        // Opening/launching leaves the launcher — don't leave Today sitting underneath on return.
        if (!isReply) closeToday()
    }

    private fun sendFire(action: Fire, replyText: String?): Boolean = try {
        val ri = action.remoteInput
        if (ri != null && !replyText.isNullOrBlank()) {
            val fill = Intent()
            val results = Bundle().apply { putCharSequence(ri.resultKey, replyText) }
            RemoteInput.addResultsToIntent(arrayOf(ri) + action.extraInputs, fill, results)
            action.pendingIntent.send(activity, 0, fill)
        } else {
            action.pendingIntent.send()
        }
        true
    } catch (_: PendingIntent.CanceledException) {
        false
    }

    fun dismissBriefItem(item: BriefItem) {
        activity.haptic(activity.contentFrame)
        if (item.signal is NotificationSignal) {
            TeclasNotificationListener.dismiss(item.signalRef)
        }
        activity.briefRepository.dismissItem(item)
    }
}
