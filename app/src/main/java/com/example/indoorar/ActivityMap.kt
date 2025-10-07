package com.example.indoorar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Pose
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.example.indoorar.tracking.SensorFusionTracker
import com.example.indoorar.ui.MinimapView
import io.github.sceneview.ar.ARSceneView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

class ActivityMap : BaseActivity() {

    // ---- AR / ARSceneView ----
    private lateinit var arSceneView: ARSceneView
    private var isArActive = false
    private var arInstallRequested = false
    private var lastArPose: Pose? = null
    private var arSupported: Boolean = false

    // Debug / axis inversion
    private var invertZAxisForAr = false
    private var autoAxisEvaluated = false
    private var lastArZForAxisEval: Float? = null
    private var lastSensorZForAxisEval: Float? = null

    // Rota
    private val routeNodes = mutableListOf<RoutePoint>()
    private val sphereHighlightDistance = 1.0 // placeholder (mantido se futuro highlight)

    // UI
    private lateinit var loadingText: TextView
    private lateinit var minimapView: MinimapView
    private lateinit var btnEscolherDestino: MaterialButton
    private lateinit var cardDestino: MaterialCardView
    private lateinit var recyclerDestino: RecyclerView
    private lateinit var btnLimparRota: MaterialButton
    private lateinit var tvDistancia: TextView
    private lateinit var cameraPreview: PreviewView
    private lateinit var tvPassos: TextView
    private lateinit var tvDebug: TextView // novo
    private var totalSteps: Int = 0
    private var lastHeadingRad: Float = 0f
    private var lastPitchRad: Float = 0f
    private var arRouteOverlay: com.example.indoorar.ui.ARRouteOverlayView? = null

    // ---- AR Anchors (rota 3D real no chão) ----
    private var floorY: Float? = null
    private val routeAnchors = mutableListOf<com.google.ar.core.Anchor>()
    private var built3DRouteForCurrentPath: Boolean = false
    private var mapWorldYawDelta: Float? = null
    private var mapWorldOffsetX: Float? = null
    private var mapWorldOffsetZ: Float? = null
    private var calibrationDone = false
    private val maxAnchors3D = 80
    private val arrow3DSpacingMeters = 0.25f // reduzido para ter mais setas
    // Modelo 3D de seta (GLB)
    private val arrowModelFile = "models/arrow.glb"
    private val arrowModelAssetUri = "file:///android_asset/models/arrow.glb"
    private val densify3DStepMeters = 0.30f
    private val continuousLineWidth = 0.18f
    private val continuousLineHeight = 0.02f
    // removido useContinuousExtrudedLine (não utilizado)
    private val routeSegmentAnchors = mutableListOf<com.google.ar.core.Anchor>()

    // Novos helpers / flags ausentes anteriormente
    private var debugArrowCreated: Boolean = false
    private val baseScale: Float = 1.0f

    // Verifica existência do asset da seta
    private fun hasArrowAsset(): Boolean = try { assets.open(arrowModelFile).close(); true } catch (_: Exception) { false }

    // Converte yaw (rad) para quaternion eixo Y (x,y,z,w)
    private fun yawToQuaternionY(yaw: Float): FloatArray {
        val half = yaw / 2f
        val s = kotlin.math.sin(half)
        val c = kotlin.math.cos(half)
        return floatArrayOf(0f, s, 0f, c)
    }

    // Obtém Session via reflexão a partir do arSceneView
    private fun obterArSessionRefletido(): com.google.ar.core.Session? {
        return try {
            arSceneView.javaClass.declaredFields.firstOrNull { f -> f.type.name.contains("Session", true) }?.let { f ->
                f.isAccessible = true
                (f.get(arSceneView) as? com.google.ar.core.Session)?.let { return it }
            }
            arSceneView.javaClass.methods.firstOrNull { m -> m.parameterCount == 0 && m.returnType.name.contains("Session", true) }?.let { m ->
                runCatching { m.invoke(arSceneView) as? com.google.ar.core.Session }.getOrNull()?.let { return it }
            }
            null
        } catch (_: Throwable) { null }
    }

    // Estima altura do piso (floorY). Idealmente usaria planos detectados; aqui aproxima pela altura da câmera - 1.5m
    private fun estimarFloorY(@Suppress("UNUSED_PARAMETER") session: com.google.ar.core.Session): Float? {
        val pose = lastArPose ?: return null
        // Altura média olhos/phone ~1.5m acima do chão
        return (pose.ty() - 1.5f)
            .coerceIn(-10f, 10f) // sanity clamp
    }

    // Cria segmento (fallback) caso não seja possível carregar seta GLB
    private fun criarNodeSegmentoExtrudado(anchor: com.google.ar.core.Anchor, length: Float, @Suppress("UNUSED_PARAMETER") isLast: Boolean) {
        runCatching {
            val arNodeCls = Class.forName("io.github.sceneview.ar.node.ArNode")
            val node = arNodeCls.getDeclaredConstructor().newInstance()
            arNodeCls.methods.firstOrNull { it.name.equals("setAnchor", true) && it.parameterTypes.size == 1 }?.invoke(node, anchor)
            val thickness = 0.06f
            val scaleZ = length.coerceAtMost(2.0f)
            arNodeCls.methods.firstOrNull { it.name.equals("setScale", true) && it.parameterTypes.size == 3 }?.let { m ->
                runCatching { m.invoke(node, thickness, thickness, scaleZ) }
            }
            // Qualquer método que indique cor/ material simplificado (best effort)
            val sceneObj = arSceneView.javaClass.methods.firstOrNull { it.name.lowercase() in listOf("getscene","scene") && it.parameterCount==0 }?.invoke(arSceneView)
            val addChildMethods = sceneObj?.javaClass?.methods?.filter { m -> m.name.lowercase().contains("addchild") && m.parameterTypes.size==1 }
            addChildMethods?.forEach { m -> runCatching { m.invoke(sceneObj, node); return } }
        }.onFailure {
            Log.w("IndoorAR","Falha criar segmento extrudado: ${it.message}")
        }
    }

    // Map id
    private var mapId: String? = null

    // Destinos / POIs
    private val destinos = mutableListOf<DestinoPoi>()
    private var destinoSelecionado: DestinoPoi? = null

    // Grafo
    private val graphNodes = mutableMapOf<String, GraphNode>()
    private val adjacency = mutableMapOf<String, MutableList<GraphEdge>>()
    private var graphLoaded = false

    // Rota
    private var currentPathNodeIds: List<String> = emptyList()
    private var cumulativeDistances: List<Double> = emptyList()
    private var totalDistance: Double = 0.0
    private var lastUserRecalcCheckPose: Pair<Float, Float>? = null
    private var routeStartSteps: Int? = null // baseline de passos quando rota inicia

    // Sensor fusion fallback
    private var sensorTracker: SensorFusionTracker? = null
    private var estX = 0f
    private var estZ = 0f
    private val reanchorNodeThresholdMeters = 0.8f
    private val stepLengthMeters = 0.7f
    private val mapNorthDegrees: Float = 0f

    // Location services
    private lateinit var settingsClient: SettingsClient
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val enableLocationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) proceedAfterLocationReady() else {
            Toast.makeText(this, "A localização é necessária para usar o mapa.", Toast.LENGTH_LONG).show(); finish()
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val camOk = perms[Manifest.permission.CAMERA] == true || hasCameraPermission()
        val actOk = (perms[Manifest.permission.ACTIVITY_RECOGNITION] == true || hasActivityRecognitionPermission())
        val locOk = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasLocationPermission()
        if (!camOk) { Toast.makeText(this, "Permissão de câmera necessária", Toast.LENGTH_LONG).show(); finish(); return@registerForActivityResult }
        if (!locOk) { Toast.makeText(this, "Permissão de localização necessária", Toast.LENGTH_LONG).show(); finish(); return@registerForActivityResult }
        if (!actOk) Toast.makeText(this, "Sem ACTIVITY_RECOGNITION: passos podem ser menos precisos.", Toast.LENGTH_LONG).show()
        ensureLocationServicesEnabled()
    }

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val value = res.data?.getStringExtra("QR_VALUE")
            val extracted = value?.let { extractMapId(it) }
            if (!extracted.isNullOrBlank()) { mapId = extracted; carregarMapa() } else {
                Toast.makeText(this, "QR inválido", Toast.LENGTH_SHORT).show(); finish()
            }
        } else { Toast.makeText(this, "Scan cancelado", Toast.LENGTH_SHORT).show(); finish() }
    }

    private lateinit var destinoAdapter: DestinoPoiAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)
        bindViews()
        setupRecycler()
        setupButtons()
        mapId = intent.getStringExtra("MAP_ID")?.let { extractMapId(it) }
        checkAndRequestPermissions()

        // Verificar suporte ARCore (sem criar Session manual para evitar tela preta)
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        arSupported = availability.isSupported
        if (arSupported) {
            Toast.makeText(this, "ARCore suportado", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Dispositivo sem ARCore. Modo minimapa.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Removido: arSceneView.resume() (método inexistente nesta versão)
        if (hasCameraPermission()) checkArCoreSupport()
    }

    override fun onPause() {
        super.onPause()
        stopArMode()
        sensorTracker?.stop()
        // Removido: arSceneView.pause()
    }

    // ---- Binding ----
    private fun bindViews() {
        loadingText = findViewById(R.id.textViewMap)
        minimapView = findViewById(R.id.minimap)
        btnEscolherDestino = findViewById(R.id.btnEscolherDestino)
        cardDestino = findViewById(R.id.cardDestino)
        recyclerDestino = findViewById(R.id.recyclerDestino)
        btnLimparRota = findViewById(R.id.btnLimparRota)
        tvDistancia = findViewById(R.id.tvDistancia)
        cameraPreview = findViewById(R.id.cameraPreview)
        arSceneView = findViewById(R.id.arSceneView)
        tvPassos = findViewById(R.id.tvPassos)
        arRouteOverlay = findViewById(R.id.arRouteOverlay)
        tvDebug = findViewById(R.id.tvDebug)
    }

    // ---- Permissions & Location ----
    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        if (!hasCameraPermission()) needed += Manifest.permission.CAMERA
        if (!hasActivityRecognitionPermission()) needed += Manifest.permission.ACTIVITY_RECOGNITION
        if (!hasLocationPermission()) needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (needed.isNotEmpty()) requestPermissions.launch(needed.toTypedArray()) else ensureLocationServicesEnabled()
    }

    private fun ensureLocationServicesEnabled() {
        if (!this::settingsClient.isInitialized) settingsClient = LocationServices.getSettingsClient(this)
        if (!this::fusedLocationClient.isInitialized) fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 2000L).setMinUpdateIntervalMillis(1000L).build()
        val settingsReq = LocationSettingsRequest.Builder().addLocationRequest(req).setAlwaysShow(true).build()
        settingsClient.checkLocationSettings(settingsReq)
            .addOnSuccessListener { proceedAfterLocationReady() }
            .addOnFailureListener { ex ->
                if (ex is ResolvableApiException) {
                    try { enableLocationLauncher.launch(IntentSenderRequest.Builder(ex.resolution).build()) }
                    catch (_: Exception) { Toast.makeText(this, "Ative a localização", Toast.LENGTH_LONG).show(); finish() }
                } else { Toast.makeText(this, "Localização desativada.", Toast.LENGTH_LONG).show(); finish() }
            }
    }

    private fun proceedAfterLocationReady() {
        checkArCoreSupport()
        ensureMapLoadedOrScan()
        if (arSupported && !isArActive) startArMode() // inicia câmera AR imediatamente
    }

    private fun ensureMapLoadedOrScan() {
        if (mapId.isNullOrBlank()) {
            val intentScan = Intent(this, ActivityScanQR::class.java).apply { putExtra("RETURN_RESULT", true) }
            scanLauncher.launch(intentScan)
        } else carregarMapa()
    }

    // ---- ARCore availability & ARSceneView ----
    private fun checkArCoreSupport() {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (availability.isTransient) {
            loadingText.visibility = View.VISIBLE
            loadingText.text = getString(R.string.verifying_arcore)
            loadingText.postDelayed({ checkArCoreSupport() }, 500)
            return
        }
        when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> { /* pronto para uso */ }
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD, ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> {
                try {
                    val status = ArCoreApk.getInstance().requestInstall(this, !arInstallRequested)
                    if (status == ArCoreApk.InstallStatus.INSTALLED) { /* agora instalado */ } else arInstallRequested = true
                } catch (_: Exception) { arSupported = false; initializeCameraFallback() }
            }
            else -> { arSupported = false; initializeCameraFallback() }
        }
    }

    private fun initializeCameraFallback() {
        if (isArActive) return
        arSceneView.visibility = View.GONE
        cameraPreview.visibility = View.VISIBLE
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().apply { setSurfaceProvider(cameraPreview.surfaceProvider) }
                provider.unbindAll(); provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                if (loadingText.isVisible) loadingText.visibility = View.GONE
            } catch (e: Exception) { Toast.makeText(this, "Falha CameraX: ${e.message}", Toast.LENGTH_LONG).show() }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---- Firestore loading ----
    private fun carregarMapa() {
        val id = mapId
        if (id.isNullOrBlank() || id.contains('/')) { Toast.makeText(this, "QR inválido", Toast.LENGTH_SHORT).show(); finish(); return }
        loadingText.visibility = View.VISIBLE
        val db = FirebaseFirestore.getInstance()
        val docRef = try { db.collection("mapas").document(id) } catch (_: IllegalArgumentException) { Toast.makeText(this, "ID inválido", Toast.LENGTH_SHORT).show(); finish(); return }
        docRef.get().addOnSuccessListener { doc ->
            if (!doc.exists()) { Toast.makeText(this, "Mapa não encontrado", Toast.LENGTH_LONG).show(); finish(); return@addOnSuccessListener }
            docRef.collection("formas").get().addOnSuccessListener { formasSnap ->
                docRef.collection("pois").get().addOnSuccessListener { poisSnap ->
                    docRef.collection("nodes").get().addOnSuccessListener { nodesSnap ->
                        docRef.collection("edges").get().addOnSuccessListener { edgesSnap ->
                            processarDadosMapa(formasSnap.documents, poisSnap.documents, nodesSnap.documents, edgesSnap.documents)
                        }.addOnFailureListener { falha() }
                    }.addOnFailureListener { falha() }
                }.addOnFailureListener { falha() }
            }.addOnFailureListener { falha() }
        }.addOnFailureListener { falha() }
    }

    private fun falha() { Toast.makeText(this, "Erro carregando mapa", Toast.LENGTH_LONG).show(); finish() }

    private fun processarDadosMapa(
        formas: List<DocumentSnapshot>,
        pois: List<DocumentSnapshot>,
        nodes: List<DocumentSnapshot>,
        edges: List<DocumentSnapshot>
    ) {
        var minX = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        formas.forEach { f ->
            val pos = f.get("posicao") as? List<*> ?: return@forEach
            val tam = f.get("tamanho") as? List<*> ?: return@forEach
            val x = (pos.getOrNull(0) as? Number)?.toFloat() ?: 0f
            val z = (pos.getOrNull(1) as? Number)?.toFloat() ?: 0f
            val w = (tam.getOrNull(1) as? Number)?.toFloat() ?: 0f
            val h = (tam.getOrNull(0) as? Number)?.toFloat() ?: 0f
            minX = min(minX, x); minZ = min(minZ, z)
            maxX = max(maxX, x + w); maxZ = max(maxZ, z + h)
        }
        pois.forEach { p ->
            val x = (p.get("x") as? Number)?.toFloat() ?: 0f
            val z = (p.get("y") as? Number)?.toFloat() ?: 0f
            minX = min(minX, x); minZ = min(minZ, z)
            maxX = max(maxX, x); maxZ = max(maxZ, z)
        }
        if (minX == Float.MAX_VALUE) { minX = 0f; minZ = 0f; maxX = 10f; maxZ = 10f }
        minimapView.setWorldBounds(minX, minZ, maxX, maxZ)

        try { minimapView.clearFormas() } catch (_: Exception) {}

        formas.forEach { f ->
            val pos = f.get("posicao") as? List<*> ?: return@forEach
            val tam = f.get("tamanho") as? List<*> ?: return@forEach
            val corHex = (f.getString("cor") ?: "#D9D9D9").replace("#", "")
            val r = corHex.chunked(2).getOrNull(0)?.toInt(16) ?: 0xD9
            val g = corHex.chunked(2).getOrNull(1)?.toInt(16) ?: 0xD9
            val b = corHex.chunked(2).getOrNull(2)?.toInt(16) ?: 0xD9
            val x = (pos.getOrNull(0) as? Number)?.toFloat() ?: 0f
            val z = (pos.getOrNull(1) as? Number)?.toFloat() ?: 0f
            val h = (tam.getOrNull(0) as? Number)?.toFloat() ?: 0.1f
            val w = (tam.getOrNull(1) as? Number)?.toFloat() ?: 0.1f
            val isWalkable = when (val any = f.get("isWalkable")) {
                is Boolean -> any
                is Number -> any.toInt() != 0
                else -> true
            }
            val corFinal = if (isWalkable) android.graphics.Color.BLACK else android.graphics.Color.rgb(r, g, b)
            minimapView.addForma(x, z, w, h, corFinal)
        }

        val oldSize = destinos.size
        destinos.clear()
        pois.forEach { p ->
            val x = (p.get("x") as? Number)?.toFloat() ?: 0f
            val z = (p.get("y") as? Number)?.toFloat() ?: 0f
            val iconRes = resolvePoiIconRes(p)
            val isStart = (p.get("isStartQR") as? Boolean) == true
            minimapView.addPoi(x, z, colorForPoiIconRes(iconRes), iconRes, isStart)
            val name = p.getString("name") ?: "POI"
            val id = p.getString("id") ?: (name + x + z)
            destinos += DestinoPoi(id, name, x, z, iconRes, isStart)
        }
        if (oldSize > 0) destinoAdapter.notifyItemRangeRemoved(0, oldSize)
        if (destinos.isNotEmpty()) destinoAdapter.notifyItemRangeInserted(0, destinos.size)
        btnEscolherDestino.isEnabled = destinos.isNotEmpty()

        graphNodes.clear(); adjacency.clear()
        nodes.forEach { n ->
            val id = n.getString("id") ?: return@forEach
            val x = (n.get("x") as? Number)?.toFloat() ?: return@forEach
            val z = (n.get("y") as? Number)?.toFloat() ?: return@forEach
            graphNodes[id] = GraphNode(id, x, z)
        }
        edges.forEach { e ->
            val from = e.getString("fromNodeId") ?: return@forEach
            val to = e.getString("toNodeId") ?: return@forEach
            val peso = (e.get("peso") as? Number)?.toDouble() ?: return@forEach
            adjacency.getOrPut(from) { mutableListOf() }.add(GraphEdge(from, to, peso))
            adjacency.getOrPut(to) { mutableListOf() }.add(GraphEdge(to, from, peso))
        }
        graphLoaded = graphNodes.isNotEmpty()

        loadingText.visibility = View.GONE
        minimapView.invalidate()

        try { minimapView.setRotateWithHeading(false) } catch (_: Exception) {}

        val startPoi = destinos.firstOrNull { it.isStart }
        val initX = startPoi?.x ?: graphNodes.values.firstOrNull()?.x ?: 0f
        val initZ = startPoi?.z ?: graphNodes.values.firstOrNull()?.z ?: 0f

        sensorTracker?.stop(); sensorTracker = null
        startSensorTracking(initX, initZ)
    }

    // ---- UI / Recycler ----
    private fun setupRecycler() {
        recyclerDestino.layoutManager = LinearLayoutManager(this)
        destinoAdapter = DestinoPoiAdapter(destinos) { poi ->
            cardDestino.visibility = View.GONE
            destinoSelecionado = poi
            if (!graphLoaded) { Toast.makeText(this, "Grafo não carregado", Toast.LENGTH_SHORT).show(); return@DestinoPoiAdapter }
            calcularRotaAStar(poi)
            if (arSupported) startArMode() else initializeCameraFallback()
        }
        recyclerDestino.adapter = destinoAdapter
    }

    private fun setupButtons() {
        btnEscolherDestino.setOnClickListener {
            if (destinos.isEmpty()) Toast.makeText(this, "Nenhum destino disponível", Toast.LENGTH_SHORT).show() else
                cardDestino.visibility = if (cardDestino.isVisible) View.GONE else View.VISIBLE
        }
        btnLimparRota.setOnClickListener { limparRota() }
    }

    // ---- Helper icons/colors ----
    private fun colorForPoiIconRes(iconRes: Int): Int = when (iconRes) {
        R.drawable.ic_door_azul, R.drawable.ic_banheiro_azul -> 0xFF32357A.toInt()
        R.drawable.ic_stairs_azul -> 0xFFFF9800.toInt()
        R.drawable.ic_extintor_azul -> 0xFFF44336.toInt()
        R.drawable.ic_elevator_azul -> 0xFF4CAF50.toInt()
        else -> 0xFF32357A.toInt()
    }

    private fun resolvePoiIconRes(p: DocumentSnapshot): Int {
        val iconName = p.getString("iconName") ?: p.getString("icon")
        val iconResNum = (p.get("iconRes") as? Number)?.toInt()
        val byName = when (iconName?.lowercase()) {
            "porta", "door" -> R.drawable.ic_door_azul
            "banheiro", "bathroom" -> R.drawable.ic_banheiro_azul
            "escada", "stairs" -> R.drawable.ic_stairs_azul
            "elevador", "elevator" -> R.drawable.ic_elevator_azul
            "extintor", "fire_extinguisher" -> R.drawable.ic_extintor_azul
            "circulo", "circle" -> R.drawable.ic_circle_azul
            "quadrado", "square" -> R.drawable.ic_square_azul
            "triangulo", "triangle" -> R.drawable.ic_triangle_azul
            else -> null
        }
        if (byName != null) return byName
        if (iconResNum != null) return iconResNum
        return R.drawable.ic_poi_default
    }

    // ---- Rota / A* ----
    private fun calcularRotaAStar(dest: DestinoPoi) {
        val startNodeId = destinos.firstOrNull { it.isStart }?.id ?: run {
            val camPose = lastArPose
            when {
                camPose != null -> encontrarNodeMaisProximo(camPose.tx(), camPose.tz())
                sensorTracker != null -> encontrarNodeMaisProximo(estX, estZ)
                graphNodes.isNotEmpty() -> graphNodes.keys.first()
                else -> { Toast.makeText(this, "Sem referência de posição.", Toast.LENGTH_SHORT).show(); return }
            }
        }
        val goalNodeId = dest.id
        if (!graphNodes.containsKey(goalNodeId)) { Toast.makeText(this, "Destino sem nó correspondente", Toast.LENGTH_SHORT).show(); return }
        val path = aStar(startNodeId, goalNodeId) ?: run { Toast.makeText(this, "Sem rota encontrada", Toast.LENGTH_SHORT).show(); return }
        currentPathNodeIds = path
        built3DRouteForCurrentPath = false
        calibrationDone = false
        gerarCumulativeDistances()
        totalDistance = if (cumulativeDistances.isNotEmpty()) cumulativeDistances.last() else 0.0
        tvDistancia.text = getString(R.string.distance_meters, totalDistance)
        routeStartSteps = totalSteps
        val ptsNodes = path.mapNotNull { graphNodes[it] }.map { it.x to it.z }
        val ptsDensificados = densificarRota(ptsNodes)
        minimapView.setRoute(ptsDensificados)
        arRouteOverlay?.setRoute(ptsDensificados)
        arRouteOverlay?.visibility = View.GONE
        val pose = lastArPose
        val (ux, uz) = if (pose != null) pose.tx() to pose.tz() else (estX to estZ)
        atualizarDistanciaRestantePrecisa(ux, uz)
        if (isArActive && lastArPose != null) tentarConstruirRota3D(ptsDensificados)
    }

    private var last3DRouteProgressDistance = 0.0

    private val densifyStepMeters = 0.5f
    private fun densificarRota(pontos: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (pontos.size < 2) return pontos
        val out = mutableListOf<Pair<Float,Float>>()
        for (i in 0 until pontos.size - 1) {
            val (ax, az) = pontos[i]; val (bx, bz) = pontos[i+1]
            out += ax to az
            val dx = bx - ax; val dz = bz - az
            val dist = kotlin.math.sqrt(dx*dx + dz*dz)
            if (dist > densifyStepMeters) {
                val steps = (dist / densifyStepMeters).toInt()
                if (steps > 1) {
                    val stepX = dx / steps; val stepZ = dz / steps
                    for (s in 1 until steps) out += (ax + stepX * s) to (az + stepZ * s)
                }
            }
        }
        out += pontos.last(); return out
    }

    private fun densificarRota3D(pontos: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (pontos.size < 2) return pontos
        val out = mutableListOf<Pair<Float, Float>>()
        for (i in 0 until pontos.size - 1) {
            val (ax, az) = pontos[i]; val (bx, bz) = pontos[i+1]
            out += ax to az
            val dx = bx - ax; val dz = bz - az
            val dist = kotlin.math.sqrt(dx*dx + dz*dz)
            if (dist > densify3DStepMeters) {
                val steps = (dist / densify3DStepMeters).toInt()
                if (steps > 1) {
                    val stepX = dx / steps; val stepZ = dz / steps
                    for (s in 1 until steps) out += (ax + stepX * s) to (az + stepZ * s)
                }
            }
        }
        out += pontos.last(); return out
    }

    private fun gerarCumulativeDistances() {
        val list = mutableListOf<Double>(); var acc = 0.0
        currentPathNodeIds.forEachIndexed { i, id ->
            if (i == 0) list += 0.0 else {
                val a = graphNodes[currentPathNodeIds[i-1]]!!; val b = graphNodes[id]!!
                acc += hypot((b.x - a.x).toDouble(), (b.z - a.z).toDouble()); list += acc
            }
        }
        cumulativeDistances = list
    }

    private fun atualizarDistanciaRestantePrecisa(ux: Float, uz: Float) {
        if (currentPathNodeIds.size < 2) return
        var melhorDistAoSegmento = Double.MAX_VALUE
        var distanciaPercorridaProjetada = 0.0
        var acumuladoAnterior = 0.0
        for (i in 0 until currentPathNodeIds.size - 1) {
            val a = graphNodes[currentPathNodeIds[i]] ?: continue
            val b = graphNodes[currentPathNodeIds[i+1]] ?: continue
            val ax = a.x; val az = a.z; val bx = b.x; val bz = b.z
            val vx = bx - ax; val vz = bz - az
            val wx = ux - ax; val wz = uz - az
            val len2 = vx*vx + vz*vz
            if (len2 == 0f) { acumuladoAnterior += hypot(vx.toDouble(), vz.toDouble()); continue }
            val t = ((wx*vx + wz*vz) / len2).coerceIn(0f,1f)
            val projX = ax + vx * t
            val projZ = az + vz * t
            val dist = hypot((projX - ux).toDouble(), (projZ - uz).toDouble())
            if (dist < melhorDistAoSegmento) {
                melhorDistAoSegmento = dist
                val segLen = hypot(vx.toDouble(), vz.toDouble())
                distanciaPercorridaProjetada = acumuladoAnterior + segLen * t
            }
            acumuladoAnterior += hypot(vx.toDouble(), vz.toDouble())
        }
        val restante = (totalDistance - distanciaPercorridaProjetada).coerceAtLeast(0.0)
        if (restante < 1.0) {
            tvDistancia.text = getString(R.string.distance_arrived)
        } else {
            tvDistancia.text = getString(R.string.distance_meters, restante)
        }
    }

    private fun tentarRecalcularSeDesviou(ux: Float, uz: Float) {
        if (destinoSelecionado == null || currentPathNodeIds.isEmpty()) return
        val last = lastUserRecalcCheckPose
        if (last != null) {
            val mov = hypot((ux - last.first).toDouble(), (uz - last.second).toDouble())
            if (mov < 0.7) return
        }
        lastUserRecalcCheckPose = ux to uz
        val dist = distanciaParaRota(ux, uz)
        if (dist > 2.0) destinoSelecionado?.let { calcularRotaAStar(it) }
    }

    private fun atualizarDestaqueEsferas(@Suppress("UNUSED_PARAMETER") ux: Float, @Suppress("UNUSED_PARAMETER") uz: Float) { /* Placeholder visual */ }

    private fun distanciaParaRota(x: Float, z: Float): Double {
        if (currentPathNodeIds.size < 2) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        for (i in 0 until currentPathNodeIds.size - 1) {
            val a = graphNodes[currentPathNodeIds[i]] ?: continue
            val b = graphNodes[currentPathNodeIds[i+1]] ?: continue
            val ax = a.x; val az = a.z; val bx = b.x; val bz = b.z
            val vx = bx - ax; val vz = bz - az; val wx = x - ax; val wz = z - az
            val len2 = vx*vx + vz*vz
            val t = if (len2 == 0f) 0f else ((wx*vx + wz*vz) / len2).coerceIn(0f,1f)
            val projX = ax + vx * t
            val projZ = az + vz * t
            val d = hypot((projX - x).toDouble(), (projZ - z).toDouble())
            if (d < best) best = d
        }
        return best
    }

    private fun desenharEsferasRota(pontos: List<Pair<Float,Float>>) {
        routeNodes.clear()
        pontos.forEach { (x,z) -> routeNodes += RoutePoint(x,z) }
        arRouteOverlay?.setRoute(pontos)
    }

    private fun limparEsferas() { routeNodes.clear() }

    private fun limparRota() {
        stopArMode()
        limparEsferas(); currentPathNodeIds = emptyList(); cumulativeDistances = emptyList(); totalDistance = 0.0
        minimapView.clearRoute(); tvDistancia.text = getString(R.string.distance_placeholder)
        arRouteOverlay?.clearRoute()
        routeStartSteps = null
        limparAnchors3D()
        last3DRouteProgressDistance = 0.0
    }

    private fun encontrarNodeMaisProximo(x: Float, z: Float): String {
        var bestId = graphNodes.keys.first(); var bestDist = Double.MAX_VALUE
        graphNodes.forEach { (id, n) ->
            val d = hypot((n.x - x).toDouble(), (n.z - z).toDouble())
            if (d < bestDist) { bestDist = d; bestId = id }
        }
        return bestId
    }

    private fun aStar(startId: String, goalId: String): List<String>? {
        if (!graphNodes.containsKey(startId) || !graphNodes.containsKey(goalId)) return null
        data class Rec(var id: String, var g: Double, var f: Double, var parent: String?)
        val open = java.util.PriorityQueue<Rec>(compareBy { it.f })
        val all = mutableMapOf<String, Rec>()
        val closed = mutableSetOf<String>()
        fun h(a: GraphNode, b: GraphNode) = hypot((a.x - b.x).toDouble(), (a.z - b.z).toDouble())
        val startRec = Rec(startId, 0.0, h(graphNodes[startId]!!, graphNodes[goalId]!!), null)
        all[startId] = startRec; open.add(startRec)
        while (open.isNotEmpty()) {
            val cur = open.poll() ?: break
            if (cur.id == goalId) {
                val path = mutableListOf<String>()
                var c: Rec? = cur
                while (c != null) { path += c.id; c = c.parent?.let { all[it] } }
                return path.reversed()
            }
            closed += cur.id
            adjacency[cur.id]?.forEach { e ->
                if (e.to in closed) return@forEach
                val tentativeG = cur.g + e.peso
                val r = all[e.to]
                if (r == null || tentativeG < r.g) {
                    val f = tentativeG + h(graphNodes[e.to]!!, graphNodes[goalId]!!)
                    if (r == null) {
                        val nr = Rec(e.to, tentativeG, f, cur.id); all[e.to] = nr; open.add(nr)
                    } else { r.g = tentativeG; r.f = f; r.parent = cur.id; open.add(r) }
                }
            }
        }
        return null
    }

    private fun extractMapId(raw: String): String? {
        val s = raw.trim(); if (!s.contains('/')) return s
        val idxParam = s.indexOf("mapId=")
        if (idxParam >= 0) {
            val sub = s.substring(idxParam + 6)
            val end = listOf('&', '#', '?', '/').map { ch -> sub.indexOf(ch) }.filter { it >= 0 }.minOrNull() ?: sub.length
            val id = sub.substring(0, end); if (id.isNotBlank()) return id
        }
        val token = "/mapas/"; val idx = s.indexOf(token)
        if (idx >= 0) {
            val sub = s.substring(idx + token.length)
            val end = sub.indexOf('/')
            val id = if (end >= 0) sub.substring(0, end) else sub
            if (id.isNotBlank()) return id
        }
        val parts = s.split('/').filter { it.isNotBlank() }
        return parts.lastOrNull()
    }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasActivityRecognitionPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    private fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startSensorTracking(initialX: Float, initialZ: Float) {
        if (sensorTracker != null) return
        val sm = getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val hasStep = (sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_DETECTOR) != null) || (sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER) != null)
        if (!hasStep) Toast.makeText(this, "Sem sensor dedicado de passos. Usando fallback.", Toast.LENGTH_LONG).show()
        sensorTracker = SensorFusionTracker(
            context = this,
            mapNorthDegrees = mapNorthDegrees,
            stepLengthMeters = stepLengthMeters,
            onPosition = { x, z, heading ->
                lastHeadingRad = heading
                estX = x; estZ = z
                val (ux, uz) = if (isArActive && lastArPose != null) {
                    val pose = lastArPose!!
                    pose.tx() to (if (invertZAxisForAr) -pose.tz() else pose.tz())
                } else x to z
                minimapView.updateUserPose(ux, uz, lastHeadingRad)
                atualizarDistanciaRestantePrecisa(ux, uz)
                tentarRecalcularSeDesviou(ux, uz)
                atualizarDestaqueEsferas(ux, uz)
                if (isArActive && currentPathNodeIds.size > 1) {
                    atualizarRota3DRestante(ux, uz)
                }
                if (!built3DRouteForCurrentPath && isArActive && currentPathNodeIds.size > 1) {
                    val ptsNodes = currentPathNodeIds.mapNotNull { graphNodes[it] }.map { it.x to it.z }
                    tentarConstruirRota3D(densificarRota(ptsNodes))
                }
                updateDebug(lastArPose, "sensor")
            },
            mapMatch = { x, z -> projectToNearestEdge(x, z) },
            reanchorCheck = { x, z -> checkReanchorToNode(x, z) },
            onStep = { steps ->
                totalSteps = steps
                val rel = routeStartSteps?.let { (steps - it).coerceAtLeast(0) }
                tvPassos.text = if (rel != null) "Passos: $steps (rota: $rel)" else "Passos: $steps"
            }
        ).also { it.start(initialX, initialZ) }
    }
    private fun stopSensorTracking() { sensorTracker?.stop(); sensorTracker = null }

    private val arFrameUpdater = object : Runnable {
        override fun run() {
            if (!isArActive) return
            updateArCameraPose()
            arSceneView.postOnAnimation(this)
        }
    }

    private fun startArMode() {
        if (!arSupported) return
        if (isArActive) return
        try {
            arSceneView.visibility = View.VISIBLE
            cameraPreview.visibility = View.GONE
            isArActive = true
            debugArrowCreated = false
            disableArVisualDebug()
            updateArCameraPose()
            if (currentPathNodeIds.size > 1) {
                calibrationDone = false
                val ptsNodes = currentPathNodeIds.mapNotNull { graphNodes[it] }.map { it.x to it.z }
                val dens = densificarRota(ptsNodes)
                tentarConstruirRota3D(dens)
            }
            arSceneView.removeCallbacks(arFrameUpdater)
            arSceneView.postOnAnimation(arFrameUpdater)
            Toast.makeText(this, "AR ativado", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao iniciar AR: ${e.message}", Toast.LENGTH_LONG).show()
            isArActive = false
            initializeCameraFallback()
        }
    }

    private fun disableArVisualDebug() {
        runCatching {
            arSceneView.javaClass.methods.filter { m ->
                m.name.lowercase().let { it.startsWith("set") && it.contains("point") && m.parameterTypes.size == 1 && (m.parameterTypes[0] == Boolean::class.java || m.parameterTypes[0] == java.lang.Boolean.TYPE) }
            }.forEach { m -> runCatching { m.invoke(arSceneView, false) } }
        }
        runCatching {
            arSceneView.javaClass.declaredFields.firstOrNull { it.name.contains("plane", true) }?.let { f ->
                f.isAccessible = true; val planeObj = f.get(arSceneView)
                val visField = planeObj?.javaClass?.declaredFields?.firstOrNull { it.name.equals("isVisible", true) }
                visField?.let { vf -> vf.isAccessible = true; runCatching { vf.setBoolean(planeObj, false) } }
                planeObj?.javaClass?.methods?.firstOrNull { it.name.equals("setVisible", true) && it.parameterTypes.size==1 }?.let { m -> runCatching { m.invoke(planeObj, false) } }
            }
        }
        runCatching {
            arSceneView.javaClass.declaredFields.firstOrNull { it.name.contains("point", true) }?.let { f ->
                f.isAccessible = true; val pcObj = f.get(arSceneView)
                pcObj?.javaClass?.methods?.filter { it.name.lowercase().contains("visible") && it.parameterTypes.size==1 }?.forEach { m -> runCatching { m.invoke(pcObj, false) } }
            }
        }
    }

    private fun stopArMode() {
        if (!isArActive) return
        arSceneView.visibility = View.GONE
        cameraPreview.visibility = View.VISIBLE
        isArActive = false
        arSceneView.removeCallbacks(arFrameUpdater)
        limparAnchors3D()
    }

    private fun updateArCameraPose() {
        val pose = getCurrentArPose()
        if (pose != null) {
            val zRaw = pose.tz()
            if (!autoAxisEvaluated) {
                if (lastArZForAxisEval == null) {
                    lastArZForAxisEval = zRaw
                    lastSensorZForAxisEval = estZ
                } else {
                    val dzAr = zRaw - (lastArZForAxisEval ?: 0f)
                    val dzSensor = estZ - (lastSensorZForAxisEval ?: estZ)
                    if (kotlin.math.abs(dzAr) > 0.15f && kotlin.math.abs(dzSensor) > 0.15f) {
                        if (dzAr.sign != dzSensor.sign) invertZAxisForAr = true
                        autoAxisEvaluated = true
                    }
                }
            }
            lastArPose = pose
            val yaw = extractYaw(pose)
            val pitch = extractPitch(pose)
            lastHeadingRad = yaw
            lastPitchRad = pitch
            val x = pose.tx()
            val z = if (invertZAxisForAr) -pose.tz() else pose.tz()
            estX = x; estZ = z
            minimapView.updateUserPose(x, z, yaw)
            atualizarDistanciaRestantePrecisa(x, z)
            if (!built3DRouteForCurrentPath && currentPathNodeIds.size > 1) {
                val ptsNodes = currentPathNodeIds.mapNotNull { graphNodes[it] }.map { it.x to it.z }
                tentarConstruirRota3D(densificarRota(ptsNodes))
            } else if (currentPathNodeIds.size > 1) {
                atualizarRota3DRestante(x, z)
            }
        } else {
            minimapView.updateUserPose(estX, estZ, lastHeadingRad)
            updateDebug(null, "poseNull")
        }
    }

    private fun getCurrentArPose(): Pose? {
        return try {
            val viewCls = arSceneView.javaClass
            val methods = viewCls.methods
            var frameObj: Any? = null
            for (m in methods) {
                if (m.parameterCount == 0) {
                    val name = m.name.lowercase()
                    if (name.contains("frame") || name == "getcurrentframe") {
                        try {
                            val r = m.invoke(arSceneView)
                            if (r != null && r.javaClass.name.lowercase().contains("frame")) {
                                frameObj = r; break
                            }
                        } catch (_: Throwable) { }
                    }
                }
            }
            if (frameObj == null) return null
            var cameraObj: Any? = null
            for (fm in frameObj.javaClass.methods) {
                if (fm.parameterCount == 0) {
                    val nm = fm.name.lowercase()
                    if (nm == "getcamera" || nm == "camera") {
                        try {
                            val r = fm.invoke(frameObj)
                            if (r != null) { cameraObj = r; break }
                        } catch (_: Throwable) {}
                    }
                }
            }
            if (cameraObj == null) return null
            for (cm in cameraObj.javaClass.methods) {
                if (cm.parameterCount == 0) {
                    val nm = cm.name.lowercase()
                    if (nm == "getpose" || nm == "pose") {
                        try {
                            val r = cm.invoke(cameraObj)
                            if (r is Pose) return r
                        } catch (_: Throwable) {}
                    }
                }
            }
            null
        } catch (_: Throwable) { null }
    }

    private fun extractYaw(pose: Pose): Float {
        val qw = pose.qw(); val qx = pose.qx(); val qy = pose.qy(); val qz = pose.qz()
        val sinyCosp = 2f * (qw * qy + qx * qz)
        val cosyCosp = 1f - 2f * (qy * qy + qz * qz)
        return kotlin.math.atan2(sinyCosp, cosyCosp)
    }

    private fun extractPitch(pose: Pose): Float {
        val qw = pose.qw(); val qx = pose.qx(); val qy = pose.qy(); val qz = pose.qz()
        val sinp = 2f * (qw * qx - qy * qz)
        return if (kotlin.math.abs(sinp) >= 1f) (Math.PI.toFloat()/2f) * kotlin.math.sign(sinp) else kotlin.math.asin(sinp)
    }

    private fun projectToNearestEdge(x: Float, z: Float): Pair<Float, Float> {
        if (currentPathNodeIds.size < 2) return x to z
        var bestDist = Float.MAX_VALUE
        var bestX = x
        var bestZ = z
        for (i in 0 until currentPathNodeIds.size - 1) {
            val a = graphNodes[currentPathNodeIds[i]] ?: continue
            val b = graphNodes[currentPathNodeIds[i + 1]] ?: continue
            val ax = a.x; val az = a.z; val bx = b.x; val bz = b.z
            val vx = bx - ax; val vz = bz - az
            val wx = x - ax; val wz = z - az
            val len2 = vx * vx + vz * vz
            if (len2 <= 0f) continue
            val t = ((wx * vx + wz * vz) / len2).coerceIn(0f, 1f)
            val px = ax + vx * t
            val pz = az + vz * t
            val dx = px - x; val dz = pz - z
            val d2 = dx * dx + dz * dz
            if (d2 < bestDist) {
                bestDist = d2
                bestX = px
                bestZ = pz
            }
        }
        return if (bestDist < 1f) bestX to bestZ else x to z
    }

    private fun checkReanchorToNode(x: Float, z: Float): Pair<Boolean, Pair<Float, Float>?> {
        var closest: GraphNode? = null
        var best = Float.MAX_VALUE
        graphNodes.values.forEach { n ->
            val dx = n.x - x; val dz = n.z - z
            val d2 = dx * dx + dz * dz
            if (d2 < best) { best = d2; closest = n }
        }
        if (closest != null && best < (reanchorNodeThresholdMeters * reanchorNodeThresholdMeters)) {
            return true to (closest!!.x to closest!!.z) // kept closest!! due to smart cast limitations
        }
        return false to null
    }

    private fun updateDebug(pose: Pose?, origin: String) {
        if (!this::tvDebug.isInitialized) return
        val yawDeg = Math.toDegrees(lastHeadingRad.toDouble()).toInt()
        val pitchDeg = Math.toDegrees(lastPitchRad.toDouble()).toInt()
        val poseStr = if (pose != null) {
            val x = pose.tx(); val y = pose.ty(); val zRaw = pose.tz(); val zAdj = if (invertZAxisForAr) -zRaw else zRaw
            "AR(x=%.2f z=%.2f rawZ=%.2f y=%.2f)".format(x, zAdj, zRaw, y)
        } else "AR(null)"
        val sensorStr = "Sens(x=%.2f z=%.2f)".format(estX, estZ)
        val stepsStr = "Steps=$totalSteps"
        val flags = "invZ=$invertZAxisForAr arAct=$isArActive hasPose=${pose!=null}" + (if (!autoAxisEvaluated) " evalPending" else "")
        tvDebug.text = listOf(origin, poseStr, sensorStr, "yaw=$yawDeg° pitch=$pitchDeg°", stepsStr, flags).joinToString("\n")
    }

    private fun tentarConstruirRota3D(pontos: List<Pair<Float, Float>>) {
        if (!isArActive || lastArPose == null) return
        if (pontos.size < 2) return
        if (!calibrationDone && lastArPose != null) calibrarMapToWorld(lastArPose!!)
        last3DRouteProgressDistance = 0.0
        construirRota3DParaPontos(pontos)
    }

    private data class ProgressProjection(
        val projX: Float,
        val projZ: Float,
        val distanceAlong: Double,
        val segmentIndex: Int,
        val t: Float
    )

    private fun projetarProgressoNaRota(ux: Float, uz: Float): ProgressProjection? {
        if (currentPathNodeIds.size < 2) return null
        var melhorDist = Double.MAX_VALUE
        var melhorProjX = ux
        var melhorProjZ = uz
        var melhorDistAlong = 0.0
        var acumuladoAnterior = 0.0
        var melhorSegmentIndex = 0
        var melhorT = 0f
        for (i in 0 until currentPathNodeIds.size - 1) {
            val a = graphNodes[currentPathNodeIds[i]] ?: continue
            val b = graphNodes[currentPathNodeIds[i+1]] ?: continue
            val ax = a.x; val az = a.z; val bx = b.x; val bz = b.z
            val vx = bx - ax; val vz = bz - az
            val len = kotlin.math.sqrt(vx*vx + vz*vz)
            if (len < 1e-4f) { acumuladoAnterior += len; continue }
            val wx = ux - ax; val wz = uz - az
            val tRaw = (wx*vx + wz*vz) / (len*len)
            val t = tRaw.coerceIn(0f,1f)
            val px = ax + vx * t
            val pz = az + vz * t
            val d = hypot((px - ux).toDouble(), (pz - uz).toDouble())
            if (d < melhorDist) {
                melhorDist = d
                melhorProjX = px
                melhorProjZ = pz
                melhorDistAlong = acumuladoAnterior + len * t
                melhorSegmentIndex = i
                melhorT = t
            }
            acumuladoAnterior += len
        }
        return ProgressProjection(melhorProjX, melhorProjZ, melhorDistAlong, melhorSegmentIndex, melhorT)
    }

    private fun atualizarRota3DRestante(ux: Float, uz: Float) {
        if (!isArActive || lastArPose == null) return
        if (currentPathNodeIds.size < 2) return
        val proj = projetarProgressoNaRota(ux, uz) ?: return
        // Só reconstruir se avançou pelo menos 0.5m desde última materialização
        if (proj.distanceAlong - last3DRouteProgressDistance < 0.50) return
        last3DRouteProgressDistance = proj.distanceAlong
        val restante = totalDistance - proj.distanceAlong
        if (restante < 0.8) { // Considera chegada
            limparAnchors3D(); return
        }
        val rem = mutableListOf<Pair<Float,Float>>()
        rem.add(proj.projX to proj.projZ)
        for (i in (proj.segmentIndex + 1) until currentPathNodeIds.size) {
            val node = graphNodes[currentPathNodeIds[i]] ?: continue
            rem.add(node.x to node.z)
        }
        if (rem.size < 2) { limparAnchors3D(); return }
        construirRota3DParaPontos(rem)
    }

    private fun construirRota3DParaPontos(pontosMapa: List<Pair<Float,Float>>) {
        try {
            val session = obterArSessionRefletido() ?: return
            if (!calibrationDone && lastArPose != null) calibrarMapToWorld(lastArPose!!)
            if (floorY == null) floorY = estimarFloorY(session) ?: (lastArPose?.ty()?.minus(1.5f) ?: 0f)
            val y = floorY ?: 0f
            val yawDelta = mapWorldYawDelta ?: 0f
            val cosY = kotlin.math.cos(yawDelta)
            val sinY = kotlin.math.sin(yawDelta)
            val offX = mapWorldOffsetX ?: 0f
            val offZ = mapWorldOffsetZ ?: 0f
            val dens = densificarRota3D(pontosMapa)
            limparAnchors3D()
            var created = 0
            val wantArrows = hasArrowAsset()
            var accDist = 0f
            var lastPlacedDist = 0f
            for (i in 0 until dens.size - 1) {
                if (created >= maxAnchors3D) break
                val (mx, mz) = dens[i]
                val (nx, nz) = dens[i + 1]
                val rx1 = mx * cosY - mz * sinY + offX
                val rz1 = mx * sinY + mz * cosY + offZ
                val rx2 = nx * cosY - nz * sinY + offX
                val rz2 = nx * sinY + nz * cosY + offZ
                val dx = rx2 - rx1; val dz = rz2 - rz1
                val segLen = kotlin.math.sqrt(dx*dx + dz*dz)
                if (segLen < 0.05f) continue
                val midX = (rx1 + rx2) * 0.5f
                val midZ = (rz1 + rz2) * 0.5f
                val heading = kotlin.math.atan2(dx.toDouble(), dz.toDouble()).toFloat()
                accDist += segLen
                val isLastSegment = i == dens.size - 2
                val forceFirst = created == 0
                val place = if (wantArrows) forceFirst || isLastSegment || (accDist - lastPlacedDist) >= arrow3DSpacingMeters else true
                if (!place) continue
                if (wantArrows) lastPlacedDist = accDist
                val quat = yawToQuaternionY(heading)
                val pose = Pose(floatArrayOf(midX, y, midZ), quat)
                val anchor = session.createAnchor(pose)
                routeSegmentAnchors.add(anchor)
                val usedArrow = if (wantArrows) tryCreateArrowNodeViaReflection(anchor, heading, isLastSegment, baseScale) else false
                if (!usedArrow) criarNodeSegmentoExtrudado(anchor, segLen, isLastSegment)
                created++
            }
            built3DRouteForCurrentPath = created > 0
            if (!built3DRouteForCurrentPath) {
                Log.w("IndoorAR","Nenhuma seta 3D criada. Verifique arrow.glb ou path. Criando seta de debug.")
                spawnDebugArrowAtCamera()
            }
        } catch (e: Exception) {
            Log.w("IndoorAR","Falha construir rota 3D: ${e.message}")
            spawnDebugArrowAtCamera()
        }
    }

    private fun spawnDebugArrowAtCamera() {
        if (debugArrowCreated) return
        val pose = lastArPose ?: return
        val session = obterArSessionRefletido() ?: return
        try {
            val forwardZ = 1.0f // 1m à frente
            val dx = 0f
            val dz = if (invertZAxisForAr) -forwardZ else forwardZ
            val camX = pose.tx(); val camZ = if (invertZAxisForAr) -pose.tz() else pose.tz(); val camY = pose.ty()
            val arrowX = camX + dx
            val arrowZ = camZ + dz
            val y = (floorY ?: (camY - 1.5f)) + 0.05f
            val heading = extractYaw(pose)
            val quat = yawToQuaternionY(heading)
            val anchorPose = Pose(floatArrayOf(arrowX, y, arrowZ), quat)
            val anchor = session.createAnchor(anchorPose)
            routeSegmentAnchors.add(anchor)
            val ok = tryCreateArrowNodeViaReflection(anchor, heading, true, 1.0f)
            Log.d("IndoorAR","Debug arrow criada em (${arrowX.format2()}, ${y.format2()}, ${arrowZ.format2()}) ok=$ok")
            debugArrowCreated = true
        } catch (e: Exception) {
            Log.w("IndoorAR","Falha criar debug arrow: ${e.message}")
        }
    }

    private fun Float.format2(): String = String.format("%.2f", this)

    private fun tryCreateArrowNodeViaReflection(anchor: com.google.ar.core.Anchor, heading: Float, isLast: Boolean, forcedScale: Float? = null): Boolean {
        if (!hasArrowAsset()) return false
        try {
            val arModelCls = Class.forName("io.github.sceneview.ar.node.ArModelNode")
            val modelNode = arModelCls.getDeclaredConstructor().newInstance()
            arModelCls.methods.firstOrNull { it.name.equals("setAnchor", true) && it.parameterTypes.size == 1 }?.invoke(modelNode, anchor)
            val base = forcedScale ?: if (isLast) 0.40f else 0.28f
            arModelCls.methods.firstOrNull { it.name.equals("setScale", true) && it.parameterTypes.size == 3 }?.let { m ->
                try { m.invoke(modelNode, base, base, base) } catch (_: Throwable) {}
            }
            arModelCls.methods.firstOrNull { it.name.equals("setScale", true) && it.parameterTypes.size == 1 && it.parameterTypes[0]==FloatArray::class.java }?.let { m ->
                try { m.invoke(modelNode, floatArrayOf(base, base, base)) } catch (_: Throwable) {}
            }
            val yawDeg = Math.toDegrees(heading.toDouble()).toFloat()
            arModelCls.methods.firstOrNull { it.name.equals("setRotation", true) && it.parameterTypes.size==3 }?.let { m ->
                try { m.invoke(modelNode, 0f, yawDeg, 0f) } catch (_: Throwable) {}
            }
            val candidates = listOf("loadModelGlbAsync", "loadModelGlb", "loadModel", "loadGlb")
            var invoked = false
            val assetPaths = listOf(arrowModelAssetUri, arrowModelFile, "/android_asset/$arrowModelFile")
            for (nm in candidates) {
                if (invoked) break
                val mList = arModelCls.methods.filter { it.name == nm }
                for (m in mList) {
                    for (p in assetPaths) {
                        try {
                            when (m.parameterTypes.size) {
                                1 -> if (m.parameterTypes[0]==String::class.java) { m.invoke(modelNode, p); invoked = true }
                                2 -> if (m.parameterTypes[0]==String::class.java) { m.invoke(modelNode, p, base); invoked = true }
                                3 -> if (m.parameterTypes[0]==String::class.java) { m.invoke(modelNode, p, base, true); invoked = true }
                            }
                        } catch (_: Throwable) {}
                        if (invoked) break
                    }
                    if (invoked) break
                }
            }
            if (!invoked) {
                arModelCls.methods.filter { it.name.lowercase().contains("load") }.forEach { m ->
                    if (invoked) return@forEach
                    for (p in assetPaths) {
                        try {
                            if (m.parameterTypes.size>=1 && m.parameterTypes[0]==String::class.java) {
                                val args = when (m.parameterTypes.size) {
                                    1 -> arrayOf(p)
                                    2 -> arrayOf(p, true)
                                    3 -> arrayOf(p, true, base)
                                    else -> arrayOf(p)
                                }
                                m.invoke(modelNode, *args)
                                invoked = true; break
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
            val sceneObj = arSceneView.javaClass.methods.firstOrNull { it.name.lowercase() in listOf("getscene","scene") && it.parameterCount==0 }?.invoke(arSceneView)
            val addChildMethods = sceneObj?.javaClass?.methods?.filter { m -> m.name.lowercase().contains("addchild") && m.parameterTypes.size==1 }
            var added = false
            addChildMethods?.forEach { m -> if (!added) try { m.invoke(sceneObj, modelNode); added = true } catch (_: Throwable) {} }
            if (added) {
                Log.d("IndoorAR","ArModelNode seta adicionada (invokedLoad=$invoked)")
                return true
            }
        } catch (_: ClassNotFoundException) { } catch (t: Throwable) {
            Log.w("IndoorAR","Falha usando ArModelNode: ${t.message}")
        }
        return try {
            val arNodeCls = Class.forName("io.github.sceneview.ar.node.ArNode")
            val node = arNodeCls.getDeclaredConstructor().newInstance()
            arNodeCls.methods.firstOrNull { it.name.equals("setAnchor", true) && it.parameterTypes.size == 1 }?.invoke(node, anchor)
            val base = forcedScale ?: if (isLast) 0.40f else 0.28f
            arNodeCls.methods.firstOrNull { it.name.equals("setScale", true) && it.parameterTypes.size == 3 }?.let { m ->
                try { m.invoke(node, base, base, base) } catch (_: Throwable) {}
            }
            arNodeCls.methods.firstOrNull { it.name.equals("setScale", true) && it.parameterTypes.size == 1 && it.parameterTypes[0]==FloatArray::class.java }?.let { m ->
                try { m.invoke(node, floatArrayOf(base, base, base)) } catch (_: Throwable) {}
            }
            val quat = yawToQuaternionY(heading)
            arNodeCls.methods.firstOrNull { it.name.lowercase().contains("quaternion") && it.parameterTypes.size == 4 }?.let { m ->
                try { m.invoke(node, quat[0], quat[1], quat[2], quat[3]) } catch (_: Throwable) {}
            }
            val pathVariants = listOf(arrowModelFile, arrowModelAssetUri, "/android_asset/$arrowModelFile")
            var invoked = false
            val loadCandidates = arNodeCls.methods.filter { m ->
                val n = m.name.lowercase(); (n.contains("load") || n.contains("model")) && m.parameterTypes.isNotEmpty()
            }
            fun attempt(m: java.lang.reflect.Method, path: String) {
                if (invoked) return
                try {
                    when (m.parameterTypes.size) {
                        1 -> if (m.parameterTypes[0] == String::class.java) { m.invoke(node, path); invoked = true }
                        2 -> if (m.parameterTypes[0] == String::class.java) { m.invoke(node, path, true); invoked = true }
                        3 -> if (m.parameterTypes[0] == String::class.java) { m.invoke(node, path, true, base); invoked = true }
                    }
                } catch (_: Throwable) {}
            }
            val priorityNames = listOf("loadModelGlbAsync", "loadModelGlb", "loadModel", "loadGlb")
            for (nm in priorityNames) {
                if (invoked) break
                loadCandidates.filter { it.name == nm }.forEach { m -> pathVariants.forEach { p -> attempt(m, p) } }
            }
            if (!invoked) loadCandidates.forEach { m -> pathVariants.forEach { p -> attempt(m, p) } }
            if (!invoked) {
                arNodeCls.methods.firstOrNull { it.name.lowercase().startsWith("set") && it.parameterTypes.size==1 && it.parameterTypes[0]==String::class.java }
                    ?.let { setter -> pathVariants.forEach { p -> if (!invoked) try { setter.invoke(node, p); invoked = true } catch (_: Throwable) {} } }
            }
            val sceneObj = arSceneView.javaClass.methods.firstOrNull { it.name.lowercase() in listOf("getscene","scene") && it.parameterCount==0 }?.invoke(arSceneView)
            val addChildMethods = sceneObj?.javaClass?.methods?.filter { m -> m.name.lowercase().contains("addchild") && m.parameterTypes.size==1 }
            var added = false
            addChildMethods?.forEach { m -> if (!added) try { m.invoke(sceneObj, node); added = true } catch (_: Throwable) {} }
            Log.d("IndoorAR","ArNode seta adicionada (invokedLoad=$invoked added=$added)")
            added
        } catch (t: Throwable) {
            Log.w("IndoorAR","Falha fallback ArNode seta: ${t.message}")
            false
        }
    }

    private fun limparAnchors3D() {
        routeSegmentAnchors.forEach { a -> try { a.detach() } catch (_: Exception) {} }
        routeSegmentAnchors.clear()
        routeAnchors.forEach { a -> try { a.detach() } catch (_: Exception) {} }
        routeAnchors.clear()
        built3DRouteForCurrentPath = false
    }

    private fun calibrarMapToWorld(pose: Pose) {
        val yawPose = extractYaw(pose)
        val yawDelta = yawPose - lastHeadingRad
        val cosY = kotlin.math.cos(yawDelta)
        val sinY = kotlin.math.sin(yawDelta)
        val rx = estX * cosY - estZ * sinY
        val rz = estX * sinY + estZ * cosY
        val worldX = pose.tx()
        val worldZ = if (invertZAxisForAr) -pose.tz() else pose.tz()
        mapWorldYawDelta = yawDelta
        mapWorldOffsetX = worldX - rx
        mapWorldOffsetZ = worldZ - rz
        calibrationDone = true
    }
}

// ---- Data classes / adapters ----

data class DestinoPoi(val id: String, val name: String, val x: Float, val z: Float, val iconRes: Int, val isStart: Boolean = false)
data class GraphNode(val id: String, val x: Float, val z: Float)
data class GraphEdge(val from: String, val to: String, val peso: Double)

data class RoutePoint(val x: Float, val z: Float)

class DestinoPoiAdapter(private val items: List<DestinoPoi>, private val onClick: (DestinoPoi) -> Unit) : RecyclerView.Adapter<DestinoPoiAdapter.VH>() {
    inner class VH(v: View) : RecyclerView.ViewHolder(v) { val icon: android.widget.ImageView = v.findViewById(R.id.ivPoi); val txt: TextView = v.findViewById(R.id.tvPoiName) }
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH { val v = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_poi, parent, false); return VH(v) }
    override fun onBindViewHolder(holder: VH, position: Int) { val item = items[position]; try { holder.icon.setImageResource(item.iconRes) } catch (_: Exception) { holder.icon.setImageResource(R.drawable.ic_poi_default) }; holder.txt.text = item.name; holder.itemView.setOnClickListener { onClick(item) } }
    override fun getItemCount(): Int = items.size
}
