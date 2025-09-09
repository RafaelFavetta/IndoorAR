package com.example.indoorar.ui

import android.graphics.PointF
import androidx.core.graphics.toColorInt

sealed class Action {
    data class BrushStroke(val points: List<PointF>) : Action()

    data class Poi(val position: PointF, val name: String) : Action()

    data class Shape(
        var start: PointF,
        var end: PointF,
        var selected: Boolean = false,
        var fillColor: Int = "#D9D9D9".toColorInt(),
        var rotation: Float = 0f
    ) : Action()
}