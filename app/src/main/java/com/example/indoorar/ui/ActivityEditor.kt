package com.example.indoorar.ui

import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.toColorInt
import androidx.core.graphics.drawable.toDrawable
import com.example.indoorar.BaseActivity
import com.example.indoorar.R
import com.example.indoorar.ui.Tool
import com.example.indoorar.ui.editor.MapEditorView
import com.example.indoorar.ui.editor.AttributePanelController
import com.example.indoorar.views.ColorPickerView
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class ActivityEditor : BaseActivity() {

    private lateinit var mapEditor: MapEditorView
    private lateinit var colorPreview: ImageView
    private lateinit var inputHex: EditText
    private lateinit var btnColorPicker: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        mapEditor = findViewById(R.id.mapEditor)
        colorPreview = findViewById(R.id.colorPreview)
        inputHex = findViewById(R.id.inputHex)
        btnColorPicker = findViewById(R.id.btnColorPicker)

        // Função para atualizar qual botão está ativo
        fun updateSelectedButton(selectedId: Int) {
            val buttons = listOf(R.id.cursor, R.id.formas, R.id.brush, R.id.poi)
            buttons.forEach { id ->
                findViewById<ImageView>(id).isSelected = (id == selectedId)
            }
        }

        // Cursor
        findViewById<LinearLayout>(R.id.linearcursor).setOnClickListener {
            mapEditor.setTool(Tool.CURSOR)
            updateSelectedButton(R.id.cursor)
        }

        // Formas
        findViewById<LinearLayout>(R.id.linearformas).setOnClickListener {
            mapEditor.setTool(Tool.FORMAS)
            updateSelectedButton(R.id.formas)
        }

        // Brush
        findViewById<LinearLayout>(R.id.linearbrush).setOnClickListener {
            mapEditor.setTool(Tool.BRUSH)
            updateSelectedButton(R.id.brush)
        }

        // POI
        findViewById<LinearLayout>(R.id.linearpoi).setOnClickListener {
            mapEditor.setTool(Tool.POI)
            updateSelectedButton(R.id.poi)

            showPoiPopup(findViewById(R.id.linearpoi))
        }

        // Camadas (grid por enquanto)
        findViewById<LinearLayout>(R.id.linearlayers).setOnClickListener {
            mapEditor.toggleGrid()
        }

        // Desfazer
        findViewById<LinearLayout>(R.id.lineardesfazer).setOnClickListener {
            mapEditor.undo()
        }

        // Botão inicial selecionado (cursor)
        updateSelectedButton(R.id.cursor)

        // Attribute Panel
        AttributePanelController(this, mapEditor)

        // ColorPicker
        btnColorPicker.setOnClickListener {
            val pickerView = ColorPickerView(this)
            pickerView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 200
            )

            val dialog = android.app.AlertDialog.Builder(this)
                .setTitle("Escolha uma cor")
                .setView(pickerView)
                .setPositiveButton("OK") { d, _ -> d.dismiss() }
                .create()

            pickerView.setOnColorChangedListener { color ->
                colorPreview.setBackgroundColor(color)
                inputHex.setText(String.format("#%06X", 0xFFFFFF and color))
            }

            dialog.show()
        }

        // HEX manual
        inputHex.setOnEditorActionListener { _, _, _ ->
            val hex = inputHex.text.toString()
            try {
                val color = hex.toColorInt()
                colorPreview.setBackgroundColor(color)
            } catch (e: IllegalArgumentException) {
                inputHex.error = "Hex inválido"
            }
            true
        }

        // Botão de salvar mapa (superior direito)
        val btnSalvar = Button(this).apply {
            text = "Salvar"
            setTextColor("#fdfdfd".toColorInt())
            setBackgroundColor("#32357A".toColorInt())
        }

        val layout = findViewById<LinearLayout>(R.id.main)
        layout.addView(btnSalvar)
        btnSalvar.setOnClickListener { salvarMapa() }
    }

    private fun showPoiPopup(anchor: LinearLayout) {
        val popupView = layoutInflater.inflate(R.layout.popup_pois, findViewById(android.R.id.content), false)

        val popupWindow = PopupWindow(
            popupView,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            isOutsideTouchable = true
            elevation = 12f
        }

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)

        popupView.measure(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val popupWidth = popupView.measuredWidth
        val popupHeight = popupView.measuredHeight

        val xOffset = anchor.width / 2 - popupWidth / 2
        val yOffset = -popupHeight - 16

        popupWindow.showAsDropDown(anchor, xOffset, yOffset)

        val poiPorta = popupView.findViewById<LinearLayout>(R.id.linearLayout1)
        val poiEscada = popupView.findViewById<LinearLayout>(R.id.linearLayout2)
        val poiElevador = popupView.findViewById<LinearLayout>(R.id.linearLayout3)
        val poiBanheiro = popupView.findViewById<LinearLayout>(R.id.linearLayout4)
        val poiExtintor = popupView.findViewById<LinearLayout>(R.id.linearLayout5)

        val poiItems = listOf(
            poiPorta to "Porta",
            poiEscada to "Escada",
            poiElevador to "Elevador",
            poiBanheiro to "Banheiro",
            poiExtintor to "Extintor de incêndio"
        )

        poiItems.forEach { (layout, name) ->
            layout.setOnClickListener {
                mapEditor.addPoi(100f, 100f, name)
                popupWindow.dismiss()
            }
            layout.background = AppCompatResources.getDrawable(this, R.drawable.ripple_clickable)
        }
    }

    fun salvarMapa() {
        val db = FirebaseFirestore.getInstance()
        val userUid = FirebaseAuth.getInstance().currentUser?.uid ?: "anonimo"

        val mapaData = hashMapOf(
            "criadorUid" to userUid,
            "dataCriacao" to Timestamp.now(),
            "nome" to "Mapa X",
            "descricao" to "Meu mapa incrível"
        )

        db.collection("mapas").add(mapaData)
            .addOnSuccessListener { mapaDoc ->
                val mapaId = mapaDoc.id

                mapEditor.actions.forEach { action ->
                    when (action) {
                        is Action.Shape -> {
                            val formaData = hashMapOf(
                                "cor" to action.fillColor,
                                "descricao" to "",
                                "nome" to "",
                                "posicao" to mapOf("x" to action.start.x, "y" to action.start.y),
                                "rotacao" to action.rotation,
                                "tamanho" to mapOf(
                                    "largura" to (action.end.x - action.start.x),
                                    "altura" to (action.end.y - action.start.y)
                                ),
                                "tipo" to "retangulo"
                            )
                            db.collection("mapas").document(mapaId)
                                .collection("formas")
                                .add(formaData)
                        }
                        is Action.Poi -> {
                            val poiData = hashMapOf(
                                "x" to action.position.x,
                                "y" to action.position.y,
                                "name" to action.name
                            )
                            db.collection("mapas").document(mapaId)
                                .collection("pois")
                                .add(poiData)
                        }
                        else -> {}
                    }
                }

                println("Mapa salvo com sucesso! ID: $mapaId")
            }
            .addOnFailureListener { e ->
                e.printStackTrace()
            }

        fun carregarMapa(mapaId: String) {
            val db = FirebaseFirestore.getInstance()

            db.collection("mapas").document(mapaId)
                .get()
                .addOnSuccessListener { mapaDoc ->
                    if (mapaDoc.exists()) {
                        // Opcional: pega dados gerais do mapa
                        val nome = mapaDoc.getString("nome") ?: ""
                        val descricao = mapaDoc.getString("descricao") ?: ""

                        // NÃO limpa o editor, só adiciona
                        // mapEditor.clearAll() → removido

                        // Carrega formas
                        db.collection("mapas").document(mapaId)
                            .collection("formas")
                            .get()
                            .addOnSuccessListener { formasSnapshot ->
                                for (formaDoc in formasSnapshot) {
                                    val pos = formaDoc.get("posicao") as Map<String, Double>
                                    val tam = formaDoc.get("tamanho") as Map<String, Double>
                                    val cor = (formaDoc.getLong("cor") ?: 0).toInt()
                                    val rotacao = (formaDoc.getDouble("rotacao") ?: 0.0).toFloat()
                                    val tipo = formaDoc.getString("tipo") ?: "retangulo"

                                    mapEditor.actions.add(
                                        Action.Shape(
                                            start = android.graphics.PointF(pos["x"]?.toFloat() ?: 0f, pos["y"]?.toFloat() ?: 0f),
                                            end = android.graphics.PointF(
                                                (pos["x"]?.toFloat() ?: 0f) + (tam["largura"]?.toFloat() ?: 0f),
                                                (pos["y"]?.toFloat() ?: 0f) + (tam["altura"]?.toFloat() ?: 0f)
                                            ),
                                            fillColor = cor,
                                            rotation = rotacao
                                        )
                                    )
                                }
                                mapEditor.invalidate()
                            }

                        // Carrega POIs
                        db.collection("mapas").document(mapaId)
                            .collection("pois")
                            .get()
                            .addOnSuccessListener { poisSnapshot ->
                                for (poiDoc in poisSnapshot) {
                                    val x = (poiDoc.getDouble("x") ?: 0.0).toFloat()
                                    val y = (poiDoc.getDouble("y") ?: 0.0).toFloat()
                                    val name = poiDoc.getString("name") ?: ""

                                    mapEditor.actions.add(Action.Poi(android.graphics.PointF(x, y), name))
                                }
                                mapEditor.invalidate()
                            }
                    }
                }
                .addOnFailureListener { e -> e.printStackTrace() }
        }

    }
}