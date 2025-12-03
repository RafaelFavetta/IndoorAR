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
import kotlin.math.sin
import androidx.core.graphics.createBitmap
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat

class MinimapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Forma(
        val x: Float,
        val z: Float,
        val w: Float,
        val h: Float,
        val color: Int,
        val zOrder: Int = 0,
        val tipo: String = "retangulo",
        val rotation: Float = 0f
    )
    private data class Poi(val x: Float, val z: Float, val color: Int, val iconRes: Int?, val isStart: Boolean)

    private val formas = mutableListOf<Forma>()
    private val pois = mutableListOf<Poi>()
    private val miniIconCache = mutableMapOf<Triple<Int, Int, Int>, android.graphics.Bitmap>()
    private val tmpPath = Path()
    private var debugDraw = false

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

    private var userHeadingRad: Float = 0f
    private var rotateWithHeading: Boolean = false

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

    // Backwards-compatible addForma: default zOrder = 0
    fun addForma(x: Float, z: Float, w: Float, h: Float, color: Int) { formas += Forma(x, z, w, h, color, 0) }
    // New overload that accepts explicit zOrder (higher zOrder draws on top)
    fun addForma(x: Float, z: Float, w: Float, h: Float, color: Int, zOrder: Int) { formas += Forma(x, z, w, h, color, zOrder) }
    // Convenience: mark walkable shapes to draw above non-walkable (walkable -> zOrder 10)
    fun addForma(x: Float, z: Float, w: Float, h: Float, color: Int, isWalkable: Boolean) {
        val zIdx = if (isWalkable) 10 else 0
        formas += Forma(x, z, w, h, color, zIdx)
    }
    // Full overload including tipo and rotation
    fun addForma(x: Float, z: Float, w: Float, h: Float, color: Int, isWalkable: Boolean, tipo: String, rotation: Float) {
        val zIdx = if (isWalkable) 10 else 0
        formas += Forma(x, z, w, h, color, zIdx, tipo ?: "retangulo", rotation)
    }

    fun addPoi(x: Float, z: Float) { pois += Poi(x, z, Color.YELLOW, null, false) }
    fun addPoi(x: Float, z: Float, color: Int) { pois += Poi(x, z, color, null, false) }
    fun addPoi(x: Float, z: Float, color: Int, iconRes: Int?, isStart: Boolean) { pois += Poi(x, z, color, iconRes, isStart) }
    fun clearPois() { pois.clear(); invalidate() }
    fun clearFormas() { formas.clear(); invalidate() }

    fun setRoute(points: List<Pair<Float, Float>>) { route.clear(); route.addAll(points); invalidate() }
    fun clearRoute() { route.clear(); invalidate() }

    fun setRotateWithHeading(enabled: Boolean) { rotateWithHeading = enabled; invalidate() }

    fun updateUserPose(x: Float, z: Float, headingRad: Float) {
        userX = x; userZ = z; userHeadingRad = headingRad; invalidate()
    }

    fun updateUserPosition(x: Float, z: Float) { // backward compatibility
        userX = x; userZ = z; invalidate()
    }

    private fun getMiniIcon(iconRes: Int, size: Int, tint: Int): android.graphics.Bitmap? {
        if (iconRes == 0) return null
        val key = Triple(iconRes, size, tint)
        miniIconCache[key]?.let { return it }
        return try {
            val orig = AppCompatResources.getDrawable(context, iconRes) ?: return null
            val dr = try { orig.mutate() } catch (_: Exception) { orig }
            val wrapped = DrawableCompat.wrap(dr)
            try { DrawableCompat.setTintList(wrapped, null) } catch (_: Exception) {}
            if (tint != 0) {
                try { DrawableCompat.setTint(wrapped, tint) } catch (_: Exception) {}
            }
            val bmp = createBitmap(size, size)
            val c = android.graphics.Canvas(bmp)
            wrapped.setBounds(0, 0, size, size)
            wrapped.draw(c)
            miniIconCache[key] = bmp
            bmp
        } catch (_: Exception) { null }
    }

    /** Enable visual debug overlay for shapes/POIs/route. Useful to diagnose rotated shapes vs route generation. */
    fun setDebugDrawEnabled(enabled: Boolean) { debugDraw = enabled; invalidate() }

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

        val ux = (userX - minX) * scaleX
        val uz = (userZ - minZ) * scaleY

        // Rotaciona mapa em torno do usuário, mantendo heading do usuário apontando para cima
        if (rotateWithHeading) {
            canvas.save()
            canvas.rotate(-Math.toDegrees(userHeadingRad.toDouble()).toFloat(), ux, uz)
        }

        // formas: draw sorted by zOrder so non-walkable (lower zOrder) appear below walkable
        formas.sortedBy { it.zOrder }.forEach { f ->
            paint.color = f.color
            // Compute world->view extents and normalize in case w/h are negative or zero
            val rawLeft = (f.x - minX) * scaleX
            val rawTop = (f.z - minZ) * scaleY
            val rawRight = rawLeft + f.w * scaleX
            val rawBottom = rawTop + f.h * scaleY
            val left = minOf(rawLeft, rawRight)
            val right = maxOf(rawLeft, rawRight)
            val top = minOf(rawTop, rawBottom)
            val bottom = maxOf(rawTop, rawBottom)
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            val wPx = (right - left).coerceAtLeast(1f)
            val hPx = (bottom - top).coerceAtLeast(1f)

            // Save paint state and set defaults
            val prevStyle = paint.style
            val prevStroke = paint.strokeWidth
            paint.style = Paint.Style.FILL

            when (f.tipo.lowercase()) {
                "circulo", "circle" -> {
                    val r = (minOf(wPx, hPx) / 2f).coerceAtLeast(1f)
                    canvas.save()
                    if (f.rotation != 0f) canvas.rotate(f.rotation, cx, cy)
                    canvas.drawCircle(cx, cy, r, paint)
                    canvas.restore()
                }
                "triangulo" -> {
                    tmpPath.rewind()
                    tmpPath.moveTo(cx, top)
                    tmpPath.lineTo(left, bottom)
                    tmpPath.lineTo(right, bottom)
                    tmpPath.close()
                    canvas.save()
                    if (f.rotation != 0f) canvas.rotate(f.rotation, cx, cy)
                    canvas.drawPath(tmpPath, paint)
                    canvas.restore()
                }
                "linha" -> {
                    val x1 = left
                    val y1 = top
                    val x2 = right
                    val y2 = bottom
                    canvas.save()
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 3f
                    if (f.rotation != 0f) canvas.rotate(f.rotation, cx, cy)
                    canvas.drawLine(x1, y1, x2, y2, paint)
                    canvas.restore()
                }
                else -> { // retangulo, quadrado e default
                    canvas.save()
                    if (f.rotation != 0f) canvas.rotate(f.rotation, cx, cy)
                    canvas.drawRect(left, top, right, bottom, paint)
                    canvas.restore()
                }
            }

            // restore paint state
            paint.style = prevStyle
            paint.strokeWidth = prevStroke
        }

        // linha base rota
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

        // setas rota
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
                if (!placed) { drawArrow(canvas, (x1 + x2) * 0.5f, (y1 + y2) * 0.5f, angle) }
                leftover = (dist + arrowSpacingPx) - segLen
                if (leftover < 0f) leftover = 0f
            }
        }

        // destino: sempre destacar o último ponto da rota, mesmo quando há apenas um
         if (route.isNotEmpty()) {
            paint.style = Paint.Style.FILL
            // use material red (matches editor's extinguisher red) for consistent appearance
            paint.color = 0xFFF44336.toInt()
             val (dx, dz) = route.last()
             canvas.drawCircle((dx - minX) * scaleX, (dz - minZ) * scaleY, 5f, paint)
         }

        // POIs (render as colored circles with centered icon)
        pois.forEach { p ->
            val cx = (p.x - minX) * scaleX
            val cy = (p.z - minZ) * scaleY
            // scale radius relative to world size for consistent look
            val r = (pinHeightBase * 0.5f).coerceAtLeast(6f)

            if (p.iconRes != null) {
                 try {
                     // Prefer a cached rasterized icon (preserve original drawable colors)
                     val iconSizeF = (r * 1.8f).coerceAtLeast(18f)
                     val iconSize = iconSizeF.toInt()
                     val bmp = getMiniIcon(p.iconRes, iconSize, 0)
                     // Draw background circle (use exact POI color like in editor)
                     paint.style = Paint.Style.FILL
                     paint.color = p.color
                     canvas.drawCircle(cx, cy, r, paint)
                     // thin white outline for contrast (matches editor)
                     paint.style = Paint.Style.STROKE
                     paint.strokeWidth = 1.5f
                     paint.color = Color.WHITE
                     canvas.drawCircle(cx, cy, r, paint)
                     if (bmp != null) {
                         val leftB = (cx - iconSize / 2f).toFloat()
                         val topB = (cy - iconSize / 2f).toFloat()
                        // Draw icon bitmap centered on top of the colored circle using filtered paint for scaling
                        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
                        canvas.drawBitmap(bmp, leftB, topB, bmpPaint)
                        // Optionally draw a subtle start ring if this POI is the start
                        if (p.isStart) {
                            paint.style = Paint.Style.STROKE
                            paint.strokeWidth = 2.6f
                            paint.color = 0xFFFFD54F.toInt() // amber
                            canvas.drawCircle(cx, cy, iconSizeF / 2f + 2f, paint)
                        }
                     } else {
                         // Fallback to drawing the drawable directly (no tint) if bitmap creation fails
                         var dr = try { AppCompatResources.getDrawable(context, p.iconRes) } catch (_: Exception) { null }
                             ?: AppCompatResources.getDrawable(context, com.example.indoorar.R.drawable.ic_poi_default)
                         dr = try { dr?.mutate() } catch (_: Exception) { dr }
                         dr?.let { d ->
                             try { DrawableCompat.clearColorFilter(d) } catch (_: Exception) {}
                             val leftI = (cx - iconSizeF / 2f).toInt()
                             val topI = (cy - iconSizeF / 2f).toInt()
                             val rightI = (leftI + iconSizeF).toInt()
                             val bottomI = (topI + iconSizeF).toInt()
                            d.setBounds(leftI, topI, rightI, bottomI)
                            d.draw(canvas)
                            if (p.isStart) {
                                paint.style = Paint.Style.STROKE
                                paint.strokeWidth = 2.6f
                                paint.color = 0xFFFFD54F.toInt()
                                canvas.drawCircle(cx, cy, iconSizeF / 2f + 2f, paint)
                            }
                         }
                     }
                  } catch (_: Exception) { /* ignore drawing icon */ }
             } else {
                // No icon: draw colored circle + white outline + optional start ring
                paint.style = Paint.Style.FILL
                paint.color = p.color
                canvas.drawCircle(cx, cy, r, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                paint.color = Color.WHITE
                canvas.drawCircle(cx, cy, r, paint)

                if (p.isStart) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.6f
                    paint.color = 0xFFFFD54F.toInt() // amber
                    canvas.drawCircle(cx, cy, r + 2f, paint)
                }
             }
        }

        if (rotateWithHeading) {
            canvas.restore()
        }

        // usuário (ponto + seta de heading opcional)
        val now = SystemClock.uptimeMillis()
        val phase = (now % 1000L).toFloat() / 1000f
        val baseRadius = 6f
        val haloRadius = baseRadius + 6f * phase
        val alpha = ((1f - phase) * 120f).toInt().coerceIn(0, 120)
        haloPaint.alpha = alpha
        canvas.drawCircle(ux, uz, haloRadius, haloPaint)
        userPaint.alpha = 255
        canvas.drawCircle(ux, uz, baseRadius, userPaint)

        // Desenha triângulo indicando heading se não estiver rotacionando mapa; se rotaciona, seta sempre para cima (já implícito)
        if (!rotateWithHeading) {
            val len = 18f
            val half = 6f
            val cosH = cos(userHeadingRad)
            val sinH = sin(userHeadingRad)
            // 0 rad = para cima na tela: tipY deve ser uz - len
            val tipX = ux + sinH * len
            val tipY = uz - cosH * len
            val leftX = ux + (-cosH * half)
            val leftY = uz - (sinH * half)
            val rightX = ux + (cosH * half)
            val rightY = uz + (sinH * half)
            val path = Path()
            path.moveTo(tipX, tipY)
            path.lineTo(leftX, leftY)
            path.lineTo(rightX, rightY)
            path.close()
            arrowPaint.alpha = 220
            canvas.drawPath(path, arrowPaint)
        }

        // Debug overlay: show shapes polygon outlines and route points
        if (debugDraw) {
            val dbgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
            // shapes outlines
            formas.sortedBy { it.zOrder }.forEach { f ->
                if (f.tipo.lowercase() == "circulo" || f.tipo.lowercase() == "circle") {
                    dbgPaint.color = if (f.zOrder >= 10) Color.GREEN else Color.RED
                    val rawLeft = (f.x - minX) * scaleX
                    val rawTop = (f.z - minZ) * scaleY
                    val rawRight = rawLeft + f.w * scaleX
                    val rawBottom = rawTop + f.h * scaleY
                    val left = minOf(rawLeft, rawRight)
                    val right = maxOf(rawLeft, rawRight)
                    val top = minOf(rawTop, rawBottom)
                    val bottom = maxOf(rawTop, rawBottom)
                    val cx2 = (left + right) / 2f
                    val cy2 = (top + bottom) / 2f
                    val r2 = (minOf(right - left, bottom - top) / 2f)
                    canvas.drawCircle(cx2, cy2, r2, dbgPaint)
                } else {
                    // polygon for rect/triangle/line
                    val rawLeft = (f.x - minX) * scaleX
                    val rawTop = (f.z - minZ) * scaleY
                    val rawRight = rawLeft + f.w * scaleX
                    val rawBottom = rawTop + f.h * scaleY
                    val left = minOf(rawLeft, rawRight)
                    val right = maxOf(rawLeft, rawRight)
                    val top = minOf(rawTop, rawBottom)
                    val bottom = maxOf(rawTop, rawBottom)
                    val cx2 = (left + right) / 2f
                    val cy2 = (top + bottom) / 2f
                    val halfW2 = (right - left) / 2f
                    val halfH2 = (bottom - top) / 2f
                    val corners = when (f.tipo.lowercase()) {
                        "triangulo" -> listOf(Pair(cx2, top), Pair(left, bottom), Pair(right, bottom))
                        "linha" -> listOf(Pair(left, top), Pair(right, bottom))
                        else -> listOf(Pair(cx2 - halfW2, cy2 - halfH2), Pair(cx2 + halfW2, cy2 - halfH2), Pair(cx2 + halfW2, cy2 + halfH2), Pair(cx2 - halfW2, cy2 + halfH2))
                    }
                    // rotate corners around center by rotation deg
                    val rot = f.rotation
                    val rotated = if (rot == 0f) corners else corners.map { (px, py) ->
                        val a = Math.toRadians(rot.toDouble())
                        val cosA = kotlin.math.cos(a).toFloat(); val sinA = kotlin.math.sin(a).toFloat()
                        val tx = px - cx2; val ty = py - cy2
                        val rx = tx * cosA - ty * sinA
                        val ry = tx * sinA + ty * cosA
                        Pair(rx + cx2, ry + cy2)
                    }
                    dbgPaint.color = if (f.zOrder >= 10) Color.GREEN else Color.RED
                    tmpPath.rewind()
                    if (rotated.isNotEmpty()) {
                        tmpPath.moveTo(rotated[0].first, rotated[0].second)
                        for (k in 1 until rotated.size) tmpPath.lineTo(rotated[k].first, rotated[k].second)
                        if (rotated.size > 2) tmpPath.close()
                        canvas.drawPath(tmpPath, dbgPaint)
                    }
                }
            }

            // route points as small squares
            val rpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.MAGENTA }
            route.forEach { pt ->
                val rx = (pt.first - minX) * scaleX
                val ry = (pt.second - minZ) * scaleY
                val s = 4f
                canvas.drawRect(rx - s, ry - s, rx + s, ry + s, rpPaint)
            }
        }

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
        tmpPath.rewind()
        tmpPath.moveTo(tx[0], tx[1])
        tmpPath.lineTo(tx[2], tx[3])
        tmpPath.lineTo(tx[4], tx[5])
        tmpPath.close()
        canvas.drawPath(tmpPath, arrowPaint)
    }
}