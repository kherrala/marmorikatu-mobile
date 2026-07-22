package fi.marmorikatu.app.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HouseIntentTest {
    @Test
    fun explicitFloorViewRequestsSteerWithoutSpinning() {
        assertEquals("ylakerta" to false, detectHouseIntent("Näytä yläkerta"))
        assertEquals("alakerta" to false, detectHouseIntent("Avaa alakerta"))
        assertEquals("kellari" to false, detectHouseIntent("Näytä kellari 3D:nä"))
    }

    @Test
    fun genericHouseViewRequestStartsPresentation() {
        assertEquals("all" to true, detectHouseIntent("Avaa talo 3D:nä"))
    }

    @Test
    fun unrelatedFloorCommandsDoNotOpenTheMap() {
        assertNull(detectHouseIntent("Sammuta yläkerta"))
        assertNull(detectHouseIntent("Kellarissa on valo päällä"))
        assertNull(detectHouseIntent("Mikä on talon lämpötila?"))
    }
}
