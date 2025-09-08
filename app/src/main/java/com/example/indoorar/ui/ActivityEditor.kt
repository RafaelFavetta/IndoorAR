package com.example.indoorar.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import com.example.indoorar.BaseActivity
import com.example.indoorar.R
import com.example.indoorar.ui.editor.MapEditorView
import com.example.indoorar.ui.Tool
import com.example.indoorar.ui.editor.AttributePanelController
import com.example.indoorar.views.ColorPickerView

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
            val buttons = listOf(
                R.id.cursor,
                R.id.formas,
                R.id.brush,
                R.id.poi
            )
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
        }

        // Camadas (grid por enquanto)
        findViewById<LinearLayout>(R.id.linearlayers).setOnClickListener {
            mapEditor.toggleGrid()
        }

        // Desfazer
        findViewById<LinearLayout>(R.id.lineardesfazer).setOnClickListener {
            mapEditor.undo()
        }

        // Define o botão inicial selecionado (cursor)
        updateSelectedButton(R.id.cursor)

        // Depois de setar os listeners dos botões:
        AttributePanelController(this, mapEditor)

        // 🔥 Integração do ColorPickerView
        btnColorPicker.setOnClickListener {
            val pickerView = ColorPickerView(this)
            pickerView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                200
            )

            val dialog = AlertDialog.Builder(this)
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

        // 🔥 Listener para digitar HEX manual
        inputHex.setOnEditorActionListener { _, _, _ ->
            val hex = inputHex.text.toString()
            try {
                val color = Color.parseColor(hex)
                colorPreview.setBackgroundColor(color)
            } catch (e: IllegalArgumentException) {
                inputHex.error = "Hex inválido"
            }
            true
        }
    }
}