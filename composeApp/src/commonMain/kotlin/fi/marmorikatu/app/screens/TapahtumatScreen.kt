package fi.marmorikatu.app.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fi.marmorikatu.app.components.EventEntry
import fi.marmorikatu.app.components.MkCameraCard
import fi.marmorikatu.app.components.rememberBase64Painter
import fi.marmorikatu.app.components.MkEventFeed
import fi.marmorikatu.app.components.MkFreshness
import fi.marmorikatu.app.components.MkPullToRefresh
import fi.marmorikatu.app.components.MkTag
import fi.marmorikatu.app.components.MkTagStatus
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.app.theme.rememberMkInteractionSource
import fi.marmorikatu.core.model.Announcement
import org.koin.compose.viewmodel.koinViewModel

/** Local filter over the event log; filtering never touches the repository. */
private enum class EventFilter(val label: String) {
    All("Kaikki"),
    Important("Tärkeät"),
    Lights("Valot"),
}

private fun EventFilter.matches(a: Announcement): Boolean = when (this) {
    EventFilter.All -> true
    EventFilter.Important -> a.priority <= 1
    EventFilter.Lights -> a.kind.startsWith("light")
}

/**
 * Tapahtumat — the house event log: an optional camera snapshot from the
 * newest image-bearing announcement, a local filter row, and the live feed.
 */
@Composable
fun TapahtumatScreen(viewModel: TapahtumatViewModel = koinViewModel()) {
    val colors = MkTheme.colors
    val state by viewModel.uiState.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val updatedAt by viewModel.updatedAt.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    var filter by remember { mutableStateOf(EventFilter.All) }

    val filtered = remember(state.events, filter) {
        state.events.filter { filter.matches(it) }
    }

    // Newest announcement that carries an image → a camera card above the feed.
    val cameraEvent = remember(state.events) {
        state.events.firstOrNull { it.image != null }
    }

    MkPullToRefresh(refreshing = refreshing, onRefresh = viewModel::refresh) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MkSpacing.pagePad, vertical = MkSpacing.x3),
            verticalArrangement = Arrangement.spacedBy(MkSpacing.x3),
        ) {
            MkFreshness(
                updatedAtEpochSeconds = updatedAt,
                refreshing = refreshing,
                onRefresh = viewModel::refresh,
            )

            if (cameraEvent != null) {
                MkCameraCard(
                    painter = rememberBase64Painter(cameraEvent.image),
                    title = cameraEvent.text,
                    subtitle = cameraEvent.kind,
                    time = Fmt.clock(cameraEvent.ts),
                    metaTime = Fmt.since(cameraEvent.ts),
                    priority = cameraEvent.priority,
                    latest = true,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2)) {
                EventFilter.entries.forEach { option ->
                    val interaction = rememberMkInteractionSource()
                    MkTag(
                        text = option.label,
                        status = if (option == filter) MkTagStatus.Accent else MkTagStatus.Neutral,
                        modifier = Modifier.clickable(interaction, indication = null) { filter = option },
                    )
                }
            }

            when {
                // First load, nothing merged yet: stay quiet.
                state.loading && state.events.isEmpty() -> Unit

                state.error && state.events.isEmpty() ->
                    Text(
                        "Tapahtumia ei voitu ladata",
                        style = MkTheme.type.body.copy(color = colors.inkLo),
                    )

                filtered.isEmpty() ->
                    Text(
                        "Ei tapahtumia",
                        style = MkTheme.type.body.copy(color = colors.inkLo),
                    )

                else -> {
                    val newest = filtered.first()
                    MkEventFeed(
                        events = filtered.map {
                            EventEntry(
                                priority = it.priority,
                                text = it.text,
                                time = Fmt.clock(it.ts),
                            )
                        },
                        live = state.streaming,
                        updatedLabel = Fmt.since(newest.ts),
                        tint = true,
                    )
                }
            }
        }
    }
}
