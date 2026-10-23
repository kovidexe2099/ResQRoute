/* ============================================================================
   ResQRoute — Victim Emergency Page logic (victim.js)
   ----------------------------------------------------------------------------
   Flow (the victim never types coordinates and never sees one):

     1. Victim presses REQUEST AMBULANCE (the ONLY action on the page —
        no registration, no login, no OTP).
     2. Only NOW the browser asks for GPS permission
        (navigator.geolocation.getCurrentPosition).
     3. POST /route/best-ambulance  { sourceLatitude, sourceLongitude,
                                      severity }        (public API)
     4. 200 -> dispatch exists: show ambulance / ETA / traffic /
        hospital + the private tracking link (token).
        409 -> no free ambulance right now: the request is queued on
        the backend and will dispatch automatically.

   Raw coordinates and internal ids are never displayed. The tracking
   link is authorized by the dispatch's random token — never by an id.
   ============================================================================ */

/* Same-origin API (the page itself is served by ResQRoute). */
const API_BASE = "";

/* Severity toggle state (NORMAL default) */
let selectedSeverity = "NORMAL";

function setSeverity(severity) {
  selectedSeverity = severity;

  document
    .querySelectorAll(".severity-option")
    .forEach((option) => {
      const active = option.dataset.severity === severity;
      option.classList.toggle("active", active);
    });
}

/* Small HTML-escape for every backend string rendered into the page.
   Entities are built programmatically (charcode 38 = "&") so the
   source contains no literal entity sequences. */
function escapeHtml(value) {
  const AMP = String.fromCharCode(38);
  return String(value == null ? "" : value)
    .replaceAll(AMP, AMP + "amp;")
    .replaceAll("<", AMP + "lt;")
    .replaceAll(">", AMP + "gt;")
    .replaceAll("\"", AMP + "quot;")
    .replaceAll("'", AMP + "#39;");
}

/* Update the 3-step progress indicator (visual only). */
function setVictimStep(step) {
  const flow = document.getElementById("victimFlow");

  if (!flow) {
    return;
  }

  const order = ["locating", "matching", "done"];
  const currentIndex = order.indexOf(step);
  const isFinalStep = step === "done";

  flow.classList.toggle("show", true);

  flow.querySelectorAll(".flow-step").forEach((element, index) => {
    element.classList.toggle("active", !isFinalStep && index === currentIndex);
    element.classList.toggle(
      "done",
      isFinalStep ? index <= currentIndex : index < currentIndex
    );
  });
}

async function requestAmbulance() {
  const button = document.getElementById("requestButton");
  const status = document.getElementById("status");
  const retryButton = document.getElementById("retryButton");

  /* one emergency per tap — the button stays disabled until the
     request finishes (success or error) */
  button.disabled = true;
  retryButton.classList.add("hidden");

  status.className = "status hidden";
  status.textContent = "";

  setVictimStep("locating");

  if (!navigator.geolocation) {
    status.className = "status error";
    status.textContent =
      "Location services are not supported on this device.";
    button.disabled = false;
    return;
  }

  navigator.geolocation.getCurrentPosition(

    async function (position) {
      const latitude = position.coords.latitude;
      const longitude = position.coords.longitude;

      setVictimStep("matching");

      try {
        const response = await fetch(
          `${API_BASE}/route/best-ambulance`,
          {
            method: "POST",
            headers: {
              "Content-Type": "application/json"
            },
            body: JSON.stringify({
              sourceLatitude: latitude,
              sourceLongitude: longitude,
              severity: selectedSeverity
            })
          }
        );

        /* 409 = accepted but queued (no free ambulance right now) */
        if (response.status === 409) {
          setVictimStep("done");

          status.className = "status error";
          status.innerHTML =
            "<strong>NO AMBULANCE FREE RIGHT NOW</strong><br><br>" +
            "Your request has been received and you are in the queue " +
            "for the next free ambulance. It will be dispatched " +
            "automatically the moment one becomes available. " +
            "Please stay safe.";

          retryButton.classList.remove("hidden");
          button.disabled = false;
          return;
        }

        if (!response.ok) {
          status.className = "status error";
          status.textContent =
            "Could not request an ambulance right now. Please try again.";

          retryButton.classList.remove("hidden");
          button.disabled = false;
          return;
        }

        const data = await response.json();

        setVictimStep("done");

        status.className = "status success";

        /* Success card: ambulance, ETA, traffic, destination hospital.
           Coordinates and internal ids are intentionally NOT shown. */
        status.innerHTML = `
          <strong>AMBULANCE DISPATCHED</strong><br><br>
          <div class="result-chips">
            <div class="result-chip">
              <div class="card-label">Ambulance</div>
              <div class="card-value">${escapeHtml(data.vehicleNumber) || "Assigned"}</div>
            </div>
            <div class="result-chip">
              <div class="card-label">Status</div>
              <div class="card-value">EN&nbsp;ROUTE</div>
            </div>
            <div class="result-chip">
              <div class="card-label">ETA to you</div>
              <div class="card-value">
                ${data.ambulanceToVictimDurationMin ?? "-"} min
              </div>
            </div>
            <div class="result-chip">
              <div class="card-label">Distance</div>
              <div class="card-value">
                ${data.ambulanceToVictimDistanceKm ?? "-"} km
              </div>
            </div>
            <div class="result-chip">
              <div class="card-label">Traffic</div>
              <div class="card-value">
                ${(data.trafficLevel || "LOW").toString().toUpperCase()}
              </div>
            </div>
            <div class="result-chip wide">
              <div class="card-label">Destination hospital</div>
              <div class="card-value">${escapeHtml(data.hospitalName) || "Nearest hospital"}</div>
            </div>
          </div>
          <br>
          Help is on the way. Please stay safe and keep your phone reachable.
        `;

        /* Private tracking link — authorized by the dispatch's random
           tracking token, never by an id. */
        if (data.trackingToken) {
          const trackButton = document.getElementById("trackButton");
          trackButton.href =
            "track.html?token=" + encodeURIComponent(data.trackingToken);
          trackButton.classList.remove("hidden");
        }

        /* The dispatch already exists — optional guidance can start.
           It never affects the dispatch in any way. */
        const guidanceSection =
          document.getElementById("guidanceSection");
        if (guidanceSection) {
          guidanceSection.classList.remove("hidden");
        }

        /* duplicate protection: this page created its emergency — the
           button is hidden so a second tap cannot create another one
           (reload the page for a deliberate new request) */
        button.classList.add("hidden");

      } catch (error) {
        status.className = "status error";
        status.textContent =
          "Could not connect to ResQRoute. Please try again.";

        retryButton.classList.remove("hidden");
        button.disabled = false;
      }
    },

    function (error) {
      status.className = "status error";

      if (error.code === 1) {
        status.textContent =
          "Location permission is required to request an ambulance. " +
          "Please allow location access and try again.";
      } else if (error.code === 2) {
        status.textContent =
          "Could not determine your location. Please try again.";
      } else if (error.code === 3) {
        status.textContent =
          "Location request timed out. Please try again.";
      } else {
        status.textContent =
          "Could not get your location. Please try again.";
      }

      retryButton.classList.remove("hidden");
      button.disabled = false;
    },

    {
      enableHighAccuracy: true,
      timeout: 10000,
      maximumAge: 0
    }
  );
}
