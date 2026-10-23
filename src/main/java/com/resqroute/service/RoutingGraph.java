package com.resqroute.service;

import java.util.ArrayList;
import java.util.List;

/**
 * The routing graph Dijkstra runs on.
 *
 * VERTEX : a point of the road network — either a landmark node
 *          loaded from the location_node table, or a temporary
 *          node inserted for this request (victim position,
 *          ambulance positions, hospital positions).
 * EDGE   : a road corridor between two vertices. The graph is
 *          undirected, so every edge is stored in both directions.
 * WEIGHT : the effective cost of traversing the corridor:
 *              base kilometres x traffic factor
 *          i.e. "traffic-adjusted kilometres" (see TrafficService).
 */
public class RoutingGraph {

    /**
     * One vertex of the graph.
     */
    public static class GraphNode {
        public final int id;
        public final String name;
        public final double latitude;
        public final double longitude;

        GraphNode(int id, String name, double latitude, double longitude) {
            this.id = id;
            this.name = name;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private final List<GraphNode> nodes = new ArrayList<>();

    /** adjacency.get(i) = edges going out of node i. */
    private final List<List<DijkstraService.Edge>> adjacency = new ArrayList<>();

    /** Adds a vertex and returns its id (its index in the graph). */
    public int addNode(String name, double latitude, double longitude) {
        int id = nodes.size();
        nodes.add(new GraphNode(id, name, latitude, longitude));
        adjacency.add(new ArrayList<>());
        return id;
    }

    /** Connects two vertices with an undirected weighted edge. */
    public void connect(int from, int to, double weight) {
        adjacency.get(from).add(new DijkstraService.Edge(to, weight));
        adjacency.get(to).add(new DijkstraService.Edge(from, weight));
    }

    public int size() {
        return nodes.size();
    }

    public GraphNode node(int id) {
        return nodes.get(id);
    }

    public List<DijkstraService.Edge> edgesFrom(int id) {
        return adjacency.get(id);
    }
}
