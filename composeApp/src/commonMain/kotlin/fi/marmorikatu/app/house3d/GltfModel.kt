package fi.marmorikatu.app.house3d

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Which of the six top-level groups a triangle belongs to. The order matches
 * `Talo`'s children in the GLB and drives per-floor visibility.
 */
enum class HouseGroup { Kellari, Krs1, Krs2, Terassi, Katos, Katto }

/** How a triangle should be shaded/toggled, derived from its glTF material name. */
enum class MatClass { Solid, ExteriorWall, InteriorWall, Glass, Roof, Door, Fixture, Furniture, Heating }

/** A tappable room patch — the flat `Room_*` mesh sitting just above a floor. */
class RoomPatch(
    val name: String,
    val group: HouseGroup,
    val center: Vec3,
    /** 9 floats per triangle (v0,v1,v2), world space — used for screen hit-testing. */
    val tris: FloatArray,
)

/** One image embedded in the GLB and referenced by a material base-color texture. */
class HouseTexture(
    val name: String?,
    val mimeType: String?,
    val bytes: ByteArray,
)

/**
 * The parsed house as a flat triangle soup ready for the software renderer.
 * Geometry is stored in world space (the GLB bakes identity node transforms, so
 * no per-node matrices are needed). `Room_*` patches and `LightOff` fixture
 * meshes are kept out of the soup — rooms are drawn as highlights, fixtures are
 * replaced by glow sprites at the anchor positions from `house-cameras.json`.
 */
class HouseModel(
    val triCount: Int,
    /** 9 floats per triangle: v0(xyz) v1(xyz) v2(xyz). */
    val verts: FloatArray,
    /** Packed 0xRRGGBB base color per triangle. */
    val rgb: IntArray,
    /** Six floats per triangle: glTF TEXCOORD_0 for v0, v1 and v2. */
    val uv: FloatArray,
    /** Index into [textures], or -1 when the triangle has no usable base-color texture. */
    val texture: IntArray,
    val group: IntArray,
    val matClass: IntArray,
    val textures: List<HouseTexture>,
    val rooms: List<RoomPatch>,
    val center: Vec3,
    val size: Vec3,
)

private const val GLB_MAGIC = 0x46546C67 // "glTF"
private const val CHUNK_JSON = 0x4E4F534A // "JSON"
private const val CHUNK_BIN = 0x004E4942 // "BIN\0"

private fun u32le(b: ByteArray, o: Int): Long =
    (b[o].toLong() and 0xFF) or
        ((b[o + 1].toLong() and 0xFF) shl 8) or
        ((b[o + 2].toLong() and 0xFF) shl 16) or
        ((b[o + 3].toLong() and 0xFF) shl 24)

private fun u16le(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

private fun indexAt(b: ByteArray, offset: Int, componentType: Int): Int = when (componentType) {
    5121 -> b[offset].toInt() and 0xFF // UNSIGNED_BYTE
    5123 -> u16le(b, offset) // UNSIGNED_SHORT
    5125 -> u32le(b, offset).toInt() // UNSIGNED_INT
    else -> error("unsupported glTF index component type $componentType")
}

private fun componentBytes(componentType: Int): Int = when (componentType) {
    5121 -> 1
    5123 -> 2
    5125, 5126 -> 4
    else -> error("unsupported glTF component type $componentType")
}

private fun f32le(b: ByteArray, o: Int): Float =
    Float.fromBits(u32le(b, o).toInt())

/**
 * Maps a glTF material name to its render class — shared by the software parser
 * and the Android Filament renderer (which reads material-instance names), so the
 * floor/wall/roof visibility rules stay identical across both.
 */
fun matClassForMaterial(matName: String?): MatClass = when (matName) {
    "WallExt", "WallExt2" -> MatClass.ExteriorWall
    "WallInt", "ConcreteW" -> MatClass.InteriorWall
    "Glass" -> MatClass.Glass
    "Roof", "Canopy" -> MatClass.Roof // Canopy = terrace shade roof; hides with roof
    // Door leaves AND window/door frames (spec `Frame`): both belong to the wall
    // openings, so they hide with the walls instead of floating once the wall is gone.
    "Door", "Frame" -> MatClass.Door
    "LightOff" -> MatClass.Fixture
    // Underfloor-heating overlays (`Heat_*`): the solid zone patch (`HeatOff`) and
    // the serpentine loop (`HeatPipe`). Hidden by default (see triVisible) — they
    // otherwise cover the oak floor; "Lämmitys" mode reveals + colours them.
    "HeatOff", "HeatPipe" -> MatClass.Heating
    // Movable furnishings/decor — hidden by the "Kalusteet" toggle. Structural
    // items (floors, railings, stairs, walls) stay so the shell reads clearly.
    "WoodFurn", "SofaWhite", "SofaGreen", "FabricBlue", "Cabinet", "BedWhite",
    "Counter", "Appliance", "TVBlack", "Ceramic", "Rattan", "DarkWood", "Rug",
    "Pot", "Plant", "SaunaWood",
    -> MatClass.Furniture
    else -> MatClass.Solid
}

/**
 * Parses a binary glTF (`.glb`) into a [HouseModel]. Only the attributes we
 * shade are decoded (POSITION, TEXCOORD_0, indices, material color and embedded
 * base-color images); normals are recomputed per face at render time. Accessor
 * byte offsets/strides and all legal unsigned index widths are honoured so a
 * harmless Blender exporter layout change cannot silently corrupt the model.
 */
fun parseGlb(bytes: ByteArray): HouseModel {
    require(u32le(bytes, 0).toInt() == GLB_MAGIC) { "not a glb" }
    // chunk 0 — JSON
    val jsonLen = u32le(bytes, 12).toInt()
    require(u32le(bytes, 16).toInt() == CHUNK_JSON) { "expected JSON chunk" }
    val jsonStart = 20
    val jsonStr = bytes.decodeToString(jsonStart, jsonStart + jsonLen)
    // chunk 1 — BIN
    val binChunkHeader = jsonStart + jsonLen
    require(u32le(bytes, binChunkHeader + 4).toInt() == CHUNK_BIN) { "expected BIN chunk" }
    val binStart = binChunkHeader + 8

    val root = Json.parseToJsonElement(jsonStr).jsonObject
    val accessors = root["accessors"]!!.jsonArray
    val bufferViews = root["bufferViews"]!!.jsonArray
    val meshes = root["meshes"]!!.jsonArray
    val materials = root["materials"]?.jsonArray ?: JsonArray(emptyList())
    val images = root["images"]?.jsonArray ?: JsonArray(emptyList())
    val gltfTextures = root["textures"]?.jsonArray ?: JsonArray(emptyList())
    val nodes = root["nodes"]!!.jsonArray
    val scene = root["scenes"]!!.jsonArray[root["scene"]?.jsonPrimitive?.int ?: 0].jsonObject

    fun obj(a: JsonArray, i: Int) = a[i].jsonObject
    fun JsonObject.intOr(k: String, d: Int) = this[k]?.jsonPrimitive?.int ?: d
    fun accBase(accessorIdx: Int): Int {
        val acc = obj(accessors, accessorIdx)
        val bv = obj(bufferViews, acc.intOr("bufferView", 0))
        return binStart + bv.intOr("byteOffset", 0) + acc.intOr("byteOffset", 0)
    }
    fun accStride(accessorIdx: Int, packedStride: Int): Int {
        val acc = obj(accessors, accessorIdx)
        val bv = obj(bufferViews, acc.intOr("bufferView", 0))
        return bv.intOr("byteStride", packedStride)
    }

    // Keep image indices stable while compacting away URI-only images. A GLB
    // should embed these, but gracefully falling back to a flat color is safer
    // than trying to resolve a filesystem URI on iOS.
    val sourceToTexture = IntArray(images.size) { -1 }
    val embeddedTextures = ArrayList<HouseTexture>(images.size)
    for (i in 0 until images.size) {
        val image = obj(images, i)
        val viewIndex = image["bufferView"]?.jsonPrimitive?.int ?: continue
        val view = obj(bufferViews, viewIndex)
        val start = binStart + view.intOr("byteOffset", 0)
        val length = view.intOr("byteLength", 0)
        if (start < binStart || length <= 0 || start + length > bytes.size) continue
        sourceToTexture[i] = embeddedTextures.size
        embeddedTextures.add(
            HouseTexture(
                name = image["name"]?.jsonPrimitive?.contentOrNull,
                mimeType = image["mimeType"]?.jsonPrimitive?.contentOrNull,
                bytes = bytes.copyOfRange(start, start + length),
            ),
        )
    }

    // Material base color (RGB), class and embedded base-color image.
    val matRgb = IntArray(materials.size) { 0xFFFFFF } // glTF default
    val matCls = Array(materials.size) { MatClass.Solid }
    val matTexture = IntArray(materials.size) { -1 }
    for (i in 0 until materials.size) {
        val m = obj(materials, i)
        matCls[i] = matClassForMaterial(m["name"]?.jsonPrimitive?.contentOrNull)
        val pbr = m["pbrMetallicRoughness"]?.jsonObject
        val bcf = pbr?.get("baseColorFactor")?.jsonArray
        if (bcf != null) {
            val r = (bcf[0].jsonPrimitive.float.coerceIn(0f, 1f) * 255f).toInt()
            val g = (bcf[1].jsonPrimitive.float.coerceIn(0f, 1f) * 255f).toInt()
            val b = (bcf[2].jsonPrimitive.float.coerceIn(0f, 1f) * 255f).toInt()
            matRgb[i] = (r shl 16) or (g shl 8) or b
        }
        val textureInfo = pbr?.get("baseColorTexture")?.jsonObject
        // TEXCOORD_0 is the default and the only set emitted by this model.
        if (textureInfo != null && textureInfo.intOr("texCoord", 0) == 0) {
            val textureIndex = textureInfo["index"]?.jsonPrimitive?.int
            val sourceIndex = textureIndex?.let { index ->
                gltfTextures.getOrNull(index)?.jsonObject?.get("source")?.jsonPrimitive?.int
            }
            if (sourceIndex != null && sourceIndex in sourceToTexture.indices) {
                matTexture[i] = sourceToTexture[sourceIndex]
            }
        }
    }

    // Collect world-space triangles of one mesh primitive into the given sinks.
    fun readTris(prim: JsonObject, sink: (FloatArray, FloatArray?) -> Unit) {
        require(prim.intOr("mode", 4) == 4) { "only glTF TRIANGLES primitives are supported" }
        val posAcc = prim["attributes"]!!.jsonObject["POSITION"]!!.jsonPrimitive.int
        val posBase = accBase(posAcc)
        val posAccessor = obj(accessors, posAcc)
        require(posAccessor.intOr("componentType", 0) == 5126) { "POSITION must use FLOAT" }
        val posStride = accStride(posAcc, 12)
        val uvAcc = prim["attributes"]!!.jsonObject["TEXCOORD_0"]?.jsonPrimitive?.int
        val uvBase = uvAcc?.let(::accBase)
        val uvStride = uvAcc?.let { accStride(it, 8) } ?: 0
        if (uvAcc != null) {
            require(obj(accessors, uvAcc).intOr("componentType", 0) == 5126) { "TEXCOORD_0 must use FLOAT" }
        }
        val idxAcc = prim["indices"]!!.jsonPrimitive.int
        val idxBase = accBase(idxAcc)
        val idxAccessor = obj(accessors, idxAcc)
        val idxCount = idxAccessor.intOr("count", 0)
        val idxComponentType = idxAccessor.intOr("componentType", 5123)
        val idxStride = accStride(idxAcc, componentBytes(idxComponentType))
        val out = FloatArray(idxCount * 3)
        val outUv = if (uvBase != null) FloatArray(idxCount * 2) else null
        var w = 0
        var uw = 0
        for (t in 0 until idxCount) {
            val vi = indexAt(bytes, idxBase + t * idxStride, idxComponentType)
            val p = posBase + vi * posStride
            out[w++] = f32le(bytes, p)
            out[w++] = f32le(bytes, p + 4)
            out[w++] = f32le(bytes, p + 8)
            if (uvBase != null && outUv != null) {
                val uv = uvBase + vi * uvStride
                outUv[uw++] = f32le(bytes, uv)
                outUv[uw++] = f32le(bytes, uv + 4)
            }
        }
        sink(out, outUv)
    }

    // ---- Walk the node tree, tagging each mesh with its top-level group ----
    val groupNames = HouseGroup.entries.associateBy { it.name }
    // Soup accumulators (grow as lists, compacted at the end).
    val vertsList = ArrayList<Float>(1 shl 18)
    val rgbList = ArrayList<Int>(1 shl 15)
    val uvList = ArrayList<Float>(1 shl 17)
    val textureList = ArrayList<Int>(1 shl 15)
    val groupList = ArrayList<Int>(1 shl 15)
    val classList = ArrayList<Int>(1 shl 15)
    val rooms = ArrayList<RoomPatch>()

    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

    fun visit(nodeIdx: Int, group: HouseGroup?) {
        val node = obj(nodes, nodeIdx)
        val nodeName = node["name"]?.jsonPrimitive?.contentOrNull
        // Root child of Talo names the group; propagate downward.
        val g = group ?: nodeName?.let { groupNames[it] }
        val meshIdx = node["mesh"]?.jsonPrimitive?.int
        if (meshIdx != null && g != null) {
            val prims = obj(meshes, meshIdx)["primitives"]!!.jsonArray
            val isRoom = nodeName?.startsWith("Room_") == true
            if (isRoom) {
                // Keep room patches separate for highlight + hit-testing.
                val tri = ArrayList<Float>()
                var cx = 0f; var cy = 0f; var cz = 0f; var n = 0
                for (pi in 0 until prims.size) {
                    readTris(obj(prims, pi)) { arr, _ -> tri.addAll(arr.asList()) }
                }
                var i = 0
                while (i < tri.size) { cx += tri[i]; cy += tri[i + 1]; cz += tri[i + 2]; n++; i += 3 }
                if (n > 0) {
                    rooms.add(
                        RoomPatch(nodeName, g, Vec3(cx / n, cy / n, cz / n), tri.toFloatArray()),
                    )
                }
            } else {
                for (pi in 0 until prims.size) {
                    val prim = obj(prims, pi)
                    val matIdx = prim["material"]?.jsonPrimitive?.int
                    val matName = matIdx?.let { obj(materials, it)["name"]?.jsonPrimitive?.contentOrNull }
                    // Fixtures (their off material) are drawn as glow sprites, not geometry.
                    if (matName == "LightOff") continue
                    val cls = matIdx?.let { matCls[it] } ?: MatClass.Solid
                    val rgb = matIdx?.let { matRgb[it] } ?: 0x9AA6B2
                    val texture = matIdx?.let { matTexture[it] } ?: -1
                    readTris(prim) { arr, uv ->
                        var i = 0
                        var uvOffset = 0
                        while (i < arr.size) {
                            for (k in 0 until 9) vertsList.add(arr[i + k])
                            // bounds from the three verts
                            for (v in 0 until 3) {
                                val x = arr[i + v * 3]; val y = arr[i + v * 3 + 1]; val z = arr[i + v * 3 + 2]
                                if (x < minX) minX = x; if (x > maxX) maxX = x
                                if (y < minY) minY = y; if (y > maxY) maxY = y
                                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                            }
                            rgbList.add(rgb)
                            for (k in 0 until 6) uvList.add(uv?.get(uvOffset + k) ?: 0f)
                            textureList.add(if (uv != null) texture else -1)
                            groupList.add(g.ordinal)
                            classList.add(cls.ordinal)
                            i += 9
                            uvOffset += 6
                        }
                    }
                }
            }
        }
        node["children"]?.jsonArray?.forEach { visit(it.jsonPrimitive.int, g) }
    }
    scene["nodes"]!!.jsonArray.forEach { visit(it.jsonPrimitive.int, null) }

    val triCount = rgbList.size
    val verts = FloatArray(vertsList.size).also { for (i in it.indices) it[i] = vertsList[i] }
    val rgb = IntArray(triCount) { rgbList[it] }
    val uv = FloatArray(uvList.size).also { for (i in it.indices) it[i] = uvList[i] }
    val texture = IntArray(triCount) { textureList[it] }
    val group = IntArray(triCount) { groupList[it] }
    val cls = IntArray(triCount) { classList[it] }
    val center = Vec3((minX + maxX) / 2f, (minY + maxY) / 2f, (minZ + maxZ) / 2f)
    val size = Vec3(maxX - minX, maxY - minY, maxZ - minZ)
    return HouseModel(triCount, verts, rgb, uv, texture, group, cls, embeddedTextures, rooms, center, size)
}
