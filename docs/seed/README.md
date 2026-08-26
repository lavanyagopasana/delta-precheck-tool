# Seed data

## `teams-roster.csv` — the 6 delivery teams (31 people)

Loads the real team structure so each Migration Manager's project dashboard offers only their own
engineers.

### Why a CSV and not a Java seeder

`AdminBootstrap`'s own docstring records what went wrong last time a specific person's address was
baked into a class: it "seeded the wrong admin on every database created anywhere else, and the only
way to correct it was a direct SQL edit." That trap scales badly at 31 rows. A CSV goes through the
already-tested `AppUserService.importCsv` path, is reviewable in a diff, and is correctable without a
deploy.

### How to load it

Teams must exist before the import runs — an unknown team name is reported as a per-row error rather
than silently leaving someone team-less. So:

1. Sign in as an ADMIN.
2. **Admin → Manage Access → Teams**: create `Team 1` … `Team 6`.
3. **Import CSV**, upload `teams-roster.csv`. No default role is needed; every row carries its own.
4. Check the result summary. `errors` is per-row, so a partial success is normal and tells you
   exactly which rows to fix.

Re-running is safe: rows are upserted by email, never duplicated.

### Emails corrected from the source list

Three addresses in the list this was transcribed from could not have worked, since email is the only
identity key in this app — a typo creates a user who can never sign in:

| As supplied | Loaded as | Problem |
|---|---|---|
| `Lakshmi.Prasanna@doudfuze.com` | `lakshmi.prasanna@cloudfuze.com` | `doudfuze`. This is a **manager**, so Team 4's whole filter hung off it |
| `Ganesh.Kondameedi@icloudfuze.com` | `ganesh.kondameedi@cloudfuze.com` | `icloudfuze` |
| `Davidraj. Dumpala@cloudfuze.com` | `davidraj.dumpala@cloudfuze.com` | space inside the local part |

**Confirm these three against the real directory.** They were inferred from the pattern of the other
28 addresses, not verified.

### Shape of the roster

6 teams, 8 managers, 23 engineers. Teams 5 and 6 have **two managers each** — both managers of a
team see that team's full engineer list. This is why teams are a real entity rather than a
`managerEmail` column on `app_users`: a single-manager column would have forced Team 5's five
engineers to be split between its two managers, leaving each manager able to assign only half.
