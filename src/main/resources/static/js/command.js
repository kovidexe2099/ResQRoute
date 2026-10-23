/* ============================================================================
   ResQRoute — Command Center logic (command.js)
   ----------------------------------------------------------------------------
   Backend endpoints used:

     GET    /ambulance                  -> loadAmbulances()
     POST   /ambulance                  -> saveAmbulance()
     PUT    /ambulance/{id}            -> updateAmbulance()
     PUT    /ambulance/{id}/status     -> updateDispatchAmbulanceStatus()
     PUT    /ambulance/{id}/location    -> sendAmbulanceGPS(), updateAmbulanceGPS()
     DELETE /ambulance/{id}            -> deleteAmbulance()
     GET    /hospital                   -> loadHospitals()
     POST   /hospital                   -> saveHospital()
     PUT    /hospital/{id}             -> updateHospital()
     POST   /route/best-ambulance      -> findBestAmbulance()
     GET    /route/latest               -> checkForLatestEmergency() (polled 2s)

   Architecture notes:
     - /route/latest returns ALL active dispatches; each one owns its
       own markers and routes in dispatchMarkers[dispatchId], so several
       simultaneous emergencies are rendered fully independently.
     - Ambulance movement on the map comes ONLY from the database (each
       dispatch's live ambulanceLatitude/Longitude), never from
       browser-remembered state.
     - The demo simulation is a manual staff-only tool: it animates the
       focused dispatch's own marker and reports its lifecycle statuses;
       it never starts by itself.
   ============================================================================ */

/* Same-origin API: this page is served by ResQRoute itself, so every
   request rides on the authenticated staff session. */
const API_BASE = "";

/* ---------------------------------------------------------------------------
   Authenticated API helper

   - JSON accept header on every call
   - CSRF token (readable XSRF-TOKEN cookie) on every mutation
   - clean redirect to the login page when the session is gone (401)
--------------------------------------------------------------------------- */

function csrfTokenFromCookie() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : null;
}

async function apiFetch(url, options = {}) {
  const headers = Object.assign(
    { "Accept": "application/json" },
    options.headers || {}
  );

  const method = (options.method || "GET").toUpperCase();

  if (method !== "GET" && method !== "HEAD") {
    const token = csrfTokenFromCookie();
    if (token) {
      headers["X-XSRF-TOKEN"] = token;
    }
    if (options.body && !headers["Content-Type"]) {
      headers["Content-Type"] = "application/json";
    }
  }

  const response = await fetch(
    url.startsWith("http") ? url : API_BASE + url,
    Object.assign({}, options, { headers })
  );

  if (response.status === 401) {
    window.location.href = "/index.html";
    throw new Error("Not authenticated");
  }

  return response;
}

/* Small HTML-escape for every backend string rendered into the page.
   Entities are built programmatically (charcode 38 = "&"). */
function escapeHtml(value) {
  const AMP = String.fromCharCode(38);
  return String(value == null ? "" : value)
    .replaceAll(AMP, AMP + "amp;")
    .replaceAll("<", AMP + "lt;")
    .replaceAll(">", AMP + "gt;")
    .replaceAll("\"", AMP + "quot;")
    .replaceAll("'", AMP + "#39;");
}

/* localStorage key for the ambulance this device streams GPS for */
const GPS_AMBULANCE_KEY = "resqroute.gpsAmbulanceId";

/* ---------------------------------------------------------------------------
   State
--------------------------------------------------------------------------- */
let allAmbulances = [];

let hospitalList = [];

let gpsAmbulanceId = null;   // chosen in the UI, no longer hardcoded

let map = null;

/* Per-dispatch map state, keyed by the persisted dispatchId:
   every active dispatch owns its OWN markers, routes and GPS position,
   so updating one ambulance can never affect another. */
let dispatchMarkers = {};

/* The active-dispatch list from the latest poll — used whenever the
   map (re)builds so ALL active dispatches re-appear independently. */
let lastActiveList = [];

/* Id of the dispatch the operator's demo simulation is animating
   (a manual, staff-only action — never the authoritative movement). */
let simulationDispatchId = null;

let lastDispatch = null;
let latestDispatchId = null;
let activeEmergencyCount = 0;
let dispatchPollingInterval = null;

let simulationSpeedMultiplier = 1;
let simulationAnimationFrame = null;

let simulationRoute = [];
let simulationRouteDistances = [];
let simulationTotalDistance = 0;
let simulationDurationSeconds = 0;
let simulationStartTime = null;

let simulationLeg = 1;

let firstLegRoute = [];
let secondLegRoute = [];

let firstLegDuration = 0;
let secondLegDuration = 0;

/* ---------------------------------------------------------------------------
   UI helpers (no API involved)
--------------------------------------------------------------------------- */

/* Toast notifications — a non-blocking replacement for alert() */
function showToast(message, type = "info") {
  const stack = document.getElementById("toastStack");
  if (!stack) {
    console.log(message);
    return;
  }

  const toast = document.createElement("div");
  toast.className = "toast toast-" + type;
  toast.textContent = message;
  stack.appendChild(toast);

  requestAnimationFrame(() => toast.classList.add("show"));

  setTimeout(() => {
    toast.classList.remove("show");
    setTimeout(() => toast.remove(), 400);
  }, 3600);
}

/* Pick a badge color class from a status string (display only) */
function statusBadgeClass(status) {
  const value = (status || "").toString().trim().toLowerCase();

  if (value === "available") {
    return "badge badge-available";
  }

  if (value === "busy" || value === "dispatched" || value === "out_of_service") {
    return "badge badge-busy";
  }

  if (value === "to_victim" || value === "at_victim" || value === "to_hospital") {
    return "badge badge-info";
  }

  return "badge";
}

/* Emergency lifecycle stepper — purely visual. */
const LIFECYCLE_STAGES = [
  "received",
  "assigned",
  "to_victim",
  "victim_reached",
  "to_hospital",
  "hospital_reached",
  "available"
];

function setLifecycleStage(stage) {
  const stepper = document.getElementById("lifecycleStepper");
  if (!stepper) {
    return;
  }

  const index = LIFECYCLE_STAGES.indexOf(stage);
  if (index === -1) {
    return;
  }

  stepper.querySelectorAll(".lifecycle-step").forEach((element, i) => {
    element.classList.toggle("done", i < index);
    element.classList.toggle("active", i === index);
  });
}

/* Stat cards: computed from data that was ALREADY fetched
   (no extra API call — the active count comes from /route/latest). */
function updateFleetStats() {
  const total = allAmbulances.length;
  const available = allAmbulances.filter(
    (a) => (a.status || "").toString().trim().toLowerCase() === "available"
  ).length;
  const busy = total - available;

  const availableElement = document.getElementById("statAvailable");
  const busyElement = document.getElementById("statBusy");
  const emergencyElement = document.getElementById("statEmergency");
  const emergencyCard = document.getElementById("statEmergencyCard");

  if (availableElement) availableElement.textContent = available;
  if (busyElement) busyElement.textContent = busy;
  if (emergencyElement) emergencyElement.textContent = activeEmergencyCount;
  if (emergencyCard) {
    emergencyCard.classList.toggle("is-active", activeEmergencyCount > 0);
  }
}

/* Live clock in the top bar */
function updateClock() {
  const clock = document.getElementById("commandClock");
  if (clock) {
    clock.textContent = new Date().toLocaleTimeString();
  }
}

/* ---------------------------------------------------------------------------
   "This device" ambulance picker: the operator chooses which unit this
   browser streams GPS for (persisted in localStorage).
--------------------------------------------------------------------------- */
function loadStoredGpsAmbulanceId() {
  const stored = window.localStorage.getItem(GPS_AMBULANCE_KEY);
  const parsed = stored ? Number(stored) : null;
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

function refreshAmbulancePicker() {
  const select = document.getElementById("gpsAmbulanceSelect");
  if (!select) {
    return;
  }

  const previous = gpsAmbulanceId;
  const sorted = [...allAmbulances].sort((a, b) => a.id - b.id);

  select.innerHTML = sorted.map((a) =>
    `<option value="${a.id}" ${a.id === previous ? "selected" : ""}>
       #${a.id} · ${escapeHtml(a.vehicleNumber || "unnamed")}
     </option>`
  ).join("");

  if (sorted.length === 0) {
    select.innerHTML =
      '<option value="">No ambulances registered</option>';
    gpsAmbulanceId = null;
    return;
  }

  if (!sorted.some((a) => a.id === previous)) {
    gpsAmbulanceId = sorted[0].id;
    select.value = String(gpsAmbulanceId);
    if (previous !== null) {
      startAutomaticGPS();   // selection vanished (deleted) — re-target
    }
  }
}

function onGpsAmbulanceChange() {
  const select = document.getElementById("gpsAmbulanceSelect");
  const chosen = select ? Number(select.value) : null;

  if (chosen && chosen !== gpsAmbulanceId) {
    gpsAmbulanceId = chosen;
    window.localStorage.setItem(GPS_AMBULANCE_KEY, String(chosen));

    const ambulance = allAmbulances.find((a) => a.id === chosen);
    showToast(
      "This device now streams GPS for " +
      (ambulance ? ambulance.vehicleNumber : "ambulance #" + chosen),
      "success"
    );

    startAutomaticGPS();
  }
}

/* ---------------------------------------------------------------------------
   Tab navigation
--------------------------------------------------------------------------- */
function showTab(id, button) {
  document
    .querySelectorAll(".cmd-section")
    .forEach((section) => {
      section.classList.remove("active");
    });

  document
    .getElementById(id)
    .classList.add("active");

  document
    .querySelectorAll(".cmd-tabs button")
    .forEach((btn) => {
      btn.classList.remove("active");
    });

  button.classList.add("active");

  const info = {
    ambulances: ["Fleet", "Manage your ambulance fleet"],
    hospitals: ["Hospitals", "Manage destination hospitals"],
    emergency: ["Emergency Dispatch", "Dispatch the best available ambulance"],
    operations: ["Dispatches & Emergencies", "Live dispatches and queued emergencies, straight from the database"],
    dispatchMap: ["Dispatch Map", "Visualize the complete emergency route"]
  }[id];

  document.getElementById("title").textContent = info[0];
  document.getElementById("subtitle").textContent = info[1];

  if (id === "hospitals") {
    loadHospitals();
  }

  if (id === "dispatchMap") {
    if (lastDispatch) {
      renderDispatchMap(lastDispatch);
    }

    setTimeout(() => {
      if (map) {
        map.invalidateSize();
      }
    }, 200);
  }
}

/* Disclosure toggles for the Add / Modify forms */
function toggleForm(id, button) {
  const form = document.getElementById(id);
  const open = form.classList.toggle("open");
  button.querySelector("span").textContent = open ? "−" : "+";
}

/* ---------------------------------------------------------------------------
   Ambulance GPS streaming (device = chosen ambulance)
--------------------------------------------------------------------------- */

async function updateAmbulanceGPS() {
  if (!gpsAmbulanceId) {
    throw new Error("No ambulance selected for this device.");
  }

  if (!navigator.geolocation) {
    throw new Error("Geolocation is not supported by this browser.");
  }

  const position = await new Promise((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(
      resolve,
      reject,
      {
        enableHighAccuracy: true,
        timeout: 10000,
        maximumAge: 0
      }
    );
  });

  return sendAmbulanceGPS(
    position.coords.latitude,
    position.coords.longitude
  );
}

let gpsWatchId = null;

async function sendAmbulanceGPS(latitude, longitude) {
  if (!gpsAmbulanceId) {
    return null;   // no device ambulance chosen yet — nothing to stream
  }

  const response = await apiFetch(
    `/ambulance/${gpsAmbulanceId}/location`,
    {
      method: "PUT",
      body: JSON.stringify({
        latitude: latitude,
        longitude: longitude,
        locationTimestamp: Date.now()   // lets the backend reject stale fixes
      })
    }
  );

  if (!response.ok) {
    throw new Error("Could not update ambulance location.");
  }

  return await response.json();
}

function startAutomaticGPS() {
  if (!navigator.geolocation) {
    console.error("Geolocation is not supported by this browser.");
    return;
  }

  if (!gpsAmbulanceId) {
    console.warn("GPS streaming idle: pick an ambulance first.");
    return;
  }

  if (gpsWatchId !== null) {
    navigator.geolocation.clearWatch(gpsWatchId);
  }

  gpsWatchId = navigator.geolocation.watchPosition(

    async function (position) {
      try {
        await sendAmbulanceGPS(
          position.coords.latitude,
          position.coords.longitude
        );
        await loadAmbulances();
      } catch (error) {
        console.error("Automatic GPS update failed:", error.message);
      }
    },

    function (error) {
      console.error("Automatic GPS tracking failed:", error.message);
    },

    {
      enableHighAccuracy: true,
      timeout: 10000,
      maximumAge: 0
    }
  );
}

/* ---------------------------------------------------------------------------
   Ambulance CRUD
--------------------------------------------------------------------------- */
async function loadAmbulances() {
  try {
    const response = await apiFetch("/ambulance");

    if (!response.ok) {
      throw new Error();
    }

    allAmbulances = await response.json();

    renderTable(allAmbulances);
    updateFleetStats();
    refreshAmbulancePicker();

  } catch (error) {
    document.getElementById("ambulanceTable").innerHTML =
      '<tr><td colspan="6">Could not load ambulances. Is Spring Boot running?</td></tr>';
  }
}

function renderTable(list) {
  document.getElementById("ambulanceTable").innerHTML =
    list.map((a) => `

      <tr>

        <td class="mono">#${a.id ?? ""}</td>

        <td><strong>${escapeHtml(a.vehicleNumber)}</strong></td>

        <td>
          <span class="${statusBadgeClass(a.status)}">
            ${(a.status ?? "").toString().toUpperCase()}
          </span>
        </td>

        <td>${escapeHtml(a.location)}</td>

        <td class="mono">
          ${a.latitude ?? "-"},
          ${a.longitude ?? "-"}
        </td>

        <td>

          <button class="btn btn-ghost btn-sm"
                  onclick="openEditById(${a.id})">
            Edit
          </button>

          <button class="btn btn-ghost btn-sm"
                  onclick="deleteAmbulance(${a.id})">
            Delete
          </button>

        </td>

      </tr>

    `).join("");
}

/* Looks the ambulance up from the already-loaded list — never passes
   user-entered data through an HTML attribute. */
function openEditById(id) {
  const ambulance = allAmbulances.find((a) => a.id === id);
  if (ambulance) {
    openEdit(ambulance);
  }
}

function filterTable() {
  const query =
    document.getElementById("searchBox").value.toLowerCase();

  renderTable(
    allAmbulances.filter((a) =>
      `${a.vehicleNumber}
       ${a.status}
       ${a.location}`
        .toLowerCase()
        .includes(query)
    )
  );
}

async function saveAmbulance() {
  const body = {
    vehicleNumber: document.getElementById("vehicleNumber").value,
    status: document.getElementById("status").value,
    location: document.getElementById("location").value,

    latitude: parseFloat(document.getElementById("latitude").value),
    longitude: parseFloat(document.getElementById("longitude").value)
  };

  const response = await apiFetch("/ambulance", {
    method: "POST",
    body: JSON.stringify(body)
  });

  if (!response.ok) {
    const error = await response.json().catch(() => null);
    return showToast(error?.message || "Could not save ambulance", "error");
  }

  showToast("Ambulance added successfully", "success");

  loadAmbulances();
}

function openEdit(a) {
  document.getElementById("modifyForm").classList.add("open");

  document.getElementById("updateId").value = a.id;
  document.getElementById("updateVehicleNumber").value = a.vehicleNumber || "";
  document.getElementById("updateStatus").value = a.status || "";
  document.getElementById("updateLocation").value = a.location || "";
  document.getElementById("updateLatitude").value = a.latitude ?? "";
  document.getElementById("updateLongitude").value = a.longitude ?? "";

  document.getElementById("modifyForm").scrollIntoView({
    behavior: "smooth",
    block: "center"
  });
}

async function updateAmbulance() {
  const id = document.getElementById("updateId").value;

  if (!id) {
    return showToast("Enter ambulance ID", "error");
  }

  const body = {
    vehicleNumber: document.getElementById("updateVehicleNumber").value,
    status: document.getElementById("updateStatus").value,
    location: document.getElementById("updateLocation").value,

    latitude: parseFloat(document.getElementById("updateLatitude").value),
    longitude: parseFloat(document.getElementById("updateLongitude").value)
  };

  const response = await apiFetch(`/ambulance/${id}`, {
    method: "PUT",
    body: JSON.stringify(body)
  });

  if (!response.ok) {
    const error = await response.json().catch(() => null);
    return showToast(error?.message || "Could not update ambulance", "error");
  }

  showToast("Ambulance updated successfully", "success");

  loadAmbulances();
}

async function deleteAmbulance(id) {
  if (!confirm("Delete this ambulance?")) {
    return;
  }

  const response = await apiFetch(`/ambulance/${id}`, {
    method: "DELETE"
  });

  if (!response.ok) {
    const error = await response.json().catch(() => null);
    return showToast(error?.message || "Could not delete ambulance", "error");
  }

  showToast("Ambulance deleted", "success");

  loadAmbulances();
}

/* ---------------------------------------------------------------------------
   Hospital CRUD
--------------------------------------------------------------------------- */
async function saveHospital() {
  const body = {
    name: document.getElementById("hospitalName").value,
    location: document.getElementById("hospitalLocation").value,

    latitude: parseFloat(document.getElementById("hospitalLatitude").value),
    longitude: parseFloat(document.getElementById("hospitalLongitude").value)
  };

  const response = await apiFetch("/hospital", {
    method: "POST",
    body: JSON.stringify(body)
  });

  if (!response.ok) {
    const error = await response.json().catch(() => null);
    return showToast(error?.message || "Could not save hospital", "error");
  }

  showToast("Hospital added successfully", "success");

  loadHospitals();
}

async function loadHospitals() {
  try {
    const response = await apiFetch("/hospital");

    if (!response.ok) {
      throw new Error();
    }

    const hospitals = await response.json();

    hospitalList = hospitals;

    renderHospitals(hospitals);

    const statHospitals = document.getElementById("statHospitals");
    if (statHospitals) {
      statHospitals.textContent = hospitals.length;
    }

  } catch (error) {
    document.getElementById("hospitalTable").innerHTML =
      '<tr><td colspan="5">Could not load hospitals.</td></tr>';
  }
}

function renderHospitals(list) {
  document.getElementById("hospitalTable").innerHTML =
    list.map((h) => `

      <tr>

        <td class="mono">#${h.id ?? ""}</td>

        <td><strong>${escapeHtml(h.name)}</strong></td>

        <td>${escapeHtml(h.location)}</td>

        <td class="mono">
          ${h.latitude ?? "-"},
          ${h.longitude ?? "-"}
        </td>

        <td>

          <button class="btn btn-ghost btn-sm"
                  onclick="openHospitalEditById(${h.id})">
            Edit
          </button>

        </td>

      </tr>

    `).join("");
}

/* Looks the hospital up from the loaded list (id-based, no data in
   HTML attributes). */
function openHospitalEditById(id) {
  const hospital = hospitalList.find((h) => h.id === id);
  if (hospital) {
    openHospitalEdit(hospital);
  }
}

function openHospitalEdit(hospital) {
  document.getElementById("modifyHospitalForm").classList.add("open");

  document.getElementById("updateHospitalId").value = hospital.id;
  document.getElementById("updateHospitalName").value = hospital.name || "";
  document.getElementById("updateHospitalLocation").value = hospital.location || "";
  document.getElementById("updateHospitalLatitude").value = hospital.latitude ?? "";
  document.getElementById("updateHospitalLongitude").value = hospital.longitude ?? "";

  document.getElementById("modifyHospitalForm").scrollIntoView({
    behavior: "smooth",
    block: "center"
  });
}

async function updateHospital() {
  const id = document.getElementById("updateHospitalId").value;

  if (!id) {
    return showToast("Enter hospital ID", "error");
  }

  const body = {
    name: document.getElementById("updateHospitalName").value,
    location: document.getElementById("updateHospitalLocation").value,

    latitude: parseFloat(document.getElementById("updateHospitalLatitude").value),
    longitude: parseFloat(document.getElementById("updateHospitalLongitude").value)
  };

  const response = await apiFetch(`/hospital/${id}`, {
    method: "PUT",
    body: JSON.stringify(body)
  });

  if (!response.ok) {
    const error = await response.json().catch(() => null);
    return showToast(error?.message || "Could not update hospital", "error");
  }

  showToast("Hospital updated successfully", "success");

  loadHospitals();
}

/* ---------------------------------------------------------------------------
   Emergency dispatch (POST /route/best-ambulance)
--------------------------------------------------------------------------- */

/* Optional convenience: fill the dispatch form with this browser's GPS. */
/* Operator testing: ONE TAP -> this device's browser GPS -> the
   EXISTING findBestAmbulance() flow. This is the exact same
   mechanism the victim page uses — no typed coordinates, and the
   same POST /route/best-ambulance endpoint. */
function requestAmbulanceFromDeviceLocation() {
  if (!navigator.geolocation) {
    showToast("Location services are not supported on this device.", "error");
    return;
  }

  showToast("Getting this device's location…", "info");

  navigator.geolocation.getCurrentPosition(

    function (position) {
      /* The browser obtained the position — fill the (testing)
         fields and reuse the existing dispatch flow unchanged. */
      document.getElementById("sourceLatitude").value =
        position.coords.latitude.toFixed(6);
      document.getElementById("sourceLongitude").value =
        position.coords.longitude.toFixed(6);

      showToast("Location obtained — dispatching", "success");

      findBestAmbulance();
    },

    function (error) {
      if (error.code === 1) {
        showToast("Location permission is required to simulate a request.",
          "error");
      } else if (error.code === 2) {
        showToast("Could not determine your location. Please try again.",
          "error");
      } else if (error.code === 3) {
        showToast("Location request timed out. Please try again.", "error");
      } else {
        showToast("Could not get your location. Please try again.", "error");
      }
    },

    {
      enableHighAccuracy: true,
      timeout: 10000,
      maximumAge: 0
    }
  );
}

/* Pretty summary cards for the dispatch result (display only). */
function renderDispatchSummary(data) {
  const summary = document.getElementById("dispatchSummary");
  if (!summary) {
    return;
  }

  const trafficClass =
    (data.trafficLevel || "low").toString().toLowerCase();

  summary.innerHTML = `

    <div class="summary-card">
      <div class="card-label">Emergency</div>
      <div class="card-value">#${data.emergencyId ?? "—"}</div>
      <div class="card-sub">${(data.severity || "NORMAL")} · ${(data.emergencyStatus || "").toString().toLowerCase()}</div>
    </div>

    <div class="summary-card">
      <div class="card-label">Ambulance</div>
      <div class="card-value">${escapeHtml(data.vehicleNumber) || "—"}</div>
      <div class="card-sub">Unit ID ${data.ambulanceId ?? "—"}</div>
    </div>

    <div class="summary-card">
      <div class="card-label">To Victim</div>
      <div class="card-value">${data.ambulanceToVictimDistanceKm ?? "-"} km</div>
      <div class="card-sub">ETA ${data.ambulanceToVictimDurationMin ?? "-"} min</div>
    </div>

    <div class="summary-card">
      <div class="card-label">Traffic</div>
      <div class="card-value traffic-${trafficClass}">
        ${(data.trafficLevel || "LOW").toString().toUpperCase()}
      </div>
      <div class="card-sub">Adjusted ETA ${data.ambulanceToVictimDurationMin ?? "-"} min</div>
    </div>

    <div class="summary-card">
      <div class="card-label">To Hospital</div>
      <div class="card-value">${data.victimToHospitalDistanceKm ?? "-"} km</div>
      <div class="card-sub">ETA ${data.victimToHospitalDurationMin ?? "-"} min</div>
    </div>

    <div class="summary-card">
      <div class="card-label">Destination</div>
      <div class="card-value">${escapeHtml(data.hospitalName) || "—"}</div>
      <div class="card-sub">${escapeHtml(data.hospitalLocation) || ""}</div>
    </div>

  `;
}

async function findBestAmbulance() {
  /* Refresh this device's ambulance GPS first (same as original) */
  try {
    await updateAmbulanceGPS();
  } catch (error) {
    console.error("Could not refresh ambulance GPS:", error.message);
  }

  const lat = parseFloat(document.getElementById("sourceLatitude").value);
  const lon = parseFloat(document.getElementById("sourceLongitude").value);

  if (isNaN(lat) || isNaN(lon)) {
    return showToast("Enter valid coordinates", "error");
  }

  try {
    const response = await apiFetch("/route/best-ambulance", {
      method: "POST",
      body: JSON.stringify({
        sourceLatitude: lat,
        sourceLongitude: lon
      })
    });

    if (response.status === 409) {
      const error = await response.json().catch(() => null);

      if (error && error.code === "NO_AMBULANCE_AVAILABLE") {
        updateLiveEmergencyStatus(
          "No available ambulance — emergency #" +
          (error.details?.emergencyId ?? "?") +
          " is QUEUED and will dispatch automatically."
        );
        return showToast(
          "No available ambulance — emergency queued", "error"
        );
      }

      return showToast(
        error?.message || "Could not dispatch ambulance.", "error"
      );
    }

    if (!response.ok) {
      document.getElementById("routeResponse").textContent =
        "Could not dispatch ambulance.";
      return showToast("Could not dispatch ambulance.", "error");
    }

    const data = await response.json();

    lastDispatch = data;
    latestDispatchId = data.dispatchId;

    updateLiveEmergencyStatus(
      "Manual dispatch created for " +
      (data.vehicleNumber || "ambulance") +
      "."
    );

    /* Show the pretty summary + keep raw JSON for debugging */
    renderDispatchSummary(data);

    document.getElementById("routeResponse").textContent =
      JSON.stringify(data, null, 2);

    /* Emergency lifecycle: received -> assigned (the backend has
       already set the ambulance to DISPATCHED atomically) */
    setLifecycleStage("assigned");

    const mapNavButton =
      document.querySelector('[data-tab="dispatchMap"]');

    showTab("dispatchMap", mapNavButton);

    const mapButton = document.createElement("button");
    mapButton.className = "btn btn-ghost btn-sm";
    mapButton.textContent = "View Route on Map";

    mapButton.onclick = function () {
      showTab("dispatchMap", mapNavButton);
    };

    const existingButton = document.getElementById("viewMapButton");

    if (existingButton) {
      existingButton.remove();
    }

    mapButton.id = "viewMapButton";

    document
      .querySelector("#dispatchResult")
      .appendChild(mapButton);

    showToast("Ambulance dispatched successfully", "success");

  } catch (error) {
    document.getElementById("routeResponse").textContent =
      "Could not connect to the backend.";
    showToast("Could not connect to the backend.", "error");
  }
}

/* ---------------------------------------------------------------------------
   Dispatch map (Leaflet)
--------------------------------------------------------------------------- */
function renderDispatchMap(data) {
  const mapArea = document.getElementById("mapArea");

  if (!data) {
    mapArea.innerHTML = `
      <div class="no-dispatch">
        <div class="nd-title"><span class="pulse-dot orange"></span> STANDBY</div>
        No dispatch available yet.<br><br>
        Dispatches appear here automatically when a victim requests an
        ambulance — and every active one is restored from the database
        after a refresh or a login.
      </div>
    `;
    resetMap();   // no stale Leaflet state behind the standby screen
    return;
  }

  const trafficLevel = data.trafficLevel || "Low";

  const trafficClass =
    trafficLevel.toLowerCase() === "high"
      ? "traffic-high"
      : trafficLevel.toLowerCase() === "medium"
        ? "traffic-medium"
        : "traffic-low";

  mapArea.innerHTML = `

    <div class="dispatch-strip">

      <div class="strip-chip">
        <div class="card-label">Emergency</div>
        <div class="card-value">#${data.emergencyId}</div>
        <div class="card-sub">${(data.severity || "NORMAL")}</div>
      </div>

      <div class="strip-chip">
        <div class="card-label">Ambulance</div>
        <div class="card-value">${escapeHtml(data.vehicleNumber)}</div>
        <div class="card-sub">ID ${data.ambulanceId}</div>
      </div>

      <div class="strip-chip">
        <div class="card-label">Status</div>
        <div class="card-value">
          <span class="${statusBadgeClass(data.ambulanceStatus)}">
            ${(data.ambulanceStatus || "DISPATCHED").toString().toUpperCase()}
          </span>
        </div>
      </div>

      <div class="strip-chip">
        <div class="card-label">To Victim</div>
        <div class="card-value">${data.ambulanceToVictimDistanceKm} km</div>
        <div class="card-sub">ETA ${data.ambulanceToVictimDurationMin} min</div>
      </div>

      <div class="strip-chip">
        <div class="card-label">Traffic</div>
        <div class="card-value ${trafficClass}">${trafficLevel}</div>
        <div class="card-sub">Adjusted ETA ${data.ambulanceToVictimDurationMin} min</div>
      </div>

    </div>

    <div class="map-container">
      <div id="map"></div>
    </div>

    <div class="lifecycle glass">

      <div class="lifecycle-track" id="lifecycleStepper">

        <div class="lifecycle-step">
          <div class="lifecycle-node"></div>
          <div class="lifecycle-label">EMERGENCY<br>RECEIVED</div>
        </div>

        <div class="lifecycle-step">
          <div class="lifecycle-node"></div>
          <div class="lifecycle-label">AMBULANCE<br>ASSIGNED</div>
        </div>

        <div class="lifecycle-step">
          <div class="lifecycle-node"></div>
          <div class="lifecycle-label">TRAVELLING<br>TO VICTIM</div>
        </div>

        <div class="lifecycle-step">
          <div class="lifecycle-node"></div>
          <div class="lifecycle-label">VICTIM<br>REACHED</div>
        </div>

        <div class="lifecycle-step">
          <div class="lifecycle-node"></div>
          <div class="lifecycle-label">TRAVELLING<br>TO HOSPITAL</div>
        </div>

        <div class="lifecycle-step">
          <div class="lifecycle-node"></div>
          <div class="lifecycle-label">HOSPITAL<br>REACHED</div>
        </div>

        <div class="lifecycle-step">
          <div class="lifecycle-node"></div>
          <div class="lifecycle-label">AMBULANCE<br>AVAILABLE</div>
        </div>

      </div>

    </div>

    <div class="simulation-panel glass">

      <div class="simulation-header">

        <div class="simulation-status">
          <span class="pulse-dot orange"></span>
          <span id="simulationStatus">Live GPS is always shown — start a demo to animate this dispatch</span>
        </div>

        <div class="simulation-actions">

          <button class="btn btn-primary btn-sm"
                  onclick="startManualSimulation()">
            START DEMO SIMULATION
          </button>

          <div class="simulation-speed-buttons">

            <button class="speed-btn active"
                    onclick="setSimulationSpeed(1, this)">
              1x
            </button>

            <button class="speed-btn"
                    onclick="setSimulationSpeed(2, this)">
              2x
            </button>

            <button class="speed-btn"
                    onclick="setSimulationSpeed(5, this)">
              5x
            </button>

            <button class="speed-btn"
                    onclick="setSimulationSpeed(10, this)">
              10x
            </button>

          </div>

        </div>

      </div>

      <div class="simulation-stats">

        <div class="sim-stat">
          <div class="sim-stat-label">SPEED</div>
          <div class="sim-stat-value" id="simulationSpeed">0 km/h</div>
        </div>

        <div class="sim-stat">
          <div class="sim-stat-label">DISTANCE REMAINING</div>
          <div class="sim-stat-value" id="simulationDistance">0 km</div>
        </div>

        <div class="sim-stat">
          <div class="sim-stat-label">ETA</div>
          <div class="sim-stat-value" id="simulationEta">0 min</div>
        </div>

        <div class="sim-stat">
          <div class="sim-stat-label">PROGRESS</div>
          <div class="sim-stat-value" id="simulationProgress">0%</div>
        </div>

      </div>

      <div class="sim-progress">
        <div class="sim-progress-bar" id="simulationProgressBar"></div>
      </div>

    </div>

    <div class="route-legend">

      <div class="legend-item">
        <div class="legend-line legend-orange"></div>
        Ambulance to Victim
      </div>

      <div class="legend-item">
        <div class="legend-line legend-green"></div>
        Victim to Hospital
      </div>

    </div>

    <div class="dispatch-details">

      <div class="detail-card glass">
        <h3>Emergency #${data.emergencyId}</h3>
        <p>Severity: <strong>${(data.severity || "NORMAL")}</strong></p>
        <p>Status: <strong>${data.emergencyStatus ?? "—"}</strong></p>
      </div>

      <div class="detail-card glass">
        <h3>Ambulance</h3>
        <p>Vehicle: <strong>${escapeHtml(data.vehicleNumber)}</strong></p>
        <p>Base: <strong>${escapeHtml(data.ambulanceLocation) || "—"}</strong></p>
        <p>Status: <strong>${data.ambulanceStatus ?? "—"}</strong></p>
      </div>

      <div class="detail-card glass">
        <h3>Hospital</h3>
        <p><strong>${escapeHtml(data.hospitalName)}</strong></p>
        <p>${escapeHtml(data.hospitalLocation) || ""}</p>
      </div>

    </div>

  `;

  /* the panel HTML above just replaced the #map DOM: rebuild the map
     and re-draw EVERY active dispatch independently from the last
     authoritative snapshot (never from browser-remembered state) */
  resetMap();
  ensureMap();

  const listToDraw = lastActiveList.length > 0
    ? lastActiveList
    : (data.dispatchStatus === "ACTIVE" ? [data] : []);

  syncDispatchMarkers(listToDraw);
}

/* Custom Leaflet marker icons (visual only) */
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

/* Glowing route: soft under-glow + solid line + flowing light on top */
function createGlowRoute(points, color) {
  return L.layerGroup([
    L.polyline(points, {
      color: color,
      weight: 12,
      opacity: 0.14,
      lineCap: "round"
    }),
    L.polyline(points, {
      color: color,
      weight: 4.5,
      opacity: 0.95,
      lineCap: "round"
    }),
    L.polyline(points, {
      color: "#ffe9d2",
      weight: 4.5,
      opacity: 0.85,
      lineCap: "round",
      className: "route-flow"
    })
  ]);
}

/* ---------------------------------------------------------------------------
   MULTI-DISPATCH MAP
   ----------------------------------------------------------------------------
   The Leaflet map is created once and every ACTIVE dispatch gets its own
   independent entry in dispatchMarkers[dispatchId]:

     { group, victimMarker, hospitalMarker, ambulanceMarker }

   - routes/markers are created once per dispatch (from its own response)
   - the ambulance marker is moved ONLY by that dispatch's own live GPS
   - completing a dispatch removes exactly its own layer group
   - the database (via the polling snapshot) is the ONLY authority —
     the map never invents or remembers positions by itself
--------------------------------------------------------------------------- */

function resetMap() {
  map = null;
  dispatchMarkers = {};
}

function ensureMap() {
  if (map) {
    return;
  }

  map = L.map("map", { zoomControl: true });

  /* Free basemap: OpenStreetMap standard tiles (no API key). */
  L.tileLayer(
    "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",
    {
      attribution: "&copy; OpenStreetMap contributors",
      subdomains: "abc",
      maxZoom: 19
    }
  ).addTo(map);

  map.setView([17.4239, 78.4738], 11);

  setTimeout(() => {
    if (map) {
      map.invalidateSize();
    }
  }, 300);
}

/* Brings the map in sync with the authoritative active-dispatch list:
   creates missing dispatches, moves each ambulance marker to its own
   live GPS, removes dispatches that are no longer active. */
function syncDispatchMarkers(activeList) {
  if (!map) {
    return;
  }

  const active = activeList || [];
  const visibleIds = new Set();

  for (const dispatch of active) {
    visibleIds.add(dispatch.dispatchId);
    ensureDispatchLayers(dispatch);
    updateDispatchAmbulanceMarker(dispatch);
  }

  for (const dispatchId of Object.keys(dispatchMarkers)) {
    const id = Number(dispatchId);
    if (!visibleIds.has(id)) {
      // completed/removed dispatch — take down exactly its own layers
      map.removeLayer(dispatchMarkers[id].group);
      delete dispatchMarkers[id];
    }
  }
}

/* Creates the victim/hospital markers and both route legs for ONE
   dispatch (once), inside a layer group owned by that dispatch. */
function ensureDispatchLayers(dispatch) {
  const existing = dispatchMarkers[dispatch.dispatchId];
  if (existing) {
    return existing;
  }

  /* OSRM geometry is [longitude, latitude] — swap to Leaflet order. */
  const legOne = (dispatch.ambulanceToVictimRoute || [])
    .map((point) => [point[1], point[0]]);
  const legTwo = (dispatch.victimToHospitalRoute || [])
    .map((point) => [point[1], point[0]]);

  const victimPoint = [dispatch.sourceLatitude, dispatch.sourceLongitude];

  const entry = {
    group: L.layerGroup().addTo(map),
    victimMarker: null,
    hospitalMarker: null,
    ambulanceMarker: null
  };

  if (legOne.length > 0) {
    createGlowRoute(legOne, "#ff6b1a").addTo(entry.group);
  }
  if (legTwo.length > 0) {
    createGlowRoute(legTwo, "#2ee6a8").addTo(entry.group);
  }

  entry.victimMarker = L.marker(victimPoint,
      { icon: createMarkerIcon("victim") })
    .bindPopup("<b>EMERGENCY #" + dispatch.emergencyId + "</b><br>" +
      "Victim position")
    .addTo(entry.group);

  if (legTwo.length > 0) {
    entry.hospitalMarker =
      L.marker(legTwo[legTwo.length - 1],
          { icon: createMarkerIcon("hospital") })
        .bindPopup("<b>HOSPITAL</b><br>" + escapeHtml(dispatch.hospitalName))
        .addTo(entry.group);
  }

  dispatchMarkers[dispatch.dispatchId] = entry;

  /* keep every dispatch in view as new ones arrive */
  fitMapToDispatches();

  return entry;
}

/* Moves ONE dispatch's ambulance marker to that dispatch's own live
   GPS value from the backend. Never touches any other dispatch. */
function updateDispatchAmbulanceMarker(dispatch) {
  const entry = dispatchMarkers[dispatch.dispatchId];
  if (!entry) {
    return;
  }

  /* while the operator's manual demo runs for this dispatch, the demo
     owns its marker; every other dispatch still follows live GPS */
  if (simulationDispatchId === dispatch.dispatchId
      && simulationAnimationFrame) {
    return;
  }

  if (dispatch.ambulanceLatitude != null
      && dispatch.ambulanceLongitude != null) {

    const position = [dispatch.ambulanceLatitude, dispatch.ambulanceLongitude];

    if (!entry.ambulanceMarker) {
      entry.ambulanceMarker =
        L.marker(position, { icon: createMarkerIcon("ambulance") })
          .bindPopup("<b>AMBULANCE</b><br>" +
            escapeHtml(dispatch.vehicleNumber))
          .addTo(entry.group);
    } else {
      entry.ambulanceMarker.setLatLng(position);
    }
  } else if (entry.ambulanceMarker) {
    entry.group.removeLayer(entry.ambulanceMarker);
    entry.ambulanceMarker = null;
  }
}

/* Fits the map around every active dispatch (victims + routes). */
function fitMapToDispatches() {
  if (!map) {
    return;
  }

  const allPoints = [];

  for (const id of Object.keys(dispatchMarkers)) {
    const entry = dispatchMarkers[id];
    if (entry.victimMarker) {
      allPoints.push(entry.victimMarker.getLatLng());
    }
  }

  if (allPoints.length > 0) {
    map.fitBounds(L.latLngBounds(allPoints), { padding: [60, 60] });
  }
}

/* ---------------------------------------------------------------------------
   Ambulance simulation (reports progress to the backend lifecycle)
--------------------------------------------------------------------------- */
function calculateDistanceKm(point1, point2) {
  const R = 6371;

  const lat1 = point1[0] * Math.PI / 180;
  const lat2 = point2[0] * Math.PI / 180;

  const deltaLat = (point2[0] - point1[0]) * Math.PI / 180;
  const deltaLon = (point2[1] - point1[1]) * Math.PI / 180;

  const a =
    Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
    Math.cos(lat1) * Math.cos(lat2) *
    Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);

  const c =
    2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

  return R * c;
}

function prepareSimulationRoute(route) {
  simulationRoute = route.map((point) => [point[0], point[1]]);

  simulationRouteDistances = [];
  simulationTotalDistance = 0;

  if (simulationRoute.length === 0) {
    return;
  }

  simulationRouteDistances.push(0);

  for (let i = 1; i < simulationRoute.length; i++) {
    const segmentDistance =
      calculateDistanceKm(
        simulationRoute[i - 1],
        simulationRoute[i]
      );

    simulationTotalDistance += segmentDistance;

    simulationRouteDistances.push(simulationTotalDistance);
  }
}

function getPositionAlongRoute(distanceKm) {
  if (simulationRoute.length === 0) {
    return null;
  }

  if (distanceKm <= 0) {
    return simulationRoute[0];
  }

  if (distanceKm >= simulationTotalDistance) {
    return simulationRoute[simulationRoute.length - 1];
  }

  for (let i = 1; i < simulationRouteDistances.length; i++) {
    if (simulationRouteDistances[i] >= distanceKm) {
      const previousDistance = simulationRouteDistances[i - 1];
      const currentDistance = simulationRouteDistances[i];

      const segmentLength = currentDistance - previousDistance;

      const segmentProgress =
        (distanceKm - previousDistance) / segmentLength;

      const previousPoint = simulationRoute[i - 1];
      const currentPoint = simulationRoute[i];

      const latitude =
        previousPoint[0] +
        (currentPoint[0] - previousPoint[0]) * segmentProgress;

      const longitude =
        previousPoint[1] +
        (currentPoint[1] - previousPoint[1]) * segmentProgress;

      return [latitude, longitude];
    }
  }

  return simulationRoute[simulationRoute.length - 1];
}

/* Reports a lifecycle status to the backend via the dedicated,
   atomic status endpoint (no full-entity PUT that could clash
   with concurrent GPS updates). */
async function updateDispatchAmbulanceStatus(status) {
  if (!lastDispatch || !lastDispatch.ambulanceId) {
    return;
  }

  try {
    const response = await apiFetch(
      `/ambulance/${lastDispatch.ambulanceId}/status`,
      {
        method: "PUT",
        body: JSON.stringify({ status: status })
      }
    );

    if (response.ok) {
      loadAmbulances();
    }
  } catch (error) {
    console.error("Could not update ambulance status:", error);
  }
}

/* Manual, staff-only demo of the focused dispatch: animates ITS OWN
   marker and reports ITS lifecycle statuses. It is never started
   automatically — the authoritative ambulance movement is always the
   device GPS -> backend -> database -> polling chain. */
function startAmbulanceSimulation(data) {
  if (!data || !data.dispatchId) {
    return;
  }

  const entry = dispatchMarkers[data.dispatchId];
  if (!entry) {
    showToast("Open the Dispatch Map first — the simulation needs " +
      "the dispatch to be visible on it.", "error");
    return;
  }

  /* OSRM geometry is [longitude, latitude] — swap to Leaflet order. */
  const legOne = (data.ambulanceToVictimRoute || [])
    .map((point) => [point[1], point[0]]);
  const legTwo = (data.victimToHospitalRoute || [])
    .map((point) => [point[1], point[0]]);

  if (legOne.length < 2 || legTwo.length < 2) {
    showToast("This dispatch has no complete route to animate.", "error");
    return;
  }

  if (simulationAnimationFrame) {
    cancelAnimationFrame(simulationAnimationFrame);
  }

  /* remember the sim state for the focused dispatch */
  simulationDispatchId = data.dispatchId;
  firstLegRoute = legOne;
  secondLegRoute = legTwo;
  firstLegDuration = data.ambulanceToVictimDurationMin || 0;
  secondLegDuration = data.victimToHospitalDurationMin || 0;

  simulationSpeedMultiplier = 1;

  simulationLeg = 1;

  simulationStartTime = performance.now();

  prepareSimulationRoute(firstLegRoute);

  simulationDurationSeconds = firstLegDuration * 60;

  document
    .querySelectorAll(".speed-btn")
    .forEach((button, index) => {
      button.classList.toggle("active", index === 0);
    });

  updateSimulationUI(0);

  updateDispatchAmbulanceStatus("TO_VICTIM");
  setLifecycleStage("to_victim");

  const status = document.getElementById("simulationStatus");
  if (status) {
    status.textContent = "Demo running — travelling to emergency";
  }

  simulationAnimationFrame =
    requestAnimationFrame(animateAmbulance);
}

function animateAmbulance() {
  /* the dispatch's layers must still exist (map may have been rebuilt
     or the dispatch completed) — otherwise stop the demo cleanly */
  const entry =
    (simulationDispatchId !== null)
      ? dispatchMarkers[simulationDispatchId]
      : null;

  if (!simulationStartTime
      || simulationRoute.length === 0
      || !entry
      || !entry.ambulanceMarker) {
    simulationAnimationFrame = null;
    return;
  }

  const marker = entry.ambulanceMarker;

  const now = performance.now();

  const elapsedSeconds = (now - simulationStartTime) / 1000;

  const simulatedSeconds = elapsedSeconds * simulationSpeedMultiplier;

  let progress =
    simulationDurationSeconds > 0
      ? simulatedSeconds / simulationDurationSeconds
      : 1;

  if (progress > 1) {
    progress = 1;
  }

  const distanceTravelled = simulationTotalDistance * progress;

  const position = getPositionAlongRoute(distanceTravelled);

  if (position) {
    marker.setLatLng(position);
  }

  updateSimulationUI(progress);

  if (progress >= 1) {
    if (simulationLeg === 1) {
      simulationLeg = 2;

      marker.setLatLng(secondLegRoute[0]);

      updateDispatchAmbulanceStatus("AT_VICTIM");
      setLifecycleStage("victim_reached");

      const status = document.getElementById("simulationStatus");
      if (status) {
        status.textContent = "Demo — ambulance reached emergency";
      }

      prepareSimulationRoute(secondLegRoute);

      simulationDurationSeconds = secondLegDuration * 60;

      simulationStartTime = performance.now();

      updateSimulationUI(0);

      setTimeout(() => {
        updateDispatchAmbulanceStatus("TO_HOSPITAL");
        setLifecycleStage("to_hospital");

        const statusText = document.getElementById("simulationStatus");
        if (statusText) {
          statusText.textContent = "Demo — travelling to hospital";
        }

        simulationAnimationFrame =
          requestAnimationFrame(animateAmbulance);
      }, 1000);

      return;
    }

    /* Final step: reporting AVAILABLE lets the backend complete the
       dispatch and automatically dispatch any QUEUED emergencies. */
    updateDispatchAmbulanceStatus("AVAILABLE");
    setLifecycleStage("available");

    const doneText = document.getElementById("simulationStatus");
    if (doneText) {
      doneText.textContent =
        "Demo finished — emergency completed, ambulance available";
    }

    const speedText = document.getElementById("simulationSpeed");
    const distanceText = document.getElementById("simulationDistance");
    const etaText = document.getElementById("simulationEta");
    const progressText = document.getElementById("simulationProgress");
    const progressBar = document.getElementById("simulationProgressBar");

    if (speedText) speedText.textContent = "0 km/h";
    if (distanceText) distanceText.textContent = "0 km";
    if (etaText) etaText.textContent = "Arrived";
    if (progressText) progressText.textContent = "100%";
    if (progressBar) progressBar.style.width = "100%";

    simulationAnimationFrame = null;
    simulationDispatchId = null;

    return;
  }

  simulationAnimationFrame =
    requestAnimationFrame(animateAmbulance);
}

function updateSimulationUI(progress) {
  const speedText = document.getElementById("simulationSpeed");
  if (!speedText) {
    return;   // panel not rendered — nothing to update
  }

  const distanceTravelled = simulationTotalDistance * progress;

  const distanceRemaining =
    Math.max(0, simulationTotalDistance - distanceTravelled);

  const averageSpeed =
    simulationDurationSeconds > 0
      ? simulationTotalDistance / (simulationDurationSeconds / 3600)
      : 0;

  const displayedSpeed = averageSpeed * simulationSpeedMultiplier;

  const remainingSeconds =
    Math.max(
      0,
      (simulationDurationSeconds * (1 - progress)) /
      simulationSpeedMultiplier
    );

  const remainingMinutes = remainingSeconds / 60;

  speedText.textContent = displayedSpeed.toFixed(1) + " km/h";

  document.getElementById("simulationDistance").textContent =
    distanceRemaining.toFixed(2) + " km";

  document.getElementById("simulationEta").textContent =
    remainingMinutes < 1
      ? Math.ceil(remainingSeconds) + " sec"
      : remainingMinutes.toFixed(1) + " min";

  document.getElementById("simulationProgress").textContent =
    Math.round(progress * 100) + "%";

  document.getElementById("simulationProgressBar").style.width =
    (progress * 100) + "%";
}

/* Button handler: the operator explicitly starts the demo for the
   currently focused dispatch. Never runs on its own. */
function startManualSimulation() {
  if (!lastDispatch) {
    showToast("No dispatch to simulate yet.", "error");
    return;
  }

  startAmbulanceSimulation(lastDispatch);
}

function setSimulationSpeed(multiplier, button) {
  if (!simulationStartTime) {
    return;
  }

  const now = performance.now();

  const elapsedSeconds = (now - simulationStartTime) / 1000;

  const currentSimulatedSeconds =
    elapsedSeconds * simulationSpeedMultiplier;

  const currentProgress =
    Math.min(1, currentSimulatedSeconds / simulationDurationSeconds);

  simulationSpeedMultiplier = multiplier;

  simulationStartTime =
    now -
    (currentProgress * simulationDurationSeconds /
      simulationSpeedMultiplier) * 1000;

  document
    .querySelectorAll(".speed-btn")
    .forEach((btn) => {
      btn.classList.remove("active");
    });

  button.classList.add("active");
}

/* ---------------------------------------------------------------------------
   Live dashboard polling (GET /route/latest — persisted dispatch IDs)
--------------------------------------------------------------------------- */
function updateLiveEmergencyStatus(message) {
  const element = document.getElementById("liveEmergencyStatus");

  if (element) {
    element.textContent = message;
  }
}

/* Queue depth gets its own element so it can never overwrite the
   "new emergency" line — and it is cleared when the queue drains. */
function renderPendingQueue(pending) {
  const banner = document.getElementById("queueBanner");

  if (!banner || !pending) {
    return;
  }

  if (pending.length > 0) {
    const critical = pending.filter(
      (e) => (e.severity || "").toUpperCase() === "CRITICAL"
    ).length;
    const high = pending.filter(
      (e) => (e.severity || "").toUpperCase() === "HIGH"
    ).length;

    banner.textContent =
      pending.length + " emergency" +
      (pending.length > 1 ? "ies" : "y") + " QUEUED" +
      (critical > 0 ? " (" + critical + " CRITICAL)" : "") +
      (high > 0 ? " (" + high + " HIGH)" : "") +
      " — waiting for a free ambulance.";

    banner.classList.remove("hidden");
  } else {
    banner.classList.add("hidden");
  }
}

/* Operational lists: active dispatches + queued emergencies, always
   rebuilt straight from the database snapshot. */
function renderOperations(snapshot) {
  const dispatchBody =
    document.getElementById("operationsDispatchTable");
  const pendingBody =
    document.getElementById("operationsPendingTable");

  if (dispatchBody) {
    const dispatches = snapshot.activeDispatches || [];

    if (dispatches.length === 0) {
      dispatchBody.innerHTML =
        '<tr><td colspan="8">No active dispatches right now.</td></tr>';
    } else {
      dispatchBody.innerHTML = dispatches.map((d) => `
        <tr>
          <td class="mono">#${d.emergencyId ?? "—"}</td>
          <td><span class="badge ${
            (d.severity || "NORMAL") === "CRITICAL"
              ? "badge-busy"
              : (d.severity === "HIGH" ? "badge-warning" : "badge-available")
          }">${escapeHtml(d.severity || "NORMAL")}</span></td>
          <td><strong>${escapeHtml(d.vehicleNumber)}</strong></td>
          <td>
            <span class="${statusBadgeClass(d.ambulanceStatus)}">
              ${(d.ambulanceStatus || "").toString().toUpperCase()}
            </span>
          </td>
          <td>${escapeHtml(d.hospitalName || "—")}</td>
          <td>${d.ambulanceToVictimDurationMin ?? "—"} min</td>
          <td>${d.victimToHospitalDurationMin ?? "—"} min</td>
          <td>${escapeHtml((d.trafficLevel || "—").toString().toUpperCase())}</td>
        </tr>
      `).join("");
    }
  }

  if (pendingBody) {
    const pending = snapshot.pendingEmergencies || [];

    if (pending.length === 0) {
      pendingBody.innerHTML =
        '<tr><td colspan="3">No queued emergencies.</td></tr>';
    } else {
      pendingBody.innerHTML = pending.map((e) => `
        <tr>
          <td class="mono">#${e.emergencyId ?? "—"}</td>
          <td><span class="badge ${
            (e.severity || "NORMAL") === "CRITICAL"
              ? "badge-busy"
              : (e.severity === "HIGH" ? "badge-warning" : "badge-available")
          }">${escapeHtml(e.severity || "NORMAL")}</span></td>
          <td><span class="badge badge-warning">QUEUED</span></td>
        </tr>
      `).join("");
    }
  }
}

async function checkForLatestEmergency() {
  try {
    const response =
      await apiFetch("/route/latest", { cache: "no-store" });

    if (!response.ok) {
      return;
    }

    const snapshot = await response.json();

    activeEmergencyCount =
      snapshot.activeDispatches
        ? snapshot.activeDispatches.length
        : 0;

    updateFleetStats();
    renderOperations(snapshot);
    renderPendingQueue(snapshot.pendingEmergencies);

    /* the authoritative multi-dispatch list: every active dispatch
       syncs its OWN markers from its OWN live GPS on every poll */
    const previousActiveIds =
        new Set(lastActiveList.map((d) => d.dispatchId));

    lastActiveList = snapshot.activeDispatches || [];
    syncDispatchMarkers(lastActiveList);

    const dispatch = snapshot.dispatch;

    if (!dispatch) {
      return;
    }

    /* focused dispatch unchanged — markers were already synced above */
    if (dispatch.dispatchId === latestDispatchId) {
      return;
    }

    /* First load after a refresh -> RESTORE the active dispatch from
       the database (no toast, no "new emergency" message). */
    const isRestore = latestDispatchId === null;

    /* a dispatch that was ALREADY active just became the focused one
       (e.g. another dispatch completed) — not a new emergency */
    const isFocusShift =
        !isRestore && previousActiveIds.has(dispatch.dispatchId);

    latestDispatchId = dispatch.dispatchId;
    lastDispatch = dispatch;

    document.getElementById("routeResponse").textContent =
      JSON.stringify(dispatch, null, 2);

    renderDispatchSummary(dispatch);
    setLifecycleStage("assigned");

    if (isRestore) {
      if (dispatch.dispatchStatus === "ACTIVE") {
        updateLiveEmergencyStatus(
          "Active dispatch restored — emergency #" + dispatch.emergencyId +
          " (" + (dispatch.vehicleNumber || "ambulance") + ")."
        );
        showTab("dispatchMap",
          document.querySelector('[data-tab="dispatchMap"]'));
      }
      return;
    }

    if (isFocusShift) {
      updateLiveEmergencyStatus(
        "Focus moved to emergency #" + dispatch.emergencyId +
        " (" + (dispatch.vehicleNumber || "ambulance") + ")."
      );
      return;
    }

    /* a genuinely NEW emergency arrived from the victim page */
    updateLiveEmergencyStatus(
      "NEW EMERGENCY #" + dispatch.emergencyId + ": " +
      (dispatch.vehicleNumber || "Ambulance") + " assigned. " +
      "Opening dispatch map..."
    );

    showToast("New emergency received — #" + dispatch.emergencyId, "error");

    showTab("dispatchMap",
      document.querySelector('[data-tab="dispatchMap"]'));

  } catch (error) {
    console.error("Could not check for new emergency:", error.message);
  }
}

function startDispatchPolling() {
  if (dispatchPollingInterval !== null) {
    clearInterval(dispatchPollingInterval);
  }

  checkForLatestEmergency();

  dispatchPollingInterval =
    setInterval(checkForLatestEmergency, 2000);
}

/* ---------------------------------------------------------------------------
   Boot
--------------------------------------------------------------------------- */

/* Ends the staff session (CSRF-protected POST), then returns to the
   login page. */
async function logout() {
  try {
    await apiFetch("/logout", { method: "POST" });
  } catch (error) {
    /* leaving anyway */
  }
  window.location.href = "/index.html";
}

gpsAmbulanceId = loadStoredGpsAmbulanceId();

loadAmbulances();
loadHospitals();
startDispatchPolling();

updateClock();
setInterval(updateClock, 1000);

/* GPS streaming needs a chosen ambulance; start it as soon as the
   fleet list has loaded (refreshAmbulancePicker sets the default). */
setTimeout(() => {
  if (gpsAmbulanceId) {
    startAutomaticGPS();
  }
}, 1500);

/* Picker events */
document.addEventListener("DOMContentLoaded", () => {
  const select = document.getElementById("gpsAmbulanceSelect");
  if (select) {
    select.addEventListener("change", onGpsAmbulanceChange);
  }
});
