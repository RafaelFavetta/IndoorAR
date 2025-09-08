package com.example.indoorar.ui

import android.content.Context
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.graphics.toColorInt
import com.example.indoorar.BaseActivity
import com.example.indoorar.R
import com.example.indoorar.ui.editor.MapEditorView
import com.example.indoorar.ui.Tool
import com.example.indoorar.ui.editor.AttributePanelController
import com.example.indoorar.ui.editor.ShapeProperties
import com.example.indoorar.views.ColorPickerView
import android.app.AlertDialog


class ActivityEditor : BaseActivity() {

    private lateinit var mapEditor: MapEditorView
    private lateinit var colorPreview: ImageView
    private lateinit var inputHex: EditText
    private lateinit var btnColorPicker: ImageButton
    private lateinit var rotationSeekBar: SeekBar
    private lateinit var rotationLabel: TextView
    private lateinit var inputShapeName: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        mapEditor = findViewById(R.id.mapEditor)
        colorPreview = findViewById(R.id.colorPreview)
        inputHex = findViewById(R.id.inputHex)
        btnColorPicker = findViewById(R.id.btnColorPicker)
        rotationSeekBar = findViewById(R.id.rotationSeekBar)
        rotationLabel = findViewById(R.id.rotationLabel)
        inputShapeName = findViewById(R.id.inputNome)

        // Atualiza botão ativo
        fun updateSelectedButton(selectedId: Int) {
            val buttons = listOf(R.id.cursor, R.id.formas, R.id.brush, R.id.poi)
            buttons.forEach { id -> findViewById<ImageView>(id).isSelected = (id == selectedId) }
        }

        // Ferramentas
        findViewById<LinearLayout>(R.id.linearcursor).setOnClickListener {
            mapEditor.setTool(Tool.CURSOR)
            updateSelectedButton(R.id.cursor)
        }
        findViewById<LinearLayout>(R.id.linearformas).setOnClickListener {
            mapEditor.setTool(Tool.FORMAS)
            updateSelectedButton(R.id.formas)
        }
        findViewById<LinearLayout>(R.id.linearbrush).setOnClickListener {
            mapEditor.setTool(Tool.BRUSH)
            updateSelectedButton(R.id.brush)
        }
        findViewById<LinearLayout>(R.id.linearpoi).setOnClickListener {
            mapEditor.setTool(Tool.POI)
            updateSelectedButton(R.id.poi)
        }

        // Camadas e desfazer
        findViewById<LinearLayout>(R.id.linearlayers).setOnClickListener { mapEditor.toggleGrid() }
        findViewById<LinearLayout>(R.id.lineardesfazer).setOnClickListener { mapEditor.undo() }

        // Botão inicial
        updateSelectedButton(R.id.cursor)

        // Inicializa painel de atributos
        AttributePanelController(this, mapEditor)

        // --- Color picker ---
        btnColorPicker.setOnClickListener {
            val pickerView = ColorPickerView(this)
            pickerView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                200
            )
            val dialog = AlertDialog.Builder(this)
                .setTitle("Escolha uma cor")
                .setView(pickerView)
                .setPositiveButton("OK") { dialogInterface: android.content.DialogInterface, _: Int ->
                    dialogInterface.dismiss()
                }
                .create()

            pickerView.setOnColorChangedListener { color ->
                colorPreview.setBackgroundColor(color)
                inputHex.setText(String.format("#%06X", 0xFFFFFF and color))
                mapEditor.getSelectedShapeProperties()?.let { props ->
                    props.fillColor = color
                    mapEditor.applyPropertiesToSelectedShape(props)
                }
            }

            dialog.show()
        }

        // Hex manual
        inputHex.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)

                val hex = inputHex.text.toString()
                try {
                    val color = hex.toColorInt()
                    colorPreview.setBackgroundColor(color)
                    mapEditor.getSelectedShapeProperties()?.let { props ->
                        props.fillColor = color
                        mapEditor.applyPropertiesToSelectedShape(props)
                    }
                    inputHex.setText(String.format("#%06X", 0xFFFFFF and color))
                } catch (e: IllegalArgumentException) {
                    inputHex.error = "Hex inválido"
                }
                true
            } else false
        }

        // Nome da forma (Enter fecha teclado e salva)
        inputShapeName.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)

                val name = inputShapeName.text.toString().trim()
                if (name.isNotEmpty()) {
                    mapEditor.getSelectedShapeProperties()?.let { props ->
                        props.name = name
                        mapEditor.applyPropertiesToSelectedShape(props)
                    }
                }
                true
            } else false
        }

        // Rotação principal da tela (sempre Int)
        rotationSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                rotationLabel.text = "$progress°"
                mapEditor.getSelectedShapeProperties()?.let { props ->
                    props.rotation = progress // <-- agora é Int
                    mapEditor.applyPropertiesToSelectedShape(props)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Atualiza inputs quando seleciona formas
        mapEditor.selectionListener = object : MapEditorView.OnShapeSelectionListener {
            override fun onShapeSelected(props: ShapeProperties) {
                rotationSeekBar.progress = props.rotation
                rotationLabel.text = "${props.rotation}°"
                inputHex.setText(String.format("#%06X", 0xFFFFFF and props.fillColor))
                inputShapeName.setText(props.name ?: "")
                colorPreview.setBackgroundColor(props.fillColor)
            }

            override fun onShapeDeselected() {
                rotationSeekBar.progress = 0
                rotationLabel.text = "0°"
                inputHex.setText("")
                inputShapeName.setText("")
                colorPreview.setBackgroundColor(0xFFFFFFFF.toInt())
            }
        }
    }
}