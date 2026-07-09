package fi.marmorikatu.core.repository

import fi.marmorikatu.core.model.CachedSpeech
import fi.marmorikatu.core.model.ChatEvent
import fi.marmorikatu.core.model.ChatMessage
import fi.marmorikatu.core.transport.bridge.BridgeApi
import kotlinx.coroutines.flow.Flow

interface AssistantRepository {
    /** One conversation turn; completes after [ChatEvent.Done]. */
    fun chat(messages: List<ChatMessage>): Flow<ChatEvent>

    /** Server-side Whisper transcription of recorded audio. */
    suspend fun transcribe(audio: ByteArray, mimeType: String, fileName: String): String

    /** Server-side Piper TTS, one WAV per sentence. */
    fun tts(text: String): Flow<ByteArray>

    suspend fun cachedGreeting(): CachedSpeech
    suspend fun cachedReport(): CachedSpeech
}

class DefaultAssistantRepository(private val bridge: BridgeApi) : AssistantRepository {
    override fun chat(messages: List<ChatMessage>): Flow<ChatEvent> = bridge.chatStream(messages)

    override suspend fun transcribe(audio: ByteArray, mimeType: String, fileName: String): String =
        bridge.transcribe(audio, mimeType, fileName)

    override fun tts(text: String): Flow<ByteArray> = bridge.tts(text)

    override suspend fun cachedGreeting(): CachedSpeech = bridge.cached("greeting")
    override suspend fun cachedReport(): CachedSpeech = bridge.cached("report")
}
