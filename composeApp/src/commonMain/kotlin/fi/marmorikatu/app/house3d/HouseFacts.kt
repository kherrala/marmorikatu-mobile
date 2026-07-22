package fi.marmorikatu.app.house3d

import fi.marmorikatu.app.components.AttentionItem
import fi.marmorikatu.app.screens.LIGHT_AREAS
import fi.marmorikatu.core.model.Announcement
import fi.marmorikatu.core.model.RuuviReading
import fi.marmorikatu.core.model.RuuviSensors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** The visual style of a floating marker — colours its dot. */
enum class MarkerKind { Person, Door, Sauna, Info, Alert, Announcement }

/**
 * A labelled point pinned to a world position that reads through walls: an
 * infomercial fact backed by a live reading, placed at the right room. [group]
 * lets it ride the explode offset with its floor.
 */
class HouseMarker(
    val label: String,
    val sub: String?,
    val pos: Vec3,
    val group: HouseGroup,
    val kind: MarkerKind,
    /** Latest-known presentation value, but no longer fresh enough for live semantics. */
    val stale: Boolean = false,
)

/** Camera framing for a page of infographic pins (or one pinned alert). */
fun markerFocus(markers: List<HouseMarker>): OrbitPreset? {
    if (markers.isEmpty()) return null
    val center = Vec3(
        markers.sumOf { it.pos.x.toDouble() }.toFloat() / markers.size,
        markers.sumOf { it.pos.y.toDouble() }.toFloat() / markers.size,
        markers.sumOf { it.pos.z.toDouble() }.toFloat() / markers.size,
    )
    var spread = 0f
    for (marker in markers) {
        spread = max(spread, abs(marker.pos.x - center.x))
        spread = max(spread, abs(marker.pos.z - center.z))
    }
    // A single small-room observation (notably sauna) still needs enough floor
    // context to orient the viewer; six metres filled the screen with one room.
    return OrbitPreset(center, max(10.0f, spread * 2.5f + 4f), 0.62f)
}

private fun oneDecimal(v: Double): String {
    val r = (v * 10).roundToInt()
    val whole = r / 10
    val frac = abs(r % 10)
    val sign = if (r < 0 && whole == 0) "-" else ""
    return "$sign$whole,$frac"
}

/**
 * The infomercial fact reel. Values are always real measurements, but may be
 * latest-known readings retained for the presentation loop; [staleSensors]
 * makes that provenance visible and never changes a fact into an alert.
 */
fun liveFacts(
    saunaC: Double?,
    outdoorC: Double?,
    kitchenCo2: Int?,
    lightsOn: Int,
    livingC: Double? = null,
    kitchenC: Double? = null,
    fireplaceC: Double? = null,
    fridgeC: Double? = null,
    freezerC: Double? = null,
    staleSensors: Set<String> = emptySet(),
): List<HouseMarker> = buildList {
    // Ordering deliberately forms spatially-varied pages of three for the
    // infomercial carousel rather than stacking three labels in one room.
    saunaC?.let {
        add(HouseMarker("Sauna", "${oneDecimal(it)}°", Vec3(1.35f, 1.1f, -6.6f), HouseGroup.Krs1, MarkerKind.Sauna, RuuviSensors.SAUNA in staleSensors))
    }
    outdoorC?.let {
        // In the front yard, south of the house, raised to chest height so the
        // label reads clearly against the façade instead of sitting on the grass.
        add(HouseMarker("Ulkoilma", "${oneDecimal(it)}°", Vec3(5.7f, 1.6f, 3.6f), HouseGroup.Krs1, MarkerKind.Info, RuuviSensors.OUTDOOR in staleSensors))
    }
    kitchenCo2?.let {
        // The attention feed owns the persistent high-CO₂ alert. Keeping this
        // infographic as Info avoids two pins touring the exact same source.
        add(HouseMarker("Keittiön CO₂", "$it ppm", Vec3(9.45f, 1.1f, -2.9f), HouseGroup.Krs1, MarkerKind.Info, RuuviSensors.AIR_QUALITY in staleSensors))
    }
    livingC?.let {
        add(HouseMarker("Olohuone", "${oneDecimal(it)}°", Vec3(13.82f, 1.1f, -5.58f), HouseGroup.Krs1, MarkerKind.Info, "Olohuone" in staleSensors))
    }
    kitchenC?.let {
        add(HouseMarker("Keittiö", "${oneDecimal(it)}°", Vec3(9.45f, 1.1f, -2.9f), HouseGroup.Krs1, MarkerKind.Info, "Keittiö" in staleSensors))
    }
    fireplaceC?.let {
        // Anchored to the model's tiled mass fireplace + flue (spec `F1.fire`/
        // `F1.flue`, Blender ~(11.24, 5.9)); app maps x→x, y→z-up, −Blender.y→z.
        add(HouseMarker("Takka", "${oneDecimal(it)}°", Vec3(11.24f, 1.1f, -5.9f), HouseGroup.Krs1, MarkerKind.Info, "Takka" in staleSensors))
    }
    fridgeC?.let {
        // The kitchen's tall VK/JK unit off the street wall (spec `F1.kit.tall0`).
        add(HouseMarker("Jääkaappi", "${oneDecimal(it)}°", Vec3(9.86f, 1.2f, -1.07f), HouseGroup.Krs1, MarkerKind.Info, RuuviSensors.FRIDGE in staleSensors))
    }
    freezerC?.let {
        // The adjacent PA freezer unit (spec `F1.kit.tall1`).
        add(HouseMarker("Pakastin", "${oneDecimal(it)}°", Vec3(10.47f, 1.2f, -1.07f), HouseGroup.Krs1, MarkerKind.Info, RuuviSensors.FREEZER in staleSensors))
    }
    if (lightsOn > 0) {
        add(HouseMarker("Valot päällä", "$lightsOn kpl", Vec3(8.5f, 1.5f, -4.0f), HouseGroup.Krs1, MarkerKind.Info))
    }
}

/**
 * PLC room key → (model room node name, floor group) for the floors that have no
 * Ruuvi sensors (2nd floor + basement). Anchoring to the model's real room node
 * lets the pins sit over the right room in each per-floor view.
 */
private val ROOM_TEMP_NODES: Map<String, Pair<String, HouseGroup>> = mapOf(
    // 2nd floor bedrooms + hall
    "yk_essi" to ("Room_2krs_MH" to HouseGroup.Krs2),   // Aikuisten makuuhuone
    "yk_onni" to ("Room_2krs_MH2" to HouseGroup.Krs2),  // Aarnin huone
    "yk_aatu" to ("Room_2krs_MH3" to HouseGroup.Krs2),  // Seelan huone
    "yk_aula" to ("Room_2krs_AULA" to HouseGroup.Krs2), // Yläkerran aula
    // basement: PLC has two loops; the model splits it into teatteri/varasto/wc.
    "kellari" to ("Room_kellari_VAR1" to HouseGroup.Kellari),          // teatteri/työhuone
    "kellari_eteinen" to ("Room_kellari_VAR2" to HouseGroup.Kellari),  // varasto
)

private fun roomTempLabel(key: String): String = when (key) {
    "yk_essi" -> "Makuuhuone"
    "yk_onni" -> "Aarni"
    "yk_aatu" -> "Seela"
    "yk_aula" -> "Yläkerran aula"
    "kellari" -> "Kellari"
    "kellari_eteinen" -> "Kellarin eteinen"
    else -> key
}

/**
 * Infographic facts for floors without Ruuvi sensors (2nd floor + basement).
 * Their values come from the PLC room temperatures, anchored to the model's real
 * room centres (via [roomCenter]) so each label sits over the right room — the
 * reason no upstairs/basement pins showed before.
 */
fun roomTempFacts(
    roomTemps: List<Pair<String, Double>>,
    roomCenter: (String) -> Vec3?,
): List<HouseMarker> = roomTemps.mapNotNull { (key, celsius) ->
    val (node, group) = ROOM_TEMP_NODES[key] ?: return@mapNotNull null
    val center = roomCenter(node) ?: return@mapNotNull null
    HouseMarker(
        roomTempLabel(key),
        "${oneDecimal(celsius)}°",
        Vec3(center.x, center.y + 1.0f, center.z),
        group,
        MarkerKind.Info,
    )
}

internal const val HOUSE_FACT_MAX_AGE_SECONDS = 30L * 60L

/** Whether a latest-known reading needs an explicit stale presentation label. */
internal fun isHouseReadingStale(
    reading: RuuviReading,
    nowEpochSeconds: Long,
    maxAgeSeconds: Long = HOUSE_FACT_MAX_AGE_SECONDS,
): Boolean = reading.tsEpoch <= 0L || nowEpochSeconds - reading.tsEpoch > maxAgeSeconds

/** Retains the newest real measurement per sensor across MQTT gaps. */
internal fun retainHouseReadings(
    previous: Map<String, RuuviReading>,
    current: Map<String, RuuviReading>,
): Map<String, RuuviReading> = buildMap {
    putAll(previous)
    current.forEach { (name, reading) ->
        val old = get(name)
        if (old == null || reading.tsEpoch >= old.tsEpoch) put(name, reading)
    }
}

/**
 * Active warn/alarm conditions become persistent world-space pins. Because the
 * list is rebuilt from live state, a pin remains until its source condition is
 * actually resolved; carousel timing can never dismiss it.
 */
fun activeAlertMarkers(items: List<AttentionItem>, model: HouseModel): List<HouseMarker> =
    items.mapNotNull { item ->
        if (item.status != "warn" && item.status != "alarm") return@mapNotNull null
        val roomName = alertRoomName(item.text)
        val room = roomName?.let { name -> model.rooms.firstOrNull { it.name == name } }
        val outdoor = isOutdoorAlert(item.text)
        HouseMarker(
            label = item.text,
            sub = item.value.takeIf { it.isNotBlank() },
            pos = when {
                room != null -> Vec3(room.center.x, room.center.y + 1.0f, room.center.z)
                outdoor -> Vec3(5.7f, 0.8f, 2.8f)
                else -> model.center
            },
            group = room?.group ?: HouseGroup.Krs1,
            kind = MarkerKind.Alert,
        )
    }

/**
 * Turns a live SSE announcement into a short-lived world-space source pin.
 * Events without a meaningful physical source (news and electricity-price
 * changes, for example) are still spoken, but deliberately do not move the
 * house camera.
 */
fun announcementMarker(announcement: Announcement, model: HouseModel): HouseMarker? {
    val areaKey = announcementLightArea(announcement)
    val isYard = areaKey == "ulko_piha" || announcement.kind.contains("person", ignoreCase = true) ||
        announcement.kind.contains("porch", ignoreCase = true) ||
        announcement.key.contains("person", ignoreCase = true) ||
        announcement.key.contains("porch", ignoreCase = true)
    val isOutdoor = isYard || announcement.kind.startsWith("weather_") ||
        announcement.kind.startsWith("outdoor_") || announcement.key == "outdoor_temp"
    val roomName = announcementRoomName(announcement)
    val room = roomName?.let { name -> model.rooms.firstOrNull { it.name == name } }
    if (room == null && !isOutdoor) return null

    val source = when {
        isYard -> "Etupiha"
        room != null -> HouseLightMap.roomTitle(room.name)
        else -> "Ulkoilma"
    }
    return HouseMarker(
        label = source,
        sub = announcement.text.trim().takeIf { it.isNotEmpty() }?.ellipsize(64),
        pos = room?.let { Vec3(it.center.x, it.center.y + 1.0f, it.center.z) }
            ?: Vec3(5.7f, 0.8f, 2.8f),
        group = room?.group ?: HouseGroup.Krs1,
        kind = MarkerKind.Announcement,
    )
}

/** Resolves backend event keys to GLB room nodes; kept internal for regression tests. */
internal fun announcementRoomName(announcement: Announcement): String? {
    val key = announcement.key.lowercase()
    val kind = announcement.kind.lowercase()
    val lightId = announcementLightId(announcement)
    if (lightId != null) {
        LIGHT_ROOM_OVERRIDES[lightId]?.let { return it }
        val area = LIGHT_AREAS.firstOrNull { candidate -> candidate.lights.any { it.id == lightId } }?.key
        HouseLightMap.roomToAreas.entries.firstOrNull { (_, areas) -> area in areas }?.key?.let { return it }
    }

    if (key.startsWith("room_temp:")) {
        return roomForBackendSensor(key.substringAfter(':'))
    }
    if (key.startsWith("co2:") || key.startsWith("pm25:")) {
        roomForBackendSensor(key.substringAfter(':'))?.let { return it }
    }
    if (key == "floor_heat:ylakerta") return "Room_2krs_AULA"
    if (key == "floor_heat:alakerta") return "Room_1krs_OH"
    if (kind.startsWith("sauna_") || key.startsWith("sauna_")) return "Room_1krs_LH"
    if (kind.startsWith("alarm_") || key.startsWith("alarm:") ||
        kind.startsWith("aux_heater_") || key.startsWith("aux:") ||
        kind.startsWith("plc_heartbeat_") || key == "plc_heartbeat" ||
        kind.startsWith("lto_") || key == "lto_efficiency"
    ) return "Room_1krs_TEKN"

    return alertRoomName("${announcement.key} ${announcement.text}")
}

private fun announcementLightId(announcement: Announcement): Int? {
    val key = announcement.key.lowercase()
    val prefix = listOf("light_on:", "light_off:", "lights_opt_on:", "lights_opt_off:")
        .firstOrNull(key::startsWith) ?: return null
    return key.removePrefix(prefix).substringBefore(':').toIntOrNull()
}

private fun announcementLightArea(announcement: Announcement): String? {
    val lightId = announcementLightId(announcement) ?: return null
    return LIGHT_AREAS.firstOrNull { area -> area.lights.any { it.id == lightId } }?.key
}

private fun roomForBackendSensor(raw: String): String? = when (raw.lowercase()) {
    "mh_aarni", "aatu", "aarni" -> "Room_2krs_MH2"
    "mh_seela", "onni", "seela" -> "Room_2krs_MH3"
    "mh_aikuiset", "essi", "aikuiset" -> "Room_2krs_MH"
    "ylakerran_aula", "yläkerran_aula" -> "Room_2krs_AULA"
    "mh_alakerta", "alakerran_makuuhuone" -> "Room_1krs_MH"
    "eteinen" -> "Room_1krs_ET"
    "olohuone" -> "Room_1krs_OH"
    "keittio", "keittiö" -> "Room_1krs_KT"
    else -> alertRoomName(raw)
}

private val LIGHT_ROOM_OVERRIDES = mapOf(
    1 to "Room_1krs_PH",
    4 to "Room_1krs_LH",
    35 to "Room_1krs_ET",
    36 to "Room_1krs_VH",
    37 to "Room_1krs_TK",
    38 to "Room_1krs_PH",
)

private fun String.ellipsize(maxLength: Int): String =
    if (length <= maxLength) this else take(maxLength - 1).trimEnd() + "…"

internal fun alertRoomName(text: String): String? {
    val value = text.lowercase()
    return when {
        "sauna" in value -> "Room_1krs_LH"
        "maalämpö" in value || "lämpöpumppu" in value || "ilmanvaihto" in value || "ivk" in value -> "Room_1krs_TEKN"
        "aarni" in value || "aarnin" in value -> "Room_2krs_MH2"
        "seela" in value || "seelan" in value -> "Room_2krs_MH3"
        "aikuisten makuuhuone" in value -> "Room_2krs_MH"
        "yläkerran aula" in value -> "Room_2krs_AULA"
        "alakerran makuuhuone" in value -> "Room_1krs_MH"
        "eteinen" in value -> "Room_1krs_ET"
        "pakastin" in value || "jääkaappi" in value || "co₂" in value ||
            "ilmanlaatu" in value || "keittiö" in value -> "Room_1krs_KT"
        "takka" in value || "olohuone" in value -> "Room_1krs_OH"
        "kellari" in value -> "Room_kellari_VAR1"
        else -> null
    }
}

private fun isOutdoorAlert(text: String): Boolean {
    val value = text.lowercase()
    return "ulko" in value || "sää" in value || "helle" in value || "myrsky" in value
}
