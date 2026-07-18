package fi.marmorikatu.core.speech

import fi.marmorikatu.core.config.AssistantGender
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
 * Finnish support is not guaranteed on every iOS version or device, so
 * [isAvailable] checks the recogniser at runtime; when it says no, the caller
 * falls back to the server Whisper pipeline.
 */
@OptIn(ExperimentalForeignApi::class)
actual class PlatformStt actual constructor() : SpeechToText {
    private val log = logger("stt-native")
    override val name = "ios-speech"

    private val recognizer: SFSpeechRecognizer? =
        SFSpeechRecognizer(locale = NSLocale(localeIdentifier = "fi_FI"))

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

/** Native TTS via AVSpeechSynthesizer with the Finnish system voice. */
actual class PlatformTts actual constructor() : SpeechOutput {
    private val log = logger("tts-native")
    override val name = "ios-avspeech"

    private val synthesizer = AVSpeechSynthesizer()

    // The active voice; reselected by persona. iOS *does* expose voice gender,
    // so this is a reliable match when the device has both fi-FI voices.
    private var voice: AVSpeechSynthesisVoice? =
        AVSpeechSynthesisVoice.voiceWithLanguage("fi-FI")

    override fun useVoice(gender: AssistantGender) {
        val fiVoices = AVSpeechSynthesisVoice.speechVoices()
            .filterIsInstance<AVSpeechSynthesisVoice>()
            .filter { it.language == "fi-FI" }
        if (fiVoices.isEmpty()) return
        val target = if (gender == AssistantGender.Mies) {
            AVSpeechSynthesisVoiceGender.AVSpeechSynthesisVoiceGenderMale
        } else {
            AVSpeechSynthesisVoiceGender.AVSpeechSynthesisVoiceGenderFemale
        }
        voice = fiVoices.firstOrNull { it.gender == target }
            ?: fiVoices.firstOrNull { it.gender == AVSpeechSynthesisVoiceGender.AVSpeechSynthesisVoiceGenderUnspecified }
            ?: fiVoices.first()
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
