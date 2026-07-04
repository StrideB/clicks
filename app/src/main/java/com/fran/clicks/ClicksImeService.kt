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
import android.os.Handler
import android.os.Looper
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
import com.fran.clicks.keyboard.PredictionEngine
import com.fran.clicks.keyboard.SpatialScorer
import com.fran.clicks.db.NgramRepository
import java.util.Locale
import kotlin.math.abs

class ClicksImeService : InputMethodService() {
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
    private var predictionEngine = PredictionEngine(emptyMap())
    private val spatialScorer = SpatialScorer()
    private val ngramRepo by lazy { NgramRepository(this) }
    private var suggestionStrip: LinearLayout? = null
    private var agenticPanel: LinearLayout? = null
    private var suggestions: List<String> = emptyList()
    private var suggestDebounce: Runnable? = null
    private var liveCorrectDebounce: Runnable? = null
    private var geminiDebounce: Runnable? = null
    private var pendingOriginal: String? = null
    private var pendingCorrected: String? = null
    private val rejectedCorrections = HashMap<String, MutableSet<String>>()
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
        refreshKeyboardChrome()
        updateInputViewShown()
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
        refreshKeyboardChrome()
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
        super.onDestroy()
    }

    private fun buildKeyboard(): SwipeImeKeyboardLayout {
        keyViews.clear()
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
            val rows = if (symbolsMode) listOf(
                "1234567890".map { it.toString() },
                listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/"),
                listOf("*", "\"", "'", ":", ";", "!", "?", ",", "back"),
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

    private fun rebuildDeck() {
        setInputView(buildKeyboard().also { deckView = it })
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
        return TextView(this).apply {
            tag = label
            text = visualLabel(label)
            gravity = Gravity.CENTER
            includeFontPadding = false
            textSize = keyTextSize(label)
            typeface = if (label == "enter") Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor(label))
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

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    clicksLongPressFired = false
                    v.background = visualKeyBackground(label, pressed = true)
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
                    if (label == "back") startDeleteRepeat()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if ((label == "clicks" || label == "enter" || label == "space") &&
                        (abs(event.rawX - downRawX) > ViewConfiguration.get(this@ClicksImeService).scaledTouchSlop ||
                            abs(event.rawY - downRawY) > ViewConfiguration.get(this@ClicksImeService).scaledTouchSlop)) {
                        cancelClicksLongPress()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    v.background = visualKeyBackground(label, pressed = false)
                    keyPreview.dismiss()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150L)
                        .setInterpolator(android.view.animation.OvershootInterpolator(2.4f)).start()
                    cancelClicksLongPress()
                    if (clicksLongPressFired) {
                        clicksLongPressFired = false
                        return true
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
                    v.background = visualKeyBackground(label, pressed = false)
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
                pendingOriginal = null; pendingCorrected = null
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
                if (autocorrectEnabled()) tryAutocorrect(live = false)
                commitValue(" ")
                learnAndPredictAfterSpace()
                updateAutoCap()
            }
            "clicks" -> openLauncherKeyboardAction(ClicksKeyboardActions.OPEN_KEYBOARD_SETTINGS)
            "123" -> { symbolsMode = true; rebuildDeck() }
            "abc" -> { symbolsMode = false; rebuildDeck() }
            "." -> commitValue(".")
            else -> {
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
        val constant = when (label) {
            "back" -> HapticFeedbackConstants.KEYBOARD_RELEASE
            "enter" -> HapticFeedbackConstants.CONFIRM
            else -> HapticFeedbackConstants.KEYBOARD_TAP
        }
        deckView?.performHapticFeedback(constant)
    }

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

    private fun resolveTapKey(label: String, rawX: Float, rawY: Float): String {
        if (label.length != 1 || !label[0].isLetter() || keyBounds.isEmpty()) return label
        spatialScorer.recordTap(rawX, rawY)
        val best = spatialScorer.bestKey(rawX, rawY, letterOnly = true) { predictionEngine.nextCharWeights(currentWord()) }
        return if (best != null && best.length == 1 && best[0].isLetter()) best
        else keyAtPoint(rawX, rawY, letterOnly = true) ?: label
    }

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

    private fun ensureGlideClassifier() {
        if (glideClassifier != null) return
        Thread {
            // Union dictionary across the system's enabled languages — corrects & predicts in all of
            // them at once, no language switching (see DictionaryLoader).
            val loaded = com.fran.clicks.keyboard.DictionaryLoader.load(this)
            val clf = StatisticalGlideTypingClassifier()
            clf.setWordData(loaded.words, loaded.freqs)
            handler.post {
                glideClassifier = clf
                predictionEngine = PredictionEngine(loaded.freqs)
                updateGlideLayout()
            }
        }.start()
    }

    private fun commitGlideWord(word: String) {
        val input = currentInputConnection ?: return
        val before = input.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        val currentWordLength = before.takeLastWhile { it.isLetter() }.length
        if (currentWordLength > 0) input.deleteSurroundingText(currentWordLength, 0)
        input.commitText("$word ", 1)
        learnAndPredictAfterSpace()
    }

    // ── Prediction / autocorrect / learning — parity with the launcher keyboard ──
    private fun imePrefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun updateAutoCap() {
        if (capsLock) return
        val before = currentInputConnection?.getTextBeforeCursor(4, 0)?.toString().orEmpty().trimEnd()
        val cap = before.isEmpty() || before.endsWith('.') || before.endsWith('!') || before.endsWith('?')
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
        scheduleSuggestions()
        scheduleLiveCorrect()
        scheduleGemini()
        updateStrip()
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
            val word = currentWord()
            if (word.length < 2) {
                val prev = previousWord()
                val ic = currentInputConnection
                val before = ic?.getTextBeforeCursor(2, 0)?.toString().orEmpty()
                val justSpaced = before.isNotEmpty() && before.last() == ' '
                val fieldEmpty = before.isEmpty() && (ic?.getTextAfterCursor(1, 0)?.isEmpty() != false)
                suggestions = when {
                    justSpaced && prev.isNotEmpty() -> ngramRepo.cachedNextWords(prev).take(3)
                    fieldEmpty -> NotificationReplyContext.quickReplies(imePrefs(), System.currentTimeMillis())
                    else -> emptyList()
                }
                updateStrip(); return@Runnable
            }
            val prev = previousWord()
            if (prev.isNotEmpty()) ngramRepo.prefetchNextWords(prev)
            ngramRepo.prefetchNextWords(word)
            val chord = AbbreviationExpander.expand(word)
            val base = predictionEngine.getSuggestions(word, 3, ngramBoost = ngramRepo.cachedNextWords(prev))
            suggestions = ((if (chord != null) listOf(chord) else emptyList()) + base).distinct().take(3)
            updateStrip()
        }
        suggestDebounce = r
        handler.postDelayed(r, 70L)
    }

    private fun autocorrectEnabled() = imePrefs().getBoolean(IME_AUTOCORRECT_PREF, true)

    private fun scheduleLiveCorrect() {
        liveCorrectDebounce?.let { handler.removeCallbacks(it) }
        if (!autocorrectEnabled()) return
        val word = currentWord()
        if (word.length < 3) return
        val r = Runnable {
            if (currentWord() != word) return@Runnable
            if (tryAutocorrect(live = true)) scheduleSuggestions()
        }
        liveCorrectDebounce = r
        handler.postDelayed(r, 600L)
    }

    private fun tryAutocorrect(live: Boolean): Boolean {
        val ic = currentInputConnection ?: return false
        val word = currentWord()
        if (word.length < 2) return false
        if (live && (word.length < 3 || predictionEngine.isPrefixOfDictWord(word))) return false
        val context = ngramRepo.cachedNextWords(previousWord())
        val top = predictionEngine.bestCorrection(word, context) ?: return false
        if (top.equals(word, ignoreCase = true)) return false
        if (rejectedCorrections[word.lowercase(Locale.US)]?.contains(top.lowercase(Locale.US)) == true) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(top, 1)
        ic.endBatchEdit()
        pendingOriginal = word; pendingCorrected = top
        return true
    }

    private fun learnAndPredictAfterSpace() {
        val tokens = wordsBeforeCursor()
        if (tokens.size >= 2) ngramRepo.recordWord(tokens[tokens.size - 1], tokens[tokens.size - 2])
        val last = tokens.lastOrNull().orEmpty()
        if (last.isNotEmpty()) ngramRepo.prefetchNextWords(last)
        suggestions = if (last.isNotEmpty()) ngramRepo.cachedNextWords(last).take(3) else emptyList()
        updateStrip()
    }

    private fun acceptSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        val cur = currentWord()
        ic.beginBatchEdit()
        if (cur.isNotEmpty()) ic.deleteSurroundingText(cur.length, 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        learnAndPredictAfterSpace()
    }

    private fun rerankGlide(results: List<String>): String {
        if (results.size < 2) return results.first()
        val ctx = ngramRepo.cachedNextWords(previousWord()).map { it.lowercase(Locale.US) }
        if (ctx.isEmpty()) return results.first()
        val best = results.take(3).minByOrNull { val i = ctx.indexOf(it.lowercase(Locale.US)); if (i < 0) Int.MAX_VALUE else i }
        return if (best != null && ctx.contains(best.lowercase(Locale.US))) best else results.first()
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

    private fun agenticCardBackground() = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF20232A.toInt(), 0xFF14161B.toInt())
    ).apply { cornerRadius = dp(16).toFloat() }

    private fun agenticCta(label: String, accent: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 12.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(0xFF0A0C0F.toInt())
        setPadding(dp(15), dp(8), dp(15), dp(8))
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(11).toFloat() }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun agenticDismiss() = TextView(this).apply {
        text = "✕"; gravity = Gravity.CENTER; textSize = 13f
        setTextColor(0xFF767C89.toInt())
        setPadding(dp(10), dp(8), dp(6), dp(8))
        isClickable = true
        setOnClickListener { keyHaptic("back"); dismissAgentic() }
    }

    private fun dismissAgentic() {
        pendingCommand = null
        pendingActions = emptyList()
        agenticStatus = null
        updateStrip()
    }

    // Render the active agentic state into the card, or hide it and restore the strip when idle.
    private fun renderAgenticPanel() {
        val panel = agenticPanel ?: return
        panel.removeAllViews()

        val status = agenticStatus
        val pending = pendingCommand
        val accent: Int; val glyph: String; val kicker: String; val title: String
        when {
            status != null -> { accent = 0xFF9B8CFF.toInt(); glyph = "✦"; kicker = "CLICKS"; title = status }
            pending != null -> {
                accent = 0xFF57E39A.toInt()
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

        panel.visibility = View.VISIBLE
        panel.background = agenticCardBackground()
        suggestionStrip?.visibility = View.GONE

        panel.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(2).toFloat() }
        }, LinearLayout.LayoutParams(dp(3), dp(30)).apply { marginEnd = dp(11) })

        panel.addView(TextView(this).apply {
            text = glyph; gravity = Gravity.CENTER; textSize = 15f
            setTextColor(accent)
            background = GradientDrawable().apply {
                setColor(0xFF16181D.toInt()); cornerRadius = dp(10).toFloat()
                setStroke(dp(1), (accent and 0x00FFFFFF) or 0x55000000)
            }
        }, LinearLayout.LayoutParams(dp(32), dp(32)))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = kicker; textSize = 9f; letterSpacing = 0.16f
            setTextColor(0xFF767C89.toInt())
        })
        col.addView(TextView(this).apply {
            text = title; textSize = 14.5f
            setTextColor(0xFFF5F2FF.toInt())
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        panel.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(11); marginEnd = dp(8)
        })

        // A pending command is confirm-before-run; a working status is in-flight (no controls).
        if (pending != null && status == null) {
            panel.addView(agenticCta("Run", accent) { keyHaptic("enter"); applyPendingCommand() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            panel.addView(agenticDismiss(),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
        }
    }

    private fun fieldTextForPolish(): String =
        currentInputConnection?.getTextBeforeCursor(1000, 0)?.toString().orEmpty()

    private fun polishAvailable(): Boolean =
        ProManager.isUnlocked(this) && GeminiClient.configured(imePrefs()) &&
            fieldTextForPolish().trim().split(Regex("\\s+")).count { it.isNotEmpty() } >= 3

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
        if (shown.isEmpty() && !canPolish) { strip.background = null; return }
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
            if (i < shown.lastIndex || canPolish) {
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
        val enabled = com.fran.clicks.keyboard.DictionaryLoader.enabledLanguages(this)
        val primary = enabled.firstOrNull() ?: return null
        val other = enabled.firstOrNull { it != primary } ?: return null
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

    private fun refreshKeyboardChrome() {
        deckView?.let { deck ->
            deck.background = deckBackground()
            for (rowIndex in 0 until deck.childCount) {
                val row = deck.getChildAt(rowIndex) as? LinearLayout ?: continue
                for (keyIndex in 0 until row.childCount) {
                    val key = row.getChildAt(keyIndex) as? TextView ?: continue
                    val raw = key.tag as? String ?: continue
                    key.text = visualLabel(raw)
                    key.setTextColor(textColor(raw))
                    key.background = visualKeyBackground(raw, pressed = false)
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
                invalidate()
            }
            glideFadeRunnable = r
            handler.postDelayed(r, 900L)
        }

        private fun clearGlideTouchState() {
            tracking = false
            traced.clear()
            trailLocal.clear()
            glideClassifier?.clear()
            invalidate()
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
                        parent?.requestDisallowInterceptTouchEvent(true)
                        keyAtPoint(startRawX, startRawY, letterOnly = true)?.let { traced.add(it) }
                        trailLocal.add(startRawX - screenX to startRawY - screenY)
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
                    glideClassifier?.addGesturePoint(ev.rawX, ev.rawY)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val clf = glideClassifier
                    val quickLeftDelete = startRawX - ev.rawX > dp(52) && abs(ev.rawY - startRawY) < dp(36) && (clf == null || !clf.hasEnoughPoints)
                    val tracedKeys = traced.toList()
                    tracking = false
                    traced.clear()
                    glidePersisting = trailLocal.size > 1
                    invalidate()
                    if (quickLeftDelete) {
                        keyHaptic("back")
                        deleteWord()
                        clf?.clear()
                        fadeGlideTrail()
                        return true
                    }
                    if (clf != null && clf.hasEnoughPoints) {
                        Thread {
                            val results = clf.getSuggestions(3)
                            clf.clear()
                            handler.post {
                                if (results.isNotEmpty()) {
                                    keyHaptic("space")
                                    commitGlideWord(rerankGlide(results))
                                } else if (tracedKeys.size >= 3) {
                                    keyHaptic("space")
                                    handleSwipeFallback(tracedKeys)
                                }
                                fadeGlideTrail()
                            }
                        }.start()
                    } else {
                        clf?.clear()
                        if (tracedKeys.size >= 3) {
                            keyHaptic("space")
                            handleSwipeFallback(tracedKeys)
                        }
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
