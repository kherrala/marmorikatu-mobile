package fi.marmorikatu.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.marmorikatu.app.components.MkAssistantFaceMini
import fi.marmorikatu.app.components.MkButton
import fi.marmorikatu.app.components.MkButtonVariant
import fi.marmorikatu.app.components.MkSwitch
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.core.config.AssistantGender

/**
 * The user-facing preferences. Everything here persists across launches; the
 * developer diagnostics stay behind the long-press on the brand kicker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    viewModel: ShellViewModel,
    onDismiss: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val colors = MkTheme.colors
    val type = MkTheme.type
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // These are plain preference reads, not flows; mirror them into local state
    // so the switches animate immediately.
    var haptics by remember { mutableStateOf(viewModel.hapticsEnabled) }
    var background by remember { mutableStateOf(viewModel.backgroundEnabled) }
    var gender by remember { mutableStateOf(viewModel.assistantGender.value) }

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
            verticalArrangement = Arrangement.spacedBy(MkSpacing.x4),
        ) {
            Text("Asetukset", style = type.title, color = colors.inkHi)

            SettingRow(
                title = "Värinä tapahtumista",
                subtitle = "Hälytykset värisevät aina, tästä riippumatta.",
                checked = haptics,
                onChange = {
                    haptics = it
                    viewModel.setHapticsEnabled(it)
                },
            )

            if (viewModel.backgroundSupported) {
                SettingRow(
                    title = "Kuuntele taustalla",
                    subtitle = "Näyttää ilmoituksen tapahtumista, kun sovellus ei ole auki.",
                    checked = background,
                    onChange = {
                        background = it
                        viewModel.setBackgroundEnabled(it)
                    },
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Kuuntele taustalla", style = type.body, color = colors.inkLo)
                    Text(
                        "Ei tuettu tällä laitteella: iOS ei salli jatkuvaa yhteyttä " +
                            "taustalla ilman palvelimen push-viestejä.",
                        style = type.caption,
                        color = colors.inkLo,
                    )
                }
            }

            Text(
                "AVUSTAJAN HAHMO",
                style = type.readout(10),
                color = colors.inkLo,
                modifier = Modifier.padding(top = MkSpacing.x2),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2),
            ) {
                PersonaChip(
                    gender = AssistantGender.Nainen,
                    name = "Nainen",
                    voice = "naisääni",
                    selected = gender == AssistantGender.Nainen,
                    onClick = { gender = AssistantGender.Nainen; viewModel.setAssistantGender(AssistantGender.Nainen) },
                    modifier = Modifier.weight(1f),
                )
                PersonaChip(
                    gender = AssistantGender.Mies,
                    name = "Mies",
                    voice = "miesääni",
                    selected = gender == AssistantGender.Mies,
                    onClick = { gender = AssistantGender.Mies; viewModel.setAssistantGender(AssistantGender.Mies) },
                    modifier = Modifier.weight(1f),
                )
            }

            MkButton(
                text = "Siirry lapsen tilaan",
                onClick = {
                    viewModel.setSurface(Surface.Kid)
                    onDismiss()
                },
                variant = MkButtonVariant.Secondary,
                block = true,
            )

            MkButton(
                text = "Diagnostiikka",
                onClick = onOpenDiagnostics,
                variant = MkButtonVariant.Ghost,
                block = true,
            )
        }
    }
}

/** One persona option: a mini avatar face + name/voice, ringed when selected. */
@Composable
private fun PersonaChip(
    gender: AssistantGender,
    name: String,
    voice: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MkTheme.colors
    val type = MkTheme.type
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MkRadius.md))
            .background(colors.surfaceRaised)
            .border(
                1.5.dp,
                if (selected) colors.accent else colors.borderSubtle,
                RoundedCornerShape(MkRadius.md),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MkAssistantFaceMini(gender = gender, modifier = Modifier.size(38.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(name, style = type.body.copy(fontWeight = FontWeight.SemiBold), color = colors.inkHi)
            Text(voice, style = type.readout(10), color = colors.inkLo)
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val colors = MkTheme.colors
    val type = MkTheme.type
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = type.body, color = colors.inkHi)
            Text(subtitle, style = type.caption, color = colors.inkLo)
        }
        MkSwitch(checked = checked, onChange = onChange)
    }
}
