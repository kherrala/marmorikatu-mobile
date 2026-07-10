package fi.marmorikatu.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import fi.marmorikatu.app.components.MkBanner
import fi.marmorikatu.app.components.MkCard
import fi.marmorikatu.app.components.MkCardHead
import fi.marmorikatu.app.components.MkFreshness
import fi.marmorikatu.app.components.MkGauge
import fi.marmorikatu.app.components.MkLineChart
import fi.marmorikatu.app.components.MkPullToRefresh
import fi.marmorikatu.app.components.MkSeries
import fi.marmorikatu.app.components.MkStatTile
import fi.marmorikatu.app.components.TimeRangeOption
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.core.model.Floor
import fi.marmorikatu.core.model.HeatPumpStatus
import fi.marmorikatu.core.model.HeatingDemand
import fi.marmorikatu.core.model.RoomTemperature
import fi.marmorikatu.core.model.Rooms
import fi.marmorikatu.core.model.Ventilation
import org.koin.compose.viewmodel.koinViewModel

private const val NO_DATA = "Ei tietoa"

/** The four focused views of the climate screen, matching the design's sub-tabs. */
private enum class IlmastoSub(val label: String) {
    Lampo("Lämpötilat"),
    Ilma("Ilmanlaatu"),
    Maalampo("Maalämpö"),
    Ilmanvaihto("Ilmanvaihto"),
}

/** Axis ticks must describe the selected window, not always a 24-hour clock. */
private fun chartLabels(range: TimeRangeOption): List<String> = when (range) {
    TimeRangeOption.H6 -> listOf("-6 t", "-4 t", "-2 t", "nyt")
    TimeRangeOption.H24 -> listOf("00:00", "06:00", "12:00", "18:00", "nyt")
    TimeRangeOption.D7 -> listOf("ma", "ke", "pe", "su")
    TimeRangeOption.D30 -> listOf("1.", "10.", "20.", "30.")
    TimeRangeOption.Y1 -> listOf("tammi", "huhti", "heinä", "loka")
}

/** Ilmasto (climate): a sub-tab bar over temperature history, air quality, heat pump, MVHR. */
@Composable
fun IlmastoScreen(viewModel: IlmastoViewModel = koinViewModel()) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    val colors = MkTheme.colors
    val snapshot by viewModel.snapshot.collectAsState()
    val rooms by viewModel.roomTemperatures.collectAsState()
    val heating by viewModel.heatingDemand.collectAsState()
    val ventilation by viewModel.ventilation.collectAsState()
    val heatPump by viewModel.heatPump.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val updatedAt by viewModel.updatedAt.collectAsState()

    var sub by remember { mutableStateOf(IlmastoSub.Lampo) }

    MkPullToRefresh(refreshing = refreshing, onRefresh = viewModel::refresh) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.appBg)
                .verticalScroll(rememberScrollState())
                .padding(MkSpacing.pagePad),
            verticalArrangement = Arrangement.spacedBy(MkSpacing.stackGap),
        ) {
            MkFreshness(
                updatedAtEpochSeconds = updatedAt,
                refreshing = refreshing,
                onRefresh = viewModel::refresh,
            )

            SubTabBar(active = sub, onSelect = { sub = it })

            when (sub) {
                IlmastoSub.Lampo -> {
                    HistoryCard(snapshot, viewModel)
                    RoomsCard(rooms, heating)
                }
                IlmastoSub.Ilma -> AirQualityCard(snapshot)
                IlmastoSub.Maalampo -> HeatPumpCard(heatPump)
                IlmastoSub.Ilmanvaihto -> VentilationCard(snapshot, ventilation)
            }

            if (snapshot.failed) {
                Text(
                    text = "Ilmastotietoja ei juuri nyt saatavilla.",
                    style = MkTheme.type.caption,
                    color = colors.inkLo,
                )
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
            MkLineChart(series = series, labels = chartLabels(snapshot.range), height = 200.dp)
        } else {
            Text(text = NO_DATA, style = MkTheme.type.readout(15), color = colors.inkLo)
        }
    }
}

// ── Ilmanlaatu: humidity / CO₂ / PM2.5 gauges + tiles ───────────────────────

@Composable
private fun AirQualityCard(snapshot: IlmastoSnapshot) {
    val hvac = snapshot.hvac
    val air = snapshot.air

    MkCard {
        MkCardHead("Ilmanlaatu" + (air?.location?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val humidity = air?.humidity?.value ?: hvac?.humidityPct
            GaugeSlot {
                if (humidity != null) {
                    MkGauge(value = humidity.toFloat(), max = 100f, label = "Kosteus", unit = "%", status = "accent", size = 96.dp)
                } else GaugePlaceholder("Kosteus")
            }
            val co2 = air?.co2
            GaugeSlot {
                if (co2 != null) {
                    MkGauge(value = co2.value.toFloat(), max = 1500f, label = "CO₂ ppm", unit = "", status = air.statusOf(co2) ?: "accent", size = 96.dp)
                } else GaugePlaceholder("CO₂")
            }
            val pm = air?.pm25
            GaugeSlot {
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
                    modifier = Modifier.weight(1f),
                )
                MkStatTile(
                    label = "Lämpötila",
                    value = temp?.let { Fmt.oneDecimal(it.value) } ?: NO_DATA,
                    unit = temp?.let { "°C" },
                    icon = MkIcons.Thermometer,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ── Maalämpö: live heat-pump register readouts ──────────────────────────────

@Composable
private fun HeatPumpCard(hp: HeatPumpStatus) {
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
        // Two columns of readouts, drawn from the ThermIQ register feed.
        val tiles = listOf(
            Triple("Menovesi", c(hp.supplyC), "°C") to MkIcons.ThermometerHot,
            Triple("Paluuvesi", c(hp.returnC), "°C") to MkIcons.ThermometerCold,
            Triple("Käyttövesi", c(hp.hotWaterC), "°C") to MkIcons.DropFill,
            Triple("Liuos ulos", c(hp.brineOutC), "°C") to MkIcons.Snowflake,
            Triple("Liuos sisään", c(hp.brineInC), "°C") to MkIcons.Drop,
            Triple("Ulkona", c(hp.outdoorC), "°C") to MkIcons.ThermometerCold,
        )
        tiles.chunked(2).forEach { rowTiles ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = MkSpacing.x2),
                horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3),
            ) {
                rowTiles.forEach { (t, icon) ->
                    val (label, value, unit) = t
                    MkStatTile(
                        label = label,
                        value = value ?: NO_DATA,
                        unit = if (value != null) unit else null,
                        icon = icon,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ── Ilmanvaihto: LTO gauge + ventilation temperatures ───────────────────────

@Composable
private fun VentilationCard(snapshot: IlmastoSnapshot, ventilation: Ventilation) {
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
        Box(modifier = Modifier.fillMaxWidth().padding(bottom = MkSpacing.x2), contentAlignment = Alignment.Center) {
            if (lto != null) {
                MkGauge(value = lto.toFloat(), max = 100f, label = "Lämmöntalteenotto", unit = "%", status = ltoStatus(lto))
            } else GaugePlaceholder("Lämmöntalteenotto")
        }
        ReadoutRow("Ulkoilma", ventilation.outdoorC)
        ReadoutRow("Tuloilma", ventilation.supplyC)
        ReadoutRow("Poistoilma", ventilation.extractC)
        ReadoutRow("Jäteilma", ventilation.exhaustC)
    }
}

@Composable
private fun ReadoutRow(label: String, celsius: Double?) {
    val colors = MkTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = MkSpacing.x2),
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

// ── Gauges ──────────────────────────────────────────────────────────────────

@Composable
private fun GaugeSlot(content: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.Center) { content() }
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
