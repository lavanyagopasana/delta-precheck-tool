# Workflow: Feature Build

The full path from idea to shipped PR for a new feature in the Delta Pre-Check Tool.
Assumes gstack is installed (`CLAUDE.md`'s Pre-flight check) — if not, follow the "no gstack"
fallback at the bottom.

## Steps

1. **`/office-hours`** (gstack) — clarify the problem. What's the actual user need, and does it
   fit the existing `Project → Server → WorkspacePair/PreCheckItem/SignOff` shape, or does it need
   something new? Cross-check against `.claude/memory/domain-knowledge.md` so the discussion is
   grounded in what the app actually does, not what `README.md` claims it does.
2. **`/autoplan`** (gstack) — CEO + eng + design review of the resulting plan.
3. **Implement**, following:
   - `.claude/commands/feature.md` for the practical how-to.
   - `.claude/rules/architecture-boundaries.md` for where the new code belongs.
   - `.claude/skills/architecture/SKILL.md` and `.claude/agents/architect.md` if placement is
     genuinely ambiguous (new aggregate vs. extending an existing one).
   - `.claude/commands/scaffold.md` for the boilerplate shape (entity/repo/service/controller/DTOs
     or a new frontend page).
4. **`/team-review`** — this project's own checklist (sign-off invariants, DTO boundary, CSV
   conventions, the `AZURE_CLIENT_ID` trap).
5. **`/review`** (gstack) — general code review.
6. **`/qa <staging-url>`** (gstack) — real browser test. Remember: if the backend needed a
   restart to pick up the change, it must be restarted **with** `AZURE_CLIENT_ID` set, or auth
   silently disables and the QA pass won't be testing real authorization behavior.
7. **`/cso`** (gstack) — security audit, only if the feature is security-sensitive (touches
   `SecurityConfig`, `AppUserService`, role gating, or the allowlist).
8. **`/ship`** (gstack) — opens the PR.

## After shipping

Update `.claude/memory/progress.md` (move the feature from "in-flight" to "recently completed," or
add it if it wasn't already tracked) and `.claude/memory/decisions.md` if the feature involved a
non-obvious choice worth recording for the next session.

## Fallback if gstack isn't installed

Use `.claude/commands/feature.md` directly for implementation guidance, `.claude/commands/
team-review.md` for review, and manually verify + open the PR (`gh pr create` or equivalent) —
see `CLAUDE.md`'s Skill routing "Option 2 — Use normal project files."
