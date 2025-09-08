package com.example.indoorar.ui.editor

data class ShapeProperties(
    var x: Float,
    var y: Float,
    var width: Float,
    var height: Float,
    var rotation: Float = 0f,       // opcional; se não usar, ignore
    var fillColor: Int? = null,     // opcional (ColorInt)
)
