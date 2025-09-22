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
    val pxPerMeter = 12f // 12 pixels = 1 metro

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

    // ===== SELECTION/DRAG (Mantido do seu código original) =====
    private var lastDragPoint: PointF? = null
    private var activeHandle: Handle? = null
    interface OnShapeSelectionListener {
        fun onShapeSelected(props: ShapeProps)
        fun onShapeDeselected()
    }
    var selectionListener: OnShapeSelectionListener? = null
    private enum class Handle { }

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
        val d = ContextCompat.getDrawable(context, com.example.indoorar.R.drawable.ic_voltar_azul) ?: return@lazy null
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
        data class PoiState(val poi: Action.Poi, val x: Float, val y: Float) : ActionState()
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
        is Action.Poi -> ActionState.PoiState(action, action.x, action.y)
        is Action.Shape -> ActionState.ShapeState(action, PointF(action.start.x, action.start.y), PointF(action.end.x, action.end.y))
        else -> null
    }
    private fun applyState(state: ActionState) {
        when (state) {
            is ActionState.PoiState -> { state.poi.x = state.x; state.poi.y = state.y }
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
                val bb = b as ActionState.PoiState; a.x == bb.x && a.y == bb.y
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

                // Se já existe um selecionado, verifica clique na lixeirinha primeiro
                actions.firstOrNull { act ->
                    when (act) {
                        is Action.Shape -> act.selected
                        is Action.Poi -> act.selected
                        else -> false
                    }
                }?.let { selectedAct ->
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

                        // Lógica de alinhamento (suavizada)
                        alignmentGuides.clear()
                        val snapThreshold = baseSnapThresholdPx / max(0.001f, scale)

                        fun applySoftSnapX(targetDelta: Float) {
                            val dist = abs(targetDelta)
                            if (dist <= snapThreshold && dist > 0f) {
                                val factor = softSnapStrength * (1f - dist / snapThreshold).coerceIn(0f, 1f)
                                val adj = targetDelta * factor
                                obj.apply {
                                    if (this is Action.Poi) x += adj
                                    if (this is Action.Shape) { start.x += adj; end.x += adj }
                                }
                            }
                        }
                        fun applySoftSnapY(targetDelta: Float) {
                            val dist = abs(targetDelta)
                            if (dist <= snapThreshold && dist > 0f) {
                                val factor = softSnapStrength * (1f - dist / snapThreshold).coerceIn(0f, 1f)
                                val adj = targetDelta * factor
                                obj.apply {
                                    if (this is Action.Poi) y += adj
                                    if (this is Action.Shape) { start.y += adj; end.y += adj }
                                }
                            }
                        }

                        val draggedBounds = when (obj) {
                            is Action.Poi -> getPoiContentRectInWorld(obj)
                            is Action.Shape -> RectF(obj.start.x, obj.start.y, obj.end.x, obj.end.y)
                            else -> null
                        }
                        val draggedCenterX = draggedBounds?.centerX() ?: 0f
                        val draggedCenterY = draggedBounds?.centerY() ?: 0f

                        actions.filter { it != obj }.forEach { other ->
                            val otherBounds = when (other) {
                                is Action.Poi -> getPoiContentRectInWorld(other)
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
                                        applySoftSnapX(dxToCenter)
                                    }
                                }
                                // Alinhamento horizontal (centro Y)
                                run {
                                    val dyToCenter = otherCenterY - draggedCenterY
                                    if (abs(dyToCenter) < snapThreshold) {
                                        alignmentGuides.add(Pair(PointF(0f, otherCenterY), PointF(width.toFloat(), otherCenterY)))
                                        applySoftSnapY(dyToCenter)
                                    }
                                }
                                // Bordas esquerda/direita
                                val edges = listOf(otherBounds.left, otherBounds.right)
                                edges.forEach { edgeX ->
                                    val dxLeft = edgeX - draggedBounds.left
                                    if (abs(dxLeft) < snapThreshold) {
                                        alignmentGuides.add(Pair(PointF(edgeX, 0f), PointF(edgeX, height.toFloat())))
                                        applySoftSnapX(dxLeft)
                                    }
                                    val dxRight = edgeX - draggedBounds.right
                                    if (abs(dxRight) < snapThreshold) {
                                        alignmentGuides.add(Pair(PointF(edgeX, 0f), PointF(edgeX, height.toFloat())))
                                        applySoftSnapX(dxRight)
                                    }
                                }
                                // Bordas superior/inferior
                                val edgesY = listOf(otherBounds.top, otherBounds.bottom)
                                edgesY.forEach { edgeY ->
                                    val dyTop = edgeY - draggedBounds.top
                                    if (abs(dyTop) < snapThreshold) {
                                        alignmentGuides.add(Pair(PointF(0f, edgeY), PointF(width.toFloat(), edgeY)))
                                        applySoftSnapY(dyTop)
                                    }
                                    val dyBottom = edgeY - draggedBounds.bottom
                                    if (abs(dyBottom) < snapThreshold) {
                                        alignmentGuides.add(Pair(PointF(0f, edgeY), PointF(width.toFloat(), edgeY)))
                                        applySoftSnapY(dyBottom)
                                    }
                                }
                            }
                        }
                        invalidate()
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
                                drawRect(norm, fill)
                                drawRect(norm, stroke)
                            }
                            Action.ShapeType.CIRCLE -> {
                                drawOval(norm, fill)
                                drawOval(norm, stroke)
                            }
                            Action.ShapeType.TRIANGLE -> {
                                val path = Path().apply {
                                    moveTo((norm.left + norm.right) / 2f, norm.top)
                                    lineTo(norm.left, norm.bottom)
                                    lineTo(norm.right, norm.bottom)
                                    close()
                                }
                                drawPath(path, fill)
                                drawPath(path, stroke)
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
            is Action.Shape -> RectF(action.start.x, action.start.y, action.end.x, action.end.y)
            is Action.Poi -> getPoiContentRectInWorld(action) ?: return
            else -> return
        }
        // Borda de seleção
        canvas.drawRect(rect, shapeSelectionPaint)

        // Desenha lixeira no canto superior direito do retângulo
        val icon = deleteIconBitmap ?: return
        val margin = dp(4f) / max(0.001f, scale)
        val size = dp(24f) / max(0.001f, scale)
        val left = rect.right - size
        val top = rect.top - size - margin
        val dest = RectF(left, top, left + size, top + size)

        // Fundo branco levemente translúcido para contraste
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL; alpha = 220 }
        canvas.drawRoundRect(dest, size/4f, size/4f, bgPaint)
        canvas.drawBitmap(icon, null, dest, null)
    }

    // Retângulo da lixeira em coordenadas do mundo para hit test
    private fun getDeleteRectForAction(action: Action): RectF? {
        val rect = when (action) {
            is Action.Shape -> RectF(action.start.x, action.start.y, action.end.x, action.end.y)
            is Action.Poi -> getPoiContentRectInWorld(action)
            else -> null
        } ?: return null
        val margin = dp(4f) / max(0.001f, scale)
        val size = dp(24f) / max(0.001f, scale)
        val left = rect.right - size
        val top = rect.top - size - margin
        return RectF(left, top, left + size, top + size)
    }

    private fun drawGrid(canvas: Canvas) {
        val spacing = 40f
        val radius = 2f
        // Compute visible world bounds based on current camera transform
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

    // ===== MÉTODOS AUXILIARES (Mantidos do seu código, com ajustes para o cache) =====

    // Gera uma chave única para o cache baseado no ícone e no tamanho solicitado.
    private fun getPoiCacheKey(poi: Action.Poi): String = "${poi.iconRes}:${poi.width}x${poi.height}"

    // Obtém (ou cria) o bitmap já escalado para o tamanho do POI, e calcula as bordas do conteúdo opaco.
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

            // Calcula e armazena o retângulo do conteúdo (pixels com alpha > limiar)
            val contentRect = computeOpaqueBounds(scaled)
            poiBitmapCache[key] = scaled
            poiContentBoundsCache[key] = contentRect
            scaled
        } catch (e: Exception) {
            null
        }
    }

    // Calcula o menor retângulo que contém todos os pixels com alpha acima do limiar.
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

    // Retorna o retângulo do conteúdo do POI em coordenadas do mundo (canvas), considerando o centro em (poi.x, poi.y).
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

    private fun hitTestHandles(hit: Action, point: PointF): Handle? = null // Mantido do seu código original

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