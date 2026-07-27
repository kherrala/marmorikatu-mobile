package fi.marmorikatu.app.screens

import fi.marmorikatu.core.model.Floor
import fi.marmorikatu.core.model.Light
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Living-area scenes (Aamuvalot / Iltavalot) must only ever turn *off* lights on
 * the main floor — a morning/evening scene reaching into the basement or upstairs
 * was the reported bug. A scene's own ON fixtures stay in scope on any floor so
 * they are still switched on; floor-scoping only narrows the turn-off set.
 */
class SceneScopeTest {

    private fun light(id: Int, floor: Floor, on: Boolean = false) =
        Light(id = id, name = "L$id", floor = floor, isOn = on)

    // A spread across floors: a preset ON light (54) placed upstairs on purpose to
    // prove the ON-set union keeps it in scope; other common lights per floor.
    private val house = listOf(
        light(54, Floor.YLAKERTA),   // Iltavalot ON fixture, deliberately upstairs
        light(19, Floor.ALAKERTA),   // Iltavalot ON fixture on the main floor
        light(90, Floor.ALAKERTA),   // main-floor common light NOT in the preset
        light(91, Floor.KELLARI),    // basement common light
        light(92, Floor.YLAKERTA),   // upstairs common light
        light(17, Floor.ALAKERTA),   // bedroom fixture (never touched)
    )

    @Test
    fun iltavalotNeverDarkensBasementOrUpstairs() {
        val scopeIds = sceneScopeLights(KotiScene.Iltavalot, house).map { it.id }.toSet()
        val onIds = sceneOnLightIds(KotiScene.Iltavalot, house)

        // A basement / upstairs common light that isn't part of the preset must be
        // out of scope, so applyPreset never turns it off.
        assertFalse(91 in scopeIds, "basement common light must be out of scope")
        assertFalse(92 in scopeIds, "upstairs common light must be out of scope")

        // A main-floor common light not in the preset IS in scope → gets turned off.
        assertTrue(90 in scopeIds)
        assertFalse(90 in onIds)

        // The preset's own ON fixtures are always in scope (turned on), even 54 upstairs.
        assertTrue(54 in scopeIds && 54 in onIds)
        assertTrue(19 in scopeIds && 19 in onIds)

        // Bedrooms are never in scope.
        assertFalse(17 in scopeIds)
    }

    @Test
    fun houseWideScenesStillOwnEveryCommonLight() {
        // KaikkiPois has a null scope floor → it should reach every common light on
        // every floor (its whole purpose), bedrooms still excluded.
        val scopeIds = sceneScopeLights(KotiScene.KaikkiPois, house).map { it.id }.toSet()
        assertEquals(setOf(54, 19, 90, 91, 92), scopeIds)
        assertFalse(17 in scopeIds)
    }
}
