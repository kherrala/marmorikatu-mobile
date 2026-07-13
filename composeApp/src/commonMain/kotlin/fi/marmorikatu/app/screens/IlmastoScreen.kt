package fi.marmorikatu.app.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import fi.marmorikatu.core.model.RuuviReading
import fi.marmorikatu.core.model.RuuviSensors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import fi.marmorikatu.app.components.MkBanner
import fi.marmorikatu.app.platform.LockLandscapeWhileVisible
import fi.marmorikatu.app.shell.UiSignals
import org.koin.compose.koinInject
import fi.marmorikatu.app.components.MkButton
import fi.marmorikatu.app.components.MkButtonSize
import fi.marmorikatu.app.components.MkButtonVariant
import fi.marmorikatu.app.components.MkCard
import fi.marmorikatu.app.components.MkCardHead
import fi.marmorikatu.app.components.MkFreshness
import fi.marmorikatu.app.components.MkGauge
import fi.marmorikatu.app.components.MkVentilationDiagram
import fi.marmorikatu.app.components.MkLineChart
import fi.marmorikatu.app.components.MkMetricDetail
import fi.marmorikatu.app.components.MkPullToRefresh
import fi.marmorikatu.app.components.MkSeries
import fi.marmorikatu.app.components.MkStatTile
import fi.marmorikatu.app.components.MkTag
import fi.marmorikatu.app.components.MkTagStatus
import fi.marmorikatu.app.components.TimeRangeOption
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.core.model.Cooling
import fi.marmorikatu.core.model.Floor
import fi.marmorikatu.core.model.HeatPumpStatus
import fi.marmorikatu.core.model.HeatingDemand
import fi.marmorikatu.core.model.RoomTemperature
import fi.marmorikatu.core.model.Rooms
import fi.marmorikatu.core.model.Ventilation
import org.koin.compose.viewmodel.koinViewModel

private const val NO_DATA = "Ei tietoa"

/**
 * The Ruuvi that backs the air-quality readouts (VOC/temperature/humidity). The
 * backend `get_air_quality` hardcodes this same sensor, so the focus charts must
 * filter to it or the shared `ruuvi` series averages every sensor together.
 */
private const val AIR_SENSOR = "Keittiö"

/** The four focused views of the climate screen, matching the design's sub-tabs. */
private enum class IlmastoSub(val label: String) {
    Lampo("Lämpötilat"),
    Ilma("Ilmanlaatu"),
    Anturit("Anturit"),
    Maalampo("Maalämpö"),
    Ilmanvaihto("Ilmanvaihto"),
    Jaahdytys("Jäähdytys"),
}

/**
 * Axis ticks describing the selected window. The intraday windows are *rolling*
 * (`-6h` / `-24h`, ending now), so they read as hours-before-now, not a fixed
 * wall clock — the old "00:00…18:00" mislabeled every 24 h tick but "nyt".
 */
private fun ilmastoAxisLabels(range: TimeRangeOption): List<String> = when (range) {
    TimeRangeOption.H6 -> listOf("-6 t", "-4 t", "-2 t", "nyt")
    TimeRangeOption.H24 -> listOf("-24 t", "-20 t", "-16 t", "-12 t", "-8 t", "-4 t", "nyt")
    TimeRangeOption.D7 -> listOf("ma", "ke", "pe", "su")
    TimeRangeOption.D30 -> listOf("1.", "10.", "20.", "30.")
    TimeRangeOption.Y1 -> listOf("tammi", "huhti", "heinä", "loka")
}

/** A readout the user tapped to focus, with its InfluxDB history source. */
data class FocusMetric(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val unit: String,
    val measurement: String,
    val field: String,
    /**
     * Optional tag filter for series where one field is shared across sensors —
     * e.g. `ruuvi`/`temperature` spans every Ruuvi, so the air-quality readouts
     * pin `sensor_name = "Keittiö"` or the chart averages fridge/outdoor/rooms.
     */
    val tagKey: String? = null,
    val tagValue: String? = null,
)

/** Ilmasto (climate): a sticky sub-tab bar scroll-spies over stacked sections. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun IlmastoScreen(
    viewModel: IlmastoViewModel = koinViewModel(),
    forceLandscapeDetail: Boolean = false,
) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    val colors = MkTheme.colors
    val snapshot by viewModel.snapshot.collectAsState()
    val rooms by viewModel.roomTemperatures.collectAsState()
    val heating by viewModel.heatingDemand.collectAsState()
    val ventilation by viewModel.ventilation.collectAsState()
    val cooling by viewModel.cooling.collectAsState()
    val heatPump by viewModel.heatPump.collectAsState()
    val ruuvi by viewModel.ruuvi.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val updatedAt by viewModel.updatedAt.collectAsState()
    val focusSeries by viewModel.focusSeries.collectAsState()
    val focusLoading by viewModel.focusLoading.collectAsState()

    // Focus selection lives in the ViewModel so it survives the landscape
    // recreation of this pager page (see KotiScreen).
    val focus by viewModel.focus.collectAsState()
    val openFocus: (FocusMetric) -> Unit = { m -> viewModel.openFocus(m) }
    val closeFocus = { viewModel.closeFocus() }

    // System back leaves the focus chart for the readouts instead of quitting.
    BackHandler(enabled = focus != null) { closeFocus() }

    // On the phone, a focus chart turns to landscape (even under an orientation
    // lock) and keeps the phone surface while it is wide.
    if (forceLandscapeDetail) {
        val uiSignals: UiSignals = koinInject()
        // Synchronous commit (SideEffect), not a coroutine — see KotiScreen.
        SideEffect { uiSignals.detailOpen.value = focus != null }
        DisposableEffect(Unit) { onDispose { uiSignals.detailOpen.value = false } }
        if (focus != null) LockLandscapeWhileVisible()
    }

    // Seed the list from the VM-preserved position and write scroll changes back,
    // so returning from a focus chart (which recreates this screen via the forced
    // landscape) lands where the user left off instead of at the top.
    val listState = rememberLazyListState(viewModel.listScrollIndex, viewModel.listScrollOffset)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                viewModel.listScrollIndex = index
                viewModel.listScrollOffset = offset
            }
    }
    val scope = rememberCoroutineScope()
    // Scroll-spy: the highlighted sub-tab follows the top-most visible section.
    // The list is [sticky tabs, Lämpö, Ilma, Maalämpö, Ilmanvaihto, Jäähdytys],
    // so the sections start at item index 1 (index 0 is the sticky tab bar).
    val sectionStart = 1
    val activeSub by remember {
        derivedStateOf {
            IlmastoSub.entries[(listState.firstVisibleItemIndex - sectionStart).coerceIn(0, IlmastoSub.entries.lastIndex)]
        }
    }

    MkPullToRefresh(refreshing = refreshing, onRefresh = viewModel::refresh) {
        val f = focus
        if (f != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.appBg)
                    // Swipe the focus page to the right to return to the readouts.
                    .pointerInput(f.label) {
                        var dragged = 0f
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragged > 64.dp.toPx()) closeFocus()
                                dragged = 0f
                            },
                            onHorizontalDrag = { _, delta -> dragged += delta },
                        )
                    }
                    .verticalScroll(rememberScrollState())
                    .padding(MkSpacing.pagePad),
                verticalArrangement = Arrangement.spacedBy(MkSpacing.stackGap),
            ) {
                MetricFocusView(f, focusSeries, focusLoading, onBack = closeFocus)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().background(colors.appBg),
                state = listState,
                contentPadding = PaddingValues(
                    start = MkSpacing.pagePad,
                    end = MkSpacing.pagePad,
                    top = MkSpacing.x4,
                    bottom = MkSpacing.x4 + MkSpacing.scrollBottomGap,
                ),
                verticalArrangement = Arrangement.spacedBy(MkSpacing.stackGap),
            ) {
                // Sticky sub-tab bar: tapping a tab anchor-scrolls to its section.
                stickyHeader(key = "tabs") {
                    Box(modifier = Modifier.fillMaxWidth().background(colors.appBg).padding(vertical = 4.dp)) {
                        SubTabBar(
                            active = activeSub,
                            onSelect = { s -> scope.launch { listState.animateScrollToItem(s.ordinal + sectionStart) } },
                        )
                    }
                }
                item(key = "lampo") {
                    Column(verticalArrangement = Arrangement.spacedBy(MkSpacing.stackGap)) {
                        HistoryCard(snapshot, viewModel)
                        RoomsCard(rooms, heating)
                    }
                }
                item(key = "ilma") { AirQualityCard(snapshot, openFocus) }
                item(key = "anturit") { AnturitSection(ruuvi, openFocus) }
                item(key = "maalampo") { HeatPumpCard(heatPump, openFocus) }
                item(key = "iv") { VentilationCard(snapshot, ventilation, openFocus) }
                item(key = "jaahdytys") { JaahdytysSection(ventilation, cooling, openFocus) }
                if (snapshot.failed) {
                    item(key = "failed") {
                        Text(
                            text = "Ilmastotietoja ei juuri nyt saatavilla.",
                            style = MkTheme.type.caption,
                            color = colors.inkLo,
                        )
                    }
                }
            }
        }
    }
}

/** The tapped readout's 24 h history chart, with a "Takaisin" affordance. */
@Composable
private fun ColumnScope.MetricFocusView(
    focus: FocusMetric,
    series: List<Float>,
    loading: Boolean,
    onBack: () -> Unit,
) {
    val colors = MkTheme.colors
    val chart = if (series.size < 2) emptyList()
    else listOf(MkSeries(name = null, values = series, color = colors.accent, area = true))
    MkMetricDetail(
        icon = focus.icon,
        label = focus.label,
        value = focus.value,
        unit = focus.unit,
        series = chart,
        labels = ilmastoAxisLabels(TimeRangeOption.H24),
        stats = emptyList(),
        onBack = onBack,
    )
    if (loading) {
        Text("Ladataan historiaa…", style = MkTheme.type.label, color = colors.inkLo)
    } else if (series.size < 2) {
        Text("Ei historiaa saatavilla.", style = MkTheme.type.label, color = colors.inkLo)
    }
}

// ── Sub-tab bar ─────────────────────────────────────────────────────────────

@Composable
private fun SubTabBar(active: IlmastoSub, onSelect: (IlmastoSub) -> Unit) {
    val colors = MkTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2),
    ) {
        IlmastoSub.entries.forEach { tab ->
            val selected = tab == active
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(MkRadius.round))
                    // The design's selected pill is a soft teal tint, not a solid fill.
                    .background(if (selected) colors.accentDim else colors.surfaceCard)
                    .border(
                        1.dp,
                        if (selected) colors.accentBorder else colors.borderSubtle,
                        RoundedCornerShape(MkRadius.round),
                    )
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = tab.label,
                    style = MkTheme.type.label,
                    color = if (selected) colors.accent else colors.inkMid,
                )
            }
        }
    }
}

// ── Lämpötilat: history + rooms ─────────────────────────────────────────────

@Composable
private fun HistoryCard(snapshot: IlmastoSnapshot, viewModel: IlmastoViewModel) {
    val colors = MkTheme.colors
    val history = snapshot.history

    val series = buildList {
        history["Ulko"]?.let { points ->
            add(MkSeries("Ulko", points.map { it.value.toFloat() }, colors.vizOutdoor, area = true))
        }
        val roomColors = listOf(colors.vizRoom, colors.vizSecondary, colors.vizTertiary)
        val roomNames = Rooms.livingSpaces
            .map { it.displayName }
            .filter { history.containsKey(it) }
            .take(3)
        roomNames.forEachIndexed { i, name ->
            add(MkSeries(name, history.getValue(name).map { it.value.toFloat() }, roomColors[i]))
        }
    }

    MkCard {
        MkCardHead(title = "Lämpötilat")
        // The five-segment control needs the full width on a phone; inline in
        // the head it would crush the title to one letter per line.
        fi.marmorikatu.app.components.MkTimeRange(
            value = snapshot.range,
            onChange = viewModel::selectRange,
            modifier = Modifier.padding(bottom = MkSpacing.x3),
        )
        if (series.any { it.values.isNotEmpty() }) {
            MkLineChart(series = series, labels = ilmastoAxisLabels(snapshot.range), height = 200.dp)
        } else {
            Text(text = NO_DATA, style = MkTheme.type.readout(15), color = colors.inkLo)
        }
    }
}

// ── Ilmanlaatu: humidity / CO₂ / PM2.5 gauges + tiles ───────────────────────

@Composable
private fun AirQualityCard(snapshot: IlmastoSnapshot, onFocus: (FocusMetric) -> Unit) {
    val hvac = snapshot.hvac
    val air = snapshot.air

    // Backend returns "Kitchen (Keittiö)"; show only the Finnish part (audit #17).
    val locationFi = air?.location?.takeIf { it.isNotBlank() }?.let { raw ->
        Regex("\\(([^)]+)\\)").find(raw)?.groupValues?.get(1) ?: raw
    }
    MkCard {
        MkCardHead("Ilmanlaatu" + (locationFi?.let { " · $it" } ?: ""))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val humidity = air?.humidity?.value ?: hvac?.humidityPct
            // Only the ruuvi humidity has a history series to focus; the hvac
            // fallback isn't the same measurement, so it stays non-tappable.
            GaugeSlot(onClick = air?.humidity?.let { h ->
                { onFocus(FocusMetric(MkIcons.DropHalf, "Kosteus", Fmt.int(h.value), "%", "ruuvi", "humidity", tagKey = "sensor_name", tagValue = AIR_SENSOR)) }
            }) {
                if (humidity != null) {
                    MkGauge(value = humidity.toFloat(), max = 100f, label = "Kosteus", unit = "%", status = "accent", size = 96.dp)
                } else GaugePlaceholder("Kosteus")
            }
            val co2 = air?.co2
            GaugeSlot(onClick = co2?.let { c ->
                { onFocus(FocusMetric(MkIcons.Wind, "CO₂", Fmt.int(c.value), "ppm", "ruuvi", "co2", tagKey = "sensor_name", tagValue = AIR_SENSOR)) }
            }) {
                if (co2 != null) {
                    MkGauge(value = co2.value.toFloat(), max = 1500f, label = "CO₂ ppm", unit = "", status = air.statusOf(co2) ?: "accent", size = 96.dp)
                } else GaugePlaceholder("CO₂")
            }
            val pm = air?.pm25
            GaugeSlot(onClick = pm?.let { p ->
                { onFocus(FocusMetric(MkIcons.Wind, "PM2.5", Fmt.oneDecimal(p.value), "µg/m³", "ruuvi", "pm2_5", tagKey = "sensor_name", tagValue = AIR_SENSOR)) }
            }) {
                if (pm != null) {
                    MkGauge(value = pm.value.toFloat(), max = 35f, label = "PM2.5", unit = "", status = air.statusOf(pm) ?: "ok", size = 96.dp)
                } else GaugePlaceholder("PM2.5")
            }
        }
        val voc = air?.voc
        val temp = air?.temperature
        if (voc != null || temp != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = MkSpacing.x3),
                horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3),
            ) {
                MkStatTile(
                    label = "VOC-indeksi",
                    value = voc?.let { Fmt.int(it.value) } ?: NO_DATA,
                    icon = MkIcons.Wind,
                    onClick = voc?.let {
                        { onFocus(FocusMetric(MkIcons.Wind, "VOC-indeksi", Fmt.int(it.value), "", "ruuvi", "voc", tagKey = "sensor_name", tagValue = AIR_SENSOR)) }
                    },
                    modifier = Modifier.weight(1f),
                )
                MkStatTile(
                    label = "Lämpötila",
                    value = temp?.let { Fmt.oneDecimal(it.value) } ?: NO_DATA,
                    unit = temp?.let { "°C" },
                    icon = MkIcons.Thermometer,
                    onClick = temp?.let {
                        { onFocus(FocusMetric(MkIcons.Thermometer, "Lämpötila", Fmt.oneDecimal(it.value), "°C", "ruuvi", "temperature", tagKey = "sensor_name", tagValue = AIR_SENSOR)) }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ── Maalämpö: live heat-pump register readouts ──────────────────────────────

/** One heat-pump readout; a non-null [field] makes the tile open a focus chart. */
private data class HpTile(
    val label: String,
    val value: String?,
    val unit: String?,
    val icon: ImageVector,
    val field: String?,
)

@Composable
private fun HeatPumpCard(hp: HeatPumpStatus, onFocus: (FocusMetric) -> Unit) {
    val colors = MkTheme.colors
    MkCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = MkSpacing.x3),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Maalämpö", style = MkTheme.type.heading, color = colors.inkHi)
            val state = when {
                !hp.available -> NO_DATA
                hp.running -> "Käy"
                else -> "Seis"
            }
            Text(state, style = MkTheme.type.readout(13), color = if (hp.running) colors.accent else colors.inkMid)
        }
        if (!hp.available) {
            Text(NO_DATA, style = MkTheme.type.readout(15), color = colors.inkLo)
            return@MkCard
        }
        fun c(v: Double?) = v?.let { "${Fmt.oneDecimal(it)}" }
        // Two columns of readouts, drawn from the ThermIQ register feed. COP leads
        // as the headline efficiency metric (design's ph-gauge tile). Tapping a
        // temperature opens its 24 h chart (thermia measurement fields).
        val tiles = listOf(
            HpTile("COP", hp.cop?.let { Fmt.oneDecimal(it) }, null, MkIcons.Leaf, null),
            HpTile("Menovesi", c(hp.supplyC), "°C", MkIcons.ThermometerHot, "supply_temp"),
            HpTile("Paluuvesi", c(hp.returnC), "°C", MkIcons.ThermometerCold, "return_temp"),
            HpTile("Käyttövesi", c(hp.hotWaterC), "°C", MkIcons.DropFill, "hotwater_temp"),
            HpTile("Liuos ulos", c(hp.brineOutC), "°C", MkIcons.Snowflake, "brine_out_temp"),
            HpTile("Liuos sisään", c(hp.brineInC), "°C", MkIcons.Drop, "brine_in_temp"),
            HpTile("Ulkona", c(hp.outdoorC), "°C", MkIcons.ThermometerCold, "outdoor_temp"),
        )
        tiles.chunked(2).forEach { rowTiles ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = MkSpacing.x2),
                horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3),
            ) {
                rowTiles.forEach { tile ->
                    MkStatTile(
                        label = tile.label,
                        value = tile.value ?: NO_DATA,
                        unit = if (tile.value != null) tile.unit else null,
                        icon = tile.icon,
                        onClick = if (tile.field != null && tile.value != null) {
                            {
                                onFocus(
                                    FocusMetric(
                                        icon = tile.icon,
                                        label = tile.label,
                                        value = tile.value,
                                        unit = tile.unit ?: "",
                                        measurement = "thermia",
                                        field = tile.field,
                                    ),
                                )
                            }
                        } else null,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowTiles.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ── Ilmanvaihto: LTO gauge + ventilation temperatures ───────────────────────

@Composable
private fun VentilationCard(
    snapshot: IlmastoSnapshot,
    ventilation: Ventilation,
    onFocus: (FocusMetric) -> Unit,
) {
    val colors = MkTheme.colors
    MkCard {
        MkCardHead("Ilmanvaihto")
        if (ventilation.freezingDanger) {
            MkBanner(
                icon = MkIcons.Snowflake,
                title = "Jäätymisvaara",
                text = "Ilmanvaihdon lämmöntalteenotto on jäätymisvaarassa.",
                status = "alarm",
                modifier = Modifier.padding(bottom = MkSpacing.x3),
            )
        }
        val lto = snapshot.hvac?.recoveryEfficiencyPct
        val ltoClick = lto?.let {
            { onFocus(FocusMetric(MkIcons.FanFill, "Lämmöntalteenotto", Fmt.int(it), "%", "hvac_lto", "lto")) }
        }
        // The MVHR system diagram (design): the four duct temperatures around the
        // heat-recovery core, with the live LTO efficiency.
        val hv = snapshot.hvac
        MkVentilationDiagram(
            outdoorC = hv?.outdoorC ?: ventilation.outdoorC,
            exhaustC = hv?.exhaustC ?: ventilation.exhaustC,
            extractC = hv?.extractC ?: ventilation.extractC,
            supplyC = hv?.supplyPostHeatC ?: ventilation.supplyC,
            preHeatC = hv?.supplyPreHeatC ?: ventilation.supplyPreHeatC,
            ltoPct = lto,
            modifier = Modifier.padding(vertical = MkSpacing.x2),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (ltoClick != null) Modifier.clickable(onClick = ltoClick) else Modifier)
                .padding(bottom = MkSpacing.x2),
            contentAlignment = Alignment.Center,
        ) {
            if (lto != null) {
                MkGauge(value = lto.toFloat(), max = 100f, label = "Lämmöntalteenotto", unit = "%", status = ltoStatus(lto))
            } else GaugePlaceholder("Lämmöntalteenotto")
        }
    }
}

@Composable
private fun ReadoutRow(label: String, celsius: Double?, onClick: (() -> Unit)? = null) {
    val colors = MkTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = MkSpacing.x2),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MkTheme.type.body, color = colors.inkMid)
        Text(
            text = celsius?.let { "${Fmt.oneDecimal(it)} °C" } ?: NO_DATA,
            style = MkTheme.type.readout(15),
            color = if (celsius != null) colors.inkHi else colors.inkLo,
        )
    }
}

// ── Jäähdytys: cooling + humidity control (read-only) ───────────────────────

/** A cooling/humidity readout; a non-null [field] makes the tile open its `hvac` history chart. */
private data class ClimTile(
    val label: String,
    val value: String?,
    val unit: String?,
    val icon: ImageVector,
    val measurement: String = "hvac",
    val field: String? = null,
)

/** Two-column grid of stat tiles (heat-pump/cooling readout style). */
@Composable
private fun StatGrid(tiles: List<ClimTile>, onFocus: (FocusMetric) -> Unit) {
    tiles.chunked(2).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = MkSpacing.x2),
            horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3),
        ) {
            row.forEach { t ->
                MkStatTile(
                    label = t.label,
                    value = t.value ?: NO_DATA,
                    unit = if (t.value != null) t.unit else null,
                    icon = t.icon,
                    onClick = if (t.value != null && t.field != null) {
                        { onFocus(FocusMetric(t.icon, t.label, t.value, t.unit ?: "", t.measurement, t.field)) }
                    } else null,
                    modifier = Modifier.weight(1f),
                )
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** Read-only mode selector: highlights the live state, not tappable (no write path). */
@Composable
private fun DisplayPills(options: List<String>, activeIndex: Int) {
    val c = MkTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MkRadius.md))
            .background(c.surfaceInset)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, opt ->
            val on = i == activeIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(MkRadius.sm))
                    .background(if (on) c.surfaceCard else androidx.compose.ui.graphics.Color.Transparent)
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(opt, style = MkTheme.type.label, color = if (on) c.inkHi else c.inkLo, maxLines = 1)
            }
        }
    }
}

@Composable
private fun JaahdytysSection(ventilation: Ventilation, cooling: Cooling, onFocus: (FocusMetric) -> Unit) {
    val c = MkTheme.colors
    val active = cooling.pumpCooling || cooling.coolingPump
    fun raw(vararg keys: String): Double? = keys.firstNotNullOfOrNull { ventilation.raw[it] }
    Column(verticalArrangement = Arrangement.spacedBy(MkSpacing.stackGap)) {
        MkCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = MkSpacing.x3),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Jäähdytys", style = MkTheme.type.heading, color = c.inkHi)
                MkTag(text = if (active) "Aktiivinen" else "Pois", status = if (active) MkTagStatus.Ok else MkTagStatus.Neutral)
            }
            DisplayPills(listOf("Pois", "Auto", "Päällä"), if (active) 2 else 0)
            StatGrid(
                listOf(
                    // meno = the chilled feed (consistently the colder coil = Jaahpatteri_2);
                    // paluu = the warmer return coil (Jaahpatteri_1). Venttiili = actuator drive.
                    ClimTile("Jäähd. meno", cooling.coolantSupplyC?.let { Fmt.oneDecimal(it) }, "°C", MkIcons.Snowflake, field = "Jaahpatteri_2"),
                    ClimTile("Jäähd. paluu", cooling.coolantReturnC?.let { Fmt.oneDecimal(it) }, "°C", MkIcons.ThermometerCold, field = "Jaahpatteri_1"),
                    ClimTile("Venttiili", raw("damperposition")?.let { Fmt.int(it) }, "%", MkIcons.Drop, field = "Toimilaite_ohjaus"),
                    ClimTile("Kiertopumppu", if (active) "Päällä" else "Pois", null, MkIcons.FanFill),
                ),
                onFocus,
            )
            // ASETUSARVOT — the PLC's cooling control setpoints (rMaxInAir /
            // rMinInAir / outdoor-min / dew-point margin). These are persistent
            // PLC config, not published live, so shown as the configured values.
            val setpoints = listOf(
                "Max sisäilma" to "23.0 °C",
                "Min sisäilma" to "22.5 °C",
                "Min ulkoilma" to "10.0 °C",
                "Ero kastepisteeseen" to "0.0 °C",
            )
            Text(
                "ASETUSARVOT",
                style = MkTheme.type.label,
                color = c.inkLo,
                modifier = Modifier.padding(top = MkSpacing.x3, bottom = MkSpacing.x1),
            )
            setpoints.forEachIndexed { i, (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = MkSpacing.x2),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = MkTheme.type.body, color = c.inkMid)
                    Text(value, style = MkTheme.type.readout(14), color = c.inkHi)
                }
                if (i < setpoints.lastIndex) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
                }
            }
        }
        MkCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = MkSpacing.x3),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Kosteusohjaus", style = MkTheme.type.heading, color = c.inkHi)
                Text("Max 60 %", style = MkTheme.type.readout(11), color = c.inkLo)
            }
            StatGrid(
                listOf(
                    ClimTile("Suhteellinen", (ventilation.relativeHumidity ?: raw("relativehumidity", "indoorrh"))?.let { Fmt.int(it) }, "%", MkIcons.DropHalf, field = "Suhteellinen_kosteus"),
                    ClimTile("Absoluuttinen", raw("abshumidity")?.let { Fmt.oneDecimal(it) }, "g/m³", MkIcons.Drop, field = "Absoluuttinen_kosteus"),
                    ClimTile("Kastepiste", raw("dewpoint")?.let { Fmt.oneDecimal(it) }, "°C", MkIcons.ThermometerCold, field = "Kastepiste"),
                    ClimTile("Entalpia", raw("enthalpy")?.let { Fmt.oneDecimal(it) }, "kJ/kg", MkIcons.Leaf, field = "Entalpia"),
                ),
                onFocus,
            )
        }
    }
}

// ── Gauges ──────────────────────────────────────────────────────────────────

@Composable
private fun GaugeSlot(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = if (onClick != null) {
            Modifier.clip(RoundedCornerShape(MkRadius.md)).clickable(onClick = onClick)
        } else Modifier,
    ) { content() }
}

@Composable
private fun GaugePlaceholder(label: String) {
    val colors = MkTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.size(120.dp),
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(text = NO_DATA, style = MkTheme.type.readout(15), color = colors.inkLo)
        }
        Text(text = label, style = MkTheme.type.label, color = colors.inkMid)
    }
}

/**
 * Heat recovery reads 0 % whenever the MVHR bypass is open — normal all
 * summer. Only a running-but-poor exchanger deserves a warning colour.
 */
private fun ltoStatus(value: Double): String = when {
    value <= 0 -> "accent"
    value >= 60 -> "ok"
    value >= 40 -> "warn"
    else -> "alarm"
}

// ── Anturit: the Ruuvi tag readings ─────────────────────────────────────────

@Composable
private fun AnturitSection(ruuvi: Map<String, RuuviReading>, onFocus: (FocusMetric) -> Unit) {
    val c = MkTheme.colors
    data class Anturi(val name: String, val icon: ImageVector, val value: String, val warn: Boolean, val focus: FocusMetric?)
    // Each Ruuvi temperature opens its 24 h history (ruuvi/temperature filtered to
    // that sensor). Pressure has no per-sensor detail here (the user asked for the
    // temperatures), so it stays a plain readout.
    fun tempFocus(name: String, icon: ImageVector, valueStr: String, sensor: String) =
        FocusMetric(icon, name, valueStr, "°C", "ruuvi", "temperature", tagKey = "sensor_name", tagValue = sensor)
    val pressureHpa = ruuvi.values.firstNotNullOfOrNull { it.pressure }?.let { it / 100.0 }
    val rows = buildList {
        ruuvi[RuuviSensors.FRIDGE]?.temperature?.let {
            val v = Fmt.oneDecimal(it)
            add(Anturi("Jääkaappi", MkIcons.ThermometerCold, "$v °C", it > 8.0, tempFocus("Jääkaappi", MkIcons.ThermometerCold, v, RuuviSensors.FRIDGE)))
        }
        ruuvi[RuuviSensors.FREEZER]?.temperature?.let {
            val v = Fmt.oneDecimal(it)
            add(Anturi("Pakastin", MkIcons.Snowflake, "$v °C", it > -15.0, tempFocus("Pakastin", MkIcons.Snowflake, v, RuuviSensors.FREEZER)))
        }
        ruuvi["Takka"]?.temperature?.let {
            val v = Fmt.int(it)
            add(Anturi("Takka", MkIcons.FlameFill, "$v °C", false, tempFocus("Takka", MkIcons.FlameFill, v, "Takka")))
        }
        ruuvi[RuuviSensors.OUTDOOR]?.temperature?.let {
            val v = Fmt.oneDecimal(it)
            add(Anturi("Ulkoilma", MkIcons.Tree, "$v °C", false, tempFocus("Ulkoilma", MkIcons.Tree, v, RuuviSensors.OUTDOOR)))
        }
        pressureHpa?.let {
            add(Anturi("Ilmanpaine", MkIcons.Gauge, "${Fmt.int(it)} hPa", false, null))
        }
    }
    if (rows.isEmpty()) return
    MkCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = MkSpacing.x3),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Anturit", style = MkTheme.type.heading, color = c.inkHi)
            Text("Ruuvi", style = MkTheme.type.readout(11), color = c.inkLo)
        }
        Column(verticalArrangement = Arrangement.spacedBy(MkSpacing.x2)) {
            rows.forEach { a ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MkRadius.sm))
                        .background(if (a.warn) c.warmDim else c.surfaceCard)
                        .border(1.dp, if (a.warn) c.warm else c.borderSubtle, RoundedCornerShape(MkRadius.sm))
                        .then(if (a.focus != null) Modifier.clickable { onFocus(a.focus) } else Modifier)
                        .padding(horizontal = MkSpacing.x3, vertical = MkSpacing.x2),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3),
                ) {
                    Icon(a.icon, null, tint = if (a.warn) c.warm else c.accent, modifier = Modifier.size(20.dp))
                    Text(a.name, style = MkTheme.type.body, color = c.inkHi, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(8.dp).clip(CircleShape)
                            .background(if (a.warn) c.warm else c.accent),
                    )
                    Text(a.value, style = MkTheme.type.readout(15), color = c.inkHi)
                    // Chevron hints the row drills into a history chart.
                    if (a.focus != null) {
                        Icon(MkIcons.CaretRight, null, tint = c.inkLo, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

// ── Room list ───────────────────────────────────────────────────────────────

@Composable
private fun RoomsCard(rooms: List<RoomTemperature>, heating: List<HeatingDemand>) {
    val colors = MkTheme.colors
    MkCard {
        MkCardHead("Huoneet")
        if (rooms.isEmpty()) {
            Text(text = NO_DATA, style = MkTheme.type.readout(15), color = colors.inkLo)
            return@MkCard
        }
        val demandByKey = heating.associateBy { it.key }
        rooms.forEach { room ->
            val known = Rooms.byMqttKey[room.key]
            val demand = demandByKey[room.key]?.percent ?: 0
            RoomRow(
                icon = floorIcon(known?.floor ?: room.floor),
                name = room.name,
                celsius = room.celsius,
                demandPct = demand,
            )
        }
    }
}

@Composable
private fun RoomRow(icon: ImageVector, name: String, celsius: Double, demandPct: Int) {
    val colors = MkTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = MkSpacing.x2),
        horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.inkLo,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = name,
            style = MkTheme.type.body,
            color = colors.inkHi,
            modifier = Modifier.weight(1f),
        )
        if (demandPct > 0) {
            Text(
                text = "$demandPct %",
                style = MkTheme.type.readout(13),
                color = colors.warm,
                modifier = Modifier.padding(end = MkSpacing.x3),
            )
        }
        Text(
            text = "${Fmt.oneDecimal(celsius)} °C",
            style = MkTheme.type.readout(16),
            color = colors.inkHi,
        )
    }
}

private fun floorIcon(floor: Floor): ImageVector = when (floor) {
    Floor.KELLARI -> MkIcons.Stairs
    Floor.ALAKERTA -> MkIcons.House
    Floor.YLAKERTA -> MkIcons.Bed
    Floor.ULKO -> MkIcons.Sun
}
