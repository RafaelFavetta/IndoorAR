package com.example.indoorar.graph

import kotlin.math.hypot

/** Utilities for converting path nodes into HUD-ready polylines. */
object PathUtils {
    /**
     * Densifies a path given as A* nodes into a polyline with roughly stepMeters spacing.
     * Returns a list of (x,z) map coordinates suitable for Minimap/HUD.
     */
    fun densify(nodes: List<Node>, stepMeters: Float = 0.25f): List<Pair<Float, Float>> {
        if (nodes.isEmpty()) return emptyList()
        if (nodes.size == 1) return listOf(nodes.first().x to nodes.first().y)
        val step = stepMeters.coerceAtLeast(0.05f)
        val out = ArrayList<Pair<Float, Float>>()
        var lastX = nodes[0].x
        var lastY = nodes[0].y
        out += lastX to lastY
        for (i in 1 until nodes.size) {
            val nx = nodes[i].x
            val ny = nodes[i].y
            val dx = nx - lastX
            val dy = ny - lastY
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (dist <= 1e-4f) continue
            val steps = kotlin.math.max(1, kotlin.math.floor((dist / step).toDouble()).toInt())
            val inv = 1f / steps
            for (k in 1..steps) {
                val t = k * inv
                val px = lastX + dx * t
                val py = lastY + dy * t
                // avoid duplicating identical point
                if (out.isEmpty() || (kotlin.math.abs(out.last().first - px) > 1e-4f || kotlin.math.abs(out.last().second - py) > 1e-4f)) {
                    out += px to py
                }
            }
            lastX = nx; lastY = ny
        }
        return out
    }
}
