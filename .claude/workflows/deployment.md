# Workflow: Deployment

**This repo has no deploy process yet** — no Dockerfile, no cloud deploy scripts, no CI/CD (no
`.github/workflows`), and it isn't even a git repository yet. There is deliberately no
project-specific `deploy.md` command (see `.claude/memory/decisions.md`) — use gstack's
`/land-and-deploy` for the generic orchestration once a real deploy target exists, and update this
workflow with real steps the first time an actual deployment happens.

## What must be decided/fixed before any real deployment (not yet done)

1. **CORS** — `WebConfig.addCorsMappings` only allows `http://localhost:3000`. A deployed
   frontend origin needs to be added, ideally via a configurable property rather than another
   hardcoded string.
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
