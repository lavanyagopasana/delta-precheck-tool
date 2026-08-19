---
description: Deploy this project to production — triggers the CI/CD workflow, which runs the full test suite first and refuses to deploy if anything fails
---

# Deploy

Trigger a production deploy of delta-precheck-tool. **Nothing deploys on a push or a merge** — the
`deploy` job in `.github/workflows/ci-cd.yml` only runs on an explicit manual trigger, and this
command is that trigger.

Read `deploy/CI-CD.md` before running this the first time in a session if you haven't already.

## What this does not do

It does not deploy from the local working tree. It asks GitHub to run the workflow, which checks out
a commit **from `origin`** and deploys that. Uncommitted local changes are invisible to it — a deploy
that "didn't pick up my change" is nearly always an unpushed commit.

## Steps

### 1. Confirm what is about to be deployed

```bash
git status --short
git log --oneline -1 origin/main
git log --oneline origin/main..HEAD 2>/dev/null | head
```

Report to the user: the commit that will deploy (the tip of `origin/main`), and anything local that
is **not** in it. If there are uncommitted or unpushed changes, say so explicitly and ask whether to
proceed — do not silently deploy an older commit than the user is looking at.

### 2. Check the target ref

The deploy always uses the workflow definition and code from the ref you dispatch. Default to `main`.
If `$ARGUMENTS` names a branch, use that instead, and warn the user that deploying a non-`main`
branch to production is unusual.

### 3. Dispatch

```bash
gh workflow run ci-cd.yml --ref main -f deploy=true
```

If `gh` is not installed or not authenticated, do **not** try to work around it with a token from the
environment. Instead tell the user to either:

- install and authenticate it: `gh auth login`, or
- trigger it in the browser: <https://github.com/lavanyagopasana/delta-precheck-tool/actions/workflows/ci-cd.yml>
  → **Run workflow** → tick *Also deploy to production after tests pass* → **Run workflow**

### 4. Watch it

```bash
sleep 5 && gh run list --workflow=ci-cd.yml --limit 1
gh run watch --exit-status
```

`--exit-status` makes a failed run a non-zero exit, so the failure is unambiguous.

### 5. Report the outcome

On success: say which commit is now live and that the health check passed (frontend `200`,
`/api/me` `401`).

On failure: fetch the log and diagnose before suggesting a re-run.

```bash
gh run view --log-failed
```

Match the failure against the known ones below rather than treating it as novel.

## Known failure modes, in likelihood order

**Preflight: "docker-compose.yml here is untracked"** — the one-time cutover in `deploy/DEPLOY.md`
has not been done on the server. This is the guard working, not a bug: deploying would overwrite the
hand-written compose file and start a proxy container that collides with host nginx on `:443`.
Nothing on the server was changed. The cutover is manual and needs a downtime window; do not try to
automate around it.

**Preflight: ".env is missing"** — the server has no `.env`. If `MANAGE_ENV` is `true` the workflow
renders one, but it still needs the required values present in GitHub Settings.

**Preflight: "tracked files are modified on the server"** — somebody hotfixed in place. Report the
file list to the user and ask; do not discard their change.

**Health check: "/api/me returned 200 unauthenticated"** — the backend came up **fully open**:
`AZURE_CLIENT_ID` resolved blank, so every `/api` route became `permitAll`. The deploy has already
rolled itself back. Fix the value (`.env` on the server, or the `AZURE_CLIENT_ID` variable when
`MANAGE_ENV` is on) — **never** by loosening a permission check. See
`.claude/rules/security-rules.md`.

**Health check: frontend did not return 200** — read the container logs in the rollback step's output
before re-running. The rollback has already restored the previous revision.

**"DEPLOY_SSH_KEY and DEPLOY_HOST are not set"** — repository secrets are missing. `deploy/CI-CD.md`
lists all six.

**Backup step skipped with a warning** — no database container was running. Expected on a first
deploy, suspicious on any other; check whether the stack was down before the deploy.

## What not to do

- Don't re-run a failed deploy without reading why it failed. The preflight failures above are all
  states that need fixing on the server first, and a re-run will hit the same wall.
- Don't add a push trigger to the deploy job to "make it automatic". That decision is recorded in the
  job's own comment: this stack rebuilds images on the server and recreates containers, so a merged
  docs typo would restart production.
- Don't deploy without knowing whether the cutover has happened. If unsure, run this and expect the
  preflight to tell you — it fails safely, before changing anything.
