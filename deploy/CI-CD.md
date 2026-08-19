# CI/CD

`.github/workflows/ci-cd.yml` is the whole pipeline. Four jobs:

| Job | Runs on | What it proves |
|---|---|---|
| `backend-tests` | every PR and push to `main` | `mvn -B -ntp test` — the JUnit suite against H2 |
| `frontend-tests` | every PR and push to `main` | `react-scripts test` — Jest + React Testing Library |
| `docker-build` | every PR and push to `main` | both images still build (no push to any registry) |
| `deploy` | push to `main` **only**, after all three pass | the stack is live and authentication is enforced |

The deploy job declares `needs` on the other three, so GitHub itself blocks a deploy whose tests
failed. It also runs on a manual `workflow_dispatch` with the `deploy` input ticked, which is the
way to re-run a deploy without pushing an empty commit.

Read this whole file before the first deploy. **Two things on the server must change first**, and
neither is something CI can do for you.

---

## Before the first deploy: two blockers

### 1. The server's `docker-compose.yml` is not the repo's

The file at `/opt/delta-precheck-tool/docker-compose.yml` was written by hand on the server. `main`
has never contained a `docker-compose.yml` at all — the one in this repo arrives with the
`feat/docker-compose-deploy` branch. They differ in ways that would take the site down:

| | Server (live today) | This repo |
|---|---|---|
| Database service | `postgres` | `db` |
| Host ports | `127.0.0.1:8532` / `8533`, fronted by host nginx | `proxy` container binds **`80:80` and `443:443`** |
| TLS termination | nginx installed on the host | the `proxy` container |
| Ticketing env | `JIRA_BASE_URL` / `JIRA_EMAIL` / `JIRA_API_TOKEN` | `TICKETING_BASE_URL` / `TICKETING_API_TOKEN` |
| Frontend args | hardcoded, no Hotjar, source maps shipped | from `.env`, `+HOTJAR_SITE_ID`, `GENERATE_SOURCEMAP=false` |
| Volumes | `delta_pg_data`, `delta_backend_uploads` | **the same two names** — matched deliberately, see below |

The volume names used to differ, and that was the most dangerous line in this table. The repo's
compose declared `postgres-data`/`backend-uploads` — names that do not exist on the server — so
`docker compose up` would have created two empty volumes, Postgres would have initialised a fresh
database, and the site would have come up looking cleanly wiped while the real data sat unreferenced
in the old volumes. The repo now declares `delta_pg_data` and `delta_backend_uploads` to match what
production already writes to, so there is nothing to migrate. Do not "tidy" those names; the
`volumes:` block in `docker-compose.yml` explains why at length.

`git reset --hard` **overwrites an untracked file that exists in the target commit without saying
so**. So an unguarded deploy would replace the server's compose file, then start a proxy container
that loses a fight with host nginx over port 443.

The pipeline's preflight step refuses to run while that untracked file is there. Clear it by working
through the cutover in [DEPLOY.md](DEPLOY.md) by hand — that is a deliberate few minutes of downtime,
which is exactly why it is not automated.

### 2. `.env` on the server still has the Jira variables

Commit `78906d6` replaced Jira with Neutara ticketing. The backend now reads `TICKETING_BASE_URL`
and `TICKETING_API_TOKEN`; `JIRA_*` is read by nothing. Blank `ticketing.api-token` disables ticket
lookup and logs a warning rather than failing, so this will not crash — ticket lookup will just
quietly stop working.

Compare the server's `.env` against [`.env.example`](../.env.example) and fill in
`POSTGRES_PASSWORD`, `TICKETING_API_TOKEN`, and `APP_DOMAIN` before deploying.

---

## Repository secrets

Settings → Secrets and variables → Actions → **Secrets** tab.

| Secret | Required | Default if unset | Notes |
|---|---|---|---|
| `DEPLOY_HOST` | **yes** | — | The server's public address. Left out of this file deliberately — see the note below |
| `DEPLOY_SSH_KEY` | **yes** | — | The full **private** key, `-----BEGIN` line through `-----END` line inclusive, with its trailing newline |
| `DEPLOY_USER` | no | `deploy` | Must be able to run `docker compose` without a password prompt |
| `DEPLOY_PORT` | no | `22` | |
| `DEPLOY_PATH` | no | `/opt/delta-precheck-tool` | Must already be a git checkout with a filled-in `.env` |
| `DEPLOY_KNOWN_HOSTS` | no, but recommended | falls back to `ssh-keyscan` | See the warning below |

**This repository is public.** The host address, SSH port, and deploy username are therefore kept in
secrets and out of this file — not because any of them is a credential (the IP is already
discoverable: `deltaprechecks.cftools.live` resolves to it), but because writing "SSH to *this*
address on *this* port as *this* user" into a world-readable file hands over a complete target
description for free. The values live in the secrets above; look them up there.

And under the **Variables** tab:

| Variable | Default if unset | Notes |
|---|---|---|
| `DEPLOY_HEALTH_BASE` | `https://deltaprechecks.cftools.live` | Public base URL the health check probes |

### Why `DEPLOY_KNOWN_HOSTS` is worth setting

Without it the workflow falls back to `ssh-keyscan`, which is trust-on-first-use: it accepts
whatever host key answers at that address. A man-in-the-middle on that first connection would be
undetectable, and the thing being handed over is a key with deploy rights on your production box.
The workflow logs a warning on every run until you set it.

Generate the value from a machine you already trust:

```bash
ssh-keyscan -p 22 <your-host>
```

Paste the entire output (all lines) as the secret.

---

## Managing `.env` from GitHub instead of on the server

By default the pipeline leaves the server's `.env` alone — it must already exist, and you edit it by
logging in. Set a repository **variable** `MANAGE_ENV` to `true` and that changes: the deploy renders
`.env` from GitHub Settings on every run and installs it before bringing the stack up. Configuration
is then edited in the browser, and nothing about routine config changes requires a shell.

Add these under **Settings ▸ Secrets and variables ▸ Actions**. Non-secret values go in the
**Variables** tab so they stay readable; only real credentials go in **Secrets**.

| Variables tab | Required | Example |
|---|---|---|
| `MANAGE_ENV` | to enable this at all | `true` |
| `APP_DOMAIN` | **yes** | `deltaprechecks.cftools.live` |
| `POSTGRES_DB` | **yes** | `delta_migration_tracker` |
| `POSTGRES_USER` | **yes** | `deltaapp` |
| `AZURE_CLIENT_ID` | **yes** | `a55e053f-bfe9-4b4a-8b74-362649f82cf0` |
| `AZURE_TENANT_ID` | **yes** | `66d8848d-26b6-4147-8124-127624d7b3a6` |
| `APP_FIRST_ADMIN_EMAIL` | **yes** | `lavanya.gopasana@cloudfuze.com` |
| `AZURE_ALLOWED_EMAIL_DOMAIN` | no | blank |
| `AZURE_REQUIRE_ALLOWLIST` | no | defaults `true` |
| `AZURE_AUTO_PROVISION_DOMAIN` | no | defaults `cloudfuze.com` |
| `HOTJAR_SITE_ID` | no | blank disables Hotjar |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` | no | default to Office 365 relay |
| `TICKETING_BASE_URL` | no | defaults to the Neutara URL |

| Secrets tab | Required | Notes |
|---|---|---|
| `POSTGRES_PASSWORD` | **yes** | read the warning below before setting this |
| `TICKETING_API_TOKEN` | no | blank disables ticket lookup, with a warning |
| `SMTP_PASSWORD` | no | blank disables mail, with a warning |

### ⚠️ `POSTGRES_PASSWORD` must be the password the database already has

The Postgres image sets the password **once**, when it initialises an empty data volume. It is
stored inside `delta_pg_data` from that moment on. Changing `POSTGRES_PASSWORD` afterwards does not
change the password inside the volume — it only changes what the backend *tries*, so the backend
then fails to connect and the site is down until the value matches again.

Before switching `MANAGE_ENV` on, read the current value off the server **once** and put exactly
that into the secret:

```bash
grep '^POSTGRES_PASSWORD=' /opt/delta-precheck-tool/.env
```

(If you genuinely want to change the database password, that is a `ALTER USER` inside the running
Postgres container followed by updating the secret — not a `.env` edit.)

### What this step does and does not do

- Required values are checked **before** anything is written, on both the runner and the server, and
  a missing one fails the deploy. `AZURE_CLIENT_ID` is checked twice over: blank means
  `SecurityConfig` treats auth as unconfigured and every `/api` route becomes `permitAll`, with no
  error and no failed startup.
- The rendered file is sent over **stdin**, not as a command argument — arguments are visible in
  `ps` to every user on that host while the command runs, and this file holds the database password.
- The previous `.env` is kept as `.env.bak-<timestamp>` (last five) whenever the content changes.
  GitHub will not show you a secret again after you save it, so a copy on disk is the way back from
  a wrong value.
- **Hand edits on the server are reverted at the next deploy.** That is the intent — one source of
  truth — but it is a behaviour change worth telling anyone else with server access about.
- The database backup runs **before** this step, using the old `.env`, so a wrong value in Settings
  cannot break the dump you would need to recover with.
- Only key *names* are echoed to the build log, never values.

Leave `MANAGE_ENV` unset and none of this happens; the step is skipped and the server's existing
`.env` is used exactly as before.

---

## The deploy user

CI connects as **`deploy`**, not `root`, and deliberately. This server also runs other applications
under Docker, so the deploy account is scoped to `/opt/delta-precheck-tool` and nothing else:

```bash
sudo adduser --disabled-password --gecos "" deploy
sudo usermod -aG docker deploy                       # required: the pipeline runs docker compose
sudo chown -R deploy:deploy /opt/delta-precheck-tool
sudo chmod 600 /opt/delta-precheck-tool/.env         # it holds POSTGRES_PASSWORD and the API token
sudo -u deploy install -d -m 700 /home/deploy/.ssh
```

`--disabled-password` means the account has no password to guess — key authentication only. Keep
`PermitRootLogin no` in `sshd_config`; port 22 is on a public IP here, and this is what lets you
leave root SSH closed permanently rather than opening it for a deploy job.

### What this does and does not contain

**Does:** file access is limited to the app directory, so a wrong path in a deploy script cannot
touch `/opt/apps/*` or anything else on the box. Actions are attributable to a distinct account in
the logs. Revoking CI access is one line in `authorized_keys`, with no effect on root.

**Does not:** membership in the `docker` group is effectively root-equivalent — anyone who can run
`docker` can `docker run -v /:/host` and own the filesystem. That is a documented property of
Docker, not a gap in this setup, and there is no way to run `docker compose` without it short of a
narrow `sudoers` rule wrapping a fixed script. So treat `deploy` as **defense in depth and an audit
trail, not a hard privilege boundary**, and treat `DEPLOY_SSH_KEY` as a credential with root-grade
consequences if leaked.

**On not disturbing the other apps:** `docker-compose.yml` pins `name: delta-precheck-tool`, and
every command the pipeline runs is a `docker compose` command scoped to that project. It can start,
stop, rebuild, and remove orphans within this stack without reaching another application's
containers. Nothing in the pipeline runs `docker system prune`, `docker volume prune`, or any
host-wide Docker command — those are the ones that would take other apps down, so don't add one.

---

## One-time server setup

Run these **on the server**, as the user the pipeline will connect as.

```bash
# 1. A dedicated key for CI. No passphrase -- a non-interactive job cannot type one.
#    Generate it on the SERVER, or locally and copy the public half up; either way the private
#    half goes into the GitHub secret and nowhere else.
ssh-keygen -t ed25519 -C "github-actions-delta-precheck" -f ~/.ssh/gha_deploy -N ""

# 2. Authorize it.
cat ~/.ssh/gha_deploy.pub >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys

# 3. Print the private key, copy it into the DEPLOY_SSH_KEY secret, then delete it from the server.
cat ~/.ssh/gha_deploy
rm ~/.ssh/gha_deploy

# 4. Confirm the deploy user can drive docker without sudo.
docker compose version && docker info >/dev/null && echo "docker OK"

# 5. Confirm the deploy path is a git checkout of THIS repo with a filled-in .env.
cd /opt/delta-precheck-tool && git remote -v && ls -la .env
```

Do not paste the private key into a chat, an issue, or a commit. GitHub's secret store is the only
place it belongs.

If the checkout at step 5 uses an HTTPS remote, the deploy's `git fetch` needs credentials the job
cannot supply. Switch it to SSH with a deploy key, or make the repo's remote one this user can fetch
non-interactively:

```bash
git remote set-url origin git@github.com:lavanyagopasana/delta-precheck-tool.git
```

---

## What a deploy actually does

1. **Configure SSH** — writes the key, pins the host key.
2. **Preflight** — refuses to continue unless: the path exists, docker works, `.env` is present,
   the path is a git checkout, `docker-compose.yml` is not an untracked hand-written file, and no
   tracked file has been edited in place on the server. Nothing has changed yet at this point, so a
   failure here is free.
3. **Database backup** — `pg_dump | gzip` into `db_backups/pre-deploy-<timestamp>-<sha>.sql.gz`,
   keeping the ten most recent. Runs *before* the checkout, deliberately: see below.
4. **Deploy** — records the current SHA to `.deploy-previous-sha`, then
   `git reset --hard <the exact SHA that passed tests>` and `docker compose up -d --build
   --remove-orphans`. It checks out a SHA rather than pulling a branch tip, so what deploys is what
   was tested and not whatever landed on `main` in the meantime.
5. **Health check** — polls the public URL until the frontend returns `200`.
6. **Roll back on failure** — dumps `docker compose ps` and the last 80 log lines, then resets to
   `.deploy-previous-sha` and rebuilds.

### Why the backup runs before the checkout

Because the rollback cannot undo a schema change. `ddl-auto=update` means the new code alters the
live database on startup from the `@Entity` annotations, with no migration file and no
down-migration. Step 6 restores the previous *code*, which then runs against a schema it has never
seen. The dump is the only path back from that.

The step verifies its own output rather than trusting an exit code — `pg_dump`'s status is masked by
the pipe into `gzip`, so it checks that the file is valid gzip and that its first lines actually
contain `PostgreSQL database dump`. An empty backup fails the deploy instead of passing as protection
that was never there. If no database container is running it skips with a warning: expected on a
first deploy, suspicious on any other.

It reads `POSTGRES_USER`/`POSTGRES_DB` out of `.env` with `sed` rather than sourcing the file.
Sourcing would *execute* it, and it holds `POSTGRES_PASSWORD` and `TICKETING_API_TOKEN` — a value
containing a backtick or `$(...)` would run as a command.

Backups land in `db_backups/`, which is already gitignored, so step 4's `git reset --hard` leaves
them alone. They are **not** a substitute for the uploads backup in
[DEPLOY.md](DEPLOY.md#backups): Postgres stores only the *path* to each evidence file, so a database
restored against a wiped uploads volume gives you pre-checks whose attachments 404.

### The health check treats `/api/me` returning 200 as a failure

That is not a mistake. An unauthenticated `GET /api/me` should answer **401**. If it answers `200`,
`AZURE_CLIENT_ID` resolved blank, every `/api` route became `permitAll`, and the backend is serving
the entire application with no authentication at all. It starts cleanly and every page renders in
that state, so nothing else catches it — and it has already happened here once
(`.claude/rules/security-rules.md`). The pipeline fails the deploy and rolls back instead.

---

## Recommended: make production a gated environment

Settings → Environments → `production`:

- **Required reviewers** — every deploy then waits for a human click, and the diff is visible in the
  run before anyone approves it.
- **Deployment branches** — restrict to `main` so a `workflow_dispatch` from a feature branch cannot
  reach production.

Worth doing at least until the pipeline has a few successful runs behind it, given this repo had no
CI of any kind before now.

---

## Suggested first run

1. Open a PR from a feature branch. Only the three test jobs run; `deploy` is skipped. Confirm green.
2. Do the [DEPLOY.md](DEPLOY.md) cutover on the server by hand, and fix `.env`.
3. Add the secrets and the `production` environment with yourself as required reviewer.
4. Trigger **Run workflow** manually with `deploy` ticked, and watch it. This is safer than learning
   whether it works from a merge to `main`.
5. Once that succeeds, merges to `main` deploy on their own.

---

## Not included, on purpose

- **No image registry.** Images build on the server, because both compose files use `build:` rather
  than `image:`. Pushing to GHCR and pulling prebuilt images is faster and makes rollback a tag
  change rather than a rebuild, but it means rewriting `docker-compose.yml` — which is exactly the
  file mid-cutover right now. Worth doing as a follow-up, once the server and the repo agree on one
  compose file.
- **No uploads-volume backup in the pipeline.** The database is dumped on every deploy, but
  `delta_backend_uploads` is not — a tar of the evidence files is slower and much larger, and a code
  deploy cannot corrupt them the way a schema change can corrupt the database. Back it up on its own
  schedule per [DEPLOY.md](DEPLOY.md#backups), and back it up *together with* a database dump.
- **No linting.** `frontend/package.json` has a `lint` script that nothing calls. Easy to add as a
  fifth job if you want it enforced.
- **No test of the SSH/rollback path.** Everything else here was verified locally: both suites run
  green (198 backend, 53 frontend), the YAML parses, every `run` block passes `bash -n`, all four
  preflight guards were simulated against a throwaway repo, and the backup step's extraction,
  dump-validation and retention logic were tested against a fake `.env` and fake dumps. The SSH
  connection, the deploy itself and the rollback cannot be exercised without the real host and
  secrets — run the first one manually via `workflow_dispatch` and watch it.
