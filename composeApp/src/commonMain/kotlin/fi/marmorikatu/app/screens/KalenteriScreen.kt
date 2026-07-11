package fi.marmorikatu.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import fi.marmorikatu.app.components.GarbageScheduleCard
import fi.marmorikatu.app.components.MkButton
import fi.marmorikatu.app.components.MkButtonVariant
import fi.marmorikatu.app.components.MkCard
import fi.marmorikatu.app.components.MkFreshness
import fi.marmorikatu.app.components.MkPullToRefresh
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import org.koin.compose.viewmodel.koinViewModel

/**
 * Kalenteri: the household's shared view of what's coming — the waste pickup
 * schedule up top, then the family calendar grouped by day.
 */
@Composable
fun KalenteriScreen(viewModel: KalenteriViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val updatedAt by viewModel.updatedAt.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    val c = MkTheme.colors
    var selected by remember { mutableStateOf<CalendarSelection?>(null) }

    MkPullToRefresh(refreshing = refreshing, onRefresh = viewModel::refresh) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = MkSpacing.pagePad,
                end = MkSpacing.pagePad,
                top = MkSpacing.pagePad,
                bottom = MkSpacing.pagePad + MkSpacing.scrollBottomGap,
            ),
            verticalArrangement = Arrangement.spacedBy(MkSpacing.stackGap),
        ) {

            item {
                Text(
                    text = "Kalenteri",
                    style = MkTheme.type.title,
                    color = c.inkHi,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item(key = "garbage") { GarbageScheduleCard(state.garbage) }

            item(key = "cal-head") {
                Text(
                    text = "Perhekalenteri".uppercase(),
                    style = MkTheme.type.kicker,
                    color = c.inkLo,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            when {
                state.failed -> item {
                    Text("Kalenteritietoja ei saatu", style = MkTheme.type.body, color = c.inkLo)
                }

                // Nothing loaded yet on the first pass: stay quiet.
                state.loading && state.days.isEmpty() -> Unit

                state.days.isEmpty() -> item {
                    Text("Ei tulevia tapahtumia", style = MkTheme.type.body, color = c.inkLo)
                }

                else -> state.days.forEach { day ->
                    item(key = "day-${day.dateLabel}") { DayHeader(day.dayLabel, day.dateLabel) }
                    day.events.forEachIndexed { index, event ->
                        item(key = "ev-${day.dateLabel}-$index-${event.time}") {
                            // Stable per-event colour, cycling the design's event palette.
                            val palette = eventPalette()
                            val color = palette[(day.dateLabel + event.time + event.title).hashCode().mod(palette.size)]
                            EventRow(event, color) {
                                selected = CalendarSelection(day.dayLabel, day.dateLabel, event)
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { CalendarDetailDialog(it, onDismiss = { selected = null }) }
}

/** A calendar entry plus the day it belongs to, for the detail popup. */
private data class CalendarSelection(
    val dayLabel: String,
    val dateLabel: String,
    val event: CalendarEvent,
)

@Composable
private fun eventPalette(): List<Color> {
    val c = MkTheme.colors
    return listOf(c.accent, c.vizSecondary, c.vizTertiary, c.warm, c.vizRoom)
}

/** A day grouping heading: `Tänään · ke 18.2.`. */
@Composable
private fun DayHeader(dayLabel: String, dateLabel: String) {
    val c = MkTheme.colors
    Row(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = dayLabel,
            style = MkTheme.type.heading.copy(fontSize = MkTheme.type.body.fontSize, letterSpacing = (-0.02).em),
            color = c.inkHi,
        )
        Text(dateLabel, style = MkTheme.type.readout(11, FontWeight.Normal), color = c.inkLo)
    }
}

/** One event: time · a coloured spine · title · who. */
@Composable
private fun EventRow(event: CalendarEvent, color: Color, onClick: () -> Unit) {
    val c = MkTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MkRadius.md))
            .background(c.surfaceCard)
            .border(1.dp, c.borderSubtle, RoundedCornerShape(MkRadius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = MkSpacing.x3, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = event.time,
            style = MkTheme.type.readout(13),
            color = c.inkHi,
            modifier = Modifier.width(44.dp),
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(if (event.who.isNotBlank()) 38.dp else 30.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        // Title and location on their own lines — a long address used to share the
        // title's line and squeeze it out of view entirely.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MkTheme.type.body,
                color = c.inkHi,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (event.who.isNotBlank()) {
                Text(
                    text = event.who,
                    style = MkTheme.type.readout(11, FontWeight.Normal),
                    color = c.inkLo,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        Icon(MkIcons.CaretRight, contentDescription = null, tint = c.inkLo, modifier = Modifier.size(16.dp))
    }
}

/** Tap-through detail for a calendar entry: day, title, time and full location. */
@Composable
private fun CalendarDetailDialog(selection: CalendarSelection, onDismiss: () -> Unit) {
    val c = MkTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        MkCard {
            Column(verticalArrangement = Arrangement.spacedBy(MkSpacing.x3)) {
                Text(
                    text = "${selection.dayLabel} · ${selection.dateLabel}".uppercase(),
                    style = MkTheme.type.readout(10, FontWeight.Normal).copy(letterSpacing = 0.1.em),
                    color = c.inkLo,
                    maxLines = 1,
                )
                Text(
                    text = selection.event.title,
                    style = MkTheme.type.heading,
                    color = c.inkHi,
                )
                DetailLine(MkIcons.Clock, selection.event.time.ifBlank { "koko päivä" })
                if (selection.event.who.isNotBlank()) DetailLine(MkIcons.MapPin, selection.event.who)
                MkButton(
                    text = "Sulje",
                    onClick = onDismiss,
                    variant = MkButtonVariant.Primary,
                    block = true,
                    modifier = Modifier.padding(top = MkSpacing.x1),
                )
            }
        }
    }
}

@Composable
private fun DetailLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    val c = MkTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.inkMid, modifier = Modifier.size(16.dp))
        Text(text, style = MkTheme.type.body, color = c.inkHi)
    }
}
