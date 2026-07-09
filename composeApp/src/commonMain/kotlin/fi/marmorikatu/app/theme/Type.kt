package fi.marmorikatu.app.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import marmorikatu_mobile.composeapp.generated.resources.Res
import marmorikatu_mobile.composeapp.generated.resources.hankengrotesk_bold
import marmorikatu_mobile.composeapp.generated.resources.hankengrotesk_medium
import marmorikatu_mobile.composeapp.generated.resources.hankengrotesk_regular
import marmorikatu_mobile.composeapp.generated.resources.hankengrotesk_semibold
import marmorikatu_mobile.composeapp.generated.resources.ibmplexmono_medium
import marmorikatu_mobile.composeapp.generated.resources.ibmplexmono_regular
import marmorikatu_mobile.composeapp.generated.resources.ibmplexmono_semibold
import marmorikatu_mobile.composeapp.generated.resources.spacegrotesk_bold
import marmorikatu_mobile.composeapp.generated.resources.spacegrotesk_medium
import marmorikatu_mobile.composeapp.generated.resources.spacegrotesk_regular
import marmorikatu_mobile.composeapp.generated.resources.spacegrotesk_semibold
import org.jetbrains.compose.resources.Font

/**
 * Three families, per `tokens/typography.css`: Space Grotesk (display),
 * Hanken Grotesk (UI), IBM Plex Mono (readouts).
 *
 * Every measured value with a unit — temperatures, prices, ppm, clocks — is
 * mono with tabular figures so digits don't jitter as they tick.
 */
@Immutable
data class MkTypography(
    val display: FontFamily,
    val ui: FontFamily,
    val mono: FontFamily,
) {
    /** Hero headline, 38sp. */
    val displayLarge: TextStyle = TextStyle(
            fontFamily = display, fontWeight = FontWeight.SemiBold,
            fontSize = 38.sp, lineHeight = 40.sp, letterSpacing = (-0.02).em,
        )

    /** Screen titles — token `--text-xl` (24px). */
    val title: TextStyle = TextStyle(
        fontFamily = display, fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.02).em,
    )

    /** Card titles / section heads — token `--text-lg` (20px). */
    val heading: TextStyle = TextStyle(
        fontFamily = display, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 25.sp,
    )

    /** Body / default UI, 15sp. */
    val body: TextStyle = TextStyle(
            fontFamily = ui, fontWeight = FontWeight.Normal,
            fontSize = 15.sp, lineHeight = 22.sp,
        )

    /** Secondary labels, 13sp. */
    val label: TextStyle = TextStyle(
            fontFamily = ui, fontWeight = FontWeight.Medium,
            fontSize = 13.sp, lineHeight = 18.sp,
        )

    /** Captions / units, 11sp. */
    val caption: TextStyle = TextStyle(
            fontFamily = ui, fontWeight = FontWeight.Normal,
            fontSize = 11.sp, lineHeight = 15.sp,
        )

    /** UPPERCASE mono micro-label — the only place all-caps is allowed. */
    val kicker: TextStyle = TextStyle(
            fontFamily = mono, fontWeight = FontWeight.Medium,
            fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.2.em,
        )

    /** UPPERCASE mono tag text (KALLIS, KOHONNUT) — spec: 0.04em. */
    val tag: TextStyle = TextStyle(
        fontFamily = mono, fontWeight = FontWeight.Medium,
        fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.04.em,
    )

    private val readoutCache = mutableMapOf<Pair<Int, FontWeight>, TextStyle>()

    /**
     * Any measured value: mono, tabular, tight. Cached — readouts are built on
     * nearly every frame and a fresh TextStyle per call is pure allocation.
     */
    fun readout(size: Int, weight: FontWeight = FontWeight.Medium): TextStyle =
        readoutCache.getOrPut(size to weight) {
            TextStyle(
                fontFamily = mono, fontWeight = weight,
                fontSize = size.sp, lineHeight = (size * 1.1f).sp,
                letterSpacing = (-0.02).em, textAlign = TextAlign.Unspecified,
            )
        }
}

@Composable
fun rememberMkTypography(): MkTypography {
    val display = FontFamily(
        Font(Res.font.spacegrotesk_regular, FontWeight.Normal),
        Font(Res.font.spacegrotesk_medium, FontWeight.Medium),
        Font(Res.font.spacegrotesk_semibold, FontWeight.SemiBold),
        Font(Res.font.spacegrotesk_bold, FontWeight.Bold),
    )
    val ui = FontFamily(
        Font(Res.font.hankengrotesk_regular, FontWeight.Normal),
        Font(Res.font.hankengrotesk_medium, FontWeight.Medium),
        Font(Res.font.hankengrotesk_semibold, FontWeight.SemiBold),
        Font(Res.font.hankengrotesk_bold, FontWeight.Bold),
    )
    val mono = FontFamily(
        Font(Res.font.ibmplexmono_regular, FontWeight.Normal),
        Font(Res.font.ibmplexmono_medium, FontWeight.Medium),
        Font(Res.font.ibmplexmono_semibold, FontWeight.SemiBold),
    )
    // One instance per font set: MkTypography eagerly builds every TextStyle.
    return remember(display, ui, mono) { MkTypography(display, ui, mono) }
}
