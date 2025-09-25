package com.example.indoorar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class MinimapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Forma(val x: Float, val z: Float, val w: Float, val h: Float, val color: Int)
    private data class Poi(val x: Float, val z: Float)

    private val formas = mutableListOf<Forma>()
    private val pois = mutableListOf<Poi>()
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

    init {
        userPaint.style = Paint.Style.FILL
        userPaint.color = Color.rgb(33, 150, 243) // Bright blue
        haloPaint.style = Paint.Style.FILL
        haloPaint.color = Color.rgb(33, 150, 243)
    }

    fun setWorldBounds(minX: Float, minZ: Float, maxX: Float, maxZ: Float) {
        this.minX = minX
        this.minZ = minZ
        this.maxX = if (maxX - minX < 0.1f) minX + 10f else maxX
        this.maxZ = if (maxZ - minZ < 0.1f) minZ + 10f else maxZ
        invalidate()
    }

    fun addForma(x: Float, z: Float, w: Float, h: Float, color: Int) { formas += Forma(x, z, w, h, color) }
    fun addPoi(x: Float, z: Float) { pois += Poi(x, z) }

    fun setRoute(points: List<Pair<Float, Float>>) { route.clear(); route.addAll(points); invalidate() }
    fun clearRoute() { route.clear(); invalidate() }

    fun updateUserPosition(x: Float, z: Float) {
        userX = x; userZ = z; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val wView = width.toFloat(); val hView = height.toFloat()
        if (wView <= 0 || hView <= 0) return
        val worldW = maxX - minX; val worldH = maxZ - minZ
        if (worldW <= 0f || worldH <= 0f) return
        val scaleX = wView / worldW; val scaleY = hView / worldH

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

        // rota
        if (route.size >= 2) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = Color.GREEN
            for (i in 0 until route.size - 1) {
                val (ax, az) = route[i]
                val (bx, bz) = route[i + 1]
                canvas.drawLine((ax - minX) * scaleX, (az - minZ) * scaleY, (bx - minX) * scaleX, (bz - minZ) * scaleY, paint)
            }
            // destino
            paint.style = Paint.Style.FILL
            paint.color = Color.RED
            val (dx, dz) = route.last()
            canvas.drawCircle((dx - minX) * scaleX, (dz - minZ) * scaleY, 5f, paint)
        }

        // POIs
        paint.color = Color.YELLOW
        pois.forEach { p ->
            val cx = (p.x - minX) * scaleX
            val cy = (p.z - minZ) * scaleY
            canvas.drawCircle(cx, cy, 4f, paint)
        }

        // usuário (ponto azul com brilho pulsando)
        val ux = (userX - minX) * scaleX
        val uz = (userZ - minZ) * scaleY
        val now = SystemClock.uptimeMillis()
        val phase = (now % 1000L).toFloat() / 1000f // 0..1
        val baseRadius = 6f
        val haloRadius = baseRadius + 6f * phase
        val alpha = ((1f - phase) * 120f).toInt().coerceIn(0, 120)
        haloPaint.alpha = alpha
        canvas.drawCircle(ux, uz, haloRadius, haloPaint)
        userPaint.alpha = 255
        canvas.drawCircle(ux, uz, baseRadius, userPaint)

        // Continuar animando o brilho
        postInvalidateOnAnimation()
    }
}
