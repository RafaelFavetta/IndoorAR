package com.example.indoorar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.ux.ArFragment
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.example.indoorar.ui.MinimapView
import com.example.indoorar.tracking.SensorFusionTracker
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class ActivityMap : BaseActivity() {

    private lateinit var arFragment: ArFragment
    private lateinit var loadingText: TextView
    private lateinit var minimapView: MinimapView
    private var mapId: String? = null

    // Track ARCore install flow to avoid re-requesting every resume
    private var arInstallRequested: Boolean = false
    private var isArReady: Boolean = false

    // UI
    private lateinit var btnEscolherDestino: MaterialButton
    private lateinit var cardDestino: MaterialCardView
    private lateinit var recyclerDestino: RecyclerView
    private lateinit var btnLimparRota: MaterialButton
    private lateinit var tvDistancia: TextView
    private lateinit var arContainer: View
    private lateinit var cameraPreview: PreviewView

    // POIs (destinos)
    private val destinos = mutableListOf<DestinoPoi>()
    private var destinoSelecionado: DestinoPoi? = null

    // Grafo
    private val graphNodes = mutableMapOf<String, GraphNode>()
    private val adjacency = mutableMapOf<String, MutableList<GraphEdge>>()
    private var graphLoaded = false

    // Rota atual
    private var currentPathNodeIds: List<String> = emptyList()
    private var currentPathAnchors: MutableList<AnchorNode> = mutableListOf()
    private var cumulativeDistances: List<Double> = emptyList()
    private var totalDistance: Double = 0.0
    private var lastUserRecalcCheckPose: Pair<Float, Float>? = null

    // Esferas da rota
    private val routeSpheres = mutableListOf<RouteSphere>()
    private var baseSphereMaterial: com.google.ar.sceneform.rendering.Material? = null
    private var nearSphereMaterial: com.google.ar.sceneform.rendering.Material? = null
    private val sphereHighlightDistance = 1.0

    // Sensor Fusion (Para quem não tem ARCore)
    private var sensorTracker: SensorFusionTracker? = null
    private var estX: Float = 0f
    private var estZ: Float = 0f
    private val reanchorNodeThresholdMeters = 0.8f
    private val stepLengthMeters = 0.7f // usar 0.7m real; sub-passos aplicam 1.0m em blocos
    private val mapNorthDegrees: Float = 0f

    // Location services
    private lateinit var settingsClient: SettingsClient
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val enableLocationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            proceedAfterLocationReady()
        } else {
            Toast.makeText(this, "A localização é necessária para usar o mapa.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val camOk = perms[Manifest.permission.CAMERA] == true || hasCameraPermission()
        val actOk = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            (perms[Manifest.permission.ACTIVITY_RECOGNITION] == true || hasActivityRecognitionPermission()) else true
        val locOk = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasLocationPermission()

        if (!camOk) { Toast.makeText(this, "Permissão de câmera necessária", Toast.LENGTH_LONG).show(); finish(); return@registerForActivityResult }
        if (!actOk) { Toast.makeText(this, "Permissão de atividade física negada. A posição pode não atualizar automaticamente.", Toast.LENGTH_LONG).show() }
        if (!locOk) { Toast.makeText(this, "Permissão de localização necessária", Toast.LENGTH_LONG).show(); finish(); return@registerForActivityResult }

        ensureLocationServicesEnabled()
    }

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val value = res.data?.getStringExtra("QR_VALUE")
            val extracted = value?.let { extractMapId(it) }
            if (!extracted.isNullOrBlank()) { mapId = extracted; carregarMapa() }
            else { Toast.makeText(this, "QR inválido", Toast.LENGTH_SHORT).show(); finish() }
        } else { Toast.makeText(this, "Scan cancelado", Toast.LENGTH_SHORT).show(); finish() }
    }

    private lateinit var destinoAdapter: DestinoPoiAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        bindViews()
        setupRecycler()
        setupButtons()

        // Sanitiza o MAP_ID vindo pela intent (pode ser URL)
        mapId = intent.getStringExtra("MAP_ID")?.let { extractMapId(it) }
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) {
            checkArCoreSupport()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorTracker?.stop()
    }

    private fun checkArCoreSupport() {
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        // If transient, re-check shortly
        if (availability.isTransient) {
            loadingText.visibility = View.VISIBLE
            loadingText.text = "Verificando AR..."
            loadingText.postDelayed({ checkArCoreSupport() }, 500)
            return
        }
        when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                initializeArFragment()
                arContainer.visibility = View.VISIBLE
                cameraPreview.visibility = View.GONE
            }
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> {
                try {
                    val status = ArCoreApk.getInstance().requestInstall(this, /*userRequestedInstall=*/!arInstallRequested)
                    when (status) {
                        ArCoreApk.InstallStatus.INSTALLED -> {
                            initializeArFragment()
                            arContainer.visibility = View.VISIBLE
                            cameraPreview.visibility = View.GONE
                        }
                        ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                            // Wait for installation to complete; on next onResume we'll be called again
                            arInstallRequested = true
                            loadingText.visibility = View.VISIBLE
                            loadingText.text = "Instalando/Atualizando AR..."
                            return
                        }
                    }
                } catch (_: Exception) {
                    // Could not request install; fall back to camera
                    initializeCameraFallback()
                }
            }
            else -> { initializeCameraFallback() }
        }
    }

    private fun initializeCameraFallback() {
        arContainer.visibility = View.GONE
        cameraPreview.visibility = View.VISIBLE
        val providerFuture = ProcessCameraProvider.getInstance(this)
        val executor = ContextCompat.getMainExecutor(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().apply { setSurfaceProvider(cameraPreview.surfaceProvider) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                if (loadingText.isVisible) loadingText.visibility = View.GONE
            } catch (e: Exception) {
                Toast.makeText(this, "Falha CameraX: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, executor)
    }

    private fun initializeArFragment() {
        try {
            stopSensorTracking()
            val tag = "AR_FRAGMENT"
            val existing = supportFragmentManager.findFragmentByTag(tag)
            if (existing == null) {
                val tx = supportFragmentManager.beginTransaction()
                tx.add(R.id.arFragmentContainer, ArFragment(), tag)
                tx.commitNow()
            }
            arFragment = supportFragmentManager.findFragmentByTag(tag) as ArFragment
            arContainer.visibility = View.VISIBLE
            cameraPreview.visibility = View.GONE

            loadingText.visibility = View.VISIBLE
            loadingText.text = "Inicializando AR..."

            arFragment.arSceneView.scene.addOnUpdateListener {
                val frame = arFragment.arSceneView.arFrame
                val cam = frame?.camera
                isArReady = cam?.trackingState == TrackingState.TRACKING
                if (isArReady && loadingText.isVisible) loadingText.visibility = View.GONE
                if (frame == null || cam == null) return@addOnUpdateListener
                val pose = cam.pose
                minimapView.updateUserPosition(pose.tx(), pose.tz())
                atualizarDistanciaRestante(pose.tx(), pose.tz())
                tentarRecalcularSeDesviou(pose.tx(), pose.tz())
                atualizarDestaqueEsferas(pose.tx(), pose.tz())
            }

            // Safety net: if session didn't start after a few seconds, fallback to CameraX
            arContainer.postDelayed({
                if (!isArReady && arFragment.arSceneView.session == null) {
                    Toast.makeText(this, "Falha ao iniciar sessão AR. Usando câmera básica.", Toast.LENGTH_LONG).show()
                    initializeCameraFallback()
                }
            }, 6000)
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao inicializar AR: ${e.message}", Toast.LENGTH_LONG).show()
            initializeCameraFallback()
        }
    }

    private fun bindViews() {
        loadingText = findViewById(R.id.textViewMap)
        minimapView = findViewById(R.id.minimap)
        btnEscolherDestino = findViewById(R.id.btnEscolherDestino)
        cardDestino = findViewById(R.id.cardDestino)
        recyclerDestino = findViewById(R.id.recyclerDestino)
        btnLimparRota = findViewById(R.id.btnLimparRota)
        tvDistancia = findViewById(R.id.tvDistancia)
        arContainer = findViewById(R.id.arFragmentContainer)
        cameraPreview = findViewById(R.id.cameraPreview)
    }

    private fun setupRecycler() {
        recyclerDestino.layoutManager = LinearLayoutManager(this)
        destinoAdapter = DestinoPoiAdapter(destinos) { poi ->
            cardDestino.visibility = View.GONE
            destinoSelecionado = poi
            if (!graphLoaded) { Toast.makeText(this, "Grafo não carregado", Toast.LENGTH_SHORT).show(); return@DestinoPoiAdapter }
            calcularRotaAStar(poi)
        }
        recyclerDestino.adapter = destinoAdapter
    }

    private fun setupButtons() {
        btnEscolherDestino.setOnClickListener {
            if (destinos.isEmpty()) {
                Toast.makeText(this, "Nenhum destino disponível", Toast.LENGTH_SHORT).show()
            } else {
                cardDestino.visibility = if (cardDestino.isVisible) View.GONE else View.VISIBLE
            }
        }
        btnLimparRota.setOnClickListener { limparRota() }
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        if (!hasCameraPermission()) needed += Manifest.permission.CAMERA
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && !hasActivityRecognitionPermission()) {
            needed += Manifest.permission.ACTIVITY_RECOGNITION
        }
        if (!hasLocationPermission()) needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (needed.isNotEmpty()) {
            requestPermissions.launch(needed.toTypedArray())
        } else {
            ensureLocationServicesEnabled()
        }
    }

    private fun ensureLocationServicesEnabled() {
        if (!this::settingsClient.isInitialized) settingsClient = LocationServices.getSettingsClient(this)
        if (!this::fusedLocationClient.isInitialized) fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        val settingsRequest = LocationSettingsRequest.Builder().addLocationRequest(req).setAlwaysShow(true).build()

        settingsClient.checkLocationSettings(settingsRequest)
            .addOnSuccessListener { proceedAfterLocationReady() }
            .addOnFailureListener { ex ->
                if (ex is ResolvableApiException) {
                    try {
                        enableLocationLauncher.launch(IntentSenderRequest.Builder(ex.resolution).build())
                    } catch (_: Exception) {
                        Toast.makeText(this, "Ative a localização para continuar", Toast.LENGTH_LONG).show(); finish()
                    }
                } else { Toast.makeText(this, "Localização desativada. Ative-a para usar o mapa.", Toast.LENGTH_LONG).show(); finish() }
            }
    }

    private fun proceedAfterLocationReady() {
        checkArCoreSupport()
        ensureMapLoadedOrScan()
    }

    private fun ensureMapLoadedOrScan() {
        if (mapId.isNullOrBlank()) {
            val intentScan = Intent(this, ActivityScanQR::class.java).apply { putExtra("RETURN_RESULT", true) }
            scanLauncher.launch(intentScan)
        } else carregarMapa()
    }

    private fun carregarMapa() {
        val id = mapId
        if (id.isNullOrBlank()) { Toast.makeText(this, "QR inválido", Toast.LENGTH_SHORT).show(); finish(); return }
        if (id.contains('/')) { Toast.makeText(this, "ID de mapa inválido", Toast.LENGTH_SHORT).show(); finish(); return }
        loadingText.visibility = View.VISIBLE
        val db = FirebaseFirestore.getInstance()
        val docRef = try { db.collection("mapas").document(id) } catch (_: IllegalArgumentException) {
            Toast.makeText(this, "ID de mapa inválido", Toast.LENGTH_SHORT).show(); finish(); return
        }
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

        if (!this::arFragment.isInitialized) {
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
                minimapView.addForma(x, z, w, h, android.graphics.Color.rgb(r, g, b))
            }
        } else {
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
                minimapView.addForma(x, z, w, h, android.graphics.Color.rgb(r, g, b))
                val sceneView = arFragment.arSceneView
                val session = sceneView.session ?: return@forEach
                val pose = Pose(floatArrayOf(x + w/2f, 0f, z + h/2f), floatArrayOf(0f,0f,0f,1f))
                val anchor: Anchor = session.createAnchor(pose)
                MaterialFactory.makeOpaqueWithColor(this, Color(android.graphics.Color.rgb(r,g,b)))
                    .thenAccept { material ->
                        val size = Vector3(w, 0.1f, h)
                        val renderable = ShapeFactory.makeCube(size, Vector3.zero(), material)
                        val anchorNode = AnchorNode(anchor)
                        anchorNode.parent = sceneView.scene
                        val node = Node().apply { this.renderable = renderable }
                        node.parent = anchorNode
                    }
            }
        }

        val oldSize = destinos.size
        destinos.clear()
        pois.forEach { p ->
            val x = (p.get("x") as? Number)?.toFloat() ?: 0f
            val z = (p.get("y") as? Number)?.toFloat() ?: 0f
            minimapView.addPoi(x, z)
            val name = p.getString("name") ?: "POI"
            val iconRes = resolvePoiIconRes(p)
            val id = p.getString("id") ?: (name + x + z)
            val isStart = (p.get("isStartQR") as? Boolean) == true
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

        if (!this::arFragment.isInitialized && sensorTracker == null) {
            val startPoi = destinos.firstOrNull { it.isStart }
            val initX = startPoi?.x ?: graphNodes.values.firstOrNull()?.x ?: 0f
            val initZ = startPoi?.z ?: graphNodes.values.firstOrNull()?.z ?: 0f
            startSensorTracking(initX, initZ)
        }
    }

    private fun resolvePoiIconRes(p: DocumentSnapshot): Int {
        val iconName = p.getString("iconName") ?: p.getString("icon")
        return when (iconName?.lowercase()) {
            "porta", "door" -> R.drawable.ic_door_azul
            "banheiro", "bathroom" -> R.drawable.ic_banheiro_azul
            "escada", "stairs" -> R.drawable.ic_stairs_azul
            "elevador", "elevator" -> R.drawable.ic_elevator_azul
            "extintor", "fire_extinguisher" -> R.drawable.ic_extintor_azul
            "circulo", "circle" -> R.drawable.ic_circle_azul
            "quadrado", "square" -> R.drawable.ic_square_azul
            "triangulo", "triangle" -> R.drawable.ic_triangle_azul
            else -> R.drawable.ic_poi_default
        }
    }

    private fun calcularRotaAStar(dest: DestinoPoi) {
        // Determine best start node even if AR isn't fully ready yet
        val startNodeId = destinos.firstOrNull { it.isStart }?.id ?: run {
            val camPose = if (this::arFragment.isInitialized) arFragment.arSceneView.arFrame?.camera?.pose else null
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
        gerarCumulativeDistances()
        totalDistance = if (cumulativeDistances.isNotEmpty()) cumulativeDistances.last() else 0.0
        tvDistancia.text = getString(R.string.distance_meters, totalDistance)
        val ptsNodes = path.mapNotNull { graphNodes[it] }.map { it.x to it.z }
        val ptsDensificados = densificarRota(ptsNodes)
        minimapView.setRoute(ptsDensificados)
        // Draw spheres only if AR session exists
        if (this::arFragment.isInitialized && arFragment.arSceneView.session != null) {
            desenharEsferasRota(ptsDensificados)
        }
    }

    private val densifyStepMeters = 0.5f
    private fun densificarRota(pontos: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (pontos.size < 2) return pontos
        val out = mutableListOf<Pair<Float,Float>>()
        for (i in 0 until pontos.size - 1) {
            val (ax, az) = pontos[i]
            val (bx, bz) = pontos[i+1]
            out += ax to az
            val dx = bx - ax
            val dz = bz - az
            val dist = kotlin.math.sqrt(dx*dx + dz*dz)
            if (dist > densifyStepMeters) {
                val steps = (dist / densifyStepMeters).toInt()
                if (steps > 1) {
                    val stepX = dx / steps
                    val stepZ = dz / steps
                    for (s in 1 until steps) out += (ax + stepX * s) to (az + stepZ * s)
                }
            }
        }
        out += pontos.last()
        return out
    }

    private fun gerarCumulativeDistances() {
        val list = mutableListOf<Double>()
        var acc = 0.0
        for (i in currentPathNodeIds.indices) {
            if (i == 0) { list += 0.0; continue }
            val a = graphNodes[currentPathNodeIds[i - 1]]!!
            val b = graphNodes[currentPathNodeIds[i]]!!
            acc += hypot((b.x - a.x).toDouble(), (b.z - a.z).toDouble())
            list += acc
        }
        cumulativeDistances = list
    }

    private fun atualizarDistanciaRestante(ux: Float, uz: Float) {
        if (currentPathNodeIds.isEmpty()) return
        var bestIdx = 0
        var bestDist = Double.MAX_VALUE
        currentPathNodeIds.forEachIndexed { idx, id ->
            val n = graphNodes[id] ?: return@forEachIndexed
            val d = hypot((n.x - ux).toDouble(), (n.z - uz).toDouble())
            if (d < bestDist) { bestDist = d; bestIdx = idx }
        }
        if (bestIdx == currentPathNodeIds.lastIndex && bestDist < 1.0) {
            tvDistancia.text = getString(R.string.distance_arrived); return
        }
        val distAteProxNo = bestDist
        val restante = if (bestIdx < cumulativeDistances.size) {
            val totalAteNo = cumulativeDistances[bestIdx]
            max(0.0, totalDistance - totalAteNo - distAteProxNo)
        } else 0.0
        tvDistancia.text = getString(R.string.distance_meters, restante)
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
        if (dist > 2.0) { destinoSelecionado?.let { calcularRotaAStar(it) } }
    }

    private fun distanciaParaRota(x: Float, z: Float): Double {
        if (currentPathNodeIds.size < 2) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        for (i in 0 until currentPathNodeIds.size - 1) {
            val a = graphNodes[currentPathNodeIds[i]] ?: continue
            val b = graphNodes[currentPathNodeIds[i + 1]] ?: continue
            val ax = a.x; val az = a.z; val bx = b.x; val bz = b.z
            val vx = bx - ax; val vz = bz - az
            val wx = x - ax; val wz = z - az
            val len2 = vx*vx + vz*vz
            val t = if (len2 == 0f) 0f else ((wx*vx + wz*vz) / len2).coerceIn(0f,1f)
            val projX = ax + t * vx
            val projZ = az + t * vz
            val d = hypot((projX - x).toDouble(), (projZ - z).toDouble())
            if (d < best) best = d
        }
        return best
    }

    private fun desenharEsferasRota(pontos: List<Pair<Float,Float>>) {
        limparEsferas()
        if (!this::arFragment.isInitialized) return
        if (pontos.isEmpty()) return
        val scene = arFragment.arSceneView.scene
        val session = arFragment.arSceneView.session ?: return
        val baseFuture = MaterialFactory.makeOpaqueWithColor(this, Color(0xFF1565C0.toInt()))
        val nearFuture = MaterialFactory.makeOpaqueWithColor(this, Color(0xFF64B5F6.toInt()))
        baseFuture.thenCombine(nearFuture) { base, near ->
            baseSphereMaterial = base; nearSphereMaterial = near
            pontos.forEach { (x, z) ->
                val pose = Pose(floatArrayOf(x, 0f, z), floatArrayOf(0f,0f,0f,1f))
                val anchor = session.createAnchor(pose)
                val anchorNode = AnchorNode(anchor)
                anchorNode.parent = scene
                val mat = baseSphereMaterial ?: return@forEach
                val renderable = ShapeFactory.makeSphere(0.07f, Vector3.zero(), mat)
                val node = Node().apply { this.renderable = renderable }
                node.parent = anchorNode
                routeSpheres += RouteSphere(anchorNode, node, x, z)
            }
        }
    }

    private fun atualizarDestaqueEsferas(ux: Float, uz: Float) {
        val baseMat = baseSphereMaterial ?: return
        val nearMat = nearSphereMaterial ?: return
        routeSpheres.forEach { s ->
            val d = hypot((s.x - ux).toDouble(), (s.z - uz).toDouble())
            val targetMat = if (d <= sphereHighlightDistance) nearMat else baseMat
            val rend = s.node.renderable ?: return@forEach
            if (rend.material != targetMat) rend.material = targetMat
        }
    }

    private fun limparEsferas() {
        routeSpheres.forEach { it.anchorNode.parent = null; it.anchorNode.anchor?.detach() }
        routeSpheres.clear()
    }

    private fun limparRota() {
        limparEsferas()
        currentPathAnchors.forEach { it.parent = null; it.anchor?.detach() }
        currentPathAnchors.clear()
        currentPathNodeIds = emptyList()
        cumulativeDistances = emptyList()
        totalDistance = 0.0
        minimapView.clearRoute()
        tvDistancia.text = getString(R.string.distance_placeholder)
    }

    private fun encontrarNodeMaisProximo(x: Float, z: Float): String {
        var bestId: String = graphNodes.keys.first()
        var bestDist = Double.MAX_VALUE
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
        val startNode = graphNodes[startId]!!
        val goalNode = graphNodes[goalId]!!
        val startRec = Rec(startId, 0.0, h(startNode, goalNode), null)
        all[startId] = startRec; open.add(startRec)
        while (open.isNotEmpty()) {
            val cur = open.poll()!!
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
                val rec = all[e.to]
                if (rec == null || tentativeG < rec.g) {
                    val node = graphNodes[e.to]!!
                    val f = tentativeG + h(node, goalNode)
                    if (rec == null) {
                        val nr = Rec(e.to, tentativeG, f, cur.id)
                        all[e.to] = nr; open.add(nr)
                    } else {
                        rec.g = tentativeG; rec.f = f; rec.parent = cur.id
                        open.add(rec)
                    }
                }
            }
        }
        return null
    }

    private fun extractMapId(raw: String): String? {
        val s = raw.trim()
        if (!s.contains('/')) return s
        val idxParam = s.indexOf("mapId=")
        if (idxParam >= 0) {
            val sub = s.substring(idxParam + 6)
            val end = listOf('&', '#', '?', '/').map { ch -> sub.indexOf(ch) }.filter { it >= 0 }.minOrNull() ?: sub.length
            val id = sub.substring(0, end)
            if (id.isNotBlank()) return id
        }
        val token = "/mapas/"
        val idx = s.indexOf(token)
        if (idx >= 0) {
            val sub = s.substring(idx + token.length)
            val end = sub.indexOf('/')
            val id = if (end >= 0) sub.substring(0, end) else sub
            if (id.isNotBlank()) return id
        }
        val parts = s.split('/').filter { it.isNotBlank() }
        if (parts.isNotEmpty()) return parts.last()
        return null
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasActivityRecognitionPermission(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        else true

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun projectToNearestEdge(x: Float, z: Float): Pair<Float, Float> {
        val segments: MutableList<Pair<GraphNode, GraphNode>> = mutableListOf()
        if (currentPathNodeIds.size >= 2) {
            for (i in 0 until currentPathNodeIds.size - 1) {
                val a = graphNodes[currentPathNodeIds[i]]
                val b = graphNodes[currentPathNodeIds[i + 1]]
                if (a != null && b != null) segments += a to b
            }
        }
        if (segments.isEmpty()) {
            val seen = mutableSetOf<String>()
            adjacency.forEach { (from, edges) ->
                val a = graphNodes[from] ?: return@forEach
                edges.forEach { e ->
                    val b = graphNodes[e.to] ?: return@forEach
                    val key = listOf(a.id, b.id).sorted().joinToString("-")
                    if (seen.add(key)) segments += a to b
                }
            }
        }
        if (segments.isEmpty()) return x to z

        var bestX = x; var bestZ = z; var bestD = Double.MAX_VALUE
        segments.forEach { (na, nb) ->
            val ax = na.x; val az = na.z
            val bx = nb.x; val bz = nb.z
            val vx = bx - ax; val vz = bz - az
            val wx = x - ax; val wz = z - az
            val len2 = (vx*vx + vz*vz)
            val t = if (len2 == 0f) 0f else ((wx*vx + wz*vz) / len2).coerceIn(0f, 1f)
            val px = ax + t * vx
            val pz = az + t * vz
            val d = hypot((px - x).toDouble(), (pz - z).toDouble())
            if (d < bestD) { bestD = d; bestX = px; bestZ = pz }
        }
        val SNAP_MAX_DIST = 1.5
        return if (bestD <= SNAP_MAX_DIST) bestX to bestZ else x to z
    }

    private fun checkReanchorToNode(x: Float, z: Float): Pair<Boolean, Pair<Float, Float>?> {
        if (graphNodes.isEmpty()) return false to null
        var bestId: String? = null
        var bestD = Double.MAX_VALUE
        graphNodes.forEach { (id, n) ->
            val d = hypot((n.x - x).toDouble(), (n.z - z).toDouble())
            if (d < bestD) { bestD = d; bestId = id }
        }
        return if (bestId != null && bestD <= reanchorNodeThresholdMeters.toDouble()) {
            val n = graphNodes[bestId]!!
            true to (n.x to n.z)
        } else false to null
    }

    private fun startSensorTracking(initialX: Float, initialZ: Float) {
        if (sensorTracker != null) return
        val sm = getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val hasStep = (sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_DETECTOR) != null) || (sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER) != null)
        if (!hasStep) { Toast.makeText(this, "Sem sensor de passos dedicado: usando detecção por acelerômetro (pode ser menos precisa).", Toast.LENGTH_LONG).show() }
        sensorTracker = SensorFusionTracker(
            context = this,
            mapNorthDegrees = mapNorthDegrees,
            stepLengthMeters = stepLengthMeters,
            onPosition = { x, z, _ ->
                estX = x; estZ = z
                minimapView.updateUserPosition(x, z)
                atualizarDistanciaRestante(x, z)
                tentarRecalcularSeDesviou(x, z)
                atualizarDestaqueEsferas(x, z)
            },
            mapMatch = { x, z -> projectToNearestEdge(x, z) },
            reanchorCheck = { x, z -> checkReanchorToNode(x, z) }
        ).also { it.start(initialX, initialZ) }
    }

    private fun stopSensorTracking() {
        sensorTracker?.stop()
        sensorTracker = null
    }
}

// Data classes

data class DestinoPoi(
    val id: String,
    val name: String,
    val x: Float,
    val z: Float,
    val iconRes: Int,
    val isStart: Boolean = false
)

data class GraphNode(val id: String, val x: Float, val z: Float)

data class GraphEdge(val from: String, val to: String, val peso: Double)

class DestinoPoiAdapter(
    private val items: List<DestinoPoi>,
    private val onClick: (DestinoPoi) -> Unit
) : RecyclerView.Adapter<DestinoPoiAdapter.VH>() {
    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: android.widget.ImageView = v.findViewById(R.id.ivPoi)
        val txt: TextView = v.findViewById(R.id.tvPoiName)
    }
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_poi, parent, false)
        return VH(v)
    }
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        try { holder.icon.setImageResource(item.iconRes) } catch (_: Exception) { holder.icon.setImageResource(R.drawable.ic_poi_default) }
        holder.txt.text = item.name
        holder.itemView.setOnClickListener { onClick(item) }
    }
    override fun getItemCount(): Int = items.size
}

data class RouteSphere(
    val anchorNode: AnchorNode,
    val node: Node,
    val x: Float,
    val z: Float
)
