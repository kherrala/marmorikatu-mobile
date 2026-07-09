package fi.marmorikatu.app.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** 4px base grid, per `tokens/spacing.css`. */
@Immutable
object MkSpacing {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x5 = 20.dp
    val x6 = 24.dp
    val x8 = 32.dp

    /** Phone screen horizontal padding. */
    val pagePad = 18.dp
    val pagePadTablet = 24.dp
    val stackGap = 12.dp
    val railWidth = 84.dp
    val tabBarHeight = 64.dp

    val touchMin = 44.dp
    val touchComfort = 52.dp
    val touchKid = 64.dp
    val iconButton = 38.dp
    val iconButtonLg = 52.dp
}

/** Soft, rounded — never pill-everything. */
@Immutable
object MkRadius {
    val xs = 6.dp
    val sm = 10.dp
    val md = 14.dp
    val lg = 18.dp
    val xl = 22.dp
    val xxl = 28.dp
    val round = 999.dp
}

/** Calm and quick; gentle overshoot for toggles, no bounce on utilities. */
@Immutable
object MkMotion {
    const val INSTANT = 90
    const val FAST = 150
    const val MED = 260
    const val SLOW = 420

    val standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val easeOut = CubicBezierEasing(0.16f, 0.84f, 0.3f, 1f)
    val emphasis = CubicBezierEasing(0.34f, 1.4f, 0.5f, 1f)

    /** Press feedback: scale to 0.96. */
    const val PRESS_SCALE = 0.96f

    fun <T> fast() = tween<T>(FAST, easing = standard)
    fun <T> med() = tween<T>(MED, easing = standard)
    fun <T> overshoot() = tween<T>(MED, easing = emphasis)
}

val LocalMkColors: ProvidableCompositionLocal<MkColors> = staticCompositionLocalOf { MkDarkColors }
val LocalMkTypography: ProvidableCompositionLocal<MkTypography> =
    staticCompositionLocalOf { error("MkTypography not provided") }

object MkTheme {
    val colors: MkColors
        @Composable @ReadOnlyComposable get() = LocalMkColors.current
    val type: MkTypography
        @Composable @ReadOnlyComposable get() = LocalMkTypography.current
}

/**
 * Dark is the primary theme; light is the daylight variant. Material 3 is kept
 * underneath only so its components (ripples, text fields) inherit sane
 * colours — all app chrome reads from [MkTheme].
 */
@Composable
fun MarmorikatuTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) MkDarkColors else MkLightColors
    val typography = rememberMkTypography()

    val material = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.inkOnAccent,
            background = colors.appBg,
            onBackground = colors.inkHi,
            surface = colors.surfaceCard,
            onSurface = colors.inkHi,
            error = colors.statusAlarm,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.inkOnAccent,
            background = colors.appBg,
            onBackground = colors.inkHi,
            surface = colors.surfaceCard,
            onSurface = colors.inkHi,
            error = colors.statusAlarm,
        )
    }

    CompositionLocalProvider(
        LocalMkColors provides colors,
        LocalMkTypography provides typography,
    ) {
        MaterialTheme(colorScheme = material) {
            CompositionLocalProvider(
                LocalTextStyle provides typography.body.copy(color = colors.inkMid),
                content = content,
            )
        }
    }
}
