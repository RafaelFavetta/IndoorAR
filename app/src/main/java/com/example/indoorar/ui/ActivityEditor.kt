package com.example.indoorar.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import com.google.firebase.firestore.FirebaseFirestore
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.indoorar.BaseActivity
import com.example.indoorar.R
import com.example.indoorar.ui.editor.AttributePanelController
import com.example.indoorar.ui.editor.MapEditorView
import com.example.indoorar.ui.editor.TutorialOverlay
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import androidx.appcompat.widget.AppCompatImageButton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.google.firebase.auth.FirebaseAuth
import android.text.InputType
import android.view.ViewGroup
import androidx.core.graphics.toColorInt
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import com.google.firebase.firestore.SetOptions
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.core.view.isVisible
import android.content.Context
import androidx.core.content.edit

class ActivityEditor : BaseActivity() {

    private lateinit var mapEditor: MapEditorView
    private lateinit var btnSalvarMapa: MaterialButton
    private lateinit var poiCard: MaterialCardView
    private lateinit var formasCard: MaterialCardView
    private lateinit var brushCard: MaterialCardView
    private lateinit var cursorCard: MaterialCardView
    private lateinit var btnMergeShapes: MaterialButton
    private lateinit var btnPreviewMode: MaterialButton
    private lateinit var btnHelpTutorial: AppCompatImageButton

    private var mapIdFromIntent: String? = null

    private val cadastroLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        btnSalvarMapa.isEnabled = true
        if (result.resultCode == RESULT_OK) {
            val mapId = result.data?.getStringExtra("MAP_ID")
            if (!mapId.isNullOrBlank()) {
                salvarEstruturaNoMapa(mapId)
            } else {
                Toast.makeText(this, "Falha: MAP_ID não retornado", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Cadastro cancelado", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        mapIdFromIntent = intent.getStringExtra("MAP_ID")

        bindViews()
        // If opened with a MAP_ID, load existing map into the editor for editing
        mapIdFromIntent?.let { mid ->
            loadMapIntoEditor(mid)
        }

        setupToolButtons()
        setupAttributePanel()
        setupPoiClicks()
        setupFormasClicks()
        setupBrushClicks()
        setupCursorClicks()
        // Position any visible tool cards initially (safety on rotate)
        alignCardAbove(R.id.cardCursor, R.id.linearcursor)
        alignCardAbove(R.id.cardBrush, R.id.linearbrush)
        alignCardAbove(R.id.cardFormas, R.id.linearformas)
        alignCardAbove(R.id.cardPoi, R.id.linearpoi)

        // modo edição (preview desligado)
        btnPreviewMode.isChecked = false
        btnPreviewMode.contentDescription = "Ativar preview"
        try {
            btnPreviewMode.setIconResource(R.drawable.ic_eye_preview_inactive)
        } catch (e: Exception) {
            btnPreviewMode.icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_eye_preview_inactive)
        }

        // Listener para a View avisar a Activity quando a ferramenta mudar
        mapEditor.onToolChangedListener = { newTool ->
            updateSelectedButtonUI(newTool)
            // Esconde/mostra cards conforme ferramenta
            poiCard.visibility = if (newTool == Tool.POI) View.VISIBLE else View.GONE
            formasCard.visibility = if (newTool == Tool.FORMAS) View.VISIBLE else View.GONE
            brushCard.visibility = if (newTool == Tool.BRUSH) View.VISIBLE else View.GONE
            if (newTool != Tool.CURSOR) {
                cursorCard.visibility = View.GONE
                // also ensure eraser off when leaving cursor
                findViewById<SwitchMaterial>(R.id.cursorEraserSwitch)?.isChecked = false
                mapEditor.setEraserEnabled(false)
            }
        }

        // Fora da toolbar: sempre fechar popups antes de agir
        btnSalvarMapa.setOnClickListener {
            hideAllToolCards()
            onSalvarClick()
        }

        btnMergeShapes.setOnClickListener {
            hideAllToolCards()
            mapEditor.mergeEdgesAndAdjustCorners()
            Toast.makeText(this, "Merge aplicado", Toast.LENGTH_SHORT).show()
        }
        btnPreviewMode.setOnClickListener {
            hideAllToolCards()
            // Toggle do estado
            val enablePreview = !mapEditor.isPreviewMode()
            mapEditor.setPreviewMode(enablePreview)
            btnPreviewMode.isChecked = enablePreview
            if (enablePreview) {
                // Entrando em preview
                mapEditor.setTool(Tool.CURSOR)
                // Limpa seleção
                mapEditor.actions.forEach { act ->
                    when (act) {
                        is Action.Shape -> act.selected = false
                        is Action.Poi -> act.selected = false
                        is Action.Text -> act.selected = false
                        else -> {}
                    }
                }
                // Fecha cards de ferramentas
                hideAllToolCards()
                // Oculta painel de atributos
                findViewById<View>(R.id.painelAtributos)?.visibility = View.GONE
                btnPreviewMode.contentDescription = "Desativar preview"
                // set active icon
                try {
                    btnPreviewMode.setIconResource(R.drawable.ic_eye_preview_active)
                } catch (e: Exception) {
                    btnPreviewMode.icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_eye_preview_active)
                }
                Toast.makeText(this, "Preview ativo", Toast.LENGTH_SHORT).show()
            } else {
                btnPreviewMode.contentDescription = "Ativar preview"
                // revert to inactive icon
                try {
                    btnPreviewMode.setIconResource(R.drawable.ic_eye_preview_inactive)
                } catch (e: Exception) {
                    btnPreviewMode.icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_eye_preview_inactive)
                }
                // Voltando para edição
                // Mantém painel de atributos oculto até o usuário selecionar algo novamente
                Toast.makeText(this, "Preview desligado", Toast.LENGTH_SHORT).show()
            }
        }

        // Tutorial: auto-show na primeira vez e botão de replay
        btnHelpTutorial.setOnClickListener {
            startEditorTour(force = true)
        }
        if (shouldShowEditorTour()) {
            startEditorTour(force = false)
        }
    }

    private fun onSalvarClick() {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, "Faça login para salvar", Toast.LENGTH_SHORT).show()
            return
        }
        btnSalvarMapa.isEnabled = false
        val db = FirebaseFirestore.getInstance()
        db.collection("usuarios").document(user.uid).get()
            .addOnSuccessListener { doc ->
                val tipo = doc.getString("tipoConta")
                if (tipo != "maker") {
                    btnSalvarMapa.isEnabled = true
                    Toast.makeText(this, "Apenas maker pode criar mapas", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }
                // Se já temos um MAP_ID (veio da HomeMaker), salva direto
                mapIdFromIntent?.let { existingId ->
                    salvarEstruturaNoMapa(existingId)
                    return@addOnSuccessListener
                }
                // Caso contrário, abre a tela de cadastro de metadados
                cadastroLauncher.launch(Intent(this, com.example.indoorar.ActivityCadastrarMapa::class.java))
            }
            .addOnFailureListener { e ->
                btnSalvarMapa.isEnabled = true
                Toast.makeText(this, "Falha ao verificar usuário: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun salvarEstruturaNoMapa(mapId: String) {
        val db = FirebaseFirestore.getInstance()
        val mapaRef = db.collection("mapas").document(mapId)

        // Primeiro, buscamos os documentos existentes nas coleções para removê-los antes de regravar
        val formasColl = mapaRef.collection("formas").get()
        val poisColl = mapaRef.collection("pois").get()
        val nodesColl = mapaRef.collection("nodes").get()
        val edgesColl = mapaRef.collection("edges").get()

        Toast.makeText(this, "Salvando mapa...", Toast.LENGTH_SHORT).show()

        // Encadeia as leituras (ok para este caso). Depois constrói um batch que apaga antigos e escreve novos.
        formasColl.addOnSuccessListener { formasSnap ->
            poisColl.addOnSuccessListener { poisSnap ->
                nodesColl.addOnSuccessListener { nodesSnap ->
                    edgesColl.addOnSuccessListener { edgesSnap ->
                        val batch = db.batch()
                        // Remove documentos antigos
                        formasSnap.documents.forEach { batch.delete(it.reference) }
                        poisSnap.documents.forEach { batch.delete(it.reference) }
                        nodesSnap.documents.forEach { batch.delete(it.reference) }
                        edgesSnap.documents.forEach { batch.delete(it.reference) }

                        // Atualiza pxPerMeter no documento principal
                        batch.set(mapaRef, mapOf("pxPerMeter" to mapEditor.pxPerMeter), SetOptions.merge())

                        // Recria FORMAS
                        mapEditor.actions.filterIsInstance<Action.Shape>().forEach { shape ->
                            val xPx = min(shape.start.x, shape.end.x)
                            val yPx = min(shape.start.y, shape.end.y)
                            val wPx = abs(shape.end.x - shape.start.x)
                            val hPx = abs(shape.end.y - shape.start.y)
                            val formaDoc = mapaRef.collection("formas").document()
                            val tipoStr = when (shape.type) {
                                Action.ShapeType.RECTANGLE -> "retangulo"
                                Action.ShapeType.SQUARE -> "quadrado"
                                Action.ShapeType.CIRCLE -> "circulo"
                                Action.ShapeType.TRIANGLE -> "triangulo"
                                Action.ShapeType.LINE -> "linha"
                            }
                            val dataForma = mapOf(
                                "cor" to String.format("#%06X", (0xFFFFFF and shape.fillColor)),
                                "nome" to shape.nome,
                                "posicao" to listOf(mapEditor.pxToMeters(xPx), mapEditor.pxToMeters(yPx)),
                                "rotacao" to shape.rotation,
                                "tamanho" to listOf(mapEditor.pxToMeters(hPx), mapEditor.pxToMeters(wPx)),
                                "tipo" to tipoStr,
                                "isWalkable" to shape.isWalkable
                            )
                            batch.set(formaDoc, dataForma)
                        }

                        // Recria POIs
                        val pois = mapEditor.actions.filterIsInstance<Action.Poi>()
                        pois.forEach { poi ->
                            val poiDoc = mapaRef.collection("pois").document(poi.id)
                            val dataPoi = mapOf(
                                "id" to poi.id,
                                "x" to mapEditor.pxToMeters(poi.x),
                                "y" to mapEditor.pxToMeters(poi.y),
                                "iconName" to iconResToName(poi.iconRes),
                                "iconRes" to poi.iconRes,
                                "isStartQR" to poi.isStartQR
                            )
                            batch.set(poiDoc, dataPoi)
                        }

                        // Recria NODES com base nos POIs
                        pois.forEach { poi ->
                            val nodeDoc = mapaRef.collection("nodes").document(poi.id)
                            val dataNode = mapOf(
                                "id" to poi.id,
                                "tipo" to "POI",
                                "x" to mapEditor.pxToMeters(poi.x),
                                "y" to mapEditor.pxToMeters(poi.y),
                                "poiIds" to listOf(poi.id)
                            )
                            batch.set(nodeDoc, dataNode)
                        }

                        // Recria EDGES
                        gerarEdgesAuto().forEach { edge ->
                            val edgeId = edge["id"] as String
                            val edgeDoc = mapaRef.collection("edges").document(edgeId)
                            batch.set(edgeDoc, edge)
                        }

                        // Commit do batch
                        batch.commit()
                            .addOnSuccessListener {
                                btnSalvarMapa.isEnabled = true
                                Toast.makeText(this, "Mapa salvo com sucesso!", Toast.LENGTH_SHORT).show()
                                startActivity(Intent(this, com.example.indoorar.ActivityMeusMapas::class.java))
                                finish()
                            }
                            .addOnFailureListener { e ->
                                btnSalvarMapa.isEnabled = true
                                Toast.makeText(this, "Erro ao salvar: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                    }.addOnFailureListener { e ->
                        btnSalvarMapa.isEnabled = true
                        Toast.makeText(this, "Erro ao ler edges: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }.addOnFailureListener { e ->
                    btnSalvarMapa.isEnabled = true
                    Toast.makeText(this, "Erro ao ler nodes: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }.addOnFailureListener { e ->
                btnSalvarMapa.isEnabled = true
                Toast.makeText(this, "Erro ao ler pois: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }.addOnFailureListener { e ->
            btnSalvarMapa.isEnabled = true
            Toast.makeText(this, "Erro ao ler formas: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun gerarEdgesAuto(): List<Map<String, Any>> {
        val nodes = mapEditor.actions.filterIsInstance<Action.Poi>()
        val shapes = mapEditor.actions.filterIsInstance<Action.Shape>() // shapes com isWalkable

        val edges = mutableListOf<Map<String, Any>>()

        // Robust collision check: segment vs shape (supports rotated rect/square, triangle, circle, line)
        fun linhaColideComShape(x1: Float, y1: Float, x2: Float, y2: Float, shape: Action.Shape): Boolean {
            if (shape.isWalkable) return false

            fun rotate(px: Float, py: Float, cx: Float, cy: Float, angleDeg: Float): Pair<Float, Float> {
                val a = Math.toRadians(angleDeg.toDouble())
                val cosA = kotlin.math.cos(a).toFloat()
                val sinA = kotlin.math.sin(a).toFloat()
                val tx = px - cx
                val ty = py - cy
                val rx = tx * cosA - ty * sinA
                val ry = tx * sinA + ty * cosA
                return Pair(rx + cx, ry + cy)
            }

            fun segSegIntersect(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float): Boolean {
                fun orient(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float {
                    return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
                }
                val o1 = orient(x1, y1, x2, y2, x3, y3)
                val o2 = orient(x1, y1, x2, y2, x4, y4)
                val o3 = orient(x3, y3, x4, y4, x1, y1)
                val o4 = orient(x3, y3, x4, y2, x2, y2)
                if (o1 == 0f && min(x1, x2) <= x3 && x3 <= max(x1, x2) && min(y1, y2) <= y3 && y3 <= max(y1, y2)) return true
                if (o2 == 0f && min(x1, x2) <= x4 && x4 <= max(x1, x2) && min(y1, y2) <= y4 && y4 <= max(y1, y2)) return true
                if (o3 == 0f && min(x3, x4) <= x1 && x1 <= max(x3, x4) && min(y3, y4) <= y1 && y1 <= max(y3, y4)) return true
                if (o4 == 0f && min(x3, x4) <= x2 && x2 <= max(x3, x4) && min(y3, y4) <= y2 && y2 <= max(y3, y4)) return true
                return (o1 > 0f) != (o2 > 0f) && (o3 > 0f) != (o4 > 0f)
            }

            fun pointInPoly(px: Float, py: Float, poly: List<Pair<Float, Float>>): Boolean {
                var inside = false
                var j = poly.size - 1
                for (i in poly.indices) {
                    val xi = poly[i].first; val yi = poly[i].second
                    val xj = poly[j].first; val yj = poly[j].second
                    val yi_gt = yi > py
                    val yj_gt = yj > py
                    if (yi_gt != yj_gt) {
                        val denom = (yj - yi)
                        val xIntersect = if (denom == 0f) xi else xi + (py - yi) * (xj - xi) / denom
                        if (px < xIntersect) inside = !inside
                    }
                    j = i
                }
                return inside
            }

            fun distPointSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
                val l2 = (x2 - x1)*(x2 - x1) + (y2 - y1)*(y2 - y1)
                if (l2 == 0f) return kotlin.math.hypot((px - x1).toDouble(), (py - y1).toDouble()).toFloat()
                var t = ((px - x1)*(x2 - x1) + (py - y1)*(y2 - y1)) / l2
                t = t.coerceIn(0f, 1f)
                val projx = x1 + t*(x2 - x1); val projy = y1 + t*(y2 - y1)
                return kotlin.math.hypot((px - projx).toDouble(), (py - projy).toDouble()).toFloat()
            }

            // Build geometry for the shape in editor world coords
            val left = min(shape.start.x, shape.end.x)
            val right = max(shape.start.x, shape.end.x)
            val top = min(shape.start.y, shape.end.y)
            val bottom = max(shape.start.y, shape.end.y)
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            val halfW = (right - left) / 2f
            val halfH = (bottom - top) / 2f

            when (shape.type) {
                Action.ShapeType.RECTANGLE, Action.ShapeType.SQUARE -> {
                    // rectangle corners relative to center then rotated
                    val corners = listOf(
                        Pair(-halfW, -halfH), Pair(halfW, -halfH), Pair(halfW, halfH), Pair(-halfW, halfH)
                    ).map { (rx, ry) ->
                        val (wx, wy) = Pair(cx + rx, cy + ry)
                        rotate(wx, wy, cx, cy, shape.rotation)
                    }
                    // check intersection with polygon edges or containment
                    if (pointInPoly(x1, y1, corners) || pointInPoly(x2, y2, corners)) return true
                    for (k in corners.indices) {
                        val a = corners[k]
                        val b = corners[(k+1) % corners.size]
                        if (segSegIntersect(x1,y1,x2,y2,a.first,a.second,b.first,b.second)) return true
                    }
                    return false
                }
                Action.ShapeType.TRIANGLE -> {
                    // construct triangle same as editor/minimap: top middle and two bottom corners
                    val rawLeft = left; val rawRight = right; val rawTop = top; val rawBottom = bottom
                    val tcx = (rawLeft + rawRight) / 2f
                    val tri = listOf(
                        Pair(tcx, rawTop), Pair(rawLeft, rawBottom), Pair(rawRight, rawBottom)
                    ).map { (px, py) -> rotate(px, py, cx, cy, shape.rotation) }
                    if (pointInPoly(x1,y1,tri) || pointInPoly(x2,y2,tri)) return true
                    for (k in tri.indices) {
                        val a = tri[k]; val b = tri[(k+1)%tri.size]
                        if (segSegIntersect(x1,y1,x2,y2,a.first,a.second,b.first,b.second)) return true
                    }
                    return false
                }
                Action.ShapeType.CIRCLE -> {
                    val radius = kotlin.math.min(halfW, halfH)
                    // distance from segment to center <= radius
                    val d = distPointSegment(cx, cy, x1, y1, x2, y2)
                    return d <= radius
                }
                Action.ShapeType.LINE -> {
                    // treat line as thick segment (approx half-thickness based on visual stroke)
                    val lx1 = shape.start.x; val ly1 = shape.start.y
                    val lx2 = shape.end.x; val ly2 = shape.end.y
                    // apply rotation around center for the segment endpoints
                    val (rx1, ry1) = rotate(lx1, ly1, cx, cy, shape.rotation)
                    val (rx2, ry2) = rotate(lx2, ly2, cx, cy, shape.rotation)
                    // thickness: a small fraction of shape size (fallback to 6px)
                    val thickness = (kotlin.math.min(halfW, halfH) * 0.12f).coerceAtLeast(6f)
                    // if the segment intersects the line segment or is closer than thickness/2, treat as collision
                    if (segSegIntersect(x1,y1,x2,y2, rx1,ry1, rx2,ry2)) return true
                    val d1 = distPointSegment(rx1, ry1, x1, y1, x2, y2)
                    val d2 = distPointSegment(rx2, ry2, x1, y1, x2, y2)
                    if (d1 <= thickness || d2 <= thickness) return true
                    // also check distance between segments
                    val midSegDist = (distPointSegment((x1+x2)/2f, (y1+y2)/2f, rx1,ry1, rx2,ry2))
                    if (midSegDist <= thickness) return true
                    return false
                }
                else -> return false
            }
        }

        for (i in nodes.indices) {
            for (j in i + 1 until nodes.size) {
                val n1 = nodes[i]
                val n2 = nodes[j]

                // verifica colisão com shapes que não são caminháveis
                val colidiu = shapes.any { shape -> linhaColideComShape(n1.x, n1.y, n2.x, n2.y, shape) }
                if (!colidiu) {
                    val dx = (n2.x - n1.x)
                    val dy = (n2.y - n1.y)
                    val distPx = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    val pesoMetros = mapEditor.pxToMeters(distPx)
                    edges.add(
                        mapOf(
                            "id" to "${n1.id}_${n2.id}",
                            "fromNodeId" to n1.id,
                            "toNodeId" to n2.id,
                            "peso" to pesoMetros
                        )
                    )
                }
            }
        }

        return edges
    }

    private fun iconResToName(resId: Int): String = when (resId) {
        R.drawable.ic_door_azul -> "door"
        R.drawable.ic_stairs_azul -> "stairs"
        R.drawable.ic_elevator_azul -> "elevator"
        R.drawable.ic_banheiro_azul -> "bathroom"
        R.drawable.ic_extintor_azul -> "fire_extinguisher"
        else -> "poi"
    }

    private fun bindViews() {
        mapEditor = findViewById(R.id.mapEditor)
        btnSalvarMapa = findViewById(R.id.btnSalvarMapa)
        poiCard = findViewById(R.id.cardPoi)
        formasCard = findViewById(R.id.cardFormas)
        brushCard = findViewById(R.id.cardBrush)
        cursorCard = findViewById(R.id.cardCursor)
        btnMergeShapes = findViewById(R.id.btnMergeShapes)
        btnPreviewMode = findViewById(R.id.btnPreviewMode)
        btnHelpTutorial = findViewById(R.id.btnHelpTutorial)
    }


    private fun updateSelectedButtonUI(selectedTool: Tool) {
        val selectedId = when (selectedTool) {
            Tool.CURSOR -> R.id.cursor
            Tool.FORMAS -> R.id.formas
            Tool.BRUSH -> R.id.brush
            Tool.POI -> R.id.poi
        }
        listOf(R.id.cursor, R.id.formas, R.id.brush, R.id.poi)
            .forEach { id -> findViewById<ImageView>(id).isSelected = (id == selectedId) }
    }

    private fun setupToolButtons() {
        val toolButtons = mapOf(
            R.id.linearcursor to Tool.CURSOR,
            R.id.linearformas to Tool.FORMAS,
            R.id.linearbrush to Tool.BRUSH,
            R.id.linearpoi to Tool.POI
        )

        toolButtons.forEach { (linearId, tool) ->
            findViewById<LinearLayout>(linearId).setOnClickListener {
                // Lógica: qualquer clique na toolbar fecha popups abertos.
                // Se clicar no mesmo botão, alterna (toggle) com base no estado anterior.
                val wasFormasVisible = formasCard.isVisible
                val wasPoiVisible = poiCard.isVisible
                val wasBrushVisible = brushCard.isVisible
                val wasCursorVisible = cursorCard.isVisible
                val wasSameTool = (mapEditor.currentTool == tool)
                hideAllToolCards()

                when (tool) {
                    Tool.FORMAS -> {
                        if (wasSameTool && wasFormasVisible) {
                            // estava aberto; mantém fechado (toggle)
                        } else {
                            mapEditor.setTool(Tool.FORMAS)
                            formasCard.visibility = View.VISIBLE
                            alignCardAbove(R.id.cardFormas, R.id.linearformas)
                        }
                    }
                    Tool.POI -> {
                        if (wasSameTool && wasPoiVisible) {
                            // toggle para fechado
                        } else {
                            mapEditor.setTool(Tool.POI)
                            poiCard.visibility = View.VISIBLE
                            alignCardAbove(R.id.cardPoi, R.id.linearpoi)
                        }
                    }
                    Tool.BRUSH -> {
                        if (wasSameTool && wasBrushVisible) {
                            // toggle para fechado
                        } else {
                            mapEditor.setTool(Tool.BRUSH)
                            brushCard.visibility = View.VISIBLE
                            alignCardAbove(R.id.cardBrush, R.id.linearbrush)
                        }
                    }
                    Tool.CURSOR -> {
                        if (wasSameTool && wasCursorVisible) {
                            // toggle para fechado
                        } else {
                            mapEditor.setTool(Tool.CURSOR)
                            cursorCard.visibility = View.VISIBLE
                            alignCardAbove(R.id.cardCursor, R.id.linearcursor)
                        }
                    }
                }
            }
        }

        // Toolbar: outros botões também fecham popups
        findViewById<LinearLayout>(R.id.linearlayers).setOnClickListener {
            hideAllToolCards()
            mapEditor.toggleGrid()
        }
        findViewById<LinearLayout>(R.id.lineardesfazer).setOnClickListener {
            hideAllToolCards()
            mapEditor.undo()
        }

        updateSelectedButtonUI(Tool.CURSOR) // Define o estado inicial
    }

    private fun setupPoiClicks() {
        val poiMap = mapOf(
            R.id.poiPorta to R.drawable.ic_door_azul,
            R.id.poiEscada to R.drawable.ic_stairs_azul,
            R.id.poiElevador to R.drawable.ic_elevator_azul,
            R.id.poiBanheiro to R.drawable.ic_banheiro_azul,
            R.id.poiExtintor to R.drawable.ic_extintor_azul
        )

        poiMap.forEach { (viewId, iconRes) ->
            findViewById<LinearLayout>(viewId).setOnClickListener {
                // Avisa o MapEditor para entrar no modo de posicionamento
                mapEditor.primeForPoiCreation(iconRes)
                Toast.makeText(this, "Toque no mapa para posicionar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupFormasClicks() {
        val map = mapOf(
            R.id.formasRetangulo to Action.ShapeType.RECTANGLE,
            R.id.formasQuadrado to Action.ShapeType.SQUARE,
            R.id.formasCirculo to Action.ShapeType.CIRCLE,
            R.id.formasTriangulo to Action.ShapeType.TRIANGLE,
            R.id.formasLinha to Action.ShapeType.LINE,
        )
        map.forEach { (viewId, type) ->
            findViewById<LinearLayout>(viewId).setOnClickListener {
                mapEditor.setShapeType(type)
                mapEditor.setTool(Tool.FORMAS)
                formasCard.visibility = View.GONE
                Toast.makeText(this, "Toque e arraste no mapa para desenhar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupBrushClicks() {
        // Tamanhos em dp para consistência
        findViewById<LinearLayout>(R.id.brushThin).setOnClickListener {
            mapEditor.getBrushPaint().strokeWidth = mapEditor.dp(2f)
            mapEditor.setTool(Tool.BRUSH)
            brushCard.visibility = View.GONE
            Toast.makeText(this, "Pincel fino ativo. Desenhe no mapa.", Toast.LENGTH_SHORT).show()
        }
        findViewById<LinearLayout>(R.id.brushMedium).setOnClickListener {
            mapEditor.getBrushPaint().strokeWidth = mapEditor.dp(6f)
            mapEditor.setTool(Tool.BRUSH)
            brushCard.visibility = View.GONE
            Toast.makeText(this, "Pincel médio ativo. Desenhe no mapa.", Toast.LENGTH_SHORT).show()
        }
        findViewById<LinearLayout>(R.id.brushThick).setOnClickListener {
            mapEditor.getBrushPaint().strokeWidth = mapEditor.dp(12f)
            mapEditor.setTool(Tool.BRUSH)
            brushCard.visibility = View.GONE
            Toast.makeText(this, "Pincel grosso ativo. Desenhe no mapa.", Toast.LENGTH_SHORT).show()
        }
        findViewById<LinearLayout>(R.id.brushText).setOnClickListener {
            val input = EditText(this).apply {
                hint = "Digite o texto"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                maxLines = 2
            }
            AlertDialog.Builder(this)
                .setTitle("Inserir texto")
                .setView(input)
                .setPositiveButton("OK") { dialog, _ ->
                    val txt = input.text?.toString()?.trim().orEmpty()
                    if (txt.isNotEmpty()) {
                        // Padrão solicitado: tamanho um pouco maior e cor preta
                        val color = "#000000".toColorInt()
                        val sizeSp = 20f
                        mapEditor.setTool(Tool.BRUSH)
                        mapEditor.primeForTextCreation(txt, sizeSp, color)
                        Toast.makeText(this, "Toque no mapa para posicionar o texto", Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                    brushCard.visibility = View.GONE
                }
                .setNegativeButton("Cancelar") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    private fun setupCursorClicks() {
        // Eraser switch: delete on tap without confirmation
        findViewById<SwitchMaterial>(R.id.cursorEraserSwitch)?.setOnCheckedChangeListener { _, isChecked ->
            mapEditor.setEraserEnabled(isChecked)
        }
        // Bring selected to front
        findViewById<LinearLayout>(R.id.cursorBringFront).setOnClickListener {
            mapEditor.bringSelectedToFront()
        }
        // Send selected to back
        findViewById<LinearLayout>(R.id.cursorSendBack).setOnClickListener {
            mapEditor.sendSelectedToBack()
        }
    }

    private fun setupAttributePanel() {
        AttributePanelController(this, mapEditor)
    }

    // Load an existing map from Firestore into the editor for modification
    private fun loadMapIntoEditor(mapId: String) {
        val db = FirebaseFirestore.getInstance()
        val mapaRef = db.collection("mapas").document(mapId)
        mapaRef.get().addOnSuccessListener { doc ->
            if (!doc.exists()) return@addOnSuccessListener
            // Load pxPerMeter if available
            val pxPerM = (doc.getDouble("pxPerMeter") ?: mapEditor.pxPerMeter.toDouble()).toFloat()
            mapEditor.pxPerMeter = pxPerM

            // Prepare container for actions
            val loadedActions = mutableListOf<com.example.indoorar.ui.Action>()

            // Load formas
            mapaRef.collection("formas").get()
                .addOnSuccessListener { snap ->
                    snap.documents.forEach { fdoc ->
                        try {
                            val pos = (fdoc.get("posicao") as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }
                            val tam = (fdoc.get("tamanho") as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }
                            val tipo = fdoc.getString("tipo") ?: ""
                            val corStr = fdoc.getString("cor")
                            val rot = (fdoc.getDouble("rotacao") ?: 0.0).toFloat()
                            val isWalk = (fdoc.getBoolean("isWalkable") ?: fdoc.getBoolean("caminhavel") ?: fdoc.getBoolean("walkable") ?: true)
                            if (pos != null && tam != null && pos.size >= 2 && tam.size >= 2) {
                                val xMeters = pos[0]; val yMeters = pos[1]
                                val hMeters = tam[0]; val wMeters = tam[1]
                                val xPx = mapEditor.metersToPx(xMeters)
                                val yPx = mapEditor.metersToPx(yMeters)
                                val wPx = mapEditor.metersToPx(wMeters)
                                val hPx = mapEditor.metersToPx(hMeters)
                                val start = android.graphics.PointF(xPx, yPx)
                                val end = android.graphics.PointF(xPx + wPx, yPx + hPx)
                                val fill = try { (corStr ?: "#D9D9D9").toColorInt() } catch (_: Exception) { "#D9D9D9".toColorInt() }
                                val shapeType = when (tipo) {
                                    "retangulo" -> com.example.indoorar.ui.Action.ShapeType.RECTANGLE
                                    "quadrado" -> com.example.indoorar.ui.Action.ShapeType.SQUARE
                                    "circulo" -> com.example.indoorar.ui.Action.ShapeType.CIRCLE
                                    "triangulo" -> com.example.indoorar.ui.Action.ShapeType.TRIANGLE
                                    "linha" -> com.example.indoorar.ui.Action.ShapeType.LINE
                                    else -> com.example.indoorar.ui.Action.ShapeType.RECTANGLE
                                }
                                val shape = com.example.indoorar.ui.Action.Shape(start = start, end = end, selected = false, fillColor = fill, rotation = rot, isWalkable = isWalk, nome = fdoc.getString("nome") ?: "", type = shapeType)
                                loadedActions.add(shape)
                            }
                        } catch (_: Exception) {}
                    }
                    // After formas loaded, load pois
                    mapaRef.collection("pois").get()
                        .addOnSuccessListener { psnap ->
                            psnap.documents.forEach { pdoc ->
                                try {
                                    val pid = pdoc.getString("id") ?: pdoc.id
                                    val x = (pdoc.getDouble("x") ?: 0.0).toFloat()
                                    val y = (pdoc.getDouble("y") ?: 0.0).toFloat()
                                    val iconRes = (pdoc.getLong("iconRes")?.toInt()) ?: R.drawable.ic_poi_default
                                    val xPx = mapEditor.metersToPx(x)
                                    val yPx = mapEditor.metersToPx(y)
                                    val poi = com.example.indoorar.ui.Action.Poi(id = pid, x = xPx, y = yPx, width = 48f, height = 48f, iconRes = iconRes)
                                    loadedActions.add(poi)
                                } catch (_: Exception) {}
                            }
                            // Replace editor actions on UI thread
                            mapEditor.post {
                                mapEditor.replaceActions(loadedActions)
                                Toast.makeText(this, "Mapa carregado para edição", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .addOnFailureListener { _ ->
                            mapEditor.post { mapEditor.replaceActions(loadedActions) }
                        }
                }
                .addOnFailureListener { _ ->
                    // If formas fail, still attempt pois
                    mapaRef.collection("pois").get()
                        .addOnSuccessListener { psnap ->
                            psnap.documents.forEach { pdoc ->
                                try {
                                    val pid = pdoc.getString("id") ?: pdoc.id
                                    val x = (pdoc.getDouble("x") ?: 0.0).toFloat()
                                    val y = (pdoc.getDouble("y") ?: 0.0).toFloat()
                                    val iconRes = (pdoc.getLong("iconRes")?.toInt()) ?: R.drawable.ic_poi_default
                                    val xPx = mapEditor.metersToPx(x)
                                    val yPx = mapEditor.metersToPx(y)
                                    val poi = com.example.indoorar.ui.Action.Poi(id = pid, x = xPx, y = yPx, width = 48f, height = 48f, iconRes = iconRes)
                                    loadedActions.add(poi)
                                } catch (_: Exception) {}
                            }
                            mapEditor.post { mapEditor.replaceActions(loadedActions) }
                        }
                        .addOnFailureListener { _ -> mapEditor.post { mapEditor.replaceActions(loadedActions) } }
                }
        }
        .addOnFailureListener { e ->
            Toast.makeText(this, "Falha ao carregar mapa: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun alignCardAbove(cardId: Int, anchorContainerId: Int) {
        val card = findViewById<View>(cardId) ?: return
        val anchor = findViewById<View>(anchorContainerId) ?: return
        card.post {
            val parent = card.parent as? View ?: return@post
            val parentWidth = parent.width
            if (parentWidth <= 0) return@post
            val locAnchor = IntArray(2)
            val locParent = IntArray(2)
            anchor.getLocationOnScreen(locAnchor)
            parent.getLocationOnScreen(locParent)
            val anchorCenterX = locAnchor[0] + anchor.width / 2f - locParent[0]
            val bias = (anchorCenterX / parentWidth).coerceIn(0f, 1f)
            val lp = card.layoutParams
            if (lp is ConstraintLayout.LayoutParams) {
                lp.horizontalBias = bias
                card.layoutParams = lp
            }
            card.bringToFront()
        }
    }

    // Helper: fecha todos os popups/cards de ferramentas
    private fun hideAllToolCards() {
        poiCard.visibility = View.GONE
        formasCard.visibility = View.GONE
        brushCard.visibility = View.GONE
        cursorCard.visibility = View.GONE
    }

    // --------------------- Tutorial Overlay  ---------------------
    private fun shouldShowEditorTour(): Boolean {
        val prefs = getSharedPreferences("editor_prefs", MODE_PRIVATE)
        return !prefs.getBoolean("tour_shown", false)
    }

    private fun markTourShown() {
        getSharedPreferences("editor_prefs", MODE_PRIVATE)
            .edit {
                putBoolean("tour_shown", true)
            }
    }

    private fun startEditorTour(force: Boolean) {
        if (!force && !shouldShowEditorTour()) return
        val root = findViewById<ViewGroup>(android.R.id.content)
        val steps = listOf(
            Pair(findViewById<View>(R.id.linearcursor), "Ferramenta de seleção: toque para selecionar e editar elementos."),
            Pair(findViewById<View>(R.id.linearformas), "Formas: toque para escolher e desenhar retângulos, círculos, etc."),
            Pair(findViewById<View>(R.id.linearpoi), "POIs: adicione portas, escadas, elevador e outros pontos importantes."),
            Pair(findViewById<View>(R.id.linearbrush), "Pincel e Texto: desenhe marcações livres ou insira textos no mapa."),
            Pair(findViewById<View>(R.id.btnPreviewMode), "Preview: alterna para modo de visualização, limpa seleções e esconde painéis."),
            Pair(findViewById<View>(R.id.btnSalvarMapa), "Salvar: salve o mapa."),
        )
        TutorialOverlay.startSequence(root, steps) {
            markTourShown()
            Toast.makeText(this, "Tutorial concluído", Toast.LENGTH_SHORT).show()
        }
    }
}
