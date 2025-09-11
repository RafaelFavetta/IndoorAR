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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.indoorar.R
import com.example.indoorar.ui.editor.MapEditorView
import com.example.indoorar.ui.editor.AttributePanelController
import com.example.indoorar.views.ColorPickerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton

data class ToolButton(val tool: Tool?, val iconId: Int?)

class ActivityEditor : AppCompatActivity() {

    private lateinit var mapEditor: MapEditorView
    private lateinit var colorPreview: ImageView
    private lateinit var inputHex: EditText
    private lateinit var btnColorPicker: ImageButton
    private lateinit var btnSalvarMapa: MaterialButton
    private lateinit var poiCard: MaterialCardView
    private lateinit var rvPoi: RecyclerView

    private lateinit var poiAdapter: PoiAdapter
    private var draggingPoi: Action.Poi? = null

    private val poiItems = listOf(
        PoiItem(R.drawable.ic_door_azul, "porta"),
        PoiItem(R.drawable.ic_stairs_azul, "escada"),
        PoiItem(R.drawable.ic_elevator_azul, "elevador"),
        PoiItem(R.drawable.ic_banheiro_azul, "banheiro"),
        PoiItem(R.drawable.ic_extintor_azul, "extintor")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        bindViews()
        setupToolButtons()
        setupAttributePanel()
        setupColorPicker()
        setupHexInput()
        setupPoiCard()
        setupSaveButton()
        setupMapEditorTouch()
    }

    private fun bindViews() {
        mapEditor = findViewById(R.id.mapEditor)
        colorPreview = findViewById(R.id.colorPreview)
        inputHex = findViewById(R.id.inputHex)
        btnColorPicker = findViewById(R.id.btnColorPicker)
        btnSalvarMapa = findViewById(R.id.btnSalvarMapa)
        poiCard = findViewById(R.id.cardPoi)
        rvPoi = findViewById(R.id.rvPoi)
    }

    private fun setupToolButtons() {
        val toolButtons = mapOf(
            R.id.linearcursor to ToolButton(Tool.CURSOR, R.id.cursor),
            R.id.linearformas to ToolButton(Tool.FORMAS, R.id.formas),
            R.id.linearbrush to ToolButton(Tool.BRUSH, R.id.brush),
            R.id.linearpoi to ToolButton(Tool.POI, R.id.poi),
            R.id.linearlayers to ToolButton(null, null),
            R.id.lineardesfazer to ToolButton(null, null)
        )

        fun updateSelectedButton(selectedId: Int) {
            listOf(R.id.cursor, R.id.formas, R.id.brush, R.id.poi)
                .forEach { id -> findViewById<ImageView>(id).isSelected = (id == selectedId) }
        }

        toolButtons.forEach { (linearId, toolButton) ->
            findViewById<LinearLayout>(linearId).setOnClickListener {
                toolButton.tool?.let { mapEditor.setTool(it) }
                toolButton.iconId?.let { updateSelectedButton(it) }
                poiCard.visibility = if (toolButton.tool == Tool.POI) View.VISIBLE else View.GONE

                if (linearId == R.id.linearlayers) mapEditor.toggleGrid()
                if (linearId == R.id.lineardesfazer) mapEditor.undo()
            }
        }
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

    private fun setupPoiCard() {
        poiAdapter = PoiAdapter(poiItems) { poi, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> draggingPoi = Action.Poi(
                    x = 0f, y = 0f, iconRes = poi.iconRes
                )
            }
        }

        rvPoi.adapter = poiAdapter
        rvPoi.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        poiCard.visibility = View.VISIBLE
    }

    private fun setupMapEditorTouch() {
        val preview = findViewById<ImageView>(R.id.ivDragPreview)
        var draggingPoiItem: PoiItem? = null

        mapEditor.setOnTouchListener { _, event ->
            draggingPoiItem?.let { poiItem ->
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

                        mapEditor.addPoi(mapX, mapY, poiItem.iconRes)
                        draggingPoiItem = null
                        mapEditor.performClick()
                    }
                }
                true
            } ?: false
        }

        // Conecta o adapter do POI com o preview e drag
        poiAdapter = PoiAdapter(poiItems) { poi, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    draggingPoiItem = poi
                    preview.setImageResource(poi.iconRes)
                    preview.visibility = View.VISIBLE
                    preview.x = event.rawX - preview.width / 2
                    preview.y = event.rawY - preview.height / 2
                }
            }
        }
        rvPoi.adapter = poiAdapter
        rvPoi.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
    }

    private fun setupSaveButton() {
        btnSalvarMapa.setOnClickListener {
            // TODO: Salvar shapes e POIs no Firebase
        }
    }
}