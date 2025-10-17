package com.example.indoorar.graph

import org.junit.Assert.*
import org.junit.Test

class GraphUtilsTest {

    @Test
    fun testDensifySimpleSegment() {
        val nodes = listOf(
            Node("A", 0f, 0f),
            Node("B", 1f, 0f)
        )
        val pts = PathUtils.densify(nodes, stepMeters = 0.25f)
        // Expect roughly 5 points: 0.0, 0.25, 0.5, 0.75, 1.0
        assertTrue("Expected at least 5 points, got ${pts.size}", pts.size >= 5)
        assertEquals(0f, pts.first().first, 1e-4f)
        assertEquals(1f, pts.last().first, 1e-4f)
    }

    @Test
    fun testAStarSimpleChain() {
        val nodes = listOf(
            Node("A", 0f, 0f),
            Node("B", 1f, 0f),
            Node("C", 2f, 0f)
        )
        val edges = listOf(
            Edge("A", "B", 1f),
            Edge("B", "C", 1f)
        )
        val g = Graph.from(nodes, edges, undirected = true)
        val res = AStarPathfinder.findPath(g, "A", "C")
        assertTrue("Path not found", res.found)
        assertEquals(3, res.nodes.size)
        assertEquals("A", res.nodes.first().id)
        assertEquals("C", res.nodes.last().id)
        assertEquals(2f, res.cost, 1e-4f)
    }
}
