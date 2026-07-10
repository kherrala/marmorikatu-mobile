package fi.marmorikatu.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import fi.marmorikatu.app.components.TimeRangeOption
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
@Composable
fun KotiScreen(viewModel: KotiViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val updatedAt by viewModel.updatedAt.collectAsState()
    val colors = MkTheme.colors

    LaunchedEffect(Unit) { viewModel.refresh() }

    var selKey by remember { mutableStateOf<String?>(null) }
    var range by remember { mutableStateOf(TimeRangeOption.H24) }
    var roomIndex by remember { mutableStateOf(0) }
    var doorDismissed by remember { mutableStateOf(false) }
    var cameraOpen by remember { mutableStateOf(false) }

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
                    range = range,
                    onRangeChange = { range = it },
                    series = series,
                    labels = selected.labels,
                    stats = selected.stats,
                    status = selected.detailStatus,
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

            if (state.rooms.isNotEmpty()) {
                val safeIndex = roomIndex.coerceIn(0, state.rooms.size - 1)
                MkClimateCard(
                    rooms = state.rooms,
                    index = safeIndex,
                    onIndexChange = { roomIndex = it },
                    targetEnabled = false, // no per-room write path exists
                )
            }

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
