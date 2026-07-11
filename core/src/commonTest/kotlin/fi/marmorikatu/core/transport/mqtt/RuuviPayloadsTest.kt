package fi.marmorikatu.core.transport.mqtt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/** Fixtures captured from the live broker on 2026-07-11. */
class RuuviPayloadsTest {

    @Test
    fun decodesTheAirQualityTag() {
        val payload = """
            {"gw_mac":"CC:F1:A2:8E:F8:8A","rssi":-50,"gwts":1783799395,"ts":1783799394,
             "data":"2BFF9904E1...","dataFormat":225,"temperature":23.565,"humidity":54.90,
             "pressure":100319,"PM1.0":0.8,"PM2.5":1.5,"CO2":499,"VOC":130,"NOx":1,
             "id":"E6:DC:F8:EC:78:3B","coords":""}
        """.trimIndent()
        val r = RuuviPayloads.parse(payload)
        assertNotNull(r)
        assertEquals("Keittiö", r.sensorName)
        assertEquals(23.565, r.temperature)
        assertEquals(499, r.co2)
        assertEquals(1.5, r.pm25)
        assertEquals(130, r.voc)
        assertEquals(1, r.nox)
        assertEquals(1783799394L, r.tsEpoch)
    }

    @Test
    fun decodesABasicTagWithBattery() {
        val payload = """
            {"gw_mac":"CC:F1:A2:8E:F8:8A","rssi":-64,"gwts":1783799395,"ts":1783799395,
             "data":"0201061BFF990405...","dataFormat":5,"temperature":-19.060,"humidity":57.135,
             "pressure":100246,"voltage":2.479,"txPower":4,"measurementSequenceNumber":11740,
             "id":"EF:AA:DF:C0:4F:8C","coords":""}
        """.trimIndent()
        val r = RuuviPayloads.parse(payload)
        assertNotNull(r)
        assertEquals("Pakastin", r.sensorName)
        assertEquals(-19.060, r.temperature)
        assertEquals(2.479, r.voltage)   // low battery — drives the alert
        assertNull(r.co2)                // basic tag has no air-quality fields
    }

    @Test
    fun ignoresNonRuuviBleDevices() {
        // A phone the gateway relays: raw hex only, no decoded `id`.
        val payload = """
            {"gw_mac":"CC:F1:A2:8E:F8:8A","rssi":-79,"gwts":1783799395,"ts":1783799340,
             "data":"02011A0DFF4C00160800D8E0353F54EA6E","coords":""}
        """.trimIndent()
        assertNull(RuuviPayloads.parse(payload))
    }

    @Test
    fun ignoresUnmappedTag() {
        val payload = """{"ts":1,"temperature":20.0,"id":"AA:BB:CC:DD:EE:FF"}"""
        assertNull(RuuviPayloads.parse(payload))
    }

    @Test
    fun malformedJsonIsNull() {
        assertNull(RuuviPayloads.parse("not json"))
    }
}
