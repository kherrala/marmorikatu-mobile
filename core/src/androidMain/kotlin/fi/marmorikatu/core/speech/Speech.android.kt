package fi.marmorikatu.core.speech

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import fi.marmorikatu.core.config.AssistantGender
import fi.marmorikatu.core.config.SpeechLanguage
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
 * Native STT via Android SpeechRecognizer. Mostly powered by Google's
 * recognition service (network); availability checked at runtime. The language
 * follows the user's [SpeechLanguage] choice.
 */
actual class PlatformStt actual constructor() : SpeechToText {
    private val log = logger("stt-native")
    override val name = "android-speechrecognizer"

    private var recognizer: SpeechRecognizer? = null

    @Volatile private var languageTag: String = SpeechLanguage.Finnish.bcp47

    override fun useLanguage(language: SpeechLanguage) {
        languageTag = language.bcp47
    }

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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Some recognisers reject the request without a calling package.
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, AndroidContext.app.packageName)
            // Wait out mid-sentence pauses before finalising, so a slow or
            // thinking speaker isn't cut off. (These are hints; Google's
            // recogniser may clamp them, but longer is better than the ~1 s default.)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_500L)
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
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "kieltä ei tueta laitteella"
        else -> "puheentunnistus epäonnistui ($error)"
    }

    override suspend fun stopListening() = withContext(Dispatchers.Main) {
        recognizer?.stopListening() ?: Unit
    }

    private fun Bundle.results(): List<String>? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
}

/** Native TTS via Android TextToSpeech in the user's chosen [SpeechLanguage]. */
actual class PlatformTts actual constructor() : SpeechOutput {
    private val log = logger("tts-native")
    override val name = "android-tts"

    private var tts: TextToSpeech? = null
    private var languageSupported = false
    private val initialized = CompletableDeferred<Boolean>()

    // The persona and language to voice; applied once the engine is ready and
    // again on every change. Volatile because they are read from the speak()
    // coroutine and the language may be set before the engine finishes init.
    @Volatile private var desiredGender: AssistantGender = AssistantGender.Nainen
    @Volatile private var language: SpeechLanguage = SpeechLanguage.Finnish

    init {
        tts = TextToSpeech(AndroidContext.app) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                applyLanguage(engine)
                installListener(engine)
                applyVoice(engine)
                // isAvailable() reports the *engine* being ready; a specific
                // language being missing surfaces per-language via applyLanguage.
                initialized.complete(true)
            } else {
                initialized.complete(false)
            }
        }
    }

    override fun useVoice(gender: AssistantGender) {
        desiredGender = gender
        tts?.let { if (initialized.isCompleted) applyVoice(it) }
    }

    override fun useLanguage(language: SpeechLanguage) {
        this.language = language
        tts?.let {
            if (initialized.isCompleted) {
                applyLanguage(it)
                applyVoice(it)
            }
        }
    }

    /** Point the engine at the current [language]; record whether it is present. */
    private fun applyLanguage(engine: TextToSpeech) {
        val result = engine.setLanguage(Locale.forLanguageTag(language.bcp47))
        languageSupported = result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /**
     * Pick a voice in the current [language] matching [desiredGender]. Android
     * exposes no gender field, so we rank the installed voices by name/feature
     * hints ("female"/"male", or Google's speaker-letter convention) and, absent
     * any hint, split the sorted set in two so the two personas at least get
     * *distinct* voices. Falls back to the engine default when there's nothing
     * to choose from.
     */
    private fun applyVoice(engine: TextToSpeech) {
        val prefix = language.prefix
        val candidates = runCatching { engine.voices }.getOrNull().orEmpty()
            .filter { it.locale?.language == prefix }
        if (candidates.isEmpty()) return

        // Android exposes no gender field; 1 = male, 0 = female, -1 = no hint.
        // Google's speaker letters alternate gender (…a/c/e ≈ female, …b/d/f ≈
        // male); some engines put "male"/"female" in the name outright.
        fun hint(v: Voice): Int {
            val n = v.name.lowercase()
            return when {
                "female" in n || Regex("-..[ace]\\b|#female").containsMatchIn(n) -> 0
                "male" in n || Regex("-..[bdf]\\b|#male").containsMatchIn(n) -> 1
                else -> -1
            }
        }
        val wantMale = desiredGender == AssistantGender.Mies
        val labelled = candidates.filter { hint(it) != -1 }
        val chosen = when {
            labelled.isNotEmpty() ->
                labelled.firstOrNull { (hint(it) == 1) == wantMale } ?: labelled.first()
            // No hints: split the name-sorted voices so each persona at least
            // gets a stable, distinct voice.
            candidates.size >= 2 -> candidates.sortedBy { it.name }.let { if (wantMale) it.last() else it.first() }
            else -> candidates.first()
        }
        runCatching { engine.setVoice(chosen) }
    }

    // Ready only when the engine initialised *and* the chosen language's voice
    // data is installed; otherwise the caller paces the stream itself instead of
    // speaking into a language the device can't synthesise.
    override suspend fun isAvailable(): Boolean = initialized.await() && languageSupported

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
