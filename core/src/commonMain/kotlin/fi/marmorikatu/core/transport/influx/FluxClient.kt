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

    /**
     * Light on-time today, in hours, per floor. Integrates each fixture's `is_on`
     * (0/1) step signal separately — grouping several lights into one table first
     * interleaves their timestamps and makes `integral` return nonsense (even
     * negatives) — then sums the per-light hours back up per `floor_name`.
     * Keyed by floor name; empty on failure.
     */
    suspend fun lightOnTimeByFloorToday(): Map<String, Double> {
        val flux = """
            import "date"
            from(bucket: "$bucket")
              |> range(start: date.truncate(t: now(), unit: 1d))
              |> filter(fn: (r) => r._measurement == "lights" and r._field == "is_on")
              |> group(columns: ["light_id", "floor_name"])
              |> integral(unit: 1h, interpolate: "linear")
              |> group(columns: ["floor_name"])
              |> sum()
              |> keep(columns: ["floor_name", "_value"])
        """.trimIndent()
        return parseGrouped(postFlux(flux), "floor_name")
    }

    /**
     * Count of today's automatic light-off decisions, grouped by the optimizer's
     * `category` tag (e.g. `co2_auto`, `porch_schedule`, `toilet`). Empty on
     * failure. `decision` is a string field, so this counts rows where it equals
     * `"off"`.
     */
    suspend fun lightAutoOffCountsToday(): Map<String, Double> {
        val flux = """
            import "date"
            from(bucket: "$bucket")
              |> range(start: date.truncate(t: now(), unit: 1d))
              |> filter(fn: (r) => r._measurement == "lights_optimizer" and r._field == "decision" and r._value == "off")
              |> group(columns: ["category"])
              |> count()
              |> keep(columns: ["category", "_value"])
        """.trimIndent()
        return parseGrouped(postFlux(flux), "category")
    }

    /**
     * Per-bucket metered energy (kWh) over [range], summed across the heat-pump
     * and aux meters — the cost-trend sparkline on the Energia tab. Each meter's
     * `Total_Active_Energy` is a monotonic counter, so `spread` (max − min) per
     * window is that window's consumption; ungrouping and summing folds both
     * meters into one bucket. These two circuits are the only metered load, so
     * this is a heat-pump+aux trend, not whole-house. Ordered oldest→newest;
     * empty on failure.
     *
     * @param range Flux duration, e.g. `-24h`, `-7d`, `-30d`, `-365d`.
     * @param every bucket width, e.g. `2h`, `1d`, `3d`, `1mo`.
     */
    suspend fun energyKwhBuckets(range: String, every: String): List<Double> {
        val flux = """
            from(bucket: "$bucket")
              |> range(start: $range)
              |> filter(fn: (r) => r._measurement == "hvac" and r._field == "Total_Active_Energy")
              |> group(columns: ["meter"])
              |> aggregateWindow(every: $every, fn: spread, createEmpty: false)
              |> group()
              |> aggregateWindow(every: $every, fn: sum, createEmpty: false)
              |> map(fn: (r) => ({ _time: r._time, _value: r._value, _field: "kwh" }))
              |> keep(columns: ["_time", "_value", "_field"])
        """.trimIndent()
        val csv = postFlux(flux) ?: return emptyList()
        return parseAnnotatedCsv(csv)["kwh"].orEmpty().map { it.value }
    }

    /**
     * Estimated sauna heating-hours over [range] — the hours the sauna room is
     * genuinely hot. The sauna is unmetered, so consumption is inferred from its
     * Ruuvi temperature. Unlike the backend's `_query_sauna_kwh`, which counts
     * every minute above **30 °C** (the room sits there for hours after — and
     * independently of — heating, wildly overestimating in summer), this counts
     * only time above 60 °C, i.e. an actual heating session. A 15-minute base
     * window keeps it cheap even over a year. Multiply by the heater's ~6 kW for
     * kWh. Returns null when the sensor has no data (caller falls back).
     */
    suspend fun saunaHeatingHours(range: String): Double? {
        val flux = """
            from(bucket: "$bucket")
              |> range(start: $range)
              |> filter(fn: (r) => r._measurement == "ruuvi" and r.sensor_name == "Sauna" and r._field == "temperature")
              |> aggregateWindow(every: 15m, fn: mean, createEmpty: false)
              |> map(fn: (r) => ({ r with _value: if r._value > 60.0 then 1.0 else 0.0 }))
              |> aggregateWindow(every: 1h, fn: mean, createEmpty: false)
              |> sum()
              |> keep(columns: ["_value", "_field"])
        """.trimIndent()
        return lastStringValue(postFlux(flux))?.toDoubleOrNull()
    }

    /**
     * Parse an annotated-CSV result of the shape `<keyColumn>, _value` (one row
     * per group) into a map. Used by the grouped scalar queries above, where the
     * grouping key is a tag column rather than `_field`.
     */
    internal fun parseGrouped(csv: String?, keyColumn: String): Map<String, Double> {
        if (csv == null) return emptyMap()
        val result = mutableMapOf<String, Double>()
        var keyIdx = -1
        var valueIdx = -1
        csv.lineSequence().forEach { rawLine ->
            val line = rawLine.trim('\r', '\n')
            if (line.isBlank()) return@forEach
            if (line.startsWith("#")) { keyIdx = -1; return@forEach }
            val cells = line.split(",")
            if (keyIdx < 0) {
                keyIdx = cells.indexOf(keyColumn)
                valueIdx = cells.indexOf("_value")
                return@forEach
            }
            if (keyIdx < 0 || valueIdx < 0 || cells.size <= maxOf(keyIdx, valueIdx)) return@forEach
            val key = cells[keyIdx].takeIf { it.isNotBlank() } ?: return@forEach
            val value = cells[valueIdx].toDoubleOrNull() ?: return@forEach
            result[key] = value
        }
        return result
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
