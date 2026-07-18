package fi.marmorikatu.app.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.util.lerp
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.core.config.AssistantGender
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/*
 * The assistant's animated face. A faithful Compose port of the Claude Design
 * avatar (SVG viewBox 320×310): the head sways, blinks, raises its brows to
 * listen, glances aside to think, and lip-syncs while speaking — all driven by
 * the [VoiceState]. The [gender] swaps the hair/beard and is otherwise the
 * same rig.
 *
 * Motion is driven off the raw frame clock (withFrameNanos), NOT Compose's
 * infinite transitions: the latter are frozen when the device's "animator
 * duration scale" is off (dev options / battery saver), which would leave the
 * avatar switching poses but never moving. The frame clock ignores that setting.
 *
 * SVG path data is parsed once into Compose Paths; the whole thing is drawn in
 * the original 320×310 coordinate space and scaled to fit.
 */

// ── palette (straight from the design) ──────────────────────────────────────
private val Skin = Color(0xFFECBD94)
private val Neck = Color(0xFFE0AC83)
private val Shoulders = Color(0xFF252B33)
private val WomanHair = Color(0xFF43302A)
private val WomanBangs = Color(0xFF4A352E)
private val ManHair = Color(0xFF2C2823)
private val ChinShadow = Color(0xFFD9A377)
private val Blush = Color(0xFFE2876F)
private val Brow = Color(0xFF3A2F28)
private val EyeWhite = Color(0xFFF7F3EC)
private val Iris = Color(0xFF4F7D8C)
private val Pupil = Color(0xFF1C2226)
private val Nose = Color(0xFFD9A377)
private val Smile = Color(0xFFC4756A)
private val MouthOuter = Color(0xFF9D544B)
private val MouthInner = Color(0xFF4A2424)
private val Teeth = Color(0xFFF2ECE2)
private val Tongue = Color(0xFFB3564E)

private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

/** Static path data, parsed once. */
private class AvatarPaths {
    val neck = path("M141 226 L141 264 Q160 275 179 264 L179 226 Q160 244 141 226 Z")
    val shoulders = path("M60 310 Q78 258 132 252 Q160 268 188 252 Q242 258 260 310 Z")
    val collar = path("M132 252 Q160 268 188 252")
    val womanHairBack = path(
        "M160 34 C 96 34 70 88 74 148 C 76 198 90 232 118 246 C 106 210 104 174 108 136 " +
            "C 124 116 196 116 212 136 C 216 174 214 210 202 246 C 230 232 244 198 246 148 " +
            "C 250 88 224 34 160 34 Z",
    )
    val face = path(
        "M160 58 C 218 58 240 104 238 148 C 236 196 208 238 160 242 C 112 238 84 196 82 148 " +
            "C 80 104 102 58 160 58 Z",
    )
    val chinShadow = path("M120 226 Q160 247 200 226 Q182 240 160 241 Q138 240 120 226 Z")
    val manBeard = path("M110 198 Q122 234 160 238 Q198 234 210 198 Q196 226 160 228 Q124 226 110 198 Z")
    val manHair = path(
        "M86 120 C 82 60 118 44 160 44 C 202 44 238 60 234 120 C 222 82 196 68 160 68 " +
            "C 124 68 98 82 86 120 Z",
    )
    val browL = path("M111 122 Q128 113 145 119")
    val browR = path("M175 119 Q192 113 209 122")
    val nose = path("M159 152 Q155 168 153 171 Q159 176 165 172")
    val smile = path("M138 199 Q160 211 182 199")
    val flat = path("M143 204 Q160 208 177 203")
    val tongue = path("M148 212 Q160 204 172 212 Q160 219 148 212 Z")
    val womanBangs = path(
        "M90 118 C 92 76 118 58 160 56 C 202 58 228 76 230 118 C 214 92 190 84 160 84 " +
            "C 130 84 104 94 90 118 Z",
    )
    val lashL = path("M113 134 L107 130 M112 138 L105 136")
    val lashR = path("M207 134 L213 130 M208 138 L215 136")
}

/** All the per-frame animated values the drawing reads. Resting-face defaults. */
private data class AvatarFrame(
    val headRot: Float = 0f,
    val headTy: Float = 0f,
    val browRaise: Float = 0f,
    val browTilt: Float = 0f,
    val eyeGrow: Float = 1f,
    val glanceX: Float = 0f,
    val glanceY: Float = 0f,
    val lidScale: Float = 0.14f,
    val smileA: Float = 1f,
    val flatA: Float = 0f,
    val openA: Float = 0f,
    val mouthOpen: Float = 0f,
)

/** Smooth 0→1→0 sine oscillation with the given period (ms). */
private fun osc(tMs: Float, periodMs: Float): Float =
    ((sin(tMs / periodMs * 2.0 * PI).toFloat()) + 1f) / 2f

/** Advance the eased pose + continuous motion by [dt] seconds at time [tMs]. */
private fun advanceAvatar(prev: AvatarFrame, state: VoiceState, dt: Float, tMs: Float): AvatarFrame {
    val listening = state == VoiceState.Listening
    val thinking = state == VoiceState.Thinking
    val speaking = state == VoiceState.Speaking
    // Critically-damped-ish approach to the target (~0.4 s to settle).
    val k = (dt * 9f).coerceIn(0f, 1f)
    fun ease(cur: Float, target: Float) = cur + (target - cur) * k

    val browRaise = ease(prev.browRaise, if (listening) 1f else 0f)
    val browTilt = ease(prev.browTilt, if (thinking) 1f else 0f)
    val eyeGrow = ease(prev.eyeGrow, if (listening) 1.07f else 1f)
    val glanceX = ease(prev.glanceX, if (thinking) 5f else 0f)
    val glanceY = ease(prev.glanceY, if (thinking) -5.5f else 0f)
    val headTilt = ease(prev.headTy, if (thinking) -2.5f else 0f) // reuse slot for eased tilt base
    val smileA = ease(prev.smileA, if (speaking || thinking) 0f else 1f)
    val flatA = ease(prev.flatA, if (thinking) 1f else 0f)
    val openA = ease(prev.openA, if (speaking) 1f else 0f)

    val listenO = osc(tMs, 3600f)
    val talkO = osc(tMs, 2300f)
    val headRot = when {
        listening -> lerp(2.6f, 4.4f, listenO)
        speaking -> lerp(-1.4f, 1.6f, talkO)
        thinking -> -2.5f
        else -> 0f
    }
    val headTy = when {
        listening -> lerp(0f, 2f, listenO)
        speaking -> lerp(0f, 1.2f, talkO)
        else -> headTilt.coerceIn(-2.5f, 0f) * 0f // keep 0 vertical when not listening/speaking
    }

    // Blink: eyelid a sliver (0.14) most of the cycle, a quick full close.
    val b = (tMs % 4700f) / 4700f
    val lidScale = if (b in 0.90f..0.965f) {
        val tri = 1f - abs((b - 0.9325f) / 0.0325f)
        lerp(0.14f, 1f, tri.coerceIn(0f, 1f))
    } else {
        0.14f
    }

    // Lip-sync: two overlaid oscillations make the open mouth flap unevenly.
    val mouthOpen = (osc(tMs, 300f) * 0.7f + osc(tMs, 170f) * 0.3f).coerceIn(0f, 1f)

    return AvatarFrame(
        headRot = headRot,
        headTy = headTy,
        browRaise = browRaise,
        browTilt = browTilt,
        eyeGrow = eyeGrow,
        glanceX = glanceX,
        glanceY = glanceY,
        lidScale = lidScale,
        smileA = smileA,
        flatA = flatA,
        openA = openA,
        mouthOpen = mouthOpen,
    )
}

private fun DrawScope.oval(color: Color, cx: Float, cy: Float, rx: Float, ry: Float, alpha: Float = 1f) =
    drawOval(color, topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2, ry * 2), alpha = alpha)

@Composable
fun MkAssistantAvatar(
    state: VoiceState,
    gender: AssistantGender,
    modifier: Modifier = Modifier,
) {
    val p = remember { AvatarPaths() }
    val accent = MkTheme.colors.accent
    val woman = gender == AssistantGender.Nainen

    // Latest state read inside the frame loop without restarting it, so pose
    // easing stays continuous across state changes.
    val currentState by rememberUpdatedState(state)
    val frame = remember { mutableStateOf(AvatarFrame()) }
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        var tMs = 0f
        var acc = AvatarFrame()
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastNanos == 0L) 0f else (now - lastNanos) / 1_000_000_000f
                lastNanos = now
                tMs += dt * 1000f
                acc = advanceAvatar(acc, currentState, dt, tMs)
                frame.value = acc
            }
        }
    }

    Canvas(modifier) {
        val f = frame.value // read in draw scope → redraws each frame, no recompose
        val s = size.width / 320f
        scale(s, s, pivot = Offset.Zero) {
            // ── body (static) ──
            drawPath(p.neck, Neck)
            drawPath(p.shoulders, Shoulders)
            drawPath(p.collar, accent, style = Stroke(2f, cap = StrokeCap.Round), alpha = 0.45f)

            // ── head group: sway + tilt about the neck base ──
            translate(0f, f.headTy) {
                rotate(f.headRot, pivot = Offset(160f, 204f)) {
                    if (woman) drawPath(p.womanHairBack, WomanHair)

                    oval(Skin, 84f, 152f, 10f, 15f)
                    oval(Skin, 236f, 152f, 10f, 15f)
                    drawPath(p.face, Skin)
                    drawPath(p.chinShadow, ChinShadow, alpha = 0.5f)
                    oval(Blush, 117f, 176f, 9f, 5.5f, alpha = 0.13f)
                    oval(Blush, 203f, 176f, 9f, 5.5f, alpha = 0.13f)

                    if (!woman) {
                        drawPath(p.manBeard, ManHair, alpha = 0.28f)
                        drawPath(p.manHair, ManHair)
                    }

                    // Brows: raise together to listen, tilt in to think.
                    translate(0f, f.browRaise * -3.5f + f.browTilt * 1.5f) {
                        rotate(f.browTilt * 7f, pivot = Offset(128f, 118f)) {
                            drawPath(p.browL, Brow, style = Stroke(4.5f, cap = StrokeCap.Round))
                        }
                        rotate(f.browTilt * -7f, pivot = Offset(192f, 118f)) {
                            drawPath(p.browR, Brow, style = Stroke(4.5f, cap = StrokeCap.Round))
                        }
                    }

                    // Eyes (grow slightly while listening).
                    scale(f.eyeGrow, f.eyeGrow, pivot = Offset(160f, 142f)) {
                        oval(EyeWhite, 128f, 142f, 16f, 10f)
                        oval(EyeWhite, 192f, 142f, 16f, 10f)
                        // Pupils glance aside while thinking.
                        translate(f.glanceX, f.glanceY) {
                            drawCircle(Iris, 7.2f, Offset(128f, 142f))
                            drawCircle(Pupil, 3.1f, Offset(128f, 142f))
                            drawCircle(Color.White, 1.5f, Offset(125.7f, 139.4f), alpha = 0.95f)
                            drawCircle(Iris, 7.2f, Offset(192f, 142f))
                            drawCircle(Pupil, 3.1f, Offset(192f, 142f))
                            drawCircle(Color.White, 1.5f, Offset(189.7f, 139.4f), alpha = 0.95f)
                        }
                        // Eyelids blink down over the eyes.
                        scale(1f, f.lidScale, pivot = Offset(128f, 130f)) {
                            drawRoundRect(Skin, Offset(111f, 130f), Size(34f, 16f), CornerRadius(8f, 8f))
                        }
                        scale(1f, f.lidScale, pivot = Offset(192f, 130f)) {
                            drawRoundRect(Skin, Offset(175f, 130f), Size(34f, 16f), CornerRadius(8f, 8f))
                        }
                    }

                    drawPath(p.nose, Nose, style = Stroke(2.6f, cap = StrokeCap.Round), alpha = 0.7f)

                    // Mouth: smile / flat (thinking) / open lip-sync (speaking).
                    if (f.smileA > 0.01f) {
                        drawPath(p.smile, Smile, style = Stroke(4.5f, cap = StrokeCap.Round), alpha = f.smileA)
                    }
                    if (f.flatA > 0.01f) {
                        drawPath(p.flat, Smile, style = Stroke(4f, cap = StrokeCap.Round), alpha = f.flatA)
                    }
                    if (f.openA > 0.01f) {
                        val mx = lerp(0.94f, 1.05f, f.mouthOpen)
                        val my = lerp(0.28f, 0.95f, f.mouthOpen)
                        scale(mx, my, pivot = Offset(160f, 198f)) {
                            oval(MouthOuter, 160f, 204f, 22f, 13f, alpha = f.openA)
                            oval(MouthInner, 160f, 206f, 16f, 9f, alpha = f.openA)
                            drawRoundRect(
                                Teeth, Offset(147f, 197f), Size(26f, 6f), CornerRadius(3f, 3f),
                                alpha = f.openA,
                            )
                            drawPath(p.tongue, Tongue, alpha = f.openA)
                        }
                    }

                    if (woman) {
                        drawPath(p.womanBangs, WomanBangs)
                        drawPath(p.lashL, Brow, style = Stroke(1.6f, cap = StrokeCap.Round))
                        drawPath(p.lashR, Brow, style = Stroke(1.6f, cap = StrokeCap.Round))
                    }
                }
            }
        }
    }
}

/**
 * A tiny static face for the settings persona picker (design's 48×48 chips).
 * No animation — just the resting smile, hair and eyes for each gender.
 */
@Composable
fun MkAssistantFaceMini(gender: AssistantGender, modifier: Modifier = Modifier) {
    val woman = gender == AssistantGender.Nainen
    val hair = remember(woman) {
        if (woman) {
            path("M24 8 C 10 8 6 22 9 34 Q11 25 13 20 Q24 14 35 20 Q37 25 39 34 C 42 22 38 8 24 8 Z")
        } else {
            path("M9 24 Q8 9 24 8 Q40 9 39 24 Q34 14 24 13 Q14 14 9 24 Z")
        }
    }
    val beard = remember { path("M14 32 Q18 40 24 40 Q30 40 34 32 Q30 37 24 37 Q18 37 14 32 Z") }
    val smile = remember { path("M19 32 Q24 36 29 32") }
    Canvas(modifier) {
        val s = size.width / 48f
        scale(s, s, pivot = Offset.Zero) {
            drawCircle(Skin, 15f, Offset(24f, 26f))
            drawPath(hair, if (woman) WomanHair else ManHair)
            if (!woman) drawPath(beard, ManHair, alpha = 0.3f)
            drawCircle(Pupil, 1.8f, Offset(19f, 25f))
            drawCircle(Pupil, 1.8f, Offset(29f, 25f))
            drawPath(smile, Color(0xFFB96A5E), style = Stroke(1.8f, cap = StrokeCap.Round))
        }
    }
}
