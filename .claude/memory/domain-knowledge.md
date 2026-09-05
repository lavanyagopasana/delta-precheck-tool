# Domain Knowledge

The real business rules, verified directly against entities and services as of this scaffold.
Where this contradicts `README.md`, **trust this file and the code** — the discrepancies are listed
explicitly below so nobody has to re-discover them.

## Core entities and their real shape

- **`Project`** — `name` (globally unique, case-insensitive), `productType` (`MESSAGE`/`EMAIL`/
  `CONTENT`), `migrationManagerName` (a single email, not a list — despite `migrationManagers`
  appearing as a list in `ProjectSummaryDto`, that's just `[migrationManagerName]` wrapped, see
  `ProjectService.buildSummary`), `engineerEmails` (team members), `createdBy`.
- **`Server`** — belongs to a `Project`; `status` (`PairStatus`: `PENDING`/`IN_PROGRESS`/
  `DELTA_READY` — this reflects workspace-pair sync state, see below); `totalPairCount`;
  `deltaInitiatedAt`/`deltaInitiatedBy` (stamped once the full sign-off chain resolves).
- **`WorkspacePair`** — belongs to a `Server`; `sourceEmail`/`sourcePath`, `destinationEmail`/
  `destinationPath`, `combination` (a free-text migration-type label, e.g. "Google Drive ->
  OneDrive"). **Pure data — no status, no pre-check, no sign-off of its own.** All of that lives at
  the `Server` level, not per pair.
- **`PreCheckItem`** — belongs to a `Server` (not a pair); `itemName`, `status` (`ItemStatus`),
  `notes`, one evidence file. One flat, server-wide checklist — not split into categories.
- **`PreCheckSubmission`** — **exactly one per `Server`** (`unique(server_id)`), `status`
  (`NOT_STARTED`→`DRAFT`→`SUBMITTED`), `startedByEmail` (who first touched it — informational only
  as of 2026-09-04, no longer a lock; any eligible person can view a half-filled form and fill in
  what's left — see the note under "The real pre-check submission rule"), `submittedBy`/
  `submittedAt`.
- **`SignOff`** — belongs to a `Server` + `role` (`MIGRATION_LEAD`/`DEV_LEAD`/`QA_LEAD`, unique per
  server+role pair); `status` (`PENDING`/`APPROVED`/`DECLINED`/`SKIPPED`). All three rows for a
  server are created together (`SignOffService.createChainIfAbsent`) the moment its pre-check is
  submitted — **a row existing does not mean that role has decided anything**, see below.
- **`Escalation`** — belongs to a `Server`; `ticketNumber` (globally unique), `description`,
  `reason`, `status` (`OPEN`/`RESOLVED`), `priority`, `resolutionNotes`, optional evidence.
  **Created manually via the API** (`EscalationCreateRequest`) — there is no automatic escalation
  logic anywhere in `EscalationService` today.
- **`AppUser`** — `email` (unique, case-insensitive), `role` (`AppUserRole`), `addedBy`, `addedAt`.
  Independent of the `Project`/`Server` domain — purely an access-control record.

## The real pre-check submission rule

`PreCheckSubmissionService.submit` requires, for the server's **one** pre-check submission:
1. Every `PreCheckItem` has a non-`NOT_STARTED` status.
2. Every item **except** the one named by `ServerService.DELTA_TYPE_ITEM`
   has an evidence file attached.
3. Every item **except** that same one has a note.
4. The project has a `migrationManagerName` assigned (fails with a specific message otherwise).

**As of 2026-09-05, the pre-check is collaborative, not single-owner.** `startedByEmail` no longer
gates anything — any eligible person (`MIGRATION_ENGINEER`/`ADMIN`, per the role gate in
`SecurityConfig`) can view a half-filled form's real content, fill in whatever items are still
unfilled, and submit it, not just whoever started it. `PreCheckSubmissionService.toDto` used to
return a redacted, zeroed-out form (`PreCheckItemDto.redacted`) to everyone except the starter until
submission, and `submit()` used to 403 anyone but the starter — both removed. Per-item attribution
(`PreCheckItem.lastModifiedBy`/`lastModifiedAt`) and per-file attribution
(`PreCheckItemEvidence.uploadedBy`/`uploadedAt`) already existed in the data model and are now
surfaced in `PreCheckPanel.js` so everyone can see who filled which section and who uploaded which
file. On successful submit, it auto-creates the sign-off chain and emails the assigned Migration
Manager.

## The real sign-off / approval rule

Strictly sequential: `MIGRATION_LEAD → DEV_LEAD → QA_LEAD`
(`SignOffService.APPROVAL_SEQUENCE`, duplicated in `ProjectService`). Only the role whose turn it
currently is can approve or decline (`requireTurn`); declining bounces the chain back one step,
resetting the previous role to `PENDING` for rework. The Dev Lead alone decides, at the moment they
approve, whether QA Lead approval is required for that specific server (`qaRequired` — see
`.claude/memory/architecture.md` for why). Once the chain fully resolves (all `APPROVED` or the QA
step `SKIPPED`), the server's Delta is finalized and `deltaInitiatedAt`/`By` are stamped.

**Critical distinction, source of a real bug:** a `SignOff` row existing (`Optional.isPresent()`)
means the chain has started, not that this role has approved. "Done" must mean
`status == APPROVED`; "genuinely pending" for a non-first role additionally requires the prior
role to already be `APPROVED`. See `.claude/memory/decisions.md`.

## `README.md` was rewritten on 2026-07-27 to match this file

The old README described a per-*workspace-pair*, per-*category* ("Pre-Check 1"/"Pre-Check 2")
pre-check model with automatic escalation on every checkbox toggle — none of that matched the
actual code (server-level pre-check, manually-created escalations, no such categories or
auto-creation logic anywhere). The rewrite aligned the README's "Key business rules," "Usage flow,"
config/env var list, and CSV column reference to the entities/services described above. If the
README and this file ever disagree again, trust the code and update whichever one is wrong — don't
assume either is automatically authoritative just because it was fixed once.

## Auto-provisioning and default posture (current, not necessarily permanent)

Anyone signing in with an email on the auto-provision domain (default `cloudfuze.com`) is silently
added as `MIGRATION_ENGINEER` on first sight, regardless of `azure.require-allowlist`. Currently
`azure.require-allowlist=false` and `azure.allowed-email-domain` is blank — both explicitly
commented as temporary testing settings in `application.properties`. Don't treat this loose
posture as the intended long-term configuration.
