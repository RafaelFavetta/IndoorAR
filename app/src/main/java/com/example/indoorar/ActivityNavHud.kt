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
import kotlin.math.hypot
import androidx.appcompat.app.AlertDialog
import com.google.firebase.firestore.FirebaseFirestore
import com.example.indoorar.graph.FirestoreGraphLoader
import com.example.indoorar.graph.AStarPathfinder
import com.example.indoorar.graph.PathUtils

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

        // Capture mapId if provided via Intent extras
        mapId = intent.getStringExtra("MAP_ID")

        // Começa com a seta apontando para frente e centralizada
        arrowView.rotation = 0f
        arrowView.translationX = 0f
        arrowView.translationY = 0f

        // Minimap: por padrão não rotaciona com heading (HUD já indica a direção)
        minimap.setRotateWithHeading(false)

        // Tenta aplicar rota/limites vindos por Intent (opcional)
        applyRouteFromIntent(intent)

        ensureCameraPermission()
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
                    val pois = snap.documents.map { d ->
                        Triple(
                            d.getString("id") ?: d.id,
                            d.getString("iconName") ?: "poi",
                            d.getBoolean("isStartQR") ?: false
                        )
                    }.sortedBy { it.first }
                    if (pois.size < 2) {
                        setInstruction(null, visible = false)
                        Toast.makeText(this, "Mapa sem POIs suficientes", Toast.LENGTH_LONG).show()
                        return@addOnSuccessListener
                    }
                    val startId = pois.firstOrNull { it.third }?.first
                    val labels = pois.map { (pid, icon, isStart) ->
                        if (isStart) "$icon (início)" else pid
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Escolha o destino")
                        .setItems(labels) { _, which ->
                            val destId = pois[which].first
                            if (destId == startId) {
                                Toast.makeText(this, "Destino igual à origem", Toast.LENGTH_SHORT).show()
                                return@setItems
                            }
                            setInstruction("Calculando rota…", visible = true)
                            FirestoreGraphLoader.load(id) { res ->
                                runOnUiThread {
                                    res.onFailure { e ->
                                        setInstruction(null, visible = false)
                                        Toast.makeText(this, "Falha ao carregar grafo: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                    res.onSuccess { loaded ->
                                        // Bounds via nodes
                                        val xs = loaded.nodes.values.map { it.x }
                                        val ys = loaded.nodes.values.map { it.y }
                                        if (xs.isNotEmpty() && ys.isNotEmpty()) {
                                            val minX = xs.minOrNull() ?: 0f
                                            val maxX = xs.maxOrNull() ?: 10f
                                            val minY = ys.minOrNull() ?: 0f
                                            val maxY = ys.maxOrNull() ?: 10f
                                            setWorldBounds(minX, minY, maxX, maxY)
                                        }
                                        val origin = startId ?: loaded.poiNodeIds.firstOrNull()
                                        if (origin == null) {
                                            setInstruction(null, visible = false)
                                            Toast.makeText(this, "Sem origem definida", Toast.LENGTH_LONG).show()
                                            return@onSuccess
                                        }
                                        val path = AStarPathfinder.findPath(loaded.graph, origin, destId)
                                        if (!path.found || path.nodes.isEmpty()) {
                                            setInstruction(null, visible = false)
                                            Toast.makeText(this, "Sem caminho entre $origin e $destId", Toast.LENGTH_LONG).show()
                                        } else {
                                            val points = PathUtils.densify(path.nodes, 0.25f)
                                            setRoute(points)
                                            setInstruction("Rota definida", visible = true)
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
            tvDistancia.text = "Distância: --"
        }
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
        val (initX, initZ) = if (routePoints.isNotEmpty()) routePoints.first() else 0f to 0f
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
        routePoints.clear()
        routePoints.addAll(points)
        minimap.setRoute(points)
        rebuildCumulativeDistances()
        updateDistanceLabelForLastPose()
    }

    @Keep
    @Suppress("unused")
    fun clearRoute() {
        routePoints.clear()
        minimap.clearRoute()
        cumulativeDistances.clear()
        totalDistance = 0.0
        lastDirectionLabel = null
        setInstruction(null, visible = false)
        tvDistancia.text = "Distância: --"
    }

    @Keep
    @Suppress("unused")
    fun updateUserPose(x: Float, z: Float, headingRad: Float) {
        minimap.updateUserPose(x, z, headingRad)
        updateGuidanceFromPose(x, z, headingRad)
        updateDistanceLabel(x, z)
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
        // Não armazenamos a última pose explicitamente; distância será atualizada no próximo updateUserPose
        if (routePoints.size < 2) {
            tvDistancia.text = "Distância: --"
        }
    }

    private fun updateDistanceLabel(ux: Float, uz: Float) {
        if (routePoints.size < 2 || cumulativeDistances.size != routePoints.size) {
            tvDistancia.text = "Distância: --"; return
        }
        val distAlong = distanceAlongRoute(ux, uz)
        val remaining = (totalDistance - distAlong).coerceAtLeast(0.0)
        tvDistancia.text = if (remaining < 1.0) {
            "Você chegou"
        } else {
            "Distância: %.1f m".format(remaining)
        }
    }

    private fun distanceAlongRoute(ux: Float, uz: Float): Double {
        if (routePoints.size < 2) return 0.0
        var bestDist = Double.MAX_VALUE
        var bestAlong = 0.0
        var acc = 0.0
        for (i in 0 until routePoints.size - 1) {
            val (ax, az) = routePoints[i]
            val (bx, bz) = routePoints[i + 1]
            val vx = bx - ax; val vz = bz - az
            val wx = ux - ax; val wz = uz - az
            val len2 = (vx * vx + vz * vz)
            val t = if (len2 <= 1e-6f) 0f else ((wx * vx + wz * vz) / len2).coerceIn(0f, 1f)
            val px = ax + vx * t
            val pz = az + vz * t
            val d = hypot((px - ux).toDouble(), (pz - uz).toDouble())
            if (d < bestDist) {
                bestDist = d
                bestAlong = acc + hypot(vx.toDouble(), vz.toDouble()) * t
            }
            acc += hypot(vx.toDouble(), vz.toDouble())
        }
        return bestAlong
    }

    // --- Guidance logic: decide arrow/instruction from route + pose ---
    private fun updateGuidanceFromPose(x: Float, z: Float, headingRad: Float) {
        if (routePoints.size < 2) return
        // Find a target point ahead along route
        val target = findTargetPointAhead(x, z)
        val (tx, tz) = target ?: return
        val dx = tx - x
        val dz = tz - z
        // angle between user's forward (headingRad) and vector to target
        val angleToTarget = kotlin.math.atan2(dx.toDouble(), dz.toDouble()).toFloat()
        val delta = normalizeAngle(angleToTarget - headingRad)
        val absDeg = kotlin.math.abs(Math.toDegrees(delta.toDouble()))
        val label = when {
            absDeg <= 30 -> "frente"
            absDeg >= 150 -> "para trás"
            delta > 0 -> "direita" // right-hand turn
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
        if (packed != null && packed.size >= 4) {
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
}