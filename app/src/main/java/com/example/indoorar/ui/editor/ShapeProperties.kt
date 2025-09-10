package com.example.indoorar.ui.editor

import android.graphics.Color
import androidx.core.graphics.toColorInt

data class ShapeProperties(

    var name: String? = null,
    var x: Float,
    var y: Float,
    var width: Float,
    var height: Float,
    var rotation: Float = 0f,
    var fillColor: Int = 0xFFCCCCCC.toInt()
)