package fi.marmorikatu.app.house3d

import fi.marmorikatu.app.screens.LIGHT_AREAS
import kotlinx.coroutines.runBlocking
import marmorikatu_mobile.composeapp.generated.resources.Res
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose's Android local-test resource reader depends on an instrumented
 * Context. Validate the actual bundled model on iOS, where raw resources are
 * available to host tests; all pure renderer policies remain in commonTest.
 */
class HouseAssetTest {
    @Test
    fun checkedInModelPresetsAndInfographicMappingsStayInSync() = runBlocking {
        val model = parseGlb(Res.readBytes("files/marmorikatu-house.glb"))
        val presets = parseCameras(Res.readBytes("files/house-cameras.json").decodeToString())

        assertTrue(model.triCount > 0)
        assertEquals(
            setOf(
                "concrete_nor", "concrete_diff", "paver_nor", "paver_diff",
                "siding2_nor", "siding2_diff", "siding_nor", "siding_diff",
                "floor_nor", "floor_diff", "deck_diff", "tierbrick_diff", "grass_diff",
            ),
            model.textures.mapNotNull { it.name }.toSet(),
        )
        assertEquals(model.triCount * 6, model.uv.size)
        assertTrue(model.texture.any { it >= 0 })
        assertTrue(model.textures.all { texture ->
            val bytes = texture.bytes
            val png = bytes.size > 8 && bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            val jpeg = bytes.size > 3 && bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
            png || jpeg
        })
        assertEquals(model.rooms.map { it.name }.toSet(), presets.rooms.keys)
        assertEquals(presets.rooms.keys, HouseLightMap.roomToAreas.keys)
        assertEquals(presets.lights.map { it.name }.toSet(), HouseLightMap.anchorToArea.keys)

        val knownAreas = LIGHT_AREAS.map { it.key }.toSet()
        assertTrue(HouseLightMap.anchorToArea.values.all { it in knownAreas })
        assertTrue(HouseLightMap.roomToAreas.values.flatten().all { it in knownAreas })
    }
}
