package com.example.indoorar.ui.editor

import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import com.example.indoorar.ui.Action
import com.example.indoorar.ui.editor.MapEditorView

class ShapeEditor(private val host: MapEditorView) {
    private var tempStart: PointF? = null
    private var tempEnd: PointF? = null
    private var currentType: Action.ShapeType = Action.ShapeType.RECTANGLE

    fun setType(type: Action.ShapeType) {
        currentType = type
    }

    fun onTouch(event: MotionEvent): Boolean {
        val p = host.screenToWorld(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tempStart = PointF(p.x, p.y)
                tempEnd = PointF(p.x, p.y)
                host.invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val s = tempStart
                if (s == null) {
                    tempStart = PointF(p.x, p.y)
                    tempEnd = PointF(p.x, p.y)
                } else {
                    tempEnd = adjustForType(s, PointF(p.x, p.y))
                }
                host.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val s = tempStart
                val e = tempEnd
                if (s != null && e != null) {
                    val adjustedEnd = adjustForType(s, e)
                    host.addAction(Action.Shape(PointF(s.x, s.y), PointF(adjustedEnd.x, adjustedEnd.y), type = currentType))
                }
                tempStart = null
                tempEnd = null
                host.invalidate()
            }
        }
        return true
    }

    private fun adjustForType(start: PointF, end: PointF): PointF {
        return when (currentType) {
            Action.ShapeType.SQUARE, Action.ShapeType.CIRCLE -> {
                val dx = end.x - start.x
                val dy = end.y - start.y
                val size = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                PointF(start.x + size * kotlin.math.sign(dx.takeIf { it != 0f } ?: 1f),
                    start.y + size * kotlin.math.sign(dy.takeIf { it != 0f } ?: 1f))
            }
            else -> end
        }
    }

    fun onDrawTemp(canvas: Canvas) {
        val s = tempStart ?: return
        val e = tempEnd ?: return
        val adjE = adjustForType(s, e)
        when (currentType) {
            Action.ShapeType.RECTANGLE, Action.ShapeType.SQUARE -> {
                val rect = RectF(s.x, s.y, adjE.x, adjE.y)
                canvas.drawRect(rect, host.shapeTempPaint)
            }
            Action.ShapeType.CIRCLE -> {
                val left = minOf(s.x, adjE.x)
                val top = minOf(s.y, adjE.y)
                val right = maxOf(s.x, adjE.x)
                val bottom = maxOf(s.y, adjE.y)
                val cx = (left + right) / 2f
                val cy = (top + bottom) / 2f
                val radius = (right - left) / 2f // square ensured
                canvas.drawCircle(cx, cy, radius, host.shapeTempPaint)
            }
            Action.ShapeType.TRIANGLE -> {
                val left = minOf(s.x, adjE.x)
                val top = minOf(s.y, adjE.y)
                val right = maxOf(s.x, adjE.x)
                val bottom = maxOf(s.y, adjE.y)
                val path = android.graphics.Path()
                path.moveTo((left + right) / 2f, top)
                path.lineTo(left, bottom)
                path.lineTo(right, bottom)
                path.close()
                canvas.drawPath(path, host.shapeTempPaint)
            }
            Action.ShapeType.LINE -> {
                canvas.drawLine(s.x, s.y, adjE.x, adjE.y, host.shapeTempPaint)
            }
        }
    }

    fun cancel() {
        tempStart = null
        tempEnd = null
    }
}
