package fi.marmorikatu.core.transport.mcp

import fi.marmorikatu.core.config.ConfigStore
import fi.marmorikatu.core.log.logger
import io.ktor.client.HttpClient
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface McpState {
    data object Disconnected : McpState
    data object Connecting : McpState
    /** Last successful tool call round-trip in milliseconds. */
    data class Connected(val lastLatencyMs: Long?) : McpState
    data class Failed(val message: String) : McpState
}

/**
 * One lazily-established MCP session over streamable HTTP to the building
 * automation server. This and [McpApi] are the only files that may import
 * the MCP SDK (v0.x churn containment).
 */
class McpConnection(
    private val httpClient: HttpClient,
    private val configStore: ConfigStore,
) {
    private val log = logger("mcp")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutex = Mutex()
    private var client: Client? = null

    private val _state = MutableStateFlow<McpState>(McpState.Disconnected)
    val state: StateFlow<McpState> = _state.asStateFlow()

    private suspend fun ensureConnected(): Client = mutex.withLock {
        client?.let { return it }
        _state.value = McpState.Connecting
        try {
            val url = "${configStore.config.value.mcpUrl}/"
            val transport = StreamableHttpClientTransport(httpClient, url)
            val newClient = Client(
                clientInfo = Implementation(name = "marmorikatu-mobile", version = "0.1.0"),
            )
            newClient.connect(transport)
            client = newClient
            _state.value = McpState.Connected(lastLatencyMs = null)
            log.i { "connected to $url" }
            newClient
        } catch (e: Exception) {
            _state.value = McpState.Failed(e.message ?: "MCP connect failed")
            throw McpException("MCP connect failed", e)
        }
    }

    private suspend fun dropConnection() = mutex.withLock {
        client?.let { runCatching { it.close() } }
        client = null
        _state.value = McpState.Disconnected
    }

    /**
     * Calls a tool and returns its first text content parsed as JSON.
     * Non-JSON text is wrapped as a JSON string primitive so callers can
     * still read prose results.
     *
     * A transport failure is retried once — but only for [idempotent] tools.
     * The server may already have executed the tool before the response was
     * lost. Setting an absolute state (`set_light {light, on}`) survives a
     * replay; a relative action (`harmony_send_command` VolumeUp) does not.
     */
    suspend fun callToolJson(
        name: String,
        arguments: Map<String, Any?> = emptyMap(),
        idempotent: Boolean = true,
    ): JsonElement {
        return try {
            callOnce(name, arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpToolFailure) {
            throw e // tool-level error: retrying won't help
        } catch (e: Exception) {
            dropConnection()
            if (!idempotent) {
                log.w(e) { "tool $name failed; not retried (may have executed)" }
                throw e
            }
            log.w(e) { "tool $name transport failure, reconnecting once" }
            callOnce(name, arguments)
        }
    }

    private suspend fun callOnce(name: String, arguments: Map<String, Any?>): JsonElement {
        val mcp = ensureConnected()
        val startMark = kotlin.time.TimeSource.Monotonic.markNow()
        val result = try {
            withTimeout(15_000) { mcp.callTool(name = name, arguments = arguments) }
        } catch (e: Exception) {
            throw McpException("tool $name failed: ${e.message}", e)
        }
        _state.value = McpState.Connected(lastLatencyMs = startMark.elapsedNow().inWholeMilliseconds)

        val text = result.content
            .filterIsInstance<TextContent>()
            .firstOrNull()?.text
            ?: throw McpException("tool $name returned no text content")

        if (result.isError == true || text.startsWith("Error")) {
            throw McpToolFailure(name, text)
        }
        val element = runCatching { json.parseToJsonElement(text) }
            .getOrElse { return JsonPrimitive(text) }
        (element as? JsonObject)?.get("error")?.let {
            throw McpToolFailure(name, it.toString())
        }
        return element
    }
}

/** The server executed the tool but reported a failure. */
class McpToolFailure(val tool: String, message: String) : Exception("$tool: $message")
