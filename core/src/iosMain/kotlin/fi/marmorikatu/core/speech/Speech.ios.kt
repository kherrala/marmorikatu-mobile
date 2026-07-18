package fi.marmorikatu.core.speech

import fi.marmorikatu.core.config.AssistantGender
import fi.marmorikatu.core.config.SpeechLanguage
import fi.marmorikatu.core.log.logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesisVoiceGender
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.setActive
import platform.Foundation.NSLocale
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionResult
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * Native STT via the Speech framework, streaming microphone buffers from
 * AVAudioEngine into a live recognition request.
 *
 * Finnish support is not guaranteed on every iOS version or device (many iPads
 * ship without it), so [isAvailable] checks the recogniser at runtime; when it
 * says no, the user can switch the assistant to English in settings, which every
 * device supports.
 */
@OptIn(ExperimentalForeignApi::class)
actual class PlatformStt actual constructor() : SpeechToText {
    private val log = logger("stt-native")
    override val name = "ios-speech"

    // Rebuilt whenever the language changes; starts on the house default.
    private var recognizer: SFSpeechRecognizer? =
        SFSpeechRecognizer(locale = NSLocale(localeIdentifier = SpeechLanguage.Finnish.iosLocale))

    override fun useLanguage(language: SpeechLanguage) {
        recognizer = SFSpeechRecognizer(locale = NSLocale(localeIdentifier = language.iosLocale))
    }

    private val audioEngine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null

    override suspend fun isAvailable(): Boolean {
        val recognizer = recognizer ?: return false
        if (!recognizer.isAvailable()) return false
        return requestAuthorization()
    }

    private suspend fun requestAuthorization(): Boolean =
        suspendCancellableCoroutine { cont ->
            SFSpeechRecognizer.requestAuthorization { status ->
                cont.resume(status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized)
            }
        }

    override fun listen(): Flow<SttEvent> = callbackFlow {
        val recognizer = recognizer
        if (recognizer == null || !recognizer.isAvailable()) {
            trySend(SttEvent.Error("puheentunnistus ei ole käytettävissä"))
            close()
            return@callbackFlow
        }

        // Measurement mode keeps iOS from applying its own processing to the
        // signal we are about to transcribe.
        val session = AVAudioSession.sharedInstance()
        session.setCategory(
            AVAudioSessionCategoryPlayAndRecord,
            mode = AVAudioSessionModeMeasurement,
            options = AVAudioSessionCategoryOptionDefaultToSpeaker,
            error = null,
        )
        session.setActive(true, error = null)

        val recognitionRequest = SFSpeechAudioBufferRecognitionRequest().apply {
            shouldReportPartialResults = true
        }
        request = recognitionRequest

        task = recognizer.recognitionTaskWithRequest(recognitionRequest) { result, error ->
            val recognition = result as? SFSpeechRecognitionResult
            if (recognition != null) {
                val text = recognition.bestTranscription.formattedString
                if (recognition.isFinal()) {
                    trySend(if (text.isBlank()) SttEvent.Error("puhetta ei tunnistettu") else SttEvent.Final(text))
                    close()
                } else if (text.isNotBlank()) {
                    trySend(SttEvent.Partial(text))
                }
            }
            if (error != null) {
                log.w { "recognition failed: ${error.localizedDescription}" }
                trySend(SttEvent.Error("puheentunnistus epäonnistui"))
                close()
            }
        }

        val input = audioEngine.inputNode
        val format = input.outputFormatForBus(0u)
        input.installTapOnBus(bus = 0u, bufferSize = 1024u, format = format) { buffer, _ ->
            (buffer as? AVAudioPCMBuffer)?.let(recognitionRequest::appendAudioPCMBuffer)
        }
        audioEngine.prepare()
        runCatching { audioEngine.startAndReturnError(null) }
            .onFailure {
                trySend(SttEvent.Error("mikrofonia ei voitu avata"))
                close()
            }

        awaitClose { teardown() }
    }

    override suspend fun stopListening() {
        // End the audio, but let the task deliver its final transcription.
        if (audioEngine.running) audioEngine.stop()
        audioEngine.inputNode.removeTapOnBus(0u)
        request?.endAudio()
    }

    private fun teardown() {
        if (audioEngine.running) audioEngine.stop()
        runCatching { audioEngine.inputNode.removeTapOnBus(0u) }
        task?.cancel()
        task = null
        request = null
    }
}

/** Native TTS via AVSpeechSynthesizer in the user's chosen [SpeechLanguage]. */
actual class PlatformTts actual constructor() : SpeechOutput {
    private val log = logger("tts-native")
    override val name = "ios-avspeech"

    private val synthesizer = AVSpeechSynthesizer()

    // The active voice, reselected on any language or persona change. iOS *does*
    // expose voice gender, so the persona match is reliable when the language has
    // both voices; language drives which set of voices we pick from.
    private var language: SpeechLanguage = SpeechLanguage.Finnish
    private var gender: AssistantGender = AssistantGender.Nainen
    private var voice: AVSpeechSynthesisVoice? =
        AVSpeechSynthesisVoice.voiceWithLanguage(SpeechLanguage.Finnish.bcp47)

    override fun useVoice(gender: AssistantGender) {
        this.gender = gender
        reselectVoice()
    }

    override fun useLanguage(language: SpeechLanguage) {
        this.language = language
        reselectVoice()
    }

    /**
     * Pick a voice in the active [language] matching the [gender] persona. Match
     * on the primary subtag ("fi"/"en") so regional variants (en-GB, en-AU) still
     * count. Falls back to the compact system voice for the language, so a device
     * with no installed voice list still speaks.
     */
    private fun reselectVoice() {
        val voices = AVSpeechSynthesisVoice.speechVoices()
            .filterIsInstance<AVSpeechSynthesisVoice>()
            .filter { it.language.startsWith(language.prefix) }
        if (voices.isEmpty()) {
            voice = AVSpeechSynthesisVoice.voiceWithLanguage(language.bcp47)
            return
        }
        // Prefer the exact locale (fi-FI, en-US) over a regional cousin.
        fun rank(v: AVSpeechSynthesisVoice) = if (v.language == language.bcp47) 0 else 1
        val target = if (gender == AssistantGender.Mies) {
            AVSpeechSynthesisVoiceGender.AVSpeechSynthesisVoiceGenderMale
        } else {
            AVSpeechSynthesisVoiceGender.AVSpeechSynthesisVoiceGenderFemale
        }
        voice = voices.filter { it.gender == target }.minByOrNull(::rank)
            ?: voices.filter { it.gender == AVSpeechSynthesisVoiceGender.AVSpeechSynthesisVoiceGenderUnspecified }.minByOrNull(::rank)
            ?: voices.minByOrNull(::rank)
    }

    /**
     * `AVSpeechSynthesizer.delegate` is a weak ObjC property. Held only by
     * the synthesizer, the Kotlin object would be collected and the
     * did-finish callback never delivered — leaving speak() suspended
     * forever. This strong reference keeps it alive.
     */
    private val delegate = SpeechDelegate()

    init {
        synthesizer.delegate = delegate
    }

    private class SpeechDelegate : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        var onFinish: (() -> Unit)? = null

        // The cancel callback has the same Kotlin signature as this one, so
        // it cannot also be overridden; cancellation is instead handled by
        // invokeOnCancellation, which clears onFinish and stops the engine.
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didFinishSpeechUtterance: AVSpeechUtterance,
        ) {
            onFinish?.invoke()
        }
    }

    override suspend fun isAvailable(): Boolean = voice != null

    override suspend fun speak(text: String) {
        val fiVoice = voice ?: return
        suspendCancellableCoroutine { cont ->
            val utterance = AVSpeechUtterance.speechUtteranceWithString(text)
            utterance.voice = fiVoice
            delegate.onFinish = {
                delegate.onFinish = null
                if (cont.isActive) cont.resume(Unit)
            }
            cont.invokeOnCancellation {
                delegate.onFinish = null
                synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
            }
            synthesizer.speakUtterance(utterance)
        }
    }

    override fun stop() {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }
}
