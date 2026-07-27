---
description: This project's own review checklist (sign-off invariants, DTO boundary, CSV conventions) — run alongside gstack's /review, not instead of it
---

# Team Review

> **Naming note:** this command is named `team-review`, not `review`, because gstack reserves
> `/review` for its own general-purpose diff review. Run gstack's `/review` first for general bugs
> and simplification, then run this for the checks specific to this codebase.

Review the current diff (or the files/area the user specifies in `$ARGUMENTS`) against this
project's specific conventions. Read `.claude/rules/architecture-boundaries.md`,
`.claude/rules/api-conventions.md`, `.claude/rules/security-rules.md`, and
`.claude/rules/code-style.md` first if you haven't already this session.

## Checklist

1. **Sign-off sequence invariants** — if the diff touches `SignOffService`, `ProjectService`, or
   anything approval-related: does it respect `APPROVAL_SEQUENCE` (Migration Manager → Dev Lead →
   QA Lead)? Are both copies of that constant (`SignOffService` and `ProjectService`) kept in sync
   if either changed? Does "Pending" mean "genuinely awaiting a decision this turn" and "Done" mean
   "actually `APPROVED`" — not merely "a row exists" (see `.claude/memory/decisions.md` for the bug
   this exact confusion caused).

2. **DTO/entity boundary** — does every new/changed controller method return a DTO, never an
   entity? Does every new DTO field actually get set by the service (`ProjectService`'s
   `copySummary` helper is the pattern to check against for "did I forget a field")?

3. **CSV import conventions** — if the diff adds or touches a CSV import path: does it use
   `CsvUtils.parseLine`, detect an optional header row the same way `AppUserService.importCsv` and
   `WorkspacePairService.importCsvGlobal` both do, and return a `*ResultDto` with per-row errors
   instead of failing the whole batch on one bad row?

4. **Auth/security fallout** — does the diff touch `SecurityConfig`, `AppUserService`, or
   `JwtEmailUtil`? If so, is there an explicit, intentional `.access(...)` rule for any new
   endpoint (not a silent fall-through)? Flag anything here for `.claude/agents/security-reviewer.md`
   and gstack's `/cso` — don't approve it here alone.

5. **The `AZURE_CLIENT_ID` trap** — if the diff includes instructions to restart the backend
   (in a PR description, a script, or documentation), does it actually include exporting
   `AZURE_CLIENT_ID`? A restart command without it will silently disable auth.

6. **Naming/placement consistency** — does a new enum value get handled everywhere the enum is
   exhausted (see `.claude/rules/architecture-boundaries.md`'s "new status" guidance)? Does new
   frontend API-call code go into `src/api/client.js` rather than a direct `axios`/`fetch` call?

7. **Stale-doc awareness** — if the diff changes behavior that `README.md` describes, does it
   also flag (even just in the PR description) that the README is now further out of date, per
   `.claude/rules/pr-standard.md`? Don't silently let documentation drift grow.

## Output

Report findings the same way gstack's `/review` does: most-severe first, with a one-line summary,
concrete failure scenario, and file:line reference. If nothing in the checklist applies to this
diff, say so plainly instead of padding the review with generic observations.
