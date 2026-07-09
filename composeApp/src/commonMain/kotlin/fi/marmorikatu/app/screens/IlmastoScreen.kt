package fi.marmorikatu.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import fi.marmorikatu.app.components.MkStatStatus
import fi.marmorikatu.app.components.MkStatTile
import fi.marmorikatu.app.components.MkTimeRange
import fi.marmorikatu.app.components.TimeRangeOption
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.core.model.Floor
import fi.marmorikatu.core.model.HeatingDemand
import fi.marmorikatu.core.model.RoomTemperature
import fi.marmorikatu.core.model.Rooms
import fi.marmorikatu.core.model.Ventilation
import org.koin.compose.viewmodel.koinViewModel

private const val NO_DATA = "Ei tietoa"
/** Axis ticks must describe the selected window, not always a 24-hour clock. */
private fun chartLabels(range: TimeRangeOption): List<String> = when (range) {
    TimeRangeOption.H6 -> listOf("-6 t", "-4 t", "-2 t", "nyt")
    TimeRangeOption.H24 -> listOf("00:00", "06:00", "12:00", "18:00", "nyt")
    TimeRangeOption.D7 -> listOf("ma", "ke", "pe", "su")
    TimeRangeOption.D30 -> listOf("1.", "10.", "20.", "30.")
    TimeRangeOption.Y1 -> listOf("tammi", "huhti", "heinä", "loka")
}

/** Ilmasto (climate): outdoor/indoor stats, LTO + CO₂ gauges, history, ventilation, rooms. */
@Composable
fun IlmastoScreen(viewModel: IlmastoViewModel = koinViewModel()) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    val colors = MkTheme.colors
    val snapshot by viewModel.snapshot.collectAsState()
    val rooms by viewModel.roomTemperatures.collectAsState()
    val heating by viewModel.heatingDemand.collectAsState()
    val ventilation by viewModel.ventilation.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val updatedAt by viewModel.updatedAt.collectAsState()

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

            StatRow(snapshot, rooms, ventilation)

            AirQualityCard(snapshot)

            HistoryCard(snapshot, viewModel)

            VentilationCard(ventilation)

            RoomsCard(rooms, heating)

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

// ── Header stat row ─────────────────────────────────────────────────────────

@Composable
private fun StatRow(
    snapshot: IlmastoSnapshot,
    rooms: List<RoomTemperature>,
    ventilation: Ventilation,
) {
    val hvac = snapshot.hvac
    val indoorMedian = median(rooms.map { it.celsius })
    val humidity = hvac?.humidityPct ?: ventilation.relativeHumidity

    Column(verticalArrangement = Arrangement.spacedBy(MkSpacing.x3)) {
        Row(horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3)) {
            TempTile("Ulkona", hvac?.outdoorC, MkIcons.ThermometerCold, Modifier.weight(1f))
            TempTile("Sisällä", indoorMedian, MkIcons.ThermometerHot, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3)) {
            PercentTile("Kosteus", humidity, MkIcons.Drop, Modifier.weight(1f))
            PercentTile("LTO", hvac?.recoveryEfficiencyPct, MkIcons.Fan, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TempTile(label: String, value: Double?, icon: ImageVector, modifier: Modifier) {
    MkStatTile(
        label = label,
        value = value?.let { Fmt.oneDecimal(it) } ?: NO_DATA,
        unit = value?.let { "°C" },
        icon = icon,
        modifier = modifier,
    )
}

@Composable
private fun PercentTile(label: String, value: Double?, icon: ImageVector, modifier: Modifier) {
    MkStatTile(
        label = label,
        value = value?.let { Fmt.int(it) } ?: NO_DATA,
        unit = value?.let { "%" },
        icon = icon,
        modifier = modifier,
    )
}

// ── Air quality: LTO + CO₂ gauges ───────────────────────────────────────────

@Composable
private fun AirQualityCard(snapshot: IlmastoSnapshot) {
    val hvac = snapshot.hvac
    val air = snapshot.air
    val co2 = air?.co2

    MkCard {
        MkCardHead("Ilmanlaatu")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            GaugeSlot {
                val lto = hvac?.recoveryEfficiencyPct
                if (lto != null) {
                    MkGauge(
                        value = lto.toFloat(),
                        max = 100f,
                        label = "Lämmöntalteenotto",
                        unit = "%",
                        status = ltoStatus(lto),
                    )
                } else {
                    GaugePlaceholder("Lämmöntalteenotto")
                }
            }
            GaugeSlot {
                if (co2 != null) {
                    val location = air.location.takeIf { it.isNotBlank() }
                    MkGauge(
                        value = co2.value.toFloat(),
                        max = 1500f,
                        label = if (location != null) "CO₂ · $location" else "CO₂",
                        unit = "",
                        status = air.statusOf(co2) ?: "accent",
                    )
                } else {
                    GaugePlaceholder("CO₂")
                }
            }
        }
    }
}

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

// ── Temperature history ─────────────────────────────────────────────────────

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
        MkTimeRange(
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

// ── Ventilation ─────────────────────────────────────────────────────────────

@Composable
private fun VentilationCard(ventilation: Ventilation) {
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

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}
