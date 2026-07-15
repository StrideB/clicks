package com.fran.teclas

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
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
import android.os.Build
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
import com.fran.teclas.glide.KeyInfo
import com.fran.teclas.glide.StatisticalGlideTypingClassifier
import com.fran.teclas.keyboard.KeyPreviewManager
import com.fran.teclas.keyboard.applyDoubleSpacePeriod
import com.fran.teclas.keyboard.shouldAutoCapitalize
import com.fran.teclas.keyboard.PredictionEngine
import com.fran.teclas.keyboard.SpatialScorer
import com.fran.teclas.keyboard.neural.TimedPoint
import com.fran.teclas.db.NgramRepository
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

class TeclasImeService : InputMethodService(), com.fran.teclas.keyboard.KeyboardHost,
    android.view.textservice.SpellCheckerSession.SpellCheckerSessionListener {

    // ── KeyboardHost (shared-core seam) — wraps the IME's existing InputConnection behavior ──
    override fun commitText(text: String) = commitValue(text)
    override fun deleteBeforeCursor(count: Int) {
        currentInputConnection?.deleteSurroundingText(count, 0)
        shadowOnDeleteBefore(count)
    }
    override fun textBeforeCursor(count: Int): String = shadowBeforeCursor(count)
    override fun textAfterCursor(count: Int): String = shadowAfterCursor(count)
    override fun moveCursor(right: Boolean) =
        keyEvent(if (right) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)

    // ── Shadow editor ────────────────────────────────────────────────────────────────────────
    // A local mirror of the text on either side of the caret. Hot-path reads (currentWord,
    // textBeforeCursor, auto-cap, tap disambiguation) hit this instead of blocking on a cross-
    // process getTextBeforeCursor() round-trip — the launcher keyboard reads its in-memory string
    // for free, this closes most of that gap. Kept honest by tracking our own edits and reconciling
    // against the absolute caret in onUpdateSelection; any edit we can't track locally invalidates
    // it and the next selection callback reseeds from the editor. While invalid, reads fall back to
    // the InputConnection, so a stale mirror can never surface — worst case is a normal IPC read.
    private val shadowBefore = StringBuilder()
    private val shadowAfter = StringBuilder()
    private var shadowCaret = 0
    private var shadowValid = false       // before-cursor mirror is populated
    private var shadowAfterValid = false  // after-cursor mirror is populated (filled lazily)
    // Reads never look back more than ~200 chars (polishAvailable is the deepest), so seeding a
    // full 1KB on each resync was 4x more cross-process payload than anything ever consumes.
    private val shadowWindow = 256
    // Ring of the caret positions our own edits just moved through. onUpdateSelection callbacks are
    // async and arrive *after* we've already advanced the caret for the next keystroke(s), so during
    // a fast burst each stale callback reports a position behind shadowCaret and used to trigger a
    // full reseed (two getText* IPC round-trips) on EVERY keystroke. Recognizing a stale position as
    // "one of our own recent edits" lets us keep the already-correct mirror instead of re-reading it.
    private val selfCaretRing = IntArray(32)
    private var selfCaretIdx = 0

    // ── Temporary keystroke-path instrumentation ───────────────────────────────────────────────
    // Logs each hot-path step with the ms since the previous step, all under one tag. When the
    // keyboard freezes, the LAST line printed (and any large "+Nms") pinpoints exactly which call
    // hung. Capture with:  adb logcat -c ; <reproduce> ; adb logcat -d -s TeclasPerf:D
    // Flip PERF_LOG to false to silence it once the culprit is found.
    private var perfLastNanos = 0L
    private var perfOverlay: TextView? = null
    // Either diagnostic wants the shared on-screen overlay + pullable file plumbing (perfLine).
    private val diagOn: Boolean get() = PERF_LOG || RENDER_LOG
    private fun plog(where: String) {
        if (!PERF_LOG) return
        val now = System.nanoTime()
        val dt = if (perfLastNanos == 0L) 0L else (now - perfLastNanos) / 1_000_000
        perfLastNanos = now
        android.util.Log.d("TeclasPerf", "$where  +${dt}ms")
    }

    // Live on-screen rolling log (Vivo hides logcat). Each timed operation and each key-down appends
    // a line; the overlay shows the last ~10, so a single screenshot while typing captures the full
    // recent sequence with per-op durations and the cross-process read count (perfIpcReads = getText*
    // fallback reads that fire when the cursor-mirror is invalid — a prime suspect for the lag).
    private var perfIpcReads = 0
    private var perfWorstMs = 0L
    private var perfOverlayPending = false
    private val perfLines = ArrayDeque<String>()
    // Off-main writer so the perf file itself never adds jank to the thing we're measuring.
    private val diagExecutor by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    private val diagFile: java.io.File? by lazy { getExternalFilesDir(null)?.let { java.io.File(it, "teclas_perf.log") } }
    private fun perfLine(s: String) {
        if (!diagOn) return
        android.util.Log.d("TeclasPerf", s)
        perfLines.addLast(s)
        while (perfLines.size > 10) perfLines.removeFirst()
        // Throttle the on-screen refresh to ~8Hz. Re-laying-out a 10-line text view on every single
        // log line (5–10x per keystroke) was itself dropping frames and polluting the frame monitor.
        if (!perfOverlayPending) {
            perfOverlayPending = true
            handler.postDelayed({
                perfOverlayPending = false
                perfOverlay?.let { it.text = perfLines.joinToString("\n"); it.visibility = View.VISIBLE }
            }, 120L)
        }
        // Append to a pullable file (Vivo hides logcat), but ONLY meaningful lines — skip the flood of
        // "… 0ms" so the per-line file I/O doesn't itself become a per-keystroke cost. Key/section
        // markers and any non-zero timing are kept. Truncated on keyboard open.
        //   adb pull /sdcard/Android/data/com.fran.teclas/files/teclas_perf.log
        if (s.endsWith(" 0ms")) return
        val f = diagFile ?: return
        runCatching { diagExecutor.execute { runCatching { f.appendText(s + "\n") } } }
    }
    private fun perfReport(label: String, ms: Long) {
        if (!PERF_LOG) return
        if (ms > perfWorstMs) perfWorstMs = ms
        perfLine("$label ${ms}ms" + if (perfIpcReads > 0) " ipc=$perfIpcReads" else "")
    }
    private inline fun <T> ptime(label: String, block: () -> T): T {
        if (!PERF_LOG) return block()
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            perfReport(label, (System.nanoTime() - t0) / 1_000_000)
        }
    }
    // Render-only timing: measures synchronous main-thread cost (e.g. view construction at keyboard
    // open). Active whenever either diagnostic is on, so it works in the lightweight RENDER_LOG mode.
    private inline fun <T> renderTime(label: String, block: () -> T): T {
        if (!diagOn) return block()
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            perfLine("$label ${(System.nanoTime() - t0) / 1_000_000}ms")
        }
    }

    private fun recordSelfCaret() {
        selfCaretRing[selfCaretIdx % selfCaretRing.size] = shadowCaret
        selfCaretIdx++
    }

    private fun caretIsRecentlyOurs(caret: Int): Boolean {
        val n = minOf(selfCaretIdx, selfCaretRing.size)
        for (i in 0 until n) if (selfCaretRing[i] == caret) return true
        return false
    }

    // Frame-time monitor: the event handlers all measure 0ms, so if the keyboard still feels slow the
    // cost is in the framework's per-frame measure/layout/draw (which runs AFTER our handlers return)
    // or in dropped frames. This logs any frame interval that overran the ~16ms budget, so the file
    // shows real jank ("frame Nms") even when every handler is 0ms. Event-armed (see armFrameSample):
    // it samples a bounded burst of frames after each interaction and then releases the display, so it
    // never pins 60fps while idle — the mistake the old always-on monitor made, which added its own
    // jank to the very frames it was measuring. Active whenever either diagnostic flag is on.
    private var perfFrameLast = 0L
    private var perfFrameRunning = false
    private var perfFrameBudget = 0
    private val perfFrameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!perfFrameRunning) return
            if (perfFrameLast != 0L) {
                val ms = (frameTimeNanos - perfFrameLast) / 1_000_000
                if (ms in 24..4000) perfLine("‼ frame ${ms}ms")
            }
            perfFrameLast = frameTimeNanos
            // Event-armed burst: keep sampling only for a bounded number of frames after the last
            // interaction, then release the display. When the user types continuously each keystroke
            // re-arms the budget, so we watch exactly the frames that follow input and stop pinning
            // 60fps the instant typing pauses. Idle => zero frame callbacks scheduled.
            if (--perfFrameBudget > 0) {
                android.view.Choreographer.getInstance().postFrameCallback(this)
            } else {
                perfFrameRunning = false
            }
        }
    }
    // Arm (or extend) a short frame-sampling burst. Called at keyboard-open and after each keystroke.
    private fun armFrameSample(frames: Int = 20) {
        if (!diagOn) return
        if (frames > perfFrameBudget) perfFrameBudget = frames
        if (perfFrameRunning) return
        perfFrameRunning = true
        perfFrameLast = 0L
        android.view.Choreographer.getInstance().postFrameCallback(perfFrameCallback)
    }

    private fun seedShadow(caret: Int) = ptime("seedShadow(IPC)") { seedShadowInner(caret) }
    private fun seedShadowInner(caret: Int) {
        val ic = currentInputConnection
        if (ic == null) { shadowValid = false; shadowAfterValid = false; return }
        // Only read the BEFORE-cursor half here (one IPC). The AFTER half is almost never consumed
        // mid-word — the only readers are field-empty checks, which short-circuit when the before
        // text is non-empty — so read it lazily in shadowAfterCursor. This halves the reseed's
        // main-thread cost (the ~27ms seedShadow spikes that drop a frame after every backspace).
        perfIpcReads++
        val before = ic.getTextBeforeCursor(shadowWindow, 0)?.toString().orEmpty()
        shadowBefore.setLength(0); shadowBefore.append(before)
        shadowCaret = caret.coerceAtLeast(0)
        shadowValid = true
        shadowAfterValid = false
        // Reseed = the mirror is now authoritative here; forget older caret positions so a later
        // tap-to-reposition onto a stale position can't be mistaken for one of our own edits.
        selfCaretIdx = 0
        recordSelfCaret()
    }

    private fun invalidateShadow() { shadowValid = false; shadowAfterValid = false }

    private fun shadowOnCommit(text: String) {
        if (!shadowValid) return
        shadowBefore.append(text)
        shadowCaret += text.length
        // Keep only a working window near the caret; reads never look back more than ~200 chars.
        if (shadowBefore.length > shadowWindow * 2) shadowBefore.delete(0, shadowBefore.length - shadowWindow)
        recordSelfCaret()
    }

    private fun shadowOnDeleteBefore(count: Int) {
        if (!shadowValid) return
        val d = count.coerceAtMost(shadowBefore.length)
        shadowBefore.setLength(shadowBefore.length - d)
        shadowCaret -= d
        recordSelfCaret()
    }

    private fun shadowBeforeCursor(count: Int): String =
        if (shadowValid) shadowBefore.substring((shadowBefore.length - count).coerceAtLeast(0))
        else { perfIpcReads++; currentInputConnection?.getTextBeforeCursor(count, 0)?.toString().orEmpty() }

    private fun shadowAfterCursor(count: Int): String {
        // Lazily fill the after-cursor mirror the first time it's actually needed after a reseed.
        if (shadowValid && !shadowAfterValid) {
            val ic = currentInputConnection
            if (ic != null) {
                perfIpcReads++
                val after = ic.getTextAfterCursor(shadowWindow, 0)?.toString().orEmpty()
                shadowAfter.setLength(0); shadowAfter.append(after)
                shadowAfterValid = true
            }
        }
        return if (shadowValid && shadowAfterValid) shadowAfter.substring(0, count.coerceAtMost(shadowAfter.length))
        else { perfIpcReads++; currentInputConnection?.getTextAfterCursor(count, 0)?.toString().orEmpty() }
    }
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
    // FrameLayout wrapping the deck so the attach picker can float over the whole keyboard.
    private var imeRoot: android.widget.FrameLayout? = null
    private var attachOverlay: View? = null
    private var shareCardOverlay: View? = null
    // A file picked via the system picker, staged and waiting to commit once the field regains focus
    // (the target editor loses focus while the picker activity is up, so we can't commit mid-pick).
    private var pendingAttachment: Triple<java.io.File, String, String>? = null
    private val handler = Handler(Looper.getMainLooper())
    private val keyViews = mutableMapOf<String, TextView>()
    private val keyBounds = linkedMapOf<String, Rect>()
    private var glideClassifier: StatisticalGlideTypingClassifier? = null
    // Optional neural glide decoder (encoder-decoder + beam search). No-op until a model ships in
    // assets; when present it decodes ahead of the statistical classifier. Wired identically in the
    // launcher's Widget keyboard via the same NeuralGlideEngine.
    private var neuralGlide: com.fran.teclas.keyboard.neural.NeuralGlideEngine? = null
    // Hybrid decoder fuses neural + statistical (see HybridGlideDecoder); null until the engines load.
    private var hybridDecoder: com.fran.teclas.keyboard.neural.HybridGlideDecoder? = null
    private val glideLearning by lazy { com.fran.teclas.keyboard.neural.GlideLearningStore(this) }
    private var glideFreqs: Map<String, Float> = emptyMap()
    private val glideScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )
    // On-device Gemini Nano proofreading (ML Kit GenAI). nanoSupported gates the UI to devices that
    // actually have AICore; pendingProofread holds a correction awaiting the user's tap-to-apply.
    private val nanoProofread by lazy { com.fran.teclas.keyboard.NanoProofreadEngine(this) }
    private val nanoRewrite by lazy { com.fran.teclas.keyboard.NanoRewriteEngine(this) }
    @Volatile private var nanoSupported = false
    private var pendingProofread: String? = null
    // Last committed glide (path + bounds + word), kept so that if you correct it we can re-label the
    // swipe with the RIGHT word — the highest-signal training data (see GlideLearningStore.recordCorrection).
    private var lastGlidePath: List<TimedPoint> = emptyList()
    private var lastGlideBounds: FloatArray? = null
    private var lastGlideWord: String = ""
    // @Volatile: read by the background prediction thread; swapped on main when dictionaries load
    // or the active language flips. PredictionEngine itself is immutable after construction.
    @Volatile private var predictionEngine = PredictionEngine(emptyMap())         // active pointer (primary or extended)
    @Volatile private var predictionEnginePrimary = PredictionEngine(emptyMap())  // primary language only
    @Volatile private var predictionEngineExtended = PredictionEngine(emptyMap()) // primary + secondary phone languages
    private var hasLatentLanguages = false
    private var latentLanguageActive = false
    private val spatialScorer = SpatialScorer()
    private val ngramRepo by lazy { NgramRepository(this) }
    private val hapticEngine by lazy { com.fran.teclas.keyboard.CustomHapticEngine(this) }
    // ── Unified keyboard algorithm ──────────────────────────────────────────────────────────────
    // One composite scorer (edit distance + frequency + personal usage + n-gram context + bigram
    // LM + phonetics + morphology) that both the suggestion strip and autocorrect route through.
    // All learning state lives in the shared "teclas" prefs, so both keyboards learn as one:
    // personal word usage, rejected corrections, and the adapted signal weights.
    private val personalFreq by lazy { com.fran.teclas.keyboard.unified.PersonalFrequencyStore(imePrefs()) }
    private val rejectedStore by lazy { com.fran.teclas.keyboard.unified.RejectedCorrectionsStore(imePrefs()) }
    private val adaptiveWeights by lazy { com.fran.teclas.keyboard.unified.AdaptiveWeights(imePrefs()) }
    private val contextModel = com.fran.teclas.keyboard.unified.ContextModel()
    private val unifiedRanker by lazy {
        com.fran.teclas.keyboard.unified.UnifiedRanker(
            engine = { predictionEngine },
            personalBoost = { personalFreq.boost(it) },
            isRejectedPair = { t, c -> rejectedStore.contains(t, c) },
            lmProb = { prev, w -> contextModel.prob(prev, w) },
            weights = { adaptiveWeights.weights() }
        )
    }
    // Load the bigram LM off-main AFTER the keyboard is already visible: cold-open cost stays
    // zero; until it lands, lmProb returns 0 and ranking simply runs without the LM signal.
    private fun warmContextModel() {
        if (contextModel.isLoaded) return
        runCatching { predictExecutor.execute {
            runCatching { assets.open("dict/en_bigrams.txt").use { contextModel.load(it) } }
        } }
    }
    private val autocorrect by lazy {
        com.fran.teclas.keyboard.AutocorrectCore(
            host = this,
            engine = { predictionEngine },
            contextNextWords = { ngramRepo.cachedNextWords(it) },
            extendedEngine = { predictionEngineExtended },
            useFallback = true,  // match the launcher: accept edit-distance-1 corrections, not only strict
            ranker = { unifiedRanker },
            rejectedPersist = { t, c -> rejectedStore.add(t, c) },
            rejectedContains = { t, c -> rejectedStore.contains(t, c) }
        )
    }
    private val predictionCore by lazy {
        com.fran.teclas.keyboard.PredictionCore(this, { predictionEngine }, ngramRepo, ranker = { unifiedRanker })
    }
    private val glideCore by lazy { com.fran.teclas.keyboard.GlideCore(this, ngramRepo) }
    private var suggestionStrip: LinearLayout? = null
    private var agenticPanel: LinearLayout? = null
    private var spellChecker: android.view.textservice.SpellCheckerSession? = null
    private var lastSpellWord: String = ""
    private var inlineScroll: HorizontalScrollView? = null   // Gboard-style autofill chip row
    private var inlineRow: LinearLayout? = null
    // Agentic emoji + smart symbols shown in the suggestion strip: (display, textToInsert, isEmoji).
    private var smartChips: List<Triple<String, String, Boolean>> = emptyList()
    private var emojiTriggerWord: String = ""

    // A HUD result shown in the agentic panel (translation / mood read / drafted reply). [insert] is
    // non-null when the result can be dropped into the field with an Insert button.
    // A result surfaced in the agentic panel: a title/body, an optional Insert (drop [insert] into the
    // field), and optional follow-up chips that turn a one-shot answer into a next step ("Full forecast",
    // "Copy"…). This is the result-loop: the go button answers, then offers where to go from there.
    private data class HudAction(val label: String, val run: () -> Unit)
    private data class Hud(
        val kicker: String, val body: String, val insert: String?,
        val actions: List<HudAction> = emptyList()
    )
    private var agenticHud: Hud? = null
    // Discoverability: starter chips shown when the go button is held over an empty field.
    private var agenticStarters: List<HudAction> = emptyList()

    // Swipe up on a key to insert its symbol without leaving letters (mirrors the symbols layout).
    private val keyUpSymbols get() = com.fran.teclas.keyboard.KeyboardSymbols.keyUp
    private var suggestions: List<String> = emptyList()
    // True right after a glide commits a word, while the strip shows the swipe's other decodings as
    // tap-to-correct alternatives. Any physical key press clears it (back to normal typing/next-word).
    private var glideJustCommitted = false

    // ── Background prediction pipeline (the Gboard model) ───────────────────────────────────────
    // Dictionary-scanning work (suggestions + the space-bar autocorrect decision) runs here, never
    // on the UI thread. Results post back to main; [predictGeneration] drops stale answers when the
    // user has typed past them. [pendingCorrection] is (word, correction-or-null) precomputed for
    // the in-progress word so the space key applies it instantly instead of running a dictionary
    // search inside the keystroke.
    private val predictExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "teclas-predict").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    @Volatile private var predictGeneration = 0
    private var pendingCorrection: Pair<String, String?>? = null   // main-thread only

    private var suggestDebounce: Runnable? = null
    private var chipsDebounce: Runnable? = null      // emoji chips + spellcheck on a slower cadence
    private var liveCorrectDebounce: Runnable? = null
    private var geminiDebounce: Runnable? = null
    private var lastSpaceMs = 0L
    private var deleteRepeatRunnable: Runnable? = null
    private var deleteRepeatActive = false
    private var deleteRepeatFired = false
    private var currentEditorPackage: String? = null

    // Docked-deck typing routes through this IME's InputConnection (physical-keyboard behavior:
    // commit/delete deltas, never read the field — fixes apps like Telegram that expose their
    // "Message" placeholder as node text). [deckBuffer] holds keys typed before a field is focused;
    // they're committed once the connection binds (onStartInput).
    private val deckBuffer = StringBuilder()

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onEvaluateInputViewShown(): Boolean {
        return !shouldDeferToDeck()
    }

    /**
     * Handle one keystroke from the docked launcher deck. Commits via the InputConnection when a
     * field is focused; otherwise buffers it and asks the accessibility service to focus a field (or
     * open the app's search), after which [onStartInput] flushes the buffer.
     */
    fun onDeckKey(payload: String) {
        val ic = currentInputConnection
        if (ic != null) {
            flushDeckBuffer(ic)
            applyDeckKey(ic, payload)
            return
        }
        when (payload) {
            InputInjectionService.KEY_BACKSPACE -> if (deckBuffer.isNotEmpty()) deckBuffer.deleteCharAt(deckBuffer.length - 1)
            InputInjectionService.KEY_ENTER -> {}
            else -> deckBuffer.append(payload)
        }
        sendBroadcast(Intent(InputInjectionService.ACTION_PREPARE_FIELD).setPackage(packageName))
    }

    private fun applyDeckKey(ic: android.view.inputmethod.InputConnection, payload: String) {
        when (payload) {
            InputInjectionService.KEY_BACKSPACE -> ic.deleteSurroundingText(1, 0)
            InputInjectionService.KEY_ENTER -> if (!sendDefaultEditorAction(true)) ic.commitText("\n", 1)
            else -> ic.commitText(payload, 1)
        }
    }

    private fun flushDeckBuffer(ic: android.view.inputmethod.InputConnection) {
        if (deckBuffer.isEmpty()) return
        ic.commitText(deckBuffer.toString(), 1)
        deckBuffer.clear()
    }

    override fun onCreateInputView(): View {
        shifted = false
        // Account mode: route AI through the proxy with the signed-in user's Google ID token.
        GeminiClient.proxy = GeminiProxy.binding(this)
        // On-device Gemini Nano first (shared with the launcher when in the same process).
        if (GeminiClient.nano == null) GeminiClient.nano = NanoPromptEngine(applicationContext).also { it.warmUp() }
        // llama.cpp tier: the only on-device path AICore can't block while we serve another app.
        if (GeminiClient.local == null) {
            GeminiClient.local = { p, mt, t, j, g, q -> com.fran.teclas.llm.LocalLlmEngine.generateBlocking(applicationContext, p, mt, t, json = j, grammar = g, quality = q) }
            GeminiClient.localReady = { com.fran.teclas.llm.LocalLlmEngine.ready(applicationContext) }
        }
        ensureGlideClassifier()
        checkNanoProofreadSupport()
        initSpellChecker()
        spatialScorer.importState(imePrefs().getString(TOUCH_MODEL_PREF, "") ?: "")
        AgenticRouter.ensureLoaded(this)
        return renderTime("open onCreateInputView") { buildInputView() }
    }

    // Wrap the keyboard deck in a FrameLayout root so a picker can overlay it. [deckView] still points
    // at the deck itself, so all existing keyboard logic is unchanged.
    private fun buildInputView(): View {
        attachOverlay = null
        shareCardOverlay = null
        // Truncate the pullable diag file up-front so both open-timing lines below survive in it (the
        // serial diagExecutor would otherwise wipe an already-appended "open buildKeyboard" line).
        if (diagOn) runCatching { diagExecutor.execute { runCatching { diagFile?.writeText("") } } }
        val deck = renderTime("open buildKeyboard") { buildKeyboard() }.also { deckView = it }
        // On a wide canvas, don't stretch across the whole inner display — pin a narrower, centered
        // panel (matching the launcher keyboard); on phones keep the full-width keyboard.
        val deckParams = if (imeIsWideCanvas()) {
            android.widget.FrameLayout.LayoutParams(
                imeKeyboardPanelWidth(),
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        } else {
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        return android.widget.FrameLayout(this).apply {
            addView(deck, deckParams)
            // Diagnostic on-screen readout of the slowest keystroke step (Vivo hides logcat).
            if (diagOn) {
                armFrameSample()
                perfOverlay = TextView(this@TeclasImeService).apply {
                    textSize = 9.5f
                    setTextColor(0xFF6BFF6B.toInt())
                    setBackgroundColor(0xE6000000.toInt())
                    setPadding(dp(6), dp(3), dp(6), dp(3))
                    typeface = Typeface.MONOSPACE
                    setLineSpacing(0f, 0.95f)
                    maxLines = 12
                    visibility = View.GONE
                }
                addView(perfOverlay, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START))
            }
        }.also {
            imeRoot = it
            // Key previews render inside this window (one reused view), never as per-press popups.
            keyPreview.attachHost(it)
        }
    }

    /**
     * With a centered, narrower panel on a wide canvas, the window still spans the full width — so
     * restrict the touchable region to the actual keyboard panel, letting taps in the side gaps fall
     * through to the app. Skipped when an overlay (attach picker / share card) is up or the panel
     * hasn't been measured yet, to avoid making the keyboard untouchable.
     */
    override fun onComputeInsets(outInsets: android.inputmethodservice.InputMethodService.Insets?) {
        super.onComputeInsets(outInsets)
        outInsets ?: return
        if (!imeIsWideCanvas()) return
        if (attachOverlay != null || shareCardOverlay != null) return
        val deck = deckView ?: return
        if (deck.width == 0 || deck.height == 0) return
        val loc = IntArray(2)
        deck.getLocationInWindow(loc)
        outInsets.touchableInsets = android.inputmethodservice.InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(loc[0], loc[1], loc[0] + deck.width, loc[1] + deck.height)
    }

    // System spellchecker — real correction candidates for hard misspellings (parity with launcher).
    private fun initSpellChecker() {
        if (spellChecker != null) return
        runCatching {
            val tsm = getSystemService(TEXT_SERVICES_MANAGER_SERVICE) as android.view.textservice.TextServicesManager
            spellChecker = tsm.newSpellCheckerSession(null, null, this, true)
        }
    }

    override fun onGetSuggestions(results: Array<out android.view.textservice.SuggestionsInfo>?) {
        handler.post {
            if (currentWord().lowercase(Locale.US) != lastSpellWord.lowercase(Locale.US)) return@post
            val words = results?.firstOrNull()?.let { info ->
                (0 until info.suggestionsCount).map { info.getSuggestionAt(it) }
            }?.filter { it.isNotBlank() } ?: emptyList()
            if (words.isNotEmpty()) {
                suggestions = (suggestions + words).distinct().take(3)
                updateStrip()
            }
        }
    }

    override fun onGetSentenceSuggestions(results: Array<out android.view.textservice.SentenceSuggestionsInfo>?) {}

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentEditorPackage = info?.packageName?.toString() ?: currentEditorPackage
        warmContextModel()        // lazy bigram-LM load, off-main, no-op once loaded
        ensureGlideClassifier()   // retry if a previous load failed (onCreateInputView runs only once)
        if (shouldDeferToDeck()) {
            hideImeSurface()
            return
        }
        armFrameSample()
        // Seed the shadow mirror from the fresh editor so the first keystrokes read locally.
        seedShadow(info?.initialSelStart?.takeIf { it >= 0 } ?: 0)
        refreshChromeOrRebuild()
        // On a FRESH field (not an input restart), open the layout the field wants: numbers/symbols
        // for numeric/phone/date fields, letters otherwise. Guarded by !restarting so a per-keystroke
        // input restart never yanks the user out of a layout they manually switched to.
        if (!restarting) {
            val want = desiredSymbolsModeFor(info)
            if (want != symbolsMode) { symbolsMode = want; rebuildKeyRows() }
        }
        updateInputViewShown()
        clearInlineSuggestions()
        agenticHud = null
        agenticStarters = emptyList()
        scheduleSuggestions()
        // A file picked via the system picker while we were unfocused — commit it now (IC is fresh).
        if (pendingAttachment != null) handler.postDelayed({ commitPendingAttachment() }, 150)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        perfFrameRunning = false   // stop the frame monitor while hidden
        invalidateShadow()   // leaving the field; the mirror no longer describes anything
        // Don't let the attach sheet or a share card linger across fields or when the keyboard hides.
        hideAttachPicker()
        hideShareCard()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        currentEditorPackage = attribute?.packageName?.toString()
        if (shouldDeferToDeck()) {
            hideImeSurface()
            currentInputConnection?.let { flushDeckBuffer(it) }   // commit keys typed before the field focused
            return
        }
        shifted = shouldStartShifted(attribute)
        refreshChromeOrRebuild()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (shouldDeferToDeck()) hideImeSurface()
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
        plog("onUpdateSelection old=$oldSelStart new=$newSelStart caret=$shadowCaret valid=$shadowValid")
        // Reconcile the shadow against the editor's ground-truth caret. A collapsed caret exactly
        // where our own edits left it → the mirror is still correct, keep it (no IPC). Anything else
        // — a range selection, or a caret that jumped somewhere we didn't drive (tap, autofill, the
        // app's own edit) — reseed from the editor. This runs off the keystroke hot path.
        if (newSelStart != newSelEnd) {
            invalidateShadow()
        } else if (shadowValid && newSelStart == shadowCaret) {
            // Mirror already at the true caret — nothing to do, no IPC.
        } else if (shadowValid && newSelStart < shadowCaret && caretIsRecentlyOurs(newSelStart)) {
            // A belated callback for one of our own just-committed edits, arriving while we've
            // already advanced. The mirror is ahead and correct — don't pay a reseed for a stale
            // event. This is what stops fast typing from doing two getText* IPC calls per keystroke.
        } else {
            seedShadow(newSelStart)
        }
        if (!isLauncherEditorActive()) scheduleSuggestions()
    }

    private fun isLauncherEditorActive(): Boolean {
        return currentEditorPackage == packageName
    }

    /**
     * Whether the IME should stand down and let the docked launcher deck handle input: either the
     * launcher's own editor is active, or an app is in the docked freeform top region (the deck is
     * visible below it and types into the app via the accessibility injector — no IME handoff).
     */
    private fun shouldDeferToDeck(): Boolean {
        return isLauncherEditorActive() || DockedFreeform.externalAppInFront
    }

    private fun hideImeSurface() {
        stopDeleteRepeat(clearFired = true)
        requestHideSelf(0)
        runCatching { hideWindow() }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        deckBuffer.clear()
        stopDeleteRepeat(clearFired = true)
        imePrefs().edit().putString(TOUCH_MODEL_PREF, spatialScorer.exportState()).apply()
        glideScope.cancel()
        neuralGlide?.close()
        nanoProofread.close()
        nanoRewrite.close()
        runCatching { spellChecker?.close() }
        runCatching { thumbExecutor.shutdownNow() }
        runCatching { predictExecutor.shutdownNow() }
        if (diagOn) runCatching { diagExecutor.shutdownNow() }
        AttachBridge.pending = null
        super.onDestroy()
    }

    private fun buildKeyboard(): SwipeImeKeyboardLayout {
        keyViews.clear()
        lastBuiltTheme = keyboardTheme()
        lastBuiltMode = selectedNeuTokens().mode
        return SwipeImeKeyboardLayout().apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(8))
            background = deckBackground()
            minimumHeight = imeKeyboardHeight()
            // Per-component open-cost breakdown (RENDER_LOG): buildKeyboard's total was ~19ms after
            // the canvas switch; these localize whatever remains (strip vs chrome vs key grid).
            addView(renderTime("open strip") { buildSuggestionStrip() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)))
            addView(renderTime("open agentic") { buildAgenticPanel() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = dp(4); rightMargin = dp(4); topMargin = dp(1); bottomMargin = dp(2)
            })
            addView(renderTime("open autofill") { buildInlineAutofillRow() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply {
                leftMargin = dp(4); rightMargin = dp(4)
            })
            renderTime("open keyRows") { addKeyRows(this) }
            post { captureKeyBounds() }
        }
    }

    // Children before the key rows: suggestion strip, agentic panel, inline autofill row.
    private val chromeChildCount = 3

    // ── Canvas keyboard (experimental, Stage 1) ─────────────────────────────────────────────────
    // When active, ONE view draws the whole key grid into a bitmap buffer instead of ~30 child views
    // in nested LinearLayouts — the AOSP/Gboard model, and the fix for the cold-open build cost.
    // Gated to the Teclas theme for now while it's brought to visual/behavioral parity stage by stage.
    private var canvasKeyboardView: TeclasCanvasKeyboardView? = null
    private fun useCanvasKeyboard(): Boolean = CANVAS_KB

    // Typeface for a key label, matching the view keyboard's keyView() per-theme choice exactly so the
    // canvas renders every theme faithfully (SeeMe/Brushed use monospace; enter is always bold).
    private fun keyTypeface(label: String): Typeface {
        val theme = keyboardTheme()
        KeyboardThemeDrawables.typeface(keyboardVisualTheme(), label)?.let { return it }
        return if (theme == KEYBOARD_THEME_SEEME || theme == KEYBOARD_THEME_BRUSHED) {
            Typeface.create(Typeface.MONOSPACE, if (label == "enter") Typeface.BOLD else Typeface.NORMAL)
        } else if (label == "enter") Typeface.DEFAULT_BOLD
        else Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    // Brushed places letter labels differently (higher on the face, engraved symbol hints) and only
    // uppercases on caps-lock; every other theme uses DynamicFlickKeyView's default placement. Returned
    // as (labelBias, symbolBias, labelMaxScale, symbolScale, engraved) — mirrors setLabelPlacement().
    private data class CanvasLabelSpec(
        val labelBias: Float, val symbolBias: Float,
        val labelMaxScale: Float, val symbolScale: Float, val engraved: Boolean
    )
    private fun canvasLetterSpec(): CanvasLabelSpec =
        if (keyboardTheme() == KEYBOARD_THEME_BRUSHED)
            CanvasLabelSpec(0.28f, 0.04f, 0.52f, 0.16f, engraved = true)
        else CanvasLabelSpec(0.52f, 0.24f, 0.62f, 0.16f, engraved = false)

    // Brushed function keys are drawn with a small optical vertical nudge (OpticalKeyTextView); this is
    // that per-label offset table. Zero for every other theme (labels center normally).
    private fun canvasOpticalOffset(label: String): Pair<Float, Float> {
        if (keyboardTheme() != KEYBOARD_THEME_BRUSHED) return 0f to 0f
        val y = dp(when (label) {
            "enter" -> -2; "shift" -> -12; "back", "space" -> -16; "123", "abc", "." -> -5; else -> 0
        }).toFloat()
        val x = if (label == "back") -dp(4).toFloat() else 0f
        return x to y
    }

    // The row model both the view keyboard and the canvas keyboard lay out from (single source).
    private fun currentKeyRows(): List<List<String>> = if (symbolsMode) listOf(
        com.fran.teclas.keyboard.KeyboardSymbols.ROW_DIGITS,
        com.fran.teclas.keyboard.KeyboardSymbols.ROW_SYMBOLS_1,
        com.fran.teclas.keyboard.KeyboardSymbols.ROW_SYMBOLS_2 + listOf("back"),
        listOf("abc", "teclas", "space", ".", "enter")
    ) else listOf(
        "qwertyuiop".map { it.toString() },
        "asdfghjkl".map { it.toString() },
        listOf("shift") + "zxcvbnm".map { it.toString() } + listOf("back"),
        listOf("123", "teclas", "space", ".", "enter")
    )

    // The canvas view publishes its own key rects (screen coords) here — same downstream wiring the
    // view keyboard's captureKeyBounds uses, so glide/flick/tap resolution are identical.
    private fun setCanvasKeyBounds(fresh: LinkedHashMap<String, Rect>) {
        if (fresh == keyBounds) return
        keyBounds.clear(); keyBounds.putAll(fresh)
        spatialScorer.setKeys(keyBounds)
        updateGlideLayout()
    }

    private fun addKeyRows(deck: LinearLayout) {
        if (useCanvasKeyboard()) {
            val cv = TeclasCanvasKeyboardView().also { canvasKeyboardView = it }
            val rowsHeight = keyRowHeight() * 4 - keyRowOverlap() * 3
            deck.addView(cv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowsHeight))
            return
        }
        canvasKeyboardView = null
        currentKeyRows().forEachIndexed { rowIndex, row ->
            deck.addView(keyRow(row, rowIndex), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, keyRowHeight()).apply {
                if (rowIndex > 0) topMargin = -keyRowOverlap()
            })
        }
    }

    // One laid-out key in the canvas keyboard. [cell] is the full key rect (matching the old per-key
    // view bounds, so keyBounds — and therefore glide/tap resolution — stay identical). Label geometry
    // mirrors the two draw modes of the view keyboard: flick letters place the glyph mid-face with the
    // swipe-down symbol hint up top (DynamicFlickKeyView); every other key centers its label.
    private class CanvasKeyCell(
        val label: String,
        val cell: Rect,
        val idle: Drawable,
        val pressed: Drawable,
        val primaryText: String,
        val symbolHint: String?,
        val isFlickLetter: Boolean,
        val color: Int,
        val symbolColor: Int,
        val typeface: Typeface,
        val textSizePx: Float,
        // Theme-driven label geometry (see canvasLetterSpec / canvasOpticalOffset).
        val labelBias: Float,
        val symbolBias: Float,
        val labelMaxScale: Float,
        val symbolScale: Float,
        val engraved: Boolean,
        val offsetX: Float,
        val offsetY: Float
    )

    // ── Canvas keyboard view (Stage 1, Teclas theme) ────────────────────────────────────────────
    // Draws the whole key grid once into an offscreen bitmap and blits it; the pressed key is drawn
    // live on top so press/release never re-renders the buffer. Key backgrounds are the exact same
    // Drawables the view keyboard builds (faithful Teclas look); labels reuse keyDisplayText. Taps are
    // resolved by coordinate against the same keyBounds the glide engine reads — which this view
    // publishes itself — so glide/flick/cursor-pan/delete are handled by the parent
    // SwipeImeKeyboardLayout exactly as before. This view only handles discrete taps that fall through
    // the deck's glide interception.
    private inner class TeclasCanvasKeyboardView : View(this@TeclasImeService) {
        private var keys: List<CanvasKeyCell> = emptyList()
        private var pressedLabel: String? = null
        private var buffer: android.graphics.Bitmap? = null
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        // Touch state (mirrors ImeKeyTouchListener). The whole grid is one view, so the pressed key is
        // captured on DOWN and that same key commits on UP — exactly the per-key-view capture semantics.
        private var downRawX = 0f
        private var downRawY = 0f
        private var spaceCursorLastX = 0f
        private var spaceCursorMoved = false
        private var longPressFired = false
        private var longPressRunnable: Runnable? = null

        fun rebuild() { relayoutKeys(); publishBounds(); invalidate() }
        fun republishBounds() { publishBounds() }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(
                MeasureSpec.getSize(widthMeasureSpec),
                keyRowHeight() * 4 - keyRowOverlap() * 3
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            relayoutKeys()
            publishBounds()
        }

        // Mirror keyRow()'s LinearLayout math: per-row horizontal inset, fixed-square 123/enter keys,
        // the rest sharing the remaining width by keyWeight; rows stacked with keyRowOverlap.
        private fun relayoutKeys() = renderTime("canvas relayout") { relayoutKeysInner() }
        private fun relayoutKeysInner() {
            val totalWidth = width
            if (totalWidth <= 0) return
            val rowH = keyRowHeight()
            val overlap = keyRowOverlap()
            val goSize = themedGoKeySize()
            val density = resources.displayMetrics.scaledDensity
            val out = ArrayList<CanvasKeyCell>(36)
            val spec = canvasLetterSpec()
            val brushed = keyboardTheme() == KEYBOARD_THEME_BRUSHED
            currentKeyRows().forEachIndexed { rowIndex, row ->
                val inset = when (rowIndex) { 1 -> dp(12); 2 -> dp(6); 3 -> dp(18); else -> 0 }
                val rowTop = rowIndex * (rowH - overlap)
                var weightSum = 0f
                var fixedTotal = 0
                row.forEach { lbl ->
                    if (lbl == "enter" || lbl == "123") fixedTotal += goSize else weightSum += keyWeight(lbl)
                }
                val flexible = (totalWidth - inset * 2 - fixedTotal).coerceAtLeast(0)
                var x = inset
                row.forEach { lbl ->
                    val square = lbl == "enter" || lbl == "123"
                    val kw = if (square) goSize
                        else if (weightSum > 0f) (flexible * keyWeight(lbl) / weightSum).toInt() else 0
                    val cellTop = if (square) rowTop + (rowH - goSize) / 2 else rowTop
                    val cellH = if (square) goSize else rowH
                    val flick = lbl.length == 1 && lbl[0].isLetter() && !symbolsMode
                    // Brushed uppercases letters on caps-lock only and blanks the teclas key (its face
                    // art carries the meaning); every other theme uses visualLabel (shift OR caps).
                    val primary = when {
                        flick && brushed -> if (capsLock) lbl.uppercase(Locale.US) else lbl.lowercase(Locale.US)
                        brushed && lbl == "teclas" -> ""
                        else -> visualLabel(lbl)
                    }
                    val (ox, oy) = if (flick) 0f to 0f else canvasOpticalOffset(lbl)
                    out.add(CanvasKeyCell(
                        lbl,
                        Rect(x, cellTop, x + kw, cellTop + cellH),
                        cachedKeyBackground(lbl, pressed = false),
                        cachedKeyBackground(lbl, pressed = true),
                        primary,
                        if (flick) com.fran.teclas.keyboard.KeyboardSymbols.keyUp[lbl.lowercase(Locale.US)] else null,
                        flick,
                        textColor(lbl),
                        symbolHintColor(),
                        keyTypeface(lbl),
                        keyTextSize(lbl) * density,
                        spec.labelBias, spec.symbolBias, spec.labelMaxScale, spec.symbolScale, spec.engraved,
                        ox, oy
                    ))
                    x += kw
                }
            }
            keys = out
            renderBuffer()
        }

        private fun renderBuffer() = renderTime("canvas bitmap") { renderBufferInner() }
        private fun renderBufferInner() {
            val w = width; val h = height
            if (w <= 0 || h <= 0) return
            val bmp = buffer?.takeIf { it.width == w && it.height == h }
                ?: android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { buffer = it }
            val c = Canvas(bmp)
            c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            keys.forEach { drawKey(c, it, pressed = false) }
        }

        private fun drawKey(canvas: Canvas, k: CanvasKeyCell, pressed: Boolean) {
            val d = if (pressed) k.pressed else k.idle
            d.setBounds(k.cell.left, k.cell.top, k.cell.right, k.cell.bottom)
            d.draw(canvas)
            val cx = k.cell.exactCenterX()
            if (k.isFlickLetter) {
                // Mirror DynamicFlickKeyView: symbol hint and letter each drawn at their own face bias,
                // inside a face inset by keyVerticalInset(), with sizes scaled off the face height. The
                // bias/scale/engrave values are theme-driven (canvasLetterSpec), so Brushed's higher,
                // engraved placement and every other theme's default both render from one path.
                val faceInset = keyVerticalInset().toFloat()
                val faceTop = k.cell.top + faceInset
                val faceHeight = (k.cell.height() - faceInset * 2).coerceAtLeast(1f)
                k.symbolHint?.let { s ->
                    symbolPaint.color = k.symbolColor
                    symbolPaint.textSize = faceHeight * k.symbolScale
                    val m = symbolPaint.fontMetrics
                    val baseline = faceTop + faceHeight * k.symbolBias - (m.ascent + m.descent) / 2f
                    if (k.engraved) {
                        val original = symbolPaint.color
                        symbolPaint.color = 0x99000000.toInt(); symbolPaint.alpha = 150
                        canvas.drawText(s, cx, baseline + 1.4f, symbolPaint)
                        symbolPaint.color = 0x66FFFFFF; symbolPaint.alpha = 80
                        canvas.drawText(s, cx, baseline - 0.8f, symbolPaint)
                        symbolPaint.color = original; symbolPaint.alpha = 215
                    } else {
                        symbolPaint.alpha = 230
                    }
                    canvas.drawText(s, cx, baseline, symbolPaint)
                    symbolPaint.alpha = 255
                }
                labelPaint.color = k.color
                labelPaint.typeface = k.typeface
                labelPaint.textSize = minOf(k.textSizePx, faceHeight * k.labelMaxScale)
                val m = labelPaint.fontMetrics
                val baseline = faceTop + faceHeight * k.labelBias - (m.ascent + m.descent) / 2f
                canvas.drawText(k.primaryText, cx, baseline, labelPaint)
            } else if (k.primaryText.isNotEmpty()) {
                // Plain keys: label centered in the cell (matches gravity=CENTER), plus the theme's
                // optical nudge (Brushed function keys; zero elsewhere).
                labelPaint.color = k.color
                labelPaint.typeface = k.typeface
                labelPaint.textSize = k.textSizePx
                val m = labelPaint.fontMetrics
                val cy = k.cell.exactCenterY()
                canvas.drawText(k.primaryText, cx + k.offsetX, cy - (m.ascent + m.descent) / 2f + k.offsetY, labelPaint)
            }
        }

        override fun onDraw(canvas: Canvas) {
            buffer?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            // Pressed key drawn live over the cached buffer — a press never re-renders all 34 keys.
            pressedLabel?.let { pl -> keys.firstOrNull { it.label == pl }?.let { drawKey(canvas, it, pressed = true) } }
        }

        private fun publishBounds() {
            if (keys.isEmpty()) return
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val fresh = LinkedHashMap<String, Rect>(keys.size)
            keys.forEach { k ->
                fresh[k.label] = Rect(loc[0] + k.cell.left, loc[1] + k.cell.top, loc[0] + k.cell.right, loc[1] + k.cell.bottom)
            }
            setCanvasKeyBounds(fresh)
        }

        private fun keyAtLocal(x: Float, y: Float): String? =
            keys.firstOrNull { it.cell.contains(x.toInt(), y.toInt()) }?.label

        private fun armLongPress(delayMs: Long, action: () -> Unit) {
            val r = Runnable { longPressFired = true; action() }
            longPressRunnable = r
            handler.postDelayed(r, delayMs)
        }

        private fun cancelKeyLongPress() {
            longPressRunnable?.let { handler.removeCallbacks(it) }
            longPressRunnable = null
            stopAgenticHapticRamp()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val lbl = keyAtLocal(event.x, event.y) ?: return false
                    pressedLabel = lbl
                    downRawX = event.rawX; downRawY = event.rawY
                    spaceCursorLastX = event.rawX; spaceCursorMoved = false
                    longPressFired = false
                    keyHaptic(lbl)
                    invalidate()
                    when (lbl) {
                        "teclas" -> armLongPress(ViewConfiguration.getLongPressTimeout().toLong()) {
                            keyHaptic("enter")
                            openLauncherKeyboardAction(TeclasKeyboardActions.SWITCH_TO_WIDGET_MODE)
                        }
                        "enter", "space" -> {
                            val total = (ViewConfiguration.getLongPressTimeout() * 1.25).toLong()
                            startAgenticHapticRamp(total)
                            armLongPress(total) {
                                agenticConfirmHaptic()
                                if (lbl == "space") runGeminiCompose() else runAgenticCommand()
                            }
                        }
                        "123", "abc" -> armLongPress(ViewConfiguration.getLongPressTimeout().toLong()) {
                            keyHaptic("enter"); openImeSettings()
                        }
                        "back" -> startDeleteRepeat()
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val lbl = pressedLabel ?: return true
                    if ((lbl == "teclas" || lbl == "enter" || lbl == "space" || lbl == "123" || lbl == "abc") &&
                        (abs(event.rawX - downRawX) > touchSlopPx || abs(event.rawY - downRawY) > touchSlopPx)) {
                        cancelKeyLongPress()
                    }
                    // Space-swipe cursor control: drag left/right on the space bar to move the caret.
                    if (lbl == "space") {
                        val step = dp(7).toFloat()
                        var delta = event.rawX - spaceCursorLastX
                        while (abs(delta) >= step) {
                            moveTextCursor(delta > 0)
                            spaceCursorMoved = true
                            spaceCursorLastX += if (delta > 0) step else -step
                            delta = event.rawX - spaceCursorLastX
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val lbl = pressedLabel
                    pressedLabel = null
                    invalidate()
                    seemeReleaseHaptic(this)
                    cancelKeyLongPress()
                    if (longPressFired) { longPressFired = false; return true }
                    if (lbl == "space" && spaceCursorMoved) { spaceCursorMoved = false; return true }
                    if (lbl == "back") {
                        val repeated = deleteRepeatFired
                        stopDeleteRepeat(clearFired = true)
                        if (repeated) return true
                    }
                    if (lbl != null) {
                        handleKey(resolveTapKey(lbl, event.rawX, event.rawY))
                        armFrameSample()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressedLabel = null
                    invalidate()
                    cancelKeyLongPress()
                    longPressFired = false
                    stopDeleteRepeat(clearFired = true)
                    return true
                }
            }
            return false
        }
    }

    /**
     * Flip between letters and symbols by swapping ONLY the key rows inside the live deck. The old
     * path rebuilt the entire input view (strip, panels, cached backgrounds, a setInputView window
     * pass) on every 123/abc tap, which made the mode switch itself feel slow.
     */
    private fun setSymbolsMode(on: Boolean) {
        if (symbolsMode == on) return
        symbolsMode = on
        rebuildKeyRows()
    }

    /** Swap the on-screen key rows to match the current [symbolsMode], in place (no window pass). */
    private fun rebuildKeyRows() {
        val deck = deckView ?: run { rebuildDeck(); return }
        keyPreview.dismiss()
        // Canvas path: the grid is ONE persistent view — swap the rows by re-laying-out its cells and
        // repainting the buffer in place. The old path detached the canvas and constructed a fresh one
        // (new bitmap, full layout pass) on every 123/abc tap, re-paying most of the open cost.
        canvasKeyboardView?.takeIf { it.parent === deck }?.let { cv ->
            cv.rebuild()
            return
        }
        while (deck.childCount > chromeChildCount) deck.removeViewAt(deck.childCount - 1)
        keyViews.clear()
        addKeyRows(deck)
        deck.post { captureKeyBounds() }
    }

    /** Numeric/phone/date fields should open straight to the digits+symbols layout (Gboard behavior). */
    private fun desiredSymbolsModeFor(info: EditorInfo?): Boolean {
        val cls = (info?.inputType ?: return false) and android.text.InputType.TYPE_MASK_CLASS
        return cls == android.text.InputType.TYPE_CLASS_NUMBER ||
            cls == android.text.InputType.TYPE_CLASS_PHONE ||
            cls == android.text.InputType.TYPE_CLASS_DATETIME
    }

    private var lastBuiltTheme: String? = null
    private var lastBuiltMode: NeuMode? = null

    private fun rebuildDeck() {
        lastBuiltTheme = keyboardTheme()
        lastBuiltMode = selectedNeuTokens().mode
        hideAttachPicker()
        hideShareCard()
        setInputView(buildInputView())
    }

    // On focus, rebuild the whole deck (fresh cached backgrounds) only if the theme changed;
    // otherwise a light text-only chrome refresh — keeps the cached key backgrounds valid.
    private fun refreshChromeOrRebuild() {
        if (keyboardTheme() != lastBuiltTheme || selectedNeuTokens().mode != lastBuiltMode) rebuildDeck() else refreshKeyboardChrome()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (themeMode() == THEME_MODE_SYSTEM && selectedNeuTokens().mode != lastBuiltMode) {
            rebuildDeck()
        }
    }

    private fun keyRow(labels: List<String>, rowIndex: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            // Keys are centered, not baseline-aligned; skipping the baseline pass trims the measure
            // cost of every key row — paid at keyboard-open (buildKeyboard) and on each layout swap.
            isBaselineAligned = false
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
                        marginStart = dp(2)
                    })
                } else if (label == "123") {
                    addView(keyView(label), LinearLayout.LayoutParams(themedGoKeySize(), themedGoKeySize()).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        marginEnd = dp(2)
                    })
                } else {
                    addView(keyView(label), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, keyWeight(label)))
                }
            }
        }
    }

    private fun keyView(label: String): TextView {
        val isLetter = label.length == 1 && label[0].isLetter() && !symbolsMode
        val useBrushedOpticalKey = keyboardTheme() == KEYBOARD_THEME_BRUSHED && !isLetter && label != "teclas"
        return (if (isLetter) com.fran.teclas.keyboard.DynamicFlickKeyView(this) else if (useBrushedOpticalKey) com.fran.teclas.keyboard.OpticalKeyTextView(this) else TextView(this)).apply {
            tag = label
            text = if (keyboardTheme() == KEYBOARD_THEME_BRUSHED && label == "teclas") "" else if (isLetter) visualLabel(label) else keyDisplayText(label)
            if (keyboardTheme() == KEYBOARD_THEME_BRUSHED && label == "teclas") {
                contentDescription = "Undocked, tap to dock"
            }
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            if (keyboardTheme() == KEYBOARD_THEME_BRUSHED && this is com.fran.teclas.keyboard.OpticalKeyTextView) {
                opticalTextOffsetY = dp(
                    when (label) {
                        "enter" -> -2
                        "shift" -> -12
                        "back", "space" -> -16
                        "123", "abc", "." -> -5
                        else -> 0
                    }
                ).toFloat()
                opticalTextOffsetX = if (label == "back") -dp(4).toFloat() else 0f
            }
            textSize = keyTextSize(label)
            typeface = KeyboardThemeDrawables.typeface(keyboardVisualTheme(), label) ?: if (keyboardTheme() == KEYBOARD_THEME_SEEME || keyboardTheme() == KEYBOARD_THEME_BRUSHED) {
                Typeface.create(Typeface.MONOSPACE, if (label == "enter") Typeface.BOLD else Typeface.NORMAL)
            } else if (label == "enter") Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textColor(label))
            if (isLetter && this is com.fran.teclas.keyboard.DynamicFlickKeyView) {
                setKeyFaceInsets(keyHorizontalInset(), keyVerticalInset())
                if (keyboardTheme() == KEYBOARD_THEME_BRUSHED) {
                    setLabelPlacement(
                        labelBias = 0.28f,
                        symbolBias = 0.04f,
                        labelMaxScale = 0.52f,
                        symbolScale = 0.16f,
                        extraBottomInsetPx = 0,
                        engravedSymbols = true
                    )
                }
                val sym = com.fran.teclas.keyboard.KeyboardSymbols.keyUp[label.lowercase(Locale.US)]
                setSymbolHint(sym, symbolHintColor())
                if (keyboardTheme() == KEYBOARD_THEME_BRUSHED) {
                    val brushedLabel = if (capsLock) label.uppercase(Locale.US) else label.lowercase(Locale.US)
                    setDrawnPrimaryLabel(brushedLabel, textColor(label), keyTextSize(label) * resources.displayMetrics.scaledDensity, typeface)
                } else {
                    setDrawnPrimaryLabel(
                        visualLabel(label),
                        textColor(label),
                        keyTextSize(label) * resources.displayMetrics.scaledDensity,
                        typeface
                    )
                }
            }
            background = visualKeyBackground(label, pressed = false)
            isClickable = true
            // No per-key hardware layer: 30+ layers cost texture memory and force a texture
            // re-upload on every press/label change. Default display-list rendering redraws only
            // the pressed key's small dirty rect, which is cheaper for views this size.
            setOnTouchListener(ImeKeyTouchListener(label))
            keyViews[label] = this
        }
    }

    private val touchSlopPx by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private inner class ImeKeyTouchListener(private val label: String) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var teclasLongPressRunnable: Runnable? = null
        private var teclasLongPressFired = false
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
                    teclasLongPressFired = false
                    perfWorstMs = 0L   // fresh keystroke: start tracking its worst step
                    perfLine("── key '$label' ──")
                    v.background = pressedBg
                    keyHaptic(label)
                    // Instant press nudge — no ViewPropertyAnimator. Starting two 35ms animators on
                    // every key down/up flooded the main thread's animation phase during fast typing.
                    v.translationY = dp(4).toFloat()
                    if (label == "teclas") {
                        val runnable = Runnable {
                            teclasLongPressFired = true
                            keyHaptic("enter")
                            openLauncherKeyboardAction(TeclasKeyboardActions.SWITCH_TO_WIDGET_MODE)
                        }
                        teclasLongPressRunnable = runnable
                        handler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    if (label == "enter" || label == "space") {
                        // Two hold triggers, one ramp. Go/enter runs the typed line as an agentic
                        // command; space asks Gemini to keep writing. Taps are unaffected.
                        val holdLabel = label
                        val total = (ViewConfiguration.getLongPressTimeout() * 1.25).toLong()
                        startAgenticHapticRamp(total)
                        val runnable = Runnable {
                            teclasLongPressFired = true
                            agenticConfirmHaptic()
                            if (holdLabel == "space") runGeminiCompose() else runAgenticCommand()
                        }
                        teclasLongPressRunnable = runnable
                        handler.postDelayed(runnable, total)
                    }
                    if (label == "123" || label == "abc") {
                        // Long-press the mode key to open Keyboard Settings; a tap still flips
                        // symbols/letters.
                        val runnable = Runnable {
                            teclasLongPressFired = true
                            keyHaptic("enter")
                            openImeSettings()
                        }
                        teclasLongPressRunnable = runnable
                        handler.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    if (label == "back") startDeleteRepeat()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if ((label == "teclas" || label == "enter" || label == "space" || label == "123" || label == "abc") &&
                        (abs(event.rawX - downRawX) > touchSlopPx || abs(event.rawY - downRawY) > touchSlopPx)) {
                        cancelTeclasLongPress()
                    }
                    // Space-swipe cursor control (parity with the launcher): drag left/right on the
                    // space bar to move the caret a character at a time via DPAD key events.
                    if (label == "space") {
                        val step = dp(7).toFloat()
                        var delta = event.rawX - spaceCursorLastX
                        while (abs(delta) >= step) {
                            moveTextCursor(delta > 0)
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
                    seemeReleaseHaptic(v)
                    v.translationY = 0f
                    cancelTeclasLongPress()
                    if (teclasLongPressFired) {
                        teclasLongPressFired = false
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
                    ptime("handleKey('$label')") { handleKey(resolveTapKey(label, event.rawX, event.rawY)) }
                    armFrameSample()   // watch the frames that render this keystroke, then release
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.background = idleBg
                    v.translationY = 0f
                    cancelTeclasLongPress()
                    teclasLongPressFired = false
                    if (label == "back") stopDeleteRepeat(clearFired = true)
                    return true
                }
            }
            return true
        }

        private fun cancelTeclasLongPress() {
            teclasLongPressRunnable?.let { handler.removeCallbacks(it) }
            teclasLongPressRunnable = null
            stopAgenticHapticRamp()
        }
    }

    private fun handleKey(label: String) {
        pendingCommand = null
        pendingActions = emptyList()
        glideJustCommitted = false   // leaving the post-swipe "tap an alternative" window
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
                refreshKeyboardChrome(rebuildBackgrounds = keyboardTheme() == KEYBOARD_THEME_BRUSHED)
            }
            "back" -> {
                // Undo autocorrect via the shared core (restore original + remember rejection).
                if (input != null && autocorrect.undoOnBackspace()) { onTextChanged(); return }
                if (input == null) {
                    VivoDockedExperiment.injectInput("⌫")
                } else if (input.deleteSurroundingText(1, 0)) {
                    shadowOnDeleteBefore(1)
                } else {
                    keyEvent(KeyEvent.KEYCODE_DEL)
                }
                onTextChanged()
            }
            "enter" -> {
                // Proofread mode: fix clearly-misspelled words before the message is sent.
                if (proofreadEnabled()) proofreadBeforeCursor()
                keyEvent(KeyEvent.KEYCODE_ENTER)
            }
            "space" -> {
                if (maybeRunAiCommand()) return
                // Double-space → ". " via the shared core.
                val now = System.currentTimeMillis()
                if (now - lastSpaceMs < 500L && applyDoubleSpacePeriod()) {
                    lastSpaceMs = 0L; updateAutoCap(); return
                }
                lastSpaceMs = now
                // One batch edit around correct+space: the editor sees a single atomic change
                // (one selection update, one undo step) instead of delete/commit/commit.
                input?.beginBatchEdit()
                try {
                    if (proofreadEnabled()) {
                        // Never auto-change the word mid-sentence; just learn it (so your slang/abbrev
                        // stops being flagged) and let it stand. Fixing happens only on send.
                        userDict.noteTyped(currentWord())
                    } else if (autocorrectEnabled()) {
                        // Fast path: the prediction thread already decided this word's correction
                        // while it was being typed — apply the cached answer with zero dictionary
                        // work inside the keystroke. Falls back to the synchronous search only when
                        // the cache doesn't match (e.g. space immediately after a cursor jump).
                        val word = currentWord()
                        val pre = pendingCorrection
                        if (pre != null && pre.first == word) {
                            pre.second?.let { autocorrect.applyCorrection(word, it) }
                        } else {
                            autocorrect.correctBeforeCommit()
                        }
                        pendingCorrection = null
                    }
                    commitValue(" ")
                } finally {
                    input?.endBatchEdit()
                }
                learnAndPredictAfterSpace()
                updateAutoCap()
            }
            "teclas" -> openImeSettings()
            "123" -> setSymbolsMode(true)
            "abc" -> setSymbolsMode(false)
            "." -> commitValue(".")
            else -> {
                autocorrect.clearPending()
                plog("letter: commitValue ->")
                commitValue(if ((shifted || capsLock) && label.length == 1) label.uppercase() else label)
                plog("letter: committed")
                if (shifted && !capsLock && label.length == 1 && label[0].isLetter()) {
                    shifted = false
                    refreshKeyboardChrome()
                    plog("letter: refreshKeyboardChrome done")
                }
                onTextChanged()
                plog("letter: onTextChanged done")
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

    // Open the IME's OWN settings (not the launcher's teclas settings) — the keyboard used in other
    // apps should configure itself, not jump into the launcher.
    private fun openImeSettings() {
        requestHideSelf(0)
        runCatching { hideWindow() }
        startActivity(Intent(this, ImeSettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun commitValue(value: String) {
        val input = currentInputConnection
        if (input != null) { input.commitText(value, 1); shadowOnCommit(value) }
        else VivoDockedExperiment.injectInput(value)
    }

    private fun keyEvent(code: Int) {
        val input = currentInputConnection
        if (input != null) {
            input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        } else {
            VivoDockedExperiment.injectInput(if (code == KeyEvent.KEYCODE_ENTER) "⏎" else "")
        }
        // Raw key events move/edit the caret in ways we don't model (ENTER, DEL, DPAD) — drop the
        // mirror; onUpdateSelection reseeds it.
        invalidateShadow()
    }

    private fun keyHaptic(label: String) {
        if (!imePrefs().getBoolean(HAPTICS_PREF, true)) return
        // Tuned per-label composition primitives (parity with the launcher keyboard) instead of the
        // coarse system constants.
        haptics().tap(label)
    }

    private fun hapticsOn() = imePrefs().getBoolean(HAPTICS_PREF, true)

    /** Step the text cursor one character left/right. Used by two-finger trackpad panning; DPAD key
     *  events respect each editor's own cursor bounds and selection semantics. */
    private fun moveTextCursor(right: Boolean) {
        sendDownUpKeyEvents(
            if (right) android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            else android.view.KeyEvent.KEYCODE_DPAD_LEFT
        )
        invalidateShadow()   // caret moved outside our model; onUpdateSelection reseeds
    }

    /** The keyboard's haptic engine with the user's keyboard-only intensity applied (0–100 → 0f–1f).
     *  Read live so the Settings slider takes effect on the next keystroke without a restart. */
    private fun haptics(): com.fran.teclas.keyboard.CustomHapticEngine {
        hapticEngine.intensity = imePrefs().getInt(HAPTIC_LEVEL_PREF, 100).coerceIn(0, 100) / 100f
        return hapticEngine
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
        // Canvas keyboard owns its bounds (there are no per-key views to walk); have it republish so
        // the deck's "recapture if the window moved" path on touch-down keeps working identically.
        canvasKeyboardView?.let { it.republishBounds(); return }
        val fresh = linkedMapOf<String, Rect>()
        val loc = IntArray(2)
        keyViews.forEach { (label, view) ->
            if (view.width <= 0 || view.height <= 0) return@forEach
            view.getLocationOnScreen(loc)
            fresh[label] = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        }
        // This runs after every layout pass, and layout passes happen per keystroke (the strip's
        // setText). When nothing moved, feeding identical bounds downstream made the glide
        // classifier rebuild its full-dictionary pruner on the main thread on every key press —
        // steadily worsening jank/GC as you typed. Skip all of it when the keys haven't moved.
        if (fresh == keyBounds) return
        keyBounds.clear()
        keyBounds.putAll(fresh)
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

    private val tapResolver by lazy { com.fran.teclas.keyboard.TapResolver(spatialScorer) }

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

    private fun mergePersonalFreqs(base: Map<String, Float>, personal: Map<String, Float>): Map<String, Float> {
        if (personal.isEmpty()) return base
        val out = HashMap<String, Float>(base)
        for ((w, b) in personal) out[w] = minOf(1f, (out[w] ?: 0f) + b)
        return out
    }

    @Volatile private var glideLoading = false
    private fun ensureGlideClassifier() {
        if (glideClassifier != null || glideLoading) return
        glideLoading = true
        Thread {
            // STEP 1 — get the statistical classifier live with the fewest possible dependencies.
            // Everything that could fail (personal-freq merge, neural ONNX load) is deferred to steps
            // 2/3 so a failure there can never leave glide typing dead (the bug that made swipes emit
            // raw key-path gibberish). Falls back to the plain union dictionary if the adaptive load
            // throws for any reason.
            var baseFreqs: Map<String, Float> = emptyMap()
            var glideWords: List<String> = emptyList()
            try {
                val (words, freqs, primaryFreqs, extendedFreqs, latent) = loadGlideDictionary()
                baseFreqs = freqs
                glideWords = words
                val clf = StatisticalGlideTypingClassifier()
                clf.setWordData(words, freqs)
                // Engines index the dictionary in their constructor — build them here (off main),
                // publish on main.
                val enginePrimary = PredictionEngine(primaryFreqs)
                val engineExtended = PredictionEngine(extendedFreqs)
                handler.post {
                    glideClassifier = clf
                    glideFreqs = freqs
                    predictionEnginePrimary = enginePrimary
                    predictionEngineExtended = engineExtended
                    hasLatentLanguages = latent
                    latentLanguageActive = false
                    predictionEngine = enginePrimary
                    updateGlideLayout()
                }
            } catch (t: Throwable) {
                android.util.Log.e("TeclasGlide", "classifier load FAILED", t)
                glideLoading = false
                return@Thread   // let a later onStartInputView retry
            }

            // STEP 2 — fold in personal glide frequencies (best-effort; never blocks glide).
            runCatching {
                val personal = glideLearning.personalFrequencyBoost()
                if (personal.isNotEmpty()) {
                    val merged = mergePersonalFreqs(baseFreqs, personal)
                    glideClassifier?.setWordData(glideWords, merged)
                    handler.post { glideFreqs = merged }
                }
            }.onFailure { android.util.Log.w("TeclasGlide", "personal freq merge skipped: ${it.message}") }

            // STEP 3 — load the neural model (heavy); it joins the hybrid when ready (best-effort).
            runCatching {
                val neural = com.fran.teclas.keyboard.neural.NeuralGlideEngine(this).apply {
                    setDictionary(glideWords, glideFreqs)
                    load()
                }
                handler.post {
                    neuralGlide = neural
                    hybridDecoder = com.fran.teclas.keyboard.neural.HybridGlideDecoder(neural)
                }
            }.onFailure { android.util.Log.w("NeuralSwipe", "neural load skipped: ${it.message}") }

            glideLoading = false
        }.start()
    }

    /** (glideWords, glideFreqs, primaryFreqs, extendedFreqs, hasLatent). Uses the adaptive loader,
     *  falling back to the plain union dictionary if it throws — the IME must always get a dictionary. */
    private fun loadGlideDictionary(): Quintuple {
        return try {
            val a = com.fran.teclas.keyboard.DictionaryLoader.loadAdaptive(this)
            Quintuple(a.extendedWords, a.extendedFreqs, a.primaryFreqs, a.extendedFreqs, a.latentLangs.isNotEmpty())
        } catch (t: Throwable) {
            android.util.Log.w("TeclasGlide", "loadAdaptive failed, using union dict: ${t.message}")
            val l = com.fran.teclas.keyboard.DictionaryLoader.load(this)
            Quintuple(l.words, l.freqs, l.freqs, l.freqs, false)
        }
    }
    private data class Quintuple(
        val extendedWords: List<String>, val extendedFreqs: Map<String, Float>,
        val primaryFreqs: Map<String, Float>, val extFreqs: Map<String, Float>, val latent: Boolean
    )

    // Glide word placement/rerank/context now live in the shared GlideCore.

    // ── Prediction / autocorrect / learning — parity with the launcher keyboard ──
    private fun imePrefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun updateAutoCap() {
        if (capsLock) return
        val cap = shouldAutoCapitalize()
        if (shifted != cap) { shifted = cap; refreshKeyboardChrome() }
    }

    private fun currentWord(): String =
        shadowBeforeCursor(48).takeLastWhile { it.isLetter() }

    private fun wordsBeforeCursor(): List<String> {
        val before = shadowBeforeCursor(96)
        return WORD_RE.findAll(before).map { it.value }.toList()
    }

    private fun previousWord(): String = previousWordOf(shadowBeforeCursor(96))

    /** [previousWord] over a text snapshot — used off the main thread by the prediction pipeline. */
    private fun previousWordOf(before: String): String {
        val tokens = WORD_RE.findAll(before).map { it.value }.toList()
        val endsLetter = before.isNotEmpty() && before.last().isLetter()
        return if (endsLetter) tokens.getOrElse(tokens.size - 2) { "" } else tokens.lastOrNull().orEmpty()
    }

    private fun onTextChanged() {
        // Typing resumes → the go-button starter hints are stale; drop them.
        agenticStarters = emptyList()
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
        // Third-party apps only. Typing into the launcher's own editor is command/search text, not
        // prose — no AI next-word suggestions there.
        if (isLauncherEditorActive()) return
        if (!ProManager.isUnlocked(this) || !GeminiClient.configured(imePrefs())) return
        val ctx = shadowBeforeCursor(80).trim()
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
        chipsDebounce?.let { handler.removeCallbacks(it) }
        // No strip to populate (view not built / not shown) → skip all per-keystroke prediction work.
        if (suggestionStrip == null) return
        // Base prediction strip. The debounce only coalesces bursts; the dictionary work itself runs
        // on the prediction thread (never the UI thread), off a text snapshot taken from the shadow
        // mirror on main. While it's at it, the same pass precomputes the space-bar autocorrect
        // decision for the in-progress word, so pressing space applies a cached answer instead of
        // paying a dictionary search inside the keystroke — Gboard's decode-while-typing pipeline.
        val r = Runnable {
            // Re-evaluate the active language every keystroke (not only on space) so mid-word
            // suggestions/corrections use the right dictionary while typing a secondary language.
            updateActiveLanguage()
            val before = shadowBeforeCursor(96)
            val fieldEmpty = before.isEmpty() && shadowAfterCursor(1).isEmpty()
            val gen = ++predictGeneration
            // runCatching: a debounce that fires after onDestroy would hit a shut-down executor.
            runCatching { predictExecutor.execute {
                // A newer keystroke already superseded this one — skip the dictionary work entirely
                // so a fast burst can't back the worker thread up with stale computations.
                if (gen != predictGeneration) return@execute
                val t0 = System.nanoTime()
                val word = before.takeLast(48).takeLastWhile { it.isLetter() }
                val prev = previousWordOf(before)
                // Above ~18 chars it's not a real word (URL, gibberish, run-on); an edit-distance
                // scan over the dictionary is pointless AND the most expensive case (this is the
                // predict.compute 60–90ms spikes). Skip prediction/correction for it.
                val tooLong = word.length > 18
                val base = if (tooLong) emptyList() else predictionCore.computeSuggestions(word, prev)
                val correction = if (!tooLong && word.length >= 2)
                    autocorrect.computeCorrection(word, ngramRepo.cachedNextWords(prev), prev) else null
                val computeMs = (System.nanoTime() - t0) / 1_000_000
                handler.post {
                    if (gen != predictGeneration) return@post   // user typed past this answer
                    perfReport("predict.compute(bg)", computeMs)
                    pendingCorrection = word to correction
                    suggestions = if (base.isEmpty()) {
                        // IME extra: notification quick-replies when the field is empty.
                        if (fieldEmpty) NotificationReplyContext.quickReplies(imePrefs(), System.currentTimeMillis()) else emptyList()
                    } else base
                    updateStrip()
                    plog("predict updateStrip done")
                    // Correction/spellcheck orchestration: the on-device engine already ran (correction
                    // + suggestions). The system spellchecker is a cross-process FALLBACK, not a
                    // parallel path — only reach for it when we came up empty-handed on a real word, so
                    // it stops costing an IPC + an extra strip relayout on every single word.
                    maybeSystemSpellcheck(word, base.isNotEmpty() || correction != null)
                }
            } }
        }
        suggestDebounce = r
        // 90ms: rank() is now budget-capped to single-digit ms, so prediction no longer saturates the
        // CPU and we can keep a snappy cadence — which also means the space-bar autocorrect precompute
        // is ready in time (no synchronous main-thread fallback hitch on space).
        handler.postDelayed(r, 90L)
        // Emoji chips are heavier and less time-critical — run them on a slower cadence (180ms) so
        // fast typing doesn't fire them every keystroke. (System spellcheck used to ride along here on
        // every word; it's now a fallback triggered from the prediction result — see maybeSystemSpellcheck.)
        val cs = Runnable {
            val word = currentWord()
            perfIpcReads = 0
            ptime("computeSmartChips") { computeSmartChips(word) }
            updateStrip()
        }
        chipsDebounce = cs
        handler.postDelayed(cs, 180L)
    }

    /**
     * System spellcheck as a fallback only. The on-device engine (correction + suggestions) has already
     * run for this word; [hadLocalAnswer] is true when it produced a correction or any suggestion. In
     * that common case we skip the cross-process spellchecker entirely — it's redundant and its async
     * result would trigger a second strip relayout for the same word. We reach for it solely when the
     * local dictionary is empty-handed on a real word (a hard misspelling / proper noun it can't
     * reach); the result merges back in via onGetSuggestions, which drops it if the user has typed on.
     */
    private fun maybeSystemSpellcheck(word: String, hadLocalAnswer: Boolean) {
        if (hadLocalAnswer || word.length < 4) return
        val sc = spellChecker ?: return
        lastSpellWord = word
        runCatching { sc.getSuggestions(android.view.textservice.TextInfo(word), 5) }
    }

    // Build emoji + smart-symbol chips for the current word/context. Emoji lead; symbols fill in.
    private fun computeSmartChips(word: String) {
        val chips = ArrayList<Triple<String, String, Boolean>>()
        emojiTriggerWord = ""
        if (word.length >= 2) {
            emojiTriggerWord = word.lowercase()
            SmartChips.emojiFor(imePrefs(), emojiTriggerWord).take(4).forEach { chips.add(Triple(it, it, true)) }
        }
        val before = shadowBeforeCursor(24)
        SmartChips.symbolsFor(before, word).forEach { s -> if (chips.none { it.first == s }) chips.add(Triple(s, s, false)) }
        smartChips = chips.take(5)
    }

    // Insert an emoji/symbol chip. Emoji get a leading space when needed and record the pick so the
    // ranking learns; symbols insert as-is.
    private fun insertSmartChip(display: String, insert: String, emoji: Boolean) {
        val ic = currentInputConnection ?: return
        keyHaptic("space")
        if (emoji) {
            val trigger = emojiTriggerWord
            if (trigger.isNotEmpty()) {
                val before = ic.getTextBeforeCursor(trigger.length, 0)?.toString().orEmpty()
                if (before.equals(trigger, ignoreCase = true)) {
                    ic.deleteSurroundingText(trigger.length, 0)
                }
            }
            val prev = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
            val sep = if (prev.isNotEmpty() && prev != " ") " " else ""
            ic.commitText(sep + insert, 1)
            if (trigger.isNotEmpty()) SmartChips.recordEmojiPick(imePrefs(), trigger, insert)
        } else {
            ic.commitText(insert, 1)
        }
        smartChips = emptyList()
        invalidateShadow()   // chip insert edits via raw IC; reseed before the next read
        onTextChanged()
    }

    private fun autocorrectEnabled() = imePrefs().getBoolean(IME_AUTOCORRECT_PREF, true)

    // Proofread mode (opt-in): don't auto-change words as you type — instead learn your words and fix
    // clearly-misspelled ones only when you send. Off by default, so normal typing is untouched.
    private fun proofreadEnabled() = imePrefs().getBoolean(IME_PROOFREAD_PREF, false)
    private val userDict by lazy { com.fran.teclas.keyboard.UserDictionary(imePrefs()) }

    /** Fix clearly-misspelled words in the text before the cursor (used on send in proofread mode).
     *  Leaves dictionary words, your own words, and anything without a confident correction alone. */
    private fun proofreadBeforeCursor() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1000, 0)?.toString() ?: return
        if (before.isBlank()) return
        val fixed = com.fran.teclas.keyboard.Proofreader.fix(
            before,
            isKnownWord = { predictionEngine.isDictWord(it) || userDict.contains(it) },
            correct = { predictionEngine.bestCorrection(it) }
        )
        if (fixed == before) return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(before.length, 0)
        ic.commitText(fixed, 1)
        ic.endBatchEdit()
    }

    // ── On-device Gemini Nano proofreading (real grammar/spelling, shown as a tap-to-apply preview) ──
    private fun checkNanoProofreadSupport() {
        glideScope.launch { runCatching { nanoSupported = nanoProofread.isSupported() } }
    }

    /** Proofread the whole line before the cursor with Gemini Nano; surface the fix as a preview. */
    private fun runNanoProofread() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1000, 0)?.toString().orEmpty()
        if (before.trim().length < 4) { flashStatus("Type a sentence to proofread"); return }
        pendingProofread = null
        agenticStatus = "Proofreading…"; updateStrip()
        glideScope.launch {
            when (val res = nanoProofread.proofread(before)) {
                is com.fran.teclas.keyboard.NanoProofreadEngine.Result.Corrected -> {
                    pendingProofread = res.text; agenticStatus = null; updateStrip()
                }
                com.fran.teclas.keyboard.NanoProofreadEngine.Result.Unchanged -> flashStatus("Looks good ✓")
                com.fran.teclas.keyboard.NanoProofreadEngine.Result.Downloading -> flashStatus("Preparing proofreader…", 2500)
                com.fran.teclas.keyboard.NanoProofreadEngine.Result.Unsupported -> { nanoSupported = false; agenticStatus = null; proofreadBeforeCursor(); updateStrip() }
                com.fran.teclas.keyboard.NanoProofreadEngine.Result.Error -> { agenticStatus = null; updateStrip() }
            }
        }
    }

    /** Replace the line before the cursor with the accepted Nano correction. */
    private fun applyProofread() {
        val corrected = pendingProofread ?: return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1000, 0)?.toString().orEmpty()
        keyHaptic("enter")
        ic.beginBatchEdit(); ic.deleteSurroundingText(before.length, 0); ic.commitText(corrected, 1); ic.endBatchEdit()
        pendingProofread = null; agenticStatus = null; onTextChanged()
    }

    private fun flashStatus(msg: String, delay: Long = 1200) {
        agenticStatus = msg; updateStrip()
        handler.postDelayed({ if (agenticStatus == msg && pendingProofread == null) { agenticStatus = null; updateStrip() } }, delay)
    }

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
        val before = shadowBeforeCursor(96).lowercase()
        val words = before.split(NON_LETTER_RE).filter { it.length >= 2 }.takeLast(4)
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

    /**
     * Bias glide candidates toward the language the user is actually writing. The glide shape-matcher
     * scores across the full multi-language union, so a similarly-shaped secondary-language word with
     * a higher union frequency can beat the intended primary-language word (e.g. swiping English but
     * getting Spanish). While the secondary language isn't active (the hysteresis in
     * [updateActiveLanguage]), stably move primary-dictionary words ahead — never dropping any, so a
     * word that only exists in the other language still works.
     */
    private fun languagePreferredOrder(words: List<String>): List<String> {
        if (words.size < 2 || latentLanguageActive || !hasLatentLanguages) return words
        val primary = words.filter { predictionEnginePrimary.isDictWord(it) }
        return if (primary.isEmpty() || primary.size == words.size) words
        else primary + words.filterNot { predictionEnginePrimary.isDictWord(it) }
    }

    /** Learn the just-committed word against its predecessor (n-gram) and warm next-word predictions,
     *  WITHOUT touching the suggestion strip — so a glide can learn while still showing alternatives. */
    private fun recordCommittedWordContext() {
        updateActiveLanguage()
        val tokens = wordsBeforeCursor()
        if (tokens.size >= 2) ngramRepo.recordWord(tokens[tokens.size - 1], tokens[tokens.size - 2])
        val last = tokens.lastOrNull().orEmpty()
        if (last.isNotEmpty()) {
            ngramRepo.prefetchNextWords(last)
            // Unified-ranker learning: the user's own committed words rise in ranking over time.
            personalFreq.noteCommitted(last)
        }
    }

    private fun learnAndPredictAfterSpace() {
        recordCommittedWordContext()
        val last = wordsBeforeCursor().lastOrNull().orEmpty()
        suggestions = if (last.isNotEmpty()) ngramRepo.cachedNextWords(last).take(3) else emptyList()
        glideJustCommitted = false   // these are next-word predictions: tapping one appends
        updateStrip()
    }

    // Weight-adaptation telemetry: which rank the user picked, and — when they overrode our #1 —
    // the signal vectors of their pick vs ours, so AdaptiveWeights can learn which signals to
    // trust more for this user. Runs on the prediction thread; the tap itself pays nothing.
    private fun recordSuggestionPick(picked: String, typedBefore: String, shown: List<String>) {
        val rank = shown.indexOf(picked)
        if (rank < 0) return
        val prev = predictionCore.previousWord()
        runCatching { predictExecutor.execute {
            val overrode = rank > 0 && typedBefore.length >= 2
            val pickedSig = if (overrode) unifiedRanker.explain(typedBefore, picked, prevWord = prev) else null
            val topSig = if (overrode) shown.firstOrNull()?.let { unifiedRanker.explain(typedBefore, it, prevWord = prev) } else null
            adaptiveWeights.onSuggestionPicked(rank.coerceAtMost(2), pickedSig, topSig)
        } }
    }

    private fun acceptSuggestion(word: String) {
        if (currentInputConnection == null) return
        val before = predictionCore.currentWord()
        recordSuggestionPick(word, before, suggestions.toList())
        when {
            // Mid-word: the strip shows completions/corrections of the partial word → replace it.
            before.isNotEmpty() -> predictionCore.replaceCurrentWord(word)
            // Just swiped: the strip shows alternate decodings → replace the committed word so a
            // mis-decode is fixed in place instead of the alternative being appended after it. This is
            // a correction: re-label that swipe with the RIGHT word for the next retrain.
            glideJustCommitted -> {
                if (lastGlidePath.size > 1) {
                    glideLearning.recordCorrection(word, lastGlidePath, lastGlideBounds, lastGlideWord)
                    lastGlidePath = emptyList()
                }
                predictionCore.replaceCommittedWord(word); recordCommittedWordContext(); updateStrip(); return
            }
            // Otherwise these are next-word predictions → append.
            else -> commitValue("$word ")
        }
        learnAndPredictAfterSpace()
    }

    private fun buildSuggestionStrip(): LinearLayout {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // We center children vertically ourselves, so the default baseline-alignment measure pass
            // buys nothing and only costs an extra measure of every text child on each strip repaint.
            isBaselineAligned = false
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        suggestionStrip = strip
        updateStrip()
        return strip
    }

    // One well-background instance for the strip's lifetime; setBackground(same instance) is a
    // no-op in View, so repaints don't re-allocate or re-invalidate.
    private var stripWellCache: android.graphics.drawable.GradientDrawable? = null
    private fun stripWellBackground(): Drawable = stripWellCache ?: GradientDrawable().apply {
        setColor(0x14FFFFFF); cornerRadius = dp(10).toFloat()
    }.also { stripWellCache = it }

    // ── Typing-strip fast path ──────────────────────────────────────────────────────────────────
    // The steady-state strip (word suggestions + emoji/symbol chips + Fix/Polish) repaints on every
    // keystroke. Tearing down and re-inflating its views each time — the old removeAllViews +
    // fresh TextViews + fresh drawables — cost a measure/layout/alloc storm per key press. Instead
    // a fixed set of slot views is built ONCE and repaints are just setText/visibility flips
    // (LatinIME's recycled SuggestionStripView). Rare states (agentic status, command previews,
    // proofread, actions) still use the legacy rebuild path.
    private var typingRow: LinearLayout? = null
    private val stripSuggestionViews = ArrayList<TextView>(3)
    private val stripSuggestionDividers = ArrayList<View>(3)
    private val stripChipViews = ArrayList<TextView>(5)
    private val stripChipDividers = ArrayList<View>(5)
    private var stripFixView: TextView? = null
    private var stripFixDivider: View? = null
    private var stripPolishView: TextView? = null
    // Live data behind the slots; click listeners read these so they're bound once, not per repaint.
    private var stripShown: List<String> = emptyList()
    private var stripChipData: List<Triple<String, String, Boolean>> = emptyList()

    private fun stripDivider(): View = View(this).apply { setBackgroundColor(0x22FFFFFF) }
    private fun dividerParams() = LinearLayout.LayoutParams(dp(1), dp(18)).apply {
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun ensureTypingRow(): LinearLayout {
        typingRow?.let { return it }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Same as the strip container: we center vertically, so skip the baseline-alignment pass
            // that would otherwise re-measure all 3 suggestion slots + chips a second time per repaint.
            isBaselineAligned = false
        }
        stripFixView = TextView(this).apply {
            text = "⌁ Fix"; gravity = Gravity.CENTER; textSize = 14f
            setTextColor(0xFF33E1C4.toInt()); setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            setOnClickListener { keyHaptic("space"); runNanoProofread() }
            row.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        stripFixDivider = stripDivider().also { row.addView(it, dividerParams()) }
        repeat(3) { i ->
            stripSuggestionViews.add(TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                isClickable = true
                setOnClickListener {
                    stripShown.getOrNull(i)?.let { w -> keyHaptic("space"); acceptSuggestion(w) }
                }
                row.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            })
            stripSuggestionDividers.add(stripDivider().also { row.addView(it, dividerParams()) })
        }
        repeat(5) { i ->
            stripChipViews.add(TextView(this).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(8), 0, dp(8), 0)
                isClickable = true
                setOnClickListener {
                    stripChipData.getOrNull(i)?.let { (d, ins, e) -> insertSmartChip(d, ins, e) }
                }
                row.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
            })
            stripChipDividers.add(stripDivider().also { row.addView(it, dividerParams()) })
        }
        stripPolishView = TextView(this).apply {
            text = "✨"; gravity = Gravity.CENTER; textSize = 17f
            background = GradientDrawable().apply { setColor(0x338B5CF6); cornerRadius = dp(9).toFloat() }
            isClickable = true
            setOnClickListener { polishField() }
            row.addView(this, LinearLayout.LayoutParams(dp(46), LinearLayout.LayoutParams.MATCH_PARENT).apply { marginStart = dp(4) })
        }
        typingRow = row
        return row
    }

    /** Repaint the steady-state strip in place. All views are recycled; only text/visibility move. */
    private fun renderTypingStrip(
        shown: List<String>,
        chips: List<Triple<String, String, Boolean>>,
        showFix: Boolean,
        canPolish: Boolean
    ) {
        val strip = suggestionStrip ?: return
        val row = ensureTypingRow()
        if (row.parent !== strip) {
            (row.parent as? ViewGroup)?.removeView(row)
            strip.removeAllViews()
            strip.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        stripShown = shown
        stripChipData = chips
        val ink = stripInk()
        val hasTrailing = shown.isNotEmpty() || chips.isNotEmpty() || canPolish
        stripFixView?.visibility = if (showFix) View.VISIBLE else View.GONE
        stripFixDivider?.visibility = if (showFix && hasTrailing) View.VISIBLE else View.GONE
        for (i in 0 until 3) {
            val v = stripSuggestionViews[i]
            val d = stripSuggestionDividers[i]
            val w = shown.getOrNull(i)
            if (w == null) { v.visibility = View.GONE; d.visibility = View.GONE; continue }
            v.visibility = View.VISIBLE
            if (!v.text.contentEquals(w)) v.text = w
            if (v.currentTextColor != ink) v.setTextColor(ink)
            d.visibility = if (i < shown.lastIndex || canPolish || chips.isNotEmpty()) View.VISIBLE else View.GONE
        }
        for (i in 0 until 5) {
            val v = stripChipViews[i]
            val d = stripChipDividers[i]
            val chip = chips.getOrNull(i)
            if (chip == null) { v.visibility = View.GONE; d.visibility = View.GONE; continue }
            v.visibility = View.VISIBLE
            if (!v.text.contentEquals(chip.first)) v.text = chip.first
            val size = if (chip.third) 18f else 16f
            if (v.textSize != size * resources.displayMetrics.scaledDensity) v.textSize = size
            if (v.currentTextColor != ink) v.setTextColor(ink)
            d.visibility = if (i < chips.lastIndex || canPolish) View.VISIBLE else View.GONE
        }
        stripPolishView?.visibility = if (canPolish) View.VISIBLE else View.GONE
    }

    // ── Agentic panel ──────────────────────────────────────────────────────────
    // One elegant glass card for every agentic action. Idle -> GONE, so the resting keyboard is
    // untouched; a pending command or working status elevates into the card and hides the plain
    // suggestion strip. Each action gets its own accent spine (Teclas' colored-spine language).
    // Command / Files / Email render here as they land; typing suggestions stay in the strip.

    private fun buildAgenticPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Centered children, so skip the baseline measure pass (same as the strip/key rows).
            isBaselineAligned = false
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
    // dedicated row above the keys. Teclas never sees the credentials — the fill goes provider->field.

    private fun buildInlineAutofillRow(): HorizontalScrollView {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false }
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

    /** Suggestion-strip text color that actually reads on the current theme. The strip text was
     *  hardcoded near-white, which vanished on the light "brushed" theme — this makes it dark there. */
    private fun stripInk(): Int = if (agenticPanelLight()) 0xFF14161B.toInt() else 0xFFE9EDF2.toInt()

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

    // A follow-up chip: outlined (not filled) so it reads as a secondary next-step next to the Insert CTA.
    private fun agenticFollowUp(label: String, accent: Int, fill: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 11.8f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(accent)
        setPadding(dp(10), dp(7), dp(10), dp(7))
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        background = GradientDrawable().apply {
            setColor(fill); cornerRadius = dp(11).toFloat()
            setStroke(dp(1), (accent and 0x00FFFFFF) or 0x66000000)
        }
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
        agenticStarters = emptyList()
        updateStrip()
    }

    // ── Clipboard AI skills (Phase 2) ────────────────────────────────────────────
    private fun clipboardText(): String {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return ""
        val clip = cm.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
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
        // Fast idle path (the case on essentially every keystroke): nothing agentic is showing and
        // the panel is already hidden, so there is nothing to rebuild. Returning here avoids the
        // removeAllViews + color-alloc churn that ran ~3x per keystroke and accumulated as GC
        // pressure — the "gets slower the longer you type" effect.
        val idle = agenticHud == null && agenticStarters.isEmpty() &&
            agenticStatus == null && pendingCommand == null
        if (idle) {
            if (panel.visibility != View.GONE) {
                panel.removeAllViews()
                panel.visibility = View.GONE
                panel.background = null
                suggestionStrip?.visibility = View.VISIBLE
            }
            return
        }
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
                    currentInputConnection?.commitText(insert, 1); invalidateShadow(); onTextChanged(); updateStrip()
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            // Follow-up chips: outlined mint pills that take the result somewhere next.
            hud.actions.take(2).forEach { action ->
                panel.addView(agenticFollowUp(action.label, mint, tileBg) {
                    keyHaptic("enter"); action.run()
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
            }
            panel.addView(TextView(this).apply {
                text = "✕"; gravity = Gravity.CENTER; textSize = 13f; setTextColor(kickerInk)
                setPadding(dp(10), dp(8), dp(6), dp(8)); isClickable = true
                setOnClickListener { keyHaptic("back"); agenticHud = null; updateStrip() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
            if (wasHidden) animateAgenticPanelIn(panel)
            return
        }

        val starters = agenticStarters
        if (starters.isNotEmpty()) {
            val wasHidden = panel.visibility != View.VISIBLE
            panel.visibility = View.VISIBLE
            panel.background = agenticCardBackground(cardTop, cardBottom, cardStroke)
            suggestionStrip?.visibility = View.GONE
            panel.addView(TextView(this).apply {
                text = "TRY"; gravity = Gravity.CENTER; textSize = 8.8f; letterSpacing = 0.12f
                setTextColor(kickerInk); setPadding(dp(2), 0, dp(6), 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            starters.forEach { starter ->
                row.addView(agenticFollowUp(starter.label, violet, tileBg) {
                    keyHaptic("enter"); agenticStarters = emptyList(); starter.run()
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
            }
            panel.addView(android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                setPadding(0, 0, dp(12), 0)
                addView(row)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            panel.addView(TextView(this).apply {
                text = "✕"; gravity = Gravity.CENTER; textSize = 13f; setTextColor(kickerInk)
                setPadding(dp(10), dp(8), dp(6), dp(8)); isClickable = true
                setOnClickListener { keyHaptic("back"); agenticStarters = emptyList(); updateStrip() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
            if (wasHidden) animateAgenticPanelIn(panel)
            return
        }

        val status = agenticStatus
        val pending = pendingCommand
        val accent: Int; val glyph: String; val kicker: String; val title: String
        when {
            status != null -> { accent = violet; glyph = "✦"; kicker = "TECLAS"; title = status }
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
            shadowBeforeCursor(200)
                .trim().split(Regex("\\s+")).count { it.isNotEmpty() } >= 3

    private fun updateStrip() {
        perfIpcReads = 0
        ptime("updateStrip") { updateStripBody() }
    }

    private fun updateStripBody() {
        val strip = suggestionStrip ?: return
        // Elevate command previews and working status into the elegant panel; it toggles the strip's
        // visibility. The strip is still populated below as the fallback surface when the panel idles.
        ptime("renderAgenticPanel") { renderAgenticPanel() }
        // Legacy (rare) states rebuild the strip from scratch; the steady typing path below never
        // does — it recycles the fixed slot views in renderTypingStrip.
        val status = agenticStatus
        if (status != null) {
            strip.removeAllViews()
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
            strip.removeAllViews()
            strip.background = stripWellBackground()
            strip.addView(TextView(this).apply {
                text = pending.label
                gravity = Gravity.CENTER_VERTICAL; textSize = 15f
                setTextColor(stripInk())
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
        val proof = pendingProofread
        if (proof != null) {
            strip.removeAllViews()
            strip.background = stripWellBackground()
            strip.addView(TextView(this).apply {
                text = proof
                gravity = Gravity.CENTER_VERTICAL; textSize = 15f
                setTextColor(stripInk())
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(8), 0, dp(6), 0)
                isClickable = true
                setOnClickListener { applyProofread() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            strip.addView(TextView(this).apply {
                text = "✕"; gravity = Gravity.CENTER; textSize = 15f
                setTextColor(0xFF8B95A5.toInt()); setPadding(dp(10), 0, dp(6), 0)
                isClickable = true
                setOnClickListener { keyHaptic("back"); pendingProofread = null; updateStrip() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
            strip.addView(TextView(this).apply {
                text = "FIX"; gravity = Gravity.CENTER; textSize = 11f; letterSpacing = 0.12f
                setTextColor(0xFF33E1C4.toInt()); setPadding(dp(12), 0, dp(10), 0)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x3333E1C4); cornerRadius = dp(9).toFloat()
                }
                isClickable = true
                setOnClickListener { applyProofread() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply { marginStart = dp(4) })
            return
        }
        if (polishing) {
            strip.removeAllViews()
            strip.background = stripWellBackground()
            strip.addView(TextView(this).apply {
                text = "✨ Polishing…"
                gravity = Gravity.CENTER; textSize = 15f
                setTextColor(0xFFCBB4FF.toInt())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            return
        }
        if (pendingActions.isNotEmpty()) {
            strip.removeAllViews()
            strip.background = stripWellBackground()
            pendingActions.forEachIndexed { i, action ->
                strip.addView(TextView(this).apply {
                    text = action.first
                    gravity = Gravity.CENTER; textSize = 14f
                    setTextColor(stripInk())
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
        // Offer on-device Nano proofreading once there's a sentence to check (Nano-capable devices only).
        val showFix = nanoSupported && run {
            val b = shadowBeforeCursor(60)
            b.trim().length >= 6 && b.contains(' ')
        }
        if (shown.isEmpty() && !canPolish && chips.isEmpty() && !showFix) {
            renderTypingStrip(emptyList(), emptyList(), showFix = false, canPolish = false)
            strip.background = null
            // Nothing to show: blank the strip when the field is empty (fresh keyboard / not typing);
            // INVISIBLE, not GONE — collapsing the row resized the whole IME window on the first
            // keystroke, a guaranteed jank right when typing starts. The reserved row keeps the
            // keyboard's height constant for the entire session.
            if (agenticHud == null && agenticStatus == null && pendingCommand == null &&
                inlineScroll?.visibility != View.VISIBLE) {
                val fieldEmpty = shadowBeforeCursor(1).isEmpty() && shadowAfterCursor(1).isEmpty()
                suggestionStrip?.visibility = if (fieldEmpty) View.INVISIBLE else View.VISIBLE
            }
            return
        }
        // The strip was being populated but stayed invisible whenever inline autofill (or the panel)
        // had hidden it. Once we have real word suggestions/chips to show, make the strip visible and
        // stand down the autofill row — typing a word takes priority.
        suggestionStrip?.visibility = View.VISIBLE
        inlineScroll?.visibility = View.GONE
        strip.background = stripWellBackground()
        ptime("renderTypingStrip") { renderTypingStrip(shown, chips, showFix, canPolish) }
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

    // How the shared AgenticEngine renders into the IME: paint the panel/strip and commit via the
    // InputConnection. The launcher provides its own AgenticHost — same engine, different surface.
    private val agenticHost = object : AgenticHost {
        override val hostContext: Context get() = this@TeclasImeService
        override fun prefs() = imePrefs()
        override fun clipboardText() = this@TeclasImeService.clipboardText()
        override fun isPro() = ProManager.isUnlocked(this@TeclasImeService)
        override fun isGoogleConnected() = GmailAuth(this@TeclasImeService).isConnected
        override fun post(action: () -> Unit) { handler.post(action) }
        override fun clearField(consumed: String) {
            if (consumed.isNotEmpty()) currentInputConnection?.deleteSurroundingText(consumed.length, 0)
            onTextChanged()
        }
        override fun showStatus(msg: String) { agenticStatus = msg; updateStrip() }
        override fun flashStatus(msg: String, ms: Long) { flashAgenticStatus(msg, ms) }
        override fun showResult(result: AgenticResult) {
            agenticHud = Hud(result.kicker, result.body, result.insert, result.followUps.map { HudAction(it.label, it.run) })
            updateStrip()
        }
        override fun insertText(text: String) { currentInputConnection?.commitText(text, 1); invalidateShadow(); onTextChanged(); updateStrip() }
        override fun runAttach() { showAttachPicker() }
        override fun showShareCard(card: ShareCard) { showShareCardOverlay(card) }
    }

    // Agentic quick-action: hold the go/enter key to drop your current location into the field — no
    // maps app, no leaving the chat. Permission can't be requested from an IME, so if it's not
    // already granted (e.g. via Teclas weather) we point the user there instead.
    // Hold go/enter -> read the typed line, classify it, and show a preview chip the user taps to run
    // (Acti-style: nothing launches without confirmation). Empty / unknown text falls through to a
    // location share or a hint. Tapping go/enter still sends/enters as normal.
    private fun runAgenticCommand() {
        val raw = currentInputConnection?.getTextBeforeCursor(200, 0)?.toString() ?: ""
        // Clipboard skills: a bare trigger word (mood, stock, notion…) runs a shared skill on the COPIED
        // text, not the field. Handled by the shared AgenticEngine so the launcher keyboard behaves identically.
        if (AgenticEngine.tryClipSkill(agenticHost, raw)) return
        // Empty field: first try a conversation-aware chirp. If the person you're replying to just
        // asked something ("what thai place should we try?"), read that incoming message and offer a
        // ready action (Find place, Navigate, Web search). Falls back to starter chips.
        if (raw.isBlank()) {
            val pkg = currentEditorPackage
            val incoming = pkg?.let { TeclasNotificationListener.latestConversation(this, it) }
            val fresh = incoming != null && System.currentTimeMillis() - incoming.whenMs < 20 * 60_000
            if (fresh && ProManager.isUnlocked(this) && GeminiClient.configured(imePrefs())) {
                agenticStatus = "🤖 Reading the chat…"; updateStrip()
                Thread {
                    val cmd = runCatching { conversationChirp(incoming!!.preview) }.getOrNull()
                    handler.post {
                        agenticStatus = null
                        if (cmd != null) { pendingCommandText = ""; pendingCommand = cmd; updateStrip() }
                        else { val s = buildStarters(); if (s.isNotEmpty()) { agenticStarters = s; updateStrip() } }
                    }
                }.start()
                return
            }
            val starters = buildStarters()
            if (starters.isNotEmpty()) { agenticStarters = starters; updateStrip(); return }
        }
        val cmd = AgenticRouter.classify(raw)
        if (cmd != null) {
            pendingCommandText = raw
            pendingCommand = cmd
            updateStrip()
            return
        }
        // Free-form fallback: the planner interprets the line first — commands (single or
        // multi-step) win; text that maps to NO skill falls back to message actions below.
        if (raw.isNotBlank() && ProManager.isUnlocked(this) && GeminiClient.configured(imePrefs())) {
            pendingCommandText = raw
            agenticStatus = "\uD83E\uDD16 Thinking\u2026"
            updateStrip()
            val key = GeminiClient.apiKey(imePrefs()); val model = GeminiClient.model(imePrefs())
            val names = AgenticRouter.catalogNames()
            Thread {
                // Multi-step planner (grammar-locked to the catalog on the local tier).
                val steps = AgenticPlanner.plan(imePrefs(), raw, names)
                handler.post {
                    agenticStatus = null
                    val commands = steps.orEmpty().mapNotNull { AgenticRouter.commandForSkill(it.skill, it.arg) }
                    when {
                        commands.size > 1 -> commands.forEachIndexed { i, c ->
                            handler.postDelayed({
                                val status = AgenticRouter.execute(this@TeclasImeService, c)
                                flashAgenticStatus(status ?: c.label, 1400)
                            }, i * 900L)
                        }
                        commands.size == 1 -> { pendingCommand = commands[0]; updateStrip() }
                        // No skill matched: treat it as a message → inline AI actions (fix, translate).
                        raw.trim().split(Regex("\\s+")).count { it.isNotEmpty() } >= 3 -> {
                            pendingCommandText = raw
                            pendingActions = buildMessageActions()
                            updateStrip()
                        }
                        else -> flashAgenticStatus("No command matched", 1800)
                    }
                }
            }.start()
            return
        }
        flashAgenticStatus("Type a command \u2014 play, nearest, timer\u2026", 2200)
    }

    /**
     * Read the person's latest message and suggest ONE actionable chirp \u2014 the Kelly/thai case:
     * "what thai restaurant should we go to?" \u2192 a "Thai near you" chip that opens Maps. Grammar-free
     * but validated against the real skill catalog, so it can only produce actions Teclas performs.
     * On-device (Bonsai/Nano); blocking, call off the main thread. Returns null when nothing helps.
     */
    private fun conversationChirp(incoming: String): AgenticRouter.Command? {
        if (incoming.isBlank()) return null
        val prompt = """Someone just messaged the user. Suggest ONE helpful phone action to help them reply, or none.
Their message: "${incoming.take(200)}"
Reply ONLY as JSON: {"skill":"<Find place|Navigate|Web search|none>","query":"<what to search or find>","label":"<chip text, max 5 words>"}
Use "Find place" for restaurants, venues or things nearby; "Navigate" for directions; "Web search" for facts or lookups; "none" if no action helps."""
        // Strict grammar: the local model can ONLY emit {skill,query,label} with a real skill, so
        // the chirp always parses into a runnable command (no silent "nothing happened").
        val grammar = """
            root ::= "{" ws "\"skill\"" ws ":" ws skill ws "," ws "\"query\"" ws ":" ws str ws "," ws "\"label\"" ws ":" ws str ws "}" ws
            skill ::= "\"Find place\"" | "\"Navigate\"" | "\"Web search\"" | "\"none\""
            str ::= "\"" ([^"\\] | "\\" (["\\bfnrt])){0,60} "\""
            ws ::= [ \t\n]{0,6}
        """.trimIndent()
        val out = GeminiClient.generate(
            GeminiClient.apiKey(imePrefs()), GeminiClient.model(imePrefs()), prompt,
            maxTokens = 80, temperature = 0.1, json = true, grammar = grammar,
        ) ?: return null
        val inner = out.substringAfter('{', "").substringBeforeLast('}')
        if (inner.isBlank()) return null
        val obj = runCatching { org.json.JSONObject("{$inner}") }.getOrNull() ?: return null
        val skill = obj.optString("skill").trim()
        if (skill.isBlank() || skill.equals("none", ignoreCase = true)) return null
        val query = obj.optString("query").trim()
        if (query.isBlank()) return null
        val label = obj.optString("label").trim()
        val cmd = AgenticRouter.commandForSkill(skill, query) ?: return null
        return if (label.isNotBlank()) cmd.copy(label = label) else cmd
    }

    // Starter chips for the held-go-over-empty-field case: Share location runs on tap; the rest seed
    // their trigger into the field so the user just finishes the thought.
    // Chirps are share verbs, not command verbs: each drops a complete thing into the conversation via
    // a preview card. No seed-text starters (the "insert `play ` then re-hold go" flow was clunky).
    private fun buildStarters(): List<HudAction> {
        val chips = ArrayList<HudAction>()
        chips.add(HudAction("\ud83c\udfb5 Song") { AgenticEngine.shareSong(agenticHost) })
        chips.add(HudAction("\ud83d\udccd My location") { AgenticEngine.sharePlace(agenticHost) })
        if (canAttachHere()) chips.add(HudAction("\ud83d\udcce Attach") { showAttachPicker() })
        // The real skill catalog — GO used to offer only the chirps above. Tap seeds the trigger.
        AgenticRouter.starters(limit = 12).forEach { s ->
            chips.add(HudAction(s.label) {
                agenticStarters = emptyList()
                currentInputConnection?.commitText(s.insert, 1)
                onTextChanged()
            })
        }
        return chips
    }

    // --- attach picker ---------------------------------------------------------------------------
    // A Neu-styled sheet that floats OVER the keyboard: pick a recent photo and it drops straight into
    // the chat/email draft via commitContent — no app switch. The action only fires when the field
    // actually accepts inline content (checked before we open).

    private fun canAttachHere(): Boolean = AttachContent.editorAcceptsContent(currentInputEditorInfo)

    fun showAttachPicker() {
        val root = imeRoot ?: return
        if (!canAttachHere()) { flashAgenticStatus("📎 This field can’t take attachments", 2200); return }
        if (attachOverlay != null) { hideAttachPicker(); return }
        keyHaptic("enter")
        // Build the shell immediately (with a loading line) so it feels instant; fill the grid off-thread.
        val overlay = buildAttachOverlay(emptyList(), loading = true)
        attachOverlay = overlay
        root.addView(overlay, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        animateAttachIn(overlay)
        Thread {
            val items = AttachContent.recentImages(this, 30)
            handler.post {
                if (attachOverlay !== overlay) return@post  // dismissed while loading
                val fresh = buildAttachOverlay(items, loading = false)
                root.removeView(overlay)
                root.addView(fresh, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
                attachOverlay = fresh
            }
        }.start()
    }

    fun hideAttachPicker() {
        val overlay = attachOverlay ?: return
        attachOverlay = null
        (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
    }

    // --- share card ------------------------------------------------------------------------------
    // The preview beat of a share: a full-bleed card over the keyboard showing exactly what will land
    // in the conversation (now-playing track with album art, meeting spot with address). One tap
    // inserts into the field — the user still hits the app's own send. Never auto-sends.

    private fun showShareCardOverlay(card: ShareCard) {
        val root = imeRoot ?: return
        hideShareCard()
        agenticStatus = null; updateStrip()
        val overlay = buildShareCardView(card)
        shareCardOverlay = overlay
        // Pin to the deck's real height: a full-bleed bitmap child would otherwise inflate the IME
        // window way past the keyboard (the card must cover the keyboard, not the conversation).
        val cardHeight = deckView?.height?.takeIf { it > 0 } ?: imeKeyboardHeight()
        root.addView(overlay, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, cardHeight, Gravity.BOTTOM))
        animateAttachIn(overlay)
    }

    fun hideShareCard() {
        val overlay = shareCardOverlay ?: return
        shareCardOverlay = null
        (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
    }

    private fun buildShareCardView(card: ShareCard): View {
        val tokens = selectedNeuTokens()
        val radius = dp(18).toFloat()
        val frame = android.widget.FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(0xFF14161B.toInt()); cornerRadius = radius }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            isClickable = true
        }

        // Backdrop: album art full-bleed under a legibility scrim, or a quiet Neu panel when there's none.
        if (card.art != null) {
            frame.addView(android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setImageBitmap(card.art)
            }, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
            frame.addView(View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x33000000, 0x66000000, 0xE6000000.toInt()))
            }, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        } else {
            frame.background = Neu.drawable(tokens, radius, NeuLevel.RAISED)
            frame.addView(TextView(this).apply {
                text = if (card.kicker.contains("LOCATION") || card.kicker.contains("SPOT")) "📍" else "🎵"
                textSize = 54f; alpha = 0.25f; gravity = Gravity.CENTER
            }, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(18) })
        }
        val onArt = card.art != null
        val ink = if (onArt) 0xFFF5F5F7.toInt() else tokens.ink
        val inkDim = if (onArt) 0xFFB9BEC9.toInt() else tokens.inkDim
        val mint = 0xFF57E39A.toInt()

        // Bottom-anchored text stack: kicker, title, subtitle, then the tap-to-share pill.
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(14))
        }
        col.addView(TextView(this).apply {
            text = card.kicker; textSize = 9.5f; letterSpacing = 0.18f
            setTextColor(mint); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        col.addView(TextView(this).apply {
            text = card.title; textSize = 21f; setTextColor(ink)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        if (card.subtitle.isNotBlank()) col.addView(TextView(this).apply {
            text = card.subtitle; textSize = 13.5f; setTextColor(inkDim)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        col.addView(TextView(this).apply {
            text = "Tap to drop into the message"; textSize = 11.5f; setTextColor(inkDim)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply {
                setColor(if (onArt) 0x30FFFFFF else 0x14000000)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), (mint and 0x00FFFFFF) or 0x55000000)
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        frame.addView(col, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Close, top-right.
        frame.addView(TextView(this).apply {
            text = "✕"; textSize = 15f; setTextColor(if (onArt) 0xCCFFFFFF.toInt() else inkDim)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10)); isClickable = true
            setOnClickListener { keyHaptic("back"); hideShareCard() }
        }, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))

        // The whole card is the commit target: insert, dismiss, then offer the optional follow-up.
        frame.setOnClickListener {
            agenticConfirmHaptic()
            hideShareCard()
            currentInputConnection?.commitText(card.insertText, 1)
            onTextChanged()
            val fu = card.followUp
            if (fu != null) {
                agenticHud = Hud(card.kicker, "Dropped in — need it yourself?", null,
                    listOf(HudAction(fu.label) { agenticHud = null; updateStrip(); fu.run() }))
            }
            updateStrip()
        }
        return frame
    }

    private fun animateAttachIn(v: View) {
        if (!animationsEnabled()) return
        v.alpha = 0f; v.translationY = dp(24).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(180L)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun buildAttachOverlay(items: List<AttachContent.MediaItem>, loading: Boolean): View {
        val tokens = selectedNeuTokens()
        val light = tokens.mode == NeuMode.LIGHT
        val ink = tokens.ink; val inkDim = tokens.inkDim
        val cardStroke = if (light) 0x14000000 else 0x22FFFFFF

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Neu.drawable(tokens, dp(18).toFloat(), NeuLevel.RAISED)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true  // swallow taps so they don't fall through to keys behind
        }

        // Header: title · Files… · close
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "📎  Attach"; textSize = 15f; setTextColor(ink)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "Files…"; textSize = 12.5f; setTextColor(Neu.BLUE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(if (light) 0xFFE9EFF7.toInt() else 0xFF12212F.toInt()); cornerRadius = dp(11).toFloat()
                setStroke(dp(1), (Neu.BLUE and 0x00FFFFFF) or 0x55000000)
            }
            isClickable = true
            setOnClickListener { keyHaptic("enter"); openSystemFilePicker() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        header.addView(TextView(this).apply {
            text = "✕"; textSize = 15f; setTextColor(inkDim); gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(6), dp(8)); isClickable = true
            setOnClickListener { keyHaptic("back"); hideAttachPicker() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
        sheet.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val bodyHeight = (imeKeyboardHeight() - dp(78)).coerceAtLeast(dp(120))
        val bodyParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, bodyHeight).apply { topMargin = dp(10) }

        when {
            loading -> sheet.addView(TextView(this).apply {
                text = "Loading photos…"; textSize = 13.5f; setTextColor(inkDim); gravity = Gravity.CENTER
            }, bodyParams)
            items.isEmpty() -> {
                // No media visible — either permission not granted, or genuinely empty.
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
                col.addView(TextView(this).apply {
                    text = "No photos available"; textSize = 14f; setTextColor(ink); gravity = Gravity.CENTER
                })
                col.addView(TextView(this).apply {
                    text = "Grant photo access in the Teclas app, or use Files…"
                    textSize = 12f; setTextColor(inkDim); gravity = Gravity.CENTER
                    setPadding(dp(24), dp(6), dp(24), 0)
                })
                sheet.addView(col, bodyParams)
            }
            else -> {
                val cols = 4
                val tile = ((resources.displayMetrics.widthPixels - dp(28) - dp(6) * (cols - 1)) / cols).coerceAtLeast(dp(56))
                val grid = android.widget.GridLayout(this).apply { columnCount = cols; rowCount = (items.size + cols - 1) / cols }
                items.forEachIndexed { i, item ->
                    val cell = android.widget.ImageView(this).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        background = GradientDrawable().apply {
                            setColor(if (light) 0xFFDDE1E8.toInt() else 0xFF10131A.toInt()); cornerRadius = dp(10).toFloat()
                            setStroke(dp(1), cardStroke)
                        }
                        clipToOutline = true
                        outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(view: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat()) }
                        }
                        isClickable = true
                        setOnClickListener { attachMediaItem(item) }
                    }
                    loadThumbnailInto(cell, item, tile)
                    val lp = android.widget.GridLayout.LayoutParams().apply {
                        width = tile; height = tile
                        setMargins(0, 0, if ((i % cols) == cols - 1) 0 else dp(6), dp(6))
                    }
                    grid.addView(cell, lp)
                }
                sheet.addView(android.widget.ScrollView(this).apply {
                    isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
                    addView(grid)
                }, bodyParams)
            }
        }
        return sheet
    }

    // Load a MediaStore thumbnail off the main thread and set it once, guarding against recycled cells.
    // A small bounded pool keeps us from spawning a thread per tile when the grid fills.
    private val thumbExecutor by lazy { java.util.concurrent.Executors.newFixedThreadPool(3) }
    private fun loadThumbnailInto(view: android.widget.ImageView, item: AttachContent.MediaItem, sizePx: Int) {
        view.tag = item.uri
        thumbExecutor.execute {
            val bmp = runCatching {
                contentResolver.loadThumbnail(item.uri, android.util.Size(sizePx, sizePx), null)
            }.getOrNull()
            if (bmp != null) handler.post { if (view.tag == item.uri) view.setImageBitmap(bmp) }
        }
    }

    private fun attachMediaItem(item: AttachContent.MediaItem) {
        if (!AttachContent.accepts(currentInputEditorInfo, item.mime)) {
            flashAgenticStatus("📎 This field won’t accept ${item.mime}", 2400); return
        }
        keyHaptic("enter")
        val ic = currentInputConnection; val info = currentInputEditorInfo
        agenticConfirmHaptic()
        Thread {
            val ok = AttachContent.commit(this, ic, info, item.uri, item.mime, item.displayName)
            handler.post {
                hideAttachPicker()
                if (!ok) flashAgenticStatus("📎 Couldn’t attach that here", 2200)
                else onTextChanged()
            }
        }.start()
    }

    // Arbitrary files live outside MediaStore, so this is the one unavoidable trip out: a tiny bridge
    // activity runs the system document picker and hands the result back (see AttachPickerActivity).
    private fun openSystemFilePicker() {
        hideAttachPicker()
        val mimes = AttachContent.acceptedMimeTypes(currentInputEditorInfo)
        // Register the return path before launching: the picker stages the file and calls back. We stash
        // it and commit once the field is focused again (see onStartInputView) — committing while the
        // picker activity still holds focus would silently drop it.
        AttachBridge.pending = { file, mime, name ->
            handler.post { pendingAttachment = Triple(file, mime, name); commitPendingAttachment() }
        }
        runCatching {
            startActivity(Intent(this, AttachPickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(AttachPickerActivity.EXTRA_MIME_TYPES, if (mimes.isEmpty()) arrayOf("*/*") else mimes)
            })
        }.onFailure { AttachBridge.pending = null; flashAgenticStatus("Couldn’t open files", 2000) }
    }

    // Commit a file picked via the system picker, once we have a live input connection. Called on the
    // bridge callback and again from onStartInputView (whichever wins the race to a focused field).
    private fun commitPendingAttachment() {
        val (file, mime, name) = pendingAttachment ?: return
        val ic = currentInputConnection ?: return   // no field yet — retry on next onStartInputView
        val info = currentInputEditorInfo
        pendingAttachment = null
        if (!AttachContent.accepts(info, mime)) { flashAgenticStatus("📎 This field won’t accept $mime", 2600); return }
        Thread {
            val ok = AttachContent.commitFile(this, ic, info, file, mime, name)
            handler.post { if (ok) onTextChanged() else flashAgenticStatus("📎 Couldn’t attach that here", 2200) }
        }.start()
    }

    private fun applyPendingCommand() {
        val cmd = pendingCommand ?: return
        val raw = pendingCommandText
        pendingCommand = null
        // Clear the command text from the field — it was a command, not message content.
        if (raw.isNotEmpty()) currentInputConnection?.deleteSurroundingText(raw.length, 0)
        if (cmd.insertsLocation) { AgenticRouter.recordUse(this, cmd.skillId); runAgenticLocation(); return }
        if (cmd.fetchWeather) { AgenticRouter.recordUse(this, cmd.skillId); AgenticEngine.runWeather(agenticHost, cmd.arg); return }
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
        val level = imePrefs().getInt(HAPTIC_LEVEL_PREF, 100).coerceIn(0, 100) / 100f
        val amp = (amplitude * level).toInt().coerceIn(1, 255)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 26)
                v.vibrate(android.os.VibrationEffect.createOneShot(ms, amp))
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
        val loader = com.fran.teclas.keyboard.DictionaryLoader
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
        if (!GeminiClient.configured(imePrefs())) { flashAgenticStatus("Add a Gemini key in Teclas settings", 2400); return }
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
            flashAgenticStatus("\uD83D\uDCCD Enable location in Teclas", 2600)
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
        // Honest guard: the account-mode proxy makes Polish LOOK configured even with no key and no
        // sign-in. Without real auth, say so (and how to fix it) instead of a dead "Polishing…".
        // On-device Nano needs neither — it serves keyless and offline.
        if (nanoSupported != true && GeminiClient.nano?.ready != true && !GeminiClient.localReady() &&
            key.isBlank() && GeminiClient.proxy?.idTokenProvider?.invoke() == null) {
            flashStatus("Add your Gemini key: tap ⚙ (the teclas key) → API key", 2800)
            return
        }
        polishing = true
        keyHaptic("enter")
        updateStrip()
        Thread {
            // On-device first via the keyboard-safe Rewriting API — the raw Prompt API is blocked
            // by AICore while the IME serves another app, but this one isn't. Cloud is the fallback.
            val nanoResult = kotlinx.coroutines.runBlocking {
                runCatching { nanoRewrite.rewrite(text) }
                    .getOrDefault(com.fran.teclas.keyboard.NanoRewriteEngine.Result.Error)
            }
            // No cloud auth and on-device can't serve → say WHY honestly instead of letting the
            // keyless cloud fallback die with "couldn't reach the AI".
            val cloudless = key.isBlank() && GeminiClient.proxy?.idTokenProvider?.invoke() == null
            if (cloudless && nanoResult == com.fran.teclas.keyboard.NanoRewriteEngine.Result.Downloading) {
                handler.post {
                    polishing = false
                    flashStatus("Preparing on-device AI — try again in a minute…", 2600)
                    onTextChanged()
                }
                return@Thread
            }
            if (cloudless && nanoResult == com.fran.teclas.keyboard.NanoRewriteEngine.Result.Blocked &&
                !com.fran.teclas.llm.LocalLlmEngine.ready(this)) {
                handler.post {
                    polishing = false
                    flashStatus("Download the keyboard AI model in Teclas settings (search \"keyboard ai\") to polish offline in any app.", 3400)
                    onTextChanged()
                }
                return@Thread
            }
            val result = (nanoResult as? com.fran.teclas.keyboard.NanoRewriteEngine.Result.Rewritten)?.text
                ?: if (nanoResult == com.fran.teclas.keyboard.NanoRewriteEngine.Result.Unchanged) text
                else GeminiClient.fetchRewrite(key, model, text)
            handler.post {
                polishing = false
                when {
                    result != null && result != text -> {
                        ic.beginBatchEdit()
                        ic.deleteSurroundingText(text.length, 0)
                        ic.commitText(result, 1)
                        ic.endBatchEdit()
                        onTextChanged()
                    }
                    result == text -> { flashStatus("Looks good ✓"); onTextChanged() }
                    else -> { flashStatus(GeminiClient.lastErrorMessage ?: "Couldn't reach the AI — check your key/connection", 2600); onTextChanged() }
                }
            }
        }.start()
    }

    // A glide that the decoder couldn't turn into a word must NOT dump the raw traced letters into the
    // text (that's the "swipe gives a bunch of letters, not the word" bug). Drop it instead — a lost
    // gesture is far better than garbage. This only happens when the classifier isn't ready yet or the
    // shape genuinely matched nothing; both are rare once the dictionary has loaded.
    private fun handleSwipeFallback(keys: List<String>) {
        // intentionally a no-op — never commit the raw key trace
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
        if (deleteCount > 0) { input.deleteSurroundingText(deleteCount, 0); shadowOnDeleteBefore(deleteCount) }
    }

    private fun glideTrailColors(): IntArray {
        val theme = keyboardVisualTheme()
        val core = when (theme) {
            KEYBOARD_THEME_GOKEYS -> 0xFFF2691E.toInt()
            KEYBOARD_THEME_TECLAS -> 0xFFF5C451.toInt()
            KEYBOARD_THEME_SKEUO -> 0xFF8FD694.toInt()
            KEYBOARD_THEME_HYPER3D -> 0xFF7EA2FF.toInt()
            KEYBOARD_THEME_HYPER3D_BLACK -> 0xFFFF6B6B.toInt()
            KEYBOARD_THEME_HYPER3D_LIGHT -> 0xFF4E6FE7.toInt()
            KEYBOARD_THEME_BRUSHED -> if (selectedNeuTokens().mode == NeuMode.LIGHT) 0xFF9FA7B2.toInt() else 0xFFC9CED6.toInt()
            else -> goKeyColor()
        }
        val tail = when (theme) {
            KEYBOARD_THEME_HYPER3D_LIGHT -> 0xFF8FD6FF.toInt()
            KEYBOARD_THEME_HYPER3D_BLACK -> 0xFF9DB4FF.toInt()
            KEYBOARD_THEME_TECLAS -> 0xFFFF8A68.toInt()
            else -> brighten(core)
        }
        return intArrayOf(adjustAlpha(core, 0x20), tail, core, adjustAlpha(core, 0x22))
    }

    // [rebuildBackgrounds] is only needed on a theme change. Shift / auto-cap flips just change the
    // letter case, so rebuilding every key's background drawable on each of those (which happens
    // constantly while typing) was wasted UI-thread allocation — the main IME sluggishness. Default
    // to a light text-only refresh.
    private fun refreshKeyboardChrome(rebuildBackgrounds: Boolean = false) {
        // Canvas keyboard: labels/colors live in its bitmap buffer, so a shift/caps/theme change means
        // re-rendering the buffer rather than walking per-key views.
        canvasKeyboardView?.let {
            if (rebuildBackgrounds) deckView?.background = deckBackground()
            it.rebuild()
            return
        }
        deckView?.let { deck ->
            if (rebuildBackgrounds) deck.background = deckBackground()
            for (rowIndex in 0 until deck.childCount) {
                val row = deck.getChildAt(rowIndex) as? LinearLayout ?: continue
                for (keyIndex in 0 until row.childCount) {
                    val key = row.getChildAt(keyIndex) as? TextView ?: continue
                    val raw = key.tag as? String ?: continue
                    val ink = textColor(raw)
                    if (key.currentTextColor != ink) key.setTextColor(ink)
                    if (key is com.fran.teclas.keyboard.DynamicFlickKeyView && raw.length == 1 && raw[0].isLetter() && !symbolsMode) {
                        if (keyboardTheme() == KEYBOARD_THEME_BRUSHED) {
                            key.setLabelPlacement(
                                labelBias = 0.28f,
                                symbolBias = 0.04f,
                                labelMaxScale = 0.52f,
                                symbolScale = 0.16f,
                                extraBottomInsetPx = 0,
                                engravedSymbols = true
                            )
                        }
                        key.setSymbolHint(com.fran.teclas.keyboard.KeyboardSymbols.keyUp[raw.lowercase(Locale.US)], symbolHintColor())
                        if (keyboardTheme() == KEYBOARD_THEME_BRUSHED) {
                            val brushedLabel = if (capsLock) raw.uppercase(Locale.US) else raw.lowercase(Locale.US)
                            key.setDrawnPrimaryLabel(brushedLabel, ink, keyTextSize(raw) * resources.displayMetrics.scaledDensity, key.typeface)
                        } else {
                            key.setDrawnPrimaryLabel(
                                visualLabel(raw),
                                ink,
                                keyTextSize(raw) * resources.displayMetrics.scaledDensity,
                                key.typeface
                            )
                        }
                    } else {
                        // Only touch TextView.text when the label actually changed — setText always
                        // invalidates and can trigger a layout pass across the row.
                        val newText = keyDisplayText(raw)
                        if (key.text?.toString() != newText.toString()) key.text = newText
                    }
                    if (rebuildBackgrounds) key.background = visualKeyBackground(raw, pressed = false)
                }
            }
        }
    }

    private inner class SwipeImeKeyboardLayout : LinearLayout(this@TeclasImeService) {
        // Key bounds refresh after every layout pass of the deck (rows swapped, autofill row shown,
        // size changed) instead of on every touch-down. One coalesced post per pass.
        private var boundsCapturePending = false
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            if (!boundsCapturePending) {
                boundsCapturePending = true
                post { boundsCapturePending = false; captureKeyBounds() }
            }
        }

        private var startRawX = 0f
        private var startRawY = 0f
        private var tracking = false
        // Whether the touch went DOWN on a letter key. Glide/flick/delete gestures may only begin
        // from a letter — otherwise a drag that starts on 123/space/back/etc. hijacks the touch and
        // (worst case) fires quick-left-delete, eating the last word, or flashes the glide trail,
        // instead of the key doing its job. This is the gate that fixes "pressing 123 deletes my text".
        private var downOnLetterKey = false
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
        // Gradient colors are theme-derived and stable for the whole gesture; computing them (and a
        // fresh IntArray) inside dispatchDraw allocated on every frame of every glide.
        private var trailColorsCache: IntArray? = null
        // The gradient tracks the trail's endpoints; rebuilding the shader only after ~6px of
        // movement (instead of every frame) keeps the visual identical without per-frame allocation.
        private var trailShaderEndX = Float.NaN
        private var trailShaderEndY = Float.NaN
        private var trailShaderStartX = Float.NaN
        private var trailShaderStartY = Float.NaN
        private var glidePersisting = false
        private val touchSlop = ViewConfiguration.get(this@TeclasImeService).scaledTouchSlop
        // A glide must clearly outrun a normal tap's finger-drift before it steals the touch from the
        // key, otherwise a tap with a little slide reads as a phantom swipe and eats the keystroke.
        private val glideActivationSlop = maxOf(touchSlop * 2f, dp(20).toFloat())
        // Two-finger trackpad: drag with two fingers anywhere on the keyboard to step the text cursor.
        private var cursorPanActive = false
        private var cursorPanLastX = 0f
        private val cursorPanStep = dp(13).toFloat()

        private fun beginCursorPan(ev: MotionEvent) {
            cursorPanActive = true
            cursorPanLastX = (ev.getX(0) + ev.getX(1)) / 2f
            clearGlideTouchState()
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        /** Feed two-finger horizontal drag into left/right cursor steps. Returns true while panning. */
        private fun handleCursorPan(ev: MotionEvent): Boolean {
            if (!cursorPanActive) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (ev.pointerCount >= 2) {
                        val midX = (ev.getX(0) + ev.getX(1)) / 2f
                        var delta = midX - cursorPanLastX
                        while (abs(delta) >= cursorPanStep) {
                            val right = delta > 0
                            moveTextCursor(right)
                            cursorPanLastX += if (right) cursorPanStep else -cursorPanStep
                            delta = midX - cursorPanLastX
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cursorPanActive = false
                }
            }
            return true
        }

        // Kick the evaporating-trail draw loop after release: dispatchDraw's sliding time-window
        // drains the remaining trail tail-first within ~one window (the Gboard glide-out), replacing
        // the old timed full-clear that made the trail linger and then blink away all at once.
        private fun fadeGlideTrail() {
            postInvalidateOnAnimation()
        }

        private fun clearGlideTouchState() {
            tracking = false
            traced.clear()
            trailLocal.clear()
            trailTimes.clear()
            trailColorsCache = null // recompute next gesture so theme switches take effect
            trailPaint.shader = null
            trailShaderEndX = Float.NaN
            trailShaderEndY = Float.NaN
            trailShaderStartX = Float.NaN
            trailShaderStartY = Float.NaN
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
                // Gboard-style evaporating trail: only the last GLIDE_TRAIL_WINDOW_MS of the path
                // is drawn, so the tail continuously melts away behind the finger mid-glide, and
                // the remainder glides out on its own within one window after release. Draw-only —
                // the full path stays stored (the decoders snapshot it at UP).
                val cutoff = android.os.SystemClock.uptimeMillis() - GLIDE_TRAIL_WINDOW_MS
                var first = 0
                while (first < trailTimes.size && trailTimes[first] < cutoff) first++
                if (first >= trailLocal.size - 1) {
                    // Fully evaporated. After release that's the end of the gesture's visuals.
                    if (glidePersisting) {
                        glidePersisting = false
                        trailLocal.clear()
                        trailTimes.clear()
                    }
                    return
                }
                trailPath.reset()
                trailPath.moveTo(trailLocal[first].first, trailLocal[first].second)
                for (i in first + 1 until trailLocal.size) trailPath.lineTo(trailLocal[i].first, trailLocal[i].second)
                val start = trailLocal[first]
                val end = trailLocal.last()
                val colors = trailColorsCache ?: glideTrailColors().also { trailColorsCache = it }
                // Rebuild the gradient only after either visible endpoint moves ~6px — the tail
                // now moves every frame, so this throttle is what keeps evaporation allocation-light.
                val moved = (end.first - trailShaderEndX).let { it * it } +
                    (end.second - trailShaderEndY).let { it * it } +
                    (start.first - trailShaderStartX).let { it * it } +
                    (start.second - trailShaderStartY).let { it * it }
                if (trailPaint.shader == null || moved.isNaN() || moved > 36f) {
                    trailPaint.shader = android.graphics.LinearGradient(
                        start.first,
                        start.second,
                        end.first,
                        end.second,
                        colors,
                        null,
                        Shader.TileMode.CLAMP
                    )
                    trailShaderEndX = end.first
                    trailShaderEndY = end.second
                    trailShaderStartX = start.first
                    trailShaderStartY = start.second
                }
                trailPaint.strokeWidth = dp(12).toFloat()
                trailPaint.alpha = 58
                canvas.drawPath(trailPath, trailPaint)
                trailPaint.strokeWidth = dp(5).toFloat()
                trailPaint.alpha = 222
                canvas.drawPath(trailPath, trailPaint)
                trailPaint.shader = null
                trailPaint.alpha = 255
                // Drive the melt between touch events (and after release) at display rate.
                postInvalidateOnAnimation()
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (ev.pointerCount == 2) { beginCursorPan(ev); return true }
                }
                MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX
                    startRawY = ev.rawY
                    glidePersisting = false
                    tracking = false
                    traced.clear()
                    trailLocal.clear()
                    trailTimes.clear()
                    val loc = IntArray(2)
                    getLocationOnScreen(loc)
                    val moved = loc[0].toFloat() != screenX || loc[1].toFloat() != screenY
                    screenX = loc[0].toFloat()
                    screenY = loc[1].toFloat()
                    // Key bounds are captured on layout (and after a row swap) — re-walking all 34
                    // key views on EVERY touch-down put avoidable work at the most latency-critical
                    // moment. Recapture here only if the deck actually moved on screen (window
                    // shifted between apps) or nothing is cached yet.
                    if (moved || keyBounds.isEmpty()) captureKeyBounds()
                    // Gate the whole glide/flick/delete state machine on the down-key being a letter.
                    val downKey = keyAtPoint(startRawX, startRawY, letterOnly = false)
                    downOnLetterKey = downKey != null && downKey.length == 1 && downKey[0].isLetter()
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking && downOnLetterKey &&
                        (abs(ev.rawX - startRawX) > glideActivationSlop || abs(ev.rawY - startRawY) > glideActivationSlop)) {
                        tracking = true
                        if (hapticsOn()) haptics().glideStart()   // firm click on glide activation
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
            // A second finger landing mid-touch switches to two-finger cursor panning.
            if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN && ev.pointerCount == 2 && !cursorPanActive) {
                beginCursorPan(ev); return true
            }
            if (handleCursorPan(ev)) return true
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
                        if (hapticsOn()) haptics().symbolFlick()
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
                    if (quickLeftDelete) {
                        keyHaptic("back"); deleteWord(); clf?.clear(); fadeGlideTrail(); return true
                    }
                    // Anti-ghost-swipe: a real glide crosses ≥2 distinct keys. A wiggle confined to one
                    // key must never decode to a word — drop it rather than commit a phantom word.
                    if (clf != null && clf.hasEnoughPoints && tracedKeys.size >= 2) {
                        val contextBoost = glideCore.contextBoost()
                        // Snapshot inputs on the main thread (touch state is about to be cleared).
                        val hybrid = hybridDecoder
                        val neuralPath = snapshotSwipePath()
                        val bounds = letterKeyBounds()
                        val centers = letterKeyCenters()
                        val freqs = glideFreqs
                        glideScope.launch {
                            // Hybrid fuses neural + statistical (falls back to statistical-only when
                            // the neural model isn't present); geometric trace stays the last resort.
                            // Ask for a few extra candidates so the intended active-language word is
                            // in the pool for languagePreferredOrder to promote.
                            val statistical: suspend () -> List<String> = {
                                withContext(kotlinx.coroutines.Dispatchers.Default) {
                                    try { clf.getSuggestions(6, contextBoost) }
                                    catch (e: Throwable) { emptyList() }
                                }
                            }
                            val res = if (hybrid != null) {
                                hybrid.decode(neuralPath, bounds, centers, freqs, 6, statistical)
                            } else {
                                val s = statistical()
                                com.fran.teclas.keyboard.neural.HybridResult(
                                    s, emptyList(), s, false, false,
                                    if (s.isEmpty()) com.fran.teclas.keyboard.neural.GlideSource.NONE
                                    else com.fran.teclas.keyboard.neural.GlideSource.STATISTICAL
                                )
                            }
                            runCatching { clf.clear() }
                            val results = languagePreferredOrder(res.words)
                            if (results.isNotEmpty()) {
                                if (hapticsOn()) haptics().glideCommit()
                                val top = glideCore.rerank(results)
                                glideCore.commitWord(top)
                                recordCommittedWordContext()
                                // Online-learning: record the accepted glide for personal frequency + corpus.
                                glideLearning.recordAcceptance(top, neuralPath, bounds, res.source, res.agreed)
                                // Remember it so a follow-up correction can re-label the swipe.
                                lastGlidePath = neuralPath; lastGlideBounds = bounds; lastGlideWord = top
                                // Show the swipe's other decodings as tap-to-correct alternatives
                                // (parity with the launcher): a mis-decode is one tap from fixed.
                                suggestions = (listOf(top) + results.filter { it != top }).distinct().take(3)
                                glideJustCommitted = true
                                updateStrip()
                            } else {
                                handleSwipeFallback(tracedKeys)   // no-op: never dump raw letters
                            }
                            fadeGlideTrail()
                        }
                    } else {
                        clf?.clear()
                        val netX = abs(ev.rawX - startRawX); val netY = abs(ev.rawY - startRawY)
                        when {
                            tracedKeys.size >= 3 -> { keyHaptic("space"); handleSwipeFallback(tracedKeys) }
                            // Tap recovery: a short drag that isn't a real glide would otherwise be
                            // swallowed (the parent stole the touch from the key). Type the start key
                            // so the keystroke isn't lost.
                            netX < glideActivationSlop * 2f && netY < glideActivationSlop * 2f ->
                                keyAtPoint(startRawX, startRawY, letterOnly = true)?.let {
                                    handleKey(resolveTapKey(it, startRawX, startRawY))
                                }
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

    // Key text with the swipe-down symbol shown small above the letter (so users see it).
    private fun keyDisplayText(label: String): CharSequence {
        val base = visualLabel(label)
        return if (label.length == 1 && label[0].isLetter() && !symbolsMode)
            com.fran.teclas.keyboard.keyLabelWithSymbol(base, label, symbolHintColor())
        else base
    }

    private fun symbolHintColor(): Int = (textColor("q") and 0x00FFFFFF) or (0x72 shl 24)

    private fun goLegendColor(): Int =
        if (KeyboardThemeDrawables.isAddedTheme(keyboardVisualTheme())) {
            KeyboardThemeDrawables.textColor(keyboardVisualTheme(), "enter", selectedNeuTokens().mode == NeuMode.DARK, goKeyColor())
        } else if (selectedNeuTokens().mode == NeuMode.LIGHT) 0xFFFFFFFF.toInt() else 0xFF050506.toInt()

    private fun textColor(label: String): Int {
        if (label == "enter") return goLegendColor()
        val theme = keyboardVisualTheme()
        if (KeyboardThemeDrawables.isAddedTheme(theme)) {
            return when (label) {
                "shift", "back" -> KeyboardThemeDrawables.accent(theme, selectedNeuTokens().mode == NeuMode.DARK, goKeyColor())
                else -> KeyboardThemeDrawables.textColor(theme, label, selectedNeuTokens().mode == NeuMode.DARK)
            }
        }
        if (isHyper3dTheme(theme)) {
            val visualTheme = hyper3dVisualTheme(theme)
            return when {
                visualTheme == KEYBOARD_THEME_HYPER3D_LIGHT && isFnKey(label) -> 0xFF596170.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D_LIGHT -> 0xFF1E2633.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D_BLACK && label == "teclas" -> 0xFF9DB4FF.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D_BLACK && isFnKey(label) -> 0xFF9AA2B1.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D && label == "teclas" -> 0xFFEAF0FF.toInt()
                visualTheme == KEYBOARD_THEME_HYPER3D && isFnKey(label) -> 0xFF9AA2B1.toInt()
                else -> 0xFFEEF1F7.toInt()
            }
        }
        if (theme == KEYBOARD_THEME_SEEME) {
            return when (label) {
                "teclas" -> 0xFFFF5A60.toInt()
                "123", "back", "shift", "." -> 0xFF8A8A8A.toInt()
                else -> 0xFFF2F2F2.toInt()
            }
        }
        if (theme == KEYBOARD_THEME_BRUSHED) {
            return BrushedDrawables.ink(label, selectedNeuTokens().mode == NeuMode.DARK)
        }
        if (theme == KEYBOARD_THEME_GOKEYS) {
            return if (keyboardLightMode(theme)) {
                when (label) {
                    "enter" -> 0xFFFFFFFF.toInt()
                    "123", "teclas", "back", "shift", "." -> 0xFF6F7884.toInt()
                    else -> 0xFF202733.toInt()
                }
            } else {
                when (label) {
                    "enter" -> 0xFFFFFFFF.toInt()
                    "123", "teclas", "back", "shift", "." -> 0xFF9AA1AB.toInt()
                    else -> 0xFFF3F5F8.toInt()
                }
            }
        }
        return if (keyboardLightMode(theme)) {
            when (label) {
                "enter" -> Neu.ACCENT
                "teclas" -> 0xFF2F6C4C.toInt()
                "123", "back", "shift", "." -> 0xFF6F7884.toInt()
                else -> 0xFF202733.toInt()
            }
        } else {
            val tokens = selectedNeuTokens()
            when (label) {
                "enter" -> Neu.ACCENT
                "teclas" -> Neu.GREEN
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
            "teclas" -> 1.55f
            "123", "back", "shift" -> 1.02f
            else -> 1f
        }
    }

    /**
     * A wide canvas (foldable inner display, tablet, big landscape) where a full-width keyboard
     * stretches the keys unpleasantly. Same >=600dp breakpoint the launcher uses for its inner UI.
     */
    private fun imeIsWideCanvas(): Boolean = resources.configuration.screenWidthDp >= 600

    /**
     * The size the key metrics scale from. On a wide canvas we add the SAME inner-screen boost the
     * launcher keyboard applies (shared `inner_keyboard_size_boost` pref, +12) so the IME keys grow
     * taller to match — otherwise the keyboard reads short and wide on the fold's inner screen.
     */
    private fun effectiveKeyboardSize(): Int {
        val base = KeyboardSettings.keyboardSize(this)
        if (!imeIsWideCanvas()) return base
        val boost = imePrefs().getInt("inner_keyboard_size_boost", 52).coerceIn(-20, 150)
        return (base + boost + 12).coerceIn(0, 190)
    }

    /**
     * Centered panel width on a wide canvas — mirrors the launcher's `unfoldedKeyboardPanelWidth`
     * (shared `inner_keyboard_width_percent` pref) so the IME is the same narrower, centered block
     * instead of spanning the whole inner display.
     */
    private fun imeKeyboardPanelWidth(): Int {
        val maxWidth = resources.displayMetrics.widthPixels
        val ceiling = maxWidth - dp(36)
        val pct = imePrefs().getInt("inner_keyboard_width_percent", 68).coerceIn(48, 100)
        // Honour the user's width percent so the panel is genuinely narrower + centered; keep only a
        // small absolute floor so tiny percents can't make it unusably narrow.
        val floor = dp(440).coerceAtMost(ceiling)
        return (maxWidth * pct / 100f).toInt().coerceIn(floor, ceiling)
    }

    private fun imeKeyboardHeight(): Int {
        val rowCount = 4
        return keyRowHeight() * rowCount - keyRowOverlap() * (rowCount - 1) + dp(6) + dp(38)
    }

    private fun keyRowHeight(): Int {
        val size = effectiveKeyboardSize()
        return dp(56 + (size * 20 / 100))
    }

    private fun keyRowOverlap(): Int {
        val size = effectiveKeyboardSize()
        return dp(12 + size * 3 / 100)
    }

    private fun keyVerticalInset(): Int {
        val size = effectiveKeyboardSize()
        val theme = keyboardVisualTheme()
        if (theme == KEYBOARD_THEME_DEFAULT) return dp(7 + size * 4 / 100)
        if (theme != KEYBOARD_THEME_TECLAS) return dp(10 + size * 5 / 100)
        if (theme == KEYBOARD_THEME_TECLAS || theme == KEYBOARD_THEME_GOKEYS || theme == KEYBOARD_THEME_BRUSHED || isHyper3dTheme(theme)) {
            return dp(10 + size * 5 / 100)
        }
        return dp(7 + size * 4 / 100)
    }

    private fun keyHorizontalInset(): Int {
        val theme = keyboardVisualTheme()
        return if (theme == KEYBOARD_THEME_DEFAULT || theme == KEYBOARD_THEME_TECLAS) 0 else dp(3)
    }

    private fun themedGoKeySize(): Int {
        val size = effectiveKeyboardSize()
        return dp(39 + (size * 6 / 100))
    }

    private fun keyTextSize(label: String): Float {
        val size = effectiveKeyboardSize()
        if (KeyboardThemeDrawables.isAddedTheme(keyboardVisualTheme())) {
            val base = when (label) {
                "shift" -> 23f
                "space" -> 18f
                "123", "abc", "enter", "back", "." -> 13.5f
                else -> 20f
            }
            val growth = when (label) {
                "shift" -> 2f
                "space" -> 2f
                "123", "abc", "enter", "back", "." -> 1.4f
                else -> 2.2f
            }
            return base + (size * growth / 100f)
        }
        if (keyboardTheme() == KEYBOARD_THEME_BRUSHED) {
            val brushedBase = when (label) {
                "shift", "." -> 22f
                "back" -> 19f
                "space" -> 18f
                "123", "abc", "enter" -> 15.5f
                "teclas" -> 13.5f
                else -> 19f
            }
            val brushedGrowth = when (label) {
                "123", "abc", "enter", "teclas" -> 1.5f
                "space" -> 2f
                else -> 2.5f
            }
            return brushedBase + (size * brushedGrowth / 100f)
        }
        val base = when (label) {
            "shift" -> 24f
            "space" -> 18f
            "123", "teclas", "enter", "back", "." -> 13.5f
            else -> 20f
        }
        val growth = when (label) {
            "shift" -> 2.5f
            "space" -> 2f
            "123", "teclas", "enter", "back", "." -> 1.5f
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
        val theme = keyboardVisualTheme()
        if (theme == KEYBOARD_THEME_DEFAULT || theme == KEYBOARD_THEME_TECLAS) {
            if (label == "enter" || label == "123") return base
            val vInset = keyVerticalInset()
            return InsetDrawable(base, keyHorizontalInset(), vInset, keyHorizontalInset(), vInset)
        }
        val vInset = keyVerticalInset()
        val fixedInset = dp(2)
        val topBottom = if (label == "enter" || label == "123") fixedInset else vInset
        return InsetDrawable(base, keyHorizontalInset(), topBottom, keyHorizontalInset(), topBottom)
    }

    // Canvas-keyboard drawable cache. visualKeyBackground constructs multi-layer drawables from
    // scratch, and the canvas relayout asks for 72 of them (idle+pressed × ~36 keys) on every
    // keyboard-open, 123/abc swap, and rotation. Keyed by everything that changes their look and
    // cleared automatically when that signature changes (theme swap, dark-mode flip, go-key recolor,
    // caps-lock toggle — Brushed's shift face differs under caps-lock). Safe to share one instance
    // across rebuilds: the canvas sets bounds before every draw and no View owns these drawables.
    private val keyBgCache = HashMap<String, Drawable>()
    private var keyBgCacheSig: String? = null
    private fun cachedKeyBackground(label: String, pressed: Boolean): Drawable {
        val sig = "${keyboardVisualTheme()}|${selectedNeuTokens().mode}|${goKeyColor()}|$capsLock"
        if (sig != keyBgCacheSig) { keyBgCache.clear(); keyBgCacheSig = sig }
        return keyBgCache.getOrPut("$label|$pressed") { visualKeyBackground(label, pressed) }
    }

    private fun keyIdleBackground(label: String): Drawable {
        val theme = keyboardVisualTheme()
        if (KeyboardThemeDrawables.isAddedTheme(theme)) {
            return KeyboardThemeDrawables.keyLayer(
                this,
                theme,
                label,
                pressed = false,
                darkMode = selectedNeuTokens().mode == NeuMode.DARK,
                goColor = if (theme == KEYBOARD_THEME_TECLAS_GLASS) goKeyColor() else KeyboardThemeDrawables.DEFAULT_ACCENT
            )
        }
        if (theme == KEYBOARD_THEME_SEEME) {
            return SeemeDrawables.key(label, pressed = false, density = resources.displayMetrics.density, goColor = goKeyColor())
        }
        if (theme == KEYBOARD_THEME_BRUSHED) {
            val visualLabel = if (label == "shift" && capsLock) "shift_lock" else label
            return BrushedDrawables.key(visualLabel, pressed = false, dark = selectedNeuTokens().mode == NeuMode.DARK, density = resources.displayMetrics.density, docked = false, goColor = goKeyColor())
        }
        if (label == "enter") return themedGoKeyBackground(goKeyColor(), pressed = false, theme = theme)
        hyper3dKeyDrawable(label, theme)?.let { return it }
        if (theme == KEYBOARD_THEME_GOKEYS) return goKeysKeyBackground(label, pressed = false)
        if (label == "123") return themed123KeyBackground(pressed = false, theme = theme)
        return when (theme) {
            KEYBOARD_THEME_TECLAS -> physicalKeyBackground(pressed = false, premium = false, fn = isFnKey(label), theme = theme)
            KEYBOARD_THEME_SKEUO -> physicalKeyBackground(pressed = false, premium = true, fn = isFnKey(label), theme = theme)
            else -> Neu.drawable(selectedNeuTokens(), dp(7).toFloat(), NeuLevel.RAISED_SM)
        }
    }

    private fun keyPressedBackground(label: String): Drawable {
        val theme = keyboardVisualTheme()
        if (KeyboardThemeDrawables.isAddedTheme(theme)) {
            return KeyboardThemeDrawables.keyLayer(
                this,
                theme,
                label,
                pressed = true,
                darkMode = selectedNeuTokens().mode == NeuMode.DARK,
                goColor = if (theme == KEYBOARD_THEME_TECLAS_GLASS) brighten(goKeyColor()) else KeyboardThemeDrawables.DEFAULT_ACCENT
            )
        }
        if (theme == KEYBOARD_THEME_SEEME) {
            return SeemeDrawables.key(label, pressed = true, density = resources.displayMetrics.density, goColor = brighten(goKeyColor()))
        }
        if (theme == KEYBOARD_THEME_BRUSHED) {
            val visualLabel = if (label == "shift" && capsLock) "shift_lock" else label
            return BrushedDrawables.key(visualLabel, pressed = true, dark = selectedNeuTokens().mode == NeuMode.DARK, density = resources.displayMetrics.density, docked = false, goColor = brighten(goKeyColor()))
        }
        if (label == "enter") return themedGoKeyBackground(brighten(goKeyColor()), pressed = true, theme = theme)
        hyper3dKeyDrawable(label, theme)?.let { return it }
        if (theme == KEYBOARD_THEME_GOKEYS) return goKeysKeyBackground(label, pressed = true)
        if (label == "123") return themed123KeyBackground(pressed = true, theme = theme)
        return when (theme) {
            KEYBOARD_THEME_TECLAS -> physicalKeyBackground(pressed = true, premium = false, fn = isFnKey(label), theme = theme)
            KEYBOARD_THEME_SKEUO -> physicalKeyBackground(pressed = true, premium = true, fn = isFnKey(label), theme = theme)
            else -> Neu.drawable(selectedNeuTokens(), dp(7).toFloat(), NeuLevel.PRESSED_SM)
        }
    }

    private fun deckBackground(): Drawable {
        val theme = keyboardVisualTheme()
        if (KeyboardThemeDrawables.isAddedTheme(theme)) {
            return KeyboardThemeDrawables.panel(this, theme, selectedNeuTokens().mode == NeuMode.DARK)
        }
        if (theme == KEYBOARD_THEME_SEEME) return SeemeDrawables.panel(darkTint = true)
        if (theme == KEYBOARD_THEME_BRUSHED) return BrushedDrawables.panel(selectedNeuTokens().mode == NeuMode.DARK, resources.displayMetrics.density)
        if (theme == KEYBOARD_THEME_DEFAULT) return Neu.drawable(selectedNeuTokens(), dp(16).toFloat(), NeuLevel.RAISED)
        val light = keyboardLightMode(theme)
        val colors = if (light) {
            when (theme) {
                KEYBOARD_THEME_SKEUO -> intArrayOf(0xFFF1F3F6.toInt(), 0xFFE0E4EA.toInt(), 0xFFC9D0DA.toInt())
                KEYBOARD_THEME_TECLAS -> intArrayOf(0xFFECEFF4.toInt(), 0xFFDDE2E9.toInt(), 0xFFC7CED9.toInt())
                KEYBOARD_THEME_GOKEYS -> intArrayOf(0xFFF3F5F8.toInt(), 0xFFE2E6EC.toInt(), 0xFFC9D0DA.toInt())
                KEYBOARD_THEME_HYPER3D, KEYBOARD_THEME_HYPER3D_LIGHT -> intArrayOf(0xFFE8EBF0.toInt(), 0xFFD4D8E0.toInt(), 0xFFBEC4CF.toInt())
                else -> intArrayOf(0xFFECEEF2.toInt(), 0xFFDDE1E8.toInt(), 0xFFC9D0DA.toInt())
            }
        } else {
            when (theme) {
                KEYBOARD_THEME_SKEUO -> intArrayOf(0xFF24262D.toInt(), 0xFF101116.toInt(), 0xFF050506.toInt())
                KEYBOARD_THEME_TECLAS -> intArrayOf(0xFF1A1C21.toInt(), 0xFF111318.toInt(), 0xFF08090C.toInt(), 0xFF030304.toInt())
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
        return resolveTeclasNeuTokens(themeMode())
    }

    private fun isSystemDarkMode(): Boolean {
        return teclasSystemDarkMode()
    }

    private fun keyboardLightMode(theme: String): Boolean {
        if (KeyboardThemeDrawables.isAddedTheme(theme)) {
            return KeyboardThemeDrawables.isLight(theme, selectedNeuTokens().mode == NeuMode.DARK)
        }
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
        // Default: Cursor Violet (brand accent). A user-chosen accent overrides it.
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(GO_KEY_COLOR_PREF, 0xFFC9A7FF.toInt())
    }

    private fun seemeReleaseHaptic(view: View) {
        if (!hapticsOn()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
        }
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
                label == "teclas" -> R.drawable.key_hyper3d_black_accent
                isFnKey(label) -> R.drawable.key_hyper3d_black_dark
                else -> R.drawable.key_hyper3d_black
            }
            KEYBOARD_THEME_HYPER3D_LIGHT -> when {
                label == "enter" -> R.drawable.key_hyper3d_light_go
                label == "teclas" -> R.drawable.key_hyper3d_light_accent
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
        val teclas = theme == KEYBOARD_THEME_TECLAS
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
        val faceTop = if (pressed) 0xFF3A3E4A.toInt() else if (teclas) 0xFF22252B.toInt() else 0xFF34373E.toInt()
        val faceMid = if (pressed) 0xFF2A2D36.toInt() else if (teclas) 0xFF171A1F.toInt() else 0xFF202329.toInt()
        val faceBot = if (pressed) 0xFF151719.toInt() else if (teclas) 0xFF07080B.toInt() else 0xFF0B0C10.toInt()
        val skirt = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF050609.toInt()) }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(faceTop, faceMid, faceBot)).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(1), if (teclas) 0xFF111318.toInt() else 0xFF05060A.toInt())
        }
        val glint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(if (teclas) 0x10FFFFFF else 0x3DFFFFFF, 0x00FFFFFF)).apply { shape = GradientDrawable.OVAL }
        return LayerDrawable(arrayOf(skirt, face, glint)).apply {
            val drop = if (pressed) dp(1) else dp(if (teclas) 4 else 6)
            setLayerInset(0, dp(1), drop, dp(1), 0)
            setLayerInset(1, dp(2), 0, dp(2), drop)
            setLayerInset(2, dp(8), dp(5), dp(8), dp(if (teclas) 20 else 18))
        }
    }

    private fun physicalKeyBackground(pressed: Boolean, premium: Boolean, fn: Boolean, theme: String): Drawable {
        val teclas = theme == KEYBOARD_THEME_TECLAS
        val radius = dp(when {
            premium -> 10
            teclas -> 5
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
                teclas -> 0xFF050609.toInt()
                else -> 0xFF07080B.toInt()
            },
            if (teclas) 0xFF020304.toInt() else 0xFF050609.toInt(),
            0xFF010102.toInt()
        )).apply {
            cornerRadius = radius
            setStroke(dp(1), 0xFF040507.toInt())
        }
        val outerRim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            when {
                pressed -> 0xFFFF8E68.toInt()
                premium -> 0xFF525866.toInt()
                teclas -> 0xFF24272F.toInt()
                else -> 0xFF34373E.toInt()
            },
            if (teclas) 0xFF05060A.toInt() else 0xFF090A0D.toInt()
        )).apply {
            cornerRadius = radius
            setStroke(dp(1), if (premium) 0xFF05060A.toInt() else 0xFF040507.toInt())
        }
        val faceColors = when {
            pressed -> intArrayOf(0xFFFF9B72.toInt(), 0xFFFF5A3C.toInt(), 0xFF9C2F11.toInt())
            premium -> intArrayOf(0xFF4B505B.toInt(), 0xFF2D323D.toInt(), 0xFF151821.toInt(), 0xFF08090D.toInt())
            teclas -> intArrayOf(0xFF2B2E35.toInt(), 0xFF202329.toInt(), 0xFF15171C.toInt(), 0xFF0A0B0E.toInt())
            else -> intArrayOf(0xFF34373E.toInt(), 0xFF202329.toInt(), 0xFF14161B.toInt(), 0xFF0B0C10.toInt())
        }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, faceColors).apply {
            cornerRadius = radius
            setStroke(dp(1), if (pressed) 0xFFFFB199.toInt() else if (premium) 0xFF3C4350.toInt() else if (teclas) 0xFF111318.toInt() else 0xFF05060A.toInt())
        }
        val topGlint = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            if (pressed) 0x66FFFFFF else if (premium) 0x55FFFFFF else if (teclas) 0x20FFFFFF else 0x3DFFFFFF,
            0x00FFFFFF
        )).apply { cornerRadius = radius }
        val warmBleed = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(if (fn && !pressed) (if (teclas) 0x16F5C451 else 0x22F5C451) else if (pressed) 0x66FF5A3C else 0x00000000, 0x00000000)).apply { cornerRadius = radius }
        return LayerDrawable(arrayOf(skirt, outerRim, face, warmBleed, topGlint)).apply {
            val drop = if (pressed) dp(1) else dp(when {
                premium -> 8
                teclas -> 8
                else -> 5
            })
            val side = if (premium) dp(2) else dp(if (teclas) 2 else 1)
            setLayerInset(0, dp(1), drop, dp(1), 0)
            setLayerInset(1, side, dp(if (pressed) 1 else 0), side, drop)
            setLayerInset(2, side + dp(1), dp(1), side + dp(1), drop + dp(if (premium) 1 else 0))
            setLayerInset(3, side + dp(2), dp(if (teclas) 5 else 3), side + dp(2), drop + dp(1))
            setLayerInset(4, side + dp(3), dp(2), side + dp(3), dp(if (premium) 19 else if (teclas) 24 else 20))
        }
    }

    private fun themedGoKeyBackground(fillColor: Int, pressed: Boolean, theme: String): Drawable {
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
        return label in setOf("123", "teclas", "back", "shift", ".")
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

    companion object {
        // The live IME instance, so the docked launcher deck can type through its InputConnection.
        @Volatile
        internal var instance: TeclasImeService? = null

        private const val PREFS_NAME = "teclas"
        private const val TOUCH_MODEL_PREF = "touch_model_v1"
        private const val HAPTICS_PREF = "haptics"
        const val HAPTIC_LEVEL_PREF = "haptic_level"   // 0–100, keyboard-only vibration intensity
        private const val THEME_MODE_PREF = "theme_mode"
        private const val THEME_MODE_DARK = "dark"
        private const val THEME_MODE_LIGHT = "light"
        private const val THEME_MODE_SYSTEM = "system"
        private const val KEYBOARD_THEME_PREF = "keyboard_theme"
        private const val KEYBOARD_THEME_DEFAULT = "default"
        private const val KEYBOARD_THEME_TECLAS = "teclas"
        // Canvas keyboard: render the key grid as a single bitmap-backed view (buildKeyboard ~55ms ->
        // ~19ms) instead of ~30 child views. Theme-generic — backgrounds (visualKeyBackground) and
        // colors (textColor) already branch per theme; the canvas additionally drives typeface, letter
        // placement, and Brushed's engraved hints / optical offsets from the same per-theme logic the
        // view keyboard uses (keyTypeface, canvasLetterSpec, canvasOpticalOffset). Validated on Teclas;
        // other themes render through the same faithful path.
        private const val CANVAS_KB = true
        private const val KEYBOARD_THEME_SKEUO = "skeuo"
        private const val KEYBOARD_THEME_GOKEYS = "gokeys"
        private const val KEYBOARD_THEME_HYPER3D = "hyper3d"
        private const val KEYBOARD_THEME_HYPER3D_BLACK = "hyper3d_black"
        private const val KEYBOARD_THEME_HYPER3D_LIGHT = "hyper3d_light"
        private const val KEYBOARD_THEME_BRUSHED = "brushed"
        private const val KEYBOARD_THEME_SEEME = "seeme"
        private const val KEYBOARD_THEME_GOOGLE = KeyboardThemeDrawables.GOOGLE
        private const val KEYBOARD_THEME_IOS = KeyboardThemeDrawables.IOS
        private const val KEYBOARD_THEME_PIXEL_SAND = KeyboardThemeDrawables.PIXEL_SAND
        private const val KEYBOARD_THEME_TECLAS_GLASS = KeyboardThemeDrawables.TECLAS_GLASS
        private const val GO_KEY_COLOR_PREF = "go_key_color"

        // Hot-path regexes, compiled once (these used to be re-compiled on every keystroke).
        private val WORD_RE = Regex("[A-Za-z]+")
        private val NON_LETTER_RE = Regex("[^\\p{L}]+")

        // Visible lifetime of each glide-trail point: the tail melts away this long after the
        // finger passed through (Gboard-style), instead of the whole trail lingering then blinking
        // out. Also how long the trail takes to fully glide out after release.
        private const val GLIDE_TRAIL_WINDOW_MS = 320L

        // Temporary: keystroke-path timing to logcat (tag TeclasPerf) to localize the freeze.
        private const val PERF_LOG = false
        // Narrow render-only diagnostic: times view construction at keyboard-open and samples a short
        // burst of frames after each interaction (NOT continuously — the old always-on monitor pinned
        // the display at 60fps and added the very jank it measured). Independent of PERF_LOG so it can
        // run alone with almost none of the keystroke-path overhead.
        private const val RENDER_LOG = false
    }
}
