# Decisions

Why things are the way they are. Newest first.

## 2026-08-10 — Migrated from MySQL to PostgreSQL

Swapped the database engine at the user's explicit request. Checked first whether this was safe to
do as a pure driver/config swap: both hand-written `@Query`s (`TicketRepository`) are plain portable
JPQL with no native SQL, and the one raw `columnDefinition` fragment (`WorkspaceCombination
.currentCycleNumber`, `"int default 1"`) is ANSI-compatible with both engines — so no entity or
query changes were needed, only `pom.xml` (`mysql-connector-j` → `org.postgresql:postgresql`) and
`application.properties` (JDBC URL/driver/default credentials, dropped the now-unnecessary
`spring.jpa.database-platform` line the same way the MySQL one already logged a deprecation warning
about).

**Corrected claim, checked against the real running database:** first assumed the
`delta_cycles.status` native-MySQL-`ENUM` problem (Hibernate's `ddl-auto=update` couldn't widen it
when `DECLINED` was added) simply didn't exist on Postgres, since `@Enumerated(EnumType.STRING)`
maps to a plain `VARCHAR` here with no native enum type. That's true but incomplete — after actually
installing Postgres locally and inspecting `pg_constraint`, Hibernate 6 turns out to auto-generate a
`CHECK` constraint enumerating every allowed value for all 18 enum-mapped columns in this schema, and
`ddl-auto=update` is exactly as unable to widen an existing `CHECK` constraint as it was unable to
widen MySQL's native `ENUM`. Same problem class, different mechanism — see the corrected
`docs/KNOWN_LIMITATIONS.md` entry. Caught this specifically by verifying with `psql` instead of
trusting the first (wrong) assumption.

**Real operational difference to know about:** Postgres has no `createDatabaseIfNotExist`-style
connection-string flag like MySQL did — the target database must already exist
(`createdb delta_migration_tracker`) before the app's first connection anywhere this runs, including
a fresh local setup. Updated `CLAUDE.md`, `README.md`, and this project's other `.claude/` docs that
referenced MySQL specifically.

**Verified end-to-end, not just compiled:** installed PostgreSQL 16 locally
(`winget install PostgreSQL.PostgreSQL.16`), created the `delta_migration_tracker` database, and
confirmed the backend actually starts against it — real `HikariPool` → `PgConnection`, all 13 tables
created fresh by `ddl-auto=update`, port 8081 live, `/api/tickets/open-count` correctly returning 401
unauthenticated (proves the security filter chain initialized, not just that the process is up).
This same verification pass also caught two more things that needed fixing, not just the tracked
`application.properties`: a gitignored `backend/application.properties` local override still had the
old MySQL URL (Spring's `file:./` property-source precedence makes it outrank the tracked config, so
it silently would have kept connecting to MySQL, or after this fix, failed outright since
`mysql-connector-j` is no longer even on the classpath) — and
`backend/src/test/resources/application-test.properties`'s H2 test database was running in
`MODE=MySQL` compatibility mode (now `MODE=PostgreSQL`).

**Not migrated:** this local dev machine's existing MySQL data (the `delta_migration_tracker`
database, including real-looking project/server/combination test data) does not carry over
automatically — the app now points at a fresh, empty Postgres database. No data-migration script
was requested or written.

## 2026-07-30 — Corrected Azure app registration (the 2026-07-27 pair was stale)

The client ID `a55e053f-bfe9-4b4a-8b74-362649f82cf0` / tenant ID
`66d8848d-26b6-4147-8124-127624d7b3a6` baked in on 2026-07-27 (see entry below) turned out to be
wrong — a teammate (Abhinav Surattu) confirmed via the Azure Portal that the actual app
registration for this project, named **"delta migration readiness tracker"**, is client ID
`4145c1b2-a596-4d84-bede-6e2ca276c9c7`, tenant ID `807d6772-847c-40e2-9bec-e2c930b3a42e`. This was
discovered because `frontend/.env.local` had *already* been pointed at this correct pair (source
unclear), while the backend defaults and docs still had the stale one — so login failed with
"account does not exist in tenant" for anyone whose `.env.local` had been reset to match the
(wrong) documented default. Updated `application.properties`, `CLAUDE.md`, and this file to the
corrected pair.

**Known follow-up, not yet resolved:** this tenant's directory name is `filefuze`, and this single
tenant registration only accepts accounts that exist in that Entra ID directory. A `cloudfuze.com`
account (e.g. `lavanya.gopasana@cloudfuze.com`) is rejected by Microsoft itself, before this app's
own auth logic runs, unless that account is first added as an external/guest user in the
`filefuze` tenant. This is a directory-membership issue to resolve with whoever administers that
tenant — not something fixable by changing this app's code or config.

## 2026-07-27 — Repo pushed to GitHub; new Azure app registration baked in as defaults

**Repo is now live** at `github.com/lavanyagopasana/delta-precheck-tool` (`main` branch) — `git
init` had never been run before this. While staging the first commit, found (and correctly kept
gitignored) a real secret: `backend/application.properties` (repo root — distinct from the one in
`src/main/resources/`, a local-only override file Spring Boot auto-loads from the working
directory) contains a real SMTP password. A `.gitignore` "cleanup" earlier the same day had
mistakenly removed the rule protecting this exact file, having wrongly concluded it was dead
weight without checking whether a file existed at that path. Caught before committing; the
`.gitignore` rule is restored. **Lesson for next time:** never remove a `.gitignore` rule for a
path you haven't actually checked the contents of, even if it looks like it doesn't match anything
obvious.

**New Azure app registration wired in**: client ID `a55e053f-bfe9-4b4a-8b74-362649f82cf0`, tenant
ID `66d8848d-26b6-4147-8124-127624d7b3a6` — this is now a **single-tenant** registration (previously
multi-tenant, using the generic `organizations` issuer). Both values are baked into
`backend/src/main/resources/application.properties` as defaults (not secrets — public OAuth
identifiers) and into `frontend/.env.local` (gitignored, personal-machine file — update this
manually if the Claude session's write is blocked by the `.env*` deny rule in
`.claude/settings.json`; that rule is intentional and shouldn't be worked around by editing
`.claude/settings.json` itself). This permanently resolves the `AZURE_CLIENT_ID` "must export every
restart" footgun described further down this file and in `.claude/rules/security-rules.md` — that
guidance is now about what to check if auth *degrades*, not a routine step.

## 2026-07-27 — Allowlist bypass + over-permissive project visibility (security fix)

**What was wrong, in two parts:** (1) `azure.require-allowlist` defaulted to `false`, so any valid
Microsoft account — not just people added under Manage Access — could sign in at all. Confirmed via
direct DB query that `alex@filefuze.co` had signed in and used the app despite never appearing in
`app_users`. (2) Independent of that, `ProjectService.isVisible()` treated "authenticated but no
recognized role" (`callerRole == null`) the same as "auth not configured at all"
(`callerEmail == null`) — both returned `true` (see everything). An unrecognized, unregistered
caller should see nothing, not everything; conflating the two defeated the entire per-role
visibility model for anyone not yet in `app_users`.

**Fix:** `azure.require-allowlist` now defaults to `true`. `isVisible()` now returns `false` when
`callerRole == null` while `callerEmail != null`, keeping the `callerEmail == null` (auth-not-
configured) case as the only one that still defaults open. Both changes are pure default-tightening
— no new admin action needed on already-registered users.

## 2026-07-27 — Dashboard/Projects approval counts fixed (row-exists vs. actually-approved)

**What was wrong:** `DashboardService.getSummary()` and `ProjectService.buildSummary()` both
computed "Dev Approvals Done" / "Migration Manager Approvals Done" as *"does a `SignOff` row exist
for this role"* (`signOffRepository.countByRole(...)` / `.findByServerIdAndRole(...).isPresent()`),
not *"has this role actually approved."* Because all three sign-off rows are created together the
moment a pre-check is submitted (see `.claude/memory/architecture.md`), this meant a server whose
Migration Manager hadn't approved yet — but whose chain had started — was counted as "Done." The
"Pending" figure was computed as `totalServers - done`, which coincidentally also produced a
plausible-looking number, but for the wrong reason (it was counting servers that hadn't even
started their pre-check, not servers genuinely awaiting a decision).

**The fix:** both services now check `SignOffStatus` directly. "Done" = role's `SignOff.status ==
APPROVED`. "Pending" for Migration Manager = `status == PENDING` (always a genuine active turn,
since it's first in sequence). "Pending" for Dev Lead = `status == PENDING` **and** Migration
Manager's row is already `APPROVED` (otherwise it hasn't genuinely reached Dev Lead yet).

**Why it matters for future work:** this is the single most likely place for a *new* bug of the
same shape. Any code that counts or checks sign-off/submission status must check `.getStatus()`,
never just row existence. See `.claude/rules/security-rules.md`... no — see
`.claude/skills/code-review/SKILL.md` and `.claude/agents/code-reviewer.md`, both of which treat
this as a named, specifically-checked-for pattern.

## 2026-07-27 — "Total Requests" renamed to "Total Approval Requests," redefined

**What changed:** the Dashboard's first stat card used to show `serverRepository.count()` (every
server, regardless of pre-check/approval state) labeled "Total Requests." It's now "Total Approval
Requests" = Migration Manager Pending + Dev Lead Pending — i.e., the count of approvals genuinely
awaiting a decision right now, matching what a viewer would actually see as open items on the
Approvals page.

**Why:** the user reported the card's number didn't match what the Approvals page actually showed
as pending, and the redefinition (paired with the Done/Pending fix above) makes the two consistent.

## 2026-07-27 — Approvals page: "Delta Ready" label collision fixed

**What was wrong:** `ReadinessDot` (in `StatusBadge.js`, used only on the Approvals page) labeled
its `GREEN` state "Delta Ready" — but that describes **workspace-pair sync status**
(`ServerReadinessDto.computeReadinessStatus`, purely from `PairStatus` + open escalations), a
completely different thing from the sign-off chain's own "Delta Ready" terminal state shown in the
same row's "Current Status" column. A server with pairs fully synced but zero approvals could show
"Delta Ready" right next to "Not yet approved by the Migration Manager" — reading as contradictory.

**The fix:** relabeled to "Pairs Synced" / "Pairs Syncing" / "Pairs Not Synced". Then, per a
follow-up request, the Approvals table's Server column was simplified further to just the server
name + pair count, dropping the readiness dot and open-escalation text entirely (moved out of
scope for that specific table — still available elsewhere, e.g. `ProjectDetailsPage`'s
`ServerInfoCard`).

## 2026-07-27 — Admin bulk user CSV import added

Added `AppUserImportResultDto`, `AppUserService.importCsv`, `POST /api/admin/users/import-csv`,
and a modal on `AdminUsersPage` (role dropdown + CSV upload). Follows the exact same shape as
`WorkspacePairService.importCsvGlobal` (optional header auto-detection, per-row error collection,
`*ResultDto` summary) rather than inventing a new import convention — see
`.claude/skills/api-design/SKILL.md`.

## 2026-07-27 — `EngineerChecklist` redesigned (checkbox list → chip/tag combobox)

The old component was a fixed-height scrollable list of plain checkboxes — reported as "clumsy."
Replaced with a searchable combobox: selected engineers render as removable chips, typing filters
the roster, clicking an option adds it. Only used on `ProjectDetailsPage`'s Assignments card, so
this was a safe, contained redesign (`.checklist` CSS class is now dead but left in place since
nothing else references it removing it wouldn't be harmful either).

## 2026-07-27 — Recurring incident: backend restarted without `AZURE_CLIENT_ID`

Multiple times this session, the backend was restarted (to pick up code changes — there's no
`spring-boot-devtools`) from a fresh shell that didn't have `AZURE_CLIENT_ID` exported. Each time,
this silently dropped the backend into fully-open `permitAll` mode rather than failing loudly,
which looked like an authorization/role bug (`/api/me` returning null `email`/`role` for a real
admin) until traced back to the missing env var. See `.claude/rules/security-rules.md` for the
full explanation and `.claude/memory/project-context.md`/`CLAUDE.md` for where the value lives
(`frontend/.env.local`'s `REACT_APP_AZURE_CLIENT_ID`, same value as backend's `AZURE_CLIENT_ID`).
**This is the single most likely "phantom bug" to hit again** — check it before assuming a real
code defect whenever auth/role behavior looks wrong after any restart.

## Historical — `PROJECT_MANAGER` role renamed to `MIGRATION_MANAGER`

The role now called `MIGRATION_MANAGER` throughout the codebase (`AppUserRole`, `SignOffRole`'s
`MIGRATION_LEAD`, UI labels) was previously named `PROJECT_MANAGER`. Same role and responsibilities
— app-wide rename, not a new concept. If you encounter older external documentation, tickets, or
diagrams (e.g. `Delta-Migration-Workflow-and-Access.pdf`) still using "Project Manager," that's the
same thing as today's Migration Manager.

## Scaffolding decisions made when creating this `.claude/` structure

- **No `deploy.md` command was created.** The repo has no unique deploy logic (no Docker, no cloud
  deploy scripts, no CI/CD) — gstack's `/land-and-deploy` covers this generically, and inventing
  project-specific deploy steps that don't exist yet would be fabrication. Add `deploy.md` once
  there's a real, project-specific deploy process to codify.
- **No existing `.claude/commands/` or `.claude/skills/` were found** prior to this scaffold, so no
  gstack name-collision renames were needed beyond the one mandated by the scaffold's own
  instructions (`review.md` → `team-review.md`, since gstack reserves `/review`).
- **`.mcp.json` suggests two MCP servers**, both evidenced directly by the repo: a MySQL MCP
  (`@benborla29/mcp-server-mysql`, read-only by default) — evidenced by real friction this session
  manually locating `mysql.exe` and hand-escaping PowerShell queries just to inspect `app_users` and
  `signoffs` data — and a Jira MCP (`mcp-atlassian`) — evidenced by the `Escalation.ticketNumber`
  field and the sidebar's literal "Jira Tickets Tracking" label. No GitHub MCP was added: this isn't
  a git repository yet, so there's no evidenced need for one today.
- **`.claude/settings.json` denies reading `.env`/`.env.local` files** going forward, even though
  `frontend/.env.local` was read directly earlier this session to retrieve `AZURE_CLIENT_ID`'s
  value out of genuine necessity. That was a reasonable one-off; the deny rule establishes better
  hygiene from here on (real secrets, when they exist, shouldn't be read wholesale into an agent's
  context by default).
