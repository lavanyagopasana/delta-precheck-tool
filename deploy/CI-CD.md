# CI/CD

`.github/workflows/ci-cd.yml` is the whole pipeline. Four jobs:

| Job | Runs on | What it proves |
|---|---|---|
| `backend-tests` | every PR and push to `main` | `mvn -B -ntp test` — the JUnit suite against H2 |
| `frontend-tests` | every PR and push to `main` | `react-scripts test` — Jest + React Testing Library |
| `docker-build` | every PR and push to `main` | both images still build (no push to any registry) |
| `deploy` | **manual trigger only**, after all three pass | the stack is live and authentication is enforced |

The deploy job declares `needs` on the other three, so GitHub itself blocks a deploy whose tests
failed.

**Tests and deploys are both automatic on `main`.** A merged PR is a push to `main`, and that deploys once the three test jobs pass. Every other branch and every PR runs the tests only.

**This means any merge restarts production**, including a docs-only one, because the stack rebuilds images on the server and recreates containers. A red test suite still blocks the deploy (`needs`), a failed health check still rolls back automatically, and adding required reviewers to the `production` environment under Settings → Environments puts a human click back in front of every deploy without a code change.
Continuous deployment on every merge is the usual default and it is the wrong default here: this
stack rebuilds images on the server and takes the site through a container recreation, so a docs typo
merged at 6pm would restart production. Deploying is a decision somebody makes.

Three ways to trigger one, in order of convenience:

1. **`/deploy`** in Claude Code — the project skill at `.claude/commands/deploy.md`. It shows you
   what is about to deploy, warns about unpushed commits, dispatches the workflow, watches the run,
   and diagnoses a failure against the known modes rather than just reporting red. Needs the `gh`
   CLI (`gh auth login`).
2. **The Actions tab** — <https://github.com/lavanyagopasana/delta-precheck-tool/actions/workflows/ci-cd.yml>
   → **Run workflow** → tick *Also deploy to production after tests pass*. No CLI needed.
3. **`gh workflow run ci-cd.yml --ref main -f deploy=true`**

Dispatching *without* ticking the box runs the three test jobs and skips the deploy — a way to get a
full test run on demand.

Read this whole file before the first deploy.

---

## Before the first deploy

### The server's `docker-compose.yml` is replaced automatically, once

The file at `/opt/delta-precheck-tool/docker-compose.yml` was written by hand on the server and is
untracked; the repo ships its own at the same path. `git reset --hard` overwrites an untracked file
present in the target commit without saying so, which used to make this a hard stop -- the tracked
file started a `proxy` container binding `:80` and `:443`, and that does not take over from the host
nginx holding those ports, it loses a race against it.

**That is no longer the case.** The proxy service now sits behind a compose profile the deploy never
passes, and the tracked file publishes the same `127.0.0.1:8532` / `127.0.0.1:8533` mappings the
server's own file does. So the swap is like-for-like, and the deploy performs it itself:

1. Copies the existing file to `docker-compose.yml.pre-ci-<timestamp>` -- the only record of what
   production was actually running, and gitignored so it can never be committed back (it carries the
   real Entra ID pair in its frontend build args).
2. Checks out the tracked version.
3. Runs `docker compose build` **while the old stack is still serving traffic**, so the site is not
   down for the length of a full `npm run build`.
4. Runs `docker compose down` against the **old** file, so its containers release the loopback ports
   and their `container_name`s before the new ones claim them. Volumes are untouched.
5. Runs `docker compose up -d` from the already-built images -- a container swap, roughly the time
   Spring takes to start.

No shell access needed. It happens on the first deploy and is a no-op on every one after.

The differences it resolves:

| | Server (hand-written) | This repo |
|---|---|---|
| Database service | `postgres` | `db` |
| Host ports | `127.0.0.1:8532` / `8533` | **the same** |
| TLS termination | nginx on the host | **still nginx on the host** — the proxy container is opt-in |
| Ticketing env | `JIRA_BASE_URL` / `JIRA_EMAIL` / `JIRA_API_TOKEN` | `TICKETING_BASE_URL` / `TICKETING_API_TOKEN` |
| Frontend args | hardcoded, no Hotjar, source maps shipped | from `.env`, `+HOTJAR_SITE_ID`, `GENERATE_SOURCEMAP=false` |
| Entra ID env | not passed at all — the backend used the baked-in defaults | passed from `.env`, since those defaults are now placeholders |
| Volumes | `delta_pg_data`, `delta_backend_uploads` | **the same two names**, matched deliberately |

The volume names are the line that mattered most. They used to differ: the repo declared
`postgres-data`/`backend-uploads`, names that exist nowhere on the server, so `docker compose up`
would have created two empty volumes, Postgres would have initialised a fresh database, and the site
would have come up looking cleanly wiped while the real data sat unreferenced in the old volumes. Do
not "tidy" those names; the `volumes:` block in `docker-compose.yml` explains why at length.

### The server's `.env` still has the Jira variables

Commit `78906d6` replaced Jira with Neutara ticketing. The backend now reads `TICKETING_BASE_URL`
and `TICKETING_API_TOKEN`; `JIRA_*` is read by nothing. Blank `ticketing.api-token` disables ticket
lookup and logs a warning rather than failing, so this will not crash — ticket lookup will just
quietly stop working.

Nothing to do by hand: with `MANAGE_ENV` on, set `ENV_REMOVE_KEYS` to
`JIRA_BASE_URL,JIRA_EMAIL,JIRA_API_TOKEN` and the deploy removes them, while
`TICKETING_API_TOKEN` from Secrets is merged in. Clear `ENV_REMOVE_KEYS` afterwards.

Also note what the server's `.env` has **never** needed and now does: the hand-written compose file
did not pass `AZURE_CLIENT_ID` or `AZURE_TENANT_ID` to the backend at all, because
`application.properties` carried the real values as defaults. Those defaults are placeholders now, so
both must reach the container -- put them in Secrets and the merge step writes them into `.env`. If
they end up blank the backend runs **fully unauthenticated**; the merge step's validation and the
health check each catch that independently and the deploy rolls back.

---

## Repository secrets

Settings → Secrets and variables → Actions → **Secrets** tab.

| Secret | Required | Default if unset | Notes |
|---|---|---|---|
| `DEPLOY_HOST` | **yes** | — | The server's public address. Left out of this file deliberately — see the note below |
| `DEPLOY_SSH_KEY` | **yes** | — | The full **private** key, `-----BEGIN` line through `-----END` line inclusive, with its trailing newline |
| ~~`DEPLOY_USER`~~ | — | — | **Moved to the Variables tab** — see the note below |
| `DEPLOY_PORT` | no | `22` | |
| `DEPLOY_PATH` | no | `/opt/delta-precheck-tool` | Must already be a git checkout with a filled-in `.env` |
| `DEPLOY_KNOWN_HOSTS` | no, but recommended | falls back to `ssh-keyscan` | See the warning below |

**This repository is public.** The host address, SSH port, and deploy username are therefore kept in
secrets and out of this file — not because any of them is a credential (the server's IP is already
discoverable, since the app's own public hostname resolves to it), but because writing "SSH to *this*
address on *this* port as *this* user" into a world-readable file hands over a complete target
description for free. The values live in the secrets above; look them up there.

The same reasoning is why the example values below are placeholders rather than the real ones. The
Entra ID client and tenant IDs are genuinely not secrets — they are compiled into the JavaScript
bundle and served to every visitor, so anyone can read them off the sign-in redirect — but there is
no reason for a public file to hand over the tenant to aim at, and an individual's email address in a
public repo is scraped for spam and names the ADMIN account of a tool holding customer migration
data. Get the real values from GitHub Settings or the app registration.

And under the **Variables** tab:

| Variable | Default if unset | Notes |
|---|---|---|
| `DEPLOY_HEALTH_BASE` | `https://deltaprechecks.cftools.live` | Public base URL the health check probes. The real hostname, not a placeholder — deliberately: it is the address every user types into a browser, so hiding it in a public file buys nothing, and it works as a default so the health check needs no extra variable |

### Secrets vs Variables: workflow logs are public

**On a public repository, workflow logs are readable by anyone.** GitHub renders each step's `env:`
block into the log, and the two stores behave differently there:

| Store | In the repo? | In a public log? |
|---|---|---|
| Secret | no | masked as `***` |
| Variable | no | **plain text** |

So the choice is not about the repository — neither is in it. It is about what a stranger reads in a
build log. Anything identifying belongs in **Secrets**, whatever the "is it really a credential?"
answer is: a value that is not a credential can still be something you would rather not publish.

These four are read as `${{ secrets.X || vars.X }}`, so either store works and Secrets wins when both
are set. Put them in **Secrets**:

| Name | Why not a Variable |
|---|---|
| `APP_FIRST_ADMIN_EMAIL` | a named individual's address, in a log anyone can open |
| `AZURE_CLIENT_ID` | not a credential — it ships in the JavaScript bundle — but no reason to also name the tenant in a public log |
| `AZURE_TENANT_ID` | same |
| `SMTP_USERNAME` | a real mailbox address |

Fine as **Variables**, because nothing identifying appears: `MANAGE_ENV`, `ENV_REMOVE_KEYS`,
`APP_DOMAIN` (the address every user types into a browser), `HOTJAR_SITE_ID`, `TICKETING_BASE_URL`,
`AZURE_REQUIRE_ALLOWLIST`, `AZURE_AUTO_PROVISION_DOMAIN`, `POSTGRES_DB`, `POSTGRES_USER`.

### Why `DEPLOY_USER` is a variable and the rest are secrets

**Workflow logs on a public repository are publicly readable.** Anything stored as a *secret* is
masked as `***` in those logs; anything stored as a *variable* appears in plain text. Neither is in
the repository itself, so the choice is purely about what shows up in a build log a stranger can
open.

`DEPLOY_HOST`, `DEPLOY_PORT` and `DEPLOY_PATH` stay **secrets** for that reason. A public log reading
"ssh to <address> on <port> as <user> in <path>" is a complete target description.

`DEPLOY_USER` is a **variable** because masking it backfired. Its value is the word `deploy`, and
GitHub masks every occurrence of a secret's value anywhere in the output — so `deploy/DEPLOY.md`
rendered as `***/DEPLOY.md`, "re-run this deploy" as "re-run this ***", and even the step title
"refuse to deploy into a state that would break the site" as "refuse to *** into a state...". A
deploy pipeline whose failure messages are unreadable is worse than one that names a generic
username. If you ever set it to something identifying, move it back to Secrets.

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
logging in. Set a repository **variable** `MANAGE_ENV` to `true` and the deploy starts applying
values from GitHub Settings, so routine config changes need no shell.

**It merges, it does not replace.** The `.env` on this server was written by the infrastructure team,
and this step does not take ownership of the whole file. It starts from what is already there,
applies only the keys GitHub actually defines, and leaves everything else exactly as it was —
including their comments and any key this template has never heard of. So you can adopt it
incrementally: a new key goes into Settings alone, and nothing already on the server has to be
migrated first.

The consequence of merging is that **an unset variable means "leave it alone", not "delete it"** — it
has to, or turning `MANAGE_ENV` on with three keys configured would wipe the other fourteen. Deleting
is therefore explicit, via `ENV_REMOVE_KEYS`.

Add these under **Settings ▸ Secrets and variables ▸ Actions**. Non-secret values go in the
**Variables** tab so they stay readable; only real credentials go in **Secrets**.

| Variables tab | Required | Example |
|---|---|---|
| `MANAGE_ENV` | to enable this at all | `true` |
| `ENV_REMOVE_KEYS` | only to delete something | `JIRA_BASE_URL,JIRA_EMAIL,JIRA_API_TOKEN` |
| `APP_DOMAIN` | **yes** | `your-app.example.com` |
| `POSTGRES_DB` | **yes** | `delta_migration_tracker` |
| `POSTGRES_USER` | **yes** | `deltaapp` |
| `AZURE_CLIENT_ID` | **yes** | `00000000-0000-0000-0000-000000000000` |
| `AZURE_TENANT_ID` | **yes** | `00000000-0000-0000-0000-000000000000` |
| `APP_FIRST_ADMIN_EMAIL` | **yes** | `first.admin@yourdomain.com` |
| `AZURE_ALLOWED_EMAIL_DOMAIN` | no | blank |
| `AZURE_REQUIRE_ALLOWLIST` | no | defaults `true` |
| `AZURE_AUTO_PROVISION_DOMAIN` | no | defaults `yourdomain.com` |
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

### Undoing a mistake

**A wrong value** — edit it in Settings and deploy again. The new value overwrites the old one; there
is nothing to clean up.

**A key added by mistake** — say you typo'd `TICKETNG_API_TOKEN` and it is now sitting in the
server's `.env`. Set `ENV_REMOVE_KEYS` to that name and deploy:

```
ENV_REMOVE_KEYS = TICKETNG_API_TOKEN
```

Removals run before overrides, so a key can be removed and re-added in the same run. Listing a key
that isn't there logs a warning rather than failing. Once the key is gone, clear `ENV_REMOVE_KEYS`
again — leaving it set just means the removal is re-attempted (harmlessly) on every deploy.

Worth knowing: **a stray key is inert.** Compose only reads the variables `docker-compose.yml`
actually references, so a misspelled one is never passed to any container. It is clutter, not a
malfunction — which is why removal is a tidy-up rather than an emergency.

**Something worse** — every version of the file is on the server as `.env.bak-<timestamp>` (last
five kept). `cp .env.bak-20260819T101500Z .env` and redeploy.

### What this step does and does not do

- Required values are validated on the **merged result**, not on what GitHub supplied — in merge mode
  a required key is allowed to come from the file the infra team already wrote. A missing or blank
  one fails the deploy and the merged file is discarded, so the server keeps its working `.env`.
- `AZURE_CLIENT_ID` gets a dedicated check because blank is the dangerous case: `SecurityConfig`
  then treats auth as unconfigured and every `/api` route becomes `permitAll`, with no error and no
  failed startup.
- Values are sent over **stdin**, not as command arguments — arguments are visible in `ps` to every
  user on that host while the command runs, and these include the database password.
- The merge is delete-then-append rather than an in-place `sed` substitution, because the replacement
  text would otherwise be interpreted by `sed` and these values are passwords and URLs full of `/`
  and `&`.
- The database backup runs **before** this step, using the old `.env`, so a wrong value in Settings
  cannot break the dump you would need to recover with.
- Re-running with the same settings is a no-op — it reports `.env unchanged` and writes nothing.
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
