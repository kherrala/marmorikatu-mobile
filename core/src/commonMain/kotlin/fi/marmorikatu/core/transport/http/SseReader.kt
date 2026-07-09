package fi.marmorikatu.core.transport.http

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line

/**
 * One server-sent event. [data] is the concatenation of all `data:` lines,
 * joined with newlines per the SSE spec.
 */
data class SseEvent(
    val id: String? = null,
    val event: String? = null,
    val data: String,
)

/**
 * Minimal SSE parser over a raw [ByteReadChannel].
 *
 * Hand-rolled because the bridge streams SSE from a POST request
 * (`/chat/stream`), which Ktor's GET-shaped SSE plugin does not cover.
 * Comment lines (`:` prefix) and unknown fields are ignored; an event is
 * emitted at each blank line if any `data:` was accumulated.
 */
suspend fun ByteReadChannel.readSseEvents(onEvent: suspend (SseEvent) -> Unit) {
    var id: String? = null
    var event: String? = null
    val data = StringBuilder()

    while (true) {
        val line = readUTF8Line() ?: break
        when {
            line.isEmpty() -> {
                if (data.isNotEmpty()) {
                    onEvent(SseEvent(id, event, data.toString()))
                }
                id = null
                event = null
                data.setLength(0)
            }
            line.startsWith(":") -> Unit // comment/keepalive
            else -> {
                val colon = line.indexOf(':')
                val field: String
                val value: String
                if (colon >= 0) {
                    field = line.substring(0, colon)
                    value = line.substring(colon + 1).removePrefix(" ")
                } else {
                    field = line
                    value = ""
                }
                when (field) {
                    "id" -> id = value
                    "event" -> event = value
                    "data" -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(value)
                    }
                }
            }
        }
    }
    // Stream ended without a trailing blank line: flush what we have.
    if (data.isNotEmpty()) {
        onEvent(SseEvent(id, event, data.toString()))
    }
}
