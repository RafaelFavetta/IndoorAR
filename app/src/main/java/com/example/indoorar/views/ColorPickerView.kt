package com.example.indoorar.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class ColorPickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val colors = listOf(
        Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW,
        Color.MAGENTA, Color.CYAN, Color.BLACK, Color.WHITE,
        Color.parseColor("#32357A"), // azul do app
        Color.parseColor("#FF6F00"), // laranja
        Color.parseColor("#00C853")  // verde
    )

    private val paint = Paint()
    private var cellSize = 0f
    private var listener: ((Int) -> Unit)? = null

    fun setOnColorChangedListener(l: (Int) -> Unit) {
        listener = l
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        cellSize = width.toFloat() / colors.size
        colors.forEachIndexed { index, color ->
            paint.color = color
            canvas.drawRect(
                index * cellSize, 0f,
                (index + 1) * cellSize, height.toFloat(), paint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val index = (event.x / cellSize).toInt().coerceIn(0, colors.size - 1)
            listener?.invoke(colors[index])
            return true
        }
        return super.onTouchEvent(event)
    }
}
