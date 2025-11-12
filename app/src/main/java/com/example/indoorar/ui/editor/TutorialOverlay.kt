package com.example.indoorar.ui.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import com.example.indoorar.R
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.graphics.Color

/**
 * Fullscreen overlay that dims the background and highlights (spotlight) one target view.
 * It punches a transparent hole over the target and shows a tooltip card with descriptive text.
 */
class TutorialOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var targetView: View? = null
    private var message: String = ""
    private val dimPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0xBB000000.toInt() // semi-transparent black
    }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val holePath = Path()
    private var holeRect = RectF()
    private var radius = 0f
    private var tooltipCard: CardView? = null
    private var tooltipText: TextView? = null

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
    }

    fun spotlight(target: View, text: String, padding: Float = 24f, cornerRadius: Float = 32f) {
        targetView = target
        message = text
        radius = cornerRadius
        // Ensure layout pass then invalidate for drawing
        post {
            buildHole(target, padding)
            ensureTooltip()
            placeTooltip()
            invalidate()
        }
    }

    private fun buildHole(target: View, padding: Float) {
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val parentLoc = IntArray(2)
        getLocationOnScreen(parentLoc)
        val left = (loc[0] - parentLoc[0] - padding)
        val top = (loc[1] - parentLoc[1] - padding)
        val right = (loc[0] - parentLoc[0] + target.width + padding)
        val bottom = (loc[1] - parentLoc[1] + target.height + padding)
        holeRect.set(left, top, right, bottom)
        holePath.reset()
        holePath.addRoundRect(holeRect, radius, radius, Path.Direction.CW)
    }

    private fun ensureTooltip() {
        if (tooltipCard != null) return
        tooltipCard = CardView(context).apply {
            radius = 16f
            cardElevation = 8f
            setCardBackgroundColor(0xFFFFFFFF.toInt())
        }
        tooltipText = TextView(context).apply {
            setTextColor(0xFF222222.toInt())
            textSize = 14f
            setPadding(24, 24, 24, 24)
            text = styledMessage(message)
        }
        tooltipCard!!.addView(tooltipText)
        addView(tooltipCard, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun placeTooltip() {
        tooltipText?.text = styledMessage(message)
        val card = tooltipCard ?: return
        val margin = 16
        val desiredYAbove = (holeRect.top - card.measuredHeight - margin)
        val placeAbove = desiredYAbove > 0
        val xCenter = (holeRect.centerX() - card.measuredWidth / 2f).coerceIn(margin.toFloat(), (width - card.measuredWidth - margin).toFloat())
        val y = if (placeAbove) desiredYAbove else (holeRect.bottom + margin)
        card.x = xCenter
        card.y = y
    }

    private fun styledMessage(text: String): CharSequence {
        val idx = text.indexOf(':')
        if (idx < 0) return text
        val ss = SpannableString(text)
        try {
            val blue = Color.parseColor("#32357A")
            ss.setSpan(ForegroundColorSpan(blue), 0, idx + 1, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            ss.setSpan(ForegroundColorSpan(Color.BLACK), idx + 1, text.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        } catch (_: Exception) {
            return text
        }
        return ss
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        post { placeTooltip() }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.saveLayer(null, null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawPath(holePath, clearPaint)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    fun nextStep(target: View, text: String) {
        spotlight(target, text)
    }

    fun cleanup(onFinished: () -> Unit) {
        (parent as? ViewGroup)?.removeView(this)
        onFinished()
    }

    companion object {
        fun startSequence(
            root: ViewGroup,
            steps: List<Pair<View, String>>,
            onFinish: () -> Unit
        ) {
            if (steps.isEmpty()) {
                onFinish(); return
            }
            val overlay = TutorialOverlay(root.context)
            root.addView(
                overlay,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            var index = 0

            fun advance() {
                if (index >= steps.size) {
                    overlay.cleanup(onFinish)
                    return
                }
                val (target, msg) = steps[index]
                overlay.nextStep(target, msg)
            }

            overlay.setOnClickListener {
                index++
                advance()
            }
            advance()
        }
    }
}
