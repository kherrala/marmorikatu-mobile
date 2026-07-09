package fi.marmorikatu.core.speech

import fi.marmorikatu.core.log.logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.Foundation.NSLocale
import platform.Speech.SFSpeechRecognizer
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * Native STT via the Speech framework. Finnish support depends on the iOS
 * version and device — [isAvailable] checks at runtime; when unavailable,
 * callers fall back to the server Whisper engine.
 *
 * Live audio capture via AVAudioEngine + SFSpeechAudioBufferRecognitionRequest
 * is a Phase-4+ enhancement; v1 reports unavailable unless the recognizer
 * exists AND is available, and the debug screen surfaces which engine ran.
 */
actual class PlatformStt actual constructor() : SpeechToText {
    private val log = logger("stt-native")
    override val name = "ios-speech"

    private val recognizer: SFSpeechRecognizer? =
        SFSpeechRecognizer(locale = NSLocale(localeIdentifier = "fi_FI"))

    override suspend fun isAvailable(): Boolean =
        recognizer?.isAvailable() == true

    override fun listen(): Flow<SttEvent> = callbackFlow {
        // Buffer-based live recognition needs AVAudioEngine plumbing that is
        // deliberately deferred; emit a clear error so the engine switcher
        // falls back to server STT instead of silently hanging.
        trySend(SttEvent.Error("iOS-natiivi puheentunnistus ei ole vielä käytössä"))
        close()
        awaitClose { }
    }

    override suspend fun stopListening() {}
}

/** Native TTS via AVSpeechSynthesizer with the Finnish system voice. */
actual class PlatformTts actual constructor() : SpeechOutput {
    private val log = logger("tts-native")
    override val name = "ios-avspeech"

    private val synthesizer = AVSpeechSynthesizer()
    private val voice: AVSpeechSynthesisVoice? =
        AVSpeechSynthesisVoice.voiceWithLanguage("fi-FI")

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
