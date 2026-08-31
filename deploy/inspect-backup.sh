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
# Ids AND names. Matching on name alone hid a whole category: a project deleted while in Delta
# phase is re-created by the PMO poll within five minutes under the SAME NAME but a NEW id, with
# no servers and no approvals. By name that looks present and healthy; by id the original -- and
# all the work hanging off it -- is gone.
LIVE_ROWS="$(docker compose exec -T "$DB_SERVICE" \
  psql -U "$PGUSER" -d "$PGDB" -Atc \
  "SELECT id || E'\\t' || name FROM projects ORDER BY name;" || true)"
LIVE_COUNT="$(printf '%s\n' "$LIVE_ROWS" | grep -c . || true)"
echo "production currently holds $LIVE_COUNT project(s)."
echo

printf '%s\n' "$LIVE_ROWS" > /tmp/.live_projects.$$
trap 'rm -f /tmp/.live_projects.$$' EXIT

# --- what the backup holds ----------------------------------------------------------------------
# One pass, collecting every COPY block into arrays and reporting at the end, because pg_dump emits
# tables alphabetically -- workspace_combinations lands after sign_offs, so counts cannot be resolved
# on the fly.
gunzip -c "$BACKUP" | awk -v livefile="/tmp/.live_projects.$$" '
  function idx(hdr, want,   n, parts, i) {
    n = split(hdr, parts, ", ")
    for (i = 1; i <= n; i++) { gsub(/^ +| +$/, "", parts[i]); if (parts[i] == want) return i }
    return 0
  }
  BEGIN {
    FS = "\t"
    while ((getline line < livefile) > 0) {
      if (!length(line)) continue
      split(line, kv, "\t")
      liveid[kv[1]] = 1          # this exact row survived
      livename[kv[2]] = 1        # some row with this name exists
    }
  }
  # COPY public.projects (id, name, ...) FROM stdin;
  /^COPY / {
    # Parsed out of $0, not $2: FS is a tab so the data rows split correctly (project names
    # contain spaces, and whitespace splitting silently misaligned every field), but the COPY
    # header line has no tabs at all -- under that FS the whole line is $1 and $2 is empty.
    tbl = $0; sub(/^COPY +/, "", tbl); sub(/[ (].*$/, "", tbl); sub(/^public\./, "", tbl)
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
    n = 0; recoverable = 0; reshelled = 0; lostwork = 0
    # Ordered by server count descending, so the rows carrying the most work are read first
    # rather than being buried in the arbitrary array order awk would otherwise use.
    for (id in pname) { if (id in liveid) continue; ord[++n] = id }
    for (a = 1; a <= n; a++) for (b = a + 1; b <= n; b++) {
      sa = (ord[a] in nserv) ? nserv[ord[a]] : 0
      sb = (ord[b] in nserv) ? nserv[ord[b]] : 0
      if (sb > sa) { t = ord[a]; ord[a] = ord[b]; ord[b] = t }
    }
    for (k = 1; k <= n; k++) {
      id = ord[k]; nm = pname[id]
      sv = (id in nserv) ? nserv[id] : 0
      ap = (id in approvals) ? approvals[id] : 0
      cy = (id in cycles) ? cycles[id] : 0
      it = (id in items) ? items[id] : 0
      ph = (pphase[id] == "\\N" || pphase[id] == "") ? "-" : pphase[id]
      by = (pby[id] == "\\N" || pby[id] == "") ? "-" : pby[id]
      qualifies = (sv > 0 || toupper(ph) == "DELTA")
      if (nm in livename) {
        # Name is back but this row is not: the PMO poll recreated an empty shell.
        want = qualifies ? "YES (name reused - see below)" : "no (empty, non-Delta)"
        reshelled++
        if (sv > 0 || ap > 0 || it > 0) lostwork++
      } else {
        want = qualifies ? "YES" : "no (empty, non-Delta)"
      }
      if (qualifies) recoverable++
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
      if (reshelled > 0) {
        print ""
        printf "!! %d of these have their NAME back in production under a different id.\n", reshelled
        print "   That is the PMO poll recreating a Delta-phase project it saw missing -- an EMPTY"
        print "   shell, with none of the servers, checklists or approvals below."
        if (lostwork > 0) {
          printf "   %d of them held real work, so that work is currently unreachable in the app.\n", lostwork
        }
        print "   The restore script SKIPS these rather than guessing: projects.name is UNIQUE, so"
        print "   somebody has to decide whether to delete the empty shell first and restore the"
        print "   original, which is usually what you want."
      }
    }
  }
'
echo
echo "Nothing was modified. This was a read-only inspection."
