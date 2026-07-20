package fi.marmorikatu.core.repository

import com.russhwolf.settings.MapSettings
import fi.marmorikatu.core.fakes.FakeMcpApi
import fi.marmorikatu.core.fakes.FakeMqttClient
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The PLC silently drops light commands that arrive faster than its scan
 * cycle — a measured burst of eight publishes landed as seven. Commands must
 * therefore leave the device one at a time, spaced.
 */
class LightsPacingTest {

    @Test
    fun burstOfCommandsIsPacedNotFired() = runTest {
        val mqtt = FakeMqttClient()
        val repo = DefaultLightsRepository(mqtt, FakeMcpApi(), backgroundScope, MapSettings())
        runCurrent()

        // A scene change touches several lights at once.
        listOf(49, 50, 52, 53).forEach { repo.setLight(it, true) }
        runCurrent()

        // Only the first light has gone out; the rest are queued behind the spacing.
        // Each delivered command is a /set plus its /command provenance breadcrumb.
        assertEquals(2, mqtt.published.size)

        advanceTimeBy(200)
        runCurrent()
        assertEquals(4, mqtt.published.size)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(8, mqtt.published.size)
        assertEquals(
            listOf(49, 50, 52, 53).flatMap {
                listOf("marmorikatu/light/$it/set", "marmorikatu/light/$it/command")
            },
            mqtt.published.map { it.first },
        )
        // The /set carries the retained bool; the /command breadcrumb carries the JSON.
        assertTrue(mqtt.published.filter { it.first.endsWith("/set") }.all { it.second == "true" })
        assertTrue(
            mqtt.published.filter { it.first.endsWith("/command") }
                .all { it.second == """{"on":true,"src":"mobile"}""" },
        )

        coroutineContext.cancelChildren()
    }

    @Test
    fun optimisticStateIsVisibleBeforeTheCommandLeaves() = runTest {
        val mqtt = FakeMqttClient()
        val repo = DefaultLightsRepository(mqtt, FakeMcpApi(), backgroundScope, MapSettings())
        runCurrent()
        repo.refreshCatalog()
        runCurrent()

        repo.setLight(43, true)
        runCurrent()

        // The UI must not wait on the pacing delay to show the change.
        assertEquals(true, repo.lights.value.first { it.id == 43 }.displayedOn)

        coroutineContext.cancelChildren()
    }
}
