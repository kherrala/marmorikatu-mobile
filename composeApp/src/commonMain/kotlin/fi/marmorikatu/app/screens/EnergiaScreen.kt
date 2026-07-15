package fi.marmorikatu.app.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fi.marmorikatu.app.components.MkCard
import fi.marmorikatu.app.components.MkCardHead
import fi.marmorikatu.app.components.MkMetricDetailPage
import fi.marmorikatu.app.components.MkPriceBars
import fi.marmorikatu.app.components.MkPullToRefresh
import fi.marmorikatu.app.components.MkSeries
import fi.marmorikatu.app.components.MkStat
import fi.marmorikatu.app.components.MkStatStatus
import fi.marmorikatu.app.components.MkStatTile
import fi.marmorikatu.app.components.MkTag
import fi.marmorikatu.app.components.MkTagStatus
import fi.marmorikatu.app.components.TimeRangeOption
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.platform.LockLandscapeWhileVisible
import fi.marmorikatu.app.shell.UiSignals
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.core.model.EnergyReading
import fi.marmorikatu.core.model.PriceTier
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.coroutines.coroutineContext

private const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L

/** The scroll-anchored sub-sections of the Energia tab, in display order. */
private enum class EnSection(val label: String) {
    Hinta("Hinta"),
    Mittari("Mittari"),
    Kulutus("Kulutus"),
    Optimointi("Optimointi"),
    Valot("Valot"),
}

/**
 * Energia: pörssisähkön hinta, sähkömittari, kulutus ja kustannus valittavalla
 * aikavälillä, sekä lämmityksen ja valojen optimointi. Yläreunan välilehdet
 * vierittävät kuhunkin osioon (kuten Valot-näytön kerrospalkki). Mittarien ja
 * hintojen lukemat avaavat koko sivun historiakäyrän (kuten Ilmasto-näyttö).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun EnergiaScreen(
    modifier: Modifier = Modifier,
    viewModel: EnergiaViewModel = koinViewModel(),
    forceLandscapeDetail: Boolean = false,
) {
    val priceState by viewModel.prices.collectAsState()
    val liveEnergy by viewModel.liveEnergy.collectAsState()
    val range by viewModel.range.collectAsState()
    val cost by viewModel.cost.collectAsState()
    val costLoading by viewModel.costLoading.collectAsState()
    val heatingOpti by viewModel.heatingOpti.collectAsState()
    val lightUsage by viewModel.lightUsage.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val focus by viewModel.focus.collectAsState()
    val focusSeries by viewModel.focusSeries.collectAsState()
    val focusLoading by viewModel.focusLoading.collectAsState()
    val focusRange by viewModel.focusRange.collectAsState()

    // System back leaves the focus chart for the readouts instead of quitting.
    BackHandler(enabled = focus != null) { viewModel.closeFocus() }

    // On the phone, a focus chart turns to landscape (even under an orientation
    // lock) and keeps the phone surface while it is wide — see IlmastoScreen.
    if (forceLandscapeDetail) {
        val uiSignals: UiSignals = koinInject()
        // Synchronous commit (SideEffect), not a coroutine — see KotiScreen.
        SideEffect { uiSignals.setDetailOpen("energia", focus != null) }
        DisposableEffect(Unit) { onDispose { uiSignals.setDetailOpen("energia", false) } }
        if (focus != null) LockLandscapeWhileVisible()
    }

    LaunchedEffect(Unit) {
        while (coroutineContext.isActive) {
            viewModel.refresh()
            delay(REFRESH_INTERVAL_MS)
        }
    }

    // Seed the list from the VM-preserved position and write scroll changes
    // back, so returning from a focus chart (whose forced landscape recreates
    // this pager page) lands where the user left off instead of at the top.
    val listState = rememberLazyListState(viewModel.listScrollIndex, viewModel.listScrollOffset)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                viewModel.listScrollIndex = index
                viewModel.listScrollOffset = offset
            }
    }
    val scope = rememberCoroutineScope()
    val sections = EnSection.entries
    // Layout: sticky tabs = item 0, then one item per section (Hinta = 1 … Valot = 5).
    val barPx = with(LocalDensity.current) { 56.dp.roundToPx() }
    // The current section is the last one whose top has scrolled up to (or past) the
    // sticky bar — read from layout offsets so it's correct even with the tab bar as
    // the first (pinned) item.
    val activeSection by remember {
        derivedStateOf {
            val secs = listState.layoutInfo.visibleItemsInfo.filter { it.index in 1..sections.size }
            val current = secs.lastOrNull { it.offset <= barPx } ?: secs.firstOrNull()
            current?.let { sections[it.index - 1] } ?: EnSection.Hinta
        }
    }

    MkPullToRefresh(refreshing = refreshing, onRefresh = viewModel::refresh) {
        val f = focus
        if (f != null) {
            EnergiaFocusPage(
                f = f,
                focusSeries = focusSeries,
                focusLoading = focusLoading,
                focusRange = focusRange,
                onRangeChange = viewModel::setFocusRange,
                cost = cost,
                costRange = range,
                costLoading = costLoading,
                onBack = viewModel::closeFocus,
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = modifier.fillMaxSize().background(MkTheme.colors.appBg),
                contentPadding = PaddingValues(
                    start = MkSpacing.pagePad,
                    end = MkSpacing.pagePad,
                    top = MkSpacing.x2,
                    bottom = MkSpacing.pagePad + MkSpacing.scrollBottomGap,
                ),
                verticalArrangement = Arrangement.spacedBy(MkSpacing.x3),
            ) {
                stickyHeader(key = "tabs") {
                    EnSubTabs(
                        active = activeSection,
                        onSelect = { section ->
                            scope.launch {
                                listState.animateScrollToItem(sections.indexOf(section) + 1, -barPx)
                            }
                        },
                    )
                }
                item(key = "hinta") { PriceCard(priceState, viewModel::openFocus) }
                item(key = "mittari") { MetersCard(liveEnergy, viewModel::openFocus) }
                item(key = "kulutus") { KulutusSection(cost, range, costLoading, viewModel::setRange, viewModel::openFocus) }
                item(key = "optimointi") { HeatingOptiCard(heatingOpti, viewModel::openFocus) }
                item(key = "valot") { LightUsageCard(lightUsage) }
            }
        }
    }
}

/**
 * The tapped readout's full-page history chart. Most metrics chart an InfluxDB
 * series at a selectable window; the Kulutus/Kustannus tiles chart the section's
 * already-loaded per-bucket kWh trend instead (its window comes from the
 * section's own 24 h / 7 pv / 30 pv / 12 kk selector, so no separate picker).
 */
@Composable
private fun EnergiaFocusPage(
    f: FocusMetric,
    focusSeries: List<Float>,
    focusLoading: Boolean,
    focusRange: TimeRangeOption,
    onRangeChange: (TimeRangeOption) -> Unit,
    cost: CostView?,
    costRange: EnergyRange,
    costLoading: Boolean,
    onBack: () -> Unit,
) {
    val c = MkTheme.colors
    if (f.measurement == EnergiaViewModel.KULUTUS_TREND) {
        val values = cost?.trend.orEmpty()
        val series = if (values.size < 2) emptyList()
        else listOf(MkSeries(name = null, values = values, color = c.accent, area = true))
        val stats = buildList {
            cost?.kwh?.let { add(MkStat("kWh", it)) }
            cost?.cost?.let { add(MkStat("€", it)) }
            cost?.avg?.let { add(MkStat("ka", "$it c/kWh")) }
            cost?.peak?.let { add(MkStat("huippu", it)) }
            cost?.cheap?.let { add(MkStat("halvin", it)) }
            // Explicit kWh min/max (and a ka fallback): the plotted buckets are
            // kWh whichever tile opened this, so MkMetricDetail's derived stats
            // must not label them with the Kustannus tile's € unit.
            if (values.size >= 2) {
                fun kwh(v: Float) = "${Fmt.oneDecimal(v.toDouble())} kWh"
                add(MkStat("min", kwh(values.min())))
                add(MkStat("max", kwh(values.max())))
                if (cost?.avg == null) add(MkStat("ka", kwh(values.average().toFloat())))
            }
        }
        MkMetricDetailPage(
            icon = f.icon,
            label = "${f.label} · ${costRange.window}",
            value = (if (f.field == "eur") cost?.cost else cost?.kwh) ?: "—",
            unit = if (f.field == "eur") "€" else "kWh",
            series = series,
            labels = chartLabels(costRange.toTimeRange()),
            stats = stats,
            onBack = onBack,
            loading = costLoading,
            swipeKey = f.label,
        )
    } else {
        val series = if (focusSeries.size < 2) emptyList()
        else listOf(MkSeries(name = null, values = focusSeries, color = c.accent, area = true))
        MkMetricDetailPage(
            icon = f.icon,
            label = f.label,
            value = f.value,
            unit = f.unit,
            series = series,
            labels = chartLabels(focusRange),
            stats = emptyList(),
            range = focusRange,
            onRangeChange = onRangeChange,
            onBack = onBack,
            loading = focusLoading,
            swipeKey = f.label,
        )
    }
}

/** The Kulutus window → the shared chart-label range (they line up 1:1). */
private fun EnergyRange.toTimeRange(): TimeRangeOption = when (this) {
    EnergyRange.H24 -> TimeRangeOption.H24
    EnergyRange.D7 -> TimeRangeOption.D7
    EnergyRange.D30 -> TimeRangeOption.D30
    EnergyRange.Y1 -> TimeRangeOption.Y1
}

// ── Sub-tab bar ──────────────────────────────────────────────────────────────

@Composable
private fun EnSubTabs(active: EnSection, onSelect: (EnSection) -> Unit) {
    val c = MkTheme.colors
    Box(modifier = Modifier.fillMaxWidth().background(c.appBg).padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EnSection.entries.forEach { section ->
                val on = section == active
                val shape = RoundedCornerShape(MkRadius.round)
                Row(
                    modifier = Modifier
                        .clip(shape)
                        .background(if (on) c.accentDim else c.surfaceCard)
                        .border(1.dp, if (on) c.accent else c.borderSubtle, shape)
                        .clickable { onSelect(section) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = section.label,
                        style = MkTheme.type.label.copy(fontWeight = FontWeight.SemiBold),
                        color = if (on) c.accent else c.inkMid,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ── Hinta ────────────────────────────────────────────────────────────────────

@Composable
private fun PriceCard(state: PriceState, onFocus: (FocusMetric) -> Unit) {
    // Drops "tänään" once the curve carries into tomorrow's prices, so the title
    // stays honest when the chart spans two days.
    val spansTomorrow = (state as? PriceState.Ready)?.model?.spansTomorrow == true
    // Tapping the card opens the stored spot-price history at a selectable window.
    val onClick = (state as? PriceState.Ready)?.model?.currentCents?.let { cents ->
        {
            onFocus(
                FocusMetric(
                    MkIcons.LightningFill, "Sähkön hinta", Fmt.oneDecimal(cents), "c/kWh",
                    "electricity", "price_with_tax",
                ),
            )
        }
    }
    MkCard(interactive = onClick != null, onClick = onClick) {
        MkCardHead(if (spansTomorrow) "Sähkön hinta" else "Sähkön hinta tänään")
        when (state) {
            is PriceState.Ready -> {
                val m = state.model
                val (tag, status) = when (m.nowTier) {
                    PriceTier.Expensive -> "KALLIS NYT" to "warn"
                    PriceTier.Cheap -> "EDULLINEN" to "ok"
                    PriceTier.Normal -> "NORMAALI" to "info"
                    null -> null to "info"
                }
                MkPriceBars(
                    bars = m.bars,
                    labels = m.axisLabels,
                    height = 132.dp,
                    nowValue = m.currentCents?.let { Fmt.oneDecimal(it) },
                    nowUnit = "c/kWh",
                    nowTag = tag,
                    nowStatus = status,
                )
            }
            PriceState.Loading -> QuietLine("Ladataan hintoja…")
            PriceState.Failed -> QuietLine("Ei tietoa")
        }
    }
}

// ── Mittari ──────────────────────────────────────────────────────────────────

@Composable
private fun MetersCard(live: Map<String, EnergyReading>, onFocus: (FocusMetric) -> Unit) {
    val c = MkTheme.colors
    val hp = live["heatpump"]
    val ex = live["extra"]
    // Frequency and phase voltages are grid-wide, so either meter answers; the
    // cumulative energy registers are per-circuit, so sum both for a house total.
    fun rawOf(r: EnergyReading?, key: String): Double? =
        r?.raw?.entries?.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
    fun grid(key: String): Double? = rawOf(hp, key) ?: rawOf(ex, key)
    fun sum(key: String): Double? {
        val a = rawOf(hp, key)
        val b = rawOf(ex, key)
        return if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)
    }
    // The meter whose reading a grid-wide field came from, so its history chart
    // pins the same source (both meters store the field as separate series).
    fun meterOf(key: String): String? = when {
        rawOf(hp, key) != null -> "heatpump"
        rawOf(ex, key) != null -> "extra"
        else -> null
    }
    // A grid-wide readout's focus chart: hvac/<field> pinned to the meter that
    // supplied the shown value. Null (not tappable) without a live reading.
    fun gridFocus(label: String, key: String, unit: String, icon: ImageVector): (() -> Unit)? {
        val v = grid(key) ?: return null
        val m = meterOf(key) ?: return null
        return { onFocus(FocusMetric(icon, label, Fmt.oneDecimal(v), unit, "hvac", key, tagKey = "meter", tagValue = m)) }
    }

    val hpKw = hp?.powerKw
    val auxKw = ex?.powerKw
    MkCard {
        MkCardHead("Sähkömittari")
        Row(horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3)) {
            MkStatTile(
                label = "Maalämpö",
                value = hpKw?.let { Fmt.oneDecimal(it) } ?: "—",
                unit = "kW",
                icon = MkIcons.Lightning,
                onClick = hpKw?.let {
                    {
                        onFocus(
                            FocusMetric(
                                MkIcons.Lightning, "Maalämpö", Fmt.oneDecimal(it), "kW",
                                "hvac", "Total_Active_Power", tagKey = "meter", tagValue = "heatpump",
                            ),
                        )
                    }
                },
                modifier = Modifier.weight(1f),
            )
            MkStatTile(
                label = "Lisävastus",
                value = auxKw?.let { Fmt.oneDecimal(it) } ?: "—",
                unit = "kW",
                icon = MkIcons.FlameFill,
                // Only tint when the resistive heater is actually drawing power;
                // it idles at ~0.07 kW standby, which isn't worth a warn.
                status = if ((auxKw ?: 0.0) > 0.1) MkStatStatus.Warn else MkStatStatus.None,
                onClick = auxKw?.let {
                    {
                        onFocus(
                            FocusMetric(
                                MkIcons.FlameFill, "Lisävastus", Fmt.oneDecimal(it), "kW",
                                "hvac", "Total_Active_Power", tagKey = "meter", tagValue = "extra",
                            ),
                        )
                    }
                },
                modifier = Modifier.weight(1f),
            )
            MkStatTile(
                label = "Taajuus",
                value = grid("Grid_Frequency")?.let { Fmt.oneDecimal(it) } ?: "—",
                unit = "Hz",
                icon = MkIcons.Waveform,
                onClick = gridFocus("Taajuus", "Grid_Frequency", "Hz", MkIcons.Waveform),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.padding(top = MkSpacing.x3),
            horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2),
        ) {
            PhaseChip("L1", grid("L1_Voltage"), Modifier.weight(1f), gridFocus("Vaihe L1", "L1_Voltage", "V", MkIcons.Lightning))
            PhaseChip("L2", grid("L2_Voltage"), Modifier.weight(1f), gridFocus("Vaihe L2", "L2_Voltage", "V", MkIcons.Lightning))
            PhaseChip("L3", grid("L3_Voltage"), Modifier.weight(1f), gridFocus("Vaihe L3", "L3_Voltage", "V", MkIcons.Lightning))
        }
        Box(modifier = Modifier.fillMaxWidth().padding(top = MkSpacing.x3).height(1.dp).background(c.borderSubtle))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = MkSpacing.x3),
            horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2),
        ) {
            MeterTotalCell("Lukema", sum("Total_Active_Energy"), c.inkHi, TextAlign.Start, Modifier.weight(1f))
            MeterTotalCell("Verkosta", sum("Forward_Active_Energy"), c.inkHi, TextAlign.Center, Modifier.weight(1f))
            MeterTotalCell("Verkkoon", sum("Reverse_Active_Energy"), c.accent, TextAlign.End, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PhaseChip(
    label: String,
    volts: Double?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val c = MkTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(MkRadius.sm))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(c.surfaceInset)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MkTheme.type.readout(10), color = c.inkLo)
        Text(
            volts?.let { "${Fmt.oneDecimal(it)} V" } ?: "—",
            style = MkTheme.type.readout(13),
            color = c.inkHi,
            maxLines = 1,
        )
    }
}

@Composable
private fun MeterTotalCell(label: String, kwh: Double?, valueColor: Color, align: TextAlign, modifier: Modifier) {
    val c = MkTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = when (align) {
            TextAlign.Start -> Alignment.Start
            TextAlign.End -> Alignment.End
            else -> Alignment.CenterHorizontally
        },
    ) {
        Text(label, style = MkTheme.type.kicker, color = c.inkLo, maxLines = 1)
        Text(
            text = kwh?.let { "${Fmt.int(it)} kWh" } ?: "—",
            style = MkTheme.type.readout(15),
            color = valueColor,
            maxLines = 1,
        )
    }
}

// ── Kulutus + Kustannustrendi ────────────────────────────────────────────────

@Composable
private fun KulutusSection(
    cost: CostView?,
    range: EnergyRange,
    loading: Boolean,
    onRange: (EnergyRange) -> Unit,
    onFocus: (FocusMetric) -> Unit,
) {
    val c = MkTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(MkSpacing.x3)) {
        Row(horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3)) {
            // Both tiles open the window's per-bucket kWh trend as a full-page
            // chart (KULUTUS_TREND renders from the loaded cost view).
            MkStatTile(
                label = "Kulutus ${range.window}",
                value = cost?.kwh ?: "—",
                unit = "kWh",
                icon = MkIcons.LightningFill,
                onClick = cost?.kwh?.let {
                    { onFocus(FocusMetric(MkIcons.LightningFill, "Kulutus", it, "kWh", EnergiaViewModel.KULUTUS_TREND, "kwh")) }
                },
                modifier = Modifier.weight(1f),
            )
            MkStatTile(
                label = "Kustannus",
                value = cost?.cost ?: "—",
                unit = "€",
                icon = MkIcons.Lightning,
                onClick = cost?.cost?.let {
                    { onFocus(FocusMetric(MkIcons.Lightning, "Kustannus", it, "€", EnergiaViewModel.KULUTUS_TREND, "eur")) }
                },
                modifier = Modifier.weight(1f),
            )
        }
        // 24 h / 7 pv / 30 pv / 12 kk range selector.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EnergyRange.entries.forEach { r ->
                val on = r == range
                val shape = RoundedCornerShape(MkRadius.round)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(shape)
                        .background(if (on) c.accentDim else c.surfaceCard)
                        .border(1.dp, if (on) c.accent else c.borderSubtle, shape)
                        .clickable { onRange(r) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = r.tab,
                        style = MkTheme.type.label.copy(fontWeight = FontWeight.SemiBold),
                        color = if (on) c.accent else c.inkMid,
                        maxLines = 1,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2),
        ) {
            Text(
                text = "Kulutus laitteittain · ${range.window}",
                style = MkTheme.type.heading,
                color = c.inkMid,
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                Text("Lasketaan…", style = MkTheme.type.readout(11), color = c.inkLo, maxLines = 1)
            }
        }
        val consumers = cost?.consumers.orEmpty()
        if (consumers.isEmpty()) {
            QuietLine("Ei kulutustietoa")
        } else {
            val max = consumers.maxOf { it.kwh }.coerceAtLeast(0.01)
            consumers.forEach { ConsumerRow(it, max) }
        }

        CostTrendCard(cost)
    }
}

@Composable
private fun ConsumerRow(comp: EnergyComponent, max: Double) {
    val c = MkTheme.colors
    val shape = RoundedCornerShape(MkRadius.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surfaceCard)
            .border(1.dp, c.borderSubtle, shape)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(MkRadius.sm)).background(c.accentDim),
            contentAlignment = Alignment.Center,
        ) {
            Icon(consumerIcon(comp.name), null, tint = c.accent, modifier = Modifier.size(16.dp))
        }
        Text(
            text = comp.name,
            style = MkTheme.type.body,
            color = c.inkHi,
            modifier = Modifier.width(84.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(MkRadius.round)).background(c.track),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((comp.kwh / max).toFloat())
                    .clip(RoundedCornerShape(MkRadius.round))
                    .background(c.warm),
            )
        }
        Text(
            // Drop the decimal on large values so the "kWh" unit always fits the column
            // (a full year's consumer can be thousands of kWh).
            text = "${if (comp.kwh >= 100) Fmt.int(comp.kwh) else Fmt.oneDecimal(comp.kwh)} kWh",
            style = MkTheme.type.readout(12),
            color = c.inkMid,
            modifier = Modifier.widthIn(min = 78.dp),
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun CostTrendCard(cost: CostView?) {
    val c = MkTheme.colors
    MkCard {
        MkCardHead("Kustannustrendi", action = {
            Text(cost?.window ?: "", style = MkTheme.type.readout(10), color = c.inkLo)
        })
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = cost?.cost ?: "—",
                        style = MkTheme.type.readout(28, FontWeight.Medium),
                        color = c.inkHi,
                    )
                    Text(
                        text = "€",
                        style = MkTheme.type.readout(15),
                        color = c.inkLo,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Text(
                    text = buildString {
                        append(cost?.kwh ?: "—"); append(" kWh")
                        cost?.avg?.let { append(" · ka "); append(it); append(" c/kWh") }
                    },
                    style = MkTheme.type.readout(11),
                    color = c.inkLo,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            TrendBars(cost?.trend.orEmpty(), Modifier.weight(1f).height(40.dp))
        }
        if (cost?.peak != null || cost?.cheap != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = MkSpacing.x3),
                horizontalArrangement = Arrangement.spacedBy(MkSpacing.x4),
            ) {
                cost?.peak?.let { PeakCheapLabel(MkIcons.CaretUp, c.warm, it) }
                cost?.cheap?.let { PeakCheapLabel(MkIcons.CaretDown, c.statusOk, it) }
            }
        }
    }
}

/** The Kustannustrendi sparkline as accent bars, scaled to the window's peak bucket. */
@Composable
private fun TrendBars(values: List<Float>, modifier: Modifier = Modifier) {
    if (values.isEmpty()) {
        Box(modifier)
        return
    }
    val c = MkTheme.colors
    val max = values.max().coerceAtLeast(0.0001f)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEach { v ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(v / max)
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(c.accent),
            )
        }
    }
}

@Composable
private fun PeakCheapLabel(icon: ImageVector, tint: Color, text: String) {
    val c = MkTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
        Text(text, style = MkTheme.type.readout(11), color = c.inkLo, maxLines = 1)
    }
}

// ── Optimointi ("Lämmityksen optimointi") ────────────────────────────────────

@Composable
private fun HeatingOptiCard(opti: HeatingOpti?, onFocus: (FocusMetric) -> Unit) {
    val c = MkTheme.colors
    MkCard {
        MkCardHead("Lämmityksen optimointi", action = opti?.let { o ->
            { MkTag(o.nowTierLabel, status = o.nowTone.toTagStatus()) }
        })
        if (opti == null) {
            QuietLine("Ladataan…")
            return@MkCard
        }
        // Per-hour tier forecast: bar heights encode the tier, first bar is "now".
        Row(
            modifier = Modifier.fillMaxWidth().height(34.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            opti.tierBars.forEach { bar ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(tierHeight(bar.tier))
                        .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        .background(tierColor(bar.tier)),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            opti.tierBars.forEach { bar ->
                Text(
                    text = bar.label,
                    style = MkTheme.type.readout(8),
                    color = if (bar.current) c.inkHi else c.inkLo,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        opti.biasC?.let { bias ->
            val shape = RoundedCornerShape(MkRadius.md)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MkSpacing.x3)
                    .clip(shape)
                    .background(c.accentDim)
                    .border(1.dp, c.accentBorder, shape)
                    // The applied bias opens the optimizer's total_bias history.
                    .clickable {
                        onFocus(
                            FocusMetric(
                                MkIcons.ThermometerHot, "Lämpötilakorjaus", signed(bias), "°C",
                                "indoor_publisher", "total_bias",
                            ),
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(MkIcons.ThermometerHot, null, tint = c.accent, modifier = Modifier.size(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Lämpötilakorjaus ${signed(bias)} °C",
                        style = MkTheme.type.body.copy(fontWeight = FontWeight.Medium),
                        color = c.inkHi,
                    )
                    Text(
                        text = "Säätää lämpöpumppua sähkön hinnan mukaan",
                        style = MkTheme.type.readout(10),
                        color = c.inkLo,
                    )
                }
            }
        }

        Column(modifier = Modifier.padding(top = MkSpacing.x2)) {
            opti.rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.key, style = MkTheme.type.body, color = c.inkMid, modifier = Modifier.weight(1f))
                    Text(
                        text = row.value,
                        style = MkTheme.type.readout(12),
                        color = row.tone.toInk(),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                    )
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
            }
        }
    }
}

// ── Valot ("Valojen käyttö") ─────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LightUsageCard(usage: LightUsage?) {
    val c = MkTheme.colors
    MkCard {
        MkCardHead("Valojen käyttö", action = usage?.let { u ->
            { Text("${u.onNow}/${u.total} päällä", style = MkTheme.type.readout(11), color = c.inkMid) }
        })
        if (usage == null) {
            QuietLine("Ladataan…")
            return@MkCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2)) {
            LightStatCell("kWh tänään", usage.kwhToday ?: "—", c.inkHi, Modifier.weight(1f))
            LightStatCell("auto-off", usage.autoOffToday.toString(), c.accent, Modifier.weight(1f))
            LightStatCell("käyttöä", usage.totalUseLabel ?: "—", c.statusOk, Modifier.weight(1f))
        }

        if (usage.areaRows.isNotEmpty()) {
            SectionLabel("Käyttö alueittain")
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                usage.areaRows.forEach { AreaRow(it) }
            }
        }

        if (usage.autoRules.isNotEmpty()) {
            SectionLabel("Automaatio tänään")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                usage.autoRules.forEach { AutoRuleChip(it) }
            }
        }
    }
}

@Composable
private fun LightStatCell(label: String, value: String, valueColor: Color, modifier: Modifier) {
    val c = MkTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(MkRadius.sm))
            .background(c.surfaceInset)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = MkTheme.type.readout(16), color = valueColor, maxLines = 1)
        Text(label, style = MkTheme.type.kicker, color = c.inkLo, maxLines = 1)
    }
}

@Composable
private fun AreaRow(area: AreaUse) {
    val c = MkTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(area.icon, null, tint = c.accent, modifier = Modifier.size(15.dp))
        Text(
            text = area.name,
            style = MkTheme.type.body,
            color = c.inkHi,
            modifier = Modifier.width(78.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(MkRadius.round)).background(c.track),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((area.pct / 100f).coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(MkRadius.round))
                    .background(c.accent),
            )
        }
        Text(
            text = area.hoursLabel,
            style = MkTheme.type.readout(11),
            color = c.inkMid,
            modifier = Modifier.width(62.dp),
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

@Composable
private fun AutoRuleChip(rule: AutoRule) {
    val c = MkTheme.colors
    val shape = RoundedCornerShape(MkRadius.round)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(c.surfaceInset)
            .border(1.dp, c.borderSubtle, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(rule.icon, null, tint = c.accent, modifier = Modifier.size(13.dp))
        Text(rule.label, style = MkTheme.type.body, color = c.inkMid, maxLines = 1)
        Text(rule.count.toString(), style = MkTheme.type.readout(12), color = c.inkHi, maxLines = 1)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MkTheme.type.kicker,
        color = MkTheme.colors.inkLo,
        modifier = Modifier.padding(top = MkSpacing.x3, bottom = MkSpacing.x2),
    )
}

// ── Shared bits ──────────────────────────────────────────────────────────────

@Composable
private fun tierColor(t: HeatTier): Color = when (t) {
    HeatTier.Cheap -> MkTheme.colors.statusOk
    HeatTier.Normal -> MkTheme.colors.inkLo
    HeatTier.Expensive -> MkTheme.colors.warm
    HeatTier.PreHeat -> MkTheme.colors.accent
}

/** Bar-height fraction per tier, mirroring the design's cheap<preheat<normal<expensive ramp. */
private fun tierHeight(t: HeatTier): Float = when (t) {
    HeatTier.Cheap -> 0.40f
    HeatTier.Normal -> 0.62f
    HeatTier.Expensive -> 1.0f
    HeatTier.PreHeat -> 0.78f
}

@Composable
private fun OptiTone.toInk(): Color = when (this) {
    OptiTone.Warn -> MkTheme.colors.warm
    OptiTone.Accent -> MkTheme.colors.accent
    OptiTone.Ok -> MkTheme.colors.statusOk
    OptiTone.Ink -> MkTheme.colors.inkHi
}

private fun OptiTone.toTagStatus(): MkTagStatus = when (this) {
    OptiTone.Warn -> MkTagStatus.Warn
    OptiTone.Accent -> MkTagStatus.Accent
    OptiTone.Ok -> MkTagStatus.Ok
    OptiTone.Ink -> MkTagStatus.Neutral
}

private fun signed(v: Double): String = (if (v >= 0) "+" else "") + Fmt.oneDecimal(v)

private fun consumerIcon(name: String): ImageVector = when {
    name.contains("Maalämpö") -> MkIcons.ThermometerHot
    name.contains("Valaistus") -> MkIcons.LightbulbFill
    name.contains("Sauna") -> MkIcons.FlameFill
    name.contains("Ilmanvaihto") -> MkIcons.Fan
    else -> MkIcons.LightningFill
}

@Composable
private fun QuietLine(text: String) {
    Text(
        text = text,
        style = MkTheme.type.body,
        color = MkTheme.colors.inkLo,
        modifier = Modifier.padding(vertical = MkSpacing.x2),
    )
}
