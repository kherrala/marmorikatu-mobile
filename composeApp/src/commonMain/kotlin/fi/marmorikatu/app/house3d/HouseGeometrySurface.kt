package fi.marmorikatu.app.house3d

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Renders just the 3D house geometry, filling [modifier], under the given orbit
 * camera and visibility settings. Platform-specific: iOS draws it with the shared
 * Compose software rasterizer ([drawHouse]); Android renders it on the GPU with
 * Google Filament (textured, true per-pixel depth). All the surrounding chrome —
 * camera control, gestures, room tint/selection, markers, light rings, picking —
 * stays common in [HouseView3d] and composites on top, projected with the same
 * orbit camera so it aligns on both platforms.
 */
@Composable
expect fun HouseGeometrySurface(
    model: HouseModel,
    eye: Vec3,
    target: Vec3,
    mode: FloorMode,
    showRoof: Boolean,
    showWalls: Boolean,
    showFurniture: Boolean,
    /** "Lämmitys" mode: reveal the underfloor-heating loop overlays (hidden otherwise). */
    showHeating: Boolean,
    /** Circuit "nn" → 0..1 heat intensity, colouring each loop cold→hot in Lämmitys mode. */
    heatByCircuit: Map<String, Float>,
    explode: Float,
    /**
     * World positions of the fixtures that are currently on (floor-filtered,
     * explode-adjusted). In dark mode the GPU renderers place a warm point light
     * at each so lit rooms glow and unlit rooms fall dark; in light mode they keep
     * the flat global lighting and ignore this.
     */
    litLights: List<Vec3>,
    modifier: Modifier,
)
