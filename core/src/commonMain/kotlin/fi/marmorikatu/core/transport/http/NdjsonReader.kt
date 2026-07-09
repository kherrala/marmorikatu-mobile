package fi.marmorikatu.core.transport.http

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.json.JsonElement

/**
 * Reads newline-delimited JSON (one object per line, as returned by the
 * bridge `/tts` endpoint). Blank lines are skipped; a malformed line stops
 * the stream by throwing.
 */
suspend fun ByteReadChannel.readNdjson(onLine: suspend (JsonElement) -> Unit) {
    while (true) {
        val line = readUTF8Line() ?: break
        if (line.isBlank()) continue
        onLine(AppJson.parseToJsonElement(line))
    }
}
