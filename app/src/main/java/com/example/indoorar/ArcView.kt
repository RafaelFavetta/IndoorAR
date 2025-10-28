package com.example.indoorar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * Simples view decorativa que desenha um arco no topo da tela.
 * Usada por main_activity.xml como header curvo.
 */
class ArcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // Cor padrão combinando com header "#32357A"; pode ser alterada via background na View
        color = 0xFF32357A.toInt()
    }
    private val path = Path()

    // Altura padrão quando wrap_content
    private val defaultHeightPx = dp(220f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        val desiredH = defaultHeightPx
        val h = when (hMode) {
            MeasureSpec.EXACTLY -> hSize
            MeasureSpec.AT_MOST -> desiredH.coerceAtMost(hSize)
            else -> desiredH
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Se a view tiver background definido no XML, use-o como cor de preenchimento
        background?.let {
            it.setBounds(0, 0, width, height)
            it.draw(canvas)
            return
        }

        path.reset()
        // Desenha um arco suave que ocupa o topo; curva bézier para um visual sutil
        path.moveTo(0f, h * 0.65f)
        path.cubicTo(
            w * 0.33f, h * 0.35f,
            w * 0.66f, h * 0.95f,
            w, h * 0.55f
        )
        path.lineTo(w, 0f)
        path.lineTo(0f, 0f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()
}

