package com.example.indoorar.ui

import android.os.Bundle
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.indoorar.R

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

        // Pega os ícones
        val cursor = findViewById<ImageView>(R.id.cursor)
        val formas = findViewById<ImageView>(R.id.formas)
        val icones = findViewById<ImageView>(R.id.icones)
        val texto = findViewById<ImageView>(R.id.texto)
        val layers = findViewById<ImageView>(R.id.layers)
        val desfazer = findViewById<ImageView>(R.id.desfazer)

        val icons = listOf(cursor, formas, icones, texto, layers, desfazer)

        // Função pra marcar só 1 como selecionado
        fun selectIcon(selected: ImageView) {
            icons.forEach { it.isSelected = false }
            selected.isSelected = true
        }

        // Adiciona clique em todos
        icons.forEach { icon ->
            icon.setOnClickListener {
                selectIcon(icon)
            }
        }

        // Define o cursor como selecionado ao abrir
        selectIcon(cursor)
    }
}
