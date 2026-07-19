package fi.marmorikatu.core.transport.mqtt

/**
 * Topic map of the WAGO PLC publisher and ThermIQ bridge. All marmorikatu
 * state topics are retained — subscribing yields an instant full snapshot;
 * the PLC republishes roughly every 13 seconds.
 */
object MqttTopics {
    const val LIGHTS = "marmorikatu/lights"
    const val LIGHT_NAMES = "marmorikatu/names/lights"
    const val OUTLETS = "marmorikatu/outlets"
    const val SWITCHES = "marmorikatu/switches"
    const val TEMPERATURES = "marmorikatu/temperatures"
    const val HEATING = "marmorikatu/heating"
    const val COOLING = "marmorikatu/cooling"
    const val VENTILATION = "marmorikatu/ventilation"
    const val STATUS = "marmorikatu/status"
    const val ENERGY_HEATPUMP = "marmorikatu/energy/heatpump"
    const val ENERGY_EXTRA = "marmorikatu/energy/extra"

    /**
     * ThermIQ heat-pump register dump. Lives under its own root (not
     * `marmorikatu/`) and is published by the ThermIQ bridge, not the PLC.
     * Not retained, so the first value can lag a publish cycle after connect.
     */
    const val THERMIQ = "ThermIQ/marmorikatu/data"

    /**
     * Publishing an empty payload here asks the ThermIQ bridge to read all
     * registers and publish [THERMIQ] immediately — used on pull-to-refresh so
     * the heat-pump tiles don't wait for the next periodic publish.
     */
    const val THERMIQ_READ = "ThermIQ/marmorikatu/read"

    /**
     * Ruuvi Gateway feed — one message per tag under `ruuvi/<gw_mac>/<tag_mac>`,
     * already decoded to JSON (temperature, CO₂, …) with the real per-measurement
     * timestamp. The `#` wildcard also carries non-Ruuvi BLE devices, which the
     * parser discards; overall volume is only ~5–10 msg/s, well within the
     * message buffer.
     */
    const val RUUVI = "ruuvi/CC:F1:A2:8E:F8:8A/#"

    /** Prefix used to recognise a Ruuvi message by its per-tag topic. */
    const val RUUVI_PREFIX = "ruuvi/"

    /** All state topics the app subscribes to. */
    val STATE_SUBSCRIPTIONS = listOf(
        LIGHTS, LIGHT_NAMES, OUTLETS, TEMPERATURES, HEATING, COOLING,
        VENTILATION, STATUS, ENERGY_HEATPUMP, ENERGY_EXTRA, THERMIQ, RUUVI,
    )

    /**
     * Command topic for one light. Payload is the literal string
     * `"true"` or `"false"`, QoS 1, retain=false.
     */
    fun lightSet(index: Int): String = "marmorikatu/light/$index/set"

    fun lightSetPayload(on: Boolean): String = if (on) "true" else "false"

    /**
     * Provenance breadcrumb published alongside (not instead of) [lightSet], so
     * the home-automation lights-optimizer can tell a mobile-app command apart
     * from a physical wall press and never fights it. The PLC does NOT consume
     * this topic — it only reads `/set` (which stays byte-identical). Recorded
     * by `plc_mqtt_subscriber` as the `light_command` InfluxDB measurement.
     */
    fun lightCommand(index: Int): String = "marmorikatu/light/$index/command"

    fun lightCommandPayload(on: Boolean): String = """{"on":$on,"src":"mobile"}"""
}
