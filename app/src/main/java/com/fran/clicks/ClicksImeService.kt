package com.fran.clicks

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.HorizontalScrollView
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import com.fran.clicks.glide.KeyInfo
import com.fran.clicks.glide.StatisticalGlideTypingClassifier
import com.fran.clicks.keyboard.KeyPreviewManager
import com.fran.clicks.keyboard.applyDoubleSpacePeriod
import com.fran.clicks.keyboard.shouldAutoCapitalize
import com.fran.clicks.keyboard.PredictionEngine
import com.fran.clicks.keyboard.SpatialScorer
import com.fran.clicks.keyboard.neural.TimedPoint
import com.fran.clicks.db.NgramRepository
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

class ClicksImeService : InputMethodService(), com.fran.clicks.keyboard.KeyboardHost {

    // ── KeyboardHost (shared-core seam) — wraps the IME's existing InputConnection behavior ──
    override fun commitText(text: String) = commitValue(text)
    override fun deleteBeforeCursor(count: Int) { currentInputConnection?.deleteSurroundingText(count, 0) }
    override fun textBeforeCursor(count: Int): String =
        currentInputConnection?.getTextBeforeCursor(count, 0)?.toString().orEmpty()
    override fun textAfterCursor(count: Int): String =
        currentInputConnection?.getTextAfterCursor(count, 0)?.toString().orEmpty()
    override fun moveCursor(right: Boolean) =
        keyEvent(if (right) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
    override fun editorPackage(): String? = currentEditorPackage
    override fun isPasswordField(): Boolean {
        val t = currentInputEditorInfo?.inputType ?: return false
        val cls = t and android.text.InputType.TYPE_MASK_CLASS
        val variation = t and android.text.InputType.TYPE_MASK_VARIATION
        return (cls == android.text.InputType.TYPE_CLASS_TEXT &&
            (variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) ||
            (cls == android.text.InputType.TYPE_CLASS_NUMBER &&
                variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }
    override val hostHapticsEnabled: Boolean get() = imePrefs().getBoolean(HAPTICS_PREF, true)
    override fun onAgenticCommand(text: String) { runAgenticCommand() }
    override fun openHostKeyboardSettings() = openImeSettings()
    private var shifted = false
    private var symbolsMode = false
    private var capsLock = false
    private var lastShiftTapMs = 0L
    private val keyPreview by lazy { KeyPreviewManager(this) }
    private var polishing = false
    private var agenticStatus: String? = null
    private var pendingCommand: AgenticRouter.Command? = null
    private var pendingCommandText: String = ""
    private var pendingActions: List<Pair<String, String>> = emptyList()
    private var deckView: SwipeImeKeyboardLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private val keyViews = mutableMapOf<String, TextView>()
    private val keyBounds = linkedMapOf<String, Rect>()
    private var glideClassifier: StatisticalGlideTypingClassifier? = null
    // Optional neural glide decoder (encoder-decoder + beam search). No-op until a model ships in
    // assets; when present it decodes ahead of the statistical classifier. Wired identically in the
    // launcher's Widget keyboard via the same NeuralGlideEngine.
    private var neuralGlide: com.fran.clicks.keyboard.neural.NeuralGlideEngine? = null
    private val glideScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )
    private var predictionEngine = PredictionEngine(emptyMap())         // active pointer (primary or extended)
    private var predictionEnginePrimary = PredictionEngine(emptyMap())  // primary language only
    private var predictionEngineExtended = PredictionEngine(emptyMap()) // primary + secondary phone languages
    private var hasLatentLanguages = false
    private var latentLanguageActive = false
    private val spatialScorer = SpatialScorer()
    private val ngramRepo by lazy { NgramRepository(this) }
    private val hapticEngine by lazy { com.fran.clicks.keyboard.CustomHapticEngine(this) }
    private val autocorrect by lazy {
        com.fran.clicks.keyboard.AutocorrectCore(
            host = this,
            engine = { predictionEngine },
            contextNextWords = { ngramRepo.cachedNextWords(it) },
            extendedEngine = { predictionEngineExtended }
        )
    }
    private val predictionCore by lazy {
        com.fran.clicks.keyboard.PredictionCore(this, { predictionEngine }, ngramRepo)
    }
    private val glideCore by lazy { com.fran.clicks.keyboard.GlideCore(this, ngramRepo) }
    private var suggestionStrip: LinearLayout? = null
    private var agenticPanel: LinearLayout? = null
    private var inlineScroll: HorizontalScrollView? = null   // Gboard-style autofill chip row
    private var inlineRow: LinearLayout? = null
    // Agentic emoji + smart symbols shown in the suggestion strip: (display, textToInsert, isEmoji).
    private var smartChips: List<Triple<String, String, Boolean>> = emptyList()
    private var emojiTriggerWord: String = ""

    // A HUD result shown in the agentic panel (translation / mood read / drafted reply). [insert] is
    // non-null when the result can be dropped into the field with an Insert button.
    private data class Hud(val kicker: String, val body: String, val insert: String?)
    private var agenticHud: Hud? = null

    // Swipe up on a key to insert its symbol without leaving letters (mirrors the symbols layout).
    private val keyUpSymbols get() = com.fran.clicks.keyboard.KeyboardSymbols.keyUp
    private var suggestions: List<String> = emptyList()
    private var suggestDebounce: Runnable? = null
    private var liveCorrectDebounce: Runnable? = null
    private var geminiDebounce: Runnable? = null
    private var lastSpaceMs = 0L
    private var deleteRepeatRunnable: Runnable? = null
    private var deleteRepeatActive = false
    private var deleteRepeatFired = false
    private var currentEditorPackage: String? = null

    override fun onEvaluateInputViewShown(): Boolean {
        return !isLauncherEditorActive()
    }

    override fun onCreateInputView(): View {
        shifted = false
        ensureGlideClassifier()
        spatialScorer.importState(imePrefs().getString(TOUCH_MODEL_PREF, "") ?: "")
        AgenticRouter.ensureLoaded(this)
        return buildKeyboard().also { deckView = it }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentEditorPackage = info?.packageName?.toString() ?: currentEditorPackage
        if (isLauncherEditorActive()) {
            hideImeSurface()
            return
        }
        refreshChromeOrRebuild()
        updateInputViewShown()
        clearInlineSuggestions()
        agenticHud = null
        scheduleSuggestions()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        currentEditorPackage = attribute?.packageName?.toString()
        if (isLauncherEditorActive()) {
            hideImeSurface()
            return
        }
        shifted = shouldStartShifted(attribute)
        refreshChromeOrRebuild()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (isLauncherEditorActive()) hideImeSurface()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (!isLauncherEditorActive()) scheduleSuggestions()
    }

    private fun isLauncherEditorActive(): Boolean {
        return currentEditorPackage == packageName
    }

    private fun hideImeSurface() {
        stopDeleteRepeat(clearFired = true)
        requestHideSelf(0)
        runCatching { hideWindow() }
    }

    override fun onDestroy() {
        stopDeleteRepeat(clearFired = true)
        imePrefs().edit().putString(TOUCH_MODEL_PREF, spatialScorer.exportState()).apply()
        glideScope.cancel()
        neuralGlide?.close()
        super.onDestroy()
    }

    private fun buildKeyboard(): SwipeImeKeyboardLayout {
        keyViews.clear()
        lastBuiltTheme = keyboardTheme()
        return SwipeImeKeyboardLayout().apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(8))
            background = deckBackground()
            minimumHeight = imeKeyboardHeight()
            addView(buildSuggestionStrip(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
            addView(buildAgenticPanel(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(4); rightMargin = dp(4); topMargin = dp(1); bottomMargin = dp(2)
            })
            addView(buildInlineAutofillRow(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply {
                leftMargin = dp(4); rightMargin = dp(4)
            })
            val rows = if (symbolsMode) listOf(
                com.fran.clicks.keyboard.KeyboardSymbols.ROW_DIGITS,
                com.fran.clicks.keyboard.KeyboardSymbols.ROW_SYMBOLS_1,
                com.fran.clicks.keyboard.KeyboardSymbols.ROW_SYMBOLS_2 + listOf("back"),
                listOf("abc", "clicks", "space", ".", "enter")
            ) else listOf(
                "qwertyuiop".map { it.toString() },
                "asdfghjkl".map { it.toString() },
                listOf("shift") + "zxcvbnm".map { it.toString() } + listOf("back"),
                listOf("123", "clicks", "space", ".", "enter")
            )
            rows.forEachIndexed { rowIndex, row ->
                addView(keyRow(row, rowIndex), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, keyRowHeight()).apply {
                    if (rowIndex > 0) topMargin = -keyRowOverlap()
                })
            }
            post { captureKeyBounds() }
        }
    }

    private var lastBuiltTheme: String? = null

    private fun rebuildDeck() {
        lastBuiltTheme = keyboardTheme()
        setInputView(buildKeyboard().also { deckView = it })
    }

    // On focus, rebuild the whole deck (fresh cached backgrounds) only if the theme changed;
    // otherwise a light text-only chrome refresh — keeps the cached key backgrounds valid.
    private fun refreshChromeOrRebuild() {
        if (keyboardTheme() != lastBuiltTheme) rebuildDeck() else refreshKeyboardChrome()
    }

    private fun keyRow(labels: List<String>, rowIndex: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val inset = when (rowIndex) {
                1 -> dp(12)
                2 -> dp(6)
                3 -> dp(18)
                else -> 0
            }
            setPadding(inset, 0, inset, 0)
            labels.forEach { label ->
                if (label == "enter") {
                    addView(keyView(label), LinearLayout.LayoutParams(themedGoKeySize(), themedGoKeySize()).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        marginStart = dp(4)
                    })
                } else if (label == "123") {
                    addView(keyView(label), LinearLayout.LayoutParams(themedGoKeySize(), themedGoKeySize()).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        marginEnd = dp(4)
                    })
                } else {
                    addView(keyView(label), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, keyWeight(label)))
                }
            }
        }
    }

    private fun keyView(label: String): TextView {
        val isLetter = label.length == 1 && label[0].isLetter() && !symbolsMode
        return (if (isLetter) com.fran.clicks.keyboard.DynamicFlickKeyView(this) else TextView(this)).apply {
            tag = label
            text = if (isLetter) visualLabel(label) else keyDisplayText(label)
            gravity = Gravity.CENTER
            includeFontPadding = false
            textSize = keyTextSize(label)
            typeface = if (label == "enter") Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor(label))
            if (isLetter && this is com.fran.clicks.keyboard.DynamicFlickKeyView) {
                setKeyFaceInsets(dp(1), keyVerticalInset())
                val sym = com.fran.clicks.keyboard.KeyboardSymbols.keyUp[label.lowercase(Locale.US)]
                setSymbolHint(sym, symbolHintColor())
            }
            background = visualKeyBackground(label, pressed = false)
            isClickable = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setOnTouchListener(ImeKeyTouchListener(label))
            keyViews[label] = this
        }
    }

    private inner class ImeKeyTouchListener(private val label: String) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var clicksLongPressRunnable: Runnable? = null
        private var clicksLongPressFired = false
        private var spaceCursorLastX = 0f
        private var spaceCursorMoved = false
        // Cache backgrounds once per key so press/release don't allocate a drawable every tap —
        // that per-keystroke allocation was the main reason the IME felt slower than the launcher.
        private val idleBg = visualKeyBackground(label, pressed = false)
        private val pressedBg = visualKeyBackground(label, pressed = true)

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    spaceCursorLastX = event.rawX
                    spaceCursorMoved = false
                    clicksLongPressFired = false
                    v.background = pressedBg
                    keyHaptic(label)
                    keyPreview.show(v, label)
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(45L).start()
                    if (label == "clicks") {
                        val runnable = Runnable {
                            clicksLongPressFired = true
                            keyHaptic("enter")
                            openLauncherKeyboardAction(ClicksKeyboardActions.SWITCH_TO_WIDGET_MODE)
                        }
                        clicksLongPressRunnable = runnable
                        handler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    if (label == "enter" || label == "space") {
                        // Two hold triggers, one ramp. Go/enter runs the typed line as an agentic
                        // command; space asks Gemini to keep writing. Taps are unaffected.
                        val holdLabel = label
                        val total = (ViewConfiguration.getLongPressTimeout() * 1.25).toLong()
                        startAgenticHapticRamp(total)
                        val runnable = Runnable {
                            clicksLongPressFired = true
                            agenticConfirmHaptic()
                            if (holdLabel == "space") runGeminiCompose() else runAgenticCommand()
                        }
                        clicksLongPressRunnable = runnable
                        handler.postDelayed(runnable, total)
                    }
                    if (label == "123" || label == "abc") {
                        // Long-press the mode key to open Keyboard Settings; a tap still flips
                        // symbols/letters.
                        val runnable = Runnable {
                            clicksLongPressFired = true
                            keyHaptic("enter")
                            openImeSettings()
                        }
                        clicksLongPressRunnable = runnable
                        handler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    if (label == "back") startDeleteRepeat()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if ((label == "clicks" || label == "enter" || label == "space" || label == "123" || label == "abc") &&
                        (abs(event.rawX - downRawX) > ViewConfiguration.get(this@ClicksImeService).scaledTouchSlop ||
                            abs(event.rawY - downRawY) > ViewConfiguration.get(this@ClicksImeService).scaledTouchSlop)) {
                        cancelClicksLongPress()
                    }
                    // Space-swipe cursor control (parity with the launcher): drag left/right on the
                    // space bar to move the caret a character at a time via DPAD key events.
                    if (label == "space") {
                        val step = dp(9).toFloat()
                        var delta = event.rawX - spaceCursorLastX
                        while (abs(delta) >= step) {
                            keyEvent(if (delta > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                            spaceCursorMoved = true
                            spaceCursorLastX += if (delta > 0) step else -step
                            delta = event.rawX - spaceCursorLastX
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    v.background = idleBg
                    keyPreview.dismiss()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150L)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2.4f)).start()
                    cancelClicksLongPress()
                    if (clicksLongPressFired) {
                        clicksLongPressFired = false
                        return true
                    }
                    if (label == "space" && spaceCursorMoved) {
                        spaceCursorMoved = false
                        return true   // it was a cursor drag, not a space insert
                    }
                    if (label == "back") {
                        val repeated = deleteRepeatFired
                        stopDeleteRepeat(clearFired = true)
                        if (repeated) return true
                    }
                    handleKey(resolveTapKey(label, event.rawX, event.rawY))
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.background = idleBg
                    keyPreview.dismiss()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                    cancelClicksLongPress()
                    clicksLongPressFired = false
                    if (label == "back") stopDeleteRepeat(clearFired = true)
                    return true
                }
            }
            return true
        }

        private fun cancelClicksLongPress() {
            clicksLongPressRunnable?.let { handler.removeCallbacks(it) }
            clicksLongPressRunnable = null
            stopAgenticHapticRamp()
        }
    }

    private fun handleKey(label: String) {
        pendingCommand = null
        pendingActions = emptyList()
        val input = currentInputConnection
        when (label) {
            "shift" -> {
                val now = System.currentTimeMillis()
                when {
                    capsLock -> { capsLock = false; shifted = false }
                    shifted && now - lastShiftTapMs < 350L -> capsLock = true   // double-tap = lock
                    shifted -> shifted = false
                    else -> shifted = true
                }
                lastShiftTapMs = now
                refreshKeyboardChrome()
            }
            "back" -> {
                // Undo autocorrect via the shared core (restore original + remember rejection).
                if (input != null && autocorrect.undoOnBackspace()) { onTextChanged(); return }
                if (input == null) {
                    VivoDockedExperiment.injectInput("⌫")
                } else if (!input.deleteSurroundingText(1, 0)) {
                    keyEvent(KeyEvent.KEYCODE_DEL)
                }
                onTextChanged()
            }
            "enter" -> keyEvent(KeyEvent.KEYCODE_ENTER)
            "space" -> {
                if (maybeRunAiCommand()) return
                // Double-space → ". " via the shared core.
                val now = System.currentTimeMillis()
                if (now - lastSpaceMs < 500L && applyDoubleSpacePeriod()) {
                    lastSpaceMs = 0L; updateAutoCap(); return
                }
                lastSpaceMs = now
                if (autocorrectEnabled()) autocorrect.correctBeforeCommit()
                commitValue(" ")
                learnAndPredictAfterSpace()
                updateAutoCap()
            }
            "clicks" -> openImeSettings()
            "123" -> { symbolsMode = true; rebuildDeck() }
            "abc" -> { symbolsMode = false; rebuildDeck() }
            "." -> commitValue(".")
            else -> {
                autocorrect.clearPending()
                commitValue(if ((shifted || capsLock) && label.length == 1) label.uppercase() else label)
                if (shifted && !capsLock && label.length == 1 && label[0].isLetter()) {
                    shifted = false
                    refreshKeyboardChrome()
                }
                onTextChanged()
            }
        }
    }

    private fun openLauncherKeyboardAction(action: String) {
        requestHideSelf(0)
        runCatching { hideWindow() }
        startActivity(Intent(this, MainActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    // Open the IME's OWN settings (not the launcher's clicks settings) — the keyboard used in other
    // apps should configure itself, not jump into the launcher.
    private fun openImeSettings() {
        requestHideSelf(0)
        runCatching { hideWindow() }
        startActivity(Intent(this, ImeSettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun commitValue(value: String) {
        val input = currentInputConnection
        if (input != null) input.commitText(value, 1) else VivoDockedExperiment.injectInput(value)
    }

    private fun keyEvent(code: Int) {
        val input = currentInputConnection
        if (input != null) {
            input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        } else {
            VivoDockedExperiment.injectInput(if (code == KeyEvent.KEYCODE_ENTER) "⏎" else "")
        }
    }

    private fun keyHaptic(label: String) {
        if (!imePrefs().getBoolean(HAPTICS_PREF, true)) return
        // Tuned per-label composition primitives (parity with the launcher keyboard) instead of the
        // coarse system constants.
        hapticEngine.tap(label)
    }

    private fun hapticsOn() = imePrefs().getBoolean(HAPTICS_PREF, true)

    private fun startDeleteRepeat() {
        stopDeleteRepeat(clearFired = true)
        deleteRepeatActive = true
        val repeat = object : Runnable {
            override fun run() {
                if (!deleteRepeatActive) return
                deleteRepeatFired = true
                handleKey("back")
                keyHaptic("back")
                handler.postDelayed(this, 45L)
            }
        }
        deleteRepeatRunnable = repeat
        handler.postDelayed(repeat, 380L)
    }

    private fun stopDeleteRepeat(clearFired: Boolean) {
        deleteRepeatRunnable?.let { handler.removeCallbacks(it) }
        deleteRepeatRunnable = null
        deleteRepeatActive = false
        if (clearFired) deleteRepeatFired = false
    }

    private fun captureKeyBounds() {
        keyBounds.clear()
        val loc = IntArray(2)
        keyViews.forEach { (label, view) ->
            if (view.width <= 0 || view.height <= 0) return@forEach
            view.getLocationOnScreen(loc)
            keyBounds[label] = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        }
        spatialScorer.setKeys(keyBounds)
        updateGlideLayout()
    }

    private fun updateGlideLayout() {
        val clf = glideClassifier ?: return
        if (keyBounds.isEmpty()) return
        val keyInfos = keyBounds.mapNotNull { (label, rect) ->
            if (label.length != 1 || !label[0].isLetter()) return@mapNotNull null
            KeyInfo(
                char = label[0],
                centerX = rect.exactCenterX(),
                centerY = rect.exactCenterY(),
                width = rect.width().toFloat(),
                height = rect.height().toFloat()
            )
        }
        clf.setLayout(keyInfos)
    }

    private val tapResolver by lazy { com.fran.clicks.keyboard.TapResolver(spatialScorer) }

    private fun resolveTapKey(label: String, rawX: Float, rawY: Float): String = tapResolver.resolve(
        label, rawX, rawY, keyBounds, smartTouchEnabled(),
        nextCharWeights = { predictionEngine.nextCharWeights(currentWord()) },
        fallback = { x, y -> keyAtPoint(x, y, letterOnly = true) }
    )

    private fun smartTouchEnabled() = imePrefs().getBoolean(IME_SMART_TOUCH_PREF, true)

    private fun keyAtPoint(rawX: Float, rawY: Float, letterOnly: Boolean = false): String? {
        if (keyBounds.isEmpty()) captureKeyBounds()
        val x = rawX.toInt()
        val y = rawY.toInt()
        keyBounds.entries.firstOrNull { (label, rect) ->
            (!letterOnly || (label.length == 1 && label[0].isLetter())) && rect.contains(x, y)
        }?.let { return it.key }
        return keyBounds.entries
            .filter { (label, _) -> !letterOnly || (label.length == 1 && label[0].isLetter()) }
            .minByOrNull { (_, rect) ->
                val dx = rect.exactCenterX() - rawX
                val dy = rect.exactCenterY() - rawY
                dx * dx + dy * dy
            }
            ?.key
    }

    // Screen-space [left, top, width, height] of the letter keys — used to normalize a glide into
    // [0,1] so the neural decoder is independent of Docked vs. Widget keyboard size.
    private fun letterKeyBounds(): FloatArray? {
        val rects = keyBounds.filterKeys { it.length == 1 && it[0].isLetter() }.values
        if (rects.isEmpty()) return null
        val left = rects.minOf { it.left }.toFloat()
        val top = rects.minOf { it.top }.toFloat()
        val right = rects.maxOf { it.right }.toFloat()
        val bottom = rects.maxOf { it.bottom }.toFloat()
        return floatArrayOf(left, top, right - left, bottom - top)
    }

    // Letter-key centers (screen space) for the neural decoder's nearest-key feature.
    private fun letterKeyCenters(): List<KeyInfo> =
        keyBounds.mapNotNull { (label, rect) ->
            if (label.length != 1 || !label[0].isLetter()) return@mapNotNull null
            KeyInfo(label[0], rect.exactCenterX(), rect.exactCenterY(), rect.width().toFloat(), rect.height().toFloat())
        }

    private fun ensureGlideClassifier() {
        if (glideClassifier != null) return
        Thread {
            // Primary language is active by default; secondary phone languages load as a latent
            // extended dictionary that only takes over once the user writes a couple of its words
            // (see updateActiveLanguage). Glide can shape-match the full set so gestures work in any.
            val adaptive = com.fran.clicks.keyboard.DictionaryLoader.loadAdaptive(this)
            val clf = StatisticalGlideTypingClassifier()
            clf.setWordData(adaptive.extendedWords, adaptive.extendedFreqs)
            // Neural decoder shares the same dictionary; load() is a no-op (stays not-ready) until a
            // model is placed in assets, so this never changes behavior on its own.
            val neural = com.fran.clicks.keyboard.neural.NeuralGlideEngine(this).apply {
                setDictionary(adaptive.extendedWords, adaptive.extendedFreqs)
                load()
            }
            handler.post {
                glideClassifier = clf
                neuralGlide = neural
                predictionEnginePrimary = PredictionEngine(adaptive.primaryFreqs)
                predictionEngineExtended = PredictionEngine(adaptive.extendedFreqs)
                hasLatentLanguages = adaptive.latentLangs.isNotEmpty()
                latentLanguageActive = false
                predictionEngine = predictionEnginePrimary
                android.util.Log.d("ClicksGlide", "classifier loaded words=${adaptive.extendedWords.size}")
                updateGlideLayout()
            }
        }.start()
    }

    // Glide word placement/rerank/context now live in the shared GlideCore.

    // ── Prediction / autocorrect / learning — parity with the launcher keyboard ──
    private fun imePrefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun updateAutoCap() {
        if (capsLock) return
        val cap = shouldAutoCapitalize()
        if (shifted != cap) { shifted = cap; refreshKeyboardChrome() }
    }

    private fun currentWord(): String =
        currentInputConnection?.getTextBeforeCursor(48, 0)?.toString().orEmpty().takeLastWhile { it.isLetter() }

    private fun wordsBeforeCursor(): List<String> {
        val before = currentInputConnection?.getTextBeforeCursor(96, 0)?.toString().orEmpty()
        return Regex("[A-Za-z]+").findAll(before).map { it.value }.toList()
    }

    private fun previousWord(): String {
        val before = currentInputConnection?.getTextBeforeCursor(96, 0)?.toString().orEmpty()
        val tokens = Regex("[A-Za-z]+").findAll(before).map { it.value }.toList()
        val endsLetter = before.isNotEmpty() && before.last().isLetter()
        return if (endsLetter) tokens.getOrElse(tokens.size - 2) { "" } else tokens.lastOrNull().orEmpty()
    }

    private fun onTextChanged() {
        // No immediate updateStrip: it would repaint the strip with stale suggestions (and pay a
        // 1KB IPC read) on every keystroke, only to be repainted ~70ms later by scheduleSuggestions.
        // One debounced repaint = half the per-key work and no flicker. Agentic state changes still
        // call updateStrip directly, so previews/status stay instant.
        scheduleSuggestions()
        scheduleLiveCorrect()
        scheduleGemini()
    }

    // Tier 2: blend Gemini's contextual next-word predictions into the strip (Pro + API key only).
    // On-device fills the strip instantly at 70ms; Gemini upgrades it a beat later.
    private fun scheduleGemini() {
        geminiDebounce?.let { handler.removeCallbacks(it) }
        if (!ProManager.isUnlocked(this) || !GeminiClient.configured(imePrefs())) return
        val ctx = currentInputConnection?.getTextBeforeCursor(80, 0)?.toString()?.trim().orEmpty()
        if (ctx.length < 2) return
        val key = GeminiClient.apiKey(imePrefs())
        val model = GeminiClient.model(imePrefs())
        val r = Runnable {
            Thread {
                val g = GeminiClient.fetchSuggestions(key, model, ctx)
                if (g.isNotEmpty()) handler.post {
                    suggestions = (g + suggestions).distinct().take(3)
                    updateStrip()
                }
            }.start()
        }
        geminiDebounce = r
        handler.postDelayed(r, 260L)
    }

    private fun scheduleSuggestions() {
        suggestDebounce?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            computeSmartChips(currentWord())
            val base = predictionCore.computeSuggestions()
            suggestions = if (base.isEmpty()) {
                // IME extra: notification quick-replies when the field is empty.
                val ic = currentInputConnection
                val before = ic?.getTextBeforeCursor(2, 0)?.toString().orEmpty()
                val fieldEmpty = before.isEmpty() && (ic?.getTextAfterCursor(1, 0)?.isEmpty() != false)
                if (fieldEmpty) NotificationReplyContext.quickReplies(imePrefs(), System.currentTimeMillis()) else emptyList()
            } else base
            updateStrip()
        }
        suggestDebounce = r
        handler.postDelayed(r, 70L)
    }

    // Build emoji + smart-symbol chips for the current word/context. Emoji lead; symbols fill in.
    private fun computeSmartChips(word: String) {
        val chips = ArrayList<Triple<String, String, Boolean>>()
        emojiTriggerWord = ""
        if (word.length >= 2) {
            emojiTriggerWord = word.lowercase()
            SmartChips.emojiFor(imePrefs(), emojiTriggerWord).take(4).forEach { chips.add(Triple(it, it, true)) }
        }
        val before = currentInputConnection?.getTextBeforeCursor(24, 0)?.toString().orEmpty()
        SmartChips.symbolsFor(before, word).forEach { s -> if (chips.none { it.first == s }) chips.add(Triple(s, s, false)) }
        smartChips = chips.take(5)
    }

    // Insert an emoji/symbol chip. Emoji get a leading space when needed and record the pick so the
    // ranking learns; symbols insert as-is.
    private fun insertSmartChip(display: String, insert: String, emoji: Boolean) {
        val ic = currentInputConnection ?: return
        keyHaptic("space")
        if (emoji) {
            val prev = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
            val sep = if (prev.isNotEmpty() && prev != " ") " " else ""
            ic.commitText(sep + insert, 1)
            if (emojiTriggerWord.isNotEmpty()) SmartChips.recordEmojiPick(imePrefs(), emojiTriggerWord, insert)
        } else {
            ic.commitText(insert, 1)
        }
        smartChips = emptyList()
        onTextChanged()
    }

    private fun autocorrectEnabled() = imePrefs().getBoolean(IME_AUTOCORRECT_PREF, true)

    private fun scheduleLiveCorrect() {
        // Disabled: rewriting a word mid-typing (before space) fights the user and re-triggers when
        // they go back to retype. Correct only on space/punctuation (undoable via backspace) and show
        // candidates in the strip — matching Gboard. See scheduleLiveAutocorrect in the launcher.
        liveCorrectDebounce?.let { handler.removeCallbacks(it) }
    }

    // Autocorrect now lives in the shared AutocorrectCore (see `autocorrect`).

    // Auto-language: start in the primary language and only switch a secondary phone language ON
    // when the last few words are clearly written in it (>= 2 words valid in that language but not
    // the primary). Reverts as soon as the user is back to all-primary words, so English is never
    // "corrected" into a language the user isn't actually writing. 1 latent word holds the current
    // state (hysteresis) so a lone shared/borrowed word doesn't flip the engine mid-sentence.
    private fun updateActiveLanguage() {
        if (!hasLatentLanguages) return
        val before = currentInputConnection?.getTextBeforeCursor(96, 0)?.toString()?.lowercase().orEmpty()
        val words = before.split(Regex("[^\\p{L}]+")).filter { it.length >= 2 }.takeLast(4)
        if (words.isEmpty()) return
        val latentHits = words.count { predictionEngineExtended.isDictWord(it) && !predictionEnginePrimary.isDictWord(it) }
        val active = when {
            latentHits >= 2 -> true
            latentHits == 0 -> false
            else -> latentLanguageActive
        }
        if (active != latentLanguageActive) {
            latentLanguageActive = active
            predictionEngine = if (active) predictionEngineExtended else predictionEnginePrimary
        }
    }

    private fun learnAndPredictAfterSpace() {
        updateActiveLanguage()
        val tokens = wordsBeforeCursor()
        if (tokens.size >= 2) ngramRepo.recordWord(tokens[tokens.size - 1], tokens[tokens.size - 2])
        val last = tokens.lastOrNull().orEmpty()
        if (last.isNotEmpty()) ngramRepo.prefetchNextWords(last)
        suggestions = if (last.isNotEmpty()) ngramRepo.cachedNextWords(last).take(3) else emptyList()
        updateStrip()
    }

    private fun acceptSuggestion(word: String) {
        if (currentInputConnection == null) return
        predictionCore.replaceCurrentWord(word)
        learnAndPredictAfterSpace()
    }

    private fun buildSuggestionStrip(): LinearLayout {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        suggestionStrip = strip
        updateStrip()
        return strip
    }

    private fun stripWellBackground() = android.graphics.drawable.GradientDrawable().apply {
        setColor(0x14FFFFFF); cornerRadius = dp(10).toFloat()
    }

    // ── Agentic panel ──────────────────────────────────────────────────────────
    // One elegant glass card for every agentic action. Idle -> GONE, so the resting keyboard is
    // untouched; a pending command or working status elevates into the card and hides the plain
    // suggestion strip. Each action gets its own accent spine (Clicks' colored-spine language).
    // Command / Files / Email render here as they land; typing suggestions stay in the strip.

    private fun buildAgenticPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(8), dp(10), dp(8))
        }
        agenticPanel = panel
        renderAgenticPanel()
        return panel
    }

    // ── Inline autofill (Gboard-style) ──────────────────────────────────────────
    // The system autofill provider (e.g. Google Password Manager) supplies credential/address/OTP
    // chips; we declare support, hand it a styled request, and render the chips it inflates in a
    // dedicated row above the keys. Clicks never sees the credentials — the fill goes provider->field.

    private fun buildInlineAutofillRow(): HorizontalScrollView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        inlineRow = row
        inlineScroll = scroll
        return scroll
    }

    private fun clearInlineSuggestions() {
        inlineRow?.removeAllViews()
        inlineScroll?.visibility = View.GONE
        if (agenticPanel?.visibility != View.VISIBLE) suggestionStrip?.visibility = View.VISIBLE
    }

    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest {
        val light = agenticPanelLight()
        val chipBg = if (light) 0xFFEDEFF4.toInt() else 0xFF20232A.toInt()
        val titleInk = if (light) 0xFF14161B.toInt() else 0xFFF5F2FF.toInt()
        val subInk = if (light) 0xFF6B7280.toInt() else 0xFF9AA2B1.toInt()
        val style = InlineSuggestionUi.newStyleBuilder()
            .setChipStyle(
                ViewStyle.Builder()
                    .setBackgroundColor(chipBg)
                    .setPadding(dp(14), dp(0), dp(14), dp(0))
                    .build()
            )
            .setTitleStyle(TextViewStyle.Builder().setTextColor(titleInk).setTextSize(14f).build())
            .setSubtitleStyle(TextViewStyle.Builder().setTextColor(subInk).setTextSize(12f).build())
            .build()
        val stylesBundle = UiVersions.newStylesBuilder().addStyle(style).build()
        val spec = InlinePresentationSpec.Builder(Size(dp(64), dp(36)), Size(dp(360), dp(44)))
            .setStyle(stylesBundle)
            .build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(6)
            .build()
    }

    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val row = inlineRow ?: return false
        val suggestions = response.inlineSuggestions
        row.removeAllViews()
        if (suggestions.isEmpty()) { clearInlineSuggestions(); return false }
        inlineScroll?.visibility = View.VISIBLE
        suggestionStrip?.visibility = View.GONE
        agenticPanel?.visibility = View.GONE
        val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40))
        for (suggestion in suggestions) {
            runCatching {
                suggestion.inflate(this, size, mainExecutor) { view ->
                    if (view != null) {
                        row.addView(view, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT
                        ).apply { marginEnd = dp(6) })
                        inlineScroll?.visibility = View.VISIBLE
                    }
                }
            }
        }
        return true
    }

    // The panel follows the same light/dark signal as the keyboard deck, so on the default theme it
    // tracks the system theme. HYPER3D_BLACK stays dark by design (matches deckBackground).
    private fun agenticPanelLight(): Boolean =
        selectedNeuTokens().mode == NeuMode.LIGHT && keyboardVisualTheme() != KEYBOARD_THEME_HYPER3D_BLACK

    private fun agenticCardBackground(top: Int, bottom: Int, stroke: Int) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)
    ).apply { cornerRadius = dp(16).toFloat(); setStroke(dp(1), stroke) }

    private fun agenticCta(label: String, accent: Int, ink: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 12.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(ink)
        setPadding(dp(15), dp(8), dp(15), dp(8))
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(11).toFloat() }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun agenticDismiss(ink: Int) = TextView(this).apply {
        text = "✕"; gravity = Gravity.CENTER; textSize = 13f
        setTextColor(ink)
        setPadding(dp(10), dp(8), dp(6), dp(8))
        isClickable = true
        setOnClickListener { keyHaptic("back"); dismissAgentic() }
    }

    private fun dismissAgentic() {
        pendingCommand = null
        pendingActions = emptyList()
        agenticStatus = null
        agenticHud = null
        updateStrip()
    }

    // ── Clipboard AI skills (Phase 2) ────────────────────────────────────────────
    private fun clipboardText(): String {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return ""
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
    }

    private fun aiReady(): Boolean {
        if (!ProManager.isUnlocked(this)) { flashAgenticStatus("✨ This is a Pro feature", 2000); return false }
        if (!GeminiClient.configured(imePrefs())) { flashAgenticStatus("Add a Gemini key in Clicks settings", 2400); return false }
        return true
    }

    // Run a Gemini instruction on the clipboard text and show the result in the HUD.
    private fun runClipboardSkill(need: String, status: String, kicker: String, instruction: String, insertable: Boolean) {
        val text = clipboardText()
        if (text.isBlank()) { flashAgenticStatus(need, 2600); return }
        if (!aiReady()) return
        agenticStatus = status
        updateStrip()
        val key = GeminiClient.apiKey(imePrefs()); val model = GeminiClient.model(imePrefs())
        Thread {
            val out = GeminiClient.fetchTransform(key, model, text, instruction)
            handler.post {
                agenticStatus = null
                if (!out.isNullOrBlank()) { agenticHud = Hud(kicker, out, if (insertable) out else null); updateStrip() }
                else flashAgenticStatus("Couldn’t do that — try again", 1800)
            }
        }.start()
    }

    private fun runEmotionDetection() = runClipboardSkill(
        "Copy a message first, then hold go", "🧠 Reading the mood…", "EMOTION",
        "Analyze the mood behind this message, then suggest how to respond. Reply in exactly two short " +
            "lines: 'Mood: <one line>' and 'Reply: <one line>'. Be concise.", insertable = false)

    private fun runTranslateHud() = runClipboardSkill(
        "Copy text to translate, then hold go", "🌐 Translating…", "TRANSLATION",
        "Translate this into natural, everyday English. Reply with ONLY the translation.", insertable = true)

    private fun runSuperXReply(tone: String) {
        val t = tone.ifBlank { "supportive" }
        runClipboardSkill(
            "Copy the post text first, then hold go", "𝕏 Drafting reply…", "X REPLY · $t",
            "Write a short, context-aware reply to this social post in a $t tone. One or two sentences, " +
                "no hashtags unless natural. Reply with ONLY the reply text.", insertable = true)
    }

    // ── API skills (Phase 3) — need a free key pasted in settings ─────────────────
    private fun runStockSniffer(ticker: String) {
        val key = imePrefs().getString(StockApi.KEY_PREF, null)?.trim().orEmpty()
        if (key.isBlank()) { flashAgenticStatus("Add a Finnhub key in Clicks settings", 2600); return }
        val sym = ticker.ifBlank { clipboardText() }.trim()
        if (sym.isBlank()) { flashAgenticStatus("Type or copy a ticker first", 2400); return }
        agenticStatus = "📈 Checking $sym…"
        updateStrip()
        Thread {
            val out = StockApi.lookup(sym, key)
            handler.post {
                agenticStatus = null
                if (out != null) { agenticHud = Hud("STOCK", out, out); updateStrip() }
                else flashAgenticStatus("Couldn’t find “$sym”", 2000)
            }
        }.start()
    }

    private fun runWorldCupOdds() {
        val key = imePrefs().getString(OddsApi.KEY_PREF, null)?.trim().orEmpty()
        if (key.isBlank()) { flashAgenticStatus("Add an odds API key in Clicks settings", 2600); return }
        agenticStatus = "🏆 Getting odds…"
        updateStrip()
        Thread {
            val out = OddsApi.worldCup(key)
            handler.post {
                agenticStatus = null
                if (out != null) { agenticHud = Hud("WORLD CUP", out, null); updateStrip() }
                else flashAgenticStatus("Couldn’t get odds", 2000)
            }
        }.start()
    }

    // ── OAuth skills (Phase 4) ────────────────────────────────────────────────────
    private fun runMeet() {
        if (!GmailAuth(this).isConnected) { flashAgenticStatus("Connect Google in the Clicks app first", 2800); return }
        agenticStatus = "📹 Creating Meet link…"
        updateStrip()
        Thread {
            val link = MeetApi.createMeeting(this)
            handler.post {
                agenticStatus = null
                if (link != null) {
                    currentInputConnection?.commitText(link, 1); onTextChanged(); updateStrip()
                } else flashAgenticStatus("Couldn’t create a Meet link — reconnect Google (Calendar)", 2800)
            }
        }.start()
    }

    private fun runNotion(query: String) {
        val token = imePrefs().getString(NotionApi.KEY_PREF, null)?.trim().orEmpty()
        if (token.isBlank()) { flashAgenticStatus("Add a Notion token in Clicks settings", 2800); return }
        val q = query.ifBlank { clipboardText() }.trim()
        if (q.isBlank()) { flashAgenticStatus("Type what to find, e.g. “notion roadmap”", 2600); return }
        agenticStatus = "🔎 Finding in Notion…"
        updateStrip()
        Thread {
            val found = NotionApi.summon(q, token)
            handler.post {
                agenticStatus = null
                if (found != null) {
                    val (title, url) = found
                    val body = if (title.isNotBlank()) "$title\n$url" else url
                    agenticHud = Hud("NOTION", body, url); updateStrip()
                } else flashAgenticStatus("No Notion page for “$q”", 2200)
            }
        }.start()
    }

    // Honor the system "remove animations" accessibility setting.
    private fun animationsEnabled(): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(
            contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }.getOrDefault(true)

    private fun animateAgenticPanelIn(panel: View) {
        if (!animationsEnabled()) { panel.alpha = 1f; panel.translationY = 0f; panel.scaleX = 1f; panel.scaleY = 1f; return }
        panel.alpha = 0f
        panel.translationY = dp(10).toFloat()
        panel.scaleX = 0.98f; panel.scaleY = 0.98f
        panel.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(190L)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
            .start()
    }

    // Render the active agentic state into the card, or hide it and restore the strip when idle.
    private fun renderAgenticPanel() {
        val panel = agenticPanel ?: return
        panel.removeAllViews()

        val light = agenticPanelLight()
        val cardTop = if (light) 0xFFFFFFFF.toInt() else 0xFF20232A.toInt()
        val cardBottom = if (light) 0xFFECEFF4.toInt() else 0xFF14161B.toInt()
        val cardStroke = if (light) 0x14000000 else 0x1EFFFFFF
        val tileBg = if (light) 0xFFF3F5F9.toInt() else 0xFF16181D.toInt()
        val titleInk = if (light) 0xFF14161B.toInt() else 0xFFF5F2FF.toInt()
        val kickerInk = if (light) 0xFF6B7280.toInt() else 0xFF767C89.toInt()
        val ctaInk = if (light) 0xFFFFFFFF.toInt() else 0xFF0A0C0F.toInt()
        val mint = if (light) 0xFF12A968.toInt() else 0xFF57E39A.toInt()
        val violet = if (light) 0xFF6D5FD6.toInt() else 0xFF9B8CFF.toInt()

        val hud = agenticHud
        if (hud != null) {
            val wasHidden = panel.visibility != View.VISIBLE
            panel.visibility = View.VISIBLE
            panel.background = agenticCardBackground(cardTop, cardBottom, cardStroke)
            suggestionStrip?.visibility = View.GONE
            panel.addView(View(this).apply {
                background = GradientDrawable().apply { setColor(violet); cornerRadius = dp(2).toFloat() }
            }, LinearLayout.LayoutParams(dp(3), dp(30)).apply { marginEnd = dp(11) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = hud.kicker; textSize = 9f; letterSpacing = 0.16f; setTextColor(kickerInk)
            })
            col.addView(TextView(this).apply {
                text = hud.body; textSize = 13.5f; setTextColor(titleInk)
                maxLines = 6; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            panel.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            val insert = hud.insert
            if (insert != null) {
                panel.addView(agenticCta("Insert", violet, ctaInk) {
                    keyHaptic("enter"); agenticHud = null
                    currentInputConnection?.commitText(insert, 1); onTextChanged(); updateStrip()
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            panel.addView(TextView(this).apply {
                text = "✕"; gravity = Gravity.CENTER; textSize = 13f; setTextColor(kickerInk)
                setPadding(dp(10), dp(8), dp(6), dp(8)); isClickable = true
                setOnClickListener { keyHaptic("back"); agenticHud = null; updateStrip() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
            if (wasHidden) animateAgenticPanelIn(panel)
            return
        }

        val status = agenticStatus
        val pending = pendingCommand
        val accent: Int; val glyph: String; val kicker: String; val title: String
        when {
            status != null -> { accent = violet; glyph = "✦"; kicker = "CLICKS"; title = status }
            pending != null -> {
                accent = mint
                val parts = pending.label.split("  ", limit = 2)
                glyph = if (parts.size == 2) parts[0].trim() else "▶"
                kicker = "COMMAND"
                title = if (parts.size == 2) parts[1].trim() else pending.label
            }
            else -> {
                panel.visibility = View.GONE
                panel.background = null
                suggestionStrip?.visibility = View.VISIBLE
                return
            }
        }

        val wasHidden = panel.visibility != View.VISIBLE
        panel.visibility = View.VISIBLE
        panel.background = agenticCardBackground(cardTop, cardBottom, cardStroke)
        suggestionStrip?.visibility = View.GONE

        panel.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(2).toFloat() }
        }, LinearLayout.LayoutParams(dp(3), dp(30)).apply { marginEnd = dp(11) })

        panel.addView(TextView(this).apply {
            text = glyph; gravity = Gravity.CENTER; textSize = 15f
            setTextColor(accent)
            background = GradientDrawable().apply {
                setColor(tileBg); cornerRadius = dp(10).toFloat()
                setStroke(dp(1), (accent and 0x00FFFFFF) or 0x55000000)
            }
        }, LinearLayout.LayoutParams(dp(32), dp(32)))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = kicker; textSize = 9f; letterSpacing = 0.16f
            setTextColor(kickerInk)
        })
        col.addView(TextView(this).apply {
            text = title; textSize = 14.5f
            setTextColor(titleInk)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        panel.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(11); marginEnd = dp(8)
        })

        // A pending command is confirm-before-run; a working status is in-flight (no controls).
        if (pending != null && status == null) {
            panel.addView(agenticCta("Run", accent, ctaInk) { keyHaptic("enter"); applyPendingCommand() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            panel.addView(agenticDismiss(kickerInk),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
        }

        if (wasHidden) animateAgenticPanelIn(panel)
    }

    private fun fieldTextForPolish(): String =
        currentInputConnection?.getTextBeforeCursor(1000, 0)?.toString().orEmpty()

    // Runs on every strip repaint, so keep it cheap: a short read is enough to see ≥3 words. The
    // full field is only read (fieldTextForPolish) when a polish actually fires.
    private fun polishAvailable(): Boolean =
        ProManager.isUnlocked(this) && GeminiClient.configured(imePrefs()) &&
            (currentInputConnection?.getTextBeforeCursor(200, 0)?.toString().orEmpty())
                .trim().split(Regex("\\s+")).count { it.isNotEmpty() } >= 3

    private fun updateStrip() {
        val strip = suggestionStrip ?: return
        // Elevate command previews and working status into the elegant panel; it toggles the strip's
        // visibility. The strip is still populated below as the fallback surface when the panel idles.
        renderAgenticPanel()
        strip.removeAllViews()
        val status = agenticStatus
        if (status != null) {
            strip.background = stripWellBackground()
            strip.addView(TextView(this).apply {
                text = status
                gravity = Gravity.CENTER; textSize = 15f
                setTextColor(0xFFCBB4FF.toInt())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            return
        }
        val pending = pendingCommand
        if (pending != null) {
            strip.background = stripWellBackground()
            strip.addView(TextView(this).apply {
                text = pending.label
                gravity = Gravity.CENTER_VERTICAL; textSize = 15f
                setTextColor(0xFFE9EDF2.toInt())
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(6), 0, dp(6), 0)
                isClickable = true
                setOnClickListener { keyHaptic("enter"); applyPendingCommand() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            strip.addView(TextView(this).apply {
                text = "APPLY"
                gravity = Gravity.CENTER; textSize = 11f; letterSpacing = 0.12f
                setTextColor(0xFFCBB4FF.toInt())
                setPadding(dp(12), 0, dp(10), 0)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x338B5CF6); cornerRadius = dp(9).toFloat()
                }
                isClickable = true
                setOnClickListener { keyHaptic("enter"); applyPendingCommand() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply { marginStart = dp(4) })
            return
        }
        if (polishing) {
            strip.background = stripWellBackground()
            strip.addView(TextView(this).apply {
                text = "✨ Polishing…"
                gravity = Gravity.CENTER; textSize = 15f
                setTextColor(0xFFCBB4FF.toInt())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            return
        }
        if (pendingActions.isNotEmpty()) {
            strip.background = stripWellBackground()
            pendingActions.forEachIndexed { i, action ->
                strip.addView(TextView(this).apply {
                    text = action.first
                    gravity = Gravity.CENTER; textSize = 14f
                    setTextColor(0xFFE9EDF2.toInt())
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    isClickable = true
                    setOnClickListener { keyHaptic("enter"); runInlineTransform(action.second) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                if (i < pendingActions.lastIndex) {
                    strip.addView(View(this).apply { setBackgroundColor(0x22FFFFFF) },
                        LinearLayout.LayoutParams(dp(1), dp(18)))
                }
            }
            return
        }
        val shown = suggestions.take(3)
        val canPolish = polishAvailable()
        val chips = smartChips
        if (shown.isEmpty() && !canPolish && chips.isEmpty()) { strip.background = null; return }
        // The strip was being populated but stayed invisible whenever inline autofill (or the panel)
        // had hidden it. Once we have real word suggestions/chips to show, make the strip visible and
        // stand down the autofill row — typing a word takes priority.
        suggestionStrip?.visibility = View.VISIBLE
        inlineScroll?.visibility = View.GONE
        strip.background = stripWellBackground()
        shown.forEachIndexed { i, w ->
            strip.addView(TextView(this).apply {
                text = w
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(0xFFE9EDF2.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                isClickable = true
                setOnClickListener { keyHaptic("space"); acceptSuggestion(w) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            if (i < shown.lastIndex || canPolish || chips.isNotEmpty()) {
                strip.addView(View(this).apply { setBackgroundColor(0x22FFFFFF) },
                    LinearLayout.LayoutParams(dp(1), dp(18)))
            }
        }
        chips.forEachIndexed { i, (display, insert, emoji) ->
            strip.addView(TextView(this).apply {
                text = display
                gravity = Gravity.CENTER
                textSize = if (emoji) 18f else 16f
                setTextColor(0xFFE9EDF2.toInt())
                maxLines = 1
                setPadding(dp(8), 0, dp(8), 0)
                isClickable = true
                setOnClickListener { insertSmartChip(display, insert, emoji) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
            if (i < chips.lastIndex || canPolish) {
                strip.addView(View(this).apply { setBackgroundColor(0x22FFFFFF) },
                    LinearLayout.LayoutParams(dp(1), dp(18)))
            }
        }
        if (canPolish) {
            strip.addView(TextView(this).apply {
                text = "✨"
                gravity = Gravity.CENTER
                textSize = 17f
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x338B5CF6); cornerRadius = dp(9).toFloat()
                }
                isClickable = true
                setOnClickListener { polishField() }
            }, LinearLayout.LayoutParams(dp(46), LinearLayout.LayoutParams.MATCH_PARENT).apply { marginStart = dp(4) })
        }
    }

    // Inline AI command: "<text> //formal" + space -> transform the text with that style (shared with
    // the launcher via AiStyleCommands). Only fires on a KNOWN command. Pro + API key gated.
    private fun maybeRunAiCommand(): Boolean {
        if (polishing || !ProManager.isUnlocked(this) || !GeminiClient.configured(imePrefs())) return false
        val before = currentInputConnection?.getTextBeforeCursor(600, 0)?.toString() ?: return false
        val (content, instruction) = AiStyleCommands.match(before) ?: return false
        runAiTransform(before.length, content, instruction)
        return true
    }

    // Agentic quick-action: hold the go/enter key to drop your current location into the field — no
    // maps app, no leaving the chat. Permission can't be requested from an IME, so if it's not
    // already granted (e.g. via Clicks weather) we point the user there instead.
    // Hold go/enter -> read the typed line, classify it, and show a preview chip the user taps to run
    // (Acti-style: nothing launches without confirmation). Empty / unknown text falls through to a
    // location share or a hint. Tapping go/enter still sends/enters as normal.
    private fun runAgenticCommand() {
        val raw = currentInputConnection?.getTextBeforeCursor(200, 0)?.toString() ?: ""
        // Clipboard skills (Phase 2): a bare trigger word operates on the COPIED text, not the field.
        val t = raw.trim().lowercase()
        val clipSkill: String? = when {
            t == "mood" || t == "emotion" || t == "vibe" || t == "vibe check" || t == "read mood" -> "mood"
            t == "translate" || t == "translate this" || t == "translate clipboard" || t == "tl" -> "translate"
            t == "xreply" || t.startsWith("xreply ") || t.startsWith("x reply") || t.startsWith("reply x") -> "xreply"
            t.startsWith("stock ") || t.startsWith("ticker ") ||
                (t.startsWith("$") && t.length in 2..6 && t.drop(1).all { it.isLetterOrDigit() }) -> "stock"
            t == "worldcup" || t == "world cup" || t == "wc" || t == "wc odds" || t == "world cup odds" || t == "odds" -> "odds"
            t == "meet" || t == "google meet" || t == "new meet" || t == "gmeet" -> "meet"
            t.startsWith("notion ") || t == "notion" -> "notion"
            else -> null
        }
        if (clipSkill != null) {
            if (raw.isNotEmpty()) currentInputConnection?.deleteSurroundingText(raw.length, 0)
            onTextChanged()
            when (clipSkill) {
                "mood" -> runEmotionDetection()
                "translate" -> runTranslateHud()
                "xreply" -> runSuperXReply(t.removePrefix("xreply").removePrefix("x reply").removePrefix("reply x").trim())
                "stock" -> runStockSniffer(
                    when {
                        t.startsWith("stock ") -> t.removePrefix("stock ")
                        t.startsWith("ticker ") -> t.removePrefix("ticker ")
                        else -> t.removePrefix("$")
                    }.trim()
                )
                "odds" -> runWorldCupOdds()
                "meet" -> runMeet()
                "notion" -> runNotion(t.removePrefix("notion").trim())
            }
            return
        }
        val cmd = AgenticRouter.classify(raw)
        if (cmd != null) {
            pendingCommandText = raw
            pendingCommand = cmd
            updateStrip()
            return
        }
        // A real message (not a command): offer inline AI actions on it — fix, translate — right in
        // the field, before trying to interpret it as a command.
        if (raw.isNotBlank() && ProManager.isUnlocked(this) && GeminiClient.configured(imePrefs()) &&
            raw.trim().split(Regex("\\s+")).count { it.isNotEmpty() } >= 3) {
            pendingCommandText = raw
            pendingActions = buildMessageActions()
            updateStrip()
            return
        }
        // Free-form fallback: let Gemini pick a skill (Pro + configured). It only ranks among real
        // skills, so "I wanna hear jazz" routes to Play music without inventing an action.
        if (raw.isNotBlank() && ProManager.isUnlocked(this) && GeminiClient.configured(imePrefs())) {
            pendingCommandText = raw
            agenticStatus = "\uD83E\uDD16 Thinking\u2026"
            updateStrip()
            val key = GeminiClient.apiKey(imePrefs()); val model = GeminiClient.model(imePrefs())
            val names = AgenticRouter.catalogNames()
            Thread {
                val match = GeminiClient.fetchSkillMatch(key, model, raw, names)
                handler.post {
                    agenticStatus = null
                    val c = match?.let { AgenticRouter.commandForSkill(it.first, it.second) }
                    if (c != null) { pendingCommand = c; updateStrip() }
                    else flashAgenticStatus("No command matched", 1800)
                }
            }.start()
            return
        }
        flashAgenticStatus("Type a command \u2014 play, nearest, timer\u2026", 2200)
    }

    private fun applyPendingCommand() {
        val cmd = pendingCommand ?: return
        val raw = pendingCommandText
        pendingCommand = null
        // Clear the command text from the field — it was a command, not message content.
        if (raw.isNotEmpty()) currentInputConnection?.deleteSurroundingText(raw.length, 0)
        if (cmd.insertsLocation) { AgenticRouter.recordUse(this, cmd.skillId); runAgenticLocation(); return }
        if (cmd.fetchWeather) { AgenticRouter.recordUse(this, cmd.skillId); runAgenticWeather(cmd.arg); return }
        if (cmd.insertText != null) {
            currentInputConnection?.commitText(cmd.insertText, 1)
            onTextChanged()
            return
        }
        val statusMsg = AgenticRouter.execute(this, cmd)
        onTextChanged()
        flashAgenticStatus(statusMsg ?: "Couldn\u2019t run that", 1700)
    }

    private val vibrator by lazy { getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator }
    private val agenticHapticRunnables = ArrayList<Runnable>()

    // Escalating buzz while the go/enter key is held toward an agentic action, then a strong confirm.
    private fun startAgenticHapticRamp(total: Long) {
        stopAgenticHapticRamp()
        listOf(0.30 to 45, 0.55 to 105, 0.80 to 175).forEach { (frac, amp) ->
            val r = Runnable { vibe(16, amp) }
            agenticHapticRunnables.add(r)
            handler.postDelayed(r, (total * frac).toLong())
        }
    }
    private fun stopAgenticHapticRamp() {
        agenticHapticRunnables.forEach { handler.removeCallbacks(it) }
        agenticHapticRunnables.clear()
    }
    private fun agenticConfirmHaptic() = vibe(28, 255)
    private fun vibe(ms: Long, amplitude: Int) {
        if (!imePrefs().getBoolean(HAPTICS_PREF, true)) return
        val v = vibrator ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26)
                v.vibrate(android.os.VibrationEffect.createOneShot(ms, amplitude.coerceIn(1, 255)))
            else @Suppress("DEPRECATION") v.vibrate(ms)
        }
    }

    private val langNames = mapOf(
        "es" to "Spanish", "fr" to "French", "de" to "German",
        "pt" to "Portuguese", "it" to "Italian", "en" to "English"
    )

    // Inline AI actions when you hold go/enter over a real message: fix it, or translate it into your
    // other keyboard language — right where you type, no app switch (the "texting mom" case).
    private fun buildMessageActions(): List<Pair<String, String>> {
        val actions = ArrayList<Pair<String, String>>()
        actions.add("✨ Polish" to
            "Fix the spelling, grammar, capitalization and punctuation of this message. Keep the meaning and tone.")
        translateTargetLanguage()?.let { lang ->
            actions.add("🌐 → $lang" to
                "Translate this message to $lang. Reply with only the translation, nothing else.")
        }
        return actions
    }
    private fun translateTargetLanguage(): String? {
        // Consider in-app selection plus the phone's other bundled locales, so "translate to X" still
        // offers a secondary language even though corrections default to the primary only.
        val loader = com.fran.clicks.keyboard.DictionaryLoader
        val langs = (loader.enabledLanguages(this) + loader.systemBundledLanguages(this)).distinct()
        val primary = langs.firstOrNull() ?: return null
        val other = langs.firstOrNull { it != primary } ?: return null
        return langNames[other]
    }

    // Run a Gemini transform on the field text and replace it inline.
    private fun runInlineTransform(instruction: String) {
        pendingActions = emptyList()
        val ic = currentInputConnection ?: return
        val text = fieldTextForPolish()
        if (text.trim().length < 2) return
        if (!ProManager.isUnlocked(this) || !GeminiClient.configured(imePrefs())) return
        val key = GeminiClient.apiKey(imePrefs()); val model = GeminiClient.model(imePrefs())
        agenticStatus = "🤖 Working…"
        updateStrip()
        Thread {
            val result = GeminiClient.fetchTransform(key, model, text, instruction)
            handler.post {
                agenticStatus = null
                if (result != null && result != text) {
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(text.length, 0)
                    ic.commitText(result, 1)
                    ic.endBatchEdit()
                }
                onTextChanged()
                updateStrip()
            }
        }.start()
    }

    // Hold the space bar for Gemini writing assist: continue / draft from what's already in the
    // field and commit the result inline — the "help me write this" companion to go/enter's command
    // trigger. No app switch, so you keep writing without chasing another tool.
    private fun runGeminiCompose() {
        if (!ProManager.isUnlocked(this)) { flashAgenticStatus("✨ Gemini writing is a Pro feature", 2200); return }
        if (!GeminiClient.configured(imePrefs())) { flashAgenticStatus("Add a Gemini key in Clicks settings", 2400); return }
        val context = currentInputConnection?.getTextBeforeCursor(600, 0)?.toString().orEmpty()
        if (context.isBlank()) { flashAgenticStatus("Type a little, then hold space for Gemini", 2200); return }
        agenticStatus = "✨ Writing…"
        updateStrip()
        val key = GeminiClient.apiKey(imePrefs()); val model = GeminiClient.model(imePrefs())
        Thread {
            val draft = GeminiClient.fetchCompose(key, model, context)
            handler.post {
                agenticStatus = null
                if (!draft.isNullOrBlank()) {
                    val sep = if (context.isNotEmpty() && !context.last().isWhitespace()) " " else ""
                    currentInputConnection?.commitText(sep + draft, 1)
                    onTextChanged()
                    updateStrip()
                } else flashAgenticStatus("✨ Nothing to add", 1600)
            }
        }.start()
    }

    private fun runAgenticLocation() {
        if (!AgenticLocation.hasPermission(this)) {
            flashAgenticStatus("\uD83D\uDCCD Enable location in Clicks", 2600)
            return
        }
        agenticStatus = "\uD83D\uDCCD Locating\u2026"
        updateStrip()
        Thread {
            val text = AgenticLocation.currentLocationText(this)
            handler.post {
                agenticStatus = null
                if (text != null) {
                    currentInputConnection?.commitText(text, 1)
                    onTextChanged()
                    updateStrip()
                } else {
                    flashAgenticStatus("\uD83D\uDCCD Location unavailable", 2000)
                }
            }
        }.start()
    }

    private fun runAgenticWeather(query: String) {
        agenticStatus = "⛅ Checking weather…"
        updateStrip()
        Thread {
            val text = WeatherApi.lookup(query)
            handler.post {
                agenticStatus = null
                if (text != null) {
                    currentInputConnection?.commitText(text, 1)
                    onTextChanged()
                    updateStrip()
                } else {
                    flashAgenticStatus("⛅ Couldn’t get weather for “$query”", 2200)
                }
            }
        }.start()
    }

    private fun flashAgenticStatus(message: String, ms: Long) {
        agenticStatus = message
        updateStrip()
        handler.postDelayed({ agenticStatus = null; updateStrip() }, ms)
    }

    private fun runAiTransform(deleteLen: Int, content: String, instruction: String) {
        val ic = currentInputConnection ?: return
        val key = GeminiClient.apiKey(imePrefs())
        val model = GeminiClient.model(imePrefs())
        polishing = true
        keyHaptic("enter")
        updateStrip()
        Thread {
            val result = GeminiClient.fetchTransform(key, model, content, instruction)
            handler.post {
                polishing = false
                ic.beginBatchEdit()
                ic.deleteSurroundingText(deleteLen, 0)
                ic.commitText((result ?: content) + " ", 1)
                ic.endBatchEdit()
                onTextChanged()
            }
        }.start()
    }

    // AI Polish: rewrite the whole field with Gemini (Pro + API key), shown as a sparkle in the strip.
    private fun polishField() {
        if (polishing) return
        val ic = currentInputConnection ?: return
        val text = fieldTextForPolish()
        if (text.trim().length < 3) return
        val key = GeminiClient.apiKey(imePrefs())
        val model = GeminiClient.model(imePrefs())
        polishing = true
        keyHaptic("enter")
        updateStrip()
        Thread {
            val result = GeminiClient.fetchRewrite(key, model, text)
            handler.post {
                polishing = false
                if (result != null && result != text) {
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(text.length, 0)
                    ic.commitText(result, 1)
                    ic.endBatchEdit()
                }
                onTextChanged()
            }
        }.start()
    }

    private fun handleSwipeFallback(keys: List<String>) {
        val rawWord = keys.joinToString("")
        currentInputConnection?.commitText(rawWord, 1)
    }

    private fun deleteWord() {
        val input = currentInputConnection ?: return
        val before = input.getTextBeforeCursor(128, 0)?.toString().orEmpty()
        val trimmed = before.trimEnd()
        val deleteCount = if (trimmed.isEmpty()) before.length else {
            val trailingSpaces = before.length - trimmed.length
            val wordLength = trimmed.takeLastWhile { !it.isWhitespace() }.length
            trailingSpaces + wordLength
        }
        if (deleteCount > 0) input.deleteSurroundingText(deleteCount, 0)
    }

    private fun glideTrailColors(): IntArray {
        val theme = keyboardVisualTheme()
        val core = when (theme) {
            KEYBOARD_THEME_GOKEYS -> 0xFFF2691E.toInt()
            KEYBOARD_THEME_CLICKS -> 0xFFF5C451.toInt()
            KEYBOARD_THEME_SKEUO -> 0xFF8FD694.toInt()
            KEYBOARD_THEME_HYPER3D -> 0xFF7EA2FF.toInt()
            KEYBOARD_THEME_HYPER3D_BLACK -> 0xFFFF6B6B.toInt()
            KEYBOARD_THEME_HYPER3D_LIGHT -> 0xFF4E6FE7.toInt()
            else -> goKeyColor()
        }
        val tail = when (theme) {
            KEYBOARD_THEME_HYPER3D_LIGHT -> 0xFF8FD6FF.toInt()
            KEYBOARD_THEME_HYPER3D_BLACK -> 0xFF9DB4FF.toInt()
            KEYBOARD_THEME_CLICKS -> 0xFFFF8A68.toInt()
            else -> brighten(core)
        }
        return intArrayOf(adjustAlpha(core, 0x20), tail, core, adjustAlpha(core, 0x22))
    }

    // [rebuildBackgrounds] is only needed on a theme change. Shift / auto-cap flips just change the
    // letter case, so rebuilding every key's background drawable on each of those (which happens
    // constantly while typing) was wasted UI-thread allocation — the main IME sluggishness. Default
    // to a light text-only refresh.
    private fun refreshKeyboardChrome(rebuildBackgrounds: Boolean = false) {
        deckView?.let { deck ->
            if (rebuildBackgrounds) deck.background = deckBackground()
            for (rowIndex in 0 until deck.childCount) {
                val row = deck.getChildAt(rowIndex) as? LinearLayout ?: continue
                for (keyIndex in 0 until row.childCount) {
                    val key = row.getChildAt(keyIndex) as? TextView ?: continue
                    val raw = key.tag as? String ?: continue
                    key.text = keyDisplayText(raw)
                    key.setTextColor(textColor(raw))
                    if (rebuildBackgrounds) key.background = visualKeyBackground(raw, pressed = false)
                }
            }
        }
    }

    private inner class SwipeImeKeyboardLayout : LinearLayout(this@ClicksImeService) {
        private var startRawX = 0f
        private var startRawY = 0f
        private var tracking = false
        private val traced = mutableListOf<String>()
        private val trailLocal = mutableListOf<Pair<Float, Float>>()
        // Event timestamps parallel to trailLocal — needed for the neural decoder's velocity/accel
        // features (the statistical classifier doesn't use them).
        private val trailTimes = mutableListOf<Long>()
        private var screenX = 0f
        private var screenY = 0f
        private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val trailPath = Path()
        private var glidePersisting = false
        private var glideFadeRunnable: Runnable? = null
        private val touchSlop = ViewConfiguration.get(this@ClicksImeService).scaledTouchSlop

        private fun fadeGlideTrail() {
            glideFadeRunnable?.let { handler.removeCallbacks(it) }
            val r = Runnable {
                glidePersisting = false
                trailLocal.clear()
                trailTimes.clear()
                invalidate()
            }
            glideFadeRunnable = r
            handler.postDelayed(r, 900L)
        }

        private fun clearGlideTouchState() {
            tracking = false
            traced.clear()
            trailLocal.clear()
            trailTimes.clear()
            glideClassifier?.clear()
            invalidate()
        }

        /** Snapshot the raw screen-space path (with timestamps) for the neural decoder. */
        fun snapshotSwipePath(): List<TimedPoint> {
            val n = minOf(trailLocal.size, trailTimes.size)
            val out = ArrayList<TimedPoint>(n)
            for (i in 0 until n) {
                out.add(TimedPoint(trailLocal[i].first + screenX, trailLocal[i].second + screenY, trailTimes[i]))
            }
            return out
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if ((tracking || glidePersisting) && trailLocal.size > 1) {
                trailPath.reset()
                trailPath.moveTo(trailLocal[0].first, trailLocal[0].second)
                for (i in 1 until trailLocal.size) trailPath.lineTo(trailLocal[i].first, trailLocal[i].second)
                val start = trailLocal.first()
                val end = trailLocal.last()
                val colors = glideTrailColors()
                trailPaint.shader = android.graphics.LinearGradient(
                    start.first,
                    start.second,
                    end.first,
                    end.second,
                    colors,
                    null,
                    Shader.TileMode.CLAMP
                )
                trailPaint.strokeWidth = dp(12).toFloat()
                trailPaint.alpha = 58
                canvas.drawPath(trailPath, trailPaint)
                trailPaint.strokeWidth = dp(5).toFloat()
                trailPaint.alpha = 222
                canvas.drawPath(trailPath, trailPaint)
                trailPaint.shader = null
                trailPaint.alpha = 255
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    glideFadeRunnable?.let { handler.removeCallbacks(it) }
                    glidePersisting = false
                    tracking = false
                    traced.clear()
                    trailLocal.clear()
                    trailTimes.clear()
                    val loc = IntArray(2)
                    getLocationOnScreen(loc)
                    screenX = loc[0].toFloat()
                    screenY = loc[1].toFloat()
                    captureKeyBounds()
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking && (abs(ev.rawX - startRawX) > touchSlop || abs(ev.rawY - startRawY) > touchSlop)) {
                        tracking = true
                        if (hapticsOn()) hapticEngine.glideStart()   // firm click on glide activation
                        android.util.Log.d("ClicksGlide", "glide start keyBounds=${keyBounds.size} clfReady=${glideClassifier != null}")
                        parent?.requestDisallowInterceptTouchEvent(true)
                        keyAtPoint(startRawX, startRawY, letterOnly = true)?.let { traced.add(it) }
                        trailLocal.add(startRawX - screenX to startRawY - screenY)
                        trailTimes.add(ev.eventTime)
                        glideClassifier?.addGesturePoint(startRawX, startRawY)
                        return true
                    }
                    return tracking
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracking = false
                    return false
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!tracking) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    keyAtPoint(ev.rawX, ev.rawY, letterOnly = true)?.let { key ->
                        if (traced.isEmpty() || traced.last() != key) traced.add(key)
                    }
                    trailLocal.add(ev.rawX - screenX to ev.rawY - screenY)
                    trailTimes.add(ev.eventTime)
                    glideClassifier?.addGesturePoint(ev.rawX, ev.rawY)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val clf = glideClassifier
                    // Swipe-for-symbol: a short, vertical-dominant flick (up OR down, Apple-style) on a
                    // key inserts that key's symbol instead of gliding — quick punctuation, no mode
                    // switch. Gated on "not a real glide" so full glide words are untouched.
                    val dyUp = ev.rawY - startRawY
                    val dxUp = ev.rawX - startRawX
                    val flickKey = keyAtPoint(startRawX, startRawY, letterOnly = true)
                    val flickSymbol = if (flickKey != null) keyUpSymbols[flickKey] else null
                    if (flickSymbol != null && abs(dyUp) > dp(24) && abs(dyUp) >= abs(dxUp) * 1.4f &&
                        abs(dxUp) < dp(56) && (clf == null || !clf.hasEnoughPoints)) {
                        tracking = false
                        traced.clear()
                        clf?.clear()
                        trailLocal.clear()
                        trailTimes.clear()
                        glidePersisting = false
                        invalidate()
                        keyHaptic("space")
                        commitValue(flickSymbol)
                        onTextChanged()
                        return true
                    }
                    // Only treat a left swipe as delete when the classifier is READY and decided the
                    // path isn't a word. If clf is still loading (null), a normal glide must never be
                    // misread as a word-delete — that silently ate typing in some apps.
                    val quickLeftDelete = clf != null && !clf.hasEnoughPoints &&
                        startRawX - ev.rawX > dp(52) && abs(ev.rawY - startRawY) < dp(36)
                    val tracedKeys = traced.toList()
                    tracking = false
                    traced.clear()
                    glidePersisting = trailLocal.size > 1
                    invalidate()
                    android.util.Log.d("ClicksGlide", "UP pkg=$currentEditorPackage clfReady=${clf != null} " +
                        "enoughPts=${clf?.hasEnoughPoints} traced=${tracedKeys.size} quickDel=$quickLeftDelete")
                    if (quickLeftDelete) {
                        keyHaptic("back"); deleteWord(); clf?.clear(); fadeGlideTrail(); return true
                    }
                    if (clf != null && clf.hasEnoughPoints) {
                        val contextBoost = glideCore.contextBoost()
                        // Snapshot inputs for the optional neural decoder on the main thread (touch
                        // state is about to be cleared). Null when neural is off/not-ready.
                        val neural = neuralGlide?.takeIf { it.enabled }
                        val neuralPath = if (neural != null) snapshotSwipePath() else emptyList()
                        val bounds = if (neural != null) letterKeyBounds() else null
                        val centers = if (neural != null) letterKeyCenters() else emptyList()
                        glideScope.launch {
                            var results: List<String> = emptyList()
                            // 1) Neural decode first (runs off-main inside the engine).
                            if (neural != null && bounds != null && neuralPath.size > 1) {
                                results = try {
                                    neural.decodeWords(neuralPath, bounds[0], bounds[1], bounds[2], bounds[3], centers, 3)
                                } catch (e: Throwable) {
                                    android.util.Log.e("ClicksGlide", "neural decode failed", e); emptyList()
                                }
                            }
                            // 2) Statistical fallback when neural is off or returns nothing.
                            if (results.isEmpty()) {
                                results = withContext(kotlinx.coroutines.Dispatchers.Default) {
                                    try { clf.getSuggestions(3, contextBoost) }
                                    catch (e: Throwable) { android.util.Log.e("ClicksGlide", "decode failed", e); emptyList() }
                                }
                            }
                            runCatching { clf.clear() }
                            android.util.Log.d("ClicksGlide", "results=${results.size} top=${results.firstOrNull()} neural=${neural != null}")
                            if (results.isNotEmpty()) {
                                if (hapticsOn()) hapticEngine.glideCommit()
                                glideCore.commitWord(glideCore.rerank(results)); learnAndPredictAfterSpace()
                            } else if (tracedKeys.size >= 3) {
                                keyHaptic("space"); handleSwipeFallback(tracedKeys)
                            }
                            fadeGlideTrail()
                        }
                    } else {
                        clf?.clear()
                        if (tracedKeys.size >= 3) { keyHaptic("space"); handleSwipeFallback(tracedKeys) }
                        fadeGlideTrail()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    clearGlideTouchState()
                }
            }
            return true
        }
    }

    private fun visualLabel(label: String): String {
        return when (label) {
            "shift" -> if (capsLock) "⇪" else if (shifted) "⇧" else "↑"
            "back" -> "⌫"
            "enter" -> "GO"
            "space" -> "space"
            else -> if ((shifted || capsLock) && label.length == 1) label.uppercase() else label
        }
    }

    // Key text with the swipe-down symbol shown small above the letter (so users see it).
    private fun keyDisplayText(label: String): CharSequence {
        val base = visualLabel(label)
        return if (label.length == 1 && label[0].isLetter() && !symbolsMode)
            com.fran.clicks.keyboard.keyLabelWithSymbol(base, label, symbolHintColor())
        else base
    }

    private fun symbolHintColor(): Int = (textColor("q") and 0x00FFFFFF) or (0x72 shl 24)

    private fun textColor(label: String): Int {
        val theme = keyboardVisualTheme()
        if (isHyper3dTheme(theme)) {
            val visualTheme = hyper3dVisualTheme(theme)
            return when {
                visualTheme == KEYBOARD_THEME_HYPER3D_LIGHT && label == "enter" -> 0xFF104026.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D_LIGHT && isFnKey(label) -> 0xFF596170.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D_LIGHT -> 0xFF1E2633.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D_BLACK && label == "enter" -> 0xFFFF6B6B.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D_BLACK && label == "clicks" -> 0xFF9DB4FF.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D_BLACK && isFnKey(label) -> 0xFF9AA2B1.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D && label == "enter" -> 0xFFEAFFF2.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D && label == "clicks" -> 0xFFEAF0FF.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D && isFnKey(label) -> 0xFF9AA2B1.toInt()
                else -> 0xFFEEF1F7.toInt()
            }
        }
        if (theme == KEYBOARD_THEME_GOKEYS) {
            return if (keyboardLightMode(theme)) {
                when (label) {
                    "enter" -> 0xFFFFFFFF.toInt()
                    "123", "clicks", "back", "shift", "." -> 0xFF6F7884.toInt()
                    else -> 0xFF202733.toInt()
                }
            } else {
                when (label) {
                    "enter" -> 0xFFFFFFFF.toInt()
                    "123", "clicks", "back", "shift", "." -> 0xFF9AA1AB.toInt()
                    else -> 0xFFF3F5F8.toInt()
                }
            }
        }
        return if (keyboardLightMode(theme)) {
            when (label) {
                "enter" -> Neu.ACCENT
                "clicks" -> 0xFF2F6C4C.toInt()
                "123", "back", "shift", "." -> 0xFF6F7884.toInt()
                else -> 0xFF202733.toInt()
            }
        } else {
            val tokens = selectedNeuTokens()
            when (label) {
                "enter" -> Neu.ACCENT
                "clicks" -> Neu.GREEN
                "123", "back", "shift", "." -> tokens.inkDim
                else -> tokens.ink
            }
        }
    }

    private fun keyWeight(label: String): Float {
        return when (label) {
            "space" -> 3.65f
            "enter" -> 0.82f
            "." -> 0.86f
            "clicks" -> 1.55f
            "123", "back", "shift" -> 1.02f
            else -> 1f
        }
    }

    private fun imeKeyboardHeight(): Int {
        val size = KeyboardSettings.keyboardSize(this)
        val rowCount = 4
        return keyRowHeight() * rowCount - keyRowOverlap() * (rowCount - 1) + dp(6) + dp(38)
    }

    private fun keyRowHeight(): Int {
        val size = KeyboardSettings.keyboardSize(this)
        return dp(56 + (size * 20 / 100))
    }

    private fun keyRowOverlap(): Int {
        val size = KeyboardSettings.keyboardSize(this)
        return dp(8 + size * 3 / 100)
    }

    private fun keyVerticalInset(): Int {
        val size = KeyboardSettings.keyboardSize(this)
        val theme = keyboardVisualTheme()
        if (theme == KEYBOARD_THEME_CLICKS || theme == KEYBOARD_THEME_GOKEYS || isHyper3dTheme(theme)) {
            return dp(10 + size * 5 / 100)
        }
        return dp(7 + size * 4 / 100)
    }

    private fun themedGoKeySize(): Int {
        val size = KeyboardSettings.keyboardSize(this)
        return dp(39 + (size * 6 / 100))
    }

    private fun keyTextSize(label: String): Float {
        val size = KeyboardSettings.keyboardSize(this)
        val base = when (label) {
            "shift" -> 24f
            "space" -> 18f
            "123", "clicks", "enter", "back", "." -> 13.5f
            else -> 20f
        }
        val growth = when (label) {
            "shift" -> 2.5f
            "space" -> 2f
            "123", "clicks", "enter", "back", "." -> 1.5f
            else -> 2.5f
        }
        return base + (size * growth / 100f)
    }

    private fun keyBackground(label: String): Drawable {
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), keyPressedBackground(label))
            addState(intArrayOf(), keyIdleBackground(label))
        }
    }

    private fun visualKeyBackground(label: String, pressed: Boolean): Drawable {
        val base = if (pressed) keyPressedBackground(label) else keyIdleBackground(label)
        if (label == "enter" || label == "123") return base
        val vInset = keyVerticalInset()
        return InsetDrawable(base, dp(1), vInset, dp(1), vInset)
    }

    private fun keyIdleBackground(label: String): Drawable {
        val theme = keyboardVisualTheme()
        hyper3dKeyDrawable(label, theme)?.let { return it }
        if (theme == KEYBOARD_THEME_GOKEYS) return goKeysKeyBackground(label, pressed = false)
        if (label == "enter") return themedGoKeyBackground(goKeyColor(), pressed = false, theme = theme)
        if (label == "123") return themed123KeyBackground(pressed = false, theme = theme)
        return when (theme) {
            KEYBOARD_THEME_CLICKS -> physicalKeyBackground(pressed = false, premium = false, fn = isFnKey(label), theme = theme)
            KEYBOARD_THEME_SKEUO -> physicalKeyBackground(pressed = false, premium = true, fn = isFnKey(label), theme = theme)
            else -> Neu.drawable(selectedNeuTokens(), dp(7).toFloat(), NeuLevel.RAISED_SM)
        }
    }

    private fun keyPressedBackground(label: String): Drawable {
        val theme = keyboardVisualTheme()
        hyper3dKeyDrawable(label, theme)?.let { return it }
        if (theme == KEYBOARD_THEME_GOKEYS) return goKeysKeyBackground(label, pressed = true)
        if (label == "enter") return themedGoKeyBackground(brighten(goKeyColor()), pressed = true, theme = theme)
        if (label == "123") return themed123KeyBackground(pressed = true, theme = theme)
        return when (theme) {
            KEYBOARD_THEME_CLICKS -> physicalKeyBackground(pressed = true, premium = false, fn = isFnKey(label), theme = theme)
            KEYBOARD_THEME_SKEUO -> physicalKeyBackground(pressed = true, premium = true, fn = isFnKey(label), theme = theme)
            else -> Neu.drawable(selectedNeuTokens(), dp(7).toFloat(), NeuLevel.PRESSED_SM)
        }
    }

    private fun deckBackground(): Drawable {
        val theme = keyboardVisualTheme()
        if (theme == KEYBOARD_THEME_DEFAULT) return Neu.drawable(selectedNeuTokens(), dp(16).toFloat(), NeuLevel.RAISED)
        val light = keyboardLightMode(theme)
        val colors = if (light) {
            when (theme) {
                KEYBOARD_THEME_SKEUO -> intArrayOf(0xFFF1F3F6.toInt(), 0xFFE0E4EA.toInt(), 0xFFC9D0DA.toInt())
                KEYBOARD_THEME_CLICKS -> intArrayOf(0xFFECEFF4.toInt(), 0xFFDDE2E9.toInt(), 0xFFC7CED9.toInt())
                KEYBOARD_THEME_GOKEYS -> intArrayOf(0xFFF3F5F8.toInt(), 0xFFE2E6EC.toInt(), 0xFFC9D0DA.toInt())
                KEYBOARD_THEME_HYPER3D, KEYBOARD_THEME_HYPER3D_LIGHT -> intArrayOf(0xFFE8EBF0.toInt(), 0xFFD4D8E0.toInt(), 0xFFBEC4CF.toInt())
                else -> intArrayOf(0xFFECEEF2.toInt(), 0xFFDDE1E8.toInt(), 0xFFC9D0DA.toInt())
            }
        } else {
            when (theme) {
                KEYBOARD_THEME_SKEUO -> intArrayOf(0xFF24262D.toInt(), 0xFF101116.toInt(), 0xFF050506.toInt())
                KEYBOARD_THEME_CLICKS -> intArrayOf(0xFF1A1C21.toInt(), 0xFF111318.toInt(), 0xFF08090C.toInt(), 0xFF030304.toInt())
                KEYBOARD_THEME_GOKEYS -> intArrayOf(0xFF15171B.toInt(), 0xFF101115.toInt(), 0xFF0B0C0F.toInt())
                KEYBOARD_THEME_HYPER3D_BLACK -> intArrayOf(0xFF0C0C0E.toInt(), 0xFF080809.toInt(), 0xFF050506.toInt())
                KEYBOARD_THEME_HYPER3D_LIGHT -> intArrayOf(0xFFE8EBF0.toInt(), 0xFFD4D8E0.toInt(), 0xFFBEC4CF.toInt())
                KEYBOARD_THEME_HYPER3D -> intArrayOf(0xFF171A20.toInt(), 0xFF0D0F13.toInt(), 0xFF050608.toInt())
                else -> intArrayOf(0xFF1F2127.toInt(), 0xFF0C0D10.toInt(), 0xFF030304.toInt())
            }
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadii = floatArrayOf(dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), 0f, 0f, 0f, 0f)
            setStroke(dp(1), if (light) 0x66FFFFFF else when (theme) {
                KEYBOARD_THEME_SKEUO -> 0x303B4250
                KEYBOARD_THEME_GOKEYS -> 0x14FFFFFF
                KEYBOARD_THEME_HYPER3D_LIGHT -> 0x66FFFFFF
                else -> 0x181B20
            })
        }
    }

    private fun shouldStartShifted(attribute: EditorInfo?): Boolean {
        val variation = attribute?.inputType ?: return false
        return variation and android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0
    }

    private fun selectedNeuTokens(): NeuTokens {
        return when (themeMode()) {
            THEME_MODE_DARK -> Neu.Dark
            THEME_MODE_LIGHT -> Neu.Light
            else -> if (isSystemDarkMode()) Neu.Dark else Neu.Light
        }
    }

    private fun isSystemDarkMode(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun keyboardLightMode(theme: String): Boolean {
        if (keyboardTheme() == KEYBOARD_THEME_DEFAULT) return false
        return selectedNeuTokens().mode == NeuMode.LIGHT && theme != KEYBOARD_THEME_HYPER3D_BLACK
    }

    private fun hyper3dVisualTheme(theme: String): String {
        return if (theme == KEYBOARD_THEME_HYPER3D && keyboardLightMode(theme)) KEYBOARD_THEME_HYPER3D_LIGHT else theme
    }

    private fun isHyper3dTheme(theme: String): Boolean {
        return theme == KEYBOARD_THEME_HYPER3D ||
            theme == KEYBOARD_THEME_HYPER3D_BLACK ||
            theme == KEYBOARD_THEME_HYPER3D_LIGHT
    }

    private fun keyboardTheme(): String {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEYBOARD_THEME_PREF, KEYBOARD_THEME_DEFAULT) ?: KEYBOARD_THEME_DEFAULT
    }

    private fun keyboardVisualTheme(): String {
        return keyboardTheme()
    }

    private fun themeMode(): String {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(THEME_MODE_PREF, THEME_MODE_SYSTEM) ?: THEME_MODE_SYSTEM
    }

    private fun goKeyColor(): Int {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(GO_KEY_COLOR_PREF, 0xFFFF5A3C.toInt())
    }

    private fun hyper3dKeyDrawable(label: String, keyboardTheme: String): Drawable? {
        val id = when (hyper3dVisualTheme(keyboardTheme)) {
            KEYBOARD_THEME_HYPER3D -> when {
                label == "enter" -> R.drawable.key_hyper3d_go
                isFnKey(label) -> R.drawable.key_hyper3d_dark
                else -> R.drawable.key_hyper3d
            }
            KEYBOARD_THEME_HYPER3D_BLACK -> when {
                label == "enter" -> R.drawable.key_hyper3d_black_go
                label == "clicks" -> R.drawable.key_hyper3d_black_accent
                isFnKey(label) -> R.drawable.key_hyper3d_black_dark
                else -> R.drawable.key_hyper3d_black
            }
            KEYBOARD_THEME_HYPER3D_LIGHT -> when {
                label == "enter" -> R.drawable.key_hyper3d_light_go
                label == "clicks" -> R.drawable.key_hyper3d_light_accent
                isFnKey(label) -> R.drawable.key_hyper3d_light_dark
                else -> R.drawable.key_hyper3d_light
            }
            else -> return null
        }
        return runCatching { resources.getDrawable(id, this.theme) }.getOrNull()
    }

    private fun goKeysKeyBackground(label: String, pressed: Boolean): Drawable {
        val isGo = label == "enter"
        val radius = dp(if (isGo) 9 else 6).toFloat()
        if (isGo) {
            return GradientDrawable().apply {
                setColor(if (pressed) 0xFFE35C16.toInt() else 0xFFFF7A2A.toInt())
                cornerRadius = radius
                setStroke(dp(1), if (pressed) 0xFFE35C16.toInt() else 0xFFFF8A43.toInt())
            }
        }
        if (keyboardLightMode(keyboardTheme())) {
            return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                if (pressed) 0xFFD0D6DF.toInt() else 0xFFF3F5F8.toInt(),
                if (pressed) 0xFFB8C1CE.toInt() else 0xFFDCE2EA.toInt()
            )).apply {
                cornerRadius = radius
                setStroke(dp(1), if (pressed) 0xFFAAB4C1.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        return GradientDrawable().apply {
            setColor(if (pressed) 0xFF1A1D22.toInt() else 0xFF22262D.toInt())
            cornerRadius = radius
            setStroke(dp(1), if (pressed) 0xFF16191E.toInt() else 0xFF2E333B.toInt())
        }
    }

    private fun themed123KeyBackground(pressed: Boolean, theme: String): Drawable {
        if (theme == KEYBOARD_THEME_DEFAULT) return Neu.drawable(selectedNeuTokens(), dp(99).toFloat(), if (pressed) NeuLevel.PRESSED_SM else NeuLevel.RAISED_SM)
        val clicks = theme == KEYBOARD_THEME_CLICKS
        if (keyboardLightMode(theme)) {
            val faceTop = if (pressed) 0xFFD1D8E2.toInt() else 0xFFF1F4F8.toInt()
            val faceMid = if (pressed) 0xFFBEC7D3.toInt() else 0xFFDCE3EC.toInt()
            val faceBot = if (pressed) 0xFFA8B3C0.toInt() else 0xFFC4CDD8.toInt()
            val skirt = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x668A96A6) }
            val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(faceTop, faceMid, faceBot)).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), 0xFFFFFFFF.toInt())
            }
            val glint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x88FFFFFF.toInt(), 0x00FFFFFF)).apply { shape = GradientDrawable.OVAL }
            return LayerDrawable(arrayOf(skirt, face, glint)).apply {
                val drop = if (pressed) dp(1) else dp(5)
                setLayerInset(0, dp(1), drop, dp(1), 0)
                setLayerInset(1, dp(2), 0, dp(2), drop)
                setLayerInset(2, dp(8), dp(5), dp(8), dp(18))
            }
        }
        val faceTop = if (pressed) 0xFF3A3E4A.toInt() else if (clicks) 0xFF22252B.toInt() else 0xFF34373E.toInt()
        val faceMid = if (pressed) 0xFF2A2D36.toInt() else if (clicks) 0xFF171A1F.toInt() else 0xFF202329.toInt()
        val faceBot = if (pressed) 0xFF151719.toInt() else if (clicks) 0xFF07080B.toInt() else 0xFF0B0C10.toInt()
        val skirt = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF050609.toInt()) }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(faceTop, faceMid, faceBot)).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), if (clicks) 0xFF111318.toInt() else 0xFF05060A.toInt())
        }
        val glint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(if (clicks) 0x10FFFFFF else 0x3DFFFFFF, 0x00FFFFFF)).apply { shape = GradientDrawable.OVAL }
        return LayerDrawable(arrayOf(skirt, face, glint)).apply {
            val drop = if (pressed) dp(1) else dp(if (clicks) 4 else 6)
            setLayerInset(0, dp(1), drop, dp(1), 0)
            setLayerInset(1, dp(2), 0, dp(2), drop)
            setLayerInset(2, dp(8), dp(5), dp(8), dp(if (clicks) 20 else 18))
        }
    }

    private fun physicalKeyBackground(pressed: Boolean, premium: Boolean, fn: Boolean, theme: String): Drawable {
        val clicks = theme == KEYBOARD_THEME_CLICKS
        val radius = dp(when {
            premium -> 10
            clicks -> 5
            else -> 9
        }).toFloat()
        if (keyboardLightMode(theme)) {
            val skirt = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFB3BCC9.toInt(), 0xFF8E99A8.toInt(), 0xFF687482.toInt())).apply {
                cornerRadius = radius
                setStroke(dp(1), 0x88FFFFFF.toInt())
            }
            val outerRim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                if (pressed) 0xFFD3DBE5.toInt() else 0xFFFFFFFF.toInt(),
                if (pressed) 0xFFAEB8C6.toInt() else 0xFFCBD3DE.toInt()
            )).apply {
                cornerRadius = radius
                setStroke(dp(1), 0xFFFFFFFF.toInt())
            }
            val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                if (pressed) 0xFFD2DAE4.toInt() else 0xFFF5F7FA.toInt(),
                if (pressed) 0xFFBBC5D1.toInt() else 0xFFDCE3EC.toInt(),
                if (pressed) 0xFFA4AFBD.toInt() else 0xFFC2CCD8.toInt()
            )).apply {
                cornerRadius = radius
                setStroke(dp(1), if (pressed) 0xFFA6B1BE.toInt() else 0xFFFFFFFF.toInt())
            }
            val topGlint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99FFFFFF.toInt(), 0x00FFFFFF)).apply { cornerRadius = radius }
            val warmBleed = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(if (fn && !pressed) 0x22F5C451 else if (pressed) 0x33FF5A3C else 0x00000000, 0x00000000)).apply { cornerRadius = radius }
            return LayerDrawable(arrayOf(skirt, outerRim, face, warmBleed, topGlint)).apply {
                val drop = if (pressed) dp(1) else dp(if (premium) 7 else 6)
                val side = dp(2)
                setLayerInset(0, dp(1), drop, dp(1), 0)
                setLayerInset(1, side, dp(if (pressed) 1 else 0), side, drop)
                setLayerInset(2, side + dp(1), dp(1), side + dp(1), drop + dp(1))
                setLayerInset(3, side + dp(2), dp(4), side + dp(2), drop + dp(1))
                setLayerInset(4, side + dp(3), dp(2), side + dp(3), dp(20))
            }
        }
        val skirt = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            when {
                premium -> 0xFF14171D.toInt()
                clicks -> 0xFF050609.toInt()
                else -> 0xFF07080B.toInt()
            },
            if (clicks) 0xFF020304.toInt() else 0xFF050609.toInt(),
            0xFF010102.toInt()
        )).apply {
            cornerRadius = radius
            setStroke(dp(1), 0xFF040507.toInt())
        }
        val outerRim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            when {
                pressed -> 0xFFFF8E68.toInt()
                premium -> 0xFF525866.toInt()
                clicks -> 0xFF24272F.toInt()
                else -> 0xFF34373E.toInt()
            },
            if (clicks) 0xFF05060A.toInt() else 0xFF090A0D.toInt()
        )).apply {
            cornerRadius = radius
            setStroke(dp(1), if (premium) 0xFF05060A.toInt() else 0xFF040507.toInt())
        }
        val faceColors = when {
            pressed -> intArrayOf(0xFFFF9B72.toInt(), 0xFFFF5A3C.toInt(), 0xFF9C2F11.toInt())
            premium -> intArrayOf(0xFF4B505B.toInt(), 0xFF2D323D.toInt(), 0xFF151821.toInt(), 0xFF08090D.toInt())
            clicks -> intArrayOf(0xFF2B2E35.toInt(), 0xFF202329.toInt(), 0xFF15171C.toInt(), 0xFF0A0B0E.toInt())
            else -> intArrayOf(0xFF34373E.toInt(), 0xFF202329.toInt(), 0xFF14161B.toInt(), 0xFF0B0C10.toInt())
        }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, faceColors).apply {
            cornerRadius = radius
            setStroke(dp(1), if (pressed) 0xFFFFB199.toInt() else if (premium) 0xFF3C4350.toInt() else if (clicks) 0xFF111318.toInt() else 0xFF05060A.toInt())
        }
        val topGlint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (pressed) 0x66FFFFFF else if (premium) 0x55FFFFFF else if (clicks) 0x20FFFFFF else 0x3DFFFFFF,
            0x00FFFFFF
        )).apply { cornerRadius = radius }
        val warmBleed = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(if (fn && !pressed) (if (clicks) 0x16F5C451 else 0x22F5C451) else if (pressed) 0x66FF5A3C else 0x00000000, 0x00000000)).apply { cornerRadius = radius }
        return LayerDrawable(arrayOf(skirt, outerRim, face, warmBleed, topGlint)).apply {
            val drop = if (pressed) dp(1) else dp(when {
                premium -> 8
                clicks -> 8
                else -> 5
            })
            val side = if (premium) dp(2) else dp(if (clicks) 2 else 1)
            setLayerInset(0, dp(1), drop, dp(1), 0)
            setLayerInset(1, side, dp(if (pressed) 1 else 0), side, drop)
            setLayerInset(2, side + dp(1), dp(1), side + dp(1), drop + dp(if (premium) 1 else 0))
            setLayerInset(3, side + dp(2), dp(if (clicks) 5 else 3), side + dp(2), drop + dp(1))
            setLayerInset(4, side + dp(3), dp(2), side + dp(3), dp(if (premium) 19 else if (clicks) 24 else 20))
        }
    }

    private fun themedGoKeyBackground(fillColor: Int, pressed: Boolean, theme: String): Drawable {
        if (theme == KEYBOARD_THEME_DEFAULT) return Neu.drawable(selectedNeuTokens(), dp(99).toFloat(), if (pressed) NeuLevel.PRESSED_SM else NeuLevel.RAISED_SM)
        val skirt = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF050609.toInt())
        }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(brighten(fillColor), fillColor, darken(fillColor))).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), brighten(fillColor))
        }
        val glint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x55FFFFFF, 0x00FFFFFF)).apply {
            shape = GradientDrawable.OVAL
        }
        return LayerDrawable(arrayOf(skirt, face, glint)).apply {
            val drop = if (pressed) dp(1) else dp(if (theme == KEYBOARD_THEME_SKEUO) 6 else 4)
            setLayerInset(0, dp(1), drop, dp(1), 0)
            setLayerInset(1, dp(2), 0, dp(2), drop)
            setLayerInset(2, dp(8), dp(5), dp(8), dp(if (theme == KEYBOARD_THEME_SKEUO) 18 else 20))
        }
    }

    private fun isFnKey(label: String): Boolean {
        return label in setOf("123", "clicks", "back", "shift", ".")
    }

    private fun blend(foreground: Int, background: Int, amount: Float): Int {
        val fgA = foreground ushr 24 and 0xFF
        val fgR = foreground ushr 16 and 0xFF
        val fgG = foreground ushr 8 and 0xFF
        val fgB = foreground and 0xFF
        val bgA = background ushr 24 and 0xFF
        val bgR = background ushr 16 and 0xFF
        val bgG = background ushr 8 and 0xFF
        val bgB = background and 0xFF
        val t = amount.coerceIn(0f, 1f)
        val a = (bgA + (fgA - bgA) * t).toInt()
        val r = (bgR + (fgR - bgR) * t).toInt()
        val g = (bgG + (fgG - bgG) * t).toInt()
        val b = (bgB + (fgB - bgB) * t).toInt()
        return a shl 24 or (r shl 16) or (g shl 8) or b
    }

    private fun brighten(color: Int) = Color.rgb(
        (Color.red(color) + 42).coerceAtMost(255),
        (Color.green(color) + 42).coerceAtMost(255),
        (Color.blue(color) + 42).coerceAtMost(255)
    )

    private fun darken(color: Int) = Color.rgb(
        (Color.red(color) * 0.62f).toInt(),
        (Color.green(color) * 0.62f).toInt(),
        (Color.blue(color) * 0.62f).toInt()
    )

    private fun adjustAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        private const val PREFS_NAME = "clicks"
        private const val TOUCH_MODEL_PREF = "touch_model_v1"
        private const val HAPTICS_PREF = "haptics"
        private const val THEME_MODE_PREF = "theme_mode"
        private const val THEME_MODE_DARK = "dark"
        private const val THEME_MODE_LIGHT = "light"
        private const val THEME_MODE_SYSTEM = "system"
        private const val KEYBOARD_THEME_PREF = "keyboard_theme"
        private const val KEYBOARD_THEME_DEFAULT = "default"
        private const val KEYBOARD_THEME_CLICKS = "clicks"
        private const val KEYBOARD_THEME_SKEUO = "skeuo"
        private const val KEYBOARD_THEME_GOKEYS = "gokeys"
        private const val KEYBOARD_THEME_HYPER3D = "hyper3d"
        private const val KEYBOARD_THEME_HYPER3D_BLACK = "hyper3d_black"
        private const val KEYBOARD_THEME_HYPER3D_LIGHT = "hyper3d_light"
        private const val GO_KEY_COLOR_PREF = "go_key_color"
    }
}
