package com.example.indoorar

import android.content.Context
import android.content.Intent
import com.example.indoorar.graph.Node
import com.example.indoorar.graph.PathUtils

object RouteBus {
    /**
     * Broadcasts new world bounds and route points to the HUD.
     * bounds: float[4] -> [minX, minZ, maxX, maxZ]
     * points: list of (x,z) in map coordinates
     */
    fun sendUpdateRoute(context: Context, bounds: FloatArray?, points: List<Pair<Float, Float>>) {
        val intent = Intent(ActivityNavHud.ACTION_UPDATE_ROUTE)
        bounds?.let { intent.putExtra(ActivityNavHud.EXTRA_WORLD_BOUNDS, it) }
        if (points.isNotEmpty()) {
            val packed = FloatArray(points.size * 2)
            var i = 0
            for ((x, z) in points) {
                packed[i++] = x
                packed[i++] = z
            }
            intent.putExtra(ActivityNavHud.EXTRA_ROUTE, packed)
        }
        context.sendBroadcast(intent)
    }

    /** Convenience: densify a list of A* nodes and broadcast to HUD. */
    fun sendUpdateRouteFromNodes(context: Context, bounds: FloatArray?, nodes: List<Node>, stepMeters: Float = 0.25f) {
        val points = PathUtils.densify(nodes, stepMeters)
        sendUpdateRoute(context, bounds, points)
    }

    /** Clears the current route from the HUD. */
    fun sendClearRoute(context: Context) {
        val intent = Intent(ActivityNavHud.ACTION_CLEAR_ROUTE)
        context.sendBroadcast(intent)
    }
}