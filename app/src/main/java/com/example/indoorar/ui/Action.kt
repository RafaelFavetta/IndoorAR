package com.example.indoorar.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import androidx.core.graphics.toColorInt
import java.util.UUID

sealed class Action {

    data class BrushStroke(
        val points: List<PointF>
    ) : Action()

    // <<< MUDANÇA: A classe Poi agora só guarda os dados, não a imagem.
    data class Poi(
        val id: String = UUID.randomUUID().toString(),
        var x: Float,
        var y: Float,
        var width: Float = 100f,
        var height: Float = 100f,
        var iconRes: Int, // A referência do ícone (ex: R.drawable.ic_door)
        var selected: Boolean = false
    ) : Action() // Não tem mais corpo a classe

    data class Shape(
        var start: PointF,
        var end: PointF,
        var selected: Boolean = false,
        var fillColor: Int = "#D9D9D9".toColorInt(),
        var rotation: Float = 0f
    ) : Action()
}