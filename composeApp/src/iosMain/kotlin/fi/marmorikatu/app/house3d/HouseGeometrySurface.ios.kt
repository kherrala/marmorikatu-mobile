package fi.marmorikatu.app.house3d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.decodeToImageBitmap
import kotlinx.coroutines.CancellationException
import marmorikatu_mobile.composeapp.generated.resources.Res

/**
 * iOS geometry surface: the SceneKit (Metal) GPU renderer when a USDZ export is
 * bundled, otherwise the shared Compose software rasterizer. Flip [USE_SCENEKIT]
 * to force software while debugging the interop compositing.
 */
private const val USE_SCENEKIT = true

/** Set true to overlay the Compose slot size vs native view bounds for diagnosing interop sizing. */
internal const val DEBUG_SURFACE_SIZE = true

private sealed interface HouseAssetState {
    data object Loading : HouseAssetState
    data class Ready(val bytes: ByteArray) : HouseAssetState
    data object Unavailable : HouseAssetState
}

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
    var asset by remember {
        mutableStateOf<HouseAssetState>(
            if (USE_SCENEKIT) HouseAssetState.Loading else HouseAssetState.Unavailable,
        )
    }
    LaunchedEffect(Unit) {
        if (USE_SCENEKIT) {
            asset = try {
                HouseAssetState.Ready(Res.readBytes("files/marmorikatu-house.usdz"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                HouseAssetState.Unavailable
            }
        }
    }

    when (val current = asset) {
        HouseAssetState.Loading -> Box(modifier)
        is HouseAssetState.Ready ->
            SceneKitHouseSurface(
                current.bytes,
                eye,
                target,
                mode,
                showRoof,
                showWalls,
                showFurniture,
                showHeating,
                heatByCircuit,
                explode,
                litLights,
                modifier,
            )
        HouseAssetState.Unavailable -> {
            // Decode software textures only after native loading really failed.
            // Previously null meant both "still loading" and "missing", so iOS
            // built the CPU renderer before immediately replacing it with SceneKit.
            val cache = remember(model) {
                SoftwareRenderCache(model).apply {
                    installTextures(
                        model.textures.map { texture ->
                            runCatching { texture.bytes.decodeToImageBitmap() }.getOrNull()
                        },
                    )
                }
            }
            Canvas(modifier) {
                drawHouse(model, eye, target, mode, showRoof, showWalls, explode, cache, showHeating)
            }
        }
    }
}
