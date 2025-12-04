package com.example.indoorar.graph

import com.google.firebase.firestore.FirebaseFirestore

/** Loads nodes/edges/pois for a map from Firestore and builds a Graph. */
object FirestoreGraphLoader {
    data class LoadedGraph(
        val graph: Graph,
        val nodes: Map<String, Node>,
        val poiNodeIds: List<String>
    )

    fun load(mapId: String, onResult: (Result<LoadedGraph>) -> Unit) {
        val db = FirebaseFirestore.getInstance()
        val mapaRef = db.collection("mapas").document(mapId)
        // Fetch nodes
        mapaRef.collection("nodes").get()
            .addOnSuccessListener { nodeSnap ->
                val nodes = nodeSnap.documents.mapNotNull { d ->
                    val id = d.getString("id") ?: d.id
                    val x = (d.getDouble("x") ?: 0.0).toFloat()
                    val y = (d.getDouble("y") ?: 0.0).toFloat()
                    val poiIds = (d.get("poiIds") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                    Node(id = id, x = x, y = y, poiIds = poiIds)
                }
                val nodesById = nodes.associateBy { it.id }
                // Fetch edges next
                mapaRef.collection("edges").get()
                    .addOnSuccessListener { edgeSnap ->
                        val edges = edgeSnap.documents.mapNotNull { d ->
                            val from = d.getString("fromNodeId") ?: return@mapNotNull null
                            val to = d.getString("toNodeId") ?: return@mapNotNull null
                            val w = (d.getDouble("peso") ?: 0.0).toFloat()
                            Edge(from = from, to = to, weight = w)
                        }

                        // Fetch formas so we can respect walkable areas when creating the waypoint mesh
                        mapaRef.collection("formas").get()
                            .addOnSuccessListener { formaSnap ->
                                val formas = formaSnap.documents.mapNotNull { d ->
                                    val pos = (d.get("posicao") as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }
                                    val tam = (d.get("tamanho") as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }
                                    val tipo = d.getString("tipo") ?: "retangulo"
                                    val rot = (d.getDouble("rotacao") ?: 0.0).toFloat()
                                    val walkable = when {
                                        d.contains("isWalkable") -> (d.getBoolean("isWalkable") ?: false)
                                        d.contains("caminhavel") -> (d.getBoolean("caminhavel") ?: false)
                                        d.contains("walkable") -> (d.getBoolean("walkable") ?: false)
                                        else -> false
                                    }
                                    if (pos != null && tam != null && pos.size >= 2 && tam.size >= 2) {
                                        FormaData(pos[0], pos[1], tam[0], tam[1], tipo ?: "retangulo", rot, walkable)
                                    } else null
                                }

                                // Cria malha densa de waypoints para garantir conectividade total,
                                // levando em conta as formas marcadas como walkable.
                                val enhancedData = enhanceGraphConnectivity(nodes, edges, formas)
                                val graph = Graph.from(enhancedData.first, enhancedData.second, undirected = true)

                                // Fetch POIs to know which node-ids to test
                                mapaRef.collection("pois").get()
                                    .addOnSuccessListener { poiSnap ->
                                        val poiIds = poiSnap.documents.mapNotNull { it.getString("id") ?: it.id }
                                        // Many maps set node id = poi id. Use intersection.
                                        val poiNodeIds = poiIds.filter { nodesById.containsKey(it) }
                                        onResult(Result.success(LoadedGraph(graph, nodesById, poiNodeIds)))
                                    }
                                    .addOnFailureListener { e ->
                                        // Even if POIs fail to load, try with all nodes marked as POIs
                                        val allNodeIds = nodes.map { it.id }
                                        onResult(Result.success(LoadedGraph(graph, nodesById, allNodeIds)))
                                    }
                            }
                            .addOnFailureListener {
                                // If formas fetch fails, fall back to previous behavior
                                val enhancedData = enhanceGraphConnectivity(nodes, edges, emptyList())
                                val graph = Graph.from(enhancedData.first, enhancedData.second, undirected = true)
                                mapaRef.collection("pois").get()
                                    .addOnSuccessListener { poiSnap ->
                                        val poiIds = poiSnap.documents.mapNotNull { it.getString("id") ?: it.id }
                                        val poiNodeIds = poiIds.filter { nodesById.containsKey(it) }
                                        onResult(Result.success(LoadedGraph(graph, nodesById, poiNodeIds)))
                                    }
                                    .addOnFailureListener { e ->
                                        val allNodeIds = nodes.map { it.id }
                                        onResult(Result.success(LoadedGraph(graph, nodesById, allNodeIds)))
                                    }
                            }
                    }
                    .addOnFailureListener { e -> onResult(Result.failure(e)) }
            }
            .addOnFailureListener { e -> onResult(Result.failure(e)) }
    }

    // Minimal representation of forma used by the loader
    private data class FormaData(val x: Float, val z: Float, val h: Float, val w: Float, val tipo: String, val rotation: Float, val isWalkable: Boolean)

    // Helper: test whether a world point lies within any walkable forma (same semantics as MinimapView)
    private fun isPointInWalkable(wx: Float, wz: Float, formas: List<FormaData>): Boolean {
        val walkables = formas.filter { it.isWalkable }
        if (walkables.isEmpty()) return true // no walkable shapes -> allow everywhere
        for (f in walkables) {
            val cx = f.x + f.w * 0.5f
            val cz = f.z + f.h * 0.5f
            val localX = wx - cx
            val localZ = wz - cz
            val rot = f.rotation
            val lx: Float
            val lz: Float
            if (rot != 0f) {
                val a = Math.toRadians((-rot).toDouble()).toFloat()
                val cosA = kotlin.math.cos(a)
                val sinA = kotlin.math.sin(a)
                lx = localX * cosA - localZ * sinA
                lz = localX * sinA + localZ * cosA
            } else {
                lx = localX; lz = localZ
            }
            when (f.tipo.lowercase()) {
                "circulo", "circle" -> {
                    val r = kotlin.math.min(f.w, f.h) * 0.5f
                    if (lx * lx + lz * lz <= r * r) return true
                }
                "triangulo" -> {
                    if (kotlin.math.abs(lx) <= f.w * 0.5f && kotlin.math.abs(lz) <= f.h * 0.5f) return true
                }
                else -> {
                    if (kotlin.math.abs(lx) <= f.w * 0.5f && kotlin.math.abs(lz) <= f.h * 0.5f) return true
                }
            }
        }
        return false
    }

    /**
     * Enhanced connectivity builder that respects walkable areas. If formas contains any walkable shapes,
     * only waypoints and nodes inside walkable shapes are kept.
     */
    private fun enhanceGraphConnectivity(
        originalNodes: List<Node>,
        originalEdges: List<Edge>,
        formas: List<FormaData>
    ): Pair<List<Node>, List<Edge>> {
        // If no formas provided, fallback to original implementation with empty formas
        if (originalNodes.isEmpty()) return originalNodes to originalEdges

        // Calculate bounds
        val minX = originalNodes.minOf { it.x }
        val maxX = originalNodes.maxOf { it.x }
        val minY = originalNodes.minOf { it.y }
        val maxY = originalNodes.maxOf { it.y }

        val width = maxX - minX
        val height = maxY - minY

        // Create waypoint mesh spacing ~1.5m
        val gridSpacing = 1.5f
        val cols = (width / gridSpacing).toInt().coerceAtLeast(2)
        val rows = (height / gridSpacing).toInt().coerceAtLeast(2)

        val allNodes = mutableListOf<Node>()

        // If there are walkable shapes, we will filter original nodes to those inside walkable areas
        val anyWalkable = formas.any { it.isWalkable }
        val filteredOriginalNodes = if (anyWalkable) {
            originalNodes.filter { isPointInWalkable(it.x, it.y, formas) }
        } else originalNodes

        allNodes.addAll(filteredOriginalNodes)

        val waypointNodes = mutableListOf<Node>()
        for (row in 0..rows) {
            for (col in 0..cols) {
                val x = minX + (width * col / cols)
                val y = minY + (height * row / rows)
                val wpId = "waypoint_${row}_${col}"
                // If anyWalkable: include waypoint only if inside a walkable forma
                if (!anyWalkable || isPointInWalkable(x, y, formas)) {
                    waypointNodes.add(Node(id = wpId, x = x, y = y))
                }
            }
        }
        allNodes.addAll(waypointNodes)

        val allEdges = mutableListOf<Edge>()
        // Keep original edges only if both endpoints survived the filtering
        val keptNodeIds = allNodes.map { it.id }.toSet()
        for (e in originalEdges) {
            if (e.from in keptNodeIds && e.to in keptNodeIds) allEdges.add(e)
        }

        // Connect waypoints neighbors (4-connectivity + diagonals) but only for waypoints that exist (we filtered)
        for (row in 0..rows) {
            for (col in 0..cols) {
                val currentId = "waypoint_${row}_${col}"
                if (currentId !in keptNodeIds) continue
                // Right
                if (col < cols) {
                    val rightId = "waypoint_${row}_${col + 1}"
                    if (rightId in keptNodeIds) allEdges.add(Edge(currentId, rightId, gridSpacing))
                }
                // Down
                if (row < rows) {
                    val downId = "waypoint_${row + 1}_${col}"
                    if (downId in keptNodeIds) allEdges.add(Edge(currentId, downId, gridSpacing))
                }
                // Diagonals
                if (row < rows && col < cols) {
                    val diagId = "waypoint_${row + 1}_${col + 1}"
                    if (diagId in keptNodeIds) {
                        val diagDist = kotlin.math.sqrt(2f) * gridSpacing
                        allEdges.add(Edge(currentId, diagId, diagDist))
                    }
                }
                if (row < rows && col > 0) {
                    val diagId = "waypoint_${row + 1}_${col - 1}"
                    if (diagId in keptNodeIds) {
                        val diagDist = kotlin.math.sqrt(2f) * gridSpacing
                        allEdges.add(Edge(currentId, diagId, diagDist))
                    }
                }
            }
        }

        // Connect each original node to nearby waypoints (max 3) but only to waypoints that exist and are walkable
        val maxConnectionDistance = gridSpacing * 2f
        for (origNode in filteredOriginalNodes) {
            val nearbyWaypoints = waypointNodes
                .map { wp ->
                    val dx = wp.x - origNode.x
                    val dy = wp.y - origNode.y
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    wp to dist
                }
                .filter { it.second <= maxConnectionDistance }
                .sortedBy { it.second }
                .take(3)

            for ((wp, dist) in nearbyWaypoints) {
                if (wp.id in keptNodeIds) allEdges.add(Edge(origNode.id, wp.id, dist))
            }
        }

        // Connect original nodes that are close to each other (<=3m)
        val existingConnections = originalEdges.flatMap { listOf("${it.from}_${it.to}", "${it.to}_${it.from}") }.toSet()
        for (i in filteredOriginalNodes.indices) {
            for (j in i + 1 until filteredOriginalNodes.size) {
                val n1 = filteredOriginalNodes[i]
                val n2 = filteredOriginalNodes[j]
                val key = "${n1.id}_${n2.id}"
                val keyRev = "${n2.id}_${n1.id}"
                if (key !in existingConnections && keyRev !in existingConnections) {
                    val dx = n2.x - n1.x
                    val dy = n2.y - n1.y
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (dist <= 3f) {
                        allEdges.add(Edge(n1.id, n2.id, dist))
                    }
                }
            }
        }

        return allNodes to allEdges
    }
}
