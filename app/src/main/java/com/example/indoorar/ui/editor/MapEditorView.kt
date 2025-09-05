package com.example.indoorar.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.withTranslation
import com.example.indoorar.ui.Action
import com.example.indoorar.ui.Tool
import com.example.indoorar.ui.editor.BrushEditor
import com.example.indoorar.ui.editor.PoiEditor
import com.example.indoorar.ui.editor.ShapeEditor
import kotlin.math.max
import kotlin.math.min

class MapEditorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ======= VISUAL (pan/zoom) =======
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    // ======= TOOL =======
    var currentTool: Tool = Tool.CURSOR
        private set

    // ======= CAMADAS =======
    var showGrid = true
    var showBrush = true
    var showPois = true

    // ======= DADOS =======
    private val actions = mutableListOf<Action>()

    // ======= SELEÇÃO/DRAG =======
    private var draggingShape: Action.Shape? = null
    private var lastDragPoint: PointF? = null

    // ======= PAINTS (expostos aos editores) =======
    internal val gridDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(210, 210, 210)
        style = Paint.Style.FILL
    }
    internal val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(50, 100, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    internal val poiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f)
    }

    // ======= EDITORES =======
    private val brushEditor = BrushEditor(this)
    private val shapeEditor = ShapeEditor(this)
    private val poiEditor = PoiEditor(this)

    // ======= GESTOS =======
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prevScale = scale
                scale *= detector.scaleFactor
                scale = max(0.5f, min(scale, 3f))
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
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (currentTool == Tool.CURSOR && draggingShape == null) {
                    offsetX -= dx
                    offsetY -= dy
                    invalidate()
                    return true
                }
                return false
            }
        })

    // ======= API =======
    fun setTool(tool: Tool) {
        currentTool = tool
        // limpa estados temporários ao trocar
        brushEditor.cancel()
        shapeEditor.cancel()
        draggingShape = null
        lastDragPoint = null
        invalidate()
    }

    fun undo() {
        if (actions.isNotEmpty()) {
            actions.removeAt(actions.lastIndex)
            invalidate()
        }
    }

    fun toggleGrid() { showGrid = !showGrid; invalidate() }
    fun toggleBrushLayer() { showBrush = !showBrush; invalidate() }
    fun togglePoiLayer() { showPois = !showPois; invalidate() }

    // usado pelos editores
    internal fun addAction(action: Action) { actions.add(action) }

    // ======= TOUCH =======
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        val world = screenToWorld(event.x, event.y)

        if (currentTool == Tool.CURSOR) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // limpa seleção anterior
                    actions.forEach { if (it is Action.Shape) it.selected = false }
                    // tenta selecionar shape
                    val hit = hitTestShapes(world)
                    if (hit != null) {
                        hit.selected = true
                        draggingShape = hit
                        lastDragPoint = world
                        invalidate()
                    } else {
                        draggingShape = null
                        lastDragPoint = null
                        gestureDetector.onTouchEvent(event)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (draggingShape != null && lastDragPoint != null) {
                        val dx = world.x - lastDragPoint!!.x
                        val dy = world.y - lastDragPoint!!.y
                        draggingShape!!.start = PointF(draggingShape!!.start.x + dx, draggingShape!!.start.y + dy)
                        draggingShape!!.end   = PointF(draggingShape!!.end.x   + dx, draggingShape!!.end.y   + dy)
                        lastDragPoint = world
                        invalidate()
                    } else {
                        gestureDetector.onTouchEvent(event)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingShape = null
                    lastDragPoint = null
                    gestureDetector.onTouchEvent(event)
                }
            }
            return true
        }

        // delega para os editores
        return when (currentTool) {
            Tool.BRUSH  -> brushEditor.onTouch(event)
            Tool.FORMAS -> shapeEditor.onTouch(event)
            Tool.POI    -> poiEditor.onTouch(event)
            else        -> true
        }
    }

    // ======= DRAW =======
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.withTranslation(offsetX, offsetY) {
            scale(scale, scale)

            if (showGrid) drawGrid(this)
            drawActions(this)

            // previews temporários (dos editores)
            brushEditor.onDrawTemp(this)
            shapeEditor.onDrawTemp(this)
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
                is Action.Shape -> {
                    val rect = RectF(action.start.x, action.start.y, action.end.x, action.end.y)
                    canvas.drawRect(rect, brushPaint)
                    if (action.selected) {
                        canvas.drawRect(rect, selectionPaint)
                    }
                }
            }
        }
    }

    private fun hitTestShapes(p: PointF, padding: Float = dp(6f)): Action.Shape? {
        for (i in actions.size - 1 downTo 0) {
            val a = actions[i]
            if (a is Action.Shape) {
                val left = min(a.start.x, a.end.x) - padding
                val right = max(a.start.x, a.end.x) + padding
                val top = min(a.start.y, a.end.y) - padding
                val bottom = max(a.start.y, a.end.y) + padding
                if (p.x in left..right && p.y in top..bottom) return a
            }
        }
        return null
    }

    // ======= UTILS (visíveis aos editores) =======
    internal fun screenToWorld(x: Float, y: Float): PointF =
        PointF((x - offsetX) / scale, (y - offsetY) / scale)

    internal fun dp(v: Float): Float = v * resources.displayMetrics.density
}
