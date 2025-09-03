package com.example.indoorar.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class MapEditorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        style = Paint.Style.FILL
    }

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prevScale = scale
                scale *= detector.scaleFactor
                scale = max(0.5f, min(scale, 3f))

                // manter o foco no ponto do gesto
                val focusX = detector.focusX
                val focusY = detector.focusY
                offsetX = (offsetX - focusX) * (scale / prevScale) + focusX
                offsetY = (offsetY - focusY) * (scale / prevScale) + focusY

                invalidate()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                offsetX -= distanceX
                offsetY -= distanceY
                invalidate()
                return true
            }
        })


    override fun onTouchEvent(event: MotionEvent): Boolean {
        val a = scaleDetector.onTouchEvent(event)
        val b = gestureDetector.onTouchEvent(event)
        return a || b || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        val spacing = 50f  // espaçamento entre os pontos
        val radius = 3f    // tamanho do ponto

        val cols = (width / spacing / scale).toInt() + 4
        val rows = (height / spacing / scale).toInt() + 4

        for (i in -cols..cols) {
            for (j in -rows..rows) {
                val x = i * spacing
                val y = j * spacing
                canvas.drawCircle(x, y, radius, dotPaint)
            }
        }

        canvas.restore()
    }
}
