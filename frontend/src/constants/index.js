// Shared app-wide constants. Kept in one place so values duplicated across components (the 20MB
// attachment limit was declared identically in the tickets page and PreCheckPanel) can't drift
// out of sync. Values are unchanged from their previous inline definitions.

// Max size for an evidence/attachment upload, in megabytes. Mirrors the backend multipart cap
// (spring.servlet.multipart.max-file-size=20MB).
export const MAX_EVIDENCE_FILE_SIZE_MB = 20;

// How often the NavBar re-polls the open-ticket count badge, in milliseconds.
export const TICKET_POLL_MS = 30000;

// The "Delta Type" pre-check item's name, and the item that only applies when it's set to Pre delta.
// Mirror ServerService.DELTA_TYPE_ITEM / PRE_DELTA_MIGRATION_ITEM on the backend -- these strings are
// the contract between the two, so they must match exactly.
export const DELTA_TYPE_ITEM = "Delta Type";

// Message-only checklist item, a yes/no capability question. Mirrors
// ServerService.DELTA_MESSAGE_SYNC_ITEM -- the name is the matching key, so the two must stay exact.
export const DELTA_MESSAGE_SYNC_ITEM = "Delta Message Sync";
export const PRE_DELTA_MIGRATION_ITEM = "Previous Delta Migration";

// Renamed from "Pre Delta Migration" on 2026-08-06. The name IS the matching key and it's persisted
// per row, so checklists seeded before the rename still carry the old string -- match both or the
// item stops being conditionally hidden on existing combinations. Mirrors
// ServerService.isPreDeltaMigrationItem on the backend; keep the two in step.
const LEGACY_PRE_DELTA_MIGRATION_ITEM = "Pre Delta Migration";

export const isPreDeltaMigrationItem = (itemName) =>
  itemName === PRE_DELTA_MIGRATION_ITEM || itemName === LEGACY_PRE_DELTA_MIGRATION_ITEM;

// Badge colors for a Delta cycle's type. Final delta is the irreversible one that ends the
// combination and makes its server decommissionable, so it reads as a heavier action (purple) than
// the routine repeating pre-deltas (blue).
export const DELTA_TYPE_BADGE = {
  PRE_DELTA: { color: "blue", label: "Pre-Delta" },
  FINAL_DELTA: { color: "purple", label: "Final Delta" },
};

// Colour by PHASE, not just delta type. Keying on type alone painted every pre-delta the same blue
// whether it was awaiting approval, running or finished -- so a row of combinations at completely
// different stages all read as one state, next to a product-type badge that was also blue.
//
// Four stops, coarse on purpose, since the badge text already names the exact phase:
//   yellow  waiting on a person
//   blue    cleared and in motion
//   green   this cycle is done
//   purple  the Final Delta completed -- the irreversible milestone the token is reserved for
export const DELTA_PHASE_BADGE_COLOR = {
  IN_APPROVAL: "yellow",
  READY: "blue",
  STARTED: "blue",
  FINISHED: "green",
  COMPLETE: "purple",
};

// Where a recorded cycle sits in its own post-approval life. Mirrors DeltaCycleStatus.
export const DELTA_CYCLE_STATUS_BADGE = {
  APPROVED: { color: "yellow", label: "Approved — not started" },
  RUNNING: { color: "blue", label: "Running" },
  COMPLETED: { color: "green", label: "Completed" },
};
