package com.example.indoorar

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.*
import kotlin.math.max
import kotlin.math.min

class MapEditorView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    private val paint = Paint().apply {
        color = Color.CYAN
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val shapes = mutableListOf<Shape>()

    // Pan & Zoom
    private var scaleFactor = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    private var currentTool: Tool = Tool.SELECT

    enum class Tool { SELECT, RECTANGLE, CIRCLE }

    fun setTool(tool: Tool) {
        currentTool = tool
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scaleFactor, scaleFactor)

        drawGrid(canvas)

        for (shape in shapes) {
            shape.draw(canvas, paint)
        }

        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas) {
        val gridPaint = Paint().apply {
            color = Color.parseColor("#222833")
            strokeWidth = 1f
        }
        val step = 100
        for (x in 0..width step step) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), gridPaint)
        }
        for (y in 0..height step step) {
            canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), gridPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        if (event.action == MotionEvent.ACTION_DOWN) {
            val x = (event.x - offsetX) / scaleFactor
            val y = (event.y - offsetY) / scaleFactor

            when (currentTool) {
                Tool.RECTANGLE -> {
                    shapes.add(RectangleShape(x - 100, y - 100, x + 100, y + 100))
                    invalidate()
                }
                Tool.CIRCLE -> {
                    shapes.add(CircleShape(x, y, 100f))
                    invalidate()
                }
                else -> {}
            }
        }
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = max(0.5f, min(scaleFactor, 3.0f))
            invalidate()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent?, distanceX: Float, distanceY: Float
        ): Boolean {
            offsetX -= distanceX
            offsetY -= distanceY
            invalidate()
            return true
        }
    }
}
