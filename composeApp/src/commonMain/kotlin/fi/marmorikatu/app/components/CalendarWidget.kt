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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkTheme

/**
 * The design's compact "Kalenteri · jäte" summary — the family calendar's next
 * event on the left, the next waste pickup on the right, in one tappable row.
 *
 * It lives on the home screen (tapping it opens the calendar detail view) and,
 * per the design, heads that detail view too: the very thing you tapped stays
 * in view as you arrive, so the summary and its expansion read as one place.
 */
@Composable
fun CalendarNextCard(
    eventTime: String?,
    eventTitle: String?,
    garbage: GarbagePickup?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val c = MkTheme.colors
    MkCard(
        modifier = modifier,
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
                // MkIcons has no calendar glyph yet; Clock stands in, as on the tab bar.
                Icon(MkIcons.Clock, null, tint = c.accent, modifier = Modifier.size(19.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Kalenteri · jäte".uppercase(),
                    style = MkTheme.type.kicker,
                    color = c.inkLo,
                    maxLines = 1,
                )
                // "HH:MM Title" — either half can be blank (an all-day event, or no
                // upcoming event at all), so join the present pieces and fall back.
                val headline = listOfNotNull(
                    eventTime?.takeIf { it.isNotBlank() },
                    eventTitle?.takeIf { it.isNotBlank() },
                ).joinToString(" ").ifBlank { "Ei tulevia tapahtumia" }
                // Marquee-scrolled so a long event title/location reads in full.
                MarqueeText(
                    text = headline,
                    style = MkTheme.type.heading.copy(fontSize = 14.sp),
                    color = c.inkHi,
                    modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
                )
            }
            if (garbage != null) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(MkIcons.Trash, null, tint = c.warm, modifier = Modifier.size(12.dp))
                        Text(
                            text = garbage.type,
                            style = MkTheme.type.readout(10, FontWeight.Normal),
                            color = c.warm,
                            maxLines = 1,
                        )
                    }
                    Text(
                        text = garbage.daysLabel,
                        style = MkTheme.type.readout(10, FontWeight.Normal),
                        color = c.inkLo,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
