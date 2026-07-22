package com.fran.teclas.keyboard.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * The seam every voice-to-text backend implements. The keyboard talks ONLY to this interface — the
 * mic button, the RECORD_AUDIO permission, and the text-commit path never change — so an on-device
 * open-source recognizer (Whisper / streaming Zipformer via sherpa-onnx) drops in behind it later
 * without touching any of the UI.
 *
 * The plan the interface is shaped for:
 *   1. an OSS acoustic model (Whisper / Zipformer) does audio → text on-device,
 *   2. [hotwords] biases decoding toward the user's OWN vocabulary (contacts, app names, dictionary)
 *      so voice gets the same names right that typing already learned,
 *   3. the caller runs the result back through the keyboard's own dictionary / autocorrect / n-gram
 *      context, so voice and typing share one language brain.
 * The default [AndroidVoiceInputEngine] covers 1 today; 2–3 land when sherpa-onnx is wired.
 */
interface VoiceInputEngine {
    fun isAvailable(): Boolean

    /**
     * Begin listening. All callbacks fire on the main thread.
     * @param hotwords personal vocabulary to bias the decoder toward (honored by engines that support
     *   contextual biasing; ignored — harmlessly — by ones that don't).
     */
    fun start(languageTag: String, hotwords: List<String>, cb: Callbacks)
    fun stop()
    val isListening: Boolean

    interface Callbacks {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onStateChanged(listening: Boolean)
        fun onError(message: String)
    }
}

/**
 * Zero-dependency default: Android's on-device [SpeechRecognizer] (offline on API 31+ when a language
 * package is installed, cloud fallback otherwise). It streams partials and works today, which lets the
 * mic button + commit flow ship and be tested now; the OSS engine replaces it behind [VoiceInputEngine].
 * Contextual biasing isn't supported by this backend — [hotwords] is accepted and ignored.
 */
class AndroidVoiceInputEngine(private val context: Context) : VoiceInputEngine {
    private var recognizer: SpeechRecognizer? = null
    override var isListening: Boolean = false
        private set

    override fun isAvailable(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    override fun start(languageTag: String, hotwords: List<String>, cb: VoiceInputEngine.Callbacks) {
        if (isListening) return
        if (!isAvailable()) { cb.onError("Voice recognition unavailable on this device"); return }
        val onDevice = Build.VERSION.SDK_INT >= 31 &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        val rec = runCatching {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
        }.getOrNull() ?: run { cb.onError("Couldn't start voice"); return }
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                isListening = false; cb.onStateChanged(false)
                // NO_MATCH / SPEECH_TIMEOUT are the normal "you stopped talking" endings — not errors.
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    cb.onError(errorText(error))
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                isListening = false; cb.onStateChanged(false)
                if (text.isNotBlank()) cb.onFinal(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) cb.onPartial(text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        isListening = true; cb.onStateChanged(true)
        runCatching { rec.startListening(intent) }.onFailure {
            isListening = false; cb.onStateChanged(false); cb.onError("Couldn't start voice")
        }
    }

    override fun stop() {
        isListening = false
        recognizer?.let { r ->
            runCatching { r.stopListening() }
            runCatching { r.cancel() }
            runCatching { r.destroy() }
        }
        recognizer = null
    }

    private fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "No offline voice model — install the language pack in system Voice settings"
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice is busy — try again"
        else -> "Voice error"
    }
}
