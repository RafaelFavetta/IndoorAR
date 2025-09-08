package com.example.indoorar.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get

class ColorPickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var listener: ((Int) -> Unit)? = null

    private lateinit var shader: LinearGradient

    fun setOnColorChangedListener(l: (Int) -> Unit) {
        listener = l
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            intArrayOf(
                Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN,
                Color.GREEN, Color.YELLOW, Color.RED
            ),
            null,
            Shader.TileMode.CLAMP
        )
        paint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val x = event.x.coerceIn(0f, width.toFloat() - 1)
            val y = event.y.coerceIn(0f, height.toFloat() - 1)
            val pixel = getColorAt(x, y)
            listener?.invoke(pixel)
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun getColorAt(x: Float, y: Float): Int {
        val bitmap = createBitmap(width, height)
        val c = Canvas(bitmap)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return bitmap[x.toInt(), y.toInt()]
    }
}