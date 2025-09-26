package com.example.indoorar.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import com.google.firebase.firestore.FirebaseFirestore
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.indoorar.BaseActivity
import com.example.indoorar.R
import com.example.indoorar.ui.editor.AttributePanelController
import com.example.indoorar.ui.editor.MapEditorView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import com.google.firebase.auth.FirebaseAuth
import android.text.InputType
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.toColorInt

class ActivityEditor : BaseActivity() {

    private lateinit var mapEditor: MapEditorView
    private lateinit var colorPreview: ImageView
    private lateinit var inputHex: EditText
    private lateinit var btnColorPicker: ImageButton
    private lateinit var btnSalvarMapa: MaterialButton
    private lateinit var poiCard: MaterialCardView
    private lateinit var formasCard: MaterialCardView
    private lateinit var brushCard: MaterialCardView
    private lateinit var cursorCard: MaterialCardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        bindViews()
        setupToolButtons()
        setupAttributePanel()
        setupPoiClicks()
        setupFormasClicks()
        setupBrushClicks()
        setupCursorClicks()

        // Listener para a View avisar a Activity quando a ferramenta mudar
        mapEditor.onToolChangedListener = { newTool ->
            updateSelectedButtonUI(newTool)
            // Esconde/mostra cards conforme ferramenta
            poiCard.visibility = if (newTool == Tool.POI) View.VISIBLE else View.GONE
            formasCard.visibility = if (newTool == Tool.FORMAS) View.VISIBLE else View.GONE
            brushCard.visibility = if (newTool == Tool.BRUSH) View.VISIBLE else View.GONE
            // Hide cursor card on generic tool change; toggled explicitly on click
            if (newTool != Tool.CURSOR) {
                cursorCard.visibility = View.GONE
                // also ensure eraser off when leaving cursor
                findViewById<android.widget.Switch>(R.id.cursorEraserSwitch)?.isChecked = false
                mapEditor.setEraserEnabled(false)
            }
        }

        btnSalvarMapa.setOnClickListener {
            salvarMapa()
        }
    }

    private fun salvarMapa() {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(this, "Faça login para salvar", Toast.LENGTH_SHORT).show()
            return
        }
        btnSalvarMapa.isEnabled = false
        Toast.makeText(this, "Verificando permissões...", Toast.LENGTH_SHORT).show()
        val db = FirebaseFirestore.getInstance()
        db.collection("usuarios").document(user.uid).get()
            .addOnSuccessListener { doc ->
                val tipo = doc.getString("tipoConta")
                if (tipo != "maker") {
                    btnSalvarMapa.isEnabled = true
                    Toast.makeText(this, "Apenas maker pode criar mapas", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }
                val nomeAutor = doc.getString("nome") ?: user.uid
                // Abrir card para inserir o nome do mapa
                solicitarNomeMapa { nomeMapa ->
                    if (nomeMapa.isBlank()) {
                        btnSalvarMapa.isEnabled = true
                        Toast.makeText(this, "Informe um nome para o mapa", Toast.LENGTH_SHORT).show()
                    } else {
                        executarSalvamento(user.uid, nomeAutor, nomeMapa.trim())
                    }
                }
            }
            .addOnFailureListener { e ->
                btnSalvarMapa.isEnabled = true
                Toast.makeText(this, "Falha ao verificar usuário: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun solicitarNomeMapa(onConfirm: (String) -> Unit) {
        val input = EditText(this).apply {
            hint = "Nome do mapa"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            maxLines = 1
        }
        val container = FrameLayout(this).apply {
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, 0)
            addView(input, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("Salvar mapa")
            .setMessage("Digite o nome do mapa")
            .setView(container)
            .setPositiveButton("Salvar") { dialog, _ ->
                onConfirm(input.text?.toString() ?: "")
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar") { dialog, _ ->
                btnSalvarMapa.isEnabled = true
                dialog.dismiss()
            }
            .setOnCancelListener {
                btnSalvarMapa.isEnabled = true
            }
            .show()
    }

    private fun executarSalvamento(uid: String, autorNome: String, nomeMapa: String) {
        val db = FirebaseFirestore.getInstance()
        val mapaRef = db.collection("mapas").document()

        val mapaData = mapOf(
            "criadorUid" to uid,
            // Somente 'nomeAutor' será persistido
            "nomeAutor" to autorNome,
            "dataCriacao" to com.google.firebase.Timestamp.now(),
            "nome" to nomeMapa,
            // Mantém descrição vazia por enquanto (poderá ter outro diálogo futuramente)
            "descricao" to "",
            // Escala para conversão px <-> m (fundamental para ZXing/ARCore/A*)
            "pxPerMeter" to mapEditor.pxPerMeter
        )

        val batch = db.batch()
        batch.set(mapaRef, mapaData)

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

        // POIS
        val pois = mapEditor.actions.filterIsInstance<Action.Poi>()
        pois.forEach { poi ->
            val poiDoc = mapaRef.collection("pois").document(poi.id)
            val dataPoi = mapOf(
                "id" to poi.id,
                // não persistir nome/descricao para POI
                "x" to mapEditor.pxToMeters(poi.x),
                "y" to mapEditor.pxToMeters(poi.y),
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
                startActivity(android.content.Intent(this, com.example.indoorar.ActivityMeusMapas::class.java))
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



    private fun bindViews() {
        mapEditor = findViewById(R.id.mapEditor)
        colorPreview = findViewById(R.id.colorPreview)
        inputHex = findViewById(R.id.inputHex)
        btnColorPicker = findViewById(R.id.btnColorPicker)
        btnSalvarMapa = findViewById(R.id.btnSalvarMapa)
        poiCard = findViewById(R.id.cardPoi)
        formasCard = findViewById(R.id.cardFormas)
        brushCard = findViewById(R.id.cardBrush)
        cursorCard = findViewById(R.id.cardCursor)
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
                when (tool) {
                    Tool.FORMAS -> {
                        if (mapEditor.currentTool == Tool.FORMAS) {
                            // Toggle o card se já estiver na ferramenta
                            val visible = formasCard.visibility == View.VISIBLE
                            formasCard.visibility = if (visible) View.GONE else View.VISIBLE
                            poiCard.visibility = View.GONE
                            brushCard.visibility = View.GONE
                        } else {
                            mapEditor.setTool(Tool.FORMAS)
                            formasCard.visibility = View.VISIBLE
                            poiCard.visibility = View.GONE
                            brushCard.visibility = View.GONE
                        }
                    }
                    Tool.POI -> {
                        if (mapEditor.currentTool == Tool.POI) {
                            val visible = poiCard.visibility == View.VISIBLE
                            poiCard.visibility = if (visible) View.GONE else View.VISIBLE
                            formasCard.visibility = View.GONE
                            brushCard.visibility = View.GONE
                        } else {
                            mapEditor.setTool(Tool.POI)
                            poiCard.visibility = View.VISIBLE
                            formasCard.visibility = View.GONE
                            brushCard.visibility = View.GONE
                        }
                    }
                    Tool.BRUSH -> {
                        if (mapEditor.currentTool == Tool.BRUSH) {
                            val visible = brushCard.visibility == View.VISIBLE
                            brushCard.visibility = if (visible) View.GONE else View.VISIBLE
                            formasCard.visibility = View.GONE
                            poiCard.visibility = View.GONE
                        } else {
                            mapEditor.setTool(Tool.BRUSH)
                            brushCard.visibility = View.VISIBLE
                            formasCard.visibility = View.GONE
                            poiCard.visibility = View.GONE
                        }
                    }
                    Tool.CURSOR -> {
                        if (mapEditor.currentTool == Tool.CURSOR) {
                            val visible = cursorCard.visibility == View.VISIBLE
                            cursorCard.visibility = if (visible) View.GONE else View.VISIBLE
                            // hide others
                            formasCard.visibility = View.GONE
                            poiCard.visibility = View.GONE
                            brushCard.visibility = View.GONE
                        } else {
                            mapEditor.setTool(Tool.CURSOR)
                            cursorCard.visibility = View.VISIBLE
                            // hide others
                            formasCard.visibility = View.GONE
                            poiCard.visibility = View.GONE
                            brushCard.visibility = View.GONE
                        }
                    }
                    else -> {
                        mapEditor.setTool(tool)
                        // Oculta quaisquer cards quando mudar para outras ferramentas
                        poiCard.visibility = View.GONE
                        formasCard.visibility = View.GONE
                        brushCard.visibility = View.GONE
                        cursorCard.visibility = View.GONE
                    }
                }
            }
        }

        findViewById<LinearLayout>(R.id.linearlayers).setOnClickListener { mapEditor.toggleGrid() }
        findViewById<LinearLayout>(R.id.lineardesfazer).setOnClickListener { mapEditor.undo() }

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
        fun currentColor(): Int {
            val hex = inputHex.text?.toString()?.ifBlank { "#3264FF" } ?: "#3264FF"
            return try { hex.toColorInt() } catch (_: Exception) { "#3264FF".toColorInt() }
        }
        // Tamanhos em dp para consistência
        findViewById<LinearLayout>(R.id.brushThin).setOnClickListener {
            mapEditor.getBrushPaint().strokeWidth = mapEditor.dp(2f)
            mapEditor.getBrushPaint().color = currentColor()
            mapEditor.setTool(Tool.BRUSH)
            brushCard.visibility = View.GONE
            Toast.makeText(this, "Pincel fino ativo. Desenhe no mapa.", Toast.LENGTH_SHORT).show()
        }
        findViewById<LinearLayout>(R.id.brushMedium).setOnClickListener {
            mapEditor.getBrushPaint().strokeWidth = mapEditor.dp(6f)
            mapEditor.getBrushPaint().color = currentColor()
            mapEditor.setTool(Tool.BRUSH)
            brushCard.visibility = View.GONE
            Toast.makeText(this, "Pincel médio ativo. Desenhe no mapa.", Toast.LENGTH_SHORT).show()
        }
        findViewById<LinearLayout>(R.id.brushThick).setOnClickListener {
            mapEditor.getBrushPaint().strokeWidth = mapEditor.dp(12f)
            mapEditor.getBrushPaint().color = currentColor()
            mapEditor.setTool(Tool.BRUSH)
            brushCard.visibility = View.GONE
            Toast.makeText(this, "Pincel grosso ativo. Desenhe no mapa.", Toast.LENGTH_SHORT).show()
        }
        findViewById<LinearLayout>(R.id.brushText).setOnClickListener {
            val input = EditText(this).apply {
                hint = "Digite o texto"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
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
        findViewById<android.widget.Switch>(R.id.cursorEraserSwitch)?.setOnCheckedChangeListener { _, isChecked ->
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

}