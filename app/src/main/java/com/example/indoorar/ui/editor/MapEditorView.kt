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
import kotlin.math.roundToInt
import kotlin.math.abs
import android.os.SystemClock


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

    // ======= SNAP GUIDES =====
    private val snapGuides = mutableListOf<Pair<PointF, PointF>>() // linhas visuais

    // --- Snap inteligente (histerese + dwell) ---
    private data class AxisSnap(
        var active: Float? = null,     // alvo travado (linha X ou Y)
        var candidate: Float? = null,  // candidato sob avaliação
        var seenAt: Long = 0L          // quando vimos o candidato pela 1ª vez
    )

    private val snapX = AxisSnap()
    private val snapY = AxisSnap()

    private val gridSpacing = 40f               // mesmo passo do seu grid
    private val snapAccept = dp(6f)             // distância para GRUDAR
    private val snapRelease = dp(14f)           // distância para SOLTAR
    private val snapDwellMs = 60L               // precisa ficar ~60ms perto pra travar


    // ======= DADOS =======
    private val actions = mutableListOf<Action>()

    // ======= SELEÇÃO/DRAG =======
    private var draggingShape: Action.Shape? = null
    private var lastDragPoint: PointF? = null
    private var activeHandle: Handle? = null

    private enum class Handle {
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
        TOP_CENTER, BOTTOM_CENTER, LEFT_CENTER, RIGHT_CENTER
    }

    // ======= PAINTS =======
     val gridDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(210, 210, 210)
        style = Paint.Style.FILL
    }
    val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(50, 100, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND


    }
     val poiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
     val shapeSelectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D99FF") // azul Figma
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val snapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6A00") // laranja Figma
        strokeWidth = dp(1.5f)
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

    // ======= TOUCH =======
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // pinch-zoom sempre
        scaleDetector.onTouchEvent(event)

        // Se NÃO estiver no cursor, delega para o editor correspondente e sai.
        if (currentTool != Tool.CURSOR) {
            return when (currentTool) {
                Tool.BRUSH  -> brushEditor.onTouch(event)
                Tool.FORMAS -> shapeEditor.onTouch(event)
                Tool.POI    -> poiEditor.onTouch(event)
                else        -> true
            }
        }

        // Modo cursor: permitir pan com gesto (quando não está arrastando shape)
        gestureDetector.onTouchEvent(event)

        val world = screenToWorld(event.x, event.y)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastDragPoint = world

                // limpar seleção anterior
                actions.forEach { if (it is Action.Shape) it.selected = false }

                // hit-test em shapes
                val hit = hitTestShapes(world)
                if (hit != null) {
                    hit.selected = true
                    draggingShape = hit
                    activeHandle = hitTestHandles(hit, world)
                } else {
                    draggingShape = null
                    activeHandle = null
                }

                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val prev = lastDragPoint ?: world
                val dx = world.x - prev.x
                val dy = world.y - prev.y

                draggingShape?.let { shape ->
                    val minSize = dp(30f)
                    val rect = RectF(shape.start.x, shape.start.y, shape.end.x, shape.end.y)

                    if (activeHandle != null) {
                        // ===== RESIZE =====
                        when (activeHandle!!) {
                            Handle.TOP_LEFT -> {
                                val newLeft = shape.start.x + dx
                                val newTop = shape.start.y + dy
                                if ((rect.right - newLeft) > minSize) shape.start.x = newLeft
                                if ((rect.bottom - newTop) > minSize) shape.start.y = newTop
                            }
                            Handle.TOP_RIGHT -> {
                                val newRight = shape.end.x + dx
                                val newTop = shape.start.y + dy
                                if ((newRight - rect.left) > minSize) shape.end.x = newRight
                                if ((rect.bottom - newTop) > minSize) shape.start.y = newTop
                            }
                            Handle.BOTTOM_LEFT -> {
                                val newLeft = shape.start.x + dx
                                val newBottom = shape.end.y + dy
                                if ((rect.right - newLeft) > minSize) shape.start.x = newLeft
                                if ((newBottom - rect.top) > minSize) shape.end.y = newBottom
                            }
                            Handle.BOTTOM_RIGHT -> {
                                val newRight = shape.end.x + dx
                                val newBottom = shape.end.y + dy
                                if ((newRight - rect.left) > minSize) shape.end.x = newRight
                                if ((newBottom - rect.top) > minSize) shape.end.y = newBottom
                            }
                            Handle.TOP_CENTER -> {
                                val newTop = shape.start.y + dy
                                if ((rect.bottom - newTop) > minSize) shape.start.y = newTop
                            }
                            Handle.BOTTOM_CENTER -> {
                                val newBottom = shape.end.y + dy
                                if ((newBottom - rect.top) > minSize) shape.end.y = newBottom
                            }
                            Handle.LEFT_CENTER -> {
                                val newLeft = shape.start.x + dx
                                if ((rect.right - newLeft) > minSize) shape.start.x = newLeft
                            }
                            Handle.RIGHT_CENTER -> {
                                val newRight = shape.end.x + dx
                                if ((newRight - rect.left) > minSize) shape.end.x = newRight
                            }
                        }

                        // snap durante resize
                        snapToOtherShapes(shape)
                    } else {
                        // ===== DRAG NORMAL =====
                        shape.start.x += dx
                        shape.start.y += dy
                        shape.end.x += dx
                        shape.end.y += dy

                        // snap durante drag
                        snapToOtherShapes(shape)
                    }
                }

                lastDragPoint = world
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingShape = null
                activeHandle = null
                lastDragPoint = null

                // limpa snap e guias
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
        val selectedShapes = mutableListOf<Action.Shape>()

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
                    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#D9D9D9")
                        style = Paint.Style.FILL
                    }

                    if (action.selected) {
                        selectedShapes.add(action)
                    } else {
                        canvas.drawRect(rect, fillPaint)
                    }
                }
            }
        }

        selectedShapes.forEach { shape ->
            val rect = RectF(shape.start.x, shape.start.y, shape.end.x, shape.end.y)

            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#D9D9D9")
                style = Paint.Style.FILL
            }

            canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, shapeSelectionPaint)
            drawHandles(canvas, rect)
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
            color = Color.parseColor("#0D99FF")
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

    private fun hitTestHandles(shape: Action.Shape, p: PointF, size: Float = dp(24f)): Handle? {
        val rect = RectF(shape.start.x, shape.start.y, shape.end.x, shape.end.y)
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
            if (p.x in (pos.x - half)..(pos.x + half) &&
                p.y in (pos.y - half)..(pos.y + half)) {
                return handle
            }
        }
        return null
    }

    private fun snap(value: Float, spacing: Float = 40f): Float {
        return (value / spacing).roundToInt() * spacing
    }

    private fun snapToOtherShapes(shape: Action.Shape) {
        val rect = RectF(shape.start.x, shape.start.y, shape.end.x, shape.end.y)

        // Âncoras ativas (pontos do shape atual)
        val anchorsX: FloatArray = when (activeHandle) {
            Handle.LEFT_CENTER, Handle.TOP_LEFT, Handle.BOTTOM_LEFT -> floatArrayOf(rect.left)
            Handle.RIGHT_CENTER, Handle.TOP_RIGHT, Handle.BOTTOM_RIGHT -> floatArrayOf(rect.right)
            Handle.TOP_CENTER, Handle.BOTTOM_CENTER -> floatArrayOf() // mexendo só Y
            else -> floatArrayOf(rect.left, rect.centerX(), rect.right) // drag normal
        }
        val anchorsY: FloatArray = when (activeHandle) {
            Handle.TOP_CENTER, Handle.TOP_LEFT, Handle.TOP_RIGHT -> floatArrayOf(rect.top)
            Handle.BOTTOM_CENTER, Handle.BOTTOM_LEFT, Handle.BOTTOM_RIGHT -> floatArrayOf(rect.bottom)
            Handle.LEFT_CENTER, Handle.RIGHT_CENTER -> floatArrayOf() // mexendo só X
            else -> floatArrayOf(rect.top, rect.centerY(), rect.bottom) // drag normal
        }

        // 1. Bordas/centros de outras shapes
        val candidatesX = mutableListOf<Float>()
        val candidatesY = mutableListOf<Float>()
        actions.forEach { a ->
            if (a is Action.Shape && a !== shape) {
                val r = RectF(a.start.x, a.start.y, a.end.x, a.end.y)
                candidatesX += listOf(r.left, r.centerX(), r.right)
                candidatesY += listOf(r.top, r.centerY(), r.bottom)
            }
        }

        // 2. Grid como fallback (só entra se não houver shapes relevantes)
        if (candidatesX.isEmpty()) {
            anchorsX.forEach { ax -> candidatesX += nearestGrid(ax) }
        }
        if (candidatesY.isEmpty()) {
            anchorsY.forEach { ay -> candidatesY += nearestGrid(ay) }
        }

        // Snap final
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

        // melhor candidato (menor distância entre QUALQUER âncora e QUALQUER candidato)
        var best: Candidate? = null
        anchors.forEach { a ->
            candidates.forEach { c ->
                val d = abs(a - c)
                if (best == null || d < best!!.dist) best = Candidate(c, a, d)
            }
        }

        // Se já está travado neste eixo, mantém enquanto não ultrapassar o "release"
        axis.active?.let { locked ->
            val (closestAnchor, distToLocked) = anchors.toList().minByWithDist { abs(it - locked) }
            return if (distToLocked <= snapRelease) {
                locked - closestAnchor // mantém travado
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
                        val (closestAnchor, _) = anchors.toList().minByWithDist { abs(it - axis.active!!) }
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


    // ======= UTILS =======
    internal fun screenToWorld(x: Float, y: Float): PointF =
        PointF((x - offsetX) / scale, (y - offsetY) / scale)

    internal fun dp(v: Float): Float = v * resources.displayMetrics.density
}


