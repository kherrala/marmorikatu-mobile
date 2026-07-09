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

    /** All state topics the app subscribes to. */
    val STATE_SUBSCRIPTIONS = listOf(
        LIGHTS, LIGHT_NAMES, OUTLETS, TEMPERATURES, HEATING, COOLING,
        VENTILATION, STATUS, ENERGY_HEATPUMP, ENERGY_EXTRA,
    )

    /**
     * Command topic for one light. Payload is the literal string
     * `"true"` or `"false"`, QoS 1, retain=false.
     */
    fun lightSet(index: Int): String = "marmorikatu/light/$index/set"

    fun lightSetPayload(on: Boolean): String = if (on) "true" else "false"
}
