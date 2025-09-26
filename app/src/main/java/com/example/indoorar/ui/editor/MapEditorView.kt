package com.example.indoorar.ui.editor

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
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

    // ===== ESCALA PARA CONVERSÃO =====
    val pxPerMeter = 40f // 40 pixels = 1 metro

    // Funções auxiliares
    fun pxToMeters(px: Float) = px / pxPerMeter
    fun metersToPx(m: Float) = m * pxPerMeter

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

    // Touch click/drag discrimination
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private var downXScreen: Float = 0f
    private var downYScreen: Float = 0f
    private var hasMovedBeyondSlop: Boolean = false

    // ===== CACHE DE IMAGENS =====
    // Substitui o cache antigo por chaves baseadas em (iconRes + tamanho),
    // e armazena também o retângulo do conteúdo opaco (sem transparência).
    private val poiBitmapCache = mutableMapOf<String, Bitmap>()
    private val poiContentBoundsCache = mutableMapOf<String, Rect>()

    // ===== CONFIGURAÇÕES VISUAIS =====
    var showGrid = true
    var showBrush = true // Mantido do seu código original
    var showPois = true  // Mantido do seu código original

    // Suavização do snap/alinhamento
    private var baseSnapThresholdPx = 16f // distância em px de tela para começar a "puxar"
    private var softSnapStrength = 0.35f  // 0..1, quanto do deslocamento aplicar por frame
    private var baseHardSnapThresholdPx = 6f // quando chegar bem perto, dar uma travadinha
    // ===== SELECTION/DRAG (Mantido do seu código original) =====
    private var lastDragPoint: PointF? = null
    private var activeHandle: Handle? = null
    interface OnShapeSelectionListener {
        fun onShapeSelected(props: ShapeProps)
        fun onShapeDeselected()
    }
    var selectionListener: OnShapeSelectionListener? = null
    private enum class Handle { TOP_LEFT, TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT }

    // Quick action buttons next to delete
    private enum class QuickBtn { DELETE, ROUND, STROKE, DUP }

    // Handle visuals
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = "#0D99FF".toColorInt(); style = Paint.Style.STROKE; strokeWidth = dp(1.5f) }
    private fun handleRadiusWorld(): Float = dp(6f) / max(0.001f, scale)

    // Resize state
    private var resizeInitialBounds: RectF? = null

    // ===== PAINTS =====
    private val gridDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(210, 210, 210); style = Paint.Style.FILL }
    private val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapeSelectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val shapeTempPaint: Paint by lazy { Paint().apply { color = "#0D99FF".toColorInt(); strokeWidth = 4f; style = Paint.Style.STROKE; isAntiAlias = true } }

    // ===== EDITORES E GESTOS =====
    private val brushEditor = BrushEditor(this)
    private val shapeEditor = ShapeEditor(this)
    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    // ===== GUIDES DE ALINHAMENTO =====
    private val alignmentGuides = mutableListOf<Pair<PointF, PointF>>()
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#FF9800".toColorInt()
        alpha = 170 // mais suave
        strokeWidth = dp(1.5f)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    // Ícone de lixeira (cacheado)
    private val deleteIconBitmap: Bitmap? by lazy {
        val d = ContextCompat.getDrawable(context, com.example.indoorar.R.drawable.ic_delete_red_24) ?: return@lazy null
        val w = max(1, d.intrinsicWidth)
        val h = max(1, d.intrinsicHeight)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        bmp
    }

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

    /** Fornece o Paint usado para o traço do pincel. */
    fun getBrushPaint(): Paint = brushPaint

    /** Alterna a exibição do grid e invalida a View. */
    fun toggleGrid() {
        showGrid = !showGrid
        invalidate()
    }

    /** Prepara a View para criar um POI no próximo toque. Chamado pela Activity. */
    fun primeForPoiCreation(iconRes: Int) {
        pendingPoiResId = iconRes
        setTool(Tool.POI)
    }

    /** Permite selecionar o tipo de forma que será desenhada no modo FORMAS. */
    fun setShapeType(type: Action.ShapeType) {
        shapeEditor.setType(type)
    }

    /** Define a ferramenta ativa e notifica a Activity. */
    fun setTool(tool: Tool) {
        if (currentTool == tool && tool != Tool.POI) return // Permite re-clicar em POI

        currentTool = tool
        onToolChangedListener?.invoke(tool)

        // Ativa o grid apenas para o cursor
        showGrid = (tool == Tool.CURSOR)

        // Limpa estados para evitar bugs entre ferramentas
        draggingObject = null
        if (tool != Tool.POI) {
            pendingPoiResId = null
        }
        invalidate()
    }

    // ===== UNDO STACK (robusto) =====
    private sealed class EditorOp { abstract fun undo(host: MapEditorView) }
    private class AddOp(private val action: Action) : EditorOp() {
        override fun undo(host: MapEditorView) { host.actions.remove(action); host.invalidate() }
    }
    private class DeleteOp(private val action: Action, private val index: Int) : EditorOp() {
        override fun undo(host: MapEditorView) {
            val idx = index.coerceIn(0, host.actions.size)
            host.actions.add(idx, action)
            host.invalidate()
        }
    }
    private sealed class ActionState {
        data class PoiState(val poi: Action.Poi, val x: Float, val y: Float, val width: Float, val height: Float) : ActionState()
        data class ShapeState(val shape: Action.Shape, val start: PointF, val end: PointF) : ActionState()
    }
    private class MoveOp(private val before: ActionState, private val after: ActionState) : EditorOp() {
        override fun undo(host: MapEditorView) {
            host.applyState(before)
            host.invalidate()
        }
    }
    private val undoStack = ArrayDeque<EditorOp>()
    private val UNDO_LIMIT = 100
    private fun pushOp(op: EditorOp) { undoStack.addLast(op); if (undoStack.size > UNDO_LIMIT) undoStack.removeFirst() }
    fun canUndo() = undoStack.isNotEmpty()
    fun undo() {
        val op = if (undoStack.isNotEmpty()) undoStack.removeLast() else null
        if (op != null) {
            op.undo(this)
            selectionListener?.onShapeDeselected()
        } else if (actions.isNotEmpty()) { // fallback
            actions.removeAt(actions.lastIndex)
        }
        invalidate()
    }

    private fun snapshotOf(action: Action?): ActionState? = when (action) {
        is Action.Poi -> ActionState.PoiState(action, action.x, action.y, action.width, action.height)
        is Action.Shape -> ActionState.ShapeState(action, PointF(action.start.x, action.start.y), PointF(action.end.x, action.end.y))
        else -> null
    }
    private fun applyState(state: ActionState) {
        when (state) {
            is ActionState.PoiState -> { state.poi.x = state.x; state.poi.y = state.y; state.poi.width = state.width; state.poi.height = state.height }
            is ActionState.ShapeState -> {
                state.shape.start.x = state.start.x; state.shape.start.y = state.start.y
                state.shape.end.x = state.end.x; state.shape.end.y = state.end.y
            }
        }
    }
    private fun statesEqual(a: ActionState?, b: ActionState?): Boolean {
        if (a == null || b == null || a::class != b::class) return false
        return when (a) {
            is ActionState.PoiState -> {
                val bb = b as ActionState.PoiState; a.x == bb.x && a.y == bb.y && a.width == bb.width && a.height == bb.height
            }
            is ActionState.ShapeState -> {
                val bb = b as ActionState.ShapeState
                a.start.x == bb.start.x && a.start.y == bb.start.y && a.end.x == bb.end.x && a.end.y == bb.end.y
            }
        }
    }

    // Snapshot do início do arraste
    private var dragSnapshotBefore: ActionState? = null

    // ===== TOUCH =====

    // Helpers for bounds and handles
    private fun getActionBounds(action: Action): RectF? = when (action) {
        is Action.Shape -> {
            val left = min(action.start.x, action.end.x)
            val top = min(action.start.y, action.end.y)
            val right = max(action.start.x, action.end.x)
            val bottom = max(action.start.y, action.end.y)
            RectF(left, top, right, bottom)
        }
        is Action.Poi -> RectF(action.x - action.width / 2f, action.y - action.height / 2f, action.x + action.width / 2f, action.y + action.height / 2f)
        else -> null
    }

    private fun getHandlePositions(bounds: RectF): Map<Handle, PointF> {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        return mapOf(
            Handle.TOP_LEFT to PointF(bounds.left, bounds.top),
            Handle.TOP to PointF(cx, bounds.top),
            Handle.TOP_RIGHT to PointF(bounds.right, bounds.top),
            Handle.RIGHT to PointF(bounds.right, cy),
            Handle.BOTTOM_RIGHT to PointF(bounds.right, bounds.bottom),
            Handle.BOTTOM to PointF(cx, bounds.bottom),
            Handle.BOTTOM_LEFT to PointF(bounds.left, bounds.bottom),
            Handle.LEFT to PointF(bounds.left, cy)
        )
    }

    private fun drawHandles(canvas: Canvas, bounds: RectF) {
        val r = handleRadiusWorld()
        val positions = getHandlePositions(bounds)
        positions.values.forEach { p ->
            canvas.drawCircle(p.x, p.y, r, handleFillPaint)
            canvas.drawCircle(p.x, p.y, r, handleStrokePaint)
        }
    }

    private fun hitTestHandles(hit: Action, point: PointF): Handle? {
        val bounds = getActionBounds(hit) ?: return null
        // Increase handle hit radius: at least 16dp in screen space, scaled to world coords
        val r = max(handleRadiusWorld() * 1.5f, dp(16f) / max(0.001f, scale))
        val positions = getHandlePositions(bounds)
        return positions.entries.firstOrNull { (_, pos) ->
            val dx = point.x - pos.x
            val dy = point.y - pos.y
            dx * dx + dy * dy <= r * r
        }?.key
    }

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
                downXScreen = event.x
                downYScreen = event.y
                hasMovedBeyondSlop = false

                // Botões rápidos (delete/round/stroke/dup) têm prioridade
                if (handleQuickButtonsTap(world)) return true

                // Se já existe um selecionado, verifica clique na lixeirinha primeiro
                val currentlySelected = actions.firstOrNull { act ->
                    when (act) {
                        is Action.Shape -> act.selected
                        is Action.Poi -> act.selected
                        else -> false
                    }
                }
                currentlySelected?.let { selectedAct ->
                    val delRect = getDeleteRectForAction(selectedAct)
                    if (delRect != null && world.x in delRect.left..delRect.right && world.y in delRect.top..delRect.bottom) {
                        AlertDialog.Builder(context)
                            .setTitle("Deseja excluir?")
                            .setMessage("Esta ação removerá o item do mapa.")
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Excluir") { _, _ ->
                                // Recalcula o índice no momento da exclusão para evitar inconsistências
                                val idx = actions.indexOf(selectedAct)
                                if (idx >= 0) {
                                    actions.removeAt(idx)
                                    pushOp(DeleteOp(selectedAct, idx))
                                    selectionListener?.onShapeDeselected()
                                    draggingObject = null
                                    invalidate()
                                }
                            }
                            .show()
                        return true
                    }
                    // Testa hit nos handles do item selecionado
                    val handle = hitTestHandles(selectedAct, world)
                    if (handle != null) {
                        activeHandle = handle
                        draggingObject = selectedAct
                        dragSnapshotBefore = snapshotOf(draggingObject)
                        resizeInitialBounds = getActionBounds(selectedAct)
                        // mantém seleção como está
                        invalidate()
                        return true
                    }
                }

                draggingObject = hitTestObjects(world)
                actions.forEach { action ->
                    val isSelected = (action == draggingObject)
                    when (action) {
                        is Action.Shape -> action.selected = isSelected
                        is Action.Poi -> action.selected = isSelected
                        else -> {}
                    }
                }

                // snapshot para possível MoveOp
                dragSnapshotBefore = snapshotOf(draggingObject)

                draggingObject?.let { obj ->
                    when (obj) {
                        is Action.Poi -> { touchOffsetX = world.x - obj.x; touchOffsetY = world.y - obj.y }
                        is Action.Shape -> { touchOffsetX = world.x - obj.start.x; touchOffsetY = world.y - obj.start.y }
                        is Action.BrushStroke -> {}
                    }
                }

                // Não mostrar painel aqui. Ao iniciar qualquer toque, esconda para não atrapalhar o arraste.
                selectionListener?.onShapeDeselected()

                // Limpa as guides ao iniciar um novo toque
                alignmentGuides.clear()

                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Detecta se virou arraste (moveu além do slop)
                if (!hasMovedBeyondSlop) {
                    val dx = abs(event.x - downXScreen)
                    val dy = abs(event.y - downYScreen)
                    if (dx > touchSlop || dy > touchSlop) hasMovedBeyondSlop = true
                }

                if (hasMovedBeyondSlop) {
                    draggingObject?.let { obj ->
                        if (activeHandle != null) {
                            // Redimensionamento
                            val init = resizeInitialBounds ?: getActionBounds(obj)
                            if (init != null) {
                                val minSize = dp(12f) / max(0.001f, scale)
                                var left = init.left
                                var top = init.top
                                var right = init.right
                                var bottom = init.bottom
                                when (activeHandle) {
                                    Handle.LEFT, Handle.TOP_LEFT, Handle.BOTTOM_LEFT -> { left = world.x }
                                    else -> {}
                                }
                                when (activeHandle) {
                                    Handle.RIGHT, Handle.TOP_RIGHT, Handle.BOTTOM_RIGHT -> { right = world.x }
                                    else -> {}
                                }
                                when (activeHandle) {
                                    Handle.TOP, Handle.TOP_LEFT, Handle.TOP_RIGHT -> { top = world.y }
                                    else -> {}
                                }
                                when (activeHandle) {
                                    Handle.BOTTOM, Handle.BOTTOM_LEFT, Handle.BOTTOM_RIGHT -> { bottom = world.y }
                                    else -> {}
                                }
                                // Normaliza e aplica tamanho mínimo
                                var nLeft = min(left, right)
                                var nRight = max(left, right)
                                var nTop = min(top, bottom)
                                var nBottom = max(top, bottom)
                                if ((nRight - nLeft) < minSize) {
                                    val mid = (nLeft + nRight) / 2f
                                    nLeft = mid - minSize / 2f
                                    nRight = mid + minSize / 2f
                                }
                                if ((nBottom - nTop) < minSize) {
                                    val mid = (nTop + nBottom) / 2f
                                    nTop = mid - minSize / 2f
                                    nBottom = mid + minSize / 2f
                                }

                                when (obj) {
                                    is Action.Shape -> {
                                        obj.start.x = nLeft
                                        obj.start.y = nTop
                                        obj.end.x = nRight
                                        obj.end.y = nBottom
                                        // Mantém selecionado e atualiza painel
                                        selectionListener?.onShapeSelected(shapeToProperties(obj))
                                    }
                                    is Action.Poi -> {
                                        val newW = (nRight - nLeft).coerceAtLeast(minSize)
                                        val newH = (nBottom - nTop).coerceAtLeast(minSize)
                                        obj.x = (nLeft + nRight) / 2f
                                        obj.y = (nTop + nBottom) / 2f
                                        obj.width = newW
                                        obj.height = newH
                                        selectionListener?.onShapeSelected(poiToProperties(obj))
                                    }
                                    else -> {}
                                }
                                invalidate()
                            }
                        } else {
                            // Movimento normal
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

                            // Lógica de alinhamento (suavizada + snap quando muito perto)
                            alignmentGuides.clear()
                            val snapThreshold = baseSnapThresholdPx / max(0.001f, scale)
                            val hardSnapThreshold = baseHardSnapThresholdPx / max(0.001f, scale)

                            fun applySnapX(targetDelta: Float) {
                                val dist = abs(targetDelta)
                                if (dist <= 0f) return
                                val adj = if (dist <= hardSnapThreshold) {
                                    // Snap completo
                                    targetDelta
                                } else if (dist <= snapThreshold) {
                                    // Snap suave
                                    val factor = softSnapStrength * (1f - dist / snapThreshold).coerceIn(0f, 1f)
                                    targetDelta * factor
                                } else 0f
                                if (adj != 0f) {
                                    obj.apply {
                                        if (this is Action.Poi) x += adj
                                        if (this is Action.Shape) { start.x += adj; end.x += adj }
                                    }
                                }
                            }
                            fun applySnapY(targetDelta: Float) {
                                val dist = abs(targetDelta)
                                if (dist <= 0f) return
                                val adj = if (dist <= hardSnapThreshold) {
                                    targetDelta
                                } else if (dist <= snapThreshold) {
                                    val factor = softSnapStrength * (1f - dist / snapThreshold).coerceIn(0f, 1f)
                                    targetDelta * factor
                                } else 0f
                                if (adj != 0f) {
                                    obj.apply {
                                        if (this is Action.Poi) y += adj
                                        if (this is Action.Shape) { start.y += adj; end.y += adj }
                                    }
                                }
                            }

                            val draggedBounds = when (obj) {
                                is Action.Poi -> getActionBounds(obj)
                                is Action.Shape -> RectF(obj.start.x, obj.start.y, obj.end.x, obj.end.y)
                                else -> null
                            }
                            val draggedCenterX = draggedBounds?.centerX() ?: 0f
                            val draggedCenterY = draggedBounds?.centerY() ?: 0f

                            actions.filter { it != obj }.forEach { other ->
                                val otherBounds = when (other) {
                                    is Action.Poi -> getActionBounds(other)
                                    is Action.Shape -> RectF(other.start.x, other.start.y, other.end.x, other.end.y)
                                    else -> null
                                }
                                if (draggedBounds != null && otherBounds != null) {
                                    val otherCenterX = otherBounds.centerX()
                                    val otherCenterY = otherBounds.centerY()
                                    // Alinhamento vertical (centro X)
                                    run {
                                        val dxToCenter = otherCenterX - draggedCenterX
                                        if (abs(dxToCenter) < snapThreshold) {
                                            alignmentGuides.add(Pair(PointF(otherCenterX, 0f), PointF(otherCenterX, height.toFloat())))
                                            applySnapX(dxToCenter)
                                        }
                                    }
                                    // Alinhamento horizontal (centro Y)
                                    run {
                                        val dyToCenter = otherCenterY - draggedCenterY
                                        if (abs(dyToCenter) < snapThreshold) {
                                            alignmentGuides.add(Pair(PointF(0f, otherCenterY), PointF(width.toFloat(), otherCenterY)))
                                            applySnapY(dyToCenter)
                                        }
                                    }
                                    // Bordas esquerda/direita
                                    val edges = listOf(otherBounds.left, otherBounds.right)
                                    edges.forEach { edgeX ->
                                        val dxLeft = edgeX - draggedBounds.left
                                        if (abs(dxLeft) < snapThreshold) {
                                            alignmentGuides.add(Pair(PointF(edgeX, 0f), PointF(edgeX, height.toFloat())))
                                            applySnapX(dxLeft)
                                        }
                                        val dxRight = edgeX - draggedBounds.right
                                        if (abs(dxRight) < snapThreshold) {
                                            alignmentGuides.add(Pair(PointF(edgeX, 0f), PointF(edgeX, height.toFloat())))
                                            applySnapX(dxRight)
                                        }
                                    }
                                    // Bordas superior/inferior
                                    val edgesY = listOf(otherBounds.top, otherBounds.bottom)
                                    edgesY.forEach { edgeY ->
                                        val dyTop = edgeY - draggedBounds.top
                                        if (abs(dyTop) < snapThreshold) {
                                            alignmentGuides.add(Pair(PointF(0f, edgeY), PointF(width.toFloat(), edgeY)))
                                            applySnapY(dyTop)
                                        }
                                        val dyBottom = edgeY - draggedBounds.bottom
                                        if (abs(dyBottom) < snapThreshold) {
                                            alignmentGuides.add(Pair(PointF(0f, edgeY), PointF(width.toFloat(), edgeY)))
                                            applySnapY(dyBottom)
                                        }
                                    }
                                }
                            }
                            invalidate()
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                alignmentGuides.clear()

                val isClick = !hasMovedBeyondSlop

                if (isClick) {
                    draggingObject?.let {
                        // Clique em objeto: mostrar painel
                        selectionListener?.onShapeSelected(
                            if (it is Action.Shape) shapeToProperties(it) else poiToProperties(it as Action.Poi)
                        )
                        performClick()
                    } ?: run {
                        // Clique em área vazia: ocultar painel
                        selectionListener?.onShapeDeselected()
                    }
                } else {
                    // Registrar movimento
                    draggingObject?.let { obj ->
                        val before = dragSnapshotBefore
                        val after = snapshotOf(obj)
                        if (!statesEqual(before, after)) {
                            if (before != null && after != null) pushOp(MoveOp(before, after))
                        }
                    }
                }

                draggingObject = null
                activeHandle = null
                resizeInitialBounds = null
                dragSnapshotBefore = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ===== LÓGICA DE DESENHO (Mantido do seu código, com ajustes para o cache) =====

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Fill background to avoid transparency when panning far from origin
        canvas.drawColor(Color.WHITE)
        canvas.withTranslation(offsetX, offsetY) {
            scale(scale, scale)
            if (showGrid) drawGrid(this)
            // Desenha guides de alinhamento
            alignmentGuides.forEach { (p1, p2) ->
                drawLine(p1.x, p1.y, p2.x, p2.y, guidePaint)
            }
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
                    val norm = RectF(min(rect.left, rect.right), min(rect.top, rect.bottom), max(rect.left, rect.right), max(rect.top, rect.bottom))
                    val cx = (norm.left + norm.right) / 2f
                    val cy = (norm.top + norm.bottom) / 2f
                    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = action.fillColor; style = Paint.Style.FILL }
                    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f }

                    canvas.withSave {
                        rotate(action.rotation, cx, cy)
                        when (action.type) {
                            Action.ShapeType.RECTANGLE, Action.ShapeType.SQUARE -> {
                                val cr = action.cornerRadius.coerceAtLeast(0f)
                                if (cr > 0f) {
                                    drawRoundRect(norm, cr, cr, fill)
                                    if (action.strokeEnabled) drawRoundRect(norm, cr, cr, stroke)
                                } else {
                                    drawRect(norm, fill)
                                    if (action.strokeEnabled) drawRect(norm, stroke)
                                }
                            }
                            Action.ShapeType.CIRCLE -> {
                                drawOval(norm, fill)
                                if (action.strokeEnabled) drawOval(norm, stroke)
                            }
                            Action.ShapeType.TRIANGLE -> {
                                val path = Path().apply {
                                    moveTo((norm.left + norm.right) / 2f, norm.top)
                                    lineTo(norm.left, norm.bottom)
                                    lineTo(norm.right, norm.bottom)
                                    close()
                                }
                                drawPath(path, fill)
                                if (action.strokeEnabled) drawPath(path, stroke)
                            }
                            Action.ShapeType.LINE -> {
                                // Usa a cor de preenchimento como cor da linha
                                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = action.fillColor
                                    style = Paint.Style.STROKE
                                    strokeWidth = 3f
                                }
                                drawLine(action.start.x, action.start.y, action.end.x, action.end.y, linePaint)
                            }
                        }
                    }

                    if (action.selected) drawSelection(canvas, action)
                }
            }
        }
    }

    private fun drawSelection(canvas: Canvas, action: Action) {
        val rect = when (action) {
            is Action.Shape -> getActionBounds(action)
            is Action.Poi -> getActionBounds(action)
            else -> null
        } ?: return
        // Borda de seleção
        canvas.drawRect(rect, shapeSelectionPaint)
        // Desenha handles
        drawHandles(canvas, rect)

        // Desenha botões rápidos (DELETE, ROUND, STROKE, DUP) alinhados no topo direito
        val btnRects = getQuickButtonRects(rect)
        btnRects.forEach { (btn, dest) ->
            // Fundo
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL; alpha = 230 }
            canvas.drawRoundRect(dest, dest.width()/5f, dest.height()/5f, bgPaint)
            when (btn) {
                QuickBtn.DELETE -> {
                    val icon = deleteIconBitmap
                    if (icon != null) canvas.drawBitmap(icon, null, dest, null) else drawXIcon(canvas, dest, Color.RED)
                }
                QuickBtn.ROUND -> {
                    drawRoundIcon(canvas, dest)
                }
                QuickBtn.STROKE -> {
                    val enabled = (action as? Action.Shape)?.strokeEnabled ?: true
                    drawStrokeIcon(canvas, dest, enabled)
                }
                QuickBtn.DUP -> {
                    drawDuplicateIcon(canvas, dest)
                }
            }
        }
    }

    private fun getQuickButtonRects(bounds: RectF): Map<QuickBtn, RectF> {
        val margin = dp(4f) / max(0.001f, scale)
        val size = dp(28f) / max(0.001f, scale)
        val gap = dp(6f) / max(0.001f, scale)
        var right = bounds.right
        val top = bounds.top - size - margin
        val map = linkedMapOf<QuickBtn, RectF>()
        fun add(btn: QuickBtn) {
            val left = right - size
            map[btn] = RectF(left, top, right, top + size)
            right = left - gap
        }
        add(QuickBtn.DELETE)
        add(QuickBtn.ROUND)
        add(QuickBtn.STROKE)
        add(QuickBtn.DUP)
        return map
    }

    // Simple glyph icons
    private fun drawXIcon(canvas: Canvas, r: RectF, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = r.width() * 0.12f }
        canvas.drawLine(r.left + r.width()*0.2f, r.top + r.height()*0.2f, r.right - r.width()*0.2f, r.bottom - r.height()*0.2f, p)
        canvas.drawLine(r.right - r.width()*0.2f, r.top + r.height()*0.2f, r.left + r.width()*0.2f, r.bottom - r.height()*0.2f, p)
    }

    private fun drawRoundIcon(canvas: Canvas, r: RectF) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = "#0D99FF".toColorInt(); style = Paint.Style.STROKE; strokeWidth = r.width()*0.12f }
        canvas.drawRoundRect(RectF(r.left + r.width()*0.22f, r.top + r.height()*0.22f, r.right - r.width()*0.22f, r.bottom - r.height()*0.22f), r.width()*0.2f, r.height()*0.2f, p)
    }

    private fun drawStrokeIcon(canvas: Canvas, r: RectF, enabled: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (enabled) Color.BLACK else Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = r.width()*0.12f }
        canvas.drawRect(RectF(r.left + r.width()*0.25f, r.top + r.height()*0.25f, r.right - r.width()*0.25f, r.bottom - r.height()*0.25f), p)
        if (!enabled) {
            val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = r.width()*0.12f }
            canvas.drawLine(r.left + r.width()*0.25f, r.bottom - r.height()*0.25f, r.right - r.width()*0.25f, r.top + r.height()*0.25f, cut)
        }
    }

    private fun drawDuplicateIcon(canvas: Canvas, r: RectF) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; style = Paint.Style.STROKE; strokeWidth = r.width()*0.10f }
        val inset = r.width()*0.20f
        val back = RectF(r.left + inset*0.6f, r.top + inset*0.6f, r.right - inset*1.4f, r.bottom - inset*1.4f)
        val front = RectF(r.left + inset*1.0f, r.top + inset*1.0f, r.right - inset*0.6f, r.bottom - inset*0.6f)
        canvas.drawRect(back, p)
        canvas.drawRect(front, p)
    }

    // Hit test for quick buttons and delete dialog/action
    private fun handleQuickButtonsTap(world: PointF): Boolean {
        val selectedAct = actions.firstOrNull { when (it) { is Action.Shape -> it.selected; is Action.Poi -> it.selected; else -> false } } ?: return false
        val bounds = getActionBounds(selectedAct) ?: return false
        val map = getQuickButtonRects(bounds)
        val hit = map.entries.firstOrNull { (_, r) -> world.x in r.left..r.right && world.y in r.top..r.bottom } ?: return false
        when (hit.key) {
            QuickBtn.DELETE -> {
                AlertDialog.Builder(context)
                    .setTitle("Deseja excluir?")
                    .setMessage("Esta ação removerá o item do mapa.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Excluir") { _, _ ->
                        val idx = actions.indexOf(selectedAct)
                        if (idx >= 0) {
                            actions.removeAt(idx)
                            pushOp(DeleteOp(selectedAct, idx))
                            selectionListener?.onShapeDeselected()
                            invalidate()
                        }
                    }
                    .show()
            }
            QuickBtn.ROUND -> {
                if (selectedAct is Action.Shape && (selectedAct.type == Action.ShapeType.RECTANGLE || selectedAct.type == Action.ShapeType.SQUARE)) {
                    val b = bounds
                    val w = (b.right - b.left)
                    val h = (b.bottom - b.top)
                    val target = (min(w, h) * 0.2f).coerceAtLeast(dp(6f) / max(0.001f, scale))
                    selectedAct.cornerRadius = if (selectedAct.cornerRadius <= 0f) target else 0f
                    selectionListener?.onShapeSelected(shapeToProperties(selectedAct))
                    invalidate()
                }
            }
            QuickBtn.STROKE -> {
                if (selectedAct is Action.Shape) {
                    selectedAct.strokeEnabled = !selectedAct.strokeEnabled
                    selectionListener?.onShapeSelected(shapeToProperties(selectedAct))
                    invalidate()
                }
            }
            QuickBtn.DUP -> {
                val offset = dp(12f) / max(0.001f, scale)
                val copy = when (selectedAct) {
                    is Action.Shape -> {
                        val s = selectedAct
                        val dup = com.example.indoorar.ui.Action.Shape(
                            start = PointF(s.start.x + offset, s.start.y + offset),
                            end = PointF(s.end.x + offset, s.end.y + offset),
                            selected = false,
                            fillColor = s.fillColor,
                            rotation = s.rotation,
                            isWalkable = s.isWalkable,
                            nome = s.nome,
                            type = s.type,
                            cornerRadius = s.cornerRadius,
                            strokeEnabled = s.strokeEnabled
                        )
                        dup
                    }
                    is Action.Poi -> {
                        val p = selectedAct
                        com.example.indoorar.ui.Action.Poi(
                            x = p.x + offset,
                            y = p.y + offset,
                            width = p.width,
                            height = p.height,
                            iconRes = p.iconRes
                        )
                    }
                    else -> null
                }
                if (copy != null) {
                    addAction(copy)
                    invalidate()
                }
            }
        }
        return true
    }

    // ===== MÉTODOS AUXILIARES RESTAURADOS =====

    // Retângulo da lixeira em coordenadas do mundo para hit test
    private fun getDeleteRectForAction(action: Action): RectF? {
        val rect = when (action) {
            is Action.Shape -> getActionBounds(action)
            is Action.Poi -> getActionBounds(action)
            else -> null
        } ?: return null
        val margin = dp(4f) / max(0.001f, scale)
        val size = dp(32f) / max(0.001f, scale) // hit maior
        val left = rect.right - size
        val top = rect.top - size - margin
        return RectF(left, top, left + size, top + size)
    }

    private fun drawGrid(canvas: Canvas) {
        val spacing = pxPerMeter
        val radius = 2f
        val worldLeft = -offsetX / scale
        val worldTop = -offsetY / scale
        val worldRight = (width - offsetX) / scale
        val worldBottom = (height - offsetY) / scale
        val startCol = kotlin.math.floor(worldLeft / spacing).toInt() - 2
        val endCol = kotlin.math.ceil(worldRight / spacing).toInt() + 2
        val startRow = kotlin.math.floor(worldTop / spacing).toInt() - 2
        val endRow = kotlin.math.ceil(worldBottom / spacing).toInt() + 2
        for (i in startCol..endCol) {
            val x = i * spacing
            for (j in startRow..endRow) {
                val y = j * spacing
                canvas.drawCircle(x, y, radius, gridDotPaint)
            }
        }
    }

    private fun getPoiCacheKey(poi: Action.Poi): String = "${'$'}{poi.iconRes}:${'$'}{poi.width}x${'$'}{poi.height}"

    private fun getBitmapForPoi(poi: Action.Poi): Bitmap? {
        val key = getPoiCacheKey(poi)
        poiBitmapCache[key]?.let { return it }
        return try {
            val drawable = ContextCompat.getDrawable(context, poi.iconRes) ?: return null
            val baseBmp = if (drawable is BitmapDrawable) {
                drawable.bitmap
            } else {
                val bitmap = Bitmap.createBitmap(
                    max(1, drawable.intrinsicWidth),
                    max(1, drawable.intrinsicHeight),
                    Bitmap.Config.ARGB_8888
                )
                val c = Canvas(bitmap)
                drawable.setBounds(0, 0, c.width, c.height)
                drawable.draw(c)
                bitmap
            }
            val targetW = max(1, poi.width.toInt())
            val targetH = max(1, poi.height.toInt())
            val scaled = if (baseBmp.width == targetW && baseBmp.height == targetH) baseBmp
            else Bitmap.createScaledBitmap(baseBmp, targetW, targetH, true)
            val contentRect = computeOpaqueBounds(scaled)
            poiBitmapCache[key] = scaled
            poiContentBoundsCache[key] = contentRect
            scaled
        } catch (e: Exception) { null }
    }

    private fun computeOpaqueBounds(bmp: Bitmap, alphaThreshold: Int = 10): Rect {
        val w = bmp.width
        val h = bmp.height
        var left = w
        var right = -1
        var top = h
        var bottom = -1
        val pixels = IntArray(w)
        for (y in 0 until h) {
            bmp.getPixels(pixels, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val a = (pixels[x] ushr 24) and 0xFF
                if (a > alphaThreshold) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (right >= left && bottom >= top) Rect(left, top, right + 1, bottom + 1) else Rect(0, 0, w, h)
    }

    private fun getPoiContentRectInWorld(poi: Action.Poi): RectF? {
        val bmp = getBitmapForPoi(poi) ?: return null
        val key = getPoiCacheKey(poi)
        val content = poiContentBoundsCache[key] ?: return null
        val left = poi.x - bmp.width / 2f + content.left
        val top = poi.y - bmp.height / 2f + content.top
        val right = left + content.width()
        val bottom = top + content.height()
        return RectF(left, top, right, bottom)
    }

    private fun hitTestObjects(point: PointF): Action? {
        return actions.asReversed().find { action ->
            when (action) {
                is Action.Poi -> {
                    val rect = getPoiContentRectInWorld(action) ?: return@find false
                    point.x in rect.left..rect.right && point.y in rect.top..rect.bottom
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


    internal fun screenToWorld(x: Float, y: Float) = PointF((x - offsetX) / scale, (y - offsetY) / scale)
    internal fun dp(v: Float) = v * resources.displayMetrics.density

    fun addPoi(x: Float, y: Float, iconRes: Int) {
        val poi = Action.Poi(x = x, y = y, iconRes = iconRes)
        actions.add(poi)
        pushOp(AddOp(poi))
        invalidate()
    }

    fun addAction(action: Action) {
        actions.add(action)
        pushOp(AddOp(action))
        invalidate()
    }
}