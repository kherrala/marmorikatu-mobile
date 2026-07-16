package fi.marmorikatu.app.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fi.marmorikatu.app.components.MkBanner
import fi.marmorikatu.app.components.MkPullToRefresh
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.core.model.Floor
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * Valot: the whole house's lighting, matching the Claude design — one-tap scene
 * presets on top, a sticky floor-tab bar that scroll-anchors to each floor, and
 * every floor stacked as a section of collapsible area cards. Tapping a card
 * expands it to per-fixture toggles.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ValotScreen(
    modifier: Modifier = Modifier,
    viewModel: ValotViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val failure by viewModel.failure.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val colors = MkTheme.colors

    LaunchedEffect(Unit) { viewModel.refresh() }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(setOf<String>()) }

    // Item index of each floor header, so the fixed tab bar can scroll to it. The
    // bar is now a fixed bar above the list, so the list is presets(0), then per
    // floor: header + one item per area (floors start at index 1).
    val floors = state.floors
    val headerIndex = remember(floors) {
        val map = LinkedHashMap<Floor, Int>()
        var idx = 1
        floors.forEach { f -> map[f.floor] = idx; idx += 1 + f.areas.size }
        map
    }
    val activeFloor by remember(floors) {
        derivedStateOf {
            // +1 so a floor counts as "current" once its header reaches the top,
            // even while the previous floor's last card still peeks below it.
            val i = (listState.firstVisibleItemIndex + 1).coerceAtLeast(1)
            floors.lastOrNull { (headerIndex[it.floor] ?: Int.MAX_VALUE) <= i }?.floor
                ?: floors.firstOrNull()?.floor
        }
    }

    MkPullToRefresh(refreshing = refreshing, onRefresh = viewModel::refresh) {
        Column(modifier = modifier.fillMaxSize().background(colors.appBg)) {
            // Fixed floor tabs below the app header — they stay put while the list
            // scrolls beneath them (as a stickyHeader they slid up under the header's
            // soft edge, since the content tucks up by -14dp). Tapping jumps to a floor.
            FloorTabs(
                floors = floors,
                active = activeFloor,
                onSelect = { floor ->
                    headerIndex[floor]?.let { idx ->
                        scope.launch { listState.animateScrollToItem(idx) }
                    }
                },
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(
                    start = MkSpacing.pagePad,
                    end = MkSpacing.pagePad,
                    top = MkSpacing.x2,
                    bottom = MkSpacing.x4 + MkSpacing.scrollBottomGap,
                ),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                item(key = "presets") {
                    PresetRow(active = state.activePreset, onApply = viewModel::applyPreset)
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
            } else if (floors.isEmpty()) {
                item(key = "empty") { CenteredNote("Ei tietoa valoista") }
            }

            floors.forEach { floor ->
                item(key = "fh:${floor.floor.name}") {
                    FloorHeader(floor = floor, onFloorOff = { viewModel.floorOff(floor.floor) })
                }
                items(floor.areas, key = { "a:${it.key}" }) { area ->
                    AreaGroupCard(
                        area = area,
                        expanded = area.key in expanded,
                        onToggleExpand = {
                            expanded = if (area.key in expanded) expanded - area.key else expanded + area.key
                        },
                        onToggleLight = { id, on -> viewModel.toggle(id, on) },
                    )
                }
            }
            }
        }
    }
}

/** The scene presets, four to a row (Aamuvalot / Iltavalot / Elokuva / Terassi / Kotiinpaluu / Kaikki pois). */
@Composable
private fun PresetRow(active: KotiScene?, onApply: (KotiScene) -> Unit) {
    val c = MkTheme.colors
    val shape = RoundedCornerShape(MkRadius.md)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Four columns per row (design grid); a short last row is padded so the
        // remaining chips keep their quarter-width rather than stretching.
        KotiScene.entries.chunked(4).forEach { rowScenes ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowScenes.forEach { s ->
                    val on = s == active
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(84.dp)
                            .clip(shape)
                            .background(if (on) c.accent else c.surfaceCard)
                            .border(1.dp, if (on) c.accent else c.borderSubtle, shape)
                            .clickable { onApply(s) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(s.icon, null, tint = if (on) c.inkOnAccent else c.inkMid, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(7.dp))
                        Text(
                            text = s.label,
                            style = MkTheme.type.label.copy(fontWeight = FontWeight.SemiBold),
                            color = if (on) c.inkOnAccent else c.inkMid,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                repeat(4 - rowScenes.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Sticky floor tabs that scroll-anchor to each floor section. */
@Composable
private fun FloorTabs(
    floors: List<ValotFloor>,
    active: Floor?,
    onSelect: (Floor) -> Unit,
) {
    val c = MkTheme.colors
    // A solid strip so the list doesn't show through as it scrolls beneath it.
    // Horizontal page padding is applied here now that the bar sits outside the
    // list (it used to inherit the LazyColumn's contentPadding as a sticky header).
    Box(modifier = Modifier.fillMaxWidth().background(c.appBg).padding(horizontal = MkSpacing.pagePad, vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            floors.forEach { floor ->
                val on = floor.floor == active
                val shape = RoundedCornerShape(MkRadius.round)
                Row(
                    modifier = Modifier
                        .clip(shape)
                        .background(if (on) c.accentDim else c.surfaceCard)
                        .border(1.dp, if (on) c.accent else c.borderSubtle, shape)
                        .clickable { onSelect(floor.floor) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = floor.name,
                        style = MkTheme.type.label.copy(fontWeight = FontWeight.SemiBold),
                        color = if (on) c.accent else c.inkMid,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A floor section heading: icon + name + on-count, with a "Sammuta" action. */
@Composable
private fun FloorHeader(floor: ValotFloor, onFloorOff: () -> Unit) {
    val c = MkTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(floor.icon, null, tint = c.inkMid, modifier = Modifier.size(18.dp))
        Text(floor.name, style = MkTheme.type.heading, color = c.inkHi)
        Text(
            text = "${floor.areasOn} päällä",
            style = MkTheme.type.readout(11),
            color = if (floor.areasOn > 0) c.accent else c.inkLo,
            modifier = Modifier.weight(1f),
        )
        if (floor.areasOn > 0) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(MkRadius.sm))
                    .clickable { onFloorOff() }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(MkIcons.Power, null, tint = c.inkLo, modifier = Modifier.size(15.dp))
                Text("Sammuta", style = MkTheme.type.readout(12), color = c.inkLo)
            }
        }
    }
}

/** A collapsible area card: header (icon + name + count + chevron), expands to per-fixture rows. */
@Composable
private fun AreaGroupCard(
    area: ValotArea,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleLight: (Int, Boolean) -> Unit,
) {
    val c = MkTheme.colors
    val shape = RoundedCornerShape(MkRadius.lg)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surfaceCard)
            .border(1.dp, c.borderSubtle, shape)
            .animateContentSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onToggleExpand() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(MkRadius.sm))
                    .background(if (area.isOn) c.accent else c.surfaceInset),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    area.icon, null,
                    tint = if (area.isOn) c.inkOnAccent else c.inkLo,
                    modifier = Modifier.size(19.dp),
                )
            }
            Text(
                text = area.name,
                style = MkTheme.type.heading,
                color = c.inkHi,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (area.isOn) "${area.onCount}/${area.total} valoa" else "Pois",
                style = MkTheme.type.readout(13),
                color = if (area.isOn) c.accent else c.inkLo,
            )
            Icon(
                if (expanded) MkIcons.CaretUp else MkIcons.CaretDown,
                null,
                tint = c.inkLo,
                modifier = Modifier.size(16.dp),
            )
        }

        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                area.lights.forEach { light -> LightToggleRow(light, onToggleLight) }
            }
        }
    }
}

/** One fixture row: state dot + label + a lightbulb toggle, tinted when on. */
@Composable
private fun LightToggleRow(light: ValotLight, onToggle: (Int, Boolean) -> Unit) {
    val c = MkTheme.colors
    val shape = RoundedCornerShape(MkRadius.sm)
    val name = if (light.pending) "${light.label}…" else light.label
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (light.on) c.accentDim else c.surfaceInset)
            .border(1.dp, if (light.on) c.accent else c.borderSubtle, shape)
            .clickable { onToggle(light.id, !light.on) }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (light.on) c.accent else c.inkLo),
        )
        Text(
            text = name,
            style = MkTheme.type.body,
            color = if (light.on) c.inkHi else c.inkLo,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (light.on) MkIcons.LightbulbFill else MkIcons.Lightbulb,
            null,
            tint = if (light.on) c.accent else c.inkLo,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = MkTheme.type.body.copy(fontWeight = FontWeight.Medium), color = MkTheme.colors.inkLo)
    }
}
