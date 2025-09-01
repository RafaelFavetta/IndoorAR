package com.example.indoorar.model

data class Posicao(
    var x: Float = 0f,
    var y: Float = 0f
)

data class Tamanho(
    var altura: Float = 100f,
    var largura: Float = 100f
)

data class ShapeData(
    var nome: String = "",
    var descricao: String = "",
    var cor: Int = 0xFF000000.toInt(),
    var tipo: String = "retangulo", // quadrado, círculo, triângulo etc.
    var posicao: Posicao = Posicao(),
    var tamanho: Tamanho = Tamanho()
)