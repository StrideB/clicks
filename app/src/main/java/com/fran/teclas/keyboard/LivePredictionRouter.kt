package com.fran.teclas.keyboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LETTER_KEYS = "qwertyuiopasdfghjklzxcvbnm"

/**
 * Async prediction pipeline:
 *   text change → debounce 25ms → distinctUntilChanged
 *   → mapLatest (Dispatchers.Default) → route suggestions to per-key DynamicFlickKeyView
 *   → collect on Main (caller's scope)
 *
 * Routing logic (BlackBerry style):
 *   For each suggestion, look at suggestion[currentWord.length] — the NEXT character the user
 *   would type — and display the full word on that key. Typing towards a key reveals the word.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LivePredictionRouter(
    private val scope: CoroutineScope,
    private val getKeyView: (String) -> DynamicFlickKeyView?,
    private val getSuggestions: (word: String) -> List<String>
) {
    private val compositionFlow = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    fun onTextChanged(currentWord: String) {
        compositionFlow.tryEmit(currentWord)
    }

    // Removed: BlackBerry-style per-key word prediction (showing words ON the keys). It duplicated the
    // suggestion strip and was confusing, so the router no longer runs. Left inert so callers compile;
    // onTextChanged is a harmless no-op. Delete this class + its wiring when convenient.
    fun start() { /* no-op — feature removed */ }
}
