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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
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
import fi.marmorikatu.app.components.MkSwitch
import fi.marmorikatu.app.format.Fmt
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
    /**
     * Bumped by the shell each time it (re-)asserts [presentation] (e.g. a voice
     * "show the house"), so the showcase can restart even after a manual interaction
     * left `presenting` false while [presentation] itself stayed true.
     */
    presentationNonce: Int = 0,
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
    // Per-room illuminance (lux) for the dark-mode room glow (keyed by presence room).
    val presence by ilmastoVm.presence.collectAsState()
    val roomIlluminance = remember(presence) { presence.mapValues { it.value.illuminance } }
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
    // Local mirror of the incoming presentation flag. The auto-showcase (spin +
    // floor tour) starts when the overlay opens, but any deliberate interaction
    // must stop it — otherwise the running tour keeps yanking the camera back to
    // the whole house and wipes a just-picked room / floor / filter.
    var presenting by remember { mutableStateOf(presentation) }
    // Re-sync on either a change of the flag itself OR a shell nonce bump, so a fresh
    // voice "show the house" restarts the reel even when `presentation` was already true.
    LaunchedEffect(presentation, presentationNonce) { presenting = presentation }
    // Phone: once a manual interaction has stopped the showcase, resume it after a
    // spell of no interaction — the attract-loop should return by itself, in both the
    // whole-house and floor views (the tablet kiosk handles its own idle in the shell,
    // so this is scoped to the non-embedded overlay). `touch()` marks activity cheaply
    // (a plain array write, no recomposition, safe to call every drag frame); the
    // watcher polls it and re-arms `presenting`.
    val lastTouch = remember { arrayOf(TimeSource.Monotonic.markNow()) }
    val touch = { lastTouch[0] = TimeSource.Monotonic.markNow() }
    if (!embedded) {
        LaunchedEffect(presenting, idle) {
            if (presenting || idle) return@LaunchedEffect
            lastTouch[0] = TimeSource.Monotonic.markNow()
            while (true) {
                delay(1_000)
                if (lastTouch[0].elapsedNow().inWholeMilliseconds >= SHOWCASE_IDLE_RESTART_MS) {
                    presenting = true
                    break
                }
            }
        }
    }
    var selectedRoom by remember { mutableStateOf<String?>(null) }
    var focus by remember { mutableStateOf<OrbitPreset?>(null) }
    var focusToken by remember { mutableIntStateOf(0) }

    // Voice/presentation requests can change while the overlay is already open.
    // When the showcase/screensaver starts, leave any focused floor or room and
    // return to the whole-house rotating overview.
    LaunchedEffect(presenting, idle) {
        // Only drive state when the showcase/screensaver turns ON. When it turns
        // off (a deliberate interaction), leave spin/floor as the interaction set
        // them — otherwise this would immediately undo e.g. the Pyöritä toggle.
        if (presenting || idle) {
            spin = true
            selectedRoom = null
            floorMode = FloorMode.All
            if (idle) {
                // The screensaver always comes up as the clean textured exterior,
                // spinning, with facts — no matter what the user last toggled (they
                // may have hidden walls, exploded the model, or stopped presenting).
                presenting = true
                showWalls = true; showRoof = true; showFurniture = true
                showHeating = false; explode = 0f
            }
            (load as? HouseLoad.Ready)?.let {
                focus = frameVisible(it.model, FloorMode.All, showRoof, showWalls, embedded)
                focusToken++
            }
        }
    }

    val ready = load as? HouseLoad.Ready

    fun clearRoomSelection() {
        selectedRoom = null
        ready?.let {
            focus = frameVisible(it.model, floorMode, showRoof, showWalls, embedded)
            focusToken++
        }
    }

    // Room inspection auto-returns only on the shared kiosk (embedded), and the
    // timer restarts on any in-panel interaction ([roomInteractionNonce], bumped by
    // the light toggles) so it can't close under someone who's actively using it. On
    // the phone the panel stays until dismissed.
    var roomInteractionNonce by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectedRoom, roomInteractionNonce) {
        if (selectedRoom == null || !embedded) return@LaunchedEffect
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
        touch(); presenting = false
        spin = false
        floorMode = mode
        selectedRoom = null
        ready?.let { focus = frameVisible(it.model, mode, showRoof, showWalls, embedded); focusToken++ }
    }

    // Lock the floor to the room's level and fly the camera to it. Shared by a room
    // tap and the swipe-through navigation so both keep the map in sync with the card.
    fun focusRoom(name: String) {
        ready?.model?.rooms?.firstOrNull { it.name == name }?.group?.let {
            floorMode = floorModeForGroup(it)
        }
        ready?.presets?.rooms?.get(name)?.let { focus = comfortableRoomFocus(it); focusToken++ }
    }

    // Apply an external fly-to-floor request once the model is ready.
    LaunchedEffect(floorNonce, ready) {
        if (floorNonce > 0 && floorTarget != null && ready != null) applyFloor(floorTarget)
    }

    // When a live announcement pinpoints activity on a different floor, follow it —
    // but ONLY during the auto-showcase and never with a room open. A user who has
    // deliberately parked on a floor (or opened a room) keeps their view; the event
    // still shows its pin, it just doesn't hijack the camera.
    LaunchedEffect(liveAnnouncementMarker) {
        if (!presenting || selectedRoom != null) return@LaunchedEffect
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
                    MkIconButton(icon = MkIcons.Microphone, onClick = { touch(); presenting = false; shell.onMic() }, label = "Puhu", round = true)
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
                        val initialFocus = remember(s.model) { frameVisible(s.model, FloorMode.All, false, false, embedded) }
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
                                heatPumpMode = heatPumpModeLabel(heatPump),
                                heatPumpPowerKw = heatPump.powerKw,
                                heatPumpSupplyC = heatPump.supplyC,
                                hotWaterC = heatPump.hotWaterC,
                                ventMode = ventOperatingModeLabel(ventilation),
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
                            roomIlluminance = roomIlluminance,
                            roomTint = ::roomTintFor,
                            // Alerts never enter the rotating reel: their source
                            // pin stays present until the live condition clears.
                            markers = pinnedAlerts + listOfNotNull(liveAnnouncementMarker),
                            facts = (facts + upstairs + tech).filter { it.kind != MarkerKind.Alert },
                            autoSpin = spin,
                            infomercial = presenting,
                            accent = colors.accent,
                            glow = colors.warm,
                            onRoomTap = { name ->
                                // In the screensaver a tap should only wake the kiosk
                                // (handled by the shell's interaction observer), not
                                // pick a room.
                                if (!idle) {
                                    // A deliberate room pick ends the auto-showcase so
                                    // its floor tour stops fighting the selection.
                                    touch(); presenting = false
                                    spin = false
                                    selectedRoom = name
                                    // Lock the floor filter to the room's floor (so it
                                    // doesn't fall back to the whole house when the
                                    // selection later clears) and fly to the room.
                                    focusRoom(name)
                                }
                            },
                            // Any manual orbit/pan/zoom ends the auto-showcase; in the
                            // screensaver the same gesture wakes the kiosk instead.
                            onUserInteract = { grabbedFloor ->
                                if (idle) {
                                    onExitIdle()
                                } else {
                                    touch(); presenting = false; spin = false
                                    // Keep the floor the showcase was presenting (the tour
                                    // toured it internally while our floorMode stayed Koko
                                    // talo) so grabbing control doesn't snap to the whole
                                    // house — and the carport roof stays hidden on a floor.
                                    floorMode = grabbedFloor
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
                    val climate = HouseLightMap.roomClimate[room]
                    val roomTempC = climate?.let { cl ->
                        cl.ruuvi?.let { showcaseRuuvi[it]?.temperature }
                            ?: cl.tempKey?.let { key -> roomTemps.firstOrNull { it.key == key }?.celsius }
                    }
                    val roomHeatingPct = if (room in HouseLightMap.manualHeatRooms) {
                        100 // manual, no thermostat — always-on loop
                    } else {
                        climate?.heatingKey?.let { key ->
                            heatingDemand.firstOrNull { it.key == key }?.percent
                        }
                    }
                    // Rooms on this floor, in model order, for swipe-through navigation.
                    val navRooms = remember(room, ready) {
                        val g = ready?.model?.rooms?.firstOrNull { it.name == room }?.group
                        ready?.model?.rooms?.filter { it.group == g }?.map { it.name }?.distinct()
                            ?.takeIf { it.isNotEmpty() } ?: listOf(room)
                    }
                    val navIdx = navRooms.indexOf(room).coerceAtLeast(0)
                    SwipeableRoomCard(
                        onNext = { navRooms[(navIdx + 1) % navRooms.size].let { selectedRoom = it; focusRoom(it) }; roomInteractionNonce++ },
                        onPrev = { navRooms[(navIdx - 1 + navRooms.size) % navRooms.size].let { selectedRoom = it; focusRoom(it) }; roomInteractionNonce++ },
                        onDismiss = { clearRoomSelection() },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(MkSpacing.x3),
                    ) {
                        RoomPanel(
                            room = room,
                            dataLayer = dataLayer,
                            nowSec = nowSec,
                            valotVm = valotVm,
                            tempC = roomTempC,
                            targetC = heatPump.indoorTargetC,
                            heatingPct = roomHeatingPct,
                            onClose = { clearRoomSelection() },
                            onInteract = { roomInteractionNonce++ },
                        )
                    }
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
                // Every control-rail toggle also ends the auto-showcase so the tour
                // stops driving the camera once the user takes manual control.
                val onRoof = {
                    touch(); presenting = false
                    showRoof = !showRoof
                    if (selectedRoom == null) ready?.let {
                        focus = frameVisible(it.model, floorMode, showRoof, showWalls, embedded); focusToken++
                    }
                    Unit
                }
                val onWalls = {
                    touch(); presenting = false
                    showWalls = !showWalls
                    if (selectedRoom == null) ready?.let {
                        focus = frameVisible(it.model, floorMode, showRoof, showWalls, embedded); focusToken++
                    }
                    Unit
                }
                val onFurniture = { touch(); presenting = false; showFurniture = !showFurniture; Unit }
                val onHeating = { touch(); presenting = false; showHeating = !showHeating; Unit }
                val onSpin = { touch(); presenting = false; spin = !spin; Unit }
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
                                Chip("Kalusteet", active = showFurniture) { onFurniture() }
                                Chip("Lämmitys", active = showHeating) { onHeating() }
                                Chip("Pyöritä", active = spin) { onSpin() }
                                Spacer(Modifier.width(MkSpacing.x2))
                                Text("KERROSVÄLI", style = type.caption.copy(fontFamily = type.mono, fontSize = 9.5.sp), color = colors.inkLo)
                                Slider(explode, { touch(); presenting = false; explode = it }, valueRange = 0f..3f, colors = sliderColors, modifier = Modifier.weight(2f))
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
                                    Chip("Kalusteet", active = showFurniture) { onFurniture() }
                                    Chip("Lämmitys", active = showHeating) { onHeating() }
                                    Chip("Pyöritä", active = spin) { onSpin() }
                                }
                                // The Kerrosväli (explode) slider is a kiosk/tablet
                                // affordance; on the phone overlay it isn't useful, so
                                // it's hidden there (embedded == false).
                                if (embedded) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("KERROSVÄLI", style = type.caption.copy(fontFamily = type.mono, fontSize = 9.5.sp), color = colors.inkLo, modifier = Modifier.width(84.dp))
                                        Slider(explode, { touch(); presenting = false; explode = it }, valueRange = 0f..3f, colors = sliderColors, modifier = Modifier.weight(1f))
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

/** Phone: resume the auto-showcase this long after the last manual interaction. */
private const val SHOWCASE_IDLE_RESTART_MS = 20_000L

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
    // The iPad kiosk's content column (nav rail subtracted) sits around ~880–900dp,
    // below the old 1050 gate, so it fell back to the 3-row stacked layout. Lower the
    // gate so the tablet uses the single control row the design intends.
    if (embedded && widthDp >= 700f) HouseControlLayout.WideSingleRow
    else HouseControlLayout.CompactStacked

/**
 * Wraps the room detail card so it follows the finger: a horizontal fling swaps to the
 * next/previous room on the floor (the card slides out and the new one slides in), a
 * downward fling dismisses it, and anything short springs back. Callbacks are read fresh
 * each frame so the gesture always acts on the current room.
 */
@Composable
private fun SwipeableRoomCard(
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val latestNext by rememberUpdatedState(onNext)
    val latestPrev by rememberUpdatedState(onPrev)
    val latestDismiss by rememberUpdatedState(onDismiss)
    Box(
        modifier
            .offset { IntOffset(offset.value.x.roundToInt(), offset.value.y.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch { offset.snapTo(offset.value + dragAmount) }
                    },
                    onDragEnd = {
                        val end = offset.value
                        scope.launch {
                            when {
                                // Downward fling → dismiss (slide off the bottom first).
                                end.y > SWIPE_DISMISS_PX && end.y > abs(end.x) -> {
                                    offset.animateTo(Offset(end.x, 900f), tween(200)); latestDismiss()
                                }
                                // Left fling → next room (out left, in from the right).
                                end.x < -SWIPE_NAV_PX -> {
                                    offset.animateTo(Offset(-700f, end.y), tween(160)); latestNext()
                                    offset.snapTo(Offset(700f, 0f)); offset.animateTo(Offset.Zero, tween(220))
                                }
                                // Right fling → previous room (out right, in from the left).
                                end.x > SWIPE_NAV_PX -> {
                                    offset.animateTo(Offset(700f, end.y), tween(160)); latestPrev()
                                    offset.snapTo(Offset(-700f, 0f)); offset.animateTo(Offset.Zero, tween(220))
                                }
                                else -> offset.animateTo(Offset.Zero, spring())
                            }
                        }
                    },
                )
            },
    ) { content() }
}

private const val SWIPE_NAV_PX = 90f
private const val SWIPE_DISMISS_PX = 130f

@Composable
private fun RoomPanel(
    room: String,
    dataLayer: DataLayer,
    nowSec: Long,
    valotVm: ValotViewModel,
    tempC: Double?,
    targetC: Double?,
    heatingPct: Int?,
    onClose: () -> Unit,
    onInteract: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = MkTheme.colors
    val type = MkTheme.type
    val valot by valotVm.uiState.collectAsState()
    val commandFailed by valotVm.failure.collectAsState()
    val areaKeys = HouseLightMap.roomToAreas[room] ?: emptyList()
    val areas = remember(valot, room) { valot.floors.flatMap { it.areas }.filter { it.key in areaKeys } }
    val lights = remember(areas) { areas.flatMap { it.lights } }
    val onCount = lights.count { it.on }
    Column(
        modifier = modifier
            .width(320.dp)
            .clip(RoundedCornerShape(MkRadius.md))
            .background(c.surfaceCard)
            .border(1.dp, c.borderSubtle, RoundedCornerShape(MkRadius.md))
            .padding(MkSpacing.x4),
        verticalArrangement = Arrangement.spacedBy(MkSpacing.x3),
    ) {
        // Header: room title + close
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(HouseLightMap.roomTitle(room), style = type.title, color = c.inkHi, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(MkRadius.round)).background(c.track).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Icon(MkIcons.X, contentDescription = "Sulje", tint = c.inkMid, modifier = Modifier.size(14.dp)) }
        }
        // Room + temperature
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MkIcons.House, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(HouseLightMap.roomTitle(room), style = type.body, color = c.inkMid, modifier = Modifier.weight(1f))
            Text(tempC?.let { "${Fmt.comma(it, 1)}°" } ?: "–", style = type.readout(30), color = c.inkHi)
        }
        // Target + floor-heating demand
        if (targetC != null || heatingPct != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    targetC?.let { "tavoite ${Fmt.comma(it, 1)}°" }.orEmpty(),
                    style = type.caption.copy(fontFamily = type.mono), color = c.inkLo, modifier = Modifier.weight(1f),
                )
                if (heatingPct != null) {
                    Text("Lattialämmitys ", style = type.caption.copy(fontFamily = type.mono), color = c.inkLo)
                    Text("$heatingPct %", style = type.caption.copy(fontFamily = type.mono, fontWeight = FontWeight.Bold), color = c.warm)
                }
            }
        }
        if (heatingPct != null) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(MkRadius.round)).background(c.track)) {
                Box(
                    Modifier.fillMaxWidth((heatingPct.coerceIn(0, 100)) / 100f).fillMaxHeight()
                        .clip(RoundedCornerShape(MkRadius.round)).background(c.warm),
                )
            }
        }
        if (commandFailed) {
            Text("Valon ohjaus epäonnistui", style = type.caption, color = c.statusAlarm)
        }
        // Lights section header + divider
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("VALOT $onCount / ${lights.size}", style = type.caption.copy(fontFamily = type.mono), color = c.inkLo)
            Box(Modifier.weight(1f).height(1.dp).background(c.borderSubtle))
        }
        if (valot.loading) {
            Text("Ladataan valoja…", style = type.caption, color = c.inkLo)
        } else if (lights.isEmpty()) {
            Text("Ei valaisimia tässä huoneessa.", style = type.caption, color = c.inkLo)
        } else {
            Column(
                Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                lights.forEach { light ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (light.pending) "${light.label}…" else light.label,
                            style = type.body, color = c.inkHi, modifier = Modifier.weight(1f),
                        )
                        // Optimistic toggle: always enabled so a pending command can't
                        // block turning the light back the other way.
                        MkSwitch(checked = light.on, onChange = { onInteract(); valotVm.toggle(light.id, it) })
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
