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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import com.example.indoorar.BaseActivity
import com.example.indoorar.R
import com.example.indoorar.ui.editor.AttributePanelController
import com.example.indoorar.ui.editor.MapEditorView
import com.example.indoorar.views.ColorPickerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import com.google.firebase.auth.FirebaseAuth


class ActivityEditor : BaseActivity() {

    private val db = FirebaseFirestore.getInstance()

    private lateinit var mapEditor: MapEditorView
    private lateinit var colorPreview: ImageView
    private lateinit var inputHex: EditText
    private lateinit var btnColorPicker: ImageButton
    private lateinit var btnSalvarMapa: MaterialButton
    private lateinit var poiCard: MaterialCardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        bindViews()
        setupToolButtons()
        setupAttributePanel()
        setupPoiClicks()

        // Listener para a View avisar a Activity quando a ferramenta mudar
        mapEditor.onToolChangedListener = { newTool ->
            updateSelectedButtonUI(newTool)
            // Se a ferramenta não for mais POI, esconde o card
            if (newTool != Tool.POI) {
                poiCard.visibility = View.GONE
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
                executarSalvamento(user.uid, nomeAutor)
            }
            .addOnFailureListener { e ->
                btnSalvarMapa.isEnabled = true
                Toast.makeText(this, "Falha ao verificar usuário: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun executarSalvamento(uid: String, autorNome: String) {
        val db = FirebaseFirestore.getInstance()
        val mapaRef = db.collection("mapas").document()

        val mapaData = mapOf(
            "criadorUid" to uid,
            "autorNome" to autorNome,
            "dataCriacao" to com.google.firebase.Timestamp.now(),
            // Futuro: inputs de nome/descricao do mapa (atualmente fixos)
            "nome" to "Mapa Teste",
            "descricao" to "Exemplo de mapa"
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
            val dataForma = mapOf(
                "cor" to String.format("#%06X", (0xFFFFFF and shape.fillColor)),
                "descricao" to shape.descricao,
                "nome" to shape.nome,
                "posicao" to listOf(mapEditor.pxToMeters(xPx), mapEditor.pxToMeters(yPx)),
                "rotacao" to shape.rotation,
                "tamanho" to listOf(mapEditor.pxToMeters(hPx), mapEditor.pxToMeters(wPx)),
                "tipo" to "retangulo",
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
                "name" to (poi.nome.ifBlank { "POI" }),
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

        // EDGES
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
                    val peso = hypot((n2.x - n1.x).toDouble(), (n2.y - n1.y).toDouble())
                    edges.add(
                        mapOf(
                            "id" to "${n1.id}_${n2.id}",
                            "fromNodeId" to n1.id,
                            "toNodeId" to n2.id,
                            "peso" to peso
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
                mapEditor.setTool(tool)
                if (tool == Tool.POI) {
                    poiCard.visibility = View.VISIBLE
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

    private fun setupAttributePanel() {
        AttributePanelController(this, mapEditor)
    }

}