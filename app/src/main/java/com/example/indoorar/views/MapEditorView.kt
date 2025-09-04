package com.example.indoorar.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.example.indoorar.ui.Action
import com.example.indoorar.ui.Tool
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.withTranslation

class MapEditorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ======= ESTADO DE VISUALIZAÇÃO (pan/zoom) =======
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    // ======= FERRAMENTA ATUAL =======
    var currentTool: Tool = Tool.CURSOR

    // ======= CAMADAS (pode desligar/ligar depois) =======
    var showGrid = true
    var showBrush = true
    var showPois = true

    // ======= AÇÕES (para desenhar e desfazer) =======
    private val actions = mutableListOf<Action>()
    private var tempStroke: MutableList<PointF>? = null

    // ======= PAINTS =======
    private val gridDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(210, 210, 210)
        style = Paint.Style.FILL
    }
    private val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(50, 100, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val poiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    // ======= GESTOS =======
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prevScale = scale
                scale *= detector.scaleFactor
                scale = max(0.5f, min(scale, 3f))
                // manter foco
                val fx = detector.focusX
                val fy = detector.focusY
                offsetX = (offsetX - fx) * (scale / prevScale) + fx
                offsetY = (offsetY - fy) * (scale / prevScale) + fy
                invalidate()
                return true
            }
        })

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                dx: Float,
                dy: Float
            ): Boolean {
                // Só faz pan com um dedo quando a ferramenta é CURSOR (senão conflita com desenho)
                if (currentTool == Tool.CURSOR) {
                    offsetX -= dx
                    offsetY -= dy
                    invalidate()
                    return true
                }
                return false
            }
        })

    // ======= API pública =======
    fun setTool(tool: Tool) {
        currentTool = tool
        // cancela traço temporário se trocar de ferramenta no meio
        if (tool != Tool.BRUSH) tempStroke = null
        invalidate()
    }

    fun undo() {
        if (actions.isNotEmpty()) {
            actions.removeAt(actions.lastIndex) // mais seguro
            invalidate()
        }
    }


    fun toggleGrid() { showGrid = !showGrid; invalidate() }
    fun toggleBrushLayer() { showBrush = !showBrush; invalidate() }
    fun togglePoiLayer() { showPois = !showPois; invalidate() }

    // ======= TOUCH =======
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Pinça sempre funciona
        scaleDetector.onTouchEvent(event)

        // Pan com 1 dedo só funciona no modo CURSOR
        if (currentTool == Tool.CURSOR) {
            gestureDetector.onTouchEvent(event)
        }

        when (currentTool) {
            Tool.BRUSH -> handleBrushTouch(event)
            Tool.POI -> handlePoiTouch(event)
            else -> { /* outras ferramentas/seleção virão depois */ }
        }
        return true
    }

    private fun handleBrushTouch(event: MotionEvent) {
        val p = screenToWorld(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tempStroke = mutableListOf(p)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                tempStroke?.add(p)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tempStroke?.let { points ->
                    if (points.size > 1) {
                        actions.add(Action.BrushStroke(points.toList()))
                    }
                }
                tempStroke = null
                invalidate()
            }
        }
    }

    private fun handlePoiTouch(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val p = screenToWorld(event.x, event.y)
            actions.add(Action.Poi(p))
            invalidate()
        }
    }

    // ======= DRAW =======
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.withTranslation(offsetX, offsetY) {
            scale(scale, scale)

            if (showGrid) drawGrid(this)
            drawActions(this)
            drawTemp(this)

        }
    }

    private fun drawGrid(canvas: Canvas) {
        val spacing = 40f
        val radius = 2f
        val cols = (width / spacing / scale).toInt() + 4
        val rows = (height / spacing / scale).toInt() + 4
        for (i in -cols..cols) {
            for (j in -rows..rows) {
                val x = i * spacing
                val y = j * spacing
                canvas.drawCircle(x, y, radius, gridDotPaint)
            }
        }
    }

    private fun drawActions(canvas: Canvas) {
        actions.forEach { action ->
            when (action) {
                is Action.BrushStroke -> if (showBrush) {
                    val path = Path()
                    action.points.firstOrNull()?.let { first ->
                        path.moveTo(first.x, first.y)
                        for (k in 1 until action.points.size) {
                            val pt = action.points[k]
                            path.lineTo(pt.x, pt.y)
                        }
                        canvas.drawPath(path, brushPaint)
                    }
                }
                is Action.Poi -> if (showPois) {
                    canvas.drawCircle(action.position.x, action.position.y, dp(5f), poiPaint)
                }
            }
        }
    }

    private fun drawTemp(canvas: Canvas) {
        tempStroke?.let { points ->
            if (points.size > 1) {
                val path = Path()
                path.moveTo(points[0].x, points[0].y)
                for (k in 1 until points.size) {
                    path.lineTo(points[k].x, points[k].y)
                }
                canvas.drawPath(path, brushPaint)
            }
        }
    }

    // ======= UTILS =======
    private fun screenToWorld(x: Float, y: Float): PointF {
        return PointF((x - offsetX) / scale, (y - offsetY) / scale)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}