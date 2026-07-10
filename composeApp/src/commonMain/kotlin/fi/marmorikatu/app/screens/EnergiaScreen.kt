package fi.marmorikatu.app.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
                .padding(MkSpacing.pagePad),
            verticalArrangement = Arrangement.spacedBy(MkSpacing.x3),
        ) {
            MkFreshness(
                updatedAtEpochSeconds = updatedAt,
                refreshing = refreshing,
                onRefresh = viewModel::refresh,
            )
            PriceCard(priceState)
            PriceStats(priceState)
            ConsumptionCard(consumption)
            MetersCard(liveEnergy)
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
                val expensive = m.isExpensiveNow
                MkPriceBars(
                    bars = m.bars,
                    labels = PRICE_LABELS.split(","),
                    height = 132.dp,
                    nowValue = m.currentCents?.let { Fmt.oneDecimal(it) },
                    nowUnit = "c/kWh",
                    nowTag = if (expensive) "KALLIS NYT" else "EDULLINEN",
                    nowStatus = if (expensive) "warn" else "ok",
                )
            }
            PriceState.Loading -> QuietLine("Ladataan hintoja…")
            PriceState.Failed -> QuietLine("Ei tietoa")
        }
    }
}

@Composable
private fun PriceStats(state: PriceState) {
    val model = (state as? PriceState.Ready)?.model
    Row(horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3)) {
        PriceStat("Halvin", model?.minCents, Modifier.weight(1f))
        PriceStat("Kallein", model?.maxCents, Modifier.weight(1f))
        PriceStat("Keskiarvo", model?.avgCents, Modifier.weight(1f))
    }
}

@Composable
private fun PriceStat(label: String, cents: Double?, modifier: Modifier) {
    val known = cents != null
    MkStatTile(
        label = label,
        value = if (known) Fmt.oneDecimal(cents!!) else "Ei tietoa",
        unit = if (known) "c/kWh" else null,
        icon = MkIcons.Lightning,
        modifier = modifier,
    )
}

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
        MkCardHead("Kulutus laitteittain")
        MeterRow("Lämpöpumppu", MkIcons.ThermometerHot, live["heatpump"])
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(c.borderSubtle),
        )
        MeterRow("Lisävastus", MkIcons.FlameFill, live["extra"])
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
