@file:OptIn(
    ExperimentalForeignApi::class,
    kotlinx.cinterop.BetaInteropApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package fi.marmorikatu.app.house3d

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToURL
import platform.SceneKit.SCNCamera
import platform.SceneKit.SCNCameraProjectionDirectionVertical
import platform.SceneKit.SCNLookAtConstraint
import platform.SceneKit.SCNNode
import platform.SceneKit.SCNScene
import platform.SceneKit.SCNTransaction
import platform.SceneKit.SCNVector3Make
import platform.SceneKit.SCNView
import platform.UIKit.UIColor
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth

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
        backgroundColor = UIColor.clearColor
        opaque = false
        autoenablesDefaultLighting = true
        allowsCameraControl = false
        // Compose owns the camera clock and explicitly invalidates this view after
        // every camera/model change. A permanent SCNView display link otherwise
        // burns 60 fps while the house is completely static.
        rendersContinuously = false
        preferredFramesPerSecond = 60
        userInteractionEnabled = false
        // In Compose's non-interactive interop path the view is hosted inside a
        // container that CMP sizes, but the child is added with no autoresizing —
        // so the SCNView kept its 0×0 factory frame and only the top of the model
        // rendered (the rest was blank). Fill the container as it resizes.
        setAutoresizingMask(UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight)
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
    private var appliedExplode = Float.NaN

    private val leaves = ArrayList<Triple<SCNNode, HouseGroup, MatClass>>()
    private val groupNodes = HashMap<HouseGroup, SCNNode>()

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
        view.scene = scene
        view.pointOfView = cameraNode
        classifyLeaves(scene.rootNode)
        loaded = true
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
                    leaves.add(Triple(node, group, matClassForMaterial(node.geometry?.firstMaterial?.name)))
                }
            }
            node.childNodes.forEach { visit(it as SCNNode) }
        }
        visit(root)
    }

    private var appliedWidth = 0.0
    private var appliedHeight = 0.0

    /**
     * Force the native surface to the Compose-measured size (in points). The
     * autoresizing mask handles container resizes, but on the very first layout
     * the container can settle after the child is added; setting the frame here
     * from Compose's own bounds guarantees the SCNView (and its Metal drawable)
     * fill the stage regardless of the interop container's timing.
     */
    fun setBounds(widthPts: Double, heightPts: Double) {
        if (widthPts <= 0.0 || heightPts <= 0.0) return
        if (widthPts == appliedWidth && heightPts == appliedHeight) return
        appliedWidth = widthPts
        appliedHeight = heightPts
        view.setFrame(CGRectMake(0.0, 0.0, widthPts, heightPts))
        view.setNeedsDisplay()
    }

    fun update(eye: Vec3, target: Vec3, mode: FloorMode, showRoof: Boolean, showWalls: Boolean, showFurniture: Boolean, explode: Float) {
        if (!loaded) return
        val cameraChanged = eye != appliedEye || target != appliedTarget
        val explodeChanged = explode != appliedExplode
        val visibilityChanged = mode != appliedMode || showRoof != appliedShowRoof ||
            showWalls != appliedShowWalls || showFurniture != appliedShowFurniture
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
                    node.hidden = !triVisible(group, cls, mode, showRoof, showWalls, showFurniture)
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
        appliedWidth = 0.0
        appliedHeight = 0.0
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
    explode: Float,
    modifier: Modifier,
) {
    val house = remember(usdz) { SceneKitHouse() }
    val density = LocalDensity.current.density
    var boundsPx by remember { mutableStateOf(0L) }
    UIKitView(
        factory = {
            // Load synchronously with native view creation so the first update
            // cannot be lost before the scene exists.
            house.load(usdz)
            house.view
        },
        modifier = modifier.fillMaxSize().onSizeChanged {
            // Pack w/h into one Long so the update lambda re-runs on any resize.
            boundsPx = (it.width.toLong() shl 32) or (it.height.toLong() and 0xffffffffL)
        },
        update = {
            val w = (boundsPx shr 32).toInt()
            val h = (boundsPx and 0xffffffffL).toInt()
            if (density > 0f) house.setBounds(w / density.toDouble(), h / density.toDouble())
            house.update(eye, target, mode, showRoof, showWalls, showFurniture, explode)
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
