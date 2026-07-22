package fi.marmorikatu.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.screens.KotiScene
import fi.marmorikatu.app.screens.ValotViewModel
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import org.koin.compose.viewmodel.koinViewModel

/**
 * The fold-in "Pikatoiminnot · Valot" sheet reached from the lightbulb FAB —
 * the house-wide light shortcuts that used to sit at the top of the Valot tab:
 * scene presets, per-floor off, and a global off. Shares [ValotViewModel] with
 * the Valot screen so the live on-counts and active preset stay in sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightsQuickSheet(
    onDismiss: () -> Unit,
    viewModel: ValotViewModel = koinViewModel(),
) {
    val colors = MkTheme.colors
    val type = MkTheme.type
    val state by viewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val areas = remember(state.floors) { state.floors.flatMap { it.areas } }
    val onNow = areas.sumOf { it.onCount }
    val total = areas.sumOf { it.total }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceCard,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MkSpacing.pagePad)
                .padding(bottom = MkSpacing.x8),
            verticalArrangement = Arrangement.spacedBy(MkSpacing.x3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PIKATOIMINNOT · VALOT", style = type.readout(10), color = colors.inkLo)
                Spacer(Modifier.weight(1f))
                Text("$onNow/$total päällä", style = type.readout(10), color = colors.inkMid)
            }

            // Scene presets (KaikkiPois is the dedicated all-off button below).
            val presets = remember { KotiScene.entries.filter { it != KotiScene.KaikkiPois } }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.chunked(2).forEach { rowScenes ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowScenes.forEach { scene ->
                            PresetChip(
                                scene = scene,
                                active = scene == state.activePreset,
                                onClick = { viewModel.applyPreset(scene) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowScenes.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            Text("SAMMUTA KERROS", style = type.readout(10), color = colors.inkLo)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                state.floors.forEach { floor ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(MkRadius.md))
                            .background(colors.surfaceRaised)
                            .border(1.dp, colors.borderSubtle, RoundedCornerShape(MkRadius.md))
                            .alpha(if (floor.areasOn > 0) 1f else 0.45f)
                            .clickable(enabled = floor.areasOn > 0) { viewModel.floorOff(floor.floor) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val floorOn = floor.areas.sumOf { it.onCount }
                        val floorTotal = floor.areas.sumOf { it.total }
                        Icon(floor.icon, null, tint = colors.inkMid, modifier = Modifier.size(16.dp))
                        Text(floor.name, style = type.label.copy(fontWeight = FontWeight.SemiBold), color = colors.inkHi, maxLines = 1)
                        Text("$floorOn/$floorTotal", style = type.readout(9), color = colors.inkLo, maxLines = 1)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .clip(RoundedCornerShape(MkRadius.md))
                    .background(colors.warmDim)
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(MkRadius.md))
                    .clickable { viewModel.allOff() }
                    .padding(vertical = 11.dp, horizontal = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(MkIcons.Power, null, tint = colors.warm, modifier = Modifier.size(17.dp))
                Text(
                    "Sammuta kaikki valot",
                    style = type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.inkHi,
                )
            }
        }
    }
}

/** Per-preset icon accent (from the design): each scene reads at a glance. */
private fun sceneIconColor(scene: KotiScene): Color = when (scene) {
    KotiScene.Aamuvalot -> Color(0xFFDE9A47)   // sunrise orange
    KotiScene.Iltavalot -> Color(0xFF7C8CD6)   // evening periwinkle
    KotiScene.Elokuva -> Color(0xFFA779CE)     // cinema violet
    KotiScene.Terassi -> Color(0xFF57A97B)     // terrace green
    KotiScene.Kotiinpaluu -> Color(0xFF34C0A4) // homecoming teal
    KotiScene.Autokatos -> Color(0xFF97A2B0)   // carport blue-grey
    KotiScene.KaikkiPois -> Color(0xFF97A2B0)
}

/** One scene preset: horizontal icon + label, accent-filled when active. */
@Composable
private fun PresetChip(
    scene: KotiScene,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MkTheme.colors
    val shape = RoundedCornerShape(MkRadius.md)
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (active) c.accent else c.surfaceRaised)
            .border(1.dp, if (active) c.accent else c.borderSubtle, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            scene.icon,
            null,
            tint = if (active) c.inkOnAccent else sceneIconColor(scene),
            modifier = Modifier.size(19.dp),
        )
        Text(
            scene.label,
            style = MkTheme.type.body.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = if (active) c.inkOnAccent else c.inkHi,
            maxLines = 2,
            textAlign = TextAlign.Start,
        )
    }
}
