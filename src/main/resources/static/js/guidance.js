/* ============================================================================
   ResQRoute — WHILE-YOU-WAIT guidance (guidance.js)
   ----------------------------------------------------------------------------
   Controlled, predefined first-aid guidance only:

     - fixed categories, fixed texts — no free-form "AI diagnosis"
     - action-oriented, concise, never a diagnosis or a certainty
     - always defers to emergency dispatchers / medical professionals
     - purely a client-side information panel: it can never delay or
       affect dispatch or tracking in any way

   Used by victim.html (after a dispatch succeeds) and track.html.
   ============================================================================ */

/* Reusable by both pages — no module system, plain globals. */

const RESQ_GUIDANCE_DISCLAIMER =
  "General first-aid information only — not a medical diagnosis. " +
  "Always follow the instructions of the emergency dispatcher or the " +
  "medical professionals on scene. ResQRoute does not replace " +
  "professional emergency care.";

const RESQ_GUIDANCE_CATEGORIES = [
  {
    id: "unresponsive",
    label: "Unresponsive person",
    steps: [
      "Gently shake the shoulders and shout — check whether they respond.",
      "If there is no response and no normal breathing, ask someone to " +
      "stay on the line with the emergency dispatcher, and start chest " +
      "compressions if you are trained.",
      "Do not move them unless they are in immediate danger.",
      "Unlock the main door so the ambulance crew can reach you quickly."
    ]
  },
  {
    id: "cardiac",
    label: "CPR / cardiac emergency",
    steps: [
      "If the person is not breathing normally, start chest compressions: " +
      "push hard and fast in the centre of the chest, about 100–120 " +
      "presses per minute, if you are trained.",
      "Send someone for an AED (automated external defibrillator) if one " +
      "is nearby, and use it as soon as it arrives.",
      "Do not pause compressions for longer than necessary.",
      "Follow the dispatcher's instructions — they will guide you step by step."
    ]
  },
  {
    id: "bleeding",
    label: "Severe bleeding",
    steps: [
      "Press firmly on the wound with a clean cloth or your hand and keep " +
      "pressing.",
      "Do not remove soaked dressings — add more material on top.",
      "Help the person lie down and keep them warm if possible.",
      "Do not give them anything to eat or drink."
    ]
  },
  {
    id: "choking",
    label: "Choking",
    steps: [
      "Encourage them to cough if they can still cough, speak or breathe.",
      "If they cannot breathe or speak: give up to 5 firm blows between " +
      "the shoulder blades, then up to 5 abdominal thrusts if you are " +
      "trained. Alternate until help arrives.",
      "Call for help immediately and keep trying to clear the blockage."
    ]
  },
  {
    id: "burns",
    label: "Burns",
    steps: [
      "Cool the burn under gently running water for 10–20 minutes.",
      "Remove rings, watches and tight clothing near the burn if you can " +
      "do it easily.",
      "Cover the burn loosely with clean, non-fluffy cloth or cling film.",
      "Do not use ice, butter, toothpaste or ointments."
    ]
  },
  {
    id: "seizure",
    label: "Seizure",
    steps: [
      "Protect them from injury: clear hard objects away and cushion the head.",
      "Do not restrain them and never put anything in their mouth.",
      "Note the time the seizure started and stay with them.",
      "Once the shaking stops, if they are breathing, roll them onto their " +
      "side and keep watching them."
    ]
  },
  {
    id: "chest-pain",
    label: "Chest-pain emergency",
    steps: [
      "Help them sit or half-recline in the most comfortable position and " +
      "loosen tight clothing.",
      "Keep them still — do not let them walk or exert themselves.",
      "Do not give any medication unless a medical professional instructs you.",
      "If they become unresponsive and stop breathing normally, be ready " +
      "to start chest compressions if you are trained."
    ]
  },
  {
    id: "general",
    label: "General emergency",
    steps: [
      "Stay calm and stay with the person.",
      "Clear the way for the ambulance and unlock the main door.",
      "Keep a phone reachable and this page open.",
      "Collect the person's medications and medical documents if you can.",
      "Do not give food or drink unless a professional advises it."
    ]
  },
  {
    id: "unsure",
    label: "I'm not sure what is happening",
    steps: [
      "That's okay — the ambulance is already on its way.",
      "Stay with the person and watch whether they respond and breathe normally.",
      "If they stop responding or stop breathing normally, tell the " +
      "dispatcher immediately.",
      "Clear the way for the ambulance and unlock the main door."
    ]
  }
];

function resqGuidanceCategoryById(id) {
  return RESQ_GUIDANCE_CATEGORIES.find(
    (category) => category.id === id
  ) || null;
}

/* Renders the category chips into a container. */
function resqRenderGuidanceChips(containerId, onPick) {
  const container = document.getElementById(containerId);
  if (!container) {
    return;
  }

  container.innerHTML = RESQ_GUIDANCE_CATEGORIES.map(
    (category) => `
      <button type="button"
              class="guidance-chip"
              data-guidance-id="${category.id}">
        ${category.label}
      </button>
    `
  ).join("");

  container.querySelectorAll("[data-guidance-id]").forEach((chip) => {
    chip.addEventListener("click", () => {
      container.querySelectorAll(".guidance-chip").forEach((other) => {
        other.classList.remove("active");
      });
      chip.classList.add("active");
      onPick(chip.dataset.guidanceId);
    });
  });
}

/* Renders the controlled guidance text for one category. */
function resqRenderGuidanceText(outputId, categoryId) {
  const output = document.getElementById(outputId);
  const category = resqGuidanceCategoryById(categoryId);

  if (!output || !category) {
    return;
  }

  output.innerHTML = `
    <div class="guidance-card">
      <div class="guidance-title">${category.label}</div>
      <ul class="guidance-steps">
        ${category.steps.map((step) => `<li>${step}</li>`).join("")}
      </ul>
      <div class="guidance-disclaimer">${RESQ_GUIDANCE_DISCLAIMER}</div>
    </div>
  `;

  output.classList.remove("hidden");
  output.scrollIntoView({ behavior: "smooth", block: "nearest" });
}
