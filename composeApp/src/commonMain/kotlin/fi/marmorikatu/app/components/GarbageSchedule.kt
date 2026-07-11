package fi.marmorikatu.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme

/**
 * One upcoming waste pickup. Kept UI-free (no colours / icons) so the view model
 * that produces the schedule stays free of Compose types; the glyph and pill
 * tinting are derived here at render time.
 */
data class GarbagePickup(
    val type: String,
    /** Weekday + date, already formatted for display, e.g. `to 19.2.`. */
    val dateLabel: String,
    /** Countdown phrase, e.g. `huomenna` / `6 pv`. */
    val daysLabel: String,
    /** Drives the warm-tinted "soon" pill on the next pickup or two. */
    val soon: Boolean = false,
)

/**
 * The waste collector's brand line. A static label in the design — there is no
 * backend field for it — so it lives with the component.
 */
private const val PROVIDER = "Pirkanmaan Jätehuolto"

/**
 * Per-waste-type Phosphor glyphs, matching the design. Metalli/Lasi have no
 * dedicated glyph in MkIcons yet, so they keep the recycle mark.
 */
private fun garbageIcon(type: String): ImageVector = when {
    type.contains("bio", ignoreCase = true) -> MkIcons.Leaf
    type.contains("seka", ignoreCase = true) -> MkIcons.Trash
    type.contains("kartonki", ignoreCase = true) || type.contains("pahvi", ignoreCase = true) -> MkIcons.Package
    type.contains("paperi", ignoreCase = true) -> MkIcons.Newspaper
    type.contains("vaarallinen", ignoreCase = true) -> MkIcons.Warning
    else -> MkIcons.ArrowsClockwise // muovi, metalli, lasi
}

// ── Full schedule ───────────────────────────────────────────────────────────

/**
 * View mode 1 — the full "Roskienkeräys" schedule: every upcoming pickup as a
 * row of type · date · countdown pill, inside a titled card.
 */
@Composable
fun GarbageScheduleCard(
    pickups: List<GarbagePickup>,
    modifier: Modifier = Modifier,
) {
    val c = MkTheme.colors
    MkCard(modifier = modifier, interactive = false) {
        MkCardHead(
            title = "Roskienkeräys",
            action = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MkIcons.ArrowsClockwise, null, tint = c.inkLo, modifier = Modifier.size(13.dp))
                    Text(PROVIDER, style = MkTheme.type.readout(10, FontWeight.Normal), color = c.inkLo, maxLines = 1)
                }
            },
        )
        if (pickups.isEmpty()) {
            Text("Ei tulevia keräyksiä", style = MkTheme.type.body, color = c.inkLo)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(MkSpacing.x2)) {
                pickups.forEach { g -> GarbageRow(g) }
            }
        }
    }
}

@Composable
private fun GarbageRow(g: GarbagePickup) {
    val c = MkTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MkRadius.md))
            .background(c.surfaceInset)
            .padding(horizontal = MkSpacing.x3, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(MkRadius.sm))
                .background(c.accentDim),
        ) {
            Icon(garbageIcon(g.type), null, tint = c.accent, modifier = Modifier.size(16.dp))
        }
        Text(
            text = g.type,
            style = MkTheme.type.body.copy(fontWeight = FontWeight.Medium),
            color = c.inkHi,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(g.dateLabel, style = MkTheme.type.readout(12), color = c.inkMid, maxLines = 1)
        GarbageDaysPill(g)
    }
}

/** The countdown pill: warm-tinted when a pickup is imminent, quiet otherwise. */
@Composable
private fun GarbageDaysPill(g: GarbagePickup) {
    val c = MkTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(MkRadius.round))
            .background(if (g.soon) c.warmDim else c.track)
            .padding(horizontal = MkSpacing.x2, vertical = 3.dp),
    ) {
        Text(
            text = g.daysLabel,
            style = MkTheme.type.readout(11, FontWeight.Normal),
            color = if (g.soon) c.warm else c.inkMid,
            maxLines = 1,
        )
    }
}

// ── Compact next pickup ─────────────────────────────────────────────────────

/**
 * View mode 2 — a compact "next pickup" card for the home screen: the single
 * soonest collection with its countdown. Tapping it opens the calendar.
 */
@Composable
fun GarbageNextCard(
    next: GarbagePickup,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val c = MkTheme.colors
    MkCard(
        modifier = modifier,
        status = if (next.soon) MkCardStatus.Warn else MkCardStatus.None,
        interactive = onClick != null,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(MkRadius.md))
                    .background(c.accentDim),
            ) {
                Icon(garbageIcon(next.type), null, tint = c.accent, modifier = Modifier.size(19.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Seuraava roskienkeräys".uppercase(), style = MkTheme.type.kicker, color = c.inkLo, maxLines = 1)
                Row(
                    modifier = Modifier.padding(top = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = next.type,
                        style = MkTheme.type.body.copy(fontWeight = FontWeight.SemiBold),
                        color = c.inkHi,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(next.dateLabel, style = MkTheme.type.readout(11, FontWeight.Normal), color = c.inkLo, maxLines = 1)
                }
            }
            GarbageDaysPill(next)
        }
    }
}
