# Workflow: Deployment

This repo has no Dockerfile, no cloud deploy scripts, and no CI/CD (no `.github/workflows`) — but it
**is** a git repository now (`github.com/lavanyagopasana/delta-precheck-tool`) and it **has been
deployed at least once**. There is deliberately no project-specific `deploy.md` command (see
`.claude/memory/decisions.md`) — use gstack's `/land-and-deploy` for generic orchestration.

## The frontend does NOT need build-time URLs anymore (2026-08-06)

The first production deploy failed with a login that worked perfectly on every laptop. Root cause:
`react-scripts` inlines every `REACT_APP_*` value into `main.<hash>.js` at build time, so the bundle
shipped `redirectUri: "http://localhost:3000"` and an API base of `http://localhost:8080`. Nothing
set on the server could override it — the values were already frozen inside the JavaScript.

Two things now prevent that recurring, both in `frontend/src/config/runtimeConfig.js`:

- **`frontend/public/runtime-config.js`** is copied verbatim into `build/` (webpack doesn't process
  `public/`), so it stays editable on the server after a build. It takes precedence over build-time
  env vars. Deploy once, retarget without rebuilding.
- **A localhost value from a build-time env var is ignored** when the page is served from a
  non-loopback host, falling back to the page's own origin. A bundle built with dev settings still
  works when deployed. Covered by `frontend/src/config/runtimeConfig.test.js`.

**Consequence for a normal deploy: set nothing.** Serve `build/` and point a reverse proxy's `/api`
at the backend, and both the redirect URI and the API base resolve to the deployed origin. Only edit
`build/runtime-config.js` (`apiBase`) when the backend is on a **different** origin — which is also
the only case where `APP_ALLOWED_ORIGINS` matters, since same-origin requests aren't CORS at all.

Serve `runtime-config.js` with a short/no-cache header — it's intentionally unhashed so it can be
edited in place, which also means a browser can cache a stale copy.

Beware `frontend/.env.local`: `react-scripts` loads it for **production** builds too, at *higher*
precedence than `.env.production`. Creating `.env.production` does not override it. Only a shell/CI
variable or removing the key from `.env.local` does.

## What must still be decided/fixed before a real deployment

1. **CORS** — `app.allowed-origins` (`APP_ALLOWED_ORIGINS`) defaults to `http://localhost:3000`,
   owned by `SecurityConfig`. Only needed for a cross-origin backend (see above). `SecurityConfig`
   logs the effective list at startup and warns when it's still the localhost-only default, so a
   misconfiguration is visible in the log instead of only as an opaque browser error.
2. **Auth posture** — `AZURE_REQUIRE_ALLOWLIST=false` and a blank `AZURE_ALLOWED_EMAIL_DOMAIN` are
   both explicitly "temporarily off while testing." Decide the real intended production values
   before this is reachable outside local development.
3. **`AZURE_CLIENT_ID` must be set in whatever deploys the backend**, persistently (not "exported
   in a terminal") — a process manager/container/PaaS env-var configuration, not a manual export.
   Getting this wrong doesn't fail loudly (see `.claude/rules/security-rules.md`) — verify
   `/api/me` returns a real populated response post-deploy, don't just confirm the app loads.
4. **Database** — currently assumes a local MySQL at `localhost:3306` with `root`/`root` by
   default. A real deploy needs real `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` and a real MySQL
   instance reachable from wherever the backend runs. `Hibernate ddl-auto=update` will create the
   schema on first connect — same as local — but there's no rollback story if a schema change
   goes wrong in production (see `.claude/agents/architect.md`'s schema-change escalation rule).
5. **SMTP** — `spring.mail.username` defaults to `leo@fuzebot.io`, password blank (sending
   silently disabled). Confirm real SMTP credentials are configured if the deployed environment
   should actually send notification emails.
6. **File storage** — `backend/uploads` is local filesystem storage. If the backend runs anywhere
   without persistent local disk (most container/serverless platforms), evidence uploads would be
   lost on redeploy/restart — this would need to move to real object storage before a production
   deployment, not something to discover after the fact.
7. **No git repository, no CI/CD** — `/land-and-deploy` and `/ship` both assume a real git
   remote/PR flow exists. Initializing git (`git init`, review `.gitignore` first, then a first
   commit) is a prerequisite, one-time decision for the team — don't do this unprompted mid-task
   (see `AGENTS.md`'s escalation rules).

## Once a real deploy target and process exist

Replace this file's guidance with the actual steps, and consider adding a project-specific
`.claude/commands/deploy.md` at that point if there's genuine project-specific logic (a specific
host to push to, a specific restart command) that gstack's generic `/land-and-deploy` doesn't
know — see `.claude/memory/decisions.md` for why it was omitted originally.
