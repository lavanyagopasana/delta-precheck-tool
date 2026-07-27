# Workflow: Code Review

Run both passes below for any non-trivial diff — they check different things and neither
substitutes for the other.

## Steps

1. **`/review`** (gstack) — general bugs, simplification, efficiency, dead code, naming. This is
   the broad pass and doesn't know anything project-specific.
2. **`/team-review`** (this project) — the specific checklist in
   `.claude/commands/team-review.md` / `.claude/skills/code-review/SKILL.md` /
   `.claude/agents/code-reviewer.md`: sign-off sequence invariants, `SignOffStatus`
   done-vs-pending semantics, DTO/entity boundary, CSV import shape, duplicated-constant sync,
   and whether any auth-adjacent change needs to be escalated rather than approved here.
3. If the diff touches `SecurityConfig`, `AppUserService`, or `JwtEmailUtil` — also run
   **`/cso`** (gstack) and flag it for `.claude/agents/security-reviewer.md` explicitly. Don't
   let a security-sensitive change pass on the general + project-specific review alone.
4. If the diff changes behavior `README.md` describes, note the growing documentation drift per
   `.claude/rules/pr-standard.md` — either fix the README or explicitly call out that it wasn't
   fixed, so the gap stays visible instead of silently compounding.

## Severity framing

Findings from step 2 that involve approval/sign-off status counting, an entity leaking past the
controller boundary, or a new endpoint with no `SecurityConfig` decision at all should be treated
as high severity — these are the specific shapes of bug that have actually happened in this
codebase, not hypothetical concerns.

## Fallback if gstack isn't installed

Run `.claude/commands/team-review.md` alone, and apply general code-quality judgment manually for
what gstack's `/review` would otherwise cover.
