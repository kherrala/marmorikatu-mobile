package fi.marmorikatu.app.house3d

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fi.marmorikatu.app.components.rememberWallClock
import fi.marmorikatu.app.components.MkIconButton
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.screens.KotiViewModel
import fi.marmorikatu.app.screens.ValotViewModel
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import fi.marmorikatu.core.model.RuuviSensors
import fi.marmorikatu.core.model.RuuviReading
import fi.marmorikatu.core.repository.AnnouncementsRepository
import fi.marmorikatu.core.speech.SpeechOutput
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import marmorikatu_mobile.composeapp.generated.resources.Res
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

/** Which data layer colours the rooms. Lämpö/Ilma are placeholders for now. */
enum class DataLayer(val label: String) { Valot("Valot"), Energia("Energia") }

private sealed interface HouseLoad {
    data object Loading : HouseLoad
    data class Ready(val model: HouseModel, val presets: CameraPresets) : HouseLoad
    data class Failed(val message: String) : HouseLoad
}

/**
 * The full-screen 3D house overlay (design `mk-house-3d`): header + close, an
 * orbitable dollhouse with live light glows and event pings, a floor segmented
 * control, a data-layer selector (Valot / Energia live; Lämpö / Ilma *tulossa*),
 * the Kerrosväli explode slider, and a tap-to-open room panel.
 *
 * [presentation] opens it as the kiosk infomercial — the house rotates on its
 * own and facts cycle at their real positions. [idle] adds the screensaver
 * takeover (big clock + "Kosketa jatkaaksesi"); any tap calls [onDismiss].
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun House3dOverlay(
    onDismiss: () -> Unit,
    presentation: Boolean = false,
    idle: Boolean = false,
    /** Tablet kiosk content keeps the app rail/header around this scene. */
    embedded: Boolean = false,
    onExitIdle: () -> Unit = onDismiss,
    /** External fly-to-floor request (voice); each new [floorNonce] re-applies. */
    floorTarget: FloorMode? = null,
    floorNonce: Int = 0,
) {
    val colors = MkTheme.colors
    val type = MkTheme.type

    val load by produceState<HouseLoad>(HouseLoad.Loading) {
        value = try {
            val glb = Res.readBytes("files/marmorikatu-house.glb")
            val camJson = Res.readBytes("files/house-cameras.json").decodeToString()
            val model = withContext(Dispatchers.Default) { parseGlb(glb) }
            HouseLoad.Ready(model, parseCameras(camJson))
        } catch (t: Throwable) {
            HouseLoad.Failed(t.message ?: "Mallin lataus epäonnistui")
        }
    }

    val valotVm: ValotViewModel = koinViewModel()
    val valot by valotVm.uiState.collectAsState()
    LaunchedEffect(valotVm) { valotVm.refresh() }
    val onAreas = remember(valot) {
        valot.floors.flatMap { it.areas }.filter { it.isOn }.map { it.key }.toSet()
    }
    val lightsOn = remember(valot) {
        valot.floors.sumOf { f -> f.areas.sumOf { a -> a.lights.count { it.on } } }
    }

    // Live sensor readings for the fact reel — real values only (no fabrication).
    val ilmastoVm: fi.marmorikatu.app.screens.IlmastoViewModel = koinViewModel()
    val ruuvi by ilmastoVm.ruuvi.collectAsState()
    // Upstairs has no Ruuvi sensors; its infographic pins come from the PLC room
    // temperatures instead (anchored to the model's real room centres below).
    val roomTemps by ilmastoVm.roomTemperatures.collectAsState()
    // Building-systems telemetry for the showcase (heat pump + MVHR).
    val heatPump by ilmastoVm.heatPump.collectAsState()
    val ventilation by ilmastoVm.ventilation.collectAsState()
    // Underfloor-heating loop demand (0..1 per circuit) for the "Lämmitys" colouring.
    val heatingDemand by ilmastoVm.heatingDemand.collectAsState()
    val heatByCircuit = remember(heatingDemand) {
        heatIntensityByCircuit(heatingDemand.associate { it.key to it.percent })
    }
    val kotiVm: KotiViewModel = koinViewModel()
    val koti by kotiVm.uiState.collectAsState()
    // Shared shell VM for the in-viewer theme toggle + voice mic (same instance
    // the app shell uses, so toggling here flips the whole app's theme).
    val shell: fi.marmorikatu.app.shell.ShellViewModel = koinViewModel()
    val darkTheme by shell.dark.collectAsState()
    // The live repository deliberately clears Ruuvi state on disconnect so
    // normal screens cannot raise stale alerts. The presentation layer keeps a
    // separate latest-known copy for its non-alarming carousel fallback.
    var retainedRuuvi by remember { mutableStateOf<Map<String, RuuviReading>>(emptyMap()) }
    LaunchedEffect(ruuvi) {
        if (ruuvi.isNotEmpty()) retainedRuuvi = retainHouseReadings(retainedRuuvi, ruuvi)
    }
    val showcaseRuuvi = remember(retainedRuuvi, ruuvi) { retainHouseReadings(retainedRuuvi, ruuvi) }
    var nowSec by remember { mutableLongStateOf(Clock.System.now().epochSeconds) }
    LaunchedEffect(Unit) {
        while (true) {
            nowSec = Clock.System.now().epochSeconds
            delay(30_000)
        }
    }
    val facts = remember(showcaseRuuvi, ruuvi, lightsOn, nowSec) {
        val staleSensors = showcaseRuuvi.filter { (name, reading) ->
            name !in ruuvi || isHouseReadingStale(reading, nowSec)
        }.keys
        fun reading(name: String) = showcaseRuuvi[name]
        liveFacts(
            saunaC = reading(RuuviSensors.SAUNA)?.temperature,
            outdoorC = reading(RuuviSensors.OUTDOOR)?.temperature,
            kitchenCo2 = reading(RuuviSensors.AIR_QUALITY)?.co2,
            lightsOn = lightsOn,
            livingC = reading("Olohuone")?.temperature,
            kitchenC = reading("Keittiö")?.temperature,
            fireplaceC = reading("Takka")?.temperature,
            fridgeC = reading(RuuviSensors.FRIDGE)?.temperature,
            freezerC = reading(RuuviSensors.FREEZER)?.temperature,
            staleSensors = staleSensors,
        )
    }
    // Earliest on-since per area — the live signal the Energia layer colours by.
    val areaOnSince = remember(valot) {
        valot.floors.flatMap { it.areas }.associate { it.key to it.onSinceSec }
    }

    var floorMode by remember { mutableStateOf(FloorMode.All) }
    // Kiosk (presentation) opens as the textured exterior — walls + roof on, per
    // the design; the phone overlay opens as the dollhouse.
    var showWalls by remember { mutableStateOf(presentation) }
    var showRoof by remember { mutableStateOf(presentation) }
    var showFurniture by remember { mutableStateOf(true) }
    // "Lämmitys" inspection mode: reveal + colour the underfloor-heating loops.
    var showHeating by remember { mutableStateOf(false) }
    var explode by remember { mutableFloatStateOf(0f) }
    var dataLayer by remember { mutableStateOf(DataLayer.Valot) }
    var spin by remember { mutableStateOf(presentation) }
    var selectedRoom by remember { mutableStateOf<String?>(null) }
    var focus by remember { mutableStateOf<OrbitPreset?>(null) }
    var focusToken by remember { mutableIntStateOf(0) }

    // Voice/presentation requests can change while the overlay is already open.
    // When the showcase/screensaver starts, leave any focused floor or room and
    // return to the whole-house rotating overview.
    LaunchedEffect(presentation, idle) {
        spin = presentation || idle
        if (presentation || idle) {
            selectedRoom = null
            floorMode = FloorMode.All
            (load as? HouseLoad.Ready)?.let {
                focus = frameVisible(it.model, FloorMode.All, showRoof, showWalls)
                focusToken++
            }
        }
    }

    val ready = load as? HouseLoad.Ready

    fun clearRoomSelection() {
        selectedRoom = null
        ready?.let {
            focus = frameVisible(it.model, floorMode, showRoof, showWalls)
            focusToken++
        }
    }

    // Room inspection is intentionally temporary on the shared kiosk. Return
    // to the current building/floor overview even if nobody closes the panel.
    LaunchedEffect(selectedRoom) {
        if (selectedRoom == null) return@LaunchedEffect
        delay(ROOM_SELECTION_TIMEOUT_MS)
        clearRoomSelection()
    }

    // The connection manager owns this HTTP-SSE stream and anchors it at the
    // newest history id, so this collector receives only genuinely new events
    // while the 3D view is on screen. Speech stays sequential and a source pin
    // remains visible for at least a few seconds (or for the utterance length).
    val announcements: AnnouncementsRepository = koinInject()
    val platformTts: SpeechOutput = koinInject(named("platformTts"))
    val latestReady by rememberUpdatedState(ready)
    var liveAnnouncementMarker by remember { mutableStateOf<HouseMarker?>(null) }
    var liveAnnouncementId by remember { mutableLongStateOf(Long.MIN_VALUE) }
    LaunchedEffect(announcements, platformTts) {
        announcements.announcements.collect { announcement ->
            val text = announcement.text.trim()
            if (text.isEmpty()) return@collect
            val shownAt = TimeSource.Monotonic.markNow()
            liveAnnouncementId = announcement.id
            liveAnnouncementMarker = latestReady?.let { announcementMarker(announcement, it.model) }
            try {
                if (platformTts.isAvailable()) platformTts.speak(text)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // A missing/broken system voice must not cancel the SSE
                // collector; the next live announcement should still work.
            } finally {
                // Very short utterances still get a visible zoom/highlight.
                val remainingMs = (3_000L - shownAt.elapsedNow().inWholeMilliseconds).coerceAtLeast(0L)
                if (remainingMs > 0L) delay(remainingMs)
                if (liveAnnouncementId == announcement.id) liveAnnouncementMarker = null
            }
        }
    }

    fun applyFloor(mode: FloorMode) {
        floorMode = mode
        selectedRoom = null
        ready?.let { focus = frameVisible(it.model, mode, showRoof, showWalls); focusToken++ }
    }

    // Apply an external fly-to-floor request once the model is ready.
    LaunchedEffect(floorNonce, ready) {
        if (floorNonce > 0 && floorTarget != null && ready != null) applyFloor(floorTarget)
    }

    // When a live announcement pinpoints activity on a different floor while a
    // single floor is shown, switch to that floor so the event is visible.
    LaunchedEffect(liveAnnouncementMarker) {
        val group = liveAnnouncementMarker?.group ?: return@LaunchedEffect
        val floor = floorModeForGroup(group)
        if (floorMode != FloorMode.All && floor != FloorMode.All && floor != floorMode) applyFloor(floor)
    }

    // Room tint per layer. Valot uses the live fixture rings; Energia adds an
    // amber room wash that deepens with how long its lights have been on.
    fun roomTintFor(room: RoomPatch): Color? {
        val keys = HouseLightMap.roomToAreas[room.name] ?: return null
        return when (dataLayer) {
            // Valot needs no flat wash — the surfaces are warmed by the live light
            // output itself (see the rasterizer), which reads far more naturally.
            DataLayer.Valot -> null
            DataLayer.Energia -> {
                val since = keys.mapNotNull { areaOnSince[it] }.minOrNull()
                    ?: return colors.statusIdle.copy(alpha = 0.08f)
                val mins = ((nowSec - since) / 60L).coerceAtLeast(0L)
                val frac = (mins / 240f).coerceIn(0f, 1f)
                colors.warm.copy(alpha = 0.16f + 0.36f * frac)
            }
        }
    }

    // Back steps out one layer at a time: room → floor → whole house → close
    // (which returns to the home view). Only the top level dismisses the overlay.
    BackHandler(enabled = true) {
        when {
            selectedRoom != null -> clearRoomSelection()
            floorMode != FloorMode.All -> applyFloor(FloorMode.All)
            else -> onDismiss()
        }
    }

    Box(Modifier.fillMaxSize().background(colors.appBg)) {
        Column(
            Modifier.fillMaxSize()
                // The tablet shell already applies safeDrawingPadding around
                // its rail, header, and content. Applying the global status-bar
                // inset again here moved the complete 3D HUD down whenever the
                // iPad rotated or changed split-view size.
                .then(if (embedded) Modifier else Modifier.statusBarsPadding()),
        ) {
            // The standalone phone overlay owns its header. In tablet kiosk
            // mode the normal app header and navigation rail stay mounted.
            if (!embedded) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = MkSpacing.pagePad, end = MkSpacing.pagePad, top = MkSpacing.x3, bottom = MkSpacing.x2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Talo", style = type.title, color = colors.inkHi)
                        Text(
                            "3D-näkymä · ${lightsOnLabel(lightsOn)}",
                            style = type.caption.copy(fontFamily = type.mono),
                            color = colors.inkLo,
                        )
                    }
                    // Quick dark/light toggle (the night lighting only shows in dark)
                    // and a voice mic, mirroring the main nav.
                    MkIconButton(
                        icon = if (darkTheme) MkIcons.Sun else MkIcons.Moon,
                        onClick = { shell.toggleTheme() },
                        label = "Teema",
                        round = true,
                    )
                    Spacer(Modifier.width(MkSpacing.x2))
                    MkIconButton(icon = MkIcons.Microphone, onClick = { shell.onMic() }, label = "Puhu", round = true)
                    Spacer(Modifier.width(MkSpacing.x2))
                    MkIconButton(icon = MkIcons.X, onClick = onDismiss, label = "Sulje", round = true)
                }
            }

            // ---- 3D stage ----
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.radialGradient(
                            colors = listOf(colors.accent.copy(alpha = 0.09f), Color.Transparent),
                            radius = 900f,
                        ),
                    ),
                )
                when (val s = load) {
                    is HouseLoad.Ready -> {
                        val initialFocus = remember(s.model) { frameVisible(s.model, FloorMode.All, false, false) }
                        val selectedGroup = selectedRoom?.let { name -> s.model.rooms.firstOrNull { it.name == name }?.group }
                        val pinnedAlerts = remember(koti.attention, facts, s.model) {
                            (activeAlertMarkers(koti.attention, s.model) + facts.filter { it.kind == MarkerKind.Alert })
                                .distinctBy { it.label }
                        }
                        // Basement + 2nd-floor pins (no Ruuvi there): PLC room temps
                        // anchored to the model's real room centres.
                        val upstairs = remember(roomTemps, s.model) {
                            roomTempFacts(roomTemps.map { it.key to it.celsius }) { name ->
                                s.model.rooms.firstOrNull { it.name == name }?.center
                            }
                        }
                        // Building systems: heat pump + MVHR (technical room) and the
                        // electricity main (carport at the back).
                        val elecLabel = koti.kpis.firstOrNull { it.key == "sahko" }
                            ?.takeIf { it.value != "Ei tietoa" }
                            ?.let { "${it.value} ${it.unit.orEmpty()}".trim() }
                        val tech = remember(heatPump, ventilation, elecLabel, s.model) {
                            techFacts(
                                heatPumpAvailable = heatPump.available,
                                heatPumpPowerKw = heatPump.powerKw,
                                heatPumpSupplyC = heatPump.supplyC,
                                ventSupplyC = ventilation.supplyC,
                                electricityLabel = elecLabel,
                            ) { name -> s.model.rooms.firstOrNull { it.name == name }?.center }
                        }
                        HouseView3d(
                            model = s.model,
                            presets = s.presets,
                            floorMode = floorMode,
                            showRoof = showRoof,
                            showWalls = showWalls,
                            showFurniture = showFurniture,
                            showHeating = showHeating,
                            heatByCircuit = heatByCircuit,
                            explode = explode,
                            selectedRoom = selectedRoom,
                            focus = focus ?: initialFocus,
                            focusTier = cameraExplodeTier(floorMode, showRoof, selectedGroup),
                            focusToken = focusToken,
                            lightOnAreas = onAreas,
                            roomTint = ::roomTintFor,
                            // Alerts never enter the rotating reel: their source
                            // pin stays present until the live condition clears.
                            markers = pinnedAlerts + listOfNotNull(liveAnnouncementMarker),
                            facts = (facts + upstairs + tech).filter { it.kind != MarkerKind.Alert },
                            autoSpin = spin,
                            infomercial = presentation,
                            accent = colors.accent,
                            glow = colors.warm,
                            onRoomTap = { name ->
                                // In the screensaver a tap should only wake the kiosk
                                // (handled by the shell's interaction observer), not
                                // pick a room.
                                if (!idle) {
                                    selectedRoom = name
                                    // Lock the floor filter to the room's floor so it
                                    // doesn't fall back to the whole house when the
                                    // room selection later clears (e.g. picked from a
                                    // showcase floor tour).
                                    s.model.rooms.firstOrNull { it.name == name }?.group?.let {
                                        floorMode = floorModeForGroup(it)
                                    }
                                    s.presets.rooms[name]?.let { focus = comfortableRoomFocus(it); focusToken++ }
                                }
                            },
                        )
                    }
                    is HouseLoad.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(s.message, style = type.body, color = colors.inkLo)
                    }
                    HouseLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Ladataan mallia…", style = type.body.copy(fontFamily = type.mono), color = colors.inkLo)
                    }
                }

                if (idle) {
                    // Screensaver chrome (design `mk-house-3d` idle): a clean
                    // title block top-left and the wall clock + prompt top-right,
                    // over the full-bleed rotating house — no controls or rail.
                    Column(
                        modifier = Modifier.align(Alignment.TopStart).padding(start = MkSpacing.x5, top = MkSpacing.x4),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("Talo", style = type.title, color = colors.inkHi)
                        Text(
                            "Koko talo · ${lightsOnLabel(lightsOn)}",
                            style = type.caption.copy(fontFamily = type.mono),
                            color = colors.inkLo,
                        )
                        Text(
                            "Napauta huonetta · hehku = valot päällä",
                            style = type.caption.copy(fontFamily = type.mono, fontSize = 9.5.sp),
                            color = colors.inkLo,
                        )
                    }
                    Column(
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = MkSpacing.x5, top = MkSpacing.x4),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(rememberWallClock(), style = type.readout(40), color = colors.inkHi)
                        Text(
                            "KOSKETA JATKAAKSESI",
                            style = type.caption.copy(fontFamily = type.mono, fontSize = 10.sp, letterSpacing = 0.08.em),
                            color = colors.inkLo,
                        )
                    }
                } else {
                    Text(
                        text = if (dataLayer == DataLayer.Energia) "ENERGIA · käyttöaika" else "VALOT · elävä tila",
                        style = type.caption.copy(fontFamily = type.mono, fontSize = 9.5.sp),
                        color = colors.inkLo,
                        modifier = Modifier.align(Alignment.TopStart).padding(start = MkSpacing.x4, top = MkSpacing.x2),
                    )
                }

                selectedRoom?.let { room ->
                    RoomPanel(
                        room = room,
                        dataLayer = dataLayer,
                        nowSec = nowSec,
                        valotVm = valotVm,
                        onClose = {
                            clearRoomSelection()
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(MkSpacing.x3),
                    )
                }
            }

            // ---- Controls ---- (hidden in the idle screensaver)
            if (!idle) {
            Column(
                Modifier.fillMaxWidth().background(colors.surfaceInset)
                    .then(if (embedded) Modifier else Modifier.navigationBarsPadding())
                    .padding(horizontal = MkSpacing.pagePad, vertical = MkSpacing.x3),
                verticalArrangement = Arrangement.spacedBy(MkSpacing.x2),
            ) {
                val onRoof = {
                    showRoof = !showRoof
                    if (selectedRoom == null) ready?.let {
                        focus = frameVisible(it.model, floorMode, showRoof, showWalls); focusToken++
                    }
                    Unit
                }
                val onWalls = {
                    showWalls = !showWalls
                    if (selectedRoom == null) ready?.let {
                        focus = frameVisible(it.model, floorMode, showRoof, showWalls); focusToken++
                    }
                    Unit
                }
                val sliderColors = SliderDefaults.colors(
                    thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.track,
                )
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    when (houseControlLayout(maxWidth.value, embedded)) {
                        HouseControlLayout.WideSingleRow -> {
                            // Genuine wide kiosk: keep the design's single control row.
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2),
                            ) {
                                FloorMode.entries.forEach { mode ->
                                    SegmentButton(mode.label, floorMode == mode, Modifier.weight(1.1f)) { applyFloor(mode) }
                                }
                                Spacer(Modifier.width(MkSpacing.x2))
                                Chip("Katto", active = showRoof) { onRoof() }
                                Chip("Seinät", active = showWalls) { onWalls() }
                                Chip("Kalusteet", active = showFurniture) { showFurniture = !showFurniture }
                                Chip("Lämmitys", active = showHeating) { showHeating = !showHeating }
                                Chip("Pyöritä", active = spin) { spin = !spin }
                                Spacer(Modifier.width(MkSpacing.x2))
                                Text("KERROSVÄLI", style = type.caption.copy(fontFamily = type.mono, fontSize = 9.5.sp), color = colors.inkLo)
                                Slider(explode, { explode = it }, valueRange = 0f..3f, colors = sliderColors, modifier = Modifier.weight(2f))
                            }
                        }
                        HouseControlLayout.CompactStacked -> {
                            // A landscape phone can be routed through the embedded
                            // tablet shell, but it does not have tablet width. Stack
                            // controls and let chips scroll instead of wrapping text.
                            Column(verticalArrangement = Arrangement.spacedBy(MkSpacing.x2)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2),
                                ) {
                                    FloorMode.entries.forEach { mode ->
                                        SegmentButton(mode.label, floorMode == mode, Modifier.weight(1f)) { applyFloor(mode) }
                                    }
                                }
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2),
                                ) {
                                    Chip("Katto", active = showRoof) { onRoof() }
                                    Chip("Seinät", active = showWalls) { onWalls() }
                                    Chip("Kalusteet", active = showFurniture) { showFurniture = !showFurniture }
                                    Chip("Lämmitys", active = showHeating) { showHeating = !showHeating }
                                    Chip("Pyöritä", active = spin) { spin = !spin }
                                }
                                // The Kerrosväli (explode) slider is a kiosk/tablet
                                // affordance; on the phone overlay it isn't useful, so
                                // it's hidden there (embedded == false).
                                if (embedded) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("KERROSVÄLI", style = type.caption.copy(fontFamily = type.mono, fontSize = 9.5.sp), color = colors.inkLo, modifier = Modifier.width(84.dp))
                                        Slider(explode, { explode = it }, valueRange = 0f..3f, colors = sliderColors, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }

        // The idle screensaver is simply the slowly-rotating house (no clock
        // takeover — not in the design); any touch resets idle via the shell's
        // interaction observer.
    }
}

private const val ROOM_SELECTION_TIMEOUT_MS = 15_000L

private fun Int.pad2(): String = if (this < 10) "0$this" else "$this"

internal fun lightsOnLabel(count: Int): String =
    if (count == 1) "1 valo päällä" else "$count valoa päällä"

@Composable
private fun SegmentButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MkTheme.colors
    val type = MkTheme.type
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MkRadius.sm))
            .background(if (selected) c.accentDim else c.track)
            .border(1.dp, if (selected) c.accentBorder else c.borderSubtle, RoundedCornerShape(MkRadius.sm))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = type.label,
            color = if (selected) c.accent else c.inkMid,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Chip(
    label: String,
    active: Boolean = false,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    val c = MkTheme.colors
    val type = MkTheme.type
    val fg = when {
        !enabled -> c.inkLo
        active -> c.accent
        else -> c.inkMid
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(MkRadius.round))
            .background(if (active) c.accentDim else c.track)
            .border(1.dp, if (active) c.accentBorder else c.borderSubtle, RoundedCornerShape(MkRadius.round))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = MkSpacing.x3, vertical = MkSpacing.x2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        Text(label, style = type.label, color = fg, maxLines = 1, softWrap = false)
        if (!enabled) Text("tulossa", style = type.caption.copy(fontFamily = type.mono, fontSize = 8.5.sp), color = c.inkLo)
    }
}

internal enum class HouseControlLayout { WideSingleRow, CompactStacked }

/** A phone in landscape may use the embedded shell; width, not shell type, decides density. */
internal fun houseControlLayout(widthDp: Float, embedded: Boolean): HouseControlLayout =
    if (embedded && widthDp >= 1_050f) HouseControlLayout.WideSingleRow
    else HouseControlLayout.CompactStacked

@Composable
private fun RoomPanel(
    room: String,
    dataLayer: DataLayer,
    nowSec: Long,
    valotVm: ValotViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MkTheme.colors
    val type = MkTheme.type
    val valot by valotVm.uiState.collectAsState()
    val commandFailed by valotVm.failure.collectAsState()
    val areaKeys = HouseLightMap.roomToAreas[room] ?: emptyList()
    val areas = remember(valot, room) { valot.floors.flatMap { it.areas }.filter { it.key in areaKeys } }
    Column(
        modifier = modifier
            .width(260.dp)
            .clip(RoundedCornerShape(MkRadius.md))
            .background(c.surfaceCard)
            .border(1.dp, c.borderSubtle, RoundedCornerShape(MkRadius.md))
            .padding(MkSpacing.x3),
        verticalArrangement = Arrangement.spacedBy(MkSpacing.x2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(HouseLightMap.roomTitle(room), style = type.label, color = c.inkHi, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(MkRadius.round)).background(c.track).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Icon(MkIcons.X, contentDescription = "Sulje", tint = c.inkMid, modifier = Modifier.size(12.dp)) }
        }
        if (dataLayer == DataLayer.Energia && !valot.loading) {
            val since = areas.mapNotNull { it.onSinceSec }.minOrNull()
            val label = if (since == null) "Ei valoja päällä" else "Päällä ${durationLabel(nowSec - since)}"
            Text(label, style = type.caption.copy(fontFamily = type.mono), color = c.warm)
        }
        if (commandFailed) {
            Text("Valon ohjaus epäonnistui", style = type.caption, color = c.statusAlarm)
        }
        if (valot.loading) {
            Text("Ladataan valoja…", style = type.caption, color = c.inkLo)
        } else if (areas.all { it.lights.isEmpty() }) {
            Text("Ei valaisimia tässä huoneessa.", style = type.caption, color = c.inkLo)
        } else {
            Column(
                Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                areas.forEach { area ->
                    area.lights.forEach { light ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(MkRadius.sm))
                                .background(c.surfaceInset)
                                // Optimistic: always tappable (a pending command must
                                // not block the opposite toggle, or you could turn a
                                // light on but never off).
                                .clickable { valotVm.toggle(light.id, !light.on) }
                                .padding(horizontal = MkSpacing.x2, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (light.pending) "${light.label}…" else light.label,
                                style = type.caption, color = c.inkMid, modifier = Modifier.weight(1f),
                            )
                            Box(Modifier.size(9.dp).clip(RoundedCornerShape(MkRadius.round)).background(if (light.on) c.warm else c.statusIdle))
                        }
                    }
                }
            }
        }
    }
}

private fun durationLabel(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "$h h $m min" else "$m min"
}
