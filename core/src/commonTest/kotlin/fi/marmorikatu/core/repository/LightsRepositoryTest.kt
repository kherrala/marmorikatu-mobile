package fi.marmorikatu.core.repository

import com.russhwolf.settings.MapSettings
import fi.marmorikatu.core.fakes.FakeMcpApi
import fi.marmorikatu.core.fakes.FakeMqttClient
import fi.marmorikatu.core.transport.mqtt.MqttTopics
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LightsRepositoryTest {

    @Test
    fun mergesCatalogWithLiveStateAndReflectsToggle() = runTest {
        val mqtt = FakeMqttClient()
        val mcp = FakeMcpApi()
        val repo = DefaultLightsRepository(mqtt, mcp, backgroundScope, MapSettings())
        runCurrent() // let the repository's MQTT collector subscribe

        repo.refreshCatalog()
        mqtt.emit(MqttTopics.LIGHTS, """{"1":false,"43":true,"51":false}""")
        runCurrent()

        val lights = repo.lights.value
        assertEquals(3, lights.size)
        val olohuone = lights.first { it.id == 43 }
        assertEquals("Olohuone", olohuone.name)
        assertTrue(olohuone.isOn)

        // Single toggle goes over MQTT when connected and shows optimistically.
        repo.setLight(51, true)
        runCurrent()
        assertEquals(
            listOf(Triple("marmorikatu/light/51/set", "true", false)),
            mqtt.published,
        )
        assertEquals(true, repo.lights.value.first { it.id == 51 }.displayedOn)
        assertEquals(false, repo.lights.value.first { it.id == 51 }.isOn)

        // Confirmation from the retained topic settles the pending state.
        mqtt.emit(MqttTopics.LIGHTS, """{"1":false,"43":true,"51":true}""")
        runCurrent()
        val billiard = repo.lights.value.first { it.id == 51 }
        assertTrue(billiard.isOn)
        assertNull(billiard.pendingOn)

        coroutineContext.cancelChildren()
    }

    @Test
    fun unmappedPlcGapIndicesAreNeverExposed() = runTest {
        val mqtt = FakeMqttClient()
        // Catalog has no entry for 21/27; those are gaps in the PLC's
        // Controls[] array and must not appear as controllable lights.
        val repo = DefaultLightsRepository(mqtt, FakeMcpApi(), backgroundScope, MapSettings())
        runCurrent()

        mqtt.emit(MqttTopics.LIGHTS, """{"21":false,"27":true,"43":true}""")
        mqtt.emit(MqttTopics.LIGHT_NAMES, """{"21":"","27":"","43":"Olohuone"}""")
        runCurrent()

        assertEquals(listOf(43), repo.lights.value.map { it.id })

        coroutineContext.cancelChildren()
    }

    @Test
    fun fallsBackToMcpWhenMqttDisconnected() = runTest {
        val mqtt = FakeMqttClient()
        val mcp = FakeMcpApi()
        val repo = DefaultLightsRepository(mqtt, mcp, backgroundScope, MapSettings())
        runCurrent()

        mqtt.setConnected(false)
        repo.setLight(43, true)
        runCurrent()

        assertTrue(mqtt.published.isEmpty())
        assertEquals(listOf("set_light:43:true"), mcp.calls)

        coroutineContext.cancelChildren()
    }

    @Test
    fun batchCommandsAlwaysGoViaMcp() = runTest {
        val mqtt = FakeMqttClient()
        val mcp = FakeMcpApi()
        val repo = DefaultLightsRepository(mqtt, mcp, backgroundScope, MapSettings())
        runCurrent()
        repo.refreshCatalog()
        mcp.calls.clear()

        repo.setAll(false)
        runCurrent()
        assertEquals(listOf("set_all_lights:false"), mcp.calls)
        assertTrue(mqtt.published.isEmpty())

        coroutineContext.cancelChildren()
    }

    @Test
    fun catalogIsCachedInSettingsForOfflineStart() = runTest {
        val settings = MapSettings()
        val repo = DefaultLightsRepository(
            FakeMqttClient(), FakeMcpApi(), backgroundScope, settings,
        )
        runCurrent()
        repo.refreshCatalog()

        // A fresh repository over the same settings sees names without MCP.
        val offlineRepo = DefaultLightsRepository(
            FakeMqttClient(), FakeMcpApi().apply { catalog = emptyList() },
            backgroundScope, settings,
        )
        runCurrent() // the initial publish is dispatched, not inline
        assertEquals(
            setOf("Kylpyhuone", "Olohuone", "Biljardipöytä"),
            offlineRepo.lights.value.map { it.name }.toSet(),
        )

        coroutineContext.cancelChildren()
    }
}
