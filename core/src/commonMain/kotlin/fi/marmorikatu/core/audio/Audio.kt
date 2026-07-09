package fi.marmorikatu.core.audio

import kotlinx.coroutines.flow.StateFlow

/** Result of one recording session. */
data class RecordedAudio(
    val bytes: ByteArray,
    /** `audio/mp4` (AAC in an mp4/m4a container) on both platforms. */
    val mimeType: String,
    val fileName: String,
)

/**
 * Push-to-talk microphone recorder. Android: MediaRecorder (AAC, 16 kHz
 * mono); iOS: AVAudioRecorder (kAudioFormatMPEG4AAC, same parameters).
 * Whisper handles the mp4 container fine — the kiosk already uploads mp4.
 */
expect class AudioRecorder() {
    suspend fun start()
    suspend fun stop(): RecordedAudio?
    fun cancel()
}

sealed interface PlayerState {
    data object Idle : PlayerState
    data object Playing : PlayerState
}

/**
 * Sequential playback queue for per-sentence WAV clips. Clip boundaries are
 * sentence boundaries, so small gaps between clips are acceptable in v1.
 */
expect class AudioPlayer() {
    val state: StateFlow<PlayerState>
    /** Enqueues one WAV clip; playback starts immediately if idle. */
    fun enqueue(wavBytes: ByteArray)
    fun stop()
}

/** Microphone permission. */
expect class MicPermission() {
    /** Requests if needed; returns whether recording is allowed. */
    suspend fun ensureGranted(): Boolean
}
