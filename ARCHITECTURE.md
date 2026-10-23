# ResQRoute — Architecture

ResQRoute is an intelligent ambulance emergency-response and routing system:
**Spring Boot + MySQL + REST + Dijkstra + Traffic + OSRM + Leaflet + GPS**.

---

## 1. The One-Line Summary

> **Dijkstra decides WHICH ambulance and WHICH hospital, using a
> traffic-weighted graph of the road network. OSRM then describes the
> practical road route (geometry, distance, duration) for the chosen pair.**

---

## 2. Layering

```
Browser (victim / dashboard / landing)
        │  REST (JSON)
        ▼
Controllers ── RouteController, AmbulanceController, HospitalController,
        │       OsrmController, GlobalExceptionHandler
        ▼
Services ────── EmergencyService (create + priority queue)
        │       RouteService     (dispatch orchestration)
        │       AmbulanceService (fleet + GPS + lifecycle)
        │       HospitalService  (hospital CRUD)
        │       GraphService     (builds the routing graph)
        │       DijkstraService  (shortest paths)
        │       TrafficService   (simulated traffic model)
        │       OsrmService      (external road routing)
        ▼
Repositories ── Spring Data JPA
        ▼
MySQL ───────── ambulance, hospital, emergency, dispatch, location_node
```

---

## 3. Routing Graph and Shortest-Path Selection

| Concept | Meaning in ResQRoute |
|---|---|
| **Vertex** | A point of the road-network model: a landmark stored in the `location_node` table (e.g. "Begumpet"), plus temporary vertices inserted per request: the victim position, every available ambulance and every hospital. |
| **Edge** | A road corridor between two vertices. Each landmark connects to its K=5 nearest landmarks; every temporary vertex connects to its 3 nearest landmarks. Undirected. |
| **Edge weight** | `effective cost = haversine km × traffic factor of the corridor midpoint` — i.e. **traffic-adjusted kilometres**. |
| **Source** | The victim's vertex (the emergency position). |
| **Destination** | The ambulance vertices (to choose the unit) and the hospital vertices (to choose the destination). |
| **Minimised** | Total traffic-adjusted kilometres along the path — the cheapest path through the traffic-weighted road model. |

One Dijkstra run from the victim gives the cost to **every** ambulance and
**every** hospital simultaneously (single-source shortest paths).

- Time complexity: **O((V + E) log V)** — binary heap.
- Space complexity: **O(V + E)**.
- Output actually drives decisions: ambulances are ranked by `costTo(ambulance)`,
  hospitals by `costTo(hospital)`; the lowest cost wins (see `RouteService.dispatchToEmergency`).

### Why Dijkstra and OSRM are different things

| | Dijkstra (ours) | OSRM (external) |
|---|---|---|
| What it is | Our algorithm on our graph | A public road-routing service |
| Decides | WHICH ambulance / hospital | nothing — it only describes |
| Provides | traffic-adjusted path cost | real road geometry, road distance, road duration |
| Failure mode | vertex unreachable → straight-line cost fallback | unreachable → straight-line estimate fallback |

---

## 4. Traffic (simulated, honestly)

`TrafficService` is the ONLY place traffic is defined, so a real traffic API
can replace it later without touching any other class.

- Zone model (simulated, documented): distance from the configured city centre
  — inside 4 km → **HIGH**, inside 9 km → **MEDIUM**, else **LOW**.
- Multipliers: HIGH ×2.0, MEDIUM ×1.5, LOW ×1.0.
- Traffic affects the system in **two genuine ways**:
  1. **Graph edge weights** → changes the Dijkstra ranking → changes which ambulance is selected.
  2. **ETA** → `ambulanceToVictimDurationMin = roadDuration × traffic factor`.

It is NOT called "live traffic" anywhere — it is a simulation.

---

## 5. Dispatch Flow (POST /route/best-ambulance)

```
1. EmergencyService.createEmergency   [TX1 — committed]
     validate victim GPS, normalize severity (CRITICAL/NORMAL)
     persist Emergency(PENDING)          ← never lost, even on later failure
2. RouteService.dispatchToEmergency   [TX2 — all-or-nothing]
     a. candidates  = ambulances with status AVAILABLE + valid GPS
     b. hospitals   = hospitals with valid GPS        (else 409 NO_HOSPITALS)
     c. graph       = landmarks + victim + candidates + hospitals
     d. DIJKSTRA    = one run from the victim vertex
     e. rank ambulances by costTo(ambulance)  → best first
     f. ATOMIC RESERVE loop:
          UPDATE ambulance SET status='DISPATCHED'
          WHERE id=? AND status='AVAILABLE'      ← one SQL statement
          0 rows = lost the race → try the next ranked ambulance
        all lost → 409 NO_AMBULANCE_AVAILABLE (emergency stays PENDING, queued)
     g. hospital = min costTo(hospital)
     h. OSRM: ambulance→victim and victim→hospital practical routes
        (failure → documented straight-line estimate ×1.3, 40 km/h)
     i. persist Dispatch(ACTIVE) + routes (LONGTEXT JSON)
        emergency → DISPATCHED
```

Concurrency: two emergencies racing for the same ambulance can never both win —
the reservation is a single conditional UPDATE inside a transaction, and a
`UNIQUE(emergency_id)` constraint on dispatch guarantees one dispatch per emergency.

---

## 6. Emergency Lifecycle

```
PENDING ──dispatch──▶ DISPATCHED ──ambulance AVAILABLE──▶ COMPLETED
```

When an ambulance becomes AVAILABLE (`PUT /ambulance/{id}/status`):
1. Its ACTIVE dispatch is marked COMPLETED (with timestamp), the emergency COMPLETED. [TX]
2. After that transaction commits, the pending queue is processed in priority
   order: **CRITICAL first, then longest waiting (FIFO)** — each attempt in its
   own transaction, so a failure never rolls back the status change.

Ambulance status lifecycle (validated set):
`AVAILABLE → DISPATCHED → TO_VICTIM → AT_VICTIM → TO_HOSPITAL → AVAILABLE`
(plus `OUT_OF_SERVICE`; legacy "Busy" rows are simply never selected).

---

## 7. GPS

- **Victim**: browser Geolocation → POST /route/best-ambulance. The victim
  never types coordinates and never sees raw values. Permission denied /
  timeout / unsupported → clear message + retry.
- **Ambulance device**: the dashboard picks which unit this device is
  ("This device:" selector, stored in localStorage — nothing is hardcoded).
  `watchPosition` → `PUT /ambulance/{id}/location` with a client timestamp.
  The backend: validates ranges, **rejects stale (out-of-order) fixes**, and
  skips sub-metre jitter within 10 s to avoid pointless writes.
- Coordinates are validated everywhere (victim, ambulance, hospital, GPS).

---

## 8. Database

`ambulance` (+ `last_location_update` column), `hospital`, `emergency`,
`dispatch` (routes in LONGTEXT, UNIQUE `emergency_id`), `location_node`
(road-network vertices, seeded with 16 Hyderabad landmarks on first start).

All emergency/dispatch data is in the database — there is **no controller
memory**. Restarts lose nothing; the dashboard snapshot
(`GET /route/latest`) is computed straight from MySQL.

---

## 9. Running & Testing

```bash
# requires JDK 21 + MySQL running (see application.properties)
mvnw spring-boot:run          # http://localhost:8080

mvnw test                     # automated tests (H2, no MySQL needed)
```

Pages: `/` (landing, live map), `/command.html` (operations centre),
`/victim.html` (one-tap emergency request).

Useful endpoint checks:
```
GET  /osrm/test?fromLat=&fromLon=&toLat=&toLon=     – live OSRM route
GET  /route/latest                      – dashboard snapshot
POST /route/best-ambulance              – {"sourceLatitude":…, "sourceLongitude":…, "severity":"NORMAL|CRITICAL"}
PUT  /ambulance/{id}/status              – {"status":"TO_VICTIM"} (lifecycle)
PUT  /ambulance/{id}/location            – {"latitude":…, "longitude":…, "locationTimestamp":epochMs}
```

---

## 10. Known Limitations

- Traffic is **simulated** (radial zones) — swap `TrafficService.trafficLevelAt` for a real feed later.
- The OSRM fallback (`×1.3 road factor, 40 km/h`) is an **estimate**, used only when OSRM is unreachable or returns no route.
- Graph landmarks are a small model of Hyderabad (16 nodes) — add rows to `location_node` to refine it.
- The public OSRM server occasionally rate-limits bursts; the system degrades gracefully to estimates.
- Device-to-ambulance GPS is browser-based (prototype transport); a real system would use vehicle telemetry.
- No authentication (by design, college project).
