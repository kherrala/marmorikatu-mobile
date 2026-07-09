package fi.marmorikatu.core.transport.bridge

import fi.marmorikatu.core.config.ConfigStore
import fi.marmorikatu.core.log.logger
import fi.marmorikatu.core.model.Announcement
import fi.marmorikatu.core.model.ChatEvent
import fi.marmorikatu.core.model.ChatMessage
import fi.marmorikatu.core.model.CachedSpeech
import fi.marmorikatu.core.transport.http.AppJson
import fi.marmorikatu.core.transport.http.readNdjson
import fi.marmorikatu.core.transport.http.readSseEvents
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Client for the claude-bridge service (:3002) — conversation, voice I/O,
 * and the announcements push channel.
 */
@OptIn(ExperimentalEncodingApi::class)
class BridgeApi(
    private val httpClient: HttpClient,
    private val configStore: ConfigStore,
) {
    private val log = logger("bridge")
    private val base: String get() = configStore.config.value.bridgeUrl

    // --- Health ---------------------------------------------------------------

    @Serializable
    data class Health(val status: String = "", val tools_count: Int = 0)

    suspend fun health(): Health = httpClient.get("$base/health").body()

    // --- Conversation -----------------------------------------------------------

    /**
     * Streams a conversation turn. Emits [ChatEvent]s as the bridge produces
     * them; the flow completes after the terminal [ChatEvent.Done].
     */
    fun chatStream(messages: List<ChatMessage>): Flow<ChatEvent> = flow {
        httpClient.preparePost("$base/chat/stream") {
            contentType(ContentType.Application.Json)
            setBody(chatBody(messages))
            timeout {
                requestTimeoutMillis = 300_000
                socketTimeoutMillis = 120_000
            }
        }.execute { response ->
            response.bodyAsChannel().readSseEvents { sse ->
                parseChatEvent(sse.data)?.let { emit(it) }
            }
        }
    }

    private fun chatBody(messages: List<ChatMessage>): JsonObject = buildJsonObject {
        putJsonArray("messages") {
            messages.forEach { m ->
                add(buildJsonObject {
                    put("role", m.role)
                    put("content", m.content)
                    if (m.images.isNotEmpty()) {
                        // JsonPrimitive escapes the payload; splicing it into a
                        // quoted string would break on any `"` or backslash.
                        putJsonArray("images") { m.images.forEach { add(JsonPrimitive(it)) } }
                    }
                })
            }
        }
    }

    /** A single malformed event must not tear down the whole stream. */
    private fun parseChatEvent(data: String): ChatEvent? =
        runCatching { parseChatEventOrThrow(data) }
            .onFailure { log.w { "skipping unparseable chat event: ${it.message}" } }
            .getOrNull()

    private fun parseChatEventOrThrow(data: String): ChatEvent? {
        val obj = runCatching { AppJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        return when {
            "done" in obj -> ChatEvent.Done(
                response = obj["response"]?.jsonPrimitive?.contentOrNull ?: "",
                // Each entry is {"tool": "get_latest", "input": {...}}; older
                // bridge builds emitted bare tool-name strings.
                toolCalls = obj["tool_calls"]?.jsonArray.orEmpty().mapNotNull { call ->
                    when (call) {
                        is JsonObject -> call["tool"]?.jsonPrimitive?.contentOrNull
                        else -> call.jsonPrimitive.contentOrNull
                    }
                },
            )
            "audio" in obj -> ChatEvent.Audio(
                wav = Base64.decode(obj["audio"]!!.jsonPrimitive.content),
                text = obj["text"]?.jsonPrimitive?.contentOrNull,
            )
            "text" in obj -> ChatEvent.Text(obj["text"]!!.jsonPrimitive.content)
            "tool_use" in obj -> ChatEvent.ToolUse(obj["tool_use"]!!.jsonPrimitive.content)
            "screenshot" in obj -> ChatEvent.Screenshot(obj["screenshot"]!!.jsonPrimitive.content)
            else -> null
        }
    }

    // --- Voice ----------------------------------------------------------------

    /** Uploads recorded audio; returns the Finnish transcript. */
    suspend fun transcribe(audio: ByteArray, mimeType: String, fileName: String): String {
        val response = httpClient.post("$base/transcribe") {
            timeout { requestTimeoutMillis = 60_000 }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "audio", audio,
                            Headers.build {
                                append(HttpHeaders.ContentType, mimeType)
                                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                            },
                        )
                    }
                )
            )
        }
        val obj = AppJson.parseToJsonElement(response.body<String>()).jsonObject
        return obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
    }

    /** Server-side Piper TTS: one WAV clip per sentence, streamed as NDJSON. */
    fun tts(text: String): Flow<ByteArray> = flow {
        httpClient.preparePost("$base/tts") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("text", text) })
            timeout {
                requestTimeoutMillis = 120_000
                socketTimeoutMillis = 60_000
            }
        }.execute { response ->
            response.bodyAsChannel().readNdjson { element ->
                element.jsonObject["audio"]?.jsonPrimitive?.contentOrNull?.let {
                    emit(Base64.decode(it))
                }
            }
        }
    }

    suspend fun cached(kind: String): CachedSpeech {
        val obj = AppJson.parseToJsonElement(
            httpClient.get("$base/cached/$kind").body<String>()
        ).jsonObject
        return CachedSpeech(
            text = obj["text"]?.jsonPrimitive?.contentOrNull ?: "",
            clips = obj["audio"]?.jsonArray.orEmpty().mapNotNull { clip ->
                clip.jsonObject["audio"]?.jsonPrimitive?.contentOrNull?.let { Base64.decode(it) }
            },
        )
    }

    // --- Announcements ----------------------------------------------------------

    /**
     * Live announcement feed (SSE). Suspends for the life of the stream;
     * the caller retries with the last seen id for gapless resume.
     */
    fun announcementStream(lastEventId: Long? = null): Flow<Announcement> = flow {
        httpClient.prepareGet("$base/announcements/stream") {
            timeout {
                requestTimeoutMillis = Long.MAX_VALUE
                // Keepalives arrive every 20 s; three misses = dead connection.
                socketTimeoutMillis = 60_000
            }
            lastEventId?.let { header("Last-Event-ID", it.toString()) }
        }.execute { response ->
            response.bodyAsChannel().readSseEvents { sse ->
                runCatching {
                    AppJson.decodeFromString(Announcement.serializer(), sse.data)
                }.onSuccess { emit(it) }
                    .onFailure { log.d { "skipping non-announcement event: ${sse.data.take(80)}" } }
            }
        }
    }

    suspend fun announcementHistory(limit: Int = 20): List<Announcement> {
        val obj = AppJson.parseToJsonElement(
            httpClient.get("$base/announcements/history?limit=$limit").body<String>()
        ).jsonObject
        return obj["events"]?.jsonArray.orEmpty().mapNotNull { event ->
            runCatching { AppJson.decodeFromJsonElement(Announcement.serializer(), event) }.getOrNull()
        }
    }
}
