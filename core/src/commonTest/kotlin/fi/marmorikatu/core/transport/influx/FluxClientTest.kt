package fi.marmorikatu.core.transport.influx

import com.russhwolf.settings.MapSettings
import fi.marmorikatu.core.config.ConfigStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FluxClientTest {

    /** Verbatim shape of an InfluxDB 2.x annotated-CSV response. */
    private val annotatedCsv = """
        #datatype,string,long,dateTime:RFC3339,double,string
        #group,false,false,false,false,true
        #default,_result,,,,
        ,result,table,_time,_value,_field
        ,,0,2026-07-09T10:00:00Z,20.1,Ulkolampotila
        ,,0,2026-07-09T10:30:00Z,20.6,Ulkolampotila

        #datatype,string,long,dateTime:RFC3339,double,string
        #group,false,false,false,false,true
        #default,_result,,,,
        ,result,table,_time,_value,_field
        ,,1,2026-07-09T10:00:00Z,23.9,Keittio
    """.trimIndent()

    private fun clientReturning(body: String): FluxClient {
        val engine = MockEngine { respond(body, HttpStatusCode.OK) }
        return FluxClient(HttpClient(engine), ConfigStore(MapSettings()))
    }

    @Test
    fun parsesMultiTableAnnotatedCsv() {
        val parsed = clientReturning("").parseAnnotatedCsv(annotatedCsv)
        assertEquals(setOf("Ulkolampotila", "Keittio"), parsed.keys)
        assertEquals(2, parsed.getValue("Ulkolampotila").size)
        assertEquals(20.6, parsed.getValue("Ulkolampotila")[1].value)
        assertEquals("2026-07-09T10:00:00Z", parsed.getValue("Keittio")[0].timeIso)
    }

    @Test
    fun ignoresBlankAndMalformedRows() {
        val parsed = clientReturning("").parseAnnotatedCsv(
            "#datatype,string\n,result,table,_time,_value,_field\n,,0,t,notanumber,X\n\n"
        )
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun historyReturnsSeriesKeyedByField() = runTest {
        val history = clientReturning(annotatedCsv).history("rooms", listOf("Keittio"))
        assertEquals(23.9, history.getValue("Keittio").single().value)
    }

    @Test
    fun latestPicksTheFinalPointPerField() = runTest {
        val latest = clientReturning(annotatedCsv).latest("hvac", listOf("Ulkolampotila"))
        assertEquals(20.6, latest["Ulkolampotila"])
    }

    @Test
    fun queryFailureDegradesToEmptyRatherThanThrowing() = runTest {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }
        val client = FluxClient(HttpClient(engine), ConfigStore(MapSettings()))
        // A 500 body is simply not parseable as annotated CSV.
        assertTrue(client.history("rooms", listOf("Keittio")).isEmpty())
    }
}
