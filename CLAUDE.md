# Delta Pre-Check Tool

A full-stack internal tool (CloudFuze) for tracking pre-migration checklist compliance and
approval sign-off across Content/Email/Message migration projects. Each **Project** (e.g. a
customer engagement) contains **Servers**; each Server has a CSV-imported list of **workspace
pairs** (source → destination mailbox/drive accounts), a single server-wide pre-check checklist,
and a sequential three-role sign-off chain (Migration Manager → Dev Lead → QA Lead) that must
fully resolve before the server's data migration ("Delta") can be initiated.

## Prerequisites — Install gstack once on your machine

This project uses **gstack** for AI-assisted development (code review, QA, security audits, docs, deployment). Every contributor must install gstack **once** on their own machine before using Claude Code on this repo.

**Requirements:** Claude Code, Git, Node.js 18+ ([nodejs.org](https://nodejs.org) LTS). Bun is installed automatically by gstack's setup.

**Windows users:** you must use **Git Bash** (comes with Git for Windows). PowerShell and CMD will NOT work.

### Fastest install — paste this to Claude Code

Open Claude Code (from anywhere on your machine) and paste this exact message:

> Install gstack: run `git clone --single-branch --depth 1 https://github.com/garrytan/gstack.git ~/.claude/skills/gstack && cd ~/.claude/skills/gstack && ./setup` then confirm the skills are available by listing `~/.claude/skills/`.

Claude will clone the repo, run setup, and verify. Takes ~60 seconds.

### Manual install

```bash
git clone --single-branch --depth 1 https://github.com/garrytan/gstack.git ~/.claude/skills/gstack
cd ~/.claude/skills/gstack
./setup
```

### Verify it works

Reopen this project in Claude Code and type `/office-hours` — if Claude responds with the office-hours flow, gstack is working.

### Update gstack later

Inside any Claude Code session, run `/gstack-upgrade`.

### Troubleshooting

| Problem | Fix |
|---|---|
| `/office-hours` not recognized | `cd ~/.claude/skills/gstack && ./setup` |
| Windows: `bad interpreter: /bin/bash^M` | `cd ~/.claude/skills/gstack && git config core.autocrlf false && git config core.eol lf && git rm --cached -r . && git reset --hard HEAD && ./setup` |
| `/browse` fails | `cd ~/.claude/skills/gstack && bun install && bun run build` |

## Tech Stack

- **Backend**: Java 21, Spring Boot 3.3.4 (Web, Data JPA, Validation, Security, OAuth2 Resource Server, Mail), Maven, Lombok, Hibernate (`ddl-auto=update` — **no Flyway/Liquibase**, schema evolves by annotation changes only)
- **Database**: PostgreSQL (`org.postgresql:postgresql`). Migrated from MySQL 8 on 2026-08-10 — no
  MySQL-specific SQL existed anywhere (both hand-written `@Query`s are plain portable JPQL, and the
  one raw `columnDefinition` fragment is ANSI-compatible), so the swap was driver + connection
  config only, no entity/query changes. Unlike MySQL's connection string, Postgres has no
  `createDatabaseIfNotExist` equivalent — the target database must exist before first connect (see
  the `DB_URL` row below).
- **Frontend**: React 18 (Create React App / `react-scripts` 5), `react-router-dom` 6, `axios`, `@azure/msal-browser` + `@azure/msal-react`
- **Auth**: Microsoft Entra ID (Azure AD), single-tenant app registration (client ID + tenant ID come from the environment — `application.properties` holds only placeholders, see Environment Variables). The frontend deliberately sends the **ID token** (not an access token) as the bearer credential — see `frontend/src/auth/getAccessToken.js` for why. Auth can still be disabled entirely by explicitly overriding `AZURE_CLIENT_ID` to blank (the backend then runs fully open, `permitAll`, no login screen) — but that's no longer the default state.
- **File storage**: local filesystem (`backend/uploads`), served at `/uploads/**`
- **Email**: SMTP via Spring Mail, defaults to Office 365's relay (`smtp.office365.com`)
- **Tests**: a real suite exists on both sides — **backend 22 files / 156 tests** (`mvn -o test`, JUnit 5 + Mockito + `@SpringBootTest`, H2 via `application-test.properties`) and **frontend 4 files / 26 tests** (`CI=true npx react-scripts test --watchAll=false`, Jest + React Testing Library). Both were green as of 2026-08-12. Note the characterization gate: `EndpointCharacterizationTest` STRICT-compares endpoint JSON against committed golden files in `backend/src/test/resources/snapshots/`. When you intentionally add a DTO field, that test fails with `Unexpected: <field>` — delete the affected snapshot, re-run to regenerate the baseline (it fails loudly the first time by design), **review the diff to confirm it's purely additive**, then re-run to lock it in.
- **CI/CD**: none configured (no `.github/workflows`), so nothing runs those suites automatically — run both locally before opening a PR.

## Architecture Summary

```
Project (1) ──< Server (N) ──< WorkspaceCombination (N)   ["Teams to Slack" — the real unit of work]
                     │                  │
                     │                  ├──< PreCheckItem (N)        [checklist, per COMBINATION]
                     │                  ├──1 PreCheckSubmission      [NOT_STARTED → SUBMITTED]
                     │                  ├──< SignOff (3: MM, Dev, QA) [chain, auto-created on submit]
                     │                  └──< DeltaCycle (N)          [immutable per-cycle history]
                     │
                     └──< WorkspacePair (N)   [CSV-imported rows; FK is to SERVER, and the owning
                                               combination is matched by NAME string, not a FK]

AppUser (email → role)  ── independent of Azure AD identity; drives all authorization
   └──1 Team (nullable)  ── which engineers a Migration Manager may assign to a project
```

**The pre-check and sign-off chain are per `WorkspaceCombination`, not per `Server`.** A server
holds several combinations (each a source→destination product pairing) and each runs its own
checklist, submission, chain, and Delta lifecycle independently. Routes are
`/api/combinations/{id}/...` accordingly. `WorkspacePair` is the exception — it hangs off `Server`
and is tied to its combination by a plain name string (`ServerService.sameCombination`), so renaming
a combination without migrating its pairs orphans them.

Submitting (`PreCheckSubmissionService.submit`) requires a Migration Manager on the project and
every item to have a status, plus evidence and a note — except `DELTA_TYPE_ITEM`, which is
deliberately exempt from the evidence/note requirements, and pre-delta-migration items, which are
exempt unless that migration is required. It then auto-creates all three `SignOff` rows
(`SignOffService.createChainIfAbsent`), all `PENDING`. Approval is strictly sequential
(`SignOffRole.APPROVAL_SEQUENCE` — a single shared constant now, no longer duplicated in
`ProjectService`): only the role whose turn it is may act, though **`ADMIN` can override any step**
(`SignOffService.isEligible`). The Dev Lead decides at approval time whether QA Lead is required —
saying no auto-`SKIP`s the QA row and marks the combination Delta Ready immediately.

**Declining does NOT hand back one step.** `SignOffService.decline` →
`DeltaCycleService.recordDeclineAndRollOver` snapshots the whole attempt (every item's status, note
and evidence path, plus the full chain outcome) into a `DeltaCycle`, then **rolls over**: the live
checklist is wiped back to unfilled, `submittedBy`/`submittedAt`/`startedByEmail` are cleared, the
live `SignOff` rows are deleted, and `currentCycleNumber` is incremented. Nothing is lost — the
history holds it — but somebody has to fill the entire checklist in again. A combination sitting at
`currentCycleNumber: 2` with `submissionStatus: NOT_STARTED` and `completedCycleCount: 0` is the
normal post-decline state, not a bug (`completedCycleCount` only counts cycles whose status is
`COMPLETED`, and a declined cycle never is).

**Teams scope the engineer picker.** `Team` is a real entity; membership is `AppUser.team` (nullable)
and who *manages* a team is derived from each member's `role`, never stored twice. A team can have
more than one Migration Manager (Teams 5 and 6 in the real roster do), and both see the same engineer
pool. `GET /api/roster` carries `engineersByManager` (manager email → their team's engineer emails)
alongside the flat lists; `ProjectDetailsPage` scopes its picker with
`engineersByManager[manager] ?? engineers`. **An absent key deliberately means "show everyone",** not
"show nobody" — a strict filter would leave an empty dropdown and block assignment with nothing on
screen explaining why. Team reads are open to any allowlisted caller; every write is ADMIN-only,
because team membership decides which engineers a manager can assign and a manager editing teams
could widen their own pool. Seed data and the three corrected email addresses: `docs/seed/`.

Authorization is a separate concern from authentication: any valid Microsoft account can sign in,
but `AppUserService` (backed by the `app_users` table) decides what they can actually do via
`AppUserRole` (`ADMIN`, `MIGRATION_MANAGER`, `DEV_LEAD`, `QA_LEAD`, `MIGRATION_ENGINEER`). Anyone
signing in with the auto-provision domain (default `cloudfuze.com`) is silently added as
`MIGRATION_ENGINEER` on first sight — everyone else needs an admin to add them.

## Critical Constraints

- **`AZURE_CLIENT_ID`/`AZURE_TENANT_ID` come from the environment, not from this repository.** The
  real pair lives in `.env` on the server, and in GitHub Settings → Variables when `MANAGE_ENV` is
  on. `application.properties` holds all-zero GUID placeholders. The registration they identify is
  "Delta Migration Readiness Tracker", single-tenant ("Accounts in this organizational directory
  only — cloudfuze.com"), SPA platform, delegated scopes only, no client secret. Confirmed working
  end-to-end against the deployed site on 2026-08-12.
  - **The real values were committed here from 2026-08-11 to 2026-08-19 and were then removed**,
    because this repository is public. Neither is a credential — both are compiled into the
    JavaScript bundle and served to every visitor, and the thing guarding sign-in is Microsoft's own
    password/MFA check — but a public file naming the tenant to aim at buys nothing. They remain in
    git history; removing them from there would mean rewriting it. **Don't paste them back in.**
  - **The placeholders are all-zero GUIDs, not blank, and that matters.** `SecurityConfig` reads
    `@Value("${azure.client-id:}")` with its own *empty* fallback, so deleting the property lines
    would not fail loudly — it would resolve to blank, `authConfigured()` would return false, and
    every `/api/**` route would become `permitAll`. A non-blank placeholder keeps auth enabled, so a
    deployment that forgets the variable rejects every token instead of admitting everyone.
  - Consequence: running locally without `AZURE_CLIENT_ID` set no longer lets you sign in. Set the
    variable, or set it explicitly blank to run open on purpose.
  - **Do NOT reintroduce client ID `4145c1b2-a596-4d84-bede-6e2ca276c9c7` / tenant ID
    `807d6772-847c-40e2-9bec-e2c930b3a42e`.** This file previously recorded that pair as "confirmed
    with the team" — it is **wrong** and can never work for `@cloudfuze.com` accounts. It belongs to
    a directory whose real Entra ID name is `filefuze`; signing in produces *"Selected user account
    does not exist in tenant 'filefuze' and cannot access the application '4145c1b2-…'"*. Tenant
    membership follows which directory an account was created in, not the email domain suffix, so no
    app-side config can rescue it.
  - Neither value is a secret (both are public OAuth identifiers, not credentials), so baking them
    in as defaults is safe — and it closes a footgun that bit this project repeatedly before: when
    these had to be exported manually every backend restart, forgetting silently fell back to
    fully-open `permitAll` mode (`/api/me` would return `{email: null, role: null, allowed: true}`
    for everyone) rather than failing loudly. If auth ever looks broken again, the first thing to
    check is whether something is now *explicitly* overriding `AZURE_CLIENT_ID` to blank (the only
    way it can degrade now) — see `.claude/memory/decisions.md`.
- **`README.md` was rewritten on 2026-07-27 to match the actual current code** (server-level
  pre-check/sign-off, manually-created escalations, the real CSV column aliases, current env var
  list). If it and the entities/services (`.claude/memory/domain-knowledge.md`) ever disagree
  again, trust the code — but as of this rewrite there's no known discrepancy.
- **Tests exist and are the pattern to copy** — backend 22 files / 166 `@Test` methods, frontend 7
  files / 53 tests (frontend counts verified 2026-08-18 by running the suite; backend counted from
  source, not re-run). This bullet previously read "No tests exist", which contradicted the Tech
  Stack section two screens above it and was wrong for long enough to be worth calling out. Look at
  a neighbouring test before writing a new one; `.claude/rules/testing-standard.md` covers the areas
  still uncovered.
- **This is a real git repo**, pushed to `github.com/lavanyagopasana/delta-precheck-tool` (`main`
  branch) — normal git workflows apply.
- **CORS origins are env-driven** (`SecurityConfig.java`, `app.allowed-origins`/`APP_ALLOWED_ORIGINS`)
  — defaults to `http://localhost:3000`; set the env var to a comma-separated list to add a deployed
  frontend origin, no code change needed. Owned by `SecurityConfig` (via a `CorsConfigurationSource`
  wired into the filter chain), not `WebConfig` — a `WebMvcConfigurer`-only registration never runs
  for requests Spring Security itself rejects (401/403).
- **Hibernate `ddl-auto=update`** means schema changes happen by editing `@Entity` annotations directly
  — there is no migration file to write or review. **But `update` only ADDS; it never alters or drops.**
  The trap this hides: Hibernate 6 generates a `CHECK` constraint for every
  `@Enumerated(EnumType.STRING)` column listing that enum's values, and `update` does **not** rewrite
  it when you add a value. So adding an enum constant is a schema change that silently does not get
  applied — it works on every freshly created database (local, tests, CI) and fails only on a
  long-lived one, i.e. production, where it surfaces as
  `DataIntegrityViolationException` → *"That conflicts with an existing record."* This actually
  happened: `ba0bf01` added `NOT_APPLICABLE`/`UP_TO_DATE` to `ItemStatus` and the deployed app could
  not save the Hyperlinks Verified or Drive changes items. `EnumCheckConstraintSync` now repairs every
  enum column's constraint from the Java enum at startup, so adding a value is safe again — but the
  general rule stands: **an `update`-mode schema is only as current as the parts Hibernate is willing
  to alter.**
- The approval sequence (`MIGRATION_LEAD, DEV_LEAD, QA_LEAD`) is defined as an identical constant in **two** places (`SignOffService` and `ProjectService`) — keep both in sync if it ever changes.

## Repository Navigation

```
backend/src/main/java/com/cloudfuze/deltatracker/
├── controller/   13 REST controllers (Admin, Dashboard, Escalation, Me, PreCheckItem,
│                 PreCheckSubmission, Project, Roster, Server, SignOff, SignOffApproval, Upload,
│                 WorkspacePair)
├── service/      Business logic — one service per aggregate (see Architecture Summary)
├── entity/       JPA entities — the ground truth for the data model (trust these over README)
├── repository/   Spring Data JPA repositories
├── dto/          Request/response shapes — controllers never return entities directly
├── config/       SecurityConfig (JWT/CORS/auth-gating), WebConfig (/uploads static serving)
├── exception/    ApiException, EvidenceRequiredException, GlobalExceptionHandler (uniform error JSON)
├── seed/         AdminBootstrap — seeds the first ADMIN row on an empty app_users table
└── util/         CsvUtils (hand-rolled CSV line parser), JwtEmailUtil

frontend/src/
├── pages/        One file per route (Dashboard, Projects, ProjectDetails, Approvals, Escalations,
│                 ServerPreCheck, AdminUsers, Login)
├── components/   Shared UI (DataTable, Modal, Toast, NavBar, StatusBadge, EngineerChecklist,
│                 CsvImportPanel, WorkspacePairsPanel, PreCheckPanel, AttachmentPreview)
├── auth/         MSAL setup (authConfig, msalInstance, getAccessToken, CurrentUserContext)
└── api/client.js Single axios instance + every API call the frontend makes (the practical API map)
```

## Memory Files

- [`.claude/memory/project-context.md`](.claude/memory/project-context.md) — who this is for, business purpose, current state
- [`.claude/memory/architecture.md`](.claude/memory/architecture.md) — deeper architecture notes than fit above
- [`.claude/memory/domain-knowledge.md`](.claude/memory/domain-knowledge.md) — the real business rules, verified against entities/services
- [`.claude/memory/decisions.md`](.claude/memory/decisions.md) — why things are the way they are, including recent fixes
- [`.claude/memory/repository-map.md`](.claude/memory/repository-map.md) — full file-by-file map
- [`.claude/memory/progress.md`](.claude/memory/progress.md) — what's done, in-flight, and known gaps

## Environment Variables

| Variable | Default | Notes |
|---|---|---|
| `SERVER_PORT` (or `PORT`) | `8081` | Listen port. `PORT` is checked second, so a PaaS that injects it works unchanged |
| `APP_FIRST_ADMIN_EMAIL` | `first.admin@yourdomain.com` (placeholder) | The single `ADMIN` row `AdminBootstrap` seeds **only** into a completely empty `app_users` table. Set this per deployment; blank skips seeding (and logs a warning, since nobody can then sign in) |
| `DB_URL` | `jdbc:postgresql://localhost:5432/delta_migration_tracker` | Database must already exist — run `createdb delta_migration_tracker` once; tables/columns are still created automatically |
| `DB_USERNAME` | `postgres` | |
| `DB_PASSWORD` | `postgres` | |
| `AZURE_CLIENT_ID` | `00000000-...-000000000000` (placeholder) | **Must be set per environment** — the real value is in `.env`/GitHub Settings, never in this repo. The placeholder keeps auth enabled so a forgotten value fails loudly; setting it blank disables auth entirely |
| `AZURE_TENANT_ID` | `00000000-...-000000000000` (placeholder) | **Must be set per environment.** Single-tenant — only accounts in that Entra ID tenant can authenticate at all |
| `AZURE_ALLOWED_EMAIL_DOMAIN` | *(blank)* | Currently unset for testing; set to `cloudfuze.com` to restrict sign-in |
| `AZURE_REQUIRE_ALLOWLIST` | `true` | Only people added under Manage Access can sign in. Set `false` only for local testing with unregistered accounts |
| `AZURE_AUTO_PROVISION_DOMAIN` | `cloudfuze.com` | Auto-added as `MIGRATION_ENGINEER` on first sign-in |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` / `SMTP_PASSWORD` | `smtp.office365.com` / `587` / `leo@fuzebot.io` / *(blank)* | Blank password disables sending (logs a warning) |
| `PMO_BASE_URL` | `https://neutarapm.cftools.live` | Root of the PMO tool (Neutara PM), whose project list is mirrored into this one. **No port** — their docs say `:3001`, but that is their internal app port; publicly it sits behind nginx on 443 and the port form times out |
| `PMO_API_KEY` | *(blank)* | Sent as the `X-API-Key` header. **A real secret** — environment only, never in the repo. Blank disables the sync entirely (poll becomes a no-op, manual trigger returns "isn't configured"). A `503` from that endpoint means PMO's *own* `EXTERNAL_API_KEY` is unset on their server, not that this key is wrong |
| `PMO_IMPORT_STATUSES` | `ACTIVE` | Comma-separated PMO statuses to import. ACTIVE only by product decision — of 190 PMO projects 105 are `COMPLETED` and 1 `CANCELLED`, which can never need a pre-check. Blank imports every status |
| `PMO_IMPORT_PHASES` | `DELTA` | Comma-separated PMO phases the poll imports. DELTA only — this tool is a pre-Delta readiness checklist, so a project in KICKOFF or PILOT_MIGRATION has no pre-check to fill in yet and arrives on its own the first poll after PMO advances it. PMO's phases: KICKOFF, PILOT_MIGRATION, ONETIME_MIGRATION, DELTA, FINAL_VALIDATION, CLOSURE, COMPLETED. Blank imports every phase — **which is what the poll did before 2026-08-29, and why the deployed database holds projects in every phase**. **Gates creation only**: a project already mirrored here keeps syncing as it moves past Delta, so its PMO Phase label stays current (that label is what tells an admin which rows are stale). Nothing here deletes — clearing out the pre-filter imports is a deliberate human action |
| `PMO_AUTO_SYNC_ENABLED` | `true` | Runs the batch poll as a reconciliation pass alongside the Delta-phase webhook. Was `false` when the webhook landed, on the reasoning that the webhook is the one arrival path; back on since 2026-08-29 because the webhook is a single delivery with no retry on PMO's side and refuses every call while `PMO_WEBHOOK_API_KEY` is unset — correctly, but invisibly from our end. The poll notices either. Both paths share the same per-project logic, so a project is identical whichever created it. The admin-triggered `POST /api/pmo/sync` stays available regardless of this flag |
| `PMO_SYNC_INTERVAL_MS` / `PMO_SYNC_INITIAL_DELAY_MS` | `300000` / `60000` | Only relevant if `PMO_AUTO_SYNC_ENABLED=true`. Poll every 5 min, first run 1 min after boot |
| `PMO_WEBHOOK_API_KEY` | *(blank)* | `POST /api/webhooks/pmo/delta-phase` — PMO Tracker's real-time notification the instant a project moves into Delta phase (its manager and that manager's whole team are attached automatically, same as the poll does). Checked against the `X-API-Key` header PMO sends. **A real secret** — environment only. Blank refuses every call (503) rather than leaving the endpoint silently open. Deliberately a SEPARATE credential from `PMO_API_KEY` even if PMO's side is configured with the identical value — one is the key *we* send *them*, this is the key *they* must send *us* |
| `METABASE_BASE_URL` | `https://metabase.cloudfuze.com` | Root of the Metabase site, no path. The migration data itself lives here, one database per customer engagement (`/browse/databases`) |
| `METABASE_API_KEY` | *(blank)* | Sent as the `x-api-key` header. **A real secret** — environment only. Metabase 0.49+, created under Admin → Authentication → API keys. Preferred over username/password: scoped, revocable, and keeps a human's password out of the environment |
| `METABASE_USERNAME` / `METABASE_PASSWORD` | *(blank)* | Fallback auth — posted to `/api/session` for a token sent as `X-Metabase-Session` (cached in memory, re-fetched on a 401). **Real secrets.** Only needed because API keys are an admin-enabled feature that may be off; if `METABASE_API_KEY` is set, it wins. Will not work for an SSO-only or MFA-enabled account — use an API key there |
| `APP_FRONTEND_URL` | `http://localhost:3000` | Used to build links in outgoing emails |
| `APP_ALLOWED_ORIGINS` | `http://localhost:3000` | Comma-separated CORS allowlist for `/api/**` (`SecurityConfig`) — add the deployed frontend's origin here |
| (frontend) `REACT_APP_AZURE_CLIENT_ID` | — | Set in `frontend/.env.local`, same value as backend's `AZURE_CLIENT_ID` |
| (frontend) `REACT_APP_AZURE_TENANT_ID` | — | Set in `frontend/.env.local`, same value as backend's `AZURE_TENANT_ID` |
| (frontend) `REACT_APP_ALLOWED_EMAIL_DOMAIN` | *(blank)* | |
| (frontend) `REACT_APP_API_BASE` | `http://localhost:8081` | Backend origin, no `/api` suffix, no trailing slash — set for a deployed backend |
| (frontend) `REACT_APP_HOTJAR_SITE_ID` | *(blank)* | Hotjar Site ID, digits only (site `DeltaPrechecks`). Blank disables Hotjar entirely — no script requested, which is the intended state locally. Baked into the bundle at build time like every `REACT_APP_*`, so the image needs `--build-arg REACT_APP_HOTJAR_SITE_ID=...` (the `ARG` is declared in `frontend/Dockerfile`); `docker run -e` is silently ignored. `hotjarSiteId` in `frontend/public/runtime-config.js` stays blank and exists only as a post-build override to turn recording ON for a bundle built without it. See `frontend/src/analytics/hotjar.js` |

## Common Commands

```bash
# Backend (run from backend/)
mvn spring-boot:run                    # start API on :8081 — auth config is now baked in, no env var needed
mvn -o -q compile                      # fast offline compile check

# Frontend (run from frontend/)
npm start                              # start dev server on :3000
npm run build                          # production build
```

## Available gstack Commands

gstack is installed globally at `~/.claude/skills/gstack`. Use `/browse` from gstack for all web browsing; never use `mcp__claude-in-chrome__*` tools.

- **Planning:** `/office-hours`, `/autoplan`, `/spec`, `/plan-ceo-review`, `/plan-eng-review`, `/plan-design-review`
- **Review & investigate:** `/review`, `/investigate`, `/codex`
- **Testing:** `/qa <url>`, `/qa-only <url>`, `/browse`, `/open-gstack-browser`
- **Security & docs:** `/cso`, `/document-release`, `/document-generate`
- **Ship & deploy:** `/ship`, `/land-and-deploy`, `/canary`
- **Safety:** `/careful`, `/freeze`, `/guard`, `/unfreeze`
- **Learn & upgrade:** `/learn`, `/gstack-upgrade`

This project's own `/team-review` (`.claude/commands/team-review.md`) is a **project-specific**
checklist (sign-off chain invariants, DTO/entity boundary, CSV parsing conventions) — run it
alongside gstack's `/review`, not instead of it.

## Recommended Workflow

- **New feature:** `/office-hours` → `/autoplan` → implement → `/review` → `/qa` → `/cso` → `/ship`
- **Routine change:** implement → `/review` → `/qa` → `/ship`
- **Bug fix:** `/investigate` → fix → `/review` → `/qa` → `/ship`

Before every PR (never skip):

1. `/review` — bugs CI won't catch
2. `/qa <staging-url>` — real browser test
3. `/cso` — security audit (if security-sensitive)
4. `/ship` — opens PR

## Pre-flight — gstack availability check

Before offering the Skill routing menu OR running any gstack slash command, Claude MUST first verify gstack is installed:

```bash
test -f ~/.claude/skills/gstack/setup && echo "gstack_installed" || echo "gstack_missing"
```

- `gstack_installed` → show Menu A below
- `gstack_missing` → show Menu B below

Install command (used when the user chooses "Install gstack now"):

```bash
git clone --single-branch --depth 1 https://github.com/garrytan/gstack.git ~/.claude/skills/gstack && cd ~/.claude/skills/gstack && ./setup
```

After install, tell the user: "✅ gstack installed. Reopen this project in Claude Code so the new skills are discovered. Then re-ask your original question."

If install fails, report the error, suggest the manual install, and fall back to the normal project approach.

## Skill routing

Before any repository task, Claude must run the Pre-flight check and show the correct menu.

**Menu A — gstack IS installed**

"Before I start, choose one:
1. Use gstack workflow
2. Use normal project files / plain Claude approach
3. Let Claude recommend the best option first"

**Menu B — gstack is NOT installed**

"gstack is not installed on your machine. Before I start, choose one:
1. Install gstack now (~60 seconds), then use gstack workflow
2. Use normal project files / plain Claude approach (no gstack workflows available)
3. Let Claude recommend the best option first"

The install option MUST appear on every question until gstack is installed — not just the first time.

**Slash command exception:** if the user types a gstack slash command (`/review`, `/qa`, `/cso`, `/ship`, `/office-hours`, etc.) directly, run the Pre-flight check first. If installed, run the command directly. If not, show Menu B.

Claude must wait for the user's selection before reading files, editing files, or invoking any skill.

**Option 1 (Menu A) — Use gstack workflow**

Mappings:
- Product brainstorm / feature ideas → `/office-hours`
- Rough idea to spec → `/spec`
- Scope tradeoffs → `/plan-ceo-review`
- New-feature architecture → `/plan-eng-review`
- Bugs / unexpected errors → `/investigate`
- Test a URL → `/qa` or `/qa-only`
- Diff review before land → `/review`
- Security-sensitive change → `/cso`
- Open a PR → `/ship`
- Deploy / verify prod → `/land-and-deploy`
- Docs update → `/document-release`
- Docs generation → `/document-generate`

**Option 1 (Menu B) — Install gstack now**

Run the install command. On success, tell the user to reopen the project. On failure, fall back to Option 2.

**Option 2 — Use normal project files / plain Claude approach**

Reading files, explaining code, small edits, typo fixes, one-file updates, basic refactoring, config changes, project Q&A, checking implementation details.

**Option 3 — Let Claude recommend**

If gstack installed → recommend between gstack workflow and normal approach. If gstack missing → recommend between installing gstack (for tasks that need it) or normal approach (for small tasks).
