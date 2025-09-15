package com.example.indoorar.ui.editor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import com.example.indoorar.ui.Action
import com.example.indoorar.ui.Tool
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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

    // ===== ESTADO E CONTROLE =====
    var currentTool: Tool = Tool.CURSOR
        private set
    private var draggingObject: Action? = null
    val actions = mutableListOf<Action>()
    var onToolChangedListener: ((Tool) -> Unit)? = null
    private var pendingPoiResId: Int? = null

    // ===== CONTROLE DE CÂMERA E TOQUE =====
    var scale = 1f
    var offsetX = 0f
    var offsetY = 0f
    private var touchOffsetX = 0f
    private var touchOffsetY = 0f

    // ===== CACHE DE IMAGENS =====
    private val bitmapCache = mutableMapOf<Int, Bitmap>()

    // ===== CONFIGURAÇÕES VISUAIS =====
    var showGrid = true
    var showBrush = true // Mantido do seu código original
    var showPois = true  // Mantido do seu código original

    // ===== SELECTION/DRAG (Mantido do seu código original) =====
    private var lastDragPoint: PointF? = null
    private var activeHandle: Handle? = null
    interface OnShapeSelectionListener {
        fun onShapeSelected(props: ShapeProps)
        fun onShapeDeselected()
    }
    var selectionListener: OnShapeSelectionListener? = null
    private enum class Handle { }

    // ===== PAINTS (Mantido do seu código original) =====
    private val gridDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(210, 210, 210); style = Paint.Style.FILL }
    private val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapeSelectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val shapeTempPaint: Paint by lazy { Paint().apply { color = Color.RED; strokeWidth = 4f; style = Paint.Style.STROKE; isAntiAlias = true } }

    // ===== EDITORES E GESTOS =====
    private val brushEditor = BrushEditor(this)
    private val shapeEditor = ShapeEditor(this)
    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        brushPaint.apply {
            color = Color.rgb(50, 100, 255)
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        shapeSelectionPaint.apply {
            color = "#0D99FF".toColorInt()
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
        }

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
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

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                offsetX -= dx
                offsetY -= dy
                invalidate()
                return true
            }
        })
    }

    // ===== MÉTODOS PÚBLICOS (API DA VIEW) =====

    /** Prepara a View para criar um POI no próximo toque. Chamado pela Activity. */
    fun primeForPoiCreation(iconRes: Int) {
        pendingPoiResId = iconRes
        setTool(Tool.POI)
    }

    /** Define a ferramenta ativa e notifica a Activity. */
    fun setTool(tool: Tool) {
        if (currentTool == tool && tool != Tool.POI) return // Permite re-clicar em POI

        currentTool = tool
        onToolChangedListener?.invoke(tool)

        // Limpa estados para evitar bugs entre ferramentas
        draggingObject = null
        if (tool != Tool.POI) {
            pendingPoiResId = null
        }
        invalidate()
    }

    fun undo() {
        if (actions.isNotEmpty()) {
            actions.removeAt(actions.lastIndex)
            invalidate()
        }
    }

    fun addPoi(x: Float, y: Float, iconRes: Int) {
        val poi = Action.Poi(x = x, y = y, iconRes = iconRes)
        actions.add(poi)
        invalidate()
    }

    fun addAction(action: Action) {
        actions.add(action)
        invalidate()
    }

    fun toggleGrid() { showGrid = !showGrid; invalidate() }
    fun toggleBrushLayer() { showBrush = !showBrush; invalidate() }
    fun togglePoiLayer() { showPois = !showPois; invalidate() }
    fun getBrushPaint() = brushPaint


    // ===== TOUCH =====

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) return true

        val world = screenToWorld(event.x, event.y)

        // MODO DE CRIAÇÃO DE POI (tem prioridade)
        if (currentTool == Tool.POI) {
            if (event.action == MotionEvent.ACTION_UP) {
                pendingPoiResId?.let { resId -> addPoi(world.x, world.y, resId) }
                setTool(Tool.CURSOR)
            }
            return true
        }

        // Outras ferramentas que não sejam o cursor
        if (currentTool != Tool.CURSOR) {
            return when (currentTool) {
                Tool.BRUSH -> brushEditor.onTouch(event)
                Tool.FORMAS -> shapeEditor.onTouch(event)
                else -> super.onTouchEvent(event)
            }
        }

        // MODO DE INTERAÇÃO (CURSOR)
        if (draggingObject == null) {
            gestureDetector.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingObject = hitTestObjects(world)
                actions.forEach { action ->
                    val isSelected = (action == draggingObject)
                    when (action) {
                        is Action.Shape -> action.selected = isSelected
                        is Action.Poi -> action.selected = isSelected
                        else -> {}
                    }
                }

                draggingObject?.let { obj ->
                    when (obj) {
                        is Action.Poi -> { touchOffsetX = world.x - obj.x; touchOffsetY = world.y - obj.y }
                        is Action.Shape -> { touchOffsetX = world.x - obj.start.x; touchOffsetY = world.y - obj.start.y }
                        is Action.BrushStroke -> {}
                    }
                }

                // Dispara o listener de seleção (mantido do seu código)
                draggingObject?.let {
                    selectionListener?.onShapeSelected(
                        if (it is Action.Shape) shapeToProperties(it) else poiToProperties(it as Action.Poi)
                    )
                } ?: selectionListener?.onShapeDeselected()

                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                draggingObject?.let { obj ->
                    when (obj) {
                        is Action.Poi -> { obj.x = world.x - touchOffsetX; obj.y = world.y - touchOffsetY }
                        is Action.Shape -> {
                            val width = obj.end.x - obj.start.x
                            val height = obj.end.y - obj.start.y
                            obj.start.x = world.x - touchOffsetX
                            obj.start.y = world.y - touchOffsetY
                            obj.end.x = obj.start.x + width
                            obj.end.y = obj.start.y + height
                        }

                        is Action.BrushStroke -> {}
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingObject = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ===== LÓGICA DE DESENHO (Mantido do seu código, com ajustes para o cache) =====

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.withTranslation(offsetX, offsetY) {
            scale(scale, scale)
            if (showGrid) drawGrid(this)
            drawActions(this)
            if (currentTool == Tool.BRUSH) brushEditor.onDrawTemp(this)
            if (currentTool == Tool.FORMAS) shapeEditor.onDrawTemp(this)
        }
    }

    private fun drawActions(canvas: Canvas) {
        actions.forEach { action ->
            when (action) {
                is Action.BrushStroke -> {
                    if (!showBrush) return@forEach
                    val path = Path()
                    action.points.firstOrNull()?.let { first ->
                        path.moveTo(first.x, first.y)
                        for (i in 1 until action.points.size) path.lineTo(action.points[i].x, action.points[i].y)
                        canvas.drawPath(path, brushPaint)
                    }
                }
                is Action.Poi -> {
                    if (!showPois) return@forEach
                    getBitmapForPoi(action)?.let { bmp ->
                        canvas.drawBitmap(bmp, action.x - bmp.width / 2f, action.y - bmp.height / 2f, null)
                        if (action.selected) drawSelection(canvas, action)
                    }
                }
                is Action.Shape -> {
                    val rect = RectF(action.start.x, action.start.y, action.end.x, action.end.y)
                    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = action.fillColor }
                    canvas.withSave {
                        rotate(action.rotation, rect.centerX(), rect.centerY())
                        drawRect(rect, fill)
                    }
                    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f }
                    canvas.drawRect(rect, stroke) // Mantido do seu código original
                    if (action.selected) drawSelection(canvas, action)
                }
            }
        }
    }

    private fun drawSelection(canvas: Canvas, action: Action) {
        val rect = when (action) {
            is Action.Shape -> RectF(action.start.x, action.start.y, action.end.x, action.end.y)
            is Action.Poi -> {
                val bmp = getBitmapForPoi(action) ?: return
                RectF(action.x - bmp.width / 2f, action.y - bmp.height / 2f, action.x + bmp.width / 2f, action.y + bmp.height / 2f)
            }
            else -> return
        }
        canvas.drawRect(rect, shapeSelectionPaint)
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

    // ===== MÉTODOS AUXILIARES (Mantidos do seu código, com ajustes para o cache) =====

    private fun getBitmapForPoi(poi: Action.Poi): Bitmap? {
        return bitmapCache[poi.iconRes] ?: try {
            val original = BitmapFactory.decodeResource(resources, poi.iconRes)
            val scaled = Bitmap.createScaledBitmap(original, poi.width.toInt(), poi.height.toInt(), true)
            bitmapCache[poi.iconRes] = scaled
            scaled
        } catch (e: Exception) {
            null
        }
    }

    private fun hitTestObjects(point: PointF): Action? {
        return actions.asReversed().find { action ->
            when (action) {
                is Action.Poi -> {
                    val bmp = getBitmapForPoi(action) ?: return@find false
                    val left = action.x - bmp.width / 2f
                    val top = action.y - bmp.height / 2f
                    val right = left + bmp.width
                    val bottom = top + bmp.height
                    point.x in left..right && point.y in top..bottom
                }
                is Action.Shape -> {
                    val left = min(action.start.x, action.end.x)
                    val top = min(action.start.y, action.end.y)
                    val right = max(action.start.x, action.end.x)
                    val bottom = max(action.start.y, action.end.y)
                    point.x in left..right && point.y in top..bottom
                }
                else -> false
            }
        }
    }

    private fun poiToProperties(poi: Action.Poi) =
        ShapeProps(poi.x, poi.y, poi.width, poi.height, 0f, null)

    private fun shapeToProperties(shape: Action.Shape) =
        ShapeProps(min(shape.start.x, shape.end.x), min(shape.start.y, shape.end.y),
            abs(shape.end.x - shape.start.x), abs(shape.end.y - shape.start.y), shape.rotation, shape.fillColor)

    private fun hitTestHandles(hit: Action, point: PointF): Handle? = null // Mantido do seu código original

    internal fun screenToWorld(x: Float, y: Float) = PointF((x - offsetX) / scale, (y - offsetY) / scale)
    internal fun dp(v: Float) = v * resources.displayMetrics.density
}