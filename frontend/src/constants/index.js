// Shared app-wide constants. Kept in one place so values duplicated across components (the 20MB
// attachment limit was declared identically in the tickets page and PreCheckPanel) can't drift
// out of sync. Values are unchanged from their previous inline definitions.

// Max size for an evidence/attachment upload, in megabytes. Mirrors the backend multipart cap
// (spring.servlet.multipart.max-file-size=20MB).
export const MAX_EVIDENCE_FILE_SIZE_MB = 20;

// How often the NavBar re-polls the open-ticket count badge, in milliseconds.
export const TICKET_POLL_MS = 30000;
