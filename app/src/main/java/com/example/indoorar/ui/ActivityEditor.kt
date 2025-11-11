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
                Toast.makeText(this, "Preview ativo", Toast.LENGTH_SHORT).show()
            } else {
                btnPreviewMode.contentDescription = "Ativar preview"
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

        val batch = db.batch()
        // Atualiza somente pxPerMeter sem sobrescrever outros campos
        batch.set(mapaRef, mapOf("pxPerMeter" to mapEditor.pxPerMeter), SetOptions.merge())

        // FORMAS
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
                // remove descricao, mantém somente nome
                "nome" to shape.nome,
                "posicao" to listOf(mapEditor.pxToMeters(xPx), mapEditor.pxToMeters(yPx)),
                "rotacao" to shape.rotation,
                "tamanho" to listOf(mapEditor.pxToMeters(hPx), mapEditor.pxToMeters(wPx)),
                "tipo" to tipoStr,
                "isWalkable" to shape.isWalkable
            )
            batch.set(formaDoc, dataForma)
        }

        // POIs
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

        // NODES (baseado em POIs)
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

        // EDGES (peso em metros)
        gerarEdgesAuto().forEach { edge ->
            val edgeId = edge["id"] as String
            val edgeDoc = mapaRef.collection("edges").document(edgeId)
            batch.set(edgeDoc, edge)
        }

        Toast.makeText(this, "Salvando mapa...", Toast.LENGTH_SHORT).show()
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
    }

    private fun gerarEdgesAuto(): List<Map<String, Any>> {
        val nodes = mapEditor.actions.filterIsInstance<Action.Poi>()
        val shapes = mapEditor.actions.filterIsInstance<Action.Shape>() // shapes com isWalkable

        val edges = mutableListOf<Map<String, Any>>()

        // Função pra checar se tem colisão com uma parede
        fun linhaColideComShape(x1: Float, y1: Float, x2: Float, y2: Float, shape: Action.Shape): Boolean {
            if (!shape.isWalkable) {
                val left = min(shape.start.x, shape.end.x)
                val right = max(shape.start.x, shape.end.x)
                val top = min(shape.start.y, shape.end.y)
                val bottom = max(shape.start.y, shape.end.y)

                // checa se a linha cruza o retângulo
                val closestX = max(left, min(x2, right))
                val closestY = max(top, min(y2, bottom))
                val dx = x2 - x1
                val dy = y2 - y1
                return dx != 0f && dy != 0f && closestX in left..right && closestY in top..bottom
            }
            return false
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
