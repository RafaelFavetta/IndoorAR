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

                        // Cria malha densa de waypoints para garantir conectividade total
                        val enhancedData = enhanceGraphConnectivity(nodes, edges)
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
                    .addOnFailureListener { e -> onResult(Result.failure(e)) }
            }
            .addOnFailureListener { e -> onResult(Result.failure(e)) }
    }

    /**
     * Cria uma malha densa de waypoints para garantir conectividade total do grafo.
     * Adiciona nós intermediários e conecta todos os nós próximos entre si.
     */
    private fun enhanceGraphConnectivity(
        originalNodes: List<Node>,
        originalEdges: List<Edge>
    ): Pair<List<Node>, List<Edge>> {
        if (originalNodes.isEmpty()) return originalNodes to originalEdges

        // Calcula bounds do mapa
        val minX = originalNodes.minOf { it.x }
        val maxX = originalNodes.maxOf { it.x }
        val minY = originalNodes.minOf { it.y }
        val maxY = originalNodes.maxOf { it.y }

        val width = maxX - minX
        val height = maxY - minY

        // Cria malha de waypoints (espaçamento de ~1.5m)
        val gridSpacing = 1.5f
        val cols = (width / gridSpacing).toInt().coerceAtLeast(2)
        val rows = (height / gridSpacing).toInt().coerceAtLeast(2)

        val allNodes = mutableListOf<Node>()
        allNodes.addAll(originalNodes)

        val waypointNodes = mutableListOf<Node>()
        for (row in 0..rows) {
            for (col in 0..cols) {
                val x = minX + (width * col / cols)
                val y = minY + (height * row / rows)
                val wpId = "waypoint_${row}_${col}"
                waypointNodes.add(Node(id = wpId, x = x, y = y))
            }
        }
        allNodes.addAll(waypointNodes)

        val allEdges = mutableListOf<Edge>()
        allEdges.addAll(originalEdges)

        // Conecta cada waypoint aos seus vizinhos na malha (4-conectividade)
        for (row in 0..rows) {
            for (col in 0..cols) {
                val currentId = "waypoint_${row}_${col}"
                // Direita
                if (col < cols) {
                    val rightId = "waypoint_${row}_${col + 1}"
                    allEdges.add(Edge(currentId, rightId, gridSpacing))
                }
                // Baixo
                if (row < rows) {
                    val downId = "waypoint_${row + 1}_${col}"
                    allEdges.add(Edge(currentId, downId, gridSpacing))
                }
                // Diagonal direita-baixo (para melhor conectividade)
                if (row < rows && col < cols) {
                    val diagId = "waypoint_${row + 1}_${col + 1}"
                    val diagDist = kotlin.math.sqrt(2f) * gridSpacing
                    allEdges.add(Edge(currentId, diagId, diagDist))
                }
                // Diagonal esquerda-baixo
                if (row < rows && col > 0) {
                    val diagId = "waypoint_${row + 1}_${col - 1}"
                    val diagDist = kotlin.math.sqrt(2f) * gridSpacing
                    allEdges.add(Edge(currentId, diagId, diagDist))
                }
            }
        }

        // Conecta cada nó original ao waypoint mais próximo (máximo 3 waypoints)
        val maxConnectionDistance = gridSpacing * 2f
        for (origNode in originalNodes) {
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
                allEdges.add(Edge(origNode.id, wp.id, dist))
            }
        }

        // Conecta nós originais próximos entre si (se não já conectados)
        val existingConnections = originalEdges.flatMap {
            listOf("${it.from}_${it.to}", "${it.to}_${it.from}")
        }.toSet()

        for (i in originalNodes.indices) {
            for (j in i + 1 until originalNodes.size) {
                val n1 = originalNodes[i]
                val n2 = originalNodes[j]
                val key = "${n1.id}_${n2.id}"
                val keyRev = "${n2.id}_${n1.id}"

                if (key !in existingConnections && keyRev !in existingConnections) {
                    val dx = n2.x - n1.x
                    val dy = n2.y - n1.y
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)

                    // Conecta nós próximos (até 3m de distância)
                    if (dist <= 3f) {
                        allEdges.add(Edge(n1.id, n2.id, dist))
                    }
                }
            }
        }

        return allNodes to allEdges
    }
}
