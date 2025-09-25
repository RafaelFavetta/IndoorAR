package com.example.indoorar

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.ar.sceneform.ux.ArFragment
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.example.indoorar.ui.MinimapView
import java.util.PriorityQueue
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.max

// Add ARCore availability import
import com.google.ar.core.ArCoreApk

class ActivityMap : BaseActivity() {

    private lateinit var arFragment: ArFragment
    private lateinit var loadingText: TextView
    private lateinit var minimapView: MinimapView
    private var mapId: String? = null

    // UI
    private lateinit var btnEscolherDestino: MaterialButton
    private lateinit var cardDestino: MaterialCardView
    private lateinit var recyclerDestino: RecyclerView
    private lateinit var btnLimparRota: MaterialButton
    private lateinit var tvDistancia: TextView

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
    private var cumulativeDistances: List<Double> = emptyList() // acumulada por nó no path
    private var totalDistance: Double = 0.0
    private var lastUserRecalcCheckPose: Pair<Float, Float>? = null

    // Esferas da rota
    private val routeSpheres = mutableListOf<RouteSphere>()
    private var baseSphereMaterial: com.google.ar.sceneform.rendering.Material? = null
    private var nearSphereMaterial: com.google.ar.sceneform.rendering.Material? = null
    private val sphereHighlightDistance = 1.0

    // Track ARCore install prompt state
    private var userRequestedInstall: Boolean = true
    private var arInstallReady: Boolean = false
    private var arInitialized: Boolean = false

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val camOk = perms[Manifest.permission.CAMERA] == true
        if (!camOk) {
            Toast.makeText(this, "Permissão de câmera necessária", Toast.LENGTH_LONG).show()
            finish(); return@registerForActivityResult
        }
        // If ARCore install is already handled, we can initialize AR now
        if (arInstallReady) ensureArInitialized()
        ensureMapLoadedOrScan()
    }

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val value = res.data?.getStringExtra("QR_VALUE")
            val extracted = value?.let { extractMapId(it) }
            if (!extracted.isNullOrBlank()) {
                mapId = extracted; carregarMapa()
            } else { Toast.makeText(this, "QR inválido", Toast.LENGTH_SHORT).show(); finish() }
        } else { Toast.makeText(this, "Scan cancelado", Toast.LENGTH_SHORT).show(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        // ARCore check
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (availability.isUnsupported) {
            Toast.makeText(this, "Seu dispositivo não contém suporte ao ARCore.", Toast.LENGTH_LONG).show()
            finish(); return
        }

        bindViews()
        // Obtain reference to the ArFragment so it's available during map processing
        try {
            arFragment = supportFragmentManager.findFragmentById(R.id.arFragment) as ArFragment
        } catch (_: Exception) { /* fragment not inflated yet; will retry later */ }
        // Do not initialize AR here; wait until we know ARCore is installed and camera permission is granted
        setupRecycler()
        setupButtons()

        // Sanitiza o MAP_ID vindo pela intent (pode ser URL)
        mapId = intent.getStringExtra("MAP_ID")?.let { extractMapId(it) }
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Request ARCore install/update if needed. If INSTALL_REQUESTED, Play Store flow will start and we return.
        val installStatus = ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)
        if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
            userRequestedInstall = false
            return
        }
        // If INSTALLED, mark ready and try to init if we have camera permission
        arInstallReady = true
        ensureArInitialized()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun ensureArInitialized() {
        if (arInitialized) return
        if (!hasCameraPermission()) return
        initializeArFragment()
        arInitialized = true
    }

    private fun initializeArFragment() {
        try {
            arFragment.arSceneView.scene.addOnUpdateListener {
                if (loadingText.isVisible) loadingText.visibility = View.GONE
                val frame = arFragment.arSceneView.arFrame ?: return@addOnUpdateListener
                val pose = frame.camera.pose
                minimapView.updateUserPosition(pose.tx(), pose.tz())
                atualizarDistanciaRestante(pose.tx(), pose.tz())
                tentarRecalcularSeDesviou(pose.tx(), pose.tz())
                atualizarDestaqueEsferas(pose.tx(), pose.tz())
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao inicializar AR: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
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
    }

    private fun setupRecycler() {
        recyclerDestino.layoutManager = LinearLayoutManager(this)
        recyclerDestino.adapter = DestinoPoiAdapter(destinos) { poi ->
            cardDestino.visibility = View.GONE
            destinoSelecionado = poi
            if (!graphLoaded) {
                Toast.makeText(this, "Grafo não carregado", Toast.LENGTH_SHORT).show(); return@DestinoPoiAdapter
            }
            calcularRotaAStar(poi)
        }
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.CAMERA
        }
        if (needed.isNotEmpty()) requestPermissions.launch(needed.toTypedArray()) else ensureMapLoadedOrScan()
    }

    private fun ensureMapLoadedOrScan() {
        if (mapId.isNullOrBlank()) {
            val intentScan = Intent(this, ActivityScanQR::class.java).apply {
                putExtra("RETURN_RESULT", true)
            }
            scanLauncher.launch(intentScan)
        } else carregarMapa()
    }

    private fun carregarMapa() {
        val id = mapId
        if (id.isNullOrBlank()) { Toast.makeText(this, "QR inválido", Toast.LENGTH_SHORT).show(); finish(); return }
        if (id.contains('/')) { Toast.makeText(this, "ID de mapa inválido", Toast.LENGTH_SHORT).show(); finish(); return }
        loadingText.visibility = View.VISIBLE
        val db = FirebaseFirestore.getInstance()
        val docRef = try {
            db.collection("mapas").document(id)
        } catch (e: IllegalArgumentException) {
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
        // Bounds
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

        // Formas (desenho + AR)
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
                    anchorNode.setParent(sceneView.scene)
                    val node = Node().apply { this.renderable = renderable }
                    node.setParent(anchorNode)
                }
        }

        destinos.clear()
        pois.forEach { p ->
            val x = (p.get("x") as? Number)?.toFloat() ?: 0f
            val z = (p.get("y") as? Number)?.toFloat() ?: 0f
            minimapView.addPoi(x, z)
            val name = p.getString("name") ?: "POI"
            // Resolve icon by name (string) if provided; fall back to a safe default. Avoid using raw int ids from Firestore.
            val iconRes = resolvePoiIconRes(p)
            val id = p.getString("id") ?: (name + x + z)
            val isStart = (p.get("isStartQR") as? Boolean) == true
            destinos += DestinoPoi(id, name, x, z, iconRes, isStart)
        }
        recyclerDestino.adapter?.notifyDataSetChanged()
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
    }

    private fun resolvePoiIconRes(p: DocumentSnapshot): Int {
        // Prefer a string field like "iconName" that maps to a drawable resource name.
        val iconName = p.getString("iconName")
            ?: p.getString("icon") // allow alternative key
        if (!iconName.isNullOrBlank()) {
            val resId = resources.getIdentifier(iconName, "drawable", packageName)
            if (resId != 0) return resId
        }
        // Fallbacks: try a generic location icon if present; otherwise default poi icon.
        return try {
            R.drawable.ic_location
        } catch (_: Exception) {
            // If ic_location doesn't exist in this build, use a very safe default the layout uses.
            resources.getIdentifier("ic_poi_default", "drawable", packageName).takeIf { it != 0 }
                ?: android.R.drawable.star_on
        }
    }

    private fun calcularRotaAStar(dest: DestinoPoi) {
        // Ensure AR is ready before using arFragment
        if (!arInitialized) {
            Toast.makeText(this, "AR ainda não está pronto. Aguarde alguns segundos e tente novamente.", Toast.LENGTH_SHORT).show()
            return
        }
        val frame = arFragment.arSceneView.arFrame
        val camPose = frame?.camera?.pose
        val startNodeId = destinos.firstOrNull { it.isStart }?.id ?: run {
            if (camPose == null) { Toast.makeText(this, "Posição indisponível", Toast.LENGTH_SHORT).show(); return }
            encontrarNodeMaisProximo(camPose.tx(), camPose.tz())
        }
        val goalNodeId = dest.id
        if (!graphNodes.containsKey(goalNodeId)) {
            Toast.makeText(this, "Destino sem nó correspondente", Toast.LENGTH_SHORT).show(); return
        }
        val path = aStar(startNodeId, goalNodeId)
        if (path == null) {
            Toast.makeText(this, "Sem rota encontrada", Toast.LENGTH_SHORT).show(); return
        }
        currentPathNodeIds = path
        gerarCumulativeDistances()
        totalDistance = if (cumulativeDistances.isNotEmpty()) cumulativeDistances.last() else 0.0
        tvDistancia.text = "Distância: %.2f m".format(totalDistance)
        val ptsNodes = path.mapNotNull { graphNodes[it] }.map { it.x to it.z }
        val ptsDensificados = densificarRota(ptsNodes)
        minimapView.setRoute(ptsDensificados)
        desenharEsferasRota(ptsDensificados)
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
                    for (s in 1 until steps) {
                        out += (ax + stepX * s) to (az + stepZ * s)
                    }
                }
            }
        }
        // garante último ponto
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
        // encontrar nó mais próximo do usuário
        var bestIdx = 0
        var bestDist = Double.MAX_VALUE
        currentPathNodeIds.forEachIndexed { idx, id ->
            val n = graphNodes[id] ?: return@forEachIndexed
            val d = hypot((n.x - ux).toDouble(), (n.z - uz).toDouble())
            if (d < bestDist) { bestDist = d; bestIdx = idx }
        }
        // se está no último nó
        if (bestIdx == currentPathNodeIds.lastIndex && bestDist < 1.0) {
            tvDistancia.text = "Distância: Chegou"; return
        }
        val distAteProxNo = bestDist
        val restante = if (bestIdx < cumulativeDistances.size) {
            val totalAteNo = cumulativeDistances[bestIdx]
            max(0.0, totalDistance - totalAteNo - distAteProxNo)
        } else 0.0
        tvDistancia.text = "Distância: %.2f m".format(restante)
    }

    private fun tentarRecalcularSeDesviou(ux: Float, uz: Float) {
        if (destinoSelecionado == null || currentPathNodeIds.isEmpty()) return
        val last = lastUserRecalcCheckPose
        if (last != null) {
            val mov = hypot((ux - last.first).toDouble(), (uz - last.second).toDouble())
            if (mov < 0.7) return // não se moveu o bastante desde última checagem
        }
        lastUserRecalcCheckPose = ux to uz
        // distância do usuário ao segmento mais próximo da rota
        val dist = distanciaParaRota(ux, uz)
        if (dist > 2.0) {
            destinoSelecionado?.let { calcularRotaAStar(it) }
        }
    }

    private fun distanciaParaRota(x: Float, z: Float): Double {
        if (currentPathNodeIds.size < 2) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        for (i in 0 until currentPathNodeIds.size - 1) {
            val a = graphNodes[currentPathNodeIds[i]] ?: continue
            val b = graphNodes[currentPathNodeIds[i + 1]] ?: continue
            // projeção ponto-segmento 2D
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
        if (pontos.isEmpty()) return
        val scene = arFragment.arSceneView.scene
        val session = arFragment.arSceneView.session ?: return
        val baseFuture = MaterialFactory.makeOpaqueWithColor(this, Color(0xFF1565C0.toInt())) // azul escuro
        val nearFuture = MaterialFactory.makeOpaqueWithColor(this, Color(0xFF64B5F6.toInt())) // azul claro
        baseFuture.thenCombine(nearFuture) { base, near ->
            baseSphereMaterial = base; nearSphereMaterial = near
            pontos.forEach { (x, z) ->
                val pose = Pose(floatArrayOf(x, 0f, z), floatArrayOf(0f,0f,0f,1f))
                val anchor = session.createAnchor(pose)
                val anchorNode = AnchorNode(anchor)
                anchorNode.setParent(scene)
                val mat = baseSphereMaterial ?: return@forEach
                val renderable = ShapeFactory.makeSphere(0.07f, Vector3.zero(), mat)
                val node = Node().apply { this.renderable = renderable }
                node.setParent(anchorNode)
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
        routeSpheres.forEach { it.anchorNode.setParent(null); it.anchorNode.anchor?.detach() }
        routeSpheres.clear()
    }

    private fun limparRota() {
        limparEsferas()
        // limpar anterior
        currentPathAnchors.forEach { it.setParent(null); it.anchor?.detach() }
        currentPathAnchors.clear()
        currentPathNodeIds = emptyList()
        cumulativeDistances = emptyList()
        totalDistance = 0.0
        minimapView.clearRoute()
        tvDistancia.text = "Distância: --"
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

    // Extrai/sanitiza id de mapa a partir de uma string (id puro, URL com parametro mapId, ou segmento final)
    private fun extractMapId(raw: String): String? {
        val s = raw.trim()
        if (!s.contains('/')) return s
        val idxParam = s.indexOf("mapId=")
        if (idxParam >= 0) {
            val sub = s.substring(idxParam + 6)
            val end = listOf('&', '#', '?', '/').map { c -> sub.indexOf(c).takeIf { it >= 0 } ?: sub.length }.min()
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
        // Guard against invalid resource ids that may come from remote content
        try {
            holder.icon.setImageResource(item.iconRes)
        } catch (_: Exception) {
            val fallback = holder.itemView.resources.getIdentifier("ic_poi_default", "drawable", holder.itemView.context.packageName)
            if (fallback != 0) holder.icon.setImageResource(fallback)
        }
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
