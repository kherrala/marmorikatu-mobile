package fi.marmorikatu.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import fi.marmorikatu.app.components.MkCard
import fi.marmorikatu.app.components.MkCardPadding
import fi.marmorikatu.app.components.MkCardStatus
import fi.marmorikatu.app.components.MkFreshness
import fi.marmorikatu.app.components.MkPullToRefresh
import fi.marmorikatu.app.components.MkTag
import fi.marmorikatu.app.components.MkTagStatus
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.core.model.BusDeparture
import org.koin.compose.viewmodel.koinViewModel

/**
 * Bussit: the live Nysse departure board. A refreshing list of the next buses,
 * each with the minutes until it leaves and a "leave home now" hint.
 */
@Composable
fun BussitScreen(viewModel: BussitViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val updatedAt by viewModel.updatedAt.collectAsState()

    // The loop lives with the composition: it auto-refreshes every 30 s while
    // the screen is visible and stops when it leaves.
    LaunchedEffect(Unit) { viewModel.autoRefresh() }

    val c = MkTheme.colors

    MkPullToRefresh(refreshing = refreshing, onRefresh = viewModel::refresh) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(MkSpacing.pagePad),
            verticalArrangement = Arrangement.spacedBy(MkSpacing.stackGap),
        ) {
            item {
                MkFreshness(
                    updatedAtEpochSeconds = updatedAt,
                    refreshing = refreshing,
                    onRefresh = viewModel::refresh,
                )
            }

            item {
                Text(
                    text = "Bussit",
                    style = MkTheme.type.title,
                    color = c.inkHi,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            when {
                state.failed -> item {
                    Text(
                        text = "Bussitietoja ei saatu",
                        style = MkTheme.type.body,
                        color = c.inkLo,
                    )
                }

                // Nothing loaded yet on the first pass: stay quiet.
                state.loading && state.departures.isEmpty() -> Unit

                state.departures.isEmpty() -> item {
                    Text(
                        text = "Ei lähteviä busseja",
                        style = MkTheme.type.body,
                        color = c.inkLo,
                    )
                }

                else -> {
                    val featured = state.departures.first()
                    val rest = state.departures.drop(1)
                    item(key = "featured") { FeaturedDepartureCard(featured) }
                    if (rest.isNotEmpty()) {
                        item(key = "rest-head") {
                            Text(
                                text = "Seuraavat lähdöt",
                                style = MkTheme.type.heading,
                                color = c.inkMid,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    items(rest, key = { it.lineRef + it.departureTimeMs }) { d ->
                        DepartureCard(d)
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .height(7.dp)
                                    .defaultMinSize(minWidth = 7.dp)
                                    .clip(RoundedCornerShape(MkRadius.round))
                                    .background(c.statusOk),
                            )
                            Text(
                                text = "Nysse-reaaliaika · päivittyy jatkuvasti",
                                style = MkTheme.type.readout(11, FontWeight.Normal),
                                color = c.inkLo,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The soonest departure, promoted to a large card: line, leave/arrive times, leave-by. */
@Composable
private fun FeaturedDepartureCard(d: BusDeparture) {
    val c = MkTheme.colors
    val minutes = Fmt.minutesUntil(d.departureTimeMs)
    val leaveIn = d.leaveByMs?.let { Fmt.minutesUntil(it) }
    val walk = d.leaveByMs?.let { ((d.departureTimeMs - it) / 60_000L).toInt() }?.takeIf { it > 0 }
    val status = when {
        leaveIn != null && leaveIn <= 0 -> MkCardStatus.Warn
        minutes < 3 -> MkCardStatus.Accent
        else -> MkCardStatus.None
    }

    MkCard(status = status, interactive = false) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .defaultMinSize(minWidth = 46.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(MkRadius.sm))
                    .background(c.accent)
                    .padding(horizontal = 12.dp),
            ) {
                Text(d.lineRef, style = MkTheme.type.readout(20, FontWeight.SemiBold), color = c.inkOnAccent, maxLines = 1)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = d.destinationName,
                    style = MkTheme.type.heading,
                    color = c.inkHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = listOfNotNull(
                    d.stopName.takeIf { it.isNotBlank() },
                    walk?.let { "$it min kävelyä" },
                ).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(sub, style = MkTheme.type.readout(11), color = c.inkLo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                d.vehicleAtStop -> MkTag(text = "PYSÄKILLÄ", status = MkTagStatus.Accent)
                (d.delaySeconds ?: 0) > 60 -> MkTag(text = "MYÖHÄSSÄ", status = MkTagStatus.Warn)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            FeaturedMetric("Lähtee", Fmt.clock(d.departureTimeMs / 1000.0), c.inkHi)
            FeaturedMetric(
                "Aikaa",
                if (minutes < 1) "nyt" else "$minutes min",
                c.accent,
                align = Alignment.CenterHorizontally,
            )
            if (leaveIn != null) {
                FeaturedMetric(
                    "Kotoa",
                    if (leaveIn <= 0) "Lähde nyt" else "$leaveIn min",
                    if (leaveIn <= 3) c.warm else c.inkHi,
                    align = Alignment.End,
                    valueSize = 18,
                )
            }
        }
    }
}

@Composable
private fun FeaturedMetric(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
    align: Alignment.Horizontal = Alignment.Start,
    valueSize: Int = 26,
) {
    val c = MkTheme.colors
    Column(horizontalAlignment = align) {
        Text(
            text = label.uppercase(),
            style = MkTheme.type.readout(10).copy(letterSpacing = 0.12.em),
            color = c.inkLo,
        )
        Text(value, style = MkTheme.type.readout(valueSize), color = valueColor, maxLines = 1)
    }
}

/** One departure row: line tile · destination + stop · minutes-until readout. */
@Composable
private fun DepartureCard(d: BusDeparture) {
    val c = MkTheme.colors
    val minutes = Fmt.minutesUntil(d.departureTimeMs)

    MkCard(padding = MkCardPadding.Pad, interactive = false) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Line number in a rounded accent tile.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .defaultMinSize(minWidth = 42.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(MkRadius.sm))
                    .background(c.accent)
                    .padding(horizontal = 11.dp),
            ) {
                Text(
                    text = d.lineRef,
                    style = MkTheme.type.readout(17, FontWeight.SemiBold),
                    color = c.inkOnAccent,
                    maxLines = 1,
                )
            }

            // Destination + stop (+ optional leave-home hint).
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = d.destinationName,
                    style = MkTheme.type.body,
                    color = c.inkHi,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (d.stopName.isNotBlank()) {
                    Text(
                        text = d.stopName,
                        style = MkTheme.type.readout(11, FontWeight.Normal),
                        color = c.inkLo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                d.leaveByMs?.let { leaveByMs ->
                    val leaveIn = Fmt.minutesUntil(leaveByMs)
                    val hint = if (leaveIn < 1) "Lähde nyt" else "Lähde $leaveIn min kuluttua"
                    Text(
                        text = hint,
                        style = MkTheme.type.readout(11, FontWeight.Medium),
                        color = c.accent,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                    )
                }
            }

            // Right: optional status tag over the minutes-until readout.
            Column(horizontalAlignment = Alignment.End) {
                when {
                    d.vehicleAtStop -> MkTag(text = "PYSÄKILLÄ", status = MkTagStatus.Accent)
                    (d.delaySeconds ?: 0) > 60 -> MkTag(text = "MYÖHÄSSÄ", status = MkTagStatus.Warn)
                }
                if (minutes < 1) {
                    Text(
                        text = "nyt",
                        style = MkTheme.type.readout(22, FontWeight.Medium),
                        color = c.accent,
                        maxLines = 1,
                    )
                } else {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = minutes.toString(),
                            style = MkTheme.type.readout(22),
                            color = c.inkHi,
                            modifier = Modifier.alignByBaseline(),
                            maxLines = 1,
                        )
                        Text(
                            text = "min",
                            style = MkTheme.type.readout(11, FontWeight.Normal),
                            color = c.inkLo,
                            modifier = Modifier.alignByBaseline().padding(start = 3.dp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
