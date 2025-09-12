package com.example.indoorar.ui

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.cardview.widget.CardView
import com.example.indoorar.R
import com.example.indoorar.ui.editor.MapEditorView
import com.example.indoorar.ui.editor.AttributePanelController
import com.example.indoorar.views.ColorPickerView
import com.google.android.material.button.MaterialButton

data class ActionPoi(val x: Float, val y: Float, val iconRes: Int)

class ActivityEditor : AppCompatActivity() {

    private lateinit var mapEditor: MapEditorView
    private lateinit var colorPreview: ImageView
    private lateinit var inputHex: EditText
    private lateinit var btnColorPicker: ImageButton
    private lateinit var btnSalvarMapa: MaterialButton
    private lateinit var poiCard: CardView
    private lateinit var preview: ImageView

    private var draggingPoi: ActionPoi? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        bindViews()
        setupToolButtons()
        setupAttributePanel()
        setupColorPicker()
        setupHexInput()
        setupPoiClicks()
        setupMapEditorTouch()
        setupSaveButton()
    }

    private fun bindViews() {
        mapEditor = findViewById(R.id.mapEditor)
        colorPreview = findViewById(R.id.colorPreview)
        inputHex = findViewById(R.id.inputHex)
        btnColorPicker = findViewById(R.id.btnColorPicker)
        btnSalvarMapa = findViewById(R.id.btnSalvarMapa)
        poiCard = findViewById(R.id.cardPoi)
        preview = findViewById(R.id.ivDragPreview)
    }

    private fun setupToolButtons() {
        val toolButtons = mapOf(
            R.id.linearcursor to Tool.CURSOR,
            R.id.linearformas to Tool.FORMAS,
            R.id.linearbrush to Tool.BRUSH,
            R.id.linearpoi to Tool.POI
        )

        fun updateSelectedButton(selectedId: Int) {
            listOf(R.id.cursor, R.id.formas, R.id.brush, R.id.poi)
                .forEach { id -> findViewById<ImageView>(id).isSelected = (id == selectedId) }
        }

        toolButtons.forEach { (linearId, tool) ->
            findViewById<LinearLayout>(linearId).setOnClickListener {
                mapEditor.setTool(tool)
                val iconId = when (linearId) {
                    R.id.linearcursor -> R.id.cursor
                    R.id.linearformas -> R.id.formas
                    R.id.linearbrush -> R.id.brush
                    R.id.linearpoi -> R.id.poi
                    else -> null
                }
                iconId?.let { updateSelectedButton(it) }
                poiCard.visibility = if (tool == Tool.POI) View.VISIBLE else View.GONE
            }
        }

        // Layers e desfazer
        findViewById<LinearLayout>(R.id.linearlayers).setOnClickListener { mapEditor.toggleGrid() }
        findViewById<LinearLayout>(R.id.lineardesfazer).setOnClickListener { mapEditor.undo() }

        updateSelectedButton(R.id.cursor)
    }

    private fun setupAttributePanel() {
        AttributePanelController(this, mapEditor)
    }

    private fun setupColorPicker() {
        btnColorPicker.setOnClickListener {
            val pickerView = ColorPickerView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 200
                )
            }
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
    }

    private fun setupHexInput() {
        inputHex.setOnEditorActionListener { _, _, _ ->
            val hex = inputHex.text.toString()
            try { colorPreview.setBackgroundColor(hex.toColorInt()) }
            catch (_: IllegalArgumentException) { inputHex.error = "Hex inválido" }
            true
        }
    }

    private fun setupPoiClicks() {
        val poiMap = mapOf(
            R.id.poiPorta to R.drawable.ic_door_azul,
            R.id.poiEscada to R.drawable.ic_stairs_azul,
            R.id.poiElevador to R.drawable.ic_elevator_azul,
            R.id.poiBanheiro to R.drawable.ic_banheiro_azul,
            R.id.poiExtintor to R.drawable.ic_extintor_azul
        )

        poiMap.forEach { (id, iconRes) ->
            findViewById<LinearLayout>(id).setOnClickListener {
                draggingPoi = ActionPoi(0f, 0f, iconRes)
                preview.setImageResource(iconRes)
                preview.visibility = View.VISIBLE
                poiCard.visibility = View.GONE  // <<< Esconde o card automaticamente
            }
        }
    }


    private fun setupMapEditorTouch() {
        mapEditor.setOnTouchListener { _, event ->
            draggingPoi?.let { poi ->
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> {
                        preview.x = event.rawX - preview.width / 2
                        preview.y = event.rawY - preview.height / 2
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        preview.visibility = View.GONE
                        val location = IntArray(2)
                        mapEditor.getLocationOnScreen(location)
                        val mapX = (event.rawX - location[0]) / mapEditor.scale - mapEditor.offsetX / mapEditor.scale
                        val mapY = (event.rawY - location[1]) / mapEditor.scale - mapEditor.offsetY / mapEditor.scale
                        mapEditor.addPoi(mapX, mapY, poi.iconRes)
                        draggingPoi = null
                        mapEditor.performClick()
                    }
                }
                true
            } ?: false
        }
    }

    private fun setupSaveButton() {
        btnSalvarMapa.setOnClickListener {
            // TODO: salvar shapes e POIs
        }
    }
}
