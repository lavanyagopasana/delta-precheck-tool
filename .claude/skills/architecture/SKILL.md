---
name: architecture
description: This project's specific layering, aggregate boundaries, and where new concepts belong (controller/service/repository/entity, DTO boundary, the Project→Server→WorkspacePair/PreCheckItem/SignOff shape). Not a general software-architecture skill.
---

# Architecture (project-specific)

Load this skill when deciding where new code should live, whether a new concept needs a new
entity, or when tracing how a request flows through this specific codebase. This is the detailed
version of `.claude/rules/architecture-boundaries.md` and the "Architecture Summary" in `CLAUDE.md`
— read those first for the compact version.

## The actual data shape (verified against entities, not README)

```
Project (1) ──< Server (N) ──< WorkspacePair (N)     [pure CSV-imported data rows]
                     │
                     ├──< PreCheckItem (N)             [flat server-wide checklist]
                     ├──1 PreCheckSubmission            [one per server, NOT_STARTED→DRAFT→SUBMITTED]
                     ├──< SignOff (3 rows: MM/Dev/QA)   [sequential chain]
                     └──< Escalation (N)                [manually created, ticketNumber unique]

AppUser (email → AppUserRole)   ── independent of Project/Server; drives authorization only
```

`README.md` describes an **older** model (per-workspace-pair pre-checks split into "Pre-Check 1 /
Pre-Check 2" categories, with automatic escalation on every checkbox toggle). That model is gone
from the code — `PreCheckSubmission` has a unique constraint on `server_id` (one per server, full
stop), and `EscalationService` has no auto-creation logic at all today. Trust this skill and the
entities over the README. See `.claude/memory/domain-knowledge.md` for the complete list of
README/code discrepancies.

## Layering — where logic belongs

`controller → service → repository → entity`, with DTOs at the controller boundary only. See
`.claude/rules/architecture-boundaries.md` for the full rules. The short version: controllers
extract the caller's email and delegate to exactly one service call; services own every business
rule and can call other services; repositories are Spring Data derived-query interfaces with no
custom implementations; entities never serialize directly to a client.

## Deciding where a new concept goes

1. **Extending an existing status/enum** → add the value, then grep for every place that
   exhaustively handles that enum (Java `switch`/`if`-chains and the matching frontend
   label/color mapping) — neither language enforces exhaustiveness here, so it's a manual check.
2. **A new thing that belongs to `Server`** (most likely case — `PreCheckItem`, `WorkspacePair`,
   `SignOff`, `Escalation` are all this shape) → `@ManyToOne(fetch = FetchType.LAZY)` back to
   `Server`, own repository with `findByServerId`, own service, own DTO(s). `Server` itself doesn't
   hold a `@OneToMany` collection of any of these — lookups always go through the child's
   repository, not `server.getXs()`. Match that.
3. **A genuinely new top-level aggregate** (not naturally hanging off `Project`/`Server`) — this is
   the one case worth pausing on. Check `.claude/agents/architect.md`'s guidance and consider
   whether it's actually a field/relationship on something existing before creating a new table.
4. **Cross-cutting logic used by 2+ services** → `util/` package (`CsvUtils`, `JwtEmailUtil` are
   the only two so far — both are genuinely reused, not prematurely extracted).

## Auth is a layer that cuts across all of this

Authentication (is this a valid Microsoft account — `SecurityConfig`) and authorization (what can
this specific person do — `AppUserService` + `app_users` table) are separate and both independent
of the `Project`/`Server` domain model above. A new endpoint always needs a decision in both: does
`SecurityConfig` need a new `.access(...)` rule, and does the business logic itself need to check
the caller's project-level relationship (e.g. `ProjectService.isVisible`'s per-role visibility
rules) on top of the coarse role check? See `.claude/rules/security-rules.md`.

## Known architectural debt (don't "fix" without checking `.claude/memory/decisions.md` first)

- `APPROVAL_SEQUENCE` is duplicated (`SignOffService`, `ProjectService`).
- `normalizeHeader` (CSV header detection) is duplicated (`AppUserService`, `WorkspacePairService`).
- `postgresql` driver is on the backend classpath but unused (MySQL is the actual database) —
  likely leftover from an earlier deploy-target consideration, not an active dual-DB setup.
- CORS is hardcoded to `http://localhost:3000` (`WebConfig`) — no environment-based configuration
  yet for a deployed frontend origin.
