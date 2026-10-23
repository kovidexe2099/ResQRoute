package com.resqroute.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests of Dijkstra's algorithm on hand-built graphs
 * with known answers.
 */
class DijkstraServiceTests {

    private final DijkstraService dijkstra = new DijkstraService();

    @Test
    void shortestPathOnKnownGraph() {
        //        0 --1-- 1
        //        |       |
        //        4       2
        //        |       |
        //        2 --3-- 3
        RoutingGraph graph = new RoutingGraph();
        int a = graph.addNode("A", 0, 0);
        int b = graph.addNode("B", 0, 1);
        int c = graph.addNode("C", 1, 0);
        int d = graph.addNode("D", 1, 1);

        graph.connect(a, b, 1);
        graph.connect(b, d, 2);
        graph.connect(a, c, 4);
        graph.connect(c, d, 3);

        var result = dijkstra.findShortestPaths(graph, a);

        // A -> B -> D has cost 3 (better than A -> C -> D = 7)
        assertEquals(3.0, result.costTo(d), 1e-9);
        assertEquals(1.0, result.costTo(b), 1e-9);
        assertEquals(4.0, result.costTo(c), 1e-9);
    }

    @Test
    void pathReconstructionReturnsTheShortestPath() {
        RoutingGraph graph = new RoutingGraph();
        int a = graph.addNode("A", 0, 0);
        int b = graph.addNode("B", 0, 1);
        int c = graph.addNode("C", 1, 0);
        int d = graph.addNode("D", 1, 1);

        graph.connect(a, b, 1);
        graph.connect(b, d, 2);
        graph.connect(a, c, 4);
        graph.connect(c, d, 3);

        var result = dijkstra.findShortestPaths(graph, a);

        assertEquals(List.of(a, b, d), result.pathTo(d));
        assertEquals(List.of(a), result.pathTo(a));
    }

    @Test
    void unreachableVertexHasInfiniteCost() {
        RoutingGraph graph = new RoutingGraph();
        int a = graph.addNode("A", 0, 0);
        int island = graph.addNode("Island", 10, 10);   // no edges

        var result = dijkstra.findShortestPaths(graph, a);

        assertFalse(result.isReachable(island));
        assertTrue(Double.isInfinite(result.costTo(island)));
        assertTrue(result.isReachable(a));
    }

    @Test
    void undirectedGraphWorksWithBiggerWeightsFirst() {
        RoutingGraph graph = new RoutingGraph();
        int a = graph.addNode("A", 0, 0);
        int b = graph.addNode("B", 0, 1);
        int c = graph.addNode("C", 0, 2);

        // a direct expensive edge vs a cheap two-hop detour
        graph.connect(a, c, 100);
        graph.connect(a, b, 1);
        graph.connect(b, c, 1);

        var result = dijkstra.findShortestPaths(graph, a);

        // Dijkstra must prefer the 2-hop detour (cost 2) over the
        // direct edge (cost 100), even though the direct edge exists.
        assertEquals(2.0, result.costTo(c), 1e-9);
        assertEquals(List.of(a, b, c), result.pathTo(c));
    }
}
