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
import fi.marmorikatu.app.components.MkCard
import fi.marmorikatu.app.components.MkCardPadding
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
                    items(state.departures, key = { it.lineRef + it.departureTimeMs }) { d ->
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
