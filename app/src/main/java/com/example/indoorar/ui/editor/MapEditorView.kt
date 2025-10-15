package com.example.indoorar.ui.editor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
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
import androidx.core.graphics.createBitmap

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
    private data class PendingText(val text: String, val size: Float, val color: Int)
    private var pendingText: PendingText? = null
    // Cursor extras
    private var eraserEnabled: Boolean = false

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
    // Preview mode: oculta grid, seleção, handles e quick buttons
    private var previewMode: Boolean = false
    fun setPreviewMode(enabled: Boolean) { previewMode = enabled; invalidate() }
    fun isPreviewMode() = previewMode
    var showBrush = true // Mantido do seu código original
    var showPois = true  // Mantido do seu código original
    private val neutralBackgroundColor = "#C7CCD1".toColorInt()
    private var canvasPatternPaint: Paint? = null

    private fun buildCanvasPatternPaint(): Paint {
        val tile = dp(32f).toInt().coerceAtLeast(8)
        val bmp = createBitmap(tile, tile)
        val c = Canvas(bmp)
        // fundo branco
        c.drawColor(Color.WHITE)
        // bolinha sutil (como canvas de design) – usar cinza claro #E2E5E9
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#E2E5E9".toColorInt()
            style = Paint.Style.FILL
        }
        val r = dp(1.2f)
        // padrão com uma bolinha central e quatro deslocadas para suavizar repetição
        val mid = tile / 2f
        c.drawCircle(mid, mid, r, dotPaint)
        c.drawCircle(mid, r * 2f, r, dotPaint)
        c.drawCircle(r * 2f, mid, r, dotPaint)
        c.drawCircle(tile - r * 2f, mid, r, dotPaint)
        c.drawCircle(mid, tile - r * 2f, r, dotPaint)
        return Paint().apply { shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }

    private fun ensurePattern() {
        if (canvasPatternPaint == null) {
            canvasPatternPaint = buildCanvasPatternPaint()
        }
    }

    // Suavização do snap/alinhamento (ajustado para ficar mais leve)
    private var baseSnapThresholdPx = 64f // alcance de snap (suave) maior para média distância
    private var softSnapStrength = 0.45f  // fração aplicada no snap suave (quando fora do hard)
    private var baseHardSnapThresholdPx = 22f // hard snap quando muito próximo
    // Novo: limiar maior apenas para exibir guias (sem aplicar snap)
    private var baseGuideThresholdPx = 48f

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
    private enum class QuickBtn { DELETE, ROUND, STROKE, DUP, T_INC, T_DEC, RENAME }

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
    // Selection envelope paints (blue theme)
    private val envelopeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#0D99FF".toColorInt()
        alpha = 48 // soft fill
        style = Paint.Style.FILL
    }
    private val envelopeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#0D99FF".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    // ===== EDITORES E GESTOS =====
    private val brushEditor = BrushEditor(this)
    private val shapeEditor = ShapeEditor(this)
    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    // ===== GUIDES DE ALINHAMENTO =====
    private val alignmentGuides = mutableListOf<Pair<PointF, PointF>>()
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#FF9800".toColorInt()
        alpha = 180
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
    }
    // Sobreposição (overlap) entre elementos
    private val overlapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#66FF0000".toColorInt() // vermelho translúcido
        style = Paint.Style.FILL
    }

    // Ícone de lixeira (cacheado)
    private val deleteIconBitmap: Bitmap? by lazy {
        val d = ContextCompat.getDrawable(context, com.example.indoorar.R.drawable.ic_delete_red_24) ?: return@lazy null
        val w = max(1, d.intrinsicWidth)
        val h = max(1, d.intrinsicHeight)
        val bmp = createBitmap(w, h)
        val c = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        bmp
    }

    init {
        // Permite sombras suaves em shapes (necessário para setShadowLayer)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
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

    /** Prepara a View para criar um Texto no próximo toque (mantém ferramenta atual). */
    fun primeForTextCreation(text: String, size: Float, color: Int) {
        pendingText = PendingText(text, size, color)
        // mantém ferramenta BRUSH para coerência com UI; criação acontece no próximo toque
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

        // Removido: grid não depende mais da ferramenta
        // showGrid = (tool == Tool.CURSOR)

        // Limpa estados para evitar bugs entre ferramentas
        draggingObject = null
        if (tool != Tool.POI) {
            pendingPoiResId = null
        }
        invalidate()
    }

    /** Enable/disable eraser behavior in cursor tool. */
    fun setEraserEnabled(enabled: Boolean) {
        eraserEnabled = enabled
        invalidate()
    }

    fun bringSelectedToFront() {
        val sel = actions.firstOrNull { it is Action.Shape && it.selected || it is Action.Poi && it.selected || it is Action.Text && it.selected }
        if (sel != null) {
            actions.remove(sel)
            actions.add(sel)
            invalidate()
        }
    }

    fun sendSelectedToBack() {
        val sel = actions.firstOrNull { it is Action.Shape && it.selected || it is Action.Poi && it.selected || it is Action.Text && it.selected }
        if (sel != null) {
            actions.remove(sel)
            actions.add(0, sel)
            invalidate()
        }
    }

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
        data class PoiState(val poi: Action.Poi, val x: Float, val y: Float, val width: Float, val height: Float, val rotation: Float) : ActionState()
        data class ShapeState(val shape: Action.Shape, val start: PointF, val end: PointF, val rotation: Float) : ActionState()
        data class TextState(val text: Action.Text, val x: Float, val y: Float, val size: Float) : ActionState()
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
        is Action.Poi -> ActionState.PoiState(action, action.x, action.y, action.width, action.height, action.rotation)
        is Action.Shape -> ActionState.ShapeState(action, PointF(action.start.x, action.start.y), PointF(action.end.x, action.end.y), action.rotation)
        is Action.Text -> ActionState.TextState(action, action.x, action.y, action.sizeSp)
        else -> null
    }
    private fun applyState(state: ActionState) {
        when (state) {
            is ActionState.PoiState -> { state.poi.x = state.x; state.poi.y = state.y; state.poi.width = state.width; state.poi.height = state.height; state.poi.rotation = state.rotation }
            is ActionState.ShapeState -> {
                state.shape.start.x = state.start.x; state.shape.start.y = state.start.y
                state.shape.end.x = state.end.x; state.shape.end.y = state.end.y
                state.shape.rotation = state.rotation
            }
            is ActionState.TextState -> { state.text.x = state.x; state.text.y = state.y; state.text.sizeSp = state.size }
        }
    }
    private fun statesEqual(a: ActionState?, b: ActionState?): Boolean {
        if (a == null || b == null || a::class != b::class) return false
        return when (a) {
            is ActionState.PoiState -> { val bb = b as ActionState.PoiState; a.x == bb.x && a.y == bb.y && a.width == bb.width && a.height == bb.height && a.rotation == bb.rotation }
            is ActionState.ShapeState -> { val bb = b as ActionState.ShapeState; a.start.x == bb.start.x && a.start.y == bb.start.y && a.end.x == bb.end.x && a.end.y == bb.end.y && a.rotation == bb.rotation }
            is ActionState.TextState -> { val bb = b as ActionState.TextState; a.x == bb.x && a.y == bb.y && a.size == bb.size }
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
        is Action.Text -> {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(action.sizeSp) }
            val fm = p.fontMetrics
            val width = p.measureText(action.text)
            val left = action.x
            val top = action.y + fm.ascent
            val right = left + width
            val bottom = action.y + fm.descent
            RectF(left, top, right, bottom)
        }
        else -> null
    }

    // Get center and rotation for actions that support rotation
    private fun getCenterAndRotation(action: Action): Pair<PointF, Float>? = when (action) {
        is Action.Shape -> {
            val b = getActionBounds(action) ?: return null
            Pair(PointF(b.centerX(), b.centerY()), action.rotation)
        }
        is Action.Poi -> Pair(PointF(action.x, action.y), action.rotation)
        else -> null
    }

    // Map a world point into the object's local (unrotated) space
    private fun worldToLocalForAction(action: Action, point: PointF): PointF {
        val cr = getCenterAndRotation(action)
        if (cr == null) return PointF(point.x, point.y)
        val (c, rot) = cr
        if (rot == 0f) return PointF(point.x, point.y)
        val m = Matrix().apply { setRotate(-rot, c.x, c.y) }
        val pts = floatArrayOf(point.x, point.y)
        m.mapPoints(pts)
        return PointF(pts[0], pts[1])
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
            // Blue envelope-style handles: blue ring with white core for contrast
            val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = "#0D99FF".toColorInt()
                style = Paint.Style.STROKE
                strokeWidth = r * 0.55f
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = "#0D99FF".toColorInt()
                alpha = 60
                style = Paint.Style.FILL
            }
            val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            // Base blue filled circle with a ring and white center
            canvas.drawCircle(p.x, p.y, r, fill)
            canvas.drawCircle(p.x, p.y, r, outer)
            canvas.drawCircle(p.x, p.y, r * 0.45f, core)
        }
    }

    private fun hitTestHandles(hit: Action, point: PointF): Handle? {
        val bounds = getActionBounds(hit) ?: return null
        val r = max(handleRadiusWorld() * 1.5f, dp(16f) / max(0.001f, scale))
        // Transform point into local (unrotated) space before testing against handle positions
        val localPoint = worldToLocalForAction(hit, point)
        val positions = getHandlePositions(bounds)
        return positions.entries.firstOrNull { (_, pos) ->
            val dx = localPoint.x - pos.x
            val dy = localPoint.y - pos.y
            dx * dx + dy * dy <= r * r
        }?.key
    }

    private fun drawSelectionEnvelope(canvas: Canvas, bounds: RectF) {
        // Blue rounded rectangle envelope with crisp stroke only (no fill)
        val corner = max(dp(6f) / max(0.001f, scale), min(bounds.width(), bounds.height()) * 0.12f)
        val prevStroke = envelopeStrokePaint.strokeWidth
        envelopeStrokePaint.strokeWidth = dp(2.5f) / max(0.001f, scale)
        // No fill: just outline
        canvas.drawRoundRect(bounds, corner, corner, envelopeStrokePaint)
        envelopeStrokePaint.strokeWidth = prevStroke
    }

    // ===== TOUCH =====

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) return true
        if (previewMode) { // somente pan/zoom no preview
            gestureDetector.onTouchEvent(event)
            return true
        }

        val world = screenToWorld(event.x, event.y)

        // Criação de texto
        if (pendingText != null) {
            if (event.action == MotionEvent.ACTION_UP) {
                val p = pendingText!!
                addText(world.x, world.y, p.text, p.size, p.color)
                pendingText = null
                setTool(Tool.CURSOR)
            }
            return true
        }

        // Criação de POI
        if (currentTool == Tool.POI) {
            if (event.action == MotionEvent.ACTION_UP) {
                pendingPoiResId?.let { resId -> addPoi(world.x, world.y, resId) }
                setTool(Tool.CURSOR)
            }
            return true
        }

        if (currentTool != Tool.CURSOR) {
            return when (currentTool) {
                Tool.BRUSH -> brushEditor.onTouch(event)
                Tool.FORMAS -> shapeEditor.onTouch(event)
                else -> super.onTouchEvent(event)
            }
        }

        // Cursor tool: eraser support (delete on tap without confirmation)
        if (eraserEnabled) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val hit = hitTestObjects(world)
                    if (hit != null) {
                        val idx = actions.indexOf(hit)
                        if (idx >= 0) {
                            actions.removeAt(idx)
                            pushOp(DeleteOp(hit, idx))
                            selectionListener?.onShapeDeselected()
                            invalidate()
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {}
            }
            // fall through to allow panning/scroll if nothing hit
        }

        if (draggingObject == null) { gestureDetector.onTouchEvent(event) }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downXScreen = event.x
                downYScreen = event.y
                hasMovedBeyondSlop = false

                // Botões rápidos (delete/round/stroke/dup) têm prioridade
                if (!eraserEnabled && handleQuickButtonsTap(world)) return true

                // Se já existe um selecionado, verifica clique na lixeirinha primeiro
                val currentlySelected = actions.firstOrNull { act ->
                    when (act) {
                        is Action.Shape -> act.selected
                        is Action.Poi -> act.selected
                        is Action.Text -> act.selected
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
                    // Testa hit nos handles do item selecionado (apenas Shapes; sem handles para Texto e POI)
                    if (selectedAct is Action.Shape) {
                        val handle = hitTestHandles(selectedAct, world)
                        if (handle != null) {
                            activeHandle = handle
                            draggingObject = selectedAct
                            dragSnapshotBefore = snapshotOf(draggingObject)
                            // Guardar bounds iniciais no espaço local (não-rotacionado) para resize coerente
                            resizeInitialBounds = getActionBounds(selectedAct)
                            // mantém seleção como está
                            invalidate()
                            return true
                        }
                    }
                }

                draggingObject = hitTestObjects(world)
                actions.forEach { action ->
                    val isSelected = (action == draggingObject)
                    when (action) {
                        is Action.Shape -> action.selected = isSelected
                        is Action.Poi -> action.selected = isSelected
                        is Action.Text -> action.selected = isSelected
                        else -> {}
                    }
                }

                // snapshot para possível MoveOp
                dragSnapshotBefore = snapshotOf(draggingObject)

                draggingObject?.let { obj ->
                    when (obj) {
                        is Action.Poi -> { touchOffsetX = world.x - obj.x; touchOffsetY = world.y - obj.y }
                        is Action.Shape -> { touchOffsetX = world.x - obj.start.x; touchOffsetY = world.y - obj.start.y }
                        is Action.Text -> { touchOffsetX = world.x - obj.x; touchOffsetY = world.y - obj.y }
                        is Action.BrushStroke -> {}
                    }
                }

                // Não esconder o painel ao iniciar toque
                // selectionListener?.onShapeDeselected()

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
                            // Redimensionamento (usa coordenadas locais alinhadas à rotação do objeto)
                            val init = resizeInitialBounds ?: getActionBounds(obj)
                            if (init != null) {
                                val minSize = dp(12f) / max(0.001f, scale)
                                // Transformar ponto de toque atual para espaço local do objeto
                                val local = worldToLocalForAction(obj, world)
                                var left = init.left
                                var top = init.top
                                var right = init.right
                                var bottom = init.bottom
                                when (activeHandle) {
                                    Handle.LEFT, Handle.TOP_LEFT, Handle.BOTTOM_LEFT -> { left = local.x }
                                    else -> {}
                                }
                                when (activeHandle) {
                                    Handle.RIGHT, Handle.TOP_RIGHT, Handle.BOTTOM_RIGHT -> { right = local.x }
                                    else -> {}
                                }
                                when (activeHandle) {
                                    Handle.TOP, Handle.TOP_LEFT, Handle.TOP_RIGHT -> { top = local.y }
                                    else -> {}
                                }
                                when (activeHandle) {
                                    Handle.BOTTOM, Handle.BOTTOM_LEFT, Handle.BOTTOM_RIGHT -> { bottom = local.y }
                                    else -> {}
                                }
                                // Normaliza e aplica tamanho mínimo (no espaço local)
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
                                        // POI não possui handles dedicados; mantemos comportamento anterior
                                    }
                                    else -> {}
                                }
                                invalidate()
                            }
                        } else {
                            // Movimento normal
                            when (obj) {
                                is Action.Poi -> { obj.x = world.x - touchOffsetX; obj.y = world.y - touchOffsetY; selectionListener?.onShapeSelected(poiToProperties(obj)) }
                                is Action.Shape -> {
                                    val width = obj.end.x - obj.start.x
                                    val height = obj.end.y - obj.start.y
                                    obj.start.x = world.x - touchOffsetX
                                    obj.start.y = world.y - touchOffsetY
                                    obj.end.x = obj.start.x + width
                                    obj.end.y = obj.start.y + height
                                    selectionListener?.onShapeSelected(shapeToProperties(obj))
                                }
                                is Action.Text -> { obj.x = world.x - touchOffsetX; obj.y = world.y - touchOffsetY }
                                is Action.BrushStroke -> {}
                            }

                            // Alinhamento e snap (guia com alcance maior; snap com alcance maior e hard+soft)
                            alignmentGuides.clear()
                            val snapThreshold = baseSnapThresholdPx / max(0.001f, scale)
                            val hardThreshold = baseHardSnapThresholdPx / max(0.001f, scale)
                            val guideThreshold = baseGuideThresholdPx / max(0.001f, scale)
                            val proximityPx = 900f
                            val proximityWorld = proximityPx / max(0.001f, scale)

                            // Bounds do item arrastado
                            val draggedBounds = when (obj) {
                                is Action.Poi -> getActionBounds(obj)
                                is Action.Shape -> RectF(obj.start.x, obj.start.y, obj.end.x, obj.end.y)
                                is Action.Text -> getActionBounds(obj)
                                else -> null
                            }
                            val dLeft = draggedBounds?.left ?: 0f
                            val dRight = draggedBounds?.right ?: 0f
                            val dTop = draggedBounds?.top ?: 0f
                            val dBottom = draggedBounds?.bottom ?: 0f
                            val dCx = draggedBounds?.centerX() ?: 0f
                            val dCy = draggedBounds?.centerY() ?: 0f

                            data class AxisSnap(val delta: Float, val guide: Float)
                            data class JoinSnap(val dx: AxisSnap, val dy: AxisSnap, val dist2: Float)
                            var bestJoin: JoinSnap? = null
                            var bestX: AxisSnap? = null
                            var bestY: AxisSnap? = null

                            fun updBestX(c: AxisSnap) { if (bestX == null || abs(c.delta) < abs(bestX!!.delta)) bestX = c }
                            fun updBestY(c: AxisSnap) { if (bestY == null || abs(c.delta) < abs(bestY!!.delta)) bestY = c }

                            actions.filter { it != obj }.forEach { other ->
                                val ob = getActionBounds(other) ?: return@forEach
                                val ocx = ob.centerX(); val ocy = ob.centerY()

                                // Candidatos para X
                                val candX = listOf(
                                    AxisSnap(ocx - dCx, ocx),
                                    AxisSnap(ob.left - dLeft, ob.left),
                                    AxisSnap(ob.right - dLeft, ob.right),
                                    AxisSnap(ob.left - dRight, ob.left),
                                    AxisSnap(ob.right - dRight, ob.right)
                                ).minByOrNull { abs(it.delta) }!!

                                // Candidatos para Y
                                val candY = listOf(
                                    AxisSnap(ocy - dCy, ocy),
                                    AxisSnap(ob.top - dTop, ob.top),
                                    AxisSnap(ob.bottom - dTop, ob.bottom),
                                    AxisSnap(ob.top - dBottom, ob.top),
                                    AxisSnap(ob.bottom - dBottom, ob.bottom)
                                ).minByOrNull { abs(it.delta) }!!

                                val dxC = ocx - dCx
                                val dyC = ocy - dCy
                                val far = dxC*dxC + dyC*dyC > proximityWorld*proximityWorld

                                if (!far) {
                                    // Join candidate (considera snap por eixo)
                                    val dist2 = candX.delta * candX.delta + candY.delta * candY.delta
                                    if (bestJoin == null || dist2 < bestJoin!!.dist2) {
                                        bestJoin = JoinSnap(candX, candY, dist2)
                                    }
                                }

                                // Atualiza melhores individuais com limiar de guia (alcance maior)
                                if (abs(candX.delta) < guideThreshold) updBestX(candX)
                                if (abs(candY.delta) < guideThreshold) updBestY(candY)
                            }

                            if (bestJoin != null) {
                                val j = bestJoin!!
                                val joinX = j.dx
                                val joinY = j.dy
                                val adx = abs(joinX.delta)
                                val ady = abs(joinY.delta)

                                // Aplica hard snap se muito perto; senão soft snap dentro do snapThreshold
                                if (adx < hardThreshold) {
                                    applySnapX(joinX.delta)
                                } else if (adx < snapThreshold) {
                                    applySnapX(joinX.delta * softSnapStrength)
                                }
                                if (ady < hardThreshold) {
                                    applySnapY(joinY.delta)
                                } else if (ady < snapThreshold) {
                                    applySnapY(joinY.delta * softSnapStrength)
                                }

                                // Guias se dentro do alcance de guia
                                if (adx < guideThreshold) alignmentGuides.add(Pair(PointF(joinX.guide, 0f), PointF(joinX.guide, height.toFloat())))
                                if (ady < guideThreshold) alignmentGuides.add(Pair(PointF(0f, joinY.guide), PointF(width.toFloat(), joinY.guide)))
                            } else {
                                // Individuais por eixo
                                bestX?.let { x ->
                                    val adx = abs(x.delta)
                                    if (adx < hardThreshold) {
                                        applySnapX(x.delta)
                                    } else if (adx < snapThreshold) {
                                        applySnapX(x.delta * softSnapStrength)
                                    }
                                    if (adx < guideThreshold) alignmentGuides.add(Pair(PointF(x.guide, 0f), PointF(x.guide, height.toFloat())))
                                }
                                bestY?.let { y ->
                                    val ady = abs(y.delta)
                                    if (ady < hardThreshold) {
                                        applySnapY(y.delta)
                                    } else if (ady < snapThreshold) {
                                        applySnapY(y.delta * softSnapStrength)
                                    }
                                    if (ady < guideThreshold) alignmentGuides.add(Pair(PointF(0f, y.guide), PointF(width.toFloat(), y.guide)))
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
                        // Clique em objeto: mostrar painel (somente para Shape/Poi)
                        when (it) {
                            is Action.Shape -> selectionListener?.onShapeSelected(shapeToProperties(it))
                            is Action.Poi -> selectionListener?.onShapeSelected(poiToProperties(it))
                            else -> {}
                        }
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
        if (previewMode) {
            // modo preview: plano cinza neutro
            canvas.drawColor(neutralBackgroundColor)
        } else {
            // modo edição: fundo canvas
            ensurePattern()
            canvasPatternPaint?.let { canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), it) }
        }
        // Agora conteúdo do mundo (aplica transformações)
        canvas.withTranslation(offsetX, offsetY) {
            scale(scale, scale)
            if (!previewMode && showGrid) drawGrid(this)
            if (!previewMode) alignmentGuides.forEach { (p1, p2) -> drawLine(p1.x, p1.y, p2.x, p2.y, guidePaint) }
            updateAutoCornerRadii()
            drawActions(this)
            if (!previewMode && currentTool == Tool.BRUSH) brushEditor.onDrawTemp(this)
            if (!previewMode && currentTool == Tool.FORMAS) shapeEditor.onDrawTemp(this)
            if (!previewMode) drawOverlapOverlay(this)
        }
    }

    private fun drawOverlapOverlay(canvas: Canvas) {
        // Foca no objeto em movimento, senão o selecionado
        val ref = draggingObject ?: actions.firstOrNull { when (it) { is Action.Shape -> it.selected; is Action.Poi -> it.selected; is Action.Text -> it.selected; else -> false } } ?: return
        val refBounds = getActionBounds(ref) ?: return

        // Considera apenas vizinhos próximos para evitar poluição visual
        val proximityPx = 450f
        val proximityWorld = proximityPx / max(0.001f, scale)
        val refCenter = PointF(refBounds.centerX(), refBounds.centerY())

        actions.asSequence()
            .filter { it != ref }
            .forEach { other ->
                val ob = getActionBounds(other) ?: return@forEach
                val ocx = (ob.left + ob.right) / 2f
                val ocy = (ob.top + ob.bottom) / 2f
                val dx = ocx - refCenter.x
                val dy = ocy - refCenter.y
                if (dx*dx + dy*dy <= proximityWorld*proximityWorld) {
                    val inter = RectF()
                    if (inter.setIntersect(refBounds, ob) && inter.width() > 0f && inter.height() > 0f) {
                        canvas.drawRect(inter, overlapPaint)
                    }
                }
            }
    }

    private fun drawActions(canvas: Canvas) {
        actions.forEach { action ->
            when (action) {
                is Action.BrushStroke -> {
                    if (previewMode) return@forEach
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = action.color
                        style = Paint.Style.STROKE
                        strokeWidth = action.strokeWidth
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                    }
                    val path = Path().apply {
                        moveTo(action.points.first().x, action.points.first().y)
                        for (i in 1 until action.points.size) lineTo(action.points[i].x, action.points[i].y)
                    }
                    canvas.drawPath(path, paint)
                }
                is Action.Shape -> {
                    val left = min(action.start.x, action.end.x)
                    val top = min(action.start.y, action.end.y)
                    val right = max(action.start.x, action.end.x)
                    val bottom = max(action.start.y, action.end.y)
                    val w = right - left
                    val h = bottom - top
                    // Usa a cor da própria shape para fill; stroke segue estilo
                    val strokeColor = if (action.isWalkable) Color.WHITE else "#C5C8CC".toColorInt()
                    val baseStrokeDp = if (action.isWalkable) 2f else 1f
                    val strokeWidthPx = (dp(baseStrokeDp) / max(0.001f, scale)).coerceIn(0.6f, 4f)
                    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = action.fillColor
                        style = Paint.Style.FILL
                        if (action.isWalkable) {
                            val shadowR = (dp(6f) / max(0.6f, scale)).coerceAtLeast(2f)
                            setShadowLayer(shadowR, dp(2f)/scale, dp(2f)/scale, 0x44000000)
                        }
                    }
                    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = strokeColor
                        style = Paint.Style.STROKE
                        strokeWidth = strokeWidthPx
                        strokeCap = Paint.Cap.SQUARE
                    }
                    val cx = (left + right) / 2f
                    val cy = (top + bottom) / 2f
                    canvas.withSave {
                        rotate(action.rotation, cx, cy)
                        when (action.type) {
                            Action.ShapeType.RECTANGLE, Action.ShapeType.SQUARE -> {
                                val r = RectF(left, top, right, bottom)
                                val radius = action.cornerRadius
                                if (radius > 0f && action.edgeTop && action.edgeRight && action.edgeBottom && action.edgeLeft) {
                                    canvas.drawRoundRect(r, radius, radius, fillPaint)
                                } else {
                                    canvas.drawRect(r, fillPaint)
                                }
                                if (action.strokeEnabled) {
                                    val allEdges = action.edgeTop && action.edgeRight && action.edgeBottom && action.edgeLeft
                                    if (allEdges) {
                                        if (radius > 0f) {
                                            canvas.drawRoundRect(r, radius, radius, strokePaint)
                                        } else {
                                            canvas.drawRect(r, strokePaint)
                                        }
                                    } else {
                                        if (action.edgeTop) canvas.drawLine(left, top, right, top, strokePaint)
                                        if (action.edgeRight) canvas.drawLine(right, top, right, bottom, strokePaint)
                                        if (action.edgeBottom) canvas.drawLine(right, bottom, left, bottom, strokePaint)
                                        if (action.edgeLeft) canvas.drawLine(left, bottom, left, top, strokePaint)
                                    }
                                }
                            }
                            Action.ShapeType.CIRCLE -> {
                                val radius = min(w, h) / 2f
                                canvas.drawCircle(cx, cy, radius, fillPaint)
                                if (action.strokeEnabled) canvas.drawCircle(cx, cy, radius, strokePaint)
                            }
                            Action.ShapeType.TRIANGLE -> {
                                val path = Path().apply {
                                    moveTo((left + right)/2f, top)
                                    lineTo(left, bottom)
                                    lineTo(right, bottom)
                                    close()
                                }
                                canvas.drawPath(path, fillPaint)
                                if (action.strokeEnabled) canvas.drawPath(path, strokePaint)
                            }
                            Action.ShapeType.LINE -> {
                                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = strokeColor
                                    style = Paint.Style.STROKE
                                    strokeCap = Paint.Cap.ROUND
                                    strokeWidth = ((if (action.isWalkable) dp(2.5f) else dp(2f)) / max(0.001f, scale)).coerceIn(0.6f, 4.5f)
                                }
                                canvas.drawLine(action.start.x, action.start.y, action.end.x, action.end.y, p)
                            }
                        }
                    }
                    if (!previewMode && action.selected) {
                        val b = RectF(left, top, right, bottom)
                        // Desenha envelope e handles rotacionados em torno do centro
                        canvas.withSave {
                            rotate(action.rotation, cx, cy)
                            drawSelectionEnvelope(canvas, b)
                            drawHandles(canvas, b)
                        }
                        // Botões rápidos permanecem não-rotacionados (hit-test consistente)
                        drawQuickButtons(canvas, action, b)
                    }
                }
                is Action.Poi -> {
                    val bmp = getBitmapForPoi(action)
                    if (bmp != null) {
                        val left = action.x - bmp.width / 2f
                        val top = action.y - bmp.height / 2f
                        canvas.withSave {
                            rotate(action.rotation, action.x, action.y)
                            canvas.drawBitmap(bmp, left, top, null)
                        }
                    }
                    if (!previewMode && action.selected) {
                        val styleKey = getPoiCacheKey(action) + "::pinV1"
                        val bmp = poiBitmapCache[styleKey] ?: getBitmapForPoi(action)
                        val content = poiContentBoundsCache[styleKey]
                        // Se temos bounds precisos do conteúdo, usa-os; caso contrário, fallback para bounds do POI
                        val b = if (bmp != null && content != null) {
                            // Retângulo do conteúdo no espaço local (centrado no POI)
                            val localLeft = -bmp.width / 2f + content.left
                            val localTop = -bmp.height / 2f + content.top
                            val localRight = localLeft + content.width()
                            val localBottom = localTop + content.height()
                            RectF(action.x + localLeft, action.y + localTop, action.x + localRight, action.y + localBottom)
                        } else getActionBounds(action)
                        if (b != null) {
                            // Desenha envelope rotacionado em torno do centro do POI
                            canvas.withSave {
                                rotate(action.rotation, action.x, action.y)
                                drawSelectionEnvelope(canvas, b)
                                // POI não exibe handles
                            }
                            // Quick buttons não-rotacionados
                            drawQuickButtons(canvas, action, b)
                        }
                    }
                }
                is Action.Text -> {
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = action.color; textSize = dp(action.sizeSp) }
                    canvas.drawText(action.text, action.x, action.y, p)
                    if (!previewMode && action.selected) getActionBounds(action)?.let { b ->
                        drawSelectionEnvelope(canvas, b)
                        drawQuickButtons(canvas, action, b)
                    }
                }
            }
        }
    }

    private fun updateAutoCornerRadii() {
        val epsilon = 3f
        val candidates = actions.filterIsInstance<Action.Shape>().filter {
            it.isWalkable && !it.manualCornerModified && (it.type == Action.ShapeType.RECTANGLE || it.type == Action.ShapeType.SQUARE)
        }
        candidates.forEach { shape ->
            val left = min(shape.start.x, shape.end.x)
            val top = min(shape.start.y, shape.end.y)
            val right = max(shape.start.x, shape.end.x)
            val bottom = max(shape.start.y, shape.end.y)
            var contactLeft = false; var contactRight = false; var contactTop = false; var contactBottom = false
            var contactLeftLoose = false; var contactRightLoose = false; var contactTopLoose = false; var contactBottomLoose = false
            candidates.forEach { other ->
                if (other === shape) return@forEach
                val oL = min(other.start.x, other.end.x)
                val oT = min(other.start.y, other.end.y)
                val oR = max(other.start.x, other.end.x)
                val oB = max(other.start.y, other.end.y)
                val heightOverlap = (min(bottom, oB) - max(top, oT))
                val widthOverlap = (min(right, oR) - max(left, oL))
                val verticalOverlapStrict = heightOverlap > (min(bottom-top, oB-oT) * 0.18f)
                val horizontalOverlapStrict = widthOverlap > (min(right-left, oR-oL) * 0.18f)
                val verticalOverlapLoose = heightOverlap > (min(bottom-top, oB-oT) * 0.05f)
                val horizontalOverlapLoose = widthOverlap > (min(right-left, oR-oL) * 0.05f)
                if (verticalOverlapStrict) {
                    if (abs(oR - left) <= epsilon) contactLeft = true
                    if (abs(oL - right) <= epsilon) contactRight = true
                }
                if (horizontalOverlapStrict) {
                    if (abs(oB - top) <= epsilon) contactTop = true
                    if (abs(oT - bottom) <= epsilon) contactBottom = true
                }
                if (verticalOverlapLoose) {
                    if (abs(oR - left) <= epsilon) contactLeftLoose = true
                    if (abs(oL - right) <= epsilon) contactRightLoose = true
                }
                if (horizontalOverlapLoose) {
                    if (abs(oB - top) <= epsilon) contactTopLoose = true
                    if (abs(oT - bottom) <= epsilon) contactBottomLoose = true
                }
            }
            val width = right - left
            val height = bottom - top
            val minDim = min(width, height)
            // Ajuste: agora também arredonda em junções em T (versão 'loose')
            val hasStrict = (contactLeft || contactRight) && (contactTop || contactBottom)
            val hasT = (contactLeftLoose || contactRightLoose) && (contactTopLoose || contactBottomLoose)
            val externalEdgesCount = listOf(shape.edgeTop, shape.edgeRight, shape.edgeBottom, shape.edgeLeft).count { it }
            val shouldRound = (hasStrict || hasT) && externalEdgesCount >= 2 && minDim > dp(12f)/max(0.001f, scale)
            shape.cornerRadius = if (shouldRound) (minDim * 0.22f).coerceAtMost(minDim/2f) else 0f
        }
    }

    // ===== QUICK BUTTONS SUPPORT (hit test only) =====
    private fun getQuickButtonRects(bounds: RectF, buttons: List<QuickBtn>): Map<QuickBtn, RectF> {
        val size = dp(28f) / max(0.001f, scale)
        val spacing = dp(6f) / max(0.001f, scale)
        val map = mutableMapOf<QuickBtn, RectF>()
        var xRight = bounds.right
        val top = bounds.top - spacing - size
        buttons.forEach { btn ->
            val left = xRight - size
            val r = RectF(left, top, xRight, top + size)
            map[btn] = r
            xRight = left - spacing
        }
        return map
    }

    private fun drawQuickButtons(canvas: Canvas, action: Action, bounds: RectF) {
        if (previewMode) return
        val buttons: List<QuickBtn> = when (action) {
            is Action.Text -> listOf(QuickBtn.DUP, QuickBtn.T_INC, QuickBtn.T_DEC, QuickBtn.RENAME, QuickBtn.DELETE)
            is Action.Shape -> listOf(QuickBtn.DUP, QuickBtn.STROKE, QuickBtn.ROUND, QuickBtn.DELETE)
            is Action.Poi -> listOf(QuickBtn.DUP, QuickBtn.DELETE)
            else -> emptyList()
        }
        val rects = getQuickButtonRects(bounds, buttons)
        rects.forEach { (btn, r) ->
            // fundo
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL; alpha = 235 }
            val rx = r.width() * 0.22f
            val ry = r.height() * 0.22f
            canvas.drawRoundRect(RectF(r.left, r.top, r.right, r.bottom), rx, ry, bg)
            // borda leve
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = r.width()*0.06f }
            canvas.drawRoundRect(RectF(r.left, r.top, r.right, r.bottom), rx, ry, border)

            when (btn) {
                QuickBtn.DELETE -> {
                    val icon = deleteIconBitmap
                    if (icon != null) canvas.drawBitmap(icon, null, r, null) else drawXIcon(canvas, r, Color.RED)
                }
                QuickBtn.ROUND -> drawRoundIcon(canvas, r)
                QuickBtn.STROKE -> drawStrokeIcon(canvas, r, (action as? Action.Shape)?.strokeEnabled ?: true)
                QuickBtn.DUP -> drawDuplicateIcon(canvas, r)
                QuickBtn.T_INC -> drawTextIncIcon(canvas, r)
                QuickBtn.T_DEC -> drawTextDecIcon(canvas, r)
                QuickBtn.RENAME -> drawRenameIcon(canvas, r)
            }
        }
    }

    private fun drawXIcon(canvas: Canvas, r: RectF, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = r.width()*0.12f }
        canvas.drawLine(r.left + r.width()*0.2f, r.top + r.height()*0.2f, r.right - r.width()*0.2f, r.bottom - r.height()*0.2f, p)
        canvas.drawLine(r.right - r.width()*0.2f, r.top + r.height()*0.2f, r.left + r.width()*0.2f, r.bottom - r.height()*0.2f, p)
    }

    private fun drawRoundIcon(canvas: Canvas, r: RectF) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = "#0D99FF".toColorInt(); style = Paint.Style.STROKE; strokeWidth = r.width()*0.12f }
        val inset = r.width()*0.2f
        canvas.drawRoundRect(RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset), r.width()*0.2f, r.height()*0.2f, p)
    }

    private fun drawStrokeIcon(canvas: Canvas, r: RectF, enabled: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (enabled) Color.DKGRAY else Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = r.width()*0.12f }
        val inset = r.width()*0.22f
        canvas.drawRect(RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset), p)
        if (!enabled) {
            val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = r.width()*0.12f }
            canvas.drawLine(r.left + inset, r.bottom - inset, r.right - inset, r.top + inset, cut)
        }
    }

    private fun drawDuplicateIcon(canvas: Canvas, r: RectF) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; style = Paint.Style.STROKE; strokeWidth = r.width()*0.10f }
        val inset = r.width()*0.22f
        val back = RectF(r.left + inset*0.5f, r.top + inset*0.5f, r.right - inset*1.4f, r.bottom - inset*1.4f)
        val front = RectF(r.left + inset*0.9f, r.top + inset*0.9f, r.right - inset*0.5f, r.bottom - inset*0.5f)
        canvas.drawRect(back, p)
        canvas.drawRect(front, p)
    }

    private fun drawTextIncIcon(canvas: Canvas, r: RectF) {
        // A+ icon, high-contrast
        val aPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; style = Paint.Style.STROKE; strokeWidth = r.width()*0.12f; strokeCap = Paint.Cap.ROUND }
        val symPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = "#0D99FF".toColorInt(); style = Paint.Style.STROKE; strokeWidth = r.width()*0.14f; strokeCap = Paint.Cap.ROUND }
        val base = r.bottom - r.height()*0.15f
        val midX = r.left + r.width()*0.28f
        val leftX = r.left + r.width()*0.10f
        val rightX = r.left + r.width()*0.46f
        val apexY = r.top + r.height()*0.22f
        canvas.drawLine(leftX, base, midX, apexY, aPaint)
        canvas.drawLine(midX, apexY, rightX, base, aPaint)
        canvas.drawLine(leftX + (rightX-leftX)*0.22f, base - (base-apexY)*0.45f, rightX - (rightX-leftX)*0.22f, base - (base-apexY)*0.45f, aPaint)
        val cX = r.right - r.width()*0.28f
        val cY = r.top + r.height()*0.52f
        val len = min(r.width(), r.height()) * 0.26f
        canvas.drawLine(cX - len/2f, cY, cX + len/2f, cY, symPaint)
        canvas.drawLine(cX, cY - len/2f, cX, cY + len/2f, symPaint)
    }

    private fun drawTextDecIcon(canvas: Canvas, r: RectF) {
        // A- icon, high-contrast
        val aPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; style = Paint.Style.STROKE; strokeWidth = r.width()*0.12f; strokeCap = Paint.Cap.ROUND }
        val symPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = "#0D99FF".toColorInt(); style = Paint.Style.STROKE; strokeWidth = r.width()*0.14f; strokeCap = Paint.Cap.ROUND }
        val base = r.bottom - r.height()*0.15f
        val midX = r.left + r.width()*0.28f
        val leftX = r.left + r.width()*0.10f
        val rightX = r.left + r.width()*0.46f
        val apexY = r.top + r.height()*0.22f
        canvas.drawLine(leftX, base, midX, apexY, aPaint)
        canvas.drawLine(midX, apexY, rightX, base, aPaint)
        canvas.drawLine(leftX + (rightX-leftX)*0.22f, base - (base-apexY)*0.45f, rightX - (rightX-leftX)*0.22f, base - (base-apexY)*0.45f, aPaint)
        val cX = r.right - r.width()*0.28f
        val cY = r.top + r.height()*0.52f
        val len = min(r.width(), r.height()) * 0.26f
        canvas.drawLine(cX - len/2f, cY, cX + len/2f, cY, symPaint)
    }

    private fun drawRenameIcon(canvas: Canvas, r: RectF) {
        // Simple pencil/edit icon
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; style = Paint.Style.STROKE; strokeWidth = r.width()*0.10f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val inset = r.width()*0.20f
        val x1 = r.left + inset
        val y1 = r.bottom - inset
        val x2 = r.right - inset*0.6f
        val y2 = r.top + inset*0.6f
        canvas.drawLine(x1, y1, x2, y2, p)
        canvas.drawLine(x2, y2, x2 - inset*0.4f, y2 + inset*0.15f, p)
        canvas.drawLine(x1, y1, x1 + inset*0.25f, y1 - inset*0.18f, p)
    }

    private fun handleQuickButtonsTap(world: PointF): Boolean {
        if (previewMode) return false
        val selectedAct = actions.firstOrNull { when (it) { is Action.Shape -> it.selected; is Action.Poi -> it.selected; is Action.Text -> it.selected; else -> false } } ?: return false
        val bounds = getActionBounds(selectedAct) ?: return false
        val buttons: List<QuickBtn> = when (selectedAct) {
            is Action.Text -> listOf(QuickBtn.DUP, QuickBtn.T_INC, QuickBtn.T_DEC, QuickBtn.RENAME, QuickBtn.DELETE)
            is Action.Shape -> listOf(QuickBtn.DUP, QuickBtn.STROKE, QuickBtn.ROUND, QuickBtn.DELETE)
            is Action.Poi -> listOf(QuickBtn.DUP, QuickBtn.DELETE)
            else -> emptyList()
        }
        val map = getQuickButtonRects(bounds, buttons)
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
                    val w = bounds.width(); val h = bounds.height()
                    val target = (min(w, h) * 0.2f).coerceAtLeast(dp(6f) / max(0.001f, scale))
                    selectedAct.cornerRadius = if (selectedAct.cornerRadius <= 0f) target else 0f
                    selectedAct.manualCornerModified = true
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
                        Action.Shape(
                            start = PointF(s.start.x + offset, s.start.y + offset),
                            end = PointF(s.end.x + offset, s.end.y + offset),
                            selected = false,
                            fillColor = s.fillColor,
                            rotation = s.rotation,
                            isWalkable = s.isWalkable,
                            nome = s.nome,
                            type = s.type,
                            cornerRadius = s.cornerRadius,
                            strokeEnabled = s.strokeEnabled,
                            manualCornerModified = s.manualCornerModified,
                            edgeTop = s.edgeTop,
                            edgeRight = s.edgeRight,
                            edgeBottom = s.edgeBottom,
                            edgeLeft = s.edgeLeft
                        )
                    }
                    is Action.Poi -> {
                        val p = selectedAct
                        Action.Poi(
                            x = p.x + offset,
                            y = p.y + offset,
                            width = p.width,
                            height = p.height,
                            iconRes = p.iconRes,
                            rotation = p.rotation
                        )
                    }
                    is Action.Text -> {
                        val t = selectedAct
                        Action.Text(
                            x = t.x + offset,
                            y = t.y + offset,
                            text = t.text,
                            sizeSp = t.sizeSp,
                            color = t.color,
                            selected = false
                        )
                    }
                    else -> null
                }
                if (copy != null) { addAction(copy); invalidate() }
            }
            QuickBtn.T_INC -> {
                if (selectedAct is Action.Text) {
                    selectedAct.sizeSp = (selectedAct.sizeSp + 2f).coerceAtMost(72f)
                    invalidate()
                }
            }
            QuickBtn.T_DEC -> {
                if (selectedAct is Action.Text) {
                    selectedAct.sizeSp = (selectedAct.sizeSp - 2f).coerceAtLeast(8f)
                    invalidate()
                }
            }
            QuickBtn.RENAME -> {
                if (selectedAct is Action.Text) {
                    val input = EditText(context).apply {
                        setText(selectedAct.text)
                        setSelection(text.length)
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        maxLines = 2
                    }
                    AlertDialog.Builder(context)
                        .setTitle("Renomear texto")
                        .setView(input)
                        .setPositiveButton("OK") { dialog, _ ->
                            val newText = input.text?.toString()?.trim().orEmpty()
                            if (newText.isNotEmpty()) {
                                selectedAct.text = newText
                                invalidate()
                            }
                            dialog.dismiss()
                        }
                        .setNegativeButton("Cancelar") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }
        }
        return true
    }

    // ===== MÉTODOS AUXILIARES =====

    // Retângulo da lixeira em coordenadas do mundo para hit test
    private fun getDeleteRectForAction(action: Action): RectF? {
        val rect = when (action) {
            is Action.Shape -> getActionBounds(action)
            is Action.Poi -> getActionBounds(action)
            is Action.Text -> getActionBounds(action)
            else -> null
        } ?: return null
        val margin = dp(4f) / max(0.001f, scale)
        val size = dp(32f) / max(0.001f, scale) // hit maior
        val left = rect.right - size
        val top = rect.top - size - margin
        return RectF(left, top, left + size, top + size)
    }

    private fun drawGrid(canvas: Canvas) {
        val spacing = pxPerMeter * 4 // Espaçamento maior para bolinhas tipo Figma
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

    private fun getPoiCacheKey(poi: Action.Poi): String {
        return try {
            val entry = context.resources.getResourceEntryName(poi.iconRes)
            "${entry}:${poi.width.toInt()}x${poi.height.toInt()}"
        } catch (_: Exception) {
            "res_${poi.iconRes}:${poi.width.toInt()}x${poi.height.toInt()}"
        }
    }

    private fun colorForPoiIcon(resId: Int): Int = try {
        when (resId) {
            com.example.indoorar.R.drawable.ic_door_azul, com.example.indoorar.R.drawable.ic_banheiro_azul -> "#32357A".toColorInt() // azul
            com.example.indoorar.R.drawable.ic_stairs_azul -> "#FF9800".toColorInt() // laranja
            com.example.indoorar.R.drawable.ic_extintor_azul -> "#F44336".toColorInt() // vermelho
            com.example.indoorar.R.drawable.ic_elevator_azul -> "#4CAF50".toColorInt() // verde
            else -> "#32357A".toColorInt()
        }
    } catch (_: Exception) { "#32357A".toColorInt() }

    private fun getBitmapForPoi(poi: Action.Poi): Bitmap? {
        val styleKey = getPoiCacheKey(poi) + "::pinV1"
        poiBitmapCache[styleKey]?.let { return it }
        return try {
            val targetW = max(1, poi.width.toInt())
            val targetH = max(1, poi.height.toInt())
            if (targetW <= 2 || targetH <= 4) return null
            val bmp = createBitmap(targetW, targetH)
            val c = Canvas(bmp)
            val pinColor = colorForPoiIcon(poi.iconRes)
            val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pinColor; style = Paint.Style.FILL }
            val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = targetW * 0.05f }
            val cx = targetW / 2f
            val topPadding = targetH * 0.04f
            val r = min(targetW * 0.42f, (targetH * 0.50f)) * 0.9f
            val cy = topPadding + r
            val bottomTipY = targetH.toFloat() - paintStroke.strokeWidth * 0.6f
            val angleStart = 200f
            val sweep = 140f
            val radStart = Math.toRadians(angleStart.toDouble())
            val p1x = (cx + r * kotlin.math.cos(radStart)).toFloat()
            val p1y = (cy + r * kotlin.math.sin(radStart)).toFloat()
            val path = Path().apply {
                moveTo(cx, bottomTipY)
                lineTo(p1x, p1y)
                addArc(RectF(cx - r, cy - r, cx + r, cy + r), angleStart, sweep)
                lineTo(cx, bottomTipY)
                close()
            }
            c.drawPath(path, paintFill)
            c.drawPath(path, paintStroke)
            val innerR = r * 0.60f
            val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
            c.drawCircle(cx, cy, innerR, innerPaint)
            val drawable = try { ContextCompat.getDrawable(context, poi.iconRes) } catch (_: Exception) { null }
                ?: ContextCompat.getDrawable(context, com.example.indoorar.R.drawable.ic_poi_default)
            drawable?.let { dr ->
                val iconSize = (innerR * 1.4f).coerceAtMost(innerR * 1.8f)
                val iconLeft = (cx - iconSize / 2f).toInt()
                val iconTop = (cy - iconSize / 2f).toInt()
                val iconRight = (iconLeft + iconSize).toInt()
                val iconBottom = (iconTop + iconSize).toInt()
                try { dr.mutate(); dr.setTint(pinColor) } catch (_: Exception) {}
                dr.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                dr.draw(c)
            }
            val contentRect = computeOpaqueBounds(bmp)
            poiBitmapCache[styleKey] = bmp
            poiContentBoundsCache[styleKey] = contentRect
            bmp
        } catch (_: Exception) { null }
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
        val styleKey = getPoiCacheKey(poi) + "::pinV1"
        val bmp = poiBitmapCache[styleKey] ?: getBitmapForPoi(poi) ?: return null
        val content = poiContentBoundsCache[styleKey] ?: return null
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
                    // Testa hit no espaço local (des-rotacionado)
                    val local = worldToLocalForAction(action, point)
                    val rect = getPoiContentRectInWorld(action) ?: return@find false
                    local.x in rect.left..rect.right && local.y in rect.top..rect.bottom
                }
                is Action.Shape -> {
                    // Des-rotaciona o ponto e testa contra o retângulo base da shape
                    val local = worldToLocalForAction(action, point)
                    val b = getActionBounds(action) ?: return@find false
                    when (action.type) {
                        Action.ShapeType.CIRCLE -> {
                            val cx = b.centerX(); val cy = b.centerY()
                            val r = min(b.width(), b.height()) / 2f
                            val dx = local.x - cx
                            val dy = local.y - cy
                            dx*dx + dy*dy <= r*r
                        }
                        Action.ShapeType.TRIANGLE -> {
                            // Usa path no espaço local
                            val path = Path().apply {
                                moveTo((b.left + b.right)/2f, b.top)
                                lineTo(b.left, b.bottom)
                                lineTo(b.right, b.bottom)
                                close()
                            }
                            val rp = Region(
                                RectF(b.left, b.top, b.right, b.bottom).let { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }
                            )
                            val rgn = Region()
                            rgn.setPath(path, rp)
                            rgn.contains(local.x.toInt(), local.y.toInt())
                        }
                        Action.ShapeType.LINE -> {
                            // Caixa de interseção em torno da linha
                            val threshold = dp(8f) / max(0.001f, scale)
                            val x1 = action.start.x; val y1 = action.start.y
                            val x2 = action.end.x; val y2 = action.end.y
                            // Distância ponto-linha segment
                            val dx = x2 - x1
                            val dy = y2 - y1
                            val len2 = dx*dx + dy*dy
                            val t = if (len2 == 0f) 0f else (((local.x - x1) * dx + (local.y - y1) * dy) / len2).coerceIn(0f, 1f)
                            val px = x1 + t * dx
                            val py = y1 + t * dy
                            val ddx = local.x - px
                            val ddy = local.y - py
                            ddx*ddx + ddy*ddy <= threshold*threshold
                        }
                        else -> {
                            local.x in b.left..b.right && local.y in b.top..b.bottom
                        }
                    }
                }
                is Action.Text -> {
                    val rect = getActionBounds(action) ?: return@find false
                    point.x in rect.left..rect.right && point.y in rect.top..rect.bottom
                }
                else -> false
            }
        }
    }

    private fun poiToProperties(poi: Action.Poi) = ShapeProps(poi.x, poi.y, poi.width, poi.height, poi.rotation, null)

    private fun shapeToProperties(shape: Action.Shape) = ShapeProps(
        min(shape.start.x, shape.end.x), min(shape.start.y, shape.end.y),
        abs(shape.end.x - shape.start.x), abs(shape.end.y - shape.start.y), shape.rotation, shape.fillColor
    )

    private fun textToProperties(text: Action.Text): ShapeProps {
        val r = getActionBounds(text) ?: RectF(text.x, text.y, text.x, text.y)
        return ShapeProps(r.left, r.top, r.width(), r.height(), 0f, text.color)
    }

    fun screenToWorld(x: Float, y: Float) = PointF((x - offsetX) / scale, (y - offsetY) / scale)
    fun dp(v: Float) = v * resources.displayMetrics.density

    private fun applySnapX(delta: Float) {
        val obj = draggingObject ?: return
        when (obj) {
            is Action.Shape -> { obj.start.x += delta; obj.end.x += delta }
            is Action.Poi -> { obj.x += delta }
            is Action.Text -> { obj.x += delta }
            is Action.BrushStroke -> {}
        }
    }

    private fun applySnapY(delta: Float) {
        val obj = draggingObject ?: return
        when (obj) {
            is Action.Shape -> { obj.start.y += delta; obj.end.y += delta }
            is Action.Poi -> { obj.y += delta }
            is Action.Text -> { obj.y += delta }
            is Action.BrushStroke -> {}
        }
    }

    fun addPoi(x: Float, y: Float, iconRes: Int) {
        val poi = Action.Poi(x = x, y = y, iconRes = iconRes)
        actions.add(poi)
        pushOp(AddOp(poi))
        invalidate()
    }

    fun addText(x: Float, y: Float, text: String, size: Float, color: Int) {
        val t = Action.Text(x = x, y = y, text = text, sizeSp = size, color = color)
        actions.add(t)
        pushOp(AddOp(t))
        invalidate()
    }

    fun addAction(action: Action) {
        actions.add(action)
        pushOp(AddOp(action))
        invalidate()
    }

    fun mergeEdgesAndAdjustCorners() {
        mergeRectShapeEdges()
        updateAutoCornerRadii()
        invalidate()
    }

    private fun mergeRectShapeEdges(tolerance: Float = 3f, overlapFrac: Float = 0.85f) {
        val rects = actions.filterIsInstance<Action.Shape>().filter { it.type == Action.ShapeType.RECTANGLE || it.type == Action.ShapeType.SQUARE }
        // Reset
        rects.forEach { s -> s.edgeTop = true; s.edgeRight = true; s.edgeBottom = true; s.edgeLeft = true }
        for (i in 0 until rects.size) {
            val a = rects[i]
            val aL = min(a.start.x, a.end.x)
            val aT = min(a.start.y, a.end.y)
            val aR = max(a.start.x, a.end.x)
            val aB = max(a.start.y, a.end.y)
            val aH = aB - aT
            val aW = aR - aL
            for (j in i+1 until rects.size) {
                val b = rects[j]
                val bL = min(b.start.x, b.end.x)
                val bT = min(b.start.y, b.end.y)
                val bR = max(b.start.x, b.end.x)
                val bB = max(b.start.y, b.end.y)
                val bH = bB - bT
                val bW = bR - bL
                // Horizontal adjacency (vertical shared edge)
                if (abs(aR - bL) <= tolerance || abs(bR - aL) <= tolerance) {
                    val overlap = min(aB, bB) - max(aT, bT)
                    val minH = min(aH, bH)
                    if (overlap >= minH * overlapFrac) {
                        if (abs(aR - bL) <= tolerance) { a.edgeRight = false; b.edgeLeft = false }
                        if (abs(bR - aL) <= tolerance) { b.edgeRight = false; a.edgeLeft = false }
                    }
                }
                // Vertical adjacency (horizontal shared edge)
                if (abs(aB - bT) <= tolerance || abs(bB - aT) <= tolerance) {
                    val overlap = min(aR, bR) - max(aL, bL)
                    val minW = min(aW, bW)
                    if (overlap >= minW * overlapFrac) {
                        if (abs(aB - bT) <= tolerance) { a.edgeBottom = false; b.edgeTop = false }
                        if (abs(bB - aT) <= tolerance) { b.edgeBottom = false; a.edgeTop = false }
                    }
                }
            }
        }
    }
}
