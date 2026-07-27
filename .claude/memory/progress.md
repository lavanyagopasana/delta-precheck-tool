# Progress

## Recently completed (this session, 2026-07-27)

- Fixed Dashboard/Projects approval Done/Pending counts to reflect actual `SignOffStatus`, not row
  existence (see `.claude/memory/decisions.md`).
- Renamed and redefined the Dashboard's "Total Requests" card to "Total Approval Requests."
- Fixed the Approvals page's misleading "Delta Ready"/"Not Ready" readiness label (now "Pairs
  Synced"/etc.), then simplified that column to just server name + pair count.
- Added CSV bulk-import for the admin user allowlist (`AppUserService.importCsv`,
  `POST /api/admin/users/import-csv`, a modal on `AdminUsersPage`).
- Redesigned `EngineerChecklist` from a scrollable checkbox list to a search/chip combobox.
- Scaffolded this entire `.claude/` structure, `CLAUDE.md`, `AGENTS.md`, `.mcp.json`.

## In-flight / not fully resolved

- **`README.md` is still stale** — the discrepancies documented in
  `.claude/memory/domain-knowledge.md` weren't fixed in the README itself as part of this session,
  only recorded. Updating the README to match the current server-level pre-check/sign-off model
  (and removing the fictional auto-escalation description) is a real, currently-unclaimed task.
- **`APPROVAL_SEQUENCE` and `normalizeHeader` remain duplicated** (`SignOffService`/`ProjectService`
  and `AppUserService`/`WorkspacePairService` respectively). Noted as known debt, not yet extracted
  to a shared location — see `.claude/rules/architecture-boundaries.md`.
- **`.gitignore`'s `backend/application.properties` entry doesn't match the real file path**
  (`backend/src/main/resources/application.properties`) — flagged, not yet fixed. Low urgency only
  because the file currently holds no real secrets, just env-var-overridable defaults.

## Known gaps (not bugs, just absent)

- **No automated tests anywhere.** See `.claude/rules/testing-standard.md` for the target standard
  and priority order for the first ones.
- **Not a git repository yet** — no version history, no PR review process, no CI/CD. Once `git
  init` happens, `.claude/rules/pr-standard.md` describes the target PR discipline.
- **No production deploy process exists** — hence no `deploy.md` command (see
  `.claude/memory/decisions.md`); rely on gstack's `/land-and-deploy` once a real target exists, or
  create a project-specific deploy command once there's real logic to codify.
- **CORS is hardcoded to `localhost:3000` only** — will need a real allowed-origins list before any
  non-local frontend can talk to a deployed backend.
- **`Delta-Migration-Workflow-Flowchart.png` / `Delta-Migration-Workflow-and-Access.pdf`** weren't
  cross-checked against current code as part of this scaffold — unknown whether they're current.

## Suggested next steps (not commitments — surface these, don't assume they're wanted)

1. Reconcile `README.md` with `.claude/memory/domain-knowledge.md`'s discrepancy list.
2. Write the first backend tests for `SignOffService` and `ProjectService.buildSummary` (the
   latter as a regression test for the Done/Pending bug specifically).
3. Consider extracting `APPROVAL_SEQUENCE` to a single shared location.
4. Decide on an intended production auth posture (`azure.require-allowlist`,
   `azure.allowed-email-domain`) before this goes anywhere beyond local/testing use.
