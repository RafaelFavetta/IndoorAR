package com.example.indoorar

import android.graphics.Canvas
import android.graphics.Paint

abstract class Shape {
    abstract fun draw(canvas: Canvas, paint: Paint)
    abstract fun contains(x: Float, y: Float): Boolean
}

class RectangleShape(var left: Float, var top: Float, var right: Float, var bottom: Float) : Shape() {
    override fun draw(canvas: Canvas, paint: Paint) {
        canvas.drawRect(left, top, right, bottom, paint)
    }

    override fun contains(x: Float, y: Float): Boolean {
        return x in left..right && y in top..bottom
    }
}

class CircleShape(var cx: Float, var cy: Float, var radius: Float) : Shape() {
    override fun draw(canvas: Canvas, paint: Paint) {
        canvas.drawCircle(cx, cy, radius, paint)
    }

    override fun contains(x: Float, y: Float): Boolean {
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= radius * radius
    }
}
