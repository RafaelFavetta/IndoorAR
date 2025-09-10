package com.example.indoorar.ui

import android.graphics.PointF
import androidx.core.graphics.toColorInt

sealed class Action {
    data class BrushStroke(val points: List<PointF>) : Action()
    data class Poi(
        var start: PointF,
        var end: PointF,
        val iconName: String,
        var selected: Boolean = false
    ) : Action()

    data class Shape(
        var start: PointF,
        var end: PointF,
        var selected: Boolean = false,
        var fillColor: Int = "#D9D9D9".toColorInt(),
        var rotation: Int = 0
    ) : Action()
}