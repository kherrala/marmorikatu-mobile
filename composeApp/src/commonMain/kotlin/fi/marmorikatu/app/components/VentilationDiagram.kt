package fi.marmorikatu.app.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.app.theme.MkTheme

/** One of the four duct-temperature corners of [MkVentilationDiagram]. */
enum class VentZone { Outdoor, Supply, Extract, Exhaust }

/**
 * The MVHR (heat-recovery ventilation) system diagram from the design: four
 * labelled air ducts around a central heat-exchanger (LTO) hexagon and the
 * supply-air post-heater. Geometry mirrors the design's 360×168 SVG; the
 * temperatures and efficiency are live. With [onZoneClick] set, tapping a
 * duct corner reports its [VentZone] (the caller opens its history chart);
 * the centre stays inert so the LTO hexagon isn't a hidden tap target.
 */
@Composable
fun MkVentilationDiagram(
    outdoorC: Double?,
    exhaustC: Double?,
    extractC: Double?,
    supplyC: Double?,
    preHeatC: Double?,
    ltoPct: Double?,
    modifier: Modifier = Modifier,
    onZoneClick: ((VentZone) -> Unit)? = null,
) {
    val c = MkTheme.colors
    val measurer = rememberTextMeasurer()
    val mono = MkTheme.type.mono
    fun t(v: Double?) = v?.let { "${Fmt.oneDecimal(it)}°" } ?: "–"

    Box(modifier = modifier.fillMaxWidth().aspectRatio(360f / 168f)) {
    Canvas(Modifier.fillMaxSize()) {
        val sx = size.width / 360f
        val sy = size.height / 168f
        fun o(x: Float, y: Float) = Offset(x * sx, y * sy)
        val strokeW = 2f * sx

        // Ducts.
        listOf(
            o(46f, 122f) to o(150f, 122f),
            o(210f, 122f) to o(244f, 122f),
            o(274f, 122f) to o(344f, 122f),
            o(314f, 48f) to o(210f, 48f),
            o(150f, 48f) to o(16f, 48f),
        ).forEach { (a, b) -> drawLine(c.inkLo, a, b, strokeWidth = strokeW, cap = StrokeCap.Round) }

        // Arrowheads (direction of flow).
        fun tri(a: Offset, b: Offset, tip: Offset) {
            drawPath(Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(tip.x, tip.y); close() }, c.inkMid)
        }
        tri(o(148f, 116f), o(148f, 128f), o(158f, 122f)) // outdoor → LTO
        tri(o(344f, 116f), o(344f, 128f), o(354f, 122f)) // supply → house
        tri(o(212f, 42f), o(212f, 54f), o(202f, 48f))    // extract → LTO
        tri(o(18f, 42f), o(18f, 54f), o(8f, 48f))        // exhaust → out

        // Heat-exchanger hexagon.
        val hex = Path().apply {
            moveTo(o(150f, 48f).x, o(150f, 48f).y); lineTo(o(180f, 34f).x, o(180f, 34f).y)
            lineTo(o(210f, 48f).x, o(210f, 48f).y); lineTo(o(210f, 122f).x, o(210f, 122f).y)
            lineTo(o(180f, 136f).x, o(180f, 136f).y); lineTo(o(150f, 122f).x, o(150f, 122f).y); close()
        }
        drawPath(hex, c.surfaceInset)
        drawPath(hex, c.accent, style = Stroke(width = 1.5f * sx))

        // Supply-air post-heater.
        drawPath(
            Path().apply {
                val l = o(244f, 106f); val r = o(274f, 138f)
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        l.x, l.y, r.x, r.y,
                        androidx.compose.ui.geometry.CornerRadius(4f * sx, 4f * sx),
                    )
                )
            },
            c.accentDim,
        )

        // Labels + readouts (mono, centred at the design's anchor points).
        fun label(text: String, x: Float, y: Float, sizeSp: Float, color: Color, weight: FontWeight = FontWeight.Normal) {
            val res = measurer.measure(text, TextStyle(fontFamily = mono, fontSize = sizeSp.sp, fontWeight = weight, color = color))
            drawText(res, color = color, topLeft = Offset(o(x, y).x - res.size.width / 2f, o(x, y).y - res.size.height / 2f))
        }
        label("LTO", 180f, 78f, 14f, c.inkHi, FontWeight.SemiBold)
        ltoPct?.let { label("${Fmt.int(it)}%", 180f, 98f, 12f, c.accent) }
        if (preHeatC != null && supplyC != null) label("${Fmt.oneDecimal(preHeatC)}→${Fmt.oneDecimal(supplyC)}°", 259f, 92f, 9f, c.inkMid)
        label(t(outdoorC), 30f, 110f, 12f, c.inkHi)   // Ulkoilma
        label(t(supplyC), 330f, 110f, 12f, c.accent)  // Tuloilma
        label(t(extractC), 330f, 72f, 12f, c.inkHi)   // Poistoilma
        label(t(exhaustC), 30f, 72f, 12f, c.inkHi)    // Jäteilma
        label("ULKOILMA", 34f, 150f, 8f, c.inkLo)
        label("TULOILMA", 326f, 150f, 8f, c.inkLo)
        label("POISTOILMA", 322f, 26f, 8f, c.inkLo)
        label("JÄTEILMA", 34f, 26f, 8f, c.inkLo)
    }

    // Invisible corner tap targets over the four duct readouts. The middle
    // column (the LTO hexagon) is left free of targets on purpose.
    if (onZoneClick != null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(0.36f).fillMaxHeight().clickable { onZoneClick(VentZone.Exhaust) })
                Spacer(Modifier.weight(0.28f))
                Box(Modifier.weight(0.36f).fillMaxHeight().clickable { onZoneClick(VentZone.Extract) })
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(0.36f).fillMaxHeight().clickable { onZoneClick(VentZone.Outdoor) })
                Spacer(Modifier.weight(0.28f))
                Box(Modifier.weight(0.36f).fillMaxHeight().clickable { onZoneClick(VentZone.Supply) })
            }
        }
    }
    }
}
