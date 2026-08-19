# Deploying

The whole stack is Docker. Database, backend, frontend, and the TLS-terminating proxy all come up
from one file, and every setting that used to be a manual edit on the server is now a tracked file.

```bash
cp .env.example .env      # fill in POSTGRES_PASSWORD, HOTJAR_SITE_ID, TICKETING_API_TOKEN
docker compose up -d --build
```

That is the whole routine deploy, and in practice you should not be running it by hand at all --
`deploy/CI-CD.md` covers the pipeline that does it, including the `.env` merge and a database backup.

**The TLS-terminating proxy container does not start by default.** It sits behind a compose profile,
so the stack comes up as database + backend + frontend on `127.0.0.1:8533` and `127.0.0.1:8532`, with
the nginx installed on the host continuing to terminate TLS in front of them -- exactly how production
has always run. That makes a deploy a like-for-like container swap with no fight over port 443.

Enabling the containerised edge is an opt-in cutover, documented below. It is worth doing eventually
-- it is what turns `client_max_body_size`, the CSP and the security headers into version-controlled
files instead of hand-edits on a host with no record of who changed what -- but it needs a downtime
window, so it is a decision rather than a side effect.

---

## Optional: cutting over from the host nginx to the proxy container

The nginx installed directly on the server terminates TLS and routes `/api` today, and nothing in the
deploy pipeline changes that. This section is how you hand that job to the `proxy` container instead.
Both want port 443, so it is a cutover, not a start, and the container only ever starts when you pass
its profile:

```bash
docker compose --profile proxy up -d
```

**Not a prerequisite for deploying.** Skip this whole section and the pipeline works. Do it when you
want the edge configuration under version control, in a window where a few minutes of downtime is
acceptable.

### 1. Confirm the certificate exists where the container expects it

```bash
ls /etc/letsencrypt/live/deltaprechecks.cftools.live/fullchain.pem
```

The proxy mounts `/etc/letsencrypt` read-only and will refuse to start without this. Certbot keeps
owning renewal; the container only reads.

### 2. Confirm the existing volumes are the ones this file will use

**No data migration is needed, and that is on purpose.** `docker-compose.yml` declares
`delta_pg_data` and `delta_backend_uploads` — the same names the hand-written compose file on the
server has been using all along. The stack therefore attaches to the database and uploads you
already have.

That is a constraint, not a coincidence, and this step exists to verify it rather than assume it:

```bash
docker volume ls | grep delta
```

Expect `delta-precheck-tool_delta_pg_data` and `delta-precheck-tool_delta_backend_uploads`. Compose
prefixes every volume with the project name, and `docker-compose.yml` pins that name (`name:
delta-precheck-tool`) rather than letting it be inferred from the directory — so these resolve the
same way no matter where the checkout sits on disk, and moving or renaming the folder no longer
points the stack at a different, empty database.

**If either name is missing, stop.** A name mismatch does not fail and does not warn: compose
creates a new empty volume, Postgres initialises a fresh database, Hibernate builds every table from
the entities, `AdminBootstrap` seeds the first admin into what looks like a brand-new install, and
the site comes up with no projects, no servers, and no `DeltaCycle` history. The real data is still
there in the old volume, unreferenced — recoverable, but only if somebody notices before work gets
entered into the empty one. See the comment in `docker-compose.yml`'s `volumes:` block.

The one case that *does* need a copy is a backend that previously ran with **no** mounted volume at
all: its uploads are inside the container and vanish when it is removed.

```bash
docker cp <old-backend-container>:/data/uploads/. ./uploads-backup/
# then, after step 5:
docker run --rm -v delta-precheck-tool_delta_backend_uploads:/dest \
  -v "$PWD/uploads-backup":/src alpine sh -c "cp -a /src/. /dest/"
```

### 3. Validate the proxy config BEFORE freeing the ports

Do this while the old nginx is still serving traffic. If the template has a typo, you find out now
rather than after the site is already down.

```bash
docker compose build
docker compose run --rm --no-deps --entrypoint sh proxy -c '/docker-entrypoint.sh true && nginx -t'
```

Expect `syntax is ok` and `test is successful`. The entrypoint renders the template first, so this
tests the real substituted config, not the template. A failure here costs nothing; the same failure
after step 5 is an outage.

### 4. Stop the host nginx to free the ports

```bash
sudo systemctl stop nginx
sudo systemctl disable nginx
```

Disable, not just stop. If it restarts on boot it will fight the container for port 443 and
whichever loses stays down.

### 5. Bring the stack up

```bash
docker compose --profile proxy up -d --build
docker compose ps
docker compose logs -f proxy
```

Note the `--profile proxy`. Without it the proxy container is not created at all, and you will have
stopped the host nginx without starting anything to replace it.

### 6. Verify before walking away

```bash
# HTTP/2 negotiated
curl -s -o /dev/null -w '%{http_version}\n' https://deltaprechecks.cftools.live/     # expect 2

# X-Frame-Options appears at most once
curl -sI https://deltaprechecks.cftools.live/api/me | grep -ci x-frame-options       # expect 0 or 1

# HTTP redirects to HTTPS
curl -s -o /dev/null -w '%{http_code}\n' http://deltaprechecks.cftools.live/         # expect 301
```

Then **sign in with a real account**. A CSP that blocks the token endpoint fails only at the token
exchange, and no header check will catch it. If sign-in breaks, `connect-src` in
`deploy/proxy/default.conf.template` is the first thing to look at.

Then **upload a 5MB screenshot as evidence**. This is what `client_max_body_size 25m` exists for.

### 7. Switch certbot to webroot renewal

The container now holds port 80, so `certbot --standalone` can no longer bind it and renewal will
fail silently 90 days from now. The proxy already serves the challenge path from a shared volume.

```bash
sudo certbot certonly --webroot \
  -w /var/lib/docker/volumes/delta-precheck-tool_certbot-webroot/_data \
  -d deltaprechecks.cftools.live \
  --deploy-hook "docker compose -f /path/to/delta-precheck-tool/docker-compose.yml exec proxy nginx -s reload"
```

The deploy hook matters as much as the webroot. Without it the certificate renews on disk and the
running nginx keeps serving the expired one until something restarts it.

**Verify renewal actually works, now, not in 90 days:**

```bash
sudo certbot renew --dry-run
```

---

## Routine operations

| Task | Command |
|---|---|
| Deploy latest code | `docker compose up -d --build` |
| Change the Hotjar Site ID | edit `.env`, then `docker compose up -d --build frontend` |
| Change a backend setting | edit `.env`, then `docker compose up -d backend` |
| Change proxy config | edit the template, then `docker compose restart proxy` |
| Tail logs | `docker compose logs -f backend` |

**A frontend change needs `--build`, not a restart.** Every `REACT_APP_*` is compiled into the
bundle by `npm run build`; restarting the container reruns nothing and changes nothing. This is the
single most common way a config change appears to have no effect.

## Backups

Two volumes hold everything that cannot be rebuilt from git:

```bash
docker compose exec db pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" > backup-$(date +%F).sql

docker run --rm -v delta-precheck-tool_delta_backend_uploads:/src -v "$PWD":/dest \
  alpine tar czf /dest/uploads-$(date +%F).tar.gz -C /src .
```

**Back both up together.** Postgres holds only the path to each evidence file; the bytes live in
`delta_backend_uploads`. A database restored against a wiped uploads volume gives you pre-checks whose
attachments 404, with no way to tell which were lost.

## Rolling back

```bash
git checkout <previous-tag>
docker compose up -d --build
```

Volumes are untouched by a rebuild, so data survives. The schema is Hibernate `ddl-auto=update`,
which adds columns but never drops them — so rolling the code back is safe, but a schema change is
not reversed by it.
