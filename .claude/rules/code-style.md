# Code Style

Project-specific conventions derived from the existing codebase. gstack's `/review` catches
general code smells; this file is what it doesn't know about this repo specifically.

## Backend (Java / Spring Boot)

- **Constructor injection only.** Every service/controller in this repo takes its dependencies via
  a plain constructor (no `@Autowired` on fields, no Lombok `@RequiredArgsConstructor` — they're
  written out explicitly, e.g. `AppUserService(AppUserRepository appUserRepository)`). Match this.
- **Lombok `@Getter`/`@Setter`/`@NoArgsConstructor` on entities and DTOs**, not `@Data` (avoid
  `@Data`'s generated `equals`/`hashCode`/`toString` on JPA entities — it's a known footgun with
  lazy associations and bidirectional relationships).
- **One enum per file**, named for the concept it represents (`SignOffStatus`, `ItemStatus`,
  `PairStatus`, `SubmissionStatus`, `EscalationStatus`, `EscalationPriority`, `AppUserRole`,
  `SignOffRole`, `ProductType`). Don't fold multiple statuses into one shared enum even if they
  look similar — `SignOffStatus` and `ItemStatus` both have a "pending-ish" state but mean
  different things.
- **Package-private helper methods stay private** in the service that uses them (see
  `ProjectService.roleLabel`, `AppUserService.normalizeHeader`) rather than being extracted to a
  shared utility unless genuinely reused across 2+ services (`CsvUtils`, `JwtEmailUtil` are the
  only two extracted so far, because import/CSV parsing and JWT-email extraction both repeat
  across multiple services/controllers).
- **Comments explain "why", not "what".** Follow the existing style: e.g.
  `PreCheckSubmissionService.isItemComplete`'s comment explains which status counts as "complete"
  and why evidence is checked separately — it doesn't restate what the code does line by line.
- **Case-insensitive email matching everywhere.** Every email comparison/lookup uses
  `equalsIgnoreCase` or a repository's `*IgnoreCase` method (`findByEmailIgnoreCase`,
  `existsByEmailIgnoreCase`). Never use `.equals()` on an email string.
- **CSV parsing goes through `CsvUtils.parseLine`**, never a naive `String.split(",")` — it handles
  quoted fields with embedded commas. Header detection normalizes via lowercasing and stripping
  non-letters (see `normalizeHeader` in both `WorkspacePairService` and `AppUserService` — these
  two are duplicated; if you touch one, check whether the other needs the same fix).

## Frontend (React)

- **Function components with hooks only** — no class components exist in this codebase, don't
  introduce one.
- **Inline `style={{...}}` objects are the dominant styling approach**, not CSS modules or
  styled-components (only `index.css` has hand-written classes for structural/reusable things like
  `.card`, `.badge`, `.checklist`, `.engineer-select`). Match whichever pattern the file you're
  editing already uses — don't introduce a third styling approach.
- **All API calls go through `src/api/client.js`.** Never call `axios` or `fetch` directly from a
  page/component — add a new exported function to `client.js` instead, even for a one-off call.
  This keeps the whole backend surface area discoverable in one file.
- **Toasts for feedback, not `alert()`** — use `useToast()` from `components/Toast.js`.
  `window.confirm()` is used sparingly for destructive actions only (removing a user, rejecting a
  sign-off) — match that pattern, don't add new confirm dialogs for non-destructive actions.
- **One page = one route = one file** under `src/pages/`. Shared pieces used by 2+ pages go in
  `src/components/`, not duplicated per page.

## Naming

- Backend DTOs are suffixed by role: `*Dto` (read/response shape), `*Request` (write/input shape),
  `*ResultDto` (bulk-operation summary, e.g. `AppUserImportResultDto`,
  `WorkspacePairImportResultDto`). Don't reuse an entity as a response body — always map to a DTO.
- Frontend API functions in `client.js` are named as verbs matching their HTTP method:
  `get*`/`create*`/`update*`/`remove*`/`import*Csv`. Keep new additions consistent with that.
