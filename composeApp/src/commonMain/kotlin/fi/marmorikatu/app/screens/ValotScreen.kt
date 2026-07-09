package fi.marmorikatu.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fi.marmorikatu.app.components.MkAreaLightCard
import fi.marmorikatu.app.components.MkBanner
import fi.marmorikatu.app.components.MkButton
import fi.marmorikatu.app.components.MkButtonSize
import fi.marmorikatu.app.components.MkButtonVariant
import fi.marmorikatu.app.components.MkFreshness
import fi.marmorikatu.app.components.MkLight
import fi.marmorikatu.app.components.MkLightMode
import fi.marmorikatu.app.components.MkLightRow
import fi.marmorikatu.app.components.MkPullToRefresh
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.core.model.Light
import org.koin.compose.viewmodel.koinViewModel

/**
 * Valot: control every light in the house. Lights are grouped by floor and, within
 * a floor, by area (the first word of their name). Areas with several lights get a
 * scene ladder; lone fixtures get a plain toggle row.
 */
@Composable
fun ValotScreen(
    modifier: Modifier = Modifier,
    viewModel: ValotViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val failure by viewModel.failure.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val updatedAt by viewModel.updatedAt.collectAsState()
    val colors = MkTheme.colors

    LaunchedEffect(Unit) { viewModel.refresh() }

    MkPullToRefresh(refreshing = refreshing, onRefresh = viewModel::refresh) {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .background(colors.appBg),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = MkSpacing.pagePad,
                vertical = MkSpacing.x4,
            ),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            item(key = "freshness") {
                MkFreshness(
                    updatedAtEpochSeconds = updatedAt,
                    refreshing = refreshing,
                    onRefresh = viewModel::refresh,
                )
            }

            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${state.areasOn} aluetta päällä",
                        style = MkTheme.type.readout(11).copy(letterSpacing = 0.1.em),
                        color = colors.inkLo,
                    )
                    MkButton(
                        text = "Kaikki pois",
                        onClick = viewModel::allOff,
                        variant = MkButtonVariant.Secondary,
                        size = MkButtonSize.Sm,
                    )
                }
            }

            if (failure) {
                item(key = "failure") {
                    MkBanner(
                        icon = MkIcons.Warning,
                        title = "Komento ei vahvistunut",
                        text = "Valon tila ei päivittynyt ajoissa. Yritä uudelleen.",
                        status = "alarm",
                    )
                }
            }

            if (state.loading) {
                item(key = "loading") {
                    CenteredNote("Haetaan valoja…")
                }
            } else if (state.floors.isEmpty()) {
                item(key = "empty") {
                    CenteredNote("Ei tietoa valoista")
                }
            }

            state.floors.forEach { section ->
                item(key = "floor:${section.label}") {
                    Text(
                        text = section.label,
                        style = MkTheme.type.readout(11).copy(letterSpacing = 0.14.em),
                        color = colors.inkLo,
                        modifier = Modifier.padding(start = 2.dp, top = 5.dp),
                    )
                }
                items(section.areas, key = { it.key }) { area ->
                    when (area) {
                        is AreaUi.SceneArea -> SceneAreaCard(area, viewModel)
                        is AreaUi.SingleLight -> SingleLightRow(area.light, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun SceneAreaCard(area: AreaUi.SceneArea, viewModel: ValotViewModel) {
    val chips = area.lights.map { light ->
        MkLight(
            name = if (light.pendingOn != null) "${light.name}…" else light.name,
            on = light.displayedOn,
        )
    }
    val orderedIds = area.lights.map { it.id }
    MkAreaLightCard(
        name = area.name,
        modifier = Modifier.fillMaxWidth(),
        icon = areaIcon(area.name),
        mode = MkLightMode.Scene,
        level = area.level,
        onLevelChange = { level -> viewModel.setAreaLevel(orderedIds, level) },
        lights = chips,
        showLights = true,
        // A scene ladder cannot say "just this one light"; tapping a chip can.
        onLightToggle = { index, on ->
            area.lights.getOrNull(index)?.let { viewModel.toggle(it.id, on) }
        },
    )
}

@Composable
private fun SingleLightRow(light: Light, viewModel: ValotViewModel) {
    MkLightRow(
        name = light.name,
        modifier = Modifier.fillMaxWidth(),
        meta = if (light.pendingOn != null) "odottaa…" else null,
        icon = areaIcon(light.name.trim().substringBefore(' ')),
        on = light.displayedOn,
        onToggle = { on -> viewModel.toggle(light.id, on) },
    )
}

@Composable
private fun CenteredNote(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MkTheme.type.body.copy(fontWeight = FontWeight.Medium, fontSize = 14.sp),
            color = MkTheme.colors.inkLo,
        )
    }
}

/** Map an area name to its glyph; falls back to a lightbulb. */
private fun areaIcon(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        n.contains("keittiö") -> MkIcons.CookingPot
        n.contains("olohuone") -> MkIcons.Armchair
        n.contains("makuu") || n == "mh" || n.startsWith("mh ") -> MkIcons.Bed
        n.contains("kellari") -> MkIcons.Stairs
        n.contains("sauna") -> MkIcons.Flame
        n.contains("ulko") || n.contains("terassi") ||
            n.contains("autokatos") || n.contains("piha") -> MkIcons.Door
        n.contains("wc") || n.contains("kylpy") -> MkIcons.Drop
        else -> MkIcons.Lightbulb
    }
}
