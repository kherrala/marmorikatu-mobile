package fi.marmorikatu.core.transport.mqtt

import fi.marmorikatu.core.fixtures.MqttFixtures
import fi.marmorikatu.core.model.Floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlcPayloadsTest {

    // --- against live captured payloads ------------------------------------

    @Test
    fun parsesLiveLightsSnapshot() {
        val lights = PlcPayloads.parseLights(MqttFixtures.LIGHTS)
        assertTrue(lights.isNotEmpty())
        // Captured snapshot had light 43 on.
        assertEquals(true, lights[43])
        assertEquals(false, lights[1])
    }

    @Test
    fun parsesLiveLightNames() {
        val names = PlcPayloads.parseLightNames(MqttFixtures.LIGHT_NAMES)
        assertEquals("Kylpyhuone", names[1])
        // Index 21 is a PLC array gap with an empty name — must be skipped.
        assertTrue(21 !in names)
    }

    @Test
    fun parsesLiveTemperatures() {
        val temps = PlcPayloads.parseTemperatures(MqttFixtures.TEMPERATURES)
        assertEquals(9, temps.size)
        val aatu = temps.first { it.key == "yk_aatu" }
        assertEquals("Aatun huone", aatu.name)
        assertEquals(Floor.YLAKERTA, aatu.floor)
        assertTrue(aatu.celsius in 5.0..35.0)
    }

    @Test
    fun extraTemperaturesExcludeRooms() {
        val extras = PlcPayloads.parseExtraTemperatures(MqttFixtures.TEMPERATURES)
        assertTrue("tuloilmakanava" in extras)
        assertTrue("yk_aatu" !in extras)
    }

    @Test
    fun parsesLiveHeating() {
        val heating = PlcPayloads.parseHeating(MqttFixtures.HEATING)
        assertEquals(9, heating.size)
        assertTrue(heating.all { it.percent in 0..100 })
    }

    @Test
    fun parsesLiveCooling() {
        val cooling = PlcPayloads.parseCooling(MqttFixtures.COOLING)
        assertEquals(true, cooling.pumpCooling)
        assertEquals(false, cooling.coolingPump)
    }

    @Test
    fun parsesLiveOutlets() {
        val outlets = PlcPayloads.parseOutlets(MqttFixtures.OUTLETS)
        assertEquals(setOf("ulkopistorasia", "autokatos_pistorasia"), outlets.keys)
    }

    @Test
    fun parsesLiveStatus() {
        val status = PlcPayloads.parseStatus(MqttFixtures.STATUS)
        assertTrue(status.publishCount > 0)
        assertTrue(status.modbusConnected)
        assertEquals(status.commandsReceived, status.commandsApplied)
    }

    @Test
    fun parsesLiveVentilation() {
        val ventilation = PlcPayloads.parseVentilation(MqttFixtures.VENTILATION)
        // Firmware publishes English CamelCase keys, not the Finnish names.
        assertEquals(18.2, ventilation.outdoorC)
        assertEquals(24.1, ventilation.supplyC)       // SupplyTempPostHeat
        assertEquals(22.4, ventilation.supplyPreHeatC)
        assertEquals(24.5, ventilation.extractC)
        assertEquals(20.5, ventilation.exhaustC)
        assertEquals(43.8, ventilation.relativeHumidity)
        assertEquals(false, ventilation.freezingDanger)
        assertTrue(ventilation.raw.isNotEmpty())
    }

    @Test
    fun parsesLiveEnergy() {
        val energy = PlcPayloads.parseEnergy("heatpump", MqttFixtures.ENERGY_HEATPUMP)
        assertEquals("heatpump", energy.meter)
        // Meter keys are snake_case: Total_Active_Power / Total_Active_Energy.
        assertEquals(0.0, energy.powerKw)
        assertEquals(40624.77, energy.energyKwh)
        assertTrue(energy.raw.isNotEmpty())
    }

    // --- value-form tolerance ------------------------------------------------

    @Test
    fun boolValueForms() {
        val lights = PlcPayloads.parseLights(
            """{"1":true,"2":"true","3":1,"4":"on","5":"ON","6":false,"7":"0","8":0,"9":"off"}"""
        )
        (1..5).forEach { assertEquals(true, lights[it], "index $it") }
        (6..9).forEach { assertEquals(false, lights[it], "index $it") }
    }

    @Test
    fun malformedPayloadYieldsEmpty() {
        assertTrue(PlcPayloads.parseLights("not json").isEmpty())
        assertTrue(PlcPayloads.parseTemperatures("[1,2,3]").isEmpty())
        assertEquals(0, PlcPayloads.parseStatus("").publishCount)
    }

    @Test
    fun heatingClampsOutOfRange() {
        val heating = PlcPayloads.parseHeating("""{"essi":150,"aatu":-5}""")
        assertEquals(100, heating.first { it.key == "essi" }.percent)
        assertEquals(0, heating.first { it.key == "aatu" }.percent)
    }
}
