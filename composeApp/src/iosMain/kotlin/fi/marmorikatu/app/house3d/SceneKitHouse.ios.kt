@file:OptIn(
    ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package fi.marmorikatu.app.house3d

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import fi.marmorikatu.app.theme.MkTheme
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToURL
import platform.SceneKit.SCNCamera
import platform.SceneKit.SCNCameraProjectionDirectionVertical
import platform.SceneKit.SCNLight
import platform.SceneKit.SCNLightTypeAmbient
import platform.SceneKit.SCNLightTypeOmni
import platform.SceneKit.SCNLookAtConstraint
import platform.SceneKit.SCNNode
import platform.SceneKit.SCNScene
import platform.SceneKit.SCNTransaction
import platform.SceneKit.SCNVector3Make
import platform.SceneKit.SCNView
import platform.UIKit.UIColor
import platform.UIKit.UIScreen

/**
 * SceneKit (Metal) GPU renderer for iOS. Loads a USDZ export of the house model
 * into an [SCNView], driven by the shared orbit camera and the same
 * floor/wall/roof/explode visibility rules as Filament on Android and the
 * software rasterizer — so the common Compose overlays (markers, rings, room
 * tint, picking) line up identically over it.
 *
 * The USDZ **must** preserve the glTF node names (the six `Talo` group children —
 * `Kellari`/`Krs1`/`Krs2`/`Terassi`/`Katos`/`Katto` — and the `Room_*`/`Light_*`
 * leaves) and the material names (`WallExt`, `Roof`, `Glass`, `LightOff`, …) so
 * [classifyLeaves] can reuse [matClassForMaterial] and the group ancestry, exactly
 * like the Filament path.
 */
class SceneKitHouse {
    val view: SCNView = SCNView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), options = null).apply {
        // Opaque + painted with the app background (set via [setBackground]). The
        // transparent-composite path left a white window background showing through
        // in the uncovered interop region on iPad; an opaque themed fill can never
        // read as white in dark mode, whatever the interop compositing does.
        opaque = true
        autoenablesDefaultLighting = true
        allowsCameraControl = false
        // Compose owns the camera clock and explicitly invalidates this view after
        // every camera/model change. A permanent SCNView display link otherwise
        // burns 60 fps while the house is completely static.
        rendersContinuously = false
        preferredFramesPerSecond = 60
        userInteractionEnabled = false
    }

    /**
     * A container that resizes the SCNView to its own bounds on every layout pass.
     * CMP sizes THIS view to the full composable region, but the raw SCNView child
     * would keep its 0×0 factory frame; driving the child from [layoutSubviews]
     * fills the region (and refreshes the Metal drawable) on every resize/rotation.
     */
    /** Reports the container's laid-out size (points) each layout pass — for diagnostics. */
    var onContainerLayout: ((width: Double, height: Double) -> Unit)? = null

    val container: UIView = object : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
        override fun layoutSubviews() {
            super.layoutSubviews()
            // Report only. CMP mis-sizes this container on iPad (using nativeScale
            // where the screen scale is 2.0, so it comes out ~81% of the slot). The
            // SCNView child is sized from the Compose slot instead (see setSlotSizePx),
            // and clipping is off so it can overflow the undersized container.
            bounds.useContents { onContainerLayout?.invoke(size.width, size.height) }
        }
    }.apply {
        setClipsToBounds(false)
        addSubview(view)
    }

    /**
     * Size the SCNView to the Compose slot, converting the Compose-measured pixels
     * with the real screen scale rather than trusting CMP's container frame. This
     * is the fix for the undersized native view (white-L) on iPad.
     */
    fun setSlotSizePx(widthPx: Double, heightPx: Double) {
        if (widthPx <= 0.0 || heightPx <= 0.0) return
        val scale = UIScreen.mainScreen.scale.takeIf { it > 0.0 } ?: 2.0
        view.setFrame(CGRectMake(0.0, 0.0, widthPx / scale, heightPx / scale))
        view.setNeedsDisplay()
    }

    /** Paint the SCNView + container with the app background so no white shows. */
    fun setBackground(color: UIColor) {
        view.setBackgroundColor(color)
        container.setBackgroundColor(color)
    }

    private val cameraNode = SCNNode().apply {
        camera = SCNCamera().apply {
            fieldOfView = 48.0
            projectionDirection = SCNCameraProjectionDirectionVertical
            zNear = 0.1
            zFar = 500.0
        }
    }
    private val targetNode = SCNNode()
    private var loaded = false
    private var sourceUrl: NSURL? = null
    private var appliedEye: Vec3? = null
    private var appliedTarget: Vec3? = null
    private var appliedMode: FloorMode? = null
    private var appliedShowRoof = false
    private var appliedShowWalls = false
    private var appliedShowFurniture = false
    private var appliedShowHeating = false
    private var appliedExplode = Float.NaN

    private val leaves = ArrayList<Triple<SCNNode, HouseGroup, MatClass>>()
    private val groupNodes = HashMap<HouseGroup, SCNNode>()

    // --- Dark-mode room lighting ---------------------------------------------
    private var sceneRoot: SCNNode? = null
    // Dim cool fill so unlit rooms read as moonlit, not pitch black.
    private val ambientNode = SCNNode().apply {
        light = SCNLight().apply {
            type = SCNLightTypeAmbient
            color = UIColor.colorWithRed(0.42, green = 0.5, blue = 0.62, alpha = 1.0)
            intensity = 0.0
        }
    }
    // Pool of warm omni lights, one per active fixture, grown on demand.
    private val lightPool = ArrayList<SCNNode>()
    private var appliedDark: Boolean? = null
    private var appliedLightKey = ""

    private fun newOmniLight(): SCNNode = SCNNode().apply {
        light = SCNLight().apply {
            type = SCNLightTypeOmni
            color = UIColor.colorWithRed(1.0, green = 0.86, blue = 0.62, alpha = 1.0)
            intensity = 0.0
            // Keep each lamp roughly room-scale so it doesn't wash the whole house.
            attenuationStartDistance = 0.6
            attenuationEndDistance = 6.5
        }
    }

    fun load(usdz: ByteArray) {
        if (loaded) return
        val url = writeTemp(usdz) ?: return
        val scene = SCNScene.sceneWithURL(url, options = null, error = null)
        if (scene == null) {
            NSFileManager.defaultManager.removeItemAtURL(url, error = null)
            return
        }
        sourceUrl = url
        scene.rootNode.addChildNode(targetNode)
        scene.rootNode.addChildNode(cameraNode)
        cameraNode.constraints = listOf(
            SCNLookAtConstraint.lookAtConstraintWithTarget(targetNode).apply { gimbalLockEnabled = true },
        )
        // The USDZ ships a light environment/backdrop; clear it so the SCNView is
        // transparent and the dark app background shows through instead of a white box.
        scene.background.contents = null
        scene.rootNode.addChildNode(ambientNode)
        sceneRoot = scene.rootNode
        view.scene = scene
        view.pointOfView = cameraNode
        classifyLeaves(scene.rootNode)
        loaded = true
        view.setNeedsDisplay()
    }

    /**
     * Realistic per-room lighting in dark mode: kill the flat default light, add a
     * dim ambient, and place a warm omni light at each active fixture so lit rooms
     * glow and unlit ones fall dark. In light mode, restore the flat default light
     * and turn every added light off (the normal look is unchanged).
     */
    fun updateLighting(dark: Boolean, positions: List<Vec3>) {
        if (!loaded) return
        val key = positions.joinToString("|") { "${it.x},${it.y},${it.z}" }
        if (dark == appliedDark && key == appliedLightKey) return
        appliedDark = dark
        appliedLightKey = key
        if (!dark) {
            view.autoenablesDefaultLighting = true
            ambientNode.light?.intensity = 0.0
            lightPool.forEach { it.light?.intensity = 0.0 }
            view.setNeedsDisplay()
            return
        }
        view.autoenablesDefaultLighting = false
        ambientNode.light?.intensity = 380.0
        while (lightPool.size < positions.size) {
            val node = newOmniLight()
            sceneRoot?.addChildNode(node)
            lightPool.add(node)
        }
        positions.forEachIndexed { i, p ->
            lightPool[i].position = SCNVector3Make(p.x, p.y, p.z)
            lightPool[i].light?.intensity = 1300.0
        }
        for (i in positions.size until lightPool.size) lightPool[i].light?.intensity = 0.0
        view.setNeedsDisplay()
    }

    private fun classifyLeaves(root: SCNNode) {
        val byName = HouseGroup.entries.associateBy { it.name }
        fun groupOf(node: SCNNode): HouseGroup? {
            var cur: SCNNode? = node
            var guard = 0
            while (cur != null && guard++ < 32) {
                byName[cur.name]?.let { return it }
                cur = cur.parentNode
            }
            return null
        }
        fun visit(node: SCNNode) {
            node.name?.let { name -> byName[name]?.let { groupNodes[it] = node } }
            if (node.geometry != null) {
                groupOf(node)?.let { group ->
                    // Floor-heating overlays (incl. the Metal manifold boxes) classify
                    // by name so they hide/reveal with the Lämmitys layer.
                    val cls = if (node.name?.startsWith("Heat_") == true) MatClass.Heating
                    else matClassForMaterial(node.geometry?.firstMaterial?.name)
                    leaves.add(Triple(node, group, cls))
                }
            }
            node.childNodes.forEach { visit(it as SCNNode) }
        }
        visit(root)
    }

    fun update(eye: Vec3, target: Vec3, mode: FloorMode, showRoof: Boolean, showWalls: Boolean, showFurniture: Boolean, showHeating: Boolean, explode: Float) {
        if (!loaded) return
        val cameraChanged = eye != appliedEye || target != appliedTarget
        val explodeChanged = explode != appliedExplode
        val visibilityChanged = mode != appliedMode || showRoof != appliedShowRoof ||
            showWalls != appliedShowWalls || showFurniture != appliedShowFurniture || showHeating != appliedShowHeating
        if (!cameraChanged && !explodeChanged && !visibilityChanged) return
        // Compose already owns the camera tween. Prevent SceneKit from adding
        // implicit actions that trail the projected HUD after camera/layout changes.
        SCNTransaction.begin()
        SCNTransaction.setDisableActions(true)
        try {
            if (cameraChanged) {
                cameraNode.position = SCNVector3Make(eye.x, eye.y, eye.z)
                targetNode.position = SCNVector3Make(target.x, target.y, target.z)
            }
            // Orbiting only changes two camera nodes. Floor transforms and the
            // full leaf visibility walk are reserved for their actual controls;
            // doing both across the USDZ on all 60 frames dominated the iPad CPU.
            if (explodeChanged) {
                for ((group, node) in groupNodes) {
                    node.position = SCNVector3Make(0f, groupTier(group) * explode, 0f)
                }
            }
            if (visibilityChanged) {
                for ((node, group, cls) in leaves) {
                    node.hidden = !triVisible(group, cls, mode, showRoof, showWalls, showFurniture, showHeating)
                }
            }
        } finally {
            SCNTransaction.commit()
        }
        appliedEye = eye
        appliedTarget = target
        appliedMode = mode
        appliedShowRoof = showRoof
        appliedShowWalls = showWalls
        appliedShowFurniture = showFurniture
        appliedShowHeating = showHeating
        appliedExplode = explode
        view.setNeedsDisplay()
    }

    fun release() {
        view.rendersContinuously = false
        view.scene = null
        sourceUrl?.let { NSFileManager.defaultManager.removeItemAtURL(it, error = null) }
        sourceUrl = null
        loaded = false
        appliedEye = null
        appliedTarget = null
        appliedMode = null
        appliedExplode = Float.NaN
        leaves.clear()
        groupNodes.clear()
    }

    private fun writeTemp(bytes: ByteArray): NSURL? {
        val data = bytes.toNSData()
        val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + NSUUID().UUIDString() + ".usdz")
        return if (data.writeToURL(url, atomically = true)) url else null
    }
}

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }
    }

/**
 * Embeds the SceneKit [SCNView] via Compose's [UIKitView] and drives its camera +
 * visibility from the shared orbit state each recomposition. The common overlays
 * (markers, rings, room tint) composite on top in [HouseView3d].
 */
@Composable
fun SceneKitHouseSurface(
    usdz: ByteArray,
    eye: Vec3,
    target: Vec3,
    mode: FloorMode,
    showRoof: Boolean,
    showWalls: Boolean,
    showFurniture: Boolean,
    showHeating: Boolean,
    @Suppress("UNUSED_PARAMETER") heatByCircuit: Map<String, Float>,
    explode: Float,
    litLights: List<Vec3>,
    modifier: Modifier,
) {
    // TODO: per-circuit hot/cold colouring on SceneKit (Filament path colours first).
    val house = remember(usdz) { SceneKitHouse() }
    // Realistic room lighting only in dark mode; light mode keeps the flat default.
    val dark = MkTheme.colors.isDark
    // The 3D sits on the app background so the uncovered interop region can never
    // read as a white box (the earlier transparent-composite bug on iPad).
    val bg = MkTheme.colors.appBg
    val uiBg = remember(bg) {
        UIColor.colorWithRed(bg.red.toDouble(), green = bg.green.toDouble(), blue = bg.blue.toDouble(), alpha = 1.0)
    }
    var composeSize by remember { mutableStateOf(IntSize.Zero) }
    var nativeSize by remember { mutableStateOf(0.0 to 0.0) }
    val density = LocalDensity.current.density
    if (DEBUG_SURFACE_SIZE) {
        LaunchedEffect(house) { house.onContainerLayout = { w, h -> nativeSize = w to h } }
    }
    // CMP converts the interop view's pixel size to points with Compose's
    // LocalDensity, which on this iPad is ~2.45 while the real UIScreen.scale is
    // 2.0 — so the SceneKit view came out ~81% of the slot (the white-L). Force
    // the interop subtree's density to the true screen scale so CMP sizes it right.
    val screenScale = (UIScreen.mainScreen.scale.toFloat()).takeIf { it > 0f } ?: 2f
    Box(modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalDensity provides Density(screenScale)) {
        UIKitView(
            factory = {
                // Load synchronously with native view creation so the first update
                // cannot be lost before the scene exists. The container resizes the
                // SCNView to fill on every layout pass (see [SceneKitHouse.container]).
                house.load(usdz)
                house.setBackground(uiBg)
                house.container
            },
            modifier = Modifier.fillMaxSize().onSizeChanged { composeSize = it },
            update = {
                house.setBackground(uiBg)
                house.setSlotSizePx(composeSize.width.toDouble(), composeSize.height.toDouble())
                house.update(eye, target, mode, showRoof, showWalls, showFurniture, showHeating, explode)
                house.updateLighting(dark, litLights)
            },
            onRelease = { house.release() },
            onReset = null,
            // SceneKit is visual only. Compose owns all gestures, semantics, and HUD
            // layers, while the native view stays in the default underlay position.
            properties = UIKitInteropProperties(
                isInteractive = false,
                isNativeAccessibilityEnabled = false,
            ),
        )
        }
        if (DEBUG_SURFACE_SIZE) {
            androidx.compose.material3.Text(
                text = "compose ${composeSize.width}x${composeSize.height}px (÷${density}) · " +
                    "native ${nativeSize.first.toInt()}x${nativeSize.second.toInt()}pt · " +
                    "scale ${UIScreen.mainScreen.scale}/${UIScreen.mainScreen.nativeScale}",
                color = androidx.compose.ui.graphics.Color(0xFFFF3B30),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
            )
        }
    }
}
