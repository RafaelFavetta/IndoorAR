package com.example.indoorar.ui

import android.graphics.PointF

// Todas as "ações" que o editor desenha/gerencia
sealed class Action {
    data class BrushStroke(val points: List<PointF>) : Action()

    data class Poi(val position: PointF) : Action()

    // Retângulo básico (p/ “Formas”), com flag de seleção
    data class Shape(
        var start: PointF,
        var end: PointF,
        var selected: Boolean = false
    ) : Action()
}
