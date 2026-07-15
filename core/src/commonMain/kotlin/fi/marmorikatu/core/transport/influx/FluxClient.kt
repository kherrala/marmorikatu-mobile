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
        // group(_field) collapses every tag series of a field into one table
        // before the window mean — a field stored under several tags (e.g.
        // electricity/price_with_tax's two price sources) would otherwise come
        // back as separate series that the CSV parse concatenates out of time
        // order. The explicit sort keeps each field's points chronological.
        val flux = """
            from(bucket: "$bucket")
              |> range(start: $range)
              |> filter(fn: (r) => r._measurement == "$measurement")
              |> filter(fn: (r) => $predicate)$tagFilter
              |> group(columns: ["_field"])
              |> aggregateWindow(every: $every, fn: mean, createEmpty: false)
              |> sort(columns: ["_time"])
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

    // ── Energy-cost components (local port of the backend get_energy_cost) ───────
    // Each mirrors one of `scripts/mcp_tools/energy.py`'s queries so the Energia
    // tab can compute cost/consumption straight from InfluxDB instead of the slow
    // MCP round-trip. All validated to match the backend on real ranges.

    /**
     * Total metered heating energy (kWh) over [range]: the increase of the
     * heat-pump and aux meters' cumulative `Total_Active_Energy` counters, i.e.
     * `spread` (max − min) per meter summed. Whole-range spread (not per-window)
     * so inter-window gaps aren't dropped. Null → caller treats as 0. Like the
     * backend, this over-reads if a meter ever resets; the counters are monotonic
     * in practice.
     */
    suspend fun meteredHeatingKwh(range: String): Double? {
        val flux = """
            from(bucket: "$bucket")
              |> range(start: $range)
              |> filter(fn: (r) => r._measurement == "hvac" and r.sensor_group == "energy" and r._field == "Total_Active_Energy")
              |> filter(fn: (r) => r.meter == "heatpump" or r.meter == "extra")
              |> group(columns: ["meter"])
              |> spread()
              |> group()
              |> sum()
              |> keep(columns: ["_value"])
        """.trimIndent()
        return lastStringValue(postFlux(flux))?.toDoubleOrNull()
    }

    /**
     * Estimated lighting energy (kWh) over [range]: per-light hourly mean of
     * `is_on` summed across all fixtures and hours (≈ fixture-hours) × 10 W,
     * matching the backend's `_query_lighting_kwh`. Null → 0.
     */
    suspend fun lightingKwh(range: String): Double? {
        val flux = """
            from(bucket: "$bucket")
              |> range(start: $range)
              |> filter(fn: (r) => r._measurement == "lights" and r._field == "is_on")
              |> toFloat()
              |> aggregateWindow(every: 1h, fn: mean, createEmpty: false)
              |> group()
              |> sum()
              |> map(fn: (r) => ({ _value: r._value * 10.0 / 1000.0, _field: "kwh" }))
        """.trimIndent()
        return lastStringValue(postFlux(flux))?.toDoubleOrNull()
    }

    /**
     * Number of populated hourly ventilation buckets over [range] — the count of
     * hours the kitchen Ruuvi reported (a continuous-fan proxy), matching the
     * backend's `_query_fan_kwh` `len(...)`. Multiply by 0.3 kWh (300 W) for
     * energy. Null → 0. Counting real buckets (not `0.3 × hours`) matters over
     * long windows, since the sensor's history is shorter than a year.
     */
    suspend fun ventilationBucketHours(range: String): Double? {
        val flux = """
            from(bucket: "$bucket")
              |> range(start: $range)
              |> filter(fn: (r) => r._measurement == "ruuvi" and (r.sensor_name == "Keittio" or r.sensor_name == "Keittiö"))
              |> filter(fn: (r) => r._field == "pressure")
              |> aggregateWindow(every: 1h, fn: mean, createEmpty: false)
              |> count()
              |> group()
              |> sum()
              |> keep(columns: ["_value"])
        """.trimIndent()
        return lastStringValue(postFlux(flux))?.toDoubleOrNull()
    }

    /**
     * Mean spot price incl. VAT (c/kWh) over [range], from `electricity`
     * `price_with_tax`. Adds `group()` before `mean()` so both price-source tags
     * are averaged together — the backend omits it and silently keeps only one
     * source on long ranges. Null → caller uses the backend's 5.0 fallback.
     */
    suspend fun avgSpotPriceCents(range: String): Double? {
        val flux = """
            from(bucket: "$bucket")
              |> range(start: $range)
              |> filter(fn: (r) => r._measurement == "electricity" and r._field == "price_with_tax")
              |> group()
              |> mean()
        """.trimIndent()
        return lastStringValue(postFlux(flux))?.toDoubleOrNull()
    }

    /**
     * Latest reading of every electricity-meter field, per meter — a fast
     * InfluxDB snapshot to seed the meter card instantly on cold start, before
     * the retained MQTT topics arrive. Keyed by meter (`heatpump` | `extra`);
     * each inner map is field → value. Empty on failure.
     */
    suspend fun latestMeterFields(): Map<String, Map<String, Double>> {
        val flux = """
            from(bucket: "$bucket")
              |> range(start: -30m)
              |> filter(fn: (r) => r._measurement == "hvac" and (r.meter == "heatpump" or r.meter == "extra"))
              |> filter(fn: (r) => r._field == "Total_Active_Power" or r._field == "Total_Active_Energy" or r._field == "Grid_Frequency" or r._field == "L1_Voltage" or r._field == "L2_Voltage" or r._field == "L3_Voltage" or r._field == "Forward_Active_Energy" or r._field == "Reverse_Active_Energy")
              |> last()
              |> keep(columns: ["meter", "_field", "_value"])
        """.trimIndent()
        return parseMeterFields(postFlux(flux))
    }

    /** Parse a `meter, _field, _value` snapshot CSV into meter → (field → value). */
    internal fun parseMeterFields(csv: String?): Map<String, Map<String, Double>> {
        if (csv == null) return emptyMap()
        val result = mutableMapOf<String, MutableMap<String, Double>>()
        var meterIdx = -1
        var fieldIdx = -1
        var valueIdx = -1
        csv.lineSequence().forEach { rawLine ->
            val line = rawLine.trim('\r', '\n')
            if (line.isBlank()) return@forEach
            if (line.startsWith("#")) { meterIdx = -1; return@forEach }
            val cells = line.split(",")
            if (meterIdx < 0) {
                meterIdx = cells.indexOf("meter")
                fieldIdx = cells.indexOf("_field")
                valueIdx = cells.indexOf("_value")
                return@forEach
            }
            if (meterIdx < 0 || fieldIdx < 0 || valueIdx < 0 || cells.size <= maxOf(meterIdx, fieldIdx, valueIdx)) return@forEach
            val meter = cells[meterIdx].takeIf { it.isNotBlank() } ?: return@forEach
            val field = cells[fieldIdx].takeIf { it.isNotBlank() } ?: return@forEach
            val value = cells[valueIdx].toDoubleOrNull() ?: return@forEach
            result.getOrPut(meter) { mutableMapOf() }[field] = value
        }
        return result
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
