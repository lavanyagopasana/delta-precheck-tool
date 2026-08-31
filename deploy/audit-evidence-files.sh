#!/usr/bin/env bash
#
# READ-ONLY. Answers "which evidence files are actually still on disk?" and reports what backup
# facilities exist that could bring back the ones that are not.
#
# Written 2026-08-29. Deleting a project does not only delete database rows: ServerPurgeService also
# calls fileStorageService.delete() on every precheck_items.evidence_file_path AND every
# delta_cycle_items.evidence_file_path, and that is a real unlink. So a database-only restore brings
# the rows back with their paths intact while the files those paths point at are gone -- the app then
# 404s and AttachmentPreview shows its "can't be shown" card.
#
# Worth running before concluding anything is lost, for one specific reason: FileStorageService.delete
# swallows every IOException and RuntimeException without logging. If some deletes failed (permissions,
# a read-only mount, a path that did not resolve), those files are still there and nothing said so.
#
# USAGE
#   ./audit-evidence-files.sh                        # every project
#   ./audit-evidence-files.sh "city of orange"       # one project (substring, case-insensitive)
#
# With a project given it also prints the exact stored filenames that project references. Those are
# the only files worth pulling out of any filesystem backup that turns up -- stored names are UUIDs,
# so there is no way to guess them from the project or the original upload name.
set -euo pipefail

DB_SERVICE="${DB_SERVICE:-db}"
BACKEND_SERVICE="${BACKEND_SERVICE:-backend}"
UPLOAD_DIR="${UPLOAD_DIR:-/data/uploads}"   # where docker-compose.yml mounts delta_backend_uploads
PROJECT_FILTER="${1:-}"

[ -f .env ] || { echo "ERROR: no .env here -- run from the deploy directory." >&2; exit 1; }
PGU="$(grep -E '^POSTGRES_USER=' .env | cut -d= -f2-)"
PGD="$(grep -E '^POSTGRES_DB=' .env | cut -d= -f2-)"
: "${PGU:?POSTGRES_USER not found in .env}"
: "${PGD:?POSTGRES_DB not found in .env}"

echo "=============================================================="
echo " 1. Evidence files referenced by the database vs present on disk"
echo "=============================================================="

# Both tables, because a decline snapshots the checklist into delta_cycle_items and the live
# precheck_items are wiped -- so the declined attempt's evidence lives ONLY in the snapshot table.
# Dollar-quoted so the project name needs no escaping, and matched with ILIKE on a substring so the
# caller does not have to reproduce an em-dash or the exact full name.
if [ -n "$PROJECT_FILTER" ]; then
  echo "scoped to project matching: $PROJECT_FILTER"
  echo
  SCOPE_LIVE="AND p.name ILIKE \$q\$%$PROJECT_FILTER%\$q\$"
  SCOPE_CYCLE="AND p.name ILIKE \$q\$%$PROJECT_FILTER%\$q\$"
else
  SCOPE_LIVE=""
  SCOPE_CYCLE=""
fi

# Joined out to projects even in the unscoped case, so both branches run the identical query shape --
# a scoped run that used a different query could disagree with the unscoped one about the same file.
REFERENCED="$(docker compose exec -T "$DB_SERVICE" psql -U "$PGU" -d "$PGD" -Atc "
  SELECT DISTINCT i.evidence_file_path
  FROM precheck_items i
  JOIN workspace_combinations c ON c.id = i.combination_id
  JOIN servers s ON s.id = c.server_id
  JOIN projects p ON p.id = s.project_id
  WHERE i.evidence_file_path IS NOT NULL $SCOPE_LIVE
  UNION
  SELECT DISTINCT ci.evidence_file_path
  FROM delta_cycle_items ci
  JOIN delta_cycles dc ON dc.id = ci.cycle_id
  JOIN workspace_combinations c2 ON c2.id = dc.combination_id
  JOIN servers s2 ON s2.id = c2.server_id
  JOIN projects p ON p.id = s2.project_id
  WHERE ci.evidence_file_path IS NOT NULL $SCOPE_CYCLE
  ORDER BY 1;")"

REF_COUNT="$(printf '%s\n' "$REFERENCED" | grep -c . || true)"
echo "referenced by database : $REF_COUNT file(s)"

ON_DISK="$(docker compose exec -T "$BACKEND_SERVICE" sh -lc "ls -1 '$UPLOAD_DIR' 2>/dev/null" || true)"
DISK_COUNT="$(printf '%s\n' "$ON_DISK" | grep -c . || true)"
echo "present in $UPLOAD_DIR : $DISK_COUNT file(s)"
echo

printf '%s\n' "$ON_DISK" | sed 's#^#/uploads/#' | sort -u > /tmp/.disk.$$
printf '%s\n' "$REFERENCED" | sort -u > /tmp/.ref.$$
trap 'rm -f /tmp/.disk.$$ /tmp/.ref.$$' EXIT

MISSING="$(comm -23 /tmp/.ref.$$ /tmp/.disk.$$ | grep -c . || true)"
PRESENT="$(comm -12 /tmp/.ref.$$ /tmp/.disk.$$ | grep -c . || true)"
echo "referenced AND present : $PRESENT"
echo "referenced but MISSING : $MISSING"
echo

if [ -n "$PROJECT_FILTER" ]; then
  echo "Every evidence file this project references:"
  echo "(LIVE = current checklist, DECLINED = frozen snapshot of a declined attempt)"
  echo
  printf '%-46s %-22s %-12s %s\n' "STORED FILE" "ITEM" "WHERE" "ON DISK?"
  while IFS='|' read -r path item where; do
    [ -z "$path" ] && continue
    if grep -qxF "$path" /tmp/.disk.$$; then state="yes"; else state="MISSING"; fi
    printf '%-46s %-22s %-12s %s\n' "${path#/uploads/}" "$(echo "$item" | cut -c1-22)" "$where" "$state"
  done <<< "$(docker compose exec -T "$DB_SERVICE" psql -U "$PGU" -d "$PGD" -Atc "
    SELECT i.evidence_file_path || '|' || i.item_name || '|LIVE'
    FROM precheck_items i
    JOIN workspace_combinations c ON c.id = i.combination_id
    JOIN servers s ON s.id = c.server_id
    JOIN projects p ON p.id = s.project_id
    WHERE i.evidence_file_path IS NOT NULL $SCOPE_LIVE
    UNION ALL
    SELECT ci.evidence_file_path || '|' || ci.item_name || '|DECLINED'
    FROM delta_cycle_items ci
    JOIN delta_cycles dc ON dc.id = ci.cycle_id
    JOIN workspace_combinations c2 ON c2.id = dc.combination_id
    JOIN servers s2 ON s2.id = c2.server_id
    JOIN projects p ON p.id = s2.project_id
    WHERE ci.evidence_file_path IS NOT NULL $SCOPE_CYCLE
    ORDER BY 3, 2;")"
  echo
  echo "The MISSING rows above are the only files that need recovering. Stored names are UUIDs, so"
  echo "this list is the only way to know which files to pull out of a backup."
  echo
elif [ "$MISSING" != "0" ]; then
  echo "Which projects are affected:"
  docker compose exec -T "$DB_SERVICE" psql -U "$PGU" -d "$PGD" -c "
    SELECT p.name AS project,
           count(*) FILTER (WHERE i.evidence_file_path IS NOT NULL) AS evidence_refs
    FROM precheck_items i
    JOIN workspace_combinations c ON c.id = i.combination_id
    JOIN servers s ON s.id = c.server_id
    JOIN projects p ON p.id = s.project_id
    WHERE i.evidence_file_path IS NOT NULL
    GROUP BY p.name
    UNION ALL
    SELECT p.name || ' (declined snapshot)',
           count(*) FILTER (WHERE ci.evidence_file_path IS NOT NULL)
    FROM delta_cycle_items ci
    JOIN delta_cycles dc ON dc.id = ci.cycle_id
    JOIN workspace_combinations c2 ON c2.id = dc.combination_id
    JOIN servers s2 ON s2.id = c2.server_id
    JOIN projects p ON p.id = s2.project_id
    WHERE ci.evidence_file_path IS NOT NULL
    GROUP BY p.name
    ORDER BY 1;"
  echo
fi

echo "=============================================================="
echo " 2. Anything that could restore the missing files"
echo "=============================================================="
echo "The pre-deploy backups are pg_dump only -- they contain NO files, so they cannot help here."
echo "A filesystem-level backup or snapshot of the uploads volume is the only route."
echo

VOL="$(docker volume ls -q | grep -i 'backend_uploads' || true)"
echo "-- uploads volume --"
if [ -n "$VOL" ]; then
  for v in $VOL; do
    echo "  $v"
    docker volume inspect "$v" --format '    mountpoint: {{ .Mountpoint }}{{ println }}    created:    {{ .CreatedAt }}' 2>/dev/null || true
  done
  echo "  (a Docker volume keeps no history of its own -- an unlinked file is simply gone from it)"
else
  echo "  none found matching *backend_uploads*"
fi
echo

echo "-- backup tooling installed --"
found=0
for tool in restic borg borgmatic duplicity duplicati rsnapshot rdiff-backup bacula-fd veeamconfig timeshift; do
  if command -v "$tool" >/dev/null 2>&1; then echo "  FOUND: $tool"; found=1; fi
done
[ "$found" = "0" ] && echo "  none of the common backup tools are installed"
echo

echo "-- filesystem snapshot capability --"
if command -v lvs >/dev/null 2>&1 && lvs 2>/dev/null | grep -qi snap; then
  echo "  LVM snapshots present:"; lvs 2>/dev/null | sed 's/^/    /'
elif command -v zfs >/dev/null 2>&1; then
  echo "  ZFS present; snapshots:"; zfs list -t snapshot 2>/dev/null | head -20 | sed 's/^/    /'
elif command -v btrfs >/dev/null 2>&1 && btrfs subvolume list / >/dev/null 2>&1; then
  echo "  btrfs subvolumes:"; btrfs subvolume list / 2>/dev/null | head -20 | sed 's/^/    /'
else
  echo "  no LVM/ZFS/btrfs snapshot capability detected"
fi
echo

echo "-- scheduled jobs mentioning backup/rsync/snapshot --"
{ crontab -l 2>/dev/null; cat /etc/crontab 2>/dev/null; cat /etc/cron.*/* 2>/dev/null; } \
  | grep -Ei 'backup|rsync|snapshot|restic|borg|dump' | grep -v '^#' | head -10 | sed 's/^/  /' \
  || echo "  none found"
echo

echo "-- /var/backups and any obvious backup directories --"
ls -lh /var/backups 2>/dev/null | head -10 | sed 's/^/  /' || echo "  /var/backups absent or empty"
find / -maxdepth 3 -type d \( -iname '*backup*' -o -iname '*snapshot*' \) 2>/dev/null \
  | grep -v -e '^/proc' -e '^/sys' -e 'delta-precheck-tool/db_backups' | head -10 | sed 's/^/  /' || true
echo

echo "=============================================================="
echo "Nothing was modified. This was a read-only audit."
echo
echo "If section 2 found no filesystem backup, the missing evidence images are not recoverable"
echo "from anything on this server. Everything else about those pre-checks survived: item"
echo "statuses, notes, who declined and why."
echo "=============================================================="
