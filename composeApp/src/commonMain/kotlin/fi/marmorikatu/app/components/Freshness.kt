package fi.marmorikatu.app.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkMotion
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.app.theme.mkGlow
import fi.marmorikatu.app.theme.rememberBreathe
import fi.marmorikatu.app.theme.rememberSpin

/**
 * How fresh a screen's data is: a live dot that flashes whenever new data
 * lands, the age of that data in plain Finnish, and a spinner while a refresh
 * is in flight.
 *
 * @param updatedAtEpochSeconds when the data last changed; null while loading.
 * @param refreshing true while a fetch is running.
 */
@Composable
fun MkFreshness(
    updatedAtEpochSeconds: Long?,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
    onRefresh: (() -> Unit)? = null,
) {
    val colors = MkTheme.colors
    val type = MkTheme.type

    // A short pulse each time the timestamp advances — the "new data" flash.
    val flash = remember { Animatable(1f) }
    LaunchedEffect(updatedAtEpochSeconds) {
        if (updatedAtEpochSeconds != null) {
            flash.snapTo(2.2f)
            flash.animateTo(1f, tween(MkMotion.SLOW, easing = MkMotion.easeOut))
        }
    }

    // Re-read the clock every second so "12 s sitten" actually ticks.
    val age = rememberTickingAge(updatedAtEpochSeconds)

    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = MkSpacing.x1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MkSpacing.x2),
    ) {
        // A static dot at rest — the flash on new data carries the signal.
        // A perpetual breathe here would redraw the whole tree every frame
        // (120fps) on every screen, which is wasteful and floods logcat with
        // Android 15's per-frame setRequestedFrameRate hints.
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(flash.value)
                .then(if (flash.value > 1.05f) Modifier.mkGlow(colors.accent, 6.dp) else Modifier)
                .background(
                    color = if (updatedAtEpochSeconds == null) colors.inkLo else colors.accent,
                    shape = CircleShape,
                )
        )

        Text(
            text = when {
                refreshing -> "Päivitetään…"
                age == null -> "Ladataan…"
                else -> "Päivitetty $age"
            },
            style = type.readout(11),
            color = colors.inkLo,
        )

        Box(modifier = Modifier.weight(1f))

        if (onRefresh != null) {
            val spin = if (refreshing) rememberSpin() else 0f
            MkIconButton(
                icon = MkIcons.ArrowsClockwise,
                label = "Päivitä",
                onClick = onRefresh,
                enabled = !refreshing,
                modifier = Modifier.rotate(spin),
            )
        }
    }
}

/** Recomputes the freshness phrase while composed, updating only when it changes. */
@Composable
private fun rememberTickingAge(updatedAtEpochSeconds: Long?): String? {
    if (updatedAtEpochSeconds == null) return null
    val age = remember(updatedAtEpochSeconds) {
        mutableStateOf(Fmt.freshness(updatedAtEpochSeconds.toDouble()))
    }
    LaunchedEffect(updatedAtEpochSeconds) {
        while (true) {
            delay(1_000)
            // Only a *changed* state value invalidates composition. Fmt.freshness
            // reads "juuri nyt" for the whole first minute, so a live feed that
            // refreshes every ~13 s never trips this — the screen stops redrawing
            // (and stops flooding logcat with Android 15's per-frame
            // setRequestedFrameRate hints) when nothing visibly changed.
            val next = Fmt.freshness(updatedAtEpochSeconds.toDouble())
            if (next != age.value) age.value = next
        }
    }
    return age.value
}

/**
 * Standard pull-to-refresh wrapper. Every screen that can re-fetch should use
 * it, so the gesture means the same thing everywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MkPullToRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = modifier,
        indicator = {
            androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                state = state,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = MkTheme.colors.surfaceCard,
                color = MkTheme.colors.accent,
            )
        },
    ) {
        content()
    }
}
