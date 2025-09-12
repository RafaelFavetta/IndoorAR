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

    data class Poi(
        val id: String = UUID.randomUUID().toString(),
        var x: Float,
        var y: Float,
        var width: Float = 100f,
        var height: Float = 100f,
        var iconRes: Int,
        var selected: Boolean = false
    ) : Action() {
        var bitmap: Bitmap? = null

        fun loadBitmap(resources: Resources) {
            if (bitmap == null) {
                try {
                    val original = BitmapFactory.decodeResource(resources, iconRes)
                    bitmap = Bitmap.createScaledBitmap(
                        original,
                        width.toInt(),
                        height.toInt(),
                        true
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    bitmap = null
                }
            }
        }
    }

    data class Shape(
        var start: PointF,
        var end: PointF,
        var selected: Boolean = false,
        var fillColor: Int = "#D9D9D9".toColorInt(),
        var rotation: Float = 0f
    ) : Action()
}
