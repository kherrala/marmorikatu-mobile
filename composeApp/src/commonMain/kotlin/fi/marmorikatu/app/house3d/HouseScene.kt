package fi.marmorikatu.app.house3d

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max

/** A named light fixture anchor in world space (from `house-cameras.json`). */
class LightAnchor(val name: String, val pos: Vec3)

/** An orbit-camera preset: where to look, how far, and the polar angle. */
data class OrbitPreset(val target: Vec3, val radius: Float, val phi: Float)

/** Keeps small-room presets from cropping away all surrounding floor context. */
fun comfortableRoomFocus(preset: OrbitPreset): OrbitPreset =
    if (preset.radius >= MIN_ROOM_FOCUS_RADIUS) preset else preset.copy(radius = MIN_ROOM_FOCUS_RADIUS)

private const val MIN_ROOM_FOCUS_RADIUS = 8.5f

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
    Alakerta("Alakerta", setOf(HouseGroup.Krs1, HouseGroup.Terassi, HouseGroup.Katos)),
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
): Boolean {
    // Light-fixture meshes are never drawn as geometry — the on/off state is shown
    // by the animated ring overlay instead (the software parser drops them; the
    // Filament path relies on this rule).
    if (matClass == MatClass.Fixture) return false
    // "Kalusteet" off hides the movable furnishings/decor.
    if (!showFurniture && matClass == MatClass.Furniture) return false
    if (group !in mode.groups) return false
    // Roof off hides the main roof group AND every roof-material surface (carport
    // + wing roofs live in other groups), so nothing roof-like blocks the view.
    if (!showRoof && (group == HouseGroup.Katto || matClass == MatClass.Roof)) return false
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
 * Frames the currently-visible geometry: target = bbox centre, radius scaled
 * from its horizontal extent (README recipe), keeping the caller's current
 * orbit yaw in [HouseView3d] so the move feels continuous.
 */
fun frameVisible(
    model: HouseModel,
    mode: FloorMode,
    showRoof: Boolean,
    showWalls: Boolean,
): OrbitPreset {
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    var any = false
    for (t in 0 until model.triCount) {
        if (!triVisible(HouseGroup.entries[model.group[t]], MatClass.entries[model.matClass[t]], mode, showRoof, showWalls)) continue
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
    if (!any) return OrbitPreset(model.center, max(model.size.x, model.size.z) * 1.4f, phi)
    val center = Vec3((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f)
    val radius = max(maxX - minX, maxZ - minZ) * 1.35f + 2f
    return OrbitPreset(center, radius, phi)
}

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
