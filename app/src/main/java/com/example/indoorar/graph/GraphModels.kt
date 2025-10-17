package com.example.indoorar.graph

import java.util.PriorityQueue
import kotlin.math.hypot

/** Basic graph primitives for indoor routing. All distances are meters. */
data class Node(
    val id: String,
    val x: Float,
    val y: Float,
    val poiIds: List<String> = emptyList()
)

data class Edge(
    val from: String,
    val to: String,
    val weight: Float
)

class Graph(
    val nodesById: Map<String, Node>,
    val adj: Map<String, List<Edge>>
) {
    companion object {
        fun from(nodes: List<Node>, edges: List<Edge>, undirected: Boolean = true): Graph {
            val nodesById = nodes.associateBy { it.id }
            val tmp = HashMap<String, MutableList<Edge>>()
            fun add(from: String, to: String, w: Float) {
                tmp.getOrPut(from) { mutableListOf() }.add(Edge(from, to, w))
            }
            for (e in edges) {
                if (nodesById.containsKey(e.from) && nodesById.containsKey(e.to)) {
                    add(e.from, e.to, e.weight)
                    if (undirected) add(e.to, e.from, e.weight)
                }
            }
            return Graph(nodesById, tmp)
        }
    }
}

/** Result of a shortest-path query. */
data class PathResult(
    val found: Boolean,
    val nodes: List<Node>,
    val cost: Float
)

/** A* search over the Graph. Heuristic: Euclidean distance. */
object AStarPathfinder {
    fun findPath(graph: Graph, startId: String, goalId: String): PathResult {
        if (startId == goalId) {
            val n = graph.nodesById[startId]
            return if (n != null) PathResult(true, listOf(n), 0f) else PathResult(false, emptyList(), Float.POSITIVE_INFINITY)
        }
        val start = graph.nodesById[startId] ?: return PathResult(false, emptyList(), Float.POSITIVE_INFINITY)
        val goal = graph.nodesById[goalId] ?: return PathResult(false, emptyList(), Float.POSITIVE_INFINITY)

        fun h(a: Node, b: Node): Float = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

        val open = PriorityQueue(compareBy<Pair<String, Float>> { it.second })
        val cameFrom = HashMap<String, String>()
        val gScore = HashMap<String, Float>()
        val fScore = HashMap<String, Float>()

        gScore[start.id] = 0f
        fScore[start.id] = h(start, goal)
        open.add(start.id to fScore[start.id]!!)

        val closed = HashSet<String>()

        while (open.isNotEmpty()) {
            val polled = open.poll() ?: continue
            val currentId = polled.first
            if (currentId == goal.id) {
                // reconstruct
                val pathIds = ArrayList<String>()
                var cur: String? = currentId
                while (cur != null) {
                    pathIds.add(cur)
                    cur = cameFrom[cur]
                }
                pathIds.reverse()
                val pathNodes = pathIds.mapNotNull { graph.nodesById[it] }
                return PathResult(true, pathNodes, gScore[goal.id] ?: Float.POSITIVE_INFINITY)
            }
            if (!closed.add(currentId)) continue
            val neighbors = graph.adj[currentId] ?: emptyList()
            for (e in neighbors) {
                if (e.to in closed) continue
                val tentative = (gScore[currentId] ?: Float.POSITIVE_INFINITY) + e.weight
                if (tentative < (gScore[e.to] ?: Float.POSITIVE_INFINITY)) {
                    cameFrom[e.to] = currentId
                    gScore[e.to] = tentative
                    val est = tentative + h(graph.nodesById[e.to]!!, goal)
                    fScore[e.to] = est
                    open.add(e.to to est)
                }
            }
        }
        return PathResult(false, emptyList(), Float.POSITIVE_INFINITY)
    }
}