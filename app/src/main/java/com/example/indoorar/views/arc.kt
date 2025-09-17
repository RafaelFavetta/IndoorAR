package com.example.indoorar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class ArcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = android.graphics.Color.parseColor("#32357A")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val path = Path()
        val width = width.toFloat()
        val height = height.toFloat()

        // Profundidade do arco: quase a altura total da view
        val arcHeight = height * 0.9f  // ajuste para deixar apenas uma "sobrinha" no final

        // Arco apontado para baixo, ocupando toda a largura
        path.moveTo(0f, 0f)                   // canto superior esquerdo
        path.lineTo(0f, arcHeight)            // lado esquerdo até a curva
        path.quadTo(width / 2, height, width, arcHeight)  // curva central
        path.lineTo(width, 0f)                // canto superior direito
        path.close()

        canvas.drawPath(path, paint)
    }
}
