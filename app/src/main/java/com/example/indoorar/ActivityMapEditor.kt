package com.example.indoorar

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.indoorar.model.Posicao
import com.example.indoorar.model.ShapeData
import com.example.indoorar.model.Tamanho
import com.example.indoorar.ui.MapCanvasView

class MapCreationActivity : AppCompatActivity() {

    private lateinit var mapCanvas: MapCanvasView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_editor)

        mapCanvas = findViewById(R.id.mapCanvas)

        // Exemplo: adicionar formas iniciais
        mapCanvas.addShape(
            ShapeData(
                nome = "Sala 1",
                descricao = "Sala de aula",
                cor = Color.BLUE,
                tipo = "retangulo",
                posicao = Posicao(200f, 200f),
                tamanho = Tamanho(200f, 150f)
            )
        )

        mapCanvas.addShape(
            ShapeData(
                nome = "Extintor",
                descricao = "Extintor de incêndio",
                cor = Color.RED,
                tipo = "circulo",
                posicao = Posicao(500f, 400f),
                tamanho = Tamanho(100f, 100f)
            )
        )
    }
}
