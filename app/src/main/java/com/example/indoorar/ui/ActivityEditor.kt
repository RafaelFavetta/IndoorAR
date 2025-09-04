package com.example.indoorar.ui

import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.example.indoorar.R
import com.example.indoorar.views.MapEditorView
import com.example.indoorar.ui.Tool

class ActivityEditor : AppCompatActivity() {

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
        findViewById<ImageView>(R.id.cursor).setOnClickListener {
            mapEditor.setTool(Tool.CURSOR)
            updateSelectedButton(R.id.cursor)
        }

        // Formas
        findViewById<ImageView>(R.id.formas).setOnClickListener {
            mapEditor.setTool(Tool.FORMAS)
            updateSelectedButton(R.id.formas)
        }

        // Brush
        findViewById<ImageView>(R.id.brush).setOnClickListener {
            mapEditor.setTool(Tool.BRUSH)
            updateSelectedButton(R.id.brush)
        }

        // POI
        findViewById<ImageView>(R.id.poi).setOnClickListener {
            mapEditor.setTool(Tool.POI)
            updateSelectedButton(R.id.poi)
        }

        // Camadas (grid por enquanto)
        findViewById<ImageView>(R.id.layers).setOnClickListener {
            mapEditor.toggleGrid()
        }

        // Desfazer
        findViewById<ImageView>(R.id.desfazer).setOnClickListener {
            mapEditor.undo()
        }

        // Define o botão inicial selecionado (cursor)
        updateSelectedButton(R.id.cursor)
    }
}
