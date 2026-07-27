# PR Standard

This repo isn't a git repository yet (no `.git/`), so there's no PR history to pattern-match
against. This file sets the bar for once it is one — follow it once `git init` happens and PRs
become real, and treat it as the target standard for how a change should be organized even before
then (e.g. when reviewing a diff or deciding what belongs together in one commit).

## Before opening a PR

Run, in order (see `CLAUDE.md`'s Recommended Workflow):

1. `/review` (gstack) — general bugs/simplification/efficiency pass.
2. `.claude/commands/team-review.md` (`/team-review`) — this project's own checklist: sign-off
   sequence invariants, DTO/entity boundary, CSV parsing conventions, the `AZURE_CLIENT_ID` trap.
3. `/qa <url>` (gstack) — real browser test against a running instance, not just "it compiles."
4. `/cso` (gstack) — only if the change touches `SecurityConfig`, `AppUserService`, or anything
   role/auth-gated.
5. `/ship` (gstack) — opens the PR once the above pass.

## Scope

- One logical change per PR. This session is a good negative example to avoid repeating: many
  small, unrelated fixes (a dashboard label rename, an auth outage from a dropped env var, a CSV
  bulk-import feature, a UI redesign) all happened in one continuous work session because they
  were discovered incidentally — but they should ship as **separate PRs**, not one, once this is a
  real repo with PR history. Bundle only things a reviewer would want to accept/reject together.
- If a fix reveals a second, unrelated bug, note it and fix it in a follow-up PR rather than
  expanding scope silently.

## Commit / PR description

- Explain **why**, not just what — this codebase's own code comments already follow that
  convention (see `SignOffService`'s and `PreCheckSubmissionService`'s comments); PR descriptions
  should match.
- If the change fixes a bug in the Done/Pending, sign-off, or auth logic, explain what the
  *previous* (wrong) behavior was and what specifically was wrong about it — not just "fixed
  approval counts." Future readers (including future Claude sessions) need the "before" to
  understand why the "after" looks the way it does.
- Call out any known-stale documentation the change didn't address (e.g. README sections that
  still describe the old per-workspace-pair pre-check model — see
  `.claude/memory/domain-knowledge.md`) so the gap stays visible instead of silently persisting.

## What must be true before merging

- The backend actually restarts cleanly and `mvn -o -q compile` (or a full `mvn compile`) passes —
  don't rely on "the IDE didn't show an error."
- If the change touches `SecurityConfig` or `AppUserService`, confirm `/api/me` returns a real
  populated response with a valid token, not just that the app "loads" (see
  `.claude/rules/security-rules.md` — a silently-open backend still loads fine).
- If the change touches the sign-off chain or approval counts, verify against a real project with
  servers in more than one chain state (not-submitted, mid-chain, fully approved) — a fix that only
  looks right for the fully-approved case is not verified.
- New tests (if any — see `.claude/rules/testing-standard.md`) actually run and pass.

## Reviewer checklist (for whoever reviews, human or agent)

- Does this stay within the layering in `.claude/rules/architecture-boundaries.md`?
- Does any new endpoint have an explicit, intentional entry in `SecurityConfig` (not just falling
  through to the default)?
- Does this duplicate logic that already exists once (the two `APPROVAL_SEQUENCE` copies, the two
  `normalizeHeader` copies) instead of extending the existing copy or extracting a shared helper?
- Is the PR's own description honest about what changed and why, per the section above?
