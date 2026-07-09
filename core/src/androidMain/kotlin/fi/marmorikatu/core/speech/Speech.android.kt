package fi.marmorikatu.core.speech

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import fi.marmorikatu.core.log.logger
import fi.marmorikatu.core.platform.AndroidContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Native STT via Android SpeechRecognizer with `fi-FI`. Mostly powered by
 * Google's recognition service (network); availability checked at runtime.
 */
actual class PlatformStt actual constructor() : SpeechToText {
    private val log = logger("stt-native")
    override val name = "android-speechrecognizer"

    private var recognizer: SpeechRecognizer? = null

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.Main) {
        SpeechRecognizer.isRecognitionAvailable(AndroidContext.app)
    }

    override fun listen(): Flow<SttEvent> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(AndroidContext.app)) {
            // Usually a missing <queries> entry or a device with no recogniser.
            trySend(SttEvent.Error("puheentunnistus ei ole käytettävissä"))
            close()
            return@callbackFlow
        }

        val rec = SpeechRecognizer.createSpeechRecognizer(AndroidContext.app)
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.results()?.firstOrNull()?.let { trySend(SttEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.results()?.firstOrNull().orEmpty()
                trySend(if (text.isBlank()) SttEvent.Error("puhetta ei tunnistettu") else SttEvent.Final(text))
                close()
            }

            override fun onError(error: Int) {
                log.w { "SpeechRecognizer error $error" }
                trySend(SttEvent.Error(errorText(error)))
                close()
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fi-FI")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fi-FI")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Some recognisers reject the request without a calling package.
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, AndroidContext.app.packageName)
            // Stop on silence rather than waiting for the engine's own default.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_000L)
        }
        rec.startListening(intent)
        awaitClose {
            runCatching { rec.destroy() }
            recognizer = null
        }
    }.flowOn(Dispatchers.Main)

    private fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "puhetta ei tunnistettu"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "en kuullut mitään"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "mikrofonilupa puuttuu"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "verkkovirhe"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "tunnistin on varattu"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "suomea ei tueta laitteella"
        else -> "puheentunnistus epäonnistui ($error)"
    }

    override suspend fun stopListening() = withContext(Dispatchers.Main) {
        recognizer?.stopListening() ?: Unit
    }

    private fun Bundle.results(): List<String>? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
}

/** Native TTS via Android TextToSpeech with a Finnish voice. */
actual class PlatformTts actual constructor() : SpeechOutput {
    private val log = logger("tts-native")
    override val name = "android-tts"

    private var tts: TextToSpeech? = null
    private var finnishSupported = false
    private val initialized = CompletableDeferred<Boolean>()

    init {
        tts = TextToSpeech(AndroidContext.app) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                val result = engine.setLanguage(Locale.forLanguageTag("fi-FI"))
                finnishSupported = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                installListener(engine)
                initialized.complete(finnishSupported)
            } else {
                initialized.complete(false)
            }
        }
    }

    override suspend fun isAvailable(): Boolean = initialized.await()

    /**
     * One listener for the engine's lifetime, dispatching by utterance id.
     * Re-installing a listener per call would drop the completion of an
     * utterance still queued from the previous sentence, hanging its
     * continuation forever.
     */
    private val pending = mutableMapOf<String, (Unit) -> Unit>()

    private fun installListener(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onDone(id: String?) = complete(id)

            @Deprecated("Deprecated in API")
            override fun onError(id: String?) = complete(id)

            override fun onError(id: String?, errorCode: Int) = complete(id)

            override fun onStart(id: String?) = Unit

            private fun complete(id: String?) {
                val callback = id?.let { synchronized(pending) { pending.remove(it) } }
                callback?.invoke(Unit)
            }
        })
    }

    override suspend fun speak(text: String) {
        if (!initialized.await()) return
        val engine = tts ?: return
        val utteranceId = "mk-${utteranceCounter++}"
        suspendCancellableCoroutine { cont ->
            synchronized(pending) {
                pending[utteranceId] = { if (cont.isActive) cont.resume(Unit) }
            }
            cont.invokeOnCancellation {
                synchronized(pending) { pending.remove(utteranceId) }
                engine.stop()
            }
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        }
    }

    private var utteranceCounter = 0

    override fun stop() {
        tts?.stop()
    }
}
