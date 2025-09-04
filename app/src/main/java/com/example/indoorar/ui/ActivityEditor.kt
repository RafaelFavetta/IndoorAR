package com.example.indoorar.ui

import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.indoorar.R

enum class Tool {
    CURSOR,
    FORMAS,
    BRUSH,
    POI,
    LAYERS,
    DESFAZER
}

class ActivityEditor : AppCompatActivity() {

    private var selectedTool: Tool = Tool.CURSOR

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_editor)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Pega os layouts (área clicável)
        val cursorLayout = findViewById<LinearLayout>(R.id.linearcursor)
        val formasLayout = findViewById<LinearLayout>(R.id.linearformas)
        val brushLayout = findViewById<LinearLayout>(R.id.linearbrush)
        val poiLayout = findViewById<LinearLayout>(R.id.linearpoi)
        val layersLayout = findViewById<LinearLayout>(R.id.linearlayers)
        val desfazerLayout = findViewById<LinearLayout>(R.id.lineardesfazer)

        // Pega os ícones
        val cursor = findViewById<ImageView>(R.id.cursor)
        val formas = findViewById<ImageView>(R.id.formas)
        val brush = findViewById<ImageView>(R.id.brush)
        val poi = findViewById<ImageView>(R.id.poi)
        val layers = findViewById<ImageView>(R.id.layers)
        val desfazer = findViewById<ImageView>(R.id.desfazer)

        // Inclui o brush na lista
        val icons = listOf(cursor, formas, brush, poi, layers, desfazer)

        // Função pra marcar só 1 como selecionado e setar ferramenta
        fun selectIcon(selected: ImageView, tool: Tool) {
            icons.forEach { it.isSelected = false }
            selected.isSelected = true
            selectedTool = tool
        }

        // Adiciona clique em cada layout
        cursorLayout.setOnClickListener { selectIcon(cursor, Tool.CURSOR) }
        formasLayout.setOnClickListener { selectIcon(formas, Tool.FORMAS) }
        brushLayout.setOnClickListener { selectIcon(brush, Tool.BRUSH) }
        poiLayout.setOnClickListener { selectIcon(poi, Tool.POI) }
        layersLayout.setOnClickListener { selectIcon(layers, Tool.LAYERS) }
        desfazerLayout.setOnClickListener { selectIcon(desfazer, Tool.DESFAZER) }

        // Define o cursor como selecionado ao abrir
        selectIcon(cursor, Tool.CURSOR)
    }
}
