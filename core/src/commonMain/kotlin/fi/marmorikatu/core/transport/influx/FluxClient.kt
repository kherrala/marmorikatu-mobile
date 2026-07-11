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
        tagKey: String? = null,
        tagValue: String? = null,
    ): Map<String, List<FluxPoint>> {
        if (fields.isEmpty()) return emptyMap()
        val predicate = fields.joinToString(" or ") { """r._field == "$it"""" }
        // Optional tag filter — e.g. a specific Ruuvi sensor_name, since several
        // sensors share the `ruuvi`/`temperature` series.
        val tagFilter = if (tagKey != null && tagValue != null) {
            "\n              |> filter(fn: (r) => r.$tagKey == \"$tagValue\")"
        } else ""
        val flux = """
            from(bucket: "$bucket")
              |> range(start: $range)
              |> filter(fn: (r) => r._measurement == "$measurement")
              |> filter(fn: (r) => $predicate)$tagFilter
              |> aggregateWindow(every: $every, fn: mean, createEmpty: false)
              |> keep(columns: ["_time", "_value", "_field"])
        """.trimIndent()
        return runQuery(flux)
    }

    /**
     * Heat-recovery (LTO) efficiency history, computed in Flux exactly as the
     * Grafana dashboard / backend `get_heat_recovery_efficiency` do — the
     * `LTO_hyotysuhde` sensor is unconnected, so efficiency is derived:
     *   η = (supply_after_HRU − outdoor) / (setpoint − outdoor) × 100,
     * kept only when it lands in (0, 100]. Returns a percentage series.
     */
    suspend fun recoveryEfficiencyHistory(range: String = "-24h", every: String = "30m"): List<FluxPoint> {
        val flux = """
            from(bucket: "$bucket")
              |> range(start: $range)
              |> filter(fn: (r) => r._measurement == "hvac")
              |> filter(fn: (r) => r._field == "Ulkolampotila" or r._field == "Tuloilma_ennen_lammitysta" or r._field == "Tuloilma_asetusarvo")
              |> aggregateWindow(every: $every, fn: mean, createEmpty: false)
              |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
              |> filter(fn: (r) => exists r.Ulkolampotila and exists r.Tuloilma_ennen_lammitysta and exists r.Tuloilma_asetusarvo)
              |> filter(fn: (r) => r.Tuloilma_asetusarvo != r.Ulkolampotila)
              |> map(fn: (r) => ({ _time: r._time, _value: (r.Tuloilma_ennen_lammitysta - r.Ulkolampotila) / (r.Tuloilma_asetusarvo - r.Ulkolampotila) * 100.0, _field: "lto" }))
              |> filter(fn: (r) => r._value > 0.0 and r._value <= 100.0)
              |> keep(columns: ["_time", "_value", "_field"])
        """.trimIndent()
        val csv = postFlux(flux) ?: return emptyList()
        return parseAnnotatedCsv(csv)["lto"] ?: emptyList()
    }

    /**
     * Latest value of one field filtered to a specific tag — e.g. the outdoor
     * Ruuvi (`ruuvi`/`temperature`, `sensor_name = "Ulkolämpötila"`), where
     * several sensors share the same field and an untagged [latest] would be
     * ambiguous. Returns null if unreachable or absent.
     */
    suspend fun latest(measurement: String, field: String, tagKey: String, tagValue: String): Double? {
        val flux = """
            from(bucket: "$bucket")
              |> range(start: -3h)
              |> filter(fn: (r) => r._measurement == "$measurement")
              |> filter(fn: (r) => r._field == "$field")
              |> filter(fn: (r) => r.$tagKey == "$tagValue")
              |> last()
              |> keep(columns: ["_time", "_value", "_field"])
        """.trimIndent()
        val csv = postFlux(flux) ?: return null
        return parseAnnotatedCsv(csv)[field]?.lastOrNull()?.value
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

    /**
     * Latest string value of one field — e.g. the `tier` classification the
     * heating optimizer writes. The numeric [latest]/[history] readers drop
     * non-numeric values, so string fields need their own path. Returns null
     * when InfluxDB is unreachable or the field is absent (callers fall back).
     */
    suspend fun latestString(measurement: String, field: String): String? {
        val flux = """
            from(bucket: "$bucket")
              |> range(start: -3h)
              |> filter(fn: (r) => r._measurement == "$measurement")
              |> filter(fn: (r) => r._field == "$field")
              |> last()
              |> keep(columns: ["_time", "_value", "_field"])
        """.trimIndent()
        return lastStringValue(postFlux(flux))
    }

    private suspend fun postFlux(flux: String): String? {
        val url = "${configStore.config.value.influxUrl}/api/v2/query?org=$org"
        return try {
            httpClient.post(url) {
                header(HttpHeaders.Authorization, "Token $token")
                header(HttpHeaders.Accept, "application/csv")
                contentType(ContentType("application", "vnd.flux"))
                setBody(flux)
            }.bodyAsText()
        } catch (e: Exception) {
            log.w(e) { "flux query failed" }
            null
        }
    }

    private suspend fun runQuery(flux: String): Map<String, List<FluxPoint>> {
        val csv = postFlux(flux) ?: return emptyMap()
        return parseAnnotatedCsv(csv)
    }

    /** Read the last data row's `_value` cell as a raw string. */
    internal fun lastStringValue(csv: String?): String? {
        if (csv == null) return null
        var valueIdx = -1
        var result: String? = null
        csv.lineSequence().forEach { rawLine ->
            val line = rawLine.trim('\r', '\n')
            if (line.isBlank()) return@forEach
            if (line.startsWith("#")) {
                valueIdx = -1
                return@forEach
            }
            val cells = line.split(",")
            if (valueIdx < 0) {
                valueIdx = cells.indexOf("_value")
                return@forEach
            }
            if (valueIdx < 0 || cells.size <= valueIdx) return@forEach
            cells[valueIdx].takeIf { it.isNotBlank() }?.let { result = it }
        }
        return result
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
