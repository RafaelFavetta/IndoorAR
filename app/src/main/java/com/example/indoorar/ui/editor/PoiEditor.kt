package com.example.indoorar.ui.editor

import android.graphics.Canvas
import android.view.MotionEvent
import com.example.indoorar.ui.Action
import com.example.indoorar.views.MapEditorView

class PoiEditor(private val host: MapEditorView) {
    fun onTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            host.addAction(Action.Poi(host.screenToWorld(event.x, event.y)))
            host.invalidate()
        }
        return true
    }
    fun onDrawTemp(canvas: Canvas) { /* no preview */ }
    fun cancel() {}
}
