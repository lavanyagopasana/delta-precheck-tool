#!/usr/bin/env bash
#
# Restore projects that were deleted by hand from production, out of a pre-deploy backup, WITHOUT
# rolling anything else back.
#
# Written 2026-08-29 after projects were deleted from the deployed app while clearing out the
# non-Delta PMO imports -- the intent was "projects with 0 servers, or not in Delta phase", and some
# projects that had real servers, pre-checks and approvals went with them.
#
# WHY NOT JUST RESTORE THE DUMP
# Loading the dump over production would roll the whole database back to the moment the backup was
# taken, discarding every checklist edit, approval and PMO sync since. This copies only the missing
# projects and their subtrees across, leaving everything else exactly as it is.
#
# WHAT IT SELECTS
#   a project present in the backup, ABSENT from production now, and either
#     * it has at least one server, or
#     * its PMO phase is DELTA
# The two halves of the maintainer's rule. A project with no servers AND not in Delta phase is left
# deleted, because that is what the cleanup was actually for.
#
# PREREQUISITES
#   1. The backup is already loaded into a scratch database next to production:
#        createdb -U "$PGUSER" "$RECOVERY_DB"
#        gunzip -c db_backups/pre-deploy-<...>.sql.gz | psql -U "$PGUSER" -d "$RECOVERY_DB"
#   2. Run this from the deploy directory, where docker-compose.yml is.
#
# USAGE
#   ./restore-deleted-projects.sh                 # dry run: reports what it WOULD restore
#   APPLY=1 ./restore-deleted-projects.sh         # actually writes
#
# It is safe to run the dry run repeatedly. The apply is a single transaction: either every table
# lands or none does, so a failure part-way cannot leave a project with servers but no checklist.
set -euo pipefail

RECOVERY_DB="${RECOVERY_DB:-recovery}"
APPLY="${APPLY:-0}"
DB_SERVICE="${DB_SERVICE:-db}"

# Names SampleProjectBootstrap invents. They pass the "has servers" test -- the seeding gives them
# servers, combinations and delta-cycle history -- but restoring them would put demo data back into
# production, which is the opposite of what anyone recovering real work wants. Skipped by default;
# set SKIP_SAMPLE=0 to include them (only useful on a demo instance).
SKIP_SAMPLE="${SKIP_SAMPLE:-1}"
SAMPLE_NAMES="${SAMPLE_NAMES:-Demo prjct,Mercado}"

# Optional allowlist of exact project names, comma-separated. Empty means "every qualifying project".
# Use it to restore one specific project rather than the whole qualifying set.
ONLY_PROJECTS="${ONLY_PROJECTS:-}"

# Read the credentials the stack itself uses, rather than asking for them again and risking a
# mismatch with the database the app is actually talking to.
if [ ! -f .env ]; then
  echo "ERROR: no .env here. Run this from the deploy directory (where docker-compose.yml is)." >&2
  exit 1
fi
PGUSER="$(grep -E '^POSTGRES_USER=' .env | cut -d= -f2-)"
PGDB="$(grep -E '^POSTGRES_DB=' .env | cut -d= -f2-)"
: "${PGUSER:?POSTGRES_USER not found in .env}"
: "${PGDB:?POSTGRES_DB not found in .env}"

psql_run() {  # psql_run <database> <sql>
  docker compose exec -T "$DB_SERVICE" psql -U "$PGUSER" -d "$1" -v ON_ERROR_STOP=1 -Atc "$2"
}

echo "production database : $PGDB"
echo "backup database     : $RECOVERY_DB"
echo "mode                : $([ "$APPLY" = "1" ] && echo APPLY || echo 'DRY RUN (set APPLY=1 to write)')"
[ "$SKIP_SAMPLE" = "1" ] && echo "skipping demo data  : $SAMPLE_NAMES"
[ -n "$ONLY_PROJECTS" ] && echo "restricted to       : $ONLY_PROJECTS"
echo

# --- which projects come back -----------------------------------------------------------------
# Computed in the backup database, minus whatever production already holds. Cross-database queries
# need an extension Postgres does not ship enabled, so the live id list is fetched first and passed
# in -- there are at most a couple of hundred, well inside a sane IN list.
LIVE_IDS="$(psql_run "$PGDB" "SELECT COALESCE(string_agg(id::text, ','), '-1') FROM projects;")"

# Built as SQL text so the exclusions are visible in one place rather than being applied later and
# leaving the dry run reporting a different set from what the apply would write.
SAMPLE_CLAUSE=""
if [ "$SKIP_SAMPLE" = "1" ]; then
  quoted=""
  IFS=',' read -ra names <<< "$SAMPLE_NAMES"
  for nm in "${names[@]}"; do
    nm="$(echo "$nm" | sed 's/^ *//; s/ *$//')"
    [ -z "$nm" ] && continue
    quoted="$quoted${quoted:+, }'${nm//'/''}'"
  done
  [ -n "$quoted" ] && SAMPLE_CLAUSE="AND p.name NOT IN ($quoted)"
fi

ONLY_CLAUSE=""
if [ -n "$ONLY_PROJECTS" ]; then
  quoted=""
  IFS=',' read -ra only <<< "$ONLY_PROJECTS"
  for nm in "${only[@]}"; do
    nm="$(echo "$nm" | sed 's/^ *//; s/ *$//')"
    [ -z "$nm" ] && continue
    quoted="$quoted${quoted:+, }'${nm//'/''}'"
  done
  [ -n "$quoted" ] && ONLY_CLAUSE="AND p.name IN ($quoted)"
fi

SELECT_MISSING="
  SELECT p.id
  FROM projects p
  WHERE p.id NOT IN ($LIVE_IDS)
    AND (
      EXISTS (SELECT 1 FROM servers s WHERE s.project_id = p.id)
      OR upper(coalesce(p.external_phase, '')) = 'DELTA'
    )
    $SAMPLE_CLAUSE
    $ONLY_CLAUSE
"

PROJECT_IDS="$(psql_run "$RECOVERY_DB" "SELECT COALESCE(string_agg(id::text, ','), '') FROM ($SELECT_MISSING) q;")"

if [ -z "$PROJECT_IDS" ]; then
  echo "Nothing to restore: every qualifying project in the backup is already in production."
  exit 0
fi

echo "Projects to restore:"
docker compose exec -T "$DB_SERVICE" psql -U "$PGUSER" -d "$RECOVERY_DB" -c "
  SELECT p.id,
         p.name,
         coalesce(p.external_phase, '-')                                   AS pmo_phase,
         (SELECT count(*) FROM servers s WHERE s.project_id = p.id)        AS servers,
         (SELECT count(*) FROM sign_offs so
            JOIN workspace_combinations c ON c.id = so.combination_id
            JOIN servers s2 ON s2.id = c.server_id
          WHERE s2.project_id = p.id)                                      AS approvals,
         (SELECT count(*) FROM delta_cycles dc
            JOIN workspace_combinations c2 ON c2.id = dc.combination_id
            JOIN servers s3 ON s3.id = c2.server_id
          WHERE s3.project_id = p.id)                                      AS delta_cycles
  FROM projects p
  WHERE p.id IN ($PROJECT_IDS)
  ORDER BY p.name;"
echo

# A project whose NAME is already taken in production cannot be restored under its old row: `name`
# is UNIQUE, and the usual cause is the PMO sync having recreated it under a new id. Reported rather
# than force-renamed, because merging the two rows is a judgement call about which one holds the work.
CLASHES="$(psql_run "$RECOVERY_DB" "SELECT COALESCE(string_agg(name, ' | '), '') FROM projects WHERE id IN ($PROJECT_IDS);")"
if [ -n "$CLASHES" ]; then
  while IFS= read -r nm; do
    [ -z "$nm" ] && continue
    taken="$(psql_run "$PGDB" "SELECT count(*) FROM projects WHERE lower(name) = lower('${nm//\'/\'\'}');")"
    if [ "$taken" != "0" ]; then
      echo "WARNING: \"$nm\" already exists in production under a different id -- skipping it." >&2
      echo "         Decide by hand which row holds the real work before restoring this one." >&2
      PROJECT_IDS="$(psql_run "$RECOVERY_DB" "SELECT COALESCE(string_agg(id::text, ','), '') FROM projects WHERE id IN ($PROJECT_IDS) AND lower(name) <> lower('${nm//\'/\'\'}');")"
    fi
  done <<< "$(psql_run "$RECOVERY_DB" "SELECT name FROM projects WHERE id IN ($PROJECT_IDS);")"
fi
[ -z "$PROJECT_IDS" ] && { echo "Every candidate clashed on name. Nothing to do."; exit 0; }

SERVER_IDS="$(psql_run "$RECOVERY_DB" "SELECT COALESCE(string_agg(id::text, ','), '-1') FROM servers WHERE project_id IN ($PROJECT_IDS);")"
COMBO_IDS="$(psql_run "$RECOVERY_DB" "SELECT COALESCE(string_agg(id::text, ','), '-1') FROM workspace_combinations WHERE server_id IN ($SERVER_IDS);")"
CYCLE_IDS="$(psql_run "$RECOVERY_DB" "SELECT COALESCE(string_agg(id::text, ','), '-1') FROM delta_cycles WHERE combination_id IN ($COMBO_IDS);")"

# --- what moves, and in what order -------------------------------------------------------------
# Parents before children, so no insert lands before the row it references. Reversing this order or
# dropping a line leaves dangling references that the app reads as a project with servers but no
# checklist -- which is exactly what an interrupted manual restore looks like.
TABLES=(
  "projects|id IN ($PROJECT_IDS)"
  "project_engineers|project_id IN ($PROJECT_IDS)"
  "project_metabase_databases|project_id IN ($PROJECT_IDS)"
  "servers|project_id IN ($PROJECT_IDS)"
  "workspace_pairs|server_id IN ($SERVER_IDS)"
  "workspace_combinations|server_id IN ($SERVER_IDS)"
  "precheck_items|combination_id IN ($COMBO_IDS)"
  "precheck_submissions|combination_id IN ($COMBO_IDS)"
  "sign_offs|combination_id IN ($COMBO_IDS)"
  "tickets|combination_id IN ($COMBO_IDS)"
  "delta_cycles|combination_id IN ($COMBO_IDS)"
  "delta_cycle_items|cycle_id IN ($CYCLE_IDS)"
  "delta_cycle_signoffs|cycle_id IN ($CYCLE_IDS)"
)

# Columns are intersected between the two databases rather than using SELECT *. The backup predates
# whatever has been deployed since, so a column added in the meantime exists in production and not in
# the dump -- and a COPY with mismatched column counts fails halfway through the restore.
columns_for() {
  local tbl="$1"
  psql_run "$RECOVERY_DB" "
    SELECT string_agg(quote_ident(column_name), ', ' ORDER BY ordinal_position)
    FROM information_schema.columns
    WHERE table_schema = current_schema() AND table_name = '$tbl'
      AND column_name IN (
        SELECT column_name FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = '$tbl'
      );"
}
prod_columns_for() {
  psql_run "$PGDB" "
    SELECT string_agg(quote_ident(column_name), ',' ORDER BY column_name)
    FROM information_schema.columns
    WHERE table_schema = current_schema() AND table_name = '$1';"
}

echo "Row counts to copy:"
TOTAL=0
for entry in "${TABLES[@]}"; do
  tbl="${entry%%|*}"; where="${entry#*|}"
  n="$(psql_run "$RECOVERY_DB" "SELECT count(*) FROM $tbl WHERE $where;")"
  printf '  %-30s %s\n' "$tbl" "$n"
  TOTAL=$((TOTAL + n))
done
echo "  total rows: $TOTAL"
echo

if [ "$APPLY" != "1" ]; then
  echo "DRY RUN -- nothing written. Re-run with APPLY=1 to restore."
  exit 0
fi

# --- the write ---------------------------------------------------------------------------------
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

for entry in "${TABLES[@]}"; do
  tbl="${entry%%|*}"; where="${entry#*|}"
  recov_cols="$(psql_run "$RECOVERY_DB" "
    SELECT string_agg(quote_ident(c.column_name), ', ' ORDER BY c.ordinal_position)
    FROM information_schema.columns c
    WHERE c.table_schema = current_schema() AND c.table_name = '$tbl';")"
  prod_cols="$(prod_columns_for "$tbl")"
  # Keep only columns both sides have, in the backup's own order.
  shared="$(python3 - "$recov_cols" "$prod_cols" <<'PY'
import sys
recov = [c.strip() for c in sys.argv[1].split(',') if c.strip()]
prod = {c.strip() for c in sys.argv[2].split(',') if c.strip()}
print(', '.join(c for c in recov if c in prod))
PY
)"
  [ -z "$shared" ] && { echo "ERROR: no shared columns for $tbl" >&2; exit 1; }
  docker compose exec -T "$DB_SERVICE" psql -U "$PGUSER" -d "$RECOVERY_DB" -v ON_ERROR_STOP=1 \
    -c "\copy (SELECT $shared FROM $tbl WHERE $where) TO STDOUT WITH (FORMAT csv)" > "$STAGE/$tbl.csv"
  echo "$shared" > "$STAGE/$tbl.cols"
done

# One transaction for the whole restore: a project must not arrive without its checklist.
{
  echo "BEGIN;"
  for entry in "${TABLES[@]}"; do
    tbl="${entry%%|*}"
    cols="$(cat "$STAGE/$tbl.cols")"
    echo "\\copy $tbl ($cols) FROM '/tmp/restore/$tbl.csv' WITH (FORMAT csv)"
  done
  # Explicit ids were inserted, so every id sequence is now behind the data and the next insert from
  # the app would collide. Only tables with their own id sequence need this -- project_engineers is a
  # plain join table with no surrogate key.
  for tbl in projects servers workspace_combinations workspace_pairs precheck_items \
             precheck_submissions sign_offs tickets delta_cycles delta_cycle_items \
             delta_cycle_signoffs project_metabase_databases; do
    echo "SELECT setval(pg_get_serial_sequence('$tbl', 'id'), GREATEST((SELECT COALESCE(max(id), 1) FROM $tbl), 1));"
  done
  echo "COMMIT;"
} > "$STAGE/load.sql"

docker compose exec -T "$DB_SERVICE" mkdir -p /tmp/restore
for f in "$STAGE"/*.csv; do
  docker compose exec -T "$DB_SERVICE" sh -c "cat > /tmp/restore/$(basename "$f")" < "$f"
done
docker compose exec -T "$DB_SERVICE" psql -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 < "$STAGE/load.sql"
docker compose exec -T "$DB_SERVICE" rm -rf /tmp/restore

echo
echo "Restored. Verify in the app: the projects should be back with their servers, and the approvals"
echo "should reappear on the Approvals page (declined ones included -- those live in delta_cycles)."
echo "The backend does not need restarting; it reads this data per request."
