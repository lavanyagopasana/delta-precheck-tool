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
- **Database**: MySQL 8 (`mysql-connector-j`). A `postgresql` driver is also on the classpath but unused today — leftover from an earlier deploy target consideration, not an active dual-DB setup.
- **Frontend**: React 18 (Create React App / `react-scripts` 5), `react-router-dom` 6, `axios`, `@azure/msal-browser` + `@azure/msal-react`
- **Auth**: Microsoft Entra ID (Azure AD), single-tenant app registration (client ID + tenant ID are baked into `application.properties` as defaults — see Environment Variables). The frontend deliberately sends the **ID token** (not an access token) as the bearer credential — see `frontend/src/auth/getAccessToken.js` for why. Auth can still be disabled entirely by explicitly overriding `AZURE_CLIENT_ID` to blank (the backend then runs fully open, `permitAll`, no login screen) — but that's no longer the default state.
- **File storage**: local filesystem (`backend/uploads`), served at `/uploads/**`
- **Email**: SMTP via Spring Mail, defaults to Office 365's relay (`smtp.office365.com`)
- **Tests**: none exist yet in either backend (`spring-boot-starter-test` is on the classpath but `backend/src/test/` doesn't exist) or frontend (no `*.test.js` files). See `.claude/rules/testing-standard.md` before writing the first ones.
- **CI/CD**: none configured (no `.github/workflows`). This also isn't a git repository yet (no `.git/`) — see `.claude/memory/decisions.md`.

## Architecture Summary

```
Project (1) ──< Server (N) ──< WorkspacePair (N)      [CSV-imported source/destination rows, no own status]
                     │
                     ├──< PreCheckItem (N)              [flat server-wide checklist]
                     ├──1 PreCheckSubmission            [NOT_STARTED → DRAFT → SUBMITTED, one per server]
                     └──< SignOff (3: MM, Dev, QA)      [sequential chain, auto-created on submit]

AppUser (email → role)  ── independent of Azure AD identity; drives all authorization
```

Submitting a server's pre-check (`PreCheckSubmissionService.submit`) requires every item to have a
status, evidence file, and note, and a Migration Manager to be assigned to the project — then it
auto-creates all three `SignOff` rows (`SignOffService.createChainIfAbsent`) in one shot, all
`PENDING`. Approval is strictly sequential (`SignOffService.APPROVAL_SEQUENCE`, duplicated in
`ProjectService`): only the role whose turn it is can approve/decline; declining bounces the chain
back one step. The Dev Lead decides at approval time whether QA Lead is required — saying no
auto-`SKIP`s the QA row and finalizes the Delta immediately. Once the whole chain resolves, the
server's `deltaInitiatedAt`/`By` are stamped.

Authorization is a separate concern from authentication: any valid Microsoft account can sign in,
but `AppUserService` (backed by the `app_users` table) decides what they can actually do via
`AppUserRole` (`ADMIN`, `MIGRATION_MANAGER`, `DEV_LEAD`, `QA_LEAD`, `MIGRATION_ENGINEER`). Anyone
signing in with the auto-provision domain (default `cloudfuze.com`) is silently added as
`MIGRATION_ENGINEER` on first sight — everyone else needs an admin to add them.

## Critical Constraints

- **`AZURE_CLIENT_ID`/`AZURE_TENANT_ID` are baked into `application.properties` as defaults**
  (as of 2026-07-27 — client ID `a55e053f-bfe9-4b4a-8b74-362649f82cf0`, tenant ID
  `66d8848d-26b6-4147-8124-127624d7b3a6`; this is now a **single-tenant** registration, not
  multi-tenant as it was originally). Neither value is a secret (both are public OAuth identifiers,
  not credentials), so baking them in as defaults is safe — and it closes a footgun that bit this
  project repeatedly before: when these had to be exported manually every backend restart,
  forgetting silently fell back to fully-open `permitAll` mode (`/api/me` would return
  `{email: null, role: null, allowed: true}` for everyone) rather than failing loudly. If auth ever
  looks broken again, the first thing to check is whether something is now *explicitly* overriding
  `AZURE_CLIENT_ID` to blank (the only way it can degrade now) — see `.claude/memory/decisions.md`.
- **`README.md` was rewritten on 2026-07-27 to match the actual current code** (server-level
  pre-check/sign-off, manually-created escalations, the real CSV column aliases, current env var
  list). If it and the entities/services (`.claude/memory/domain-knowledge.md`) ever disagree
  again, trust the code — but as of this rewrite there's no known discrepancy.
- **No tests exist.** Don't assume a testing pattern is already established; `.claude/rules/testing-standard.md` defines the target standard for new code.
- **This is a real git repo**, pushed to `github.com/lavanyagopasana/delta-precheck-tool` (`main`
  branch) — normal git workflows apply.
- **CORS origins are env-driven** (`SecurityConfig.java`, `app.allowed-origins`/`APP_ALLOWED_ORIGINS`)
  — defaults to `http://localhost:3000`; set the env var to a comma-separated list to add a deployed
  frontend origin, no code change needed. Owned by `SecurityConfig` (via a `CorsConfigurationSource`
  wired into the filter chain), not `WebConfig` — a `WebMvcConfigurer`-only registration never runs
  for requests Spring Security itself rejects (401/403).
- **Hibernate `ddl-auto=update`** means schema changes happen by editing `@Entity` annotations directly — there is no migration file to write or review.
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
| `DB_URL` | `jdbc:mysql://localhost:3306/delta_migration_tracker?...` | DB auto-created on first connect |
| `DB_USERNAME` | `root` | |
| `DB_PASSWORD` | `root` | |
| `AZURE_CLIENT_ID` | `a55e053f-bfe9-4b4a-8b74-362649f82cf0` | Baked in; override only to test a different app registration or to disable auth (set blank) |
| `AZURE_TENANT_ID` | `66d8848d-26b6-4147-8124-127624d7b3a6` | Single-tenant — only accounts in this Entra ID tenant can authenticate at all |
| `AZURE_ALLOWED_EMAIL_DOMAIN` | *(blank)* | Currently unset for testing; set to `cloudfuze.com` to restrict sign-in |
| `AZURE_REQUIRE_ALLOWLIST` | `true` | Only people added under Manage Access can sign in. Set `false` only for local testing with unregistered accounts |
| `AZURE_AUTO_PROVISION_DOMAIN` | `cloudfuze.com` | Auto-added as `MIGRATION_ENGINEER` on first sign-in |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` / `SMTP_PASSWORD` | `smtp.office365.com` / `587` / `leo@fuzebot.io` / *(blank)* | Blank password disables sending (logs a warning) |
| `APP_FRONTEND_URL` | `http://localhost:3000` | Used to build links in outgoing emails |
| `APP_ALLOWED_ORIGINS` | `http://localhost:3000` | Comma-separated CORS allowlist for `/api/**` (`SecurityConfig`) — add the deployed frontend's origin here |
| (frontend) `REACT_APP_AZURE_CLIENT_ID` | — | Set in `frontend/.env.local`, same value as backend's `AZURE_CLIENT_ID` |
| (frontend) `REACT_APP_AZURE_TENANT_ID` | — | Set in `frontend/.env.local`, same value as backend's `AZURE_TENANT_ID` |
| (frontend) `REACT_APP_ALLOWED_EMAIL_DOMAIN` | *(blank)* | |
| (frontend) `REACT_APP_API_BASE` | `http://localhost:8080` | Backend origin, no `/api` suffix, no trailing slash — set for a deployed backend |

## Common Commands

```bash
# Backend (run from backend/)
mvn spring-boot:run                    # start API on :8080 — auth config is now baked in, no env var needed
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
