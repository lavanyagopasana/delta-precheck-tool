# Project Context

## Who this is for

An internal CloudFuze tool used by a Content/Email/Message migration team to track pre-migration
checklist compliance and approval sign-off before a customer's data migration ("Delta") is
initiated. Users fall into the roles in `AppUserRole`: `ADMIN` (manages who can access the app and
what role they have), `MIGRATION_MANAGER` (first approver in the sign-off chain, usually owns a
project), `DEV_LEAD` and `QA_LEAD` (second and third approvers), and `MIGRATION_ENGINEER` (does the
day-to-day pre-check work — importing servers/pairs, filling out checklists).

## Business purpose

Each customer engagement is a **Project**. A project has one or more **Servers** (a migration
batch), each with a CSV-imported list of **workspace pairs** (source account/path →
destination account/path — e.g. `jane@old-tenant.com` → `jane@new-tenant.com`). Before a server's
actual data migration can be kicked off, its pre-migration checklist must be fully completed with
evidence, submitted for review, and pass a sequential three-role sign-off
(Migration Manager → Dev Lead → QA Lead). Escalations track problems that come up along the way,
each tied to a real ticket number (the sidebar calls this feature "Jira Tickets Tracking").

## Current state (as of this scaffold)

- **Not yet a git repository** — no `.git/` directory exists. No CI/CD is configured.
- **No automated tests exist** in either backend or frontend, despite both toolchains being wired
  up for them (`spring-boot-starter-test`, `react-scripts test`).
- **Auth is optional and currently loosely configured**: `AZURE_REQUIRE_ALLOWLIST=false` (any
  valid Microsoft account can use the app regardless of the `app_users` allowlist) and
  `AZURE_ALLOWED_EMAIL_DOMAIN` is blank (no domain restriction), both explicitly "temporarily off
  while testing" per `application.properties`'s own comments. Don't assume these represent the
  intended production posture — they're a deliberate loosening for active development.
- The app is being actively developed and manually tested against a real local MySQL database with
  real-looking (if not real) project/server data — see `.claude/memory/progress.md` for what's been
  touched recently.

## Who to ask about what

This file doesn't name specific people (that context lives outside the repo) — but the roster of
who currently holds which role is queryable live via `GET /api/roster` or the Admin → Manage Access
page, and is the authoritative source, not this file.

## Documents alongside the code

- `Delta-Migration-Workflow-Flowchart.png` and `Delta-Migration-Workflow-and-Access.pdf` at the
  repo root — process documentation for the human workflow this app supports. Not verified against
  the current code as part of this scaffold; treat with the same caution as `README.md` (see
  `.claude/memory/domain-knowledge.md`) until someone confirms they're current.
