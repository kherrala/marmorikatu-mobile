package fi.marmorikatu.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import fi.marmorikatu.app.components.Detection
import fi.marmorikatu.app.platform.LockLandscapeWhileVisible
import fi.marmorikatu.app.shell.UiSignals
import org.koin.compose.koinInject
import fi.marmorikatu.app.components.MkAttentionStrip
import fi.marmorikatu.app.components.MkButton
import fi.marmorikatu.app.components.MkButtonSize
import fi.marmorikatu.app.components.MkButtonVariant
import fi.marmorikatu.app.components.MkArticleViewer
import fi.marmorikatu.app.components.MkCameraViewer
import fi.marmorikatu.app.components.MkClimateCard
import fi.marmorikatu.app.components.rememberTickingAge
import fi.marmorikatu.app.components.MkDoorAlert
import fi.marmorikatu.app.components.rememberBase64Painter
import fi.marmorikatu.app.components.MkMetricDetail
import fi.marmorikatu.app.components.MkPullToRefresh
import fi.marmorikatu.app.components.MkSeries
import fi.marmorikatu.app.components.MkStatTile
import fi.marmorikatu.app.components.GarbageNextCard
import fi.marmorikatu.app.components.MkWeatherWidget
import fi.marmorikatu.app.components.WeatherReading
import fi.marmorikatu.app.components.TimeRangeOption
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import org.koin.compose.viewmodel.koinViewModel

/**
 * Koti (home): the attention strip, an optional door alert, the climate card,
 * and a 2-column KPI grid. Tapping a KPI swaps the page for its [MkMetricDetail]
 * with a "Takaisin" affordance.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun KotiScreen(viewModel: KotiViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val news by viewModel.news.collectAsState()
    val activePreset by viewModel.activePreset.collectAsState()
    val kpiDetailSeries by viewModel.kpiDetailSeries.collectAsState()
    val kpiDetailLoading by viewModel.kpiDetailLoading.collectAsState()
    val weather by viewModel.weather.collectAsState()
    val outdoorTemp by viewModel.outdoorTemp.collectAsState()
    val nextGarbage by viewModel.nextGarbage.collectAsState()
    val updatedAt by viewModel.updatedAt.collectAsState()
    val metricsUpdatedAt by viewModel.metricsUpdatedAt.collectAsState()
    val newsReading by viewModel.newsReading.collectAsState()
    val colors = MkTheme.colors

    LaunchedEffect(Unit) { viewModel.refresh() }

    // Detail selection lives in the ViewModel: turning the phone to landscape
    // recycles this pager page (disposing even rememberSaveable state), so local
    // state would bounce the detail closed. The VM survives the recreation.
    val selKey by viewModel.detailKey.collectAsState()
    val roomDetailIndex by viewModel.roomDetailIndex.collectAsState()
    val detailRange by viewModel.detailRange.collectAsState()
    var roomIndex by remember { mutableStateOf(0) }
    var doorDismissed by remember { mutableStateOf(false) }
    // VM-backed so it survives the landscape recreation the camera viewer forces.
    val cameraOpen by viewModel.cameraOpen.collectAsState()
    var newsOpen by remember { mutableStateOf(false) }
    // Attention chips can be dismissed (tap) and restored from a collapsed strip.
    var dismissedAttn by remember { mutableStateOf(setOf<String>()) }
    var showDismissed by remember { mutableStateOf(false) }

    // System back leaves a detail chart for the dashboard instead of quitting.
    BackHandler(enabled = selKey != null || roomDetailIndex != null) {
        viewModel.closeDetail()
    }

    // A detail chart turns the phone to landscape (even under an orientation
    // lock) to use the width, and signals the shell to keep the phone surface
    // rather than swapping to the kiosk when it goes wide.
    val detailOpen = selKey != null || roomDetailIndex != null || cameraOpen
    val uiSignals: UiSignals = koinInject()
    // Commit the signal synchronously at composition-apply (SideEffect), not on a
    // coroutine (LaunchedEffect) — otherwise the forced rotation can outrun it and
    // the shell briefly swaps to the kiosk surface, disposing this detail.
    SideEffect { uiSignals.detailOpen.value = detailOpen }
    DisposableEffect(Unit) { onDispose { uiSignals.detailOpen.value = false } }

    // A tapped news notification (routed here by the shell) opens the news reader.
    val openNewsReader by uiSignals.openNewsReader.collectAsState()
    LaunchedEffect(openNewsReader) {
        if (openNewsReader) {
            newsOpen = true
            uiSignals.openNewsReader.value = false
        }
    }
    if (detailOpen) LockLandscapeWhileVisible()

    // Dashboard scroll, seeded from and written back to the VM. It is attached only
    // to the dashboard branch below, so a detail chart never clobbers it — returning
    // from a chart lands where the user left off.
    val scrollState = rememberScrollState(viewModel.scrollOffset)
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value }.collect { viewModel.scrollOffset = it }
    }

    MkPullToRefresh(refreshing = refreshing, onRefresh = viewModel::refresh) {
        val selected = selKey?.let { key -> state.kpis.firstOrNull { it.key == key } }
        val room = roomDetailIndex?.let { state.rooms.getOrNull(it) }
        // Detail charts render in their own scroll container, separate from the
        // dashboard's [scrollState], so opening/closing one leaves it untouched.
        if (selected != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.appBg)
                    .verticalScroll(rememberScrollState())
                    .padding(MkSpacing.pagePad),
                verticalArrangement = Arrangement.spacedBy(MkSpacing.stackGap),
            ) {
                // Load this metric's history at the chosen window from InfluxDB.
                val hasSource = selected.detailMeasurement != null && selected.detailField != null
                LaunchedEffect(selected.key, detailRange, hasSource) {
                    if (hasSource) {
                        viewModel.loadKpiDetail(
                            selected.detailMeasurement!!,
                            selected.detailField!!,
                            selected.detailTagKey,
                            selected.detailTagValue,
                            detailRange,
                        )
                    }
                }
                val loaded = if (hasSource) kpiDetailSeries else selected.seriesValues
                val series = if (loaded.size < 2) emptyList() else listOf(
                    MkSeries(
                        name = null,
                        values = loaded,
                        color = lineColor(selected.detailStatus, colors),
                        area = true,
                    ),
                )
                MkMetricDetail(
                    icon = selected.icon,
                    label = selected.label,
                    value = selected.value,
                    unit = selected.unit ?: selected.detailUnit,
                    series = series,
                    labels = chartLabels(detailRange),
                    stats = selected.stats,
                    status = selected.detailStatus,
                    range = if (hasSource) detailRange else null,
                    onRangeChange = if (hasSource) ({ viewModel.setDetailRange(it) }) else null,
                    onBack = { viewModel.closeDetail() },
                    // Swipe the card to the right to go back to the grid.
                    modifier = Modifier.pointerInput(selKey) {
                        var dragged = 0f
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragged > 56.dp.toPx()) viewModel.closeDetail()
                                dragged = 0f
                            },
                            onHorizontalDrag = { _, delta -> dragged += delta },
                        )
                    },
                )
                if (hasSource && kpiDetailLoading) {
                    Text("Ladataan historiaa…", style = MkTheme.type.label, color = colors.inkLo)
                } else if (hasSource && loaded.size < 2) {
                    Text("Ei historiaa saatavilla.", style = MkTheme.type.label, color = colors.inkLo)
                }
            }
        } else if (room != null) {
            // Room temperature detail — opened by tapping the climate card's value.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.appBg)
                    .verticalScroll(rememberScrollState())
                    .padding(MkSpacing.pagePad),
                verticalArrangement = Arrangement.spacedBy(MkSpacing.stackGap),
            ) {
                val field = room.historyField
                LaunchedEffect(room.name, detailRange, field) {
                    if (field != null) viewModel.loadKpiDetail("rooms", field, null, null, detailRange)
                }
                val series = if (kpiDetailSeries.size < 2) emptyList() else listOf(
                    MkSeries(name = null, values = kpiDetailSeries, color = colors.accent, area = true),
                )
                MkMetricDetail(
                    icon = room.icon ?: MkIcons.Thermometer,
                    label = room.name,
                    value = room.temp,
                    unit = "°C",
                    series = series,
                    labels = chartLabels(detailRange),
                    stats = emptyList(),
                    status = "accent",
                    range = if (field != null) detailRange else null,
                    onRangeChange = if (field != null) ({ viewModel.setDetailRange(it) }) else null,
                    onBack = { viewModel.closeDetail() },
                    modifier = Modifier.pointerInput(room.name) {
                        var dragged = 0f
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragged > 56.dp.toPx()) viewModel.closeDetail()
                                dragged = 0f
                            },
                            onHorizontalDrag = { _, delta -> dragged += delta },
                        )
                    },
                )
                if (field != null && kpiDetailLoading) {
                    Text("Ladataan historiaa…", style = MkTheme.type.label, color = colors.inkLo)
                } else if (field != null && kpiDetailSeries.size < 2) {
                    Text("Ei historiaa saatavilla.", style = MkTheme.type.label, color = colors.inkLo)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.appBg)
                    .verticalScroll(scrollState)
                    .padding(MkSpacing.pagePad),
                verticalArrangement = Arrangement.spacedBy(MkSpacing.stackGap),
            ) {
            val activeAttn = state.attention.filterNot { it.text in dismissedAttn }
            val dismissedAttnItems = state.attention.filter { it.text in dismissedAttn }
            // Only surface the strip when something actually needs attention — the
            // "Kaikki kunnossa" empty state was redundant noise.
            if (activeAttn.isNotEmpty()) {
                MkAttentionStrip(
                    items = activeAttn,
                    onItemClick = { dismissedAttn = dismissedAttn + it.text },
                )
            }
            if (dismissedAttnItems.isNotEmpty()) {
                DismissedToggle(dismissedAttnItems.size, showDismissed) { showDismissed = !showDismissed }
                if (showDismissed) {
                    MkAttentionStrip(
                        items = dismissedAttnItems,
                        title = "Ohitetut",
                        onItemClick = { dismissedAttn = dismissedAttn - it.text },
                        modifier = Modifier.alpha(0.55f),
                    )
                }
            }

            val door = state.door
            if (door != null && !doorDismissed) {
                val doorPainter = rememberBase64Painter(door.image)
                MkDoorAlert(
                    painter = doorPainter,
                    title = door.title,
                    time = door.time,
                    subtitle = door.subtitle,
                    detection = Detection(),
                    onView = { viewModel.openCamera() },
                    onDismiss = { doorDismissed = true },
                )
                if (cameraOpen) {
                    MkCameraViewer(
                        painter = doorPainter,
                        title = door.title,
                        subtitle = door.subtitle,
                        time = door.time,
                        onDismiss = { viewModel.closeCamera() },
                    )
                }
            }

            weather?.let { w ->
                MkWeatherWidget(
                    forecast = w,
                    outdoorTempOverride = outdoorTemp?.primary?.celsius,
                    outdoorSource = outdoorTemp?.primary?.source,
                    outdoorAlternatives = outdoorTemp?.alternatives.orEmpty()
                        .map { WeatherReading(it.source, it.celsius) },
                )
            }

            nextGarbage?.let { g -> GarbageNextCard(next = g) }

            SectionLabel("Valaistus")
            SceneRow(active = activePreset, onScene = viewModel::applyPreset)

            if (state.rooms.isNotEmpty()) {
                val safeIndex = roomIndex.coerceIn(0, state.rooms.size - 1)
                SectionLabel("Lämpötilat", updatedAtEpochSeconds = updatedAt)
                MkClimateCard(
                    rooms = state.rooms,
                    index = safeIndex,
                    onIndexChange = { roomIndex = it },
                    targetEnabled = false, // no per-room write path exists
                    colorTempByBand = true, // design: warm ≥22°, accent ≤19.5°
                    onTempClick = { viewModel.openRoomDetail(safeIndex) },
                )
            }

            // News sits directly under the climate card, above the KPI grid (design).
            news.firstOrNull()?.let { top ->
                SectionLabel("Uutiset")
                NewsCard(top, onOpen = { newsOpen = true }, onRead = viewModel::toggleReadNews, reading = newsReading)
                if (newsOpen) {
                    MkArticleViewer(
                        title = "Uutiset",
                        body = news.joinToString("\n\n") { h ->
                            buildString {
                                append("## ${h.title}")
                                val m = listOf(h.source.ifBlank { "Uutiset" }, h.published)
                                    .filter { it.isNotBlank() }.joinToString(" · ")
                                if (m.isNotBlank()) append("\n*$m*")
                                if (h.description.isNotBlank()) append("\n${h.description}")
                            }
                        },
                        meta = "${news.size} uutista",
                        onRead = viewModel::toggleReadNews,
                        onDismiss = { newsOpen = false },
                    )
                }
            }

            if (state.kpis.isNotEmpty()) SectionLabel("Mittarit", updatedAtEpochSeconds = metricsUpdatedAt)
            KpiGrid(state.kpis) { viewModel.openKpiDetail(it) }

            when {
                state.loading && state.kpis.isEmpty() ->
                    Text("Ladataan…", style = MkTheme.type.label, color = colors.inkLo)
                state.error != null ->
                    Text(state.error!!, style = MkTheme.type.label, color = colors.inkLo)
            }

            Spacer(Modifier.height(MkSpacing.scrollBottomGap))
            }
        }
    }
}

/** Top news headline: the left opens the full list, the speaker reads it aloud. */
@Composable
private fun NewsCard(news: NewsHeadline, onOpen: () -> Unit, onRead: () -> Unit, reading: Boolean) {
    val c = MkTheme.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(fi.marmorikatu.app.theme.MkRadius.lg)
    val pill = androidx.compose.foundation.shape.RoundedCornerShape(fi.marmorikatu.app.theme.MkRadius.round)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surfaceCard)
            .border(1.dp, c.borderSubtle, shape)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: icon + headline — tapping opens the full news list.
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(fi.marmorikatu.app.theme.MkRadius.md))
                .clickable(onClick = onOpen)
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(fi.marmorikatu.app.theme.MkRadius.md))
                    .background(c.accentDim),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(MkIcons.Info, null, tint = c.accent, modifier = Modifier.size(19.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Uutiset" + news.published.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                    style = MkTheme.type.readout(10).copy(letterSpacing = 0.1.em),
                    color = c.inkLo,
                )
                Text(
                    text = news.title,
                    style = MkTheme.type.heading,
                    color = c.inkHi,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        // Right: the speaker reads every headline aloud; while reading it becomes
        // a stop control (tap to cancel).
        val tint = if (reading) c.warm else c.accent
        Row(
            modifier = Modifier
                .clip(pill)
                .background(if (reading) c.warmDim else c.accentDim)
                .clickable(onClick = onRead)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            androidx.compose.material3.Icon(
                if (reading) MkIcons.X else MkIcons.SpeakerHigh,
                if (reading) "Lopeta luku" else "Lue uutiset",
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            Text(if (reading) "Lopeta" else "Lue", style = MkTheme.type.readout(10).copy(letterSpacing = 0.06.em), color = tint)
        }
    }
}

/**
 * One-tap lighting scenes, per the design's "Pikatilat" strip. Scrolls
 * horizontally; an active scene is filled with the accent tint so its state is
 * visible at a glance, and tapping it again toggles it off.
 */
@Composable
internal fun SceneRow(active: KotiScene?, onScene: (KotiScene) -> Unit) {
    val c = MkTheme.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(fi.marmorikatu.app.theme.MkRadius.md)
    // Full-width grid: the four presets share the row as equal columns (design's
    // repeat(4,1fr)) rather than a left-aligned horizontally scrolling strip.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KotiScene.entries.forEach { s ->
            val on = s == active
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 62.dp)
                    .clip(shape)
                    .background(if (on) c.accentDim else c.surfaceCard)
                    .border(1.dp, if (on) c.accentBorder else c.borderSubtle, shape)
                    .clickable { onScene(s) }
                    .padding(vertical = 11.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                androidx.compose.material3.Icon(
                    imageVector = s.icon,
                    contentDescription = null,
                    tint = c.accent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = s.label,
                    style = MkTheme.type.label,
                    color = if (on) c.accent else c.inkMid,
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/** Collapsed toggle that reveals the dismissed-attention strip. */
@Composable
private fun DismissedToggle(count: Int, shown: Boolean, onToggle: () -> Unit) {
    val c = MkTheme.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(fi.marmorikatu.app.theme.MkRadius.md)
    Row(
        modifier = Modifier
            .clip(shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.material3.Icon(MkIcons.EyeSlash, null, tint = c.inkLo, modifier = Modifier.size(15.dp))
        Text(
            text = if (shown) "Piilota ohitetut" else "Näytä ohitetut ($count)",
            style = MkTheme.type.readout(11).copy(letterSpacing = 0.06.em),
            color = c.inkLo,
        )
    }
}

/** An uppercase mono section label with a live-data dot, per the design. */
@Composable
internal fun SectionLabel(text: String, updatedAtEpochSeconds: Long? = null) {
    val colors = MkTheme.colors
    // A plain uppercase kicker (design). The old leading accent dot was purely
    // decorative; a right-aligned freshness dot + "N s sitten" is shown only when
    // an updated-at is supplied (the design's climate section).
    val age = rememberTickingAge(updatedAtEpochSeconds)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text.uppercase(),
            style = MkTheme.type.readout(11).copy(letterSpacing = 0.12.em),
            color = colors.inkLo,
        )
        if (updatedAtEpochSeconds != null && age != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(colors.accent, androidx.compose.foundation.shape.CircleShape),
                )
                Text(
                    text = age,
                    style = MkTheme.type.readout(10),
                    color = colors.inkLo,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Two-column grid of KPI tiles; each opens its detail via [onSelect]. */
@Composable
private fun KpiGrid(kpis: List<KotiKpi>, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        kpis.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                row.forEach { kpi ->
                    MkStatTile(
                        label = kpi.label,
                        value = kpi.value,
                        unit = kpi.unit,
                        icon = kpi.icon,
                        status = kpi.statStatus,
                        tag = kpi.tag,
                        tagStatus = kpi.tagStatus,
                        spark = kpi.seriesValues,
                        // Flash when the source produces a new reading (its ts/push
                        // advances), and dim when that feed goes stale.
                        pulseKey = kpi.freshAsOf,
                        dimmed = kpi.stale,
                        onClick = { onSelect(kpi.key) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** X-axis ticks describing the selected detail window. */
internal fun chartLabels(range: TimeRangeOption): List<String> = when (range) {
    TimeRangeOption.H6 -> listOf("-6 t", "-4 t", "-2 t", "nyt")
    TimeRangeOption.H24 -> listOf("00:00", "06:00", "12:00", "18:00", "nyt")
    TimeRangeOption.D7 -> listOf("ma", "ke", "pe", "su")
    TimeRangeOption.D30 -> listOf("1.", "10.", "20.", "30.")
    TimeRangeOption.Y1 -> listOf("tammi", "huhti", "heinä", "loka")
}

private fun lineColor(status: String, colors: fi.marmorikatu.app.theme.MkColors): Color = when (status) {
    "ok" -> colors.statusOk
    "warn" -> colors.warm
    "alarm" -> colors.statusAlarmInk
    "info" -> colors.statusInfo
    else -> colors.accent
}
