package fi.marmorikatu.app.house3d

import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
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
    explode: Float,
    modifier: Modifier,
) {
    val filament = remember { FilamentHouse() }
    LaunchedEffect(Unit) {
        filament.loadModel(Res.readBytes("files/marmorikatu-house.glb"))
    }
    // Push the latest camera + visibility to the render loop each recomposition.
    SideEffect { filament.update(eye, target, mode, showRoof, showWalls, showFurniture, explode) }
    DisposableEffect(Unit) { onDispose { filament.destroy() } }
    AndroidView(
        factory = { ctx -> TextureView(ctx).also { filament.attach(it) } },
        modifier = modifier,
    )
}
