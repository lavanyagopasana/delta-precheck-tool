# Deploying

The whole stack is Docker. Database, backend, frontend, and the TLS-terminating proxy all come up
from one file, and every setting that used to be a manual edit on the server is now a tracked file.

```bash
cp .env.example .env      # fill in POSTGRES_PASSWORD, HOTJAR_SITE_ID, TICKETING_API_TOKEN
docker compose up -d --build
```

That is the whole routine deploy. The rest of this file is the one-time cutover from the host
nginx, and the things that will bite you if nobody wrote them down.

---

## First time only: cutting over from the host nginx

Until now an nginx installed directly on the server terminated TLS and routed `/api`. The proxy
container replaces it. Both want port 443, so this is a cutover, not a start.

**Do this in a window where a few minutes of downtime is acceptable.**

### 1. Confirm the certificate exists where the container expects it

```bash
ls /etc/letsencrypt/live/deltaprechecks.cftools.live/fullchain.pem
```

The proxy mounts `/etc/letsencrypt` read-only and will refuse to start without this. Certbot keeps
owning renewal; the container only reads.

### 2. Move any existing uploads onto the volume

Skip only if you are certain no evidence has ever been uploaded. If the backend previously ran
without a mounted volume, its files are inside the old container and will be lost when it is
removed.

```bash
docker cp <old-backend-container>:/data/uploads/. ./uploads-backup/
```

Restore them after step 5:

```bash
docker run --rm -v delta-precheck-tool_backend-uploads:/dest \
  -v "$PWD/uploads-backup":/src alpine sh -c "cp -a /src/. /dest/"
```

Check the volume name first with `docker volume ls` — compose prefixes it with the project
directory name.

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
docker compose up -d --build
docker compose ps
docker compose logs -f proxy
```

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

docker run --rm -v delta-precheck-tool_backend-uploads:/src -v "$PWD":/dest \
  alpine tar czf /dest/uploads-$(date +%F).tar.gz -C /src .
```

**Back both up together.** Postgres holds only the path to each evidence file; the bytes live in
`backend-uploads`. A database restored against a wiped uploads volume gives you pre-checks whose
attachments 404, with no way to tell which were lost.

## Rolling back

```bash
git checkout <previous-tag>
docker compose up -d --build
```

Volumes are untouched by a rebuild, so data survives. The schema is Hibernate `ddl-auto=update`,
which adds columns but never drops them — so rolling the code back is safe, but a schema change is
not reversed by it.
