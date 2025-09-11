package com.example.indoorar.ui.editor

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.withTranslation
import com.example.indoorar.ui.Action
import com.example.indoorar.ui.Tool
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withSave
import com.example.indoorar.R
import androidx.core.graphics.createBitmap
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.UUID


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

    // ======= VISUAL (pan/zoom) =======
    var scale = 1f
    var offsetX = 0f
    var offsetY = 0f
    private var draggingObject: Action? = null
    private val poiBitmapCache = mutableMapOf<Int, Bitmap>()
    private val objects = mutableListOf<Action>()

    // ======= TOOL =======
    var currentTool: Tool = Tool.CURSOR
        private set

    // ======= CAMADAS =======
    var showGrid = true
    var showBrush = true
    var showPois = true

    // ======= SNAP GUIDES =====
    private val snapGuides = mutableListOf<Pair<PointF, PointF>>()

    private data class AxisSnap(
        var active: Float? = null,
        var candidate: Float? = null,
        var seenAt: Long = 0L
    )

    private val snapX = AxisSnap()
    private val snapY = AxisSnap()

    private val gridSpacing = 40f
    private val snapAccept = dp(6f)
    private val snapRelease = dp(14f)
    private val snapDwellMs = 60L

    // ======= DADOS =======
    val actions = mutableListOf<Action>()

    // ======= SELEÇÃO/DRAG =======
    private var draggingShape: Action? = null
    private var lastDragPoint: PointF? = null
    private var activeHandle: Handle? = null

    interface OnShapeSelectionListener {
        fun onShapeSelected(props: ShapeProperties)
        fun onShapeDeselected()
    }

    var selectionListener: OnShapeSelectionListener? = null


    private enum class Handle {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
        TOP_CENTER, BOTTOM_CENTER, LEFT_CENTER, RIGHT_CENTER
    }

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

    fun getBrushPaint(): Paint = brushPaint
    fun getShapeTempPaint(): Paint = shapeSelectionPaint


    private val poiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val shapeSelectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#0D99FF".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val snapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = "#FF6A00".toColorInt()
        strokeWidth = dp(1.5f)
    }

    // ======= EDITORES =======
    private val brushEditor = BrushEditor(this)
    private val shapeEditor = ShapeEditor(this)
    var onPoiClickListener: ((x: Float, y: Float) -> Unit)? = null


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

    private val poiIcons = mutableMapOf<String, Bitmap>()

    private fun getPoiIcon(name: String): Bitmap {
        return poiIcons.getOrPut(name) {
            try {
                val resId = when (name) {
                    "porta" -> R.drawable.ic_door_azul
                    "escada" -> R.drawable.ic_stairs_azul
                    "elevador" -> R.drawable.ic_elevator_azul
                    "banheiro" -> R.drawable.ic_banheiro_azul
                    "extintor" -> R.drawable.ic_extintor_azul
                    else -> R.drawable.ic_poi_default
                }
                BitmapFactory.decodeResource(resources, resId)
                    ?: createBitmap(1, 1) // fallback
            } catch (_: Exception) {
                createBitmap(1, 1) // fallback se crashar
            }
        }
    }

    private fun getPoiBitmap(resId: Int): Bitmap {
        return poiBitmapCache.getOrPut(resId) {
            BitmapFactory.decodeResource(resources, resId)
                ?: createBitmap(32, 32)
        }
    }



    // ======= API =======
    fun setTool(tool: Tool) {
        currentTool = tool
        brushEditor.cancel()
        shapeEditor.cancel()
        draggingShape = null
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

    internal fun addAction(action: Action) { actions.add(action) }

    fun addPoi(x: Float, y: Float, iconRes: Int) {
        val poi = Action.Poi(
            x = x,
            y = y,
            iconRes = iconRes
        )
        objects.add(poi)
        invalidate()
    }



    // ======= TOUCH =======

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val world = screenToWorld(event.x, event.y)

        if (currentTool == Tool.POI && event.action == MotionEvent.ACTION_DOWN) {
            onPoiClickListener?.invoke(world.x, world.y)
            return true
        }

        if (event.action == MotionEvent.ACTION_UP) {
            performClick()
        }

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
                // limpa seleção
                actions.forEach {
                    when (it) {
                        is Action.Shape -> it.selected = false
                        is Action.Poi -> it.selected = false
                        else -> {}
                    }
                }

                val hit = hitTestObjects(world)
                if (hit != null) {
                    when (hit) {
                        is Action.Shape -> {
                            hit.selected = true
                            selectionListener?.onShapeSelected(shapeToProperties(hit))
                        }
                        is Action.Poi -> {
                            hit.selected = true
                            selectionListener?.onShapeSelected(poiToProperties(hit))
                        }
                        else -> {}
                    }
                    draggingObject = hit
                    activeHandle = hitTestHandles(hit, world)
                } else {
                    draggingObject = null
                    activeHandle = null
                    selectionListener?.onShapeDeselected()
                }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val prev = lastDragPoint ?: world
                val dx = world.x - prev.x
                val dy = world.y - prev.y

                draggingObject?.let { action ->
                    when (action) {
                        is Action.Shape -> {
                            selectionListener?.onShapeSelected(shapeToProperties(action))
                            val minSize = dp(30f)
                            val rect = RectF(action.start.x, action.start.y, action.end.x, action.end.y)
                            if (activeHandle != null) {
                                when (activeHandle!!) {
                                    Handle.TOP_LEFT -> {
                                        val newLeft = action.start.x + dx
                                        val newTop = action.start.y + dy
                                        if ((rect.right - newLeft) > minSize) action.start.x = newLeft
                                        if ((rect.bottom - newTop) > minSize) action.start.y = newTop
                                    }
                                    Handle.TOP_RIGHT -> {
                                        val newRight = action.end.x + dx
                                        val newTop = action.start.y + dy
                                        if ((newRight - rect.left) > minSize) action.end.x = newRight
                                        if ((rect.bottom - newTop) > minSize) action.start.y = newTop
                                    }
                                    Handle.BOTTOM_LEFT -> {
                                        val newLeft = action.start.x + dx
                                        val newBottom = action.end.y + dy
                                        if ((rect.right - newLeft) > minSize) action.start.x = newLeft
                                        if ((newBottom - rect.top) > minSize) action.end.y = newBottom
                                    }
                                    Handle.BOTTOM_RIGHT -> {
                                        val newRight = action.end.x + dx
                                        val newBottom = action.end.y + dy
                                        if ((newRight - rect.left) > minSize) action.end.x = newRight
                                        if ((newBottom - rect.top) > minSize) action.end.y = newBottom
                                    }
                                    Handle.TOP_CENTER -> {
                                        val newTop = action.start.y + dy
                                        if ((rect.bottom - newTop) > minSize) action.start.y = newTop
                                    }
                                    Handle.BOTTOM_CENTER -> {
                                        val newBottom = action.end.y + dy
                                        if ((newBottom - rect.top) > minSize) action.end.y = newBottom
                                    }
                                    Handle.LEFT_CENTER -> {
                                        val newLeft = action.start.x + dx
                                        if ((rect.right - newLeft) > minSize) action.start.x = newLeft
                                    }
                                    Handle.RIGHT_CENTER -> {
                                        val newRight = action.end.x + dx
                                        if ((newRight - rect.left) > minSize) action.end.x = newRight
                                    }
                                }
                                snapToOtherShapes(action)
                            } else {
                                action.start.x += dx
                                action.start.y += dy
                                action.end.x += dx
                                action.end.y += dy
                                snapToOtherShapes(action)
                            }
                        }

                        is Action.Poi -> {
                            if (activeHandle != null) {
                                val minSize = dp(20f)
                                val rect = RectF(action.x, action.y, action.x + action.width, action.y + action.height)
                                when (activeHandle!!) {
                                    Handle.TOP_LEFT -> {
                                        val newLeft = action.x + dx
                                        val newTop = action.y + dy
                                        val newRight = rect.right
                                        val newBottom = rect.bottom
                                        val newWidth = newRight - newLeft
                                        val newHeight = newBottom - newTop
                                        if (newWidth > minSize) {
                                            action.x = newLeft
                                            action.width = newWidth
                                        }
                                        if (newHeight > minSize) {
                                            action.y = newTop
                                            action.height = newHeight
                                        }
                                    }
                                    Handle.TOP_RIGHT -> {
                                        val newRight = action.x + action.width + dx
                                        val newTop = action.y + dy
                                        val newWidth = newRight - action.x
                                        val newHeight = (action.y + action.height) - newTop
                                        if (newWidth > minSize) action.width = newWidth
                                        if (newHeight > minSize) { action.y = newTop; action.height = (action.y + action.height) - newTop }
                                    }
                                    Handle.BOTTOM_LEFT -> {
                                        val newLeft = action.x + dx
                                        val newBottom = action.y + action.height + dy
                                        val newWidth = (action.x + action.width) - newLeft
                                        val newHeight = newBottom - action.y
                                        if (newWidth > minSize) { action.x = newLeft; action.width = (action.x + action.width) - newLeft }
                                        if (newHeight > minSize) action.height = newHeight
                                    }
                                    Handle.BOTTOM_RIGHT -> {
                                        val newRight = action.x + action.width + dx
                                        val newBottom = action.y + action.height + dy
                                        val newWidth = newRight - action.x
                                        val newHeight = newBottom - action.y
                                        if (newWidth > minSize) action.width = newWidth
                                        if (newHeight > minSize) action.height = newHeight
                                    }
                                    Handle.TOP_CENTER -> {
                                        val newTop = action.y + dy
                                        val newHeight = (action.y + action.height) - newTop
                                        if (newHeight > minSize) { action.y = newTop; action.height = newHeight }
                                    }
                                    Handle.BOTTOM_CENTER -> {
                                        val newBottom = action.y + action.height + dy
                                        val newHeight = newBottom - action.y
                                        if (newHeight > minSize) action.height = newHeight
                                    }
                                    Handle.LEFT_CENTER -> {
                                        val newLeft = action.x + dx
                                        val newWidth = (action.x + action.width) - newLeft
                                        if (newWidth > minSize) { action.x = newLeft; action.width = newWidth }
                                    }
                                    Handle.RIGHT_CENTER -> {
                                        val newRight = action.x + action.width + dx
                                        val newWidth = newRight - action.x
                                        if (newWidth > minSize) action.width = newWidth
                                    }
                                }
                            } else {
                                // drag normal POI
                                action.x += dx
                                action.y += dy
                            }
                        }
                        else -> {}
                    }
                }

                lastDragPoint = world
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingObject = null
                activeHandle = null
                lastDragPoint = null
                snapX.active = null; snapX.candidate = null
                snapY.active = null; snapY.candidate = null
                snapGuides.clear()
                invalidate()
            }
        }

        return true
    }

    // ======= DRAW =======
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.withTranslation(offsetX, offsetY) {
            scale(scale, scale)
            if (showGrid) drawGrid(this)
            drawActions(this)
            brushEditor.onDrawTemp(this)
            shapeEditor.onDrawTemp(this)
            drawSnapGuides(this)
        }
    }

    private fun drawSnapGuides(canvas: Canvas) {
        snapGuides.clear()
        snapX.active?.let { x -> snapGuides.add(PointF(x, 0f) to PointF(x, height.toFloat())) }
        snapY.active?.let { y -> snapGuides.add(PointF(0f, y) to PointF(width.toFloat(), y)) }
        snapGuides.forEach { (p1, p2) ->
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, snapPaint)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val spacing = gridSpacing
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
        val selectedObjects = mutableListOf<Action>()

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
                    val bitmap = BitmapFactory.decodeResource(resources, action.iconRes)
                    bitmap?.let {
                        val left = action.x - it.width / 2f
                        val top = action.y - it.height / 2f
                        canvas.drawBitmap(it, left, top, null)
                    }

                    if (action.selected) selectedObjects.add(action)
                }

                is Action.Shape -> {
                    val rect = RectF(action.start.x, action.start.y, action.end.x, action.end.y)

                    // Fill
                    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = action.fillColor
                        style = Paint.Style.FILL
                    }

                    canvas.withSave {
                        rotate(action.rotation, rect.centerX(), rect.centerY())
                        drawRect(rect, fillPaint)
                    }

                    // Stroke
                    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        style = Paint.Style.STROKE
                        strokeWidth = 2f
                    }
                    canvas.drawRect(rect, strokePaint)

                    if (action.selected) selectedObjects.add(action)
                }
            }
        }

        // Desenha seleção e handles
        selectedObjects.forEach { obj ->
            val rect = when (obj) {
                is Action.Shape -> RectF(obj.start.x, obj.start.y, obj.end.x, obj.end.y)
                is Action.Poi -> {
                    val bitmap = BitmapFactory.decodeResource(resources, obj.iconRes)
                    if (bitmap != null) {
                        RectF(
                            obj.x - bitmap.width / 2f,
                            obj.y - bitmap.height / 2f,
                            obj.x + bitmap.width / 2f,
                            obj.y + bitmap.height / 2f
                        )
                    } else null
                }
                else -> null
            }

            rect?.let {
                // Fundo (opcional)
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (obj is Action.Shape) obj.fillColor else Color.TRANSPARENT
                    style = Paint.Style.FILL
                }
                canvas.drawRect(it, fill)

                // Contorno
                canvas.drawRect(it, shapeSelectionPaint)
                drawHandles(canvas, it)
            }
        }
    }

    private fun drawHandles(canvas: Canvas, rect: RectF) {
        val handleSize = dp(8f)
        val half = handleSize / 2
        val points = mapOf(
            Handle.TOP_LEFT to PointF(rect.left, rect.top),
            Handle.TOP_RIGHT to PointF(rect.right, rect.top),
            Handle.BOTTOM_LEFT to PointF(rect.left, rect.bottom),
            Handle.BOTTOM_RIGHT to PointF(rect.right, rect.bottom),
            Handle.TOP_CENTER to PointF(rect.centerX(), rect.top),
            Handle.BOTTOM_CENTER to PointF(rect.centerX(), rect.bottom),
            Handle.LEFT_CENTER to PointF(rect.left, rect.centerY()),
            Handle.RIGHT_CENTER to PointF(rect.right, rect.centerY())
        )
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#0D99FF".toColorInt()
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
        }
        points.values.forEach { p ->
            val left = p.x - half
            val top = p.y - half
            val right = p.x + half
            val bottom = p.y + half
            canvas.drawRect(left, top, right, bottom, fill)
            canvas.drawRect(left, top, right, bottom, stroke)
        }
    }

    private fun hitTestShapes(p: PointF, padding: Float = dp(6f)): Action? {

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

    private fun hitTestObjects(p: PointF): Action? {
        for (i in actions.size - 1 downTo 0) {
            val a = actions[i]
            when (a) {
                is Action.Shape -> {
                    val left = minOf(a.start.x, a.end.x)
                    val right = maxOf(a.start.x, a.end.x)
                    val top = minOf(a.start.y, a.end.y)
                    val bottom = maxOf(a.start.y, a.end.y)
                    if (p.x in left..right && p.y in top..bottom) return a
                }
                is Action.Poi -> {
                    val bitmap = BitmapFactory.decodeResource(resources, a.iconRes)
                    val halfW = bitmap.width / 2f
                    val halfH = bitmap.height / 2f
                    val rectLeft = a.x - halfW
                    val rectTop = a.y - halfH
                    val rectRight = a.x + halfW
                    val rectBottom = a.y + halfH
                    if (p.x in rectLeft..rectRight && p.y in rectTop..rectBottom) return a
                }
                else -> { /* ignore others */ }
            }
        }
        return null
    }

    private fun hitTestHandles(action: Action, p: PointF, size: Float = dp(50f)): Handle? {
        val rect = when (action) {
            is Action.Shape -> RectF(action.start.x, action.start.y, action.end.x, action.end.y)
            is Action.Poi -> RectF(action.x, action.y, action.x + action.width, action.y + action.height)
            else -> return null
        }
        val half = size / 2
        val handles = mapOf(
            Handle.TOP_LEFT to PointF(rect.left, rect.top),
            Handle.TOP_RIGHT to PointF(rect.right, rect.top),
            Handle.BOTTOM_LEFT to PointF(rect.left, rect.bottom),
            Handle.BOTTOM_RIGHT to PointF(rect.right, rect.bottom),
            Handle.TOP_CENTER to PointF(rect.centerX(), rect.top),
            Handle.BOTTOM_CENTER to PointF(rect.centerX(), rect.bottom),
            Handle.LEFT_CENTER to PointF(rect.left, rect.centerY()),
            Handle.RIGHT_CENTER to PointF(rect.right, rect.centerY())
        )
        for ((handle, pos) in handles) {
            if (p.x in (pos.x - half)..(pos.x + half) && p.y in (pos.y - half)..(pos.y + half)) {
                return handle
            }
        }
        return null
    }

    private fun snapToOtherShapes(shape: Action.Shape) {
        val rect = RectF(shape.start.x, shape.start.y, shape.end.x, shape.end.y)
        val anchorsX: FloatArray = when (activeHandle) {
            Handle.LEFT_CENTER, Handle.TOP_LEFT, Handle.BOTTOM_LEFT -> floatArrayOf(rect.left)
            Handle.RIGHT_CENTER, Handle.TOP_RIGHT, Handle.BOTTOM_RIGHT -> floatArrayOf(rect.right)
            Handle.TOP_CENTER, Handle.BOTTOM_CENTER -> floatArrayOf()
            else -> floatArrayOf(rect.left, rect.centerX(), rect.right)
        }
        val anchorsY: FloatArray = when (activeHandle) {
            Handle.TOP_CENTER, Handle.TOP_LEFT, Handle.TOP_RIGHT -> floatArrayOf(rect.top)
            Handle.BOTTOM_CENTER, Handle.BOTTOM_LEFT, Handle.BOTTOM_RIGHT -> floatArrayOf(rect.bottom)
            Handle.LEFT_CENTER, Handle.RIGHT_CENTER -> floatArrayOf()
            else -> floatArrayOf(rect.top, rect.centerY(), rect.bottom)
        }

        val candidatesX = mutableListOf<Float>()
        val candidatesY = mutableListOf<Float>()
        actions.forEach { a ->
            if (a is Action.Shape && a !== shape) {
                val r = RectF(a.start.x, a.start.y, a.end.x, a.end.y)
                candidatesX += listOf(r.left, r.centerX(), r.right)
                candidatesY += listOf(r.top, r.centerY(), r.bottom)
            }
        }

        if (candidatesX.isEmpty()) anchorsX.forEach { ax -> candidatesX += nearestGrid(ax) }
        if (candidatesY.isEmpty()) anchorsY.forEach { ay -> candidatesY += nearestGrid(ay) }

        val dx = applyAxisSnap(snapX, anchorsX, candidatesX)
        val dy = applyAxisSnap(snapY, anchorsY, candidatesY)

        if (dx != 0f) {
            when (activeHandle) {
                Handle.LEFT_CENTER, Handle.TOP_LEFT, Handle.BOTTOM_LEFT -> shape.start.x += dx
                Handle.RIGHT_CENTER, Handle.TOP_RIGHT, Handle.BOTTOM_RIGHT -> shape.end.x += dx
                else -> { shape.start.x += dx; shape.end.x += dx }
            }
        }
        if (dy != 0f) {
            when (activeHandle) {
                Handle.TOP_CENTER, Handle.TOP_LEFT, Handle.TOP_RIGHT -> shape.start.y += dy
                Handle.BOTTOM_CENTER, Handle.BOTTOM_LEFT, Handle.BOTTOM_RIGHT -> shape.end.y += dy
                else -> { shape.start.y += dy; shape.end.y += dy }
            }
        }
    }

    private data class Candidate(val target: Float, val anchor: Float, val dist: Float)

    private fun applyAxisSnap(axis: AxisSnap, anchors: FloatArray, candidates: List<Float>): Float {
        if (anchors.isEmpty() || candidates.isEmpty()) return 0f
        val now = SystemClock.uptimeMillis()

        var best: Candidate? = null
        anchors.forEach { a ->
            candidates.forEach { c ->
                val d = abs(a - c)
                if (best == null || d < best!!.dist) best = Candidate(c, a, d)
            }
        }

        // Se já está travado neste eixo, mantém enquanto não ultrapassar o "release"
        axis.active?.let { locked ->
            val (closestAnchor, distToLocked) = anchors.minByWithDist { abs(it - locked) }
            return if (distToLocked <= snapRelease) {
                locked - closestAnchor
            } else {
                axis.active = null
                axis.candidate = null
                0f
            }
        }

        // Não travado: só trava se perto o suficiente e após dwell
        best?.let { b ->
            if (b.dist <= snapAccept) {
                if (axis.candidate == b.target) {
                    if (now - axis.seenAt >= snapDwellMs) {
                        axis.active = b.target
                        axis.candidate = null
                        val (closestAnchor, _) = anchors.minByWithDist { abs(it - axis.active!!) }
                        return axis.active!! - closestAnchor
                    }
                } else {
                    axis.candidate = b.target
                    axis.seenAt = now
                }
            } else {
                axis.candidate = null
            }
        }
        return 0f
    }


    private fun nearestGrid(v: Float): Float {
        return (v / gridSpacing).roundToInt() * gridSpacing
    }

    private inline fun <T> Iterable<T>.minByWithDist(dist: (T) -> Float): Pair<T, Float> {
        var bestItem: T? = null
        var bestDist = Float.MAX_VALUE
        for (item in this) {
            val d = dist(item)
            if (d < bestDist) { bestDist = d; bestItem = item }
        }
        @Suppress("UNCHECKED_CAST")
        return bestItem as T to bestDist
    }

    // <-- nova extensão para FloatArray (resolve o erro)
    private inline fun FloatArray.minByWithDist(dist: (Float) -> Float): Pair<Float, Float> {
        if (this.isEmpty()) throw NoSuchElementException("FloatArray is empty")
        var bestItem = this[0]
        var bestDist = dist(bestItem)
        for (i in 1 until size) {
            val d = dist(this[i])
            if (d < bestDist) {
                bestDist = d
                bestItem = this[i]
            }
        }
        return bestItem to bestDist
    }

    private fun poiToProperties(poi: Action.Poi): ShapeProperties {
        return ShapeProperties(
            x = poi.x,
            y = poi.y,
            width = poi.width,
            height = poi.height,
            rotation = 0f
        )
    }

    private fun shapeToProperties(shape: Action.Shape): ShapeProperties {
        val left = min(shape.start.x, shape.end.x)
        val top = min(shape.start.y, shape.end.y)
        val right = max(shape.start.x, shape.end.x)
        val bottom = max(shape.start.y, shape.end.y)
        val w = right - left
        val h = bottom - top

        return ShapeProperties(
            x = left,
            y = top,
            width = w,
            height = h,
            rotation = shape.rotation
        )
    }



    // dentro de MapEditorView
    fun getSelectedShapeRef(): Action.Shape? {
        for (i in actions.size - 1 downTo 0) {
            val a = actions[i]
            if (a is Action.Shape && a.selected) return a
        }
        return null
    }

    private fun getSelectedShape(): Action.Shape? {
        for (i in actions.size - 1 downTo 0) {
            val a = actions[i]
            if (a is Action.Shape && a.selected) return a
        }
        return null
    }

    fun getSelectedShapeProperties(): ShapeProperties? {
        val s = getSelectedShape() ?: return null
        return shapeToProperties(s)
    }

    fun applyPropertiesToSelectedShape(props: ShapeProperties) {
        val s = getSelectedShape() ?: return
        val minSize = dp(30f)
        val w = max(props.width, minSize)
        val h = max(props.height, minSize)

        s.start.x = props.x
        s.start.y = props.y
        s.end.x = props.x + w
        s.end.y = props.y + h
        s.rotation = props.rotation

        invalidate()
        selectionListener?.onShapeSelected(shapeToProperties(s))
    }


    internal fun screenToWorld(x: Float, y: Float): PointF =
        PointF((x - offsetX) / scale, (y - offsetY) / scale)

    internal fun dp(v: Float): Float = v * resources.displayMetrics.density
}
