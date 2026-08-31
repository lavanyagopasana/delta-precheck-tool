#!/usr/bin/env bash
#
# Guided wrapper around restore-deleted-projects.sh: loads a pre-deploy backup into a scratch
# database, shows a dry run, asks for confirmation, then restores.
#
# Exists because the alternative was a long multi-line paste into an SSH session, which truncated
# mid-heredoc on the first attempt and left the shell waiting for a terminator. A file fetched by
# URL cannot half-arrive.
#
# USAGE
#   ./guided-restore.sh db_backups/pre-deploy-<...>.sql.gz
#
# Everything before the confirmation prompt is non-destructive: it creates a SEPARATE scratch
# database and reads production only to list project names. The single write to production happens
# after you answer y, inside one transaction.
set -euo pipefail

BACKUP="${1:-}"
RECOVERY_DB="${RECOVERY_DB:-recovery}"
BRANCH="${BRANCH:-feat/poll-delta-phase-only}"
RAW="https://raw.githubusercontent.com/lavanyagopasana/delta-precheck-tool/$BRANCH/deploy"

if [ -z "$BACKUP" ]; then
  echo "usage: $0 db_backups/pre-deploy-<...>.sql.gz" >&2
  echo >&2
  echo "available:" >&2
  ls -1t db_backups/pre-deploy-*.sql.gz 2>/dev/null >&2 || echo "  (none -- wrong directory?)" >&2
  exit 1
fi
[ -f .env ] || { echo "ERROR: no .env here -- run from the deploy directory." >&2; exit 1; }
[ -f "$BACKUP" ] || { echo "ERROR: no such backup: $BACKUP" >&2; exit 1; }

PGU="$(grep -E '^POSTGRES_USER=' .env | cut -d= -f2-)"
: "${PGU:?POSTGRES_USER not found in .env}"
echo "postgres user : $PGU"
echo "backup        : $BACKUP"
echo

echo "== 1/4 scratch database (production untouched) =="
if docker compose exec -T db createdb -U "$PGU" "$RECOVERY_DB" 2>/dev/null; then
  echo "created '$RECOVERY_DB'"
else
  # Reusing is fine and saves reloading on a second run, but a half-loaded database from an
  # interrupted attempt would give a misleading dry run -- so it is dropped and rebuilt.
  echo "'$RECOVERY_DB' exists -- dropping and recreating so the load is known-complete"
  docker compose exec -T db dropdb -U "$PGU" "$RECOVERY_DB"
  docker compose exec -T db createdb -U "$PGU" "$RECOVERY_DB"
fi

echo "== 2/4 loading the backup into it =="
gunzip -c "$BACKUP" | docker compose exec -T db psql -U "$PGU" -d "$RECOVERY_DB" -q
echo "loaded."

echo "== 3/4 fetching the restore script =="
curl -fsSL "$RAW/restore-deleted-projects.sh" -o /tmp/restore-deleted-projects.sh
chmod +x /tmp/restore-deleted-projects.sh
echo "fetched."

echo
echo "== 4/4 DRY RUN -- nothing has been written to production =="
RECOVERY_DB="$RECOVERY_DB" /tmp/restore-deleted-projects.sh

echo
echo "If the list above contains a project you do NOT want (e.g. the seeded demo projects"
echo "'Demo prjct' or 'Mercado'), answer N and report it -- the skip should have excluded them."
echo
read -r -p "Restore the project(s) listed above? [y/N] " ok
case "$ok" in
  y|Y)
    RECOVERY_DB="$RECOVERY_DB" APPLY=1 /tmp/restore-deleted-projects.sh
    echo
    echo "Removing the scratch database..."
    docker compose exec -T db dropdb -U "$PGU" "$RECOVERY_DB"
    echo "Done. Check the app: the project, its servers and its approvals should be back."
    ;;
  *)
    echo "Aborted -- nothing written to production."
    echo "'$RECOVERY_DB' left in place; it is a scratch database and can be dropped with:"
    echo "  docker compose exec -T db dropdb -U $PGU $RECOVERY_DB"
    ;;
esac
