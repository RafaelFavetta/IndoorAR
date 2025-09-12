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

    fun onTouch(event: MotionEvent): Boolean {
        val p = host.screenToWorld(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tempStart = p
                tempEnd = p
                host.invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                tempEnd = p
                host.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val s = tempStart
                val e = tempEnd
                if (s != null && e != null) host.addAction(Action.Shape(s, e))
                tempStart = null
                tempEnd = null
                host.invalidate()
            }
        }
        return true
    }

    fun onDrawTemp(canvas: Canvas) {
        val s = tempStart ?: return
        val e = tempEnd ?: return
        val rect = RectF(s.x, s.y, e.x, e.y)
        canvas.drawRect(rect, host.shapeTempPaint) // <- Corrigido aqui
    }

    fun cancel() {
        tempStart = null
        tempEnd = null
    }
}
