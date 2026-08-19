# Delta Pre-Check Tool

An internal CloudFuze tool for tracking pre-migration checklist compliance and approval sign-off
across Content/Email/Message migration projects. Each **Project** (a customer engagement) contains
**Servers**; each Server has a CSV-imported list of source → destination workspace pairs, a single
server-wide pre-check checklist, and a sequential three-role sign-off chain (Migration Manager →
Dev Lead → QA Lead) that must fully resolve before the server's data migration ("Delta") can be
initiated.

## Tech Stack

- **Backend**: Java 21, Spring Boot 3.3.4 (Web, Data JPA, Validation, Security, OAuth2 Resource
  Server, Mail), Maven, Lombok, Hibernate (`ddl-auto=update` — no migration tool; schema evolves by
  editing `@Entity` annotations)
- **Database**: PostgreSQL
- **Frontend**: React 18 (Create React App), `react-router-dom`, `axios`, `@azure/msal-browser` +
  `@azure/msal-react`
- **Auth**: Microsoft Entra ID (Azure AD), single-tenant app registration
- **File storage**: local filesystem (`backend/uploads`), served at `/uploads/**`
- **Email**: SMTP via Spring Mail (defaults to Office 365's relay)

## Prerequisites

- JDK 21+ and Maven (`mvn -version`)
- Node.js 18+ and npm
- PostgreSQL running locally, with the `delta_migration_tracker` database already created —
  unlike MySQL, Postgres has no "create on first connect" option, so run `createdb
  delta_migration_tracker` (or `psql -c "CREATE DATABASE delta_migration_tracker;"`) once before
  starting the app for the first time. Tables/columns are still created automatically after that.

## Configuration

All backend settings live in `backend/src/main/resources/application.properties` and are overridable
by environment variable — nothing needs editing for local dev, the defaults below are what
`mvn spring-boot:run` uses out of the box.

| Property | Env var | Local default | Set for production |
|---|---|---|---|
| JDBC URL | `DB_URL` | `jdbc:postgresql://localhost:5432/delta_migration_tracker` | your real DB host/name |
| Listen port | `SERVER_PORT` (or `PORT`) | `8081` | as needed — a PaaS-injected `PORT` is honoured automatically |
| First admin seeded on an empty DB | `APP_FIRST_ADMIN_EMAIL` | `first.admin@yourdomain.com` (placeholder) | **set this** — the admin who should own Manage Access in *that* environment |
| DB username | `DB_USERNAME` | `postgres` | real DB user |
| DB password | `DB_PASSWORD` | `postgres` | real DB password (secret) |
| CORS allowlist for `/api/**` | `APP_ALLOWED_ORIGINS` | `http://localhost:3000` | your deployed frontend origin (comma-separate for more than one) |
| Frontend URL (used in email links) | `APP_FRONTEND_URL` | `http://localhost:3000` | your deployed frontend URL |
| Azure app (client) ID | `AZURE_CLIENT_ID` | baked-in default | usually leave as-is — it's a public identifier, not a secret |
| Azure tenant ID | `AZURE_TENANT_ID` | baked-in default | usually leave as-is |
| Restrict sign-in to an email domain | `AZURE_ALLOWED_EMAIL_DOMAIN` | *(blank — open)* | `cloudfuze.com` before go-live |
| Require admin-managed allowlist | `AZURE_REQUIRE_ALLOWLIST` | `true` | leave `true` |
| Auto-provision domain | `AZURE_AUTO_PROVISION_DOMAIN` | `cloudfuze.com` | as needed |
| SMTP host/port/username | `SMTP_HOST`/`SMTP_PORT`/`SMTP_USERNAME` | Office 365 relay defaults | as needed |
| SMTP password | `SMTP_PASSWORD` | *(blank — sending disabled)* | real SMTP password (secret) |
| Ticketing site URL | `TICKETING_BASE_URL` | `https://neutaraticketing.cftools.live` | leave as-is |
| Ticketing API token | `TICKETING_API_TOKEN` | *(blank — ticket lookup disabled)* | a `nta_…` bearer token from your ticketing profile (secret) |

Frontend settings are build-time env vars (Create React App) in `frontend/.env.local` — copy
`frontend/.env.example` to get started:

| Env var | Local default | Set for production |
|---|---|---|
| `REACT_APP_API_BASE` | `http://localhost:8081` | your deployed backend URL, no `/api` suffix |
| `REACT_APP_AZURE_CLIENT_ID` | — | same value as backend's `AZURE_CLIENT_ID` |
| `REACT_APP_AZURE_TENANT_ID` | — | same value as backend's `AZURE_TENANT_ID` |
| `REACT_APP_AZURE_REDIRECT_URI` | `http://localhost:3000` | your deployed frontend URL — must also be registered as a redirect URI on the Azure app registration |
| `REACT_APP_ALLOWED_EMAIL_DOMAIN` | `cloudfuze.com` | as needed |
| `REACT_APP_HOTJAR_SITE_ID` | *(blank)* | Hotjar Site ID (digits only) enabling session recording; blank disables it. Leave blank locally so your own sessions aren't recorded. Baked in at build time, so a container needs `docker build --build-arg REACT_APP_HOTJAR_SITE_ID=...` — setting it with `docker run -e` does nothing |

## Deploy (Docker)

The whole stack -- Postgres, backend, frontend, and the TLS-terminating proxy -- runs from
`docker-compose.yml`:

```bash
cp .env.example .env      # fill in POSTGRES_PASSWORD, HOTJAR_SITE_ID, TICKETING_API_TOKEN
docker compose up -d --build
```

**Read [`deploy/DEPLOY.md`](deploy/DEPLOY.md) before the first run on a server that currently has a
host nginx.** Moving TLS into the proxy container is a cutover, and certbot has to switch to webroot
renewal once the container holds port 80.

Everything that used to be a manual edit on the server -- `client_max_body_size`, the CSP, HTTP/2,
rate limiting -- now lives in `deploy/proxy/default.conf.template` and is version-controlled.

## Run the backend

```bash
cd backend
mvn spring-boot:run
```

The API starts on **http://localhost:8081**. Auth is already configured (client/tenant ID are
baked into `application.properties` as defaults) — no environment variable is required to get a
working login locally. The database starts empty; an admin user is seeded automatically on first
run against an empty `app_users` table (`AdminBootstrap`).

Uploaded evidence files are stored under `backend/uploads` and served at
`http://localhost:8081/uploads/<file>`.

## Run the frontend

```bash
cd frontend
npm install
npm start
```

The app opens on **http://localhost:3000** and talks to the backend at
`http://localhost:8081/api` by default (override with `REACT_APP_API_BASE`).

## Sign in with Microsoft

Sign-in is required by default — any Microsoft work/school account in the configured Entra ID
tenant can authenticate, but **authentication alone doesn't grant access**. A second, independent
check (the `app_users` allowlist, managed under **Manage Access**) decides whether a signed-in
account can actually use the app, and as what role. Accounts on the auto-provision domain (default
`cloudfuze.com`) are added automatically as `Migration Engineer` the first time they sign in;
everyone else needs an admin to add them first.

To point this at a different Azure app registration instead of the baked-in default, set
`AZURE_CLIENT_ID`/`AZURE_TENANT_ID` (backend) and `REACT_APP_AZURE_CLIENT_ID`/
`REACT_APP_AZURE_TENANT_ID` (frontend) to the new registration's values, and add the frontend's
URL as a redirect URI (platform: Single-page application) on that registration.

Auth can be turned off entirely for local testing by explicitly setting `AZURE_CLIENT_ID=` (blank)
— the backend then runs fully open (`permitAll`, no login screen). Never do this in a real
deployment.

## Importing data (CSV)

There's no manual form to create a Server or workspace pair one at a time — both come from
importing a CSV. The importer matches header names loosely (case-insensitive, punctuation-stripped,
several aliases accepted per column):

| Column | Required | Accepted header aliases |
|---|---|---|
| Server URL | yes (global import only) | `server_url`, `url`, `server` |
| Source email | yes | `source_email`, `source`, `source_user`, `source_account` |
| Source path | no | `source_path`, `source_folder`, `source_folder_path` |
| Destination email | yes | `destination_email`, `destination`, `destination_user`, `target_email`, `destination_account` |
| Destination path | no | `destination_path`, `destination_folder`, `target_path`, `destination_folder_path` |
| Combination (e.g. "Google Drive -> OneDrive") | no | `combination`, `platform_combination`, `source_destination_type` |

Bad rows are collected as per-row errors rather than failing the whole import — the response
reports total/created/updated counts plus a list of row-level problems.

## Usage flow

1. **Projects** — create a project (name, product type: Content/Email/Message), assign a Migration
   Manager and engineers.
2. Add **Servers** to the project, then import each server's workspace pairs via CSV.
3. **Server pre-check** — fill out the server's flat checklist (one item per row: status, evidence
   file, note). All items must be complete, evidenced, and noted (barring one exempt item) before
   the form can be submitted; only whoever started the submission can submit it. Submitting requires
   the project to have a Migration Manager assigned.
4. Submitting auto-creates the sign-off chain and emails the assigned Migration Manager.
5. **Approvals** — the chain resolves strictly in order: Migration Manager, then Dev Lead, then QA
   Lead. Only the role whose turn it is can approve or decline; declining bounces the chain back
   one step for rework. The Dev Lead alone decides, at approval time, whether QA Lead sign-off is
   required for that server — skipping it finalizes the Delta immediately.
6. Once the chain fully resolves, the server is marked Delta-initiated.
7. **Escalations** — created manually (there's no automatic escalation logic) to track and resolve
   issues found along the way.

## Key business rules

- Pre-check tracking is **per server**, not per workspace pair — one flat checklist
  (`PreCheckItem`) and exactly one submission record (`PreCheckSubmission`) per server. Workspace
  pairs are pure data (source/destination account mapping); they carry no status or checklist of
  their own.
- A pre-check submission can only be submitted by whoever started it (locked by email) — anyone
  else's attempt is rejected with a message naming the current owner.
- The sign-off chain is created all at once (all three rows, `PENDING`) the moment the pre-check is
  submitted — a row existing means the chain has started, not that the role has decided anything.
  "Done" means `APPROVED`; a later role isn't genuinely pending until every earlier role is
  `APPROVED`.
- Escalations, sign-offs, and pre-check attribution are all keyed by email, compared
  case-insensitively throughout.

## Project structure

```
delta-precheck-tool/
├── backend/    Spring Boot API (entities, repositories, services, controllers, seed data)
└── frontend/   React app (Dashboard, Projects, Approvals, Escalations, Admin, Pre-Check form)
```
