package fi.marmorikatu.app.house3d

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import fi.marmorikatu.app.components.rememberFrameMillis
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkTheme
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * The interactive orbit view: owns the camera, handles drag/pinch/tap, draws the
 * model via [drawHouse], and floats marker labels (event pings + the rotating
 * infomercial fact) over it. When [autoSpin] is set the camera turns slowly on
 * its own — a user gesture pauses that for 25 s, then it resumes (kiosk feel).
 * The camera keeps its own yaw across focus moves so transitions stay continuous.
 */
@Composable
fun HouseView3d(
    model: HouseModel,
    presets: CameraPresets,
    floorMode: FloorMode,
    showRoof: Boolean,
    showWalls: Boolean,
    showFurniture: Boolean,
    showHeating: Boolean,
    heatByCircuit: Map<String, Float>,
    explode: Float,
    selectedRoom: String?,
    focus: OrbitPreset?,
    focusTier: Float,
    focusToken: Int,
    lightOnAreas: Set<String>,
    roomTint: (RoomPatch) -> Color?,
    markers: List<HouseMarker>,
    facts: List<HouseMarker>,
    autoSpin: Boolean,
    infomercial: Boolean,
    accent: Color,
    glow: Color,
    onRoomTap: (String) -> Unit,
    onUserInteract: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Opposite side of the house by default: over the terrace / living-room
    // corner (+x, +z), looking back at the interior.
    var theta by remember { mutableStateOf(focus?.theta ?: WHOLE_HOUSE_THETA) }
    var phi by remember { mutableStateOf(focus?.phi ?: 0.95f) }
    var radius by remember { mutableStateOf(focus?.radius ?: 26f) }
    var target by remember { mutableStateOf(focus?.target ?: model.center) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val explodeAnim by animateFloatAsState(explode, label = "explode")
    // The focus tier is camera state, just like its target/radius/phi. Keeping it
    // in the same animation writer is important when the source is on a different
    // floor: with an exploded house, applying the selected floor's tier made an
    // upstairs alert zoom several metres below its pin.
    var targetTier by remember { mutableFloatStateOf(focusTier) }
    val cameraTarget = Vec3(target.x, target.y + targetTier * explodeAnim, target.z)
    val roomOverlayCache = remember(model) { RoomOverlayRenderCache() }
    val lightProjector = remember { CameraProjector() }
    val markerProjector = remember { CameraProjector() }
    val latestTarget by rememberUpdatedState(cameraTarget)
    val latestTheta by rememberUpdatedState(theta)
    val latestPhi by rememberUpdatedState(phi)
    val latestRadius by rememberUpdatedState(radius)
    val latestExplode by rememberUpdatedState(explodeAnim)
    val latestOnRoomTap by rememberUpdatedState(onRoomTap)
    val latestOnUserInteract by rememberUpdatedState(onUserInteract)
    var interactionTick by remember { mutableIntStateOf(0) }
    var spinPaused by remember { mutableStateOf(false) }
    val latestUserFloor by rememberUpdatedState(floorMode)

    // Yaw is no longer hard-snapped on floor change; the camera tween eases `theta`
    // to each preset's target yaw (street-up for a floor, terrace corner for the
    // whole house) so the move is one coordinated glide instead of an instant twist.
    // [spinLock] suppresses auto-rotation while a tween runs or the showcase is
    // parked on a single-floor page — both want a still camera.
    val tweening = remember { mutableStateOf(false) }
    val spinLock = remember { mutableStateOf(false) }

    // Auto-rotate, pausing 25 s after any user gesture.
    LaunchedEffect(interactionTick) {
        if (interactionTick == 0) return@LaunchedEffect
        spinPaused = true; delay(25_000); spinPaused = false
    }
    LaunchedEffect(autoSpin) {
        if (!autoSpin) return@LaunchedEffect
        var last = 0L
        var pendingNanos = 0L
        while (true) {
            val now = withFrameNanos { it }
            // Only the whole-house view spins; a locked floorplan / tween must stay put.
            if (last != 0L && !spinPaused && !spinLock.value && latestUserFloor == FloorMode.All) {
                // ProMotion can otherwise make the software renderer alternate
                // between 120 Hz and missed frames. A steady 60 Hz workload is
                // visibly smoother, and a long suspended frame cannot jump the camera.
                pendingNanos += (now - last).coerceAtMost(50_000_000L)
                if (pendingNanos >= AUTO_SPIN_STEP_NANOS) {
                    // Preserve the fractional frame remainder. Resetting it to
                    // zero made 120 Hz iPads alternate between short and long
                    // camera steps, perceived as a left/right judder.
                    val steps = pendingNanos / AUTO_SPIN_STEP_NANOS
                    theta += steps * AUTO_SPIN_STEP_NANOS / 1_000_000_000f * AUTO_SPIN_RADIANS_PER_SECOND
                    pendingNanos -= steps * AUTO_SPIN_STEP_NANOS
                }
            } else {
                // Not spinning (paused, locked, or on a floor): drop the remainder so
                // re-entering the whole-house view doesn't apply a stale accumulated step.
                pendingNanos = 0L
            }
            last = now
        }
    }

    // Infomercial: tour the building in physical order and show up to three
    // facts belonging to the active floor. Facts currently happen to be
    // concentrated downstairs, so a fact-only pager used to remain on Krs1
    // forever and never showcase the basement or upstairs.
    var showcasePageIndex by remember { mutableIntStateOf(0) }
    val factFade = remember { Animatable(1f) }
    val visibleFacts = remember(infomercial, facts, floorMode) {
        if (infomercial) facts.filter { it.group in floorMode.groups } else emptyList()
    }
    val showcasePages = remember(visibleFacts, floorMode) {
        buildShowcasePages(visibleFacts, floorMode)
    }
    // Read the freshest page list inside the loop so a live fact going stale/fresh
    // (which changes the page COUNT) doesn't restart the tour at page 0. The effect
    // only restarts when the showcase starts/stops or the user's floor changes.
    val latestPages by rememberUpdatedState(showcasePages)
    LaunchedEffect(infomercial, floorMode) {
        showcasePageIndex = 0
        factFade.snapTo(1f)
        if (!infomercial) return@LaunchedEffect
        while (true) {
            delay(4_800)
            val n = latestPages.size
            if (n <= 1) continue
            factFade.animateTo(0f, tween(420))
            showcasePageIndex = (showcasePageIndex + 1) % n
            factFade.animateTo(1f, tween(520))
        }
    }
    val showcasePage = showcasePages.getOrNull(
        if (showcasePages.isEmpty()) 0 else showcasePageIndex % showcasePages.size,
    )
    val shownFacts = showcasePage?.facts.orEmpty()

    // Alerts are not carousel entries: every active source pin stays on screen
    // until live state removes it. If several are active, the camera visits each
    // one while all pins remain visible. With no alert, it frames the current
    // page of facts (see SHOWCASE_FACTS_PER_PAGE) so the house movement and the
    // infographic tell one story.
    val announcementMarkers = remember(markers) { markers.filter { it.kind == MarkerKind.Announcement } }
    val pinnedAlerts = remember(markers) { markers.filter { it.kind == MarkerKind.Alert } }
    val alertKey = remember(pinnedAlerts) {
        pinnedAlerts.joinToString("|") { "${it.label}@${it.pos.x},${it.pos.y},${it.pos.z}" }
    }
    var alertFocusIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(alertKey) {
        alertFocusIndex = 0
        if (pinnedAlerts.size <= 1) return@LaunchedEffect
        while (true) {
            delay(5_800)
            alertFocusIndex = (alertFocusIndex + 1) % pinnedAlerts.size
        }
    }
    // A just-arrived spoken announcement temporarily takes camera priority over
    // the persistent alert tour. Once it clears, unresolved alerts resume.
    val automaticMarkers = remember(
        announcementMarkers, pinnedAlerts, alertFocusIndex, selectedRoom, spinPaused, shownFacts,
    ) {
        when {
            // A just-arrived spoken announcement is intentional and always wins.
            announcementMarkers.isNotEmpty() -> listOf(announcementMarkers.last())
            // Manual interaction owns the camera temporarily: while a room is open or
            // within 25 s of a gesture, neither a standing alert nor the ambient fact
            // reel may pull the camera (their pins still show via `markers`).
            selectedRoom != null || spinPaused -> emptyList()
            pinnedAlerts.isNotEmpty() -> listOf(pinnedAlerts[alertFocusIndex.coerceIn(0, pinnedAlerts.lastIndex)])
            else -> shownFacts
        }
    }
    val selectedRoomGroup = remember(selectedRoom, model) {
        selectedRoom?.let { name -> model.rooms.firstOrNull { it.name == name }?.group }
    }
    // A focused source isolates its physical floor without changing the user's
    // segmented-control selection. Multiple sources spanning floors fall back
    // to the complete house, and the previous view returns when focus clears.
    // Manual interaction pauses automatic camera ownership. Otherwise an empty
    // floor page still isolates and frames that floor, so floors with no active
    // measurements remain part of the ordered building tour.
    val showcaseFloorMode = showcasePage?.floorMode?.takeIf {
        infomercial && selectedRoom == null && !spinPaused
    }
    val renderedFloorMode = remember(floorMode, showcaseFloorMode, automaticMarkers, selectedRoomGroup) {
        focusedFloorMode(
            selected = showcaseFloorMode ?: floorMode,
            focusedMarkers = automaticMarkers,
            selectedRoomGroup = selectedRoomGroup,
        )
    }
    val latestFloorMode by rememberUpdatedState(renderedFloorMode)
    // Suppress auto-rotation while a focus tween runs or the showcase has parked on a
    // single floor page — the autospin loop (above) reads this each frame.
    SideEffect { spinLock.value = tweening.value || renderedFloorMode != FloorMode.All }
    // Text/value updates at the same physical source must not restart a 900 ms
    // camera tween. Only source position/floor and carousel membership matter.
    val automaticFocusKey = remember(automaticMarkers) { markerCameraKey(automaticMarkers) }
    val showcaseDestination = remember(
        infomercial, renderedFloorMode, automaticMarkers, selectedRoom, spinPaused,
        pinnedAlerts, announcementMarkers, model, showRoof, showWalls,
    ) {
        if (infomercial && automaticMarkers.isEmpty() && selectedRoom == null && !spinPaused &&
            pinnedAlerts.isEmpty() && announcementMarkers.isEmpty()
        ) {
            CameraDestination(
                orbit = frameVisible(model, renderedFloorMode, showRoof, showWalls),
                tier = cameraExplodeTier(renderedFloorMode, showRoof, selectedGroup = null),
            )
        } else {
            null
        }
    }
    var announcementReturnFocus by remember { mutableStateOf<CameraDestination?>(null) }
    var announcementReturnInteraction by remember { mutableIntStateOf(0) }
    var appliedExplicitFocusToken by remember { mutableIntStateOf(focusToken) }
    // Every camera target now goes through one writer. Previously the explicit
    // room/floor tween and infographic tween could run concurrently and fight
    // over target/radius/phi on iOS, producing a visible side-to-side shake.
    LaunchedEffect(focusToken, focusTier, infomercial, automaticFocusKey, showcaseDestination) {
        val hasNewExplicitFocus = focusToken != appliedExplicitFocusToken
        if (hasNewExplicitFocus) appliedExplicitFocusToken = focusToken
        if (announcementMarkers.isNotEmpty() && announcementReturnFocus == null) {
            // Preserve the orbit to return to after the temporary announcement zoom.
            // If a focus tween is mid-flight, the live pose is transient — capture its
            // intended destination ([focus]) instead so we don't restore a half-move.
            val returnOrbit = if (tweening.value && focus != null) focus else OrbitPreset(target, radius, phi)
            announcementReturnFocus = CameraDestination(returnOrbit, targetTier)
            announcementReturnInteraction = interactionTick
        }
        if (hasNewExplicitFocus && announcementMarkers.isNotEmpty() && focus != null) {
            // A room/floor command issued while speech is active becomes the
            // return destination; do not jump back to the stale pre-command view.
            announcementReturnFocus = CameraDestination(focus, focusTier)
            announcementReturnInteraction = interactionTick
        }
        val returnFocus = announcementReturnFocus
        val canRestoreManualView = announcementMarkers.isEmpty() &&
            pinnedAlerts.isEmpty() && !infomercial &&
            interactionTick == announcementReturnInteraction
        val needsTierUpdate = automaticMarkers.isEmpty() && targetTier != focusTier
        if (announcementMarkers.isEmpty() && returnFocus != null) {
            announcementReturnFocus = null
        }
        if (!hasNewExplicitFocus && !infomercial && pinnedAlerts.isEmpty() &&
            announcementMarkers.isEmpty() && !canRestoreManualView && !needsTierUpdate
        ) {
            return@LaunchedEffect
        }
        val destination = focus?.takeIf { hasNewExplicitFocus }?.let { CameraDestination(it, focusTier) }
            ?: markerFocus(automaticMarkers)?.let {
                CameraDestination(it, markerCameraTier(automaticMarkers))
            }
            ?: showcaseDestination
            ?: returnFocus?.takeIf { canRestoreManualView }
            ?: CameraDestination(OrbitPreset(target, radius, phi), focusTier)
                .takeIf { automaticMarkers.isEmpty() && targetTier != focusTier }
            ?: return@LaunchedEffect
        val startT = target
        val startR = radius
        val startP = phi
        val startTheta = theta
        // Ease yaw only when the preset asks for a specific one; otherwise keep the
        // current yaw so most focus moves don't twist the house. Take the shortest
        // way round so a wrap-around doesn't spin the long way.
        val destTheta = destination.orbit.theta
        val startTier = targetTier
        val interactionAtStart = interactionTick
        val startNs = withFrameNanos { it }
        var progress = 0f
        tweening.value = true
        try {
            while (progress < 1f && interactionTick == interactionAtStart) {
                val now = withFrameNanos { it }
                progress = (((now - startNs) / 1_000_000L).toFloat() / 900f).coerceIn(0f, 1f)
                val eased = easeInOutQuad(progress)
                target = lerp(startT, destination.orbit.target, eased)
                radius = lerp(startR, destination.orbit.radius, eased)
                phi = lerp(startP, destination.orbit.phi, eased)
                targetTier = lerp(startTier, destination.tier, eased)
                if (destTheta != null) theta = startTheta + shortestAngleDelta(startTheta, destTheta) * eased
            }
        } finally {
            tweening.value = false
        }
    }

    val gestures = Modifier
        .pointerInput(model) {
            // One finger orbits; two fingers pan the look-at target across the
            // floorplan (+ pinch to zoom).
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var dragging = false
                var travelled = Offset.Zero
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    if (pressed == 0) break
                    val pan = event.calculatePan()
                    val zoom = event.calculateZoom()
                    // A single finger must travel past touch slop before this becomes a
                    // manipulation; below that we consume nothing so a tap-to-select can
                    // still land (a tiny jitter was previously eaten as a pan). A second
                    // finger / pinch is always a manipulation.
                    if (!dragging) {
                        travelled += pan
                        if (pressed >= 2 || zoom != 1f || travelled.getDistance() > viewConfiguration.touchSlop) {
                            dragging = true
                        } else {
                            continue
                        }
                    }
                    if (pan != Offset.Zero || zoom != 1f) {
                        interactionTick += 1
                        // Manual drag/pan/zoom means the user has taken the wheel —
                        // stop the auto-showcase so its tour stops fighting them.
                        latestOnUserInteract()
                    }
                    // A single floor reads as a floorplan: the camera angle is locked
                    // and a one-finger drag scrolls across the plan. The whole house
                    // ("Koko talo") orbits with one finger, pans with two.
                    val floorplan = latestFloorMode != FloorMode.All
                    if (pressed >= 2) radius = (radius / zoom).coerceIn(2.5f, 90f)
                    if (floorplan || pressed >= 2) {
                        // Slide the target in the horizontal plane, oriented by the
                        // camera so a swipe moves the plan the way the fingers go.
                        val eye = orbitEye(target, theta, phi, radius)
                        val right = (target - eye).normalized().cross(Vec3.UP).normalized()
                        val fwdFloor = Vec3.UP.cross(right).normalized()
                        // Move 1:1 with the floor under the finger: world-units per
                        // pixel = the visible world height (2·r·tan(fov/2)) / view height.
                        val k = if (size.height > 0) {
                            2f * tan(PROJECTION_FOV_RAD / 2f) * radius / size.height
                        } else {
                            radius * 0.0005f
                        }
                        // X is inverted vs the natural cross-product so a left swipe
                        // moves the plan left; Y already reads correctly.
                        target = target + right * (-pan.x * k) + fwdFloor * (pan.y * k)
                    } else {
                        theta -= pan.x * 0.005f
                        phi = (phi - pan.y * 0.005f).coerceIn(0.12f, 1.52f)
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
        .pointerInput(model) {
            detectTapGestures { pos ->
                interactionTick += 1
                val eye = orbitEye(latestTarget, latestTheta, latestPhi, latestRadius)
                pickRoom(
                    model, eye, latestTarget, latestFloorMode, latestExplode,
                    size.width.toFloat(), size.height.toFloat(), pos.x, pos.y,
                )?.let(latestOnRoomTap)
            }
        }

    Box(modifier = modifier.fillMaxSize().onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }.then(gestures)) {
        val eye = orbitEye(cameraTarget, theta, phi, radius)
        // World positions of the fixtures currently on, on the shown floor. Drives
        // both the dark-mode point lights (below, in the native renderers) and the
        // ring overlay. Unlike the rings, illumination is NOT gated on walls — a
        // lit room glows whether or not its walls are drawn.
        val litLights = remember(lightOnAreas, presets, explodeAnim, renderedFloorMode) {
            presets.lights.filter {
                anchorGroup(it.name) in renderedFloorMode.groups &&
                    // DEBUG_ALL_LIGHTS: light every fixture to tune the dark-mode glow
                    // offline (no live light data on the emulator). Revert to the
                    // lightOnAreas check before committing.
                    (DEBUG_ALL_LIGHTS ||
                        (HouseLightMap.anchorToArea[it.name]?.let { key -> key in lightOnAreas } ?: false))
            }
                .map { Vec3(it.pos.x, it.pos.y + groupTier(anchorGroup(it.name)) * explodeAnim, it.pos.z) }
        }
        // The 3D geometry (software on iOS, Filament GPU on Android)…
        HouseGeometrySurface(
            model = model,
            eye = eye,
            target = cameraTarget,
            mode = renderedFloorMode,
            showRoof = showRoof,
            showWalls = showWalls,
            showFurniture = showFurniture,
            showHeating = showHeating,
            heatByCircuit = heatByCircuit,
            explode = explodeAnim,
            litLights = litLights,
            modifier = Modifier.fillMaxSize(),
        )
        // …then the shared room tint + selection highlight on top, projected with
        // the same orbit camera so it lines up over either renderer.
        Canvas(Modifier.fillMaxSize()) {
            drawRoomOverlays(
                model, eye, cameraTarget, renderedFloorMode, explodeAnim, selectedRoom, roomTint, accent,
                roomOverlayCache,
            )
        }

        // Resolve light and infographic sources before starting the shared pulse
        // clock. One draw-only frame observer drives both layers without causing
        // the native 3D scene or this composition to update for every ring frame.
        // Light glows only read in the cutaway view. With the walls in place the
        // room is enclosed, so a projected 2D ring (which has no depth test) would
        // otherwise bleed through the walls and roof — show them only when the
        // walls are hidden. (The 3D point lights above are not gated this way.)
        val onLightPos = if (showWalls) emptyList() else litLights
        val w = canvasSize.width; val h = canvasSize.height
        val canProjectMarkers = markerProjector.update(eye, cameraTarget, w, h)
        val visibleMarkers = remember(markers, renderedFloorMode) {
            markers.filter { it.group in renderedFloorMode.groups }
        }
        // Draw exactly the ambient facts that currently own automatic focus.
        // Previously all three carousel facts stayed in the HUD during an alert,
        // announcement, or interaction pause, including labels for hidden floors.
        val displayedFacts = remember(automaticMarkers) {
            automaticMarkers.filter { marker ->
                marker.kind != MarkerKind.Alert && marker.kind != MarkerKind.Announcement
            }
        }
        val factAlpha = factFade.value
        val labelled = remember(visibleMarkers, displayedFacts, factAlpha) {
            visibleMarkers.map { it to 1f } + displayedFacts.map { it to factAlpha }
        }
        val projectedLabels = labelled.mapNotNull { (m, alpha) ->
            val yoff = groupTier(m.group) * explodeAnim
            val off = if (canProjectMarkers) markerProjector.project(m.pos.x, m.pos.y + yoff, m.pos.z) else null
            off?.let { ProjectedHudLabel(m, alpha, it) }
        }
        val pulseMillis = if (onLightPos.isNotEmpty() || projectedLabels.isNotEmpty()) {
            rememberFrameMillis()
        } else {
            null
        }

        // Animated source dots that rise and fade from each on-fixture (design's
        // light look). A separate layer keeps this out of the native scene.
        if (onLightPos.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                val cw = size.width; val ch = size.height
                if (!lightProjector.update(eye, cameraTarget, cw, ch)) return@Canvas
                val ms = pulseMillis?.value ?: return@Canvas
                onLightPos.forEach { p ->
                    val off = lightProjector.project(p.x, p.y, p.z) ?: return@forEach
                    // Concentric rings expanding outward and fading (design's pulse).
                    for (k in 0 until 2) {
                        val ph = ((ms / 1600f) + k * 0.5f) % 1f
                        drawCircle(
                            color = glow.copy(alpha = (1f - ph) * 0.45f),
                            radius = 2.5f + ph * 13f,
                            center = off,
                            style = Stroke(width = 1.6f),
                        )
                    }
                    // Steady warm core at the source.
                    drawCircle(Color(1f, 0.93f, 0.78f, 0.95f), radius = 2.8f, center = off)
                }
            }
        }

        if (projectedLabels.isNotEmpty()) {
            // The pulse now radiates from each label's own dot (see MarkerLabel), so a
            // marker is one cohesive chip instead of a floating pill plus a separate
            // ring/core at the source below it.
            MarkerHud(
                labels = projectedLabels,
                accent = accent,
                reserveRoomPanel = selectedRoom != null,
            )
        }
    }
}

private data class CameraDestination(val orbit: OrbitPreset, val tier: Float)

/** Stable identity of a physical camera destination; labels can update live. */
internal fun markerCameraKey(markers: List<HouseMarker>): String = markers.joinToString("|") {
    "${it.kind}:${it.group}@${it.pos.x},${it.pos.y},${it.pos.z}"
}

internal fun markerCameraTier(markers: List<HouseMarker>): Float =
    markers.map { groupTier(it.group) }.average().toFloat()

internal fun floorModeForGroup(group: HouseGroup): FloorMode = when (group) {
    HouseGroup.Kellari -> FloorMode.Kellari
    HouseGroup.Krs2 -> FloorMode.Ylakerta
    HouseGroup.Krs1 -> FloorMode.Alakerta
    // The terrace/lawn and detached carport aren't part of a single floor cutaway;
    // selecting them shows the whole-house view where they live.
    HouseGroup.Terassi, HouseGroup.Katos, HouseGroup.Katto -> FloorMode.All
}

internal fun focusedFloorMode(
    selected: FloorMode,
    focusedMarkers: List<HouseMarker>,
    selectedRoomGroup: HouseGroup?,
): FloorMode {
    val markerFloors = focusedMarkers.map { floorModeForGroup(it.group) }.distinct()
    return when {
        markerFloors.size == 1 -> markerFloors.single()
        markerFloors.size > 1 -> FloorMode.All
        selectedRoomGroup != null -> floorModeForGroup(selectedRoomGroup)
        else -> selected
    }
}

internal data class ShowcasePage(
    val floorMode: FloorMode,
    val facts: List<HouseMarker>,
)

/**
 * Builds a deterministic bottom-to-top presentation tour. The whole-house page
 * resets spatial context between loops; each physical floor then gets at least
 * one page even when it currently has no fact, while populated floors are chunked
 * into pages of SHOWCASE_FACTS_PER_PAGE highlights.
 */
internal fun buildShowcasePages(
    facts: List<HouseMarker>,
    selected: FloorMode,
): List<ShowcasePage> {
    if (selected != FloorMode.All) {
        val floorFacts = facts.filter { it.group in selected.groups }
        return if (floorFacts.isEmpty()) listOf(ShowcasePage(selected, emptyList()))
        else floorFacts.chunked(SHOWCASE_FACTS_PER_PAGE).map { ShowcasePage(selected, it) }
    }
    // The whole-house page opens the loop with the house-level systems (outdoor,
    // electricity, heat pump, ventilation, lights) instead of being blank; each
    // floor then tours its own room facts.
    val overview = facts.filter { it.label in SHOWCASE_OVERVIEW_LABELS }
    fun floorPages(mode: FloorMode): List<ShowcasePage> {
        val floorFacts = facts.filter { it.group in mode.groups && it.label !in SHOWCASE_OVERVIEW_LABELS }
        return if (floorFacts.isEmpty()) {
            listOf(ShowcasePage(mode, emptyList()))
        } else {
            floorFacts.chunked(SHOWCASE_FACTS_PER_PAGE).map { ShowcasePage(mode, it) }
        }
    }
    return buildList {
        if (overview.isNotEmpty()) {
            overview.chunked(SHOWCASE_FACTS_PER_PAGE).forEach { add(ShowcasePage(FloorMode.All, it)) }
        } else {
            add(ShowcasePage(FloorMode.All, emptyList()))
        }
        addAll(floorPages(FloorMode.Kellari))
        addAll(floorPages(FloorMode.Alakerta))
        addAll(floorPages(FloorMode.Ylakerta))
    }
}

/** House-level facts shown on the whole-house overview page (not a specific floor). */
private val SHOWCASE_OVERVIEW_LABELS = setOf("Ulkoilma", "Sähkö", "Maalämpö", "Ilmanvaihto", "Valot päällä")

/** DEBUG: force every fixture on to tune dark-mode lighting offline. Revert to false. */
private const val DEBUG_ALL_LIGHTS = false

/** Vertical field of view (radians) the renderers/projection use (48°). */
private const val PROJECTION_FOV_RAD = (48.0 * PI / 180.0).toFloat()

/** How many live observations the infomercial shows on one page/frame. */
private const val SHOWCASE_FACTS_PER_PAGE = 4

private const val AUTO_SPIN_STEP_NANOS = 16_666_667L
private const val AUTO_SPIN_RADIANS_PER_SECOND = 0.16f

private data class ProjectedHudLabel(
    val marker: HouseMarker,
    val alpha: Float,
    val anchor: Offset,
)


/**
 * Measures the marker pills before placing them so labels remain inside the 3D
 * stage and do not stack directly on top of each other. This matters especially
 * on iPad, where rotation and split view resize the native SceneKit surface
 * without changing the marker's world-space position.
 */
@Composable
private fun MarkerHud(
    labels: List<ProjectedHudLabel>,
    accent: Color,
    reserveRoomPanel: Boolean,
) {
    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            labels.forEach { label ->
                MarkerLabel(
                    marker = label.marker,
                    accent = accent,
                    modifier = Modifier.alpha(label.alpha),
                )
            }
        },
    ) { measurables, constraints ->
        val margin = 12.dp.roundToPx()
        val topInset = 30.dp.roundToPx()
        val gap = 6.dp.roundToPx()
        val panelReserve = if (reserveRoomPanel) 276.dp.roundToPx() else 0
        val labelMaxWidth = (constraints.maxWidth - panelReserve - margin * 2)
            .coerceAtLeast(120.dp.roundToPx())
            .coerceAtMost(constraints.maxWidth)
        val placeables = measurables.map { measurable ->
            measurable.measure(
                constraints.copy(
                    minWidth = 0,
                    minHeight = 0,
                    maxWidth = labelMaxWidth,
                ),
            )
        }
        val positions = placeHudLabels(
            anchors = labels.map { it.anchor },
            sizes = placeables.map { IntSize(it.width, it.height) },
            viewport = IntSize(constraints.maxWidth, constraints.maxHeight),
            reservedRightPx = panelReserve,
            marginPx = margin,
            topInsetPx = topInset,
            gapPx = gap,
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val position = positions[index]
                placeable.place(position.x, position.y)
            }
        }
    }
}

private data class HudBounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    fun overlaps(other: HudBounds, gap: Int): Boolean =
        x < other.right + gap && right + gap > other.x &&
            y < other.bottom + gap && bottom + gap > other.y
}

/** Pure placement policy kept internal so viewport edge/collision cases are testable. */
internal fun placeHudLabels(
    anchors: List<Offset>,
    sizes: List<IntSize>,
    viewport: IntSize,
    reservedRightPx: Int = 0,
    marginPx: Int = 0,
    topInsetPx: Int = 0,
    gapPx: Int = 0,
): List<IntOffset> {
    require(anchors.size == sizes.size)
    if (anchors.isEmpty()) return emptyList()

    val viewportWidth = viewport.width.coerceAtLeast(0)
    val viewportHeight = viewport.height.coerceAtLeast(0)
    val contentRight = (viewportWidth - marginPx - reservedRightPx).coerceAtLeast(marginPx)
    val contentBottom = (viewportHeight - marginPx).coerceAtLeast(topInsetPx)
    val occupied = ArrayList<HudBounds>(anchors.size)

    return anchors.mapIndexed { index, anchor ->
        val size = sizes[index]
        val maxX = (contentRight - size.width).coerceAtLeast(marginPx)
        val maxY = (contentBottom - size.height).coerceAtLeast(topInsetPx)
        val x = (anchor.x.roundToInt() - marginPx / 2).coerceIn(marginPx, maxX)
        val desiredY = (anchor.y.roundToInt() - size.height - gapPx)
            .coerceIn(topInsetPx, maxY)
        val stride = (size.height + gapPx).coerceAtLeast(1)
        val candidates = buildList {
            add(desiredY)
            for (step in 1..anchors.size + 1) {
                add((desiredY + stride * step).coerceAtMost(maxY))
                add((desiredY - stride * step).coerceAtLeast(topInsetPx))
            }
        }.distinct()
        val y = candidates.firstOrNull { candidateY ->
            val candidate = HudBounds(x, candidateY, size.width, size.height)
            occupied.none { candidate.overlaps(it, gapPx) }
        } ?: desiredY
        occupied += HudBounds(x, y, size.width, size.height)
        IntOffset(x, y)
    }
}

/** A pulsing dot + name pill that reads through walls — used for pings and facts. */
@Composable
private fun MarkerLabel(marker: HouseMarker, accent: Color, modifier: Modifier = Modifier) {
    val c = MkTheme.colors
    val type = MkTheme.type
    // Dot legend: grey = stale (last-known reading), otherwise by kind — red for a
    // person/alert, amber for a door/sauna/announcement, green for a live reading.
    val dot = if (marker.stale) c.inkLo else when (marker.kind) {
        MarkerKind.Person, MarkerKind.Alert -> c.statusAlarm
        MarkerKind.Announcement -> c.warm
        MarkerKind.Door -> c.warm
        MarkerKind.Sauna -> c.warm
        MarkerKind.Info -> accent
    }
    // A gentle pulse radiates from the dot itself (a live reading only — stale
    // readings stay a flat grey dot). This replaces the old separate source ping.
    val pulse = rememberInfiniteTransition(label = "markerPulse")
    val phase by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing)),
        label = "markerPulsePhase",
    )
    Row(
        // Background WITHOUT clip so the dot's pulse rings can extend past the pill.
        modifier = modifier
            .background(c.surfaceCard.copy(alpha = 0.94f), RoundedCornerShape(MkRadius.round))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(9.dp)
                .drawBehind {
                    if (marker.stale) return@drawBehind
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val base = 4.dp.toPx()
                    val travel = 11.dp.toPx()
                    for (ring in 0 until 2) {
                        val p = (phase + ring * 0.5f) % 1f
                        val fade = (1f - p) * (1f - p)
                        drawCircle(
                            color = dot.copy(alpha = fade * 0.55f),
                            radius = base + p * travel,
                            center = center,
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                }
                .clip(RoundedCornerShape(MkRadius.round))
                .background(dot),
        )
        Text(
            text = buildString {
                append(marker.label)
                marker.sub?.let { append(" · "); append(it) }
            },
            style = type.caption.copy(fontSize = 10.5.sp),
            color = if (marker.stale) c.inkMid else c.inkHi,
            modifier = Modifier.padding(start = 6.dp).widthIn(max = 280.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
