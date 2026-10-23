package com.resqroute.service;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.resqroute.dto.DispatchResponse;
import com.resqroute.dto.EmergencySummary;
import com.resqroute.dto.TrackingResponse;
import com.resqroute.exception.NoAvailableAmbulanceException;
import com.resqroute.exception.NoHospitalException;
import com.resqroute.exception.InvalidStatusException;
import com.resqroute.exception.NotFoundException;
import com.resqroute.model.Ambulance;
import com.resqroute.model.Dispatch;
import com.resqroute.model.Emergency;
import com.resqroute.model.Hospital;
import com.resqroute.repository.AmbulanceRepository;
import com.resqroute.repository.DispatchRepository;
import com.resqroute.repository.EmergencyRepository;
import com.resqroute.repository.HospitalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The dispatch orchestrator of ResQRoute.
 *
 * Responsibilities are clearly separated:
 *
 *   DIJKSTRA (our algorithm on our graph)
 *     -> decides WHICH available ambulance to send
 *     -> decides WHICH hospital to deliver to
 *     (single run from the victim node gives the traffic-adjusted
 *      cost to every ambulance and every hospital at once)
 *
 *   OSRM (external road-routing service)
 *     -> describes the practical road route for the CHOSEN pair:
 *        geometry, road distance, road duration.
 *        If OSRM is unreachable, a documented straight-line
 *        fallback keeps the dispatch working.
 *
 *   TRAFFIC
 *     -> multiplies graph edge weights (so it changes the Dijkstra
 *        decision) and multiplies ETA durations.
 */
@Service
public class RouteService {

    private static final Logger log = LoggerFactory.getLogger(RouteService.class);

    /**
     * Source of the private victim tracking tokens. SecureRandom is a
     * cryptographically secure generator; 32 random bytes (64 hex
     * characters) make guessing or enumerating a token impossible.
     */
    private static final SecureRandom TRACKING_TOKEN_RANDOM = new SecureRandom();
    private static final int TRACKING_TOKEN_BYTES = 32;

    /**
     * Fallback estimates used when OSRM is unreachable.
     * Roads are ~30% longer than the crow flies; an ambulance in
     * traffic averages ~40 km/h. Documented estimates, not live data.
     */
    private static final double FALLBACK_ROAD_FACTOR = 1.3;
    private static final double FALLBACK_SPEED_KMH = 40.0;

    private final EmergencyRepository emergencyRepository;
    private final DispatchRepository dispatchRepository;
    private final AmbulanceRepository ambulanceRepository;
    private final HospitalRepository hospitalRepository;

    private final GraphService graphService;
    private final DijkstraService dijkstraService;
    private final TrafficService trafficService;
    private final OsrmService osrmService;

    private final ObjectMapper objectMapper;

    public RouteService(EmergencyRepository emergencyRepository,
                        DispatchRepository dispatchRepository,
                        AmbulanceRepository ambulanceRepository,
                        HospitalRepository hospitalRepository,
                        GraphService graphService,
                        DijkstraService dijkstraService,
                        TrafficService trafficService,
                        OsrmService osrmService,
                        ObjectMapper objectMapper) {
        this.emergencyRepository = emergencyRepository;
        this.dispatchRepository = dispatchRepository;
        this.ambulanceRepository = ambulanceRepository;
        this.hospitalRepository = hospitalRepository;
        this.graphService = graphService;
        this.dijkstraService = dijkstraService;
        this.trafficService = trafficService;
        this.osrmService = osrmService;
        this.objectMapper = objectMapper;
    }

    /* ==================================================================
       DISPATCH
    ================================================================== */

    /**
     * Attempts to dispatch an ambulance to a PENDING emergency.
     *
     * Runs in its own transaction. If anything fails, everything this
     * attempt changed rolls back — the emergency claim, the ambulance
     * reservation and the dispatch row can never be left half-done:
     * an ambulance is never DISPATCHED without a matching dispatch,
     * and an emergency is never DISPATCHED twice.
     */
    @Transactional
    public DispatchResponse dispatchToEmergency(Long emergencyId) {

        Emergency emergency = emergencyRepository.findById(emergencyId)
                .orElseThrow(() ->
                        new NotFoundException("Emergency not found: " + emergencyId));

        if (!Emergency.STATUS_PENDING.equals(emergency.getStatus())) {
            throw new InvalidStatusException(
                    "Emergency #" + emergencyId + " is already "
                            + emergency.getStatus());
        }

        /* ---------------------------------------------------------------
            0. Claim the emergency ATOMICALLY (PENDING -> DISPATCHED).
               Exactly one racing request can win; every loser rolls
               back and the emergency ends up DISPATCHED exactly once.
               (Same pattern as the ambulance reservation in step 6.)
        --------------------------------------------------------------- */
        int claimedRows = emergencyRepository.updateStatusIfCurrent(
                emergency.getId(),
                Emergency.STATUS_DISPATCHED,
                Emergency.STATUS_PENDING);

        if (claimedRows != 1) {
            throw new InvalidStatusException(
                    "Emergency #" + emergencyId
                            + " was dispatched by another request.");
        }

        // keep the in-memory entity consistent with the UPDATE
        emergency.setStatus(Emergency.STATUS_DISPATCHED);

        double victimLatitude = emergency.getSourceLatitude();
        double victimLongitude = emergency.getSourceLongitude();

        /* ---------------------------------------------------------------
           1. Candidates: AVAILABLE ambulances with valid GPS
        --------------------------------------------------------------- */
        List<Ambulance> availableAmbulances = ambulanceRepository
                .findByStatusIgnoreCase(Ambulance.STATUS_AVAILABLE);

        // Units without a usable GPS position can never be routed to
        // the victim: they are excluded, and the victim gets a
        // truthful reason why nothing was dispatched yet.
        List<Ambulance> candidates = availableAmbulances.stream()
                .filter(Ambulance::hasValidGps)
                .toList();

        if (candidates.isEmpty()) {
            throw new NoAvailableAmbulanceException(
                    noAmbulanceReason(availableAmbulances.size()),
                    emergency.getId());
        }

        /* ---------------------------------------------------------------
           2. Hospitals with valid GPS
        --------------------------------------------------------------- */
        List<Hospital> hospitals = hospitalRepository.findAll()
                .stream()
                .filter(Hospital::hasValidGps)
                .toList();

        if (hospitals.isEmpty()) {
            throw new NoHospitalException(
                    "No hospital with valid coordinates is registered.");
        }

        /* ---------------------------------------------------------------
           3. Build the traffic-weighted graph and run DIJKSTRA once
              from the victim node.
        --------------------------------------------------------------- */
        List<GraphService.DynamicPoint> dynamicPoints = new ArrayList<>();

        int victimIndex = 0;
        dynamicPoints.add(new GraphService.DynamicPoint(
                "VICTIM", victimLatitude, victimLongitude));

        int ambulanceFirstIndex = 1;
        for (Ambulance ambulance : candidates) {
            dynamicPoints.add(new GraphService.DynamicPoint(
                    "AMBULANCE#" + ambulance.getId(),
                    ambulance.getLatitude(), ambulance.getLongitude()));
        }

        int hospitalFirstIndex = dynamicPoints.size();
        for (Hospital hospital : hospitals) {
            dynamicPoints.add(new GraphService.DynamicPoint(
                    "HOSPITAL#" + hospital.getId(),
                    hospital.getLatitude(), hospital.getLongitude()));
        }

        GraphService.BuiltGraph built = graphService.buildGraph(dynamicPoints);

        DijkstraService.DijkstraResult dijkstra =
                dijkstraService.findShortestPaths(
                        built.graph(),
                        built.dynamicPointIds().get(victimIndex));

        /* ---------------------------------------------------------------
           4. Rank ambulances by Dijkstra cost (traffic-adjusted km).
              Unreachable vertices fall back to the straight-line cost
              multiplied by the victim-zone traffic factor.
        --------------------------------------------------------------- */
        record RankedAmbulance(Ambulance ambulance, double cost, int graphId) {
        }

        List<RankedAmbulance> rankedAmbulances = new ArrayList<>();

        for (int i = 0; i < candidates.size(); i++) {
            Ambulance ambulance = candidates.get(i);
            int graphId = built.dynamicPointIds().get(ambulanceFirstIndex + i);

            double cost = dijkstra.costTo(graphId);

            if (!dijkstra.isReachable(graphId)) {
                cost = fallbackCostKm(ambulance.getLatitude(),
                        ambulance.getLongitude(),
                        victimLatitude, victimLongitude);
            }

            rankedAmbulances.add(new RankedAmbulance(ambulance, cost, graphId));
        }

        rankedAmbulances.sort(Comparator
                .comparingDouble(RankedAmbulance::cost)
                .thenComparing(r -> r.ambulance().getId()));

        /* ---------------------------------------------------------------
           5. Rank hospitals by Dijkstra cost from the same run.
        --------------------------------------------------------------- */
        record RankedHospital(Hospital hospital, double cost, int graphId) {
        }

        List<RankedHospital> rankedHospitals = new ArrayList<>();

        for (int i = 0; i < hospitals.size(); i++) {
            Hospital hospital = hospitals.get(i);
            int graphId = built.dynamicPointIds().get(hospitalFirstIndex + i);

            double cost = dijkstra.costTo(graphId);

            if (!dijkstra.isReachable(graphId)) {
                cost = fallbackCostKm(victimLatitude, victimLongitude,
                        hospital.getLatitude(), hospital.getLongitude());
            }

            rankedHospitals.add(new RankedHospital(hospital, cost, graphId));
        }

        rankedHospitals.sort(Comparator
                .comparingDouble(RankedHospital::cost)
                .thenComparing(r -> r.hospital().getId()));

        Hospital hospital = rankedHospitals.get(0).hospital();

        if (log.isInfoEnabled()) {
            List<String> costs = rankedAmbulances.stream()
                    .map(r -> r.ambulance().getVehicleNumber()
                            + "=" + String.format("%.2f", r.cost()))
                    .toList();
            log.info("Dijkstra ranking for emergency #{}: {}",
                    emergencyId, costs);
        }

        /* ---------------------------------------------------------------
           6. Reserve the best ambulance ATOMICALLY.
              If another emergency won the race for it, try the next
              ranked ambulance. This is what prevents the same
              ambulance being assigned to two emergencies.
        --------------------------------------------------------------- */
        Ambulance reserved = null;

        for (RankedAmbulance ranked : rankedAmbulances) {
            int rows = ambulanceRepository.updateStatusIfCurrent(
                    ranked.ambulance().getId(),
                    Ambulance.STATUS_DISPATCHED,
                    Ambulance.STATUS_AVAILABLE);

            if (rows == 1) {
                reserved = ranked.ambulance();

                /* keep the in-memory instance consistent with the
                   database UPDATE (the @Modifying query bypasses the
                   persistence context) */
                reserved.setStatus(Ambulance.STATUS_DISPATCHED);
                break;
            }

            log.info("Ambulance #{} lost the reservation race, trying next",
                    ranked.ambulance().getId());
        }

        if (reserved == null) {
            throw new NoAvailableAmbulanceException(emergency.getId());
        }

        /* ---------------------------------------------------------------
           7. OSRM: practical routes for the CHOSEN ambulance/hospital.
        --------------------------------------------------------------- */
        OsrmService.OsrmRoute toVictim = osrmService
                .getRoute(reserved.getLatitude(), reserved.getLongitude(),
                        victimLatitude, victimLongitude)
                .orElse(fallbackRoute(reserved.getLatitude(), reserved.getLongitude(),
                        victimLatitude, victimLongitude));

        OsrmService.OsrmRoute toHospital = osrmService
                .getRoute(victimLatitude, victimLongitude,
                        hospital.getLatitude(), hospital.getLongitude())
                .orElse(fallbackRoute(victimLatitude, victimLongitude,
                        hospital.getLatitude(), hospital.getLongitude()));

        // Traffic on the ambulance -> victim corridor
        // (single, centralized midpoint sampling rule — TrafficService)
        String trafficLevel = trafficService.corridorTrafficLevel(
                reserved.getLatitude(), reserved.getLongitude(),
                victimLatitude, victimLongitude);

        double toVictimDurationMin = trafficService.adjustDurationMinutes(
                toVictim.durationSeconds() / 60.0, trafficLevel);

        // Traffic on the victim -> hospital corridor
        String hospitalTrafficLevel = trafficService.corridorTrafficLevel(
                victimLatitude, victimLongitude,
                hospital.getLatitude(), hospital.getLongitude());

        double toHospitalDurationMin = trafficService.adjustDurationMinutes(
                toHospital.durationSeconds() / 60.0, hospitalTrafficLevel);

        /* ---------------------------------------------------------------
           8. Persist the dispatch and mark the emergency DISPATCHED.
        --------------------------------------------------------------- */
        Dispatch dispatch = new Dispatch();
        dispatch.setCreatedAt(Instant.now());
        dispatch.setStatus(Dispatch.STATUS_ACTIVE);
        dispatch.setEmergency(emergency);
        dispatch.setAmbulance(reserved);
        dispatch.setHospital(hospital);
        dispatch.setAmbulanceToVictimDistanceKm(round(toVictim.distanceKm()));
        dispatch.setAmbulanceToVictimDurationMin(round(toVictimDurationMin));
        dispatch.setVictimToHospitalDistanceKm(round(toHospital.distanceKm()));
        dispatch.setVictimToHospitalDurationMin(round(toHospitalDurationMin));
        dispatch.setTrafficLevel(trafficLevel);
        dispatch.setAmbulanceToVictimRouteJson(toJson(toVictim.coordinates()));
        dispatch.setVictimToHospitalRouteJson(toJson(toHospital.coordinates()));

        // private tracking token: the victim's ONLY key to this dispatch
        dispatch.setTrackingToken(newTrackingToken());
        dispatch.setTrackingTokenCreatedAt(Instant.now());

        dispatch = dispatchRepository.save(dispatch);

        // (the emergency was already atomically claimed DISPATCHED at
        //  the start of this transaction — no second write is needed)

        final Ambulance chosenAmbulance = reserved;
        final double chosenCost = rankedAmbulances.stream()
                .filter(r -> r.ambulance().getId().equals(chosenAmbulance.getId()))
                .findFirst()
                .map(RankedAmbulance::cost)
                .orElse(-1.0);

        log.info("Emergency #{} -> ambulance #{} ({}), hospital {} ({}), "
                        + "Dijkstra cost {}, dispatch #{}",
                emergencyId,
                reserved.getId(), reserved.getVehicleNumber(),
                hospital.getId(), hospital.getName(),
                String.format("%.2f", chosenCost),
                dispatch.getId());

        return toDispatchResponse(dispatch);
    }

    /* ==================================================================
       DASHBOARD SNAPSHOT (no in-memory state — everything from the DB)
    ================================================================== */

    /** Free, dispatchable ambulances (used by the pending queue). */
    @Transactional
    public long countAvailableAmbulances() {
        return ambulanceRepository
                .findByStatusIgnoreCase(Ambulance.STATUS_AVAILABLE)
                .stream()
                .filter(Ambulance::hasValidGps)
                .count();
    }

    /**
     * Everything the live dashboard needs, straight from the database.
     * Survives restarts by construction: there is no controller memory.
     *
     * Read-only transaction: the dispatch -> emergency / ambulance /
     * hospital associations are loaded lazily while the response is
     * built, so the session must be open for the whole snapshot and
     * must not depend on Open Session In View.
     */
    @Transactional(readOnly = true)
    public DashboardSnapshot latestSnapshot() {

        Dispatch latest = dispatchRepository
                .findFirstByStatusOrderByCreatedAtDesc(Dispatch.STATUS_ACTIVE)
                .orElseGet(() -> dispatchRepository
                        .findFirstByOrderByCreatedAtDesc()
                        .orElse(null));

        List<DispatchResponse> active = dispatchRepository
                .findByStatusOrderByCreatedAtDesc(Dispatch.STATUS_ACTIVE)
                .stream()
                .map(this::toDispatchResponse)
                .toList();

        List<EmergencySummary> pending = emergencyRepository
                .findByStatusOrderByCreatedAtAsc(Emergency.STATUS_PENDING)
                .stream()
                .map(EmergencySummary::from)
                .toList();

        return new DashboardSnapshot(
                latest == null ? null : toDispatchResponse(latest),
                active,
                pending
        );
    }

    /**
     * Response of GET /route/latest.
     */
    public record DashboardSnapshot(
            DispatchResponse dispatch,
            List<DispatchResponse> activeDispatches,
            List<EmergencySummary> pendingEmergencies) {
    }

    /**
     * ALL currently active dispatches, each with its OWN live ambulance
     * GPS (read fresh from the ambulance row), routes, ETA and traffic.
     * Response of GET /route/active — the multi-dispatch view the
     * Command Center renders: every dispatch is completely
     * independent, and updating one ambulance can never affect
     * another. Staff-only.
     */
    @Transactional(readOnly = true)
    public List<DispatchResponse> activeDispatches() {
        return dispatchRepository
                .findByStatusOrderByCreatedAtDesc(Dispatch.STATUS_ACTIVE)
                .stream()
                .map(this::toDispatchResponse)
                .toList();
    }

    /* ==================================================================
       PRIVATE VICTIM TRACKING (token authorized, no ids exposed)
    ================================================================== */

    /**
     * The dispatch a tracking token points to, projected into the
     * victim-safe TrackingResponse. Read-only: the track page polls
     * it every few seconds for the live ambulance GPS position.
     *
     * A valid token always finds its own dispatch, even after
     * completion (the victim sees the final "completed" state);
     * an unknown or mistyped token gets a clean 404 and can never
     * expose another victim's dispatch.
     */
    @Transactional(readOnly = true)
    public TrackingResponse trackingByToken(String token) {
        Dispatch dispatch = dispatchRepository
                .findByTrackingToken(token)
                .orElseThrow(() -> new NotFoundException(
                        "Invalid or expired tracking link."));

        Ambulance ambulance = dispatch.getAmbulance();

        boolean gpsAvailable = ambulance.hasValidGps();

        return new TrackingResponse(
                dispatch.getStatus(),
                dispatch.getEmergency().getStatus(),

                ambulance.getVehicleNumber(),
                ambulance.getStatus(),

                gpsAvailable ? ambulance.getLatitude() : null,
                gpsAvailable ? ambulance.getLongitude() : null,
                gpsAvailable,

                dispatch.getEmergency().getSourceLatitude(),
                dispatch.getEmergency().getSourceLongitude(),

                dispatch.getAmbulanceToVictimDistanceKm(),
                dispatch.getAmbulanceToVictimDurationMin(),
                dispatch.getVictimToHospitalDistanceKm(),
                dispatch.getVictimToHospitalDurationMin(),

                dispatch.getTrafficLevel(),

                dispatch.getHospital().getName(),
                dispatch.getHospital().getLocation(),

                fromJson(dispatch.getAmbulanceToVictimRouteJson()),
                fromJson(dispatch.getVictimToHospitalRouteJson())
        );
    }

    /* ==================================================================
       Mapping helpers
    ================================================================== */

    public DispatchResponse toDispatchResponse(Dispatch dispatch) {
        return new DispatchResponse(
                dispatch.getId(),
                dispatch.getStatus(),
                dispatch.getCreatedAt(),

                dispatch.getEmergency().getId(),
                dispatch.getEmergency().getStatus(),
                dispatch.getEmergency().getSeverity(),
                dispatch.getEmergency().getSourceLatitude(),
                dispatch.getEmergency().getSourceLongitude(),

                dispatch.getAmbulance().getId(),
                dispatch.getAmbulance().getVehicleNumber(),
                dispatch.getAmbulance().getStatus(),
                dispatch.getAmbulance().getLocation(),
                dispatch.getAmbulance().getLatitude(),
                dispatch.getAmbulance().getLongitude(),

                dispatch.getHospital().getId(),
                dispatch.getHospital().getName(),
                dispatch.getHospital().getLocation(),

                dispatch.getAmbulanceToVictimDistanceKm(),
                dispatch.getAmbulanceToVictimDurationMin(),
                dispatch.getVictimToHospitalDistanceKm(),
                dispatch.getVictimToHospitalDurationMin(),

                dispatch.getTrafficLevel(),

                dispatch.getTrackingToken(),

                fromJson(dispatch.getAmbulanceToVictimRouteJson()),
                fromJson(dispatch.getVictimToHospitalRouteJson())
        );
    }

    /**
     * New random tracking token: 32 secure-random bytes as 64 hex
     * characters. Not guessable, not sequential, and unique in
     * practice (collision probability ~ 2^-256).
     */
    private String newTrackingToken() {
        byte[] bytes = new byte[TRACKING_TOKEN_BYTES];
        TRACKING_TOKEN_RANDOM.nextBytes(bytes);

        StringBuilder hex = new StringBuilder(2 * bytes.length);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }

    private String toJson(List<List<Double>> coordinates) {
        try {
            return objectMapper.writeValueAsString(coordinates);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Could not serialize route geometry", exception);
        }
    }

    private List<List<Double>> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }

        try {
            return objectMapper.readValue(
                    json, new TypeReference<List<List<Double>>>() {
                    });
        } catch (JacksonException exception) {
            log.error("Could not parse stored route geometry: {}",
                    exception.getMessage());
            return List.of();
        }
    }

    /* ==================================================================
       Fallback + small helpers
    ================================================================== */

    /**
     * Straight-line based estimate used only when OSRM is unreachable.
     */
    private OsrmService.OsrmRoute fallbackRoute(double fromLat, double fromLon,
                                                double toLat, double toLon) {
        double straightKm = trafficService.haversineKm(fromLat, fromLon, toLat, toLon);

        double roadKm = straightKm * FALLBACK_ROAD_FACTOR;

        double durationSeconds = (roadKm / FALLBACK_SPEED_KMH) * 3600.0;

        List<List<Double>> coordinates = List.of(
                List.of(fromLon, fromLat),
                List.of(toLon, toLat)
        );

        log.warn("OSRM unavailable — using straight-line fallback ({} km est.)",
                String.format("%.2f", roadKm));

        return new OsrmService.OsrmRoute(roadKm, durationSeconds, coordinates);
    }

    private double fallbackCostKm(double fromLat, double fromLon,
                                  double toLat, double toLon) {
        return trafficService.effectiveEdgeCostKm(
                trafficService.haversineKm(fromLat, fromLon, toLat, toLon),
                fromLat, fromLon, toLat, toLon
        );
    }

    /**
     * Truthful reason for the NO_AMBULANCE_AVAILABLE 409: either the
     * fleet is exhausted, or units exist but none has reported a
     * usable GPS position yet.
     */
    private String noAmbulanceReason(int availableCount) {
        if (availableCount == 0) {
            return "No ambulance is currently AVAILABLE. The emergency is "
                    + "queued as PENDING and will be dispatched as soon as "
                    + "an ambulance becomes available.";
        }
        return availableCount + " ambulance(s) are AVAILABLE, but none has "
                + "a valid GPS position yet. The emergency is queued as "
                + "PENDING and will be dispatched as soon as one reports "
                + "its location.";
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
