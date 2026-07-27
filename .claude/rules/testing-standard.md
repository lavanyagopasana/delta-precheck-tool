# Testing Standard

**Current state: zero automated tests exist.** `spring-boot-starter-test` is on the backend
classpath but `backend/src/test/` doesn't exist yet. The frontend has no `*.test.js` files despite
`react-scripts test` being wired up. This file defines the target standard for the *first* tests
written in each area — there's no existing pattern to copy, so follow this instead.

Don't treat the absence of tests as evidence that tests aren't wanted here — it's a gap, not a
convention. `.claude/agents/test-writer.md` exists specifically to start closing it. That said,
don't unilaterally add a large test suite to an unrelated PR — see `.claude/rules/pr-standard.md`.

## What to test first (priority order)

1. **`SignOffService`** — the approval sequence, turn-taking (`requireTurn`), decline-bounces-back
   behavior, and the Dev-Lead-skips-QA branch. This is the highest-value target: it's the most
   business-rule-dense class in the app and the hardest to reason about by reading alone.
2. **`PreCheckSubmissionService.submit`** — the three preconditions (all items have a status, all
   have evidence, all have notes) and the "locked by another editor" behavior
   (`startedByEmail` check).
3. **`AppUserService`** — `roleOf`/`isAllowed`/`autoProvisionIfEligible` interactions, and
   `importCsv`'s header-detection + per-row error collection.
4. **`ProjectService.buildSummary`** — the Done/Pending approval-status computation (this had a
   real bug fixed this session; see `.claude/memory/decisions.md` — a regression test here would
   have caught it).
5. Controllers and DTOs are lower priority — they're thin, and `GlobalExceptionHandler`'s uniform
   error shape means there's little controller-level logic to test in isolation.

## Backend approach

- **Framework**: JUnit 5 + Mockito (already implied by `spring-boot-starter-test`) —
  `@ExtendWith(MockitoExtension.class)` unit tests for services, mocking repositories directly
  rather than spinning up a full Spring context. Reserve `@SpringBootTest` for the rare case that
  needs the real application context wired (e.g. verifying `SecurityConfig`'s filter chain
  behavior end-to-end).
- **No test database exists.** Don't point tests at the real `delta_migration_tracker` MySQL
  database — either mock the repository layer (preferred for service-level tests) or introduce an
  in-memory H2 database scoped to `@DataJpaTest` if a real persistence-layer test is genuinely
  needed. Never assume a shared dev database is safe to write test data into.
- **Test file location**: mirror the main package structure exactly —
  `backend/src/test/java/com/cloudfuze/deltatracker/service/SignOffServiceTest.java` for
  `backend/src/main/java/.../service/SignOffService.java`.

## Frontend approach

- **Framework**: whatever `react-scripts test` provides out of the box (Jest + React Testing
  Library) — don't introduce a different test runner.
- **Start with components that have real logic**, not pure layout: `EngineerChecklist` (the
  search/filter/add/remove interactions), `DataTable` (sort/filter behavior), and
  `ApprovalsPage`'s `primaryRowFor`/`OverallStepper` logic. Purely presentational components
  (`StatusBadge`, `Modal`) are lower priority.
- **Mock `src/api/client.js`**, never let a test hit `http://localhost:8080` for real.

## What "done" looks like for a PR that adds tests

- New tests actually run (`mvn test` / `npm test` exits 0) — verify this yourself, don't assume.
- A test that exists to reproduce a bug fix should fail on the pre-fix code and pass on the
  post-fix code — if you can't demonstrate that, the test may not be testing what you think.
- Don't chase 100% coverage on a repo starting from zero — depth on the business-rule-heavy
  services above is worth far more than shallow coverage everywhere.
