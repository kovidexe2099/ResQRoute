package com.resqroute.service;

import com.resqroute.model.LocationNode;
import com.resqroute.repository.LocationNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds the routing-graph landmark nodes on first start-up.
 *
 * The location_node table models the road network Dijkstra runs on.
 * If the table is empty (fresh database), a default set of
 * Hyderabad landmarks is inserted so the graph is meaningful from
 * the very first request. Existing rows are never touched.
 *
 * These are algorithm infrastructure (like seed hospitals), not
 * user data: edit or add rows freely from the database later.
 */
@Component
public class LocationGraphSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(LocationGraphSeeder.class);

    private final LocationNodeRepository locationNodeRepository;

    public LocationGraphSeeder(LocationNodeRepository locationNodeRepository) {
        this.locationNodeRepository = locationNodeRepository;
    }

    @Override
    public void run(String... args) {
        if (locationNodeRepository.count() > 0) {
            log.info("Routing graph already has {} landmark nodes",
                    locationNodeRepository.count());
            return;
        }

        List<LocationNode> landmarks = List.of(
                new LocationNode("Charminar (Old City)", 17.3616, 78.4747),
                new LocationNode("Secunderabad Station", 17.4399, 78.4983),
                new LocationNode("Begumpet", 17.4440, 78.4626),
                new LocationNode("Tarnaka", 17.4287, 78.5360),
                new LocationNode("Ameerpet", 17.4374, 78.4487),
                new LocationNode("Jubilee Hills", 17.4239, 78.4738),
                new LocationNode("Banjara Hills", 17.4125, 78.4385),
                new LocationNode("Kukatpally", 17.4825, 78.3925),
                new LocationNode("Madhapur", 17.4483, 78.3913),
                new LocationNode("Gachibowli", 17.4400, 78.3480),
                new LocationNode("Kondapur", 17.4600, 78.3600),
                new LocationNode("LB Nagar", 17.3450, 78.5540),
                new LocationNode("Uppal", 17.4000, 78.5580),
                new LocationNode("Patancheru", 17.5850, 78.2680),
                new LocationNode("Mehdipatnam", 17.3940, 78.4390),
                new LocationNode("Paradise Circle", 17.4435, 78.4965)
        );

        locationNodeRepository.saveAll(landmarks);

        log.info("Seeded {} routing-graph landmark nodes "
                        + "(Hyderabad road-network model)",
                landmarks.size());
    }
}
