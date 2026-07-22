package fi.marmorikatu.app.house3d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import fi.marmorikatu.app.screens.LIGHT_AREAS
import fi.marmorikatu.core.model.Announcement
import fi.marmorikatu.core.model.RuuviReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HouseFeaturesTest {
    @Test
    fun explodeTierTracksSelectedRoomOrVisibleFloorCenter() {
        assertEquals(0f, cameraExplodeTier(FloorMode.Kellari, showRoof = false, selectedGroup = null))
        assertEquals(1f, cameraExplodeTier(FloorMode.Alakerta, showRoof = false, selectedGroup = null))
        assertEquals(2f, cameraExplodeTier(FloorMode.Ylakerta, showRoof = false, selectedGroup = null))
        assertEquals(1f, cameraExplodeTier(FloorMode.All, showRoof = false, selectedGroup = null))
        assertEquals(1.5f, cameraExplodeTier(FloorMode.All, showRoof = true, selectedGroup = null))
        assertEquals(2f, cameraExplodeTier(FloorMode.All, showRoof = false, selectedGroup = HouseGroup.Krs2))
    }

    @Test
    fun balconyLightExplodesWithTheUpstairs() {
        assertEquals(HouseGroup.Krs2, anchorGroup("Light_ulko_parveke"))
        assertEquals(HouseGroup.Krs2, anchorGroup("Light_2krs_AULA_1"))
        assertEquals(HouseGroup.Katos, anchorGroup("Light_ulko_katos"))
        assertEquals(HouseGroup.Kellari, anchorGroup("Light_kellari_VAR1_1"))
    }

    @Test
    fun geometryControlsHideAllExpectedHelperAndShellMaterials() {
        assertFalse(triVisible(HouseGroup.Krs1, MatClass.Fixture, FloorMode.All, true, true))
        assertFalse(triVisible(HouseGroup.Katos, MatClass.Roof, FloorMode.All, false, true))
        assertFalse(triVisible(HouseGroup.Katto, MatClass.Solid, FloorMode.All, false, true))
        assertFalse(triVisible(HouseGroup.Krs1, MatClass.Glass, FloorMode.All, true, false))
        assertTrue(triVisible(HouseGroup.Krs1, MatClass.Solid, FloorMode.Alakerta, false, false))
    }

    @Test
    fun splitOpenPlanRoomsHaveFriendlyTitles() {
        assertEquals("Keittiö", HouseLightMap.roomTitle("Room_1krs_KT"))
        assertEquals("Ruokailu", HouseLightMap.roomTitle("Room_1krs_RUOKAILU"))
        assertEquals("Olohuone", HouseLightMap.roomTitle("Room_1krs_OH"))
        assertEquals("WC", HouseLightMap.roomTitle("Room_kellari_WC"))
        assertEquals(listOf("kel_wc"), HouseLightMap.roomToAreas["Room_kellari_WC"])
        assertEquals("kel_wc", HouseLightMap.anchorToArea["Light_kellari_WC"])
    }

    @Test
    fun infographicLabelsHandleSingularAndRoundedNegativeZero() {
        assertEquals("1 valo päällä", lightsOnLabel(1))
        assertEquals("2 valoa päällä", lightsOnLabel(2))
        val outdoor = liveFacts(null, -0.01, null, 0).single()
        assertEquals("0,0°", outdoor.sub)
        val co2 = liveFacts(null, null, 1_200, 0).single()
        assertEquals(MarkerKind.Info, co2.kind)
    }

    @Test
    fun infographicRetainsButLabelsStaleMqttReadings() {
        val fresh = RuuviReading(sensorName = "Sauna", temperature = 72.0, tsEpoch = 9_900)
        assertFalse(isHouseReadingStale(fresh, nowEpochSeconds = 10_000))
        assertTrue(isHouseReadingStale(fresh, nowEpochSeconds = 12_000))
        assertTrue(isHouseReadingStale(fresh.copy(tsEpoch = 0), nowEpochSeconds = 10_000))

        val retained = retainHouseReadings(mapOf("Sauna" to fresh), emptyMap())
        assertEquals(fresh, retained["Sauna"])
        val olderReplay = fresh.copy(temperature = 65.0, tsEpoch = 9_000)
        assertEquals(fresh, retainHouseReadings(retained, mapOf("Sauna" to olderReplay))["Sauna"])

        val staleFact = liveFacts(
            saunaC = retained["Sauna"]?.temperature,
            outdoorC = null,
            kitchenCo2 = null,
            lightsOn = 0,
            staleSensors = setOf("Sauna"),
        ).single()
        assertTrue(staleFact.stale)
        assertEquals(MarkerKind.Sauna, staleFact.kind)
    }

    @Test
    fun cameraIdentityUsesPhysicalSourceAndIsolatesItsFloor() {
        val upstairsAlert = HouseMarker(
            "Aarni kuuma", "27 °C", Vec3(1f, 5f, 2f), HouseGroup.Krs2, MarkerKind.Alert,
        )
        val updatedValue = HouseMarker(
            "Aarni kuuma", "28 °C", Vec3(1f, 5f, 2f), HouseGroup.Krs2, MarkerKind.Alert,
        )
        assertEquals(markerCameraKey(listOf(upstairsAlert)), markerCameraKey(listOf(updatedValue)))
        assertEquals(2f, markerCameraTier(listOf(upstairsAlert)))
        assertEquals(FloorMode.Ylakerta, focusedFloorMode(FloorMode.Kellari, listOf(upstairsAlert), null))
        assertEquals(FloorMode.Alakerta, focusedFloorMode(FloorMode.All, emptyList(), HouseGroup.Krs1))
        assertEquals(FloorMode.Kellari, focusedFloorMode(FloorMode.Kellari, emptyList(), null))
        assertEquals(FloorMode.All, focusedFloorMode(FloorMode.Kellari, listOf(
            upstairsAlert,
            HouseMarker("Sauna", null, Vec3.ZERO, HouseGroup.Krs1, MarkerKind.Info),
        ), null))

        assertEquals(8.5f, comfortableRoomFocus(OrbitPreset(Vec3.ZERO, 4.58f, 0.55f)).radius)
        assertEquals(12f, comfortableRoomFocus(OrbitPreset(Vec3.ZERO, 12f, 0.55f)).radius)
        assertEquals(10f, markerFocus(listOf(upstairsAlert))?.radius)
    }

    @Test
    fun showcaseToursWholeHouseAndEveryFloorInOrder() {
        fun fact(label: String, group: HouseGroup) =
            HouseMarker(label, null, Vec3.ZERO, group, MarkerKind.Info)

        val pages = buildShowcasePages(
            facts = listOf(
                fact("A", HouseGroup.Krs1),
                fact("B", HouseGroup.Krs1),
                fact("C", HouseGroup.Krs1),
                fact("D", HouseGroup.Krs1),
                fact("E", HouseGroup.Krs2),
            ),
            selected = FloorMode.All,
        )

        assertEquals(
            listOf(
                FloorMode.All,
                FloorMode.Kellari,
                FloorMode.Alakerta,
                FloorMode.Alakerta,
                FloorMode.Ylakerta,
            ),
            pages.map { it.floorMode },
        )
        assertEquals(listOf(0, 0, 3, 1, 1), pages.map { it.facts.size })
        assertEquals(
            listOf(FloorMode.Ylakerta),
            buildShowcasePages(listOf(fact("E", HouseGroup.Krs2)), FloorMode.Ylakerta)
                .map { it.floorMode },
        )
    }

    @Test
    fun houseControlsStackOnLandscapePhonesAndNarrowTablets() {
        assertEquals(HouseControlLayout.CompactStacked, houseControlLayout(780f, embedded = true))
        assertEquals(HouseControlLayout.CompactStacked, houseControlLayout(980f, embedded = true))
        assertEquals(HouseControlLayout.WideSingleRow, houseControlLayout(1_180f, embedded = true))
        assertEquals(HouseControlLayout.CompactStacked, houseControlLayout(1_180f, embedded = false))
    }

    @Test
    fun alertSourcesAndInfographicFocusAreSpatiallyMapped() {
        assertEquals("Room_1krs_LH", alertRoomName("Sauna on ollut päällä pitkään"))
        assertEquals("Room_1krs_TEKN", alertRoomName("Maalämpö · korkeapainevahti"))
        assertEquals("Room_1krs_KT", alertRoomName("Pakastin lämmennyt"))
        assertEquals("Room_1krs_OH", alertRoomName("Takka-anturi ei vastaa"))

        val markers = listOf(
            HouseMarker("A", null, Vec3(0f, 1f, 0f), HouseGroup.Krs1, MarkerKind.Info),
            HouseMarker("B", null, Vec3(10f, 1f, 0f), HouseGroup.Krs1, MarkerKind.Info),
            HouseMarker("C", null, Vec3(5f, 1f, 4f), HouseGroup.Krs1, MarkerKind.Info),
        )
        val focus = markerFocus(markers)!!
        assertEquals(5f, focus.target.x)
        assertEquals(1f, focus.target.y)
        assertEquals(4f / 3f, focus.target.z)
        assertTrue(focus.radius >= 16.5f)
    }

    @Test
    fun liveAnnouncementsUseBackendKeysToFindTheirPhysicalSource() {
        fun event(kind: String, key: String, text: String = "Ilmoitus") = Announcement(
            id = 1,
            text = text,
            kind = kind,
            priority = 1,
            key = key,
            ts = 1.0,
        )

        assertEquals("Room_1krs_WC", announcementRoomName(event("light_on", "light_on:44")))
        assertEquals("Room_1krs_LH", announcementRoomName(event("lights_opt_sauna_on", "lights_opt_sauna_laude")))
        assertEquals("Room_2krs_MH2", announcementRoomName(event("room_temp_low", "room_temp:MH_Aarni")))
        assertEquals("Room_2krs_AULA", announcementRoomName(event("floor_heat_hot", "floor_heat:ylakerta")))
        assertEquals("Room_1krs_TEKN", announcementRoomName(event("alarm_on:Alarm_filter_guard", "alarm:Alarm_filter_guard")))
        assertEquals(null, announcementRoomName(event("news_headlines", "news:morning")))
    }

    @Test
    fun markerHudStaysInsideViewportAndClearOfRoomPanel() {
        val viewport = IntSize(1_024, 600)
        val sizes = listOf(IntSize(200, 40), IntSize(180, 44))
        val positions = placeHudLabels(
            anchors = listOf(Offset(-20f, -20f), Offset(1_100f, 700f)),
            sizes = sizes,
            viewport = viewport,
            reservedRightPx = 276,
            marginPx = 12,
            topInsetPx = 30,
            gapPx = 6,
        )

        positions.zip(sizes).forEach { (position, size) ->
            assertTrue(position.x >= 12)
            assertTrue(position.y >= 30)
            assertTrue(position.x + size.width <= viewport.width - 12 - 276)
            assertTrue(position.y + size.height <= viewport.height - 12)
        }
    }

    @Test
    fun markerHudSeparatesCollidingLabels() {
        val sizes = List(3) { IntSize(220, 40) }
        val positions = placeHudLabels(
            anchors = List(3) { Offset(500f, 300f) },
            sizes = sizes,
            viewport = IntSize(1_024, 600),
            marginPx = 12,
            topInsetPx = 30,
            gapPx = 6,
        )

        for (a in positions.indices) {
            for (b in a + 1 until positions.size) {
                val first = positions[a]
                val second = positions[b]
                val horizontallySeparate = first.x + sizes[a].width + 6 <= second.x ||
                    second.x + sizes[b].width + 6 <= first.x
                val verticallySeparate = first.y + sizes[a].height + 6 <= second.y ||
                    second.y + sizes[b].height + 6 <= first.y
                assertTrue(horizontallySeparate || verticallySeparate)
            }
        }
    }
}
