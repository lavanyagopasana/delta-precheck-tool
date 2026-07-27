# Architecture

Deeper notes than fit in `CLAUDE.md`'s Architecture Summary or `.claude/skills/architecture/SKILL.md`.
Read those first; this is the "why," not the "what."

## Why auth is structured as two independent layers

Authentication (`SecurityConfig`, Microsoft Entra ID JWT validation) and authorization
(`AppUserService` + `app_users` table) are deliberately decoupled. The reasoning, inferred from the
code and comments: this app is used across multiple organizations/domains (the `app_users` table
has real rows for `@cloudfuze.com`, `@filefuze.co`, and `@fuzebot.io` addresses), and the Azure AD
app registration is multi-tenant specifically so anyone with a Microsoft work/school account can
authenticate — without needing CloudFuze's own tenant admin to approve an enterprise app
registration. Actual access control is then layered on top entirely within this app's own
database, via the allowlist. This is why `azure.allowed-email-domain` and
`azure.require-allowlist` are separate, independently-toggleable settings rather than one combined
"is this person allowed" check.

## Why the sign-off chain is created all-at-once, not step-by-step

`SignOffService.createChainIfAbsent` creates all three `SignOff` rows (`MIGRATION_LEAD`,
`DEV_LEAD`, `QA_LEAD`) in one call, all `PENDING`, rather than creating each role's row only when
it becomes that role's turn. This means a `SignOff` row's mere existence tells you "the chain has
started for this server," not "it's this role's turn" or "this role has decided." Whose turn it
actually is is determined dynamically (`requireTurn`, and the frontend's
`ApprovalsPage.primaryRowFor`/`OverallStepper`), by walking `APPROVAL_SEQUENCE` and checking which
earlier roles have resolved. **This is the single most common source of confusion in this
codebase** — see `.claude/memory/decisions.md` for the concrete bug it caused.

## Why Dev Lead decides whether QA is required, at approval time

`SignOffService.approve` takes a `qaRequired` parameter only meaningful when `role == DEV_LEAD`.
This isn't a project-level or server-level setting decided in advance — it's a judgment call made
by whichever Dev Lead approves, at the moment they approve, presumably because whether QA sign-off
is warranted depends on specifics of that particular server's migration that aren't known ahead of
time. If `qaRequired` is `false`, the QA row is set to `SKIPPED` (not `APPROVED` — a real
distinction, since `SKIPPED` and `APPROVED` are both treated as "resolved" for readiness purposes
but are semantically different) and the Delta is finalized immediately.

## Why CSV import is the primary data-entry mechanism

There's no UI form to manually create a `Server` or `WorkspacePair` one at a time with all their
fields — both are populated by importing a CSV (`WorkspacePairService.importCsvGlobal` creates the
`Server` too, if it doesn't already exist by name within the project). This suggests the expected
usage pattern is: migration engineers already have this data in a spreadsheet (from wherever the
source/destination account mapping was worked out), and the app is built around ingesting that
directly rather than re-keying it. `AppUserService.importCsv` (added this session) follows the same
philosophy for bulk-adding users to the allowlist.

## Why there's no service-layer abstraction for "current user"

Every service method that needs to know who's calling takes the caller's email as an explicit
parameter (extracted in the controller via `JwtEmailUtil.extractEmail`), rather than the service
layer reaching into a request-scoped "current user" bean or `SecurityContextHolder` itself. This
keeps services testable without a full Spring Security context — a deliberate design choice worth
preserving (see `.claude/rules/testing-standard.md`'s recommendation to unit-test services by
constructing them directly with mocked dependencies).

## Why Hibernate `ddl-auto=update` instead of a migration tool

No Flyway/Liquibase dependency exists. Schema changes happen purely by changing `@Entity`
annotations and letting Hibernate reconcile the schema on next startup. This is fast for active
development but has no rollback story and can silently drop columns/tables it no longer sees a
mapping for — see `.claude/rules/architecture-boundaries.md` and `.claude/agents/architect.md` for
the guardrail this implies (escalate non-additive schema changes to a human).
