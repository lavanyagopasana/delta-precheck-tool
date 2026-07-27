# Delta Migration Readiness Tracker

A full-stack app for tracking pre-migration checklist compliance for a Content Migration team:
dashboard rollups, per-workspace-pair pre-check forms with evidence-gated checkboxes, automatic
escalation rules, and a per-server sign-off / "Initiate Delta" flow.

## Tech Stack

- **Backend**: Java 21, Spring Boot 3.3 (Web, Data JPA, Validation), MySQL
- **Frontend**: React 18 (Create React App), Axios, React Router
- **File storage**: local filesystem (`backend/uploads`) for evidence uploads

## Prerequisites

- JDK 21+ and Maven (`mvn -version`)
- Node.js 18+ and npm
- MySQL 8 running locally (a `MySQL80` Windows service is common on dev machines — check with
  `sc query MySQL80` on Windows). No database needs to be created manually; the app creates
  `delta_migration_tracker` automatically on first connect.

## Configuration

Backend datasource settings live in `backend/src/main/resources/application.properties` and can be
overridden with environment variables:

| Property | Env var | Default |
|---|---|---|
| JDBC URL | `DB_URL` | `jdbc:mysql://localhost:3306/delta_migration_tracker?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC` |
| Username | `DB_USERNAME` | `root` |
| Password | `DB_PASSWORD` | `root` |

If your local MySQL root password differs, either export `DB_PASSWORD` before starting the backend,
or edit `application.properties` directly.

## Run the backend

```bash
cd backend
mvn spring-boot:run
```

The API starts on **http://localhost:8080**. The database starts empty — no seed/demo data is
generated. Servers and workspace pairs are populated by importing a CSV (see the Servers page);
tickets, sign-offs, and pre-check progress are entered manually through the app.

Uploaded evidence files are stored under `backend/uploads` and served at `http://localhost:8080/uploads/<file>`.

## Sign in with Microsoft (optional)

The app can require sign-in with a Microsoft work/school account, restricted to `@cloudfuze.com`
addresses. Until it's configured, the app runs exactly as before with no login screen.

The app registration is **multi-tenant**, so it can be created under *anyone's own* Microsoft
account — no cloudfuze.com IT/admin action is needed. The `@cloudfuze.com` restriction is enforced
by the app itself (checking the signed-in account's email), not by Azure.

1. In the [Azure Portal](https://portal.azure.com), go to **Microsoft Entra ID → App registrations
   → New registration**.
2. Name it (e.g. "Delta Migration Readiness Tracker"). Under **Supported account types**, choose
   **Accounts in any organizational directory (Any Microsoft Entra ID tenant — Multitenant)**.
3. Under **Redirect URI**, choose platform **Single-page application (SPA)** and enter
   `http://localhost:3000` (add your production URL later the same way).
4. Click **Register**, then copy the **Application (client) ID** from the Overview page. (No
   tenant ID, client secret, or "Expose an API" step needed — the app only needs to know who's
   signed in, not call any Microsoft API.)
5. Set the value you copied:
   - Frontend: copy `frontend/.env.example` to `frontend/.env.local` and fill in
     `REACT_APP_AZURE_CLIENT_ID`.
   - Backend: export `AZURE_CLIENT_ID` before starting the backend (same pattern as
     `DB_PASSWORD`).
6. Restart both the frontend and backend. A "Sign in with Microsoft" screen now gates the app.
   Anyone can authenticate with a Microsoft work/school account, but only `@cloudfuze.com`
   addresses get past the sign-in screen — everyone else sees an "Access restricted" message.
   Change the allowed domain via `REACT_APP_ALLOWED_EMAIL_DOMAIN` (frontend) and
   `AZURE_ALLOWED_EMAIL_DOMAIN` (backend) if needed.

## Run the frontend

```bash
cd frontend
npm install
npm start
```

The app opens on **http://localhost:3000** and talks to the backend at `http://localhost:8080/api`.

## Demo flow

1. **Dashboard** — summary cards + server-wise readiness table.
2. Click a server → **Workspace Pairs** list with status badges.
3. Click a pair → **Pre-Check Form**: one card per category (Pre-Check 1 / Pre-Check 2) with a plain
   checklist, a "Check All" shortcut, a shared Notes/Remarks box, and a single evidence attachment
   per category. Check every item, add an attachment, then **Submit for Migration Manager Review** — the button
   stays disabled until both conditions are met, and the server enforces the same rule. **Save
   Draft** persists partial progress without those requirements. Once both categories are submitted,
   the pair flips to **Delta Ready** automatically.
4. **Escalations** — auto-created tickets from incomplete pre-checks or server-wide large-cursor
   issues; mark them Resolved.
5. **Sign-off** (per server) — three role sign-offs (Migration Manager, Dev Lead, QA Lead);
   **Initiate Delta** unlocks only once all three are signed.

## Key business rules implemented

- Each Pre-Check category (Pre-Check 1, Pre-Check 2) is tracked as a `PreCheckSubmission` — status
  `NOT_STARTED` → `DRAFT` → `SUBMITTED`, with shared notes, one evidence attachment, submitter name,
  and timestamp. Checkboxes underneath can be toggled freely while drafting.
- `POST /api/pairs/{pairId}/precheck-submissions/{category}/submit` rejects (400) unless **every**
  item in that category is checked **and** an evidence file is attached — enforced server-side in
  `PreCheckSubmissionService`, mirrored client-side by disabling the Submit button.
- A workspace pair becomes `DELTA_READY` only when **both** category submissions reach `SUBMITTED`.
- Auto-escalation (on every checkbox toggle, and via a 5-minute scheduled sweep):
  - Any incomplete Pre-Check 1 item → `PRIORITY_1_TICKET` for that pair.
  - Any incomplete Pre-Check 2 item → `DEV_TEAM_NOTIFY` for that pair.
  - More than 10% of a server's pairs flagged with an unresolved "large change cursor" item →
    `TEAM_LEAD_ESCALATION` for that server.
  - Escalations are only created if no matching open escalation already exists.

## Project structure

```
delta-migration-readiness-tracker/
├── backend/    Spring Boot API (entities, repositories, services, controllers, seed data)
└── frontend/   React app (Dashboard, Servers, Pre-Check Form, Escalations, Sign-off)
```
