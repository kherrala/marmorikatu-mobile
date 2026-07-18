package fi.marmorikatu.app.shell

/**
 * Finnish, first-person "what the assistant is doing right now" labels for the
 * MCP tools, shown in the voice overlay's thinking state instead of the raw
 * snake_case tool name (e.g. `get_daily_report` → "Kokoan päivän katsausta").
 *
 * Names mirror the backend registry (../marmorikatu-home-automation, the
 * scripts/mcp_tools modules). Keep in sync when tools are added there; an
 * unmapped tool falls back to a generic "Haen tietoja…".
 */
private val MCP_TOOL_LABELS: Map<String, String> = mapOf(
    // Lights
    "list_lights" to "Selaan valoja",
    "get_light_status" to "Tarkistan valojen tilaa",
    "set_light" to "Säädän valoa",
    "set_all_lights" to "Säädän kaikkia valoja",
    "set_lights_by_floor" to "Säädän kerroksen valoja",
    "set_lights_matching" to "Säädän valoja",
    "get_lights_optimizer_status" to "Tarkistan valo-optimointia",
    // Ventilation / air
    "get_heat_recovery_efficiency" to "Tarkistan lämmöntalteenottoa",
    "get_freezing_probability" to "Arvioin jäätymisriskiä",
    "get_room_temperatures" to "Haen huonelämpötiloja",
    "get_air_quality" to "Tarkistan ilmanlaatua",
    "compare_indoor_outdoor" to "Vertailen sisä- ja ulkolämpötilaa",
    // Ground-source heat pump (Thermia)
    "get_thermia_status" to "Tarkistan maalämpöpumppua",
    "get_thermia_temperatures" to "Haen maalämmön lämpötiloja",
    "get_heatpump_cop" to "Tarkistan lämpökerrointa",
    "get_brine_circuit" to "Tarkistan liuospiiriä",
    "get_hotwater_analysis" to "Tarkistan käyttövettä",
    "get_thermia_register_data" to "Luen maalämmön rekistereitä",
    "get_compressor_duty_cycle" to "Tarkistan kompressorin käyntiaikaa",
    // Energy
    "get_energy_consumption" to "Haen energiankulutusta",
    "get_electricity_prices" to "Tarkistan sähkön hintaa",
    "get_heating_status" to "Tarkistan lämmityksen tilaa",
    "get_energy_cost" to "Lasken energiakustannuksia",
    // Sauna
    "get_sauna_status" to "Tarkistan saunan tilaa",
    // TV (Harmony)
    "harmony_list_activities" to "Selaan TV-toimintoja",
    "harmony_current_activity" to "Tarkistan television tilaa",
    "harmony_start_activity" to "Käynnistän television",
    "harmony_power_off" to "Sammutan television",
    "harmony_list_devices" to "Selaan laitteita",
    "harmony_list_commands" to "Selaan komentoja",
    "harmony_send_command" to "Ohjaan televisiota",
    // External services
    "get_weather_forecast" to "Haen sääennustetta",
    "get_news_headlines" to "Haen uutisotsikoita",
    "get_news_article" to "Luen uutista",
    "get_bus_departures" to "Tarkistan bussiaikatauluja",
    "get_calendar_events" to "Tarkistan kalenteria",
    // Daily summary
    "get_daily_report" to "Kokoan päivän katsausta",
    // Generic InfluxDB introspection / queries
    "describe_schema" to "Tutkin tietokantaa",
    "list_measurements" to "Selaan mittauksia",
    "describe_measurement" to "Tutkin mittausta",
    "query_data" to "Haen tietoja",
    "get_latest" to "Haen tuoreimpia lukemia",
    "get_statistics" to "Lasken tilastoja",
    "get_time_range" to "Tarkistan aikaväliä",
)

/** The Finnish "doing" label for an MCP [tool] name, or a generic fallback. */
fun mcpToolLabel(tool: String): String =
    MCP_TOOL_LABELS[tool] ?: MCP_TOOL_LABELS[tool.removePrefix("mcp__")] ?: "Haen tietoja"
