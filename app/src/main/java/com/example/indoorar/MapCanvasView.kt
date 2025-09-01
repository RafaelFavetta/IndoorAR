package com.example.indoorar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.indoorar.model.ShapeData

class MapCanvasView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val shapes = mutableListOf<ShapeData>()
    private val paint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private var selectedShape: ShapeData? = null

    fun addShape(shape: ShapeData) {
        shapes.add(shape)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        shapes.forEach { shape ->
            paint.color = shape.cor
            when (shape.tipo) {
                "retangulo", "quadrado" -> {
                    canvas.drawRect(
                        shape.posicao.x,
                        shape.posicao.y,
                        shape.posicao.x + shape.tamanho.largura,
                        shape.posicao.y + shape.tamanho.altura,
                        paint
                    )
                }
                "circulo" -> {
                    canvas.drawCircle(
                        shape.posicao.x,
                        shape.posicao.y,
                        shape.tamanho.largura / 2,
                        paint
                    )
                }
                // outros tipos como triângulo, hexágono, etc.
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                selectedShape = shapes.findLast {
                    event.x in it.posicao.x..(it.posicao.x + it.tamanho.largura) &&
                            event.y in it.posicao.y..(it.posicao.y + it.tamanho.altura)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                selectedShape?.let {
                    it.posicao.x = event.x
                    it.posicao.y = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                selectedShape = null
            }
        }
        return true
    }
}