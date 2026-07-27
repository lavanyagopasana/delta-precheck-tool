---
name: code-review
description: This project's specific review conventions (sign-off invariants, DTO boundary, auth-gating decisions) — not a general code review skill. Use gstack's /review for general bugs/style/simplification; use this to know what's specific to this codebase.
---

# Code Review (project-specific)

This skill is the knowledge behind `.claude/commands/team-review.md`. Load it when reviewing any
change to this repo, whether or not the `/team-review` command is invoked directly — e.g. when a
user pastes a diff and asks "does this look right," bring this knowledge to bear rather than only
generic review instincts.

## What's specific to this codebase (not covered by general review skills)

**The sign-off chain is stateful and sequential**, not a simple set of independent flags.
`SignOffService.APPROVAL_SEQUENCE` = `[MIGRATION_LEAD, DEV_LEAD, QA_LEAD]`. A `SignOff` row's mere
existence means the chain has started for that server — it does **not** mean that role has
approved. Reviewing code that counts "how many are done" or "how many are pending" must check
`.getStatus() == SignOffStatus.APPROVED`, not `Optional.isPresent()`. This exact bug shipped once
this session (`DashboardService`/`ProjectService` both counted row-existence as "done") — see
`.claude/memory/decisions.md` for the full story, and treat any new approval-counting code with
extra scrutiny.

**DTOs are the only thing that crosses the controller boundary.** An entity returned directly from
a `@RestController` method (even nested inside another DTO's field) is a defect — check for it
specifically, since Jackson will serialize a lazy-loaded JPA proxy in a way that either throws or
leaks unexpected fields.

**Every new endpoint needs an intentional `SecurityConfig` decision.** There's no safe assumption
about what the default should be — review any new controller method by checking whether
`SecurityConfig.securityFilterChain` has an explicit matcher for it, and if not, whether the
generic `allowlistRequired()` fallback is actually the right call for that specific endpoint (it
usually is, but should be a conscious choice — see `.claude/rules/security-rules.md` for the
existing role-gated exceptions).

**CSV import has an established shape.** `AppUserService.importCsv` and
`WorkspacePairService.importCsvGlobal` both: use `CsvUtils.parseLine` (never naive comma-split),
auto-detect an optional header row, process row-by-row collecting errors into a list rather than
failing the whole import, and return a `*ResultDto` with `totalRows`/`createdCount`/`updatedCount`/
`errors`. A new CSV import feature that doesn't follow this shape should be flagged, not silently
accepted as "a different style is fine here."

**Duplicated constants are a known liability, not a design choice.** `APPROVAL_SEQUENCE` (in
`SignOffService` and `ProjectService`) and `normalizeHeader` (in `AppUserService` and
`WorkspacePairService`) are each defined twice. Any diff touching one copy should prompt a check of
whether the other needs the identical change.

## What this skill deliberately does NOT cover

General code smells, naming, complexity, dead code, off-by-one errors, missing null checks — that's
gstack's `/review` territory and it's good at it. Don't duplicate that effort here; focus review
time on the project-specific items above that a generic reviewer (human or AI) wouldn't know to
check for without having read this codebase's history.

## Severity guidance

Treat these as high severity, not style nits, when found:
- Approval/pending counting that conflates row-existence with approved status.
- An entity leaking past the controller boundary.
- A new endpoint with no `SecurityConfig` entry at all (not even the default fallback — i.e. it's
  unreachable or the security config wasn't updated for a new base path).
- Auth/allowlist logic changed without the reviewer confirming `/api/me` was actually tested with a
  real token (see `.claude/rules/security-rules.md`'s `AZURE_CLIENT_ID` trap — "it compiles and the
  app loads" is not sufficient verification for auth changes).
