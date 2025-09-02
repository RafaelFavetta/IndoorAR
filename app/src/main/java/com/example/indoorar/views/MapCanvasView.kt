package com.example.indoorar.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.indoorar.R
import com.example.indoorar.model.ShapeData

class MapCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var shapes = mutableListOf<ShapeData>()
    var selectedShape: ShapeData? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        shapes.forEach { shape ->
            canvas.save()
            val centerX = shape.posicao.x + shape.tamanho.largura / 2
            val centerY = shape.posicao.y + shape.tamanho.altura / 2
            canvas.rotate(shape.rotacao.toFloat(), centerX, centerY)

            when (shape.tipo) {
                "quadrado", "retangulo" -> {
                    paint.color = shape.cor
                    canvas.drawRect(
                        shape.posicao.x,
                        shape.posicao.y,
                        shape.posicao.x + shape.tamanho.largura,
                        shape.posicao.y + shape.tamanho.altura,
                        paint
                    )
                }
                "circulo" -> {
                    paint.color = shape.cor
                    canvas.drawCircle(
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
                    path.lineTo(shape.posicao.x + shape.tamanho.largura, shape.posicao.y + shape.tamanho.altura)
                    path.close()
                    canvas.drawPath(path, paint)
                }
                // Símbolos especiais como PNG
                "escada", "elevador", "porta", "extintor", "banheiro" -> {
                    val resId = when (shape.tipo) {
                        "escada" -> R.drawable.ic_escada
                        "elevador" -> R.drawable.ic_elevador
                        "porta" -> R.drawable.ic_porta
                        "extintor" -> R.drawable.ic_extintor
                        "banheiro" -> R.drawable.ic_banheiro
                        else -> 0
                    }
                    if (resId != 0) {
                        val bitmap = BitmapFactory.decodeResource(resources, resId)
                        val scaledBitmap = Bitmap.createScaledBitmap(
                            bitmap,
                            shape.tamanho.largura.toInt(),
                            shape.tamanho.altura.toInt(),
                            true
                        )
                        canvas.drawBitmap(
                            scaledBitmap,
                            shape.posicao.x,
                            shape.posicao.y,
                            paint
                        )
                    }
                }
            }
            canvas.restore()
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

    fun updateSelectedShape(shape: ShapeData) {
        val index = selectedShape?.let { shapes.indexOf(it) } ?: -1
        if (index != -1) {
            shapes[index] = shape
            selectedShape = shape
            invalidate()
        }
    }
}