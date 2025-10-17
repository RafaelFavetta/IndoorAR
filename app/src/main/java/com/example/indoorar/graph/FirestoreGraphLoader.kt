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
                        val graph = Graph.from(nodes, edges, undirected = true)
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
}
