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
    POI,
    TEXTO,
    LAYERS,
    DESFAZER
}
class ActivityEditor : AppCompatActivity() {


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
        val cursorLayout = findViewById<LinearLayout>(R.id.linear)
        val formasLayout = findViewById<LinearLayout>(R.id.linear2)
        val iconesLayout = findViewById<LinearLayout>(R.id.linear3)
        val textoLayout = findViewById<LinearLayout>(R.id.linear4)
        val layersLayout = findViewById<LinearLayout>(R.id.linear5)
        val desfazerLayout = findViewById<LinearLayout>(R.id.linear6)

        // Pega os ícones
        val cursor = findViewById<ImageView>(R.id.cursor)
        val formas = findViewById<ImageView>(R.id.formas)
        val icones = findViewById<ImageView>(R.id.icones)
        val texto = findViewById<ImageView>(R.id.texto)
        val layers = findViewById<ImageView>(R.id.layers)
        val desfazer = findViewById<ImageView>(R.id.desfazer)

        // Lista de pares (layout -> ícone)
        val layouts = listOf(
            cursorLayout to cursor,
            formasLayout to formas,
            iconesLayout to icones,
            textoLayout to texto,
            layersLayout to layers,
            desfazerLayout to desfazer
        )

        // Função pra marcar só 1 como selecionado
        fun selectIcon(selected: ImageView) {
            listOf(cursor, formas, icones, texto, layers, desfazer).forEach {
                it.isSelected = false
            }
            selected.isSelected = true
        }

        // Adiciona clique em cada layout
        layouts.forEach { (layout, icon) ->
            layout.setOnClickListener {
                selectIcon(icon)
            }
        }

        // Define o cursor como selecionado ao abrir
        selectIcon(cursor)



    }
}
