---
name: testing-patterns
description: How to write the FIRST tests for this codebase (there are zero today) — priority order, framework choices already implied by dependencies, and what NOT to test against (no shared dev DB). Not a general testing-philosophy skill.
---

# Testing Patterns (project-specific)

This codebase has **no existing test suite to pattern-match against** — `backend/src/test/`
doesn't exist, and there are no `*.test.js` files in the frontend despite both toolchains being
wired up (`spring-boot-starter-test` on the classpath, `react-scripts test` in `package.json`).
This skill exists because "look at how the existing tests are structured" — the normal way to learn
a codebase's testing conventions — isn't possible here. Follow `.claude/rules/testing-standard.md`
for the full standard; this skill is the practical how-to.

## Don't assume tests don't matter here

Zero tests is a gap, not evidence this project doesn't value them — `spring-boot-starter-test`
being present at all signals intent. Load this skill whenever asked to add tests, fix a bug (a
regression test is often the right accompaniment), or touch one of the business-rule-heavy classes
listed below.

## What to test first, and why

In priority order (see `.claude/rules/testing-standard.md` for the full rationale):

1. `SignOffService` — the approval sequence, turn-taking, decline/bounce-back, and the
   Dev-Lead-decides-QA branch. Highest logic density in the app.
2. `PreCheckSubmissionService.submit` — three preconditions (status/evidence/notes on every item)
   plus the single-editor lock (`startedByEmail`).
3. `AppUserService` — role resolution, auto-provisioning, and the new `importCsv` header-detection
   + per-row-error behavior.
4. `ProjectService.buildSummary` — the approval Done/Pending computation. This one already had a
   real bug (counting row-existence as "done") that a test would have caught — a good first
   candidate for anyone looking to add a regression test.

## Backend test setup (from scratch)

- JUnit 5 + Mockito, both already available transitively via `spring-boot-starter-test`.
- Mock the repository layer directly for service tests — `@ExtendWith(MockitoExtension.class)`,
  `@Mock` the `*Repository` dependencies, construct the service under test with the real
  constructor (this codebase uses constructor injection everywhere, which makes this
  straightforward — no need for `@InjectMocks` reflection magic, just call the constructor).
- **There is no test database.** Don't point a test at the real `delta_migration_tracker` PostgreSQL
  instance (it likely has real-looking project/server data from manual testing). If a real
  persistence-layer test is needed, use `@DataJpaTest` with the in-memory H2 driver (not currently
  a dependency — it would need to be added to `pom.xml` under `test` scope) rather than the real
  PostgreSQL connection.
- Mirror the main package path exactly under `backend/src/test/java/...` — this directory doesn't
  exist yet; creating it is part of writing the first test, not a separate setup step.

## Frontend test setup (from scratch)

- Jest + React Testing Library, both bundled with `react-scripts test` already — no new
  dependency needed.
- Mock `frontend/src/api/client.js` at the module level (`jest.mock("../api/client")`) rather than
  mocking `axios` directly — every page/component already goes through `client.js`
  (`.claude/rules/architecture-boundaries.md`), so mocking at that boundary is both easier and
  matches how the app is actually structured.
- Best first targets: `EngineerChecklist` (search/add/remove chip interactions — real logic, not
  just layout), `DataTable` (sort/filter), `ApprovalsPage`'s `primaryRowFor` (a pure function,
  trivially unit-testable without rendering anything).

## Anti-patterns to avoid

- Don't write a test that would pass against both the buggy and fixed version of a bug you're
  fixing — that's not a regression test, it's decoration. Confirm it actually fails on the
  pre-fix code first.
- Don't chase coverage percentage on files that are thin by design (controllers, DTOs) at the
  expense of the business-rule-heavy services above.
- Don't introduce a different test framework/runner than what's already implied by the
  dependencies already present (no introducing Jasmine, TestNG, Cypress, etc. without a separate,
  explicit decision to do so).
