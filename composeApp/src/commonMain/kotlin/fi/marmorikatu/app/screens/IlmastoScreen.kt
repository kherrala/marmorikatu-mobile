package fi.marmorikatu.app.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fi.marmorikatu.app.components.MkBanner
import fi.marmorikatu.app.platform.LockLandscapeWhileVisible
import fi.marmorikatu.app.shell.UiSignals
import org.koin.compose.koinInject
import fi.marmorikatu.app.components.MkButton
import fi.marmorikatu.app.components.MkButtonSize
import fi.marmorikatu.app.components.MkButtonVariant
import fi.marmorikatu.app.components.MkCard
import fi.marmorikatu.app.components.MkCardHead
import fi.marmorikatu.app.components.MkCoolBlue
import fi.marmorikatu.app.components.MkFreshness
import fi.marmorikatu.app.components.MkGauge
import fi.marmorikatu.app.components.MkVentilationDiagram
import fi.marmorikatu.app.components.MkLineChart
import fi.marmorikatu.app.components.MkMetricDetailPage
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
import kotlin.math.ln
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.core.model.Cooling
import fi.marmorikatu.core.model.Floor
import fi.marmorikatu.core.model.HeatPumpStatus
import fi.marmorikatu.core.model.HeatingDemand
import fi.marmorikatu.core.model.RoomTemperature
import fi.marmorikatu.core.model.Rooms
import fi.marmorikatu.core.model.VentAlarm
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
    Ilmanvaihto("Ilmanvaihto"),
    Ilma("Ilmanlaatu"),
    Lampo("Lämpötilat"),
    Maalampo("Maalämpö"),
    Jaahdytys("Jäähdytys"),
    Anturit("Anturit"),
}

/** Axis ticks for the selected window; shares [chartLabels] so both screens agree. */
private fun ilmastoAxisLabels(range: TimeRangeOption): List<String> = chartLabels(range)

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
    /**
     * Multiplier applied to the stored series so the chart matches the shown
     * unit — e.g. the Ruuvi pressure is stored in Pa but displayed in hPa.
     */
    val scale: Float = 1f,
)

/** Ilmasto (climate): a sticky sub-tab bar scroll-spies over stacked sections. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun IlmastoScreen(
    viewModel: IlmastoViewModel = koinViewModel(),
    forceLandscapeDetail: Boolean = false,
) {
    // Full refresh on entry, then a silent poll so the InfluxDB-only readings
    // (duct temps, cooling, efficiency) keep up with the live MQTT flows.
    LaunchedEffect(Unit) {
        viewModel.refresh()
        while (true) {
            delay(10_000)
            viewModel.poll()
        }
    }

    val colors = MkTheme.colors
    val snapshot by viewModel.snapshot.collectAsState()
    val rooms by viewModel.roomTemperatures.collectAsState()
    val heating by viewModel.heatingDemand.collectAsState()
    val ventilation by viewModel.ventilation.collectAsState()
    val cooling by viewModel.cooling.collectAsState()
    val heatPump by viewModel.heatPump.collectAsState()
    val heatPumpDuty by viewModel.heatPumpDuty.collectAsState()
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
        SideEffect { uiSignals.setDetailOpen("ilmasto", focus != null) }
        DisposableEffect(Unit) { onDispose { uiSignals.setDetailOpen("ilmasto", false) } }
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
    // The tab bar is now a fixed bar above the list (not a list item), so the
    // sections are the list's own items starting at index 0.
    val sectionStart = 0
    val activeSub by remember {
        derivedStateOf {
            IlmastoSub.entries[(listState.firstVisibleItemIndex - sectionStart).coerceIn(0, IlmastoSub.entries.lastIndex)]
        }
    }

    MkPullToRefresh(refreshing = refreshing, onRefresh = viewModel::refresh) {
        val f = focus
        if (f != null) {
            val focusRange by viewModel.focusRange.collectAsState()
            val chart = if (focusSeries.size < 2) emptyList()
            else listOf(MkSeries(name = null, values = focusSeries, color = colors.accent, area = true))
            MkMetricDetailPage(
                icon = f.icon,
                label = f.label,
                value = f.value,
                unit = f.unit,
                series = chart,
                labels = ilmastoAxisLabels(focusRange),
                stats = emptyList(),
                range = focusRange,
                onRangeChange = viewModel::setFocusRange,
                onBack = closeFocus,
                loading = focusLoading,
                swipeKey = f.label,
            )
        } else {
            Column(modifier = Modifier.fillMaxSize().background(colors.appBg)) {
                // Fixed sub-tab bar: it stays put below the app header while the list
                // scrolls beneath it. As a stickyHeader it slid up under the header's
                // soft-edge (the content tucks up by -14dp), so it's hoisted out of
                // the list. Tapping a tab anchor-scrolls to its section.
                Box(
                    // top padding clears the header's -14dp content tuck + soft edge
                    // so the pills sit fully below the chrome, not under it.
                    modifier = Modifier.fillMaxWidth().background(colors.appBg)
                        .padding(start = MkSpacing.pagePad, end = MkSpacing.pagePad, top = MkSpacing.x5, bottom = 4.dp),
                ) {
                    SubTabBar(
                        active = activeSub,
                        onSelect = { s -> scope.launch { listState.animateScrollToItem(s.ordinal + sectionStart) } },
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = MkSpacing.pagePad,
                        end = MkSpacing.pagePad,
                        top = MkSpacing.x2,
                        bottom = MkSpacing.x4 + MkSpacing.scrollBottomGap,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MkSpacing.stackGap),
                ) {
                    // Section order mirrors the redesign: ventilation first (with the
                    // system diagram), then air quality, temperatures, heat pump,
                    // cooling, and the sensor list last.
                    item(key = "iv") { VentilationCard(snapshot, ventilation, updatedAt) }
                item(key = "ilma") { AirQualityCard(snapshot, openFocus) }
                item(key = "lampo") {
                    Column(verticalArrangement = Arrangement.spacedBy(MkSpacing.stackGap)) {
                        HistoryCard(snapshot, viewModel)
                        RoomsCard(rooms, heating, openFocus)
                    }
                }
                item(key = "maalampo") { HeatPumpCard(heatPump, heatPumpDuty, openFocus) }
                item(key = "jaahdytys") { JaahdytysSection(ventilation, cooling, openFocus) }
                item(key = "anturit") { AnturitSection(ruuvi, openFocus) }
                if (snapshot.failed) {
                    item(key = "failed") {
                        Text(
                            text = "Ilmastotietoja ei juuri nyt saatavilla.",
                            style = MkTheme.type.caption,
                            color = colors.inkLo,
                        )
                    }
                }
                // Trailing space so tapping the last tab can scroll its section to
                // the very top even when it's short (the list bottoms out otherwise).
                item(key = "tailspace") { Spacer(Modifier.fillParentMaxHeight(0.9f)) }
                }
            }
        }
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
                MkGauge(value = humidity?.toFloat(), max = 100f, label = "Kosteus", unit = "%", status = "accent", size = 96.dp)
            }
            val co2 = air?.co2
            GaugeSlot(onClick = co2?.let { c ->
                { onFocus(FocusMetric(MkIcons.Wind, "CO₂", Fmt.int(c.value), "ppm", "ruuvi", "co2", tagKey = "sensor_name", tagValue = AIR_SENSOR)) }
            }) {
                MkGauge(value = co2?.value?.toFloat(), max = 1500f, label = "CO₂ ppm", unit = "", status = co2?.let { air?.statusOf(it) } ?: "accent", size = 96.dp)
            }
            val pm = air?.pm25
            GaugeSlot(onClick = pm?.let { p ->
                { onFocus(FocusMetric(MkIcons.Wind, "PM2.5", Fmt.oneDecimal(p.value), "µg/m³", "ruuvi", "pm2_5", tagKey = "sensor_name", tagValue = AIR_SENSOR)) }
            }) {
                MkGauge(value = pm?.value?.toFloat(), max = 35f, label = "PM2.5", unit = "", status = pm?.let { air?.statusOf(it) } ?: "ok", size = 96.dp)
            }
        }
        val voc = air?.voc
        // Dew point, derived from the sensor's own temperature + humidity (design
        // replaces the plain temperature tile with Kastepiste).
        val dewC = run {
            val temp = air?.temperature?.value
            val rh = air?.humidity?.value
            if (temp != null && rh != null) dewPointC(temp, rh) else null
        }
        if (voc != null || dewC != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = MkSpacing.x3),
                horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3),
            ) {
                // Derived value → no history series to open on tap.
                MkStatTile(
                    label = "Kastepiste",
                    value = dewC?.let { Fmt.oneDecimal(it) } ?: NO_DATA,
                    unit = dewC?.let { "°C" },
                    icon = MkIcons.DropHalf,
                    modifier = Modifier.weight(1f),
                )
                MkStatTile(
                    label = "VOC-indeksi",
                    value = voc?.let { Fmt.int(it.value) } ?: NO_DATA,
                    icon = MkIcons.Wind,
                    onClick = voc?.let {
                        { onFocus(FocusMetric(MkIcons.Wind, "VOC-indeksi", Fmt.int(it.value), "", "ruuvi", "voc", tagKey = "sensor_name", tagValue = AIR_SENSOR)) }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Dew point (°C) from temperature and relative humidity via the Magnus-Tetens
 * approximation — matches what the ventilation unit reports for its own air.
 */
private fun dewPointC(tempC: Double, rhPct: Double): Double? {
    if (rhPct <= 0.0) return null
    val a = 17.62
    val b = 243.12
    val gamma = ln(rhPct / 100.0) + a * tempC / (b + tempC)
    return b * gamma / (a - gamma)
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
private fun HeatPumpCard(hp: HeatPumpStatus, dutyPct: Double?, onFocus: (FocusMetric) -> Unit) {
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
            // The running state opens the compressor's power-draw history — the
            // same series behind the home screen's Maalämpö KPI.
            Text(
                text = state,
                style = MkTheme.type.readout(13),
                color = if (hp.running) colors.accent else colors.inkMid,
                modifier = if (hp.available) {
                    Modifier
                        .clip(RoundedCornerShape(MkRadius.sm))
                        .clickable {
                            onFocus(FocusMetric(MkIcons.ThermometerHot, "Maalämpö", state, "kW", "hvac", "Lampopumppu_teho"))
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                } else Modifier,
            )
        }
        if (!hp.available) {
            Text(NO_DATA, style = MkTheme.type.readout(15), color = colors.inkLo)
            return@MkCard
        }
        fun c(v: Double?) = v?.let { "${Fmt.oneDecimal(it)}" }
        // Two columns of readouts from the ThermIQ register feed, in the design's
        // order: flow/return water, brine out, COP, hot water, then the 24 h
        // compressor duty. Tapping a temperature opens its chart (thermia fields).
        val tiles = listOf(
            HpTile("Menovesi", c(hp.supplyC), "°C", MkIcons.ThermometerHot, "supply_temp"),
            HpTile("Paluuvesi", c(hp.returnC), "°C", MkIcons.ThermometerCold, "return_temp"),
            HpTile("Liuos ulos", c(hp.brineOutC), "°C", MkIcons.Snowflake, "brine_out_temp"),
            HpTile("COP", hp.cop?.let { Fmt.oneDecimal(it) }, null, MkIcons.Gauge, null),
            HpTile("Käyttövesi", c(hp.hotWaterC), "°C", MkIcons.DropFill, "hotwater_temp"),
            HpTile("Käyntiaika", dutyPct?.let { Fmt.int(it) }, "%", MkIcons.Clock, null),
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
    updatedAt: Long?,
) {
    val c = MkTheme.colors
    fun vraw(vararg keys: String): Double? = keys.firstNotNullOfOrNull { ventilation.raw[it.lowercase()] }
    // Casa IV_tila is 0-indexed: 0=Hiljainen, 1=Normaali, 2=Teho, 3=Takka.
    val mode = vraw("operatingmode")?.toInt()
    val modeIndex = (mode ?: 1).coerceIn(0, IV_MODES.lastIndex)
    val bypassOpen = (vraw("hxbypassopen", "ohitus_auki") ?: 0.0) != 0.0
    val airflow = vraw("supplyfanspeed", "exhaustfanspeed", "tulopuhallin_nopeus")?.takeIf { it > 0 }
        ?: IV_DEFAULT_AIRFLOW.getOrNull(modeIndex)?.toDouble()
    val lto = snapshot.hvac?.recoveryEfficiencyPct ?: vraw("hreefficiency", "lto_hyotysuhde")?.takeIf { it > 0 }
    val hv = snapshot.hvac

    // The supply air's journey: outside intake → LTO recovery → heating coil →
    // cooling coil → delivered; the extract air on the way back out.
    // Prefer the live MQTT ventilation feed (updates continuously) over the
    // on-demand InfluxDB snapshot; Tuloilmakanava (delivered) is InfluxDB-only.
    val outdoor = ventilation.outdoorC ?: hv?.outdoorC          // ULKOILMA (intake)
    val afterLto = ventilation.supplyPreHeatC ?: hv?.supplyPreHeatC
    val afterHeat = ventilation.supplyC ?: hv?.supplyPostHeatC
    val delivered = hv?.supplyDuctC ?: afterHeat                // TULOILMA (delivered)
    val extract = ventilation.extractC ?: hv?.extractC          // POISTOILMA
    val exhaust = ventilation.exhaustC ?: hv?.exhaustC          // JÄTEILMA
    fun delta(a: Double?, b: Double?) = if (a != null && b != null) a - b else null
    val ltoDelta = delta(afterLto, outdoor)
    val heatDelta = delta(afterHeat, afterLto)
    val coolDelta = delta(delivered, afterHeat)

    var selected by remember { mutableStateOf("lto") }
    fun tv(v: Double?) = v?.let { "${Fmt.oneDecimal(it)}°".replace('.', ',') } ?: NO_DATA
    val explain = when (selected) {
        "ulko" -> "Raitisilma otetaan sisään ulkosäleiköstä · ${tv(outdoor)}."
        "lto" -> "LTO-kenno siirtää poistoilman lämpöä tuloilmaan ${signedDelta(ltoDelta)} (${tv(outdoor)} → ${tv(afterLto)})" +
            (lto?.let { " · hyötysuhde ${Fmt.int(it)} %." } ?: ".")
        "coilH" -> "Lämmityspatteri (maapiiri) lepotilassa · ${signedDelta(heatDelta)} (${tv(afterLto)} → ${tv(afterHeat)})."
        "coilC" -> "Jäähdytyspatteri viilentää tuloilman maakylmällä ${signedDelta(coolDelta)} (${tv(afterHeat)} → ${tv(delivered)})."
        "tulo" -> "Huoneisiin puhallettava ilma · ${tv(delivered)}, viilennetty maakylmällä."
        "poisto" -> "Huoneista poistettava ilma · ${tv(extract)} (keittiö, kylpyhuoneet, sauna)."
        "jate" -> "Ulos puhallettava ilma talteenoton jälkeen · ${tv(exhaust)}."
        else -> ""
    }

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

        // System schematic, with a "juuri nyt" freshness marker over its corner.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MkRadius.md))
                .background(c.surfaceInset)
                .padding(8.dp),
        ) {
            MkVentilationDiagram(selected = selected)
            IvFreshness(updatedAt, modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 6.dp))
        }

        // The four air streams — tap to explain that stage.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = MkSpacing.x3),
            horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2),
        ) {
            IvTempCard("ULKOILMA", tv(outdoor), "sisään säleiköstä", c.accent, selected == "ulko", { selected = "ulko" }, Modifier.weight(1f))
            IvTempCard("TULOILMA", tv(delivered), "huoneisiin · viilennetty", c.accent, selected == "tulo", { selected = "tulo" }, Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = MkSpacing.x2),
            horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2),
        ) {
            IvTempCard("POISTOILMA", tv(extract), "huoneista kiertoon", c.warm, selected == "poisto", { selected = "poisto" }, Modifier.weight(1f))
            IvTempCard("JÄTEILMA", tv(exhaust), "ulos · lämpö talteen", c.accent, selected == "jate", { selected = "jate" }, Modifier.weight(1f))
        }

        // Each conditioning stage's contribution to the supply temperature.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = MkSpacing.x2),
            horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2),
        ) {
            IvDeltaChip("LTO", c.accent, signedDelta(ltoDelta), c.warm, lto?.let { "η ${Fmt.int(it)} %" } ?: "talteenotto", selected == "lto", { selected = "lto" }, Modifier.weight(1f))
            IvDeltaChip("PATTERI", c.warm, signedDelta(heatDelta), c.warm, "lämmitys", selected == "coilH", { selected = "coilH" }, Modifier.weight(1f))
            IvDeltaChip("JÄÄHDYTYS", MkCoolBlue, signedDelta(coolDelta), MkCoolBlue, "maakylmä", selected == "coilC", { selected = "coilC" }, Modifier.weight(1f))
        }

        // Plain-language explanation of the selected stage.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = MkSpacing.x3, start = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(MkIcons.Info, null, tint = c.accent, modifier = Modifier.size(13.dp).padding(top = 2.dp))
            Text(explain, style = MkTheme.type.body.copy(fontSize = 12.5.sp, lineHeight = 18.sp), color = c.inkMid)
        }

        // Operating mode (read-only — no write path to the unit).
        Row(modifier = Modifier.padding(top = MkSpacing.x3)) { DisplayPills(IV_MODES, modeIndex) }

        // LTO efficiency + airflow gauges (kept from the previous design).
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = MkSpacing.x3),
            horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3),
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                MkGauge(value = lto?.toFloat(), max = 100f, label = "LTO hyötysuhde", unit = "%", status = lto?.let { ltoStatus(it) } ?: "accent")
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                MkGauge(value = airflow?.toFloat(), max = 100f, label = "Ilmavirta", unit = "%", status = "accent")
            }
        }

        // Bypass · freeze risk · filter.
        val freeze = if (ventilation.freezingDanger) "Korkea" else freezeRiskLabel(outdoor, exhaust, vraw("dewpoint", "kastepiste"))
        val filter = if (VentAlarm.FilterGuard in ventilation.alarms) "Vaihda" else "OK"
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = MkSpacing.x3),
            horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3),
        ) {
            MkStatTile(label = "Kesäohitus", value = if (bypassOpen) "Auki" else "Kiinni", icon = MkIcons.FlowArrow, modifier = Modifier.weight(1f))
            MkStatTile(label = "Jäätymisriski", value = freeze, icon = MkIcons.Snowflake, modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = MkSpacing.x3),
            horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3),
        ) {
            MkStatTile(label = "Suodatin", value = filter, icon = MkIcons.Funnel, modifier = Modifier.weight(1f))
            // Extract-side relative humidity from the Belimo 22DTH duct sensor.
            val humidity = ventilation.relativeHumidity ?: hv?.humidityPct
            MkStatTile(label = "Kosteus", value = humidity?.let { Fmt.int(it) } ?: NO_DATA, unit = if (humidity != null) "%" else null, icon = MkIcons.DropHalf, modifier = Modifier.weight(1f))
        }
    }
}

/** One duct-temperature card in the ventilation section; a tap selects that stage. */
@Composable
private fun IvTempCard(label: String, value: String, sub: String, valueColor: Color, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MkTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(MkRadius.md))
            .background(c.surfaceInset)
            .border(1.dp, if (selected) c.accent else c.borderSubtle, RoundedCornerShape(MkRadius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label, style = MkTheme.type.readout(10).copy(letterSpacing = 0.08.em), color = c.inkMid, maxLines = 1)
        Text(value, style = MkTheme.type.readout(23, FontWeight.SemiBold), color = valueColor, maxLines = 1)
        Text(sub, style = MkTheme.type.caption, color = c.inkLo, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One conditioning-stage delta chip; a tap selects that stage. */
@Composable
private fun IvDeltaChip(label: String, marker: Color, value: String, valueColor: Color, sub: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MkTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(MkRadius.md))
            .background(c.surfaceInset)
            .border(1.dp, if (selected) c.accent else c.borderSubtle, RoundedCornerShape(MkRadius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(marker))
            Text(label, style = MkTheme.type.readout(9, FontWeight.Normal).copy(letterSpacing = 0.06.em), color = c.inkMid, maxLines = 1)
        }
        Text(value, style = MkTheme.type.readout(16, FontWeight.SemiBold), color = valueColor, maxLines = 1)
        Text(sub, style = MkTheme.type.caption, color = c.inkLo, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Compact "● juuri nyt" freshness marker for the diagram corner (design). */
@Composable
private fun IvFreshness(updatedAt: Long?, modifier: Modifier = Modifier) {
    val c = MkTheme.colors
    var now by remember { mutableStateOf(nowEpochSec()) }
    LaunchedEffect(updatedAt) { while (true) { now = nowEpochSec(); delay(5000) } }
    // Brief pulse each time the data refreshes — the "new data" flash, as on Koti.
    val flash = remember { Animatable(1f) }
    LaunchedEffect(updatedAt) {
        if (updatedAt != null) {
            flash.snapTo(2.4f)
            flash.animateTo(1f, tween(700))
        }
    }
    val label = updatedAt?.let {
        val age = (now - it).coerceAtLeast(0)
        when {
            age < 15 -> "juuri nyt"
            age < 60 -> "$age s sitten"
            age < 3600 -> "${age / 60} min sitten"
            else -> "${age / 3600} h sitten"
        }
    } ?: NO_DATA
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).scale(flash.value).clip(CircleShape).background(c.accent))
        Text(label, style = MkTheme.type.readout(10), color = c.inkMid, maxLines = 1)
    }
}

@OptIn(ExperimentalTime::class)
private fun nowEpochSec(): Long = Clock.System.now().epochSeconds

/** "+4,1°" / "−7,6°" — a signed temperature delta with the Finnish decimal comma. */
private fun signedDelta(v: Double?): String {
    if (v == null) return NO_DATA
    val mag = Fmt.oneDecimal(kotlin.math.abs(v)).replace('.', ',')
    return (if (v >= 0) "+" else "−") + mag + "°"
}

/**
 * LTO freeze-risk label from the Grafana `hvac_dashboard` formula: dew-point
 * margin gated on cold exhaust, plus outdoor and exhaust temperature scores.
 */
private fun freezeRiskLabel(outdoor: Double?, exhaust: Double?, dewPoint: Double?): String {
    if (outdoor == null || exhaust == null || dewPoint == null) return NO_DATA
    fun c01(x: Double) = x.coerceIn(0.0, 1.0)
    val margin = exhaust - dewPoint
    val coldFactor = c01((5.0 - exhaust) / 5.0)
    val dewScore = c01((5.0 - margin) / 5.0) * coldFactor * 60.0
    val tempScore = c01((-5.0 - outdoor) / 20.0) * 25.0
    val exhScore = c01((5.0 - exhaust) / 5.0) * 15.0
    val total = dewScore + tempScore + exhScore
    val prob = when {
        exhaust < 0.0 -> 60.0
        margin < 0.0 && exhaust < 5.0 -> total.coerceIn(80.0, 95.0)
        else -> total.coerceAtMost(95.0)
    }
    return when {
        prob >= 70.0 -> "Korkea"
        prob >= 30.0 -> "Kohonnut"
        else -> "Matala"
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

/** Ventilation / cooling operating modes. First = quietest, last = fireplace boost. */
private val IV_MODES = listOf("Hiljainen", "Normaali", "Teho", "Takka")

/**
 * Casa W130-M factory supply-fan speeds per mode (%), used for Ilmavirta when the
 * unit reports no live fan-speed telemetry (`SupplyFanSpeed` stays 0). From the
 * unit manual: Poissa=speed 1 (40 %), Kotona=speed 3 (75 %), Tehostus=speed 5
 * (100 %); Takka approximates the fireplace boost.
 */
private val IV_DEFAULT_AIRFLOW = listOf(40, 75, 100, 85)

/**
 * Read-only mode selector: individual rounded pills, the live one a solid accent
 * fill (design). Not tappable — there is no write path to the unit.
 */
@Composable
private fun DisplayPills(options: List<String>, activeIndex: Int) {
    val c = MkTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEachIndexed { i, opt ->
            val on = i == activeIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(MkRadius.round))
                    .background(if (on) c.accent else c.surfaceCard)
                    .border(1.dp, if (on) c.accent else c.borderSubtle, RoundedCornerShape(MkRadius.round))
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    opt,
                    style = MkTheme.type.label.copy(fontWeight = FontWeight.SemiBold),
                    color = if (on) c.inkOnAccent else c.inkMid,
                    maxLines = 1,
                )
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
                    ClimTile("Venttiili", raw("damperposition")?.let { Fmt.int(it) }, "%", MkIcons.Faders, field = "Toimilaite_ohjaus"),
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
                Row(horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2), verticalAlignment = Alignment.CenterVertically) {
                    Text("Kosteusohjaus", style = MkTheme.type.heading, color = c.inkHi)
                    MkTag(text = "ON", status = MkTagStatus.Ok)
                }
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
    // Each Ruuvi reading opens its history chart (ruuvi/temperature or
    // ruuvi/pressure, filtered to the sensor that supplied the value).
    fun tempFocus(name: String, icon: ImageVector, valueStr: String, sensor: String) =
        FocusMetric(icon, name, valueStr, "°C", "ruuvi", "temperature", tagKey = "sensor_name", tagValue = sensor)
    // Keep the sensor that supplied the pressure so its history can be focused
    // (stored in Pa; shown — and charted, via scale — in hPa).
    val pressureSrc = ruuvi.values.firstNotNullOfOrNull { r -> r.pressure?.let { r.sensorName to it } }
    val pressureHpa = pressureSrc?.second?.let { it / 100.0 }
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
            add(
                Anturi(
                    "Ilmanpaine", MkIcons.Gauge, "${Fmt.int(it)} hPa", false,
                    FocusMetric(
                        MkIcons.Gauge, "Ilmanpaine", Fmt.int(it), "hPa", "ruuvi", "pressure",
                        tagKey = "sensor_name", tagValue = pressureSrc?.first, scale = 0.01f,
                    ),
                ),
            )
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
private fun RoomsCard(
    rooms: List<RoomTemperature>,
    heating: List<HeatingDemand>,
    onFocus: (FocusMetric) -> Unit,
) {
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
            val icon = floorIcon(known?.floor ?: room.floor)
            RoomRow(
                icon = icon,
                name = room.name,
                celsius = room.celsius,
                demandPct = demand,
                // Each room temperature opens its history chart (the `rooms`
                // measurement field this sensor writes).
                onClick = known?.influxField?.let { field ->
                    {
                        onFocus(
                            FocusMetric(icon, room.name, Fmt.oneDecimal(room.celsius), "°C", "rooms", field),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun RoomRow(
    icon: ImageVector,
    name: String,
    celsius: Double,
    demandPct: Int,
    onClick: (() -> Unit)? = null,
) {
    val colors = MkTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MkRadius.sm))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = MkSpacing.x2),
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
        // Chevron hints the row drills into a history chart.
        if (onClick != null) {
            Icon(MkIcons.CaretRight, null, tint = colors.inkLo, modifier = Modifier.size(14.dp))
        }
    }
}

private fun floorIcon(floor: Floor): ImageVector = when (floor) {
    Floor.KELLARI -> MkIcons.Stairs
    Floor.ALAKERTA -> MkIcons.House
    Floor.YLAKERTA -> MkIcons.Bed
    Floor.ULKO -> MkIcons.Sun
}
