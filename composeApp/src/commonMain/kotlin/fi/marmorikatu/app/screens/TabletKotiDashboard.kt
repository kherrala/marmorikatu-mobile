package fi.marmorikatu.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fi.marmorikatu.app.components.BarState
import fi.marmorikatu.app.components.EventEntry
import fi.marmorikatu.app.components.MkButton
import fi.marmorikatu.app.components.MkButtonSize
import fi.marmorikatu.app.components.MkButtonVariant
import fi.marmorikatu.app.components.MkMetricDetailPage
import fi.marmorikatu.app.components.MkTagStatus
import fi.marmorikatu.app.components.TimeRangeOption
import fi.marmorikatu.app.components.MkCameraCard
import fi.marmorikatu.app.components.MkCameraViewer
import fi.marmorikatu.app.components.MkClimateRoom
import fi.marmorikatu.app.components.MkEventFeed
import fi.marmorikatu.app.components.MkLineChart
import fi.marmorikatu.app.components.MkPriceBar
import fi.marmorikatu.app.components.MkPriceBars
import fi.marmorikatu.app.components.MkSeries
import fi.marmorikatu.app.components.MkStatSize
import fi.marmorikatu.app.components.MkStatStatus
import fi.marmorikatu.app.components.MkStatTile
import fi.marmorikatu.app.components.rememberBase64Painter
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import org.koin.compose.viewmodel.koinViewModel

/**
 * The kiosk dashboard from `MarmorikatuApp.dc.html`'s tablet surface: a wide row
 * of KPI tiles (each with its 24 h sparkline), the lighting presets, and a
 * three-column grid — the temperature chart, the price bars + room readouts, and
 * the front-yard camera + event feed. Reuses the phone [KotiViewModel]; the extra
 * tablet-only series (chart, bars, events) come from its `tablet*` flows.
 */
@Composable
fun TabletKotiDashboard(viewModel: KotiViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val tempSeries by viewModel.tabletTempSeries.collectAsState()
    val priceBars by viewModel.tabletPriceBars.collectAsState()
    val priceLabels by viewModel.tabletPriceLabels.collectAsState()
    val events by viewModel.tabletEvents.collectAsState()
    val kpiDetailSeries by viewModel.kpiDetailSeries.collectAsState()
    val kpiDetailLoading by viewModel.kpiDetailLoading.collectAsState()
    val cameraOpen by viewModel.cameraOpen.collectAsState()
    val c = MkTheme.colors

    // Only the tapped detail's KEY is stored; the KotiKpi itself is re-resolved
    // from live state every recomposition so an open detail keeps tracking the
    // dashboard underneath — the kiosk is always-on, and a stored snapshot
    // would freeze the readout at tap time (cf. KotiScreen's selKey).
    var detailKey by remember { mutableStateOf<String?>(null) }
    var detailRange by remember { mutableStateOf(TimeRangeOption.H24) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Box(modifier = Modifier.fillMaxSize()) {
    // The kiosk is a fixed dashboard — it fills the landscape viewport without
    // scrolling (design), so the three-column body claims the height left over
    // after the KPI strip and every panel flexes to fit.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = MkSpacing.pagePadTablet, vertical = MkSpacing.x4),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The design's climate stat tiles, each carrying its 24 h sparkline
        // where a history series exists. Laid out as the design's single
        // seven-across row (`grid-template-columns:repeat(7,1fr)`); each tile
        // takes weight(1f) so the whole set shares one row and shrinks to fit.
        if (state.kioskStats.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.kioskStats.chunked(STATS_PER_ROW).forEach { rowStats ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        rowStats.forEach { kpi ->
                            MkStatTile(
                                label = kpi.label,
                                value = kpi.value,
                                unit = kpi.unit,
                                icon = kpi.icon,
                                status = kpi.statStatus,
                                tag = kpi.tag,
                                tagStatus = kpi.tagStatus,
                                // Seven across a scaled kiosk canvas is tight; the
                                // compact size keeps value + unit from clipping.
                                size = MkStatSize.Sm,
                                spark = kpi.seriesValues,
                                pulseKey = kpi.freshAsOf,
                                dimmed = kpi.stale,
                                onClick = if (kpi.detailField != null) ({ detailKey = kpi.key }) else null,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Keep the last, shorter row's tiles the same width.
                        repeat(STATS_PER_ROW - rowStats.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        // Lighting presets were removed from the kiosk home to save vertical
        // space (design); they remain reachable on the Valot screen.

        // The three-column body: chart · price + rooms · camera + events. It
        // takes weight(1f) so it fills the height left below the KPI strip, and
        // each panel flexes within its column — nothing scrolls.
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DashCard("Ulko- ja huonelämpötilat", modifier = Modifier.weight(1.5f).fillMaxHeight()) {
                if (tempSeries.isEmpty()) {
                    DashEmpty("Ei lämpötilahistoriaa")
                } else {
                    val palette = listOf(
                        c.vizOutdoor, c.vizRoom, c.vizSecondary, c.vizTertiary,
                        c.vizAccent, c.warm, c.statusAlarmInk, c.inkMid,
                    )
                    val series = tempSeries.mapIndexed { i, s ->
                        MkSeries(name = s.name, values = s.values, color = palette[i % palette.size])
                    }
                    // Fill the card: the chart canvas takes the space left after
                    // its legend (which can wrap to two rows on a narrow column).
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        MkLineChart(
                            series = series,
                            labels = TEMP_LABELS,
                            // Auto-scale to the data (rooms/outdoor routinely pass
                            // 24 °C in summer); a fixed ceiling pushed the line off
                            // the top and over the legend.
                            height = (maxHeight - CHART_LEGEND_ALLOWANCE).coerceAtLeast(96.dp),
                            showLegend = true,
                            showYAxis = true,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1.1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val sahko = state.kpis.firstOrNull { it.key == "sahko" }
                DashCard(
                    "Sähkön hinta tänään",
                    // Tapping the card opens the spot price's history detail.
                    onClick = sahko?.takeIf { it.detailField != null }?.let { { detailKey = it.key } },
                ) {
                    if (priceBars.isEmpty()) {
                        DashEmpty("Ei hintatietoa")
                    } else {
                        MkPriceBars(
                            bars = priceBars.map {
                                MkPriceBar(
                                    value = it.cents,
                                    state = when {
                                        it.past -> BarState.Past
                                        it.expensive -> BarState.Exp
                                        it.cheap -> BarState.Cheap
                                        else -> BarState.Future
                                    },
                                )
                            },
                            labels = priceLabels,
                            height = 96.dp,
                            nowValue = sahko?.value?.takeIf { it != "Ei tietoa" },
                            nowUnit = "c/kWh",
                            nowTag = sahko?.tag,
                            nowStatus = if (sahko?.statStatus == MkStatStatus.Warn) "warn" else "ok",
                        )
                    }
                }
                DashCard("Huoneet", modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (state.rooms.isEmpty()) {
                        DashEmpty("Ei tietoa")
                    } else {
                        // A tapped room chip opens that room's temperature history.
                        RoomsGrid(state.rooms, onRoomClick = { room -> detailKey = "room_${room.name}" })
                    }
                }
            }

            Column(
                modifier = Modifier.weight(0.95f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val shot = state.cameraSnapshot
                MkCameraCard(
                    painter = rememberBase64Painter(shot?.image),
                    camera = "Etupiha",
                    title = "Etupiha",
                    subtitle = shot?.subtitle ?: "Ei liikettä",
                    metaTime = shot?.time ?: "",
                    shotHeight = 112.dp,
                    live = false,
                    // Tap the still to blow it up full-screen, like the phone door alert.
                    onClick = shot?.image?.let { { viewModel.openCamera() } },
                )
                DashCard("Tapahtumat", modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (events.isEmpty()) {
                        DashEmpty("Ei tapahtumia")
                    } else {
                        // Only as many events as the panel's height affords, so the
                        // feed never spills past the card in the no-scroll layout.
                        MkEventFeed(
                            events = events.take(KIOSK_EVENT_COUNT)
                                .map { EventEntry(it.priority, it.text, it.time) },
                            live = true,
                        )
                    }
                }
            }
        }
    }

        // KPI detail overlay — tapping a stat tile opens its history chart. The
        // KPI is re-resolved from live state so the header keeps updating.
        val detail = detailKey?.let { key ->
            if (key.startsWith("room_")) {
                state.rooms.firstOrNull { "room_${it.name}" == key }?.let(::roomDetailKpi)
            } else {
                state.kioskStats.firstOrNull { it.key == key }
                    ?: state.kpis.firstOrNull { it.key == key }
            }
        }
        if (detail != null) {
            val hasSource = detail.detailMeasurement != null && detail.detailField != null
            LaunchedEffect(detail.key, detailRange, detail.detailMeasurement, detail.detailField, detail.detailTagValue) {
                if (hasSource) {
                    viewModel.loadKpiDetail(
                        detail.detailMeasurement!!,
                        detail.detailField!!,
                        detail.detailTagKey,
                        detail.detailTagValue,
                        detailRange,
                    )
                }
            }
            val loaded = if (hasSource) kpiDetailSeries else detail.seriesValues
            val chart = if (loaded.size < 2) emptyList() else listOf(
                MkSeries(name = null, values = loaded, color = c.accent, area = true),
            )
            MkMetricDetailPage(
                icon = detail.icon,
                label = detail.label,
                value = detail.value,
                unit = detail.unit ?: detail.detailUnit,
                series = chart,
                labels = chartLabels(detailRange),
                stats = detail.stats,
                range = if (hasSource) detailRange else null,
                onRangeChange = if (hasSource) ({ detailRange = it }) else null,
                onBack = { detailKey = null },
                loading = kpiDetailLoading,
                hasHistory = hasSource,
                horizontalPadding = MkSpacing.pagePadTablet,
                verticalPadding = MkSpacing.x4,
                swipeKey = detail.key,
            )
        }

        // Full-screen camera still, opened by tapping the front-yard card.
        if (cameraOpen) {
            val shot = state.cameraSnapshot
            MkCameraViewer(
                painter = rememberBase64Painter(shot?.image),
                title = shot?.title ?: "Etupiha",
                subtitle = shot?.subtitle ?: "",
                time = shot?.time ?: "",
                onDismiss = { viewModel.closeCamera() },
            )
        }
    }
}

/** A titled surface card matching the dashboard panels. */
@Composable
private fun DashCard(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MkTheme.colors
    val shape = RoundedCornerShape(MkRadius.lg)
    Column(
        modifier = modifier
            .clip(shape)
            .background(c.surfaceCard)
            .border(1.dp, c.borderSubtle, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MkTheme.type.heading, color = c.inkHi, maxLines = 1)
        content()
    }
}

/**
 * A room chip's detail as a synthetic [KotiKpi], so the kiosk's one detail
 * overlay serves the rooms too (measurement `rooms`, the room's history field).
 */
private fun roomDetailKpi(room: MkClimateRoom): KotiKpi = KotiKpi(
    key = "room_${room.name}",
    icon = room.icon ?: MkIcons.Thermometer,
    label = room.name,
    value = room.temp,
    unit = "°C",
    statStatus = MkStatStatus.None,
    tag = null,
    tagStatus = MkTagStatus.Neutral,
    detailStatus = "accent",
    detailUnit = "°C",
    seriesValues = room.history,
    labels = emptyList(),
    stats = emptyList(),
    detailMeasurement = "rooms",
    detailField = room.historyField,
)

/** A "no data yet" placeholder inside a [DashCard]. */
@Composable
private fun DashEmpty(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MkTheme.type.label, color = MkTheme.colors.inkLo)
    }
}

/** Two-column grid of room temperature readouts; a tap opens the room's history. */
@Composable
private fun RoomsGrid(rooms: List<MkClimateRoom>, onRoomClick: (MkClimateRoom) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rooms.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pair.forEach { room ->
                    RoomChip(
                        room,
                        Modifier.weight(1f),
                        onClick = if (room.historyField != null) ({ onRoomClick(room) }) else null,
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RoomChip(room: MkClimateRoom, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = MkTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MkRadius.sm))
            .background(c.surfaceInset)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        room.icon?.let { Icon(it, null, tint = c.inkLo, modifier = Modifier.size(15.dp)) }
        Text(
            text = room.name,
            style = MkTheme.type.body,
            color = c.inkMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("${room.temp}°", style = MkTheme.type.readout(14), color = c.inkHi, maxLines = 1)
    }
}

/** Kiosk stat tiles per row: the design's single row of seven (repeat(7,1fr)). */
private const val STATS_PER_ROW = 7

/** Height reserved for the temperature chart's legend so the canvas fills the rest. */
private val CHART_LEGEND_ALLOWANCE = 48.dp

/** Events shown in the kiosk feed — bounded so it never spills past its panel. */
private const val KIOSK_EVENT_COUNT = 4

/** Rolling 24 h axis ticks for the temperature chart. */
private val TEMP_LABELS = listOf("-24 h", "-18", "-12", "-6", "nyt")
