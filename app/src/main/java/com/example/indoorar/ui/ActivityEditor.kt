package com.example.indoorar.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
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

// --- Data class para mapear botão de ferramenta ---
data class ToolButton(val tool: Tool?, val iconId: Int?)

class ActivityEditor : BaseActivity() {

    private lateinit var mapEditor: MapEditorView
    private lateinit var colorPreview: ImageView
    private lateinit var inputHex: EditText
    private lateinit var btnColorPicker: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        bindViews()
        setupToolButtons()
        setupAttributePanel()
        setupColorPicker()
        setupHexInput()
        setupSaveButton()
    }

    /** Liga as views do layout */
    private fun bindViews() {
        mapEditor = findViewById(R.id.mapEditor)
        colorPreview = findViewById(R.id.colorPreview)
        inputHex = findViewById(R.id.inputHex)
        btnColorPicker = findViewById(R.id.btnColorPicker)
    }

    /** Configura os botões de ferramenta do editor */
    private fun setupToolButtons() {
        val toolButtons = mapOf(
            R.id.linearcursor to ToolButton(Tool.CURSOR, R.id.cursor),
            R.id.linearformas to ToolButton(Tool.FORMAS, R.id.formas),
            R.id.linearbrush to ToolButton(Tool.BRUSH, R.id.brush),
            R.id.linearpoi to ToolButton(Tool.POI, R.id.poi),
            R.id.linearlayers to ToolButton(null, null),   // grid toggle
            R.id.lineardesfazer to ToolButton(null, null)  // undo
        )

        fun updateSelectedButton(selectedId: Int) {
            val buttons = listOf(R.id.cursor, R.id.formas, R.id.brush, R.id.poi)
            buttons.forEach { id -> findViewById<ImageView>(id).isSelected = (id == selectedId) }
        }

        toolButtons.forEach { (linearId, toolButton) ->
            val tool = toolButton.tool
            val iconId = toolButton.iconId

            findViewById<LinearLayout>(linearId).setOnClickListener {
                when (tool) {
                    Tool.CURSOR, Tool.FORMAS, Tool.BRUSH, Tool.POI -> mapEditor.setTool(tool)
                    else -> {} // noop
                }

                iconId?.let { updateSelectedButton(it) }

                if (tool == Tool.POI) showPoiPopup(findViewById(linearId))
                if (linearId == R.id.linearlayers) mapEditor.toggleGrid()
                if (linearId == R.id.lineardesfazer) mapEditor.undo()
            }
        }

        // Botão inicial selecionado
        updateSelectedButton(R.id.cursor)
    }

    /** Inicializa o painel de atributos do editor */
    private fun setupAttributePanel() {
        AttributePanelController(this, mapEditor)
    }

    /** Configura o ColorPicker e atualização do preview */
    private fun setupColorPicker() {
        btnColorPicker.setOnClickListener {
            val pickerView = ColorPickerView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 200
                )
            }

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
    }

    /** Permite inserir cor manualmente via HEX */
    private fun setupHexInput() {
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
    }

    /** Botão de salvar mapa (superior direito) */
    private fun setupSaveButton() {
        val btnSalvar = Button(this).apply {
            text = "Salvar"
            setTextColor("#fdfdfd".toColorInt())
            setBackgroundColor("#32357A".toColorInt())
        }
    }

    private fun showPoiPopup(anchor: LinearLayout) {
        val popupView = layoutInflater.inflate(R.layout.popup_pois, null, false)
        val popupWindow = PopupWindow(
            popupView,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isFocusable = false
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            isOutsideTouchable = true
            elevation = 12f
        }

        val poiItems: List<Pair<LinearLayout, String>> = listOf(
            popupView.findViewById<LinearLayout>(R.id.linearLayout1) to "porta",
            popupView.findViewById<LinearLayout>(R.id.linearLayout2) to "escada",
            popupView.findViewById<LinearLayout>(R.id.linearLayout3) to "elevador",
            popupView.findViewById<LinearLayout>(R.id.linearLayout4) to "banheiro",
            popupView.findViewById<LinearLayout>(R.id.linearLayout5) to "extintor"
        )

        val nameToRes = mapOf(
            "porta" to R.drawable.ic_door_azul,
            "escada" to R.drawable.ic_stairs_azul,
            "elevador" to R.drawable.ic_elevator_azul,
            "banheiro" to R.drawable.ic_banheiro_azul,
            "extintor" to R.drawable.ic_extintor_azul
        )

        poiItems.forEach { (layout, name) ->
            layout.setOnClickListener {
                mapEditor.post {
                    val x = mapEditor.width / 2f
                    val y = mapEditor.height / 2f
                    val iconRes = nameToRes[name] ?: R.drawable.ic_poi_default
                    mapEditor.addPoi(x, y, iconRes)
                }
                popupWindow.dismiss()
            }
            layout.background = AppCompatResources.getDrawable(this, R.drawable.ripple_clickable)
        }

        popupWindow.showAtLocation(anchor, Gravity.CENTER, 0, 0)
    }
}