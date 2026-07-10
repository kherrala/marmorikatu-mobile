package fi.marmorikatu.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import fi.marmorikatu.app.components.Detection
import fi.marmorikatu.app.components.MkAttentionStrip
import fi.marmorikatu.app.components.MkButton
import fi.marmorikatu.app.components.MkButtonSize
import fi.marmorikatu.app.components.MkButtonVariant
import fi.marmorikatu.app.components.MkCameraViewer
import fi.marmorikatu.app.components.MkClimateCard
import fi.marmorikatu.app.components.MkDoorAlert
import fi.marmorikatu.app.components.rememberBase64Painter
import fi.marmorikatu.app.components.MkFreshness
import fi.marmorikatu.app.components.MkMetricDetail
import fi.marmorikatu.app.components.MkPullToRefresh
import fi.marmorikatu.app.components.MkSeries
import fi.marmorikatu.app.components.MkStatTile
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import org.koin.compose.viewmodel.koinViewModel

/**
 * Koti (home): the attention strip, an optional door alert, the climate card,
 * and a 2-column KPI grid. Tapping a KPI swaps the page for its [MkMetricDetail]
 * with a "Takaisin" affordance.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun KotiScreen(viewModel: KotiViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val updatedAt by viewModel.updatedAt.collectAsState()
    val news by viewModel.news.collectAsState()
    val colors = MkTheme.colors

    LaunchedEffect(Unit) { viewModel.refresh() }

    var selKey by remember { mutableStateOf<String?>(null) }
    var roomIndex by remember { mutableStateOf(0) }
    var doorDismissed by remember { mutableStateOf(false) }
    var cameraOpen by remember { mutableStateOf(false) }

    // System back leaves the KPI detail for the grid instead of quitting.
    BackHandler(enabled = selKey != null) { selKey = null }

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

            val selected = selKey?.let { key -> state.kpis.firstOrNull { it.key == key } }

            if (selected != null) {
                MkButton(
                    text = "Takaisin",
                    onClick = { selKey = null },
                    variant = MkButtonVariant.Ghost,
                    size = MkButtonSize.Sm,
                    icon = MkIcons.CaretLeft,
                    modifier = Modifier.align(Alignment.Start),
                )
                val series = if (selected.seriesValues.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        MkSeries(
                            name = null,
                            values = selected.seriesValues,
                            color = lineColor(selected.detailStatus, colors),
                            area = true,
                        ),
                    )
                }
                MkMetricDetail(
                    icon = selected.icon,
                    label = selected.label,
                    value = selected.value,
                    unit = selected.unit ?: selected.detailUnit,
                    series = series,
                    labels = selected.labels,
                    stats = selected.stats,
                    status = selected.detailStatus,
                    // Swipe the card to the right to go back to the grid.
                    modifier = Modifier.pointerInput(selKey) {
                        var dragged = 0f
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragged > 56.dp.toPx()) selKey = null
                                dragged = 0f
                            },
                            onHorizontalDrag = { _, delta -> dragged += delta },
                        )
                    },
                )
                return@Column
            }

            MkAttentionStrip(items = state.attention, updatedAt = Fmt.now())

            val door = state.door
            if (door != null && !doorDismissed) {
                val doorPainter = rememberBase64Painter(door.image)
                MkDoorAlert(
                    painter = doorPainter,
                    title = door.title,
                    time = door.time,
                    subtitle = door.subtitle,
                    detection = Detection(),
                    onView = { cameraOpen = true },
                    onDismiss = { doorDismissed = true },
                )
                if (cameraOpen) {
                    MkCameraViewer(
                        painter = doorPainter,
                        title = door.title,
                        subtitle = door.subtitle,
                        time = door.time,
                        onDismiss = { cameraOpen = false },
                    )
                }
            }

            news?.let { NewsCard(it, onRead = viewModel::readNews) }

            SectionLabel("Valaistus")
            LightPresetRow(onPreset = viewModel::runLightPreset)

            if (state.rooms.isNotEmpty()) {
                val safeIndex = roomIndex.coerceIn(0, state.rooms.size - 1)
                SectionLabel("Lämpötilat")
                MkClimateCard(
                    rooms = state.rooms,
                    index = safeIndex,
                    onIndexChange = { roomIndex = it },
                    targetEnabled = false, // no per-room write path exists
                )
            }

            if (state.kpis.isNotEmpty()) SectionLabel("Mittarit")
            KpiGrid(state.kpis) { selKey = it }

            when {
                state.loading && state.kpis.isEmpty() ->
                    Text("Ladataan…", style = MkTheme.type.label, color = colors.inkLo)
                state.error != null ->
                    Text(state.error!!, style = MkTheme.type.label, color = colors.inkLo)
            }
        }
    }
}

/** Top news headline with a read-aloud action, per the design's news card. */
@Composable
private fun NewsCard(news: NewsHeadline, onRead: () -> Unit) {
    val c = MkTheme.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(fi.marmorikatu.app.theme.MkRadius.lg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surfaceCard)
            .border(1.dp, c.borderSubtle, shape)
            .clickable(onClick = onRead)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(fi.marmorikatu.app.theme.MkRadius.md))
                .background(c.accentDim),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(MkIcons.Info, null, tint = c.accent, modifier = Modifier.size(19.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Uutiset" + news.published.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                style = MkTheme.type.readout(10).copy(letterSpacing = 0.1.em),
                color = c.inkLo,
            )
            Text(
                text = news.title,
                style = MkTheme.type.heading,
                color = c.inkHi,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            androidx.compose.material3.Icon(MkIcons.SpeakerHigh, null, tint = c.accent, modifier = Modifier.size(17.dp))
            Text("Lue", style = MkTheme.type.readout(10).copy(letterSpacing = 0.06.em), color = c.accent)
        }
    }
}

/** Four one-tap lighting quick-states, per the design's "Pikatilat" grid. */
@Composable
private fun LightPresetRow(onPreset: (KotiLightPreset) -> Unit) {
    val c = MkTheme.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(fi.marmorikatu.app.theme.MkRadius.md)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KotiLightPreset.entries.forEach { p ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(c.surfaceCard)
                    .border(1.dp, c.borderSubtle, shape)
                    .clickable { onPreset(p) }
                    .padding(vertical = 11.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                androidx.compose.material3.Icon(
                    imageVector = p.icon,
                    contentDescription = null,
                    tint = c.accent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = p.label,
                    style = MkTheme.type.label,
                    color = c.inkMid,
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/** An uppercase mono section label with a live-data dot, per the design. */
@Composable
private fun SectionLabel(text: String) {
    val colors = MkTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(colors.accent, androidx.compose.foundation.shape.CircleShape),
        )
        Text(
            text = text,
            style = MkTheme.type.readout(11).copy(letterSpacing = 0.12.em),
            color = colors.inkLo,
        )
    }
}

/** Two-column grid of KPI tiles; each opens its detail via [onSelect]. */
@Composable
private fun KpiGrid(kpis: List<KotiKpi>, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        kpis.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                row.forEach { kpi ->
                    MkStatTile(
                        label = kpi.label,
                        value = kpi.value,
                        unit = kpi.unit,
                        icon = kpi.icon,
                        status = kpi.statStatus,
                        tag = kpi.tag,
                        tagStatus = kpi.tagStatus,
                        onClick = { onSelect(kpi.key) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun lineColor(status: String, colors: fi.marmorikatu.app.theme.MkColors): Color = when (status) {
    "ok" -> colors.statusOk
    "warn" -> colors.warm
    "alarm" -> colors.statusAlarmInk
    "info" -> colors.statusInfo
    else -> colors.accent
}
