package fi.marmorikatu.core.speech

import fi.marmorikatu.core.audio.AudioPlayer
import fi.marmorikatu.core.audio.AudioRecorder
import fi.marmorikatu.core.repository.AssistantRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Voice I/O is pluggable: native platform engines and the server pipeline
 * (Whisper + Piper) are interchangeable behind these two interfaces, and
 * A/B-switchable on the debug screen.
 */

sealed interface SttEvent {
    /** Live partial transcript (native engines only). */
    data class Partial(val text: String) : SttEvent
    data class Final(val text: String) : SttEvent
    data class Error(val message: String) : SttEvent
}

interface SpeechToText {
    val name: String
    /** Whether this engine can transcribe Finnish on this device. */
    suspend fun isAvailable(): Boolean
    /** Starts listening; the flow completes after a Final or Error event. */
    fun listen(): Flow<SttEvent>
    /** Stops capture early (push-to-talk release). */
    suspend fun stopListening()
}

interface SpeechOutput {
    val name: String
    suspend fun isAvailable(): Boolean
    /** Speaks one sentence/utterance; suspends until playback finishes. */
    suspend fun speak(text: String)
    fun stop()
}

/**
 * Server STT: record m4a → POST /transcribe (faster-whisper on the GPU box).
 * The guaranteed-quality Finnish path.
 */
class ServerStt(
    private val recorder: AudioRecorder,
    private val assistant: AssistantRepository,
) : SpeechToText {
    override val name = "server-whisper"
    override suspend fun isAvailable(): Boolean = true

    private val mutex = Mutex()
    private var stopSignal: CompletableDeferred<Unit>? = null

    /**
     * A release that arrives before the recording coroutine has started is
     * remembered here; otherwise the signal would be delivered to a stale
     * deferred and the microphone would stay open indefinitely.
     */
    private var stopPending = false

    override fun listen(): Flow<SttEvent> = flow {
        val signal = CompletableDeferred<Unit>()
        mutex.withLock {
            stopSignal = signal
            if (stopPending) {
                stopPending = false
                signal.complete(Unit)
            }
        }
        recorder.start()
        val recorded = try {
            signal.await() // push-to-talk release
            recorder.stop()
        } catch (e: CancellationException) {
            // The press was aborted (new press, screen left): free the mic.
            recorder.cancel()
            throw e
        } finally {
            mutex.withLock { stopSignal = null }
        }

        if (recorded == null) {
            emit(SttEvent.Error("tallennus epäonnistui"))
            return@flow
        }
        val text = assistant.transcribe(recorded.bytes, recorded.mimeType, recorded.fileName)
        if (text.isBlank()) emit(SttEvent.Error("puhetta ei tunnistettu"))
        else emit(SttEvent.Final(text))
    }

    override suspend fun stopListening() {
        mutex.withLock {
            val signal = stopSignal
            if (signal != null) signal.complete(Unit) else stopPending = true
        }
    }
}

/**
 * Server TTS: Piper WAV clips played through the shared [AudioPlayer].
 * This is the shared "house voice" (fi_FI-asmo), same as the kiosk.
 */
class ServerTts(
    private val assistant: AssistantRepository,
    private val player: AudioPlayer,
) : SpeechOutput {
    override val name = "server-piper"
    override suspend fun isAvailable(): Boolean = true

    override suspend fun speak(text: String) {
        assistant.tts(text).collect { wav -> player.enqueue(wav) }
    }

    override fun stop() = player.stop()
}

/** Native platform STT (Android SpeechRecognizer / iOS Speech framework). */
expect class PlatformStt() : SpeechToText

/** Native platform TTS (Android TextToSpeech / iOS AVSpeechSynthesizer). */
expect class PlatformTts() : SpeechOutput
