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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
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
                // Areas in a 2-column grid: pairs of compact cards side by side,
                // top-aligned so an expanded (taller) card grows in place while its
                // neighbour stays put (design).
                floor.areas.chunked(2).forEachIndexed { rowIdx, pair ->
                    item(key = "ar:${floor.floor.name}:$rowIdx") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            pair.forEach { area ->
                                Box(modifier = Modifier.weight(1f)) {
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
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            // Trailing space so tapping the last floor scrolls it to the very top
            // even when that floor has few areas (the list bottoms out otherwise).
            item(key = "tailspace") { Spacer(Modifier.fillParentMaxHeight(0.9f)) }
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
    // list; the top padding clears the header's -14dp content tuck + soft edge so
    // the pills aren't hidden under the chrome.
    Box(modifier = Modifier.fillMaxWidth().background(c.appBg).padding(start = MkSpacing.pagePad, end = MkSpacing.pagePad, top = MkSpacing.x5, bottom = 2.dp)) {
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

/**
 * A compact area card for the 2-column grid: an icon tile + on-count summary,
 * with the area name below. Tapping expands it in place — a caret appears and
 * the per-fixture toggle rows unfold beneath, the card growing taller within
 * its column (the house has no dimmer, so there are no brightness levels).
 */
@Composable
private fun AreaGroupCard(
    area: ValotArea,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleLight: (Int, Boolean) -> Unit,
) {
    val c = MkTheme.colors
    val shape = RoundedCornerShape(MkRadius.md)
    // How long the group has been on, ticking every second (00:05, 27:34, 1:07:12).
    var nowSec by remember { mutableStateOf(0L) }
    LaunchedEffect(area.onSinceSec) {
        if (area.onSinceSec == null) return@LaunchedEffect
        while (true) {
            nowSec = nowEpochSec()
            delay(1000)
        }
    }
    val onFor: Long? = area.onSinceSec?.let { (nowSec - it).coerceAtLeast(0L) }
    // Per-area hue only shows while a light is on: a solid hue tile + white glyph.
    // Off, the tile is neutral (grey) so the grid stays calm until something's lit.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surfaceCard)
            .border(1.dp, if (area.isOn) area.hue.copy(alpha = 0.5f) else c.borderSubtle, shape)
            .animateContentSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() }
                .padding(horizontal = 11.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(MkRadius.sm))
                        .background(if (area.isOn) area.hue else c.surfaceInset),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        area.icon, null,
                        tint = if (area.isOn) Color.White else c.inkLo,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (onFor != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.padding(end = 6.dp),
                    ) {
                        Icon(MkIcons.Clock, null, tint = c.inkLo, modifier = Modifier.size(11.dp))
                        Text(formatOnFor(onFor), style = MkTheme.type.readout(10), color = c.inkMid, maxLines = 1)
                    }
                }
                Text(
                    // Shortened to "2/2" when the timer is showing, so both fit.
                    text = when {
                        area.isOn && onFor != null -> "${area.onCount}/${area.total}"
                        area.isOn -> "${area.onCount}/${area.total} valoa"
                        else -> "Pois"
                    },
                    style = MkTheme.type.readout(11),
                    color = if (area.isOn) c.accent else c.inkLo,
                    maxLines = 1,
                )
                if (expanded) {
                    Spacer(Modifier.width(4.dp))
                    Icon(MkIcons.CaretUp, null, tint = c.inkLo, modifier = Modifier.size(13.dp))
                }
            }
            Text(
                text = area.name,
                style = MkTheme.type.body.copy(fontWeight = FontWeight.Medium),
                color = c.inkHi,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 9.dp, end = 9.dp, bottom = 9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
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

@OptIn(ExperimentalTime::class)
private fun nowEpochSec(): Long = Clock.System.now().epochSeconds

/** Clock-style on-time: 00:05 (5 s), 27:34 (min:sec), 1:07:12 (h:m:s). */
private fun formatOnFor(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    fun p(n: Long) = n.toString().padStart(2, '0')
    return if (h > 0) "$h:${p(m)}:${p(s)}" else "${p(m)}:${p(s)}"
}
