package com.example.indoorar.ui.editor

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PointF
import android.view.MotionEvent
import com.example.indoorar.ui.Action

class BrushEditor(private val host: MapEditorView) {

    private var tempStroke: MutableList<PointF>? = null

    // ===== TOUCH =====
    fun onTouch(event: MotionEvent): Boolean {
        val p = host.screenToWorld(event.x, event.y)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tempStroke = mutableListOf(p)
                host.invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                tempStroke?.add(p)
                host.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tempStroke?.let { points ->
                    if (points.size > 1) {
                        val paint = host.getBrushPaint()
                        host.addAction(
                            Action.BrushStroke(
                                points = points.toList(),
                                color = paint.color,
                                strokeWidth = paint.strokeWidth
                            )
                        )
                    }
                }
                tempStroke = null
                host.invalidate()
            }
        }

        return true
    }

    // ===== DRAW TEMPORARY STROKE =====
    fun onDrawTemp(canvas: Canvas) {
        val points = tempStroke ?: return
        if (points.size <= 1) return

        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }

        canvas.drawPath(path, host.getBrushPaint())
    }

    // ===== CANCEL =====
    fun cancel() {
        tempStroke = null
    }
}
