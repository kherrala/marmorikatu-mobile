package fi.marmorikatu.app.house3d

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoftwareRendererTest {
    private val eye = Vec3.ZERO
    private val target = Vec3(0f, 0f, -1f)

    @Test
    fun projectWorldRejectsPointsOutsideTheFrustum() {
        val center = assertNotNull(projectWorld(eye, target, 100f, 100f, Vec3(0f, 0f, -1f)))
        assertTrue(abs(center.x - 50f) < 0.001f)
        assertTrue(abs(center.y - 50f) < 0.001f)

        assertNull(projectWorld(eye, target, 100f, 100f, Vec3(0f, 0f, -0.05f)))
        assertNull(projectWorld(eye, target, 100f, 100f, Vec3(0f, 0f, -501f)))
        assertNull(projectWorld(eye, target, 100f, 100f, Vec3(10f, 0f, -1f)))
        assertNull(projectWorld(eye, target, 100f, 100f, Vec3(0f, 0f, 1f)))
    }

    @Test
    fun triangleCrossingNearPlaneIsClippedInsteadOfDropped() {
        val mvp = Mat4.multiply(
            Mat4.perspective((48.0 * kotlin.math.PI / 180.0).toFloat(), 1f, 0.1f, 500f),
            Mat4.lookAt(eye, target, Vec3.UP),
        )
        val vertices = floatArrayOf(
            0f, 0f, -0.05f,
            -0.3f, -0.3f, -1f,
            0.3f, -0.3f, -1f,
        )
        val projected = ProjectedTriangle()
        val uv = floatArrayOf(0.5f, 0f, 0f, 1f, 1f, 1f)

        val count = projectTriangle(mvp, vertices, 0, 0f, 100f, 100f, projected, uv)

        assertTrue(count >= 3)
        for (i in 0 until count) {
            assertTrue(projected.x[i] in 0f..100f)
            assertTrue(projected.y[i] in 0f..100f)
            assertTrue(projected.cameraDepth[i] >= 0.1f - 0.0001f)
            assertTrue(projected.u[i] in 0f..1f)
            assertTrue(projected.v[i] in 0f..1f)
        }
    }

    @Test
    fun triangleFullyOutsideOneFrustumPlaneIsRejected() {
        val mvp = Mat4.multiply(
            Mat4.perspective((48.0 * kotlin.math.PI / 180.0).toFloat(), 1f, 0.1f, 500f),
            Mat4.lookAt(eye, target, Vec3.UP),
        )
        val vertices = floatArrayOf(
            10f, -0.2f, -1f,
            11f, -0.2f, -1f,
            10f, 0.2f, -1f,
        )

        assertEquals(0, projectTriangle(mvp, vertices, 0, 0f, 100f, 100f, ProjectedTriangle()))
    }

    @Test
    fun painterSortUsesPrimitiveIdsAndOrdersFarthestFirst() {
        val order = intArrayOf(3, 1, 0, 2)
        val depthByTriangle = floatArrayOf(4f, 12f, -2f, 7f)

        sortTrianglesBackToFront(order, depthByTriangle, order.size)

        assertContentEquals(intArrayOf(1, 3, 0, 2), order)
    }

    @Test
    fun painterSortHandlesDuplicatesAndArbitraryInputOrder() {
        val random = Random(7)
        repeat(50) {
            val size = random.nextInt(1, 100)
            val depth = FloatArray(size) { random.nextInt(-5, 6).toFloat() }
            val order = IntArray(size) { it }
            for (i in order.lastIndex downTo 1) {
                val other = random.nextInt(i + 1)
                val swap = order[i]
                order[i] = order[other]
                order[other] = swap
            }

            sortTrianglesBackToFront(order, depth, size)

            for (i in 1 until size) assertTrue(depth[order[i - 1]] >= depth[order[i]])
        }
    }

    @Test
    fun painterSortKeepsNearCoplanarFacesInStablePrimitiveOrder() {
        val order = intArrayOf(3, 2, 1, 0)
        val depth = floatArrayOf(10.0000f, 10.0005f, 10.0010f, 10.0015f)

        sortTrianglesBackToFront(order, depth, order.size)

        assertContentEquals(intArrayOf(0, 1, 2, 3), order)
    }

    @Test
    fun pickingChoosesFrontmostOverlappingRoomPatch() {
        fun room(name: String, z: Float) = RoomPatch(
            name = name,
            group = HouseGroup.Kellari,
            center = Vec3(0f, 0f, z),
            tris = floatArrayOf(-1f, -1f, z, 1f, -1f, z, 0f, 1f, z),
        )
        val model = HouseModel(
            triCount = 0,
            verts = FloatArray(0),
            rgb = IntArray(0),
            uv = FloatArray(0),
            texture = IntArray(0),
            group = IntArray(0),
            matClass = IntArray(0),
            textures = emptyList(),
            rooms = listOf(room("far", -4f), room("near", -2f)),
            center = Vec3.ZERO,
            size = Vec3.ZERO,
        )

        assertEquals(
            "near",
            pickRoom(model, eye, target, FloorMode.Kellari, 0f, 100f, 100f, 50f, 50f),
        )
    }
}
