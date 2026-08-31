#!/usr/bin/env bash
#
# READ-ONLY. Reports what a pre-deploy backup contains and which of those projects are missing from
# production right now. It writes NOTHING -- no database is created, loaded or modified, and the only
# thing it asks production for is a list of project names.
#
# Run this before restore-deleted-projects.sh, to confirm the deleted work is actually recoverable
# before anyone changes anything.
#
# USAGE
#   ./inspect-backup.sh                                  # list the available backups and stop
#   ./inspect-backup.sh db_backups/pre-deploy-<...>.sql.gz
#
# Reads the gzipped dump directly with awk. The dump is plain SQL (the workflow pipes pg_dump into
# gzip with no -Fc), so every table arrives as a COPY block and can be counted without a database.
set -euo pipefail

DB_SERVICE="${DB_SERVICE:-db}"
BACKUP="${1:-}"

if [ ! -f .env ]; then
  echo "ERROR: no .env here. Run this from the deploy directory (where docker-compose.yml is)." >&2
  exit 1
fi

if [ -z "$BACKUP" ]; then
  echo "Available backups (newest first). Pick the newest one from BEFORE the deletions:"
  echo
  ls -lht db_backups/pre-deploy-*.sql.gz 2>/dev/null || {
    echo "  none found -- check you are in the deploy directory" >&2; exit 1; }
  echo
  echo "Then re-run:  $0 db_backups/pre-deploy-<...>.sql.gz"
  exit 0
fi

[ -f "$BACKUP" ] || { echo "ERROR: no such file: $BACKUP" >&2; exit 1; }
gzip -t "$BACKUP" || { echo "ERROR: $BACKUP is not a valid gzip file" >&2; exit 1; }

echo "backup : $BACKUP"
echo "taken  : $(date -r "$BACKUP" '+%Y-%m-%d %H:%M:%S %Z' 2>/dev/null || stat -c '%y' "$BACKUP")"
echo

# --- what production holds now (a single SELECT -- no writes) ------------------------------------
PGUSER="$(grep -E '^POSTGRES_USER=' .env | cut -d= -f2-)"
PGDB="$(grep -E '^POSTGRES_DB=' .env | cut -d= -f2-)"
LIVE_NAMES="$(docker compose exec -T "$DB_SERVICE" \
  psql -U "$PGUSER" -d "$PGDB" -Atc "SELECT name FROM projects ORDER BY name;" || true)"
LIVE_COUNT="$(printf '%s\n' "$LIVE_NAMES" | grep -c . || true)"
echo "production currently holds $LIVE_COUNT project(s)."
echo

printf '%s\n' "$LIVE_NAMES" > /tmp/.live_project_names.$$
trap 'rm -f /tmp/.live_project_names.$$' EXIT

# --- what the backup holds ----------------------------------------------------------------------
# One pass, collecting every COPY block into arrays and reporting at the end, because pg_dump emits
# tables alphabetically -- workspace_combinations lands after sign_offs, so counts cannot be resolved
# on the fly.
gunzip -c "$BACKUP" | awk -v livefile="/tmp/.live_project_names.$$" '
  function idx(hdr, want,   n, parts, i) {
    n = split(hdr, parts, ", ")
    for (i = 1; i <= n; i++) { gsub(/^ +| +$/, "", parts[i]); if (parts[i] == want) return i }
    return 0
  }
  BEGIN {
    while ((getline line < livefile) > 0) if (length(line)) live[line] = 1
  }
  # COPY public.projects (id, name, ...) FROM stdin;
  /^COPY / {
    tbl = $2; sub(/^public\./, "", tbl)
    hdr = $0; sub(/^[^(]*\(/, "", hdr); sub(/\).*$/, "", hdr)
    cur = tbl
    if (tbl == "projects")               { c_id = idx(hdr,"id"); c_name = idx(hdr,"name"); c_ph = idx(hdr,"external_phase"); c_by = idx(hdr,"created_by") }
    else if (tbl == "servers")           { s_id = idx(hdr,"id"); s_pid = idx(hdr,"project_id") }
    else if (tbl == "workspace_combinations") { w_id = idx(hdr,"id"); w_sid = idx(hdr,"server_id") }
    else if (tbl == "sign_offs")         { o_cid = idx(hdr,"combination_id") }
    else if (tbl == "delta_cycles")      { d_cid = idx(hdr,"combination_id") }
    else if (tbl == "precheck_items")    { p_cid = idx(hdr,"combination_id") }
    next
  }
  cur != "" && $0 == "\\." { cur = ""; next }
  cur == "projects"  { pname[$c_id] = $c_name; pphase[$c_id] = $c_ph; pby[$c_id] = (c_by ? $c_by : "?"); next }
  cur == "servers"   { sproj[$s_id] = $s_pid; nserv[$s_pid]++; next }
  cur == "workspace_combinations" { wserv[$w_id] = $w_sid; next }
  cur == "sign_offs"      { so[$o_cid]++; next }
  cur == "delta_cycles"   { dc[$d_cid]++; next }
  cur == "precheck_items" { pi[$p_cid]++; next }
  END {
    # Roll the per-combination counts up to their project.
    for (c in wserv) { pj = sproj[wserv[c]]
      if (c in so) approvals[pj] += so[c]
      if (c in dc) cycles[pj]    += dc[c]
      if (c in pi) items[pj]     += pi[c]
    }
    print "PROJECTS IN THE BACKUP THAT ARE MISSING FROM PRODUCTION"
    print "(these are the deletions -- \"restore?\" applies your rule: has servers, or PMO phase DELTA)"
    print ""
    printf "%-36s %-22s %-13s %7s %9s %6s %6s  %s\n", "PROJECT", "CREATED BY", "PMO PHASE", "SERVERS", "APPROVALS", "CYCLES", "ITEMS", "RESTORE?"
    n = 0; recoverable = 0
    for (id in pname) {
      nm = pname[id]
      if (nm in live) continue                      # still in production, not deleted
      n++
      sv = (id in nserv) ? nserv[id] : 0
      ap = (id in approvals) ? approvals[id] : 0
      cy = (id in cycles) ? cycles[id] : 0
      it = (id in items) ? items[id] : 0
      ph = (pphase[id] == "\\N" || pphase[id] == "") ? "-" : pphase[id]
      want = (sv > 0 || toupper(ph) == "DELTA") ? "YES" : "no (empty, non-Delta)"
      if (want == "YES") recoverable++
      by = (pby[id] == "\\N" || pby[id] == "") ? "-" : pby[id]
      printf "%-36s %-22s %-13s %7d %9d %6d %6d  %s\n", substr(nm,1,36), substr(by,1,22), substr(ph,1,13), sv, ap, cy, it, want
    }
    print ""
    if (n == 0) {
      print "Nothing is missing -- every project in this backup is still in production."
      print "If you expected deletions here, this backup was taken BEFORE them; try an older one."
    } else {
      printf "%d project(s) in this backup are no longer in production.\n", n
      printf "%d of them match the restore rule and would be restored.\n", recoverable
      print ""
      print "Rows with non-zero APPROVALS / CYCLES / ITEMS are the ones holding real engineer work."
      print "CYCLES includes declined approvals -- a decline is snapshotted into delta_cycles."
      print ""
      print "CREATED BY is the person who made the project by hand in this app. Anything mirrored"
      print "from the PMO tool says \"PMO sync\" instead."
    }
  }
'
echo
echo "Nothing was modified. This was a read-only inspection."
