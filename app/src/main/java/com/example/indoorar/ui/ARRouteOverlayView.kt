package com.example.indoorar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.cos
import androidx.core.graphics.toColorInt

/**
 * Overlay 2D simples para desenhar setas indicando a rota na câmera AR.
 * Não usa projeção real de ARCore (por falta de callback acessível nesta versão),
 * mas projeta em perspectiva aproximada baseada em posição e heading do usuário.
 */
class ARRouteOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val routePoints = mutableListOf<Pair<Float, Float>>()
    private var camX = 0f
    private var camZ = 0f
    private var headingRad = 0f
    private var pitchRad = 0f

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = "#1E88E5".toColorInt() // azul mais forte
    }
    private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val path = Path()

    // Config (ajustes de debug: permitir seta bem próxima do usuário)
    private val maxDepthMeters = 12f
    private val minDepthMeters = 0.10f // reduzido de 0.35f
    private val arrowBaseSizePx = 50f // aumentado de 44f para mais visibilidade
    private val minArrowSizePx = 18f
    private val spacingMeters = 0.9f

    data class Projected(val sx: Float, val sy: Float, val scale: Float, val angleRad: Float)

    private var lastProjected: List<Projected> = emptyList()
    var lastArrowCount: Int = 0; private set
    var usedFallbackHeading: Boolean = false; private set

    fun setRoute(points: List<Pair<Float, Float>>) {
        routePoints.clear(); routePoints.addAll(points); invalidate()
    }
    fun clearRoute() { routePoints.clear(); invalidate() }
    fun updateUserPose(x: Float, z: Float, headingRad: Float) = updateCameraPose(x, z, headingRad)
    fun updateUserPose(x: Float, z: Float, headingRad: Float, pitchRad: Float) = updateCameraPose(x, z, headingRad, pitchRad)

    fun updateCameraPose(x: Float, z: Float, headingRad: Float) { camX = x; camZ = z; this.headingRad = headingRad; invalidate() }
    fun updateCameraPose(x: Float, z: Float, headingRad: Float, pitchRad: Float) { camX = x; camZ = z; this.headingRad = headingRad; this.pitchRad = pitchRad; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (routePoints.size < 2) { lastArrowCount = 0; usedFallbackHeading = false; return }
        val w = width.toFloat(); val h = height.toFloat(); if (w <= 0f || h <= 0f) { lastArrowCount = 0; usedFallbackHeading = false; return }

        // Normaliza pitch (-90..+90) -> (-1..1)
        val pitchNorm = (pitchRad / (Math.PI.toFloat() / 2f)).coerceIn(-1f, 1f)
        // Base vertical (quanto mais olhando para baixo (pitch negativo típico), subir a projeção)
        val baseYFactor = (0.985f - (pitchNorm * 0.20f)).coerceIn(0.70f, 0.99f)

        fun project(heading: Float): List<Projected> {
            val sinH = sin(heading); val cosH = cos(heading)
            var accAlong = 0f; var lastWX = camX; var lastWZ = camZ
            val proj = ArrayList<Projected>()
            var anyAdded = false
            for (i in 0 until routePoints.size) {
                val (rx, rz) = routePoints[i]
                val dx = rx - camX; val dz = rz - camZ
                val forward = dx * sinH + dz * cosH
                val right = dx * cosH - dz * sinH
                if (!anyAdded) {
                    if (forward <= 0f) continue
                    if (forward > maxDepthMeters) continue
                } else {
                    if (forward < minDepthMeters || forward > maxDepthMeters) continue
                }
                val segDx = rx - lastWX; val segDz = rz - lastWZ
                accAlong += kotlin.math.sqrt(segDx * segDx + segDz * segDz); lastWX = rx; lastWZ = rz
                if (anyAdded && accAlong < spacingMeters) continue
                accAlong = 0f
                val depthNorm = forward / maxDepthMeters
                val scale = (1f - depthNorm) * 0.9f + 0.1f
                val persp = 1f / (1f + 0.11f * forward)
                val sx = w * 0.5f + (right / (forward + 0.0001f)) * w * 0.45f
                val sy = h * (baseYFactor - depthNorm * 0.16f)
                if (sx < -140f || sx > w + 140f || sy < 0f || sy > h) continue
                val angleRad = run {
                    var j = i + 1; var found: Pair<Float, Float>? = null
                    while (j < routePoints.size) {
                        val c = routePoints[j]; val ddx = c.first - rx; val ddz = c.second - rz
                        if (ddx*ddx + ddz*ddz > 0.04f) { found = c; break }; j++
                    }
                    if (found == null && i > 0) found = routePoints[i - 1]
                    if (found != null) {
                        val dirX = found.first - rx; val dirZ = found.second - rz
                        val fwd = dirX * sinH + dirZ * cosH; val rgt = dirX * cosH - dirZ * sinH
                        atan2(rgt, fwd)
                    } else 0f
                }
                proj += Projected(sx, sy, scale * persp, angleRad)
                if (!anyAdded) anyAdded = true
            }
            return proj
        }

        usedFallbackHeading = false
        var proj = project(headingRad)
        if (proj.isEmpty()) {
            proj = project((headingRad + Math.PI.toFloat()) % (Math.PI.toFloat()*2f))
            if (proj.isNotEmpty()) usedFallbackHeading = true
        }
        if (proj.isEmpty()) {
            val first = routePoints.first()
            val dx = first.first - camX; val dz = first.second - camZ
            val sx = w * 0.5f
            val sy = h * (baseYFactor - 0.02f)
            proj = listOf(Projected(sx, sy, 1f, atan2(dx, dz)))
        }
        lastProjected = proj
        lastArrowCount = proj.size

        for (p in proj) {
            val size = (arrowBaseSizePx * p.scale).coerceAtLeast(minArrowSizePx)
            val half = size / 2f
            path.reset()
            path.moveTo(0f, -size * 0.95f)
            path.lineTo(-half * 0.6f, half * 0.55f)
            path.lineTo(0f, half * 0.30f)
            path.lineTo(half * 0.6f, half * 0.55f)
            path.close()
            canvas.save(); canvas.translate(p.sx, p.sy); canvas.rotate(Math.toDegrees(p.angleRad.toDouble()).toFloat()); canvas.drawPath(path, arrowPaint); canvas.drawPath(path, arrowStrokePaint); canvas.restore()
        }
    }
}
