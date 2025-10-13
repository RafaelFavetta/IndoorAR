package com.example.indoorar.ui

import android.graphics.PointF
import androidx.core.graphics.toColorInt
import java.util.UUID

sealed class Action {

    data class BrushStroke(
        val points: List<PointF>,
        val color: Int,
        val strokeWidth: Float
    ) : Action()

    // Novo tipo: Texto
    data class Text(
        var x: Float,
        var y: Float,
        var text: String,
        var sizeSp: Float = 14f,
        var color: Int = "#000000".toColorInt(),
        var selected: Boolean = false
    ) : Action()

    // POI sem 'nome' e 'descricao'
    data class Poi(
        val id: String = UUID.randomUUID().toString(),
        var x: Float,
        var y: Float,
        var width: Float = 100f,
        var height: Float = 100f,
        var iconRes: Int,
        var selected: Boolean = false,
        var isStartQR: Boolean = false,
        var rotation: Float = 0f
    ) : Action()

    // Tipos de formas suportadas
    enum class ShapeType { RECTANGLE, SQUARE, CIRCLE, TRIANGLE, LINE }

    // Shape sem 'descricao'; mantém 'nome'
    data class Shape(
        var start: PointF,
        var end: PointF,
        var selected: Boolean = false,
        var fillColor: Int = "#D9D9D9".toColorInt(),
        var rotation: Float = 0f,
        var isWalkable: Boolean = true,
        var nome: String = "",
        var type: ShapeType = ShapeType.RECTANGLE,
        // Novos atributos opcionais de edição visual
        var cornerRadius: Float = 0f,          // em coordenadas do canvas (mesma unidade de start/end)
        var strokeEnabled: Boolean = true,     // exibir traçado (contorno) ao desenhar
        var manualCornerModified: Boolean = false, // se o usuário alterou manualmente (não auto-ajustar)
        // Flags de bordas (para merge automático de contornos). Só usadas em retângulos/quadrados.
        var edgeTop: Boolean = true,
        var edgeRight: Boolean = true,
        var edgeBottom: Boolean = true,
        var edgeLeft: Boolean = true
    ) : Action()
}