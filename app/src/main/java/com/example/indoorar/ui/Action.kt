package com.example.indoorar.ui

import android.graphics.PointF

sealed class Action {
    data class BrushStroke(val points: List<PointF>) : Action()
    data class Poi(val position: PointF) : Action()
    data class Shape(
        var start: PointF,
        var end: PointF,
        var selected: Boolean = false
    ) : Action()
}
