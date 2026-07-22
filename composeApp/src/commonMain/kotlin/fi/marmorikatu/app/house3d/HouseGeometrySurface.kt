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
    explode: Float,
    modifier: Modifier,
)
