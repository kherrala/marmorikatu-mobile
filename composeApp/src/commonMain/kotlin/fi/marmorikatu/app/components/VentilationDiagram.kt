package fi.marmorikatu.app.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import fi.marmorikatu.app.theme.MkTheme

/** Cooling accent (geothermal cold) — a literal blue, distinct from the teal accent. */
val MkCoolBlue = Color(0xFF5CC3EA)

/**
 * The MVHR system schematic (design): supply air travels ULKO → LTO recovery →
 * heating coil → cooling coil → KOTI, with the extract air giving up its heat in
 * the LTO on the way out. [selected] (ulko/tulo/poisto/jate/lto/coilH/coilC) draws
 * a highlight ring around that stage.
 *
 * Drawn in two layers for cheap animation: a static Canvas (ducts, core, coils,
 * labels, fan bodies) that repaints only when [selected] changes, and a thin
 * overlay Canvas that repaints per frame with just the marching airflow and the
 * rotating fan blades.
 */
@Composable
fun MkVentilationDiagram(
    modifier: Modifier = Modifier,
    selected: String? = null,
) {
    val c = MkTheme.colors
    val measurer = rememberTextMeasurer()
    val mono = MkTheme.type.mono

    val anim = rememberInfiniteTransition(label = "vent")
    val flow by anim.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1350, easing = LinearEasing), RepeatMode.Restart), label = "flow",
    )
    val spin by anim.animateFloat(
        0f, 360f, infiniteRepeatable(tween(2300, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    )

    Box(modifier = modifier.fillMaxWidth().aspectRatio(360f / 164f)) {
        // ── Static layer: everything that doesn't move (repaints on selection). ──
        Canvas(Modifier.fillMaxSize()) {
            val sx = size.width / 360f
            val sy = size.height / 164f
            fun o(x: Float, y: Float) = Offset(x * sx, y * sy)
            fun on(k: String) = selected == k

            drawRect(c.warm.copy(alpha = 0.05f), o(0f, 0f), Size(116f * sx, 164f * sy))
            drawRect(c.accent.copy(alpha = 0.05f), o(244f, 0f), Size(116f * sx, 164f * sy))

            // Duct base lanes.
            listOf(
                o(26f, 48f) to o(143f, 48f), o(26f, 116f) to o(143f, 116f),
                o(217f, 48f) to o(334f, 48f), o(217f, 116f) to o(334f, 116f),
            ).forEach { (a, b) -> drawLine(c.surfaceInset, a, b, strokeWidth = 12f * sy, cap = StrokeCap.Round) }

            // Flow arrowheads.
            fun tri(pts: List<Offset>, color: Color) =
                drawPath(Path().apply { moveTo(pts[0].x, pts[0].y); lineTo(pts[1].x, pts[1].y); lineTo(pts[2].x, pts[2].y); close() }, color)
            tri(listOf(o(108f, 109f), o(108f, 123f), o(120f, 116f)), c.accent)
            tri(listOf(o(66f, 41f), o(66f, 55f), o(54f, 48f)), c.accent)
            tri(listOf(o(248f, 109f), o(248f, 123f), o(236f, 116f)), c.warm)

            // Duct end dots + selection rings.
            data class Node(val x: Float, val y: Float, val color: Color, val key: String)
            listOf(
                Node(26f, 48f, c.accent, "jate"), Node(26f, 116f, c.accent, "ulko"),
                Node(334f, 48f, c.accent, "tulo"), Node(334f, 116f, c.warm, "poisto"),
            ).forEach { n ->
                drawCircle(n.color, radius = 4.5f * sx, center = o(n.x, n.y))
                if (on(n.key)) drawCircle(c.accent, radius = 9f * sx, center = o(n.x, n.y), style = Stroke(width = 1.6f * sx))
            }

            // LTO recovery core.
            if (on("lto")) {
                drawPath(
                    Path().apply { addRoundRect(RoundRect(o(138f, 29f).x, o(138f, 29f).y, o(222f, 135f).x, o(222f, 135f).y, CornerRadius(17f * sx, 17f * sy))) },
                    c.accent, style = Stroke(width = 1.4f * sx),
                )
            }
            val core = RoundRect(o(143f, 34f).x, o(143f, 34f).y, o(217f, 130f).x, o(217f, 130f).y, CornerRadius(14f * sx, 14f * sy))
            drawPath(Path().apply { addRoundRect(core) }, c.surfaceCard)
            // Faint static crossing lines (the moving dashes ride on top in the overlay).
            drawLine(c.accent.copy(alpha = 0.4f), o(147f, 116f), o(213f, 48f), strokeWidth = 2.5f * sx)
            drawLine(c.warm.copy(alpha = 0.4f), o(213f, 116f), o(147f, 48f), strokeWidth = 2.5f * sx)
            drawPath(Path().apply { addRoundRect(core) }, c.accent, style = Stroke(width = 2f * sx))
            // (The "LTO" label box is drawn in the overlay, above the moving dashes.)

            // Conditioning coils: heating (warm) then cooling (blue).
            fun coil(x: Float, stroke: Color, key: String) {
                if (on(key)) {
                    drawPath(
                        Path().apply { addRoundRect(RoundRect(o(x - 6f, 26f).x, o(x - 6f, 26f).y, o(x + 26f, 70f).x, o(x + 26f, 70f).y, CornerRadius(10f * sx, 10f * sy))) },
                        c.accent, style = Stroke(width = 1.4f * sx),
                    )
                }
                val body = RoundRect(o(x, 32f).x, o(x, 32f).y, o(x + 20f, 64f).x, o(x + 20f, 64f).y, CornerRadius(6f * sx, 6f * sy))
                drawPath(Path().apply { addRoundRect(body) }, c.surfaceCard)
                drawPath(Path().apply { addRoundRect(body) }, stroke, style = Stroke(width = 2f * sx))
                listOf(41f, 48f, 55f).forEach { wy ->
                    drawPath(
                        Path().apply {
                            moveTo(o(x + 4f, wy).x, o(x + 4f, wy).y)
                            quadraticBezierTo(o(x + 10f, wy - 4f).x, o(x + 10f, wy - 4f).y, o(x + 16f, wy).x, o(x + 16f, wy).y)
                        },
                        stroke, style = Stroke(width = 1.8f * sx, cap = StrokeCap.Round),
                    )
                }
            }
            coil(232f, c.warm, "coilH")
            coil(262f, MkCoolBlue, "coilC")

            // Fan bodies (the blades spin in the overlay).
            listOf(48f, 116f).forEach { cy ->
                val center = o(306f, cy)
                drawCircle(c.surfaceCard, radius = 13f * sx, center = center)
                drawCircle(c.inkMid, radius = 13f * sx, center = center, style = Stroke(width = 1.5f * sx))
                drawCircle(c.inkHi, radius = 2.6f * sx, center = center)
            }

            centeredText(measurer, "ULKO", o(14f, 88f), mono, 12f, c.inkMid, letterSpacingSp = 1.6f, anchorStart = true)
            centeredText(measurer, "KOTI", o(346f, 88f), mono, 12f, c.inkMid, letterSpacingSp = 1.6f, anchorEnd = true)
        }

        // ── Animated overlay: marching airflow + spinning fan blades only. ──
        Canvas(Modifier.fillMaxSize()) {
            val sx = size.width / 360f
            val sy = size.height / 164f
            fun o(x: Float, y: Float) = Offset(x * sx, y * sy)

            val air = PathEffect.dashPathEffect(floatArrayOf(3f * sx, 13f * sx), -flow * 16f * sx)
            fun fl(from: Offset, to: Offset, color: Color, brush: Brush? = null) {
                if (brush != null) drawLine(brush, from, to, strokeWidth = 5f * sy, cap = StrokeCap.Round, pathEffect = air)
                else drawLine(color, from, to, strokeWidth = 5f * sy, cap = StrokeCap.Round, pathEffect = air)
            }
            fl(o(143f, 48f), o(26f, 48f), c.accent)
            fl(o(26f, 116f), o(143f, 116f), c.accent)
            fl(o(217f, 48f), o(232f, 48f), c.warm)
            fl(o(252f, 48f), o(262f, 48f), c.warm)
            fl(o(282f, 48f), o(334f, 48f), c.accent)
            fl(o(334f, 116f), o(217f, 116f), c.warm)
            // Crossing streams inside the core.
            fl(o(147f, 116f), o(213f, 48f), c.accent, Brush.linearGradient(listOf(c.accent, c.warm), start = o(147f, 116f), end = o(213f, 48f)))
            fl(o(213f, 116f), o(147f, 48f), c.warm, Brush.linearGradient(listOf(c.warm, c.accent), start = o(213f, 116f), end = o(147f, 48f)))

            // Spinning fan blades.
            listOf(48f, 116f).forEach { cy ->
                val center = o(306f, cy)
                rotate(spin, pivot = center) {
                    listOf(
                        Offset(0f, -8f) to Size(3.4f, 6.6f), Offset(0f, 8f) to Size(3.4f, 6.6f),
                        Offset(-8f, 0f) to Size(6.6f, 3.4f), Offset(8f, 0f) to Size(6.6f, 3.4f),
                    ).forEach { (d, s) ->
                        drawOval(
                            c.inkMid.copy(alpha = 0.9f),
                            topLeft = Offset(center.x + d.x * sx - s.width * sx, center.y + d.y * sy - s.height * sy),
                            size = Size(s.width * 2f * sx, s.height * 2f * sy),
                        )
                    }
                }
                drawCircle(c.inkHi, radius = 2.6f * sx, center = center)
            }

            // LTO label — painted last so the crossing streams never obscure it.
            drawPath(
                Path().apply { addRoundRect(RoundRect(o(162f, 69f).x, o(162f, 69f).y, o(198f, 95f).x, o(198f, 95f).y, CornerRadius(6f * sx, 6f * sy))) },
                c.surfaceCard,
            )
            centeredText(measurer, "LTO", o(180f, 82f), mono, 12.5f, c.inkHi, FontWeight.SemiBold)
        }
    }
}

/** Draw text centred (or edge-anchored) at [at] in the current DrawScope. */
private fun DrawScope.centeredText(
    measurer: TextMeasurer, text: String, at: Offset, family: FontFamily, sizeSp: Float, color: Color,
    weight: FontWeight = FontWeight.Normal, letterSpacingSp: Float = 0f, anchorStart: Boolean = false, anchorEnd: Boolean = false,
) {
    val res = measurer.measure(text, TextStyle(fontFamily = family, fontSize = sizeSp.sp, fontWeight = weight, color = color, letterSpacing = letterSpacingSp.sp))
    val left = when {
        anchorEnd -> at.x - res.size.width
        anchorStart -> at.x
        else -> at.x - res.size.width / 2f
    }
    drawText(res, color = color, topLeft = Offset(left, at.y - res.size.height / 2f))
}
