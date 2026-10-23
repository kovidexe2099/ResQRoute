package com.resqroute.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Dijkstra's shortest-path algorithm.
 *
 * Runs on the traffic-weighted routing graph built by GraphService
 * and returns, for one SOURCE vertex, the minimum effective cost to
 * every other vertex together with the actual shortest path
 * (sequence of vertices).
 *
 * Complexity:
 *   Time  : O((V + E) * log V)   (binary heap)
 *   Space : O(V + E)
 *
 * What exactly is being minimised:
 *   the sum of "traffic-adjusted kilometres" along the path —
 *   base kilometres of every corridor multiplied by the traffic
 *   factor of the zone that corridor passes through.
 */
@Service
public class DijkstraService {

    /**
     * A directed weighted edge of the graph.
     */
    public static class Edge {
        public final int destination;
        public final double weight;

        public Edge(int destination, double weight) {
            this.destination = destination;
            this.weight = weight;
        }
    }

    /**
     * Result of one Dijkstra run: cheapest costs + predecessors.
     */
    public static class DijkstraResult {

        private final double[] distance;
        private final int[] previous;

        DijkstraResult(double[] distance, int[] previous) {
            this.distance = distance;
            this.previous = previous;
        }

        /** Minimum effective cost from the source to the target. */
        public double costTo(int target) {
            return distance[target];
        }

        public boolean isReachable(int target) {
            return Double.isFinite(distance[target]);
        }

        /** The shortest path itself, from source to target (inclusive). */
        public List<Integer> pathTo(int target) {
            List<Integer> path = new ArrayList<>();
            int current = target;

            while (current != -1) {
                path.add(current);
                current = previous[current];
            }

            // path was built backwards, reverse it
            for (int i = 0, j = path.size() - 1; i < j; i++, j--) {
                Integer temp = path.get(i);
                path.set(i, path.get(j));
                path.set(j, temp);
            }

            return path;
        }
    }

    /**
     * Computes the cheapest path from SOURCE to every vertex.
     */
    public DijkstraResult findShortestPaths(RoutingGraph graph, int source) {
        int n = graph.size();

        double[] distance = new double[n];
        int[] previous = new int[n];

        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        Arrays.fill(previous, -1);

        distance[source] = 0;

        // heap entries: { cost so far, vertex id }
        PriorityQueue<double[]> queue = new PriorityQueue<>(
                Comparator.comparingDouble(entry -> entry[0])
        );

        queue.offer(new double[]{0, source});

        while (!queue.isEmpty()) {
            double[] current = queue.poll();

            double currentCost = current[0];
            int currentNode = (int) current[1];

            // stale queue entry (vertex already improved) — skip it
            if (currentCost > distance[currentNode]) {
                continue;
            }

            for (Edge edge : graph.edgesFrom(currentNode)) {
                int neighbor = edge.destination;
                double newCost = currentCost + edge.weight;

                if (newCost < distance[neighbor]) {
                    distance[neighbor] = newCost;
                    previous[neighbor] = currentNode;
                    queue.offer(new double[]{newCost, neighbor});
                }
            }
        }

        return new DijkstraResult(distance, previous);
    }
}
