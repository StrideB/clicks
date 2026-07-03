package com.fran.clicks

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class InputInjectionService : AccessibilityService() {
    private var focusedEditable: AccessibilityNodeInfo? = null

    private val keystrokeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!KeyboardSettings.isDocked(this@InputInjectionService)) return
            when (intent?.action) {
                ACTION_INJECT_KEY -> injectKey(intent.getStringExtra(EXTRA_CHAR).orEmpty())
                ACTION_TOGGLE_SPLIT_SCREEN -> performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(ACTION_INJECT_KEY).apply {
            addAction(ACTION_TOGGLE_SPLIT_SCREEN)
        }
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
                focusedEditable = findEditableTarget()
            }
        }
    }

    private fun injectKey(raw: String) {
        val target = focusedEditable?.takeIf { it.isEditable && it.refresh() }
            ?: findEditableTarget()
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
        val success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!success && raw != KEY_BACKSPACE && raw != KEY_ENTER) {
            pasteText(target, raw)
        }
    }

    private fun findEditableTarget(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: windows
            .firstOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?.root
        return root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: root?.findEditableDescendant()
    }

    private fun AccessibilityNodeInfo.findEditableDescendant(): AccessibilityNodeInfo? {
        if (isEditable && isFocused) return this
        for (index in 0 until childCount) {
            val child = getChild(index) ?: continue
            val found = child.findEditableDescendant()
            if (found != null) return found
        }
        return if (isEditable) this else null
    }

    private fun pasteText(target: AccessibilityNodeInfo, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("clicks_inject", text))
        target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runCatching { unregisterReceiver(keystrokeReceiver) }
        focusedEditable = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_INJECT_KEY = "com.fran.clicks.ACTION_INJECT_KEY"
        const val ACTION_TOGGLE_SPLIT_SCREEN = "com.fran.clicks.ACTION_TOGGLE_SPLIT_SCREEN"
        const val EXTRA_CHAR = "extra_char"
        const val KEY_BACKSPACE = "__BACKSPACE__"
        const val KEY_ENTER = "__ENTER__"
    }
}
