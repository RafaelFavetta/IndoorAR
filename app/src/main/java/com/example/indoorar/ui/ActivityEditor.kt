package com.example.indoorar.ui

import android.graphics.Color
import android.os.Bundle
import android.widget.*
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.toColorInt
import androidx.core.graphics.drawable.toDrawable
import com.example.indoorar.BaseActivity
import com.example.indoorar.R
import android.graphics.drawable.ColorDrawable
import com.example.indoorar.ui.Tool
import com.example.indoorar.ui.editor.MapEditorView
import com.example.indoorar.ui.editor.AttributePanelController
import com.example.indoorar.views.ColorPickerView
import com.google.firebase.Timestamp
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

        popupWindow.showAtLocation(anchor, android.view.Gravity.CENTER, 0, 0)

        val poiItems: List<Pair<LinearLayout, String>> = listOf(
            popupView.findViewById<LinearLayout>(R.id.linearLayout1) to "porta",
            popupView.findViewById<LinearLayout>(R.id.linearLayout2) to "escada",
            popupView.findViewById<LinearLayout>(R.id.linearLayout3) to "elevador",
            popupView.findViewById<LinearLayout>(R.id.linearLayout4) to "banheiro",
            popupView.findViewById<LinearLayout>(R.id.linearLayout5) to "extintor"
        )


        poiItems.forEach { (layout, name) ->
            layout.setOnClickListener {
                mapEditor.addPoi(100f, 100f, name)
                popupWindow.dismiss()
            }
            layout.background = AppCompatResources.getDrawable(this, R.drawable.ripple_clickable)
        }
    }
}
