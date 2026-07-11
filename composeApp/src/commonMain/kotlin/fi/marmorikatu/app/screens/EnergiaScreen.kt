package fi.marmorikatu.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import fi.marmorikatu.app.components.MkCard
import fi.marmorikatu.app.components.MkCardHead
import fi.marmorikatu.app.components.MkFreshness
import fi.marmorikatu.app.components.MkPriceBars
import fi.marmorikatu.app.components.MkPullToRefresh
import fi.marmorikatu.app.components.MkStatTile
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.core.model.EnergyReading
import fi.marmorikatu.core.model.PriceTier
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.koin.compose.viewmodel.koinViewModel
import kotlin.coroutines.coroutineContext

private const val PRICE_LABELS = "00,06,12,18,24"
private const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L

/**
 * Energia: pörssisähkön hinta tänään sekä lämpöpumpun ja lisävastuksen
 * reaaliaikainen teho ja mittarilukemat.
 */
@Composable
fun EnergiaScreen(
    modifier: Modifier = Modifier,
    viewModel: EnergiaViewModel = koinViewModel(),
) {
    val priceState by viewModel.prices.collectAsState()
    val liveEnergy by viewModel.liveEnergy.collectAsState()
    val consumption by viewModel.consumption.collectAsState()
    val totalConsumptionKwh by viewModel.totalConsumptionKwh.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val updatedAt by viewModel.updatedAt.collectAsState()

    // Refresh on entry, then re-poll while the screen stays composed (prices
    // change hourly). The loop cancels when the screen leaves composition.
    LaunchedEffect(Unit) {
        while (coroutineContext.isActive) {
            viewModel.refresh()
            delay(REFRESH_INTERVAL_MS)
        }
    }

    MkPullToRefresh(refreshing = refreshing, onRefresh = viewModel::refresh) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = MkSpacing.pagePad,
                    end = MkSpacing.pagePad,
                    top = MkSpacing.pagePad,
                    bottom = MkSpacing.pagePad + MkSpacing.scrollBottomGap,
                ),
            verticalArrangement = Arrangement.spacedBy(MkSpacing.x3),
        ) {
            PriceCard(priceState)
            MetersCard(liveEnergy)
            TotalsRow(totalConsumptionKwh)
            ConsumptionCard(consumption)
            CheapestWindowTip(priceState)
        }
    }
}

@Composable
private fun PriceCard(state: PriceState) {
    MkCard {
        MkCardHead("Sähkön hinta tänään")
        when (state) {
            is PriceState.Ready -> {
                val m = state.model
                // Three bands from the backend optimizer (CHEAP/NORMAL/EXPENSIVE),
                // so a genuinely cheap hour reads cheap even on a flat day.
                val (tag, status) = when (m.nowTier) {
                    PriceTier.Expensive -> "KALLIS NYT" to "warn"
                    PriceTier.Cheap -> "EDULLINEN" to "ok"
                    PriceTier.Normal -> "NORMAALI" to "info"
                    null -> null to "info"
                }
                MkPriceBars(
                    bars = m.bars,
                    labels = PRICE_LABELS.split(","),
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

/** Today's total consumption + its cost (design: "Kulutus tänään" / "Kustannus"). */
@Composable
private fun TotalsRow(totalKwh: Double?) {
    Row(horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3)) {
        MkStatTile(
            label = "Kulutus tänään",
            value = totalKwh?.let { Fmt.oneDecimal(it) } ?: "Ei tietoa",
            unit = if (totalKwh != null) "kWh" else null,
            icon = MkIcons.LightningFill,
            modifier = Modifier.weight(1f),
        )
        MkStatTile(
            label = "Kustannus",
            // No wired backend source yet: the MCP `get_energy_cost` tool exists
            // server-side but isn't called from here, so an honest placeholder
            // beats fabricating a euro figure. See EnergiaViewModel gap notes.
            value = "Ei tietoa",
            icon = null,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Cheapest hour block today, as a spot-price tip (design: "Halvimmat tunnit
 * tänä yönä"). Renamed to "tänään" here since it's derived from today's whole
 * curve, not specifically the coming night — honest about what was computed.
 */
@Composable
private fun CheapestWindowTip(state: PriceState) {
    val window = (state as? PriceState.Ready)?.model?.cheapestWindow ?: return
    val c = MkTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MkRadius.md))
            .background(c.accentDim)
            .border(1.dp, c.accentBorder, RoundedCornerShape(MkRadius.md))
            .padding(horizontal = MkSpacing.x3, vertical = MkSpacing.x3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2),
    ) {
        Icon(MkIcons.LightbulbFill, null, tint = c.accent, modifier = Modifier.size(16.dp))
        Text(
            text = buildAnnotatedString {
                append("Halvimmat tunnit tänään ")
                withStyle(SpanStyle(color = c.inkHi, fontFamily = MkTheme.type.mono, fontWeight = FontWeight.Medium)) {
                    append("${pad2(window.startHour)}–${pad2(window.endHour)}")
                }
                append(" · ${Fmt.comma(window.minCents, 1)}–${Fmt.comma(window.maxCents, 1)} c/kWh")
            },
            style = MkTheme.type.body,
            color = c.inkMid,
        )
    }
}

private fun pad2(value: Int): String = value.toString().padStart(2, '0')

/** Estimated consumption by component, as labelled bars (design: "Kulutus laitteittain"). */
@Composable
private fun ConsumptionCard(components: List<EnergyComponent>?) {
    if (components.isNullOrEmpty()) return
    val c = MkTheme.colors
    val max = components.maxOf { it.kwh }.coerceAtLeast(0.01)
    MkCard {
        MkCardHead("Kulutus laitteittain")
        components.forEach { comp ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(MkRadius.sm))
                        .background(c.accentDim),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(consumerIcon(comp.name), null, tint = c.accent, modifier = Modifier.size(16.dp))
                }
                Text(
                    text = comp.name,
                    style = MkTheme.type.body,
                    color = c.inkHi,
                    modifier = Modifier.width(96.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(7.dp)
                        .clip(RoundedCornerShape(MkRadius.round))
                        .background(c.track),
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
                    text = "${Fmt.oneDecimal(comp.kwh)} kWh",
                    style = MkTheme.type.readout(12),
                    color = c.inkMid,
                    modifier = Modifier.width(72.dp),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun consumerIcon(name: String): ImageVector = when {
    name.contains("Maalämpö") -> MkIcons.ThermometerHot
    name.contains("Valaistus") -> MkIcons.LightbulbFill
    name.contains("Sauna") -> MkIcons.FlameFill
    name.contains("Ilmanvaihto") -> MkIcons.Fan
    else -> MkIcons.LightningFill
}

@Composable
private fun MetersCard(live: Map<String, EnergyReading>) {
    val c = MkTheme.colors
    MkCard {
        MkCardHead("Sähkömittari")
        MeterRow("Maalämpö", MkIcons.ThermometerHot, live["heatpump"])
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(c.borderSubtle),
        )
        MeterRow("Lisävastus", MkIcons.FlameFill, live["extra"])
        // Grid frequency + three-phase voltages from the OR-WE-517 payload (both
        // meters read the same grid). Shown only when the fields are present.
        GridMeterInfo(live["heatpump"] ?: live["extra"])
    }
}

/** Grid frequency + L1/L2/L3 voltages, read from a meter's raw OR-WE-517 map. */
@Composable
private fun GridMeterInfo(reading: EnergyReading?) {
    val c = MkTheme.colors
    val raw = reading?.raw ?: return
    fun pick(key: String): Double? =
        raw.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
    val freq = pick("Grid_Frequency")
    val v1 = pick("L1_Voltage")
    val v2 = pick("L2_Voltage")
    val v3 = pick("L3_Voltage")
    // Cumulative import/export (design: "Verkosta" / "Verkkoon"), from the
    // OR-WE-517's own forward/reverse energy registers — real meter fields,
    // not derived. Reverse stays near-zero unless something local feeds power
    // back (there's no solar on this circuit today), but it's still honest to show.
    val fromGrid = pick("Forward_Active_Energy")
    val toGrid = pick("Reverse_Active_Energy")
    if (freq == null && v1 == null && v2 == null && v3 == null) return

    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
    Column(
        modifier = Modifier.padding(top = MkSpacing.x3),
        verticalArrangement = Arrangement.spacedBy(MkSpacing.x2),
    ) {
        if (freq != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Verkkotaajuus", style = MkTheme.type.body, color = c.inkMid)
                Text("${Fmt.oneDecimal(freq)} Hz", style = MkTheme.type.readout(14), color = c.inkHi)
            }
        }
        if (v1 != null || v2 != null || v3 != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2),
            ) {
                PhaseChip("L1", v1, Modifier.weight(1f))
                PhaseChip("L2", v2, Modifier.weight(1f))
                PhaseChip("L3", v3, Modifier.weight(1f))
            }
        }
        // Verkosta/Verkkoon only — the per-meter "Mittarilukema" is already shown
        // above each MeterRow, so repeating it here would just be a near-duplicate
        // of the same cumulative total under a different register name.
        if (fromGrid != null || toGrid != null) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(c.borderSubtle))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = MkSpacing.x1),
                horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2),
            ) {
                MeterTotalCell("Verkosta", fromGrid, c.inkHi, TextAlign.Start, Modifier.weight(1f))
                MeterTotalCell("Verkkoon", toGrid, c.accent, TextAlign.End, Modifier.weight(1f))
            }
        }
    }
}

/** One cell of the meter's cumulative-total footer: uppercase label + a `kWh` readout. */
@Composable
private fun MeterTotalCell(label: String, kwh: Double?, valueColor: Color, align: TextAlign, modifier: Modifier) {
    val c = MkTheme.colors
    Column(modifier = modifier, horizontalAlignment = when (align) {
        TextAlign.Start -> Alignment.Start
        TextAlign.End -> Alignment.End
        else -> Alignment.CenterHorizontally
    }) {
        Text(
            text = label,
            style = MkTheme.type.kicker,
            color = c.inkLo,
            maxLines = 1,
        )
        Text(
            text = kwh?.let { "${Fmt.oneDecimal(it)} kWh" } ?: "—",
            style = MkTheme.type.readout(15),
            color = valueColor,
            maxLines = 1,
        )
    }
}

@Composable
private fun PhaseChip(label: String, volts: Double?, modifier: Modifier = Modifier) {
    val c = MkTheme.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(MkRadius.sm))
            .background(c.surfaceInset)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MkTheme.type.readout(10), color = c.inkLo)
        Text(
            volts?.let { "${Fmt.oneDecimal(it)} V" } ?: "—",
            style = MkTheme.type.readout(14),
            color = c.inkHi,
            maxLines = 1,
        )
    }
}

@Composable
private fun MeterRow(name: String, icon: ImageVector, reading: EnergyReading?) {
    val c = MkTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = MkSpacing.x2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(c.accentDim, RoundedCornerShape(MkRadius.sm)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = c.accent, modifier = Modifier.size(16.dp))
        }
        Text(
            text = name,
            style = MkTheme.type.body.copy(fontWeight = FontWeight.Medium),
            color = c.inkHi,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            // Live power. The OR-WE-517 meter reports Total_Active_Power; treat
            // the number as kW per the data contract.
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = reading?.powerKw?.let { Fmt.oneDecimal(it) } ?: "—",
                    style = MkTheme.type.readout(20, FontWeight.Medium),
                    color = c.inkHi,
                )
                Text(
                    text = "kW",
                    style = MkTheme.type.readout(11),
                    color = c.inkLo,
                    modifier = Modifier.padding(start = 3.dp),
                )
            }
            Text(
                text = reading?.energyKwh
                    ?.let { "Mittarilukema ${Fmt.oneDecimal(it)} kWh" }
                    ?: "Mittarilukema —",
                style = MkTheme.type.readout(11),
                color = c.inkLo,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
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
