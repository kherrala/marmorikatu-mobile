package fi.marmorikatu.app.house3d

import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import fi.marmorikatu.app.theme.MkTheme
import marmorikatu_mobile.composeapp.generated.resources.Res

/** Android: GPU rendering via Google Filament into a [TextureView]. */
@Composable
actual fun HouseGeometrySurface(
    model: HouseModel,
    eye: Vec3,
    target: Vec3,
    mode: FloorMode,
    showRoof: Boolean,
    showWalls: Boolean,
    showFurniture: Boolean,
    showHeating: Boolean,
    heatByCircuit: Map<String, Float>,
    explode: Float,
    litLights: List<Vec3>,
    modifier: Modifier,
) {
    val filament = remember { FilamentHouse() }
    // Realistic room lighting only in dark mode; light mode keeps the flat default.
    val dark = MkTheme.colors.isDark
    LaunchedEffect(Unit) {
        filament.loadModel(Res.readBytes("files/marmorikatu-house.glb"))
    }
    // Push the latest camera + visibility to the render loop each recomposition.
    SideEffect {
        filament.update(eye, target, mode, showRoof, showWalls, showFurniture, showHeating, explode)
        filament.updateLighting(dark, litLights)
        filament.updateHeating(heatByCircuit)
    }
    DisposableEffect(Unit) { onDispose { filament.destroy() } }
    AndroidView(
        factory = { ctx -> TextureView(ctx).also { filament.attach(it) } },
        modifier = modifier,
    )
}
