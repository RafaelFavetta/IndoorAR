package com.example.indoorar.ui.editor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import androidx.core.graphics.toColorInt
import com.example.indoorar.ui.Action
import com.example.indoorar.ui.Tool
import kotlin.math.*

data class ShapeProps(
    var x: Float,
    var y: Float,
    var width: Float,
    var height: Float,
    var rotation: Float = 0f,
    var fillColor: Int? = null
)

class MapEditorView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ===== VISUAL =====
    var scale = 1f
    var offsetX = 0f
    var offsetY = 0f
    private var draggingObject: Action? = null

    // ===== TOOL =====
    var currentTool: Tool = Tool.CURSOR
        private set

    // ===== LAYERS =====
    var showGrid = true
    var showBrush = true
    var showPois = true

    // ===== DATA =====
    val actions = mutableListOf<Action>()

    // ===== SELECTION/DRAG =====
    private var lastDragPoint: PointF? = null
    private var activeHandle: Handle? = null

    interface OnShapeSelectionListener {
        fun onShapeSelected(props: ShapeProps)
        fun onShapeDeselected()
    }
    var selectionListener: OnShapeSelectionListener? = null

    private enum class Handle { }

    // ===== PAINTS =====
    private val gridDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(210, 210, 210)
        style = Paint.Style.FILL
    }

    private val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapeSelectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Tornando o Paint temporário público, sem criar função duplicada
    val shapeTempPaint: Paint by lazy {
        Paint().apply {
            color = Color.RED
            strokeWidth = 4f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
    }

    init {
        brushPaint.color = Color.rgb(50, 100, 255)
        brushPaint.style = Paint.Style.STROKE
        brushPaint.strokeWidth = dp(2f)
        brushPaint.strokeCap = Paint.Cap.ROUND
        brushPaint.strokeJoin = Paint.Join.ROUND

        shapeSelectionPaint.color = "#0D99FF".toColorInt()
        shapeSelectionPaint.style = Paint.Style.STROKE
        shapeSelectionPaint.strokeWidth = dp(2f)
    }

    // ===== EDITORS =====
    private val brushEditor = BrushEditor(this)
    private val shapeEditor = ShapeEditor(this)
    var onPoiClickListener: ((x: Float, y: Float) -> Unit)? = null

    // ===== GESTURES =====
    private val scaleDetector = ScaleGestureDetector(context,
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

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (currentTool == Tool.CURSOR && draggingObject == null) {
                    offsetX -= dx
                    offsetY -= dy
                    invalidate()
                    return true
                }
                return false
            }
        })

    // ===== API =====
    fun setTool(tool: Tool) {
        currentTool = tool
        brushEditor.cancel()
        shapeEditor.cancel()
        draggingObject = null
        lastDragPoint = null
        activeHandle = null
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

    fun getBrushPaint() = brushPaint

    fun addAction(action: Action) {
        if (action is Action.Poi) action.loadBitmap(resources)
        actions.add(action)
        invalidate()
    }

    fun addPoi(x: Float, y: Float, iconRes: Int) {
        val poi = Action.Poi(x = x, y = y, iconRes = iconRes)
        poi.loadBitmap(resources)
        actions.add(poi)
        invalidate()
    }

    // ===== TOUCH =====
    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val world = screenToWorld(event.x, event.y)

        if (currentTool != Tool.CURSOR) {
            return when (currentTool) {
                Tool.BRUSH -> brushEditor.onTouch(event)
                Tool.FORMAS -> shapeEditor.onTouch(event)
                Tool.POI -> false
                else -> true
            }
        }

        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastDragPoint = world
                val hit = hitTestObjects(world)
                draggingObject = hit

                actions.forEach { action ->
                    when(action) {
                        is Action.Shape -> action.selected = (action == hit)
                        is Action.Poi -> action.selected = (action == hit)
                        is Action.BrushStroke -> {}
                    }
                }

                activeHandle = hit?.let { hitTestHandles(it, world) }

                hit?.let {
                    selectionListener?.onShapeSelected(
                        if (it is Action.Shape) shapeToProperties(it) else poiToProperties(it as Action.Poi)
                    )
                } ?: selectionListener?.onShapeDeselected()

                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                draggingObject?.let {
                    val dx = world.x - (lastDragPoint?.x ?: world.x)
                    val dy = world.y - (lastDragPoint?.y ?: world.y)
                    when(it) {
                        is Action.Shape -> {
                            it.start.x += dx; it.start.y += dy
                            it.end.x += dx; it.end.y += dy
                        }
                        is Action.Poi -> {
                            it.x += dx; it.y += dy
                        }
                        is Action.BrushStroke -> {}
                    }
                    lastDragPoint = world
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingObject = null
                activeHandle = null
                lastDragPoint = null
                invalidate()
            }
        }

        return true
    }

    // ===== DRAW =====
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.withTranslation(offsetX, offsetY) {
            scale(scale, scale)
            if (showGrid) drawGrid(this)
            drawActions(this)
            brushEditor.onDrawTemp(this)
            shapeEditor.onDrawTemp(this)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val spacing = 40f
        val radius = 2f
        val cols = (width / spacing / scale).toInt() + 4
        val rows = (height / spacing / scale).toInt() + 4
        for (i in -cols..cols) for (j in -rows..rows) {
            val x = i * spacing
            val y = j * spacing
            canvas.drawCircle(x, y, radius, gridDotPaint)
        }
    }

    private fun drawActions(canvas: Canvas) {
        actions.forEach { action ->
            when (action) {
                is Action.BrushStroke -> {
                    val path = Path()
                    action.points.firstOrNull()?.let { first ->
                        path.moveTo(first.x, first.y)
                        for (i in 1 until action.points.size) path.lineTo(action.points[i].x, action.points[i].y)
                        canvas.drawPath(path, brushPaint)
                    }
                }
                is Action.Poi -> {
                    val bmp = action.bitmap ?: return@forEach
                    val left = action.x - bmp.width / 2f
                    val top = action.y - bmp.height / 2f
                    canvas.drawBitmap(bmp, left, top, null)
                    if (action.selected) drawSelection(canvas, action)
                }
                is Action.Shape -> {
                    val rect = RectF(action.start.x, action.start.y, action.end.x, action.end.y)
                    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = action.fillColor ?: Color.TRANSPARENT
                        style = Paint.Style.FILL
                    }
                    canvas.withSave {
                        rotate(action.rotation, rect.centerX(), rect.centerY())
                        drawRect(rect, fill)
                    }
                    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        style = Paint.Style.STROKE
                        strokeWidth = 2f
                    }
                    canvas.drawRect(rect, stroke)
                    if (action.selected) drawSelection(canvas, action)
                }
            }
        }
    }

    private fun drawSelection(canvas: Canvas, action: Action) {
        val rect = when(action) {
            is Action.Shape -> RectF(action.start.x, action.start.y, action.end.x, action.end.y)
            is Action.Poi -> {
                val bmp = action.bitmap ?: return
                RectF(action.x - bmp.width / 2f, action.y - bmp.height / 2f,
                    action.x + bmp.width / 2f, action.y + bmp.height / 2f)
            }
            else -> return
        }
        canvas.drawRect(rect, shapeSelectionPaint)
    }

    private fun poiToProperties(poi: Action.Poi) =
        ShapeProps(poi.x, poi.y, poi.width, poi.height, 0f)

    private fun shapeToProperties(shape: Action.Shape) =
        ShapeProps(min(shape.start.x, shape.end.x), min(shape.start.y, shape.end.y),
            abs(shape.end.x - shape.start.x), abs(shape.end.y - shape.start.y), shape.rotation)

    internal fun screenToWorld(x: Float, y: Float) = PointF((x - offsetX)/scale, (y - offsetY)/scale)
    internal fun dp(v: Float) = v * resources.displayMetrics.density

    private fun hitTestObjects(point: PointF): Action? {
        return actions.reversed().firstOrNull { action ->
            when(action) {
                is Action.Shape -> {
                    val left = min(action.start.x, action.end.x)
                    val top = min(action.start.y, action.end.y)
                    val right = max(action.start.x, action.end.x)
                    val bottom = max(action.start.y, action.end.y)
                    point.x in left..right && point.y in top..bottom
                }
                is Action.Poi -> {
                    val bmp = action.bitmap ?: return@firstOrNull false
                    point.x in (action.x - bmp.width / 2f)..(action.x + bmp.width / 2f) &&
                            point.y in (action.y - bmp.height / 2f)..(action.y + bmp.height / 2f)
                }
                else -> false
            }
        }
    }

    private fun hitTestHandles(hit: Action, point: PointF): Handle? = null
}
