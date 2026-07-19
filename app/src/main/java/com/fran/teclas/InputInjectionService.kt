package com.fran.teclas

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class InputInjectionService : AccessibilityService() {
    private var focusedEditable: AccessibilityNodeInfo? = null

    // Auto-open-search: when the docked keyboard types and no field is on screen, buffer the
    // characters and tap the app's search affordance once, then flush the buffer into the field
    // that appears. Lets "just start typing" reach search-gated apps (YouTube/Spotify fake bars).
    private val handler = Handler(Looper.getMainLooper())
    private val pendingSearchText = StringBuilder()
    private var searchOpenAttempted = false
    private var searchOpenRetries = 0
    private var searchSessionActive = false
    private val flushRunnable = Runnable { flushPendingSearch() }
    private val endSessionRunnable = Runnable { endSearchSession() }
    private val prepareRetryRunnable = Runnable { retryPrepareField() }

    // What we've typed into the user-focused field, tracked locally so we never re-read the app's
    // placeholder (Telegram/WhatsApp "Message") and so fast keystrokes/deletes don't race the async
    // ACTION_SET_TEXT read-modify-write. Reset when the target field changes.
    private val focusedBuffer = StringBuilder()
    private var focusedFieldKey: String? = null

    private val keystrokeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!KeyboardSettings.isDocked(this@InputInjectionService)) return
            when (intent?.action) {
                ACTION_INJECT_KEY -> injectKey(intent.getStringExtra(EXTRA_CHAR).orEmpty())
                ACTION_PREPARE_FIELD -> prepareForegroundField()
                ACTION_TOGGLE_SPLIT_SCREEN -> performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                ACTION_REPIN_FREEFORM -> repinForegroundFreeform()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(ACTION_INJECT_KEY).apply {
            addAction(ACTION_PREPARE_FIELD)
            addAction(ACTION_TOGGLE_SPLIT_SCREEN)
            addAction(ACTION_REPIN_FREEFORM)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, keystrokeReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!KeyboardSettings.isDocked(this)) {
            focusedEditable = null
            focusedFieldKey = null
            endSearchSession()
            DockedFreeform.externalAppInFront = false
            setDockedOverlayVisible(false)
            return
        }
        updateFreeformState()
        val hideForScroll = event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            event.packageName?.toString() != packageName &&
            !isEditableEvent(event)
        setDockedOverlayVisible(!isSystemRecentsSurface(event) && !hideForScroll)
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                focusedEditable = findEditableTarget()
                if (focusedEditable != null && !isSystemRecentsSurface(event)) {
                    setDockedOverlayVisible(true)
                } else {
                    focusedEditable = null
                }
            }
        }
    }

    private fun isEditableEvent(event: AccessibilityEvent?): Boolean {
        return event?.source?.takeIf { it.refresh() }?.let { node ->
            node.isEditable || node.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true
        } == true
    }

    private fun isSystemRecentsSurface(event: AccessibilityEvent?): Boolean {
        val packageName = event?.packageName?.toString().orEmpty()
        val className = event?.className?.toString().orEmpty()
        if (looksLikeRecents(packageName, className)) return true
        return windows.any { window ->
            val root = window.root
            looksLikeRecents(
                root?.packageName?.toString().orEmpty(),
                window.title?.toString().orEmpty()
            )
        }
    }

    private fun looksLikeRecents(packageName: String, descriptor: String): Boolean {
        val text = "$packageName $descriptor".lowercase()
        val systemSurface = text.contains("systemui") ||
            text.contains("launcher") ||
            text.contains("recents") ||
            text.contains("overview")
        if (!systemSurface) return false
        return text.contains("recent") ||
            text.contains("recents") ||
            text.contains("overview") ||
            text.contains("taskview") ||
            text.contains("task_view") ||
            text.contains("multitask") ||
            text.contains("launcherrecents")
    }

    private fun setDockedOverlayVisible(visible: Boolean) {
        sendBroadcast(Intent(ACTION_SET_DOCKED_OVERLAY_VISIBLE).apply {
            setPackage(packageName)
            putExtra(EXTRA_VISIBLE, visible)
        })
    }

    private fun injectKey(raw: String) {
        // Prefer an editable in the FOREGROUND APP window (not this launcher's own window, which can
        // take focus when its keyboard deck is tapped). Then fall back to the tracked focused node
        // and finally the active window.
        val target = focusedEditable?.takeIf { it.isEditable && it.refresh() && it.packageName?.toString() != packageName }
            ?: findForegroundAppEditable()
            ?: findEditableTarget()

        if (target == null) {
            // No field on screen: buffer printable characters and tap the app's search once, so the
            // field it opens can be filled by [flushPendingSearch].
            when (raw) {
                KEY_BACKSPACE -> if (pendingSearchText.isNotEmpty()) pendingSearchText.deleteCharAt(pendingSearchText.length - 1)
                KEY_ENTER -> {}
                else -> {
                    pendingSearchText.append(raw)
                    if (!searchOpenAttempted) {
                        searchOpenAttempted = true
                        searchOpenRetries = 0
                        if (openForegroundAppSearch()) {
                            handler.removeCallbacks(flushRunnable)
                            handler.postDelayed(flushRunnable, SEARCH_OPEN_DELAY_MS)
                        } else {
                            pendingSearchText.clear()
                            searchOpenAttempted = false
                        }
                    }
                }
            }
            return
        }

        // If we opened this search, own the whole text for a short session: rewrite the field with
        // the full buffer on every key so fast keystrokes during the open transition aren't lost to
        // read-modify-write races. The session ends after a short idle gap.
        if (searchOpenAttempted || searchSessionActive || pendingSearchText.isNotEmpty()) {
            handler.removeCallbacks(flushRunnable)
            searchSessionActive = true
            searchOpenAttempted = false
            searchOpenRetries = 0
            when (raw) {
                KEY_BACKSPACE -> if (pendingSearchText.isNotEmpty()) pendingSearchText.deleteCharAt(pendingSearchText.length - 1)
                KEY_ENTER -> {
                    focusAndSetText(target, pendingSearchText.toString())
                    submitImeAction(target)
                    endSearchSession()
                    return
                }
                else -> pendingSearchText.append(raw)
            }
            focusAndSetText(target, pendingSearchText.toString())
            handler.removeCallbacks(endSessionRunnable)
            handler.postDelayed(endSessionRunnable, SEARCH_SESSION_IDLE_MS)
            return
        }

        // Field the user focused themselves. Track what we typed in a local buffer keyed to the
        // field, so we never read (and re-append) the app's placeholder and deletes always clear.
        // Focus the field if it isn't already, so apps that search-as-you-type react to the input.
        if (!target.isFocused) {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
        target.refresh()   // fresh text + isShowingHintText before we seed the buffer
        if (raw == KEY_ENTER && submitImeAction(target)) return
        val key = fieldFingerprint(target)
        if (key != focusedFieldKey) {
            // New field: seed the buffer from its real (placeholder-stripped) text once.
            focusedFieldKey = key
            focusedBuffer.setLength(0)
            focusedBuffer.append(target.editableText())
        }
        when (raw) {
            KEY_BACKSPACE -> if (focusedBuffer.isNotEmpty()) focusedBuffer.deleteCharAt(focusedBuffer.length - 1)
            KEY_ENTER -> focusedBuffer.append('\n')
            else -> focusedBuffer.append(raw)
        }
        val next = focusedBuffer.toString()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next)
        }
        val success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!success && raw != KEY_BACKSPACE && raw != KEY_ENTER) {
            pasteText(target, raw)
            focusedFieldKey = null   // paste path doesn't track cleanly — re-seed next keystroke
            return
        }
        // Keep the cursor at the end so the next character appends cleanly.
        runCatching {
            target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, next.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, next.length)
            })
        }
    }

    private fun fieldFingerprint(node: AccessibilityNodeInfo): String {
        val b = Rect(); node.getBoundsInScreen(b)
        return "${node.packageName}|${node.viewIdResourceName ?: ""}|${b.left},${b.top},${b.right},${b.bottom}"
    }

    /** Fill the field that appeared after tapping search with the buffered characters. */
    private fun flushPendingSearch() {
        val target = findForegroundAppEditable()
            ?: focusedEditable?.takeIf { it.isEditable && it.refresh() }
        if (target == null) {
            // No field yet — some apps are two levels deep (e.g. Home → Search tab → search bar).
            // Tap the next search affordance once more, then try again.
            if (searchOpenRetries < 2 && pendingSearchText.isNotEmpty() && openForegroundAppSearch()) {
                searchOpenRetries++
                handler.postDelayed(flushRunnable, SEARCH_OPEN_DELAY_MS)
                return
            }
            endSearchSession()
            return
        }
        if (pendingSearchText.isEmpty()) { endSearchSession(); return }
        // Keep the buffer as the session text so subsequent keystrokes keep rewriting the field.
        searchSessionActive = true
        searchOpenAttempted = false
        searchOpenRetries = 0
        focusAndSetText(target, pendingSearchText.toString())
        handler.removeCallbacks(endSessionRunnable)
        handler.postDelayed(endSessionRunnable, SEARCH_SESSION_IDLE_MS)
    }

    private fun endSearchSession() {
        handler.removeCallbacks(endSessionRunnable)
        pendingSearchText.clear()
        searchOpenAttempted = false
        searchOpenRetries = 0
        searchSessionActive = false
    }

    private fun focusAndSetText(target: AccessibilityNodeInfo, text: String) {
        if (!target.isFocused) {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            target.refresh()
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok && text.isNotEmpty()) pasteText(target, text)
        // Put the cursor at the end so the IME appends the rest of the word cleanly.
        runCatching {
            target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
            })
        }
    }

    /**
     * Make sure a text field is focused in the foreground app so the IME binds and can commit the
     * launcher deck's buffered keys: focus an existing editable, or open the app's search (with the
     * two-level retry). No text is set here — the IME owns text entry via its InputConnection.
     */
    private fun prepareForegroundField() {
        handler.removeCallbacks(prepareRetryRunnable)
        searchOpenRetries = 0
        val editable = findForegroundAppEditable()
        if (editable != null) {
            if (!editable.isFocused) editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            return
        }
        if (openForegroundAppSearch()) {
            handler.postDelayed(prepareRetryRunnable, SEARCH_OPEN_DELAY_MS)
        }
    }

    private fun retryPrepareField() {
        val editable = findForegroundAppEditable()
        if (editable != null) {
            if (!editable.isFocused) editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            searchOpenRetries = 0
            return
        }
        if (searchOpenRetries < 2 && openForegroundAppSearch()) {
            searchOpenRetries++
            handler.postDelayed(prepareRetryRunnable, SEARCH_OPEN_DELAY_MS)
        } else {
            searchOpenRetries = 0
        }
    }

    /** Tap the foreground app's search affordance (its search icon or bar), if one is on screen. */
    private fun openForegroundAppSearch(): Boolean {
        val root = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .filter { it.root?.packageName?.toString().let { pkg -> pkg != null && pkg != packageName } }
            .sortedByDescending { it.isFocused }
            .firstOrNull()
            ?.root ?: return false
        val candidates = ArrayList<AccessibilityNodeInfo>()
        root.collectSearchAffordances(candidates)
        // Prefer the topmost affordance — search bars sit above bottom-nav "Search" tabs, so this
        // steps into the search field rather than re-selecting the tab.
        val affordance = candidates.minByOrNull { node ->
            val bounds = Rect(); node.getBoundsInScreen(bounds); bounds.top
        } ?: return false
        return affordance.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun AccessibilityNodeInfo.collectSearchAffordances(out: MutableList<AccessibilityNodeInfo>) {
        val hay = "${text ?: ""} ${contentDescription ?: ""} ${viewIdResourceName ?: ""}".lowercase()
        if (hay.contains("search") || hay.contains("what do you want to listen")) {
            var node: AccessibilityNodeInfo? = this
            while (node != null) {
                if (node.isClickable) { out.add(node); break }
                node = node.parent
            }
        }
        for (index in 0 until childCount) {
            getChild(index)?.collectSearchAffordances(out)
        }
    }

    private fun submitImeAction(target: AccessibilityNodeInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } else {
            false
        }
    }

    private fun AccessibilityNodeInfo.editableText(): String {
        // The field is empty and only showing its placeholder — treat as empty, so we don't append
        // to (or fail to delete) the hint text. This is what caused "Message" + typed char in apps
        // like Telegram/WhatsApp whose compose box exposes its placeholder as node.text.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isShowingHintText) return ""
        val value = text?.toString().orEmpty()
        if (value.isBlank()) return ""
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) hintText?.toString().orEmpty() else ""
        val desc = contentDescription?.toString().orEmpty()
        // Some apps set neither isShowingHintText nor hintText — the placeholder only appears as the
        // node's text (or contentDescription). Match those against the app's own hint and a short list
        // of well-known compose/search placeholders.
        if (hint.isNotBlank() && value.equals(hint, ignoreCase = true)) return ""
        if (desc.isNotBlank() && value.equals(desc, ignoreCase = true)) return ""
        val normalized = value.trim().trimEnd('.', '…', ' ').trim().lowercase()
        if (normalized in PLACEHOLDER_TEXTS) return ""
        val looksLikeBrowserPlaceholder = normalized.contains("search") &&
            (normalized.contains("google") || normalized.contains("type") || normalized.contains("url") || normalized.contains("web"))
        return if (looksLikeBrowserPlaceholder) "" else value
    }

    private fun findEditableTarget(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: windows
            .firstOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?.root
        return root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: root?.findEditableDescendant()
    }

    /**
     * Set [DockedFreeform.externalAppInFront] from real window bounds: true when a non-launcher app
     * window sits in the top region (its bottom well above the screen bottom, leaving room for the
     * docked deck). Drives IME suppression + keyboard routing without a stale lifecycle flag.
     */
    private fun updateFreeformState() {
        if (!dockedTopRegionAvailable()) {
            DockedFreeform.externalAppInFront = false
            return
        }
        val displayHeight = resources.displayMetrics.heightPixels
        val topApp = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .filter { it.root?.packageName?.toString().let { pkg -> pkg != null && pkg != packageName } }
            .maxByOrNull { it.layer }
        if (topApp == null) {
            DockedFreeform.externalAppInFront = false
            return
        }
        val bounds = Rect(); topApp.getBoundsInScreen(bounds)
        // Shizuku re-pin: if the app's window dips into the keyboard band (in-app navigation resized
        // it, or the OEM launched it fullscreen), force it back to the top region. Holds it pinned
        // across navigation and works even where freeform launchBounds are ignored.
        val keyboardTop = DockedFreeform.pinBounds(this).bottom
        val slop = (8 * resources.displayMetrics.density).toInt()
        if (kotlin.math.abs(bounds.bottom - keyboardTop) > slop && !isSystemRecentsSurface(null)) {
            topApp.root?.packageName?.toString()?.let { requestPin(it) }
        }
        val inTopRegion = bounds.bottom in 1 until (displayHeight - (200 * resources.displayMetrics.density).toInt())
        DockedFreeform.externalAppInFront = inTopRegion
    }

    private var pinPackage: String? = null
    private var lastPinAtMs = 0L
    private val pinRunnable = Runnable { doPin() }

    private fun requestPin(pkg: String) {
        if (!ShizukuPinner.isReady()) return
        pinPackage = pkg
        handler.removeCallbacks(pinRunnable)
        handler.postDelayed(pinRunnable, 120)   // debounce the burst of window-change events
    }

    private fun repinForegroundFreeform() {
        if (!dockedTopRegionAvailable()) return
        val topApp = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .filter { it.root?.packageName?.toString().let { pkg -> pkg != null && pkg != packageName } }
            .maxByOrNull { it.layer }
            ?: return
        topApp.root?.packageName?.toString()?.let { requestPin(it) }
    }

    private fun dockedTopRegionAvailable(): Boolean =
        KeyboardSettings.isDocked(this) &&
            (DockedFreeform.isActive(this) || (DockedFreeform.isFeatureEnabled(this) && ShizukuPinner.isReady()))

    private fun doPin() {
        val pkg = pinPackage ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPinAtMs < 400) return     // cooldown so a stubborn app can't cause a pin storm
        lastPinAtMs = now
        val bounds = DockedFreeform.pinBounds(this)
        Thread { ShizukuPinner.pin(pkg, bounds) }.start()   // binder call off the main thread
    }

    /** Editable field in the foreground app window — any TYPE_APPLICATION window not owned by us. */
    private fun findForegroundAppEditable(): AccessibilityNodeInfo? {
        val root = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .filter { it.root?.packageName?.toString().let { pkg -> pkg != null && pkg != packageName } }
            .sortedByDescending { it.isFocused }
            .firstOrNull()
            ?.root ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: root.findEditableDescendant()
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
        clipboard.setPrimaryClip(ClipData.newPlainText("teclas_inject", text))
        target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runCatching { unregisterReceiver(keystrokeReceiver) }
        handler.removeCallbacks(flushRunnable)
        handler.removeCallbacks(prepareRetryRunnable)
        endSearchSession()
        focusedEditable = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_INJECT_KEY = "com.fran.teclas.ACTION_INJECT_KEY"
        const val ACTION_PREPARE_FIELD = "com.fran.teclas.ACTION_PREPARE_FIELD"
        const val ACTION_TOGGLE_SPLIT_SCREEN = "com.fran.teclas.ACTION_TOGGLE_SPLIT_SCREEN"
        const val ACTION_REPIN_FREEFORM = "com.fran.teclas.ACTION_REPIN_FREEFORM"
        const val ACTION_SET_DOCKED_OVERLAY_VISIBLE = "com.fran.teclas.ACTION_SET_DOCKED_OVERLAY_VISIBLE"
        const val EXTRA_CHAR = "extra_char"
        const val EXTRA_VISIBLE = "extra_visible"
        const val KEY_BACKSPACE = "__BACKSPACE__"
        const val KEY_ENTER = "__ENTER__"
        // Placeholders some apps expose as the field's own text (no isShowingHintText / hintText),
        // matched case-insensitively after trimming trailing punctuation. Treated as an empty field.
        private val PLACEHOLDER_TEXTS = setOf(
            "message", "type a message", "write a message", "send a message", "type message",
            "your message", "message…", "search", "search messages", "type here", "aa"
        )
        // How long to wait after tapping search for the app's field to appear before filling it.
        private const val SEARCH_OPEN_DELAY_MS = 450L
        // Idle gap after which a docked-typed search session ends (field returns to per-key edits).
        private const val SEARCH_SESSION_IDLE_MS = 4000L
    }
}
