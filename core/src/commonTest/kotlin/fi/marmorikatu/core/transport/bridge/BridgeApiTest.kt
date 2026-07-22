package fi.marmorikatu.core.transport.bridge

import fi.marmorikatu.core.config.ConfigStore
import fi.marmorikatu.core.model.ChatEvent
import fi.marmorikatu.core.model.ChatMessage
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import fi.marmorikatu.core.transport.http.AppJson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class BridgeApiTest {

    private fun bridgeWith(body: String, contentType: String = "text/event-stream"): BridgeApi {
        val engine = MockEngine { _ ->
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, contentType),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(AppJson) }
        }
        return BridgeApi(client, ConfigStore(MapSettings()))
    }

    @Test
    fun chatStreamParsesAllEventKinds() = runTest {
        val wav = Base64.encode(byteArrayOf(82, 73, 70, 70)) // "RIFF"
        val sse = buildString {
            append("data: {\"tool_use\": \"set_light\"}\n\n")
            append("data: {\"text\": \"Sytytän valon.\"}\n\n")
            append("data: {\"audio\": \"$wav\", \"text\": \"Sytytän valon.\"}\n\n")
            // The live bridge sends objects here, not bare tool-name strings.
            append("data: {\"done\": true, \"response\": \"Sytytän valon.\", ")
            append("\"tool_calls\": [{\"tool\": \"set_light\", \"input\": {\"light\": 51}}]}\n\n")
        }
        val events = bridgeWith(sse)
            .chatStream(listOf(ChatMessage.user("Sytytä valo")))
            .toList()

        assertEquals(4, events.size)
        assertEquals(ChatEvent.ToolUse("set_light"), events[0])
        assertEquals(ChatEvent.Text("Sytytän valon."), events[1])
        val audio = assertIs<ChatEvent.Audio>(events[2])
        assertTrue(audio.wav.contentEquals(byteArrayOf(82, 73, 70, 70)))
        val done = assertIs<ChatEvent.Done>(events[3])
        assertEquals(listOf("set_light"), done.toolCalls)
    }

    @Test
    fun malformedEventDoesNotKillTheStream() = runTest {
        val sse = buildString {
            append("data: {\"text\": {\"unexpected\": \"object\"}}\n\n") // not a primitive
            append("data: not json at all\n\n")
            append("data: {\"done\": true, \"response\": \"ok\", \"tool_calls\": []}\n\n")
        }
        val events = bridgeWith(sse).chatStream(listOf(ChatMessage.user("x"))).toList()
        assertEquals(1, events.size)
        assertIs<ChatEvent.Done>(events[0])
    }

    @Test
    fun announcementHistoryParses() = runTest {
        val body = """
            {"events": [
                {"text": "Sauna lämpiää.", "kind": "sauna_on", "priority": 1,
                 "key": "sauna_on", "ts": 1783313248.0, "id": 60}
            ], "ring_size": 1}
        """.trimIndent()
        val history = bridgeWith(body, contentType = "application/json").announcementHistory()
        assertEquals(1, history.size)
        assertEquals("Sauna lämpiää.", history[0].text)
        assertEquals(60, history[0].id)
    }

    @Test
    fun announcementSseStreamParsesLiveEventsAndSkipsHeartbeats() = runTest {
        val sse = buildString {
            append("data: {}\n\n")
            append("id: 61\n")
            append("data: {\"text\":\"Etupihalla on henkilö.\",\"kind\":\"unifi_person_front\",")
            append("\"priority\":1,\"key\":\"unifi_person_front\",\"ts\":1783313249.0,\"id\":61}\n\n")
        }

        val events = bridgeWith(sse).announcementStream(lastEventId = 60).toList()

        assertEquals(1, events.size)
        assertEquals(61, events.single().id)
        assertEquals("unifi_person_front", events.single().kind)
    }

    @Test
    fun ttsDecodesNdjsonClips() = runTest {
        val clip = Base64.encode(byteArrayOf(1, 2, 3))
        val body = "{\"audio\": \"$clip\", \"text\": \"Hei\"}\n"
        val clips = bridgeWith(body, contentType = "application/x-ndjson").tts("Hei").toList()
        assertEquals(1, clips.size)
        assertTrue(clips[0].contentEquals(byteArrayOf(1, 2, 3)))
    }
}
