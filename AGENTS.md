# Agents

This project defines five specialized agents in `.claude/agents/`. They exist for **project-specific**
concerns this codebase needs an expert lens on — general-purpose review, QA, and shipping are
already handled by gstack (`/review`, `/qa`, `/ship`, `/cso`), so these agents are scoped narrowly
to what gstack doesn't already know about this specific domain model.

Read `CLAUDE.md`'s Pre-flight/Skill routing section first: if gstack is installed, most review/QA
work should go through gstack's commands. These agents are for when you need a deep, focused pass
on one project-specific concern (invoked directly via the `Agent` tool, or as part of a
`.claude/workflows/*.md` run), not a substitute for gstack's general workflow.

## Where gstack overlaps, and where these agents pick up

| Concern | Handled by | Notes |
|---|---|---|
| General code quality, bugs, simplification | gstack `/review` | Run this first on any diff |
| Browser/functional QA | gstack `/qa <url>` | Real browser test against a running instance |
| Security audit | gstack `/cso` | Broad security sweep |
| Shipping / PR | gstack `/ship` | Opens the PR once review + QA pass |
| **Sign-off chain / approval-status correctness** | `code-reviewer` (this repo) | gstack doesn't know this project's `SignOffService.APPROVAL_SEQUENCE` invariants or the row-exists-vs-approved distinction that caused real bugs this session |
| **Auth/allowlist/JWT-claim correctness** | `security-reviewer` (this repo) | gstack's `/cso` is general; this agent knows `AppUserService`, `SecurityConfig`'s permitAll fallback, and the `AZURE_CLIENT_ID` footgun specifically |
| **New backend/frontend architecture decisions** | `architect` (this repo) | Knows this repo's entity/service/controller/DTO boundaries and where a new concern should live |
| **First tests for a module** | `test-writer` (this repo) | There are zero tests today — this agent establishes the pattern per `.claude/rules/testing-standard.md`, since there's no existing test to copy from |
| **Deep repo research / cross-file tracing** | `researcher` (this repo) | For "how does X actually work end-to-end" questions across controller → service → entity, especially where the README is stale |

## Agent Directory

### `security-reviewer` (`.claude/agents/security-reviewer.md`)
Reviews changes touching `SecurityConfig`, `AppUserService`, `JwtEmailUtil`, or any
`@AuthenticationPrincipal Jwt` usage. Owns: JWT validation correctness, allowlist/role-gating
logic, the `permitAll`-when-`AZURE_CLIENT_ID`-blank fallback, and anything that could silently
widen access. **Escalate to the human** before merging any change that touches
`SecurityConfig.securityFilterChain` or removes a `.access(...)` restriction — these are
security-sensitive by definition and gstack's `/cso` should also run on them.

### `test-writer` (`.claude/agents/test-writer.md`)
Writes the first tests for a module that has none, following `.claude/rules/testing-standard.md`.
Owns: choosing what's worth testing first (business-rule-heavy services like `SignOffService` and
`PreCheckSubmissionService` over thin controllers/DTOs), and setting up the initial test
infrastructure (`backend/src/test/` doesn't exist yet — the first test-writer run for a module also
creates the scaffolding). Does not own deciding *whether* to add tests to a given PR — that's a
human/architect call given this repo currently ships without them.

### `researcher` (`.claude/agents/researcher.md`)
Read-only deep-dive agent for "how does X actually work" questions that span multiple files —
tracing a request from controller → service → entity → repository, or reconciling what the
README claims against what the code does (the README is known to be stale in places; see
`.claude/memory/domain-knowledge.md`). Hand this agent a specific question, not a vague "explain
the codebase" — it should report findings with file:line references, not opinions.

### `architect` (`.claude/agents/architect.md`)
Owns decisions about where new functionality belongs relative to this repo's layering
(`controller` → `service` → `repository`/`entity`, DTOs at the controller boundary only) and
whether a new concept needs a new entity/table versus fitting into an existing one. Reviews for
consistency with existing patterns (e.g. the CSV-import convention in `WorkspacePairService` and
`AppUserService.importCsv`, the DTO-per-request-shape convention). Escalate to the human for any
schema change that isn't additive (Hibernate's `ddl-auto=update` will silently drop columns/tables
it no longer sees a mapping for).

### `code-reviewer` (`.claude/agents/code-reviewer.md`)
Project-specific review pass focused on things generic review wouldn't catch: sign-off sequence
invariants, `SignOffStatus` "done" vs "pending" semantics (a real bug fixed this session — see
`.claude/memory/decisions.md`), DTO/entity boundary leaks, and consistency between the two
duplicated `APPROVAL_SEQUENCE` constants. Run this **in addition to**, not instead of, gstack's
`/review`.

## Handoff Process

1. Identify which concern the task touches (see table above).
2. If it's general (bugs, style, simplification, QA, security sweep, shipping) → use the matching
   gstack command per `CLAUDE.md`'s Skill routing.
3. If it's one of the project-specific concerns above → invoke the matching agent directly, or run
   the relevant `.claude/workflows/*.md` which sequences gstack commands and these agents together.
4. An agent that finds an issue outside its own lane should say so explicitly and name which other
   agent/gstack command should pick it up — not silently fix it out of scope.

## Escalation Rules

Escalate to a human (don't just proceed) when:

- A change touches `SecurityConfig`, the `AZURE_CLIENT_ID` fallback behavior, or any
  `.access(...)` authorization rule.
- A schema change would drop or rename an existing column/table under `ddl-auto=update`.
- A fix would change the sign-off `APPROVAL_SEQUENCE` ordering or role set.
- Anything that would require initializing git or setting up CI for the first time — that's a
  one-time decision for the team, not something to do unprompted mid-task.

## Ownership Boundaries

No agent owns another agent's lane by default. `architect` decides *where* code goes;
`code-reviewer` decides whether what's there is *correct*; `security-reviewer` has veto power on
anything auth-related regardless of which other agent touched it; `test-writer` only adds tests,
never changes production logic to make a test pass without flagging it as a real bug fix first.
