package fi.marmorikatu.core.transport.http

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SseReaderTest {

    private suspend fun collect(raw: String): List<SseEvent> {
        val events = mutableListOf<SseEvent>()
        ByteReadChannel(raw).readSseEvents { events += it }
        return events
    }

    @Test
    fun parsesAnnouncementStyleEvents() = runTest {
        val raw = buildString {
            append("id: 60\n")
            append("""data: {"text": "WC alakerta katto syttyi.", "id": 60}""")
            append("\n\n")
            append("id: 61\n")
            append("""data: {"text": "Valo sammui.", "id": 61}""")
            append("\n\n")
        }
        val events = collect(raw)
        assertEquals(2, events.size)
        assertEquals("60", events[0].id)
        assertEquals("""{"text": "WC alakerta katto syttyi.", "id": 60}""", events[0].data)
    }

    @Test
    fun ignoresKeepaliveComments() = runTest {
        val events = collect(": keepalive\n\ndata: hello\n\n: another\n\n")
        assertEquals(1, events.size)
        assertEquals("hello", events[0].data)
    }

    @Test
    fun joinsMultiLineData() = runTest {
        val events = collect("data: line1\ndata: line2\n\n")
        assertEquals("line1\nline2", events[0].data)
    }

    @Test
    fun flushesTrailingEventWithoutBlankLine() = runTest {
        val events = collect("data: tail")
        assertEquals(listOf("tail"), events.map { it.data })
    }

    @Test
    fun handlesEventField() = runTest {
        val events = collect("event: update\ndata: x\n\n")
        assertEquals("update", events[0].event)
    }
}
