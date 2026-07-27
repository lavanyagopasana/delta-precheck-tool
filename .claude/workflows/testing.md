# Workflow: Testing

There is no existing automated test suite (`backend/src/test/` doesn't exist, no `*.test.js`
files). This workflow covers both "verify a change works" (which must be done manually today, via
a real running instance) and "add the first tests for an area" (a separate, opt-in effort).

## Verifying a change works today (no test suite to lean on)

1. Compile/build check: `mvn -o -q compile` (backend); let the frontend's CRA dev server surface
   errors via hot reload, or `npm run build` for a full check.
2. **Actually run the app and exercise the change** — both backend and frontend running, a real
   browser. Reading the code and confirming "it should work" is not verification.
   - `/qa <url>` (gstack) is the right tool for this once both servers are up.
   - If the backend was restarted to pick up the change, confirm `AZURE_CLIENT_ID` was exported —
     otherwise auth silently disables and you won't be testing real authorization behavior
     (`.claude/rules/security-rules.md`).
3. For anything touching sign-off/approval/pre-check state, verify against more than one data
   state (not-submitted, mid-chain, fully resolved) — see `.claude/memory/decisions.md`'s
   Done/Pending bug for why a single-state check isn't sufficient.

## Adding the first tests for an area

1. Read `.claude/rules/testing-standard.md` and `.claude/skills/testing-patterns/SKILL.md` in
   full — they define the target standard since there's no existing pattern to copy.
2. Use `.claude/agents/test-writer.md` for this, or follow its guidance directly.
3. Priority order (don't scatter effort evenly): `SignOffService` → `PreCheckSubmissionService.submit`
   → `AppUserService` → `ProjectService.buildSummary`.
4. Backend: JUnit 5 + Mockito, mock repositories, never touch the real `delta_migration_tracker`
   MySQL database from a test.
5. Frontend: Jest + React Testing Library (already bundled via `react-scripts test`), mock
   `frontend/src/api/client.js` at the module boundary rather than `axios` directly.
6. Confirm any regression test actually fails on the pre-fix code before considering it done —
   don't ship a test that would pass either way.

## Where this fits in the broader review/ship flow

`/qa <url>` (gstack) is still the primary "does this actually work" check regardless of whether
unit tests exist for the touched area — it's not optional even once tests are added, since it's
the only thing that verifies real browser/integration behavior end to end.
