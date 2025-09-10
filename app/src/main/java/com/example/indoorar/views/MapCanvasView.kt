package com.example.indoorar.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.indoorar.R
import com.example.indoorar.model.ShapeData
import androidx.core.graphics.scale
import androidx.core.graphics.withSave
import com.example.indoorar.ui.Action

class MapCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var shapes = mutableListOf<ShapeData>()
    var selectedShape: ShapeData? = null

    private val pois = mutableListOf<Action.Poi>()


    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        shapes.forEach { shape ->
            canvas.withSave {
                val centerX = shape.posicao.x + shape.tamanho.largura / 2
                val centerY = shape.posicao.y + shape.tamanho.altura / 2
                rotate(shape.rotacao.toFloat(), centerX, centerY)

                when (shape.tipo) {
                    "quadrado", "retangulo" -> {
                        paint.color = shape.cor
                        drawRect(
                            shape.posicao.x,
                            shape.posicao.y,
                            shape.posicao.x + shape.tamanho.largura,
                            shape.posicao.y + shape.tamanho.altura,
                            paint
                        )
                    }

                    "circulo" -> {
                        paint.color = shape.cor
                        drawCircle(
                            centerX,
                            centerY,
                            shape.tamanho.largura / 2,
                            paint
                        )
                    }

                    "triangulo" -> {
                        paint.color = shape.cor
                        val path = Path()
                        path.moveTo(centerX, shape.posicao.y)
                        path.lineTo(shape.posicao.x, shape.posicao.y + shape.tamanho.altura)
                        path.lineTo(
                            shape.posicao.x + shape.tamanho.largura,
                            shape.posicao.y + shape.tamanho.altura
                        )
                        path.close()
                        drawPath(path, paint)
                    }

                    "escada", "elevador", "porta", "extintor", "banheiro" -> {
                        val resId = when (shape.tipo) {
                            "escada" -> R.drawable.ic_stairs_branco
                            "elevador" -> R.drawable.ic_elevator_branco
                            "porta" -> R.drawable.ic_door_branco
                            "extintor" -> R.drawable.ic_extintor_branco
                            "banheiro" -> R.drawable.ic_banheiro_branco
                            else -> 0
                        }
                        if (resId != 0) {
                            val bitmap = BitmapFactory.decodeResource(resources, resId)
                            val scaledBitmap = bitmap.scale(
                                shape.tamanho.largura.toInt(),
                                shape.tamanho.altura.toInt()
                            )
                            drawBitmap(
                                scaledBitmap,
                                shape.posicao.x,
                                shape.posicao.y,
                                paint
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                selectedShape = shapes.findLast { shape ->
                    event.x in shape.posicao.x..(shape.posicao.x + shape.tamanho.largura) &&
                            event.y in shape.posicao.y..(shape.posicao.y + shape.tamanho.altura)
                }
                invalidate()
            }
        }
        return true
    }

    fun addShape(shape: ShapeData) {
        shapes.add(shape)
        selectedShape = shape
        invalidate()
    }

    fun addPoi(poi: Action.Poi) {
        pois.add(poi)
        invalidate()
    }


    fun updateSelectedShape(shape: ShapeData) {
        val index = selectedShape?.let { shapes.indexOf(it) } ?: -1
        if (index != -1) {
            shapes[index] = shape
            selectedShape = shape
            invalidate()
        }
    }
}