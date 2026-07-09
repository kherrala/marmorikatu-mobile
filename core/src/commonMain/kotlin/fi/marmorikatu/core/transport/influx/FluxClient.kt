package fi.marmorikatu.core.transport.influx

import fi.marmorikatu.core.config.ConfigStore
import fi.marmorikatu.core.log.logger
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

/** One `(time, value)` sample of a named series. */
data class FluxPoint(val timeIso: String, val value: Double)

/**
 * Minimal InfluxDB 2.x query client — the only source of history deep enough
 * for the charts (the MCP `query_data` tool caps results at 100 rows).
 *
 * The token is the LAN-wide admin token the whole stack shares; see the
 * project README on why the LAN is the security boundary here.
 */
class FluxClient(
    private val httpClient: HttpClient,
    private val configStore: ConfigStore,
    private val org: String = "wago",
    private val bucket: String = "building_automation",
    private val token: String = "wago-secret-token",
) {
    private val log = logger("influx")

    /**
     * Downsampled history for one measurement's fields.
     *
     * @param range Flux duration, e.g. `-24h`, `-7d`.
     * @param every aggregation window, e.g. `30m`.
     */
    suspend fun history(
        measurement: String,
        fields: List<String>,
        range: String = "-24h",
        every: String = "30m",
    ): Map<String, List<FluxPoint>> {
        if (fields.isEmpty()) return emptyMap()
        val predicate = fields.joinToString(" or ") { """r._field == "$it"""" }
        val flux = """
            from(bucket: "$bucket")
              |> range(start: $range)
              |> filter(fn: (r) => r._measurement == "$measurement")
              |> filter(fn: (r) => $predicate)
              |> aggregateWindow(every: $every, fn: mean, createEmpty: false)
              |> keep(columns: ["_time", "_value", "_field"])
        """.trimIndent()
        return runQuery(flux)
    }

    /** Latest value per field. */
    suspend fun latest(measurement: String, fields: List<String>): Map<String, Double> {
        if (fields.isEmpty()) return emptyMap()
        val predicate = fields.joinToString(" or ") { """r._field == "$it"""" }
        val flux = """
            from(bucket: "$bucket")
              |> range(start: -2h)
              |> filter(fn: (r) => r._measurement == "$measurement")
              |> filter(fn: (r) => $predicate)
              |> last()
              |> keep(columns: ["_time", "_value", "_field"])
        """.trimIndent()
        return runQuery(flux).mapNotNull { (field, points) ->
            points.lastOrNull()?.let { field to it.value }
        }.toMap()
    }

    private suspend fun runQuery(flux: String): Map<String, List<FluxPoint>> {
        val url = "${configStore.config.value.influxUrl}/api/v2/query?org=$org"
        val csv = try {
            httpClient.post(url) {
                header(HttpHeaders.Authorization, "Token $token")
                header(HttpHeaders.Accept, "application/csv")
                contentType(ContentType("application", "vnd.flux"))
                setBody(flux)
            }.bodyAsText()
        } catch (e: Exception) {
            log.w(e) { "flux query failed" }
            return emptyMap()
        }
        return parseAnnotatedCsv(csv)
    }

    /**
     * InfluxDB's annotated CSV: `#`-prefixed annotation rows, then a header
     * row, then data rows. Columns shift between queries, so the header is
     * read rather than assumed.
     */
    internal fun parseAnnotatedCsv(csv: String): Map<String, List<FluxPoint>> {
        val result = mutableMapOf<String, MutableList<FluxPoint>>()
        var timeIdx = -1
        var valueIdx = -1
        var fieldIdx = -1

        csv.lineSequence().forEach { rawLine ->
            val line = rawLine.trim('\r', '\n')
            if (line.isBlank()) return@forEach
            if (line.startsWith("#")) {
                // A new table block follows; the next header row re-binds columns.
                timeIdx = -1
                return@forEach
            }
            val cells = line.split(",")
            if (timeIdx < 0) {
                timeIdx = cells.indexOf("_time")
                valueIdx = cells.indexOf("_value")
                fieldIdx = cells.indexOf("_field")
                return@forEach
            }
            if (timeIdx < 0 || valueIdx < 0 || fieldIdx < 0) return@forEach
            if (cells.size <= maxOf(timeIdx, valueIdx, fieldIdx)) return@forEach

            val value = cells[valueIdx].toDoubleOrNull() ?: return@forEach
            val field = cells[fieldIdx]
            if (field.isBlank()) return@forEach
            result.getOrPut(field) { mutableListOf() }.add(FluxPoint(cells[timeIdx], value))
        }
        return result
    }
}
