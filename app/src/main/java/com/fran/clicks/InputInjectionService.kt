package com.fran.clicks

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class InputInjectionService : AccessibilityService() {
    private var focusedEditable: AccessibilityNodeInfo? = null

    private val keystrokeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_INJECT_KEY || !KeyboardSettings.isDocked(this@InputInjectionService)) return
            injectKey(intent.getStringExtra(EXTRA_CHAR).orEmpty())
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(ACTION_INJECT_KEY)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(keystrokeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(keystrokeReceiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!KeyboardSettings.isDocked(this)) {
            focusedEditable = null
            return
        }
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                focusedEditable = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?.takeIf { it.isEditable }
            }
        }
    }

    private fun injectKey(raw: String) {
        val target = focusedEditable?.takeIf { it.isEditable }
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: return
        val before = target.text?.toString().orEmpty()
        val next = when (raw) {
            KEY_BACKSPACE -> if (before.isNotEmpty()) before.dropLast(1) else before
            KEY_ENTER -> "$before\n"
            else -> before + raw
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next)
        }
        target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runCatching { unregisterReceiver(keystrokeReceiver) }
        focusedEditable = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_INJECT_KEY = "com.fran.clicks.ACTION_INJECT_KEY"
        const val EXTRA_CHAR = "extra_char"
        const val KEY_BACKSPACE = "__BACKSPACE__"
        const val KEY_ENTER = "__ENTER__"
    }
}
