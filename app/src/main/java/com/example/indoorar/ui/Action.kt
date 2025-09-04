package com.example.indoorar.ui

import android.graphics.PointF

sealed class Action {
    data class BrushStroke(val points: List<PointF>) : Action()
    data class Poi(val position: PointF) : Action()
    // depois podemos adicionar: DrawShape(Rect/Circle) etc.
}

