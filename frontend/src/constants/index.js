// Shared app-wide constants. Kept in one place so values duplicated across components (the 20MB
// attachment limit was declared identically in the tickets page and PreCheckPanel) can't drift
// out of sync. Values are unchanged from their previous inline definitions.

// Max size for an evidence/attachment upload, in megabytes. THREE limits must agree or the largest
// is a fiction:
//   1. this constant
//   2. spring.servlet.multipart.max-file-size in application.properties
//   3. client_max_body_size on whatever proxy terminates TLS and routes /api
// Raise one without the others and uploads fail at whichever is smallest, with an error that gets
// less useful the further out the rejection happens.
//
// 25MB, not the 1GB this was briefly set to: evidence in practice is screenshots (1-5MB), and 1GB
// meant a limit nothing enforced end-to-end plus real OutOfMemoryError exposure under concurrency.
// 25MB is roughly 5x the largest screenshot anyone attaches and still small enough that the proxy
// and Tomcat settings around it stay conservative.
//
// Checked client-side purely so the user finds out in a second instead of after pushing the file up
// a slow link only to be rejected. It is not a security control -- the backend limit is.
export const MAX_EVIDENCE_FILE_SIZE_MB = 25;

// Human-readable form of the limit above. 1024MB is technically accurate and reads badly in a
// sentence like "larger than the 1024MB attachment limit", so the UI says "1GB".
export const MAX_EVIDENCE_FILE_SIZE_LABEL =
  MAX_EVIDENCE_FILE_SIZE_MB >= 1024
    ? `${MAX_EVIDENCE_FILE_SIZE_MB / 1024}GB`
    : `${MAX_EVIDENCE_FILE_SIZE_MB}MB`;

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

// Colour by lifecycle phase — each maps to a dedicated muted badge class in index.css.
//   amber   waiting on approval
//   sky     approved, ready to start
//   indigo  migration started
//   emerald this cycle finished (Finish clicked)
//   violet  Final Delta complete
export const DELTA_PHASE_BADGE_COLOR = {
  IN_APPROVAL: "delta-in-approval",
  READY: "delta-ready",
  STARTED: "delta-started",
  FINISHED: "delta-done",
  COMPLETE: "delta-complete",
};

// Where a recorded cycle sits in its own post-approval life. Mirrors DeltaCycleStatus.
export const DELTA_CYCLE_STATUS_BADGE = {
  APPROVED: { color: "yellow", label: "Approved — not started" },
  RUNNING: { color: "blue", label: "Running" },
  COMPLETED: { color: "green", label: "Completed" },
  DECLINED: { color: "red", label: "Declined" },
};
