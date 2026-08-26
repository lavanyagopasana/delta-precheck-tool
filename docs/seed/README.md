# Seed data

## The roster is seeded automatically

`backend/src/main/resources/seed/teams-roster.csv` is loaded on startup by `TeamRosterBootstrap`.
Nobody has to import anything: deploy, and the six teams and their 31 members are there.

That file used to live here and be imported by hand through Manage Access. It moved onto the
classpath because the manual step was work a person had to remember on every fresh database --
production included, right after a deploy -- and forgetting it is invisible: the app looks fine and
simply lists every engineer everywhere instead of scoping to a team.

### Editing the roster

Edit `backend/src/main/resources/seed/teams-roster.csv` and redeploy. Columns are `email,role,team`;
role accepts either the enum name or the label the UI shows ("Migration Manager").

### What it will and will not touch

Grouped by MANAGER rather than by team name, which is what makes it safe to run against a database
an admin has been editing:

- If any manager in a group already has a team, **the whole group is skipped** — somebody set that
  team up by hand and their arrangement wins, whatever they named it.
- An **ADMIN row is never modified.** Admins sit outside the team structure.
- Somebody who **already has a team is never moved.**
- It runs on every boot and converges — once the roster is in place it does nothing but a few reads.

Disable with `APP_SEED_TEAM_ROSTER=false` for a deployment that manages its own roster.

### `teams-roster.sql` (optional, no longer needed)

Still here for loading the same data straight into a database without deploying — useful if you want
the teams present before the new code ships. Requires database access. It refuses to demote an
existing ADMIN.

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
