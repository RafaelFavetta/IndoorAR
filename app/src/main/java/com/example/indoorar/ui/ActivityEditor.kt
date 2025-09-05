package com.example.indoorar.ui

import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import com.example.indoorar.BaseActivity
import com.example.indoorar.R
import com.example.indoorar.views.MapEditorView
import com.example.indoorar.ui.Tool

class ActivityEditor : BaseActivity() {

    private lateinit var mapEditor: MapEditorView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        mapEditor = findViewById(R.id.mapEditor)

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
    }
}
