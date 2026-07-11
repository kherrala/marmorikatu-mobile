package fi.marmorikatu.core.model

/**
 * The single source of truth for room identity.
 *
 * The PLC's MQTT keys are current; the InfluxDB field names are CSV-era
 * legacy that was deliberately kept so old Grafana dashboards keep working.
 * They do **not** agree: the backend maps `yk_aatu → MH_Seela`,
 * `yk_onni → MH_Aarni`, `yk_essi → MH_aikuiset` (see `ROOM_TEMP_MAP` in
 * `plc_mqtt_subscriber.py`). Labelling a chart from the Influx field name
 * would therefore show the wrong child's bedroom — always route through here.
 */
data class Room(
    /** Key in the retained `marmorikatu/temperatures` payload. */
    val mqttKey: String,
    /** Field name in the InfluxDB `rooms` measurement. */
    val influxField: String,
    /** Field holding this room's underfloor-heating PID output, 0–100 %. */
    val influxPidField: String = "${influxField}_PID",
    /** Key in the retained `marmorikatu/heating` payload. */
    val heatingKey: String,
    val displayName: String,
    val floor: Floor,
)

object Rooms {
    val ALL = listOf(
        Room("yk_aula", "Ylakerran_aula", heatingKey = "yk_aula", displayName = "Yläkerta aula", floor = Floor.YLAKERTA),
        // The PLC/MQTT and heating keys are legacy (named after the original
        // occupants Aatu/Onni/Essi); the rooms have since changed hands. Display
        // names follow the current owners, matching the InfluxDB fields the
        // backend already renamed (MH_Seela, MH_Aarni, MH_aikuiset).
        Room("yk_aatu", "MH_Seela", heatingKey = "aatu", displayName = "Seelan huone", floor = Floor.YLAKERTA),
        Room("yk_onni", "MH_Aarni", heatingKey = "onni", displayName = "Aarnin huone", floor = Floor.YLAKERTA),
        Room("yk_essi", "MH_aikuiset", heatingKey = "essi", displayName = "Aikuisten makuuhuone", floor = Floor.YLAKERTA),
        Room("keittio", "Keittio", heatingKey = "keittio", displayName = "Keittiö", floor = Floor.ALAKERTA),
        Room("mh_ak", "MH_alakerta", heatingKey = "mh_ak", displayName = "Makuuhuone alakerta", floor = Floor.ALAKERTA),
        Room("eteinen", "Eteinen", heatingKey = "eteinen", displayName = "Eteinen", floor = Floor.ALAKERTA),
        Room("kellari_eteinen", "Kellari_eteinen", heatingKey = "kellari_eteinen", displayName = "Kellarin eteinen", floor = Floor.KELLARI),
        Room("kellari", "Kellari", heatingKey = "kellari", displayName = "Kellari", floor = Floor.KELLARI),
    )

    val byMqttKey: Map<String, Room> = ALL.associateBy { it.mqttKey }
    val byInfluxField: Map<String, Room> = ALL.associateBy { it.influxField }
    val byHeatingKey: Map<String, Room> = ALL.associateBy { it.heatingKey }

    /**
     * Rooms worth showing on the climate carousel + temperature chart. The
     * basement counts as living space (the office and home theatre are down
     * there); only its bare entrance hallway is left out.
     */
    val livingSpaces: List<Room> = ALL.filter { it.mqttKey != "kellari_eteinen" }
}
