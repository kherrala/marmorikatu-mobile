package fi.marmorikatu.core.repository

import fi.marmorikatu.core.model.AirQuality
import fi.marmorikatu.core.model.Cooling
import fi.marmorikatu.core.model.HeatPumpStatus
import fi.marmorikatu.core.model.HvacSummary
import fi.marmorikatu.core.model.HeatingDemand
import fi.marmorikatu.core.model.PlcStatus
import fi.marmorikatu.core.model.RoomTemperature
import fi.marmorikatu.core.model.Rooms
import fi.marmorikatu.core.model.RuuviReading
import fi.marmorikatu.core.model.Ventilation
import fi.marmorikatu.core.transport.influx.FluxClient
import fi.marmorikatu.core.transport.influx.FluxPoint
import fi.marmorikatu.core.transport.mcp.McpApi
import fi.marmorikatu.core.transport.mqtt.MqttClient
import fi.marmorikatu.core.transport.mqtt.MqttConnectionState
import fi.marmorikatu.core.transport.mqtt.MqttTopics
import fi.marmorikatu.core.transport.mqtt.PlcPayloads
import fi.marmorikatu.core.transport.mqtt.RuuviPayloads
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/** Best-effort I/O must still preserve structured-concurrency cancellation. */
internal suspend fun <T> bestEffortSuspend(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}

/**
 * Rooms, ventilation, and heat pump — READ ONLY in v1 by design.
 *
 * There is deliberately no heat-pump write path here: the backend `indoor`
 * service republishes `INDR_T` to the Thermia every 60 s (an app write would
 * be overwritten within a minute), and persistent register writes wear the
 * pump's flash. A safe setpoint/bias knob is a planned backend follow-up;
 * when it lands it becomes one new method backed by MCP.
 */
interface ClimateRepository {
    val roomTemperatures: StateFlow<List<RoomTemperature>>
    val heatingDemand: StateFlow<List<HeatingDemand>>
    val ventilation: StateFlow<Ventilation>
    val cooling: StateFlow<Cooling>
    val plcStatus: StateFlow<PlcStatus>

    /**
     * Live Ruuvi tag readings keyed by sensor name (Keittiö, Sauna, Pakastin, …),
     * pushed by the gateway as each tag reports. Carries CO₂/PM/VOC for the air
     * sensor, temperature/humidity/battery for the rest, and each reading's real
     * timestamp — the source for live air-quality/sauna/outdoor values, per-tile
     * freshness, and the Ruuvi alerts. Empty until the first message arrives.
     */
    val ruuvi: StateFlow<Map<String, RuuviReading>>

    /**
     * Live heat-pump view, decoded from the retained-less `ThermIQ/marmorikatu/data`
     * register feed. Seeded on launch from the last cached reading so the tiles
     * aren't blank while waiting for the first publish; power/COP are merged in
     * by the caller from other sources.
     */
    val heatPump: StateFlow<HeatPumpStatus>

    /**
     * Ask the ThermIQ bridge to publish its registers now (pull-to-refresh).
     * Best effort: does nothing if MQTT isn't connected.
     */
    suspend fun requestHeatPumpRead()

    /** On-demand raw heat pump snapshot via MCP `get_thermia_status` (debug/history). */
    suspend fun heatPumpStatus(): JsonObject

    /** Ruuvi air quality (CO₂, PM2.5, VOC) via MCP. */
    suspend fun airQuality(): AirQuality

    /** Outdoor temperature, heat-recovery efficiency and MVHR alarms. */
    suspend fun hvacSummary(): HvacSummary

    /**
     * Temperature history for the charts. Keys are Finnish display names.
     * @param range Flux duration, e.g. `-24h`.
     */
    suspend fun temperatureHistory(range: String, every: String): Map<String, List<FluxPoint>>

    /**
     * Downsampled history of a single InfluxDB measurement field, oldest→newest,
     * for the KPI sparklines. Returns an empty list on any failure so a missing
     * series just hides the trend rather than surfacing an error.
     */
    suspend fun metricHistory(
        measurement: String,
        field: String,
        range: String = "-24h",
        every: String = "30m",
        tagKey: String? = null,
        tagValue: String? = null,
    ): List<Float>

    /** Computed heat-recovery efficiency history (%) — see [FluxClient.recoveryEfficiencyHistory]. */
    suspend fun recoveryEfficiencyHistory(range: String = "-24h", every: String = "30m"): List<Float>

    /** Latest outdoor temperature from the local Ruuvi sensor (`sensor_name = "Ulkolämpötila"`), or null. */
    suspend fun outdoorRuuviTemp(): Double?

    /**
     * The sauna's current heat state, read from its Ruuvi temperature history:
     * the ignition time ([SaunaHeatState.startEpoch] — when the temperature left
     * its idle baseline and began the climb that is still standing) and whether
     * it is [SaunaHeatState.holding] hot at setpoint rather than cooling down.
     *
     * Together these let the caller show the sauna as "on" through the whole
     * session — the climb *and* the plateau — counting the elapsed time from a
     * once-latched ignition, and clear it only once the sauna actually cools.
     */
    suspend fun saunaHeatState(): SaunaHeatState
}

/**
 * The sauna's heat state derived from its temperature history.
 *
 * @param startEpoch epoch-seconds of the current heat-up's ignition, or null if
 *   it can't be pinned (already hot across the whole window, or no clear rise).
 * @param holding true while the sauna is hot and holding at setpoint (the
 *   heater cycling), false when idle or coasting down after being switched off.
 */
data class SaunaHeatState(val startEpoch: Long?, val holding: Boolean)

class DefaultClimateRepository(
    private val mqtt: MqttClient,
    private val mcp: McpApi,
    private val flux: FluxClient,
    scope: CoroutineScope,
    private val settings: Settings = Settings(),
) : ClimateRepository {

    private val json = Json { ignoreUnknownKeys = true }

    // Seed the live flows from the last payloads we saw so the climate widgets
    // render immediately on launch, before MQTT reconnects and re-delivers the
    // retained snapshot (a second or two later).
    private val _roomTemperatures = MutableStateFlow(loadCached(KEY_TEMPS, PlcPayloads::parseTemperatures))
    override val roomTemperatures: StateFlow<List<RoomTemperature>> = _roomTemperatures.asStateFlow()

    private val _heatingDemand = MutableStateFlow(loadCached(KEY_HEATING, PlcPayloads::parseHeating))
    override val heatingDemand: StateFlow<List<HeatingDemand>> = _heatingDemand.asStateFlow()

    private val _ventilation = MutableStateFlow(Ventilation())
    override val ventilation: StateFlow<Ventilation> = _ventilation.asStateFlow()

    private val _cooling = MutableStateFlow(Cooling())
    override val cooling: StateFlow<Cooling> = _cooling.asStateFlow()

    private val _plcStatus = MutableStateFlow(PlcStatus())
    override val plcStatus: StateFlow<PlcStatus> = _plcStatus.asStateFlow()

    private val _heatPump = MutableStateFlow(loadPersistedHeatPump())
    override val heatPump: StateFlow<HeatPumpStatus> = _heatPump.asStateFlow()

    private val _ruuvi = MutableStateFlow<Map<String, RuuviReading>>(emptyMap())
    override val ruuvi: StateFlow<Map<String, RuuviReading>> = _ruuvi.asStateFlow()

    init {
        scope.launch {
            mqtt.messages.collect { msg ->
                // Ruuvi topics are per-tag (ruuvi/<gw>/<mac>), so match by prefix
                // before the exact-topic dispatch below. A message that isn't a
                // decoded, mapped tag parses to null and is ignored.
                if (msg.topic.startsWith(MqttTopics.RUUVI_PREFIX)) {
                    RuuviPayloads.parse(msg.text())?.let { reading ->
                        _ruuvi.update { it + (reading.sensorName to reading) }
                    }
                    return@collect
                }
                when (msg.topic) {
                    MqttTopics.TEMPERATURES -> {
                        val text = msg.text()
                        _roomTemperatures.value = PlcPayloads.parseTemperatures(text)
                        // The cooling-coil coolant PT100s ride on this topic, not
                        // `cooling`; merge them in without clobbering pump state.
                        val extras = PlcPayloads.parseExtraTemperatures(text)
                        fun coil(key: String) =
                            extras.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
                        _cooling.update {
                            it.copy(
                                coilTemp1 = coil("jaahdpatteri_1").calibratedRtd2() ?: it.coilTemp1,
                                coilTemp2 = coil("jaahdpatteri_2").calibratedRtd2() ?: it.coilTemp2,
                                // The third PT100 on the same analog module: the live,
                                // calibrated supply-duct temp for the Tuloilma tile.
                                supplyDuctC = coil("tuloilmakanava").calibratedRtd2() ?: it.supplyDuctC,
                            )
                        }
                        cache(KEY_TEMPS, text)
                    }
                    MqttTopics.HEATING -> {
                        val text = msg.text()
                        _heatingDemand.value = PlcPayloads.parseHeating(text)
                        cache(KEY_HEATING, text)
                    }
                    MqttTopics.VENTILATION ->
                        _ventilation.value = PlcPayloads.parseVentilation(msg.text())
                    MqttTopics.COOLING -> {
                        // Only the pump states live here; keep the coil temps the
                        // temperatures topic merged in.
                        val parsed = PlcPayloads.parseCooling(msg.text())
                        _cooling.update {
                            it.copy(pumpCooling = parsed.pumpCooling, coolingPump = parsed.coolingPump)
                        }
                    }
                    MqttTopics.STATUS ->
                        _plcStatus.value = PlcPayloads.parseStatus(msg.text())
                    MqttTopics.THERMIQ ->
                        PlcPayloads.parseThermiq(msg.text())?.let {
                            _heatPump.value = it
                            persistHeatPump(it)
                        }
                }
            }
        }
        // The ThermIQ heat-pump topic isn't retained, so a fresh connection
        // replays nothing and the Maalämpö/Käyttövesi/Sisäilma tiles would read
        // "Ei tietoa" until the bridge next publishes on its own. Ask it to
        // publish the instant we (re)connect — subscriptions are already live by
        // the time the state is Connected — so the tiles fill within a second or
        // two instead of waiting out the refresh timer.
        scope.launch {
            mqtt.connectionState.collect { state ->
                when (state) {
                    is MqttConnectionState.Connected -> bestEffortSuspend { requestHeatPumpRead() }
                    // Drop cached Ruuvi readings on disconnect. Otherwise, after the
                    // app is backgrounded (connections stop), the stale timestamps
                    // linger and the "sensor offline" alert fires on foreground until
                    // fresh readings arrive — a false alarm, worst for a weak-signal
                    // tag (fridge/freezer) that is slow to refresh. An empty map has
                    // no tags to flag; they repopulate fresh within seconds.
                    is MqttConnectionState.Disconnected,
                    is MqttConnectionState.Failed -> _ruuvi.value = emptyMap()
                    else -> {}
                }
            }
        }
    }

    override suspend fun requestHeatPumpRead() {
        if (mqtt.connectionState.value is MqttConnectionState.Connected) {
            bestEffortSuspend { mqtt.publish(MqttTopics.THERMIQ_READ, "", qos = 0) }
        }
    }

    /** Last heat-pump reading, cached so the tiles aren't blank on a cold start. */
    private fun loadPersistedHeatPump(): HeatPumpStatus {
        val cached = settings.getStringOrNull(KEY_HEAT_PUMP) ?: return HeatPumpStatus(available = false)
        return runCatching {
            json.decodeFromString(HeatPumpStatus.serializer(), cached)
        }.getOrElse { HeatPumpStatus(available = false) }
    }

    private fun persistHeatPump(status: HeatPumpStatus) {
        runCatching {
            settings.putString(KEY_HEAT_PUMP, json.encodeToString(HeatPumpStatus.serializer(), status))
        }
    }

    /** Re-parse the last cached raw payload for a live topic; empty if none/bad. */
    private fun <T> loadCached(key: String, parse: (String) -> List<T>): List<T> =
        settings.getStringOrNull(key)?.let { runCatching { parse(it) }.getOrNull() } ?: emptyList()

    private fun cache(key: String, payload: String) {
        runCatching { settings.putString(key, payload) }
    }

    override suspend fun heatPumpStatus(): JsonObject = mcp.getThermiaStatus()

    override suspend fun airQuality(): AirQuality = mcp.getAirQuality()

    override suspend fun hvacSummary(): HvacSummary {
        val values = bestEffortSuspend {
            flux.latest(
                "hvac",
                listOf(
                    "Ulkolampotila", "LTO_hyotysuhde", "Suhteellinen_kosteus",
                    // Inputs for the computed heat-recovery efficiency.
                    "Tuloilma_ennen_lammitysta", "Tuloilma_asetusarvo",
                    // MVHR duct temps for the ventilation diagram. Tuloilmakanava is
                    // the air actually delivered to the rooms (post cooling battery).
                    "Poistoilma", "Jateilma", "Tuloilma_jalkeen_lammityksen", "Tuloilmakanava",
                    FIELD_HEAT_PUMP_POWER, "Alarm_freezing_danger",
                    "Alarm_filter_guard", "Alarm_fan_failure_supply",
                    "Alarm_fan_failure_extract", "Alarm_service_reminder",
                ),
            )
        }.orEmpty()

        val alarmFields = listOf(
            "Alarm_freezing_danger", "Alarm_filter_guard",
            "Alarm_fan_failure_supply", "Alarm_fan_failure_extract",
            "Alarm_service_reminder",
        )
        // The LTO_hyotysuhde field is a not-connected sensor that pins to 0, so
        // compute the supply-side recovery efficiency from the exchanger gradient:
        //   η = (supply_after_HRU − outdoor) / (extract − outdoor) × 100
        // Extract (room-return) air is the warm side. (This replaces the legacy
        // Tuloilma_asetusarvo setpoint, which is no longer published.) Valid only
        // when there's a real gradient — in mild weather outdoor ≈ extract, so the
        // result is undefined/out of range and left null ("Ei tietoa").
        val outdoor = values["Ulkolampotila"]
        val supplyAfterHru = values["Tuloilma_ennen_lammitysta"]
        val extract = values["Poistoilma"]
        val recoveryEfficiency = if (outdoor != null && supplyAfterHru != null && extract != null && extract != outdoor) {
            ((supplyAfterHru - outdoor) / (extract - outdoor) * 100.0).takeIf { it > 0.0 && it <= 100.0 }
        } else {
            null
        }

        return HvacSummary(
            outdoorC = values["Ulkolampotila"],
            recoveryEfficiencyPct = recoveryEfficiency,
            humidityPct = values["Suhteellinen_kosteus"],
            heatPumpPowerKw = values[FIELD_HEAT_PUMP_POWER],
            freezingDanger = (values["Alarm_freezing_danger"] ?: 0.0) > 0.0,
            anyAlarm = alarmFields.any { (values[it] ?: 0.0) > 0.0 },
            extractC = values["Poistoilma"],
            exhaustC = values["Jateilma"],
            supplyPreHeatC = values["Tuloilma_ennen_lammitysta"],
            supplyPostHeatC = values["Tuloilma_jalkeen_lammityksen"],
            supplyDuctC = values["Tuloilmakanava"].calibratedRtd2(),
        )
    }

    override suspend fun temperatureHistory(
        range: String,
        every: String,
    ): Map<String, List<FluxPoint>> {
        val outdoor = bestEffortSuspend {
            flux.history("hvac", listOf("Ulkolampotila"), range, every)
        }.orEmpty()
        val rooms = bestEffortSuspend {
            flux.history("rooms", Rooms.ALL.map { it.influxField }, range, every)
        }.orEmpty()

        return buildMap {
            outdoor["Ulkolampotila"]?.let { put("Ulko", it) }
            rooms.forEach { (field, points) ->
                // Influx field names are legacy and mis-assigned; Rooms is truth.
                Rooms.byInfluxField[field]?.let { put(it.displayName, points) }
            }
        }
    }

    override suspend fun metricHistory(
        measurement: String,
        field: String,
        range: String,
        every: String,
        tagKey: String?,
        tagValue: String?,
    ): List<Float> = bestEffortSuspend {
        flux.history(measurement, listOf(field), range, every, tagKey, tagValue)[field]
            .orEmpty()
            .map { it.value.toFloat() }
    }.orEmpty()

    override suspend fun recoveryEfficiencyHistory(range: String, every: String): List<Float> =
        bestEffortSuspend { flux.recoveryEfficiencyHistory(range, every).map { it.value.toFloat() } }.orEmpty()

    override suspend fun outdoorRuuviTemp(): Double? =
        bestEffortSuspend { flux.latest("ruuvi", "temperature", "sensor_name", "Ulkolämpötila") }

    override suspend fun saunaHeatState(): SaunaHeatState = bestEffortSuspend {
        // A 16 h window comfortably brackets any real sauna session (electric,
        // heats fast), so the cold start before the current climb is captured;
        // 5 min resolution pins the onset closely without a huge point count.
        val points = flux.history(
            measurement = "ruuvi",
            fields = listOf("temperature"),
            range = "-16h",
            every = "5m",
            tagKey = "sensor_name",
            tagValue = "Sauna",
        )["temperature"].orEmpty()
        SaunaHeatState(
            startEpoch = saunaHeatingOnsetIso(points)?.let { Instant.parse(it).epochSeconds },
            holding = saunaHolding(points),
        )
    } ?: SaunaHeatState(startEpoch = null, holding = false)

    private companion object {
        const val FIELD_HEAT_PUMP_POWER = "Lampopumppu_teho"
        const val KEY_HEAT_PUMP = "climate.heatpump"
        const val KEY_TEMPS = "climate.temperatures"
        const val KEY_HEATING = "climate.heating"

        /**
         * Calibration the PLC applies to the three PT100s on analog module
         * `_4AI_Pt100_RTD_2` — `Tuloilmakanava`, `Jaahdpatteri_1`, `Jaahdpatteri_2`
         * — but only in its `AirCondition` visu (and the cooling control), *not* in
         * the raw MQTT publish. The PLC formula is `(rawInt - 20) / 10`, i.e. these
         * sensors read 2.0 °C high (long cable runs); the controller subtracts it.
         * Both our feeds (the live `temperatures` MQTT topic and the InfluxDB
         * `Tuloilmakanava` field) carry the *uncalibrated* value, so we apply the
         * same −2.0 °C here to match what the PLC's HVAC page shows. See
         * ../marmorikatu-plc Tero.export (POU `AirCondition`, lines ~2976–2986).
         */
        const val PT100_RTD2_CALIBRATION_C = -2.0
        fun Double?.calibratedRtd2(): Double? = this?.plus(PT100_RTD2_CALIBRATION_C)
    }
}

/**
 * How far above the window's idle baseline the Sauna reading must climb to mark
 * the ignition point. Kept small (just clear of sensor noise, ~±0.5 °C, and the
 * few tenths of idle drift) so the *start* of the ramp is caught, not a point a
 * quarter-hour into it — measured idle sits rock-steady at 24 °C, so +3 °C
 * cleanly separates heating from rest. This is only ever computed when the sauna
 * is already confirmed heating, so a low margin can't be a false positive; it
 * only decides *when* the confirmed heat-up began.
 */
private const val SAUNA_ONSET_DELTA_C = 3.0

/**
 * The ISO time the current sauna heat-up began, from a Sauna temperature series
 * ([points], oldest→newest). Walks back from the newest sample through the
 * contiguous run that sits above `baseline + [deltaC]` and returns that run's
 * earliest sample — the ignition point. A within-session door-opening dip stays
 * above the threshold, so the run isn't split; a separate earlier session is
 * ignored when the sauna cooled to idle between them (the common case). Returns
 * null when the newest sample is already back at idle or the series is too short.
 *
 * Known limitation: two back-to-back sessions whose valley never drops below
 * `baseline + deltaC` (a re-light within an hour or two while the room stays
 * warm) merge into one run, so the reported ignition can be too early.
 */
internal fun saunaHeatingOnsetIso(points: List<FluxPoint>, deltaC: Double = SAUNA_ONSET_DELTA_C): String? {
    if (points.size < 3) return null
    val threshold = points.minOf { it.value } + deltaC
    var startIso: String? = null
    for (i in points.indices.reversed()) {
        if (points[i].value >= threshold) startIso = points[i].timeIso else break
    }
    return startIso
}

/** A Sauna reading at least this warm counts as a hot sauna, not cool ambient. */
private const val SAUNA_HOT_FLOOR_C = 50.0

/** Recent samples the plateau peak is taken over (≈ this many × the 5-min step). */
private const val SAUNA_PEAK_WINDOW = 8

/** How far below the recent peak the latest reading must sit to look like cooling. */
private const val SAUNA_FALL_MARGIN_C = 3.0

/** Samples back the "still declining" check compares against (≈ ×5-min). */
private const val SAUNA_DECLINE_LOOKBACK = 3

/**
 * Whether the Sauna series ([points], oldest→newest) shows the sauna hot and
 * *holding* — the thermostat cycling around a setpoint — rather than cooling
 * after being switched off. A single temperature can't tell 65 °C climbing,
 * holding, and cooling apart, so this reads the recent trend: it is cooling only
 * when the latest reading has dropped clearly below the recent-window peak AND is
 * still lower than it was ~15 min ago. That catches a slow late-stage cooldown
 * (which a fixed drop-rate threshold misses as it nears ambient), while a löyly
 * dip that recovers and normal setpoint jitter both stay "holding". Below
 * [hotFloor] it is off outright. Pure, so it's unit-tested.
 */
internal fun saunaHolding(
    points: List<FluxPoint>,
    hotFloor: Double = SAUNA_HOT_FLOOR_C,
    peakWindow: Int = SAUNA_PEAK_WINDOW,
    fallMargin: Double = SAUNA_FALL_MARGIN_C,
    declineLookback: Int = SAUNA_DECLINE_LOOKBACK,
): Boolean = saunaHoldingFromTemps(points.map { it.value }, hotFloor, peakWindow, fallMargin, declineLookback)

/**
 * The [saunaHolding] verdict from a plain temperature series ([values],
 * oldest→newest) — the same logic the InfluxDB path uses, exposed (public, so the
 * composeApp module can reach it) for the live MQTT Ruuvi trend the app keeps so
 * the sauna reads "on/off" straight off the live sensor when InfluxDB is
 * unavailable (or slow to reflect a switch-off).
 */
fun saunaHoldingFromTemps(
    values: List<Double>,
    hotFloor: Double = SAUNA_HOT_FLOOR_C,
    peakWindow: Int = SAUNA_PEAK_WINDOW,
    fallMargin: Double = SAUNA_FALL_MARGIN_C,
    declineLookback: Int = SAUNA_DECLINE_LOOKBACK,
): Boolean {
    val current = values.lastOrNull() ?: return false
    if (current < hotFloor) return false
    val recentPeak = values.takeLast(peakWindow).max()
    val belowPeak = current <= recentPeak - fallMargin
    val earlier = values.getOrNull(values.size - 1 - declineLookback)
    val stillFalling = earlier != null && current < earlier
    return !(belowPeak && stillFalling)
}
