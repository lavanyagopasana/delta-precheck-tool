# Known Limitations

Living list of accepted trade-offs and known gaps in the Delta Pre-Check Tool. Each entry states
what the limitation is, why it exists, and (where relevant) the change that would remove it. Update
this when a limitation is added, fixed, or an ALTER/behavior change is approved.

## Performance / data

- **500-row workspace-pair cap.** `WorkspacePairService.listByServer` caps results at 500 rows and
  logs a warning when a server exceeds it. A server with >500 pairs will not show all of them in the
  UI list. Removing this requires paginating the workspace-pairs panel end-to-end (frontend + API),
  not just raising the cap.
- **`GenerationType.IDENTITY` blocks INSERT batching.** Every entity uses `IDENTITY` ids, so
  Hibernate must round-trip each row to read back the generated id and cannot batch INSERTs.
  `hibernate.jdbc.batch_size=50` therefore only helps batched UPDATEs / multi-entity flushes. Moving
  to a `SEQUENCE`/`TABLE` generator would enable INSERT batching but is a schema + id-strategy change.

## Security / validation

- **Upload validation is in observe mode (Stage 1).** `app.upload.enforce-validation=false`:
  extension / declared content-type / magic-byte mismatches are logged as warnings but the upload is
  still accepted. Zero-byte files and path-traversal filenames are already rejected/neutralized
  unconditionally. Set `APP_UPLOAD_ENFORCE_VALIDATION=true` (Stage 2) to start rejecting mismatches
  with a 400 — do this only after logs confirm real uploads stay inside the allowlist.

## Schema / persistence

- **`ddl-auto=update` in all profiles.** Schema evolves by entity annotation changes; there is no
  migration file and no `validate` profile yet. A production `validate` profile (PART 5.5) is
  recommended so a silent schema drift fails fast at startup instead of being masked.
- **(Historical, MySQL-only — resolved by the Postgres migration) `tickets.idx_ticket_url` was
  missing on MySQL.** Confirmed via `SHOW INDEX FROM tickets`: only `PRIMARY`, `idx_ticket_status`,
  and the `server_id` FK index existed; `idx_ticket_url` never got created because a full index on
  the old `VARCHAR(2000)` column exceeded InnoDB's 3072-byte utf8mb4 key limit (`ddl-auto=update`
  logged the failure as a warning and continued instead of failing loudly). The entity column was
  fixed to `VARCHAR(512)` at the time, which resolved it on MySQL too. Kept here only as a reminder
  of the failure *mode* — MySQL's `ddl-auto=update` can silently skip a DDL statement it can't apply,
  so a missing index doesn't necessarily surface as an error. Postgres's btree index limits work
  differently and this project's fresh Postgres database has no pre-existing `tickets` table for the
  old failure to have happened against.
- **(Historical, MySQL-only) `delta_cycles.status` was a native MySQL `ENUM`, which
  `ddl-auto=update` could not widen when `DECLINED` was added as a fourth value** — had to be applied
  manually via `ALTER TABLE delta_cycles MODIFY COLUMN status ENUM(...) NOT NULL;`.
- **The same problem class exists on Postgres too, just via a different mechanism — corrected after
  first (wrongly) claiming this was MySQL-only.** `@Enumerated(EnumType.STRING)` maps to a plain
  `VARCHAR` on Postgres (confirmed: no native Postgres `ENUM` type is used), but Hibernate 6 also
  auto-generates a `CHECK` constraint enumerating every allowed value for **every** enum-mapped
  column in the schema — confirmed via `pg_constraint` right after the migration: all 18 of them,
  not just `delta_cycles.status`. `ddl-auto=update` only ever adds schema elements; it does not
  alter an existing `CHECK` constraint (same conservative behavior that caused the MySQL problem
  above). So adding a new value to **any** enum in this codebase (`ItemStatus`, `AppUserRole`,
  `SignOffStatus`, `TicketStatus`, `ProductType`, `PairStatus`, `SignOffRole`, `DeltaType`,
  `DeltaCycleStatus`, …) will likely need a manual
  `ALTER TABLE <table> DROP CONSTRAINT <table>_<column>_check, ADD CONSTRAINT <table>_<column>_check
  CHECK (<column> IN (...));` in any environment with an existing table — not verified end-to-end
  (would need an actual add-a-value-and-restart cycle to confirm `ddl-auto=update` really never
  touches it), but consistent with Hibernate's documented additive-only schema-update behavior.
  Not needed for a brand-new database: the constraint is created with every current value already
  included the first time the table is created.

## Correctness (reported, not yet fixed)

- **Date/timezone: entities use `LocalDateTime`, frontend renders with `new Date(...)`.** Timestamps
  (`createdAt`, `submittedAt`, `approvedAt`, `deltaInitiatedAt`, …) are stored/serialized as
  zoneless `LocalDateTime` (server wall-clock). No `user.timezone`/`TZ`/`hibernate.jdbc.time_zone` is
  set anywhere, so the JVM uses the host OS zone — the same code writes different values on an IST dev
  box vs a UTC server. The frontend parses them with `new Date(iso)` which interprets a zoneless
  string as the *browser's* local time, so display is off by the offset when server and user zones
  differ. Moving these to `Instant`/`OffsetDateTime` (UTC over the wire) is an entity-column change,
  is *not* a zero-behavior-change fix (values gain a `Z`, 5 frontend render sites change), and needs
  approval — full ALTER list and whether a data shift is required (depends on the prod JVM zone) is in
  the STEP 7 audit report.

## Concurrency

- **Optimistic locking added to `SignOff` and `Ticket` only.** Both now carry a `@Version` column, so
  two simultaneous sign-off approvals or concurrent ticket resolve/edit no longer last-writer-win
  silently — the losing write raises an optimistic-lock exception mapped to HTTP 409 with a
  reload-and-retry message (`GlobalExceptionHandler.handleOptimisticLock`). **ALTER pending** (run
  before/at next deploy, or restart lets `ddl-auto=update` add them):
  `ALTER TABLE sign_offs ADD COLUMN version BIGINT NOT NULL DEFAULT 0;`
  `ALTER TABLE tickets ADD COLUMN version BIGINT NOT NULL DEFAULT 0;`
  Still **not** guarded: overlapping CSV imports and other entities (`Project`, `Server`,
  `PreCheckSubmission`, `PreCheckItem`) — out of scope for this pass by request.

## Dependencies

- **Spring Boot pinned at 3.3.13 (final OSS 3.3.x patch; branch is EOL).** Bumped from 3.3.4 to
  3.3.13 (the last open-source 3.3.x release, June 2025). No further OSS security patches ship for
  3.3.x — managed deps (Tomcat, Logback, Netty, etc.) continue to accrue CVEs upstream. A future pass
  should move to a supported line (3.4.x / 3.5.x / 4.x); that is a framework upgrade requiring
  testing, out of scope for the security-tier patch bump.
- **axios pinned at 1.19.0.** The instruction was "latest 1.7.x", but `npm audit` shows the entire
  1.0.0–1.17.0 range carries ~30 high/moderate advisories (SSRF, credential leakage, prototype
  pollution). 1.19.0 is the audit-clean fix, so axios was pinned there instead (approved) — no axios
  advisory remains.
- **react-scripts (CRA) toolchain advisories persist (build-time only).** `npm audit` reports 73
  findings (63 high); essentially all are in the `react-scripts` transitive build chain (eslint, jest,
  webpack, svgo, postcss, workbox, serialize-javascript, nth-check, …) which runs only at build/test
  time and is not shipped in the browser bundle. The one shipped-code item is `react-router-dom`
  (moderate open-redirect) — left untouched by request. `npm audit fix --force` would downgrade
  react-scripts to 0.0.0 (breaking); the real fix is migrating off CRA, out of scope.

## Testing

- **Coverage expanded, still partial.** Backend: service-layer unit tests for `AppUserService` (incl.
  the roster-cache eviction lock), `TicketService`, `SignOffService` (incl. the double-approval
  guard), `PreCheckSubmissionService`, and `WorkspacePairService` (CSV oversized-cell row-error path);
  repository `@DataJpaTest` for the 500-cap ordering proof and the `@Version` stale-write proof;
  controller `@WebMvcTest` for `TicketController`, `SignOffController`, `ProjectController`,
  `AdminController`, and `PreCheckItemController` (status codes, `GlobalExceptionHandler` JSON on
  `@Valid` failure incl. the new DTO constraints, 401 unauthenticated, 403 wrong-role, 409
  stale-version); plus the `@SpringBootTest` characterization snapshots for five endpoints. Frontend:
  React Testing Library tests for `TicketsPage` and `ApprovalsPage` (error-banner regression lock) and
  form tests for `PreCheckPanel` and the ticket form. Not yet covered: `ProjectService.buildSummary`,
  the remaining controllers' `@WebMvcTest`, and general `CsvUtils` quote/BOM parsing edge cases.

## CSV import (resolved this pass)

- **Oversized cell no longer aborts the whole import.** Previously a cell longer than its column
  (`source_email`/`destination_email` 255, `source_path`/`destination_path` 1000, `combination` 200)
  caused a `DataIntegrityViolationException` mapped to a generic 409 that failed the entire file.
  `WorkspacePairService.processRow` now length-checks each field first and emits
  `Row N: <field> exceeds maximum length` into `errors[]` — the row is skipped and every other row
  still imports, matching how all other row failures behave.
