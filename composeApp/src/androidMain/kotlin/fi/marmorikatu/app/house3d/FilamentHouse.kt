package fi.marmorikatu.app.house3d

import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer

/**
 * Google Filament wrapper that renders the house GLB on the GPU into a
 * [TextureView] (so Compose overlays composite cleanly on top). The camera and
 * per-entity visibility/explode are pushed each frame from the shared orbit
 * state, using the same 48° vertical FOV as [projectWorld] so the common
 * marker/tint/pick overlays stay aligned over the GPU render.
 */
class FilamentHouse {
    companion object {
        init { Utils.init() }

        // Circuit number from a Heat_ node name (Heat_1krs_41[.pipe] → 41; JT boxes none).
        private val HEAT_CIRCUIT_RE = Regex("Heat_\\w+?_(\\d\\d)")

        // Loop colours: cold #3b82f6 → hot #ef4444, neutral #8E9AA8 for "no data".
        private val HEAT_COLD = floatArrayOf(0.05f, 0.36f, 1.0f)
        private val HEAT_HOT = floatArrayOf(0.92f, 0.04f, 0.03f)
        private val HEAT_NEUTRAL = floatArrayOf(0.557f, 0.604f, 0.659f)

        private fun lerp3(a: FloatArray, b: FloatArray, t: Float): FloatArray {
            val s = t.coerceIn(0f, 1f)
            return floatArrayOf(
                a[0] + (b[0] - a[0]) * s,
                a[1] + (b[1] - a[1]) * s,
                a[2] + (b[2] - a[2]) * s,
            )
        }
    }

    private val engine = Engine.create()
    private val renderer = engine.createRenderer().apply {
        clearOptions = Renderer.ClearOptions().apply {
            clear = true
            clearColor = floatArrayOf(0f, 0f, 0f, 0f) // transparent — overlay bg shows through
        }
    }
    private val scene: Scene = engine.createScene()
    private val cameraEntity = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity).apply {
        setExposure(16f, 1f / 125f, 100f)
    }
    private val view: View = engine.createView().apply {
        scene = this@FilamentHouse.scene
        camera = this@FilamentHouse.camera
        blendMode = View.BlendMode.TRANSLUCENT
    }
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply { isOpaque = false }
    private var swapChain: SwapChain? = null
    private val choreographer = Choreographer.getInstance()

    private val materialProvider = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
    private val resourceLoader = ResourceLoader(engine)
    private var asset: FilamentAsset? = null
    // The GLB source buffer must outlive the async texture decode (gltfio reads
    // the embedded JPEGs from it on background threads) — hold a reference so it
    // is not GC'd mid-load, which would silently drop every texture to a dummy.
    private var modelBuffer: ByteBuffer? = null
    @Volatile private var resourceLoading = false

    private val groupOf = HashMap<Int, HouseGroup>()
    private val classOf = HashMap<Int, MatClass>()
    private val hiddenEntities = HashSet<Int>()
    private val tierTransforms = Array(4) {
        floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    @Volatile private var eye = Vec3(20f, 17f, 17f)
    @Volatile private var target = Vec3.ZERO
    @Volatile private var mode = FloorMode.All
    @Volatile private var showRoof = false
    @Volatile private var showWalls = false
    @Volatile private var showFurniture = true
    @Volatile private var showHeating = false
    @Volatile private var explode = 0f
    @Volatile private var ready = false
    @Volatile private var dirty = true
    @Volatile private var sceneStateDirty = true

    private val sunEntity = EntityManager.get().create()
    private var indirect: IndirectLight? = null

    // --- Floor-heating "Lämmitys" colouring ---
    // Per-loop material instance (cloned so each circuit colours independently) +
    // its circuit number, and the live 0..1 intensity per circuit.
    private val heatCircuitOf = HashMap<Int, String>()
    private val heatInstanceOf = HashMap<Int, com.google.android.filament.MaterialInstance>()
    private val heatRevealEntities = HashSet<Int>()
    // Distinct oak-floor material instances, dimmed while the heating layer is shown.
    private val floorDimInstances = HashSet<com.google.android.filament.MaterialInstance>()
    private var floorDimApplied: Boolean? = null
    @Volatile private var heatByCircuit: Map<String, Float> = emptyMap()
    @Volatile private var heatColorDirty = true

    // --- Dark-mode room lighting ---
    private val pointLightPool = ArrayList<Int>()
    @Volatile private var darkLighting = false
    @Volatile private var litPositions: List<Vec3> = emptyList()
    @Volatile private var lightingDirty = true
    private var appliedDarkLighting: Boolean? = null

    init {
        // Exposure (setExposure below) is ~EV15 daylight, so a ~70k-lux key gives
        // mid-bright diffuse; the indirect fill must stay small or light surfaces
        // blow out to white. (Earlier 0.55×45k ambient was ~10× too strong.)
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 0.97f, 0.92f)
            .intensity(70_000f)
            .direction(-0.4f, -1f, -0.55f)
            .castShadows(false)
            .build(engine, sunEntity)
        scene.addEntity(sunEntity)
        indirect = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(0.35f, 0.37f, 0.40f))
            .intensity(6_000f)
            .build(engine)
        scene.indirectLight = indirect
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)
            // Pump the async resource decode until textures finish streaming in;
            // keep rendering each frame so they appear as soon as they land.
            if (resourceLoading) {
                resourceLoader.asyncUpdateLoad()
                val p = resourceLoader.asyncGetLoadProgress()
                if (p >= 1f) {
                    resourceLoading = false
                    // NOTE: deliberately NOT calling releaseSourceData()/freeing the
                    // buffer — the background JPEG decode reads the embedded images
                    // from the source, and freeing it here dropped every texture to a
                    // white dummy (grey floors/walls). Holding ~6MB is a fair trade.
                }
                dirty = true
            }
            if (!ready || !dirty) return
            applyFrame()
            val sc = swapChain ?: return
            if (uiHelper.isReadyToRender && renderer.beginFrame(sc, frameTimeNanos)) {
                renderer.render(view)
                renderer.endFrame()
                dirty = false
            }
        }
    }

    fun attach(textureView: TextureView) {
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
                dirty = true
            }

            override fun onDetachedFromSurface() {
                swapChain?.let { engine.destroySwapChain(it); engine.flushAndWait(); swapChain = null }
            }

            override fun onResized(width: Int, height: Int) {
                if (width <= 0 || height <= 0) return
                view.viewport = Viewport(0, 0, width, height)
                camera.setProjection(48.0, width.toDouble() / height.toDouble(), 0.1, 500.0, Camera.Fov.VERTICAL)
                dirty = true
            }
        }
        uiHelper.attachTo(textureView)
        choreographer.removeFrameCallback(frameCallback)
        choreographer.postFrameCallback(frameCallback)
    }

    fun loadModel(bytes: ByteArray) {
        if (asset != null) return
        val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
        modelBuffer = buffer
        val a = assetLoader.createAsset(buffer) ?: return
        classify(a)
        scene.addEntities(a.entities)
        asset = a
        // Stream buffers + textures on background threads; pumped in doFrame until
        // complete. releaseSourceData happens only once decoding finishes.
        resourceLoader.asyncBeginLoad(a)
        resourceLoading = true
        ready = true
        sceneStateDirty = true
        dirty = true
    }

    private fun classify(a: FilamentAsset) {
        val tm = engine.transformManager
        val rm = engine.renderableManager
        val groupNames = HouseGroup.entries.associateBy { it.name }
        for (e in a.entities) {
            if (!rm.hasComponent(e)) continue
            // Group = nearest ancestor named after a top-level Talo child.
            var cur = e
            var grp: HouseGroup? = null
            var guard = 0
            while (cur != 0 && guard++ < 32) {
                val g = a.getName(cur)?.let { groupNames[it] }
                if (g != null) { grp = g; break }
                val ti = tm.getInstance(cur)
                if (ti == 0) break
                cur = tm.getParent(ti)
            }
            if (grp == null) continue
            groupOf[e] = grp
            val nodeName = a.getName(e)
            // NOTE: Room_* nodes are the finished floor slabs (oak/tile/concrete),
            // NOT invisible pick patches — they carry the only oak (`Wood`) geometry
            // in the model, so hiding them left the interior grey. Render them; room
            // picking works off the parsed HouseModel, not these Filament entities.
            val ri = rm.getInstance(e)
            val matName = runCatching { rm.getMaterialInstanceAt(ri, 0).name }.getOrNull()
            // gltfio normally preserves the glTF material name, but keep a node-
            // name fallback for fixture bulbs so they cannot reappear if a driver
            // returns an anonymous material instance. Metal .cord/.pole nodes stay.
            classOf[e] = if (nodeName?.startsWith("Light_") == true &&
                !nodeName.endsWith(".cord") && !nodeName.endsWith(".pole")
            ) {
                MatClass.Fixture
            } else if (nodeName?.contains("ladder") == true || nodeName?.contains("rlad") == true) {
                // The exterior fire-escape ladder (`*.ladder.*`) AND the roof-access
                // ladder that runs up to the chimney (`*.rlad.*`) are tall roof-level
                // features: class them Roof so they hide in floor cutaways (and with the
                // roof toggle) and stay out of the framing bbox, like the eaves.
                MatClass.Roof
            } else {
                matClassForMaterial(matName)
            }
            // Floor-heating overlays: zones (HeatOff) + pipes (HeatPipe) come from the
            // material; the manifold boxes (Heat_*_JT*, Metal material) are caught by
            // name so they reveal/hide with the layer.
            if (nodeName?.startsWith("Heat_") == true) {
                classOf[e] = MatClass.Heating
                // Circuit number for colouring; JT manifolds have none → stay neutral.
                HEAT_CIRCUIT_RE.find(nodeName)?.groupValues?.get(1)?.let { heatCircuitOf[e] = it }
                // Reveal the thin pipes + manifolds (oak floor stays visible); the
                // solid zone patches stay hidden until the Alueet/Molemmat view lands.
                if (nodeName.contains(".pipe") || nodeName.contains("_pipe") || nodeName.contains("_JT")) {
                    heatRevealEntities.add(e)
                }
                // Colour each loop/zone's own material instance directly. The model
                // gives every heat circuit a UNIQUE material (Heat_off_<nn>/Heat_pipe_<nn>),
                // so gltfio backs each with its own native MaterialInstance and circuits
                // colour independently. (A single shared "HeatOff"/"HeatPipe" material
                // would give one native instance for the whole layer — last write wins,
                // painting everything one colour.)
                runCatching { heatInstanceOf[e] = rm.getMaterialInstanceAt(ri, 0) }
            }
            // Textured floors (oak + concrete, both baseColorFactor-white) dim to a
            // plain dark neutral while Lämmitys is on, so the loops read as a clean
            // schematic instead of fighting the floor texture (the pipes are flat
            // ribbons in the model, not tubes). Plain-colour tile floors are left as-is.
            if (nodeName?.startsWith("Room_") == true && (matName == "Wood" || matName == "ConcreteF")) {
                runCatching { floorDimInstances.add(rm.getMaterialInstanceAt(ri, 0)) }
            }
        }
        heatColorDirty = true
    }

    private fun applyFloorDim() {
        if (floorDimApplied == showHeating) return
        floorDimApplied = showHeating
        for (mi in floorDimInstances) {
            // baseColorFactor multiplies the oak texture: near-black cool grey while
            // heating (grain fades into a dark backdrop), white (unchanged) otherwise.
            if (showHeating) {
                runCatching { mi.setParameter("baseColorFactor", 0.26f, 0.28f, 0.33f, 1f) }
            } else {
                runCatching { mi.setParameter("baseColorFactor", 1f, 1f, 1f, 1f) }
            }
        }
    }

    private fun applyHeatColors() {
        if (!heatColorDirty) return
        heatColorDirty = false
        for ((e, circuit) in heatCircuitOf) {
            val mi = heatInstanceOf[e] ?: continue
            val t = heatByCircuit[circuit]
            val c = if (t != null) lerp3(HEAT_COLD, HEAT_HOT, t) else HEAT_NEUTRAL
            // The instance was cloned from the ubershader's *default* material, whose
            // metallicFactor defaults to 1.0 — a metal with no reflections renders
            // black regardless of baseColour. Force a matte dielectric so the loop
            // shows its colour, plus a modest emissive so it still reads if lighting
            // is dim.
            // Hotter loops glow more so the reds really pop against the oak; the
            // emissive lifts saturation that the daylight key would otherwise wash out.
            // A lit dielectric (metallic 0, mid roughness) so the daylight shades the
            // round tube — highlights on top, darker sides — instead of reading as a
            // flat sticker. Only a whisper of emissive so a loop in shadow still shows
            // its colour without washing the 3D form to a uniform block.
            val glow = 0.16f
            runCatching { mi.setParameter("baseColorFactor", c[0], c[1], c[2], 1f) }
            runCatching { mi.setParameter("metallicFactor", 0f) }
            runCatching { mi.setParameter("roughnessFactor", 0.55f) }
            runCatching { mi.setParameter("emissiveFactor", c[0] * glow, c[1] * glow, c[2] * glow) }
            // The round-tube export left the top faces culled (only the side edges of
            // each loop were drawing, floor showing through the middle). Render both
            // sides so the whole tube fills in.
            runCatching { mi.setCullingMode(com.google.android.filament.Material.CullingMode.NONE) }
        }
    }

    fun update(eye: Vec3, target: Vec3, mode: FloorMode, showRoof: Boolean, showWalls: Boolean, showFurniture: Boolean, showHeating: Boolean, explode: Float) {
        if (this.eye == eye && this.target == target && this.mode == mode &&
            this.showRoof == showRoof && this.showWalls == showWalls &&
            this.showFurniture == showFurniture && this.showHeating == showHeating && this.explode == explode
        ) {
            return
        }
        val geometryChanged = this.mode != mode || this.showRoof != showRoof ||
            this.showWalls != showWalls || this.showFurniture != showFurniture ||
            this.showHeating != showHeating || this.explode != explode
        this.eye = eye
        this.target = target
        this.mode = mode
        this.showRoof = showRoof
        this.showWalls = showWalls
        this.showFurniture = showFurniture
        this.showHeating = showHeating
        this.explode = explode
        if (geometryChanged) sceneStateDirty = true
        dirty = true
    }

    /**
     * Realistic per-room lighting in dark mode: dim the daylight key + indirect
     * fill and place a warm point light at each active fixture, so lit rooms glow
     * and unlit rooms fall dark. Light mode restores the flat daylight unchanged.
     */
    /** Push the live per-circuit heat intensities; recolours the loops next frame. */
    fun updateHeating(map: Map<String, Float>) {
        if (map == heatByCircuit) return
        heatByCircuit = map
        heatColorDirty = true
        dirty = true
    }

    fun updateLighting(dark: Boolean, positions: List<Vec3>) {
        if (dark == darkLighting && positions == litPositions) return
        darkLighting = dark
        litPositions = positions
        lightingDirty = true
        dirty = true
    }

    private fun rebuildIndirect(intensity: Float) {
        indirect?.let { engine.destroyIndirectLight(it) }
        indirect = IndirectLight.Builder()
            .irradiance(1, floatArrayOf(0.35f, 0.37f, 0.40f))
            .intensity(intensity)
            .build(engine)
        scene.indirectLight = indirect
    }

    private fun applyLighting() {
        if (!lightingDirty) return
        lightingDirty = false
        val lm = engine.lightManager
        // Sun + indirect only change when the dark/light mode itself flips.
        if (appliedDarkLighting != darkLighting) {
            appliedDarkLighting = darkLighting
            // No sun in dark mode — rooms are lit only by their own lamps + a faint
            // ambient fill. Light mode keeps the full daylight key.
            val sunInst = lm.getInstance(sunEntity)
            if (sunInst != 0) lm.setIntensity(sunInst, if (darkLighting) 0f else 70_000f)
            if (darkLighting) {
                // Night look (~EV13): only a whisper of ambient so the exterior reads
                // dark at night; lit lamps/windows glow above it. (With 0 lights on
                // offline this looks near-black — that's expected; real light data
                // makes the lit rooms pop.)
                camera.setExposure(8f, 1f / 125f, 100f)
                rebuildIndirect(1_500f)
            } else {
                camera.setExposure(16f, 1f / 125f, 100f)
                rebuildIndirect(6_000f)
            }
        }
        if (!darkLighting) {
            for (e in pointLightPool) if (scene.hasEntity(e)) scene.removeEntity(e)
            return
        }
        val positions = litPositions
        while (pointLightPool.size < positions.size) {
            val e = EntityManager.get().create()
            LightManager.Builder(LightManager.Type.POINT)
                .color(1f, 0.86f, 0.62f)
                .intensity(400_000f)
                .falloff(7f)
                .position(0f, 0f, 0f)
                .build(engine, e)
            pointLightPool.add(e)
        }
        positions.forEachIndexed { i, p ->
            val e = pointLightPool[i]
            val inst = lm.getInstance(e)
            if (inst != 0) {
                lm.setPosition(inst, p.x, p.y, p.z)
                lm.setIntensity(inst, 400_000f)
            }
            if (!scene.hasEntity(e)) scene.addEntity(e)
        }
        for (i in positions.size until pointLightPool.size) {
            if (scene.hasEntity(pointLightPool[i])) scene.removeEntity(pointLightPool[i])
        }
    }

    private fun applyFrame() {
        applyLighting()
        applyHeatColors()
        applyFloorDim()
        // Orbiting changes only the camera. Walking hundreds of entities and
        // writing every transform on all 60 camera frames caused avoidable work
        // on Android tablets; visibility/explode state changes far less often.
        if (sceneStateDirty) {
            val tm = engine.transformManager
            for (tier in tierTransforms.indices) tierTransforms[tier][13] = tier * explode
            for ((e, grp) in groupOf) {
                val cls = classOf[e] ?: MatClass.Solid
                // Show the whole heating layer when Lämmitys is on: the coloured zone
                // patches (the "surface" that changes colour, like the web viewer) plus
                // the loop pipes and manifolds on top. triVisible already gates Heating
                // on showHeating + the shown floor.
                val visible = e !in hiddenEntities && triVisible(grp, cls, mode, showRoof, showWalls, showFurniture, showHeating)
                val inScene = scene.hasEntity(e)
                if (visible && !inScene) scene.addEntity(e) else if (!visible && inScene) scene.removeEntity(e)
                if (visible) {
                    val ti = tm.getInstance(e)
                    if (ti != 0) {
                        tm.setTransform(ti, tierTransforms[groupTier(grp).toInt()])
                    }
                }
            }
            sceneStateDirty = false
        }
        camera.lookAt(
            eye.x.toDouble(), eye.y.toDouble(), eye.z.toDouble(),
            target.x.toDouble(), target.y.toDouble(), target.z.toDouble(),
            0.0, 1.0, 0.0,
        )
    }

    fun destroy() {
        choreographer.removeFrameCallback(frameCallback)
        uiHelper.detach()
        asset?.let { assetLoader.destroyAsset(it) }
        asset = null
        ready = false
        materialProvider.destroyMaterials()
        for (e in pointLightPool) { engine.destroyEntity(e); EntityManager.get().destroy(e) }
        pointLightPool.clear()
        indirect?.let { engine.destroyIndirectLight(it) }
        engine.destroyEntity(sunEntity)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(sunEntity)
        EntityManager.get().destroy(cameraEntity)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        resourceLoader.destroy()
        assetLoader.destroy()
        engine.destroy()
    }
}
