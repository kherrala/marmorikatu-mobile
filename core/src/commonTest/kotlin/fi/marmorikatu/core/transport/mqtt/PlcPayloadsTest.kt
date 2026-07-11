package fi.marmorikatu.core.transport.mqtt

import fi.marmorikatu.core.fixtures.MqttFixtures
import fi.marmorikatu.core.model.Floor
import fi.marmorikatu.core.model.HeatPumpAlarm
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
        assertEquals("Seelan huone", aatu.name)
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

    // --- ThermIQ heat-pump register dump -------------------------------------

    /** A live `ThermIQ/marmorikatu/data` payload captured 2026-07-10 (summer, idle). */
    private val thermiqPayload = """
        {"d0":24,"d1":20,"d2":5,"d3":22,"d4":0,"d5":22,"d6":24,"d7":53,"d8":10,"d9":11,
        "d10":-40,"d12":0,"d16":0,"d50":22,"INDR_T":20.5,"EVU":0,
        "time":"2026-07-10 17:12:52 BST","timestamp":1783699972}
    """.trimIndent().replace("\n", "")

    @Test
    fun parsesThermiqRegisters() {
        val hp = PlcPayloads.parseThermiq(thermiqPayload)!!
        assertTrue(hp.available)
        assertEquals(53.0, hp.hotWaterC)          // d7
        assertEquals(22.0, hp.indoorTargetC)      // d3 + d4/10
        assertEquals(20.5, hp.indoorC)            // d1 + d2/10, matches INDR_T
        assertEquals(24.0, hp.outdoorC)           // d0
        assertEquals(22.0, hp.supplyC)            // d5
        assertEquals(24.0, hp.returnC)            // d6
        assertEquals(11.0, hp.brineInC)           // d9
        assertEquals(10.0, hp.brineOutC)          // d8
        assertEquals(1.0, hp.brineDeltaC)         // in - out
        assertEquals(1783699972L, hp.updatedAtEpochSeconds)
        // d16 == 0 → compressor bit clear, current 0 A → idle.
        assertTrue(!hp.running)
        assertTrue(!hp.hotWaterActive)
    }

    @Test
    fun thermiqRunningFromCompressorBit() {
        // d16 bit 1 (compressor) + bit 3 (hot water) set = 0b1010 = 10.
        val hp = PlcPayloads.parseThermiq("""{"d7":48,"d16":10,"d12":8}""")!!
        assertTrue(hp.running)
        assertTrue(hp.hotWaterActive)
    }

    @Test
    fun thermiqFiltersDisconnectedSensorSentinel() {
        // −40 is the Thermia "sensor not connected" value; must read as null.
        val hp = PlcPayloads.parseThermiq("""{"d7":50,"d0":-40}""")!!
        assertEquals(null, hp.outdoorC)
    }

    @Test
    fun thermiqDecodesAuxHeaterAndFaultCodes() {
        // d13 bit0 = 3 kW aux; d19 bit3 = low brine flow; d20 bit0 = outdoor sensor.
        val hp = PlcPayloads.parseThermiq("""{"d7":50,"d13":1,"d19":8,"d20":1}""")!!
        assertTrue(hp.auxHeaterActive)
        assertTrue(HeatPumpAlarm.LowBrineFlow in hp.alarms)
        assertTrue(HeatPumpAlarm.OutdoorSensor in hp.alarms)
        assertEquals(2, hp.alarms.size)
        assertEquals("d19:3", HeatPumpAlarm.LowBrineFlow.code)
    }

    @Test
    fun thermiqClearWhenNoAuxOrAlarms() {
        val hp = PlcPayloads.parseThermiq("""{"d7":50,"d13":0,"d19":0,"d20":0}""")!!
        assertTrue(!hp.auxHeaterActive)
        assertTrue(hp.alarms.isEmpty())
    }

    @Test
    fun thermiqRejectsNonData() {
        assertEquals(null, PlcPayloads.parseThermiq("not json"))
        // No hot water and no target → not a usable ThermIQ frame.
        assertEquals(null, PlcPayloads.parseThermiq("""{"rssi":-85,"EVU":0}"""))
    }
}
