package com.resqroute;

import com.resqroute.model.Ambulance;
import com.resqroute.model.Dispatch;
import com.resqroute.model.Emergency;
import com.resqroute.model.LocationNode;
import com.resqroute.model.StaffUser;
import com.resqroute.repository.AmbulanceRepository;
import com.resqroute.repository.DispatchRepository;
import com.resqroute.repository.EmergencyRepository;
import com.resqroute.repository.HospitalRepository;
import com.resqroute.repository.LocationNodeRepository;
import com.resqroute.repository.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests of the FINAL security + tracking architecture:
 *
 * AUTHENTICATION
 *   - unauthenticated calls to staff APIs are rejected (401)
 *   - command.html is not served without a login
 *   - wrong credentials are rejected, right credentials work
 *   - the real form-login flow issues a working session
 *
 * PRIVATE TRACKING
 *   - every dispatch gets a secure random tracking token
 *   - tokens are unique and long enough to be unguessable
 *   - a valid token returns exactly its own dispatch
 *   - the tracking response leaks no internal ids
 *   - an invalid or mutated token is rejected
 *   - a completed dispatch still shows its final state
 *   - missing ambulance GPS is reported honestly
 *
 * PERSISTENCE / DUPLICATE PREVENTION
 *   - the active dispatch is fully restorable from GET /route/latest
 *   - repeated polling creates no new dispatches or emergencies
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SecurityAndTrackingTests {

    @Autowired
    private TestRestTemplate http;

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

    private static final String STAFF_USERNAME = "staff";
    private static final String STAFF_PASSWORD = "test-staff-password-2026";
    private static final PasswordEncoder STAFF_ENCODER =
            PasswordEncoderFactories.createDelegatingPasswordEncoder();

    @BeforeEach
    void cleanDatabaseAndSeedStaff() {
        dispatchRepository.deleteAll();
        emergencyRepository.deleteAll();
        ambulanceRepository.deleteAll();
        hospitalRepository.deleteAll();
        locationNodeRepository.deleteAll();

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

        staffUserRepository.findByUsername(STAFF_USERNAME)
                .ifPresent(staffUserRepository::delete);
        staffUserRepository.save(new StaffUser(
                STAFF_USERNAME, STAFF_ENCODER.encode(STAFF_PASSWORD), "STAFF"));

        authed = http.withBasicAuth(STAFF_USERNAME, STAFF_PASSWORD);

        // one standard hospital for dispatches
        authed.postForEntity("/hospital", Map.of(
                "name", "Apollo Hospital",
                "location", "Jubilee Hills",
                "latitude", 17.4239,
                "longitude", 78.4738
        ), Map.class);
    }

    /* ------------------------------------------------------------------
       Helpers
    ------------------------------------------------------------------ */

    private long addAmbulance(String vehicle, double lat, double lon) {
        ResponseEntity<Map> response = authed.postForEntity("/ambulance", Map.of(
                "vehicleNumber", vehicle,
                "status", "AVAILABLE",
                "location", "Test Base",
                "latitude", lat,
                "longitude", lon
        ), Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return ((Number) response.getBody().get("id")).longValue();
    }

    /** Victim request — public, no authentication, no CSRF token. */
    private ResponseEntity<Map> victimRequest(double lat, double lon) {
        return http.postForEntity("/route/best-ambulance", Map.of(
                "sourceLatitude", lat,
                "sourceLongitude", lon,
                "severity", "NORMAL"
        ), Map.class);
    }

    private String cookieValue(ResponseEntity<?> response, String name) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies == null) {
            return null;
        }
        for (String cookie : cookies) {
            if (cookie.startsWith(name + "=")) {
                return cookie.substring(name.length() + 1).split(";")[0];
            }
        }
        return null;
    }

    private void completeLifecycle(long ambulanceId) {
        for (String status : List.of("TO_VICTIM", "AT_VICTIM",
                "TO_HOSPITAL", "AVAILABLE")) {
            authed.exchange("/ambulance/" + ambulanceId + "/status",
                    HttpMethod.PUT,
                    new HttpEntity<>(Map.of("status", status)), Map.class);
        }
    }

    /* ==================================================================
       AUTHENTICATION
    ================================================================== */

    @Test
    void unauthenticatedCallsToStaffApis_areRejectedWith401() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                http.getForEntity("/ambulance", String.class).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                http.getForEntity("/hospital", String.class).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                http.getForEntity("/route/latest", String.class).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                http.postForEntity("/ambulance", Map.of(
                        "vehicleNumber", "TS-HACK",
                        "status", "AVAILABLE",
                        "latitude", 17.4,
                        "longitude", 78.4
                ), String.class).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                http.exchange("/ambulance/1/status", HttpMethod.PUT,
                        new HttpEntity<>(Map.of("status", "TO_VICTIM")),
                        String.class).getStatusCode());
    }

    @Test
    void commandCenterPage_isNotServedWithoutLogin() {
        ResponseEntity<String> page =
                http.getForEntity("/command.html", String.class);

        String body = page.getBody() == null ? "" : page.getBody();

        // either the 302 to the login page, or the followed login page —
        // never the Command Center content itself
        assertTrue(page.getStatusCode().is3xxRedirection()
                        || body.contains("Sign in"),
                "unauthenticated staff must land on the login page");
        assertFalse(body.contains("Ambulance Management"),
                "command center content must not be served unauthenticated");
    }

    @Test
    void wrongPassword_isRejected() {
        TestRestTemplate wrong =
                http.withBasicAuth(STAFF_USERNAME, "definitely-wrong");

        assertEquals(HttpStatus.UNAUTHORIZED,
                wrong.getForEntity("/ambulance", String.class).getStatusCode());
    }

    @Test
    void authenticatedStaff_canCallProtectedApis() {
        assertEquals(HttpStatus.OK,
                authed.getForEntity("/ambulance", String.class).getStatusCode());
        assertEquals(HttpStatus.OK,
                authed.getForEntity("/hospital", String.class).getStatusCode());
    }

    @Test
    void formLogin_issuesAWorkingSession_andRejectsBadCredentials() {
        // 1. load the login page: the CSRF token arrives as a cookie
        ResponseEntity<String> page =
                http.getForEntity("/index.html", String.class);
        assertEquals(HttpStatus.OK, page.getStatusCode());

        String csrf = cookieValue(page, "XSRF-TOKEN");
        assertNotNull(csrf, "the login page must set the XSRF-TOKEN cookie");

        // A client that does NOT follow redirects, so the 302 and the
        // Set-Cookie session header of the login POST stay visible.
        java.net.http.HttpClient noRedirects =
                java.net.http.HttpClient.newBuilder()
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                        .build();
        RestTemplate loginClient = new RestTemplate(
                new org.springframework.http.client
                        .JdkClientHttpRequestFactory(noRedirects));
        String root = http.getRootUri();

        // 2. submit the login form (with the CSRF token)
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "XSRF-TOKEN=" + csrf);

        MultiValueMap<String, String> goodCredentials =
                new LinkedMultiValueMap<>();
        goodCredentials.add("username", STAFF_USERNAME);
        goodCredentials.add("password", STAFF_PASSWORD);
        goodCredentials.add("_csrf", csrf);

        ResponseEntity<String> login = loginClient.postForEntity(
                root + "/login",
                new HttpEntity<>(goodCredentials, headers), String.class);

        // 3. success -> redirected to the Command Center
        assertTrue(login.getStatusCode().is3xxRedirection(),
                "successful login must redirect, got "
                        + login.getStatusCode());
        assertTrue(Objects.requireNonNull(
                        login.getHeaders().getLocation()).toString()
                .contains("/command.html"));

        // 4. the issued session cookie grants access to protected APIs
        String sessionId = cookieValue(login, "JSESSIONID");
        assertNotNull(sessionId, "a session must be created on login");

        HttpHeaders session = new HttpHeaders();
        session.add(HttpHeaders.COOKIE, "JSESSIONID=" + sessionId);

        assertEquals(HttpStatus.OK, http.exchange("/ambulance",
                HttpMethod.GET, new HttpEntity<>(session), String.class)
                .getStatusCode());

        // 5. wrong credentials -> back to the login page with an error
        MultiValueMap<String, String> badCredentials =
                new LinkedMultiValueMap<>();
        badCredentials.add("username", STAFF_USERNAME);
        badCredentials.add("password", "definitely-wrong");
        badCredentials.add("_csrf", csrf);

        ResponseEntity<String> failed = loginClient.postForEntity(
                root + "/login",
                new HttpEntity<>(badCredentials, headers), String.class);

        assertTrue(failed.getStatusCode().is3xxRedirection());
        assertTrue(Objects.requireNonNull(
                        failed.getHeaders().getLocation()).toString()
                .contains("/index.html"));
    }

    /* ==================================================================
       PRIVATE TRACKING
    ================================================================== */

    @Test
    void victimLocator_trackingTokensReturnEachOwnVictimLocation() {
        // two victims at DIFFERENT positions, two dispatches
        addAmbulance("VLOC-A", 17.4400, 78.4700);
        addAmbulance("VLOC-B", 17.4825, 78.3925);

        ResponseEntity<Map> first =
                victimRequest(17.4374, 78.4487);   // Ameerpet
        ResponseEntity<Map> second =
                victimRequest(17.3616, 78.4747);   // Charminar

        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals(HttpStatus.OK, second.getStatusCode());

        String tokenA = (String) first.getBody().get("trackingToken");
        String tokenB = (String) second.getBody().get("trackingToken");
        assertNotNull(tokenA);
        assertNotNull(tokenB);

        ResponseEntity<Map> trackA =
                http.getForEntity("/track/" + tokenA, Map.class);
        ResponseEntity<Map> trackB =
                http.getForEntity("/track/" + tokenB, Map.class);

        // REGRESSION (victim locator): each token returns ITS OWN
        // victim position — the "YOU" marker can never be overwritten
        // by another victim's dispatch
        assertEquals(17.4374,
                ((Number) trackA.getBody().get("victimLatitude")).doubleValue(),
                1e-6);
        assertEquals(78.4487,
                ((Number) trackA.getBody().get("victimLongitude")).doubleValue(),
                1e-6);
        assertEquals(17.3616,
                ((Number) trackB.getBody().get("victimLatitude")).doubleValue(),
                1e-6);
        assertEquals(78.4747,
                ((Number) trackB.getBody().get("victimLongitude")).doubleValue(),
                1e-6);

        // and the ambulances are the independently assigned ones
        assertNotEquals(
                trackA.getBody().get("ambulanceVehicleNumber"),
                trackB.getBody().get("ambulanceVehicleNumber"));
    }

    @Test
    void activeDispatches_areFullyIndependent_gpsAndLifecycle() {
        // THREE simultaneous dispatches on three ambulances
        addAmbulance("IND-A", 17.4400, 78.4700);
        addAmbulance("IND-B", 17.4825, 78.3925);
        addAmbulance("IND-C", 17.4239, 78.4738);

        ResponseEntity<Map> dispatchA =
                victimRequest(17.4374, 78.4487);
        ResponseEntity<Map> dispatchB =
                victimRequest(17.3940, 78.4390);
        ResponseEntity<Map> dispatchC =
                victimRequest(17.4287, 78.5360);

        assertEquals(HttpStatus.OK, dispatchA.getStatusCode());
        assertEquals(HttpStatus.OK, dispatchB.getStatusCode());
        assertEquals(HttpStatus.OK, dispatchC.getStatusCode());

        long idA = ((Number) dispatchA.getBody().get("dispatchId")).longValue();
        long idB = ((Number) dispatchB.getBody().get("dispatchId")).longValue();
        long idC = ((Number) dispatchC.getBody().get("dispatchId")).longValue();
        long unitA = ((Number) dispatchA.getBody().get("ambulanceId")).longValue();
        long unitB = ((Number) dispatchB.getBody().get("ambulanceId")).longValue();
        long unitC = ((Number) dispatchC.getBody().get("ambulanceId")).longValue();

        // GET /route/active returns ALL of them
        List<Map> active = authed.getForEntity("/route/active", List.class)
                .getBody();
        assertEquals(3, active.size());

        Map<String, Double> before = liveGpsById(active);
        double latA0 = before.get(idA + ":lat");
        double latB0 = before.get(idB + ":lat");
        double latC0 = before.get(idC + ":lat");

        /* A: move Ambulance A -> ONLY A moves */
        moveGps(unitA, 17.4380, 78.4550);
        active = authed.getForEntity("/route/active", List.class).getBody();

        assertEquals(17.4380, gpsOf(active, idA).get("lat"), 1e-9);
        assertEquals(latB0, gpsOf(active, idB).get("lat"), 1e-9);
        assertEquals(latC0, gpsOf(active, idC).get("lat"), 1e-9);

        /* B: move Ambulance B -> ONLY B moves */
        moveGps(unitB, 17.4700, 78.4300);
        active = authed.getForEntity("/route/active", List.class).getBody();

        assertEquals(17.4700, gpsOf(active, idB).get("lat"), 1e-9);
        assertEquals(17.4380, gpsOf(active, idA).get("lat"), 1e-9);
        assertEquals(latC0, gpsOf(active, idC).get("lat"), 1e-9);

        /* C: rapid repeated updates -> the LAST valid one wins */
        moveGps(unitC, 17.4300, 78.5300);
        moveGps(unitC, 17.4301, 78.5301);
        moveGps(unitC, 17.4302, 78.5302);
        active = authed.getForEntity("/route/active", List.class).getBody();
        assertEquals(17.4302, gpsOf(active, idC).get("lat"), 1e-9);
        assertEquals(17.4380, gpsOf(active, idA).get("lat"), 1e-9);
        assertEquals(17.4700, gpsOf(active, idB).get("lat"), 1e-9);

        /* "Logout" is irrelevant to the device GPS: a brand-new session
           (fresh authentication, i.e. exactly a re-login) still reads
           the latest PERSISTED positions — the database is the
           authority, never any browser session */
        TestRestTemplate freshSession =
                http.withBasicAuth(STAFF_USERNAME, STAFF_PASSWORD);
        active = freshSession.getForEntity("/route/active", List.class)
                .getBody();
        assertEquals(3, active.size());
        assertEquals(17.4380, gpsOf(active, idA).get("lat"), 1e-9);
        assertEquals(17.4700, gpsOf(active, idB).get("lat"), 1e-9);
        assertEquals(17.4302, gpsOf(active, idC).get("lat"), 1e-9);

        /* D: complete Dispatch A -> only A disappears, B and C remain
           exactly where they were, with independent state */
        completeLifecycle(unitA);

        active = authed.getForEntity("/route/active", List.class).getBody();
        assertEquals(2, active.size());
        assertFalse(active.stream()
                        .anyMatch((d) -> ((Number) d.get("dispatchId")).longValue() == idA),
                "completed dispatch A must be gone");
        assertEquals(17.4700, gpsOf(active, idB).get("lat"), 1e-9);
        assertEquals(17.4302, gpsOf(active, idC).get("lat"), 1e-9);
        assertEquals(Dispatch.STATUS_ACTIVE, statusOf(active, idB));
        assertEquals(Dispatch.STATUS_ACTIVE, statusOf(active, idC));

        /* E: a FOURTH dispatch joins while B and C are still active */
        ResponseEntity<Map> dispatchD =
                victimRequest(17.4125, 78.4385);
        assertEquals(HttpStatus.OK, dispatchD.getStatusCode());

        active = authed.getForEntity("/route/active", List.class).getBody();
        assertEquals(3, active.size());
        assertEquals(17.4700, gpsOf(active, idB).get("lat"), 1e-9);
        assertEquals(17.4302, gpsOf(active, idC).get("lat"), 1e-9);

        // cleanup: complete everything so later tests start clean
        completeLifecycle(unitB);
        completeLifecycle(unitC);
        completeLifecycle(((Number) dispatchD.getBody().get("ambulanceId")).longValue());
        active = authed.getForEntity("/route/active", List.class).getBody();
        assertEquals(0, active.size());
    }

    @Test
    void routeActive_requiresStaffAuthentication() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                http.getForEntity("/route/active", String.class)
                        .getStatusCode());

        assertEquals(HttpStatus.OK,
                authed.getForEntity("/route/active", String.class)
                        .getStatusCode());
    }

    /* helpers for the independence test ------------------------------ */

    private void moveGps(long ambulanceId, double lat, double lon) {
        authed.exchange("/ambulance/" + ambulanceId + "/location",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "latitude", lat,
                        "longitude", lon,
                        "locationTimestamp",
                        System.currentTimeMillis()
                )), Map.class);
    }

    private Map<String, Double> liveGpsById(List<Map> active) {
        Map<String, Double> result = new java.util.HashMap<>();
        for (Map<?, ?> dispatch : active) {
            Number id = (Number) dispatch.get("dispatchId");
            result.put(id + ":lat",
                    ((Number) dispatch.get("ambulanceLatitude")).doubleValue());
            result.put(id + ":lon",
                    ((Number) dispatch.get("ambulanceLongitude")).doubleValue());
        }
        return result;
    }

    private Map<String, Double> gpsOf(List<Map> active, long dispatchId) {
        Map<?, ?> found = active.stream()
                .filter((d) -> ((Number) d.get("dispatchId")).longValue()
                        == dispatchId)
                .findFirst()
                .orElseThrow();
        Map<String, Double> gps = new java.util.HashMap<>();
        gps.put("lat", ((Number) found.get("ambulanceLatitude")).doubleValue());
        gps.put("lon", ((Number) found.get("ambulanceLongitude")).doubleValue());
        return gps;
    }

    private String statusOf(List<Map> active, long dispatchId) {
        return (String) active.stream()
                .filter((d) -> ((Number) d.get("dispatchId")).longValue()
                        == dispatchId)
                .findFirst()
                .orElseThrow()
                .get("dispatchStatus");
    }

    @Test
    void dispatchCreatesASecureTrackingToken_uniquePerDispatch() {
        addAmbulance("TSA-1", 17.4400, 78.4700);
        addAmbulance("TSA-2", 17.4825, 78.3925);

        // PUBLIC victim requests: no authentication, no CSRF token needed
        ResponseEntity<Map> first = victimRequest(17.4374, 78.4487);
        ResponseEntity<Map> second = victimRequest(17.4239, 78.4738);

        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals(HttpStatus.OK, second.getStatusCode());

        String token1 = (String) first.getBody().get("trackingToken");
        String token2 = (String) second.getBody().get("trackingToken");

        assertNotNull(token1, "a dispatch must carry a tracking token");
        assertNotNull(token2, "a dispatch must carry a tracking token");

        // unguessable: 32 random bytes as 64 hex characters
        assertTrue(token1.length() >= 43,
                "token must be long enough to be unguessable (was "
                        + token1.length() + ")");
        assertNotEquals(token1, token2, "every dispatch gets its own token");
    }

    @Test
    void validToken_returnsItsOwnDispatchOnly_andLeaksNoInternalIds() {
        addAmbulance("TRK-A", 17.4400, 78.4700);
        addAmbulance("TRK-B", 17.4825, 78.3925);

        ResponseEntity<Map> first = victimRequest(17.4374, 78.4487);
        ResponseEntity<Map> second = victimRequest(17.4239, 78.4738);

        String tokenA = (String) first.getBody().get("trackingToken");
        String tokenB = (String) second.getBody().get("trackingToken");
        assertNotNull(tokenA);
        assertNotNull(tokenB);

        // token A sees its own dispatch's ambulance
        ResponseEntity<Map> trackA =
                http.getForEntity("/track/" + tokenA, Map.class);
        assertEquals(HttpStatus.OK, trackA.getStatusCode());
        assertEquals("TRK-A", trackA.getBody().get("ambulanceVehicleNumber"));

        // ...and cannot see the other dispatch
        ResponseEntity<Map> trackB =
                http.getForEntity("/track/" + tokenB, Map.class);
        assertEquals("TRK-B", trackB.getBody().get("ambulanceVehicleNumber"));
        assertNotEquals(trackA.getBody().get("ambulanceVehicleNumber"),
                trackB.getBody().get("ambulanceVehicleNumber"));

        // the victim view exposes NO internal ids
        for (String forbidden : List.of(
                "emergencyId", "ambulanceId", "hospitalId", "dispatchId",
                "trackingToken", "severity")) {
            assertFalse(trackA.getBody().containsKey(forbidden),
                    "tracking response must not contain '" + forbidden + "'");
        }
    }

    @Test
    void invalidOrMutatedToken_isRejected() {
        addAmbulance("TRK-C", 17.4400, 78.4700);

        String token = (String) victimRequest(17.4374, 78.4487)
                .getBody().get("trackingToken");

        // unknown token
        assertEquals(HttpStatus.NOT_FOUND, http
                .getForEntity("/track/this-token-does-not-exist", Map.class)
                .getStatusCode());

        // one mutated character of a REAL token must not expose anything
        String mutated = "x" + token.substring(1);
        assertEquals(HttpStatus.NOT_FOUND, http
                .getForEntity("/track/" + mutated, Map.class)
                .getStatusCode());
    }

    @Test
    void completedDispatch_keepsShowingItsFinalStateToTheVictim() {
        long ambulance = addAmbulance("TRK-D", 17.4400, 78.4700);

        String token = (String) victimRequest(17.4374, 78.4487)
                .getBody().get("trackingToken");

        completeLifecycle(ambulance);

        ResponseEntity<Map> track =
                http.getForEntity("/track/" + token, Map.class);

        assertEquals(HttpStatus.OK, track.getStatusCode());
        assertEquals(Dispatch.STATUS_COMPLETED,
                track.getBody().get("dispatchStatus"));
        assertEquals(Emergency.STATUS_COMPLETED,
                track.getBody().get("emergencyStatus"));
        assertEquals(Ambulance.STATUS_AVAILABLE,
                track.getBody().get("ambulanceStatus"));
    }

    @Test
    void missingAmbulanceGps_isReportedHonestly_neverFabricated() {
        addAmbulance("TRK-E", 17.4400, 78.4700);

        String token = (String) victimRequest(17.4374, 78.4487)
                .getBody().get("trackingToken");

        // the unit loses its GPS position
        long ambulanceId = ambulanceRepository.findAll().get(0).getId();
        Ambulance withoutGps = ambulanceRepository.findById(ambulanceId)
                .orElseThrow();
        withoutGps.setLatitude(null);
        withoutGps.setLongitude(null);
        ambulanceRepository.save(withoutGps);

        ResponseEntity<Map> track =
                http.getForEntity("/track/" + token, Map.class);

        assertEquals(HttpStatus.OK, track.getStatusCode());
        assertEquals(Boolean.FALSE,
                track.getBody().get("ambulanceLocationAvailable"));
        assertNull(track.getBody().get("ambulanceLatitude"));
        assertNull(track.getBody().get("ambulanceLongitude"));
    }

    /* ==================================================================
       PERSISTENCE + DUPLICATE PREVENTION (refresh-safe Command Center)
    ================================================================== */

    @Test
    void activeDispatch_isFullyRestorable_andPollingCreatesNoDuplicates() {
        addAmbulance("REF-1", 17.4400, 78.4700);

        ResponseEntity<Map> dispatch = victimRequest(17.4374, 78.4487);
        assertEquals(HttpStatus.OK, dispatch.getStatusCode());

        long dispatchId =
                ((Number) dispatch.getBody().get("dispatchId")).longValue();

        long emergencies = emergencyRepository.count();
        long dispatches = dispatchRepository.count();

        // three refresh/poll cycles — what the Command Center does
        for (int poll = 0; poll < 3; poll++) {
            ResponseEntity<Map> snapshot =
                    authed.getForEntity("/route/latest", Map.class);
            assertEquals(HttpStatus.OK, snapshot.getStatusCode());

            Map restored = (Map) snapshot.getBody().get("dispatch");
            assertNotNull(restored,
                    "the active dispatch must always be restorable");
            assertEquals(dispatchId,
                    ((Number) restored.get("dispatchId")).longValue());
            assertEquals(17.4374,
                    ((Number) restored.get("sourceLatitude")).doubleValue(),
                    1e-9);

            // routes + ETA + traffic + severity all survive the reload
            assertTrue(((List<?>) restored.get("ambulanceToVictimRoute"))
                    .size() >= 2, "leg 1 geometry must be restored");
            assertTrue(((List<?>) restored.get("victimToHospitalRoute"))
                    .size() >= 2, "leg 2 geometry must be restored");
            assertNotNull(restored.get("ambulanceToVictimDurationMin"));
            assertNotNull(restored.get("trafficLevel"));
            assertNotNull(restored.get("severity"));
            assertEquals("ACTIVE", restored.get("dispatchStatus"));
        }

        // nothing was duplicated by refreshing/polling
        assertEquals(emergencies, emergencyRepository.count());
        assertEquals(dispatches, dispatchRepository.count());
    }
}
