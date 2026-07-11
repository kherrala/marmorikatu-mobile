package fi.marmorikatu.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import fi.marmorikatu.app.components.MkIconButton
import fi.marmorikatu.app.components.MkLight
import fi.marmorikatu.app.components.MkLightMode
import fi.marmorikatu.app.components.MkLightRow
import fi.marmorikatu.app.components.MkPullToRefresh
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkRadius
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

    // Show one floor at a time (design: paged by floor with a selector).
    var floorIndex by remember { mutableStateOf(0) }

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

            // One-tap lighting presets pinned at the top (design).
            item(key = "presets") {
                PresetBar(active = state.activePreset, onApply = viewModel::applyPreset)
            }

            // Additive area presets — toggle one area without touching the rest.
            item(key = "areaPresets") {
                AreaPresetBar(
                    active = state.activeAreaPresets,
                    onToggle = viewModel::toggleAreaPreset,
                )
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

            if (state.floors.isNotEmpty()) {
                // "Aktiiviset" section — every light on, house-wide. Hidden entirely
                // when nothing is on, so it never wastes space with an empty note.
                if (state.activeAreas.isNotEmpty()) {
                    item(key = "aktiiviset") {
                        ActiveHeader(count = state.areasOn, onAllOff = viewModel::allOff)
                    }
                    items(state.activeAreas, key = { "act:${it.key}" }) { area -> AreaCard(area, viewModel) }
                }

                // Floor pager (real floors only).
                val idx = floorIndex.coerceIn(0, state.floors.size - 1)
                val section = state.floors[idx]
                item(key = "floor-nav") {
                    FloorSelector(
                        section = section,
                        index = idx,
                        count = state.floors.size,
                        onSelect = { floorIndex = it },
                        onFloorOff = { viewModel.floorOff(section.floor) },
                    )
                }
                items(section.areas, key = { "flr:${it.key}" }) { area -> AreaCard(area, viewModel) }
            }
        }
    }
}

/** One-tap preset chips (Aamuvalot / Iltavalot / Elokuva / Kaikki pois). */
@Composable
private fun PresetBar(active: KotiScene?, onApply: (KotiScene) -> Unit) {
    val c = MkTheme.colors
    val shape = RoundedCornerShape(MkRadius.md)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KotiScene.entries.forEach { s ->
            val on = s == active
            Column(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 60.dp)
                    .clip(shape)
                    .background(if (on) c.accent else c.surfaceInset)
                    .border(1.dp, if (on) c.accent else c.borderSubtle, shape)
                    .clickable { onApply(s) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                androidx.compose.material3.Icon(
                    s.icon, null,
                    tint = if (on) c.inkOnAccent else c.inkMid,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = s.label,
                    style = MkTheme.type.label.copy(fontWeight = FontWeight.SemiBold),
                    color = if (on) c.inkOnAccent else c.inkMid,
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/** Additive area chips (Terassi / Autokatos / Kotiintulo): each toggles its own area. */
@Composable
private fun AreaPresetBar(active: Set<LightAreaPreset>, onToggle: (LightAreaPreset) -> Unit) {
    val c = MkTheme.colors
    val shape = RoundedCornerShape(MkRadius.md)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LightAreaPreset.entries.forEach { p ->
            val on = p in active
            Row(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(shape)
                    .background(if (on) c.accentDim else c.surfaceInset)
                    .border(1.dp, if (on) c.accent else c.borderSubtle, shape)
                    .clickable { onToggle(p) }
                    .padding(vertical = 8.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(
                    p.icon, null,
                    tint = if (on) c.accent else c.inkMid,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    text = p.label,
                    style = MkTheme.type.label.copy(fontWeight = FontWeight.SemiBold),
                    color = if (on) c.accent else c.inkMid,
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/** The permanent "Aktiiviset" section header: icon tile, count, whole-house off. */
@Composable
private fun ActiveHeader(count: Int, onAllOff: () -> Unit) {
    val c = MkTheme.colors
    val shape = RoundedCornerShape(MkRadius.lg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surfaceCard)
            .border(1.dp, c.borderSubtle, shape)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(MkRadius.md)).background(c.accentDim),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(MkIcons.LightbulbFill, null, tint = c.accent, modifier = Modifier.size(19.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text("Aktiiviset", style = MkTheme.type.heading, color = c.inkHi)
            Text(
                text = if (count > 0) "$count aluetta päällä" else "Kaikki pois",
                style = MkTheme.type.readout(11),
                color = if (count > 0) c.accent else c.inkLo,
            )
        }
        if (count > 0) {
            MkButton(
                text = "Kaikki pois",
                onClick = onAllOff,
                variant = MkButtonVariant.Secondary,
                size = MkButtonSize.Sm,
            )
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

/** Floor pager header: arrows, floor icon + name + on/total, page dots, per-floor off. */
@Composable
private fun FloorSelector(
    section: FloorSection,
    index: Int,
    count: Int,
    onSelect: (Int) -> Unit,
    onFloorOff: () -> Unit,
) {
    val c = MkTheme.colors
    val onCount = section.areas.count { it.isOn() }
    val total = section.areas.size
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MkIconButton(
                icon = MkIcons.CaretLeft,
                label = "Edellinen kerros",
                onClick = { onSelect((index - 1 + count) % count) },
                enabled = count > 1,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    androidx.compose.material3.Icon(floorIcon(section.floor), null, tint = c.inkMid, modifier = Modifier.size(18.dp))
                    Text(section.label, style = MkTheme.type.heading, color = c.inkHi, maxLines = 1)
                }
                Text(
                    text = "$onCount / $total päällä",
                    style = MkTheme.type.readout(11),
                    color = if (onCount > 0) c.accent else c.inkLo,
                )
            }
            MkIconButton(
                icon = MkIcons.CaretRight,
                label = "Seuraava kerros",
                onClick = { onSelect((index + 1) % count) },
                enabled = count > 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(count) { i ->
                Box(
                    modifier = Modifier
                        .height(6.dp)
                        .defaultMinSize(minWidth = if (i == index) 16.dp else 6.dp)
                        .clip(RoundedCornerShape(MkRadius.round))
                        .background(if (i == index) c.accent else c.track),
                )
            }
        }
        if (onCount > 0) {
            MkButton(
                text = "Sammuta",
                onClick = onFloorOff,
                variant = MkButtonVariant.Secondary,
                size = MkButtonSize.Sm,
            )
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
