package com.example.indoorar.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import com.example.indoorar.databinding.ActivityMapCreatorBinding
import com.example.indoorar.model.ShapeData
import com.example.indoorar.views.MapCanvasView

class ActivityMapCreator : AppCompatActivity() {

    private lateinit var binding: ActivityMapCreatorBinding
    private lateinit var canvasView: MapCanvasView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapCreatorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        canvasView = binding.mapCanvasView

        // Botões da sidebar
        binding.btnQuadrado.setOnClickListener { addShape("quadrado") }
        binding.btnCirculo.setOnClickListener { addShape("circulo") }
        binding.btnTriangulo.setOnClickListener { addShape("triangulo") }
        binding.btnLinha.setOnClickListener { addShape("linha") }
        binding.btnEscada.setOnClickListener { addShape("escada") }
        binding.btnElevador.setOnClickListener { addShape("elevador") }
        binding.btnPorta.setOnClickListener { addShape("porta") }
        binding.btnExtintor.setOnClickListener { addShape("extintor") }
        binding.btnBanheiro.setOnClickListener { addShape("banheiro") }

        // Popout: atualizar valores ao digitar
        binding.editLargura.addTextChangedListener(NumberTextWatcher { canvasView.selectedShape?.let { shape ->
            shape.tamanho.largura = it.toFloat(); canvasView.updateSelectedShape(shape) } })
        binding.editAltura.addTextChangedListener(NumberTextWatcher { canvasView.selectedShape?.let { shape ->
            shape.tamanho.altura = it.toFloat(); canvasView.updateSelectedShape(shape) } })
        binding.editRotacao.addTextChangedListener(NumberTextWatcher { canvasView.selectedShape?.let { shape ->
            shape.rotacao = it; canvasView.updateSelectedShape(shape) } })
        binding.editNome.addTextChangedListener(SimpleTextWatcher { canvasView.selectedShape?.let { shape ->
            shape.nome = it; canvasView.updateSelectedShape(shape) } })
        binding.editDescricao.addTextChangedListener(SimpleTextWatcher { canvasView.selectedShape?.let { shape ->
            shape.descricao = it; canvasView.updateSelectedShape(shape) } })

        binding.colorPicker.setOnColorChangedListener { color ->
            canvasView.selectedShape?.let {
                it.cor = color
                canvasView.updateSelectedShape(it)
            }
        }
    }

    private fun addShape(tipo: String) {
        val shape = ShapeData(
            tipo = tipo,
            cor = 0xFF32357A.toInt(),
            posicao = canvasView.selectedShape?.posicao ?: com.example.indoorar.model.Posicao(100f,100f)
        )
        canvasView.addShape(shape)
    }
}

class NumberTextWatcher(val onChanged: (Int) -> Unit) : TextWatcher {
    override fun afterTextChanged(s: Editable?) { s?.toString()?.toIntOrNull()?.let { onChanged(it) } }
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
}

class SimpleTextWatcher(val onChanged: (String) -> Unit) : TextWatcher {
    override fun afterTextChanged(s: Editable?) { s?.let { onChanged(it.toString()) } }
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
}
