package fi.marmorikatu.app.house3d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Fixed key light for flat shading — slightly high and to the side. */
private val LIGHT_DIR = Vec3(0.35f, 0.9f, 0.25f).normalized()
private const val AMBIENT = 0.42f
private const val DIFFUSE = 0.58f
private val FOV = (48.0 * PI / 180.0).toFloat()

/** Floor group a light/marker anchor belongs to, inferred from its name token. */
fun anchorGroup(name: String): HouseGroup = when {
    name.contains("_2krs_") -> HouseGroup.Krs2
    name == "Light_ulko_parveke" -> HouseGroup.Krs2
    name.contains("_kellari_") -> HouseGroup.Kellari
    name.contains("_katos") -> HouseGroup.Katos
    else -> HouseGroup.Krs1
}

private const val CLIP_LEFT = 1
private const val CLIP_RIGHT = 1 shl 1
private const val CLIP_BOTTOM = 1 shl 2
private const val CLIP_TOP = 1 shl 3
private const val CLIP_NEAR = 1 shl 4
private const val CLIP_FAR = 1 shl 5
private const val CLIP_PLANE_COUNT = 6
private const val MAX_CLIPPED_VERTICES = 12
private const val CLIP_VERTEX_COMPONENTS = 6 // clip xyzw + texture uv
private const val TEXTURE_BATCH_VERTICES = 1536 // 512 projected triangles
private const val COLOR_BATCH_VERTICES = 3072 // 1024 projected triangles

private fun clipCode(x: Float, y: Float, z: Float, w: Float): Int {
    var code = 0
    if (x < -w) code = code or CLIP_LEFT
    if (x > w) code = code or CLIP_RIGHT
    if (y < -w) code = code or CLIP_BOTTOM
    if (y > w) code = code or CLIP_TOP
    if (z < -w) code = code or CLIP_NEAR
    if (z > w) code = code or CLIP_FAR
    return code
}

private fun projectClip(mvp: FloatArray, x: Float, y: Float, z: Float, w: Float, h: Float, out: FloatArray): Boolean {
    transformPoint(mvp, x, y, z, out)
    if (!out[0].isFinite() || !out[1].isFinite() || !out[2].isFinite() || !out[3].isFinite()) return false
    val cw = out[3]
    if (cw <= 1e-4f || clipCode(out[0], out[1], out[2], cw) != 0) return false
    out[0] = (out[0] / cw * 0.5f + 0.5f) * w
    out[1] = (1f - (out[1] / cw * 0.5f + 0.5f)) * h
    return true
}

/** Reusable homogeneous-clipping buffers for the hot triangle projection loop. */
internal class ProjectedTriangle {
    internal val clipA = FloatArray(MAX_CLIPPED_VERTICES * CLIP_VERTEX_COMPONENTS)
    internal val clipB = FloatArray(MAX_CLIPPED_VERTICES * CLIP_VERTEX_COMPONENTS)
    internal val x = FloatArray(MAX_CLIPPED_VERTICES)
    internal val y = FloatArray(MAX_CLIPPED_VERTICES)
    internal val u = FloatArray(MAX_CLIPPED_VERTICES)
    internal val v = FloatArray(MAX_CLIPPED_VERTICES)
    internal val cameraDepth = FloatArray(MAX_CLIPPED_VERTICES)
}

private fun planeDistance(v: FloatArray, offset: Int, plane: Int): Float {
    val x = v[offset]
    val y = v[offset + 1]
    val z = v[offset + 2]
    val w = v[offset + 3]
    return when (plane) {
        0 -> x + w
        1 -> w - x
        2 -> y + w
        3 -> w - y
        4 -> z + w
        else -> w - z
    }
}

private fun clipAgainstPlane(src: FloatArray, srcCount: Int, dst: FloatArray, plane: Int): Int {
    var outCount = 0
    var previous = (srcCount - 1) * CLIP_VERTEX_COMPONENTS
    var previousDistance = planeDistance(src, previous, plane)
    var previousInside = previousDistance >= 0f
    for (vertex in 0 until srcCount) {
        val current = vertex * CLIP_VERTEX_COMPONENTS
        val currentDistance = planeDistance(src, current, plane)
        val currentInside = currentDistance >= 0f
        if (currentInside != previousInside) {
            val t = previousDistance / (previousDistance - currentDistance)
            val output = outCount++ * CLIP_VERTEX_COMPONENTS
            for (component in 0 until CLIP_VERTEX_COMPONENTS) {
                dst[output + component] = src[previous + component] +
                    (src[current + component] - src[previous + component]) * t
            }
        }
        if (currentInside) {
            val output = outCount++ * CLIP_VERTEX_COMPONENTS
            for (component in 0 until CLIP_VERTEX_COMPONENTS) dst[output + component] = src[current + component]
        }
        previous = current
        previousDistance = currentDistance
        previousInside = currentInside
    }
    return outCount
}

/**
 * Projects and clips one packed triangle. The returned vertices form a convex
 * screen-space polygon in [ProjectedTriangle.x]/[ProjectedTriangle.y].
 */
internal fun projectTriangle(
    mvp: FloatArray,
    vertices: FloatArray,
    base: Int,
    yOffset: Float,
    width: Float,
    height: Float,
    projected: ProjectedTriangle,
    textureCoordinates: FloatArray? = null,
    textureBase: Int = 0,
): Int {
    var codeOr = 0
    var codeAnd = (1 shl CLIP_PLANE_COUNT) - 1
    for (vertex in 0 until 3) {
        val input = base + vertex * 3
        val output = vertex * CLIP_VERTEX_COMPONENTS
        transformPoint(mvp, vertices[input], vertices[input + 1] + yOffset, vertices[input + 2], projected.clipA, output)
        projected.clipA[output + 4] = textureCoordinates?.get(textureBase + vertex * 2) ?: 0f
        projected.clipA[output + 5] = textureCoordinates?.get(textureBase + vertex * 2 + 1) ?: 0f
        val x = projected.clipA[output]
        val y = projected.clipA[output + 1]
        val z = projected.clipA[output + 2]
        val w = projected.clipA[output + 3]
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !w.isFinite()) return 0
        val code = clipCode(x, y, z, w)
        codeOr = codeOr or code
        codeAnd = codeAnd and code
    }
    if (codeAnd != 0) return 0

    var source = projected.clipA
    var count = 3
    if (codeOr != 0) {
        var destination = projected.clipB
        for (plane in 0 until CLIP_PLANE_COUNT) {
            count = clipAgainstPlane(source, count, destination, plane)
            if (count == 0) return 0
            val swap = source
            source = destination
            destination = swap
        }
    }

    for (vertex in 0 until count) {
        val input = vertex * CLIP_VERTEX_COMPONENTS
        val clipW = source[input + 3]
        if (clipW <= 1e-4f) return 0
        projected.x[vertex] = (source[input] / clipW * 0.5f + 0.5f) * width
        projected.y[vertex] = (1f - (source[input + 1] / clipW * 0.5f + 0.5f)) * height
        projected.u[vertex] = source[input + 4]
        projected.v[vertex] = source[input + 5]
        projected.cameraDepth[vertex] = clipW
    }
    return count
}

private fun Path.append(projected: ProjectedTriangle, count: Int) {
    moveTo(projected.x[0], projected.y[0])
    for (vertex in 1 until count) lineTo(projected.x[vertex], projected.y[vertex])
    close()
}

/** Reusable camera matrices and point storage for allocation-free projection. */
internal class CameraProjector {
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    internal val matrix = FloatArray(16)
    private val point = FloatArray(4)
    private var width = 0f
    private var height = 0f

    internal fun update(eye: Vec3, target: Vec3, width: Float, height: Float): Boolean {
        val dx = target.x - eye.x
        val dy = target.y - eye.y
        val dz = target.z - eye.z
        if (width < 1f || height < 1f || dx * dx + dy * dy + dz * dz < 1e-12f) return false
        this.width = width
        this.height = height
        Mat4.perspective(FOV, width / height, 0.1f, 500f, projection)
        Mat4.lookAt(eye, target, Vec3.UP, view)
        Mat4.multiply(projection, view, matrix)
        return true
    }

    internal fun project(x: Float, y: Float, z: Float): Offset? =
        if (projectClip(matrix, x, y, z, width, height, point)) Offset(point[0], point[1]) else null
}

/** Projects a world point to a screen [Offset] with the orbit camera, or null. */
fun projectWorld(eye: Vec3, target: Vec3, w: Float, h: Float, p: Vec3): Offset? {
    val projector = CameraProjector()
    return if (projector.update(eye, target, w, h)) projector.project(p.x, p.y, p.z) else null
}

/**
 * Frame-persistent data for the software renderer. Besides eliminating large
 * per-frame allocations, this precomputes all camera-independent face data.
 */
internal class SoftwareRenderCache(internal val model: HouseModel) {
    internal val order = IntArray(model.triCount)
    internal val depth = FloatArray(model.triCount)
    internal val centers = FloatArray(model.triCount * 3)
    internal val normals = FloatArray(model.triCount * 3)
    internal val shade = FloatArray(model.triCount)
    internal val projected = ProjectedTriangle()
    internal val colorBatch = ColorBatch()
    internal val camera = CameraProjector()
    internal var textureBatches: List<TextureBatch?> = emptyList()
    internal var sortedCount = 0
    internal var sortedForwardX = Float.NaN
    internal var sortedForwardY = Float.NaN
    internal var sortedForwardZ = Float.NaN
    internal var sortedEyeX = Float.NaN
    internal var sortedEyeY = Float.NaN
    internal var sortedEyeZ = Float.NaN
    internal var sortedExplode = Float.NaN
    internal var sortedMode = -1
    internal var sortedShowRoof = false
    internal var sortedShowWalls = false

    init {
        for (triangle in 0 until model.triCount) {
            val vertex = triangle * 9
            val face = triangle * 3
            val ax = model.verts[vertex]
            val ay = model.verts[vertex + 1]
            val az = model.verts[vertex + 2]
            val bx = model.verts[vertex + 3]
            val by = model.verts[vertex + 4]
            val bz = model.verts[vertex + 5]
            val cx = model.verts[vertex + 6]
            val cy = model.verts[vertex + 7]
            val cz = model.verts[vertex + 8]
            val nx = (by - ay) * (cz - az) - (bz - az) * (cy - ay)
            val ny = (bz - az) * (cx - ax) - (bx - ax) * (cz - az)
            val nz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            centers[face] = (ax + bx + cx) / 3f
            centers[face + 1] = (ay + by + cy) / 3f
            centers[face + 2] = (az + bz + cz) / 3f
            normals[face] = nx
            normals[face + 1] = ny
            normals[face + 2] = nz
            val lengthSquared = nx * nx + ny * ny + nz * nz
            val light = if (lengthSquared > 1e-12f) {
                (nx * LIGHT_DIR.x + ny * LIGHT_DIR.y + nz * LIGHT_DIR.z) / sqrt(lengthSquared)
            } else {
                0f
            }
            shade[triangle] = (AMBIENT + DIFFUSE * kotlin.math.abs(light)).coerceIn(0f, 1f)
        }
    }

    internal fun installTextures(images: List<ImageBitmap?>) {
        textureBatches = model.textures.indices.map { index ->
            images.getOrNull(index)?.let(::TextureBatch)
        }
    }
}

/**
 * Reusable screen-space vertex buffer for flat-shaded faces. The old renderer
 * built and submitted a Path whenever the next triangle had a different shade,
 * which meant thousands of Skia draw calls per rotating iPad frame. Vertex
 * colours let the same painter-ordered faces share one submission without
 * changing their individual shading or clipped polygon shape.
 */
internal class ColorBatch {
    private val vertices = Vertices(
        vertexMode = VertexMode.Triangles,
        positions = List(COLOR_BATCH_VERTICES) { Offset.Zero },
        // Compose's MPP Vertices constructor requires a coordinate list even
        // without a shader; these are ignored by the flat-colour paint.
        textureCoordinates = List(COLOR_BATCH_VERTICES) { Offset.Zero },
        colors = List(COLOR_BATCH_VERTICES) { Color.White },
        indices = List(COLOR_BATCH_VERTICES) { it },
    )
    private val paint = Paint().apply { isAntiAlias = true }
    private var used = 0
    private var previousUsed = 0

    internal fun append(projected: ProjectedTriangle, count: Int, color: Int): Boolean {
        val needed = (count - 2) * 3
        if (needed <= 0) return true
        if (used + needed > COLOR_BATCH_VERTICES) return false
        for (vertex in 1 until count - 1) {
            put(used++, projected, 0, color)
            put(used++, projected, vertex, color)
            put(used++, projected, vertex + 1, color)
        }
        return true
    }

    private fun put(slot: Int, projected: ProjectedTriangle, source: Int, color: Int) {
        val point = slot * 2
        vertices.positions[point] = projected.x[source]
        vertices.positions[point + 1] = projected.y[source]
        vertices.colors[slot] = color
    }

    internal fun flush(canvas: Canvas) {
        if (used == 0) return
        for (slot in used until previousUsed) {
            val point = slot * 2
            vertices.positions[point] = 0f
            vertices.positions[point + 1] = 0f
        }
        canvas.drawVertices(vertices, BlendMode.SrcOver, paint)
        previousUsed = used
        used = 0
    }
}

/**
 * Fixed-size mutable vertex buffer used for textured triangles. Reusing it is
 * important on iPad: allocating one Vertices object per face at 60 fps causes
 * exactly the periodic GC hitch this renderer is designed to avoid.
 */
internal class TextureBatch(private val image: ImageBitmap) {
    private val vertices = Vertices(
        vertexMode = VertexMode.Triangles,
        positions = List(TEXTURE_BATCH_VERTICES) { Offset.Zero },
        textureCoordinates = List(TEXTURE_BATCH_VERTICES) { Offset.Zero },
        colors = List(TEXTURE_BATCH_VERTICES) { Color.White },
        indices = List(TEXTURE_BATCH_VERTICES) { it },
    )
    private val paint = Paint().apply {
        shader = ImageShader(image, TileMode.Repeated, TileMode.Repeated)
        filterQuality = FilterQuality.Low
        isAntiAlias = true
    }
    private var used = 0
    private var previousUsed = 0

    internal fun append(projected: ProjectedTriangle, count: Int, color: Int): Boolean {
        val needed = (count - 2) * 3
        if (needed <= 0) return true
        if (used + needed > TEXTURE_BATCH_VERTICES) return false
        for (vertex in 1 until count - 1) {
            put(used++, projected, 0, color)
            put(used++, projected, vertex, color)
            put(used++, projected, vertex + 1, color)
        }
        return true
    }

    private fun put(slot: Int, projected: ProjectedTriangle, source: Int, color: Int) {
        val point = slot * 2
        vertices.positions[point] = projected.x[source]
        vertices.positions[point + 1] = projected.y[source]
        vertices.textureCoordinates[point] = projected.u[source] * image.width
        vertices.textureCoordinates[point + 1] = projected.v[source] * image.height
        vertices.colors[slot] = color
    }

    internal fun flush(canvas: Canvas) {
        if (used == 0) return
        // Vertices has no draw-count argument. Collapse any vertices left over
        // from a larger previous batch to a point so its trailing triangles are
        // degenerate and therefore free of visible output.
        for (slot in used until previousUsed) {
            val point = slot * 2
            vertices.positions[point] = 0f
            vertices.positions[point + 1] = 0f
        }
        canvas.drawVertices(vertices, BlendMode.Modulate, paint)
        previousUsed = used
        used = 0
    }
}

/** Sorts triangle ids in-place without boxing, farthest camera depth first. */
internal fun sortTrianglesBackToFront(order: IntArray, depth: FloatArray, count: Int) {
    if (count > 1) sortTrianglesBackToFront(order, depth, 0, count - 1)
}

private fun sortTrianglesBackToFront(order: IntArray, depth: FloatArray, first: Int, last: Int) {
    var left = first
    var right = last
    while (left < right) {
        var low = left
        var high = right
        val pivot = order[(left + right) ushr 1]
        while (low <= high) {
            while (low <= right && comparePainterOrder(order[low], pivot, depth) < 0) low++
            while (high >= left && comparePainterOrder(order[high], pivot, depth) > 0) high--
            if (low <= high) {
                val swap = order[low]
                order[low] = order[high]
                order[high] = swap
                low++
                high--
            }
        }
        // Recurse into the smaller partition and iterate over the larger one,
        // keeping stack use bounded even for unfriendly input orderings.
        if (high - left < right - low) {
            if (left < high) sortTrianglesBackToFront(order, depth, left, high)
            left = low
        } else {
            if (low < right) sortTrianglesBackToFront(order, depth, low, right)
            right = high
        }
    }
}

/**
 * Depths closer than a few millimetres share a bucket and fall back to their
 * stable GLB primitive id. Without this tie-break, nearly coplanar faces swap
 * painter order on every tiny camera step and look like the house is shaking.
 */
private fun comparePainterOrder(a: Int, b: Int, depth: FloatArray): Int {
    val aBucket = (depth[a] * DEPTH_BUCKETS_PER_METRE).roundToInt()
    val bBucket = (depth[b] * DEPTH_BUCKETS_PER_METRE).roundToInt()
    return when {
        aBucket > bBucket -> -1
        aBucket < bBucket -> 1
        a < b -> -1
        a > b -> 1
        else -> 0
    }
}

private const val DEPTH_BUCKETS_PER_METRE = 256f
// cos(1°): resorting 17k faces every ~3 auto-rotation frames produced a periodic
// Kotlin/Native CPU spike on iPad. Exact projection/back-face culling still runs
// every frame; only the painter order remains coherent for this small arc.
private const val SORT_DIRECTION_DOT = 0.9998477f
private const val SORT_EYE_DISTANCE_SQ = 0.25f // 50 cm, also about 1° at radius 26 m
private const val SORT_EXPLODE_STEP = 0.02f

/**
 * Renders the model with a painter's-algorithm rasterizer: gather visible,
 * front-facing triangles, sort back-to-front by camera depth, flat-shade
 * from the face normal, and fill as GPU-accelerated [Path]s at full resolution
 * (crisp and fast). Back-face culling removes the triangles that would otherwise
 * be the worst offenders for the depth-sort's inevitable ordering artifacts.
 * Translucent overlays (data-layer room tint, selection) draw on top; the
 * animated light-source rings are drawn by the HouseView3d overlay.
 */
internal fun DrawScope.drawHouse(
    model: HouseModel,
    eye: Vec3,
    target: Vec3,
    mode: FloorMode,
    showRoof: Boolean,
    showWalls: Boolean,
    explode: Float,
    cache: SoftwareRenderCache,
    showHeating: Boolean = false,
) {
    require(cache.model === model) { "SoftwareRenderCache belongs to a different model" }
    val w = size.width
    val h = size.height
    if (w < 1f || h < 1f) return

    if (!cache.camera.update(eye, target, w, h)) return
    val mvp = cache.camera.matrix
    val ex = eye.x; val ey = eye.y; val ez = eye.z

    // Painter ordering must use depth along the view axis; radial distance
    // reorders side-by-side faces while the camera orbits. Sorting all ~16k
    // triangles on every display tick dominated the Kotlin/Native frame budget,
    // so retain an order for sub-degree camera movement. Faces are still culled
    // against the exact camera below, and every material/floor/explode change
    // invalidates the retained order.
    val viewX = target.x - ex
    val viewY = target.y - ey
    val viewZ = target.z - ez
    val viewLength = sqrt(viewX * viewX + viewY * viewY + viewZ * viewZ)
    if (viewLength < 1e-6f) return
    val forwardX = viewX / viewLength
    val forwardY = viewY / viewLength
    val forwardZ = viewZ / viewLength
    val directionDot = forwardX * cache.sortedForwardX +
        forwardY * cache.sortedForwardY + forwardZ * cache.sortedForwardZ
    val eyeDx = ex - cache.sortedEyeX
    val eyeDy = ey - cache.sortedEyeY
    val eyeDz = ez - cache.sortedEyeZ
    val needsSort = cache.sortedCount == 0 || !directionDot.isFinite() ||
        directionDot < SORT_DIRECTION_DOT ||
        !eyeDx.isFinite() || eyeDx * eyeDx + eyeDy * eyeDy + eyeDz * eyeDz > SORT_EYE_DISTANCE_SQ ||
        !cache.sortedExplode.isFinite() || kotlin.math.abs(explode - cache.sortedExplode) > SORT_EXPLODE_STEP ||
        cache.sortedMode != mode.ordinal || cache.sortedShowRoof != showRoof || cache.sortedShowWalls != showWalls
    if (needsSort) {
        var gathered = 0
        for (t in 0 until model.triCount) {
            val g = HouseGroup.entries[model.group[t]]
            val cls = MatClass.entries[model.matClass[t]]
            if (!triVisible(g, cls, mode, showRoof, showWalls, showHeating = showHeating)) continue
            val yoff = groupTier(g) * explode
            val face = t * 3
            val nx = cache.normals[face]
            val ny = cache.normals[face + 1]
            val nz = cache.normals[face + 2]
            val centX = cache.centers[face]
            val centY = cache.centers[face + 1] + yoff
            val centZ = cache.centers[face + 2]
            if (cls != MatClass.Glass &&
                (nx * (ex - centX) + ny * (ey - centY) + nz * (ez - centZ)) < 0f
            ) continue
            cache.order[gathered] = t
            cache.depth[t] = (centX - ex) * forwardX + (centY - ey) * forwardY + (centZ - ez) * forwardZ
            gathered++
        }
        sortTrianglesBackToFront(cache.order, cache.depth, gathered)
        cache.sortedCount = gathered
        cache.sortedForwardX = forwardX
        cache.sortedForwardY = forwardY
        cache.sortedForwardZ = forwardZ
        cache.sortedEyeX = ex
        cache.sortedEyeY = ey
        cache.sortedEyeZ = ez
        cache.sortedExplode = explode
        cache.sortedMode = mode.ordinal
        cache.sortedShowRoof = showRoof
        cache.sortedShowWalls = showWalls
    }
    val count = cache.sortedCount

    val canvas = drawContext.canvas
    var activeTexture = -1
    for (o in 0 until count) {
        val t = cache.order[o]
        val g = HouseGroup.entries[model.group[t]]
        val yoff = groupTier(g) * explode
        val b = t * 9
        val face = t * 3
        val cls = MatClass.entries[model.matClass[t]]
        if (cls != MatClass.Glass &&
            (cache.normals[face] * (ex - cache.centers[face]) +
                cache.normals[face + 1] * (ey - cache.centers[face + 1] - yoff) +
                cache.normals[face + 2] * (ez - cache.centers[face + 2])) < 0f
        ) continue
        val textureIndex = model.texture[t]
        val textureBatch = if (textureIndex >= 0) cache.textureBatches.getOrNull(textureIndex) else null
        val vertexCount = projectTriangle(
            mvp = mvp,
            vertices = model.verts,
            base = b,
            yOffset = yoff,
            width = w,
            height = h,
            projected = cache.projected,
            textureCoordinates = if (textureBatch != null) model.uv else null,
            textureBase = t * 6,
        )
        if (vertexCount == 0) continue

        val shade = cache.shade[t]
        val rgb = model.rgb[t]
        val r = ((rgb shr 16) and 0xFF) / 255f * shade
        val gg = ((rgb shr 8) and 0xFF) / 255f * shade
        val bb = (rgb and 0xFF) / 255f * shade
        val isGlass = cls == MatClass.Glass
        val alpha = if (isGlass) 0.24f else 1f
        val vertexColor = ((alpha * 255f).toInt().coerceIn(0, 255) shl 24) or
            ((r * 255f).toInt().coerceIn(0, 255) shl 16) or
            ((gg * 255f).toInt().coerceIn(0, 255) shl 8) or
            (bb * 255f).toInt().coerceIn(0, 255)

        if (textureBatch == null || isGlass) {
            if (activeTexture >= 0) {
                cache.textureBatches[activeTexture]?.flush(canvas)
                activeTexture = -1
            }
            if (!cache.colorBatch.append(cache.projected, vertexCount, vertexColor)) {
                cache.colorBatch.flush(canvas)
                check(cache.colorBatch.append(cache.projected, vertexCount, vertexColor))
            }
        } else {
            cache.colorBatch.flush(canvas)
            if (activeTexture != textureIndex) {
                if (activeTexture >= 0) cache.textureBatches[activeTexture]?.flush(canvas)
                activeTexture = textureIndex
            }
            if (!textureBatch.append(cache.projected, vertexCount, vertexColor)) {
                textureBatch.flush(canvas)
                check(textureBatch.append(cache.projected, vertexCount, vertexColor))
            }
        }
    }
    cache.colorBatch.flush(canvas)
    if (activeTexture >= 0) cache.textureBatches[activeTexture]?.flush(canvas)
}

/**
 * Data-layer room fills + selected-room highlight, projected in screen space.
 * A shared overlay drawn on top of the geometry (software on iOS, Filament on
 * Android) so the tint/selection look identical on both platforms.
 */
internal class RoomOverlayRenderCache {
    internal val projected = ProjectedTriangle()
    internal val path = Path()
    internal val selectionPath = Path()
    internal val camera = CameraProjector()
}

internal fun DrawScope.drawRoomOverlays(
    model: HouseModel,
    eye: Vec3,
    target: Vec3,
    mode: FloorMode,
    explode: Float,
    selectedRoom: String?,
    roomTint: (RoomPatch) -> Color?,
    accent: Color,
    cache: RoomOverlayRenderCache,
) {
    val w = size.width; val h = size.height
    if (!cache.camera.update(eye, target, w, h)) return
    val mvp = cache.camera.matrix
    val projected = cache.projected
    val path = cache.path
    val selectionPath = cache.selectionPath
    selectionPath.rewind()
    var hasSelection = false
    for (room in model.rooms) {
        if (room.group !in mode.groups) continue
        val isSel = room.name == selectedRoom
        val tint = roomTint(room)
        if (tint == null && !isSel) continue
        val yoff = groupTier(room.group) * explode
        path.rewind()
        var i = 0
        while (i < room.tris.size) {
            val vertexCount = projectTriangle(mvp, room.tris, i, yoff, w, h, projected)
            if (vertexCount > 0) {
                path.append(projected, vertexCount)
                if (isSel) {
                    selectionPath.append(projected, vertexCount)
                    hasSelection = true
                }
            }
            i += 9
        }
        if (tint != null) drawPath(path, tint)
    }
    if (hasSelection) drawPath(selectionPath, accent.copy(alpha = 0.32f))
}

/** 2D screen hit-test for tap-to-focus (honours the floor filter + explode). */
fun pickRoom(
    model: HouseModel,
    eye: Vec3,
    target: Vec3,
    mode: FloorMode,
    explode: Float,
    canvasW: Float,
    canvasH: Float,
    px: Float,
    py: Float,
): String? {
    val camera = CameraProjector()
    if (!camera.update(eye, target, canvasW, canvasH)) return null
    val mvp = camera.matrix
    val projected = ProjectedTriangle()
    var best: String? = null
    var bestDepth = Float.MAX_VALUE
    for (room in model.rooms) {
        if (room.group !in mode.groups) continue
        val yoff = groupTier(room.group) * explode
        var roomDepth = Float.MAX_VALUE
        var i = 0
        while (i < room.tris.size) {
            val vertexCount = projectTriangle(mvp, room.tris, i, yoff, canvasW, canvasH, projected)
            if (vertexCount > 0) {
                val hitDepth = hitDepth(px, py, projected, vertexCount)
                if (hitDepth != null && hitDepth < roomDepth) roomDepth = hitDepth
            }
            i += 9
        }
        if (roomDepth < bestDepth) {
            bestDepth = roomDepth
            best = room.name
        }
    }
    return best
}

private fun hitDepth(px: Float, py: Float, projected: ProjectedTriangle, count: Int): Float? {
    var nearest = Float.MAX_VALUE
    for (vertex in 1 until count - 1) {
        val depth = hitDepthInTriangle(px, py, projected, 0, vertex, vertex + 1)
        if (depth != null && depth < nearest) nearest = depth
    }
    return if (nearest < Float.MAX_VALUE) nearest else null
}

private fun hitDepthInTriangle(
    px: Float, py: Float,
    projected: ProjectedTriangle,
    ia: Int,
    ib: Int,
    ic: Int,
): Float? {
    val ax = projected.x[ia]; val ay = projected.y[ia]
    val bx = projected.x[ib]; val by = projected.y[ib]
    val cx = projected.x[ic]; val cy = projected.y[ic]
    val denominator = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
    if (kotlin.math.abs(denominator) < 1e-6f) return null
    val a = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / denominator
    val b = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / denominator
    val c = 1f - a - b
    if (a < -1e-5f || b < -1e-5f || c < -1e-5f) return null
    val inverseDepth = a / projected.cameraDepth[ia] +
        b / projected.cameraDepth[ib] +
        c / projected.cameraDepth[ic]
    return if (inverseDepth > 0f) 1f / inverseDepth else null
}
