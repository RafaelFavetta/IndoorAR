package com.example.indoorar.model

data class Posicao(
    var x: Float = 0f,
    var y: Float = 0f
)

data class Tamanho(
    var largura: Float = 100f,
    var altura: Float = 100f
)

data class ShapeData(
    var nome: String = "",
    var descricao: String = "",
    var cor: Int = 0xFF32357A.toInt(),
    var tipo: String = "",
    var posicao: Posicao = Posicao(),
    var tamanho: Tamanho = Tamanho(),
    var rotacao: Int = 0
)
