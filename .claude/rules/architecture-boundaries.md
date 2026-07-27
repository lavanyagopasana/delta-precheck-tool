# Architecture Boundaries

## Layering (backend)

```
controller  →  service  →  repository  →  entity
              ↑
             dto (in/out at this boundary only)
```

- **Controllers are thin.** They extract the caller's identity (`JwtEmailUtil.extractEmail`),
  delegate to exactly one service method, and map to a DTO. No business logic, no repository
  calls, no `if` statements deciding business outcomes — that all belongs in the service layer.
  Compare `AdminController` (thin: extract email, check admin, delegate) against
  `AppUserService.importCsv` (all the actual logic).
- **Services own business rules and orchestrate repositories.** A service can call other services
  (e.g. `PreCheckSubmissionService` calls `ServerService` and `SignOffService`), but a controller
  should never call two services to manually compose behavior a service should own.
- **Entities never leave the service layer.** Controllers and DTOs are the only things a
  frontend/API client sees; an entity crossing that boundary (returned directly from a
  `@RestController` method) is a bug, not a shortcut — JPA lazy-loading proxies don't serialize
  safely and it leaks internal fields.
- **Repositories are Spring Data interfaces only** — no custom repository implementation classes
  exist in this codebase; derived query methods (`findByEmailIgnoreCase`, `countByRole`, etc.) are
  the established pattern. If a query needs more than a derived method can express, put the
  aggregation logic in the service (iterate + compute in Java), matching how
  `DashboardService.getSummary` and `ProjectService.buildSummary` do their approval-status math,
  rather than introducing `@Query` JPQL as a first resort.

## Where does a new concept belong?

- **New status/enum for an existing thing** (e.g. a new `SignOffStatus` value) — extend the
  existing enum, but check every `switch`/`if` that exhausts it (`ProjectService.roleLabel`,
  `applyReadinessStage`, frontend `OverallStepper`'s `dotClass`/`label` mapping) — Java doesn't
  force exhaustiveness on a plain `if`-chain here, and neither does JS.
- **New relationship on an existing aggregate** (e.g. something new hanging off `Server`) — new
  `@ManyToOne`-owning entity + repository + service methods, following the `PreCheckItem`/
  `WorkspacePair`/`SignOff` pattern (all three are `@ManyToOne` back to `Server`, `Server` doesn't
  hold collections of them directly — lookups go through the repository, e.g.
  `signOffRepository.findByServerId`).
- **A genuinely new top-level aggregate** (not hanging off `Project`/`Server`) — this is an
  `.claude/agents/architect.md` decision; don't add a new top-level entity without checking whether
  it actually needs to be one versus a field/relationship on something existing.
- **Cross-cutting utility logic used by 2+ services** — goes in `util/` (`CsvUtils`,
  `JwtEmailUtil`). A helper used by only one service stays private to that service.

## Frontend boundaries

- **`src/api/client.js` is the only place that knows the backend's URL shape.** Pages and
  components call exported functions from it; they never construct a URL or call `axios` directly.
- **`src/auth/`** owns everything MSAL/token-related. Nothing outside it should read
  `msalInstance` or construct a bearer token directly — go through `getAccessToken()`.
- **Pages own page-level state and data fetching**; components are given data + callbacks as
  props and don't fetch their own data (exception: `NavBar`'s escalation-count poller, which is
  intentionally self-contained since every page needs the sidebar badge). Don't add a second
  self-fetching component without a similarly clear reason.

## What NOT to do

- Don't call a repository directly from a controller "just this once" — even for a trivial lookup,
  put it in the service so the layering stays predictable for the next person.
- Don't duplicate `SignOffService.APPROVAL_SEQUENCE` a third time. It's already duplicated once (in
  `ProjectService`) — extracting it to a shared constant (e.g. on `SignOffRole` itself, or a small
  shared class) is worth doing the next time either copy needs to change, per
  `.claude/memory/decisions.md`.
- Don't introduce a second HTTP client convention on the frontend (no direct `fetch()` calls) or a
  second DTO-mapping convention on the backend (no MapStruct/manual-mapper mix — this codebase maps
  entity → DTO by hand in the service layer; stay consistent).
