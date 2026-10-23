/* ============================================================================
   ResQRoute — Private victim tracking (track.js)
   ----------------------------------------------------------------------------
   The tracking token in the URL is the ONLY authorization: it points to
   exactly this victim's dispatch (a random 64-character value, not an id).
   The page polls GET /track/{token} and renders ONLY that dispatch:

     - assigned ambulance (vehicle number, status)
     - ETA / distance (backend values, never invented here)
     - live ambulance GPS position (never fabricated: if the backend
       has no valid position, an honest banner is shown instead)
     - the victim as "YOU"
     - assigned hospital
     - traffic level
     - both routes: ambulance -> victim and victim -> hospital

   Raw coordinates, other emergencies, other ambulances, ids, CRUD
   and Command Center data are never shown.
   ============================================================================ */

const POLL_INTERVAL_MS = 5000;

const token = new URLSearchParams(window.location.search).get("token");

let map = null;
let victimMarker = null;
let ambulanceMarker = null;
let hospitalMarker = null;
let legOneLayer = null;
let legTwoLayer = null;

let pollTimer = null;
let finished = false;

/* ---------------------------------------------------------------------------
   Helpers
--------------------------------------------------------------------------- */

function escapeHtml(value) {
  const AMP = String.fromCharCode(38);
  return String(value == null ? "" : value)
    .replaceAll(AMP, AMP + "amp;")
    .replaceAll("<", AMP + "lt;")
    .replaceAll(">", AMP + "gt;")
    .replaceAll("\"", AMP + "quot;")
    .replaceAll("'", AMP + "#39;");
}

/* Custom Leaflet marker (same look as the Command Center). */
function createMarkerIcon(type) {
  return L.divIcon({
    className: "qr-marker",
    html: `
      <div class="marker marker-${type}">
        <span class="marker-pulse"></span>
        <span class="marker-core"></span>
      </div>
    `,
    iconSize: [30, 30],
    iconAnchor: [15, 15]
  });
}

/* Glowing route: under-glow + solid line + flowing light. */
function createGlowRoute(points, color) {
  return L.layerGroup([
    L.polyline(points, { color, weight: 12, opacity: 0.14, lineCap: "round" }),
    L.polyline(points, { color, weight: 4.5, opacity: 0.95, lineCap: "round" }),
    L.polyline(points, {
      color: "#ffe9d2",
      weight: 4.5,
      opacity: 0.85,
      lineCap: "round",
      className: "route-flow"
    })
  ]);
}

function show(elementId) {
  document.getElementById(elementId).classList.remove("hidden");
}

function hide(elementId) {
  document.getElementById(elementId).classList.add("hidden");
}

function setTemporaryError(message) {
  const element = document.getElementById("trackError");
  element.textContent = message;
  element.classList.add("hidden");
  if (message) {
    show("trackError");
  }
}

/* Victim-friendly lifecycle index for the backend statuses. */
function lifecycleIndex(data) {
  if (data.dispatchStatus === "COMPLETED") {
    return 6;
  }
  switch (data.ambulanceStatus) {
    case "TO_HOSPITAL":
      return 5;
    case "AT_VICTIM":
      return 4;
    case "TO_VICTIM":
      return 3;
    case "DISPATCHED":
      return 2;
    default:
      return 1;
  }
}

function victimStatusLabel(ambulanceStatus, dispatchStatus) {
  if (dispatchStatus === "COMPLETED") {
    return "COMPLETED";
  }
  switch (ambulanceStatus) {
    case "DISPATCHED":
      return "ASSIGNED";
    case "TO_VICTIM":
      return "ON THE WAY TO YOU";
    case "AT_VICTIM":
      return "WITH YOU";
    case "TO_HOSPITAL":
      return "TO HOSPITAL";
    default:
      return ambulanceStatus || "—";
  }
}

/* ---------------------------------------------------------------------------
   Rendering
--------------------------------------------------------------------------- */

function render(data) {
  show("trackContent");

  /* chips ------------------------------------------------------------- */
  document.getElementById("chipAmbulance").textContent =
    data.ambulanceVehicleNumber || "Assigned ambulance";

  document.getElementById("chipStatus").textContent =
    victimStatusLabel(data.ambulanceStatus, data.dispatchStatus);

  document.getElementById("chipEtaYou").textContent =
    data.ambulanceToVictimDurationMin != null
      ? data.ambulanceToVictimDurationMin + " min"
      : "—";

  document.getElementById("chipDistanceYou").textContent =
    data.ambulanceToVictimDistanceKm != null
      ? data.ambulanceToVictimDistanceKm + " km"
      : "—";

  document.getElementById("chipEtaHospital").textContent =
    data.victimToHospitalDurationMin != null
      ? data.victimToHospitalDurationMin + " min"
      : "—";

  const traffic = (data.trafficLevel || "low").toString().toLowerCase();
  const chipTraffic = document.getElementById("chipTraffic");
  chipTraffic.textContent = traffic.toUpperCase();
  chipTraffic.className = "card-value small traffic-" + traffic;

  document.getElementById("chipHospital").textContent =
    data.hospitalName || "Assigned hospital";
  document.getElementById("chipHospitalLocation").textContent =
    data.hospitalLocation || "";

  /* banners ----------------------------------------------------------- */

  if (data.ambulanceLocationAvailable) {
    hide("gpsAlert");
  } else {
    show("gpsAlert");
  }

  if (data.dispatchStatus === "COMPLETED") {
    show("completedAlert");
    finished = true;
    if (pollTimer !== null) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  } else {
    hide("completedAlert");
  }

  /* lifecycle --------------------------------------------------------- */
  const activeIndex = lifecycleIndex(data);
  document.querySelectorAll(".lifecycle-step").forEach((step, index) => {
    step.classList.toggle("done", index < activeIndex - 1);
    step.classList.toggle("active", index === activeIndex - 1);
  });

  /* map ---------------------------------------------------------------- */
  renderMap(data);
}

function renderMap(data) {
  const victimPoint = [data.victimLatitude, data.victimLongitude];

  /* OSRM geometry is [longitude, latitude] — swap to Leaflet order. */
  const legOne = (data.ambulanceToVictimRoute || [])
    .map((point) => [point[1], point[0]]);
  const legTwo = (data.victimToHospitalRoute || [])
    .map((point) => [point[1], point[0]]);

  if (!map) {
    map = L.map("trackMap", { zoomControl: true, attributionControl: true });

    /* Free basemap: OpenStreetMap standard tiles (no API key). */
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
      attribution: "&copy; OpenStreetMap contributors",
      subdomains: "abc",
      maxZoom: 19
    }).addTo(map);

    /* leg 1: ambulance -> victim */
    if (legOne.length > 0) {
      legOneLayer = createGlowRoute(legOne, "#ff6b1a").addTo(map);
    }

    /* leg 2: victim -> hospital */
    if (legTwo.length > 0) {
      legTwoLayer = createGlowRoute(legTwo, "#2ee6a8").addTo(map);
    }

    victimMarker = L.marker(victimPoint, { icon: createMarkerIcon("victim") })
      .addTo(map)
      .bindPopup("<b>YOU</b><br>Waiting for the ambulance");

    if (legTwo.length > 0) {
      hospitalMarker =
        L.marker(legTwo[legTwo.length - 1],
          { icon: createMarkerIcon("hospital") })
          .addTo(map)
          .bindPopup("<b>HOSPITAL</b><br>" +
            escapeHtml(data.hospitalName || ""));
    }

    const allPoints = [victimPoint, ...legOne, ...legTwo];
    if (allPoints.length > 0) {
      map.fitBounds(L.latLngBounds(allPoints), { padding: [40, 40] });
    } else {
      map.setView(victimPoint, 14);
    }

    setTimeout(() => {
      if (map) {
        map.invalidateSize();
      }
    }, 250);
  }

  /* "YOU" marker: always re-synced with the authoritative backend
     value on every poll — the page never trusts stale JS state. */
  if (victimMarker
      && data.victimLatitude != null
      && data.victimLongitude != null) {
    victimMarker.setLatLng([data.victimLatitude, data.victimLongitude]);
  }

  /* hospital marker: (re)appears as soon as leg-2 geometry exists,
     even if the first poll did not have it yet */
  if (legTwo.length > 0) {
    const hospitalPoint = legTwo[legTwo.length - 1];

    if (!hospitalMarker) {
      hospitalMarker =
        L.marker(hospitalPoint, { icon: createMarkerIcon("hospital") })
          .addTo(map)
          .bindPopup("<b>HOSPITAL</b><br>" +
            escapeHtml(data.hospitalName || ""));
    } else {
      hospitalMarker.setLatLng(hospitalPoint);
    }
  }

  /* live ambulance position — only ever the real backend GPS value */
  if (data.ambulanceLocationAvailable
      && data.ambulanceLatitude != null
      && data.ambulanceLongitude != null) {

    const position = [data.ambulanceLatitude, data.ambulanceLongitude];

    if (!ambulanceMarker) {
      ambulanceMarker =
        L.marker(position, { icon: createMarkerIcon("ambulance") })
          .addTo(map)
          .bindPopup("<b>YOUR AMBULANCE</b><br>" +
            escapeHtml(data.ambulanceVehicleNumber || ""));
    } else {
      ambulanceMarker.setLatLng(position);
    }
  } else if (ambulanceMarker) {
    map.removeLayer(ambulanceMarker);
    ambulanceMarker = null;
  }
}

/* ---------------------------------------------------------------------------
   Polling
--------------------------------------------------------------------------- */

/* One poll at a time: a slow response can never overwrite the state
   of a newer one (out-of-order response protection). */
let pollInFlight = false;

async function poll() {
  if (finished || pollInFlight) {
    return;
  }

  pollInFlight = true;

  try {
    const response = await fetch(
      "/track/" + encodeURIComponent(token),
      { cache: "no-store", headers: { "Accept": "application/json" } }
    );

    if (response.status === 404) {
      stop();
      hide("trackContent");
      show("invalidState");
      return;
    }

    if (!response.ok) {
      setTemporaryError(
        "Could not refresh tracking right now. Retrying automatically…"
      );
      return;
    }

    setTemporaryError(null);

    const data = await response.json();
    render(data);
  } catch (error) {
    setTemporaryError(
      "Could not refresh tracking right now. Retrying automatically…"
    );
  } finally {
    pollInFlight = false;
  }
}

function stop() {
  if (pollTimer !== null) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

/* ---------------------------------------------------------------------------
   Boot
--------------------------------------------------------------------------- */

if (!token) {
  show("invalidState");
} else {
  poll();
  pollTimer = setInterval(poll, POLL_INTERVAL_MS);
}
