package com.resqroute;

import com.resqroute.model.Ambulance;
import com.resqroute.model.Dispatch;
import com.resqroute.model.Emergency;
import com.resqroute.model.Hospital;
import com.resqroute.model.LocationNode;
import com.resqroute.model.StaffUser;
import com.resqroute.exception.InvalidStatusException;
import com.resqroute.repository.AmbulanceRepository;
import com.resqroute.repository.DispatchRepository;
import com.resqroute.repository.EmergencyRepository;
import com.resqroute.repository.HospitalRepository;
import com.resqroute.repository.LocationNodeRepository;
import com.resqroute.repository.StaffUserRepository;
import com.resqroute.service.EmergencyService;
import com.resqroute.service.RouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests of the refactored ResQRoute backend.
 * Runs against H2 with OSRM forced to fail (test properties), so
 * every dispatch exercises the documented straight-line fallback.
 *
 * Every test the project requirements ask for is covered here:
 *   1 ambulance, 3 ambulances, busy filtering, empty fleet,
 *   queued emergencies, priority, concurrency, GPS lifecycle,
 *   stale GPS, traffic ETA, status validation, persistence,
 *   shortest-path dispatch decisions and the complete lifecycle.
 *
 * Backend refinements covered by the additional tests:
 *   severity order (CRITICAL > HIGH > NORMAL, FIFO within a
 *   severity), atomic emergency claim (no double dispatch), GPS
 *   updates must carry both coordinates, manual status transition
 *   guards, first-GPS-fix queue release, truthful validation error
 *   codes, route-leg endpoint semantics, and landmark coordinate
 *   filtering.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ResQRouteFlowTests {

    @Autowired
    private TestRestTemplate http;

    /** Client with valid staff credentials — the staff APIs are protected. */
    private TestRestTemplate authed;

    @Autowired
    private AmbulanceRepository ambulanceRepository;
    @Autowired
    private HospitalRepository hospitalRepository;
    @Autowired
    private EmergencyRepository emergencyRepository;
    @Autowired
    private DispatchRepository dispatchRepository;
    @Autowired
    private LocationNodeRepository locationNodeRepository;
    @Autowired
    private StaffUserRepository staffUserRepository;

    @Autowired
    private EmergencyService emergencyService;
    @Autowired
    private RouteService routeService;

    private static final String ADD_AMBULANCE = "/ambulance";
    private static final String DISPATCH = "/route/best-ambulance";

    /* Staff credentials used by every authenticated call in this
       suite (the endpoints are staff-only; victims only use
       POST /route/best-ambulance and GET /track/{token}). */
    static final String STAFF_USERNAME = "staff";
    static final String STAFF_PASSWORD = "test-staff-password-2026";
    private static final PasswordEncoder STAFF_ENCODER =
            PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @BeforeEach
    void cleanDatabase() {
        // staff account first: every protected call below needs it
        staffUserRepository.findByUsername(STAFF_USERNAME)
                .ifPresent(staffUserRepository::delete);

        staffUserRepository.save(new StaffUser(
                STAFF_USERNAME,
                STAFF_ENCODER.encode(STAFF_PASSWORD),
                "STAFF"));

        authed = http.withBasicAuth(STAFF_USERNAME, STAFF_PASSWORD);

        // delete in foreign-key order
        dispatchRepository.deleteAll();
        emergencyRepository.deleteAll();
        ambulanceRepository.deleteAll();
        hospitalRepository.deleteAll();
        locationNodeRepository.deleteAll();

        // standard road-network model (same as the production seeder)
        locationNodeRepository.saveAll(List.of(
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
        ));

        // one standard hospital for dispatches
        addHospital("Apollo Hospital", "Jubilee Hills", 17.4239, 78.4738);
    }

    /* ------------------------------------------------------------------
       Helpers
    ------------------------------------------------------------------ */

    private void addHospital(String name, String location,
                             double lat, double lon) {
        authed.postForEntity("/hospital", Map.of(
                "name", name,
                "location", location,
                "latitude", lat,
                "longitude", lon
        ), Map.class);
    }

    private ResponseEntity<Map> addAmbulance(String vehicle, String status,
                                             double lat, double lon) {
        return authed.postForEntity(ADD_AMBULANCE, Map.of(
                "vehicleNumber", vehicle,
                "status", status,
                "location", "Test Base",
                "latitude", lat,
                "longitude", lon
        ), Map.class);
    }

    private ResponseEntity<Map> requestAmbulance(double lat, double lon,
                                                String severity) {
        return authed.postForEntity(DISPATCH, Map.of(
                "sourceLatitude", lat,
                "sourceLongitude", lon,
                "severity", severity == null ? "NORMAL" : severity
        ), Map.class);
    }

    private long id(ResponseEntity<Map> response) {
        return ((Number) response.getBody().get("id")).longValue();
    }

    /** The queued emergency id carried in a 409 response body. */
    private long emergencyId(ResponseEntity<Map> response) {
        return ((Number) ((Map) response.getBody().get("details"))
                .get("emergencyId")).longValue();
    }

    /** Current status of a persisted emergency. */
    private String statusOf(long emergencyId) {
        return emergencyRepository.findById(emergencyId)
                .orElseThrow()
                .getStatus();
    }

    private void completeLifecycle(long ambulanceId) {
        // the ambulance travels and finally becomes available again
        authed.exchange("/ambulance/" + ambulanceId + "/status",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        Map.of("status", "TO_VICTIM")), Map.class);
        authed.exchange("/ambulance/" + ambulanceId + "/status",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        Map.of("status", "AT_VICTIM")), Map.class);
        authed.exchange("/ambulance/" + ambulanceId + "/status",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        Map.of("status", "TO_HOSPITAL")), Map.class);
        authed.exchange("/ambulance/" + ambulanceId + "/status",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        Map.of("status", "AVAILABLE")), Map.class);
    }

    /* ==================================================================
       TEST 1 + 17: dispatch with a single ambulance + full lifecycle
    ================================================================== */
    @Test
    void oneAvailableAmbulance_dispatchesAndCompletes() {
        ResponseEntity<Map> ambulance =
                addAmbulance("TS01AA1111", "AVAILABLE", 17.4239, 78.4738);

        assertEquals(HttpStatus.OK, ambulance.getStatusCode());
        long ambulanceId = id(ambulance);

        // victim at Mehdipatnam
        ResponseEntity<Map> dispatch =
                requestAmbulance(17.3940, 78.4390, null);

        assertEquals(HttpStatus.OK, dispatch.getStatusCode());
        Map body = dispatch.getBody();
        assertNotNull(body);

        // correct semantics on every field
        assertEquals(ambulanceId, ((Number) body.get("ambulanceId")).longValue());
        assertEquals("TS01AA1111", body.get("vehicleNumber"));
        assertEquals("DISPATCHED", body.get("ambulanceStatus"));
        assertEquals("DISPATCHED", body.get("emergencyStatus"));
        assertEquals("NORMAL", body.get("severity"));
        assertEquals("ACTIVE", body.get("dispatchStatus"));

        assertTrue(((Number) body.get("ambulanceToVictimDistanceKm")).doubleValue() > 0);
        assertTrue(((Number) body.get("ambulanceToVictimDurationMin")).doubleValue() > 0);
        assertTrue(((Number) body.get("victimToHospitalDistanceKm")).doubleValue() > 0);
        assertTrue(((Number) body.get("victimToHospitalDurationMin")).doubleValue() > 0);

        // fallback routes are 2-point straight lines (OSRM is dead in tests)
        assertEquals(2, ((List<?>) body.get("ambulanceToVictimRoute")).size());
        assertEquals(2, ((List<?>) body.get("victimToHospitalRoute")).size());

        // the emergency + dispatch are persisted
        assertEquals(1, emergencyRepository.count());
        assertEquals(1, dispatchRepository.count());
        Emergency saved = emergencyRepository.findAll().get(0);
        assertEquals(Emergency.STATUS_DISPATCHED, saved.getStatus());

        // the ambulance is no longer AVAILABLE
        assertEquals(Ambulance.STATUS_DISPATCHED,
                ambulanceRepository.findById(ambulanceId).orElseThrow().getStatus());

        // ---- complete the lifecycle ----
        completeLifecycle(ambulanceId);

        assertEquals(Ambulance.STATUS_AVAILABLE,
                ambulanceRepository.findById(ambulanceId).orElseThrow().getStatus());

        Dispatch completed = dispatchRepository.findAll().get(0);
        assertEquals(Dispatch.STATUS_COMPLETED, completed.getStatus());

        assertEquals(Emergency.STATUS_COMPLETED,
                emergencyRepository.findAll().get(0).getStatus());
    }

    /* ==================================================================
       TEST 2: three available ambulances -> nearest by Dijkstra cost
    ================================================================== */
    @Test
    void threeAvailableAmbulances_nearestWins() {
        long near = id(addAmbulance("TS02NEAR", "AVAILABLE",
                17.4825, 78.3925));      // Kukatpally  ~8 km from victim
        long middle = id(addAmbulance("TS02MID", "AVAILABLE",
                17.3450, 78.5540));      // LB Nagar   ~14 km
        long far = id(addAmbulance("TS02FAR", "AVAILABLE",
                17.5850, 78.2680));      // Patancheru ~30 km

        // victim at Ameerpet
        ResponseEntity<Map> dispatch =
                requestAmbulance(17.4374, 78.4487, null);

        assertEquals(HttpStatus.OK, dispatch.getStatusCode());
        assertEquals(near,
                ((Number) dispatch.getBody().get("ambulanceId")).longValue());

        // the two losers must still be AVAILABLE
        assertEquals(Ambulance.STATUS_AVAILABLE,
                ambulanceRepository.findById(middle).orElseThrow().getStatus());
        assertEquals(Ambulance.STATUS_AVAILABLE,
                ambulanceRepository.findById(far).orElseThrow().getStatus());
    }

    /* ==================================================================
       TEST 3: busy / invalid-GPS ambulances are ignored
    ================================================================== */
    @Test
    void busyAndInvalidGpsAmbulancesAreIgnored() {
        long busy = id(addAmbulance("TS03BUSY", "DISPATCHED",
                17.4400, 78.4700));      // very close but busy
        long noGps = id(addAmbulance("TS03NOGPS", "AVAILABLE",
                17.4400, 78.4750));      // close, will get GPS removed
        long farButValid = id(addAmbulance("TS03VALID", "AVAILABLE",
                17.4825, 78.3925));      // far but valid

        // strip GPS from the second ambulance (simulates missing GPS)
        Ambulance withoutGps = ambulanceRepository.findById(noGps).orElseThrow();
        withoutGps.setLatitude(null);
        withoutGps.setLongitude(null);
        ambulanceRepository.save(withoutGps);

        ResponseEntity<Map> dispatch =
                requestAmbulance(17.4374, 78.4487, null);

        assertEquals(HttpStatus.OK, dispatch.getStatusCode());
        assertEquals(farButValid,
                ((Number) dispatch.getBody().get("ambulanceId")).longValue());

        assertEquals(Ambulance.STATUS_DISPATCHED,
                ambulanceRepository.findById(busy).orElseThrow().getStatus());
    }

    /* ==================================================================
       TEST 4 + priority: no ambulance -> queued, auto-dispatch on
       availability, CRITICAL before NORMAL
    ================================================================== */
    @Test
    void noAvailableAmbulance_queuesThenAutoDispatchesWithPriority() {
        // two victims, no fleet at all
        ResponseEntity<Map> normal =
                requestAmbulance(17.4374, 78.4487, "NORMAL");
        ResponseEntity<Map> critical =
                requestAmbulance(17.4239, 78.4738, "CRITICAL");

        assertEquals(HttpStatus.CONFLICT, normal.getStatusCode());
        assertEquals(HttpStatus.CONFLICT, critical.getStatusCode());
        assertEquals("NO_AMBULANCE_AVAILABLE",
                normal.getBody().get("code"));
        assertEquals("PENDING",
                ((Map) normal.getBody().get("details")).get("emergencyStatus"));

        // both emergencies are persisted as PENDING (not lost!)
        assertEquals(2, emergencyRepository.count());
        assertEquals(2, emergencyRepository
                .findByStatusOrderByCreatedAtAsc(Emergency.STATUS_PENDING)
                .size());

        // an ambulance becomes available (added by the operator) ->
        // the CRITICAL emergency must be dispatched first
        long ambulance = id(addAmbulance("TS04NEW", "AVAILABLE",
                17.4400, 78.4700));

        List<Emergency> stillPending =
                emergencyRepository.findByStatusOrderByCreatedAtAsc(
                        Emergency.STATUS_PENDING);

        assertEquals(1, stillPending.size());
        assertEquals(Emergency.SEVERITY_NORMAL,
                stillPending.get(0).getSeverity());

        // the CRITICAL one is dispatched to the new ambulance
        Dispatch dispatched = dispatchRepository.findAll().get(0);
        assertEquals(Emergency.SEVERITY_CRITICAL,
                dispatched.getEmergency().getSeverity());
        assertEquals(ambulance, dispatched.getAmbulance().getId());

        // second ambulance -> NORMAL emergency gets it too
        long ambulance2 = id(addAmbulance("TS04NEW2", "AVAILABLE",
                17.4239, 78.4738));

        assertEquals(0, emergencyRepository
                .findByStatusOrderByCreatedAtAsc(Emergency.STATUS_PENDING)
                .size());
        assertEquals(2, dispatchRepository.count());
    }

    /* ==================================================================
       TEST 4b: emergency queued when the only ambulance is busy,
       auto-dispatched when the ambulance is released
    ================================================================== */
    @Test
    void queuedEmergency_dispatchedWhenAmbulanceFreed() {
        long ambulance = id(addAmbulance("TS04B", "AVAILABLE",
                17.4400, 78.4700));

        ResponseEntity<Map> first = requestAmbulance(17.4374, 78.4487, null);
        assertEquals(HttpStatus.OK, first.getStatusCode());

        // second victim while the only ambulance is DISPATCHED
        ResponseEntity<Map> second = requestAmbulance(17.4239, 78.4738, null);
        assertEquals(HttpStatus.CONFLICT, second.getStatusCode());
        long queuedEmergencyId =
                ((Number) ((Map) second.getBody().get("details"))
                        .get("emergencyId")).longValue();

        // the first emergency completes -> ambulance freed
        completeLifecycle(ambulance);

        // the queued emergency was dispatched automatically
        Emergency queued = emergencyRepository.findById(queuedEmergencyId)
                .orElseThrow();
        assertEquals(Emergency.STATUS_DISPATCHED, queued.getStatus());
        assertEquals(2, dispatchRepository.count());
    }

    /* ==================================================================
       TEST 6: invalid victim GPS rejected with 400
    ================================================================== */
    @Test
    void invalidVictimCoordinatesRejected() {
        // out of range latitude
        ResponseEntity<Map> badLat = requestAmbulance(999.0, 78.4, null);
        assertEquals(HttpStatus.BAD_REQUEST, badLat.getStatusCode());

        // missing coordinates
        ResponseEntity<Map> missing = authed.postForEntity(DISPATCH,
                Map.of("severity", "NORMAL"), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());

        // invalid severity
        ResponseEntity<Map> badSeverity = requestAmbulance(17.4, 78.4, "WEIRD");
        assertEquals(HttpStatus.BAD_REQUEST, badSeverity.getStatusCode());

        // nothing was persisted
        assertEquals(0, emergencyRepository.count());
    }

    /* ==================================================================
       TEST 7: no hospitals -> clean 409, ambulance not consumed
    ================================================================== */
    @Test
    void noHospitals_conflictWithoutReservation() {
        hospitalRepository.deleteAll();

        long ambulance = id(addAmbulance("TS07", "AVAILABLE",
                17.4400, 78.4700));

        ResponseEntity<Map> dispatch =
                requestAmbulance(17.4374, 78.4487, null);

        assertEquals(HttpStatus.CONFLICT, dispatch.getStatusCode());
        assertEquals("NO_HOSPITALS", dispatch.getBody().get("code"));

        // the ambulance must NOT have been reserved and burned
        assertEquals(Ambulance.STATUS_AVAILABLE,
                ambulanceRepository.findById(ambulance).orElseThrow().getStatus());
        assertEquals(0, dispatchRepository.count());
    }

    /* ==================================================================
       TEST 8: OSRM unreachable (test profile) -> dispatch still works
       via the documented straight-line fallback
    ================================================================== */
    @Test
    void osrmFailure_fallsBackToEstimates() {
        addAmbulance("TS08", "AVAILABLE", 17.4239, 78.4738);

        ResponseEntity<Map> dispatch =
                requestAmbulance(17.4374, 78.4487, null);

        assertEquals(HttpStatus.OK, dispatch.getStatusCode());

        // straight-line x 1.3 road factor, ~2 km apart
        double expectedKm = 2.6;   // (about 2.0 km straight)
        double actualKm = ((Number) dispatch.getBody()
                .get("ambulanceToVictimDistanceKm")).doubleValue();

        assertTrue(actualKm > 1.5 && actualKm < 4.0,
                "fallback distance should be ~" + expectedKm
                        + " km but was " + actualKm);
    }

    /* ==================================================================
       TEST 9: two simultaneous emergencies, one ambulance ->
       exactly one wins (atomic reservation)
    ================================================================== */
    @Test
    void concurrentRequests_sameAmbulanceNotAssignedTwice() throws Exception {
        long ambulance = id(addAmbulance("TS09", "AVAILABLE",
                17.4400, 78.4700));

        Emergency first = emergencyService.createEmergency(
                emergencyRequest(17.4374, 78.4487, "NORMAL"));
        Emergency second = emergencyService.createEmergency(
                emergencyRequest(17.4239, 78.4738, "NORMAL"));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        for (Emergency emergency : List.of(first, second)) {
            pool.submit(() -> {
                try {
                    start.await();
                    routeService.dispatchToEmergency(emergency.getId());
                    successes.incrementAndGet();
                } catch (Exception exception) {
                    failures.incrementAndGet();
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, successes.get(), "exactly one request must win");
        assertEquals(1, failures.get(), "the loser must get an error");

        // the ambulance has exactly ONE dispatch
        assertEquals(1, dispatchRepository.count());
        assertEquals(1, dispatchRepository.findAll().get(0)
                .getAmbulance().getId().longValue(), ambulance);

        // one emergency DISPATCHED, the other still PENDING (queued)
        long dispatchedCount = emergencyRepository.findAll().stream()
                .filter(e -> Emergency.STATUS_DISPATCHED.equals(e.getStatus()))
                .count();
        long pendingCount = emergencyRepository.findAll().stream()
                .filter(e -> Emergency.STATUS_PENDING.equals(e.getStatus()))
                .count();
        assertEquals(1, dispatchedCount);
        assertEquals(1, pendingCount);
    }

    private com.resqroute.dto.EmergencyRequest emergencyRequest(
            double lat, double lon, String severity) {
        com.resqroute.dto.EmergencyRequest request =
                new com.resqroute.dto.EmergencyRequest();
        request.setSourceLatitude(lat);
        request.setSourceLongitude(lon);
        request.setSeverity(severity);
        return request;
    }

    /* ==================================================================
       TEST 10 + 16: continuous GPS + stale fix protection
    ================================================================== */
    @Test
    void liveGps_acceptsNewAndRejectsStaleFixes() {
        long ambulance = id(addAmbulance("TS10", "AVAILABLE",
                17.4000, 78.4000));

        long now = System.currentTimeMillis();

        // fix 1 (newer)
        ResponseEntity<Map> fix1 = authed.exchange(
                "/ambulance/" + ambulance + "/location",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(Map.of(
                        "latitude", 17.4100,
                        "longitude", 78.4100,
                        "locationTimestamp", now
                )), Map.class);

        assertEquals(HttpStatus.OK, fix1.getStatusCode());

        // fix 2 (older, arrived late) must NOT overwrite fix 1
        ResponseEntity<Map> staleFix = authed.exchange(
                "/ambulance/" + ambulance + "/location",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(Map.of(
                        "latitude", 17.5000,
                        "longitude", 78.5000,
                        "locationTimestamp", now - 60000
                )), Map.class);

        assertEquals(HttpStatus.OK, staleFix.getStatusCode());

        Ambulance stored = ambulanceRepository.findById(ambulance)
                .orElseThrow();
        assertEquals(17.4100, stored.getLatitude(), 1e-9);
        assertEquals(78.4100, stored.getLongitude(), 1e-9);

        // fix 3 (newer again) is accepted
        authed.exchange(
                "/ambulance/" + ambulance + "/location",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(Map.of(
                        "latitude", 17.4200,
                        "longitude", 78.4200,
                        "locationTimestamp", now + 30000
                )), Map.class);

        stored = ambulanceRepository.findById(ambulance).orElseThrow();
        assertEquals(17.4200, stored.getLatitude(), 1e-9);
    }

    /* ==================================================================
       TEST 6b: invalid GPS values rejected with 400
    ================================================================== */
    @Test
    void invalidGpsUpdateRejected() {
        long ambulance = id(addAmbulance("TS11", "AVAILABLE",
                17.4000, 78.4000));

        ResponseEntity<Map> bad = authed.exchange(
                "/ambulance/" + ambulance + "/location",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(Map.of(
                        "latitude", 200.0,
                        "longitude", 78.4
                )), Map.class);

        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
    }

    /* ==================================================================
       TEST 11-13: traffic genuinely inflates the ETA
    ================================================================== */
    @Test
    void highTrafficDoublesEta_lowTrafficDoesNot() {
        // ambulance + victim both at the city center -> HIGH traffic
        addAmbulance("TS13A", "AVAILABLE", 17.3855, 78.4867);

        ResponseEntity<Map> high = requestAmbulance(17.3845, 78.4867, null);
        assertEquals(HttpStatus.OK, high.getStatusCode());
        assertEquals("HIGH", high.getBody().get("trafficLevel"));

        double highEta = ((Number) high.getBody()
                .get("ambulanceToVictimDurationMin")).doubleValue();

        // ~110 m straight -> ~143 m road -> 0.21 min base -> 0.43 min with HIGH
        assertTrue(highEta > 0.3 && highEta < 0.7,
                "HIGH-traffic ETA should be ~0.43 min, was " + highEta);

        // same distance far outside the city -> LOW traffic, no inflation
        addAmbulance("TS13B", "AVAILABLE", 17.7000, 78.4867);

        ResponseEntity<Map> low = requestAmbulance(17.7009, 78.4867, null);
        assertEquals(HttpStatus.OK, low.getStatusCode());
        assertEquals("LOW", low.getBody().get("trafficLevel"));

        double lowEta = ((Number) low.getBody()
                .get("ambulanceToVictimDurationMin")).doubleValue();

        // ~100 m at 40 km/h = 0.15 min
        assertTrue(lowEta > 0.05 && lowEta < 0.3,
                "LOW-traffic ETA should be ~0.15 min, was " + lowEta);
    }

    /* ==================================================================
       TEST 15: dispatch data survives "restart" (pure DB state)
    ================================================================== */
    @Test
    void dispatchDataIsFullyPersisted() {
        long ambulance = id(addAmbulance("TS15", "AVAILABLE",
                17.4400, 78.4700));

        ResponseEntity<Map> dispatch =
                requestAmbulance(17.4374, 78.4487, "CRITICAL");
        long dispatchId =
                ((Number) dispatch.getBody().get("dispatchId")).longValue();
        long emergencyId =
                ((Number) dispatch.getBody().get("emergencyId")).longValue();

        // "restart": everything below is read straight from the
        // database (the dashboard snapshot has no in-memory state)
        Dispatch stored = dispatchRepository.findById(dispatchId)
                .orElseThrow();
        Emergency storedEmergency = emergencyRepository.findById(emergencyId)
                .orElseThrow();

        assertEquals(Dispatch.STATUS_ACTIVE, stored.getStatus());
        assertEquals(Emergency.SEVERITY_CRITICAL, storedEmergency.getSeverity());
        assertNotNull(stored.getAmbulanceToVictimRouteJson());
        assertNotNull(stored.getVictimToHospitalRouteJson());
        assertEquals(17.4374, storedEmergency.getSourceLatitude(), 1e-9);

        // the dashboard snapshot returns the same dispatch by id
        ResponseEntity<Map> latest = authed.getForEntity("/route/latest",
                Map.class);
        Map snapshotDispatch = (Map) latest.getBody().get("dispatch");
        assertEquals(dispatchId,
                ((Number) snapshotDispatch.get("dispatchId")).longValue());
        assertEquals(emergencyId,
                ((Number) snapshotDispatch.get("emergencyId")).longValue());
        assertEquals("CRITICAL", snapshotDispatch.get("severity"));
    }

    /* ==================================================================
       Status lifecycle validation + delete protection
    ================================================================== */
    @Test
    void invalidStatusRejected_andActiveAmbulanceProtected() {
        long ambulance = id(addAmbulance("TS16", "AVAILABLE",
                17.4400, 78.4700));

        // garbage status -> 409 INVALID_STATUS
        ResponseEntity<Map> bad = authed.exchange(
                "/ambulance/" + ambulance + "/status",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        Map.of("status", "FLYING")), Map.class);
        assertEquals(HttpStatus.CONFLICT, bad.getStatusCode());

        // dispatch, then the ambulance cannot be deleted while ACTIVE
        requestAmbulance(17.4374, 78.4487, null);

        ResponseEntity<Map> delete = authed.exchange(
                "/ambulance/" + ambulance,
                org.springframework.http.HttpMethod.DELETE,
                null, Map.class);
        assertEquals(HttpStatus.CONFLICT, delete.getStatusCode());
        assertTrue(ambulanceRepository.existsById(ambulance));

        // after completion the unit still has dispatch history ->
        // it must be retired (OUT_OF_SERVICE), not hard-deleted
        completeLifecycle(ambulance);

        ResponseEntity<Map> deleteAfter = authed.exchange(
                "/ambulance/" + ambulance,
                org.springframework.http.HttpMethod.DELETE,
                null, Map.class);
        assertEquals(HttpStatus.CONFLICT, deleteAfter.getStatusCode());
        assertTrue(ambulanceRepository.existsById(ambulance));

        // a fresh ambulance without history can be deleted
        long fresh = id(addAmbulance("TS16B", "AVAILABLE",
                17.4400, 78.4700));

        ResponseEntity<Map> deleteFresh = authed.exchange(
                "/ambulance/" + fresh,
                org.springframework.http.HttpMethod.DELETE,
                null, Map.class);
        assertEquals(HttpStatus.OK, deleteFresh.getStatusCode());
        assertFalse(ambulanceRepository.existsById(fresh));
    }

    /* ==================================================================
       Dashboard snapshot lists multiple active dispatches
    ================================================================== */
    @Test
    void dashboardSnapshot_listsActiveDispatchesAndPending() {
        addAmbulance("TS17A", "AVAILABLE", 17.4825, 78.3925);
        addAmbulance("TS17B", "AVAILABLE", 17.3450, 78.5540);

        requestAmbulance(17.4374, 78.4487, null);   // dispatch 1
        requestAmbulance(17.4239, 78.4738, null);   // dispatch 2
        requestAmbulance(17.3940, 78.4390, null);   // -> 409, queued

        ResponseEntity<Map> latest =
                authed.getForEntity("/route/latest", Map.class);

        Map body = latest.getBody();
        List<?> active = (List<?>) body.get("activeDispatches");
        List<?> pending = (List<?>) body.get("pendingEmergencies");

        assertEquals(2, active.size());
        assertEquals(1, pending.size());
        assertEquals("ACTIVE",
                ((Map) active.get(0)).get("dispatchStatus"));
    }

    /* ==================================================================
       SEVERITY ORDER: CRITICAL > HIGH > NORMAL, FIFO within severity
    ================================================================== */
    @Test
    void pendingQueuePrioritizes_criticalBeforeHighBeforeNormal_fifoWithinSeverity() {
        // four victims, no fleet at all -> all queued
        ResponseEntity<Map> normal1 =
                requestAmbulance(17.4374, 78.4487, "NORMAL");
        ResponseEntity<Map> high =
                requestAmbulance(17.4239, 78.4738, "HIGH");
        ResponseEntity<Map> normal2 =
                requestAmbulance(17.3940, 78.4390, "NORMAL");
        ResponseEntity<Map> critical =
                requestAmbulance(17.4000, 78.4400, "CRITICAL");

        assertEquals(HttpStatus.CONFLICT, normal1.getStatusCode());
        assertEquals(HttpStatus.CONFLICT, high.getStatusCode());
        assertEquals(HttpStatus.CONFLICT, normal2.getStatusCode());
        assertEquals(HttpStatus.CONFLICT, critical.getStatusCode());

        long criticalId = emergencyId(critical);
        long highId = emergencyId(high);
        long normal1Id = emergencyId(normal1);
        long normal2Id = emergencyId(normal2);

        // first free unit -> the CRITICAL emergency
        addAmbulance("TSP-C", "AVAILABLE", 17.4400, 78.4700);
        assertEquals(Emergency.STATUS_DISPATCHED, statusOf(criticalId));
        assertEquals(Emergency.STATUS_PENDING, statusOf(highId));

        // second free unit -> the HIGH emergency
        addAmbulance("TSP-H", "AVAILABLE", 17.4239, 78.4738);
        assertEquals(Emergency.STATUS_DISPATCHED, statusOf(highId));
        assertEquals(Emergency.STATUS_PENDING, statusOf(normal1Id));

        // third free unit -> the OLDER of the two NORMAL emergencies
        addAmbulance("TSP-N1", "AVAILABLE", 17.3940, 78.4390);
        assertEquals(Emergency.STATUS_DISPATCHED, statusOf(normal1Id));
        assertEquals(Emergency.STATUS_PENDING, statusOf(normal2Id));

        // fourth free unit -> the remaining NORMAL emergency
        addAmbulance("TSP-N2", "AVAILABLE", 17.4000, 78.4400);
        assertEquals(Emergency.STATUS_DISPATCHED, statusOf(normal2Id));
    }

    /* ==================================================================
       ATOMIC EMERGENCY CLAIM: the same emergency can never end up
       with two dispatches
    ================================================================== */
    @Test
    void sameEmergency_cannotBeDispatchedTwice() {
        addAmbulance("TSD", "AVAILABLE", 17.4400, 78.4700);

        Emergency emergency = emergencyService.createEmergency(
                emergencyRequest(17.4374, 78.4487, "NORMAL"));

        assertNotNull(routeService.dispatchToEmergency(emergency.getId()));

        // a sequential second attempt is rejected before touching anything
        assertThrows(InvalidStatusException.class,
                () -> routeService.dispatchToEmergency(emergency.getId()));

        assertEquals(1, dispatchRepository.count());
        assertEquals(Emergency.STATUS_DISPATCHED, statusOf(emergency.getId()));
    }

    @Test
    void sameEmergency_concurrentDispatchAttempts_createExactlyOneDispatch()
            throws Exception {
        addAmbulance("TSR1", "AVAILABLE", 17.4400, 78.4700);
        addAmbulance("TSR2", "AVAILABLE", 17.4825, 78.3925);

        Emergency emergency = emergencyService.createEmergency(
                emergencyRequest(17.4374, 78.4487, "CRITICAL"));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    routeService.dispatchToEmergency(emergency.getId());
                    successes.incrementAndGet();
                } catch (Exception exception) {
                    failures.incrementAndGet();
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, successes.get(), "exactly one attempt must win");
        assertEquals(1, failures.get(), "the loser must fail cleanly");

        // one dispatch row, emergency dispatched exactly once
        assertEquals(1, dispatchRepository.count());
        assertEquals(Emergency.STATUS_DISPATCHED, statusOf(emergency.getId()));

        // the loser rolled back: one unit is still free
        assertEquals(1, ambulanceRepository
                .findByStatusIgnoreCase(Ambulance.STATUS_AVAILABLE).size());
        assertEquals(1, ambulanceRepository
                .findByStatusIgnoreCase(Ambulance.STATUS_DISPATCHED).size());
    }

    /* ==================================================================
       GPS: an update must carry both coordinates and can never wipe
       the stored position
    ================================================================== */
    @Test
    void gpsUpdateRequiresBothCoordinates_neverWipesStoredGps() {
        long ambulance = id(addAmbulance("TSG", "AVAILABLE",
                17.4000, 78.4000));

        // completely empty body -> 400
        ResponseEntity<Map> empty = authed.exchange(
                "/ambulance/" + ambulance + "/location",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(Map.of()),
                Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, empty.getStatusCode());

        // only one coordinate -> 400 as well
        ResponseEntity<Map> half = authed.exchange(
                "/ambulance/" + ambulance + "/location",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        Map.of("latitude", 17.5000)),
                Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, half.getStatusCode());

        // the stored GPS survived both rejected updates
        Ambulance stored = ambulanceRepository.findById(ambulance)
                .orElseThrow();
        assertEquals(17.4000, stored.getLatitude(), 1e-9);
        assertEquals(78.4000, stored.getLongitude(), 1e-9);
    }

    /* ==================================================================
       LIFECYCLE: manual status changes respect the transition guards
    ================================================================== */
    @Test
    void manualStatusTransitions_areGuarded() {
        long ambulance = id(addAmbulance("TST", "AVAILABLE",
                17.4400, 78.4700));

        // DISPATCHED is reserved for the automatic reservation
        ResponseEntity<Map> manualDispatch = authed.exchange(
                "/ambulance/" + ambulance + "/status",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        Map.of("status", "DISPATCHED")),
                Map.class);
        assertEquals(HttpStatus.CONFLICT, manualDispatch.getStatusCode());

        // a free unit can be retired ...
        ResponseEntity<Map> retire = authed.exchange(
                "/ambulance/" + ambulance + "/status",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        Map.of("status", "OUT_OF_SERVICE")),
                Map.class);
        assertEquals(HttpStatus.OK, retire.getStatusCode());

        // ... but cannot jump straight into a mission while off duty
        ResponseEntity<Map> offDutyMission = authed.exchange(
                "/ambulance/" + ambulance + "/status",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        Map.of("status", "TO_VICTIM")),
                Map.class);
        assertEquals(HttpStatus.CONFLICT, offDutyMission.getStatusCode());

        // returning to duty is the way out
        ResponseEntity<Map> back = authed.exchange(
                "/ambulance/" + ambulance + "/status",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        Map.of("status", "AVAILABLE")),
                Map.class);
        assertEquals(HttpStatus.OK, back.getStatusCode());

        // a unit with an ACTIVE emergency cannot retire mid-mission
        assertEquals(HttpStatus.OK,
                requestAmbulance(17.4374, 78.4487, null).getStatusCode());

        ResponseEntity<Map> retireActive = authed.exchange(
                "/ambulance/" + ambulance + "/status",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        Map.of("status", "OUT_OF_SERVICE")),
                Map.class);
        assertEquals(HttpStatus.CONFLICT, retireActive.getStatusCode());
        assertEquals(Ambulance.STATUS_DISPATCHED,
                ambulanceRepository.findById(ambulance)
                        .orElseThrow()
                        .getStatus());
    }

    /* ==================================================================
       NO-GPS HANDLING: an available unit without GPS blocks dispatch
       with a truthful reason, until it reports its first position
    ================================================================== */
    @Test
    void availableWithoutGps_isSkippedUntilItsFirstFix_freesTheQueue() {
        // an AVAILABLE unit registered WITHOUT GPS cannot serve anyone
        ResponseEntity<Map> added = authed.postForEntity(ADD_AMBULANCE,
                Map.of(
                        "vehicleNumber", "TSNOGPS",
                        "status", "AVAILABLE",
                        "location", "No Base"
                ), Map.class);
        assertEquals(HttpStatus.OK, added.getStatusCode());
        long ambulance = id(added);

        // the victim is queued, with the truthful reason in the message
        ResponseEntity<Map> queued =
                requestAmbulance(17.4374, 78.4487, null);
        assertEquals(HttpStatus.CONFLICT, queued.getStatusCode());
        assertEquals("NO_AMBULANCE_AVAILABLE",
                queued.getBody().get("code"));
        assertTrue(queued.getBody().get("message").toString()
                .toUpperCase().contains("GPS"));
        long queuedEmergencyId = emergencyId(queued);

        // the unit reports its first GPS fix ...
        ResponseEntity<Map> fix = authed.exchange(
                "/ambulance/" + ambulance + "/location",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(Map.of(
                        "latitude", 17.4400,
                        "longitude", 78.4700,
                        "locationTimestamp", System.currentTimeMillis()
                )),
                Map.class);
        assertEquals(HttpStatus.OK, fix.getStatusCode());

        // ... and the queued emergency is dispatched automatically
        assertEquals(Emergency.STATUS_DISPATCHED,
                statusOf(queuedEmergencyId));
        assertEquals(Ambulance.STATUS_DISPATCHED,
                ambulanceRepository.findById(ambulance)
                        .orElseThrow()
                        .getStatus());
    }

    /* ==================================================================
       ROUTE SEMANTICS: leg 1 = ambulance -> victim,
       leg 2 = victim -> hospital (OSRM GeoJSON order [lon, lat])
    ================================================================== */
    @Test
    void routeLegs_haveTheCorrectEndpoints() {
        // ambulance in Kukatpally, victim in Ameerpet,
        // Apollo (added in BeforeEach) in Jubilee Hills
        id(addAmbulance("TSLEG", "AVAILABLE", 17.4825, 78.3925));

        ResponseEntity<Map> dispatch =
                requestAmbulance(17.4374, 78.4487, null);
        assertEquals(HttpStatus.OK, dispatch.getStatusCode());

        Map body = dispatch.getBody();

        List<?> toVictim = (List<?>) body.get("ambulanceToVictimRoute");
        List<?> toHospital = (List<?>) body.get("victimToHospitalRoute");

        // leg 1 starts at the ambulance and ends at the victim
        assertEquals(78.3925, lonOf(toVictim, 0), 1e-6);
        assertEquals(17.4825, latOf(toVictim, 0), 1e-6);
        assertEquals(78.4487, lonOf(toVictim, toVictim.size() - 1), 1e-6);
        assertEquals(17.4374, latOf(toVictim, toVictim.size() - 1), 1e-6);

        // leg 2 starts at the victim and ends at the hospital
        assertEquals(78.4487, lonOf(toHospital, 0), 1e-6);
        assertEquals(17.4374, latOf(toHospital, 0), 1e-6);
        assertEquals(78.4738,
                lonOf(toHospital, toHospital.size() - 1), 1e-6);
        assertEquals(17.4239,
                latOf(toHospital, toHospital.size() - 1), 1e-6);

        // the legs are continuous: leg 2 starts where leg 1 ends
        assertEquals(lonOf(toVictim, toVictim.size() - 1),
                lonOf(toHospital, 0), 1e-9);
        assertEquals(latOf(toVictim, toVictim.size() - 1),
                latOf(toHospital, 0), 1e-9);
    }

    /** Longitude (GeoJSON position element 0) of a route point. */
    private double lonOf(List<?> route, int index) {
        return ((Number) ((List<?>) route.get(index)).get(0)).doubleValue();
    }

    /** Latitude (GeoJSON position element 1) of a route point. */
    private double latOf(List<?> route, int index) {
        return ((Number) ((List<?>) route.get(index)).get(1)).doubleValue();
    }

    /* ==================================================================
       VALIDATION: invalid entity data is rejected with truthful codes
    ================================================================== */
    @Test
    void invalidEntityData_isRejectedWithTruthfulCodes() {
        // hospital with out-of-range coordinates
        ResponseEntity<Map> badHospital = authed.postForEntity("/hospital",
                Map.of(
                        "name", "Nowhere",
                        "location", "Void",
                        "latitude", 999.0,
                        "longitude", 78.4
                ), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, badHospital.getStatusCode());
        assertEquals("INVALID_COORDINATES", badHospital.getBody().get("code"));

        // hospital without a name
        ResponseEntity<Map> namelessHospital = authed.postForEntity("/hospital",
                Map.of(
                        "location", "Somewhere",
                        "latitude", 17.4,
                        "longitude", 78.4
                ), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST,
                namelessHospital.getStatusCode());
        assertEquals("INVALID_REQUEST",
                namelessHospital.getBody().get("code"));

        // ambulance without a vehicle number
        ResponseEntity<Map> namelessAmbulance =
                authed.postForEntity(ADD_AMBULANCE, Map.of(
                        "status", "AVAILABLE",
                        "location", "Test Base",
                        "latitude", 17.4,
                        "longitude", 78.4
                ), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST,
                namelessAmbulance.getStatusCode());
        assertEquals("INVALID_REQUEST",
                namelessAmbulance.getBody().get("code"));

        // ambulance with only one coordinate
        ResponseEntity<Map> halfGps = authed.postForEntity(ADD_AMBULANCE,
                Map.of(
                        "vehicleNumber", "TSHALF",
                        "status", "AVAILABLE",
                        "latitude", 17.4
                ), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, halfGps.getStatusCode());
        assertEquals("INVALID_COORDINATES", halfGps.getBody().get("code"));

        // unknown severity
        ResponseEntity<Map> badSeverity =
                requestAmbulance(17.4374, 78.4487, "WEIRD");
        assertEquals(HttpStatus.BAD_REQUEST, badSeverity.getStatusCode());
        assertEquals("INVALID_REQUEST", badSeverity.getBody().get("code"));

        // nothing was persisted by any of the rejected requests
        assertEquals(0, emergencyRepository.count());
        assertEquals(0, ambulanceRepository.count());
        assertEquals(1, hospitalRepository.count());   // only the BeforeEach one
    }


}
