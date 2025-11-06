package com.example.indoorar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.indoorar.ui.MinimapView
import androidx.annotation.Keep
import com.example.indoorar.tracking.SensorFusionTracker
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import com.google.android.material.button.MaterialButton
import androidx.appcompat.widget.AppCompatImageButton
import kotlin.math.hypot
import androidx.appcompat.app.AlertDialog
import com.google.firebase.firestore.FirebaseFirestore
import com.example.indoorar.graph.FirestoreGraphLoader
import com.example.indoorar.graph.AStarPathfinder
import com.example.indoorar.graph.PathUtils
import android.graphics.Color
import androidx.core.graphics.toColorInt
import android.location.LocationManager
import android.provider.Settings
import com.example.indoorar.graph.Node
import androidx.core.view.isVisible
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * Navegação em RA com HUD 2D sobre a câmera, sem ARCore/Sceneform.
 * - PreviewView (CameraX) como fundo.
 * - Seta (ImageView) sobreposta com animações de rotação e movimento.
 * - TextView opcional para instruções.
 * - Minimap no canto inferior direito (conectado via helpers públicos).
 */
class ActivityNavHud : BaseActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var arrowView: ImageView
    private lateinit var instructionText: TextView
    private lateinit var minimap: MinimapView
    private lateinit var btnEscolherDestino: MaterialButton
    private lateinit var btnLimparRota: MaterialButton
    private lateinit var tvDistancia: TextView
    private lateinit var btnToggleManual: AppCompatImageButton
    private lateinit var manualControls: View
    private var manualControlsVisible: Boolean = false

    private var cameraProvider: ProcessCameraProvider? = null

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else Toast.makeText(this, "Permissão de câmera é necessária.", Toast.LENGTH_LONG).show()
        // Após lidar com câmera, verificamos reconhecimento de atividade para passo (opcional)
        ensureActivityRecognitionPermission()
    }

    private val requestActivityPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Seja concedido ou não, iniciamos o tracker (ele funciona com fallback sem passo dedicado)
        startTracker()
    }

    private val routePoints: MutableList<Pair<Float, Float>> = mutableListOf()
    private var lastDirectionLabel: String? = null

    // Distância acumulada da rota
    private var cumulativeDistances: MutableList<Double> = mutableListOf()
    private var totalDistance: Double = 0.0

    // Optional: currently active mapId for diagnostics
    private var mapId: String? = null

    // Cache dos POIs para seleção e resolução de nós
    private data class PoiInfo(
        val id: String,
        val x: Float,
        val y: Float,
        val iconName: String?,
        val iconRes: Int?,
        val isStart: Boolean
    )
    private val poisCache = mutableListOf<PoiInfo>()

    // Controle de bounds acumulados para o minimapa
    private var boundsMinX: Float? = null
    private var boundsMinZ: Float? = null
    private var boundsMaxX: Float? = null
    private var boundsMaxZ: Float? = null

    // Broadcast actions/extras para atualizar rota de outras telas
    companion object {
        const val ACTION_UPDATE_ROUTE = "com.example.indoorar.UPDATE_ROUTE"
        const val ACTION_CLEAR_ROUTE = "com.example.indoorar.CLEAR_ROUTE"
        const val ACTION_REQUEST_DESTINATION = "com.example.indoorar.REQUEST_DESTINATION"
        const val EXTRA_WORLD_BOUNDS = "world_bounds" // float[4] = [minX,minZ,maxX,maxZ]
        const val EXTRA_ROUTE = "route_points"       // float[]  = [x0,z0,x1,z1,...]
    }

    private val routeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_ROUTE -> applyRouteFromIntent(intent)
                ACTION_CLEAR_ROUTE -> clearRoute()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        previewView = findViewById(R.id.previewView)
        arrowView = findViewById(R.id.arrowView)
        instructionText = findViewById(R.id.instructionText)
        minimap = findViewById(R.id.minimap)
        btnEscolherDestino = findViewById(R.id.btnEscolherDestino)
        btnLimparRota = findViewById(R.id.btnLimparRota)
        tvDistancia = findViewById(R.id.tvDistancia)
        btnToggleManual = findViewById(R.id.btnToggleManual)
        manualControls = findViewById(R.id.manualControls)

        // Capture mapId if provided via Intent extras
        mapId = intent.getStringExtra("MAP_ID")

        // Começa com a seta apontando para frente e centralizada
        arrowView.rotation = 0f
        arrowView.translationX = 0f
        arrowView.translationY = 0f
        arrowView.visibility = View.GONE // inicia oculta; só aparece com rota ativa

        // Minimap: por padrão não rotaciona com heading (HUD já indica a direção)
        minimap.setRotateWithHeading(false)

        // Tenta aplicar rota/limites vindos por Intent (opcional)
        applyRouteFromIntent(intent)

        // Carrega formas/POIs para preencher o minimapa
        mapId?.let { loadMinimapContent(it) }

        ensureCameraPermission()
        ensureLocationEnabled() // sugere ligar localização do aparelho
        // Se já tem câmera concedida (ex.: retorno do sistema), garantimos o tracker também
        if (hasCameraPermission()) ensureActivityRecognitionPermission()

        btnEscolherDestino.setOnClickListener {
            // Dispara um pedido para abrir um seletor de destino em outra tela (se houver ou para logging).
            sendBroadcast(Intent(ACTION_REQUEST_DESTINATION))
            val id = mapId
            if (id.isNullOrBlank()) {
                Toast.makeText(this, "MAP_ID ausente", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            setInstruction("Carregando POIs…", visible = true)
            val db = FirebaseFirestore.getInstance()
            db.collection("mapas").document(id).collection("pois").get()
                .addOnSuccessListener { snap ->
                    poisCache.clear()
                    val pois = snap.documents.map { d ->
                        val pid = d.getString("id") ?: d.id
                        val iconName = d.getString("iconName")
                        val isStart = d.getBoolean("isStartQR") ?: false
                        val x = (d.getDouble("x") ?: 0.0).toFloat()
                        val y = (d.getDouble("y") ?: 0.0).toFloat()
                        val iconRes = (d.getLong("iconRes")?.toInt())
                        PoiInfo(pid, x, y, iconName, iconRes, isStart)
                    }.sortedBy { it.id }
                    poisCache.addAll(pois)
                    if (pois.size < 2) {
                        setInstruction(null, visible = false)
                        Toast.makeText(this, "Mapa sem POIs suficientes", Toast.LENGTH_LONG).show()
                        return@addOnSuccessListener
                    }
                    val labels = pois.map { p -> if (p.isStart) "${p.iconName ?: p.id} (início)" else p.id }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Escolha o destino")
                        .setItems(labels) { _, which ->
                            val dest = pois[which]
                            setInstruction("Calculando rota…", visible = true)
                            FirestoreGraphLoader.load(id) { res ->
                                runOnUiThread {
                                    res.onFailure { e ->
                                        setInstruction(null, visible = false)
                                        Toast.makeText(this, "Falha ao carregar grafo: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                    res.onSuccess { loaded ->
                                        // Bounds via nodes
                                        applyBoundsFromNodes(loaded.nodes.values.map { it.x to it.y })

                                        // ORIGEM: Posição atual do usuário
                                        val userX = lastUserX ?: initialX ?: 0f
                                        val userZ = lastUserZ ?: initialZ ?: 0f
                                        

                                        // Cria PoiInfo temporário para representar a posição do usuário
                                        val userOrigin = PoiInfo(
                                            id = "user_current_position",
                                            x = userX,
                                            y = userZ,
                                            iconName = null,
                                            iconRes = null,
                                            isStart = false
                                        )

                                        // Garante que origem (usuário) e destino tenham nós válidos
                                        val (enhancedGraph, originNodeId, destNodeId) = ensureNodesExist(
                                            loaded.graph,
                                            loaded.nodes,
                                            userOrigin,
                                            dest
                                        )

                                        // Agora o A* é GARANTIDO de encontrar um caminho
                                        val path = AStarPathfinder.findPath(enhancedGraph, originNodeId, destNodeId)

                                        if (!path.found || path.nodes.isEmpty()) {
                                            // Fallback: cria rota direta entre posição do usuário e destino
                                            setInstruction("Rota criada (linha direta)", visible = true)
                                            val directRoute = listOf(
                                                userX to userZ,
                                                dest.x to dest.y
                                            )
                                            setRoute(directRoute)
                                        } else {
                                            // Densifica e garante que a rota comece exatamente na posição atual do usuário
                                            val rawPoints = PathUtils.densify(path.nodes, 0.25f)
                                            val points = if (rawPoints.isNotEmpty()) {
                                                val fx = rawPoints.first().first
                                                val fz = rawPoints.first().second
                                                val dx = fx - userX
                                                val dz = fz - userZ
                                                val d2 = dx*dx + dz*dz
                                                if (d2 > 0.01f) listOf(userX to userZ) + rawPoints else rawPoints
                                            } else {
                                                listOf(userX to userZ, dest.x to dest.y)
                                            }
                                            setRoute(points)
                                        }
                                    }
                                }
                            }
                        }
                        .setNegativeButton("Cancelar") { _, _ -> setInstruction(null, visible = false) }
                        .show()
                }
                .addOnFailureListener { e ->
                    setInstruction(null, visible = false)
                    Toast.makeText(this, "Falha ao carregar POIs: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }
        btnLimparRota.setOnClickListener {
            clearRoute()
            tvDistancia.text = getString(R.string.distance_placeholder)
        }
        
        // Controles manuais de movimento (para debug/teste quando sensores não funcionam)
        findViewById<android.widget.Button>(R.id.btnMoveUp).setOnClickListener {
            moveUserManually(0f, -0.5f) // Move para cima (Z negativo)
        }
        findViewById<android.widget.Button>(R.id.btnMoveDown).setOnClickListener {
            moveUserManually(0f, 0.5f) // Move para baixo (Z positivo)
        }
        findViewById<android.widget.Button>(R.id.btnMoveLeft).setOnClickListener {
            moveUserManually(-0.5f, 0f) // Move para esquerda (X negativo)
        }
        findViewById<android.widget.Button>(R.id.btnMoveRight).setOnClickListener {
            moveUserManually(0.5f, 0f) // Move para direita (X positivo)
        }

        // Inicializa estado dos controles manuais (iniciam escondidos)
        manualControlsVisible = false
        manualControls.alpha = 0f
        manualControls.translationY = dpToPx(8f)
        manualControls.visibility = View.GONE

        btnToggleManual.setOnClickListener {
            toggleManualControls()
        }
    }

    /** Mostra ou esconde os controles manuais com animação (slide + fade) e animação do botão */
    private fun toggleManualControls() {
        val show = !manualControlsVisible
        manualControlsVisible = show

        if (show) {
            manualControls.visibility = View.VISIBLE
            manualControls.translationY = dpToPx(8f)
            manualControls.alpha = 0f
            manualControls.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withLayer()
                .start()
            btnToggleManual.animate()
                .rotation(90f)
                .setDuration(300)
                .setInterpolator(OvershootInterpolator())
                .start()
        } else {
            manualControls.animate()
                .translationY(dpToPx(8f))
                .alpha(0f)
                .setDuration(250)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction { manualControls.visibility = View.GONE }
                .withLayer()
                .start()
            btnToggleManual.animate()
                .rotation(0f)
                .setDuration(250)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    /**
     * Move o usuário manualmente (para debug quando sensores não funcionam)
     */
    private fun moveUserManually(deltaX: Float, deltaZ: Float) {
        val currentX = lastUserX ?: initialX ?: 0f
        val currentZ = lastUserZ ?: initialZ ?: 0f
        val newX = currentX + deltaX
        val newZ = currentZ + deltaZ
        
        // Atualiza a posição do usuário
        updateUserPose(newX, newZ, 0f)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATE_ROUTE)
            addAction(ACTION_CLEAR_ROUTE)
        }
        ContextCompat.registerReceiver(this, routeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(routeReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        stopTracker()
    }

    // -------- Permissões --------
    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasActivityRecognitionPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun ensureCameraPermission() {
        when {
            hasCameraPermission() -> startCamera()
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> requestCameraPermission.launch(Manifest.permission.CAMERA)
            else -> requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun ensureActivityRecognitionPermission() {
        // Opcional: pedimos para habilitar sensores de passo; se não conceder, seguimos com fallback
        if (hasActivityRecognitionPermission()) startTracker()
        else requestActivityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
    }

    // -------- CameraX --------
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build()
                // Use property access syntax instead of setter method
                preview.surfaceProvider = previewView.surfaceProvider

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (e: Exception) {
                Toast.makeText(this, "Falha ao iniciar câmera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        cameraProvider = null
    }

    // -------- Tracker de sensores (pose ao vivo) --------
    private fun startTracker() {
        if (trackerStarted) return
        // USA APENAS a posição inicial definida (POI StartQR)
        val initX = initialX ?: 0f
        val initZ = initialZ ?: 0f
        
        sensorTracker = SensorFusionTracker(
            context = this,
            mapNorthDegrees = 0f,
            stepLengthMeters = 0.7f,
            onPosition = { x, z, headingRad -> updateUserPose(x, z, headingRad) }
        ).also {
            it.start(initX, initZ)
        }
        trackerStarted = true
    }

    private fun stopTracker() {
        trackerStarted = false
        try { sensorTracker?.stop() } catch (_: Exception) {}
        sensorTracker = null
    }

    // -------- HUD API --------

    /**
     * Atualiza a seta conforme a direção desejada.
     * Aceita: "frente", "direita", "esquerda", "para trás" (ou "tras"/"trás").
     * Aplica rotação e leve deslocamento, com animação suave.
     */
    @Keep
    @Suppress("unused")
    fun updateArrow(direcao: String) {
        val (rotation, dxDp, dyDp) = when (direcao.lowercase()) {
            "frente" -> Triple(0f, 0f, 0f)
            "direita" -> Triple(90f, 56f, 0f)
            "esquerda" -> Triple(-90f, -56f, 0f)
            "para trás", "tras", "trás" -> Triple(180f, 0f, 0f)
            else -> Triple(0f, 0f, 0f)
        }
        val dx = dpToPx(dxDp)
        val dy = dpToPx(dyDp)
        arrowView.animate()
            .rotation(rotation)
            .translationX(dx)
            .translationY(dy)
            .setDuration(300)
            .withLayer()
            .start()
    }

    /** Mostra/atualiza o texto de instrução acima da câmera. */
    @Keep
    @Suppress("unused")
    fun setInstruction(texto: String?, visible: Boolean = true) {
        if (!visible || texto.isNullOrBlank()) {
            instructionText.animate().alpha(0f).setDuration(200).withEndAction {
                instructionText.visibility = View.GONE
            }.start()
        } else {
            instructionText.text = texto
            if (instructionText.visibility != View.VISIBLE) {
                instructionText.alpha = 0f
                instructionText.visibility = View.VISIBLE
            }
            instructionText.animate().alpha(1f).setDuration(200).start()
        }
    }

    /** Ajusta a posição da seta (offset do centro) em dp. */
    @Keep
    @Suppress("unused")
    fun setArrowOffset(dxDp: Float, dyDp: Float) {
        arrowView.animate()
            .translationX(dpToPx(dxDp))
            .translationY(dpToPx(dyDp))
            .setDuration(300)
            .withLayer()
            .start()
    }

    // -------- Minimap helpers (conexão sem ARCore) --------
    @Keep
    @Suppress("unused")
    fun setWorldBounds(minX: Float, minZ: Float, maxX: Float, maxZ: Float) = minimap.setWorldBounds(minX, minZ, maxX, maxZ)

    @Keep
    @Suppress("unused")
    fun setRoute(points: List<Pair<Float, Float>>) {
        // Reinicia estado de chegada quando definimos uma nova rota
        hasArrived = false
        routePoints.clear()
        routePoints.addAll(points)
        minimap.setRoute(points)
        rebuildCumulativeDistances()
        // Se rota tem 1 ponto, já estamos no destino
        if (routePoints.size == 1) {
            lastDirectionLabel = "chegou"
            hasArrived = true
            setInstruction("Você já está no destino", visible = true)
            tvDistancia.text = getString(R.string.distance_arrived)
            showArrivalAnimation()
            if (arrowView.isVisible) {
                arrowView.animate().alpha(0f).setDuration(150).withEndAction { arrowView.visibility = View.GONE }.start()
            }
        } else if (routePoints.size >= 2) {
            setInstruction("Rota definida", visible = true)
            updateDistanceLabelForLastPose()
            if (arrowView.visibility != View.VISIBLE && !hasArrived) {
                arrowView.alpha = 0f
                arrowView.visibility = View.VISIBLE
                arrowView.animate().alpha(1f).setDuration(150).start()
            }
        } else {
            setInstruction(null, visible = false)
            tvDistancia.text = getString(R.string.distance_placeholder)
            if (arrowView.isVisible) {
                arrowView.animate().alpha(0f).setDuration(150).withEndAction { arrowView.visibility = View.GONE }.start()
            }
        }
    }

    @Keep
    @Suppress("unused")
    fun clearRoute() {
        routePoints.clear()
        minimap.clearRoute()
        cumulativeDistances.clear()
        totalDistance = 0.0
        lastDirectionLabel = null
        hasArrived = false
        setInstruction(null, visible = false)
        tvDistancia.text = getString(R.string.distance_placeholder)
        if (arrowView.isVisible) {
            arrowView.animate().alpha(0f).setDuration(150).withEndAction { arrowView.visibility = View.GONE }.start()
        }
    }

    @Keep
    @Suppress("unused")
    fun updateUserPose(x: Float, z: Float, headingRad: Float) {
        lastUserX = x; lastUserZ = z
        minimap.updateUserPose(x, z, headingRad)

        // Remove waypoints da rota conforme o usuário passa por eles
        prunePassedWaypoints(x, z)

        updateGuidanceFromPose(x, z, headingRad)
        updateDistanceLabel(x, z)
    }

    // --- NOVO: projeção do usuário na rota ---
    private data class RouteProjection(
        val segIndex: Int,
        val t: Float,
        val px: Float,
        val pz: Float,
        val along: Double,
        val distToUser: Double
    )

    private fun computeProjectionOnRoute(ux: Float, uz: Float): RouteProjection? {
        if (routePoints.size < 2) return null
        var best: RouteProjection? = null
        var acc = 0.0
        for (i in 0 until routePoints.size - 1) {
            val (ax, az) = routePoints[i]
            val (bx, bz) = routePoints[i + 1]
            val vx = bx - ax; val vz = bz - az
            val wx = ux - ax; val wz = uz - az
            val len2 = (vx * vx + vz * vz)
            val segLen = hypot(vx.toDouble(), vz.toDouble())
            val t = if (len2 <= 1e-6f) 0f else ((wx * vx + wz * vz) / len2).coerceIn(0f, 1f)
            val px = ax + vx * t
            val pz = az + vz * t
            val d = hypot((px - ux).toDouble(), (pz - uz).toDouble())
            val along = acc + segLen * t
            val isBetter = best?.distToUser?.let { d < it } ?: true
            if (isBetter) {
                best = RouteProjection(i, t, px, pz, along, d)
            }
            acc += segLen
        }
        return best
    }

    /**
     * Remove pontos da rota que o usuário já passou, encurtando a linha a partir da projeção atual.
     */
    private fun prunePassedWaypoints(ux: Float, uz: Float) {
        if (routePoints.size <= 1) return

        val arrivalThresholdM = 1.8
        val keepTailBehindM = 0.6

        if (cumulativeDistances.size != routePoints.size) rebuildCumulativeDistances()
        val initialProj = computeProjectionOnRoute(ux, uz) ?: return

        val remaining = (totalDistance - initialProj.along).coerceAtLeast(0.0)
        if (remaining <= arrivalThresholdM) {
            lastDirectionLabel = "chegou"
            hasArrived = true
            setInstruction("Você chegou ao destino!", visible = true)
            tvDistancia.text = getString(R.string.distance_arrived)
            showArrivalAnimation()
            if (arrowView.isVisible) {
                arrowView.animate().alpha(0f).setDuration(150).withEndAction { arrowView.visibility = View.GONE }.start()
            }
            // Mantemos a rota por um curto período para feedback visual no minimapa, mas garantimos que a seta não reapareça
            arrowView.postDelayed({ clearRoute() }, 1500)
            return
        }

        // Remove pontos antes do rastro permitido atrás do usuário
        val cutBefore = (initialProj.along - keepTailBehindM).coerceAtLeast(0.0)
        var keepFromIndex = 0
        for (i in 0 until cumulativeDistances.size) {
            if (cumulativeDistances[i] + 1e-6 < cutBefore) keepFromIndex = i + 1 else break
        }
        if (keepFromIndex > 0) {
            repeat(keepFromIndex) { if (routePoints.size > 1) routePoints.removeAt(0) }
            rebuildCumulativeDistances()
        }

        // Recalcula a projeção para a rota atual e ajusta o primeiro ponto para começar exatamente da projeção
        computeProjectionOnRoute(ux, uz)?.let { proj2 ->
            if (routePoints.size >= 2) {
                routePoints[0] = proj2.px to proj2.pz
            }
        }
        // Empurra sempre a rota atualizada para o minimapa
        minimap.setRoute(routePoints)
        rebuildCumulativeDistances()
    }

    // ---- Distância restante ----
    private fun rebuildCumulativeDistances() {
        cumulativeDistances.clear()
        totalDistance = 0.0
        if (routePoints.size < 2) return
        cumulativeDistances.add(0.0)
        for (i in 1 until routePoints.size) {
            val (ax, az) = routePoints[i - 1]
            val (bx, bz) = routePoints[i]
            totalDistance += hypot((bx - ax).toDouble(), (bz - az).toDouble())
            cumulativeDistances.add(totalDistance)
        }
    }

    private fun updateDistanceLabelForLastPose() {
        when {
            routePoints.isEmpty() -> tvDistancia.text = getString(R.string.distance_placeholder)
            routePoints.size == 1 -> tvDistancia.text = getString(R.string.distance_arrived)
            else -> {
                val ux = lastUserX
                val uz = lastUserZ
                if (ux != null && uz != null) updateDistanceLabel(ux, uz)
                else tvDistancia.text = getString(R.string.distance_placeholder)
            }
        }
    }

    private fun updateDistanceLabel(ux: Float, uz: Float) {
        if (routePoints.isEmpty()) { tvDistancia.text = getString(R.string.distance_placeholder); return }
        if (routePoints.size == 1) { tvDistancia.text = getString(R.string.distance_arrived); return }
        if (cumulativeDistances.size != routePoints.size) { tvDistancia.text = getString(R.string.distance_placeholder); return }
        val proj = computeProjectionOnRoute(ux, uz)
        val along = proj?.along ?: 0.0
        val remaining = (totalDistance - along).coerceAtLeast(0.0)
        tvDistancia.text = if (remaining < 1.0) getString(R.string.distance_arrived) else getString(R.string.distance_meters, remaining)
    }

    // --- Guidance logic: decide arrow/instruction from route + pose ---
    private fun updateGuidanceFromPose(x: Float, z: Float, headingRad: Float) {
        // Se já marcamos chegada, não reexibir setas
        if (hasArrived) {
            if (arrowView.isVisible) {
                arrowView.animate().alpha(0f).setDuration(150).withEndAction { arrowView.visibility = View.GONE }.start()
            }
            return
        }
        if (routePoints.isEmpty()) {
            // No active route: hide arrow
            if (arrowView.isVisible) {
                arrowView.animate().alpha(0f).setDuration(150).withEndAction { arrowView.visibility = View.GONE }.start()
            }
            return
        }
        if (routePoints.size == 1) {
            if (lastDirectionLabel != "chegou") {
                lastDirectionLabel = "chegou"
                // Arrived: hide arrow and show message
                if (arrowView.isVisible) {
                    arrowView.animate().alpha(0f).setDuration(150).withEndAction { arrowView.visibility = View.GONE }.start()
                }
                setInstruction("Você já está no destino", visible = true)
            }
            return
        }
        // Ensure arrow is visible when we have an active multi-point route
        if (arrowView.visibility != View.VISIBLE && !hasArrived) {
            arrowView.alpha = 0f
            arrowView.visibility = View.VISIBLE
            arrowView.animate().alpha(1f).setDuration(150).start()
        }
        // Find a target point ahead along route
        val target = findTargetPointAhead(x, z)
        val (tx, tz) = target ?: return
        val dx = tx - x
        val dz = tz - z
        val angleToTarget = kotlin.math.atan2(dx.toDouble(), dz.toDouble()).toFloat()
        val delta = normalizeAngle(angleToTarget - headingRad)
        val absDeg = kotlin.math.abs(Math.toDegrees(delta.toDouble()))
        val label = when {
            absDeg <= 30 -> "frente"
            absDeg >= 150 -> "para trás"
            delta > 0 -> "direita"
            else -> "esquerda"
        }
        if (label != lastDirectionLabel) {
            lastDirectionLabel = label
            updateArrow(label)
            setInstruction(
                when (label) {
                    "frente" -> "Siga em frente"
                    "direita" -> "Vire à direita"
                    "esquerda" -> "Vire à esquerda"
                    else -> "Retorne"
                },
                visible = true
            )
        }
    }

    private fun findTargetPointAhead(x: Float, z: Float): Pair<Float, Float>? {
        if (routePoints.isEmpty()) return null
        // Choose the nearest route segment projection ahead of the user
        var best: Pair<Float, Float>? = null
        var bestDist2 = Float.MAX_VALUE
        for (i in 0 until routePoints.size - 1) {
            val a = routePoints[i]
            val b = routePoints[i + 1]
            val proj = projectPointOnSegment(x, z, a.first, a.second, b.first, b.second)
            val px = proj.first; val pz = proj.second
            val dx = px - x; val dz = pz - z
            val d2 = dx * dx + dz * dz
            if (d2 < bestDist2) { bestDist2 = d2; best = px to pz }
        }
        // If nothing, fallback to last point
        return best ?: routePoints.last()
    }

    private fun projectPointOnSegment(px: Float, pz: Float, ax: Float, az: Float, bx: Float, bz: Float): Pair<Float, Float> {
        val vx = bx - ax; val vz = bz - az
        val wx = px - ax; val wz = pz - az
        val len2 = vx * vx + vz * vz
        if (len2 <= 1e-6f) return ax to az
        val t = ((wx * vx + wz * vz) / len2).coerceIn(0f, 1f)
        return (ax + vx * t) to (az + vz * t)
    }

    private fun normalizeAngle(a: Float): Float {
        var ang = a
        val twoPi = (Math.PI * 2).toFloat()
        while (ang <= -Math.PI) ang += twoPi
        while (ang > Math.PI) ang -= twoPi
        return ang
    }

    // -------- Intent/broadcast helpers --------
    private fun applyRouteFromIntent(intent: Intent?) {
        intent ?: return
        // Bounds
        val bounds = intent.getFloatArrayExtra(EXTRA_WORLD_BOUNDS)
        if (bounds != null && bounds.size >= 4) {
            setWorldBounds(bounds[0], bounds[1], bounds[2], bounds[3])
        }
        // Route points
        val packed = intent.getFloatArrayExtra(EXTRA_ROUTE)
        if (packed != null && packed.size >= 2) {
            val list = ArrayList<Pair<Float, Float>>(packed.size / 2)
            var i = 0
            while (i + 1 < packed.size) {
                list += packed[i] to packed[i + 1]
                i += 2
            }
            setRoute(list)
        }
    }

    private fun dpToPx(dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    private var sensorTracker: SensorFusionTracker? = null
    private var trackerStarted: Boolean = false
    private var initialX: Float? = null
    private var initialZ: Float? = null
    private var lastUserX: Float? = null
    private var lastUserZ: Float? = null
    // Controla se já marcamos o estado de chegada (para não reexibir setas)
    private var hasArrived: Boolean = false

    @Suppress("unused")
    private fun showDiagDialog(title: String, message: String) {
        try {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        } catch (_: Exception) {
            Toast.makeText(this, "$title: $message", Toast.LENGTH_LONG).show()
        }
    }

    // Pequena animação de "chegada" para dar feedback visual
    private fun showArrivalAnimation() {
        // Garante o texto visível e com mensagem
        if (instructionText.visibility != View.VISIBLE) {
            instructionText.alpha = 0f
            instructionText.visibility = View.VISIBLE
        }
        instructionText.animate().alpha(1f).setDuration(200).start()
        // Efeito de "pulso" no texto
        instructionText.scaleX = 0.9f
        instructionText.scaleY = 0.9f
        instructionText.animate()
            .scaleX(1.05f)
            .scaleY(1.05f)
            .setDuration(220)
            .withEndAction {
                instructionText.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .start()
            }
            .start()
        // Some com a seta suavemente, caso ainda visível
        if (arrowView.isVisible) {
            arrowView.animate()
                .alpha(0f)
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(180)
                .withEndAction { arrowView.visibility = View.GONE }
                .start()
        }
    }

    // --------- Minimap content loading ---------
    private fun loadMinimapContent(mapId: String) {
        // limpa
        minimap.clearFormas()
        minimap.clearPois()
        poisCache.clear()
        resetBounds()

        val db = FirebaseFirestore.getInstance()
        val mapaRef = db.collection("mapas").document(mapId)

        // NODES: apenas para bounds
        mapaRef.collection("nodes").get()
            .addOnSuccessListener { snap ->
                val pts = snap.documents.mapNotNull { d ->
                    val x = (d.getDouble("x") ?: return@mapNotNull null).toFloat()
                    val y = (d.getDouble("y") ?: return@mapNotNull null).toFloat()
                    x to y
                }
                applyBoundsFromNodes(pts)
            }
            .addOnFailureListener { /* ignore */ }

        // FORMAS: desenha retângulos básicos
        mapaRef.collection("formas").get()
            .addOnSuccessListener { snap ->
                snap.documents.forEach { d ->
                    val pos = (d.get("posicao") as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }
                    val tam = (d.get("tamanho") as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }
                    val tipo = d.getString("tipo") ?: ""
                    val corStr = d.getString("cor")
                    val isRect = tipo == "retangulo" || tipo == "quadrado"
                    if (pos != null && tam != null && pos.size >= 2 && tam.size >= 2 && isRect) {
                        val x = pos[0]; val y = pos[1]
                        val h = tam[0]; val w = tam[1] // salvo como [altura, largura]
                        val color: Int = try {
                            corStr?.toColorInt() ?: 0xFF888888.toInt()
                        } catch (_: Exception) { 0xFF888888.toInt() }
                        minimap.addForma(x, y, w, h, color)
                        accumulateRect(x, y, w, h)
                    }
                }
                pushBoundsToMinimap()
            }
            .addOnFailureListener { /* ignore */ }

        // POIs: icones e origem
        mapaRef.collection("pois").get()
            .addOnSuccessListener { snap ->
                snap.documents.forEach { d ->
                    val pid = d.getString("id") ?: d.id
                    val x = (d.getDouble("x") ?: 0.0).toFloat()
                    val y = (d.getDouble("y") ?: 0.0).toFloat()
                    val iconName = d.getString("iconName")
                    val iconRes = (d.getLong("iconRes")?.toInt())
                    val isStart = d.getBoolean("isStartQR") ?: false
                    poisCache += PoiInfo(pid, x, y, iconName, iconRes, isStart)
                    val res = iconRes ?: mapIconNameToRes(iconName)
                    val poiColor = Color.rgb(33, 150, 243) // azul consistente com tema
                    minimap.addPoi(x, y, poiColor, res, isStart)
                    accumulatePoint(x, y)
                }
                // Define pose inicial: POI de início, senão primeiro POI, senão centro dos bounds
                val startPoi = poisCache.firstOrNull { it.isStart }
                val (sx, sz) = when {
                    startPoi != null -> startPoi.x to startPoi.y
                    poisCache.isNotEmpty() -> poisCache.first().x to poisCache.first().y
                    else -> {
                        val cx = ((boundsMinX ?: 0f) + (boundsMaxX ?: 0f)) / 2f
                        val cz = ((boundsMinZ ?: 0f) + (boundsMaxZ ?: 0f)) / 2f
                        cx to cz
                    }
                }
                initialX = sx; initialZ = sz
                // Use central API to keep lastUserX/lastUserZ in sync
                updateUserPose(sx, sz, 0f)
                // If the tracker was already running, restart from new origin
                if (trackerStarted) {
                    stopTracker()
                    startTracker()
                }
                pushBoundsToMinimap()
                minimap.invalidate()
            }
            .addOnFailureListener { /* ignore */ }
    }

    // --------- Location (ligar localização) ---------
    private fun ensureLocationEnabled() {
        try {
            val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return
            val enabled =
                lm.isLocationEnabled
            if (!enabled) {
                AlertDialog.Builder(this)
                    .setTitle("Ativar localização")
                    .setMessage("Para melhor navegação, ative a localização do dispositivo.")
                    .setPositiveButton("Abrir configurações") { _, _ ->
                        try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) {}
                    }
                    .setNegativeButton("Agora não", null)
                    .show()
            }
        } catch (_: Exception) { }
    }

    // -------- Resolução de nós para POIs --------
    @Suppress("unused")
    private fun resolveOriginNodeId(nodes: Map<String, Node>): String? {
        // Prefer the nearest node to the last known user pose
        val ux = lastUserX; val uz = lastUserZ
        if (ux != null && uz != null) {
            return resolveNodeIdForCoord(ux, uz, nodes)
        }
        // Fallback: use initial pose if available
        val ix = initialX; val iz = initialZ
        if (ix != null && iz != null) return resolveNodeIdForCoord(ix, iz, nodes)
        // Final fallback: first node id
        return nodes.values.firstOrNull()?.id
    }

    private fun resolveNodeIdForCoord(px: Float, py: Float, nodes: Map<String, Node>): String? {
        if (nodes.isEmpty()) return null
        var bestId: String? = null
        var bestD2 = Float.MAX_VALUE
        nodes.values.forEach { n ->
            val dx = n.x - px
            val dy = n.y - py
            val d2 = dx*dx + dy*dy
            if (d2 < bestD2) { bestD2 = d2; bestId = n.id }
        }
        return bestId
    }

    private fun resolveNodeIdForPoi(poiId: String, px: Float, py: Float, nodes: Map<String, Node>): String? {
        // 1) Exact node id match
        nodes[poiId]?.let { return it.id }
        // 2) Node advertising this POI id
        nodes.values.firstOrNull { it.poiIds.contains(poiId) }?.let { return it.id }
        // 3) Nearest node by coordinates
        return resolveNodeIdForCoord(px, py, nodes)
    }

    /**
     * Garante que origem e destino tenham nós no grafo, criando nós temporários se necessário.
     * Retorna: Triple(grafo aprimorado, ID do nó de origem, ID do nó de destino)
     */
    private fun ensureNodesExist(
        originalGraph: com.example.indoorar.graph.Graph,
        originalNodes: Map<String, Node>,
        originPoi: PoiInfo?,
        destPoi: PoiInfo
    ): Triple<com.example.indoorar.graph.Graph, String, String> {
        val mutableNodes = originalNodes.toMutableMap()
        val newEdges = mutableListOf<com.example.indoorar.graph.Edge>()

        // Resolve ou cria nó de origem
        val originNodeId = if (originPoi != null) {
            resolveNodeIdForPoi(originPoi.id, originPoi.x, originPoi.y, originalNodes)
                ?: createTempNode(originPoi.id, originPoi.x, originPoi.y, mutableNodes, newEdges)
        } else {
            // Se não tem origem, usa o primeiro nó disponível
            originalNodes.values.firstOrNull()?.id ?: "temp_origin"
        }

        // Resolve ou cria nó de destino
        val destNodeId = resolveNodeIdForPoi(destPoi.id, destPoi.x, destPoi.y, originalNodes)
            ?: createTempNode(destPoi.id, destPoi.x, destPoi.y, mutableNodes, newEdges)

        // Se criamos novos nós, precisamos reconstruir o grafo
        return if (newEdges.isNotEmpty()) {
            // Extrai todas as edges originais do grafo
            val allEdges = mutableListOf<com.example.indoorar.graph.Edge>()
            originalGraph.adj.forEach { (_, edges) ->
                edges.forEach { edge ->
                    allEdges.add(edge)
                }
            }
            allEdges.addAll(newEdges)

            val enhancedGraph = com.example.indoorar.graph.Graph.from(
                mutableNodes.values.toList(),
                allEdges,
                undirected = true
            )
            Triple(enhancedGraph, originNodeId, destNodeId)
        } else {
            Triple(originalGraph, originNodeId, destNodeId)
        }
    }

    /**
     * Cria um nó temporário e o conecta aos 3 nós mais próximos do grafo.
     */
    private fun createTempNode(
        poiId: String,
        x: Float,
        y: Float,
        nodes: MutableMap<String, Node>,
        edges: MutableList<com.example.indoorar.graph.Edge>
    ): String {
        val tempNodeId = "temp_$poiId"
        val tempNode = Node(id = tempNodeId, x = x, y = y, poiIds = listOf(poiId))
        nodes[tempNodeId] = tempNode

        // Conecta aos 3 nós mais próximos
        val nearestNodes = nodes.values
            .filter { it.id != tempNodeId }
            .map { node ->
                val dx = node.x - x
                val dy = node.y - y
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                node to dist
            }
            .sortedBy { it.second }
            .take(3)

        nearestNodes.forEach { (node, dist) ->
            edges.add(com.example.indoorar.graph.Edge(tempNodeId, node.id, dist))
        }

        return tempNodeId
    }

    private fun mapIconNameToRes(name: String?): Int? = when (name) {
        "door" -> R.drawable.ic_door_azul
        "stairs" -> R.drawable.ic_stairs_azul
        "elevator" -> R.drawable.ic_elevator_azul
        "bathroom" -> R.drawable.ic_banheiro_azul
        "fire_extinguisher" -> R.drawable.ic_extintor_azul
        else -> null
    }

    private fun resetBounds() {
        boundsMinX = null; boundsMinZ = null; boundsMaxX = null; boundsMaxZ = null
    }
    private fun accumulatePoint(x: Float, z: Float) {
        boundsMinX = (boundsMinX ?: x).coerceAtMost(x)
        boundsMinZ = (boundsMinZ ?: z).coerceAtMost(z)
        boundsMaxX = (boundsMaxX ?: x).coerceAtLeast(x)
        boundsMaxZ = (boundsMaxZ ?: z).coerceAtLeast(z)
    }
    private fun accumulateRect(x: Float, z: Float, w: Float, h: Float) {
        accumulatePoint(x, z)
        accumulatePoint(x + w, z + h)
    }
    private fun applyBoundsFromNodes(nodes: List<Pair<Float, Float>>) {
        nodes.forEach { (x, y) -> accumulatePoint(x, y) }
        pushBoundsToMinimap()
    }
    private fun pushBoundsToMinimap() {
        val minX = boundsMinX; val minZ = boundsMinZ; val maxX = boundsMaxX; val maxZ = boundsMaxZ
        if (minX != null && minZ != null && maxX != null && maxZ != null) {
            // pequena margem
            val padX = (maxX - minX) * 0.05f
            val padZ = (maxZ - minZ) * 0.05f
            minimap.setWorldBounds(minX - padX, minZ - padZ, maxX + padX, maxZ + padZ)
        }
    }
}
