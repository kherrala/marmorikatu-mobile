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
import androidx.compose.ui.unit.sp
import fi.marmorikatu.app.components.MkAreaLightCard
import fi.marmorikatu.app.components.MkBanner
import fi.marmorikatu.app.components.MkButton
import fi.marmorikatu.app.components.MkButtonSize
import fi.marmorikatu.app.components.MkButtonVariant
import fi.marmorikatu.app.components.MkLight
import fi.marmorikatu.app.components.MkLightMode
import fi.marmorikatu.app.components.MkLightRow
import fi.marmorikatu.app.components.MkPullToRefresh
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.core.model.Floor
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
                start = MkSpacing.pagePad,
                end = MkSpacing.pagePad,
                top = MkSpacing.x4,
                bottom = MkSpacing.x4 + MkSpacing.scrollBottomGap,
            ),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {

            // Whole-house off — the redesign's single global control, pinned top.
            item(key = "allOff") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    MkButton(
                        text = "Kaikki pois",
                        onClick = viewModel::allOff,
                        variant = MkButtonVariant.Secondary,
                        size = MkButtonSize.Sm,
                        icon = MkIcons.Power,
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
                item(key = "loading") { CenteredNote("Haetaan valoja…") }
            } else if (state.floors.isEmpty()) {
                item(key = "empty") { CenteredNote("Ei tietoa valoista") }
            }

            // Every floor stacked under its own heading (design: no pager).
            state.floors.forEach { section ->
                item(key = "floor:${section.floor.name}") { FloorHeading(section) }
                items(section.areas, key = { "flr:${section.floor.name}:${it.key}" }) { area ->
                    AreaCard(area, viewModel)
                }
            }
        }
    }
}

/** A floor separator heading (design's `mk-floor`): icon + floor name + on-count. */
@Composable
private fun FloorHeading(section: FloorSection) {
    val c = MkTheme.colors
    val onCount = section.areas.count { it.isOn() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.Icon(
            floorIcon(section.floor), null,
            tint = c.inkMid,
            modifier = Modifier.size(17.dp),
        )
        Text(section.label, style = MkTheme.type.heading, color = c.inkHi, modifier = Modifier.weight(1f))
        if (onCount > 0) {
            Text("$onCount päällä", style = MkTheme.type.readout(11), color = c.accent)
        }
    }
}

/** Render one area as its matching card/row. */
@Composable
private fun AreaCard(area: AreaUi, viewModel: ValotViewModel) {
    when (area) {
        is AreaUi.SceneArea -> SceneAreaCard(area, viewModel)
        is AreaUi.ToggleGroup -> ToggleGroupCard(area, viewModel)
        is AreaUi.SingleLight -> SingleLightRow(area.light, viewModel)
    }
}

private fun floorIcon(floor: Floor): ImageVector = when (floor) {
    Floor.ALAKERTA -> MkIcons.HouseFill
    Floor.YLAKERTA -> MkIcons.House
    Floor.KELLARI -> MkIcons.Stairs
    Floor.ULKO -> MkIcons.Door
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
private fun ToggleGroupCard(area: AreaUi.ToggleGroup, viewModel: ValotViewModel) {
    val chips = area.lights.map { light ->
        MkLight(
            name = if (light.pendingOn != null) "${light.name}…" else light.name,
            on = light.displayedOn,
        )
    }
    MkAreaLightCard(
        name = area.name,
        modifier = Modifier.fillMaxWidth(),
        icon = areaIcon(area.name),
        mode = MkLightMode.Switch,
        lights = chips,
        showLights = true,
        on = area.lights.firstOrNull()?.displayedOn ?: false,
        onToggle = { on -> area.lights.firstOrNull()?.let { viewModel.toggle(it.id, on) } },
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
