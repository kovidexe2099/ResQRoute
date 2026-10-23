package com.resqroute.service;

import com.resqroute.model.LocationNode;
import com.resqroute.repository.LocationNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the routing graph Dijkstra runs on.
 *
 * Structure:
 *   - Landmark vertices are loaded from the location_node table
 *     (a small model of the road network stored in MySQL).
 *   - Every landmark is connected to its K nearest landmarks.
 *   - Temporary "dynamic" vertices (victim, ambulances, hospitals of
 *     the current request) are connected to their K nearest
 *     landmarks, so real GPS positions join the graph honestly
 *     instead of being reduced to their nearest landmark.
 *
 * Edge weight = TrafficService.effectiveEdgeCostKm:
 *   base kilometres x traffic factor of the corridor midpoint
 *   = traffic-adjusted kilometres.
 */
@Service
public class GraphService {

    private static final Logger log =
            LoggerFactory.getLogger(GraphService.class);

    private final LocationNodeRepository locationNodeRepository;
    private final TrafficService trafficService;

    /**
     * How many nearest landmarks each landmark connects to.
     * 5 keeps the Hyderabad road-network model connected (with 3,
     * the western cluster — Kukatpally/Madhapur/Gachibowli — split
     * off from the central cluster).
     */
    private final int landmarkNeighbours;

    /** How many nearest landmarks a dynamic point connects to. */
    private final int dynamicNeighbours;

    public GraphService(
            LocationNodeRepository locationNodeRepository,
            TrafficService trafficService,
            @Value("${resqroute.graph.landmark-neighbours:5}") int landmarkNeighbours,
            @Value("${resqroute.graph.dynamic-neighbours:3}") int dynamicNeighbours) {
        this.locationNodeRepository = locationNodeRepository;
        this.trafficService = trafficService;
        this.landmarkNeighbours = Math.max(1, landmarkNeighbours);
        this.dynamicNeighbours = Math.max(1, dynamicNeighbours);
    }

    /**
     * One dynamic (request specific) vertex.
     */
    public record DynamicPoint(String name, double latitude, double longitude) {
    }

    /**
     * Builds a graph from the DB landmarks plus the given dynamic points.
     *
     * Returns the graph plus the vertex id assigned to every dynamic
     * point, in the same order as the input list.
     */
    public BuiltGraph buildGraph(List<DynamicPoint> dynamicPoints) {
        RoutingGraph graph = new RoutingGraph();

        List<LocationNode> allLandmarks =
                locationNodeRepository.findAll();

        // A landmark without usable GPS coordinates would corrupt
        // every edge weight computed from it, so such rows are
        // skipped — never trusted.
        List<LocationNode> landmarks = new ArrayList<>(
                allLandmarks.stream()
                        .filter(LocationNode::hasValidCoordinates)
                        .toList());

        if (landmarks.size() < allLandmarks.size()) {
            log.warn("Skipped {} landmark node(s) without valid GPS "
                            + "coordinates",
                    allLandmarks.size() - landmarks.size());
        }

        landmarks.sort(Comparator.comparing(LocationNode::getId));

        // 1. landmark vertices
        for (LocationNode landmark : landmarks) {
            graph.addNode(
                    landmark.getName(),
                    landmark.getLatitude(),
                    landmark.getLongitude()
            );
        }

        int landmarkCount = landmarks.size();

        // 2. landmark <-> nearest landmark edges
        for (int i = 0; i < landmarkCount; i++) {
            connectToNearest(graph, i, landmarkIds(landmarkCount),
                    landmarkNeighbours);
        }

        // 3. dynamic vertices
        List<Integer> dynamicIds = new ArrayList<>();

        for (DynamicPoint point : dynamicPoints) {
            int id = graph.addNode(point.name(), point.latitude(), point.longitude());
            dynamicIds.add(id);

            if (landmarkCount > 0) {
                connectToNearest(graph, id, landmarkIds(landmarkCount),
                        dynamicNeighbours);
            }
        }

        // 4. no landmarks configured at all: connect the dynamic
        //    points directly with each other so Dijkstra still works
        if (landmarkCount == 0 && dynamicIds.size() > 1) {
            for (int i = 0; i < dynamicIds.size(); i++) {
                for (int j = i + 1; j < dynamicIds.size(); j++) {
                    connectPair(graph, dynamicIds.get(i), dynamicIds.get(j));
                }
            }
        }

        return new BuiltGraph(graph, dynamicIds);
    }

    /** Graph + the vertex ids assigned to the dynamic points. */
    public record BuiltGraph(RoutingGraph graph, List<Integer> dynamicPointIds) {
    }

    /* ------------------------------------------------------------------
       Helpers
    ------------------------------------------------------------------ */

    private List<Integer> landmarkIds(int landmarkCount) {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < landmarkCount; i++) {
            ids.add(i);
        }
        return ids;
    }

    /**
     * Connects the vertex 'from' to its K nearest vertices among
     * 'candidates' (excluding itself).
     */
    private void connectToNearest(RoutingGraph graph,
                                  int from,
                                  List<Integer> candidates,
                                  int k) {
        record Neighbour(int id, double distanceKm) {
        }

        List<Neighbour> sorted = new ArrayList<>();
        RoutingGraph.GraphNode fromNode = graph.node(from);

        for (int candidateId : candidates) {
            if (candidateId == from) {
                continue;
            }
            RoutingGraph.GraphNode other = graph.node(candidateId);
            sorted.add(new Neighbour(
                    candidateId,
                    trafficService.haversineKm(
                            fromNode.latitude, fromNode.longitude,
                            other.latitude, other.longitude)
            ));
        }

        sorted.sort(Comparator.comparingDouble(Neighbour::distanceKm));

        int connections = Math.min(k, sorted.size());

        for (int i = 0; i < connections; i++) {
            connectPair(graph, from, sorted.get(i).id());
        }
    }

    /** Adds one undirected traffic-weighted edge. */
    private void connectPair(RoutingGraph graph, int a, int b) {
        RoutingGraph.GraphNode nodeA = graph.node(a);
        RoutingGraph.GraphNode nodeB = graph.node(b);

        double baseKm = trafficService.haversineKm(
                nodeA.latitude, nodeA.longitude,
                nodeB.latitude, nodeB.longitude
        );

        double effectiveCost = trafficService.effectiveEdgeCostKm(
                baseKm,
                nodeA.latitude, nodeA.longitude,
                nodeB.latitude, nodeB.longitude
        );

        graph.connect(a, b, effectiveCost);
    }
}
