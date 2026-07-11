package fi.marmorikatu.app.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.app.theme.rememberSpin

/** Progress of one bootstrap step shown in the splash checklist. */
enum class SplashTaskState { Pending, Ok, Failed }

/** One line of the boot checklist. */
data class MkSplashTask(val label: String, val state: SplashTaskState)

/**
 * Cold-start brand moment shown while the shell brings the real house
 * connection up (MQTT over the VPN-as-LAN tunnel — see `ConnectionManager`).
 * Mirrors [MkNavRail]'s "M" brand tile at hero scale plus the header's mono
 * wordmark kicker; no bespoke artwork, same tokens as the rest of the app.
 *
 * This composable is pure chrome — it takes no dependency on the connection
 * itself. The caller (`App.kt`) drives [tasks] from the real bootstrap state
 * rather than a fixed string, and supplies [onDismiss] only once the device
 * has had a fair chance to connect: a device away from the home network/VPN
 * would otherwise retry forever behind a silent splash.
 */
@Composable
fun MkSplash(
    tasks: List<MkSplashTask>,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = MkTheme.colors
    val type = MkTheme.type
    val spin = rememberSpin(900)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.appBg),
        contentAlignment = Alignment.Center,
    ) {
        // The splash renders outside the app's kiosk density scaling, so it would
        // read tiny on the iPad; scale the whole hero up with the canvas (and a
        // touch on phones too).
        val scale = (maxWidth.value / 400f).coerceIn(1.1f, 1.9f)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MkSpacing.x6),
            modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        ) {
            // The rail's brand tile (Navigation.kt), scaled up for a hero moment.
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(RoundedCornerShape(MkRadius.xl))
                    .background(colors.accentDim)
                    .border(1.dp, colors.accentBorder, RoundedCornerShape(MkRadius.xl)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "M",
                    style = TextStyle(
                        fontFamily = type.display,
                        fontWeight = FontWeight.Bold,
                        fontSize = 48.sp,
                        color = colors.accent,
                    ),
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MkSpacing.x1),
            ) {
                // Same mono/uppercase/wide-tracking treatment as the phone
                // header's "Marmorikatu" kicker, just larger.
                Text(
                    text = "MARMORIKATU",
                    style = type.kicker.copy(fontSize = 13.sp, letterSpacing = 0.24.em),
                    color = colors.accent,
                )
                Text(
                    text = "Kodin automaatio",
                    style = type.caption,
                    color = colors.inkLo,
                    textAlign = TextAlign.Center,
                )
            }

            // Boot checklist — one line per real bootstrap step, each showing a
            // spinner (in progress), a check (done) or a warning (failed).
            Column(verticalArrangement = Arrangement.spacedBy(MkSpacing.x2)) {
                tasks.forEach { task ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (task.state) {
                            SplashTaskState.Pending -> Icon(
                                imageVector = MkIcons.CircleNotch,
                                contentDescription = null,
                                tint = colors.inkLo,
                                modifier = Modifier.size(16.dp).rotate(spin),
                            )
                            SplashTaskState.Ok -> Icon(
                                imageVector = MkIcons.Check,
                                contentDescription = null,
                                tint = colors.statusOk,
                                modifier = Modifier.size(16.dp),
                            )
                            SplashTaskState.Failed -> Icon(
                                imageVector = MkIcons.Warning,
                                contentDescription = null,
                                tint = colors.warm,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Text(
                            text = task.label,
                            style = type.readout(12),
                            color = if (task.state == SplashTaskState.Ok) colors.inkMid else colors.inkLo,
                        )
                    }
                }
            }

            AnimatedVisibility(visible = onDismiss != null) {
                MkButton(
                    text = "Jatka ilman yhteyttä",
                    onClick = { onDismiss?.invoke() },
                    variant = MkButtonVariant.Secondary,
                    size = MkButtonSize.Sm,
                )
            }
        }
    }
}
