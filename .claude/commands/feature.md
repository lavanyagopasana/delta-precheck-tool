---
description: Implement a new feature end-to-end following this repo's conventions and layering
---

# Feature

Implement the feature described in `$ARGUMENTS` following this repo's established conventions.
This command assumes the *what* is already decided (use gstack's `/office-hours` and `/autoplan`
first if it isn't — see `CLAUDE.md`'s Recommended Workflow). This command is about the *how*,
specific to this codebase.

## Before writing code

1. Read `.claude/memory/domain-knowledge.md` and `.claude/memory/architecture.md` — confirm the
   feature's assumptions match the **actual** current data model, not `README.md` (which is known
   to be stale in places — see `.claude/memory/domain-knowledge.md` for specifics).
2. Read `.claude/rules/architecture-boundaries.md` to decide where the feature's logic belongs
   (new aggregate vs. extending an existing one — see `.claude/agents/architect.md` if it's
   genuinely ambiguous).
3. Check `.claude/rules/security-rules.md` — does this feature need a new role-gated endpoint, or
   touch the allowlist/auth model at all?

## Implementation

1. If it needs new backend structure, use `/scaffold` for the boilerplate shape, then fill in the
   actual business logic in the service layer.
2. Follow `.claude/rules/code-style.md` and `.claude/rules/api-conventions.md` exactly — don't
   introduce a new DTO-mapping style, a new error-handling pattern, or a new frontend styling
   approach just because the feature is new.
3. If the feature touches the sign-off chain, pre-check submission, or dashboard/project approval
   counts, re-read `.claude/memory/decisions.md` first — these three areas have already had
   subtle bugs from assuming "row exists" means "approved."
4. Wire any new backend endpoint into `frontend/src/api/client.js` and add an explicit
   `SecurityConfig` rule — don't leave either as an afterthought.

## Verification

1. `mvn -o -q compile` (backend) — must pass.
2. Actually exercise the feature in the running app (backend + frontend both running) — don't
   claim it works from reading the code alone. If the backend needs restarting, remember
   `AZURE_CLIENT_ID` (see `.claude/rules/security-rules.md`) or auth silently disables.
3. Check the feature against more than one data state where relevant (e.g. a sign-off feature
   against a not-yet-submitted server, a mid-chain server, and a fully-approved one — not just the
   happy path).

## Handoff

Once implemented and self-verified: `/team-review` (this repo's checklist) → gstack's `/review` →
gstack's `/qa <url>` → gstack's `/cso` if security-sensitive → gstack's `/ship`.
