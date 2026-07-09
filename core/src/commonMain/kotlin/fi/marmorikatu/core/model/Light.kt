package fi.marmorikatu.core.model

import kotlinx.serialization.Serializable

/**
 * A controllable light or outlet.
 *
 * [id] is the PLC `PersistentVars.Controls[]` index used in the MQTT command
 * topic `marmorikatu/light/<id>/set`. Names and floors come from the MCP
 * `list_lights` catalog (server-curated Finnish labels), not from the PLC.
 */
@Serializable
data class Light(
    val id: Int,
    val name: String,
    val floor: Floor,
    /** Last state confirmed by the retained `marmorikatu/lights` topic. */
    val isOn: Boolean,
    /** Desired state of an in-flight command, null when settled. */
    val pendingOn: Boolean? = null,
) {
    /** What the UI should show: optimistic while a command is in flight. */
    val displayedOn: Boolean get() = pendingOn ?: isOn
}

@Serializable
enum class Floor(val label: String) {
    KELLARI("Kellari"),
    ALAKERTA("Alakerta"),
    YLAKERTA("Yläkerta"),
    ULKO("Ulko");

    companion object {
        /** Server floor numbering: 0=Kellari, 1=Alakerta, 2=Yläkerta, null=Ulko. */
        fun fromServer(floor: Int?): Floor = when (floor) {
            0 -> KELLARI
            1 -> ALAKERTA
            2 -> YLAKERTA
            else -> ULKO
        }
    }
}

/** Catalog entry from MCP `list_lights` (no live state attached). */
@Serializable
data class LightInfo(
    val id: Int,
    val name: String,
    val floor: Floor,
)
