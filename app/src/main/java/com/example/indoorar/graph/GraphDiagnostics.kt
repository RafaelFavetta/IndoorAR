package com.example.indoorar.graph

import kotlin.concurrent.thread

/** Runs connectivity checks over the map graph by trying A* between POI nodes. */
object GraphDiagnostics {
    data class Failure(val fromId: String, val toId: String)
    data class Report(
        val poiCount: Int,
        val totalPairs: Int,
        val failed: List<Failure>
    ) {
        val okPairs: Int get() = totalPairs - failed.size
        fun summary(): String = buildString {
            append("POIs: ").append(poiCount)
                .append(" | Pairs: ").append(totalPairs)
                .append(" | OK: ").append(okPairs)
                .append(" | FAIL: ").append(failed.size)
        }
    }

    /** Loads the graph from Firestore and checks pairwise connectivity among POI node IDs. */
    fun checkConnectivity(mapId: String, onDone: (Result<Report>) -> Unit) {
        FirestoreGraphLoader.load(mapId) { res ->
            res.onFailure { onDone(Result.failure(it)) }
            res.onSuccess { loaded ->
                val graph = loaded.graph
                val poiNodes = loaded.poiNodeIds
                val n = poiNodes.size
                val totalPairs = (n * (n - 1)) / 2
                if (n <= 1) {
                    onDone(Result.success(Report(n, totalPairs, emptyList())))
                    return@onSuccess
                }
                thread(name = "GraphDiag-$mapId") {
                    val fails = ArrayList<Failure>()
                    for (i in 0 until n - 1) {
                        val a = poiNodes[i]
                        for (j in i + 1 until n) {
                            val b = poiNodes[j]
                            val path = AStarPathfinder.findPath(graph, a, b)
                            if (!path.found || path.nodes.isEmpty()) {
                                fails.add(Failure(a, b))
                            }
                        }
                    }
                    onDone(Result.success(Report(n, totalPairs, fails)))
                }
            }
        }
    }
}