package fi.marmorikatu.core.model

import kotlinx.serialization.Serializable

/** One turn in a claude-bridge conversation. */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    /** Optional base64 JPEG images for vision requests. */
    val images: List<String> = emptyList(),
) {
    companion object {
        fun user(text: String) = ChatMessage("user", text)
        fun assistant(text: String) = ChatMessage("assistant", text)
    }
}

/** Events streamed by `POST /chat/stream` (SSE over POST). */
sealed interface ChatEvent {
    /** The model invoked an MCP tool. */
    data class ToolUse(val tool: String) : ChatEvent

    /** One sentence of the reply, as soon as it is complete. */
    data class Text(val text: String) : ChatEvent

    /** Server-side Piper TTS audio for one sentence (WAV bytes). */
    data class Audio(val wav: ByteArray, val text: String?) : ChatEvent {
        override fun equals(other: Any?): Boolean =
            other is Audio && wav.contentEquals(other.wav) && text == other.text
        override fun hashCode(): Int = 31 * wav.contentHashCode() + (text?.hashCode() ?: 0)
    }

    /** Screenshot pushed by a browser tool (base64 PNG/JPEG). */
    data class Screenshot(val base64: String) : ChatEvent

    /** Terminal event carrying the full reply. */
    data class Done(val response: String, val toolCalls: List<String>) : ChatEvent
}

/** Pre-rendered greeting/quote/report from `GET /cached/...`. */
data class CachedSpeech(
    val text: String,
    /** Per-sentence WAV clips, in playback order. */
    val clips: List<ByteArray>,
)
