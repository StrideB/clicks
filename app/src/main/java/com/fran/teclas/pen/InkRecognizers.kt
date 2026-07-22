package com.fran.teclas.pen

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink

/**
 * On-device handwriting recognition via ML Kit Digital Ink — the same recognition family
 * Gboard's handwriting keyboard uses. Language models (~20 MB each) download once on first
 * use and then everything runs locally.
 *
 * One recognizer per language, cached for the process lifetime. Model download is kicked off
 * lazily on the first [recognize] call; strokes drawn before the model lands get a status
 * line instead of silently vanishing.
 */
object InkRecognizers {

    private val recognizers = HashMap<String, DigitalInkRecognizer>()
    private val modelReady = HashSet<String>()
    private val modelRequested = HashSet<String>()

    /** Keyboard language code (DictionaryLoader codes) → digital-ink model language tag. */
    fun languageTagFor(code: String): String = when (code.lowercase()) {
        "es" -> "es-ES"
        "fr" -> "fr-FR"
        "de" -> "de-DE"
        "pt" -> "pt-PT"
        "it" -> "it-IT"
        else -> "en-US"
    }

    /**
     * Recognize [ink] in [languageTag]. [onText] gets the top candidate; [onStatus] gets
     * short user-facing progress/error copy ("downloading pen model…").
     */
    fun recognize(languageTag: String, ink: Ink, onText: (String) -> Unit, onStatus: (String) -> Unit) {
        if (ink.strokes.isEmpty()) return
        val model = modelFor(languageTag) ?: run {
            onStatus("pen: language not supported")
            return
        }
        if (languageTag in modelReady) {
            runRecognition(languageTag, model, ink, onText, onStatus)
            return
        }
        val manager = RemoteModelManager.getInstance()
        manager.isModelDownloaded(model)
            .addOnSuccessListener { downloaded ->
                if (downloaded) {
                    modelReady.add(languageTag)
                    runRecognition(languageTag, model, ink, onText, onStatus)
                } else {
                    if (modelRequested.add(languageTag)) {
                        onStatus("downloading pen model…")
                        manager.download(model, DownloadConditions.Builder().build())
                            .addOnSuccessListener {
                                modelReady.add(languageTag)
                                onStatus("pen ready — write again")
                            }
                            .addOnFailureListener {
                                modelRequested.remove(languageTag)
                                onStatus("pen model download failed")
                            }
                    } else {
                        onStatus("downloading pen model…")
                    }
                }
            }
            .addOnFailureListener { onStatus("pen model check failed") }
    }

    private fun runRecognition(
        languageTag: String,
        model: DigitalInkRecognitionModel,
        ink: Ink,
        onText: (String) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        val recognizer = recognizers.getOrPut(languageTag) {
            DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        }
        recognizer.recognize(ink)
            .addOnSuccessListener { result ->
                val text = result.candidates.firstOrNull()?.text?.trim().orEmpty()
                if (text.isNotEmpty()) onText(text)
            }
            .addOnFailureListener { onStatus("pen recognition failed") }
    }

    private fun modelFor(languageTag: String): DigitalInkRecognitionModel? = runCatching {
        DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
            ?.let { DigitalInkRecognitionModel.builder(it).build() }
    }.getOrNull()
}
