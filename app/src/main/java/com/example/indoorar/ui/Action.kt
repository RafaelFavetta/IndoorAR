package com.example.indoorar.ui

import android.graphics.PointF

sealed class Action {
    data class BrushStroke(val points: List<PointF>) : Action()
    data class Poi(val position: PointF) : Action()

    // NOVO: Forma geométrica básica (retângulo por enquanto)
    data class Shape(
        val start: PointF,
        val end: PointF
    ) : Action()
}
