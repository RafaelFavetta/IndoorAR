package com.example.indoorar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class MinimapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Forma(val x: Float, val z: Float, val w: Float, val h: Float, val color: Int)
    private data class Poi(val x: Float, val z: Float, val color: Int, val iconRes: Int?, val isStart: Boolean)

    private val formas = mutableListOf<Forma>()
    private val pois = mutableListOf<Poi>()
    private val miniIconCache = mutableMapOf<Long, android.graphics.Bitmap>()

    private var route: MutableList<Pair<Float, Float>> = mutableListOf()
    private var userX: Float = 0f
    private var userZ: Float = 0f

    private var minX = 0f
    private var minZ = 0f
    private var maxX = 10f
    private var maxZ = 10f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val userPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Arrow params (in view px)
    private val arrowSpacingPx = 26f
    private val arrowLengthPx = 16f
    private val arrowWidthPx = 12f

    init {
        userPaint.style = Paint.Style.FILL
        userPaint.color = Color.rgb(33, 150, 243) // Bright blue
        haloPaint.style = Paint.Style.FILL
        haloPaint.color = Color.rgb(33, 150, 243)
        arrowPaint.style = Paint.Style.FILL
        arrowPaint.color = Color.rgb(33, 150, 243)
    }

    fun setWorldBounds(minX: Float, minZ: Float, maxX: Float, maxZ: Float) {
        this.minX = minX
        this.minZ = minZ
        this.maxX = if (maxX - minX < 0.1f) minX + 10f else maxX
        this.maxZ = if (maxZ - minZ < 0.1f) minZ + 10f else maxZ
        invalidate()
    }

    fun addForma(x: Float, z: Float, w: Float, h: Float, color: Int) { formas += Forma(x, z, w, h, color) }
    fun addPoi(x: Float, z: Float) { pois += Poi(x, z, Color.YELLOW, null, false) }
    fun addPoi(x: Float, z: Float, color: Int) { pois += Poi(x, z, color, null, false) }
    fun addPoi(x: Float, z: Float, color: Int, iconRes: Int?, isStart: Boolean) { pois += Poi(x, z, color, iconRes, isStart) }
    fun clearPois() { pois.clear(); invalidate() }
    fun clearFormas() { formas.clear(); invalidate() }

    fun setRoute(points: List<Pair<Float, Float>>) { route.clear(); route.addAll(points); invalidate() }
    fun clearRoute() { route.clear(); invalidate() }

    fun updateUserPosition(x: Float, z: Float) {
        userX = x; userZ = z; invalidate()
    }

    private fun getMiniIcon(iconRes: Int, size: Int, tint: Int): android.graphics.Bitmap? {
        if (iconRes == 0) return null
        val key = (iconRes.toLong() shl 32) or (size.toLong() and 0xFFFFFFFFL) or ((tint.toLong() and 0xFFFFFFFFL) shl 16)
        miniIconCache[key]?.let { return it }
        return try {
            val dr = context.getDrawable(iconRes) ?: return null
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            try { dr.mutate(); dr.setTint(tint) } catch (_: Exception) {}
            dr.setBounds(0, 0, size, size)
            dr.draw(c)
            miniIconCache[key] = bmp
            bmp
        } catch (_: Exception) { null }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val wView = width.toFloat(); val hView = height.toFloat()
        if (wView <= 0 || hView <= 0) return
        val worldW = maxX - minX; val worldH = maxZ - minZ
        if (worldW <= 0f || worldH <= 0f) return
        val scaleX = wView / worldW; val scaleY = hView / worldH
        val worldDiag = kotlin.math.sqrt(worldW * worldW + worldH * worldH)
        val basePin = 14f
        val referenceDiag = 50f
        val dynamicFactor = (referenceDiag / worldDiag).coerceIn(0.6f, 1.6f)
        val pinHeightBase = basePin * dynamicFactor

        // fundo
        paint.style = Paint.Style.FILL; paint.color = Color.argb(80, 0, 0, 0)
        canvas.drawRect(0f, 0f, wView, hView, paint)

        // formas
        formas.forEach { f ->
            paint.color = f.color
            val left = (f.x - minX) * scaleX
            val top = (f.z - minZ) * scaleY
            val right = left + f.w * scaleX
            val bottom = top + f.h * scaleY
            canvas.drawRect(left, top, right, bottom, paint)
        }

        // Nova: linha base da rota (contínua) antes das setas para garantir visibilidade
        if (route.size >= 2) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = Color.WHITE
            var prev = route.first()
            for (i in 1 until route.size) {
                val cur = route[i]
                val x1 = (prev.first - minX) * scaleX
                val y1 = (prev.second - minZ) * scaleY
                val x2 = (cur.first - minX) * scaleX
                val y2 = (cur.second - minZ) * scaleY
                canvas.drawLine(x1, y1, x2, y2, paint)
                prev = cur
            }
        }

        // rota (setas azuis)
        if (route.size >= 2) {
            var leftover = 0f
            for (i in 0 until route.size - 1) {
                val (ax, az) = route[i]
                val (bx, bz) = route[i + 1]
                val x1 = (ax - minX) * scaleX
                val y1 = (az - minZ) * scaleY
                val x2 = (bx - minX) * scaleX
                val y2 = (bz - minZ) * scaleY
                val dx = x2 - x1
                val dy = y2 - y1
                val segLen = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (segLen <= 0.001f) continue
                val angle = atan2(dy, dx)

                var placed = false
                var dist = if (leftover > 0f) leftover else 0f
                while (dist + arrowSpacingPx <= segLen) {
                    dist += arrowSpacingPx
                    val px = x1 + (dx * (dist / segLen))
                    val py = y1 + (dy * (dist / segLen))
                    drawArrow(canvas, px, py, angle)
                    placed = true
                }
                if (!placed) {
                    drawArrow(canvas, (x1 + x2) * 0.5f, (y1 + y2) * 0.5f, angle)
                }
                leftover = (dist + arrowSpacingPx) - segLen
                if (leftover < 0f) leftover = 0f
            }
            // destino
            paint.style = Paint.Style.FILL
            paint.color = Color.RED
            val (dx, dz) = route.last()
            canvas.drawCircle((dx - minX) * scaleX, (dz - minZ) * scaleY, 5f, paint)
        }

        // POIs (mini pins com ícone e destaque)
        pois.forEach { p ->
            val tipX = (p.x - minX) * scaleX
            val tipY = (p.z - minZ) * scaleY
            val pinHeight = pinHeightBase
            val r = pinHeight * 0.35f
            val centerY = tipY - (pinHeight - r)
            val centerX = tipX
            val path = Path()
            val startAngle = 200f
            val sweep = 140f
            val radStart = Math.toRadians(startAngle.toDouble())
            val p1x = (centerX + r * kotlin.math.cos(radStart)).toFloat()
            val p1y = (centerY + r * kotlin.math.sin(radStart)).toFloat()
            path.moveTo(tipX, tipY)
            path.lineTo(p1x, p1y)
            path.addArc(centerX - r, centerY - r, centerX + r, centerY + r, startAngle, sweep)
            path.close()
            // Fill
            paint.style = Paint.Style.FILL
            paint.color = p.color
            canvas.drawPath(path, paint)
            // Outer stroke (white)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            paint.color = Color.WHITE
            canvas.drawPath(path, paint)
            // Extra highlight ring se for start
            if (p.isStart) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.6f
                paint.color = 0xFFFFD54F.toInt() // amarelo suave (gold) para destaque
                canvas.drawCircle(centerX, centerY, r * 0.95f, paint)
            }
            // Inner circle
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            val innerR = r * 0.60f
            canvas.drawCircle(centerX, centerY, innerR, paint)
            // Ícone interno
            if (p.iconRes != null) {
                val iconSize = (innerR * 1.3f).toInt().coerceAtLeast(6)
                getMiniIcon(p.iconRes, iconSize, p.color)?.let { bmp ->
                    val left = (centerX - bmp.width / 2f)
                    val top = (centerY - bmp.height / 2f)
                    canvas.drawBitmap(bmp, left, top, null)
                }
            }
        }

        // usuário (ponto azul com brilho pulsando)
        val ux = (userX - minX) * scaleX
        val uz = (userZ - minZ) * scaleY
        val now = SystemClock.uptimeMillis()
        val phase = (now % 1000L).toFloat() / 1000f
        val baseRadius = 6f
        val haloRadius = baseRadius + 6f * phase
        val alpha = ((1f - phase) * 120f).toInt().coerceIn(0, 120)
        haloPaint.alpha = alpha
        canvas.drawCircle(ux, uz, haloRadius, haloPaint)
        userPaint.alpha = 255
        canvas.drawCircle(ux, uz, baseRadius, userPaint)

        postInvalidateOnAnimation()
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, angleRad: Float) {
        val len = arrowLengthPx
        val halfW = arrowWidthPx / 2f
        val verts = floatArrayOf(
            0f, -len / 2f,
            -halfW, len / 2f,
            halfW, len / 2f
        )
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)
        val tx = floatArrayOf(
            verts[0] * cosA - verts[1] * sinA + cx,
            verts[0] * sinA + verts[1] * cosA + cy,
            verts[2] * cosA - verts[3] * sinA + cx,
            verts[2] * sinA + verts[3] * cosA + cy,
            verts[4] * cosA - verts[5] * sinA + cx,
            verts[4] * sinA + verts[5] * cosA + cy
        )
        val path = Path()
        path.moveTo(tx[0], tx[1])
        path.lineTo(tx[2], tx[3])
        path.lineTo(tx[4], tx[5])
        path.close()
        canvas.drawPath(path, arrowPaint)
    }
}
