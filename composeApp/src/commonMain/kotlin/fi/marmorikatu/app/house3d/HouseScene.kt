package fi.marmorikatu.app.house3d

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max

/** A named light fixture anchor in world space (from `house-cameras.json`). */
class LightAnchor(val name: String, val pos: Vec3)

/**
 * An orbit-camera preset: where to look, how far, and the polar angle. [theta] is
 * an optional target yaw the camera should ease to when this preset is applied;
 * `null` keeps the current yaw (so most focus moves don't twist the house).
 */
data class OrbitPreset(val target: Vec3, val radius: Float, val phi: Float, val theta: Float? = null)

/**
 * Frames a selected room so it sits in the visible top band of the stage — the room
 * detail card covers the lower half. Pulls back a little (so the room isn't jammed
 * against the lens), tilts to a 3/4 look, and drops the look-point below the room so
 * the room rides high on screen, clear of the card.
 */
fun comfortableRoomFocus(preset: OrbitPreset): OrbitPreset =
    preset.copy(
        radius = preset.radius.coerceAtLeast(MIN_ROOM_FOCUS_RADIUS),
        phi = ROOM_FOCUS_PHI,
        target = Vec3(preset.target.x, preset.target.y - ROOM_FOCUS_LIFT, preset.target.z),
    )

private const val MIN_ROOM_FOCUS_RADIUS = 12f
// A 3/4 look (tilted off top-down) so a world-Y target drop reads as a screen-up shift.
private const val ROOM_FOCUS_PHI = 0.42f
// Metres to drop the look-point below the room, lifting the room clear of the card.
private const val ROOM_FOCUS_LIFT = 2.6f

// Metres above a floor's room-slab level to aim the floor-view camera (≈ mid-room),
// matching the model author's per-room camera heights.
private const val FLOOR_TARGET_LIFT = 1.1f

// Phone floor view: fraction of the plan's X-extent to bias the look-point along X
// (the screen-vertical axis). The framing is very sensitive to this — 0.14 sat the
// plan too high (top wall clipped behind the header), 0.0 dropped it far too low;
// lowering it slides the whole plan down, clearing the header at the top.
private const val FLOOR_NEAR_FAR_BIAS = 0.05f

/**
 * A dark-mode point light. [level] 1.0 is a full room lamp; smaller values are a subtle
 * glow (e.g. driven by a room's live illuminance).
 */
data class LitLight(val pos: Vec3, val level: Float)

/** 3D room patch → the `presence/<room>` sensor whose illuminance lights it. */
private val ROOM_TO_PRESENCE = mapOf(
    "Room_1krs_OH" to "living_room",
    "Room_kellari_VAR1" to "theater",
    "Room_1krs_ET" to "hall_down",
    "Room_2krs_AULA" to "hall_up",
    "Room_1krs_WC" to "wc_down",
    "Room_kellari_WC" to "wc_basement",
    "Room_1krs_KHH" to "khh",
    "Room_2krs_KPH" to "bath_up",
    "Room_2krs_MH2" to "bedroom_seela",
    "Room_2krs_MH3" to "bedroom_aarni",
    "Room_2krs_MH" to "bedroom_adults",
)

// The measured lux only reaches ~60 in a well-lit room, and a dark room floors around a
// few lux; GLOW_MAX_LEVEL keeps the effect a subtle hint on top of the fixture lights.
private const val GLOW_MAX_LEVEL = 0.22f
private const val GLOW_LUX_FLOOR = 4
private const val GLOW_LUX_FULL = 60

private fun luxToGlowLevel(lux: Int): Float {
    val t = (lux - GLOW_LUX_FLOOR).coerceAtLeast(0).toFloat() /
        (GLOW_LUX_FULL - GLOW_LUX_FLOOR).toFloat()
    return t.coerceIn(0f, 1f) * GLOW_MAX_LEVEL
}

/**
 * Subtle per-room glows for the dark 3D view driven by real room illuminance. A room with
 * its own presence sensor uses that lux; every other room borrows the nearest sensor
 * room's lux (its open-space neighbour). Only rooms on the shown floor are returned.
 */
fun illuminanceGlows(
    illuminance: Map<String, Int>,
    presets: CameraPresets,
    mode: FloorMode,
    explode: Float,
): List<LitLight> {
    if (illuminance.isEmpty()) return emptyList()
    // Sensor rooms that both exist in the model and have a live reading: (centre, lux).
    val sensors = ROOM_TO_PRESENCE.mapNotNull { (room, key) ->
        val center = presets.rooms[room]?.target ?: return@mapNotNull null
        val lux = illuminance[key] ?: return@mapNotNull null
        center to lux
    }
    if (sensors.isEmpty()) return emptyList()
    return presets.rooms.mapNotNull { (room, preset) ->
        val group = anchorGroup(room)
        if (group !in mode.groups) return@mapNotNull null
        val center = preset.target
        val lux = ROOM_TO_PRESENCE[room]?.let { illuminance[it] }
            ?: sensors.minByOrNull { (it.first - center).length() }?.second
            ?: return@mapNotNull null
        val level = luxToGlowLevel(lux)
        if (level <= 0f) return@mapNotNull null
        // Follow the exploded floor tier so the glow stays inside its room.
        LitLight(Vec3(center.x, center.y + groupTier(group) * explode, center.z), level)
    }
}

/** Per-room orbit presets + all light anchors, parsed from `house-cameras.json`. */
class CameraPresets(
    val rooms: Map<String, OrbitPreset>,
    val lights: List<LightAnchor>,
)

/** Vertical explode tier per model group. */
fun groupTier(group: HouseGroup): Float = when (group) {
    HouseGroup.Kellari -> 0f
    HouseGroup.Krs1, HouseGroup.Terassi, HouseGroup.Katos -> 1f
    HouseGroup.Krs2 -> 2f
    HouseGroup.Katto -> 3f
}

/**
 * The floor filters the overlay's segmented control offers. Group membership
 * matches `house-cameras.json`'s `floors` block, so these map 1:1 onto the
 * home-automation floor naming.
 */
enum class FloorMode(val label: String, val groups: Set<HouseGroup>) {
    All("Koko talo", HouseGroup.entries.toSet()),
    Kellari("Kellari", setOf(HouseGroup.Kellari)),
    // A single floor frames only its building level — the terrace/lawn (Terassi)
    // and the detached carport (Katos) belong to the whole-house overview, not the
    // floor cutaway, so they don't drag the framing out to the yard.
    Alakerta("Alakerta", setOf(HouseGroup.Krs1)),
    Ylakerta("Yläkerta", setOf(HouseGroup.Krs2)),
}

/** Maps a voice-command floor token to a [FloorMode] (null when unknown). */
fun floorModeFromToken(token: String?): FloorMode? = when (token) {
    "all" -> FloorMode.All
    "kellari" -> FloorMode.Kellari
    "alakerta" -> FloorMode.Alakerta
    "ylakerta" -> FloorMode.Ylakerta
    else -> null
}

private fun vec(a: kotlinx.serialization.json.JsonElement): Vec3 {
    val arr = a.jsonArray
    return Vec3(arr[0].jsonPrimitive.float, arr[1].jsonPrimitive.float, arr[2].jsonPrimitive.float)
}

fun parseCameras(jsonStr: String): CameraPresets {
    val root = Json.parseToJsonElement(jsonStr).jsonObject
    val rooms = buildMap {
        root["rooms"]?.jsonObject?.forEach { (name, el) ->
            val orbit = el.jsonObject["orbit"]?.jsonObject ?: return@forEach
            put(
                name,
                OrbitPreset(
                    target = vec(orbit["target"]!!),
                    radius = orbit["radius"]!!.jsonPrimitive.float,
                    phi = orbit["phi"]!!.jsonPrimitive.float,
                ),
            )
        }
    }
    val lights = buildList {
        root["lights"]?.jsonObject?.forEach { (name, el) ->
            add(LightAnchor(name, vec(el.jsonObject["position"]!!)))
        }
    }
    return CameraPresets(rooms, lights)
}

/**
 * Whether a triangle is visible under the current floor/roof/walls settings.
 * Shared by the software and Filament geometry renderers.
 */
fun triVisible(
    group: HouseGroup,
    matClass: MatClass,
    mode: FloorMode,
    showRoof: Boolean,
    showWalls: Boolean,
    showFurniture: Boolean = true,
    showHeating: Boolean = false,
): Boolean {
    // Light-fixture meshes are never drawn as geometry — the on/off state is shown
    // by the animated ring overlay instead (the software parser drops them; the
    // Filament path relies on this rule).
    if (matClass == MatClass.Fixture) return false
    // Floor-heating loop overlays are only drawn in the "Lämmitys" mode (they'd
    // otherwise mask the oak floor); then only on the shown floor.
    if (matClass == MatClass.Heating) return showHeating && group in mode.groups
    // "Kalusteet" off hides the movable furnishings/decor.
    if (!showFurniture && matClass == MatClass.Furniture) return false
    if (group !in mode.groups) return false
    // Roof handling: a single-floor view is always a cutaway, so every roof-like
    // surface (the Katto group + any Roof-material tri, e.g. wing/carport roofs in
    // other groups) is hidden regardless of the toggle. In the whole-house view the
    // Katto/Roof toggle applies.
    if ((mode != FloorMode.All || !showRoof) && (group == HouseGroup.Katto || matClass == MatClass.Roof)) return false
    // Dollhouse ("Seinät" off): drop all walls — exterior and interior — plus the
    // doors and windows they held, so nothing floats once its wall is gone.
    if (!showWalls && (
            matClass == MatClass.ExteriorWall ||
                matClass == MatClass.InteriorWall ||
                matClass == MatClass.Glass ||
                matClass == MatClass.Door
            )
    ) {
        return false
    }
    return true
}

/**
 * Underfloor-heating circuit number (`Heat_<floor>_<nn>`) → the PLC heating-demand
 * key that regulates it. From the LVI Lattialämmitys loop table; the keittiö PT100
 * regulates the whole open-plan wing (41–44). Circuits with no matching PLC key yet
 * (31 KPH+S, 32 KHH, 55 kylpyhuone) stay neutral until their key is known.
 */
val CIRCUIT_TO_HEATING_KEY: Map<String, String> = mapOf(
    "11" to "kellari", "12" to "kellari",
    "21" to "kellari", "22" to "kellari", "23" to "kellari", "24" to "kellari",
    "33" to "eteinen",
    "34" to "mh_ak",
    "41" to "keittio", "42" to "keittio", "43" to "keittio", "44" to "keittio",
    "51" to "essi", "52" to "onni", "53" to "yk_aula", "54" to "aatu",
)

/** Loops with no PLC thermostat — manually controlled and generally on (drawn hot). */
private val ALWAYS_ON_CIRCUITS = setOf("31", "32", "55")

/**
 * circuit "nn" → 0..1 heat intensity for lerping each loop's colour cold→hot.
 * PLC-regulated loops use their live demand percentage; the manual bathroom/utility
 * loops (31/32/55) read as always on. Loops with no data at all are omitted (drawn
 * neutral). [demand] is (heating key → percent 0..100).
 */
fun heatIntensityByCircuit(demand: Map<String, Int>): Map<String, Float> = buildMap {
    CIRCUIT_TO_HEATING_KEY.forEach { (circuit, key) ->
        demand[key]?.let { put(circuit, it.coerceIn(0, 100) / 100f) }
    }
    ALWAYS_ON_CIRCUITS.forEach { put(it, 1f) }
}

/**
 * Frames the currently-visible geometry: target = bbox centre, radius scaled
 * from its horizontal extent (README recipe), keeping the caller's current
 * orbit yaw in [HouseView3d] so the move feels continuous.
 */
fun frameVisible(
    model: HouseModel,
    mode: FloorMode,
    showRoof: Boolean,
    showWalls: Boolean,
    embedded: Boolean = false,
): OrbitPreset {
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    var any = false
    for (t in 0 until model.triCount) {
        val mc = MatClass.entries[model.matClass[t]]
        if (!triVisible(HouseGroup.entries[model.group[t]], mc, mode, showRoof, showWalls)) continue
        // The roof/eave overhang extends past the walls; framing to it would zoom the
        // camera out whenever the roof is shown. Frame to the footprint (walls/floors).
        if (mc == MatClass.Roof) continue
        any = true
        val base = t * 9
        for (v in 0 until 3) {
            val x = model.verts[base + v * 3]
            val y = model.verts[base + v * 3 + 1]
            val z = model.verts[base + v * 3 + 2]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
        }
    }
    // A single floor is viewed near-top-down (upright camera) so inner walls don't
    // hide the room contents; the whole house keeps the lower orbit angle.
    val phi = if (mode == FloorMode.All) 0.9f else 0.32f
    // Yaw: the whole house sits at its terrace-corner view; a single floor squares up
    // to the street-up floorplan on the phone. The wide landscape KIOSK rotates the
    // floorplan 90° so the building's long (X) axis fills the width and the entrance
    // (+Z end) sits at the bottom, instead of a tall shape wasting the wide screen.
    val theta = when {
        mode == FloorMode.All -> WHOLE_HOUSE_THETA
        embedded -> FLOOR_VIEW_THETA + HALF_PI
        else -> FLOOR_VIEW_THETA
    }
    // Framing tightness (radius = extent * factor + pad). The kiosk fills the wide
    // screen; a single floor on the phone frames close so the plan fills the portrait
    // height instead of floating in empty space. Whole-house keeps a comfortable margin.
    val factor = when {
        embedded && mode == FloorMode.All -> 1.1f
        embedded -> 0.85f              // kiosk floor (long axis laid along the width)
        mode == FloorMode.All -> 1.35f // phone whole-house
        else -> 1.0f                   // phone single floor — tighter
    }
    val pad = when {
        // Phone floor: the radius is sized from the XZ footprint only; a little margin
        // leaves headroom for the outer walls' height (the tilted view projects it up
        // on screen). The vertical position is handled by FLOOR_NEAR_FAR_BIAS below, so
        // this only needs enough slack to keep the walls off the edge — too much left
        // an empty band at the bottom.
        !embedded && mode != FloorMode.All -> 2.4f
        embedded -> 1f
        else -> 2f
    }
    if (!any) return OrbitPreset(model.center, max(model.size.x, model.size.z) * factor + pad, phi, theta)
    // The phone floor view is tilted (theta=0, phi=0.32): the near (+X) side looms
    // large in perspective. Biasing the look-point along X slides the plan up/down
    // the screen (X is the screen-vertical axis here). Now that the vertical target
    // sits at the real floor height (see cy below), no artificial lift is needed —
    // the plan centres on its own; the constant is kept as a fine-tune knob.
    val phoneFloor = !embedded && mode != FloorMode.All
    val cx = (minX + maxX) / 2f + if (phoneFloor) (maxX - minX) * FLOOR_NEAR_FAR_BIAS else 0f
    // Some floor groups' wall geometry spans multiple storeys (tall exterior walls,
    // the stairwell void), so the raw bbox Y centre drifts toward mid-building — the
    // reason the basement and upstairs framed at the wrong height while only the
    // ground floor looked right. For a single floor, take the vertical centre from
    // its room slabs (cleanly per-storey) lifted to mid-room; the whole-house view
    // keeps the bbox centre. Falls back to the bbox if the floor has no room patch.
    val slabYs = if (mode != FloorMode.All) {
        model.rooms.asSequence().filter { it.group in mode.groups }.map { it.center.y }.toList()
    } else {
        emptyList()
    }
    val cy = if (slabYs.isNotEmpty()) slabYs.average().toFloat() + FLOOR_TARGET_LIFT else (minY + maxY) / 2f
    val center = Vec3(cx, cy, (minZ + maxZ) / 2f)
    val radius = max(maxX - minX, maxZ - minZ) * factor + pad
    return OrbitPreset(center, radius, phi, theta)
}

/** Terrace-corner yaw for the whole-house view; street-up yaw for a floorplan. */
const val WHOLE_HOUSE_THETA = 0.85f
const val FLOOR_VIEW_THETA = 0f
private const val HALF_PI = 1.5707964f

/** Vertical explode tier the camera should follow for the active focus. */
fun cameraExplodeTier(mode: FloorMode, showRoof: Boolean, selectedGroup: HouseGroup?): Float =
    selectedGroup?.let(::groupTier) ?: when (mode) {
        FloorMode.Kellari -> 0f
        FloorMode.Alakerta -> 1f
        FloorMode.Ylakerta -> 2f
        // Without the roof, the visible basement/ground/upstairs bounds move
        // around tier 1. With the tier-3 roof included their midpoint is 1.5.
        FloorMode.All -> if (showRoof) 1.5f else 1f
    }
